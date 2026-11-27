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
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan text of a statement is built when it is read, never to run the statement (fork issue #13: it is planned on every call).
 * What is read is the same text: the plan of a result set, read after its rows, and the rows of {@code EXPLAIN}.
 */
class PlanLinesTest extends TestHelper {

  private static final String HOP = "SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.rid"
      + " JOIN company c ON w.target_id = c.rid";

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  @Test
  void aResultSetBuildsItsPlanLinesWhenTheyAreFirstReadAndOnce() {
    final AtomicInteger built = new AtomicInteger();
    final PostgreSQLResultSet.Plan plan = new PostgreSQLResultSet.Plan(() -> {
      built.incrementAndGet();
      return List.of("SCAN TYPE person (polymorphic)", "PROJECT 1 column(s)");
    });
    assertThat(built).hasValue(0);
    assertThat(plan.lines()).containsExactly("SCAN TYPE person (polymorphic)", "PROJECT 1 column(s)");
    assertThat(plan.prettyPrint(0, 2)).isEqualTo("+ SCAN TYPE person (polymorphic)\n+ PROJECT 1 column(s)");
    assertThat(plan.getSteps()).hasSize(2);
    assertThat(built).hasValue(1);
  }

  @Test
  void theLinesOfAHopFromABoundStart() {
    assertThat(explain(HOP + " WHERE p.pid = 1")).containsExactly(
        "TRAVERSE TO c INNER ON (w.target_id = c.rid) (read c by rid from the edge) = MATCH (p:person)-[w:works_at]->(c:company)"
            + " [supported by edge: works_at.target_id = company.rid joins an edge endpoint to an id]"
            + " [traversal: start bound by p.pid = 1]",
        "  TRAVERSE INNER ON (w.source_id = p.rid) (out works_at AS w FROM p) = MATCH (p:person)-[w:works_at]->()"
            + " [supported by edge: person.rid = works_at.source_id joins an edge endpoint to an id]"
            + " [traversal: start bound by p.pid = 1]",
        "    FETCH FROM INDEX person[pid] (pid = key)",
        "    FILTER pushed to p: (p.pid = 1)",
        "    WALK out EDGES works_at AS w of each left row",
        "  READ RECORD BY ID company AS c (rid = key) for each left row",
        "PROJECT 3 column(s)");
  }

  @Test
  void thePlanOfAResultSetReadAfterItsRowsIsThatOfExplain() {
    final String[] statements = { HOP, HOP + " WHERE p.pid IN (1, 5) ORDER BY w.since LIMIT 2",
        "SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE o.oid = 102",
        "SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE p.city = 'Delft'",
        "SELECT a.name FROM person a JOIN person b ON a.pid < b.pid",
        "SELECT p.name, o.oid FROM person p FULL JOIN orders o ON o.pid = p.pid",
        "SELECT t.pid, o.oid FROM (SELECT pid FROM person WHERE pid < 3) t JOIN orders o ON o.pid = t.pid",
        "SELECT DISTINCT city FROM person WHERE age > 1 ORDER BY city OFFSET 1",
        "SELECT city, count(*) FROM person GROUP BY city HAVING count(*) > 0" };
    for (final String sql : statements) {
      try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql)) {
        while (resultSet.hasNext())
          resultSet.next();
        final PostgreSQLResultSet.Plan plan = (PostgreSQLResultSet.Plan) resultSet.getExecutionPlan().orElseThrow();
        assertThat(plan.lines()).as(sql).isNotEmpty().containsExactlyElementsOf(explain(sql));
      }
    }
  }

  private List<String> explain(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", "EXPLAIN " + sql)) {
      final List<String> lines = new ArrayList<>();
      while (resultSet.hasNext())
        lines.add(resultSet.next().getProperty("QUERY PLAN"));
      return lines;
    }
  }
}
