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
import com.arcadedb.query.postgresql.TraversalFixture;
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
 * The traversal rule (fork issue #8) on the tiny fixture of {@link TraversalFixture}: which joins run as a traversal and which
 * keep the hash join, the plan line and its pattern, and above all that the rows are those of the equality join. The rule walks
 * only from a bound start (fork issue #52: a key equal to a constant, or in a list of constants), so the statements that assert a
 * walk have one; {@link TraversalBoundStartTest} is the specification of the bound start itself. The differential test runs every statement with the rule on and with it off and compares the two answers as
 * multisets; the two cases that the public discussion asked for (two parallel edges give two rows, a row without an edge comes
 * back once with {@code NULL}) are asserted row by row.
 */
class TraversalRuleTest extends TestHelper {

  /** The statements of the differential test: every shape the rule recognises, and some that it has to leave alone. */
  private static final List<String> STATEMENTS = List.of(
      // one hop in both directions, inner and left
      "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id",
      "SELECT p.name, c.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id LEFT JOIN company c ON w.target_id = c.id",
      "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id LEFT JOIN company c ON w.target_id = c.id",
      "SELECT p.name, c.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id",
      "SELECT c.name, p.name FROM company c LEFT JOIN works_at w ON w.target_id = c.id LEFT JOIN person p ON w.source_id = p.id",
      "SELECT c.name, p.name FROM company c JOIN works_at w ON w.target_id = c.id JOIN person p ON w.source_id = p.id",
      // the half patterns: the edge rows of a vertex, and the end vertex of an edge row
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id",
      "SELECT p.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id",
      "SELECT w.since, c.name FROM works_at w JOIN company c ON w.target_id = c.id",
      "SELECT w.since, p.name FROM works_at w LEFT JOIN person p ON w.source_id = p.id",
      // the edge table with its subtype, and without it
      "SELECT p.name, w.since FROM person p JOIN ONLY works_at w ON w.source_id = p.id",
      "SELECT p.name, w.since FROM ONLY person p LEFT JOIN works_at w ON w.source_id = p.id",
      // conditions: on the vertex, on the edge, on the far end, in the ON clause and in WHERE
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id WHERE p.pid = 1",
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id WHERE w.since >= 2020",
      "SELECT p.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id WHERE w.since >= 2020",
      "SELECT p.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id AND w.since > 2019",
      "SELECT p.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id AND p.pid < 3",
      "SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id AND w.since >= 2020 JOIN company c ON w.target_id = c.id AND c.cid = 10",
      "SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id WHERE c.name = 'Birch'",
      "SELECT p.name FROM person p, works_at w, company c WHERE w.source_id = p.id AND w.target_id = c.id AND c.cid = 10",
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id AND w.since = p.pid + 2018",
      // aggregates over a join through an edge
      "SELECT p.name, count(w.since) AS jobs FROM person p LEFT JOIN works_at w ON w.source_id = p.id GROUP BY p.name",
      "SELECT c.name, count(*) AS n FROM company c JOIN works_at w ON w.target_id = c.id GROUP BY c.name",
      // a subtype as the start, a lightweight edge type, a unidirectional one, loops and chains of hops
      "SELECT e.name, w.since FROM employee e LEFT JOIN works_at w ON w.source_id = e.id",
      "SELECT a.name, b.name FROM person a JOIN knows k ON k.source_id = a.id JOIN person b ON k.target_id = b.id",
      "SELECT a.name, b.name FROM person a LEFT JOIN knows k ON k.target_id = a.id LEFT JOIN person b ON k.source_id = b.id",
      "SELECT a.name, b.name, f.weight FROM person a JOIN follows f ON f.source_id = a.id JOIN person b ON f.target_id = b.id",
      "SELECT a.name, b.name, f.weight FROM person a LEFT JOIN follows f ON f.target_id = a.id LEFT JOIN person b ON f.source_id = b.id",
      "SELECT a.name FROM person a JOIN knows k ON k.source_id = a.id JOIN person b ON k.target_id = b.id WHERE a.pid = b.pid",
      "SELECT a.name, c.name FROM person a JOIN knows k1 ON k1.source_id = a.id JOIN person b ON k1.target_id = b.id JOIN knows k2 ON k2.source_id = b.id JOIN person c ON k2.target_id = c.id",
      "SELECT a.name, c.name FROM person a LEFT JOIN knows k1 ON k1.source_id = a.id LEFT JOIN person b ON k1.target_id = b.id LEFT JOIN works_at w ON w.source_id = b.id LEFT JOIN company c ON w.target_id = c.id",
      // shapes that are not a traversal: two edge ends against each other, no id, no edge
      "SELECT count(*) FROM works_at a JOIN works_at b ON a.target_id = b.target_id",
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id AND w.target_id = p.id",
      "SELECT p.name, o.name FROM person p JOIN company o ON p.name = o.name");

  /** The same shapes with a bound start (fork issue #52): every one of them runs as a walk with the rule on. */
  private static final List<String> BOUND_STATEMENTS = List.of(
      // one hop in both directions, inner and left
      "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id WHERE p.pid IN (1, 2, 3)",
      "SELECT p.name, c.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id LEFT JOIN company c ON w.target_id = c.id WHERE p.pid IN (1, 4, 5)",
      "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id LEFT JOIN company c ON w.target_id = c.id WHERE p.pid = 1",
      "SELECT p.name, c.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id WHERE p.pid = 3",
      "SELECT c.name, p.name FROM company c LEFT JOIN works_at w ON w.target_id = c.id LEFT JOIN person p ON w.source_id = p.id WHERE c.cid = 10",
      "SELECT c.name, p.name FROM company c JOIN works_at w ON w.target_id = c.id JOIN person p ON w.source_id = p.id WHERE c.cid IN (10, 20)",
      // the half patterns, with the table with its subtype and without it
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id WHERE p.pid = 1",
      "SELECT p.name, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id WHERE p.pid IN (4, 1)",
      "SELECT p.name, w.since FROM person p JOIN ONLY works_at w ON w.source_id = p.id WHERE p.pid = 3",
      "SELECT p.name, w.since FROM ONLY person p LEFT JOIN works_at w ON w.source_id = p.id WHERE p.pid = 3",
      // conditions on the edge, on the far end, in the ON clause, and a parameter-free expression as the constant
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id WHERE p.pid IN (1, 2, 3) AND w.since >= 2020",
      "SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id AND w.since >= 2020 JOIN company c ON w.target_id = c.id AND c.cid = 10 WHERE p.pid IN (1, 2)",
      "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id AND w.since = p.pid + 2018 WHERE p.pid = 1 + 1",
      "SELECT p.name, count(w.since) AS jobs FROM person p LEFT JOIN works_at w ON w.source_id = p.id WHERE p.pid IN (1, 4) GROUP BY p.name",
      // a lightweight edge type, a unidirectional one, loops and chains of hops
      "SELECT a.name, b.name FROM person a JOIN knows k ON k.source_id = a.id JOIN person b ON k.target_id = b.id WHERE a.pid IN (1, 3, 4)",
      "SELECT a.name, b.name FROM person a LEFT JOIN knows k ON k.target_id = a.id LEFT JOIN person b ON k.source_id = b.id WHERE a.pid IN (1, 3)",
      "SELECT a.name, b.name, f.weight FROM person a JOIN follows f ON f.source_id = a.id JOIN person b ON f.target_id = b.id WHERE a.pid = 1",
      "SELECT a.name FROM person a JOIN knows k ON k.source_id = a.id JOIN person b ON k.target_id = b.id WHERE a.pid = b.pid AND a.pid = 3",
      "SELECT a.name, c.name FROM person a JOIN knows k1 ON k1.source_id = a.id JOIN person b ON k1.target_id = b.id JOIN knows k2 ON k2.source_id = b.id JOIN person c ON k2.target_id = c.id WHERE a.pid = 4",
      "SELECT a.name, c.name FROM person a LEFT JOIN knows k1 ON k1.source_id = a.id LEFT JOIN person b ON k1.target_id = b.id LEFT JOIN works_at w ON w.source_id = b.id LEFT JOIN company c ON w.target_id = c.id WHERE a.pid IN (1, 4)");

  @Override
  protected void beginTest() {
    TraversalFixture.createSchema(database);
    TraversalFixture.load(database);
  }

  // ---- helpers ----

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

  private String plan(final String sql) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  private void rule(final boolean on) {
    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, on);
  }

  /** The rows as a sorted list of text, so that two answers compare as multisets. */
  private static List<String> multiset(final List<List<Object>> rows) {
    final List<String> out = new ArrayList<>();
    for (final List<Object> row : rows)
      out.add(row.toString());
    Collections.sort(out);
    return out;
  }

  // ---- the two cases of the public discussion ----

  @Test
  void twoParallelEdgesBetweenTheSameTwoVerticesGiveTwoRows() {
    final String sql = "SELECT w.since FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id"
        + " WHERE p.pid = 1 AND c.name = 'Acme' ORDER BY w.since";
    assertThat(plan(sql)).contains("TRAVERSE INNER").doesNotContain("HASH JOIN");
    assertThat(rows(sql)).containsExactly(row(2019), row(2022));
    rule(false);
    assertThat(plan(sql)).doesNotContain("TRAVERSE");
    assertThat(rows(sql)).as("the equality join gives the same two rows").containsExactly(row(2019), row(2022));
  }

  @Test
  void aRowWithoutAnEdgeInALeftJoinComesBackOnceWithNull() {
    final String sql = "SELECT p.name, w.since, c.name FROM person p LEFT JOIN works_at w ON w.source_id = p.id"
        + " LEFT JOIN company c ON w.target_id = c.id WHERE p.pid IN (4, 5) ORDER BY p.pid";
    assertThat(plan(sql)).contains("TRAVERSE LEFT").doesNotContain("HASH JOIN");
    assertThat(rows(sql)).containsExactly(row("Dan", null, null), row("Eve", null, null));
    rule(false);
    assertThat(rows(sql)).containsExactly(row("Dan", null, null), row("Eve", null, null));
  }

  // ---- the plan ----

  @Test
  void aJoinThroughAnEdgeTableIsOneTraversalWithItsPattern() {
    final String sql = "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id"
        + " JOIN company c ON w.target_id = c.id WHERE p.pid IN (1, 2, 3)";
    final String plan = plan(sql);
    assertThat(plan).contains("TRAVERSE INNER ON (w.source_id = p.id) (out works_at AS w FROM p) = MATCH (p:person)-[w:works_at]->()")
        .contains("[traversal: start bound by p.pid IN (1, 2, 3)]")
        .contains("WALK out EDGES works_at AS w of each left row")
        .contains("TRAVERSE TO c INNER ON (w.target_id = c.id)").contains("= MATCH (p:person)-[w:works_at]->(c:company)")
        .contains("READ RECORD BY ID company AS c (id = key) for each left row").contains("supported by edge:")
        .doesNotContain("HASH JOIN").doesNotContain("NESTED LOOP INNER");
    assertThat(rows(sql + " ORDER BY p.name, w.since")).containsExactly(row("Ann", "Acme", 2019), row("Ann", "Acme", 2022),
        row("Ben", "Acme", 2020), row("Cat", "Birch", 2021));
  }

  @Test
  void theIncomingDirectionWalksTheIncomingEdges() {
    final String sql = "SELECT c.name, p.name FROM company c LEFT JOIN works_at w ON w.target_id = c.id"
        + " LEFT JOIN person p ON w.source_id = p.id WHERE c.cid IN (10, 20, 30) ORDER BY c.name, p.name";
    assertThat(plan(sql)).contains("TRAVERSE LEFT ON (w.target_id = c.id) (in works_at AS w FROM c) = MATCH (c:company)<-[w:works_at]-()")
        .contains("= MATCH (p:person)-[w:works_at]->(c:company)").doesNotContain("HASH JOIN");
    assertThat(rows(sql)).containsExactly(row("Acme", "Ann"), row("Acme", "Ann"), row("Acme", "Ben"), row("Birch", "Cat"),
        row("Cedar", null));
  }

  @Test
  void aPushedTermOnTheVertexNarrowsTheLeftSetThroughItsIndexBeforeTheWalk() {
    // ONLY: a table with subtypes read with them (person has employee) has no index lookup, the index of a type covers its own buckets
    final String sql = "SELECT p.name, w.since FROM ONLY person p JOIN works_at w ON w.source_id = p.id WHERE p.pid = 1";
    assertThat(plan(sql)).contains("TRAVERSE INNER").contains("FETCH FROM INDEX person[pid]").doesNotContain("SCAN TYPE person");
    assertThat(rows(sql + " ORDER BY w.since")).containsExactly(row("Ann", 2019), row("Ann", 2022));
  }

  @Test
  void aTermOnTheEdgeFiltersTheEdgesOfEachVertex() {
    final String sql = "SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id"
        + " WHERE p.pid IN (1, 2, 3) AND w.since >= 2020 ORDER BY w.since";
    assertThat(plan(sql)).contains("TRAVERSE INNER").contains("FILTER pushed to w:");
    assertThat(rows(sql)).containsExactly(row("Ben", 2020), row("Cat", 2021), row("Ann", 2022));
  }

  @Test
  void theSwitchTurnsTheRuleOffAndTheDefaultIsOn() {
    final String sql = "SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id"
        + " WHERE p.pid = 1";
    assertThat(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE.getKey()).isEqualTo("arcadedb.postgresql.traversalRule");
    assertThat(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE.getDefValue()).isEqualTo(true);
    assertThat(database.getConfiguration().getValueAsBoolean(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE)).isTrue();
    assertThat(plan(sql)).contains("TRAVERSE");
    rule(false);
    final String off = plan(sql);
    assertThat(off).doesNotContain("TRAVERSE").doesNotContain("MATCH").contains("HASH JOIN").contains("supported by edge:");
    rule(true);
    assertThat(plan(sql)).contains("TRAVERSE");
  }

  // ---- what the rule leaves alone ----

  @Test
  void aUnidirectionalEdgeTypeIsWalkedOutwardsOnlyBecauseItStoresNoIncomingSide() {
    final String out = "SELECT a.name, b.name, f.weight FROM person a JOIN follows f ON f.source_id = a.id"
        + " JOIN person b ON f.target_id = b.id WHERE a.pid IN (1, 2) ORDER BY f.weight";
    assertThat(plan(out)).contains("(out follows AS f FROM a)");
    assertThat(rows(out)).containsExactly(row("Ann", "Ben", 1), row("Ben", "Ann", 2), row("Ann", "Ben", 3));

    final String in = "SELECT a.name, b.name, f.weight FROM person a JOIN follows f ON f.target_id = a.id"
        + " JOIN person b ON f.source_id = b.id WHERE a.pid IN (1, 2) ORDER BY f.weight";
    assertThat(plan(in)).as("an incoming walk of a unidirectional type would answer nothing").doesNotContain("(in follows")
        .contains("HASH JOIN");
    assertThat(rows(in)).containsExactly(row("Ben", "Ann", 1), row("Ann", "Ben", 2), row("Ben", "Ann", 3));
  }

  @Test
  void aLightweightEdgeTypeIsWalkedBothWays() {
    final String out = "SELECT a.name, b.name FROM person a JOIN knows k ON k.source_id = a.id JOIN person b ON k.target_id = b.id"
        + " WHERE a.pid IN (1, 3, 4) ORDER BY a.name, b.name";
    assertThat(plan(out)).contains("(out knows AS k FROM a)").contains("WALK out EDGES knows AS k");
    assertThat(rows(out)).containsExactly(row("Ann", "Ben"), row("Ann", "Cat"), row("Cat", "Cat"), row("Dan", "Ann"));

    final String in = "SELECT a.name, b.name FROM person a JOIN knows k ON k.target_id = a.id JOIN person b ON k.source_id = b.id"
        + " WHERE a.pid IN (1, 2, 3) ORDER BY a.name, b.name";
    assertThat(plan(in)).contains("(in knows AS k FROM a)");
    assertThat(rows(in)).containsExactly(row("Ann", "Dan"), row("Ben", "Ann"), row("Cat", "Ann"), row("Cat", "Cat"));
  }

  @Test
  void hopsChainAndEveryHopIsATraversal() {
    final String sql = "SELECT a.name, c.name FROM person a JOIN knows k1 ON k1.source_id = a.id JOIN person b ON k1.target_id = b.id"
        + " JOIN knows k2 ON k2.source_id = b.id JOIN person c ON k2.target_id = c.id WHERE a.pid IN (1, 2, 3, 4) ORDER BY a.name, c.name";
    final String plan = plan(sql);
    assertThat(plan).contains("(out knows AS k1 FROM a)").contains("(out knows AS k2 FROM b)").doesNotContain("HASH JOIN")
        .as("the second hop starts from the vertices of the first, so it is bound by the same term")
        .contains("[traversal: start bound by a.pid IN (1, 2, 3, 4)]");
    assertThat(rows(sql)).containsExactly(row("Ann", "Cat"), row("Cat", "Cat"), row("Dan", "Ben"), row("Dan", "Cat"));
  }

  @Test
  void anEdgeEndAgainstAnotherEdgeEndOrANonKeyConditionIsNotATraversal() {
    assertThat(plan("SELECT count(*) FROM works_at a JOIN works_at b ON a.target_id = b.target_id")).doesNotContain("TRAVERSE");
    assertThat(plan("SELECT p.name FROM person p JOIN works_at w ON w.since = p.pid")).doesNotContain("TRAVERSE");
    assertThat(plan("SELECT p.name FROM person p JOIN company c ON p.name = c.name")).doesNotContain("TRAVERSE");
  }

  @Test
  void aSubtypeStartAndAnOnlyReadKeepTheRowsOfTheEqualityJoin() {
    assertThat(rows("SELECT e.name, w.since FROM employee e LEFT JOIN works_at w ON w.source_id = e.id")).containsExactly(
        row("Eve", null));
    assertThat(rows("SELECT p.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id ORDER BY w.since")).as(
        "works_at reads works_at_remote too").containsExactly(row("Ann", 2019), row("Ben", 2020), row("Cat", 2021), row("Ann", 2022));
    assertThat(rows("SELECT p.name, w.since FROM person p JOIN ONLY works_at w ON w.source_id = p.id ORDER BY w.since")).as(
        "ONLY works_at does not").containsExactly(row("Ann", 2019), row("Ben", 2020), row("Ann", 2022));
  }

  @Test
  void aViewOfInformationSchemaIsNeverTheStartOfAWalkNorTheEndOfAnEdge() {
    // a view has no keys, links or edges behind its columns: its join is a plain equality, and the planner must not look for them
    final String start = "SELECT count(*) FROM information_schema.tables t JOIN works_at w ON w.source_id = t.table_name";
    assertThat(plan(start)).doesNotContain("TRAVERSE");
    assertThat(rows(start)).containsExactly(row(0L));
    final String end = "SELECT count(*) FROM information_schema.tables t JOIN person p ON t.table_name = p.id";
    assertThat(plan(end)).doesNotContain("TRAVERSE");
    assertThat(rows(end)).containsExactly(row(0L));
  }

  // ---- the differential test ----

  @Test
  void everyStatementGivesTheSameRowsWithTheRuleOnAndOff() {
    for (final String sql : STATEMENTS) {
      rule(true);
      final List<String> on = multiset(rows(sql));
      rule(false);
      final List<String> off = multiset(rows(sql));
      assertThat(plan(sql)).as(sql).doesNotContain("TRAVERSE").doesNotContain("no bound start");
      assertThat(on).as(sql).isEqualTo(off);
    }
    rule(true);
  }

  @Test
  void everyStatementWithABoundStartIsAWalkAndGivesTheSameRowsAsTheRuleOff() {
    for (final String sql : BOUND_STATEMENTS) {
      rule(true);
      final List<String> on = multiset(rows(sql));
      assertThat(plan(sql)).as("a walk: " + sql).contains("TRAVERSE").contains("[traversal: start bound by ");
      rule(false);
      final List<String> off = multiset(rows(sql));
      assertThat(plan(sql)).as(sql).doesNotContain("TRAVERSE").doesNotContain("start bound");
      assertThat(on).as(sql).isEqualTo(off);
    }
    rule(true);
  }
}
