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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write that a script means to roll back is never committed (fork issue #236): a {@code BEGIN} that the {@code postgresql}
 * language refuses leaves a failed transaction block, a {@code BEGIN} behind a comment opens a block as it does in PostgreSQL,
 * and {@code BEGIN READ ONLY} opens a block that refuses writes.
 */
class PostgreSQLRefusedBeginTest extends PostgreSQLSessionAgainstPostgresTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE acc (id bigint PRIMARY KEY, amount int)",
      "INSERT INTO acc (id, amount) VALUES (1, 100)");

  /** Statements whose every answer, row counts and SQLSTATEs included, is that of PostgreSQL. */
  private static final List<List<String>> SAME_AS_POSTGRESQL = List.of(
      // a comment is white space: the statement behind it opens, and ends, a block
      List.of("-- move the money\nBEGIN", "UPDATE acc SET amount = 0 WHERE id = 1", "ROLLBACK", "SELECT amount FROM acc"),
      List.of("/* app /* nested */ */ BEGIN", "UPDATE acc SET amount = 0 WHERE id = 1", "/* undo */ ROLLBACK",
          "SELECT amount FROM acc"),
      List.of("BEGIN -- a block", "UPDATE acc SET amount = 0 WHERE id = 1", "ROLLBACK -- undo it", "SELECT amount FROM acc"),
      List.of("BEGIN", "SELECT nosuch FROM acc", "SELECT amount FROM acc", "-- out of the failed block\nROLLBACK",
          "SELECT amount FROM acc"),
      // a block that refuses writes
      List.of("BEGIN READ ONLY", "SHOW transaction_read_only", "SELECT amount FROM acc",
          "INSERT INTO acc (id, amount) VALUES (2, 5)", "SELECT amount FROM acc", "ROLLBACK", "SHOW transaction_read_only",
          "SELECT count(*) FROM acc"),
      List.of("BEGIN TRANSACTION READ ONLY", "UPDATE acc SET amount = 0 WHERE id = 1", "COMMIT", "SELECT amount FROM acc"),
      List.of("START TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY", "DELETE FROM acc", "ROLLBACK",
          "SELECT count(*) FROM acc"),
      List.of("BEGIN READ ONLY", "CREATE TABLE other (id bigint PRIMARY KEY)", "ROLLBACK", "SELECT count(*) FROM other"),
      List.of("BEGIN", "BEGIN READ ONLY", "UPDATE acc SET amount = 0 WHERE id = 1", "ROLLBACK", "SELECT amount FROM acc"),
      List.of("BEGIN READ ONLY", "SELECT amount FROM acc", "COMMIT", "UPDATE acc SET amount = 100 WHERE id = 1",
          "SELECT amount FROM acc"),
      List.of("BEGIN READ WRITE", "UPDATE acc SET amount = 7 WHERE id = 1", "ROLLBACK", "SELECT amount FROM acc"));

  /** Each opens a block that PostgreSQL accepts and that is refused here. */
  private static final List<String> REFUSED = List.of(
      "BEGIN ISOLATION LEVEL SERIALIZABLE",
      "START TRANSACTION ISOLATION LEVEL SERIALIZABLE",
      "BEGIN ISOLATION LEVEL SERIALIZABLE READ ONLY",
      "-- strict\nBEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE");

  @Test
  void blocksAnswerAsPostgreSqlDoes() throws Exception {
    compare((postgres, arcade) -> {
      final List<String> failures = new ArrayList<>();
      try (final Connection pg = postgres.connect(false); final Connection own = arcade.connect(false)) {
        SETUP.forEach(statement -> {
          answer(pg, statement);
          assertThat(answer(own, statement)).as(statement).doesNotStartWith("error");
        });
      }
      for (final boolean simple : new boolean[] { false, true })
        for (final List<String> script : SAME_AS_POSTGRESQL)
          // each script on connections of its own: a script that fails leaves no block behind for the next one
          try (final Connection pg = postgres.connect(simple); final Connection own = arcade.connect(simple)) {
            final List<String> expected = answers(pg, script);
            final List<String> actual = answers(own, script);
            if (!expected.equals(actual))
              failures.add("simple=" + simple + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
          }
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    });
  }

  @Test
  void aRefusedBeginLeavesAFailedBlockAndNoWriteIsCommitted() throws Exception {
    compare((postgres, arcade) -> {
      try (final Connection pg = postgres.connect(false); final Connection own = arcade.connect(false)) {
        SETUP.forEach(statement -> {
          answer(pg, statement);
          assertThat(answer(own, statement)).as(statement).doesNotStartWith("error");
        });
      }
      for (final boolean simple : new boolean[] { false, true })
        for (final String begin : REFUSED)
          for (final String end : new String[] { "ROLLBACK", "COMMIT" }) {
            final String description = "simple=" + simple + " " + begin + " ... " + end;
            final List<String> rest = List.of("UPDATE acc SET amount = 0 WHERE id = 1",
                "INSERT INTO acc (id, amount) VALUES (2, 5)", "SELECT amount FROM acc", "BEGIN", end);
            try (final Connection pg = postgres.connect(simple); final Connection own = arcade.connect(simple)) {
              assertThat(answer(own, begin)).as(description).isEqualTo("error 0A000");
              for (final String statement : rest.subList(0, rest.size() - 1))
                assertThat(answer(own, statement)).as(description + ": " + statement).isEqualTo("error 25P02");
              // the failed block ends as a failed block of PostgreSQL does
              answer(pg, "BEGIN");
              answer(pg, "SELECT 1 / 0");
              assertThat(answer(own, end)).as(description).isEqualTo(answer(pg, end));
              answer(pg, "ROLLBACK");

              // PostgreSQL runs the block; rolled back, it leaves what the failed block leaves here
              answer(pg, begin);
              for (final String statement : rest.subList(0, rest.size() - 1))
                answer(pg, statement);
              answer(pg, "ROLLBACK");

              final String check = "SELECT id, amount FROM acc ORDER BY id";
              assertThat(answer(own, check)).as(description).isEqualTo(answer(pg, check)).isEqualTo("rows [1|100]");
              // the session is usable again, in autocommit
              assertThat(answer(own, "UPDATE acc SET amount = 100 WHERE id = 1")).as(description).isEqualTo("count 1");
            }
          }

      // the error says what to write instead
      try (final Connection own = arcade.connect(false)) {
        assertThat(errorMessage(own, "BEGIN ISOLATION LEVEL SERIALIZABLE"))
            .contains("ROLLBACK").contains("BEGIN ISOLATION LEVEL REPEATABLE READ");
      }
      // several statements in one message: nothing after the refused BEGIN runs, and the block stays failed
      try (final Connection own = arcade.connect(true)) {
        assertThat(answer(own, "BEGIN ISOLATION LEVEL SERIALIZABLE; UPDATE acc SET amount = 0 WHERE id = 1"))
            .isEqualTo("error 0A000");
        assertThat(answer(own, "UPDATE acc SET amount = 0 WHERE id = 1")).isEqualTo("error 25P02");
        assertThat(answer(own, "ROLLBACK")).isEqualTo("count 0");
        assertThat(answer(own, "SELECT amount FROM acc")).isEqualTo("rows [100]");
      }
    });
  }

  @Test
  void theReadOnlyModeOfTheJdbcDriverRefusesWrites() throws Exception {
    compare((postgres, arcade) -> {
      try (final Connection pg = postgres.connect(false); final Connection own = arcade.connect(false)) {
        SETUP.forEach(statement -> {
          answer(pg, statement);
          assertThat(answer(own, statement)).as(statement).doesNotStartWith("error");
        });
        assertThat(driverReadOnly(own)).isEqualTo(driverReadOnly(pg))
            .containsExactly("rows [100]", "error 25006", "rows [100]");
      }
    });
  }

  /** With autocommit off, a read-only connection of the driver opens its blocks with {@code BEGIN READ ONLY}. */
  private static List<String> driverReadOnly(final Connection connection) throws SQLException {
    connection.setReadOnly(true);
    connection.setAutoCommit(false);
    final List<String> answers = new ArrayList<>();
    answers.add(answer(connection, "SELECT amount FROM acc"));
    answers.add(answer(connection, "UPDATE acc SET amount = 0 WHERE id = 1"));
    connection.rollback();
    answers.add(answer(connection, "SELECT amount FROM acc"));
    connection.rollback();
    return answers;
  }
}
