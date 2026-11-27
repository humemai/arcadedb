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

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The text of an array and of a {@code jsonb} value in an answer of the {@code postgresql} language, against PostgreSQL 18, byte
 * for byte (fork issue #234).
 */
class PostgreSQLAnswerTextTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE v (id int PRIMARY KEY, name text, flag boolean, big bigint, price numeric, score double precision,"
          + " day date, at timestamp, seen timestamptz, raw bytea, meta jsonb, tags text[], flags boolean[], times timestamp[],"
          + " scores double precision[])",
      "INSERT INTO v VALUES (1, 'ann', true, 1, 59.90, 100, DATE '2026-01-02', TIMESTAMP '2026-01-02 10:00:00',"
          + " TIMESTAMPTZ '2026-01-02 10:00:00+00', '\\x00ff', '{\"b\": 1, \"a\": [1, 2], \"aa\": {\"z\": null, \"y\": true}}',"
          + " ARRAY['x', 'two words', ''], ARRAY[true, false], ARRAY[TIMESTAMP '2026-01-02 10:00:00',"
          + " TIMESTAMP '2026-01-02 10:00:00.5'], '{100,0.5,1e20}')",
      "INSERT INTO v VALUES (2, 'two words', false, -2, 5.00, 0.5, DATE '2025-12-31', TIMESTAMP '2025-12-31 23:59:59.000001',"
          + " TIMESTAMPTZ '2026-07-01 12:30:00.25+02', '\\x', '{\"é\": \"a\\\"b\", \"z\": 2.5}', ARRAY['null', 'NULL', 'a,b'],"
          + " '{}', '{}', '{}')",
      "INSERT INTO v VALUES (3, '', NULL, NULL, NULL, 1e20, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
      "INSERT INTO v (id, name, meta, tags) VALUES (4, 'a{b}', '{}', ARRAY['{', '}', 'a;b', '''', 'ü'])",
      "INSERT INTO v (id, name) VALUES (5, 'say \"hi\"')", "INSERT INTO v (id, name) VALUES (6, 'back\\slash')",
      "INSERT INTO v (id, name) VALUES (7, E'tab\\there')", "INSERT INTO v (id, name) VALUES (8, E'two\\nlines')",
      "INSERT INTO v (id, name) VALUES (9, 'NuLl')", "INSERT INTO v (id, name) VALUES (10, NULL)");

  private static final List<String> QUERIES = List.of(
      "SELECT array_agg(name ORDER BY id) AS a FROM v",
      "SELECT array_agg(flag ORDER BY id) AS a, array_agg(big ORDER BY id) AS b, array_agg(price ORDER BY id) AS c,"
          + " array_agg(score ORDER BY id) AS d FROM v",
      "SELECT array_agg(day ORDER BY id) AS a, array_agg(at ORDER BY id) AS b, array_agg(seen ORDER BY id) AS c FROM v",
      "SELECT array_agg(raw ORDER BY id) AS a FROM v WHERE id < 4",
      "SELECT tags, flags, times, scores FROM v ORDER BY id",
      "SELECT ARRAY['a b', NULL, 'c'] AS a, ARRAY[true, NULL] AS b, ARRAY[1, 2] AS c",
      "SELECT id, (SELECT array_agg(o.name ORDER BY o.id) FROM v o WHERE o.id <= v.id) AS names FROM v ORDER BY id",
      "SELECT meta FROM v ORDER BY id",
      "SELECT meta, meta -> 'a' AS a, meta -> 'aa' AS b, meta -> 'z' AS c FROM v ORDER BY id",
      "SELECT jsonb_agg(meta ORDER BY id) AS a, jsonb_agg(name ORDER BY id) AS b FROM v",
      "SELECT jsonb_build_object('name', name, 'tags', tags, 'id', id) AS a FROM v ORDER BY id",
      "SELECT '\"text\"'::jsonb AS a, '1.5'::jsonb AS b, 'null'::jsonb AS c, '[]'::jsonb AS d, '{\"bb\":1,\"a\":2}'::jsonb AS e");

  @Test
  void arraysAndJsonbValuesHaveTheTextOfPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres();
        final Connection postgres = postgres(container, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SETUP)
        both(postgres, arcade, statement);
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
      for (final String query : QUERIES)
        same(postgres, arcade, query);

      // the rows that a write returns
      same(postgres, arcade, "INSERT INTO v (id, meta, tags, flags) VALUES (11, '{\"b\":2,\"a\":1}', ARRAY['p q'], ARRAY[false])"
          + " RETURNING meta, tags, flags");

      // the zone of the session in an array of instants
      both(postgres, arcade, "SET TIME ZONE 'Europe/Amsterdam'");
      same(postgres, arcade, "SELECT array_agg(seen ORDER BY id) AS a FROM v");
      both(postgres, arcade, "SET TIME ZONE 'UTC'");

      // a driver reads the same elements, from the text and, once the statement is prepared on the server, from the binary format
      final String read = "SELECT tags, flags, meta FROM v WHERE id = ?";
      assertThat(prepared(arcade, read)).isEqualTo(prepared(postgres, read));
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private static String prepared(final Connection connection, final String query) throws SQLException {
    final StringBuilder out = new StringBuilder();
    try (final PreparedStatement statement = connection.prepareStatement(query)) {
      // more runs than the five after which the driver prepares the statement on the server
      for (int run = 0; run < 8; run++) {
        statement.setInt(1, 1 + run % 2);
        try (final ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            for (int i = 1; i <= 2; i++) {
              final Array array = rows.getArray(i);
              out.append(array == null ? null : List.of((Object[]) array.getArray())).append('|');
            }
            out.append(rows.getString(3)).append('\n');
          }
        }
      }
    }
    return out.toString();
  }
}
