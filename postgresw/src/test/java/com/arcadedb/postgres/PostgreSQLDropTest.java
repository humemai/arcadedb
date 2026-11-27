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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DROP TABLE} and {@code DROP INDEX} on the {@code postgresql} language against PostgreSQL 18 (fork issue #230), over
 * JDBC: what is left afterwards, the errors, what a refused statement leaves, and what {@code sql} and openCypher see.
 */
class PostgreSQLDropTest extends PostgreSQLSideBySideTestBase {

  @Test
  void dropIndexAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String setup : List.of("CREATE TABLE emp (id int PRIMARY KEY, name text, code text UNIQUE)",
            "CREATE TABLE target (id int PRIMARY KEY, code text)", "CREATE UNIQUE INDEX target_code_u ON target (code)",
            "CREATE TABLE child (id int PRIMARY KEY, c text REFERENCES target (code))", "CREATE INDEX emp_name_a ON emp (name)",
            "CREATE INDEX emp_name_b ON emp (name)", "CREATE INDEX ON emp (name, code)",
            "CREATE INDEX child_c_idx ON child (c)", "INSERT INTO emp VALUES (1, 'a', 'x'), (2, 'b', 'y')",
            "INSERT INTO target VALUES (1, 'one')", "INSERT INTO child VALUES (1, 'one')"))
          both(postgres, arcade, setup);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        for (final String[] error : new String[][] { { "DROP INDEX emp_pkey", "2BP01" }, { "DROP INDEX emp_code_key", "2BP01" },
            { "DROP INDEX nowhere", "42704" }, { "DROP INDEX emp", "42809" }, { "DROP INDEX emp_name_a, nowhere", "42704" },
            { "DROP INDEX target_code_u", "2BP01" }, { "DROP INDEX target_code_u RESTRICT", "2BP01" },
            { "DROP TABLE emp_name_a", "42809" }, { "DROP TABLE emp_pkey", "42809" }, { "DROP INDEX emp_name_a,", "42601" } })
          sameError(postgres, arcade, error[0], error[1]);

        // the index of the foreign key stays for the key; the name goes, and the key still holds
        for (final String change : List.of("DROP INDEX emp_name_a", "DROP INDEX IF EXISTS emp_name_a",
            "DROP INDEX public.emp_name_b", "DROP INDEX emp_name_code_idx", "DROP INDEX child_c_idx",
            "CREATE INDEX emp_name_a ON emp (name)", "CREATE UNIQUE INDEX emp_id_name ON emp (id, name)",
            "DROP INDEX emp_name_a, emp_id_name", "DROP INDEX IF EXISTS nowhere"))
          both(postgres, arcade, change);
        sameError(postgres, arcade, "DROP INDEX emp_name_a", "42704");
        sameError(postgres, arcade, "INSERT INTO child VALUES (2, 'two')", "23503");

        // without the unique index a key twice goes in
        both(postgres, arcade, "CREATE UNIQUE INDEX emp_name_u ON emp (name)");
        sameError(postgres, arcade, "INSERT INTO emp VALUES (3, 'a', 'z')", "23505");
        both(postgres, arcade, "DROP INDEX emp_name_u");
        both(postgres, arcade, "INSERT INTO emp VALUES (3, 'a', 'z')");
        // CASCADE takes the foreign key that needs the index
        both(postgres, arcade, "DROP INDEX target_code_u CASCADE");
        both(postgres, arcade, "INSERT INTO child VALUES (2, 'two')");
        both(postgres, arcade, "INSERT INTO target VALUES (2, 'one')");
        sameValues(postgres, arcade, "SELECT * FROM emp ORDER BY id");
        sameValues(postgres, arcade, "SELECT * FROM child ORDER BY id");
        sameValues(postgres, arcade, "SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = 'child'"
            + " AND constraint_type = 'FOREIGN KEY'");

        // a transaction block: PostgreSQL drops there, the language says what to write
        arcade.setAutoCommit(false);
        refused(arcade, "DROP INDEX emp_pkey", "autocommit");
        arcade.rollback();
        arcade.setAutoCommit(true);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  @Test
  void dropTableAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String setup : List.of("CREATE TABLE person (id int PRIMARY KEY, name text)",
            "CREATE TABLE knows (source_id int NOT NULL REFERENCES person (id), target_id int NOT NULL REFERENCES person (id))",
            "CREATE TABLE dept (id int PRIMARY KEY)", "CREATE TABLE emp (id serial PRIMARY KEY, dept int REFERENCES dept)",
            "CREATE TABLE audit (id int PRIMARY KEY, dept int REFERENCES dept ON DELETE CASCADE)",
            "CREATE TABLE tree (id int PRIMARY KEY, up int REFERENCES tree)", "CREATE TABLE other (id int PRIMARY KEY)",
            "INSERT INTO person VALUES (1, 'a'), (2, 'b')", "INSERT INTO knows VALUES (1, 2), (2, 1)",
            "INSERT INTO dept VALUES (1), (2)", "INSERT INTO emp (dept) VALUES (1), (2)", "INSERT INTO audit VALUES (1, 1)",
            "INSERT INTO tree VALUES (1, NULL), (2, 1)"))
          both(postgres, arcade, setup);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        for (final String[] error : new String[][] { { "DROP TABLE nowhere", "42P01" }, { "DROP TABLE dept", "2BP01" },
            { "DROP TABLE person", "2BP01" }, { "DROP TABLE other, nowhere", "42P01" }, { "DROP TABLE dept, emp", "2BP01" },
            { "DROP TABLE other CASCADE RESTRICT", "42601" } })
          sameError(postgres, arcade, error[0], error[1]);
        sameValues(postgres, arcade, "SELECT count(*) FROM other");
        sameValues(postgres, arcade, "SELECT count(*) FROM emp");
        // a link table cannot stay without its ends: PostgreSQL would keep its rows, the language says what to write
        refused(arcade, "DROP TABLE person CASCADE", "DROP TABLE knows, person");

        arcade.setAutoCommit(false);
        refused(arcade, "DROP TABLE other", "autocommit");
        arcade.rollback();
        arcade.setAutoCommit(true);

        // CASCADE takes the foreign keys of the other tables, and their rows stay
        both(postgres, arcade, "DROP TABLE dept CASCADE");
        both(postgres, arcade, "INSERT INTO emp (dept) VALUES (7)");
        both(postgres, arcade, "INSERT INTO audit VALUES (2, 7)");
        sameValues(postgres, arcade, "SELECT * FROM emp ORDER BY id");
        sameValues(postgres, arcade, "SELECT * FROM audit ORDER BY id");
        sameError(postgres, arcade, "SELECT * FROM dept", "42P01");
        sameValues(postgres, arcade, "SELECT table_name, constraint_name FROM information_schema.table_constraints"
            + " WHERE constraint_type = 'FOREIGN KEY' AND table_name IN ('emp', 'audit')");

        for (final String change : List.of("DROP TABLE IF EXISTS nowhere", "DROP TABLE tree", "DROP TABLE public.knows",
            "DROP TABLE IF EXISTS knows, other, nowhere", "DROP TABLE emp"))
          both(postgres, arcade, change);
        sameValues(postgres, arcade, "SELECT id, name FROM person ORDER BY id");
        // a new table of the same name starts empty, and its serial at 1
        both(postgres, arcade, "CREATE TABLE emp (id serial PRIMARY KEY, dept int)");
        both(postgres, arcade, "INSERT INTO emp (dept) VALUES (1)");
        sameValues(postgres, arcade, "SELECT * FROM emp");
        // a link table and its vertex table in one statement, whatever the order
        both(postgres, arcade,
            "CREATE TABLE likes (source_id int NOT NULL REFERENCES person (id), target_id int NOT NULL REFERENCES person (id))");
        both(postgres, arcade, "INSERT INTO likes VALUES (1, 2)");
        both(postgres, arcade, "DROP TABLE person, likes");
        sameValues(postgres, arcade, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            + " ORDER BY table_name");

        // sql sees no type that was dropped, and keeps no counter of its serial column
        run(arcade, "DROP TABLE emp");
        compare("the types of sql", "    0|\n", answer(arcade,
            "{sql} SELECT count(*) AS n FROM schema:types WHERE name IN ['person', 'emp', 'likes', 'knows', 'dept', 'tree']", false)
            .replaceFirst("^[^\n]*\n", ""));
        compare("the counters", "    0|\n",
            answer(arcade, "{sql} SELECT count(*) AS n FROM _postgresql_sequences", false).replaceFirst("^[^\n]*\n", ""));

        // an edge that sql made at a row of the table goes with the row; a type that sql made is dropped in sql
        run(arcade, "CREATE TABLE city (id int PRIMARY KEY)");
        run(arcade, "CREATE TABLE country (id int PRIMARY KEY)");
        run(arcade, "INSERT INTO city VALUES (1)");
        run(arcade, "INSERT INTO country VALUES (1)");
        run(arcade, "{sql} CREATE EDGE TYPE lies_in");
        run(arcade, "{sql} CREATE EDGE lies_in FROM (SELECT FROM city) TO (SELECT FROM country)");
        run(arcade, "DROP TABLE city");
        compare("the edges of sql", "    0|\n",
            answer(arcade, "{sql} SELECT count(*) AS n FROM lies_in", false).replaceFirst("^[^\n]*\n", ""));
        compare("openCypher", "    0|\n", answer(arcade, "{opencypher} MATCH (c:country)<-[e]-() RETURN count(e) AS n", false)
            .replaceFirst("^[^\n]*\n", ""));
        run(arcade, "{sql} CREATE VERTEX TYPE made_by_sql");
        refused(arcade, "DROP TABLE made_by_sql", "{sql}DROP TYPE `made_by_sql`");

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }
}
