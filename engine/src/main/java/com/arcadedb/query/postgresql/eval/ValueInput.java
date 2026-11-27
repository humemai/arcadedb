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

import com.arcadedb.database.Binary;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values of {@code jsonb}, {@code bytea}, arrays and {@code vector}: the input functions of PostgreSQL for a text (a string
 * literal, a cast from text, a parameter of no declared type) and the conversion of a Java value (a parameter, a value read from
 * a record). The canonical classes are:
 * <ul>
 * <li>{@code jsonb}: a {@link Map} (a JSON object), a {@link List} (a JSON array) or a {@link Jsonb.Scalar}. A scalar at the
 * top, JSON {@code null} included, is refused with {@code 0A000} when it is to be written to a record ({@link #toJson}): a
 * record holds no such value, and a JSON {@code null} would read as SQL {@code NULL}.</li>
 * <li>{@code bytea}: a {@code byte[]}.</li>
 * <li>an array: a {@link List} of the canonical class of the element type, one dimension.</li>
 * <li>{@code vector}: a {@code float[]}.</li>
 * </ul>
 */
public final class ValueInput {
  /** The deepest nesting of a JSON text that is read. */
  private static final int MAX_JSON_DEPTH = 1000;

  private ValueInput() {
  }

  /** Whether the language writes arrays of this element type. */
  public static boolean isElementType(final PgType type) {
    return type != null
        && (type == PgType.BOOL || type.isNumber() || type.isString() || type == PgType.BYTEA || type.isDateTime());
  }

  /**
   * The element type of an array type name as the language writes it ({@code _int4}), {@code null} when the name is not one of an
   * array or the element type is not known.
   */
  public static PgType elementType(final String typeName) {
    if (typeName == null || !typeName.startsWith("_"))
      return null;
    return PgType.byName(List.of(typeName.substring(1)));
  }

  // ---- jsonb ----

  /**
   * A text or a Java value as a {@code jsonb} value that a record can hold, an object or an array, {@code null} for {@code null}.
   */
  public static Object toJson(final Object value) {
    if (value == null)
      return null;
    final Object json = value instanceof String text ? new JsonText(text).read()
        : value instanceof JsonbText sent ? new JsonText(sent.text()).read()
        : value instanceof Json.Value built ? new JsonText(built.text()).read() : jsonValue(value, 0);
    if (!(json instanceof Map<?, ?>) && !(json instanceof List<?>))
      throw PostgreSQLEvaluationException.unsupported("A JSON scalar is not supported as a jsonb value: give an object or an array");
    return json;
  }

  /** The input function of {@code jsonb} for an expression, where every JSON value is one ({@link Jsonb}). */
  static Object parseJson(final String text) {
    return Jsonb.wrap(new JsonText(text).read());
  }

  private static Object jsonValue(final Object value, final int depth) {
    if (depth > MAX_JSON_DEPTH)
      throw tooDeep();
    if (value instanceof Jsonb.Scalar scalar)
      return jsonValue(scalar.value(), depth);
    if (value == null || value == JSONObject.NULL || value instanceof Boolean || value instanceof String)
      return value == JSONObject.NULL ? null : value;
    if (value instanceof Double d && (d.isNaN() || d.isInfinite()) || value instanceof Float f && (f.isNaN() || f.isInfinite()))
      throw new PostgreSQLEvaluationException("22P02", "invalid input syntax for type json: NaN and Infinity are not JSON numbers");
    if (value instanceof Number)
      return value;
    if (value instanceof JSONObject object)
      return jsonValue(object.toMap(), depth);
    if (value instanceof JSONArray array)
      return jsonValue(array.toList(), depth);
    if (value instanceof Map<?, ?> map) {
      final Map<String, Object> out = new LinkedHashMap<>();
      for (final Map.Entry<?, ?> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key))
          throw PostgreSQLEvaluationException.mismatch("the keys of a JSON object are strings");
        out.put(key, jsonValue(entry.getValue(), depth + 1));
      }
      return out;
    }
    final List<?> list = asList(value);
    if (list == null || value instanceof byte[])
      throw PostgreSQLEvaluationException.mismatch("a value of class " + value.getClass().getName() + " is not a JSON value");
    final List<Object> out = new ArrayList<>(list.size());
    for (final Object item : list)
      out.add(jsonValue(item, depth + 1));
    return out;
  }

  private static PostgreSQLEvaluationException tooDeep() {
    return new PostgreSQLEvaluationException("54001", "stack depth limit exceeded: the JSON value is nested too deeply");
  }

  /** The JSON grammar of RFC 8259, which is what the input function of PostgreSQL accepts. A duplicate key keeps its last value. */
  private static final class JsonText {
    private final String text;
    private       int    at;

    private JsonText(final String text) {
      this.text = text;
    }

    Object read() {
      final Object value = value(0);
      skip();
      if (at < text.length())
        throw invalid();
      return value;
    }

    private Object value(final int depth) {
      if (depth > MAX_JSON_DEPTH)
        throw tooDeep();
      skip();
      if (at >= text.length())
        throw invalid();
      final char c = text.charAt(at);
      if (c == '{')
        return object(depth);
      if (c == '[')
        return array(depth);
      if (c == '"')
        return string();
      if (c == '-' || c >= '0' && c <= '9')
        return number();
      if (word("true"))
        return Boolean.TRUE;
      if (word("false"))
        return Boolean.FALSE;
      if (word("null"))
        return null;
      throw invalid();
    }

    private Map<String, Object> object(final int depth) {
      final Map<String, Object> out = new LinkedHashMap<>();
      ++at;
      skip();
      if (take('}'))
        return out;
      while (true) {
        skip();
        if (at >= text.length() || text.charAt(at) != '"')
          throw invalid();
        final String key = string();
        skip();
        if (!take(':'))
          throw invalid();
        out.put(key, value(depth + 1));
        skip();
        if (take('}'))
          return out;
        if (!take(','))
          throw invalid();
      }
    }

    private List<Object> array(final int depth) {
      final List<Object> out = new ArrayList<>();
      ++at;
      skip();
      if (take(']'))
        return out;
      while (true) {
        out.add(value(depth + 1));
        skip();
        if (take(']'))
          return out;
        if (!take(','))
          throw invalid();
      }
    }

    private String string() {
      final StringBuilder out = new StringBuilder();
      ++at;
      while (at < text.length()) {
        final char c = text.charAt(at++);
        if (c == '"') {
          checkSurrogates(out);
          return out.toString();
        }
        if (c < 0x20)
          throw invalid();
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (at >= text.length())
          throw invalid();
        final char escaped = text.charAt(at++);
        switch (escaped) {
        case '"', '\\', '/' -> out.append(escaped);
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          if (at + 4 > text.length())
            throw invalid();
          int code = 0;
          for (int i = 0; i < 4; i++) {
            final int digit = hexDigit(text.charAt(at++));
            if (digit < 0)
              throw invalid();
            code = code * 16 + digit;
          }
          if (code == 0)
            throw new PostgreSQLEvaluationException("22P05",
                "unsupported Unicode escape sequence: \\u0000 cannot be converted to text");
          out.append((char) code);
        }
        default -> throw invalid();
        }
      }
      throw invalid();
    }

    private void checkSurrogates(final CharSequence value) {
      for (int i = 0; i < value.length(); i++) {
        final char c = value.charAt(i);
        if (Character.isHighSurrogate(c)) {
          if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1)))
            throw invalid();
          ++i;
        } else if (Character.isLowSurrogate(c))
          throw invalid();
      }
    }

    private Object number() {
      final int start = at;
      take('-');
      if (!take('0') && digits() == 0)
        throw invalid();
      boolean integral = true;
      if (take('.')) {
        integral = false;
        if (digits() == 0)
          throw invalid();
      }
      if (take('e') || take('E')) {
        integral = false;
        if (!take('+'))
          take('-');
        if (digits() == 0)
          throw invalid();
      }
      // a letter or a digit right after the number makes it no token at all (01, 1x)
      if (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_' || text.charAt(at) == '.'))
        throw invalid();
      final String token = text.substring(start, at);
      final BigDecimal decimal;
      try {
        decimal = new BigDecimal(token);
      } catch (final NumberFormatException e) {
        throw new PostgreSQLEvaluationException("22003", "value \"" + token + "\" is out of range for type numeric");
      }
      if (Math.abs((long) decimal.scale()) > 16383)
        throw new PostgreSQLEvaluationException("22003", "value \"" + token + "\" is out of range for type numeric");
      if (integral) {
        final BigInteger integer = decimal.toBigIntegerExact();
        if (integer.bitLength() <= 31)
          return integer.intValue();
        if (integer.bitLength() <= 63)
          return integer.longValue();
        return decimal;
      }
      // a double when it holds the same number, so that every language reads a number; else the exact decimal
      final double approximation = decimal.doubleValue();
      if (!Double.isInfinite(approximation) && BigDecimal.valueOf(approximation).compareTo(decimal) == 0)
        return approximation;
      return decimal;
    }

    private int digits() {
      int count = 0;
      while (at < text.length() && text.charAt(at) >= '0' && text.charAt(at) <= '9') {
        ++at;
        ++count;
      }
      return count;
    }

    private boolean word(final String word) {
      if (!text.startsWith(word, at))
        return false;
      final int end = at + word.length();
      if (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_'))
        return false;
      at = end;
      return true;
    }

    private boolean take(final char c) {
      if (at < text.length() && text.charAt(at) == c) {
        ++at;
        return true;
      }
      return false;
    }

    private void skip() {
      while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0)
        ++at;
    }

    private PostgreSQLEvaluationException invalid() {
      return new PostgreSQLEvaluationException("22P02", "invalid input syntax for type json: \"" + text + "\"");
    }
  }

  // ---- bytea ----

  /** A text or a Java value as a {@code bytea} value, {@code null} for {@code null}. */
  public static byte[] toBytes(final Object value) {
    if (value == null)
      return null;
    if (value instanceof byte[] bytes)
      return bytes;
    if (value instanceof Binary binary)
      return binary.toByteArray();
    if (value instanceof String text)
      return parseBytea(text);
    throw PostgreSQLEvaluationException.mismatch("a value of class " + value.getClass().getName() + " is not a bytea value");
  }

  /**
   * The input function of {@code bytea}: the hex format {@code \x0aff} (white space allowed between the bytes), else the escape
   * format, where a backslash starts {@code \\} or three octal digits {@code \ooo} and any other character is its own bytes.
   */
  public static byte[] parseBytea(final String text) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (text.startsWith("\\x")) {
      int i = 2;
      while (i < text.length()) {
        if (" \n\t\r".indexOf(text.charAt(i)) >= 0) {
          ++i;
          continue;
        }
        final int high = requireHexDigit(text.charAt(i));
        if (i + 1 >= text.length())
          throw new PostgreSQLEvaluationException("22023", "invalid hexadecimal data: odd number of digits");
        out.write(high * 16 + requireHexDigit(text.charAt(i + 1)));
        i += 2;
      }
      return out.toByteArray();
    }
    int plain = 0;
    int i = 0;
    while (i < text.length()) {
      if (text.charAt(i) != '\\') {
        ++i;
        continue;
      }
      out.writeBytes(text.substring(plain, i).getBytes(StandardCharsets.UTF_8));
      if (i + 1 < text.length() && text.charAt(i + 1) == '\\') {
        out.write('\\');
        i += 2;
      } else if (i + 3 < text.length() && octal(text.charAt(i + 1), '3') && octal(text.charAt(i + 2), '7')
          && octal(text.charAt(i + 3), '7')) {
        out.write((text.charAt(i + 1) - '0') * 64 + (text.charAt(i + 2) - '0') * 8 + (text.charAt(i + 3) - '0'));
        i += 4;
      } else
        throw new PostgreSQLEvaluationException("22P02", "invalid input syntax for type bytea");
      plain = i;
    }
    out.writeBytes(text.substring(plain).getBytes(StandardCharsets.UTF_8));
    return out.toByteArray();
  }

  private static boolean octal(final char c, final char highest) {
    return c >= '0' && c <= highest;
  }

  private static int hexDigit(final char c) {
    if (c >= '0' && c <= '9')
      return c - '0';
    if (c >= 'a' && c <= 'f')
      return c - 'a' + 10;
    if (c >= 'A' && c <= 'F')
      return c - 'A' + 10;
    return -1;
  }

  private static int requireHexDigit(final char c) {
    final int digit = hexDigit(c);
    if (digit < 0)
      throw new PostgreSQLEvaluationException("22023", "invalid hexadecimal digit: \"" + c + "\"");
    return digit;
  }

  // ---- arrays ----

  /**
   * A text or a Java collection or array as an array of {@code element}, {@code null} for {@code null}. A string element of a Java
   * collection is read by the input function of the element type, as a string literal is; numbers convert among themselves.
   */
  public static List<Object> toArray(final Object value, final PgType element) {
    if (value == null)
      return null;
    if (!isElementType(element))
      throw PostgreSQLEvaluationException.unsupported("An array of this element type is not supported");
    if (value instanceof String text)
      return parseArray(text, element);
    final List<?> items = value instanceof byte[] ? null : asList(value);
    if (items == null)
      throw PostgreSQLEvaluationException.mismatch(
          "a value of class " + value.getClass().getName() + " is not an array of " + element.displayName());
    final List<Object> out = new ArrayList<>(items.size());
    for (final Object item : items)
      out.add(toElement(item, element));
    return out;
  }

  private static Object toElement(final Object item, final PgType element) {
    if (item == null || item == JSONObject.NULL)
      return null;
    if (item instanceof String text)
      return element.isString() ? text : Casts.parse(text, element);
    final PgType from = PgType.ofJavaValue(item);
    if (from == PgType.ARRAY || item instanceof Map<?, ?>) {
      if (from == PgType.ARRAY)
        throw multidimensional();
      throw PostgreSQLEvaluationException.mismatch("an element of the array is not of type " + element.displayName());
    }
    if (from == null || !(from == element || element.isString() || from.isNumber() && element.isNumber()
        || from.isDateTime() && element.isDateTime()))
      throw PostgreSQLEvaluationException.mismatch("an element of the array is not of type " + element.displayName());
    final Object canonical = Casts.fromEngine(item, from);
    if (from == element || from.isString() && element.isString())
      return canonical;
    return Casts.cast(canonical, from, element, null);
  }

  /**
   * The input function of an array, one dimension: {@code {a,b}}, an element in double quotes with backslash escapes, an unquoted
   * {@code NULL} for a null element. More dimensions and explicit bounds ({@code [0:1]={a,b}}) are {@code 0A000}.
   */
  public static List<Object> parseArray(final String text, final PgType element) {
    final int length = text.length();
    int at = skipSpace(text, 0);
    if (at < length && text.charAt(at) == '[')
      throw PostgreSQLEvaluationException.unsupported("An array literal with explicit bounds is not supported");
    if (at >= length || text.charAt(at) != '{')
      throw malformed(text);
    at = skipSpace(text, at + 1);
    final List<Object> out = new ArrayList<>();
    if (at < length && text.charAt(at) == '}')
      return end(text, at, out);
    while (true) {
      at = skipSpace(text, at);
      if (at >= length)
        throw malformed(text);
      final StringBuilder item = new StringBuilder();
      boolean literalNull = false;
      if (text.charAt(at) == '{')
        throw multidimensional();
      if (text.charAt(at) == '"') {
        ++at;
        while (at < length && text.charAt(at) != '"') {
          if (text.charAt(at) == '\\' && at + 1 < length)
            ++at;
          item.append(text.charAt(at++));
        }
        if (at >= length)
          throw malformed(text);
        at = skipSpace(text, at + 1);
      } else {
        boolean escaped = false;
        int keep = 0;
        while (at < length && text.charAt(at) != ',' && text.charAt(at) != '}') {
          char c = text.charAt(at);
          if (c == '"' || c == '{')
            throw malformed(text);
          if (c == '\\') {
            if (at + 1 >= length)
              throw malformed(text);
            c = text.charAt(++at);
            escaped = true;
            item.append(c);
            keep = item.length();
          } else {
            item.append(c);
            if (!isArraySpace(c))
              keep = item.length();
          }
          ++at;
        }
        item.setLength(keep);
        if (item.isEmpty())
          throw malformed(text);
        literalNull = !escaped && "null".equalsIgnoreCase(item.toString());
      }
      out.add(literalNull ? null : element.isString() ? item.toString() : Casts.parse(item.toString(), element));
      if (at >= length)
        throw malformed(text);
      if (text.charAt(at) == '}')
        return end(text, at, out);
      if (text.charAt(at) != ',')
        throw malformed(text);
      ++at;
    }
  }

  private static List<Object> end(final String text, final int brace, final List<Object> out) {
    if (skipSpace(text, brace + 1) != text.length())
      throw malformed(text);
    return out;
  }

  private static boolean isArraySpace(final char c) {
    return " \t\n\r\u000b\f".indexOf(c) >= 0;
  }

  private static int skipSpace(final String text, final int from) {
    int at = from;
    while (at < text.length() && isArraySpace(text.charAt(at)))
      ++at;
    return at;
  }

  private static PostgreSQLEvaluationException malformed(final String text) {
    return new PostgreSQLEvaluationException("22P02", "malformed array literal: \"" + text + "\"");
  }

  private static PostgreSQLEvaluationException multidimensional() {
    return PostgreSQLEvaluationException.unsupported("An array of more than one dimension is not supported");
  }

  // ---- vector ----

  /**
   * A text, or a Java array or collection of numbers, as a {@code vector}, {@code null} for {@code null}. As pgvector: at least
   * one element, no null element, no {@code NaN} and no infinity.
   */
  public static float[] toVector(final Object value) {
    if (value == null)
      return null;
    if (value instanceof String text)
      return VectorDistance.parse(text);
    final List<?> items = value instanceof byte[] ? null : asList(value);
    if (items == null)
      throw PostgreSQLEvaluationException.mismatch("a value of class " + value.getClass().getName() + " is not a vector");
    if (items.isEmpty())
      throw new PostgreSQLEvaluationException("22000", "vector must have at least 1 dimension");
    if (items.size() > VectorDistance.MAX_DIMENSIONS)
      throw new PostgreSQLEvaluationException("54000",
          "vector cannot have more than " + VectorDistance.MAX_DIMENSIONS + " dimensions");
    final float[] out = new float[items.size()];
    for (int i = 0; i < out.length; i++) {
      final Object item = items.get(i);
      if (item == null)
        throw new PostgreSQLEvaluationException("22004", "array must not contain nulls");
      if (!(item instanceof Number number))
        throw PostgreSQLEvaluationException.mismatch("a vector has numbers as elements");
      out[i] = number.floatValue();
      if (Float.isNaN(out[i]))
        throw new PostgreSQLEvaluationException("22003", "NaN not allowed in vector");
      if (Float.isInfinite(out[i]))
        throw new PostgreSQLEvaluationException("22003", "infinite value not allowed in vector");
    }
    return out;
  }

  /** The elements of a Java collection or array, {@code null} for anything else. */
  public static List<?> asList(final Object value) {
    if (value instanceof List<?> list)
      return list;
    if (value instanceof Collection<?> collection)
      return new ArrayList<>(collection);
    if (value instanceof JSONArray array)
      return array.toList();
    if (value instanceof Object[] array)
      return Arrays.asList(array);
    if (value != null && value.getClass().isArray()) {
      final int length = java.lang.reflect.Array.getLength(value);
      final List<Object> out = new ArrayList<>(length);
      for (int i = 0; i < length; i++)
        out.add(java.lang.reflect.Array.get(value, i));
      return out;
    }
    return null;
  }
}
