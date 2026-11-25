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
package com.arcadedb.query.postgresql;

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.UnknownText;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The columns of a statement before it runs ({@link PostgreSQLQueryEngine#describe}), which the Postgres wire answers a Describe
 * message with (fork issue #12), and the errors of the language as the wire reads them ({@link PostgreSQLErrors}).
 */
class PostgreSQLDescribeTest extends TestHelper {
  private PostgreSQLQueryEngine engine() {
    return (PostgreSQLQueryEngine) ((DatabaseInternal) database).getQueryEngine("postgresql");
  }

  private List<String> describe(final String statement, final PgType... types) {
    return engine().describe(statement, types).stream().map(column -> column.name() + ":" + column.typeName()).toList();
  }

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private List<PgType> parameters(final String statement, final PgType... declared) {
    return engine().describeStatement(statement, declared).parameterTypes();
  }

  @Test
  void aDescribeReportsTheTypeEachParameterTakesFromItsContext() {
    assertThat(parameters("SELECT name FROM person WHERE pid = $1")).containsExactly(PgType.INT4);
    assertThat(parameters("SELECT name FROM person WHERE $2 = city AND age > $1")).containsExactly(PgType.INT4, PgType.TEXT);
    assertThat(parameters("SELECT name FROM person WHERE pid IN ($1, $2)")).containsExactly(PgType.INT4, PgType.INT4);
    assertThat(parameters("SELECT name FROM person WHERE age BETWEEN $1 AND $2")).containsExactly(PgType.INT4, PgType.INT4);
    assertThat(parameters("SELECT name FROM person WHERE name LIKE $1")).containsExactly(PgType.TEXT);
    assertThat(parameters("SELECT name FROM person ORDER BY pid LIMIT $1 OFFSET $2")).containsExactly(PgType.INT8, PgType.INT8);
    assertThat(parameters("SELECT $1::int8")).containsExactly(PgType.INT8);
    assertThat(parameters("SELECT $1")).containsExactly(PgType.TEXT);
    assertThat(parameters("SELECT oid FROM orders WHERE total > $1 AND placed = $2")).containsExactly(PgType.NUMERIC, PgType.DATE);
    assertThat(parameters("SELECT name FROM person WHERE $1 IN (SELECT pid FROM orders)")).containsExactly(PgType.INT4);
    assertThat(parameters("SELECT name FROM person WHERE pid IN (SELECT pid FROM orders WHERE oid = $1)")).containsExactly(PgType.INT4);
    assertThat(parameters("SELECT lag(name, $1) OVER (ORDER BY pid) FROM person")).containsExactly(PgType.INT4);
    assertThat(parameters("SELECT coalesce(age, $1) FROM person")).containsExactly(PgType.INT4);
    assertThat(parameters("SELECT age FROM person UNION SELECT $1")).containsExactly(PgType.INT4);
    assertThat(parameters("EXPLAIN SELECT name FROM person WHERE pid = $1")).containsExactly(PgType.INT4);
    // the first context decides, whatever the number of the parameter
    assertThat(parameters("SELECT name FROM person WHERE $1::int IS NULL OR pid = $1")).containsExactly(PgType.INT4);
  }

  @Test
  void aDescribeOfAWriteTypesAParameterAsItsColumn() {
    assertThat(parameters("INSERT INTO person (pid, name, age, city) VALUES ($1, $2, $3, $4)"))
        .containsExactly(PgType.INT4, PgType.VARCHAR, PgType.INT4, PgType.VARCHAR);
    assertThat(parameters("INSERT INTO person (pid, name) SELECT $1, $2")).containsExactly(PgType.INT4, PgType.VARCHAR);
    assertThat(parameters("UPDATE person SET age = $1 WHERE pid = $2")).containsExactly(PgType.INT4, PgType.INT4);
    assertThat(parameters("UPDATE person SET age = $1 FROM orders WHERE orders.pid = person.pid AND orders.oid = $2"))
        .containsExactly(PgType.INT4, PgType.INT4);
    assertThat(parameters("DELETE FROM person WHERE pid = $1")).containsExactly(PgType.INT4);
    assertThat(parameters("INSERT INTO person (pid, name) VALUES ($1, $2) ON CONFLICT (pid) DO UPDATE SET age = $3"))
        .containsExactly(PgType.INT4, PgType.VARCHAR, PgType.INT4);
  }

  @Test
  void aDeclaredTypeStaysAndAParameterNothingTypesIsUnknown() {
    assertThat(parameters("SELECT name FROM person WHERE pid = $1", PgType.INT8)).containsExactly(PgType.INT8);
    assertThat(parameters("SELECT name FROM person WHERE $1 IS NULL")).containsExactly(PgType.UNKNOWN);
    assertThat(parameters("SELECT name FROM person WHERE pid = $2")).containsExactly(PgType.UNKNOWN, PgType.INT4);
    assertThat(parameters("SELECT name FROM person")).isEmpty();
  }

  @Test
  void theColumnsAreThoseOfARunWithValuesOfTheInferredTypes() {
    // the output uses the parameter, and WHERE or HAVING types it, before or after the select list is planned
    assertThat(describe("SELECT $1 AS p FROM person WHERE pid = $1")).containsExactly("p:int4");
    assertThat(describe("SELECT $1 AS p FROM person GROUP BY city HAVING count(*) > $1")).containsExactly("p:int8");
    try (final ResultSet resultSet = database.query("postgresql", "SELECT $1 AS p FROM person WHERE pid = $1", 2)) {
      assertThat(((PostgreSQLResultSet) resultSet).columns()).extracting(PostgreSQLResultSet.Column::typeName).containsExactly("int4");
    }
  }

  @Test
  void twoContextsThatCannotShareATypeAreRefused() {
    assertThatThrownBy(() -> parameters("SELECT name FROM person WHERE pid = $1 OR name = $1"))
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("42883"));
  }

  @Test
  void aSelectIsDescribedWithTheTypesOfTheView() {
    assertThat(describe("SELECT name, age FROM person WHERE pid = $1")).containsExactly("name:varchar", "age:int4");
    assertThat(describe("SELECT p.name AS who, count(*) FROM person p GROUP BY p.name")).containsExactly("who:varchar", "count:int8");
    assertThat(describe("SELECT 1, 'a'")).containsExactly("?column?:int4", "?column?:text");
  }

  @Test
  void aParameterTakesItsTypeFromTheContextOrFromTheDeclaredType() {
    assertThat(describe("SELECT $1 + 1", PgType.INT8)).containsExactly("?column?:int8");
    assertThat(describe("SELECT name FROM person WHERE age > $1 AND city = $2", PgType.INT4)).containsExactly("name:varchar");
  }

  @Test
  void aDescribeRunsNothing() {
    describe("SELECT name FROM person");
    final List<String> returning = describe("INSERT INTO person (pid, name) VALUES ($1, $2) RETURNING pid, name");
    assertThat(returning).containsExactly("pid:int4", "name:varchar");
    try (final ResultSet resultSet = database.query("postgresql", "SELECT count(*) AS n FROM person")) {
      assertThat(resultSet.next().<Long>getProperty("n")).isEqualTo(5L);
    }
  }

  @Test
  void aWriteWithoutReturningHasNoColumnsAndTheAnswerIsRowless() {
    assertThat(describe("DELETE FROM person WHERE pid = $1")).isEmpty();
    assertThat(describe("BEGIN")).isEmpty();
    assertThat(describe("EXPLAIN SELECT name FROM person")).containsExactly("QUERY PLAN:text");

    try (final ResultSet resultSet = database.command("postgresql", "UPDATE person SET age = 37 WHERE pid = 1")) {
      assertThat((Object) resultSet).isInstanceOf(PostgreSQLResultSet.class);
      assertThat(((PostgreSQLResultSet) resultSet).returnsRows()).isFalse();
      assertThat(((PostgreSQLResultSet) resultSet).affectedRows()).isEqualTo(1L);
    }
    try (final ResultSet resultSet = database.command("postgresql", "UPDATE person SET age = 38 WHERE pid = 1 RETURNING age")) {
      assertThat(((PostgreSQLResultSet) resultSet).returnsRows()).isTrue();
    }
  }

  @Test
  void aParameterOfNoDeclaredTypeIsTypedByItsContextAsAStringLiteralIs() {
    try (final ResultSet resultSet = database.query("postgresql", "SELECT name FROM person WHERE pid = $1", new UnknownText("2"))) {
      assertThat(resultSet.next().<String>getProperty("name")).isEqualTo("Bo");
      assertThat(resultSet.hasNext()).isFalse();
    }
    try (final ResultSet resultSet = database.query("postgresql", "SELECT $1 AS v", new UnknownText("x"))) {
      assertThat(resultSet.next().<String>getProperty("v")).isEqualTo("x");
    }
    // a text parameter is text, which PostgreSQL does not coerce to an integer
    assertThatThrownBy(() -> database.query("postgresql", "SELECT name FROM person WHERE pid = $1", "2"))
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("42883"));
  }

  @Test
  void aDescribeOfAnUnknownNameIsRefusedAsARunIs() {
    assertThatThrownBy(() -> describe("SELECT nosuch FROM person"))
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("42703"));
    assertThatThrownBy(() -> describe("SELECT name FROM nosuch"))
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("42P01"));
  }

  @Test
  void theErrorsCarryTheSqlStateAndThePositionOfThePostgreSQLFields() {
    final String statement = "SELECT name\nFROM person WHERE";
    assertThatThrownBy(() -> describe(statement)).satisfies(e -> {
      assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("42601");
      assertThat(PostgreSQLErrors.message(e)).isEqualTo("syntax error at end of input");
      // the end of the text: PostgreSQL counts characters from 1 and points one past the last
      assertThat(PostgreSQLErrors.position(e, statement)).isEqualTo(statement.length() + 1);
    });
    assertThatThrownBy(() -> describe("SELECT name FROM FROM person")).satisfies(e -> {
      assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("42601");
      assertThat(PostgreSQLErrors.position(e, "SELECT name FROM FROM person")).isEqualTo(18);
    });
    // an error of the evaluation is the run's: describing evaluates nothing
    assertThat(describe("SELECT 1 / 0 FROM person")).containsExactly("?column?:int4");
    assertThatThrownBy(() -> database.query("postgresql", "SELECT 1 / 0 FROM person").next())
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("22012"));
    assertThat(PostgreSQLErrors.sqlState(new IllegalStateException("x"))).isNull();
    assertThat(PostgreSQLErrors.position(new IllegalStateException("x"), "SELECT 1")).isZero();
  }
}
