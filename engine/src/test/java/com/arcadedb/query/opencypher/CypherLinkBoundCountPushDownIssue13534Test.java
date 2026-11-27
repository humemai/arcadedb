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
package com.arcadedb.query.opencypher;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for issue #13534, the count push-down: a {@code COUNT { }} (or a {@code CALL { }} returning {@code count(*)})
 * whose only outer variable holds a link to a vertex counted the edges of that vertex, while a {@code MATCH} of the same
 * pattern, {@code EXISTS { }} and a pattern comprehension find nothing.
 * <p>
 * Cy knows Bo and Dee. Bo's {@code friend} is a link to Dee, who therefore has one incoming edge.
 */
class CypherLinkBoundCountPushDownIssue13534Test {
  private static final String LINK_M = "MATCH (b:person {name: 'Bo'}) WITH b.friend AS m ";
  private static final String BO_DEE = "MATCH (b:person {name: 'Bo'}), (d:person {name: 'Dee'}) ";

  private Database database;

  @BeforeEach
  void setup() {
    database = new DatabaseFactory("./target/databases/cypher13534count").create();
    database.getSchema().createVertexType("person");
    database.getSchema().createEdgeType("knows");
    database.transaction(() -> {
      final RID dee = database.newVertex("person").set("name", "Dee").save().getIdentity();
      database.newVertex("person").set("name", "Bo").set("friend", dee).save();
      database.newVertex("person").set("name", "Cy").save();
      database.command("opencypher",
          "MATCH (c:person {name: 'Cy'}), (x:person) WHERE x.name IN ['Bo', 'Dee'] CREATE (c)-[:knows]->(x)");
    });
  }

  @AfterEach
  void teardown() {
    if (database != null)
      database.drop();
  }

  @Test
  void countSubqueryDoesNotFollowALink() {
    assertThat(values(LINK_M + "RETURN COUNT { MATCH (:person)-[:knows]->(m) } AS v")).containsExactly(0L);
    assertThat(values(LINK_M + "RETURN COUNT { (:person)-[:knows]->(m) } AS v")).containsExactly(0L);
    assertThat(values(LINK_M + "RETURN COUNT { (m)<-[:knows]-() } AS v")).containsExactly(0L);
    assertThat(values(LINK_M + "RETURN COUNT { MATCH (x:person)-[:knows]->(m) } AS v")).containsExactly(0L);
    assertThat(values(LINK_M + "RETURN COUNT { MATCH (m)<-[:knows]-(:person) } AS v")).containsExactly(0L);
  }

  @Test
  void filterOnTheCountOverALinkDropsTheRow() {
    assertThat(values(LINK_M + "WHERE COUNT { MATCH (:person)-[:knows]->(m) } > 0 RETURN 1 AS v")).isEmpty();
  }

  @Test
  void callSubqueryCountingOverALinkCountsNothing() {
    assertThat(values(LINK_M + "CALL (m) { MATCH (x:person)-[:knows]->(m) RETURN count(*) AS v } RETURN v")).containsExactly(0L);
  }

  @Test
  void rowsMixingANodeAndALinkAreCountedOneByOne() {
    final String count = "RETURN COUNT { MATCH (:person)-[:knows]->(m) } AS v";
    assertThat(values(BO_DEE + "UNWIND [d, b.friend] AS m " + count)).containsExactly(1L, 0L);
    assertThat(values(BO_DEE + "UNWIND [b.friend, d] AS m " + count)).containsExactly(0L, 1L);

    final String call = "CALL (m) { MATCH (x:person)-[:knows]->(m) RETURN count(*) AS v } RETURN v";
    assertThat(values(BO_DEE + "UNWIND [d, b.friend] AS m " + call)).containsExactly(1L, 0L);
    assertThat(values(BO_DEE + "UNWIND [b.friend, d] AS m " + call)).containsExactly(0L, 1L);
  }

  @Test
  void countOverABoundNodeIsUnchanged() {
    final String node = "MATCH (m:person {name: 'Dee'}) ";
    assertThat(values(node + "RETURN COUNT { MATCH (:person)-[:knows]->(m) } AS v")).containsExactly(1L);
    assertThat(values(node + "CALL (m) { MATCH (x:person)-[:knows]->(m) RETURN count(*) AS v } RETURN v")).containsExactly(1L);
  }

  private List<Long> values(final String query) {
    final List<Long> values = new ArrayList<>();
    try (final ResultSet rs = database.query("opencypher", query)) {
      while (rs.hasNext())
        values.add(((Number) rs.next().getProperty("v")).longValue());
    }
    return values;
  }
}
