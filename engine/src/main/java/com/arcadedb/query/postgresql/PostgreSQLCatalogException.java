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
 * A hint, when there is one, is part of the message (the wire writes the message only) and is also available on its own.
 */
public class PostgreSQLCatalogException extends CommandParsingException {
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

  static String quote(final String name) {
    return '"' + name.replace("\"", "\"\"") + '"';
  }
}
