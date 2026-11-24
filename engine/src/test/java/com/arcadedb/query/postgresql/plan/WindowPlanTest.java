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
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Window functions on the {@code small} fixture (fork issue #56): {@code row_number}, {@code rank}, {@code dense_rank},
 * {@code lag}, {@code lead}, {@code first_value}, {@code last_value} and the aggregates with {@code OVER}, with the default frame of
 * PostgreSQL; the SQLSTATE of what is refused. The answers are compared with PostgreSQL 18 by the answer corpus.
 */
class WindowPlanTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  /** The rows as lists of text, so that an {@code int} and a {@code bigint} compare by what they print. */
  private List<List<String>> rows(final String sql) {
    final List<List<String>> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        final List<String> cells = new ArrayList<>();
        for (final String name : row.getPropertyNames())
          cells.add(String.valueOf((Object) row.getProperty(name)));
        rows.add(cells);
      }
    }
    return rows;
  }

  private static List<String> row(final String... cells) {
    return List.of(cells);
  }

  private void assertState(final String sql, final String sqlState) {
    assertThatThrownBy(() -> rows(sql)).as(sql).isInstanceOfSatisfying(RuntimeException.class, e -> {
      try {
        assertThat(e.getClass().getMethod("getSqlState").invoke(e)).as(String.valueOf(e.getMessage())).isEqualTo(sqlState);
      } catch (final ReflectiveOperationException noState) {
        throw new AssertionError("no SQLSTATE on " + e, noState);
      }
    });
  }

  @Test
  void rowNumberCountsTheRowsOfThePartitionInTheOrderOfTheWindow() {
    assertThat(rows("SELECT pid, row_number() OVER (ORDER BY pid) AS rn FROM person ORDER BY pid")).containsExactly(
        row("1", "1"), row("2", "2"), row("3", "3"), row("4", "4"), row("5", "5"));
    // the sort puts NULL first for DESC, as PostgreSQL does
    assertThat(rows("SELECT name, row_number() OVER (PARTITION BY city ORDER BY age DESC) AS rn FROM person ORDER BY pid"))
        .containsExactly(row("Ada", "2"), row("Bo", "1"), row("Cy", "1"), row("Di", "2"), row("Ed", "1"));
  }

  @Test
  void rankLeavesAGapAfterTiesAndDenseRankDoesNot() {
    assertThat(rows("SELECT pid, rank() OVER (ORDER BY city) AS r, dense_rank() OVER (ORDER BY city) AS d FROM person ORDER BY pid"))
        .containsExactly(row("1", "1", "1"), row("2", "1", "1"), row("3", "3", "2"), row("4", "3", "2"), row("5", "5", "3"));
    assertThat(rows("SELECT pid, rank() OVER () AS r FROM person ORDER BY pid")).extracting(r -> r.get(1)).containsOnly("1");
  }

  @Test
  void lagAndLeadReadTheNeighbourRowsWithAnOffsetAndADefault() {
    assertThat(rows("SELECT pid, lag(name) OVER (ORDER BY pid) AS p, lead(age, 2, -1) OVER (ORDER BY pid) AS n FROM person "
        + "ORDER BY pid")).containsExactly(row("1", "null", "null"), row("2", "Ada", "29"), row("3", "Bo", "52"),
        row("4", "Cy", "-1"), row("5", "Di", "-1"));
    assertThat(rows("SELECT pid, lag(name, 2, 'none') OVER (PARTITION BY city ORDER BY pid) AS p FROM person ORDER BY pid"))
        .containsExactly(row("1", "none"), row("2", "none"), row("3", "none"), row("4", "none"), row("5", "none"));
  }

  @Test
  void anAggregateWithAnOrderByRunsToTheLastPeerOfTheRow() {
    // NULL is skipped by sum, the running total stays
    assertThat(rows("SELECT pid, sum(age) OVER (ORDER BY pid) AS s FROM person ORDER BY pid")).containsExactly(
        row("1", "36"), row("2", "77"), row("3", "77"), row("4", "106"), row("5", "158"));
    // rows that tie on the ORDER BY key are peers and get the same total
    assertThat(rows("SELECT pid, sum(pid) OVER (ORDER BY city) AS s FROM person ORDER BY pid")).containsExactly(
        row("1", "3"), row("2", "3"), row("3", "10"), row("4", "10"), row("5", "15"));
    assertThat(rows("SELECT pid, count(*) OVER (ORDER BY pid) AS c, min(name) OVER (ORDER BY pid) AS m FROM person ORDER BY pid"))
        .containsExactly(row("1", "1", "Ada"), row("2", "2", "Ada"), row("3", "3", "Ada"), row("4", "4", "Ada"), row("5", "5", "Ada"));
  }

  @Test
  void anAggregateWithoutAnOrderByCoversThePartition() {
    assertThat(rows("SELECT pid, count(*) OVER (PARTITION BY city) AS c, max(age) OVER () AS m FROM person ORDER BY pid"))
        .containsExactly(row("1", "2", "52"), row("2", "2", "52"), row("3", "2", "52"), row("4", "2", "52"), row("5", "1", "52"));
  }

  @Test
  void firstValueAndLastValueReadTheFrame() {
    assertThat(rows("SELECT pid, first_value(name) OVER (PARTITION BY city ORDER BY pid) AS f, "
        + "last_value(name) OVER (PARTITION BY city ORDER BY pid) AS l FROM person ORDER BY pid")).containsExactly(
        row("1", "Ada", "Ada"), row("2", "Ada", "Bo"), row("3", "Cy", "Cy"), row("4", "Cy", "Di"), row("5", "Ed", "Ed"));
    assertThat(rows("SELECT pid, last_value(name) OVER (PARTITION BY city) AS l FROM person ORDER BY pid")).extracting(r -> r.get(1))
        .containsExactly("Bo", "Bo", "Di", "Di", "Ed");
  }

  @Test
  void theLatestRowOfEachGroupThroughADerivedTable() {
    assertThat(rows("SELECT name FROM (SELECT name, row_number() OVER (PARTITION BY city ORDER BY pid DESC) AS rn FROM person) t "
        + "WHERE rn = 1 ORDER BY name")).containsExactly(row("Bo"), row("Di"), row("Ed"));
  }

  @Test
  void aWindowFunctionCanBeAnOrderByKeyAndAnOperandAndTakesLimit() {
    assertThat(rows("SELECT name FROM person ORDER BY row_number() OVER (ORDER BY age DESC NULLS LAST) LIMIT 2")).containsExactly(
        row("Ed"), row("Bo"));
    assertThat(rows("SELECT pid, row_number() OVER (ORDER BY pid) * 10 AS x FROM person ORDER BY pid LIMIT 2")).containsExactly(
        row("1", "10"), row("2", "20"));
  }

  @Test
  void theTreeOfAWindowCallPrintsAsItWasWritten() {
    final String text = "SELECT row_number() OVER (PARTITION BY city ORDER BY age DESC NULLS LAST) FROM person";
    final String printed = AstPrinter.print(StatementParser.parse(text));
    assertThat(printed).contains("row_number() OVER (PARTITION BY city ORDER BY age DESC NULLS LAST)");
    assertThat(AstPrinter.print(StatementParser.parse(printed))).isEqualTo(printed);
    assertThat(AstPrinter.print(StatementParser.parse("SELECT count(*) OVER () FROM person"))).contains("count(*) OVER ()");
  }

  @Test
  void theWindowStepIsInThePlan() {
    try (final ResultSet resultSet = database.query("postgresql", "EXPLAIN SELECT row_number() OVER (ORDER BY pid) FROM person")) {
      final StringBuilder text = new StringBuilder();
      while (resultSet.hasNext())
        text.append(resultSet.next().toString()).append('\n');
      assertThat(text.toString()).contains("WINDOW 1 function(s)");
    }
  }

  @Test
  void whatTheWindowStepCannotRunIsRefusedWithTheSqlStateOfPostgreSQL() {
    // a window function is only for the select list and ORDER BY
    assertState("SELECT pid FROM person WHERE row_number() OVER () = 1", "42P20");
    assertState("SELECT pid FROM person GROUP BY pid, row_number() OVER ()", "42P20");
    // no such window function
    assertState("SELECT rank(1) OVER () FROM person", "42883");
    assertState("SELECT nope() OVER () FROM person", "42883");
    // what PostgreSQL has and the prototype does not
    assertState("SELECT count(DISTINCT pid) OVER () FROM person", "0A000");
    assertState("SELECT sum(age) OVER (ORDER BY pid ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM person", "0A000");
    assertState("SELECT sum(age) OVER w FROM person WINDOW w AS (ORDER BY pid)", "0A000");
    assertState("SELECT count(*) FILTER (WHERE age > 1) OVER () FROM person", "0A000");
    assertState("SELECT ntile(2) OVER (ORDER BY pid) FROM person", "0A000");
    assertState("SELECT city, count(*), rank() OVER (ORDER BY count(*)) FROM person GROUP BY city", "0A000");
    assertState("SELECT lag(age, 1, 'x') OVER (ORDER BY pid) FROM person", "0A000");
  }
}
