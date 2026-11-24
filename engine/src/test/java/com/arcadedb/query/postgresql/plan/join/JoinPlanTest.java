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
import com.arcadedb.query.postgresql.PostgreSQLCatalogException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.postgresql.plan.SelectPlan;
import com.arcadedb.query.postgresql.plan.SelectPlanner;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Joins on the {@code small} fixture of fork issue #2 (five people, three companies, five {@code works_at} edges, six orders,
 * one of them without a buyer): {@code NULL} keys, duplicates on both sides, {@code LEFT} joins with and without a partner,
 * {@code ON} residuals, left deep chains, the placement of {@code WHERE} terms, the strategy of each join (asserted on the plan
 * text, never on timing), the memory cap, and the errors of names and types. The rows of the corpus statements are checked by
 * {@code AnswerCorpusTest} against PostgreSQL 18; the rows here follow from the fixture and are checked by reading it.
 */
class JoinPlanTest extends TestHelper {

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
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

  private long count(final String sql) {
    return (Long) rows(sql).get(0).get(0);
  }

  private String plan(final String sql) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  private String state(final String sql) {
    try {
      rows(sql);
      return null;
    } catch (final PostgreSQLCatalogException e) {
      return e.getSqlState();
    } catch (final PostgreSQLEvaluationException e) {
      return e.getSqlState();
    } catch (final PostgreSQLFeatureNotSupportedException e) {
      return e.getSqlState();
    }
  }

  private SelectPlan planned(final String sql, final long cap) {
    return new SelectPlanner(database, Parameters.NONE, cap).plan((SelectStatement) StatementParser.parse(sql));
  }

  // ---- the demo and the semantics of the two join types ----

  @Test
  void theDemoStatementJoinsAndFilters() {
    final String sql = "SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid WHERE p.city = 'Delft' ORDER BY o.oid LIMIT 2";
    assertThat(rows(sql)).containsExactly(row("Ada", 100), row("Ada", 101));
    assertThat(plan(sql)).contains("HASH JOIN INNER ON").contains("FILTER pushed to p");
  }

  @Test
  void aNullKeyMatchesNothing() {
    // order 105 has no pid: it joins no person, and no other NULL
    assertThat(rows("SELECT o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid")).containsExactly(row(100), row(101),
        row(102), row(103), row(104));
    assertThat(count("SELECT count(*) FROM orders a JOIN orders b ON a.pid = b.pid")).as("two NULL keys are not equal")
        .isEqualTo(2 * 2 + 1 + 2 * 2);
  }

  @Test
  void aLeftJoinPadsTheRowsWithoutAPartnerWithNull() {
    assertThat(rows("SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid ORDER BY p.name, o.oid"))
        .containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102), row("Cy", null), row("Di", 103), row("Di", 104),
            row("Ed", null));
    assertThat(rows("SELECT o.oid, p.name FROM orders o LEFT JOIN person p ON o.pid = p.pid WHERE o.oid >= 104 ORDER BY o.oid"))
        .as("the order without a pid is kept with NULL on the right").containsExactly(row(104, "Di"), row(105, null));
    assertThat(rows("SELECT p.name FROM person p LEFT OUTER JOIN orders o ON o.pid = p.pid WHERE o.oid IS NULL ORDER BY p.name"))
        .as("the anti join form").containsExactly(row("Cy"), row("Ed"));
  }

  @Test
  void duplicatesOnBothSidesMultiply() {
    // Delft has two people, Leiden two, Utrecht one
    assertThat(count("SELECT count(*) FROM person a JOIN person b ON a.city = b.city")).isEqualTo(4 + 4 + 1);
    // Acme has three edges, Birch two
    assertThat(count("SELECT count(*) FROM works_at a JOIN works_at b ON a.target_id = b.target_id")).isEqualTo(9 + 4);
  }

  @Test
  void anOnResidualIsPartOfTheMatchOfALeftJoin() {
    // the orders with a total over 9 are 100, 101 and 102; a person without one is kept, padded
    assertThat(rows("SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid AND o.total > 9 ORDER BY p.name, o.oid"))
        .containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102), row("Cy", null), row("Di", null), row("Ed", null));
    // the same term in WHERE drops the padded rows
    assertThat(rows("SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid WHERE o.total > 9 ORDER BY o.oid"))
        .containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102));
  }

  @Test
  void aNonEqualityConditionIsAResidualOrANestedLoop() {
    assertThat(rows("SELECT a.name, b.name FROM person a JOIN person b ON a.city = b.city AND a.pid < b.pid ORDER BY a.pid"))
        .containsExactly(row("Ada", "Bo"), row("Cy", "Di"));
    assertThat(count("SELECT count(*) FROM person a JOIN person b ON a.pid < b.pid")).isEqualTo(10);
    assertThat(plan("SELECT count(*) FROM person a JOIN person b ON a.pid < b.pid")).contains("NESTED LOOP INNER");
    assertThat(count("SELECT count(*) FROM person a LEFT JOIN person b ON a.pid < b.pid AND b.pid > 100")).as("every row is padded")
        .isEqualTo(5);
  }

  @Test
  void keysOfDifferentNumericTypesJoinAndTextAgainstANumberIsRefused() {
    assertThat(count("SELECT count(*) FROM person p JOIN orders o ON p.pid::bigint = o.pid")).as("int8 against int4").isEqualTo(5);
    assertThat(count("SELECT count(*) FROM person p JOIN orders o ON p.pid * 1.0 = o.pid")).as("numeric against int4").isEqualTo(5);
    assertThat(state("SELECT 1 FROM person p JOIN orders o ON p.name = o.oid")).isEqualTo("42883");
    assertThat(state("SELECT 1 FROM person p JOIN orders o ON p.pid = o.oid + 'x'")).isEqualTo("22P02");
  }

  // ---- several joins ----

  @Test
  void joinsAreLeftDeepInTheWrittenOrder() {
    assertThat(rows(
        "SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id ORDER BY p.name, w.since"))
        .containsExactly(row("Ada", "Acme"), row("Ada", "Acme"), row("Bo", "Acme"), row("Cy", "Birch"), row("Di", "Birch"));
    assertThat(count("SELECT count(*) FROM company c LEFT JOIN works_at w ON w.target_id = c.id LEFT JOIN person p ON w.source_id = p.id"))
        .as("five edges, and Cedar without one").isEqualTo(6);
  }

  @Test
  void aCommaListIsAChainOfInnerJoins() {
    final String sql = "SELECT count(*) FROM orders o, person p WHERE o.pid = p.pid";
    assertThat(count(sql)).isEqualTo(5);
    assertThat(plan(sql)).contains("HASH JOIN INNER").doesNotContain("FILTER WHERE");
    assertThat(count("SELECT count(*) FROM person, company")).as("no condition: every pair").isEqualTo(15);
    assertThat(plan("SELECT count(*) FROM person, company")).contains("NESTED LOOP INNER");
    assertThat(count("SELECT count(*) FROM person p, company c, works_at w WHERE w.source_id = p.id AND w.target_id = c.id"))
        .isEqualTo(5);
    assertThat(rows("SELECT o.oid FROM orders o, person p WHERE (o.pid = p.pid AND p.city = 'Delft') OR (o.pid = p.pid AND o.total < 6) ORDER BY o.oid"))
        .containsExactly(row(100), row(101), row(102), row(103));
  }

  // ---- where terms below the joins ----

  @Test
  void aTermOnOneSideMovesBelowAnInnerJoinAtAnyDepth() {
    final String sql = "SELECT p.name FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id"
        + " WHERE p.city = 'Leiden' AND c.name = 'Birch' ORDER BY p.name";
    assertThat(rows(sql)).containsExactly(row("Cy"), row("Di"));
    final String plan = plan(sql);
    assertThat(plan).contains("FILTER pushed to p").contains("FILTER pushed to c").doesNotContain("FILTER WHERE");
  }

  @Test
  void aTermOnTheNullSuppliedSideStaysAboveALeftJoin() {
    final String sql = "SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid WHERE o.total > 9 ORDER BY o.oid";
    assertThat(plan(sql)).contains("FILTER WHERE").doesNotContain("FILTER pushed to o");
    assertThat(rows(sql)).containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102));
  }

  @Test
  void aTermOnThePreservedSideMovesBelowALeftJoin() {
    final String sql = "SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid WHERE p.city = 'Leiden' ORDER BY p.name, o.oid";
    assertThat(plan(sql)).contains("FILTER pushed to p").doesNotContain("FILTER WHERE");
    assertThat(rows(sql)).containsExactly(row("Cy", null), row("Di", 103), row("Di", 104));
  }

  @Test
  void anOnTermOnTheRightSideMovesBelowALeftJoinButOneOnTheLeftSideDoesNot() {
    final String right = "SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid AND o.total > 9";
    assertThat(plan(right)).contains("FILTER pushed to o");
    final String left = "SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid AND p.city = 'Delft' ORDER BY p.name, o.oid";
    assertThat(plan(left)).doesNotContain("FILTER pushed to p");
    assertThat(rows(left)).as("Cy and Di stay with NULL: their city fails the match, not the row")
        .containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102), row("Cy", null), row("Di", null), row("Ed", null));
  }

  @Test
  void aTermOnBothSidesOfAnInnerJoinIsPartOfItsCondition() {
    final String sql = "SELECT o.oid FROM orders o JOIN person p ON o.pid = p.pid WHERE p.city = 'Delft' OR o.total > 20 ORDER BY o.oid";
    assertThat(rows(sql)).containsExactly(row(100), row(101), row(102));
    assertThat(plan(sql)).doesNotContain("FILTER WHERE");
  }

  // ---- strategies ----

  @Test
  void aHashJoinBuildsOnTheRightInputOfALeftJoinAndOnTheSmallerInputOfAnInnerJoin() {
    // orders has six rows, person five, and orders.pid has no index
    assertThat(plan("SELECT p.name FROM person p JOIN orders o ON o.pid = p.pid")).contains("HASH JOIN INNER").contains("(build p)");
    assertThat(plan("SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid")).contains("HASH JOIN INNER").contains("(build p)");
    assertThat(plan("SELECT p.name FROM person p LEFT JOIN orders o ON o.pid = p.pid")).as("a LEFT join probes with the left side")
        .contains("HASH JOIN LEFT").contains("(build o)");
  }

  @Test
  void anIndexNestedLoopReadsTheRightTableByItsIndexForEachLeftRow() {
    final String sql = "SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE o.oid = 102";
    final String plan = plan(sql);
    assertThat(plan).contains("INDEX NESTED LOOP INNER").contains("FETCH FROM INDEX person[pid]").doesNotContain("HASH JOIN");
    assertThat(rows(sql)).containsExactly(row("Bo"));
    assertThat(rows("SELECT o.oid, p.name FROM orders o LEFT JOIN person p ON o.pid = p.pid WHERE o.oid = 105"))
        .as("a NULL key looks nothing up and is padded").containsExactly(row(105, null));
    assertThat(rows("SELECT o.oid, p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE o.oid = 105")).isEmpty();
  }

  @Test
  void aLinkToAnIdIsReadByRecordId() {
    final String sql = "SELECT p.name FROM orders o JOIN person p ON o.buyer = p.id WHERE o.oid = 102";
    assertThat(plan(sql)).contains("INDEX NESTED LOOP").contains("READ RECORD BY ID person");
    assertThat(rows(sql)).containsExactly(row("Bo"));
    assertThat(rows("SELECT o.oid, p.name FROM orders o LEFT JOIN person p ON o.buyer = p.id ORDER BY o.oid"))
        .as("the hash join gives the same rows: the order without a buyer is padded")
        .containsExactly(row(100, "Ada"), row(101, "Ada"), row(102, "Bo"), row(103, "Di"), row(104, "Di"), row(105, null));
  }

  @Test
  void aTableWithSubtypesIsJoinedByHashUnlessItIsReadOnly() {
    final Schema schema = database.getSchema();
    schema.createDocumentType("pet").createProperty("pid", Type.INTEGER);
    schema.getType("pet").createProperty("aid", Type.INTEGER);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "pet", "pid");
    schema.createVertexType("animal").createProperty("aid", Type.INTEGER);
    schema.buildVertexType().withName("dog").withSuperType("animal").create();
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "animal", "aid");
    database.transaction(() -> {
      database.newDocument("pet").set("pid", 1).set("aid", 7).save();
      database.newDocument("pet").set("pid", 2).set("aid", 8).save();
      database.newVertex("animal").set("aid", 7).save();
      database.newVertex("dog").set("aid", 8).save();
    });

    final String all = "SELECT p.pid, a.aid FROM pet p JOIN animal a ON p.aid = a.aid WHERE p.pid = 1";
    assertThat(plan(all)).as("the index of animal does not cover dog").contains("HASH JOIN").doesNotContain("INDEX NESTED LOOP");
    assertThat(rows("SELECT p.pid, a.aid FROM pet p JOIN animal a ON p.aid = a.aid ORDER BY p.pid")).containsExactly(row(1, 7),
        row(2, 8));
    final String only = "SELECT p.pid, a.aid FROM pet p JOIN ONLY animal a ON p.aid = a.aid WHERE p.pid = 1";
    assertThat(plan(only)).contains("INDEX NESTED LOOP").contains("FETCH FROM INDEX animal[aid]");
    assertThat(rows(only)).containsExactly(row(1, 7));
    assertThat(rows("SELECT p.pid, a.aid FROM pet p JOIN ONLY animal a ON p.aid = a.aid ORDER BY p.pid")).as("dog is not in ONLY animal")
        .containsExactly(row(1, 7));
  }

  @Test
  void anEdgeEndJoinedToAnIdIsReadByRecordIdFromABoundStartAndOtherwiseIsAPlainEquality() {
    final String all = "SELECT count(*) FROM works_at w JOIN person p ON w.source_id = p.id";
    assertThat(count(all)).isEqualTo(5);
    // all the edges (fork issue #52): no bound start, so the plan is the one of the plain equality, with the reason
    assertThat(plan(all)).contains("supported by edge:").contains("no bound start").doesNotContain("TRAVERSE");

    // the far end of one edge, named by the key of the edge table (its record id): read by record id for each edge row
    final String rid = (String) rows("SELECT id FROM works_at WHERE since = 2020").get(0).get(0);
    final String sql = "SELECT count(*) FROM works_at w JOIN person p ON w.source_id = p.id WHERE w.id = '" + rid + "'";
    assertThat(count(sql)).isEqualTo(1);
    assertThat(plan(sql)).contains("supported by edge:").contains("TRAVERSE TO p").contains("[traversal: start bound by w.id = '" + rid + "']");
    // with the traversal rule off (fork issue #8) the join is the plain equality of two columns, and the rows are the same
    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, false);
    assertThat(plan(sql)).contains("supported by edge:").doesNotContain("TRAVERSE").doesNotContain("start bound").doesNotContain("no bound start");
    assertThat(count(sql)).isEqualTo(1);
    assertThat(count(all)).isEqualTo(5);
    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, true);
    final SelectPlan select = planned(all, BuildMemory.FLOOR_CAP_BYTES);
    assertThat(select.source()).isInstanceOf(JoinSource.class);
    assertThat(((JoinSource) select.source()).support().kind()).isEqualTo(JoinSupport.Kind.EDGE);
  }

  @Test
  void theSupportOfAJoinIsADecisionWithAReason() {
    assertThat(supportOf("SELECT 1 FROM orders o JOIN person p ON o.pid = p.pid").kind()).isEqualTo(JoinSupport.Kind.INDEX);
    assertThat(supportOf("SELECT 1 FROM orders o JOIN person p ON o.buyer = p.id").kind()).isEqualTo(JoinSupport.Kind.LINK);
    final JoinSupport none = supportOf("SELECT 1 FROM person a JOIN person b ON a.city = b.city");
    assertThat(none.kind()).isEqualTo(JoinSupport.Kind.NOTHING);
    assertThat(none.supported()).isFalse();
    assertThat(none.reason()).contains("no index, link or edge pattern");
    assertThat(supportOf("SELECT 1 FROM person a JOIN person b ON a.pid < b.pid").reason()).contains("no equality");
    assertThat(plan("SELECT 1 FROM person a JOIN person b ON a.city = b.city")).contains("not supported:");
  }

  private JoinSupport supportOf(final String sql) {
    return ((JoinSource) planned(sql, BuildMemory.FLOOR_CAP_BYTES).source()).support();
  }

  @Test
  void explainNamesTheStrategyAndTheInputs() {
    try (final ResultSet resultSet = database.query("postgresql",
        "EXPLAIN SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE p.city = 'Delft'")) {
      final List<String> lines = new ArrayList<>();
      while (resultSet.hasNext())
        lines.add(resultSet.next().getProperty("QUERY PLAN"));
      assertThat(lines.get(0)).startsWith("HASH JOIN INNER");
      assertThat(lines).contains("  SCAN TYPE orders AS o (polymorphic)", "  SCAN TYPE person AS p (polymorphic)");
      assertThat(lines).anyMatch(line -> line.startsWith("  FILTER pushed to p"));
    }
  }

  // ---- the memory cap ----

  @Test
  void aJoinOverTheCapStopsWithOutOfMemoryAndNeverAnswersFromPartOfItsInput() {
    final String hash = "SELECT p.name FROM person p LEFT JOIN orders o ON o.pid = p.pid";
    assertThatThrownBy(() -> planned(hash, 100L).execute(database)).isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> {
      assertThat(e.getSqlState()).isEqualTo("53200");
      assertThat(e.getMessage()).contains("out of memory").contains("Hint:");
    });
    final String nested = "SELECT a.name FROM person a JOIN person b ON a.pid < b.pid";
    assertThatThrownBy(() -> planned(nested, 100L).execute(database)).isInstanceOfSatisfying(PostgreSQLEvaluationException.class,
        e -> assertThat(e.getSqlState()).isEqualTo("53200"));
    assertThat(planned(hash, BuildMemory.FLOOR_CAP_BYTES).execute(database).hasNext()).as("the default cap holds the small fixture")
        .isTrue();
  }

  @Test
  void anIndexNestedLoopKeepsNothingSoTheCapDoesNotApply() {
    final PostgreSQLResultSet result = planned("SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE o.oid = 102", 1L)
        .execute(database);
    assertThat(result.next().<String>getProperty("name")).isEqualTo("Bo");
  }

  // ---- names, aliases, star ----

  @Test
  void namesAreResolvedAcrossTheRelationsOfTheJoin() {
    assertThat(state("SELECT name FROM person p JOIN company c ON p.pid = c.cid")).as("two tables have name").isEqualTo("42702");
    assertThat(state("SELECT 1 FROM person p JOIN company c ON name = 'x'")).as("ambiguous in the ON clause").isEqualTo("42702");
    assertThat(state("SELECT 1 FROM person p JOIN company p ON true")).as("one alias twice").isEqualTo("42712");
    assertThat(state("SELECT 1 FROM person JOIN person ON true")).as("one table twice, no alias").isEqualTo("42712");
    assertThat(state("SELECT p.name, c.name FROM person p JOIN company c ON p.pid = c.cid ORDER BY name")).isEqualTo("42702");
    assertThat(state("SELECT 1 FROM person p JOIN orders o ON x.pid = p.pid")).isEqualTo("42P01");
    assertThat(state("SELECT 1 FROM person p JOIN orders o ON o.nosuch = p.pid")).isEqualTo("42703");
    assertThat(state("SELECT 1 FROM person p JOIN orders o ON p.age")).as("ON must be boolean").isEqualTo("42804");
    assertThat(state("SELECT p.name FROM person p JOIN company c ON p.pid = c.cid")).isNull();
  }

  @Test
  void anOnClauseSeesOnlyTheRelationsOfItsOwnJoin() {
    assertThat(state("SELECT 1 FROM person p JOIN orders o ON o.pid = p.pid JOIN company c ON c.cid = o.oid")).isNull();
    assertThat(state("SELECT 1 FROM person p JOIN orders o ON o.pid = c.cid JOIN company c ON c.cid = o.oid")).isEqualTo("42P01");
  }

  @Test
  void selectStarExpandsInTableOrderAndKeepsDuplicateLabels() {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql",
        "SELECT * FROM person p JOIN orders o ON o.pid = p.pid ORDER BY o.oid LIMIT 1")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::name).containsExactly("id", "age", "city", "name", "pid",
          "id", "address", "buyer", "oid", "pid", "placed", "tags", "total");
      final Result first = resultSet.next();
      assertThat(first.getPropertyNames()).as("the row is a map: the second and later duplicate gets a suffix").contains("id", "id_1",
          "pid", "pid_1").hasSize(13);
      assertThat(first.<String>getProperty("name")).isEqualTo("Ada");
      assertThat(first.<Integer>getProperty("oid")).isEqualTo(100);
    }
    assertThat(rows("SELECT o.* FROM person p JOIN orders o ON o.pid = p.pid WHERE p.pid = 2").get(0)).hasSize(8);
  }
  // ---- RIGHT and FULL JOIN (fork issue #57) ----

  @Test
  void aRightJoinKeepsTheRightRowsWithoutAPartnerAndIsTheMirrorOfALeftJoin() {
    final String right = "SELECT p.name, o.oid FROM person p RIGHT JOIN orders o ON o.pid = p.pid ORDER BY o.oid";
    assertThat(rows(right)).containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102), row("Di", 103), row("Di", 104),
        row(null, 105));
    assertThat(plan(right)).contains("HASH JOIN RIGHT").contains("(build p)");
    assertThat(rows("SELECT o.oid, p.name FROM orders o LEFT JOIN person p ON o.pid = p.pid ORDER BY o.oid"))
        .as("the same rows as the mirrored LEFT join").containsExactly(row(100, "Ada"), row(101, "Ada"), row(102, "Bo"), row(103, "Di"),
            row(104, "Di"), row(105, null));
    assertThat(rows("SELECT p.name, o.oid FROM person p RIGHT JOIN orders o ON o.pid = p.pid WHERE p.city = 'Delft' ORDER BY o.oid"))
        .as("a term on the null-supplying side stays above the join").containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102));
    assertThat(count("SELECT count(*) FROM person a RIGHT JOIN company c ON a.pid > c.cid / 10")).as("nested loop").isEqualTo(9);
    assertThat(plan("SELECT count(*) FROM person a RIGHT JOIN company c ON a.pid > c.cid / 10")).contains("NESTED LOOP RIGHT");
    assertThat(rows("SELECT pid, o.oid FROM person p RIGHT JOIN orders o USING (pid) ORDER BY o.oid")).as("the merged column is the right one")
        .containsExactly(row(1, 100), row(1, 101), row(2, 102), row(4, 103), row(4, 104), row(null, 105));
  }

  @Test
  void aFullJoinKeepsTheRowsOfBothSidesThatHaveNoPartner() {
    final String full = "SELECT p.name, o.oid FROM person p FULL JOIN orders o ON o.pid = p.pid ORDER BY p.pid, o.oid";
    assertThat(rows(full)).containsExactly(row("Ada", 100), row("Ada", 101), row("Bo", 102), row("Cy", null), row("Di", 103),
        row("Di", 104), row("Ed", null), row(null, 105));
    assertThat(plan(full)).contains("HASH JOIN FULL");
    assertThat(rows("SELECT p.name, o.oid FROM person p FULL JOIN orders o ON o.pid = p.pid WHERE p.pid IS NULL OR o.oid IS NULL ORDER BY p.pid, o.oid"))
        .containsExactly(row("Cy", null), row("Ed", null), row(null, 105));
    assertThat(count("SELECT count(*) FROM orders a FULL JOIN orders b ON a.pid = b.pid")).as("two NULL keys do not match, both come out")
        .isEqualTo(9 + 2);
    assertThat(count("SELECT count(*) FROM person a FULL JOIN company c ON true")).as("nested loop: every pair").isEqualTo(15);
    assertThat(plan("SELECT count(*) FROM person a FULL JOIN company c ON true")).contains("NESTED LOOP FULL");
    assertThat(state("SELECT count(*) FROM person a FULL JOIN company c ON a.pid > c.cid / 10 + 3")).as("as PostgreSQL: no equality").isEqualTo("0A000");
    assertThat(state("SELECT 1 FROM person a FULL JOIN company c ON a.city = 'Delft'")).isEqualTo("0A000");
    assertThat(count("SELECT count(*) FROM person p FULL JOIN (SELECT cid FROM company WHERE cid < 0) c ON p.pid = c.cid")).isEqualTo(5);
    assertThat(rows("SELECT p.name, o.oid FROM person p FULL JOIN orders o ON o.pid = p.pid AND o.total > 9 ORDER BY p.pid, o.oid").size())
        .as("a residual that fails leaves both rows alone: 3 pairs, 3 persons and 3 orders").isEqualTo(9);
  }

  @Test
  void theMergedColumnOfAFullJoinWithUsingIsTheCoalesceOfBothSides() {
    assertThat(rows("SELECT pid, p.name, oid FROM person p FULL JOIN orders o USING (pid) ORDER BY pid, oid")).containsExactly(
        row(1, "Ada", 100), row(1, "Ada", 101), row(2, "Bo", 102), row(3, "Cy", null), row(4, "Di", 103), row(4, "Di", 104),
        row(5, "Ed", null), row(null, null, 105));
    assertThat(rows("SELECT * FROM (SELECT pid, name FROM person WHERE pid < 4) a FULL JOIN (SELECT pid, oid FROM orders WHERE oid > 102) b USING (pid) ORDER BY pid, oid"))
        .containsExactly(row(1, "Ada", null), row(2, "Bo", null), row(3, "Cy", null), row(4, null, 103), row(4, null, 104),
            row(null, null, 105));
    assertThat(rows("SELECT a.pid, b.pid FROM person a FULL JOIN orders b USING (pid) WHERE oid IS NULL ORDER BY a.pid"))
        .as("each side keeps its own column under its own name").containsExactly(row(3, null), row(5, null));
  }

  @Test
  void aRightOrFullJoinHoldsItsBuildSideUnderTheCapAndTakesNoIndexLookup() {
    for (final String kind : new String[] { "RIGHT", "FULL" }) {
      final String hash = "SELECT count(*) FROM person p " + kind + " JOIN orders o ON o.pid = p.pid";
      assertThatThrownBy(() -> planned(hash, 100L).execute(database).hasNext()).isInstanceOfSatisfying(PostgreSQLEvaluationException.class,
          e -> assertThat(e.getSqlState()).isEqualTo("53200"));
      final String nested = "SELECT count(*) FROM person p " + kind + " JOIN orders o ON " + (kind.equals("FULL") ? "true" : "o.pid < p.pid");
      assertThatThrownBy(() -> planned(nested, 100L).execute(database).hasNext()).isInstanceOfSatisfying(PostgreSQLEvaluationException.class,
          e -> assertThat(e.getSqlState()).isEqualTo("53200"));
      assertThat(plan("SELECT count(*) FROM works_at w " + kind + " JOIN person p ON w.source_id = p.id")).doesNotContain("INDEX NESTED LOOP")
          .doesNotContain("TRAVERSE");
    }
    assertThatThrownBy(() -> rows("SELECT 1 FROM person p FULL JOIN company c")).as("a FULL join needs ON or USING")
        .isInstanceOf(com.arcadedb.query.postgresql.PostgreSQLSyntaxException.class);
  }

  // ---- CROSS JOIN (fork issue #61) ----

  @Test
  void aCrossJoinIsEveryPairAndTheNestedLoopHoldsItsRightSideUnderTheCap() {
    final String all = "SELECT count(*) FROM person CROSS JOIN company";
    assertThat(count(all)).isEqualTo(15);
    assertThat(plan(all)).contains("NESTED LOOP INNER");
    assertThat(count("SELECT count(*) FROM person p CROSS JOIN company c CROSS JOIN works_at w")).isEqualTo(75);
    assertThat(count("SELECT count(*) FROM person p CROSS JOIN company c WHERE p.pid < c.cid")).as("a WHERE term over both sides").isEqualTo(15);
    assertThat(count("SELECT count(*) FROM person p CROSS JOIN company c WHERE p.pid = c.cid / 10")).as("an equality becomes a key").isEqualTo(3);
    assertThat(plan("SELECT count(*) FROM person p CROSS JOIN company c WHERE p.pid = c.cid / 10")).contains("HASH JOIN INNER");
    assertThat(rows("SELECT p.name, c.name FROM person p CROSS JOIN company c WHERE p.pid = 1 AND c.cid > 10 ORDER BY c.cid"))
        .containsExactly(row("Ada", "Birch"), row("Ada", "Cedar"));
    assertThatThrownBy(() -> planned(all, 100L).execute(database).hasNext()).isInstanceOfSatisfying(PostgreSQLEvaluationException.class,
        e -> assertThat(e.getSqlState()).as("the same cap as every other join").isEqualTo("53200"));
    assertThat(planned(all, BuildMemory.configuredCapBytes()).execute(database).hasNext()).isTrue();
  }

  @Test
  void aCrossJoinTakesNeitherOnNorUsingAndNaturalStaysRefused() {
    assertThatThrownBy(() -> rows("SELECT 1 FROM person p CROSS JOIN company c ON p.pid = c.cid"))
        .isInstanceOf(com.arcadedb.query.postgresql.PostgreSQLSyntaxException.class);
    assertThatThrownBy(() -> rows("SELECT 1 FROM person p CROSS JOIN company c USING (pid)"))
        .isInstanceOf(com.arcadedb.query.postgresql.PostgreSQLSyntaxException.class);
    assertThat(state("SELECT 1 FROM person NATURAL JOIN company")).isEqualTo("0A000");
    assertThat(state("SELECT name FROM person CROSS JOIN company")).as("two columns of that name").isEqualTo("42702");
  }
}
