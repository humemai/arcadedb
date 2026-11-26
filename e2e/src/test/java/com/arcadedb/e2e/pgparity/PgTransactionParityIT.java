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
package com.arcadedb.e2e.pgparity;
package com.arcadedb.e2e.pgparity;

import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transaction behaviour of fork issue #29 against a real PostgreSQL 18, which the write corpus cannot show: it runs one
 * statement per case in a transaction that is rolled back. Here a scenario is a list of statements of one or two sessions on
 * the {@code small} fixture. Each statement runs on the server and on the {@code postgresql} language of ArcadeDB, and its
 * outcome (the rows of a query, {@code ok}, or the SQLSTATE) is compared step by step, then the table that was written.
 * <p>
 * A session is one connection on the server and one thread on the engine, because the transaction of an ArcadeDB database
 * belongs to its thread. The server runs at its default isolation, {@code READ COMMITTED}.
 * <p>
 * The one difference is the second writer of a row, and it is asserted on both sides: the server makes it wait for the first,
 * the engine lets both go on and refuses the commit that comes last with {@code 40001}.
 */
class PgTransactionParityIT {
  private static final String CHECK = "SELECT pid, name, age FROM person ORDER BY pid";

  private static PostgresServer pg;
  private static String         schema;

  /** One statement of session {@code A} or {@code B}; {@code waits} marks the statement that the server holds on a lock. */
  private record Step(char session, String sql, boolean waits) {
  }

  /** The outcome of every step, in order, and then the rows of {@link #CHECK}. */
  private record Run(List<String> steps, String table) {
  }

  private interface Session extends AutoCloseable {
    Future<String> send(String sql);

    @Override
    void close() throws Exception;
  }

  @BeforeAll
  static void start() throws Exception {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null && !Files.exists(directory.resolve("engine/src/test/resources/postgresql/small-schema.sql")))
      directory = directory.getParent();
    assertTrue(directory != null, "small-schema.sql not found");
    schema = Files.readString(directory.resolve("engine/src/test/resources/postgresql/small-schema.sql"), StandardCharsets.UTF_8);
    pg = PostgresServer.start();
  }

  @AfterAll
  static void stop() {
    if (pg != null)
      pg.close();
  }

  private static Step a(final String sql) {
    return new Step('A', sql, false);
  }

  private static Step b(final String sql) {
    return new Step('B', sql, false);
  }

  @Test
  void aFailedBlockRefusesEveryStatementAndCommitRollsItBack() throws Exception {
    final Run server = same(List.of(a("BEGIN"), a("INSERT INTO person (pid, name) VALUES (90, 'Tx')"),
        a("INSERT INTO person (pid, name) VALUES (1, 'Dup')"), a("SELECT count(*) FROM person"),
        a("INSERT INTO person (pid, name) VALUES (91, 'Tx')"), a("COMMIT"), a("SELECT count(*) FROM person")));
    assertEquals(List.of("ok", "ok", "23505", "25P02", "25P02", "ok", "[[5]]"), server.steps());
  }

  @Test
  void rollbackEndsAFailedBlockAndTheSessionGoesOn() throws Exception {
    final Run server = same(List.of(a("BEGIN"), a("SELECT nosuch FROM person"), a("SELECT 1"), a("ROLLBACK"),
        a("INSERT INTO person (pid, name) VALUES (92, 'Kept')"), a("SELECT count(*) FROM person"), a("BEGIN"),
        a("INSERT INTO nope (x) VALUES (1)"), a("SELECT 1"), a("ROLLBACK"), a("BEGIN"), a("SELEC 1"), a("SELECT 1"),
        a("ROLLBACK"), a("SELECT count(*) FROM person")));
    assertTrue(server.steps().containsAll(List.of("42703", "42P01", "42601", "25P02")), server.steps().toString());
  }

  @Test
  void rollbackUndoesTheWritesOfABlockAndCommitKeepsThem() throws Exception {
    same(List.of(a("BEGIN"), a("INSERT INTO person (pid, name) VALUES (93, 'Tx')"), a("UPDATE person SET age = 99 WHERE pid = 1"),
        a("DELETE FROM person WHERE pid = 5"), a("SELECT count(*) FROM person"), a("ROLLBACK"),
        a("SELECT age FROM person WHERE pid = 1"), a("START TRANSACTION"), a("INSERT INTO person (pid, name) VALUES (94, 'Tx')"),
        a("COMMIT"), a("SELECT count(*) FROM person")));
  }

  @Test
  void aSecondBeginAndACommitOrRollbackOutsideABlockAreNoError() throws Exception {
    same(List.of(a("COMMIT"), a("ROLLBACK"), a("BEGIN"), a("BEGIN"), a("INSERT INTO person (pid, name) VALUES (95, 'Tx')"),
        a("COMMIT"), a("COMMIT"), a("SELECT count(*) FROM person")));
  }

  @Test
  void aWriteAfterAnotherSessionCommittedTheSameRowGoesThrough() throws Exception {
    same(List.of(a("BEGIN"), a("SELECT age FROM person WHERE pid = 1"), b("UPDATE person SET age = 60 WHERE pid = 1"),
        a("SELECT age FROM person WHERE pid = 1"), a("UPDATE person SET age = 50 WHERE pid = 1"), a("COMMIT"),
        b("SELECT age FROM person WHERE pid = 1")));
  }

  @Test
  void theSecondWriterOfARowWaitsOnTheServerAndTheEngineRefusesTheLastCommit() throws Exception {
    final List<Step> steps = List.of(a("BEGIN"), a("UPDATE person SET age = 50 WHERE pid = 1"),
        new Step('B', "UPDATE person SET age = 60 WHERE pid = 1", true), a("COMMIT"), a("SELECT age FROM person WHERE pid = 1"));
    final Run server = onServer(steps);
    final Run engine = onEngine(steps);
    report(steps, server, engine);
    assertEquals(List.of("ok", "ok", "ok", "ok", "[[60]]"), server.steps(), "the server: B waits, then writes after A");
    assertEquals(List.of("ok", "ok", "ok", "40001", "[[60]]"), engine.steps(), "the engine: B commits, then A is refused");
    assertEquals(server.table(), engine.table(), "both end with the value of B");
  }

  private static Run same(final List<Step> steps) throws Exception {
    final Run server = onServer(steps);
    final Run engine = onEngine(steps);
    report(steps, server, engine);
    assertEquals(server.steps(), engine.steps(), "outcome of each statement");
    assertEquals(server.table(), engine.table(), "the table afterwards");
    return server;
  }

  private static void report(final List<Step> steps, final Run server, final Run engine) {
    for (int i = 0; i < steps.size(); i++)
      System.out.println("pgparity transaction " + steps.get(i).session() + " " + steps.get(i).sql() + " | server "
          + server.steps().get(i) + " | postgresql " + engine.steps().get(i));
  }

  private static Run onServer(final List<Step> steps) throws Exception {
    pg.load("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
    pg.load(schema);
    try (final Session first = serverSession(); final Session second = serverSession()) {
      return run(steps, Map.of('A', first, 'B', second), true);
    }
  }

  private static Run onEngine(final List<Step> steps) throws Exception {
    try (final ArcadeSide arcade = new ArcadeSide();
        final Session first = engineSession(arcade);
        final Session second = engineSession(arcade)) {
      return run(steps, Map.of('A', first, 'B', second), false);
    }
  }

  private static Run run(final List<Step> steps, final Map<Character, Session> sessions, final boolean server) throws Exception {
    final List<Future<String>> sent = new ArrayList<>();
    for (final Step step : steps) {
      final Future<String> outcome = sessions.get(step.session()).send(step.sql());
      sent.add(outcome);
      if (server && step.waits()) {
        // the server holds it on the row lock of the other session until that one ends
        Thread.sleep(1500);
        assertFalse(outcome.isDone(), "the server did not make the statement wait: " + step.sql());
      } else
        outcome.get(60, TimeUnit.SECONDS);
    }
    final List<String> outcomes = new ArrayList<>();
    for (final Future<String> outcome : sent)
      outcomes.add(outcome.get(60, TimeUnit.SECONDS));
    return new Run(outcomes, sessions.get('A').send(CHECK).get(60, TimeUnit.SECONDS));
  }

  private static Session serverSession() throws SQLException {
    final Connection connection = pg.newConnection();
    final ExecutorService thread = Executors.newSingleThreadExecutor();
    return new Session() {
      @Override
      public Future<String> send(final String sql) {
        return thread.submit(() -> {
          try (final Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            if (!statement.execute(sql))
              return "ok";
            try (final java.sql.ResultSet rs = statement.getResultSet()) {
              final ResultSetMetaData columns = rs.getMetaData();
              final List<List<String>> rows = new ArrayList<>();
              while (rs.next()) {
                final List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns.getColumnCount(); i++)
                  row.add(String.valueOf(rs.getString(i)));
                rows.add(row);
              }
              return rows.toString();
            }
          } catch (final SQLException e) {
            return e.getSQLState();
          }
        });
      }

      @Override
      public void close() throws Exception {
        thread.shutdownNow();
        connection.close();
      }
    };
  }

  private static Session engineSession(final ArcadeSide arcade) {
    final ExecutorService thread = Executors.newSingleThreadExecutor();
    return new Session() {
      @Override
      public Future<String> send(final String sql) {
        return thread.submit(() -> {
          try (final ResultSet rs = arcade.database().command("postgresql", sql)) {
            if (!sql.startsWith("SELECT")) {
              while (rs.hasNext())
                rs.next();
              return "ok";
            }
            final List<List<String>> rows = new ArrayList<>();
            while (rs.hasNext()) {
              final Result result = rs.next();
              final List<String> row = new ArrayList<>();
              for (final String name : result.getPropertyNames())
                row.add(String.valueOf(result.<Object>getProperty(name)));
              rows.add(row);
            }
            return rows.toString();
          } catch (final Throwable t) {
            return ArcadeSide.sqlState(t);
          }
        });
      }

      @Override
      public void close() throws Exception {
        // a transaction that a scenario left open is rolled back on its own thread before the database closes
        thread.submit(() -> {
          if (arcade.database().isTransactionActive())
            arcade.database().rollback();
        }).get(60, TimeUnit.SECONDS);
        thread.shutdownNow();
      }
    };
  }
}
