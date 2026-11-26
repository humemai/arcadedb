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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The side under test of the regression harness (fork issue #119): ArcadeDB's {@code postgresql} language
 * ({@link RegressArcade}), or for the self-test of the harness one more PostgreSQL ({@link RegressPostgresSubject}). The other
 * side, the reference, is always a PostgreSQL server ({@link RegressDatabase}). Tables are named by the key the reference gives
 * them ({@link RegressDatabase.Table#key()}).
 */
interface RegressSubject extends AutoCloseable {
  /**
   * What one side answered to one statement.
   *
   * @param columns  the names of the columns, {@code null} for a statement that returns no rows
   * @param types    the names of their types as {@code pg_type} names them
   * @param affected the rows an {@code INSERT}, {@code UPDATE}, {@code DELETE} or {@code MERGE} changed, else {@code -1}
   * @param sqlState the SQLSTATE of the error, {@code null} when there is none
   * @param crashed  true when the statement ended without an answer of the language: an exception with no SQLSTATE or with
   *                 {@code XX000}, or a time limit
   */
  record Answer(List<String> columns, List<String> types, List<List<Object>> rows, long affected, String sqlState,
      String message, boolean crashed) {
    static Answer error(final String sqlState, final String message) {
      return new Answer(null, null, List.of(), -1, sqlState, message, false);
    }

    static Answer crash(final String sqlState, final String message) {
      return new Answer(null, null, List.of(), -1, sqlState, message, true);
    }

    boolean failed() {
      return sqlState != null || crashed;
    }
  }

  /** The columns and the keys of a table as {@code information_schema} shows them; the names of constraints are not compared. */
  record Shape(List<String> columns, List<String> keys) {
    /**
     * @param query runs one query of {@code information_schema} on a side and returns its rows as text
     */
    static Shape read(final Function<String, List<String[]>> query, final String schema, final String table) {
      final String where = " WHERE table_schema = " + literal(schema) + " AND table_name = " + literal(table);
      final List<String> columns = new ArrayList<>();
      for (final String[] row : query.apply(
          "SELECT column_name, data_type, udt_name, is_nullable, character_maximum_length, numeric_precision, numeric_scale"
              + " FROM information_schema.columns" + where + " ORDER BY ordinal_position")) {
        final boolean numeric = "numeric".equals(row[1]);
        columns.add(row[0] + " " + row[1] + " " + row[2] + " nullable=" + row[3] + " length=" + row[4]
            + (numeric ? " precision=" + row[5] + " scale=" + row[6] : ""));
      }

      final Map<String, String> constraints = new LinkedHashMap<>();
      for (final String[] row : query.apply(
          "SELECT constraint_name, constraint_type FROM information_schema.table_constraints" + where))
        if (!"CHECK".equals(row[1]))
          constraints.put(row[0], row[1]);
      final Map<String, Map<Integer, String>> keyColumns = new LinkedHashMap<>();
      for (final String[] row : query.apply(
          "SELECT constraint_name, ordinal_position, column_name FROM information_schema.key_column_usage" + where))
        keyColumns.computeIfAbsent(row[0], name -> new TreeMap<>()).put(Integer.valueOf(row[1]), row[2]);

      final List<String> keys = new ArrayList<>();
      for (final Map.Entry<String, String> constraint : constraints.entrySet()) {
        final Map<Integer, String> of = keyColumns.getOrDefault(constraint.getKey(), Map.of());
        String key = constraint.getValue() + " " + of.values();
        if ("FOREIGN KEY".equals(constraint.getValue())) {
          final List<String> referenced = new ArrayList<>();
          for (final String[] row : query.apply(
              "SELECT table_name, column_name FROM information_schema.constraint_column_usage WHERE constraint_name = "
                  + literal(constraint.getKey())))
            referenced.add(row[0] + "." + row[1]);
          Collections.sort(referenced);
          key += " references " + referenced;
        }
        keys.add(key);
      }
      Collections.sort(keys);
      return new Shape(columns, keys);
    }

    private static String literal(final String value) {
      return "'" + value.replace("'", "''") + "'";
    }
  }

  /** A statement of the shared setup of a file, which is not judged. */
  default void setup(final RegressPsql.Send send, final Supplier<String> copyData) {
  }

  /** Data that {@code psql} loads itself ({@code \copy}). */
  default void load(final String sql, final String data) {
  }

  /** A new session, see {@code \connect}. */
  default void connect(final String user) {
  }

  /** Runs one statement of the file on this side. */
  Answer execute(RegressPsql.Send send, Supplier<String> copyData);

  /** The tables that the last {@link #execute} changed, created or dropped on this side. */
  Set<String> touched();

  /** @return the shape of the table on this side, {@code null} when it has no such table */
  Shape shape(RegressDatabase.Table table);

  /** @return the rows of the table on this side in the normal form of the comparison, {@code null} when it has no such table */
  List<List<Object>> rows(RegressDatabase.Table table);

  /**
   * Makes this side hold the tables of the reference again.
   *
   * @param changed the tables that the reference changed since the last call
   */
  default void sync(final RegressDatabase reference, final Set<String> changed) {
  }

  /** @return why a statement that reads (or writes) the table cannot be judged, {@code null} when it can */
  default String blocked(final String table, final boolean write) {
    return null;
  }

  /** @return whether the harness made the table on this side, not the language under test */
  default boolean copied(final String table) {
    return false;
  }

  /** The tables whose present state on this side a rollback of the language cannot take back, because the harness made it. */
  default Set<String> rebuiltByHarness() {
    return Set.of();
  }

  @Override
  void close();
}
