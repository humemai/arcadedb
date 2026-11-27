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
package com.arcadedb.query.postgresql.plan.join;

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.PostgreSQLErrors;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A condition of a join with a subquery that reads a column of the join (fork issue #170): the columns that the subquery reads decide
 * where the condition is evaluated. They were not looked at, so a condition could be moved below the join to one table, where a
 * column of the other table was unknown, or was read from a query around the join. The comparison with PostgreSQL 18 is in
 * {@code PostgreSQLJoinSubqueryTest} of the {@code postgresw} module.
 */
class JoinSubqueryPushdownTest extends TestHelper {
  @Override
  protected void beginTest() {
    // a and b both have a column g, b and o both have a column h
    for (final String statement : List.of("CREATE TABLE a (x int, g int)", "CREATE TABLE b (g int, h int)",
        "CREATE TABLE d (y int, k int)", "CREATE TABLE o (h int)", "INSERT INTO a VALUES (1, 10), (2, 20), (3, 30)",
        "INSERT INTO b VALUES (20, 30), (30, 10)", "INSERT INTO d VALUES (1, 20), (2, 20), (3, 30)", "INSERT INTO o VALUES (20)"))
      database.command("postgresql", statement).close();
  }

  /** The rows are written as {@code x/g} pairs. */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "FROM a, b WHERE a.x IN (SELECT d.y FROM d WHERE d.k = b.g); 1/20 2/20 3/30",
      "FROM a, b WHERE a.x NOT IN (SELECT d.y FROM d WHERE d.k = b.g); 1/30 2/30 3/20",
      "FROM a, b WHERE b.g IN (SELECT d.k FROM d WHERE d.y = a.x); 1/20 2/20 3/30",
      "FROM a, b WHERE a.x = (SELECT min(d.y) FROM d WHERE d.k = b.g); 1/20 3/30",
      "FROM a, b WHERE 1 = (SELECT count(*) FROM d WHERE d.k = b.g); 1/30 2/30 3/30",
      "FROM a, b WHERE a.x IN (SELECT d.y FROM d WHERE d.k = (SELECT max(e.k) FROM d e WHERE e.k = b.g)); 1/20 2/20 3/30",
      "FROM a, b WHERE EXISTS (SELECT 1 FROM d WHERE d.y = a.x AND d.k = (SELECT max(e.k) FROM d e WHERE e.k = b.g)); 1/20 2/20 3/30",
      "FROM a, b WHERE a.x IN (SELECT d.y FROM d WHERE d.k = h); 3/20",
      "FROM a JOIN b ON a.x IN (SELECT d.y FROM d WHERE d.k = b.g); 1/20 2/20 3/30",
      "FROM a LEFT JOIN b ON b.g IN (SELECT d.k FROM d WHERE d.y = a.x); 1/20 2/20 3/30",
      "FROM a RIGHT JOIN b ON a.x IN (SELECT d.y FROM d WHERE d.k = b.g); 1/20 2/20 3/30",
      "FROM a LEFT JOIN b ON a.x * 10 = b.g WHERE a.x IN (SELECT d.y FROM d WHERE d.k = b.g); 2/20 3/30" })
  void aConditionIsEvaluatedWhereTheColumnsOfItsSubqueryAre(final String from, final String expected) {
    assertThat(rows("SELECT a.x, b.g " + from + " ORDER BY a.x, b.g", "x", "g")).isEqualTo(List.of(expected.split(" ")));
  }

  /** The query around the join has a table or a column of the same name: the join's own one is read. */
  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      "b; g; a.x IN (SELECT d.y FROM d WHERE d.k = b.g); 20/3 30/3",
      "b; g; a.x = (SELECT min(d.y) FROM d WHERE d.k = b.g); 20/2 30/2",
      "b; g; EXISTS (SELECT 1 FROM d WHERE d.y = a.x AND d.k = (SELECT max(e.k) FROM d e WHERE e.k = b.g)); 20/3 30/3",
      "o; h; a.x IN (SELECT d.y FROM d WHERE d.k = h); 20/1",
      "o; h; a.x IN (SELECT d.y FROM d WHERE d.k = b.g AND d.k = o.h); 20/2" })
  void aColumnOfTheJoinWinsOverOneOfTheQueryAround(final String table, final String column, final String where,
      final String expected) {
    final String statement = "SELECT " + table + "." + column + ", (SELECT count(*) FROM a, b WHERE " + where + ") AS n FROM "
        + table + " ORDER BY " + table + "." + column;
    assertThat(rows(statement, column, "n")).isEqualTo(List.of(expected.split(" ")));
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      // as PostgreSQL: g is in a and in b
      "a.x IN (SELECT d.y FROM d WHERE d.k = g); 42702",
      // PostgreSQL answers: an IN subquery inside an EXISTS that reads both tables is outside the subset
      "EXISTS (SELECT 1 FROM d WHERE d.y = a.x AND d.k IN (SELECT e.k FROM d e WHERE e.k = b.g)); 0A000" })
  void aConditionThatCannotBeAnsweredIsRefused(final String where, final String sqlState) {
    for (final String statement : List.of("SELECT a.x FROM a, b WHERE " + where,
        "SELECT b.g, (SELECT count(*) FROM a, b WHERE " + where + ") AS n FROM b"))
      assertThatThrownBy(() -> rows(statement, "x")).as(statement)
          .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(e.getMessage()).isEqualTo(sqlState));
  }

  private List<String> rows(final String statement, final String... columns) {
    final List<String> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        final List<String> values = new ArrayList<>();
        for (final String column : columns)
          values.add(String.valueOf((Object) row.getProperty(column)));
        rows.add(String.join("/", values));
      }
    }
    return rows;
  }
}
