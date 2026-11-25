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

/**
 * A name in a query that does not resolve against the {@code FROM} clause of that query, which is where it differs from the
 * catalog errors (a table the schema does not have). It carries the SQLSTATE PostgreSQL gives:
 * <ul>
 * <li>{@code 42703} (undefined_column) for {@code SELECT nosuch FROM person}: {@code column "nosuch" does not exist};</li>
 * <li>{@code 42P01} (undefined_table) for {@code SELECT x.name FROM person}: {@code missing FROM-clause entry for table "x"};</li>
 * <li>{@code 42P10} (invalid_column_reference) for an {@code ORDER BY} position that is not in the select list, a {@code LIMIT}
 * that reads a column, and a {@code SELECT DISTINCT} sorted by an expression that is not in the list;</li>
 * <li>{@code 42712} (duplicate_alias) for two relations of one {@code FROM} clause with the same name.</li>
 * </ul>
 */
public class PostgreSQLReferenceException extends PostgreSQLCatalogException {
  private PostgreSQLReferenceException(final String sqlState, final String message, final String hint) {
    super(sqlState, message, hint);
  }

  /**
   * @param reference the name as the statement wrote it
   * @param columns   what the statement can name instead ({@link #columnList}), {@code null} when there is nothing to name
   */
  public static PostgreSQLReferenceException undefinedColumn(final String reference, final String columns) {
    return new PostgreSQLReferenceException("42703", "column " + quote(reference) + " does not exist",
        (columns == null ? "" : columns + "; ")
            + "a table has the properties its type declares as columns, nothing else: declare the property with CREATE PROPERTY in "
            + "the sql language");
  }

  public static PostgreSQLReferenceException missingFromEntry(final String relation) {
    return new PostgreSQLReferenceException("42P01", "missing FROM-clause entry for table " + quote(relation), null);
  }

  public static PostgreSQLReferenceException invalidReference(final String message) {
    return new PostgreSQLReferenceException("42P10", message, null);
  }

  public static PostgreSQLReferenceException duplicateAlias(final String relation) {
    return new PostgreSQLReferenceException("42712", "table name " + quote(relation) + " specified more than once", null);
  }
}
