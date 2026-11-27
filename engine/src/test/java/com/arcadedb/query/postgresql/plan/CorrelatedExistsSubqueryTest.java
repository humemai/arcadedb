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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.PostgreSQLErrors;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A correlated {@code EXISTS} with a scalar or {@code IN} subquery inside a term of its {@code WHERE} clause (fork issue #164): the
 * columns that this subquery reads decide whether the term belongs to the {@code EXISTS} subquery, to the query around it, or to
 * both. They were not looked at, and a column of the {@code EXISTS} subquery was then read from the query around. The comparison with
 * PostgreSQL 18 is in {@code PostgreSQLCorrelatedExistsTest} of the {@code postgresw} module.
 */
class CorrelatedExistsSubqueryTest extends TestHelper {
  @Override
  protected void beginTest() {
    // a and b both have a column g, d has none
    for (final String statement : List.of("CREATE TABLE a (x int, g int)", "CREATE TABLE b (g int)", "CREATE TABLE d (y int, k int)",
        "INSERT INTO a VALUES (1, 10), (2, 20), (3, 30)", "INSERT INTO b VALUES (20)",
        "INSERT INTO d VALUES (1, 20), (2, 10), (3, 30)"))
      database.command("postgresql", statement).close();
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', value = {
      // the subquery of the term reads a column of the EXISTS subquery
      "a.x = (SELECT min(d.y) FROM d WHERE d.k = g); 1",
      "a.x = (SELECT min(d.y) FROM d WHERE d.k = b.g); 1",
      "a.x < (SELECT max(d.y) FROM d WHERE d.k = g) + 1; 1",
      "a.x = (SELECT min(d.y) FROM d WHERE d.k = (SELECT max(e.k) FROM d e WHERE e.k = b.g)); 1",
      // it reads the query around the EXISTS
      "b.g = (SELECT max(d.k) FROM d WHERE d.y = a.x); 1",
      "1 = (SELECT count(*) FROM d WHERE d.y = a.x); 1,2,3",
      // it reads only the query around, only its own tables, or the EXISTS subquery beside a term of it
      "a.x IN (SELECT d.y FROM d WHERE d.k = a.g); 3",
      "a.x IN (SELECT d.y FROM d WHERE d.k = 30); 3",
      "b.g IN (SELECT d.k FROM d); 1,2,3",
      "b.g IN (SELECT d.k FROM d WHERE d.k = b.g) AND a.x > 1; 2,3" })
  void aTermIsAnsweredByTheColumnsThatItsSubqueryReads(final String where, final String expected) {
    assertThat(values("SELECT x FROM a WHERE EXISTS (SELECT 1 FROM b WHERE " + where + ") ORDER BY x"))
        .isEqualTo(List.of(expected.split(",")));
  }

  @Test
  void notExistsIsTheNegation() {
    assertThat(values("SELECT x FROM a WHERE NOT EXISTS (SELECT 1 FROM b WHERE a.x = (SELECT min(d.y) FROM d WHERE d.k = g))"
        + " ORDER BY x")).containsExactly("2", "3");
  }

  /** A term that reads both queries and cannot be split at a comparison: refused, and never answered with another column. */
  @ParameterizedTest
  @ValueSource(strings = {
      "a.x IN (SELECT d.y FROM d WHERE d.k = g)",
      "a.x NOT IN (SELECT d.y FROM d WHERE d.k = b.g)",
      "b.g IN (SELECT d.k FROM d WHERE d.y = a.x)",
      "1 = (SELECT count(*) FROM d WHERE d.k = b.g AND d.y = a.x)" })
  void aTermThatCannotBeSplitIsRefused(final String where) {
    final String statement = "SELECT x FROM a WHERE EXISTS (SELECT 1 FROM b WHERE " + where + ") ORDER BY x";
    assertThatThrownBy(() -> values(statement)).as(statement)
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(e.getMessage()).isEqualTo("0A000"));
  }

  /**
   * Inside a correlated subquery, a column of a query further out counts as one of the query around the {@code EXISTS}, as a
   * direct reference to it does: a comparison is answered, and an {@code IN} subquery beside a column of the {@code EXISTS}
   * subquery is refused.
   */
  @Test
  void aColumnTwoQueriesOutCountsAsOneOfTheQueryAround() {
    final String head = "SELECT x FROM a WHERE (SELECT count(*) FROM b b1 WHERE EXISTS (SELECT 1 FROM b WHERE ";
    assertThat(values(head + "b.g = (SELECT max(d.k) FROM d WHERE d.y = a.x))) > 0 ORDER BY x")).containsExactly("1");
    final String refused = head + "b.g IN (SELECT d.k FROM d WHERE d.y = a.x))) > 0 ORDER BY x";
    assertThatThrownBy(() -> values(refused)).as(refused)
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(e.getMessage()).isEqualTo("0A000"));
  }

  private List<String> values(final String statement) {
    final List<String> values = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        values.add(String.valueOf((Object) row.getProperty("x")));
      }
    }
    return values;
  }
}
