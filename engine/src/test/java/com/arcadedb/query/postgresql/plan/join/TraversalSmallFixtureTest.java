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

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance statements of fork issue #8 on the {@code small} fixture: the rows (the five of the join, the six of the two
 * {@code LEFT} joins, the six of the incoming direction), the plan with the traversal step and its pattern, and the same rows
 * with the rule switched off. {@code AnswerCorpusTest} compares the same statements with PostgreSQL 18.
 */
class TraversalSmallFixtureTest extends TestHelper {

  private static final String FIRST = "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id"
      + " JOIN company c ON w.target_id = c.id";
  private static final String LEFT = "SELECT p.name, c.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id"
      + " LEFT JOIN company c ON w.target_id = c.id";
  private static final String INCOMING = "SELECT c.name, p.name FROM company c LEFT JOIN works_at w ON w.target_id = c.id"
      + " LEFT JOIN person p ON w.source_id = p.id";

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private static List<Object> row(final Object... values) {
    return Arrays.asList(values);
  }

  private List<List<Object>> rows(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      final List<List<Object>> out = new ArrayList<>();
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> values = new ArrayList<>();
        for (final String name : result.getPropertyNames())
          values.add(result.getProperty(name));
        out.add(values);
      }
      return out;
    }
  }

  private List<String> lines(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", "EXPLAIN " + sql)) {
      final List<String> lines = new ArrayList<>();
      while (resultSet.hasNext())
        lines.add(resultSet.next().getProperty("QUERY PLAN"));
      return lines;
    }
  }

  private void rule(final boolean on) {
    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, on);
  }

  private static List<String> multiset(final List<List<Object>> rows) {
    final List<String> out = new ArrayList<>();
    for (final List<Object> row : rows)
      out.add(row.toString());
    Collections.sort(out);
    return out;
  }

  @Test
  void theFirstStatementGivesTheFiveRowsOfTheCypherPatternAndAdaAppearsTwice() {
    assertThat(multiset(rows(FIRST))).containsExactlyElementsOf(multiset(List.of(row("Ada", "Acme", 2019), row("Ada", "Acme", 2022),
        row("Bo", "Acme", 2020), row("Cy", "Birch", 2021), row("Di", "Birch", 2023))));
  }

  @Test
  void explainHasOneTraversalStepNoHashJoinAndTheCypherPattern() {
    final List<String> lines = lines(FIRST);
    assertThat(lines.stream().filter(line -> line.trim().startsWith("TRAVERSE INNER")).count()).isEqualTo(1);
    assertThat(lines).anyMatch(line -> line.contains("= MATCH (p:person)-[w:works_at]->(c:company)"));
    assertThat(lines).noneMatch(line -> line.contains("HASH JOIN"));
    assertThat(lines.stream().map(String::trim).toList()).contains("SCAN TYPE person AS p (polymorphic)",
        "WALK out EDGES works_at AS w of each left row");
    assertThat(lines).anyMatch(line -> line.contains("supported by edge:"));
  }

  @Test
  void twoLeftJoinsGiveSixRowsAndEdKeepsHisRowOnce() {
    assertThat(multiset(rows(LEFT))).containsExactlyElementsOf(multiset(List.of(row("Ada", "Acme", 2019), row("Ada", "Acme", 2022),
        row("Bo", "Acme", 2020), row("Cy", "Birch", 2021), row("Di", "Birch", 2023), row("Ed", null, null))));
  }

  @Test
  void theIncomingDirectionGivesSixRowsAndCedarHasNoPerson() {
    assertThat(multiset(rows(INCOMING))).containsExactlyElementsOf(multiset(List.of(row("Acme", "Ada"), row("Acme", "Ada"),
        row("Acme", "Bo"), row("Birch", "Cy"), row("Birch", "Di"), row("Cedar", null))));
    assertThat(lines(INCOMING)).anyMatch(line -> line.trim().startsWith("TRAVERSE LEFT") && line.contains("(in works_at AS w FROM c)"));
  }

  @Test
  void withTheRuleOffThePlainJoinsReturnTheSameRows() {
    for (final String sql : List.of(FIRST, LEFT, INCOMING)) {
      rule(true);
      final List<String> on = multiset(rows(sql));
      rule(false);
      assertThat(lines(sql)).as(sql).noneMatch(line -> line.contains("TRAVERSE"));
      assertThat(multiset(rows(sql))).as(sql).isEqualTo(on);
    }
    rule(true);
  }

  @Test
  void theResultCarriesThePlanOfTheTraversal() {
    try (final PostgreSQLResultSet result = (PostgreSQLResultSet) database.query("postgresql", FIRST)) {
      assertThat(result.getExecutionPlan().orElseThrow().prettyPrint(0, 2)).contains("TRAVERSE INNER").contains("TRAVERSE TO c");
    }
  }
}
