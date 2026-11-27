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
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal says what to write instead, in the hint field of the protocol (fork issues #241, #229, #238): the SQLSTATE is the one
 * PostgreSQL 18 gives where it refuses the statement too and {@code 0A000} where it runs it, and a statement that a hint suggests
 * runs when it is sent as it is.
 */
class PostgreSQLErrorHintTest extends PostgreSQLSessionAgainstPostgresTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE acc (id bigint PRIMARY KEY, v text)",
      "INSERT INTO acc (id, v) VALUES (1, 'a'), (2, 'b')",
      "CREATE TABLE person (id bigint PRIMARY KEY, name text)",
      "CREATE TABLE knows (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,"
          + " target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE)",
      "{sql}CREATE VERTEX TYPE City",
      "{sql}CREATE PROPERTY City.name STRING");

  /** PostgreSQL runs these: here each is a refusal with a hint that holds the second text. */
  private static final List<List<String>> REFUSED = List.of(
      List.of("CREATE VIEW v AS SELECT id FROM acc", "WITH clause"),
      List.of("WITH RECURSIVE t(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM t WHERE x < 3) SELECT x FROM t", "{opencypher}"),
      List.of("SELECT v FROM acc WHERE id = 1 FOR UPDATE", "40001"),
      List.of("CREATE TABLE gen (id bigint PRIMARY KEY, twice bigint GENERATED ALWAYS AS (id * 2) STORED)", "GENERATED"),
      List.of("SELECT make_interval(days => 3)", "interval '3 days'"),
      List.of("SELECT s.x FROM acc, LATERAL (SELECT acc.id AS x) s", "LATERAL"),
      List.of("SELECT '{\"a\": 1}'::jsonb AS v ORDER BY v", "->>"),
      List.of("TRUNCATE acc", "DELETE FROM acc"),
      List.of("CREATE EXTENSION IF NOT EXISTS plpgsql", "Leave the statement out"),
      List.of("ALTER TABLE acc ADD CONSTRAINT u UNIQUE (v)", "CREATE UNIQUE INDEX ON acc (v)"));

  /** What the hints of this language say to write, run in the order given. */
  private static final List<String> WAYS_OUT = List.of(
      "SELECT v FROM acc ORDER BY id LIMIT 2 OFFSET 1",
      "SELECT \"v\" FROM acc",
      "SELECT s.x FROM (SELECT id AS x FROM acc) AS s",
      "WITH v AS (SELECT id FROM acc) SELECT id FROM v",
      "SELECT date '2026-01-01' + interval '3 days'",
      "CREATE UNIQUE INDEX ON acc (v)",
      "SELECT id FROM acc WHERE id IN (1, 2)",
      "DELETE FROM acc WHERE id IN (SELECT id FROM acc WHERE v = 'zz')");

  @Test
  void aRefusalNamesAWayOutInTheHintField() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { true, false })
        try (final Connection pg = postgres.connect(simple); final Connection own = arcade.connect(simple)) {
          if (simple) {
            SETUP.forEach(statement -> {
              if (!statement.startsWith("{") && !statement.startsWith("CREATE TABLE knows"))
                answer(pg, statement);
              assertThat(answer(own, statement)).as(statement).doesNotStartWith("error");
            });
          }
          final List<String> failures = new ArrayList<>();
          for (final List<String> refused : REFUSED) {
            final String statement = refused.get(0);
            // PostgreSQL runs it; once is enough, some of these change its schema
            if (simple && answer(pg, statement).startsWith("error"))
              failures.add(statement + ": PostgreSQL refuses it too");
            final ServerErrorMessage error = error(own, statement);
            if (error == null) {
              failures.add(statement + ": runs");
              continue;
            }
            if (!"0A000".equals(error.getSQLState()) || error.getHint() == null || !error.getHint().contains(refused.get(1))
                || error.getMessage().contains("Hint:"))
              failures.add(statement + ": " + error.getSQLState() + " " + error.getMessage() + " | " + error.getHint());
          }
          assertThat(failures).as(String.join("\n", failures)).isEmpty();

          // refused by PostgreSQL too, with the same code
          for (final String statement : List.of("SELECT v FROM acc ORDER BY id LIMIT 1, 2", "SELECT `v` FROM acc",
              "SELECT w FROM acc", "SELECT 1 = 'a'::text")) {
            final String theirs = answer(pg, statement);
            assertThat(theirs).as(statement).startsWith("error");
            assertThat(answer(own, statement)).as(statement).isEqualTo(theirs);
            final ServerErrorMessage error = error(own, statement);
            assertThat(error.getHint()).as(statement).isNotBlank();
            assertThat(error.getMessage()).as(statement).doesNotContain("Hint:");
          }
        }
    });
  }

  @Test
  void whatTheHintsSayToWriteRuns() throws Exception {
    compare((postgres, arcade) -> {
      try (final Connection own = arcade.connect(false)) {
        SETUP.forEach(statement -> assertThat(answer(own, statement)).as(statement).doesNotStartWith("error"));
        WAYS_OUT.forEach(statement -> assertThat(answer(own, statement)).as(statement).doesNotStartWith("error"));

        // each suggestion of a statement of another language is sent exactly as the hint writes it
        runSuggestion(own, "WITH RECURSIVE t AS (SELECT 1) SELECT * FROM t");
        assertThat(answer(own, "ALTER TABLE acc ADD COLUMN extra text")).doesNotStartWith("error");
        assertThat(answer(own, "SELECT extra FROM acc WHERE id = 1")).isEqualTo("rows [null]");
        runSuggestion(own, "ALTER TABLE acc DROP COLUMN IF EXISTS extra");
        assertThat(answer(own, "SELECT extra FROM acc")).isEqualTo("error 42703");
        // sql reads names as they are stored
        runSuggestion(own, "ALTER TABLE city ADD COLUMN population int");
        assertThat(answer(own, "SELECT name, population FROM city")).isEqualTo("rows []");
        runSuggestion(own, "ALTER TABLE CITY DROP COLUMN NAME");
        runSuggestion(own, "ALTER TABLE acc RENAME TO account");
        runSuggestion(own, "DROP INDEX acc_v_idx");
        runSuggestion(own, "ALTER TABLE account DROP CONSTRAINT acc_pkey");
        assertThat(answer(own, "DELETE FROM account")).isEqualTo("count 2");
        runSuggestion(own, "DROP TABLE account");
        runSuggestion(own, "DROP TABLE IF EXISTS account");
        assertThat(answer(own, "SELECT * FROM account")).isEqualTo("error 42P01");
      }
    });
  }

  /** A link table joined on a table's own key says to join on rid, and the join the hint names runs. */
  @Test
  void aJoinOfRecordIdsWithAKeyNamesTheColumnToJoinOn() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { true, false })
        try (final Connection own = arcade.connect(simple)) {
          if (simple)
            SETUP.forEach(statement -> assertThat(answer(own, statement)).as(statement).doesNotStartWith("error"));
          final ServerErrorMessage join = error(own,
              "SELECT q.name FROM person p JOIN knows k ON k.source_id = p.id JOIN person q ON q.rid = k.target_id");
          assertThat(join.getSQLState()).isEqualTo("42883");
          assertThat(join.getMessage()).isEqualTo("operator does not exist: text = bigint");
          assertThat(join.getHint()).contains("k.source_id holds record ids").contains("join on p.rid");
          assertThat(answer(own, "SELECT q.name FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q"
              + " ON q.rid = k.target_id")).isEqualTo("rows []");
          assertThat(error(own, "SELECT name FROM person WHERE rid = 1").getHint()).contains("compare person.id");
          assertThat(error(own, "SELECT name FROM City WHERE rid = 1").getHint()).contains("name the row by another column");
          // City declares no column id: the error says to read rid (fork issue #240)
          assertThat(error(own, "SELECT name FROM City WHERE id = 1").getHint())
              .isEqualTo("no column id: read rid, or declare id bigserial PRIMARY KEY");
          // another pair of types keeps PostgreSQL's hint
          assertThat(error(own, "SELECT name FROM person WHERE name = 1").getHint()).contains("explicit type casts");
        }
    });
  }

  /** A schema statement inside a transaction, and a savepoint that cannot be rolled back to, say how to go on. */
  @Test
  void aTransactionSaysHowToGoOn() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { true, false })
        try (final Connection own = arcade.connect(simple)) {
          assertThat(answer(own, "CREATE TABLE t" + simple + " (a int)")).doesNotStartWith("error");
          own.setAutoCommit(false);
          final ServerErrorMessage create = error(own, "CREATE INDEX ON t" + simple + " (a)");
          assertThat(create.getSQLState()).isEqualTo("0A000");
          assertThat(create.getHint()).contains("autocommit=True").contains("isolation_level=\"AUTOCOMMIT\"");
          // the block is aborted
          final ServerErrorMessage savepoint = error(own, "ROLLBACK TO SAVEPOINT a");
          assertThat(savepoint.getSQLState()).isEqualTo("0A000");
          assertThat(savepoint.getMessage()).startsWith("ROLLBACK TO SAVEPOINT is not supported");
          assertThat(savepoint.getHint()).contains("send ROLLBACK");
          assertThat(error(own, "SELECT 1").getSQLState()).isEqualTo("25P02");
          own.rollback();
          // as the hint says
          assertThat(error(own, "ROLLBACK TO SAVEPOINT a").getHint()).contains("send ROLLBACK");
          own.rollback();
          own.setAutoCommit(true);
          assertThat(answer(own, "CREATE INDEX ON t" + simple + " (a)")).doesNotStartWith("error");
        }
    });
  }

  /** Runs the statement that the hint of the refusal of {@code refused} ends with. */
  private static void runSuggestion(final Connection connection, final String refused) {
    final ServerErrorMessage error = error(connection, refused);
    assertThat(error).as(refused).isNotNull();
    final String hint = error.getHint();
    assertThat(hint).as(refused).containsPattern("\\{(sql|opencypher)}");
    final String suggestion = hint.substring(Math.max(hint.indexOf("{sql}"), hint.indexOf("{opencypher}")));
    assertThat(answer(connection, suggestion)).as(refused + " -> " + suggestion).doesNotStartWith("error");
  }

  /** The error of a statement as the server sent it, {@code null} when it runs. */
  private static ServerErrorMessage error(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
      return null;
    } catch (final SQLException e) {
      return ((PSQLException) e).getServerErrorMessage();
    }
  }
}
