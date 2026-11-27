/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.arcadedb.query.postgresql.eval;

import com.arcadedb.database.Document;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code jsonb} values as the operators and functions of the language read them (fork issues #232 and #189).
 * <p>
 * A value of the type {@code jsonb} is a {@link Map} (a JSON object), a {@link List} (a JSON array) or a {@link Scalar} (a
 * string, a number, a boolean or the JSON {@code null}). SQL {@code NULL} is the Java {@code null}, so the JSON {@code null},
 * which is a value, is never the same as it. A scalar has a class of its own, so that a JSON string is never read as the text of
 * a JSON document, as a {@link String} that a client sends is. The members of an object and the elements of an array are held as
 * the record holds them; {@link #view} reads them, also in the classes that another language stores.
 */
public final class Jsonb {
  /** The deepest nesting that is written or compared. */
  private static final int MAX_DEPTH = 1000;

  private Jsonb() {
  }

  /**
   * A {@code jsonb} value that is no object and no array.
   *
   * @param value a {@link String}, a {@link Number}, a {@link Boolean} or {@link JSONObject#NULL}
   */
  public record Scalar(Object value) {
    /** The JSON {@code null}. */
    static final Scalar NULL = new Scalar(JSONObject.NULL);

    /** The text of the value as PostgreSQL writes it, which is what a client gets: a string is in double quotes. */
    @Override
    public String toString() {
      return write(this);
    }
  }

  /** One level of a value: a {@link Map}, a {@link List}, a {@link String}, a {@link Boolean}, a {@link BigDecimal} or the JSON null. */
  static Object view(final Object value) {
    if (value instanceof Scalar scalar)
      return view(scalar.value());
    if (value == null || value == JSONObject.NULL)
      return JSONObject.NULL;
    if (value instanceof Map<?, ?> || value instanceof String || value instanceof Boolean || value instanceof BigDecimal)
      return value;
    if (value instanceof Number number)
      return number(number);
    if (value instanceof Document document)
      return document.toMap(false);
    if (value instanceof JSONObject object)
      return object.toMap();
    if (!(value instanceof byte[])) {
      final List<?> list = ValueInput.asList(value);
      if (list != null)
        return list;
    }
    // a value of a class that only another language stores: the JSON that the engine writes for it
    final Object converted = new JSONObject().put("v", value).toMap().get("v");
    if (converted instanceof Number number)
      return number(number);
    if (converted instanceof String || converted instanceof Boolean || converted instanceof Map<?, ?> || converted instanceof List<?>)
      return converted;
    return converted == null || converted == JSONObject.NULL ? JSONObject.NULL : value.toString();
  }

  /** A number by its value; {@code NaN} and {@code Infinity}, which are no JSON numbers, as their text. */
  private static Object number(final Number number) {
    final String text = number.toString();
    final double approximation = number.doubleValue();
    if ((number instanceof Double || number instanceof Float) && (Double.isNaN(approximation) || Double.isInfinite(approximation)))
      return text;
    final BigDecimal decimal = new BigDecimal(text);
    // a double in exponent notation (1.0E-7) has a digit that nothing wrote
    return text.indexOf('E') >= 0 && (number instanceof Double || number instanceof Float) ? decimal.stripTrailingZeros() : decimal;
  }

  /** A member or an element as a value of the type {@code jsonb}. */
  static Object wrap(final Object value) {
    final Object json = view(value);
    if (json instanceof Map<?, ?> || json instanceof List<?>)
      return json;
    return json == JSONObject.NULL ? Scalar.NULL : new Scalar(json);
  }

  /** {@code -> text}: the member of an object with the name, SQL {@code NULL} when there is none. */
  static Object field(final Object document, final String name) {
    return view(document) instanceof Map<?, ?> map && map.containsKey(name) ? wrap(map.get(name)) : null;
  }

  /**
   * {@code -> integer}: the element of an array at the index, which counts from the end when it is negative, SQL {@code NULL}
   * when there is none. To this operator of PostgreSQL a scalar is an array of one element.
   */
  static Object element(final Object document, final long index) {
    final Object json = view(document);
    if (json instanceof List<?> list) {
      final long at = index < 0 ? list.size() + index : index;
      return at >= 0 && at < list.size() ? wrap(list.get((int) at)) : null;
    }
    return !(json instanceof Map<?, ?>) && (index == 0 || index == -1) ? wrap(json) : null;
  }

  /**
   * {@code #>}: the value at the path, where a step names a member of an object or the index of an element of an array. SQL
   * {@code NULL} when the path leads nowhere or has a {@code NULL} step. The empty path leads to the value itself.
   */
  static Object path(final Object document, final List<?> steps) {
    Object current = wrap(document);
    for (final Object step : steps) {
      if (step == null)
        return null;
      final Object json = view(current);
      if (json instanceof Map<?, ?>)
        current = field(current, (String) step);
      else if (json instanceof List<?>) {
        final Integer index = index((String) step);
        current = index == null ? null : element(current, index);
      } else
        return null;
      if (current == null)
        return null;
    }
    return current;
  }

  /** The array index that a step of a path says, as {@code strtoint} reads it, {@code null} for a text that is no integer. */
  static Integer index(final String step) {
    int start = 0;
    while (start < step.length() && " \t\n\r\f".indexOf(step.charAt(start)) >= 0)
      ++start;
    try {
      return Integer.valueOf(step.substring(start));
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  /** What {@code ->>} gives: a string as it is, SQL {@code NULL} for the JSON {@code null}, else the JSON text. */
  static String asText(final Object value) {
    if (value == null)
      return null;
    final Object json = view(value);
    if (json == JSONObject.NULL)
      return null;
    return json instanceof String text ? text : write(json);
  }

  /**
   * The text of a value as PostgreSQL writes {@code jsonb}: a space after each {@code :} and {@code ,}, and the keys of an
   * object in the order PostgreSQL keeps them, the shorter first and then by their bytes.
   */
  public static String write(final Object value) {
    final StringBuilder out = new StringBuilder();
    write(value, out, 0);
    return out.toString();
  }

  private static void write(final Object value, final StringBuilder out, final int depth) {
    checkDepth(depth);
    final Object json = view(value);
    if (json == JSONObject.NULL)
      out.append("null");
    else if (json instanceof String text)
      quote(text, out);
    else if (json instanceof BigDecimal decimal)
      out.append(decimal.toPlainString());
    else if (json instanceof Map<?, ?> map) {
      final List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
      entries.sort((a, b) -> compareKeys(String.valueOf(a.getKey()), String.valueOf(b.getKey())));
      out.append('{');
      String between = "";
      for (final Map.Entry<?, ?> entry : entries) {
        out.append(between);
        quote(String.valueOf(entry.getKey()), out);
        out.append(": ");
        write(entry.getValue(), out, depth + 1);
        between = ", ";
      }
      out.append('}');
    } else if (json instanceof List<?> list) {
      out.append('[');
      String between = "";
      for (final Object item : list) {
        out.append(between);
        write(item, out, depth + 1);
        between = ", ";
      }
      out.append(']');
    } else
      out.append(json);
  }

  private static int compareKeys(final String left, final String right) {
    final byte[] a = left.getBytes(StandardCharsets.UTF_8);
    final byte[] b = right.getBytes(StandardCharsets.UTF_8);
    return a.length != b.length ? Integer.compare(a.length, b.length) : Arrays.compareUnsigned(a, b);
  }

  private static void quote(final String text, final StringBuilder out) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      switch (c) {
      case '"' -> out.append("\\\"");
      case '\\' -> out.append("\\\\");
      case '\b' -> out.append("\\b");
      case '\f' -> out.append("\\f");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      case '\t' -> out.append("\\t");
      default -> {
        if (c < 0x20)
          out.append(String.format(Locale.ROOT, "\\%c%04x", 'u', (int) c));
        else
          out.append(c);
      }
      }
    }
    out.append('"');
  }

  /**
   * A form of a value in which two values that PostgreSQL treats as equal are equal Java objects: numbers by value, and the
   * order of the keys of an object of no account. It is what {@code =}, {@code DISTINCT} and {@code GROUP BY} compare (fork issue
   * #189). A value that is no number, object or array is itself, so the elements of an SQL array compare as before, its numbers
   * by value.
   */
  public static Object key(final Object value) {
    return key(value, 0);
  }

  private static Object key(final Object value, final int depth) {
    checkDepth(depth);
    final Object json = value instanceof Scalar scalar ? scalar.value() : value;
    if (json == null)
      return JSONObject.NULL;
    if (json instanceof Number number) {
      final Object read = number(number);
      if (!(read instanceof BigDecimal decimal))
        return number.doubleValue();
      return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    }
    if (json instanceof Map<?, ?> map) {
      final Map<Object, Object> out = new HashMap<>();
      for (final Map.Entry<?, ?> entry : map.entrySet())
        out.put(entry.getKey(), key(entry.getValue(), depth + 1));
      return out;
    }
    if (json instanceof List<?> list) {
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list)
        out.add(key(item, depth + 1));
      return out;
    }
    if (json instanceof Document || json instanceof JSONObject || json instanceof JSONArray
        || json.getClass().isArray() && !(json instanceof byte[]))
      return key(view(json), depth);
    return json;
  }

  /** {@code =} of two values. */
  static boolean equal(final Object left, final Object right) {
    return key(view(left)).equals(key(view(right)));
  }

  /**
   * {@code holder @> wanted} as PostgreSQL defines it: an object holds the names of {@code wanted} with values that hold theirs, an
   * array holds each element of {@code wanted} in some element of its own, and two scalars hold each other when they are equal.
   * Only at the top does an array also hold a scalar.
   */
  static boolean contains(final Object holder, final Object wanted) {
    final Object a = view(holder);
    final Object b = view(wanted);
    if (a instanceof List<?> list && !(b instanceof List<?>) && !(b instanceof Map<?, ?>)) {
      for (final Object item : list)
        if (equal(item, b))
          return true;
      return false;
    }
    return holds(a, b, 0);
  }

  private static boolean holds(final Object a, final Object b, final int depth) {
    checkDepth(depth);
    if (b instanceof Map<?, ?> wanted) {
      if (!(a instanceof Map<?, ?> map))
        return false;
      for (final Map.Entry<?, ?> entry : wanted.entrySet())
        if (!map.containsKey(entry.getKey()) || !holds(view(map.get(entry.getKey())), view(entry.getValue()), depth + 1))
          return false;
      return true;
    }
    if (b instanceof List<?> wanted) {
      if (!(a instanceof List<?> list))
        return false;
      for (final Object item : wanted) {
        final Object element = view(item);
        boolean held = false;
        for (int i = 0; !held && i < list.size(); i++)
          held = holds(view(list.get(i)), element, depth + 1);
        if (!held)
          return false;
      }
      return true;
    }
    return equal(a, b);
  }

  /** What {@code jsonb_typeof} says. */
  static String typeOf(final Object value) {
    final Object json = view(value);
    if (json == JSONObject.NULL)
      return "null";
    if (json instanceof Map<?, ?>)
      return "object";
    if (json instanceof List<?>)
      return "array";
    if (json instanceof String)
      return "string";
    return json instanceof Boolean ? "boolean" : "number";
  }

  /** {@code jsonb_array_length}. */
  static int arrayLength(final Object value) {
    final Object json = view(value);
    if (json instanceof List<?> list)
      return list.size();
    throw new PostgreSQLEvaluationException("22023",
        json instanceof Map<?, ?> ? "cannot get array length of a non-array" : "cannot get array length of a scalar");
  }

  /** {@code jsonb_exists}: a key of an object, a string element of an array, or the string that the value is. */
  static boolean exists(final Object value, final String name) {
    final Object json = view(value);
    if (json instanceof Map<?, ?> map)
      return map.containsKey(name);
    if (json instanceof List<?> list) {
      for (final Object item : list)
        if (name.equals(view(item)))
          return true;
      return false;
    }
    return name.equals(json);
  }

  /**
   * A cast of {@code jsonb} to {@code boolean} or to a number type, as PostgreSQL 18 has it: the JSON {@code null} is SQL
   * {@code NULL}, and a value of another JSON type is {@code 22023}.
   */
  static Object cast(final Object value, final PgType to, final int[] typmod) {
    final Object json = view(value);
    if (json == JSONObject.NULL)
      return null;
    if (to == PgType.BOOL && json instanceof Boolean)
      return json;
    if (to.isNumber() && json instanceof BigDecimal decimal)
      return Casts.cast(decimal, PgType.NUMERIC, to, typmod);
    final String found = json instanceof BigDecimal ? "numeric" : typeOf(json);
    throw new PostgreSQLEvaluationException("22023", "cannot cast jsonb " + found + " to type " + to.displayName());
  }

  // ---- values that are built and changed ----

  /** A value as a member of an object or an element of an array holds it: the JSON {@code null} is the Java {@code null}. */
  private static Object member(final Object json) {
    return json == JSONObject.NULL ? null : json;
  }

  /** {@code ||}: two objects are merged, the right one wins; else both are read as arrays, one after the other. */
  static Object concat(final Object left, final Object right) {
    final Object a = view(left);
    final Object b = view(right);
    if (a instanceof Map<?, ?> first && b instanceof Map<?, ?> second) {
      final Map<Object, Object> out = new LinkedHashMap<>(first);
      out.putAll(second);
      return out;
    }
    final List<Object> out = new ArrayList<>();
    for (final Object json : new Object[] { a, b })
      if (json instanceof List<?> list)
        out.addAll(list);
      else
        out.add(member(json));
    return out;
  }

  /** {@code -} with names: the members of an object with these names, or the string elements of an array equal to them. */
  static Object delete(final Object target, final List<?> names) {
    final Object json = view(target);
    if (json instanceof Map<?, ?> map) {
      final Map<Object, Object> out = new LinkedHashMap<>(map);
      for (final Object name : names)
        if (name != null)
          out.remove(name);
      return out;
    }
    if (json instanceof List<?> list) {
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list) {
        final Object element = view(item);
        if (!(element instanceof String && names.contains(element)))
          out.add(item);
      }
      return out;
    }
    throw invalidParameter("cannot delete from scalar");
  }

  /** {@code -} with an integer: the element of an array at the index, which counts from the end when it is negative. */
  static Object deleteElement(final Object target, final long index) {
    final Object json = view(target);
    if (json instanceof List<?> list) {
      final List<Object> out = new ArrayList<>(list);
      final long at = index < 0 ? out.size() + index : index;
      if (at >= 0 && at < out.size())
        out.remove((int) at);
      return out;
    }
    throw invalidParameter(
        json instanceof Map<?, ?> ? "cannot delete from object using integer index" : "cannot delete from scalar");
  }

  /** {@code #-}: the value without what the path leads to. */
  static Object deletePath(final Object target, final List<?> steps) {
    final Object json = view(target);
    if (!(json instanceof Map<?, ?>) && !(json instanceof List<?>))
      throw invalidParameter("cannot delete path in scalar");
    return steps.isEmpty() ? json : change(json, steps, 0, true, null, false);
  }

  /**
   * {@code jsonb_set}: the value with {@code replacement} where the path leads. With {@code create} the last step adds a member
   * that an object does not have, and an element before or after the elements of an array.
   */
  static Object set(final Object target, final List<?> steps, final Object replacement, final boolean create) {
    final Object json = view(target);
    if (!(json instanceof Map<?, ?>) && !(json instanceof List<?>))
      throw invalidParameter("cannot set path in scalar");
    return steps.isEmpty() ? json : change(json, steps, 0, false, member(view(replacement)), create);
  }

  /** A copy of the value with one change at the path; what the path does not lead to stays as it is. */
  private static Object change(final Object json, final List<?> steps, final int level, final boolean delete,
      final Object replacement, final boolean create) {
    checkDepth(level);
    if (!(json instanceof Map<?, ?>) && !(json instanceof List<?>))
      return member(json);
    final Object step = steps.get(level);
    if (step == null)
      throw new PostgreSQLEvaluationException("22004", "path element at position " + (level + 1) + " is null");
    final boolean last = level == steps.size() - 1;
    if (json instanceof Map<?, ?> map) {
      final Map<Object, Object> out = new LinkedHashMap<>(map);
      if (!out.containsKey(step)) {
        if (create && last)
          out.put(step, replacement);
      } else if (!last)
        out.put(step, change(view(out.get(step)), steps, level + 1, delete, replacement, create));
      else if (delete)
        out.remove(step);
      else
        out.put(step, replacement);
      return out;
    }
    final Integer index = index((String) step);
    if (index == null)
      throw new PostgreSQLEvaluationException("22P02",
          "path element at position " + (level + 1) + " is not an integer: \"" + step + "\"");
    final List<Object> out = new ArrayList<>((List<?>) json);
    final long at = index < 0 ? out.size() + (long) index : index;
    if (at < 0 || at >= out.size()) {
      if (create && last)
        out.add(at < 0 ? 0 : out.size(), replacement);
    } else if (!last)
      out.set((int) at, change(view(out.get((int) at)), steps, level + 1, delete, replacement, create));
    else if (delete)
      out.remove((int) at);
    else
      out.set((int) at, replacement);
    return out;
  }

  /** {@code jsonb_strip_nulls}: no member of an object, and with {@code arrays} no element of an array, is the JSON null. */
  static Object stripNulls(final Object value, final boolean arrays) {
    return wrap(strip(view(value), arrays, 0));
  }

  private static Object strip(final Object json, final boolean arrays, final int depth) {
    checkDepth(depth);
    if (json instanceof Map<?, ?> map) {
      final Map<Object, Object> out = new LinkedHashMap<>();
      for (final Map.Entry<?, ?> entry : map.entrySet()) {
        final Object found = view(entry.getValue());
        if (found != JSONObject.NULL)
          out.put(entry.getKey(), strip(found, arrays, depth + 1));
      }
      return out;
    }
    if (json instanceof List<?> list) {
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list) {
        final Object found = view(item);
        if (!arrays || found != JSONObject.NULL)
          out.add(strip(found, arrays, depth + 1));
      }
      return out;
    }
    return member(json);
  }

  /**
   * What {@code to_jsonb} makes of a value of {@code type}, as a member of an object or an element of an array holds it. The
   * value is not SQL {@code NULL}. A date or a time is its text in ISO 8601 form, as PostgreSQL writes it into JSON.
   *
   * @param typeName the name of the type, which for an array says the element type
   */
  public static Object of(final Object value, final PgType type, final String typeName) {
    switch (type) {
    case JSONB:
      return member(view(value));
    case BOOL:
    case INT2:
    case INT4:
    case INT8:
    case TEXT:
    case VARCHAR:
      return value;
    case NUMERIC: {
      // a double when it is written the same way, so that every language reads a number, as for a literal
      final BigDecimal decimal = (BigDecimal) value;
      final double approximation = decimal.doubleValue();
      return !Double.isInfinite(approximation) && BigDecimal.valueOf(approximation).equals(decimal) ? (Object) approximation
          : decimal;
    }
    case FLOAT4:
    case FLOAT8: {
      final String text = Casts.toText(value, type);
      // NaN and Infinity are no JSON numbers: PostgreSQL writes them as strings
      return text.indexOf('N') >= 0 || text.indexOf('I') >= 0 ? text : new BigDecimal(text);
    }
    case DATE:
      return Casts.toText(value, type);
    case TIMESTAMP:
      return Casts.toText(value, type).replace(' ', 'T');
    case TIMESTAMPTZ:
      return Casts.toText(value, type).replace(' ', 'T') + ":00";
    case BYTEA:
      return "\\x" + HexFormat.of().formatHex(ValueInput.toBytes(value));
    case ARRAY: {
      final PgType declared = ValueInput.elementType(typeName);
      final List<Object> out = new ArrayList<>();
      for (final Object item : ValueInput.asList(value)) {
        final PgType element = declared != null ? declared : PgType.ofJavaValue(item);
        if (element == null)
          throw PostgreSQLEvaluationException.unsupported(
              "A jsonb value of a value of class " + item.getClass().getName() + " is not supported");
        out.add(item == null || item == JSONObject.NULL ? null
            : of(Casts.fromEngine(item, element), element, element.typeName()));
      }
      return out;
    }
    default:
      throw PostgreSQLEvaluationException.unsupported(
          "A jsonb value of a value of type " + type.displayName() + " is not supported");
    }
  }

  private static PostgreSQLEvaluationException invalidParameter(final String message) {
    return new PostgreSQLEvaluationException("22023", message);
  }

  private static void checkDepth(final int depth) {
    if (depth > MAX_DEPTH)
      throw new PostgreSQLEvaluationException("54001", "stack depth limit exceeded: the JSON value is nested too deeply");
  }
}
