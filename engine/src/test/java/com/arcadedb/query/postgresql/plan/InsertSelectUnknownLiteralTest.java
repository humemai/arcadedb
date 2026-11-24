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
 * {@code INSERT ... SELECT} with an unknown-typed output (a {@code NULL} or a string literal) in the select list (fork issue #80):
 * the column that takes it decides its type, as PostgreSQL resolves it. It was {@code 42804} ("column is of type bigint but
 * expression is of type text") because the select list resolved the literal to text first.
 */
class InsertSelectUnknownLiteralTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private void write(final String sql) {
    database.transaction(() -> {
      try (final ResultSet ignored = database.command("postgresql", sql)) {
        // the count row is not needed
      }
    });
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
    assertThatThrownBy(() -> write(sql)).as(sql).isInstanceOfSatisfying(RuntimeException.class, e -> {
      try {
        assertThat(e.getClass().getMethod("getSqlState").invoke(e)).as(String.valueOf(e.getMessage())).isEqualTo(sqlState);
      } catch (final ReflectiveOperationException noState) {
        throw new AssertionError("no SQLSTATE on " + e, noState);
      }
    });
  }

  @Test
  void aNullLiteralTakesTheTypeOfTheColumn() {
    write("INSERT INTO person (pid, name, age, city) SELECT 50, 'Zed', NULL, NULL FROM company WHERE cid = 10");
    assertThat(rows("SELECT pid, name, age, city FROM person WHERE pid = 50")).containsExactly(row("50", "Zed", "null", "null"));
    write("INSERT INTO orders (oid, pid, total) SELECT 900 + cid, pid, NULL FROM person, company WHERE pid = 1 AND cid = 10");
    assertThat(rows("SELECT oid, pid, total FROM orders WHERE oid = 910")).containsExactly(row("910", "1", "null"));
  }

  @Test
  void aStringLiteralTakesTheTypeOfTheColumn() {
    write("INSERT INTO person (pid, name, age) SELECT 51, 'Y', '42' FROM company WHERE cid = 10");
    assertThat(rows("SELECT pid, name, age FROM person WHERE pid = 51")).containsExactly(row("51", "Y", "42"));
    assertState("INSERT INTO person (pid, name, age) SELECT 52, 'W', 'abc' FROM company WHERE cid = 10", "22P02");
  }

  @Test
  void anExpressionOfAnotherTypeStillNeedsACast() {
    assertState("INSERT INTO person (pid, name, age) SELECT 53, 'V', name FROM person WHERE pid = 1", "42804");
    assertState("INSERT INTO person (pid, name, age) SELECT 53, 'V', 'x'::text FROM company WHERE cid = 10", "42804");
    assertState("INSERT INTO person (pid, name, age) SELECT 53, 'V', coalesce(NULL, 'x') FROM company WHERE cid = 10", "42804");
  }
}
