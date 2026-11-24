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
import com.arcadedb.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The traversal rule walks only from a bound start (fork issue #52). The timed run of fork issue #13 measured one hop from all
 * vertices at 256.0 ms as a walk and 106.1 ms as the plain hash join, so the planner uses the walk where the statement names a
 * small start set, and keeps the hash join where the join reads all the vertices of a table.
 *
 * <p>The start of a join is its left input; it is bound when a term restricts it to the rows of a key ({@code key = constant}, or
 * {@code key IN (constants)}, the key being the {@code id} or a unique key of the table alone), or when it is a walk that began
 * there. The tests state what binds, what does not, what the plan line says in each case, and that the rows are the same with
 * the rule on and off. They run on the {@code small} fixture; {@link TraversalRuleTest} runs the differential over every shape of
 * hop on the tiny fixture of the rule.
 */
class TraversalBoundStartTest extends TestHelper {

  private static final String HOP = "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id"
      + " JOIN company c ON w.target_id = c.id";

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  // ---- helpers ----

  private static List<Object> row(final Object... values) {
    return Arrays.asList(values);
  }

  private List<List<Object>> rows(final String sql, final Object... parameters) {
    try (final ResultSet resultSet = database.query("postgresql", sql, parameters)) {
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

  private String plan(final String sql, final Object... parameters) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql, parameters)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
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

  /** The same statement with the rule off: its rows as a multiset, with the setting back on afterwards. */
  private List<String> rowsWithTheRuleOff(final String sql, final Object... parameters) {
    rule(false);
    try {
      return multiset(rows(sql, parameters));
    } finally {
      rule(true);
    }
  }

  private String planWithTheRuleOff(final String sql) {
    rule(false);
    try {
      return plan(sql);
    } finally {
      rule(true);
    }
  }

  private static int occurrences(final String text, final String part) {
    int count = 0;
    for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
      count++;
    return count;
  }

  /** The plan has no walk, the reason is in it, and the rows are those of the rule off. */
  private void assertNotAWalk(final String sql) {
    final String plan = plan(sql);
    assertThat(plan).as(sql).doesNotContain("TRAVERSE").contains("no bound start");
    assertThat(multiset(rows(sql))).as(sql).isEqualTo(rowsWithTheRuleOff(sql));
  }

  /** The plan walks, from the bound start named by {@code term}, and the rows are those of the rule off. */
  private void assertAWalk(final String sql, final String term) {
    final String plan = plan(sql);
    assertThat(plan).as(sql).contains("TRAVERSE INNER").contains("[traversal: start bound by " + term + "]").doesNotContain("HASH JOIN")
        .doesNotContain("no bound start");
    assertThat(multiset(rows(sql))).as(sql).isEqualTo(rowsWithTheRuleOff(sql));
  }

  // ---- what binds the start ----

  @Test
  void anEqualityOnTheKeyBindsTheStart() {
    final String sql = HOP + " WHERE p.pid = 1";
    assertAWalk(sql, "p.pid = 1");
    assertThat(multiset(rows(sql))).containsExactlyElementsOf(multiset(List.of(row("Ada", "Acme", 2019), row("Ada", "Acme", 2022))));
  }

  @Test
  void theConstantCanBeOnEitherSide() {
    assertAWalk(HOP + " WHERE 3 = p.pid", "3 = p.pid");
  }

  @Test
  void aListOfConstantsBindsTheStart() {
    final String sql = HOP + " WHERE p.pid IN (1, 5)";
    assertAWalk(sql, "p.pid IN (1, 5)");
    assertThat(multiset(rows(sql))).containsExactlyElementsOf(multiset(List.of(row("Ada", "Acme", 2019), row("Ada", "Acme", 2022))));
  }

  @Test
  void aParameterBindsTheStartAndSoDoesAListOfParameters() {
    final String one = HOP + " WHERE p.pid = $1";
    assertThat(plan(one, 2)).contains("TRAVERSE INNER").contains("[traversal: start bound by p.pid = $1]").doesNotContain("HASH JOIN");
    assertThat(rows(one, 2)).containsExactly(row("Bo", "Acme", 2020));
    assertThat(multiset(rows(one, 2))).isEqualTo(rowsWithTheRuleOff(one, 2));

    final String list = HOP + " WHERE p.pid IN ($1, $2)";
    assertThat(plan(list, 1, 2)).contains("TRAVERSE INNER").contains("[traversal: start bound by p.pid IN ($1, $2)]");
    assertThat(multiset(rows(list, 3, 4))).containsExactlyElementsOf(multiset(List.of(row("Cy", "Birch", 2021), row("Di", "Birch", 2023))));
  }

  @Test
  void anExpressionOfConstantsBindsTheStart() {
    assertAWalk(HOP + " WHERE p.pid = 1 + 1", "p.pid = (1 + 1)");
  }

  @Test
  void theIdOfTheTableIsAKey() {
    final String rid = (String) rows("SELECT id FROM person WHERE pid = 3").get(0).get(0);
    final String sql = HOP + " WHERE p.id = '" + rid + "'";
    assertAWalk(sql, "p.id = '" + rid + "'");
    assertThat(rows(sql)).containsExactly(row("Cy", "Birch", 2021));
  }

  @Test
  void theKeyOfTheStartCanBeTheEdgeEndOfAnIncomingWalk() {
    final String sql = "SELECT c.name, p.name FROM company c JOIN works_at w ON w.target_id = c.id JOIN person p ON w.source_id = p.id"
        + " WHERE c.cid = 20";
    assertAWalk(sql, "c.cid = 20");
    assertThat(plan(sql)).contains("(in works_at AS w FROM c)");
  }

  @Test
  void aLeftJoinFromABoundStartWalksAndKeepsTheRowWithoutAnEdge() {
    final String sql = "SELECT p.name, c.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id"
        + " LEFT JOIN company c ON w.target_id = c.id WHERE p.pid IN (4, 5)";
    assertThat(plan(sql)).contains("TRAVERSE LEFT").contains("[traversal: start bound by p.pid IN (4, 5)]").doesNotContain("HASH JOIN");
    assertThat(multiset(rows(sql))).containsExactlyElementsOf(multiset(List.of(row("Di", "Birch", 2023), row("Ed", null, null))));
    assertThat(multiset(rows(sql))).isEqualTo(rowsWithTheRuleOff(sql));
  }

  @Test
  void everyHopOfAChainStartsFromTheBoundStart() {
    final String sql = "SELECT p.name, c2.name FROM company c JOIN works_at w ON w.target_id = c.id JOIN person p ON w.source_id = p.id"
        + " JOIN works_at w2 ON w2.source_id = p.id JOIN company c2 ON w2.target_id = c2.id WHERE c.cid = 20";
    final String plan = plan(sql);
    assertThat(plan).doesNotContain("HASH JOIN").doesNotContain("no bound start");
    assertThat(occurrences(plan, "[traversal: start bound by c.cid = 20]")).as("four joins, four walk steps").isEqualTo(4);
    assertThat(multiset(rows(sql))).containsExactlyElementsOf(multiset(List.of(row("Cy", "Birch"), row("Di", "Birch"))));
    assertThat(multiset(rows(sql))).isEqualTo(rowsWithTheRuleOff(sql));
  }

  // ---- what does not bind it ----

  @Test
  void aJoinOfAllVerticesKeepsTheHashJoinAndSaysWhy() {
    // the shapes of the timed run: one hop from all vertices, an aggregate over it, and the same with LEFT joins
    for (final String sql : List.of(HOP,
        "SELECT count(*) AS n, sum(c.cid) AS cid_sum FROM person p JOIN works_at w ON w.source_id = p.id"
            + " JOIN company c ON w.target_id = c.id",
        "SELECT count(*) AS rows_n, count(w.id) AS edges_n, sum(c.cid) AS cid_sum FROM person p LEFT JOIN works_at w ON w.source_id = p.id"
            + " LEFT JOIN company c ON w.target_id = c.id")) {
      final String plan = plan(sql);
      assertThat(plan).as(sql).doesNotContain("TRAVERSE").contains("HASH JOIN").contains("[hash join: no bound start]");
      // the plan is the plan of the rule off, and the reason
      assertThat(plan.replaceAll(" \\[(hash join|index nested loop): no bound start\\]", "")).as(sql)
          .isEqualTo(planWithTheRuleOff(sql));
      assertThat(multiset(rows(sql))).as(sql).isEqualTo(rowsWithTheRuleOff(sql));
    }
  }

  @Test
  void aTermThatIsNotAKeyEqualityDoesNotBind() {
    for (final String where : List.of("p.city = 'Delft'", "p.name = 'Ada'", "p.pid > 1", "p.pid <> 1", "p.pid NOT IN (1, 2)",
        "p.pid = 1 OR p.pid = 2", "p.pid IN (1, NULL)", "p.pid IN (1, p.age)", "p.pid = p.age", "p.pid + 0 = 1", "p.pid BETWEEN 1 AND 3",
        "p.pid = (SELECT 1)"))
      assertNotAWalk(HOP + " WHERE " + where);
  }

  @Test
  void anIndexThatAllowsDuplicatesIsAnAccessPathAndNotABoundStart() {
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "person", "city");
    final String sql = HOP + " WHERE p.city = 'Delft'";
    assertThat(plan(sql)).contains("FETCH FROM INDEX person[city]");
    assertNotAWalk(sql);
  }

  @Test
  void aTermOnTheFarEndAloneDoesNotBindTheStart() {
    final String sql = HOP + " WHERE c.cid = 20";
    assertNotAWalk(sql);
    assertThat(multiset(rows(sql))).containsExactlyElementsOf(multiset(List.of(row("Cy", "Birch", 2021), row("Di", "Birch", 2023))));
  }

  @Test
  void aStartThatIsBoundByAJoinWithAnotherTableIsAHashJoin() {
    // the left input of the join through works_at is the join of orders and person, not a table and not a walk
    final String sql = "SELECT p.name, w.since FROM orders o JOIN person p ON o.buyer = p.id JOIN works_at w ON w.source_id = p.id"
        + " WHERE o.oid = 100 ORDER BY w.since";
    final String plan = plan(sql);
    assertThat(plan).doesNotContain("TRAVERSE").contains("HASH JOIN").contains("[hash join: no bound start]");
    assertThat(rows(sql)).containsExactly(row("Ada", 2019), row("Ada", 2022));
    assertThat(multiset(rows(sql))).isEqualTo(rowsWithTheRuleOff(sql));
  }

  @Test
  void aTableOfAllVerticesHasNoWalkEvenWhenTheEdgesAreFiltered() {
    final String sql = "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id WHERE w.since >= 2020";
    assertNotAWalk(sql);
  }

  // ---- the setting ----

  @Test
  void withTheSettingOffThereIsNoWalkAndNoReasonOnAnyPlanLine() {
    for (final String sql : List.of(HOP + " WHERE p.pid = 1", HOP, HOP + " WHERE c.cid = 20")) {
      rule(false);
      try {
        assertThat(plan(sql)).as(sql).doesNotContain("TRAVERSE").doesNotContain("start bound").doesNotContain("no bound start");
      } finally {
        rule(true);
      }
    }
  }

  @Test
  void theSettingIsReadForEveryStatement() {
    final String sql = HOP + " WHERE p.pid = 1";
    assertThat(plan(sql)).contains("TRAVERSE");
    assertThat(planWithTheRuleOff(sql)).doesNotContain("TRAVERSE");
    assertThat(plan(sql)).contains("TRAVERSE");
  }
}
