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

import com.arcadedb.database.Database;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constant {@code DEFAULT}s of {@code jsonb} and array columns of the {@code postgresql} language against PostgreSQL 18 (fork
 * issue #224), over JDBC: what a row takes, what the catalog shows, the errors, and what a record of another language takes.
 */
class PostgreSQLColumnDefaultTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> TABLES = List.of(
      // the first table of the memory store of the agent measure
      "CREATE TABLE IF NOT EXISTS entities (id BIGSERIAL PRIMARY KEY, name TEXT NOT NULL UNIQUE, type TEXT NOT NULL,"
          + " metadata JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
          + " updated_at TIMESTAMPTZ NOT NULL DEFAULT now())",
      "CREATE TABLE p08 (id int PRIMARY KEY, meta jsonb DEFAULT '{}')",
      "CREATE TABLE p09 (id int PRIMARY KEY, tags text[] DEFAULT '{}')",
      "CREATE TABLE d1 (id int PRIMARY KEY,"
          + " m jsonb DEFAULT '{\"b\": [1, \"x\", true, null, {\"c\": \"it''s a \\\\ line\"}], \"a\": 1}',"
          + " n jsonb NOT NULL DEFAULT '{ \"name\" : \"Ada\" , \"count\" : -3 }'::jsonb,"
          + " t text[] DEFAULT '{a,\"b c\",\"\",\"NULL\",\"q\\\"uote\"}', i int[] DEFAULT '{1,-2}',"
          + " l bigint[] DEFAULT '{10000000000}', b boolean[] DEFAULT '{t,false}', f double precision[] DEFAULT '{1.5,2}',"
          + " e int[] DEFAULT ARRAY[]::int[], s text[] DEFAULT ARRAY[]::text[], r real[] DEFAULT '{}',"
          + " u numeric[] DEFAULT '{}', d date[] DEFAULT '{}', w timestamp[] DEFAULT '{}'::timestamp[],"
          + " o jsonb DEFAULT NULL)");

  private static final String COLUMNS = "SELECT table_name, column_name, column_default, is_nullable, data_type FROM"
      + " information_schema.columns WHERE table_name IN ('p08', 'p09', 'd1') ORDER BY table_name, ordinal_position";

  @Test
  void jsonbAndArrayDefaultsAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String table : TABLES)
          both(postgres, arcade, table);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        sameValues(postgres, arcade, COLUMNS);

        for (final String write : List.of("INSERT INTO entities (name, type) VALUES ('Alice', 'person'), ('Bob', 'person')",
            "INSERT INTO p08 (id) VALUES (1)", "INSERT INTO p08 (id, meta) VALUES (2, DEFAULT), (3, NULL)",
            "INSERT INTO p09 (id) VALUES (1)", "INSERT INTO p09 (id, tags) VALUES (2, '{x}')",
            "UPDATE p09 SET tags = DEFAULT WHERE id = 2", "INSERT INTO d1 (id) VALUES (1), (2)",
            // a change of one row must not change the default of the next one
            "UPDATE d1 SET m = m || '{\"z\": 1}', t = ARRAY['changed'] WHERE id = 1", "INSERT INTO d1 (id) VALUES (3)"))
          both(postgres, arcade, write);
        for (final String query : List.of("SELECT id, name, metadata FROM entities ORDER BY id",
            "SELECT * FROM p08 ORDER BY id", "SELECT * FROM p09 ORDER BY id", "SELECT * FROM d1 ORDER BY id",
            "SELECT count(*) FROM entities WHERE metadata = '{}'",
            "INSERT INTO p08 (id) VALUES (4) RETURNING id, meta", "INSERT INTO p09 (id) VALUES (3) RETURNING *"))
          sameData(postgres, arcade, query);

        // a record that another language creates takes the defaults too
        final Database database = getServerDatabase(0, getDatabaseName());
        database.transaction(() -> database.command("sql", "INSERT INTO d1 SET id = 100"));
        database.transaction(() -> database.command("opencypher", "CREATE (:d1 {id: 101})"));
        run(postgres, "INSERT INTO d1 (id) VALUES (100), (101)");
        sameData(postgres, arcade, "SELECT * FROM d1 WHERE id >= 100 ORDER BY id");

        sameError(postgres, arcade, "CREATE TABLE e (a jsonb DEFAULT 'not json')", "22P02");
        sameError(postgres, arcade, "CREATE TABLE e (a int[] DEFAULT 'abc')", "22P02");
        sameError(postgres, arcade, "CREATE TABLE e (a int[] DEFAULT '{x}')", "22P02");
        sameError(postgres, arcade, "CREATE TABLE e (a jsonb DEFAULT 5)", "42804");
        sameError(postgres, arcade, "CREATE TABLE e (a jsonb DEFAULT '{}'::text)", "42804");
        sameError(postgres, arcade, "CREATE TABLE e (a jsonb DEFAULT ARRAY[]::text[])", "42804");

        // what the language cannot keep says what to write
        refused(arcade, "CREATE TABLE e (a jsonb DEFAULT '[]')", "write DEFAULT '{}'");
        refused(arcade, "CREATE TABLE e (a jsonb DEFAULT '{\"@type\": \"x\"}')", "A DEFAULT with a JSON key that starts with @");
        refused(arcade, "CREATE TABLE e (a text[] DEFAULT '{a,NULL}')", "A DEFAULT with elements");
        refused(arcade, "CREATE TABLE e (a date[] DEFAULT '{2026-01-01}')", "A DEFAULT with elements");
        refused(arcade, "CREATE TABLE e (a text[] DEFAULT ARRAY['a'])", "A DEFAULT that is not a constant");
        sameValues(postgres, arcade, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'e'");

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  /**
   * The rows with the elements of each array and the value of each {@code jsonb}: the text of both differs between the servers
   * (the wire quotes every text element and writes JSON without spaces), the values may not.
   */
  private void sameData(final Connection postgres, final Connection arcade, final String query) throws SQLException {
    compare(query, data(postgres, query), data(arcade, query));
  }

  private static String data(final Connection connection, final String query) throws SQLException {
    final StringBuilder out = new StringBuilder();
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(query)) {
      final ResultSetMetaData columns = rows.getMetaData();
      while (rows.next()) {
        out.append("    ");
        for (int i = 1; i <= columns.getColumnCount(); i++) {
          out.append(columns.getColumnLabel(i)).append('=');
          final String text = rows.getString(i);
          if (text == null)
            out.append("null");
          else if (columns.getColumnTypeName(i).startsWith("_"))
            out.append(Arrays.toString((Object[]) rows.getArray(i).getArray()));
          else if (columns.getColumnTypeName(i).equals("jsonb"))
            out.append(new JSONObject(text));
          else
            out.append(text);
          out.append('|');
        }
        out.append('\n');
      }
    }
    return out.toString();
  }
}
