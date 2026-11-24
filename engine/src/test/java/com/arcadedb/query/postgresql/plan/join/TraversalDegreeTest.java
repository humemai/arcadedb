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
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.RelationSource;
import com.arcadedb.query.postgresql.plan.SelectPlan;
import com.arcadedb.query.postgresql.plan.SelectPlanner;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The work of a traversal follows the degree of the vertex (fork issue #8): on a generated graph of 10,000 people, 10 companies
 * and 20,000 {@code works_at} edges, the statement that asks for one person reads the edges of that one vertex. The test counts
 * the edge entries the step took from the adjacency lists ({@link JoinSource#edgesRead()}), not the time. A statement for all
 * people is not a walk (fork issue #52): the hash join reads the edge type once and is cheaper than 10,000 walks.
 */
class TraversalDegreeTest extends TestHelper {
  private static final int PEOPLE    = 10_000;
  private static final int COMPANIES = 10;

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    database.transaction(() -> {
      final List<MutableVertex> companies = new ArrayList<>();
      for (int c = 0; c < COMPANIES; c++)
        companies.add(database.newVertex("company").set("cid", c).set("name", "company" + c).save());
      for (int p = 1; p <= PEOPLE; p++) {
        final MutableVertex person = database.newVertex("person").set("pid", p).set("name", "person" + p).save();
        person.newEdge("works_at", companies.get(p % COMPANIES), "since", 2000 + p % 20);
        person.newEdge("works_at", companies.get((p + 1) % COMPANIES), "since", 2000 + (p + 1) % 20);
      }
    });
  }

  private SelectPlan planned(final String sql) {
    return new SelectPlanner(database, Parameters.NONE).plan((SelectStatement) StatementParser.parse(sql));
  }

  /** The traversal step of a plan: the first join below the top that walks. */
  private static JoinSource walkOf(final RelationSource source) {
    RelationSource current = source;
    while (current instanceof JoinSource join) {
      if (join.strategy() == JoinSource.Strategy.TRAVERSE)
        return join;
      current = join.left();
    }
    throw new AssertionError("no traversal step in the plan");
  }

  private List<List<Object>> drain(final SelectPlan plan) {
    final List<List<Object>> out = new ArrayList<>();
    try (final ResultSet resultSet = plan.execute(database)) {
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> values = new ArrayList<>();
        for (final String name : result.getPropertyNames())
          values.add(result.getProperty(name));
        out.add(values);
      }
    }
    return out;
  }

  @Test
  void aStatementForOnePersonReadsOnlyTheEdgesOfThatVertex() {
    final SelectPlan plan = planned("SELECT p.pid, c.cid, w.since FROM person p JOIN works_at w ON w.source_id = p.id"
        + " JOIN company c ON w.target_id = c.id WHERE p.pid = 1");
    assertThat(plan.steps()).anyMatch(line -> line.contains("FETCH FROM INDEX person[pid]")).anyMatch(line -> line.startsWith("TRAVERSE"));
    final JoinSource walk = walkOf(plan.source());
    final List<List<Object>> rows = drain(plan);
    assertThat(rows).hasSize(2);
    assertThat(walk.edgesRead()).as("the two edges of person 1, of 20,000 in the type").isEqualTo(2);
  }

  @Test
  void aStatementForAllPeopleIsAHashJoinAndTheRuleOffGivesTheSameCount() {
    final String sql = "SELECT count(*) FROM person p JOIN works_at w ON w.source_id = p.id";
    final SelectPlan plan = planned(sql);
    assertThat(plan.steps()).noneMatch(line -> line.contains("TRAVERSE")).anyMatch(line -> line.contains("HASH JOIN"))
        .anyMatch(line -> line.contains("[hash join: no bound start]"));
    assertThat(drain(plan)).containsExactly(List.of(20_000L));

    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, false);
    final SelectPlan control = planned(sql);
    assertThat(control.steps()).noneMatch(line -> line.contains("TRAVERSE")).noneMatch(line -> line.contains("no bound start"));
    assertThat(drain(control)).containsExactly(List.of(20_000L));
  }

  @Test
  void aListOfThreePeopleReadsTheEdgesOfThoseThreeVerticesOnly() {
    final SelectPlan plan = planned("SELECT count(*) FROM person p JOIN works_at w ON w.source_id = p.id WHERE p.pid IN (1, 2, 3)");
    final JoinSource walk = walkOf(plan.source());
    assertThat(drain(plan)).containsExactly(List.of(6L));
    assertThat(walk.edgesRead()).as("the six edges of three vertices, of 20,000 in the type").isEqualTo(6L);
  }

  @Test
  void aLeftJoinFromOnePersonAlsoReadsOnlyThatVerticesEdges() {
    final SelectPlan plan = planned("SELECT p.pid, w.since FROM person p LEFT JOIN works_at w ON w.source_id = p.id WHERE p.pid = 7");
    final JoinSource walk = walkOf(plan.source());
    assertThat(drain(plan)).hasSize(2);
    assertThat(walk.edgesRead()).isEqualTo(2L);
  }
}
