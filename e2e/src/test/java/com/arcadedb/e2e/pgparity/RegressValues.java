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
package com.arcadedb.e2e.pgparity;

import com.arcadedb.query.postgresql.corpus.Json;
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.schema.Type;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The values of the regression harness (fork issue #119): the text a server writes, turned into the normal form that
 * {@code Values} of the parity corpus compares (numbers by value, a date as its ISO text), and into the Java value that the
 * engine API stores when a table is copied.
 */
final class RegressValues {
  /** A value or a column type that the harness cannot hold in ArcadeDB. */
  static final class Unsupported extends RuntimeException {
    Unsupported(final String message) {
      super(message);
    }
  }

  /** The property type, and for a list the type of its elements, that hold a column of a PostgreSQL type. */
  record Holder(Type type, String ofType) {
  }

  private static final Map<String, Type> SCALARS = Map.ofEntries(Map.entry("bool", Type.BOOLEAN), Map.entry("int2", Type.SHORT),
      Map.entry("int4", Type.INTEGER), Map.entry("int8", Type.LONG), Map.entry("float4", Type.FLOAT),
      Map.entry("float8", Type.DOUBLE), Map.entry("numeric", Type.DECIMAL), Map.entry("text", Type.STRING),
      Map.entry("varchar", Type.STRING), Map.entry("date", Type.DATE), Map.entry("timestamp", Type.DATETIME_MICROS),
      Map.entry("bytea", Type.BINARY));

  private static final DateTimeFormatter ISO_TIMESTAMP = new DateTimeFormatterBuilder().appendPattern("uuuu-MM-dd HH:mm:ss")
      .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter(Locale.ROOT);

  private RegressValues() {
  }

  /** @return the holder of a column of the type {@code pgType} ({@code int4}, {@code _text}), {@code null} when there is none */
  static Holder holder(final String pgType) {
    final Type scalar = SCALARS.get(pgType);
    if (scalar != null)
      return new Holder(scalar, null);
    if (pgType.equals("jsonb"))
      return new Holder(Type.MAP, null);
    if (pgType.startsWith("_")) {
      final Type element = SCALARS.get(pgType.substring(1));
      if (element != null)
        return new Holder(Type.LIST, element.name());
    }
    return null;
  }

  /**
   * The normal form of a value of the server. Text that the form cannot read (a type the engine does not have, {@code infinity}
   * as a date) stays the text.
   *
   * @param dateStyle the setting {@code DateStyle} of the session that wrote the text
   */
  static Object fromServer(final String type, final String text, final String dateStyle) {
    if (text == null)
      return null;
    try {
      return switch (type) {
        case "bool" -> text.equals("t") ? Boolean.TRUE : text.equals("f") ? Boolean.FALSE : text;
        case "int2", "int4", "int8", "oid", "numeric" -> number(text);
        // through float for float4, so that both sides widen the same single precision value
        case "float4" -> (double) Float.parseFloat(text);
        case "float8" -> Double.parseDouble(text);
        case "date" -> LocalDate.parse(text, dateFormat(dateStyle, false)).toString();
        case "timestamp" -> LocalDateTime.parse(text, dateFormat(dateStyle, true)).toString();
        case "json", "jsonb" -> Json.parse(text);
        case "bytea" -> Values.normalize(bytes(text));
        default -> type.startsWith("_") ? array(type.substring(1), text, dateStyle) : text;
      };
    } catch (final IllegalArgumentException | DateTimeParseException e) {
      return text;
    }
  }

  private static Object number(final String text) {
    return text.equals("NaN") || text.endsWith("Infinity") ? text : new BigDecimal(text);
  }

  private static DateTimeFormatter dateFormat(final String dateStyle, final boolean time) {
    final String style = dateStyle == null ? "ISO" : dateStyle;
    if (style.startsWith("ISO"))
      return time ? ISO_TIMESTAMP : DateTimeFormatter.ISO_LOCAL_DATE;
    final boolean dayFirst = style.endsWith("DMY");
    final String pattern;
    if (style.startsWith("German"))
      pattern = time ? "dd.MM.uuuu HH:mm:ss" : "dd.MM.uuuu";
    else if (style.startsWith("SQL"))
      pattern = (dayFirst ? "dd/MM/uuuu" : "MM/dd/uuuu") + (time ? " HH:mm:ss" : "");
    else if (time)
      pattern = (dayFirst ? "EEE dd MMM" : "EEE MMM dd") + " HH:mm:ss";
    else
      pattern = dayFirst ? "dd-MM-uuuu" : "MM-dd-uuuu";
    final DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder().appendPattern(pattern);
    if (time) {
      builder.appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true);
      if (style.startsWith("Postgres"))
        builder.appendPattern(" uuuu");
    }
    return builder.toFormatter(Locale.US);
  }

  private static byte[] bytes(final String text) {
    if (!text.startsWith("\\x"))
      throw new IllegalArgumentException("not the hex form of bytea");
    final byte[] bytes = new byte[(text.length() - 2) / 2];
    for (int i = 0; i < bytes.length; i++)
      bytes[i] = (byte) Integer.parseInt(text.substring(2 + 2 * i, 4 + 2 * i), 16);
    return bytes;
  }

  /** The elements of an array of one dimension, {@code {1,NULL,"a b"}}. */
  private static List<String> elements(final String text) {
    if (text.length() < 2 || text.charAt(0) != '{' || text.charAt(text.length() - 1) != '}')
      throw new IllegalArgumentException("not an array of one dimension");
    final List<String> elements = new ArrayList<>();
    int i = 1;
    final int end = text.length() - 1;
    while (i < end) {
      if (text.charAt(i) == '{')
        throw new IllegalArgumentException("not an array of one dimension");
      if (text.charAt(i) == '"') {
        final StringBuilder element = new StringBuilder();
        i++;
        while (i < end && text.charAt(i) != '"') {
          if (text.charAt(i) == '\\')
            i++;
          element.append(text.charAt(i++));
        }
        i++;
        elements.add(element.toString());
      } else {
        int comma = text.indexOf(',', i);
        if (comma < 0 || comma > end)
          comma = end;
        final String element = text.substring(i, comma);
        elements.add(element.equals("NULL") ? null : element);
        i = comma;
      }
      // the comma
      i++;
    }
    return elements;
  }

  private static List<Object> array(final String elementType, final String text, final String dateStyle) {
    final List<Object> values = new ArrayList<>();
    for (final String element : elements(text))
      values.add(fromServer(elementType, element, dateStyle));
    return values;
  }

  /**
   * The value the engine API stores for a value of the server, read with the ISO date style.
   *
   * @throws Unsupported when ArcadeDB cannot hold it
   */
  static Object toEngine(final String type, final String text) {
    if (text == null)
      return null;
    try {
      return switch (type) {
        case "bool" -> text.equals("t");
        case "int2" -> Short.valueOf(text);
        case "int4" -> Integer.valueOf(text);
        case "int8" -> Long.valueOf(text);
        case "float4" -> Float.valueOf(text);
        case "float8" -> Double.valueOf(text);
        case "numeric" -> new BigDecimal(text);
        case "text", "varchar" -> text;
        case "date" -> LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        case "timestamp" -> LocalDateTime.parse(text, ISO_TIMESTAMP);
        case "bytea" -> bytes(text);
        case "jsonb" -> {
          if (Json.parse(text) instanceof Map<?, ?> map)
            yield map;
          throw new Unsupported("a jsonb value that is not an object cannot be held");
        }
        default -> {
          if (!type.startsWith("_"))
            throw new Unsupported("a value of type " + type + " cannot be held");
          final List<Object> list = new ArrayList<>();
          for (final String element : elements(text))
            list.add(toEngine(type.substring(1), element));
          yield list;
        }
      };
    } catch (final IllegalArgumentException | DateTimeParseException e) {
      throw new Unsupported("the value '" + text + "' of type " + type + " cannot be held");
    }
  }
}
