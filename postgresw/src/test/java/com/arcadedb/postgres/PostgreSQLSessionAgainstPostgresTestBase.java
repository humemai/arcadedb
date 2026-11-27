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
 * Runs the same statements on PostgreSQL 18 and on the {@code postgresql} language of an in-process server, both over JDBC, on
 * the simple and on the extended query protocol.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
abstract class PostgreSQLSessionAgainstPostgresTestBase extends PostgresWireProtocolTestBase {
  private static final String IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PASSWORD = "session";

  /** One side of a comparison: a way to open connections to it. */
  protected interface Side {
    Connection connect(boolean simpleProtocol) throws SQLException;
  }

  protected interface Comparison {
    void run(Side postgres, Side arcade) throws Exception;
  }

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database starts with no type. */
  @Override
  protected void populateDatabase() {
  }

  protected void compare(final Comparison comparison) throws Exception {
    requireDocker();
    try (final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "session", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))) {
      container.start();
      final String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/session";
      comparison.run(simple -> postgres(url, simple), this::arcade);
    }
  }

  /**
   * What one statement answers: its rows, the count of a write, or the SQLSTATE of its error.
   */
  protected static String answer(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement()) {
      if (!run.execute(statement))
        return "count " + run.getUpdateCount();
      try (final ResultSet rows = run.getResultSet()) {
        final List<String> answer = new ArrayList<>();
        final int columns = rows.getMetaData().getColumnCount();
        while (rows.next()) {
          final StringBuilder row = new StringBuilder();
          for (int i = 1; i <= columns; i++)
            row.append(i > 1 ? "|" : "").append(rows.getString(i));
          answer.add(row.toString());
        }
        return "rows " + answer;
      }
    } catch (final SQLException e) {
      return "error " + e.getSQLState();
    }
  }

  /** The message of the error of one statement, or an empty text when it runs. */
  protected static String errorMessage(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
      return "";
    } catch (final SQLException e) {
      return e.getMessage();
    }
  }

  protected static List<String> answers(final Connection connection, final List<String> statements) {
    final List<String> answers = new ArrayList<>(statements.size());
    for (final String statement : statements)
      answers.add(statement + " -> " + answer(connection, statement));
    return answers;
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

  private static Connection postgres(final String url, final boolean simpleProtocol) throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", "postgres");
    properties.setProperty("password", PASSWORD);
    if (simpleProtocol)
      properties.setProperty("preferQueryMode", "simple");
    SQLException last = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      try {
        return DriverManager.getConnection(url, properties);
      } catch (final SQLException e) {
        last = e;
        try {
          Thread.sleep(1000);
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
    throw last;
  }

  private Connection arcade(final boolean simpleProtocol) throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    properties.setProperty("ssl", "false");
    properties.setProperty("socketTimeout", "60");
    properties.setProperty("options", "-c arcadedb.language=postgresql");
    if (simpleProtocol)
      properties.setProperty("preferQueryMode", "simple");
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), properties);
  }
}
