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
package com.arcadedb.query.postgresql;

import com.arcadedb.exception.CommandParsingException;
import com.arcadedb.function.text.TextLevenshteinDistance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A name that the relational view does not resolve: an unknown or ambiguous table, column or schema. It carries the SQLSTATE a
 * PostgreSQL client understands, as {@link PostgreSQLFeatureNotSupportedException} does for a statement outside the subset, so
 * the wire module can answer with it instead of the {@code 42601} it uses for a plain {@link CommandParsingException}. The
 * subclasses fix the SQLSTATE:
 * <ul>
 * <li>{@link PostgreSQLUndefinedTableException}: {@code 42P01}</li>
 * <li>{@link PostgreSQLUndefinedColumnException}: {@code 42703}</li>
 * <li>{@link PostgreSQLUndefinedSchemaException}: {@code 3F000}</li>
 * <li>{@link PostgreSQLAmbiguousIdentifierException}: {@code 42702} for a column, {@code 42P09} for a table</li>
 * </ul>
 * A hint, when there is one, ends the message and is also available on its own ({@link PostgreSQLError}).
 */
public class PostgreSQLCatalogException extends CommandParsingException implements PostgreSQLError {
  private static final int MAX_LISTED_COLUMNS = 40;
  /** The edits between a name and the column that PostgreSQL still suggests for it ({@code MAX_FUZZY_DISTANCE}). */
  private static final int MAX_SUGGESTION_DISTANCE = 3;

  private final String sqlState;
  private final String hint;

  protected PostgreSQLCatalogException(final String sqlState, final String message, final String hint) {
    super(hint == null ? message : message + ". Hint: " + hint);
    this.sqlState = sqlState;
    this.hint = hint;
  }

  public String getSqlState() {
    return sqlState;
  }

  /** What the user can do about it, {@code null} when there is nothing to add to the message. */
  public String getHint() {
    return hint;
  }

  /**
   * The columns a statement can name, for the hint of an unknown column: {@code the columns of person are id, age, name}. A long
   * list is cut, so the message stays readable.
   */
  public static String columnList(final String relation, final List<String> columns) {
    final StringBuilder out = new StringBuilder("the columns of ").append(relation).append(" are ")
        .append(String.join(", ", columns.subList(0, Math.min(columns.size(), MAX_LISTED_COLUMNS))));
    if (columns.size() > MAX_LISTED_COLUMNS)
      out.append(" and ").append(columns.size() - MAX_LISTED_COLUMNS).append(" more");
    return out.toString();
  }

  /**
   * The hint of a column that does not exist (fork issue #220): the column that was probably meant, in PostgreSQL's words, when a
   * name is close to the one written, then the columns the statement can name. It never says how to add a column: a statement
   * that follows such advice succeeds and leaves the column it meant as it was.
   * <p>
   * Close is PostgreSQL's rule: at most three edits, and at most half of the written name. Of the closest names one or two are
   * suggested, and none when more than two are as close. Case is ignored.
   *
   * @param column   the name as the statement wrote it, without its relation
   * @param columns  the columns the statement can name, by relation; an empty relation name for columns of no relation
   * @param list     the text that lists those columns ({@link #columnList}), {@code null} when there is nothing to name
   *
   * @return the hint, {@code null} when there is nothing to say
   */
  public static String unknownColumnHint(final String column, final Map<String, List<String>> columns, final String list) {
    final String written = column.toLowerCase(Locale.ENGLISH);
    int closest = Math.min(MAX_SUGGESTION_DISTANCE, written.length() / 2);
    final List<String> suggestions = new ArrayList<>();
    for (final Map.Entry<String, List<String>> relation : columns.entrySet())
      for (final String name : relation.getValue()) {
        final int distance = TextLevenshteinDistance.levenshteinDistance(written, name.toLowerCase(Locale.ENGLISH));
        if (distance > closest)
          continue;
        if (distance < closest) {
          closest = distance;
          suggestions.clear();
        }
        suggestions.add(quote(relation.getKey().isEmpty() ? name : relation.getKey() + "." + name));
      }
    if (suggestions.isEmpty() || suggestions.size() > 2)
      return list;
    final String suggestion = "Perhaps you meant to reference the column " + String.join(" or the column ", suggestions);
    return list == null ? suggestion : suggestion + "; " + list;
  }

  static String quote(final String name) {
    return '"' + name.replace("\"", "\"\"") + '"';
  }
}
