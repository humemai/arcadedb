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
package com.arcadedb.postgres;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The array operators {@code @>}, {@code <@}, {@code &&}, {@code =}, and {@code <>}, with arrays that a statement writes, that a
 * column holds, and that a driver binds, against PostgreSQL 18 (fork issue #233).
 */
class PostgreSQLArrayOperatorTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE t (id int PRIMARY KEY, tags text[], nums int[])",
      "INSERT INTO t VALUES (1, ARRAY['red', 'blue'], ARRAY[1, 2]), (2, '{blue}', '{3}'), (3, '{}', NULL), (4, NULL, '{}')");

  private static final List<String> QUERIES = List.of(
      "SELECT id FROM t WHERE tags @> ARRAY['red'] ORDER BY id",
      "SELECT id FROM t WHERE tags @> '{blue,red}' ORDER BY id",
      "SELECT id FROM t WHERE tags <@ ARRAY['blue', 'green'] ORDER BY id",
      "SELECT id FROM t WHERE tags && ARRAY['red', 'green'] ORDER BY id",
      "SELECT id FROM t WHERE ARRAY[1, 3] && nums ORDER BY id",
      "SELECT id FROM t WHERE tags = '{blue}' ORDER BY id",
      "SELECT id FROM t WHERE nums = ARRAY[1, 2] ORDER BY id",
      "SELECT id FROM t WHERE tags <> ARRAY['blue'] ORDER BY id",
      "SELECT id, tags @> '{}' AS a, tags <@ '{}' AS b, tags && '{}' AS c, nums = '{}' AS d,"
          + " nums IS DISTINCT FROM ARRAY[3] AS e, nums IS NOT DISTINCT FROM NULL AS f FROM t ORDER BY id",
      "SELECT ARRAY[1, NULL] @> ARRAY[1] AS a, ARRAY[1, NULL] @> ARRAY[NULL]::int[] AS b,"
          + " ARRAY[1, NULL] = ARRAY[1, NULL] AS c, ARRAY[1, NULL] = ARRAY[1, 2] AS d, ARRAY[NULL]::int[] && ARRAY[NULL]::int[] AS e,"
          + " ARRAY[1, 1] <@ ARRAY[1] AS f, ARRAY[1, 2] = ARRAY[2, 1] AS g, '{1,2}'::int[] @> NULL AS h",
      "SELECT a.id, b.id FROM t a JOIN t b ON a.tags && b.tags AND a.id < b.id ORDER BY a.id, b.id");

  @Test
  void arrayOperatorsAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres()) {
      try (final Connection postgres = postgres(server, ""); final Connection arcade = arcade("")) {
        for (final String statement : SETUP)
          both(postgres, arcade, statement);
        for (final String query : QUERIES)
          same(postgres, arcade, query);
        sameError(postgres, arcade, "SELECT id FROM t WHERE tags @> nums", "42883");
        sameError(postgres, arcade, "SELECT id FROM t WHERE tags = ARRAY[1]", "42883");
        sameError(postgres, arcade, "SELECT id FROM t WHERE id && ARRAY[1]", "42883");
        sameError(postgres, arcade, "SELECT id FROM t WHERE nums @> '{a}'", "22P02");
        refused(arcade, "SELECT id FROM t WHERE nums < ARRAY[2]", "not supported for arrays");
      }
      for (final String parameters : List.of("", "?prepareThreshold=-1"))
        try (final Connection postgres = postgres(server, parameters); final Connection arcade = arcade(parameters)) {
          // the last one binds a text[] against an integer[], which is no operator also when the array has no element
          for (final String query : List.of("SELECT id FROM t WHERE tags @> ? ORDER BY id",
              "SELECT id FROM t WHERE tags && ? ORDER BY id", "SELECT id FROM t WHERE tags = ? ORDER BY id",
              "SELECT id FROM t WHERE nums && ? ORDER BY id"))
            for (final Object[] values : List.of(new Object[] { "blue" }, new Object[] { "red", "blue" }, new Object[0]))
              compare(query + " " + values.length + parameters, bound(postgres, query, values), bound(arcade, query, values));
        }
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private static String bound(final Connection connection, final String query, final Object[] values) {
    try (final PreparedStatement statement = connection.prepareStatement(query)) {
      statement.setArray(1, connection.createArrayOf("text", values));
      try (final ResultSet rows = statement.executeQuery()) {
        return text(rows, false);
      }
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + "\n";
    }
  }
}
