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
      assertThat(resultSet).isInstanceOf(PostgreSQLResultSet.class);
      assertThat(((PostgreSQLResultSet) resultSet).returnsRows()).isFalse();
      assertThat(((PostgreSQLResultSet) resultSet).affectedRows()).isEqualTo(1L);
    }
    try (final ResultSet resultSet = database.command("postgresql", "UPDATE person SET age = 38 WHERE pid = 1 RETURNING age")) {
      assertThat(((PostgreSQLResultSet) resultSet).returnsRows()).isTrue();
    }
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
    assertThatThrownBy(() -> describe("SELECT 1 / 0 FROM person"))
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).isNull());
    assertThat(PostgreSQLErrors.sqlState(new IllegalStateException("x"))).isNull();
    assertThat(PostgreSQLErrors.position(new IllegalStateException("x"), "SELECT 1")).isZero();
  }
}
