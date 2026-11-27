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
 * {@code ALTER TABLE ... ADD CONSTRAINT} on the {@code postgresql} language against PostgreSQL 18 (fork issue #230), over JDBC:
 * a primary key, unique constraints, foreign keys and checks, over rows that meet them and rows that do not, what later writes
 * do, what a statement that fails leaves, and what {@code sql} sees.
 */
class PostgreSQLAddConstraintTest extends PostgreSQLSideBySideTestBase {
  private static final String CONSTRAINTS = "SELECT table_name, constraint_name, constraint_type FROM"
      + " information_schema.table_constraints WHERE table_schema = 'public' AND constraint_type <> 'CHECK'"
      + " ORDER BY table_name, constraint_name";
  private static final String CHECKS = "SELECT table_name, constraint_name FROM information_schema.constraint_column_usage"
      + " WHERE table_schema = 'public' AND constraint_name NOT LIKE '%not_null%' ORDER BY table_name, constraint_name,"
      + " column_name";
  private static final String COLUMNS = "SELECT table_name, column_name, is_nullable FROM information_schema.columns"
      + " WHERE table_schema = 'public' ORDER BY table_name, ordinal_position";

  @Test
  void addConstraintAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String setup : List.of("CREATE TABLE parent (id int, name text, code text, qty int)",
            "CREATE TABLE child (id int PRIMARY KEY, parent_id int, parent_code text, price numeric)",
            "INSERT INTO parent VALUES (1, 'a', 'x', 5), (2, 'a', NULL, -1), (3, 'b', NULL, NULL)",
            "INSERT INTO child VALUES (1, 1, 'x', 1.5), (2, 9, NULL, 2), (3, NULL, NULL, NULL)",
            "CREATE INDEX parent_qty_idx ON parent (qty)"))
          both(postgres, arcade, setup);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        // what the rows break, and what the statement says wrong: nothing changes
        for (final String[] error : new String[][] {
            { "ALTER TABLE parent ADD CONSTRAINT parent_name_key UNIQUE (name)", "23505" },
            { "ALTER TABLE parent ADD UNIQUE (name)", "23505" }, { "ALTER TABLE parent ADD PRIMARY KEY (code)", "23502" },
            { "ALTER TABLE parent ADD PRIMARY KEY (name)", "23505" }, { "ALTER TABLE parent ADD CHECK (qty >= 0)", "23514" },
            { "ALTER TABLE parent ADD UNIQUE (nosuch)", "42703" }, { "ALTER TABLE parent ADD UNIQUE (id, id)", "42701" },
            { "ALTER TABLE parent ADD CHECK (nosuch > 0)", "42703" }, { "ALTER TABLE child ADD PRIMARY KEY (id)", "42P16" },
            { "ALTER TABLE child ADD CONSTRAINT child_pkey UNIQUE (parent_id)", "42P07" },
            { "ALTER TABLE child ADD CONSTRAINT parent UNIQUE (parent_id)", "42P07" },
            { "ALTER TABLE child ADD CONSTRAINT child_pkey CHECK (price > 0)", "42710" },
            { "ALTER TABLE child ADD FOREIGN KEY (parent_id) REFERENCES parent (id)", "42830" },
            { "ALTER TABLE child ADD FOREIGN KEY (nosuch) REFERENCES parent (id)", "42703" },
            { "ALTER TABLE child ADD FOREIGN KEY (parent_id) REFERENCES nowhere (id)", "42P01" },
            { "ALTER TABLE child ADD FOREIGN KEY (parent_id) REFERENCES parent", "42704" },
            // the check is taken back with the key that fails
            { "ALTER TABLE parent ADD CHECK (id > 0), ADD UNIQUE (name)", "23505" },
            // the column goes with the constraint that its values break
            { "ALTER TABLE parent ADD COLUMN extra int DEFAULT 1 UNIQUE", "23505" },
            { "ALTER TABLE parent ADD COLUMN extra int DEFAULT 0 CHECK (extra > 0)", "23514" },
            { "ALTER TABLE parent ADD COLUMN extra int PRIMARY KEY", "23502" } })
          sameError(postgres, arcade, error[0], error[1]);
        sameError(postgres, arcade, "SELECT extra FROM parent", "42703");
        sameValues(postgres, arcade, CONSTRAINTS);
        sameValues(postgres, arcade, CHECKS);
        sameValues(postgres, arcade, COLUMNS);
        both(postgres, arcade, "INSERT INTO parent VALUES (0, 'a', NULL, -5)");
        both(postgres, arcade, "DELETE FROM parent WHERE id = 0");

        for (final String change : List.of("UPDATE parent SET name = 'c', qty = 0 WHERE id = 2",
            "ALTER TABLE parent ADD PRIMARY KEY (id)", "ALTER TABLE parent ADD CONSTRAINT parent_name_key UNIQUE (name)",
            "ALTER TABLE parent ADD UNIQUE (code)", "ALTER TABLE parent ADD UNIQUE (qty)",
            "ALTER TABLE parent ADD UNIQUE (name, qty)", "ALTER TABLE parent ADD CHECK (qty >= 0)",
            "ALTER TABLE parent ADD CHECK (qty < 100), ADD CONSTRAINT named CHECK (id > 0 OR name = '')",
            "ALTER TABLE parent ADD CHECK (id < qty + 100)",
            "UPDATE child SET parent_id = 2 WHERE id = 2",
            "ALTER TABLE child ADD FOREIGN KEY (parent_id) REFERENCES parent ON DELETE CASCADE",
            "ALTER TABLE child ADD CONSTRAINT child_code FOREIGN KEY (parent_code) REFERENCES parent (code)"
                + " ON UPDATE CASCADE ON DELETE SET NULL",
            "ALTER TABLE child ADD CHECK (price > 0)",
            "ALTER TABLE child ADD COLUMN sku text UNIQUE, ADD COLUMN stock int DEFAULT 1 CHECK (stock > 0),"
                + " ADD COLUMN other int REFERENCES parent (id)",
            "CREATE TABLE node (id int, up int)", "INSERT INTO node VALUES (1, NULL), (2, 1)",
            "ALTER TABLE node ADD FOREIGN KEY (up) REFERENCES node (id), ADD PRIMARY KEY (id)"))
          both(postgres, arcade, change);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        sameValues(postgres, arcade, CONSTRAINTS);
        sameValues(postgres, arcade, CHECKS);
        sameValues(postgres, arcade, COLUMNS);

        // later writes meet the constraints
        for (final String[] error : new String[][] { { "INSERT INTO parent (id, name) VALUES (1, 'z')", "23505" },
            { "INSERT INTO parent (id, name) VALUES (7, 'a')", "23505" }, { "INSERT INTO parent (name) VALUES ('z')", "23502" },
            { "UPDATE parent SET qty = -1 WHERE id = 1", "23514" },
            { "INSERT INTO child (id, parent_id) VALUES (8, 99)", "23503" },
            { "INSERT INTO child (id, price) VALUES (8, -1)", "23514" }, { "INSERT INTO child (id, stock) VALUES (8, 0)", "23514" },
            { "INSERT INTO child (id, other) VALUES (8, 99)", "23503" }, { "DELETE FROM node WHERE id = 1", "23503" } })
          sameError(postgres, arcade, error[0], error[1]);
        both(postgres, arcade, "UPDATE parent SET code = 'y' WHERE id = 1");
        both(postgres, arcade, "DELETE FROM parent WHERE id = 2");
        sameValues(postgres, arcade, "SELECT * FROM child ORDER BY id");
        sameValues(postgres, arcade, "SELECT * FROM parent ORDER BY id");

        // a block: the rows it wrote break the key, as they do the check; a rollback takes constraints back
        postgres.setAutoCommit(false);
        arcade.setAutoCommit(false);
        both(postgres, arcade, "CREATE TABLE plain (id int, v int)");
        both(postgres, arcade, "INSERT INTO plain VALUES (1, 1), (2, 1)");
        sameError(postgres, arcade, "ALTER TABLE plain ADD UNIQUE (v)", "23505");
        postgres.rollback();
        arcade.rollback();
        for (final String change : List.of("ALTER TABLE child ADD CONSTRAINT stock_small CHECK (stock < 10)",
            "ALTER TABLE child ADD UNIQUE (price)", "ALTER TABLE node ADD UNIQUE (up)",
            "ALTER TABLE parent ALTER COLUMN code SET DEFAULT 'q'", "ALTER TABLE child ADD FOREIGN KEY (stock) REFERENCES node"))
          both(postgres, arcade, change);
        sameError(postgres, arcade, "INSERT INTO child (id, stock) VALUES (8, 50)", "23514");
        postgres.rollback();
        arcade.rollback();
        sameValues(postgres, arcade, CONSTRAINTS);
        sameValues(postgres, arcade, CHECKS);
        sameValues(postgres, arcade, COLUMNS);
        both(postgres, arcade, "INSERT INTO child (id, stock) VALUES (8, 50)");
        both(postgres, arcade, "INSERT INTO node VALUES (3, 1), (4, 1)");
        postgres.commit();
        arcade.commit();
        postgres.setAutoCommit(true);
        arcade.setAutoCommit(true);

        // what PostgreSQL runs and the language does not says what to write
        refused(arcade, "ALTER TABLE child ADD CHECK (price > 0) NOT VALID", "NOT");
        refused(arcade, "ALTER TABLE child ADD FOREIGN KEY (parent_id) REFERENCES parent DEFERRABLE", "DEFERRABLE");
        // sql writes meet the constraints too
        compare("a row of sql that breaks a check", "error", "00000".equals(state(arcade,
            "{sql} UPDATE child SET price = -5 WHERE id = 1")) ? "runs" : "error");
        compare("a row of sql that breaks a key", "error", "00000".equals(state(arcade,
            "{sql} INSERT INTO node SET id = 1")) ? "runs" : "error");
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }
}
