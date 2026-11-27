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

import com.arcadedb.GlobalConfiguration;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A join of the {@code postgresql} language whose condition has a subquery that reads a column of the join, against PostgreSQL 18
 * (fork issue #170): the same statement runs on both over JDBC. It has to give the same rows or the same error, or, for the shape
 * that is outside the subset, be refused with {@code 0A000} where PostgreSQL answers.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLJoinSubqueryTest extends PostgresWireProtocolTestBase {
  private static final String IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PASSWORD = "joinsubquery";

  private static final List<String> SCHEMA = List.of(
      "CREATE TABLE a (x int, g int)",
      "CREATE TABLE b (g int, h int)",
      "CREATE TABLE d (y int, k int)",
      "CREATE TABLE o (h int)",
      "INSERT INTO a VALUES (1, 10), (2, 20), (3, 30)",
      "INSERT INTO b VALUES (20, 30), (30, 10)",
      "INSERT INTO d VALUES (1, 20), (2, 20), (3, 30)",
      "INSERT INTO o VALUES (20)");

  private static final String PAIRS = "SELECT a.x, b.g FROM a, b WHERE ";
  private static final String ORDER = " ORDER BY a.x, b.g";

  private static final List<String> ANSWERED = List.of(
      // a WHERE term of a comma join
      PAIRS + "a.x IN (SELECT d.y FROM d WHERE d.k = b.g)" + ORDER,
      PAIRS + "a.x NOT IN (SELECT d.y FROM d WHERE d.k = b.g)" + ORDER,
      PAIRS + "b.g IN (SELECT d.k FROM d WHERE d.y = a.x)" + ORDER,
      PAIRS + "a.x = (SELECT min(d.y) FROM d WHERE d.k = b.g)" + ORDER,
      PAIRS + "1 = (SELECT count(*) FROM d WHERE d.k = b.g)" + ORDER,
      PAIRS + "a.x IN (SELECT d.y FROM d WHERE d.k = (SELECT max(e.k) FROM d e WHERE e.k = b.g))" + ORDER,
      PAIRS + "EXISTS (SELECT 1 FROM d WHERE d.y = a.x AND d.k = (SELECT max(e.k) FROM d e WHERE e.k = b.g))" + ORDER,
      PAIRS + "a.x IN (SELECT d.y FROM d WHERE d.k = h)" + ORDER,
      // ON terms and outer joins
      "SELECT a.x, b.g FROM a JOIN b ON a.x IN (SELECT d.y FROM d WHERE d.k = b.g)" + ORDER,
      "SELECT a.x, b.g FROM a LEFT JOIN b ON b.g IN (SELECT d.k FROM d WHERE d.y = a.x)" + ORDER,
      "SELECT a.x, b.g FROM a RIGHT JOIN b ON a.x IN (SELECT d.y FROM d WHERE d.k = b.g)" + ORDER,
      "SELECT a.x, b.g FROM a LEFT JOIN b ON a.x * 10 = b.g WHERE a.x IN (SELECT d.y FROM d WHERE d.k = b.g)" + ORDER,
      // the query around has a column of the same name
      "SELECT b.g, (SELECT count(*) FROM a, b WHERE a.x IN (SELECT d.y FROM d WHERE d.k = b.g)) AS n FROM b ORDER BY b.g",
      "SELECT b.g, (SELECT count(*) FROM a, b WHERE a.x = (SELECT min(d.y) FROM d WHERE d.k = b.g)) AS n FROM b ORDER BY b.g",
      "SELECT b.g, (SELECT count(*) FROM a, b WHERE EXISTS (SELECT 1 FROM d WHERE d.y = a.x AND d.k = "
          + "(SELECT max(e.k) FROM d e WHERE e.k = b.g))) AS n FROM b ORDER BY b.g",
      "SELECT o.h, (SELECT count(*) FROM a, b WHERE a.x IN (SELECT d.y FROM d WHERE d.k = h)) AS n FROM o ORDER BY o.h",
      "SELECT o.h, (SELECT count(*) FROM a, b WHERE a.x IN (SELECT d.y FROM d WHERE d.k = b.g AND d.k = o.h)) AS n FROM o"
          + " ORDER BY o.h");

  /** Statements that PostgreSQL refuses: the same SQLSTATE. */
  private static final List<String> ERRORS = List.of(
      PAIRS + "a.x IN (SELECT d.y FROM d WHERE d.k = g)" + ORDER);

  /** An IN subquery inside an EXISTS that reads both tables of the join: PostgreSQL answers, the language refuses it. */
  private static final List<String> REFUSED = List.of(
      PAIRS + "EXISTS (SELECT 1 FROM d WHERE d.y = a.x AND d.k IN (SELECT e.k FROM d e WHERE e.k = b.g))" + ORDER,
      "SELECT b.g, (SELECT count(*) FROM a, b WHERE EXISTS (SELECT 1 FROM d WHERE d.y = a.x AND d.k IN "
          + "(SELECT e.k FROM d e WHERE e.k = b.g))) AS n FROM b ORDER BY b.g");

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database starts with no type. */
  @Override
  protected void populateDatabase() {
  }

  @Test
  void aJoinTermWithASubqueryAnswersAsPostgreSqlDoes() throws Exception {
    requireDocker();
    final List<String> failures = new ArrayList<>();
    try (final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "joinsubquery", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))) {
      container.start();
      try (final Connection postgres = postgres(
          "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/joinsubquery");
          final Connection arcade = arcade()) {
        for (final String statement : SCHEMA) {
          run(postgres, statement);
          run(arcade, statement);
        }
        for (final String statement : ANSWERED) {
          final String expected = answer(postgres, statement);
          final String actual = answer(arcade, statement);
          if (expected.startsWith("error") || !expected.equals(actual))
            failures.add(statement + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
        }
        for (final String statement : ERRORS) {
          final String expected = answer(postgres, statement);
          final String actual = answer(arcade, statement);
          if (!expected.startsWith("error") || !expected.equals(actual))
            failures.add(statement + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
        }
        for (final String statement : REFUSED) {
          final String expected = answer(postgres, statement);
          final String actual = answer(arcade, statement);
          if (expected.startsWith("error") || !"error 0A000".equals(actual))
            failures.add(statement + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual + " (0A000 expected)");
        }
      }
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  /** The rows with the values of their columns, or the SQLSTATE of the error. */
  private static String answer(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(statement)) {
      final int columns = rows.getMetaData().getColumnCount();
      final List<String> values = new ArrayList<>();
      while (rows.next()) {
        final StringBuilder row = new StringBuilder("(");
        for (int i = 1; i <= columns; i++)
          row.append(i > 1 ? "," : "").append(rows.getString(i));
        values.add(row.append(')').toString());
      }
      return "rows " + values;
    } catch (final SQLException e) {
      return "error " + e.getSQLState();
    }
  }

  private static void run(final Connection connection, final String statement) throws SQLException {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
    }
  }

  private static void requireDocker() {
    boolean available;
    try {
      available = DockerClientFactory.instance().isDockerAvailable();
    } catch (final RuntimeException e) {
      available = false;
    }
    if ("true".equals(System.getenv("GITHUB_ACTIONS")))
      assertThat(available).as("a Docker daemon is needed to start PostgreSQL 18").isTrue();
    assumeTrue(available, "a Docker daemon is needed to start PostgreSQL 18");
  }

  private static Connection postgres(final String url) throws Exception {
    SQLException last = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      try {
        return DriverManager.getConnection(url, "postgres", PASSWORD);
      } catch (final SQLException e) {
        last = e;
        Thread.sleep(1000);
      }
    }
    throw new IllegalStateException("PostgreSQL did not accept a connection at " + url, last);
  }

  private Connection arcade() throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    properties.setProperty("ssl", "false");
    properties.setProperty("socketTimeout", "60");
    properties.setProperty("options", "-c arcadedb.language=postgresql");
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), properties);
  }
}
