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
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scan of one table reads only the columns that the statement's expressions read, and applies the {@code WHERE} clause to a row
 * that holds only its own columns, reading the rest for the records that pass (fork issue #85). The answers are those of a scan that
 * reads every column: they are computed here from the rows that were inserted, with {@code NULL}s in two columns, and a statement
 * with a subquery, which keeps reading the whole row, is among them. The plan text does not change.
 */
class ScanPruningTest extends TestHelper {
  private static final int N = 200;

  private record Row(long id, String name, Integer age, String city, long created) {
  }

  private final List<Row> rows = new ArrayList<>();

  @BeforeEach
  void load() {
    database.getSchema().createVertexType("pr").createProperty("id", Type.LONG);
    database.getSchema().getType("pr").createProperty("name", Type.STRING);
    database.getSchema().getType("pr").createProperty("age", Type.INTEGER);
    database.getSchema().getType("pr").createProperty("city", Type.STRING);
    database.getSchema().getType("pr").createProperty("created", Type.LONG);
    database.transaction(() -> {
      for (int i = 0; i < N; i++) {
        final Row row = new Row(i, "n" + i, i % 7 == 0 ? null : 20 + i % 40, i % 11 == 0 ? null : "city_" + i % 5, 1_000L + i * 3);
        rows.add(row);
        database.newVertex("pr").set("id", row.id(), "name", row.name(), "created", row.created())
            .set("age", row.age()).set("city", row.city()).save();
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

  private List<String> expected(final Predicate<Row> where, final Comparator<Row> order, final java.util.function.Function<Row, String> shape) {
    return rows.stream().filter(where).sorted(order).map(shape).collect(Collectors.toList());
  }

  private static final Comparator<Row> BY_ID = Comparator.comparingLong(Row::id);

  @Test
  void aFilterOnOneColumnReturnsTheRowsOfTheOthers() {
    assertThat(run("SELECT id, name FROM pr WHERE city = 'city_3' ORDER BY id"))
        .isEqualTo(expected(r -> "city_3".equals(r.city()), BY_ID, r -> "id=" + r.id() + ",name=" + r.name()));
    assertThat(run("SELECT id FROM pr WHERE age > 30 AND city IS NOT NULL ORDER BY id LIMIT 5"))
        .isEqualTo(expected(r -> r.age() != null && r.age() > 30 && r.city() != null, BY_ID, r -> "id=" + r.id()).subList(0, 5));
  }

  @Test
  void nullsFollowTheThreeValuedLogicOfTheWhereClause() {
    assertThat(run("SELECT id FROM pr WHERE NOT (age < 30) ORDER BY id"))
        .isEqualTo(expected(r -> r.age() != null && !(r.age() < 30), BY_ID, r -> "id=" + r.id()));
    assertThat(run("SELECT id FROM pr WHERE age IS NULL OR city = 'city_1' ORDER BY id"))
        .isEqualTo(expected(r -> r.age() == null || "city_1".equals(r.city()), BY_ID, r -> "id=" + r.id()));
    assertThat(run("SELECT id FROM pr WHERE lower(city) = 'city_3' OR name LIKE 'n1%' ORDER BY id"))
        .isEqualTo(expected(r -> "city_3".equals(r.city()) || r.name().startsWith("n1"), BY_ID, r -> "id=" + r.id()));
  }

  @Test
  void aStatementThatReadsNoColumnStillCounts() {
    assertThat(run("SELECT count(*) AS n FROM pr")).containsExactly("n=" + N);
    assertThat(run("SELECT count(*) AS n FROM pr WHERE city = 'city_2'"))
        .containsExactly("n=" + rows.stream().filter(r -> "city_2".equals(r.city())).count());
  }

  @Test
  void aGroupingReadsTheColumnsOfItsExpressions() {
    final List<String> expected = rows.stream().collect(Collectors.groupingBy(r -> Objects.toString(r.age()))).entrySet().stream()
        .map(e -> "age=" + (e.getKey().equals("null") ? null : Integer.valueOf(e.getKey())) + ",n=" + (long) e.getValue().size() + ",s="
            + e.getValue().stream().mapToLong(Row::created).sum())
        .sorted().collect(Collectors.toList());
    assertThat(run("SELECT age, count(*) AS n, sum(created) AS s FROM pr GROUP BY age").stream().sorted().collect(Collectors.toList()))
        .isEqualTo(expected);
  }

  @Test
  void aStarAndADistinctReadEveryColumnTheyNeed() {
    final List<String> star = run("SELECT * FROM pr WHERE id = 5");
    assertThat(star).hasSize(1);
    assertThat(star.get(0)).contains("id=5").contains("name=n5").contains("age=25").contains("city=city_0").contains("created=1015");
    assertThat(run("SELECT DISTINCT city FROM pr WHERE age IS NOT NULL").stream().sorted().collect(Collectors.toList()))
        .isEqualTo(rows.stream().filter(r -> r.age() != null).map(r -> "city=" + r.city()).distinct().sorted().collect(Collectors.toList()));
  }

  @Test
  void aSubqueryKeepsReadingTheWholeRow() {
    final List<Integer> ages = rows.stream().filter(r -> "city_3".equals(r.city()) && r.age() != null).map(Row::age).distinct().toList();
    assertThat(run("SELECT id FROM pr WHERE age IN (SELECT age FROM pr WHERE city = 'city_3') ORDER BY id"))
        .isEqualTo(expected(r -> r.age() != null && ages.contains(r.age()), BY_ID, r -> "id=" + r.id()));
    assertThat(run("SELECT id FROM pr p WHERE EXISTS (SELECT 1 FROM pr q WHERE q.id = p.id + 1 AND q.city = 'city_4') ORDER BY id"))
        .isEqualTo(expected(r -> rows.stream().anyMatch(q -> q.id() == r.id() + 1 && "city_4".equals(q.city())), BY_ID, r -> "id=" + r.id()));
  }

  @Test
  void theScanOfAFilterReadsTheFilterColumnOfEveryRecordAndTheRestOfThoseThatPass() {
    final String text = "SELECT id FROM pr WHERE city = 'city_3'";
    final long passed = rows.stream().filter(r -> "city_3".equals(r.city())).count();

    assertThat(columnsRead(text)).isEqualTo(N + passed);
    // no filter: the one column the select list names
    assertThat(columnsRead("SELECT age FROM pr")).isEqualTo(N);
    // a count reads no column at all
    assertThat(columnsRead("SELECT count(*) AS n FROM pr")).isZero();
    // a subquery keeps the scan from filtering first, and it reads the columns that the statement names anywhere: id and age
    assertThat(columnsRead("SELECT id FROM pr WHERE age IN (SELECT 1)")).isEqualTo(2L * N);
  }

  @Test
  void thePlanTextIsTheOneOfTheStatement() {
    assertThat(run("EXPLAIN SELECT id FROM pr WHERE city = 'city_3'")).anyMatch(l -> l.contains("SCAN TYPE pr")).anyMatch(l -> l.contains("FILTER WHERE"))
        .anyMatch(l -> l.contains("PROJECT 1 column(s)"));
  }

  private long columnsRead(final String text) {
    final Statement statement = StatementParser.parse(text);
    final SelectPlan plan = (SelectPlan) new SelectPlanner(database, Parameters.of(new Object[0])).planQuery(statement);
    plan.execute(database);
    return ((TableSource) plan.source()).columnsRead();
  }
}
