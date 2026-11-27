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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ALTER TABLE} inside a transaction block on the {@code postgresql} language against PostgreSQL 18 (fork issue #230),
 * over JDBC: a commit keeps the change, a rollback, a failed statement and a failed commit take it back, and a statement that
 * fails while it writes the rows leaves the table as it was, for {@code sql} and openCypher too.
 */
class PostgreSQLAlterTableInBlockTest extends PostgreSQLSideBySideTestBase {
  private static final String COLUMNS = "SELECT table_name, column_name, data_type, is_nullable, column_default FROM"
      + " information_schema.columns WHERE table_schema = 'public' AND table_name IN ('facts', 'items')"
      + " ORDER BY table_name, ordinal_position";

  @Test
  void aTransactionBlockAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String setup : List.of("CREATE TABLE facts (id int PRIMARY KEY, content text, n int DEFAULT 1)",
            "CREATE TABLE items (id serial PRIMARY KEY, v text)", "INSERT INTO facts (id, content) VALUES (1, 'one'), (2, NULL)",
            "INSERT INTO items (v) VALUES ('a')"))
          both(postgres, arcade, setup);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        // a commit keeps what the block changed, and the block reads it
        postgres.setAutoCommit(false);
        arcade.setAutoCommit(false);
        for (final String change : List.of("ALTER TABLE facts ADD COLUMN tag text DEFAULT 'x'",
            "UPDATE facts SET tag = 'y' WHERE id = 2", "ALTER TABLE facts ALTER COLUMN tag SET NOT NULL"))
          both(postgres, arcade, change);
        sameValues(postgres, arcade, "SELECT id, tag FROM facts ORDER BY id");
        postgres.commit();
        arcade.commit();
        sameValues(postgres, arcade, "SELECT id, tag FROM facts ORDER BY id");
        sameValues(postgres, arcade, COLUMNS);
        postgres.commit();
        arcade.commit();

        // a rollback takes every change back, of the schema and of the rows, and a serial column keeps its counter
        for (final String change : List.of("ALTER TABLE facts ADD COLUMN a int NOT NULL DEFAULT 7",
            "ALTER TABLE facts ALTER COLUMN content SET DEFAULT 'd'", "ALTER TABLE facts ALTER COLUMN n DROP DEFAULT",
            "ALTER TABLE facts ALTER COLUMN tag DROP NOT NULL", "ALTER TABLE items ALTER COLUMN id DROP DEFAULT",
            "INSERT INTO facts (id) VALUES (3)", "CREATE TABLE fresh (id int PRIMARY KEY)",
            "ALTER TABLE fresh ADD COLUMN z int", "INSERT INTO fresh VALUES (1, 2)"))
          both(postgres, arcade, change);
        sameValues(postgres, arcade, COLUMNS);
        sameValues(postgres, arcade, "SELECT * FROM facts ORDER BY id");
        postgres.rollback();
        arcade.rollback();
        sameValues(postgres, arcade, COLUMNS);
        sameValues(postgres, arcade, "SELECT * FROM facts ORDER BY id");
        sameError(postgres, arcade, "SELECT * FROM fresh", "42P01");
        postgres.rollback();
        arcade.rollback();
        for (final String change : List.of("INSERT INTO facts (id, tag) VALUES (4, 't')", "INSERT INTO items (v) VALUES ('b')"))
          both(postgres, arcade, change);
        sameValues(postgres, arcade, "SELECT * FROM facts ORDER BY id");
        sameValues(postgres, arcade, "SELECT * FROM items ORDER BY id");
        postgres.commit();
        arcade.commit();

        // a statement that fails aborts the block, and the rollback takes the earlier ones back
        both(postgres, arcade, "ALTER TABLE facts ADD COLUMN c int DEFAULT 1");
        sameError(postgres, arcade, "ALTER TABLE facts ADD COLUMN d int NOT NULL", "23502");
        sameError(postgres, arcade, "SELECT 1", "25P02");
        postgres.rollback();
        arcade.rollback();
        sameValues(postgres, arcade, COLUMNS);
        // a COMMIT of an aborted block is a rollback
        both(postgres, arcade, "ALTER TABLE facts ADD COLUMN c int DEFAULT 1");
        sameError(postgres, arcade, "ALTER TABLE facts ALTER COLUMN content SET NOT NULL", "23502");
        postgres.commit();
        arcade.commit();
        sameValues(postgres, arcade, COLUMNS);
        sameValues(postgres, arcade, "SELECT * FROM facts ORDER BY id");
        postgres.commit();
        arcade.commit();
        postgres.setAutoCommit(true);
        arcade.setAutoCommit(true);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        // sql sees no value of a column that a rollback took back
        compare("sql after the rollbacks", "    0|\n", count(arcade, "{sql} SELECT count(*) AS n FROM facts WHERE a IS NOT NULL"
            + " OR c IS NOT NULL"));

        // another session changes a row that the block wrote: the commit fails, and the column is gone. PostgreSQL would make
        // the other session wait
        try (final Connection other = arcade("")) {
          arcade.setAutoCommit(false);
          run(arcade, "ALTER TABLE facts ADD COLUMN k int DEFAULT 1");
          run(other, "UPDATE facts SET content = 'theirs' WHERE id = 1");
          String state = "00000";
          try {
            arcade.commit();
          } catch (final SQLException e) {
            state = e.getSQLState();
          }
          compare("the commit after another write", "40001", state);
          arcade.rollback();
          arcade.setAutoCommit(true);
          compare("the column", "42703", state(arcade, "SELECT k FROM facts"));
          compare("the row of the other session", "    theirs|\n", count(arcade, "SELECT content FROM facts WHERE id = 1"));
        }
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  /**
   * The rows are written and one of them is refused part way: a property that one row lacks is mandatory, so every later write
   * of that row fails. The {@code sql} statement refuses to set the flag over such a row, so the Java API of the server sets it.
   */
  @Test
  void aStatementThatFailsWhileItWritesTheRowsLeavesTheTableAsItWas() throws Exception {
    try (final Connection arcade = arcade("")) {
      run(arcade, "CREATE TABLE partial (id int PRIMARY KEY, v text)");
      for (int id = 1; id <= 40; id++)
        run(arcade, "INSERT INTO partial VALUES (" + id + ", 'v" + id + "')");
      run(arcade, "{sql} CREATE PROPERTY partial.m STRING");
      run(arcade, "{sql} UPDATE partial SET m = 'x' WHERE id < 40");
      getServerDatabase(0, getDatabaseName()).getSchema().getType("partial").getProperty("m").setMandatory(true);
      final String rows = count(arcade, "SELECT id, v, m FROM partial ORDER BY id");

      for (final boolean inBlock : new boolean[] { false, true }) {
        arcade.setAutoCommit(!inBlock);
        compare("the statement, in a block " + inBlock, "error",
            "00000".equals(state(arcade, "ALTER TABLE partial ADD COLUMN w int DEFAULT 5")) ? "runs" : "error");
        if (inBlock) {
          compare("after the failure", "25P02", state(arcade, "SELECT 1"));
          arcade.rollback();
          arcade.setAutoCommit(true);
        }
        compare("the column, in a block " + inBlock, "42703", state(arcade, "SELECT w FROM partial"));
        compare("the rows, in a block " + inBlock, rows, count(arcade, "SELECT id, v, m FROM partial ORDER BY id"));
        compare("the values for sql, in a block " + inBlock, "    0|\n",
            count(arcade, "{sql} SELECT count(*) AS n FROM partial WHERE w IS NOT NULL"));
        compare("the values for openCypher, in a block " + inBlock, "    0|\n",
            count(arcade, "{opencypher} MATCH (p:partial) WHERE p.w IS NOT NULL RETURN count(p) AS n"));
      }
      // an action before the failing one is taken back too
      compare("two actions", "error", "00000".equals(state(arcade,
          "ALTER TABLE partial ALTER COLUMN v SET DEFAULT 'later', ADD COLUMN w int DEFAULT 5")) ? "runs" : "error");
      compare("the default", "    null|\n", count(arcade,
          "SELECT column_default FROM information_schema.columns WHERE table_name = 'partial' AND column_name = 'v'"));

      // with the row repaired the same statement runs
      run(arcade, "{sql} UPDATE partial SET m = 'x' WHERE id = 40");
      run(arcade, "ALTER TABLE partial ADD COLUMN w int DEFAULT 5");
      compare("the repaired table", "    200|\n", count(arcade, "SELECT sum(w) FROM partial"));
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }
  }

  /** The rows of an answer, without the header. */
  private static String count(final Connection connection, final String query) {
    return answer(connection, query, false).replaceFirst("^[^\n]*\n", "");
  }
}
