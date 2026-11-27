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
 * A correlated {@code EXISTS} of the {@code postgresql} language with a subquery inside a term of its {@code WHERE} clause, against
 * PostgreSQL 18 (fork issue #164): the same statement runs on both over JDBC. It has to give the same rows, or, for the shapes that
 * are outside the subset, be refused with {@code 0A000} where PostgreSQL answers.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLCorrelatedExistsTest extends PostgresWireProtocolTestBase {
  private static final String IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PASSWORD = "correlatedexists";

  private static final List<String> SCHEMA = List.of(
      "CREATE TABLE a (x int, g int)",
      "CREATE TABLE b (g int)",
      "CREATE TABLE d (y int, k int)",
      "INSERT INTO a VALUES (1, 10), (2, 20), (3, 30)",
      "INSERT INTO b VALUES (20)",
      "INSERT INTO d VALUES (1, 20), (2, 10), (3, 30)");

  private static final String HEAD = "SELECT x FROM a WHERE EXISTS (SELECT 1 FROM b WHERE ";
  private static final String TAIL = ") ORDER BY x";

  private static final String NESTED      = "SELECT x FROM a WHERE (SELECT count(*) FROM b b1 WHERE EXISTS (SELECT 1 FROM b WHERE ";
  private static final String NESTED_TAIL = ")) > 0 ORDER BY x";

  private static final List<String> ANSWERED = List.of(
      // the subquery of the term reads a column of the EXISTS subquery
      HEAD + "a.x = (SELECT min(d.y) FROM d WHERE d.k = g)" + TAIL,
      HEAD + "a.x = (SELECT min(d.y) FROM d WHERE d.k = b.g)" + TAIL,
      HEAD + "a.x < (SELECT max(d.y) FROM d WHERE d.k = g) + 1" + TAIL,
      "SELECT x FROM a WHERE NOT EXISTS (SELECT 1 FROM b WHERE a.x = (SELECT min(d.y) FROM d WHERE d.k = g))" + " ORDER BY x",
      HEAD + "a.x = (SELECT min(d.y) FROM d WHERE d.k = (SELECT max(e.k) FROM d e WHERE e.k = b.g))" + TAIL,
      // it reads the query around the EXISTS
      HEAD + "b.g = (SELECT max(d.k) FROM d WHERE d.y = a.x)" + TAIL,
      HEAD + "1 = (SELECT count(*) FROM d WHERE d.y = a.x)" + TAIL,
      // it reads only the query around, only its own tables, or the EXISTS subquery beside a term of it
      HEAD + "a.x IN (SELECT d.y FROM d WHERE d.k = a.g)" + TAIL,
      HEAD + "a.x IN (SELECT d.y FROM d WHERE d.k = 30)" + TAIL,
      HEAD + "b.g IN (SELECT d.k FROM d)" + TAIL,
      HEAD + "b.g IN (SELECT d.k FROM d WHERE d.k = b.g) AND a.x > 1" + TAIL,
      // a column two queries out
      NESTED + "b.g = (SELECT max(d.k) FROM d WHERE d.y = a.x)" + NESTED_TAIL);

  private static final List<String> REFUSED = List.of(
      HEAD + "a.x IN (SELECT d.y FROM d WHERE d.k = g)" + TAIL,
      HEAD + "a.x NOT IN (SELECT d.y FROM d WHERE d.k = b.g)" + TAIL,
      HEAD + "b.g IN (SELECT d.k FROM d WHERE d.y = a.x)" + TAIL,
      HEAD + "1 = (SELECT count(*) FROM d WHERE d.k = b.g AND d.y = a.x)" + TAIL,
      NESTED + "b.g IN (SELECT d.k FROM d WHERE d.y = a.x)" + NESTED_TAIL);

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
  void aSubqueryInsideATermAnswersAsPostgreSqlDoesOrIsRefused() throws Exception {
    requireDocker();
    final List<String> failures = new ArrayList<>();
    try (final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "correlatedexists", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))) {
      container.start();
      try (final Connection postgres = postgres(
          "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/correlatedexists");
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

  /** The values of the one column, or the SQLSTATE of the error. */
  private static String answer(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(statement)) {
      final List<String> values = new ArrayList<>();
      while (rows.next())
        values.add(rows.getString(1));
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
