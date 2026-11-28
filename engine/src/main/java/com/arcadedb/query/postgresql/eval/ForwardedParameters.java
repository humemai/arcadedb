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

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The parameters of a statement of the {@code postgresql} language as a replica sends them to the leader (fork issue #341).
 * <p>
 * The language types a parameter by the Java class of its value, which plain JSON loses: an {@link UnknownText} or a
 * {@link JsonbText} arrived as the text of its {@code toString()}, a {@code NaN} as a null, a {@code numeric} as a double, and
 * a date as a number. Here every value travels as the name of its kind and its text, so that the leader binds the value that the
 * replica was given. A value of any other class is refused with {@code 0A000} before the statement is sent.
 * <p>
 * A value is a JSON array whose first element names its kind. The whole is one JSON document that goes into the request as a
 * string, so that the reader of the request does not convert anything in it.
 */
public final class ForwardedParameters {
  /** The field of the body of a command request that carries the parameters. */
  public static final String REQUEST_FIELD = "postgresqlParams";

  private static final int MAX_DEPTH = 32;

  /** The element classes that an array keeps: its class says the type of a bound array that no element says. */
  private static final Map<String, Class<?>> ARRAY_ELEMENTS = Stream.<Class<?>>of(Object.class, String.class, Boolean.class,
          Short.class, Integer.class, Long.class, BigDecimal.class, Float.class, Double.class, byte[].class, LocalDate.class,
          LocalDateTime.class, OffsetDateTime.class)
      .collect(Collectors.toMap(Class::getSimpleName, Function.identity()));

  private ForwardedParameters() {
  }

  /**
   * The parameters as the text of the field {@link #REQUEST_FIELD}.
   *
   * @param named      the parameters by name, used when it is not empty
   * @param positional the values of {@code $1} and on otherwise, keyed by their position from 0
   *
   * @throws PostgreSQLEvaluationException {@code 0A000} for a value that cannot be sent as it is
   */
  public static String encode(final Map<String, Object> named, final Object[] positional) {
    final JSONObject encoded = new JSONObject();
    if (named != null && !named.isEmpty())
      for (final Map.Entry<String, Object> parameter : named.entrySet())
        encoded.put(parameter.getKey(), encodeValue(parameter.getValue(), parameter.getKey(), 0));
    else if (positional != null)
      for (int i = 0; i < positional.length; i++)
        encoded.put(Integer.toString(i), encodeValue(positional[i], Integer.toString(i + 1), 0));
    return encoded.toString();
  }

  /** The parameters that {@link #encode} wrote, by name or by position, each of the class it had. */
  public static Map<String, Object> decode(final String text) {
    final JSONObject encoded = new JSONObject(text);
    final Map<String, Object> parameters = new HashMap<>();
    for (final String name : encoded.keySet())
      parameters.put(name, decodeValue(encoded.getJSONArray(name), 0));
    return parameters;
  }

  private static JSONArray encodeValue(final Object value, final String parameter, final int depth) {
    if (depth > MAX_DEPTH)
      throw PostgreSQLEvaluationException.unsupported(
          "The value of parameter $" + parameter + " nests more than " + MAX_DEPTH + " levels: send it as text");
    if (value == null || value == JSONObject.NULL)
      return tagged("null");
    return switch (value) {
      case UnknownText unknown -> tagged("unknown", unknown.text());
      case JsonbText jsonb -> tagged("jsonbText", jsonb.text());
      case String string -> tagged("string", string);
      case Character character -> tagged("character", character.toString());
      case Boolean bool -> tagged("boolean", bool.toString());
      case Byte number -> tagged("byte", number.toString());
      case Short number -> tagged("short", number.toString());
      case Integer number -> tagged("integer", number.toString());
      case Long number -> tagged("long", number.toString());
      case BigInteger number -> tagged("bigInteger", number.toString());
      case BigDecimal number -> tagged("bigDecimal", number.toString());
      // the text of a float or a double reads back as the same bits, NaN and the infinities too
      case Float number -> tagged("float", number.toString());
      case Double number -> tagged("double", number.toString());
      case UUID uuid -> tagged("uuid", uuid.toString());
      case LocalDate date -> tagged("localDate", date.toString());
      case LocalDateTime dateTime -> tagged("localDateTime", dateTime.toString());
      case OffsetDateTime dateTime -> tagged("offsetDateTime", dateTime.toString());
      case ZonedDateTime dateTime -> tagged("zonedDateTime", dateTime.toString());
      case Instant instant -> tagged("instant", instant.toString());
      case java.sql.Date date -> tagged("sqlDate", Long.toString(date.getTime()));
      case Date date when date.getClass() == Date.class -> tagged("date", Long.toString(date.getTime()));
      case byte[] bytes -> tagged("bytes", Base64.getEncoder().encodeToString(bytes));
      case float[] numbers -> tagged("floats", texts(numbers));
      case double[] numbers -> tagged("doubles", texts(numbers));
      case int[] numbers -> tagged("integers", texts(numbers));
      case long[] numbers -> tagged("longs", texts(numbers));
      case Object[] array when ARRAY_ELEMENTS.get(array.getClass().getComponentType().getSimpleName())
          == array.getClass().getComponentType() -> {
        final JSONArray items = new JSONArray();
        for (final Object item : array)
          items.put((Object) encodeValue(item, parameter, depth + 1));
        yield tagged("array", array.getClass().getComponentType().getSimpleName()).put((Object) items);
      }
      case Collection<?> collection -> {
        final JSONArray items = new JSONArray();
        for (final Object item : collection)
          items.put((Object) encodeValue(item, parameter, depth + 1));
        yield tagged("list").put((Object) items);
      }
      case Map<?, ?> map -> {
        final JSONObject entries = new JSONObject();
        for (final Map.Entry<?, ?> entry : map.entrySet()) {
          if (!(entry.getKey() instanceof String key))
            throw cannotSend(value, parameter);
          entries.put(key, encodeValue(entry.getValue(), parameter, depth + 1));
        }
        yield tagged("map").put((Object) entries);
      }
      case JSONObject object -> tagged("jsonObject", object.toString());
      case JSONArray array -> tagged("jsonArray", array.toString());
      default -> throw cannotSend(value, parameter);
    };
  }

  private static Object decodeValue(final JSONArray value, final int depth) {
    if (depth > MAX_DEPTH)
      throw new IllegalArgumentException("A forwarded parameter nests more than " + MAX_DEPTH + " levels");
    final String kind = value.getString(0);
    return switch (kind) {
      case "null" -> null;
      case "unknown" -> new UnknownText(value.getString(1));
      case "jsonbText" -> new JsonbText(value.getString(1));
      case "string" -> value.getString(1);
      case "character" -> value.getString(1).charAt(0);
      case "boolean" -> Boolean.valueOf(value.getString(1));
      case "byte" -> Byte.valueOf(value.getString(1));
      case "short" -> Short.valueOf(value.getString(1));
      case "integer" -> Integer.valueOf(value.getString(1));
      case "long" -> Long.valueOf(value.getString(1));
      case "bigInteger" -> new BigInteger(bigNumber(value.getString(1)));
      case "bigDecimal" -> new BigDecimal(bigNumber(value.getString(1)));
      case "float" -> Float.valueOf(value.getString(1));
      case "double" -> Double.valueOf(value.getString(1));
      case "uuid" -> UUID.fromString(value.getString(1));
      case "localDate" -> LocalDate.parse(value.getString(1));
      case "localDateTime" -> LocalDateTime.parse(value.getString(1));
      case "offsetDateTime" -> OffsetDateTime.parse(value.getString(1));
      case "zonedDateTime" -> ZonedDateTime.parse(value.getString(1));
      case "instant" -> Instant.parse(value.getString(1));
      case "sqlDate" -> new java.sql.Date(Long.parseLong(value.getString(1)));
      case "date" -> new Date(Long.parseLong(value.getString(1)));
      case "bytes" -> Base64.getDecoder().decode(value.getString(1));
      case "floats" -> {
        final JSONArray texts = value.getJSONArray(1);
        final float[] numbers = new float[texts.length()];
        for (int i = 0; i < numbers.length; i++)
          numbers[i] = Float.parseFloat(texts.getString(i));
        yield numbers;
      }
      case "doubles" -> {
        final JSONArray texts = value.getJSONArray(1);
        final double[] numbers = new double[texts.length()];
        for (int i = 0; i < numbers.length; i++)
          numbers[i] = Double.parseDouble(texts.getString(i));
        yield numbers;
      }
      case "integers" -> {
        final JSONArray texts = value.getJSONArray(1);
        final int[] numbers = new int[texts.length()];
        for (int i = 0; i < numbers.length; i++)
          numbers[i] = Integer.parseInt(texts.getString(i));
        yield numbers;
      }
      case "longs" -> {
        final JSONArray texts = value.getJSONArray(1);
        final long[] numbers = new long[texts.length()];
        for (int i = 0; i < numbers.length; i++)
          numbers[i] = Long.parseLong(texts.getString(i));
        yield numbers;
      }
      case "array" -> {
        final Class<?> element = ARRAY_ELEMENTS.get(value.getString(1));
        if (element == null)
          throw new IllegalArgumentException("Unknown element class of a forwarded array: " + value.getString(1));
        final JSONArray items = value.getJSONArray(2);
        final Object array = Array.newInstance(element, items.length());
        for (int i = 0; i < items.length(); i++)
          Array.set(array, i, decodeValue(items.getJSONArray(i), depth + 1));
        yield array;
      }
      case "list" -> {
        final JSONArray items = value.getJSONArray(1);
        final List<Object> list = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++)
          list.add(decodeValue(items.getJSONArray(i), depth + 1));
        yield list;
      }
      case "map" -> {
        final JSONObject entries = value.getJSONObject(1);
        final Map<String, Object> map = new LinkedHashMap<>();
        for (final String key : entries.keySet())
          map.put(key, decodeValue(entries.getJSONArray(key), depth + 1));
        yield map;
      }
      case "jsonObject" -> new JSONObject(value.getString(1));
      case "jsonArray" -> new JSONArray(value.getString(1));
      default -> throw new IllegalArgumentException("Unknown kind of forwarded parameter: " + kind);
    };
  }

  /** The error of the language for a value it has no type for, as where the statement runs, or one that names the class. */
  private static PostgreSQLEvaluationException cannotSend(final Object value, final String parameter) {
    return PostgreSQLEvaluationException.unsupported(PgType.ofJavaValue(value) == null
        ? "The postgresql language has no type for the value of parameter $" + parameter + ExpressionCompiler.missingType(value)
        + ": send it as text"
        : "A replica cannot send the value of parameter $" + parameter + ", a " + value.getClass().getName()
            + ", to the leader: send it as text, or connect to the leader");
  }

  /** The limit of a JSON number token holds for the same number in text: a few characters can name billions of digits. */
  private static String bigNumber(final String text) {
    if (!JSONObject.isSafeBigNumber(text))
      throw new IllegalArgumentException("A forwarded number is too large: " + text.length() + " characters");
    return text;
  }

  private static JSONArray tagged(final String kind) {
    return new JSONArray().put(kind);
  }

  private static JSONArray tagged(final String kind, final String text) {
    return tagged(kind).put(text);
  }

  private static JSONArray tagged(final String kind, final JSONArray texts) {
    return tagged(kind).put((Object) texts);
  }

  private static JSONArray texts(final Object numbers) {
    final JSONArray texts = new JSONArray();
    for (int i = 0, length = Array.getLength(numbers); i < length; i++)
      texts.put(String.valueOf(Array.get(numbers, i)));
    return texts;
  }
}
