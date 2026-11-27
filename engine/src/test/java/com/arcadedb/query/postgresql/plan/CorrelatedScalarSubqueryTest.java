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
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A scalar subquery and an {@code IN} subquery that read columns of the query around them (fork issue #78), on the {@code small}
 * fixture: in the select list, in {@code WHERE}, in {@code SET}, with an equality, with another comparison, with an outer column in
 * the select list of the subquery, and two levels deep. They were {@code 42P01} before. The answers are compared with PostgreSQL 18
 * by the answer corpus.
 */
class CorrelatedScalarSubqueryTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

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
  void aCountPerOuterRowInTheSelectList() {
    assertThat(rows("SELECT p.name, (SELECT count(*) FROM orders o WHERE o.pid = p.pid) AS n FROM person p ORDER BY p.pid"))
        .containsExactly(row("Ada", "2"), row("Bo", "1"), row("Cy", "0"), row("Di", "2"), row("Ed", "0"));
    // the edge table of the fixture, by the record id of the vertex: the shape agents write for a degree
    assertThat(rows("SELECT p.name, (SELECT count(*) FROM works_at w WHERE w.source_id = p.rid) AS jobs FROM person p ORDER BY p.pid"))
        .containsExactly(row("Ada", "2"), row("Bo", "1"), row("Cy", "1"), row("Di", "1"), row("Ed", "0"));
  }

  @Test
  void aCorrelatedSubqueryInWhere() {
    assertThat(rows("SELECT p.name FROM person p WHERE (SELECT count(*) FROM orders o WHERE o.pid = p.pid) >= 2 ORDER BY p.pid"))
        .containsExactly(row("Ada"), row("Di"));
    assertThat(rows("SELECT p.name FROM person p WHERE p.pid IN (SELECT o.pid FROM orders o WHERE o.total > p.age) ORDER BY p.pid"))
        .containsExactly(row("Bo"));
    assertThat(rows("SELECT p.name FROM person p WHERE p.pid NOT IN (SELECT o.pid FROM orders o WHERE o.total > p.age AND o.pid IS NOT NULL) ORDER BY p.pid"))
        .containsExactly(row("Ada"), row("Cy"), row("Di"), row("Ed"));
  }

  @Test
  void aComparisonThatIsNotAnEquality() {
    assertThat(rows("SELECT p.pid, (SELECT count(*) FROM person q WHERE q.age > p.age) AS older FROM person p ORDER BY p.pid"))
        .containsExactly(row("1", "2"), row("2", "1"), row("3", "0"), row("4", "3"), row("5", "0"));
    assertThat(rows("SELECT o.oid, (SELECT sum(x.oid) FROM orders x WHERE x.oid <= o.oid) AS running FROM orders o ORDER BY o.oid"))
        .containsExactly(row("100", "100"), row("101", "201"), row("102", "303"), row("103", "406"), row("104", "510"),
            row("105", "615"));
  }

  @Test
  void aSubqueryWithoutAggregateGivesTheRowOrNullAndTwoRowsAreAnError() {
    assertThat(rows("SELECT p.name, (SELECT o.oid FROM orders o WHERE o.pid = p.pid ORDER BY o.oid DESC LIMIT 1) AS last FROM person p ORDER BY p.pid"))
        .containsExactly(row("Ada", "101"), row("Bo", "102"), row("Cy", "null"), row("Di", "104"), row("Ed", "null"));
    assertThat(rows("SELECT p.name, (SELECT o.oid FROM orders o WHERE o.pid = p.pid AND o.total > 90) FROM person p ORDER BY p.pid"))
        .containsExactly(row("Ada", "null"), row("Bo", "102"), row("Cy", "null"), row("Di", "null"), row("Ed", "null"));
    assertState("SELECT (SELECT o.oid FROM orders o WHERE o.pid = p.pid) FROM person p", "21000");
  }

  @Test
  void anOuterColumnInTheSelectListOfTheSubquery() {
    assertThat(rows("SELECT p.pid, (SELECT p.age + count(*) FROM orders o WHERE o.pid = p.pid) AS x FROM person p ORDER BY p.pid"))
        .containsExactly(row("1", "38"), row("2", "42"), row("3", "null"), row("4", "31"), row("5", "52"));
  }

  @Test
  void theInnermostScopeWinsAndAnOuterOnlyNameIsFound() {
    // pid is a column of orders: the unqualified name is the subquery's own, so every order with a pid is counted for every person
    assertThat(rows("SELECT p.name, (SELECT count(*) FROM orders o WHERE o.pid = pid) AS n FROM person p ORDER BY p.pid"))
        .containsExactly(row("Ada", "5"), row("Bo", "5"), row("Cy", "5"), row("Di", "5"), row("Ed", "5"));
    // cid is a column of company only
    assertThat(rows("SELECT c.name, (SELECT count(*) FROM person p WHERE p.pid * 10 = cid) AS n FROM company c ORDER BY c.cid"))
        .containsExactly(row("Acme", "1"), row("Birch", "1"), row("Cedar", "1"));
  }

  @Test
  void twoLevelsOfCorrelation() {
    assertThat(rows("SELECT p.pid, (SELECT count(*) FROM orders o WHERE o.pid = p.pid AND o.total > "
        + "(SELECT avg(o2.total) FROM orders o2 WHERE o2.pid = p.pid)) AS above FROM person p ORDER BY p.pid"))
        .containsExactly(row("1", "1"), row("2", "0"), row("3", "0"), row("4", "1"), row("5", "0"));
  }

  @Test
  void aCorrelatedSubqueryInSet() {
    database.transaction(
        () -> database.command("postgresql", "UPDATE person p SET age = (SELECT count(*) FROM orders o WHERE o.pid = p.pid)"));
    assertThat(rows("SELECT pid, age FROM person ORDER BY pid"))
        .containsExactly(row("1", "2"), row("2", "1"), row("3", "0"), row("4", "2"), row("5", "0"));
  }

  @Test
  void aReferenceThatNobodyHasStaysAnError() {
    assertState("SELECT (SELECT count(*) FROM orders o WHERE o.pid = zz.pid) FROM person p", "42P01");
    assertState("SELECT (SELECT count(*) FROM orders o WHERE o.pid = p.nope) FROM person p", "42703");
    assertState("SELECT (SELECT count(*) FROM orders o WHERE o.nope = 1) FROM person p", "42703");
    assertState("SELECT (SELECT o.oid, o.pid FROM orders o WHERE o.pid = p.pid) FROM person p", "42601");
  }

  @Test
  void anAggregateOverOuterColumnsOnlyIsRefusedNotComputedInside() {
    assertState("SELECT (SELECT max(p.pid)) FROM person p", "0A000");
    assertState("SELECT (SELECT 1 HAVING max(p.pid) > 0) FROM person p", "0A000");
    // in WHERE the subquery rejects the aggregate before it knows whose columns it reads, as it does for an aggregate of its own
    assertState("SELECT (SELECT count(*) FROM orders o WHERE o.pid = max(p.pid)) FROM person p", "42803");
    // an aggregate that reads a column of the subquery too belongs to the subquery, as in PostgreSQL
    assertThat(rows("SELECT p.pid, (SELECT max(o.oid + p.pid) FROM orders o WHERE o.pid = p.pid) FROM person p ORDER BY p.pid"))
        .containsExactly(row("1", "102"), row("2", "104"), row("3", "null"), row("4", "108"), row("5", "null"));
  }
}
