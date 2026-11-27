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
 * {@code numeric ^ numeric} of the {@code postgresql} language against PostgreSQL 18 (fork issue #161): the same statement runs on
 * both over JDBC, and the type and the text of the value, or the SQLSTATE of the error, have to be equal.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLNumericPowerTest extends PostgresWireProtocolTestBase {
  private static final String IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PASSWORD = "numericpower";

  private static final List<String> STATEMENTS = List.of(
      // the four statements of numeric.sql
      "SELECT 10.0 ^ 2147483647 AS overflows",
      "SELECT 117743296169.0 ^ 1000000000 AS overflows",
      "SELECT (-1.0) ^ 2147483646",
      "SELECT (-1.0) ^ 2147483647",
      // results that numeric cannot hold, and the largest that it holds
      "SELECT 10.0 ^ 999999999",
      "SELECT 10.0 ^ 10000000000",
      "SELECT (-1.5) ^ 10000000001",
      "SELECT 10.0 ^ 131072",
      "SELECT length((10.0 ^ 131071)::text)",
      // the bases 1, -1 and 0
      "SELECT 1.0 ^ 2147483647",
      "SELECT 1.0 ^ -5",
      "SELECT (-1.0) ^ -3",
      "SELECT (-1.0) ^ -2147483648",
      "SELECT 1.0 ^ 1000000000000000000000",
      "SELECT (-1.0) ^ 9999999999999999999999",
      "SELECT 0.0 ^ 0",
      "SELECT 0.0 ^ 5",
      "SELECT 0.0 ^ 12345678901",
      "SELECT 0.0 ^ -1",
      "SELECT 0.0 ^ -10000000000",
      // smaller than the smallest scale
      "SELECT 0.5 ^ 2147483647",
      "SELECT 0.5 ^ 10000000000",
      // the scale and the rounding of other results
      "SELECT 2.5 ^ 2",
      "SELECT power(2.5, 2)",
      "SELECT 2.0 ^ 0",
      "SELECT 123.456 ^ 1",
      "SELECT (-3.5) ^ 7",
      "SELECT 1.1 ^ 30",
      "SELECT 2.0 ^ 100",
      "SELECT 3.0 ^ 1000",
      "SELECT 0.5 ^ 3000",
      "SELECT 99999.9999 ^ 200",
      "SELECT 1.0000001 ^ 100000000",
      "SELECT 0.9999999 ^ 100000000");

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
  void powerOfNumericAnswersAsPostgreSqlDoes() throws Exception {
    requireDocker();
    final List<String> failures = new ArrayList<>();
    try (final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "numericpower", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))) {
      container.start();
      try (final Connection postgres = postgres(
          "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/numericpower");
          final Connection arcade = arcade()) {
        for (final String statement : STATEMENTS) {
          final String expected = answer(postgres, statement);
          final String actual = answer(arcade, statement);
          if (!expected.equals(actual))
            failures.add(statement + "\n  PostgreSQL: " + shortened(expected) + "\n  ArcadeDB:   " + shortened(actual));
        }
      }
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  /** The type and the text of the one value, or the SQLSTATE of the error. */
  private static String answer(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(statement)) {
      rows.next();
      return rows.getMetaData().getColumnTypeName(1) + " " + rows.getString(1);
    } catch (final SQLException e) {
      return "error " + e.getSQLState();
    }
  }

  private static String shortened(final String text) {
    return text.length() <= 120 ? text : text.substring(0, 60) + "..." + text.substring(text.length() - 40) + " (" + text.length()
        + " characters)";
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
