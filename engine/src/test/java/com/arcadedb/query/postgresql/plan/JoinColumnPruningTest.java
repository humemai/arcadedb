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
import com.arcadedb.database.RID;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A table that a join reads reads only the columns that the statement names anywhere (fork issue #90): the answers of joins through
 * an edge table, by traversal and by hash join, with the aggregates, an outer join, a star and a subquery, are those computed here from
 * the rows that were inserted. {@link ColumnNames} is the walk that says which names a statement mentions, and says nothing for a star.
 */
class JoinColumnPruningTest extends TestHelper {
  private static final int N = 30;

  private record Person(long id, String name, int age) {
  }

  private record Knows(int from, int to, int since) {
  }

  private final List<Person> persons = new ArrayList<>();
  private final List<Knows> knows = new ArrayList<>();

  @BeforeEach
  void load() {
    database.getSchema().createVertexType("jp").createProperty("id", Type.LONG);
    database.getSchema().getType("jp").createProperty("name", Type.STRING);
    database.getSchema().getType("jp").createProperty("age", Type.INTEGER);
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "jp", "id");
    database.getSchema().createEdgeType("jknows").createProperty("since", Type.INTEGER);
    database.getSchema().getType("jknows").setCustomValue("source_table", "jp");
    database.getSchema().getType("jknows").setCustomValue("target_table", "jp");
    database.transaction(() -> {
      final List<RID> rids = new ArrayList<>();
      for (int i = 0; i < N; i++) {
        final Person p = new Person(i, "name" + i, 20 + i % 13);
        persons.add(p);
        final MutableVertex v = database.newVertex("jp").set("id", p.id(), "name", p.name(), "age", p.age());
        v.save();
        rids.add(v.getIdentity());
      }
      for (int i = 0; i < N; i++)
        for (int d = 1; d <= i % 4; d++) {
          final Knows k = new Knows(i, (i + d * 3) % N, 2000 + (i + d) % 20);
          knows.add(k);
          database.lookupByRID(rids.get(k.from()), true).asVertex().modify().newEdge("jknows", rids.get(k.to()), "since", k.since());
        }
    });
  }

  private List<String> run(final String text) {
    try (final ResultSet rs = database.query("postgresql", text)) {
      final List<String> out = new ArrayList<>();
      while (rs.hasNext()) {
        final Result r = rs.next();
        out.add(r.getPropertyNames().stream().map(n -> n + "=" + r.getProperty(n)).collect(Collectors.joining(",")));
      }
      return out;
    }
  }

  private static final String FROM_JOIN = " FROM jp p JOIN jknows k ON k.source_id = p.rid JOIN jp q ON k.target_id = q.rid";

  @Test
  void theNeighbourListOfOnePersonIsTheOneOfTheEdges() {
    for (final long start : new long[] { 3, 7, 11, 2 }) {
      final List<String> expected = knows.stream().filter(k -> k.from() == start).sorted((a, b) -> Integer.compare(a.to(), b.to()))
          .map(k -> "qid=" + persons.get(k.to()).id() + ",name=" + persons.get(k.to()).name() + ",since=" + k.since())
          .collect(Collectors.toList());
      assertThat(run("SELECT q.id AS qid, q.name AS name, k.since AS since" + FROM_JOIN + " WHERE p.id = " + start + " ORDER BY qid"))
          .as("start " + start).isEqualTo(expected);
    }
    assertThat(String.join("\n", run("EXPLAIN SELECT q.id AS qid" + FROM_JOIN + " WHERE p.id = 3"))).contains("TRAVERSE");
  }

  @Test
  void aggregatesOverTheWholeJoinFollowTheEdges() {
    assertThat(run("SELECT count(*) AS n, sum(q.age) AS s" + FROM_JOIN))
        .containsExactly("n=" + (long) knows.size() + ",s=" + knows.stream().mapToLong(k -> persons.get(k.to()).age()).sum());
    final List<String> expected = persons.stream().filter(p -> knows.stream().anyMatch(k -> k.from() == p.id()))
        .map(p -> "pid=" + p.id() + ",n=" + knows.stream().filter(k -> k.from() == p.id()).count()).collect(Collectors.toList());
    assertThat(run("SELECT p.id AS pid, count(*) AS n FROM jp p JOIN jknows k ON k.source_id = p.rid GROUP BY p.id ORDER BY pid"))
        .isEqualTo(expected);
  }

  @Test
  void anOuterJoinPadsThePersonsWithoutEdges() {
    final long rows = persons.stream().mapToLong(p -> Math.max(1, knows.stream().filter(k -> k.from() == p.id()).count())).sum();
    assertThat(run("SELECT count(*) AS rows_n, count(k.rid) AS edges_n, sum(q.age) AS s FROM jp p LEFT JOIN jknows k ON k.source_id = p.rid"
        + " LEFT JOIN jp q ON k.target_id = q.rid"))
        .containsExactly("rows_n=" + rows + ",edges_n=" + (long) knows.size() + ",s="
            + knows.stream().mapToLong(k -> persons.get(k.to()).age()).sum());
  }

  @Test
  void aStarAndASubqueryReadWholeRows() {
    final List<String> star = run("SELECT *" + FROM_JOIN + " WHERE p.id = 3");
    assertThat(star).hasSize((int) knows.stream().filter(k -> k.from() == 3).count());
    assertThat(star.get(0)).contains("name3").contains("since=");

    final int age = persons.get(1).age();
    final long expected = knows.stream().filter(k -> k.from() == 3 && persons.get(k.to()).age() == age).count()
        + knows.stream().filter(k -> k.from() == 7 && persons.get(k.to()).age() == age).count();
    assertThat(run("SELECT count(*) AS n" + FROM_JOIN + " WHERE p.id IN (3, 7) AND q.age IN (SELECT age FROM jp WHERE id = 1)"))
        .containsExactly("n=" + expected);
  }

  @Test
  void theWalkNamesTheColumnsOfEveryClauseAndSaysNothingForAStar() {
    final var names = ColumnNames.of(StatementParser.parse(
        "SELECT a, count(b) FROM t JOIN u USING (z) WHERE c > 1 AND d IN (SELECT e FROM v WHERE f = t.g) GROUP BY a HAVING sum(h) > 0 "
            + "ORDER BY i LIMIT 1"));
    assertThat(names).containsExactlyInAnyOrder("a", "b", "z", "c", "d", "e", "f", "g", "h", "i");
    assertThat(ColumnNames.of(StatementParser.parse("SELECT * FROM t"))).isNull();
    assertThat(ColumnNames.of(StatementParser.parse("SELECT a FROM t WHERE x IN (SELECT * FROM u)"))).isNull();
    assertThat(ColumnNames.of(StatementParser.parse("SELECT row_number() OVER (PARTITION BY a ORDER BY b) FROM t")))
        .containsExactlyInAnyOrder("a", "b");
  }
}
