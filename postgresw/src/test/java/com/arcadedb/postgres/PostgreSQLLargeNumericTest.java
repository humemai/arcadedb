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
import org.postgresql.PGResultSetMetaData;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Large {@code numeric} results of the {@code postgresql} language in the text and in the binary format of the wire protocol,
 * against PostgreSQL 18 (fork issue #171): the same statement runs on both over JDBC, once for each format, and the format, the
 * type, the scale and the digits of the value, or the SQLSTATE of the error, have to be equal.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLLargeNumericTest extends PostgresWireProtocolTestBase {
  private static final String IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PASSWORD = "largenumeric";

  private static final List<String> STATEMENTS = List.of(
      // more than 16000 digits before the decimal point
      "SELECT 10.0 ^ 20000",
      "SELECT 3.0 ^ 40000",
      "SELECT (-7.0) ^ 20001",
      "SELECT 3.0 ^ 40000 + 0.25",
      // the most digits that numeric holds before the decimal point, and one more
      "SELECT 10.0 ^ 131071",
      "SELECT 10.0 ^ 131072",
      // more than 16000 digits after the decimal point, up to the most that numeric holds
      "SELECT 0." + "0".repeat(16_000) + "1",
      "SELECT -0." + "1".repeat(16_383),
      // both
      "SELECT " + "9".repeat(131_072) + "." + "9".repeat(16_383));

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
  void aLargeNumericIsReadAsFromPostgreSqlInBothFormats() throws Exception {
    requireDocker();
    final List<String> failures = new ArrayList<>();
    try (final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "largenumeric", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))) {
      container.start();
      final String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/largenumeric";
      for (final boolean binary : new boolean[] { false, true })
        for (final String statement : STATEMENTS) {
          // a connection for each statement: a result that fails while it is sent may leave its connection unusable
          final String expected;
          try (final Connection postgres = postgres(url, binary)) {
            expected = answer(postgres, statement);
          }
          final String actual;
          try (final Connection arcade = arcade(binary)) {
            actual = answer(arcade, statement);
          }
          final String format = binary ? "binary" : "text";
          if (!expected.startsWith("error") && !expected.startsWith(format + " "))
            failures.add(shortened(statement) + "\n  PostgreSQL did not answer in " + format + ": " + shortened(expected));
          else if (!expected.equals(actual))
            failures.add(shortened(statement) + " [" + format + "]\n  PostgreSQL: " + shortened(expected) + "\n  ArcadeDB:   "
                + shortened(actual));
        }
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  /** The format, the type, the scale and the digits of the one value, or the SQLSTATE of the error. */
  private static String answer(final Connection connection, final String statement) {
    try (final PreparedStatement run = connection.prepareStatement(statement); final ResultSet rows = run.executeQuery()) {
      rows.next();
      final int format = ((PGResultSetMetaData) rows.getMetaData()).getFormat(1);
      final BigDecimal value = rows.getBigDecimal(1);
      return (format == 1 ? "binary " : "text ") + rows.getMetaData().getColumnTypeName(1) + " scale " + value.scale() + " "
          + value.toPlainString();
    } catch (final SQLException e) {
      return "error " + e.getSQLState();
    }
  }

  private static String shortened(final String text) {
    return text.length() <= 120 ? text : text.substring(0, 60) + "..." + text.substring(text.length() - 30) + " (" + text.length()
        + " characters, hash " + text.hashCode() + ")";
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

  /** The connection properties that make the driver ask for one result format. */
  private static Properties format(final boolean binary) {
    final Properties properties = new Properties();
    if (binary) {
      // -1: the driver asks for binary results from the first execution
      properties.setProperty("prepareThreshold", "-1");
      properties.setProperty("binaryTransferEnable", "1700");
    } else
      properties.setProperty("binaryTransfer", "false");
    properties.setProperty("socketTimeout", "60");
    return properties;
  }

  private static Connection postgres(final String url, final boolean binary) throws Exception {
    final Properties properties = format(binary);
    properties.setProperty("user", "postgres");
    properties.setProperty("password", PASSWORD);
    SQLException last = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      try {
        return DriverManager.getConnection(url, properties);
      } catch (final SQLException e) {
        last = e;
        Thread.sleep(1000);
      }
    }
    throw new IllegalStateException("PostgreSQL did not accept a connection at " + url, last);
  }

  private Connection arcade(final boolean binary) throws SQLException {
    final Properties properties = format(binary);
    properties.setProperty("user", "root");
    properties.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    properties.setProperty("ssl", "false");
    properties.setProperty("options", "-c arcadedb.language=postgresql");
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), properties);
  }
}
