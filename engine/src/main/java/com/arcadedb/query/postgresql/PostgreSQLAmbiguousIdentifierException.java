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

import java.util.List;

/**
 * An unquoted name matches several names that differ only in case, and none exactly: {@code 42702} (ambiguous_column) for a
 * column, {@code 42P09} (ambiguous_alias) for a table. The message names every candidate, and the hint says to quote the one that
 * is meant.
 */
public class PostgreSQLAmbiguousIdentifierException extends PostgreSQLCatalogException {
  /** ambiguous_column */
  public static final String COLUMN_SQLSTATE = "42702";
  /** ambiguous_alias */
  public static final String TABLE_SQLSTATE   = "42P09";

  private final List<String> candidates;

  private PostgreSQLAmbiguousIdentifierException(final String sqlState, final String what, final String name,
      final List<String> candidates) {
    super(sqlState, what + " " + quote(name) + " is ambiguous, it matches " + quoted(candidates),
        "write the name in double quotes, exactly as it is spelled");
    this.candidates = List.copyOf(candidates);
  }

  public static PostgreSQLAmbiguousIdentifierException table(final String name, final List<String> candidates) {
    return new PostgreSQLAmbiguousIdentifierException(TABLE_SQLSTATE, "table name", name, candidates);
  }

  public static PostgreSQLAmbiguousIdentifierException column(final String name, final List<String> candidates) {
    return new PostgreSQLAmbiguousIdentifierException(COLUMN_SQLSTATE, "column reference", name, candidates);
  }

  /** The names the identifier matches, in order. */
  public List<String> getCandidates() {
    return candidates;
  }

  private static String quoted(final List<String> names) {
    final StringBuilder text = new StringBuilder();
    for (final String name : names) {
      if (!text.isEmpty())
        text.append(", ");
      text.append(quote(name));
    }
    return text.toString();
  }
}
