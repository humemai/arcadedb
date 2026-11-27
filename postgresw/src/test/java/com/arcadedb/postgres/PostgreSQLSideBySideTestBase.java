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
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
 * The same statements on PostgreSQL 18 and on the {@code postgresql} language, both over the PostgreSQL protocol: with the JDBC
 * driver, and with a Python script (psycopg and SQLAlchemy) that runs once against each server and has to print the same lines.
 * <p>
 * Needs a Docker daemon: without one the tests are skipped, except on GitHub Actions, where they fail.
 */
abstract class PostgreSQLSideBySideTestBase extends PostgresWireProtocolTestBase {
  private static final String IMAGE        = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PYTHON_IMAGE = "ghcr.io/astral-sh/uv:python3.13-bookworm-slim";
  private static final String PASSWORD     = "sidebyside";

  /** What differed, reported together at the end of a test. */
  protected final List<String> failures = new ArrayList<>();

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database starts with no type. */
  @Override
  protected void populateDatabase() {
  }

  /** A PostgreSQL 18 server with the time zone UTC and the {@code C} collation, started. */
  protected static GenericContainer<?> startPostgres() {
    requireDocker();
    final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "sidebyside", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off", "-c", "timezone=UTC")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
    container.start();
    return container;
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

  /** A connection to PostgreSQL, its session in UTC whatever the zone of this JVM; {@code parameters} go into the URL. */
  protected static Connection postgres(final GenericContainer<?> container, final String parameters) throws Exception {
    final String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/sidebyside" + parameters;
    SQLException last = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      try {
        return inUtc(DriverManager.getConnection(url, "postgres", PASSWORD));
      } catch (final SQLException e) {
        last = e;
        Thread.sleep(1000);
      }
    }
    throw new IllegalStateException("PostgreSQL did not accept a connection at " + url, last);
  }

  /** A connection to the {@code postgresql} language, its session in UTC whatever the zone of this JVM. */
  protected Connection arcade(final String parameters) throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    properties.setProperty("ssl", "false");
    properties.setProperty("socketTimeout", "60");
    properties.setProperty("options", "-c arcadedb.language=postgresql");
    return inUtc(DriverManager.getConnection(getServerPostgresJdbcUrl() + parameters, properties));
  }

  private static Connection inUtc(final Connection connection) throws SQLException {
    run(connection, "SET TIME ZONE 'UTC'");
    return connection;
  }

  protected static void run(final Connection connection, final String statement) throws SQLException {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
    } catch (final SQLException e) {
      throw new SQLException(connection.getMetaData().getURL() + ": " + statement + ": " + e.getMessage(), e.getSQLState(), e);
    }
  }

  /** The statement on both servers. A failure on the language is reported at the end, so that the rest still runs. */
  protected void both(final Connection postgres, final Connection arcade, final String statement) throws SQLException {
    run(postgres, statement);
    try {
      run(arcade, statement);
    } catch (final SQLException e) {
      failures.add(statement + "\n  PostgreSQL: runs\n  ArcadeDB:   " + e.getSQLState() + " " + e.getMessage());
    }
  }

  /** The labels, the type names, and the rows of the answer have to be equal. */
  protected void same(final Connection postgres, final Connection arcade, final String query) {
    compare(query, answer(postgres, query, true), answer(arcade, query, true));
  }

  /** As {@link #same}, without the type names: the columns of {@code information_schema} have domain types in PostgreSQL. */
  protected void sameValues(final Connection postgres, final Connection arcade, final String query) {
    compare(query, answer(postgres, query, false), answer(arcade, query, false));
  }

  protected void compare(final String what, final String expected, final String actual) {
    if (!expected.equals(actual))
      failures.add(what + "\n  PostgreSQL:\n" + expected + "  ArcadeDB:\n" + actual);
  }

  /** Both servers have to fail the statement with {@code code}. */
  protected void sameError(final Connection postgres, final Connection arcade, final String statement, final String code) {
    final String expected = state(postgres, statement);
    final String actual = state(arcade, statement);
    if (!code.equals(expected) || !code.equals(actual))
      failures.add(statement + "\n  expected: " + code + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
  }

  /** The language has to refuse the statement with {@code 0A000} and a message that says {@code advice}. */
  protected void refused(final Connection arcade, final String statement, final String advice) {
    try (final Statement run = arcade.createStatement()) {
      run.execute(statement);
      failures.add(statement + "\n  expected a refusal, and it ran");
    } catch (final SQLException e) {
      if (!"0A000".equals(e.getSQLState()) || !e.getMessage().contains(advice))
        failures.add(statement + "\n  expected 0A000 with: " + advice + "\n  ArcadeDB:   " + e.getSQLState() + " " + e.getMessage());
    }
  }

  protected static String state(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
      return "00000";
    } catch (final SQLException e) {
      return e.getSQLState();
    }
  }

  protected static String answer(final Connection connection, final String query, final boolean types) {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(query)) {
      return text(rows, types);
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }

  /** The labels and the rows of a result, a number by its value. */
  protected static String text(final ResultSet rows, final boolean types) throws SQLException {
    final ResultSetMetaData columns = rows.getMetaData();
    final StringBuilder out = new StringBuilder("    ");
    for (int i = 1; i <= columns.getColumnCount(); i++)
      out.append(columns.getColumnLabel(i)).append(types ? ":" + columns.getColumnTypeName(i) : "").append('|');
    out.append('\n');
    while (rows.next()) {
      out.append("    ");
      for (int i = 1; i <= columns.getColumnCount(); i++) {
        final Object value = rows.getObject(i);
        String text = value instanceof Number ? new BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            : String.valueOf(rows.getString(i));
        // the wire sends six digits of a second with every timestamp, and PostgreSQL no trailing zeros (fork issue #253)
        if ("timestamp".equals(columns.getColumnTypeName(i)))
          text = text.replaceFirst("(\\.\\d*?)0+$", "$1").replaceFirst("\\.$", "");
        out.append(text).append('|');
      }
      out.append('\n');
    }
    return out.toString();
  }

  /**
   * Runs {@code script} in a Python container against both servers, with the connection string as its argument, and compares
   * what it printed. The latest psycopg and SQLAlchemy, from PyPI, with uv.
   */
  protected void python(final GenericContainer<?> postgres, final String script) throws Exception {
    Testcontainers.exposeHostPorts(getServerPostgresPort(), postgres.getMappedPort(5432));
    try (final GenericContainer<?> python = new GenericContainer<>(PYTHON_IMAGE)
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep", "infinity"))) {
      python.start();
      python.execInContainer("mkdir", "-p", "/work");
      python.copyFileToContainer(Transferable.of(script), "/work/clients.py");
      final String expected = script(python, "host=host.testcontainers.internal port=" + postgres.getMappedPort(5432)
          + " dbname=sidebyside user=postgres password=" + PASSWORD + " sslmode=disable");
      final String actual = script(python, "host=host.testcontainers.internal port=" + getServerPostgresPort() + " dbname="
          + getDatabaseName() + " user=root password=" + DEFAULT_PASSWORD_FOR_TESTS
          + " sslmode=disable options='-c arcadedb.language=postgresql'");
      assertThat(actual).isEqualTo(expected);
    }
  }

  private static String script(final GenericContainer<?> python, final String connection) throws Exception {
    final Container.ExecResult result = python.execInContainer("uv", "run", "--no-project", "--with", "psycopg[binary]", "--with",
        "sqlalchemy", "python", "-I", "/work/clients.py", connection);
    assertThat(result.getExitCode()).as(connection + "\n" + result.getStdout() + "\n" + result.getStderr()).isZero();
    return result.getStdout();
  }
}
