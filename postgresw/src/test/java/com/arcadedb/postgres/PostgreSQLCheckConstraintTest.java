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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CHECK} constraints of the {@code postgresql} language against PostgreSQL 18 (fork issue #227), over JDBC: which rows
 * are refused, with which SQLSTATE and which constraint, the errors of {@code CREATE TABLE}, and the catalog.
 */
class PostgreSQLCheckConstraintTest extends PostgreSQLSideBySideTestBase {
  private static final Pattern VIOLATED = Pattern.compile("violates check constraint \"([^\"]*)\"");

  private static final List<String> TABLES = List.of("CREATE EXTENSION IF NOT EXISTS vector",
      "CREATE TABLE p13 (id int PRIMARY KEY, n int CHECK (n > 0))",
      "CREATE TABLE p14 (id int PRIMARY KEY, a int, b int, CHECK (a < b))",
      "CREATE TABLE products (id serial PRIMARY KEY, sku text NOT NULL UNIQUE, name text NOT NULL,"
          + " price numeric NOT NULL CHECK (price >= 0), stock integer NOT NULL DEFAULT 0)",
      // the facts table of the memory store of the agent measure, without its reference
      "CREATE TABLE facts (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, content TEXT NOT NULL,"
          + " confidence REAL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1), source TEXT, embedding vector(3),"
          + " tags TEXT[] DEFAULT '{}', created_at TIMESTAMPTZ DEFAULT now())",
      "CREATE TABLE c (id int PRIMARY KEY, a int CHECK (a > 0), b int, CHECK (a < b), c text CHECK (c IN ('x', 'y'))"
          + " CHECK (length(c) > 0), d int CONSTRAINT named CHECK (d > 0), CHECK (1 = 1), e int CHECK (b > 0),"
          + " f int CHECK (f > 0 OR b IS NULL))",
      "CREATE TABLE times (id int, s timestamptz, e timestamptz, CHECK (s <= e AND e IS NOT NULL),"
          + " d date CHECK (d > '2000-01-01'), j jsonb CHECK (j ->> 'k' <> 'bad'),"
          + " u uuid CHECK (u <> '00000000-0000-0000-0000-000000000000'),"
          + " w text CHECK (coalesce(w, 'ok') LIKE 'o%' AND times.id > 0))",
      "CREATE TABLE g (a int CHECK (10 / a > 0))", "CREATE TABLE person (id bigint PRIMARY KEY)",
      "CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL"
          + " REFERENCES person (id), weight int CHECK (weight > 0))");

  private static final String CHECKED = "('p13', 'p14', 'products', 'facts', 'c', 'times', 'g', 'knows')";

  @Test
  void checkConstraintsAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String table : TABLES)
          both(postgres, arcade, table);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        // PostgreSQL 18 lists each NOT NULL too, which the language does not
        sameValues(postgres, arcade, "SELECT table_name, constraint_name, constraint_type, is_deferrable, enforced FROM"
            + " information_schema.table_constraints WHERE table_name IN " + CHECKED
            + " AND constraint_type = 'CHECK' AND constraint_name NOT LIKE '%not_null' ORDER BY 1, 2");
        sameValues(postgres, arcade, "SELECT table_name, column_name, constraint_name FROM"
            + " information_schema.constraint_column_usage WHERE table_name IN " + CHECKED
            + " AND constraint_name LIKE '%check%' ORDER BY 1, 2, 3");
        sameValues(postgres, arcade, "SELECT count(*) FROM information_schema.key_column_usage WHERE constraint_name"
            + " LIKE '%check%'");

        for (final String write : List.of("INSERT INTO p13 VALUES (1, 1), (2, NULL)",
            "INSERT INTO p14 VALUES (1, 1, 2), (2, NULL, 1), (3, 5, NULL)",
            "INSERT INTO products (sku, name, price) VALUES ('s1', 'one', 0)",
            "INSERT INTO facts (content, confidence) VALUES ('a', 0), ('b', 1), ('c', NULL)",
            "INSERT INTO facts (content) VALUES ('default')", "INSERT INTO c (id, a, b) VALUES (1, 1, 2)",
            "INSERT INTO c (id, a, b) VALUES (1, 3, 4) ON CONFLICT DO NOTHING",
            "INSERT INTO times (id, s, e, d, j, u, w) VALUES (1, '2026-01-01 00:00:00Z', '2026-01-02 00:00:00Z', '2026-01-01', '{}',"
                + " '11111111-1111-1111-1111-111111111111', NULL)",
            "INSERT INTO g VALUES (1), (NULL)", "INSERT INTO person VALUES (1), (2)", "INSERT INTO knows VALUES (1, 2, 3)"))
          both(postgres, arcade, write);

        for (final String violation : List.of("INSERT INTO p13 VALUES (3, 0)", "UPDATE p13 SET n = -n WHERE id = 1",
            "INSERT INTO p13 VALUES (10, 1), (11, 0)", "INSERT INTO p14 VALUES (4, 5, 1)",
            "UPDATE p14 SET b = 0 WHERE id = 1", "INSERT INTO products (sku, name, price) VALUES ('s2', 'two', -1)",
            "INSERT INTO facts (content, confidence) VALUES ('d', 1.5)", "UPDATE facts SET confidence = -0.1",
            "INSERT INTO c (id, a, b) VALUES (2, -1, -2)", "INSERT INTO c (id, d) VALUES (2, -1)",
            "INSERT INTO c (id, c) VALUES (2, '')", "INSERT INTO c (id, b) VALUES (2, -1)",
            "INSERT INTO c (id, b, f) VALUES (2, 1, -1)",
            // a check comes before the conflict, and before a unique key
            "INSERT INTO c (id, a) VALUES (1, -1) ON CONFLICT DO NOTHING", "INSERT INTO c (id, a) VALUES (1, -1)",
            "INSERT INTO c (id, a) VALUES (1, -1) ON CONFLICT (id) DO UPDATE SET a = 5",
            "INSERT INTO c (id, a, b) VALUES (1, 5, 9) ON CONFLICT (id) DO UPDATE SET a = -5",
            "INSERT INTO times (id, s, e) VALUES (2, '2026-01-03 00:00:00Z', '2026-01-02 00:00:00Z')", "INSERT INTO times (id) VALUES (2)",
            "INSERT INTO times (id, e, d) VALUES (2, '2026-01-02 00:00:00Z', '1999-01-01')",
            "INSERT INTO times (id, e, j) VALUES (2, '2026-01-02 00:00:00Z', '{\"k\": \"bad\"}')",
            "INSERT INTO times (id, e, u) VALUES (2, '2026-01-02 00:00:00Z', '00000000000000000000000000000000')",
            "INSERT INTO times (id, e, w) VALUES (0, '2026-01-02 00:00:00Z', 'ok')", "INSERT INTO knows VALUES (2, 1, 0)",
            "UPDATE knows SET weight = -1")) {
          sameError(postgres, arcade, violation, "23514");
          compare(violation + ": the constraint", violated(postgres, violation), violated(arcade, violation));
        }
        sameError(postgres, arcade, "INSERT INTO c (id, a) VALUES (NULL, -1)", "23502");
        sameError(postgres, arcade, "INSERT INTO g VALUES (0)", "22012");
        for (final String table : List.of("p13", "p14", "products", "facts", "c", "times", "g", "knows"))
          sameValues(postgres, arcade, "SELECT count(*) FROM " + table);
        sameValues(postgres, arcade, "SELECT * FROM c ORDER BY id");

        for (final String[] error : new String[][] { { "CREATE TABLE e (a int CHECK (sum(a) > 0))", "42803" },
            { "CREATE TABLE e (a int CHECK (a IN (SELECT 1)))", "0A000" }, { "CREATE TABLE e (a int CHECK (a + 1))", "42804" },
            { "CREATE TABLE e (a int CHECK (nosuch > 0))", "42703" },
            { "CREATE TABLE e (a int CHECK (row_number() OVER () > 0))", "42P20" },
            { "CREATE TABLE e (a int CHECK (xmin::text <> ''))", "42P10" },
            { "CREATE TABLE e (a int CHECK (other.a > 0))", "42P01" },
            { "CREATE TABLE e (a int CHECK (a > 'x'))", "22P02" }, { "CREATE TABLE e (a int CHECK (a || a = 'x'))", "42883" },
            { "CREATE TABLE e (a int CONSTRAINT k CHECK (a > 0), b int CONSTRAINT k CHECK (b > 0))", "42710" } })
          sameError(postgres, arcade, error[0], error[1]);

        // what PostgreSQL accepts and every language cannot keep says what to do
        refused(arcade, "CREATE TABLE e (a timestamptz CHECK (a < now()))", "The function now() in a CHECK");
        refused(arcade, "CREATE TABLE e (a date CHECK (a < CURRENT_DATE))", "CURRENT_DATE in a CHECK");
        refused(arcade, "CREATE TABLE e (a timestamptz CHECK (a > '2000-01-01 00:00:00Z'))", "A timestamptz value in a CHECK");
        refused(arcade, "CREATE TABLE e (a timestamptz, b date, CHECK (a > b))", "A timestamptz value in a CHECK");
        refused(arcade, "CREATE TABLE e (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL"
            + " REFERENCES person (id), CHECK (source_id <> target_id))", "A CHECK that reads an end of a link table");
        refused(arcade, "CREATE TABLE e (a int CHECK (rid IS NOT NULL))", "A CHECK that reads the record id");
        refused(arcade, "CREATE TABLE e (a int CHECK (a > 0) NO INHERIT)", "not supported");
        sameValues(postgres, arcade, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'e'");

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  /** The constraint that the error of a statement names. */
  private static String violated(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
      return "no error";
    } catch (final SQLException e) {
      final Matcher matcher = VIOLATED.matcher(e.getMessage());
      return matcher.find() ? matcher.group(1) : e.getMessage();
    }
  }
}
