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

import java.util.List;

/**
 * {@code json} values (fork issue #234). Unlike {@code jsonb}, a {@code json} value of PostgreSQL is its text: the keys keep
 * their order, and the spaces are the ones that whatever built it wrote. The texts written here are those of PostgreSQL's
 * {@code to_json}, {@code json_build_object}, {@code json_agg} and their relatives, which put their spaces in different places.
 * A {@code json} value has no equality and no order.
 */
public final class Json {
  private Json() {
  }

  /** A value of the type {@code json}: valid JSON text, which is also what a client gets. */
  public record Value(String text) {
    @Override
    public String toString() {
      return text;
    }
  }

  /** The value of a text that has to be JSON, {@code 22P02} otherwise. */
  static Value parse(final String text) {
    ValueInput.parseJson(text);
    return new Value(text);
  }

  /**
   * Appends what {@code to_json} makes of a value of {@code type}: SQL {@code NULL} is the JSON {@code null}, a number keeps its
   * digits, a date or a time is its text in ISO 8601 form, and an array has no spaces.
   *
   * @param typeName the name of the type, which for an array says the element type
   */
  public static void append(final StringBuilder out, final Object value, final PgType type, final String typeName) {
    if (value == null) {
      out.append("null");
      return;
    }
    switch (type) {
    case JSON -> out.append(((Value) value).text());
    case JSONB -> out.append(Jsonb.write(value));
    case BOOL, INT2, INT4, INT8, NUMERIC -> out.append(type == PgType.BOOL ? String.valueOf(value) : Casts.toText(value, type));
    case FLOAT4, FLOAT8 -> {
      final String text = Casts.toText(value, type);
      // NaN and Infinity are no JSON numbers: PostgreSQL writes them as strings
      if (text.indexOf('N') >= 0 || text.indexOf('I') >= 0)
        Jsonb.quote(text, out);
      else
        out.append(text);
    }
    case TEXT, VARCHAR, DATE, TIMESTAMP, TIMESTAMPTZ, BYTEA -> Jsonb.quote((String) Jsonb.of(value, type, typeName), out);
    case ARRAY -> {
      final PgType declared = ValueInput.elementType(typeName);
      final List<?> items = ValueInput.asList(value);
      out.append('[');
      for (int i = 0; i < items.size(); i++) {
        if (i > 0)
          out.append(',');
        final Object item = items.get(i);
        final PgType element = declared != null ? declared : PgType.ofJavaValue(item);
        if (element == null)
          throw PostgreSQLEvaluationException.unsupported(
              "A json value of a value of class " + item.getClass().getName() + " is not supported");
        append(out, item == null ? null : Casts.fromEngine(item, element), element, element.typeName());
      }
      out.append(']');
    }
    default -> throw PostgreSQLEvaluationException.unsupported(
        "A json value of a value of type " + type.displayName() + " is not supported");
    }
  }

  /**
   * Appends a value as the name of a member of an object: the text that {@code to_json} writes for it, always a string. A name
   * that is an array or a JSON value is refused when the statement is planned.
   */
  public static void appendKey(final StringBuilder out, final Object key, final PgType type, final String typeName) {
    if (type.isNumber() || type == PgType.BOOL)
      Jsonb.quote(type == PgType.BOOL ? String.valueOf(key) : Casts.toText(key, type), out);
    else
      append(out, key, type, typeName);
  }

  /** Whether a value of {@code type} cannot name a member of an object, which PostgreSQL refuses with {@code 22023}. */
  public static boolean isNoKey(final PgType type) {
    return type == PgType.ARRAY || type == PgType.JSON || type == PgType.JSONB || type == PgType.VECTOR;
  }
}
