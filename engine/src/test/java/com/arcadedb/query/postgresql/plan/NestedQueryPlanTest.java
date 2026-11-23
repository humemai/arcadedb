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
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.join.BuildMemory;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Queries inside queries on the {@code small} fixture: derived tables, common table expressions and set operations. What they
 * answer is checked against PostgreSQL 18 by the corpus ({@code answers.tsv}, cases {@code c1-...}); this class checks what the
 * corpus cannot: the memory cap of the rows that these steps keep, the plan text, and the column types of a set operation.
 */
class NestedQueryPlanTest extends TestHelper {

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private QueryPlan planned(final String sql, final long cap) {
    return new SelectPlanner(database, Parameters.NONE, cap).planQuery(StatementParser.parse(sql));
  }

  private void assertCapStops(final String sql, final String holder) {
    assertThatThrownBy(() -> planned(sql, 100L).execute(database)).isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> {
      assertThat(e.getSqlState()).isEqualTo("53200");
      assertThat(e.getMessage()).contains("out of memory").contains(holder).contains("Hint:").contains(BuildMemory.CAP_PROPERTY);
    });
    assertThat(planned(sql, BuildMemory.DEFAULT_CAP_BYTES).execute(database).hasNext()).as("the default cap holds the small fixture")
        .isTrue();
  }

  @Test
  void aDerivedTableOverTheCapStopsWithOutOfMemory() {
    assertCapStops("SELECT count(*) FROM (SELECT name FROM person) t", "a derived table");
  }

  @Test
  void aCommonTableExpressionOverTheCapStopsWithOutOfMemory() {
    assertCapStops("WITH t AS (SELECT name FROM person) SELECT count(*) FROM t", "a common table expression");
  }

  @Test
  void aSetOperationOverTheCapStopsWithOutOfMemory() {
    assertCapStops("SELECT name FROM person UNION SELECT name FROM company", "set operation");
    assertCapStops("SELECT name FROM person INTERSECT SELECT name FROM person", "set operation");
    assertCapStops("SELECT name FROM person EXCEPT ALL SELECT name FROM company", "set operation");
  }

  @Test
  void aSubqueryOfInOverTheCapStopsWithOutOfMemory() {
    assertCapStops("SELECT name FROM person WHERE pid IN (SELECT pid FROM orders)", "a subquery");
    assertCapStops("SELECT name FROM person WHERE name NOT IN (SELECT name FROM company)", "a subquery");
  }

  @Test
  void aScalarSubqueryOverTheCapStopsWithOutOfMemory() {
    assertCapStops("SELECT (SELECT max(age) FROM person) AS m", "a subquery");
  }

  @Test
  void aScalarSubqueryHasThePostgreSQLColumnName() {
    assertThat(planned("SELECT (SELECT max(age) FROM person)", BuildMemory.DEFAULT_CAP_BYTES).columns().get(0).name()).isEqualTo("max");
    assertThat(planned("SELECT (SELECT 1 AS x)", BuildMemory.DEFAULT_CAP_BYTES).columns().get(0).name()).isEqualTo("x");
    assertThat(planned("SELECT (SELECT 1)", BuildMemory.DEFAULT_CAP_BYTES).columns().get(0).name()).isEqualTo("?column?");
  }

  @Test
  void aSetOperationInsideADerivedTableChargesTheMemoryOfTheDerivedTable() {
    assertCapStops("SELECT count(*) FROM (SELECT name FROM person UNION ALL SELECT name FROM company) t", "a derived table");
  }

  @Test
  void theColumnsOfASetOperationHaveTheTypesPostgreSQLResolves() {
    assertThat(types("SELECT 1 AS a UNION SELECT 2")).containsExactly("a:int4");
    assertThat(types("SELECT 1 AS a UNION SELECT 2::int8")).containsExactly("a:int8");
    assertThat(types("SELECT pid AS a FROM person UNION ALL SELECT total FROM orders")).containsExactly("a:numeric");
    assertThat(types("SELECT 1 AS a, NULL AS b UNION ALL SELECT 2, 'x'")).containsExactly("a:int4", "b:text");
    assertThat(types("SELECT 1 AS a UNION SELECT '2'")).containsExactly("a:int4");
    // varchar and text convert to each other implicitly, so the type of the left operand stays
    assertThat(types("SELECT name FROM person UNION SELECT 'z'::text")).containsExactly("name:varchar");
    assertThat(types("SELECT 'z'::text AS name UNION SELECT name FROM person")).containsExactly("name:text");
    assertThat(types("SELECT name FROM person UNION SELECT name FROM company")).containsExactly("name:varchar");
    assertThat(types("SELECT placed AS d FROM orders UNION ALL SELECT '2026-01-01'::timestamp")).containsExactly("d:timestamp");
  }

  private List<String> types(final String sql) {
    final List<String> out = new ArrayList<>();
    for (final PostgreSQLResultSet.Column column : planned(sql, BuildMemory.DEFAULT_CAP_BYTES).columns())
      out.add(column.name() + ":" + column.typeName());
    return out;
  }

  @Test
  void theColumnNamesAreThoseOfTheLeftOperand() {
    try (final ResultSet resultSet = database.query("postgresql", "SELECT pid AS a FROM person UNION ALL SELECT oid AS b FROM orders")) {
      final Result first = resultSet.next();
      assertThat(first.getPropertyNames()).containsExactly("a");
    }
  }

  @Test
  void theExplainOfANestedQueryShowsItsParts() {
    final List<String> union = planned("SELECT 1 UNION ALL SELECT 2 ORDER BY 1 LIMIT 1", BuildMemory.DEFAULT_CAP_BYTES).steps();
    assertThat(union).first().isEqualTo("UNION ALL of 2 inputs");
    assertThat(union).contains("SORT 1 key(s)", "LIMIT");

    final List<String> cte = planned("WITH d AS (SELECT pid FROM person) SELECT a.pid FROM d a JOIN d b ON a.pid = b.pid",
        BuildMemory.DEFAULT_CAP_BYTES).steps();
    assertThat(String.join("\n", cte)).contains("SCAN CTE d AS a").contains("SCAN CTE d AS b").contains("HASH JOIN INNER");

    final List<String> derived = planned("SELECT * FROM (SELECT pid FROM person) t", BuildMemory.DEFAULT_CAP_BYTES).steps();
    assertThat(derived).first().isEqualTo("SCAN SUBQUERY t");
  }

  @Test
  void aWithClauseIsPlannedInOrderAndRead() {
    final List<Integer> pids = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql",
        "WITH a AS (SELECT pid FROM person), b AS (SELECT pid FROM a WHERE pid > 3) SELECT pid FROM b ORDER BY pid")) {
      while (resultSet.hasNext())
        pids.add(resultSet.next().<Integer>getProperty("pid"));
    }
    assertThat(pids).containsExactly(4, 5);
  }
}
