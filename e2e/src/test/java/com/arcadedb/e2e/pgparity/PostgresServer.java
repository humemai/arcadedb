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

import com.arcadedb.query.postgresql.corpus.Json;
import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultTable;
import com.arcadedb.query.postgresql.corpus.Values;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A real PostgreSQL server for the parity harness: the official image in a Testcontainers container, one JDBC connection, and
 * the three things the harness asks of it: run a statement and keep its complete outcome, say what the parse step does with a
 * statement (without running it), and load a schema script.
 * <p>
 * The image is {@code postgres:18} unless {@code -Dpgparity.image=<name:tag>} says otherwise. The e2e module pins no image by
 * digest (it pulls {@code arcadedata/arcadedb:latest}), and the version string of the server that produced the corpus is
 * written into the header of the corpus files, so a drift of the minor version shows there.
 */
final class PostgresServer implements AutoCloseable {
  static final String IMAGE = System.getProperty("pgparity.image", "postgres:18");

  private static final String PASSWORD = "parity";

  private final GenericContainer<?> container;
  private final Connection          connection;

  private PostgresServer(final GenericContainer<?> container, final Connection connection) {
    this.container = container;
    this.connection = connection;
  }

  static PostgresServer start() throws Exception {
    final GenericContainer<?> container = new GenericContainer<>(IMAGE)
        .withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "parity"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(120))
        // the image logs this twice: once for the temporary server of the init scripts, once for the real one
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
    System.out.println("pgparity: starting " + IMAGE);
    container.start();

    final String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/parity";
    SQLException last = null;
    for (int attempt = 0; attempt < 30; attempt++) {
      try {
        final Connection connection = DriverManager.getConnection(url, "postgres", PASSWORD);
        connection.setAutoCommit(true);
        return new PostgresServer(container, connection);
      } catch (final SQLException e) {
        last = e;
        Thread.sleep(1000);
      }
    }
    container.stop();
    throw new IllegalStateException("PostgreSQL did not accept a connection at " + url, last);
  }

  /** {@code SELECT version()}: the string that goes into the header of the corpus files. */
  String version() throws SQLException {
    return singleString("SELECT version()");
  }

  /** The collation of the database, which decides how text sorts and compares. {@code unknown} when it cannot be read. */
  String collation() {
    try {
      return singleString("SELECT datcollate FROM pg_database WHERE datname = current_database()");
    } catch (final SQLException e) {
      return "unknown";
    }
  }

  private String singleString(final String sql) throws SQLException {
    try (final Statement statement = connection.createStatement(); final ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  /** Runs a script of statements (the schema and the data of the fixture). */
  void load(final String script) throws SQLException {
    try (final Statement statement = connection.createStatement()) {
      statement.execute(script);
    }
  }

  /** Runs one statement and keeps everything it answered, or the SQLSTATE of its refusal. */
  Outcome run(final String sql) {
    try (final Statement statement = connection.createStatement()) {
      statement.setQueryTimeout(60);
      if (!statement.execute(sql))
        return Outcome.answer(new ResultTable(List.of(), List.of(), List.of()));
      try (final ResultSet rs = statement.getResultSet()) {
        return Outcome.answer(read(rs));
      }
    } catch (final SQLException e) {
      if (e.getSQLState() == null)
        throw new IllegalStateException("The connection failed on: " + sql, e);
      return Outcome.refused(e.getSQLState(), e.getMessage());
    }
  }

  /**
   * What the parse step of the protocol does with a statement, without running it: {@code ok}, {@code syntax-error 42601}, or
   * {@code error <SQLSTATE>} for a statement that parsed and was refused by a later stage (an unknown table, a parameter whose
   * type cannot be determined). The driver sends the statement as a Parse and a Describe message and no Execute.
   */
  String parseOutcome(final String sql) {
    try (final PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.getMetaData();
      return "ok";
    } catch (final SQLException e) {
      final String state = e.getSQLState();
      if (state == null)
        throw new IllegalStateException("The connection failed on: " + sql, e);
      return "42601".equals(state) ? "syntax-error 42601" : "error " + state;
    }
  }

  private static ResultTable read(final ResultSet rs) throws SQLException {
    final ResultSetMetaData metaData = rs.getMetaData();
    final int columns = metaData.getColumnCount();
    final List<String> labels = new ArrayList<>();
    final List<String> types = new ArrayList<>();
    for (int i = 1; i <= columns; i++) {
      labels.add(metaData.getColumnLabel(i));
      types.add(metaData.getColumnTypeName(i));
    }
    final List<List<Object>> rows = new ArrayList<>();
    while (rs.next()) {
      final List<Object> row = new ArrayList<>(columns);
      for (int i = 1; i <= columns; i++)
        row.add(cell(rs, i, types.get(i - 1)));
      rows.add(row);
    }
    return new ResultTable(labels, types, rows);
  }

  private static Object cell(final ResultSet rs, final int index, final String type) throws SQLException {
    switch (type) {
    case "json", "jsonb" -> {
      final String text = rs.getString(index);
      return text == null ? null : Json.parse(text);
    }
    case "float4", "float8" -> {
      final double value = rs.getDouble(index);
      return rs.wasNull() ? null : Double.valueOf(value);
    }
    case "numeric" -> {
      return Values.normalize(rs.getBigDecimal(index));
    }
    default -> {
      if (type.startsWith("_"))
        return Values.normalize(rs.getArray(index));
      return Values.normalize(rs.getObject(index));
    }
    }
  }

  @Override
  public void close() {
    try {
      connection.close();
    } catch (final SQLException ignored) {
      // the container goes away anyway
    }
    container.stop();
  }
}
