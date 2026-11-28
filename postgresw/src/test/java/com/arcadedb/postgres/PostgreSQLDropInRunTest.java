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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A schema change of the {@code postgresql} language that further statements of its run follow (fork issue #335), against
 * PostgreSQL 18. PostgreSQL runs the messages before one {@code Sync} as one transaction (a JDBC statement string with several
 * statements, a JDBC batch, a psycopg pipeline), so the failure of a later statement takes a {@code DROP} back. The language
 * cannot bring back what it drops, so it refuses such a {@code DROP} before it drops anything. What {@code ALTER TABLE} changes
 * is taken back.
 */
class PostgreSQLDropInRunTest extends PostgreSQLSideBySideTestBase {

  private static final String FAILS = "SELECT nope FROM other";

  private static final String SCRIPT = """
      import sys
      import psycopg

      with psycopg.connect(sys.argv[1], autocommit=True) as conn:
          conn.execute("CREATE TABLE piped (id int PRIMARY KEY, v text)")
          conn.execute("CREATE UNIQUE INDEX piped_v ON piped (v)")
          conn.execute("INSERT INTO piped VALUES (1, 'a'), (2, 'b')")
          conn.execute("CREATE TABLE other_piped (a int)")

          def state(statement):
              try:
                  conn.execute(statement)
                  return "00000"
              except psycopg.Error as e:
                  return e.sqlstate

          def rows():
              try:
                  return conn.execute("SELECT id, v FROM piped ORDER BY id").fetchall()
              except psycopg.Error as e:
                  return e.sqlstate

          # messages before one Sync
          def pipeline(*statements):
              with conn.pipeline():
                  for statement in statements:
                      conn.execute(statement)

          # one simple query message
          def message(*statements):
              conn.execute("; ".join(statements))

          for run in (pipeline, message):
              for drop in ("DROP TABLE piped", "DROP INDEX piped_v"):
                  try:
                      run(drop, "SELECT nope FROM other_piped")
                      print(drop, "ran")
                  except psycopg.Error as e:
                      # PostgreSQL fails the later statement; the language refuses the DROP and says what to do
                      if e.sqlstate == "0A000":
                          told = f"{e} {e.diag.message_hint}"
                          assert "on its own" in told, told
                      else:
                          assert e.sqlstate == "42703", (e.sqlstate, str(e))
                      print(drop, "failed")
                  # the rows are there, and the unique index holds
                  print(rows(), state("INSERT INTO piped VALUES (3, 'a')"))

          # a DROP on its own in a pipeline, and as the last statement of one, runs
          pipeline("DROP INDEX piped_v")
          print(state("INSERT INTO piped VALUES (3, 'a')"), rows())
          pipeline("SELECT 1", "DROP TABLE piped")
          print(rows())
      """;

  @Test
  void aFailedRunKeepsWhatADropWouldTake() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        both(postgres, arcade, "CREATE TABLE other (a int)");

        // a statement string with several statements, which JDBC sends as messages before one Sync; %s is the table
        final String[][] runs = { { "DROP TABLE %s; " + FAILS, "42703" },
            { "DROP TABLE IF EXISTS %s; SELECT 1; " + FAILS, "42703" },
            { "SELECT 1; DROP TABLE %s; " + FAILS, "42703" },
            { "DROP TABLE %s; DROP TABLE nowhere", "42P01" },
            { "DROP TABLE %s CASCADE; " + FAILS, "42703" },
            { "DROP INDEX %s_v; " + FAILS, "42703" },
            { "DROP INDEX IF EXISTS %s_v; " + FAILS, "42703" } };
        for (int i = 0; i < runs.length; i++) {
          final String table = "kept" + i;
          table(postgres, arcade, table);
          final String run = runs[i][0].formatted(table);
          assertThat(state(postgres, run)).as(run).isEqualTo(runs[i][1]);
          refused(arcade, run, "followed by other statements");
          unchanged(postgres, arcade, table);
        }

        // a JDBC batch
        table(postgres, arcade, "batched");
        for (final Connection connection : new Connection[] { postgres, arcade }) {
          final String expected = connection == postgres ? "42P01" : "0A000";
          for (final String drop : List.of("DROP TABLE batched", "DROP INDEX batched_v"))
            assertThat(batch(connection, drop, "INSERT INTO nowhere VALUES (1)")).as(drop).isEqualTo(expected);
        }
        unchanged(postgres, arcade, "batched");

        // one simple query message, which was refused before
        try (final Connection simple = arcade("?preferQueryMode=simple")) {
          table(postgres, arcade, "whole");
          refused(simple, "DROP TABLE whole; " + FAILS, "DROP TABLE");
          refused(simple, "DROP INDEX whole_v; " + FAILS, "DROP INDEX");
          unchanged(postgres, arcade, "whole");
          both(postgres, simple, "DROP TABLE whole");
        }

        // there is no other way to drop in the language: nothing runs
        table(postgres, arcade, "spared");
        for (final String change : List.of("TRUNCATE spared", "TRUNCATE TABLE spared", "DROP VIEW IF EXISTS spared_view",
            "DROP SEQUENCE IF EXISTS spared_seq", "DROP SCHEMA IF EXISTS spared_schema", "DROP TYPE IF EXISTS spared_type")) {
          refused(arcade, change + "; " + FAILS, "");
          assertThat(state(postgres, change + "; " + FAILS)).as(change).isEqualTo("42703");
        }
        unchanged(postgres, arcade, "spared");

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  @Test
  void aDropThatNothingFollowsRuns() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        // on its own: a statement, a prepared statement, and a batch of one
        table(postgres, arcade, "alone");
        both(postgres, arcade, "DROP INDEX alone_v");
        both(postgres, arcade, "INSERT INTO alone VALUES (3, 'a')");
        both(postgres, arcade, "DROP TABLE alone");
        sameError(postgres, arcade, "SELECT * FROM alone", "42P01");
        table(postgres, arcade, "prepared");
        for (final Connection connection : new Connection[] { postgres, arcade })
          for (final String drop : List.of("DROP INDEX prepared_v", "DROP TABLE prepared"))
            try (final PreparedStatement statement = connection.prepareStatement(drop)) {
              statement.execute();
            }
        sameError(postgres, arcade, "SELECT * FROM prepared", "42P01");
        table(postgres, arcade, "batched");
        for (final Connection connection : new Connection[] { postgres, arcade })
          assertThat(batch(connection, "DROP TABLE batched")).isEqualTo("00000");
        sameError(postgres, arcade, "SELECT * FROM batched", "42P01");

        // the last statement of a run
        table(postgres, arcade, "last");
        both(postgres, arcade, "SELECT 1; DROP INDEX last_v");
        both(postgres, arcade, "INSERT INTO last VALUES (3, 'a')");
        both(postgres, arcade, "SELECT 1; DROP TABLE last");
        sameError(postgres, arcade, "SELECT * FROM last", "42P01");

        // a DROP that finds nothing drops nothing, and one that is refused anyway keeps its error
        both(postgres, arcade, "DROP TABLE IF EXISTS nowhere; SELECT 1");
        both(postgres, arcade, "DROP INDEX IF EXISTS nowhere; SELECT 1");
        sameError(postgres, arcade, "DROP TABLE nowhere; SELECT 1", "42P01");
        sameError(postgres, arcade, "DROP INDEX nowhere; SELECT 1", "42704");

        // a run whose statements all succeed: PostgreSQL drops, the language refuses, because it cannot know that when the DROP runs
        table(postgres, arcade, "refusedrun");
        run(postgres, "DROP TABLE refusedrun; SELECT 1");
        refused(arcade, "DROP TABLE refusedrun; SELECT 1", "followed by other statements");
        compare("the rows of the refused run", "    count|\n    2|\n", answer(arcade, "SELECT count(*) FROM refusedrun", false));
        // the connection goes on
        run(arcade, "DROP TABLE refusedrun");

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  @Test
  void aFailedRunTakesBackAlterTable() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        both(postgres, arcade, "CREATE TABLE other (a int)");
        final String[] actions = { "DROP COLUMN v", "DROP COLUMN n, ADD COLUMN w int DEFAULT 3", "RENAME COLUMN v TO w",
            "ADD COLUMN w int DEFAULT 5", "ADD CONSTRAINT %s_n UNIQUE (n)", "ALTER COLUMN n SET DEFAULT 9",
            "ALTER COLUMN n DROP DEFAULT", "ALTER COLUMN n SET NOT NULL", "ALTER COLUMN m DROP NOT NULL" };
        for (int i = 0; i < actions.length; i++) {
          final String table = "altered" + i;
          both(postgres, arcade, "CREATE TABLE " + table + " (id int PRIMARY KEY, v text, n int DEFAULT 7, m int NOT NULL)");
          both(postgres, arcade, "CREATE UNIQUE INDEX " + table + "_v ON " + table + " (v)");
          both(postgres, arcade, "INSERT INTO " + table + " VALUES (1, 'a', 1, 1), (2, 'b', 2, 2)");
          final String run = "ALTER TABLE " + table + " " + actions[i].formatted(table) + "; " + FAILS;
          sameError(postgres, arcade, run, "42703");
          final String all = "SELECT * FROM " + table + " ORDER BY id";
          sameValues(postgres, arcade, all);
          // the keys, the default, and what may be NULL are those of before
          sameError(postgres, arcade, "INSERT INTO " + table + " (id, v, n, m) VALUES (3, 'a', 3, 3)", "23505");
          both(postgres, arcade, "INSERT INTO " + table + " (id, v, m) VALUES (4, 'd', 4)");
          both(postgres, arcade, "INSERT INTO " + table + " (id, v, n, m) VALUES (5, 'e', 2, 5)");
          both(postgres, arcade, "INSERT INTO " + table + " (id, v, n, m) VALUES (6, 'f', NULL, 6)");
          sameError(postgres, arcade, "INSERT INTO " + table + " (id, v, n, m) VALUES (7, 'g', 1, NULL)", "23502");
          sameValues(postgres, arcade, all);
        }
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  @Test
  void aFailedPipelineKeepsWhatADropWouldTake() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      python(container, SCRIPT);
    }
  }

  /** A table of two rows with the unique index {@code <name>_v}, on both servers. */
  private void table(final Connection postgres, final Connection arcade, final String name) throws SQLException {
    both(postgres, arcade, "CREATE TABLE " + name + " (id int PRIMARY KEY, v text)");
    both(postgres, arcade, "CREATE UNIQUE INDEX " + name + "_v ON " + name + " (v)");
    both(postgres, arcade, "INSERT INTO " + name + " VALUES (1, 'a'), (2, 'b')");
  }

  /** The rows of such a table are there, and its unique index holds. */
  private void unchanged(final Connection postgres, final Connection arcade, final String name) {
    sameValues(postgres, arcade, "SELECT id, v FROM " + name + " ORDER BY id");
    sameError(postgres, arcade, "INSERT INTO " + name + " VALUES (3, 'a')", "23505");
  }

  /** The SQLSTATE of a JDBC batch of the statements. */
  private static String batch(final Connection connection, final String... statements) {
    try (final Statement batch = connection.createStatement()) {
      for (final String statement : statements)
        batch.addBatch(statement);
      batch.executeBatch();
      return "00000";
    } catch (final SQLException e) {
      return e.getSQLState();
    }
  }
}
