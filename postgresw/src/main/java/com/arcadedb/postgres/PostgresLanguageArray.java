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
package com.arcadedb.postgres;

import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.ValueInput;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * An array in an answer of the {@code postgresql} language (fork issue #234). To a client that asks for the binary format it is
 * the list of its elements, as every array is. In the text format it is the literal that PostgreSQL's {@code array_out} writes:
 * each element as the text of its type, {@code t} or {@code f} for a boolean, in double quotes only where the text needs them.
 * The arrays of the other languages keep the text that this protocol has always written for them.
 */
final class PostgresLanguageArray extends AbstractList<Object> {
  private final List<Object> elements;
  private final PgType       elementType;

  private PostgresLanguageArray(final List<Object> elements, final PgType elementType) {
    this.elements = elements;
    this.elementType = elementType;
  }

  /**
   * {@code value}, the value of a column of an array type of the language, as the wire writes it. A value that is no list, and a
   * list that holds a list, a document, or a record, is returned as it is, and keeps the text this protocol writes for it.
   *
   * @param elementType the type of the elements, {@code null} when the name of the column's type does not say it
   */
  static Object of(final Object value, final PgType elementType) {
    final List<?> items = value instanceof byte[] ? null : ValueInput.asList(value);
    if (items == null)
      return value;
    final List<Object> elements = new ArrayList<>(items.size());
    for (final Object item : items) {
      if (item == null || item == JSONObject.NULL)
        elements.add(null);
      else if (elementType == PgType.JSONB)
        // the text of each element, which the binary format carries too
        elements.add(PostgresLanguageAnswer.jsonb(item));
      else if (item instanceof Collection || item instanceof Map || item instanceof Result || item instanceof JSONObject
          || item instanceof JSONArray || item instanceof com.arcadedb.database.Record
          || item.getClass().isArray() && !(item instanceof byte[]))
        return value;
      else
        elements.add(item);
    }
    return new PostgresLanguageArray(elements, elementType);
  }

  @Override
  public Object get(final int index) {
    return elements.get(index);
  }

  @Override
  public int size() {
    return elements.size();
  }

  /** The array literal of the text format. */
  String literal() {
    final StringBuilder out = new StringBuilder("{");
    for (final Object element : elements) {
      // every element writes at least one character: NULL, or "" for the empty text
      if (out.length() > 1)
        out.append(',');
      if (element == null)
        out.append("NULL");
      else
        append(out, text(element));
    }
    return out.append('}').toString();
  }

  private String text(final Object element) {
    final PgType type = elementType != null ? elementType : PgType.ofJavaValue(element);
    // a jsonb element is its text already, and an interval has no cast to text
    if (type == null || type == PgType.JSONB || type == PgType.INTERVAL)
      return element.toString();
    if (type == PgType.BYTEA)
      return PostgresType.BYTEA.toText(PostgresType.BYTEA, element);
    final Object value = Casts.fromEngine(element, type);
    // the cast of a boolean to text says true, the output of an array says t
    if (type == PgType.BOOL)
      return (Boolean) value ? "t" : "f";
    return Casts.toText(value, type);
  }

  /**
   * Appends the text of one element, quoted where {@code array_out} quotes it: an empty text, the word {@code NULL} in any case,
   * and a text with a brace, a comma, a double quote, a backslash, or white space.
   */
  private static void append(final StringBuilder out, final String text) {
    boolean quoted = text.isEmpty() || text.equalsIgnoreCase("NULL");
    for (int i = 0; !quoted && i < text.length(); i++) {
      final char c = text.charAt(i);
      // from the tab to the carriage return are the other characters that PostgreSQL takes as white space
      quoted = c == '{' || c == '}' || c == ',' || c == '"' || c == '\\' || c == ' ' || c >= '\t' && c <= '\r';
    }
    if (!quoted) {
      out.append(text);
      return;
    }
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '"' || c == '\\')
        out.append('\\');
      out.append(c);
    }
    out.append('"');
  }
}
