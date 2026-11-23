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
package com.arcadedb.query.postgresql.corpus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON writer and reader for the expected outcomes of the parity corpus. It knows exactly the values of
 * {@link Values#normalize(Object)}: {@code null}, booleans, {@link BigDecimal} and {@link Double} numbers, strings, lists and
 * maps. The corpus files must not depend on a JSON library of the engine's test classpath, so this is all there is.
 * <p>
 * The writer never emits a raw tab or line break (they are escaped), so one JSON value is always one cell of a TSV file.
 */
public final class Json {
  private Json() {
  }

  public static String write(final Object value) {
    final StringBuilder out = new StringBuilder();
    write(out, value);
    return out.toString();
  }

  private static void write(final StringBuilder out, final Object value) {
    if (value == null)
      out.append("null");
    else if (value instanceof Boolean)
      out.append(value);
    else if (value instanceof BigDecimal decimal)
      out.append(decimal.toPlainString());
    else if (value instanceof Double number) {
      if (number.isNaN() || number.isInfinite())
        quote(out, number.toString());
      else
        out.append(number);
    } else if (value instanceof Number number)
      out.append(number);
    else if (value instanceof List<?> list) {
      out.append('[');
      boolean first = true;
      for (final Object item : list) {
        if (!first)
          out.append(',');
        first = false;
        write(out, item);
      }
      out.append(']');
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (final Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first)
          out.append(',');
        first = false;
        quote(out, String.valueOf(entry.getKey()));
        out.append(':');
        write(out, entry.getValue());
      }
      out.append('}');
    } else
      quote(out, String.valueOf(value));
  }

  private static void quote(final StringBuilder out, final String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      switch (c) {
      case '"' -> out.append("\\\"");
      case '\\' -> out.append("\\\\");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      case '\t' -> out.append("\\t");
      default -> {
        if (c < 0x20)
          out.append(String.format("\\u%04x", (int) c));
        else
          out.append(c);
      }
      }
    }
    out.append('"');
  }

  /**
   * @return {@code null}, a {@link Boolean}, a {@link BigDecimal}, a {@link String}, a {@link List} or a {@link Map} (in the
   * order of the text)
   *
   * @throws IllegalArgumentException when {@code text} is not one JSON value
   */
  public static Object parse(final String text) {
    final Reader reader = new Reader(text);
    final Object value = reader.value();
    reader.skipSpaces();
    if (reader.position < text.length())
      throw reader.fail("unexpected text after the value");
    return value;
  }

  private static final class Reader {
    private final String text;
    private       int    position;

    private Reader(final String text) {
      this.text = text;
    }

    private IllegalArgumentException fail(final String message) {
      return new IllegalArgumentException("JSON: " + message + " at offset " + position + " of: " + text);
    }

    private void skipSpaces() {
      while (position < text.length() && Character.isWhitespace(text.charAt(position)))
        ++position;
    }

    private Object value() {
      skipSpaces();
      if (position >= text.length())
        throw fail("unexpected end");
      final char c = text.charAt(position);
      if (c == '{')
        return object();
      if (c == '[')
        return array();
      if (c == '"')
        return string();
      if (text.startsWith("null", position)) {
        position += 4;
        return null;
      }
      if (text.startsWith("true", position)) {
        position += 4;
        return Boolean.TRUE;
      }
      if (text.startsWith("false", position)) {
        position += 5;
        return Boolean.FALSE;
      }
      return number();
    }

    private Map<String, Object> object() {
      final Map<String, Object> map = new LinkedHashMap<>();
      ++position;
      skipSpaces();
      if (peek() == '}') {
        ++position;
        return map;
      }
      while (true) {
        skipSpaces();
        if (peek() != '"')
          throw fail("a key is expected");
        final String key = string();
        skipSpaces();
        if (peek() != ':')
          throw fail("':' is expected");
        ++position;
        map.put(key, value());
        skipSpaces();
        final char next = peek();
        ++position;
        if (next == '}')
          return map;
        if (next != ',')
          throw fail("',' or '}' is expected");
      }
    }

    private List<Object> array() {
      final List<Object> list = new ArrayList<>();
      ++position;
      skipSpaces();
      if (peek() == ']') {
        ++position;
        return list;
      }
      while (true) {
        list.add(value());
        skipSpaces();
        final char next = peek();
        ++position;
        if (next == ']')
          return list;
        if (next != ',')
          throw fail("',' or ']' is expected");
      }
    }

    private String string() {
      final StringBuilder out = new StringBuilder();
      ++position;
      while (true) {
        if (position >= text.length())
          throw fail("unterminated string");
        final char c = text.charAt(position++);
        if (c == '"')
          return out.toString();
        if (c != '\\') {
          out.append(c);
          continue;
        }
        if (position >= text.length())
          throw fail("unterminated escape");
        final char escaped = text.charAt(position++);
        switch (escaped) {
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'u' -> {
          if (position + 4 > text.length())
            throw fail("short unicode escape");
          out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
          position += 4;
        }
        default -> out.append(escaped);
        }
      }
    }

    private BigDecimal number() {
      final int start = position;
      while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0)
        ++position;
      if (start == position)
        throw fail("a value is expected");
      return new BigDecimal(text.substring(start, position));
    }

    private char peek() {
      if (position >= text.length())
        throw fail("unexpected end");
      return text.charAt(position);
    }
  }
}
