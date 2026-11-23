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
import org.postgresql.PGConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
  /** The parse step has a connection of its own, so that a problem of the driver with an odd statement cannot upset the answers. */
  private final ParseSession        parseSession;
  private final List<ParseSession>  extraSessions = new ArrayList<>();
  private final String              url;
  /** The message of the server when {@code SHOW lc_collate} was refused, else {@code null}. */
  String                            showFailure;

  private PostgresServer(final GenericContainer<?> container, final String url, final Connection connection,
      final Connection parseConnection) {
    this.container = container;
    this.url = url;
    this.connection = connection;
    this.parseSession = new ParseSession(url, parseConnection);
  }

  static PostgresServer start() throws Exception {
    final GenericContainer<?> container = new GenericContainer<>(IMAGE)
        .withExposedPorts(5432)
        // The engine orders and compares text by code point (the byte order of UTF-8), which is the C collation of PostgreSQL, so the
        // server the engine is compared with runs with C too. The C locale makes initdb pick SQL_ASCII unless the encoding is given.
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "parity", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
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
        final Connection parseConnection = DriverManager.getConnection(url, "postgres", PASSWORD);
        parseConnection.setAutoCommit(true);
        return new PostgresServer(container, url, connection, parseConnection);
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

  /**
   * The collation of the database, which decides how text sorts and compares, read back with {@code SHOW lc_collate} and
   * {@code SHOW lc_ctype}, and where the server does not have those settings (PostgreSQL 18 has not: {@link #showFailure})
   * from {@code pg_database}: {@code C} when both are C, else both values. {@code unknown} when it cannot be read.
   */
  String collation() {
    try {
      return both(singleString("SHOW lc_collate"), singleString("SHOW lc_ctype"));
    } catch (final SQLException show) {
      showFailure = show.getMessage();
    }
    try {
      return both(singleString("SELECT datcollate FROM pg_database WHERE datname = current_database()"),
          singleString("SELECT datctype FROM pg_database WHERE datname = current_database()"));
    } catch (final SQLException e) {
      return "unknown";
    }
  }

  private static String both(final String collate, final String ctype) {
    return collate.equals(ctype) ? collate : "lc_collate=" + collate + " lc_ctype=" + ctype;
  }

  /** The encoding of the database ({@code SHOW server_encoding}), {@code UTF8} for a server started by {@link #start()}. */
  String encoding() throws SQLException {
    return singleString("SHOW server_encoding");
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

  /**
   * Copies a tab separated file into a table with {@code COPY <table> FROM STDIN} (the text format: the columns of a line are the
   * columns of the table), and returns the number of rows the server read.
   */
  long copyIn(final String table, final Path file) throws SQLException, IOException {
    try (final Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return connection.unwrap(PGConnection.class).getCopyAPI().copyIn("COPY " + table + " FROM STDIN", reader);
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
    return parseSession.parseOutcome(sql);
  }

  /**
   * A new database of this server named {@code name}, with {@code script} (a schema, no rows) run in it, and a parse session on it.
   * The statements of a corpus that is written against another schema than the {@code small} fixture are parsed there, so that the
   * server binds their names. The session is closed with the server.
   */
  ParseSession createDatabase(final String name, final String script) throws SQLException {
    try (final Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    final String databaseUrl = url.substring(0, url.lastIndexOf('/') + 1) + name;
    final Connection loading = DriverManager.getConnection(databaseUrl, "postgres", PASSWORD);
    loading.setAutoCommit(true);
    try (final Statement statement = loading.createStatement()) {
      statement.execute(script);
    }
    final ParseSession session = new ParseSession(databaseUrl, loading);
    extraSessions.add(session);
    return session;
  }

  /** The parse step on one connection, which is replaced when the driver fails on a statement. */
  static final class ParseSession {
    private final String url;
    private       Connection connection;

    private ParseSession(final String url, final Connection connection) {
      this.url = url;
      this.connection = connection;
    }

    /** See {@link PostgresServer#parseOutcome(String)}: the same step, on the database of this session. */
    String parseOutcome(final String sql) {
      // The driver counts the parameters of a statement by its ? markers and fails on the $1 of the protocol, so each $n is
      // sent as ?: the driver turns it back into a numbered parameter, and the server parses the same statement
      final String text = sql.replaceAll("\\$(\\d+)", "?");
      try (final PreparedStatement statement = connection.prepareStatement(text)) {
        statement.getMetaData();
        return "ok";
      } catch (final RuntimeException e) {
        // the driver is in an unknown state after this: a connection of its own for the next statement
        try {
          connection.close();
        } catch (final SQLException ignored) {
          // replaced below
        }
        try {
          connection = DriverManager.getConnection(url, "postgres", PASSWORD);
          connection.setAutoCommit(true);
        } catch (final SQLException reconnect) {
          throw new IllegalStateException("Cannot reconnect after a driver failure on: " + sql, reconnect);
        }
        throw new IllegalStateException("The driver failed on the statement: " + sql + ": " + e, e);
      } catch (final SQLException e) {
        final String state = e.getSQLState();
        if (state == null)
          throw new IllegalStateException("The connection failed on: " + sql, e);
        return "42601".equals(state) ? "syntax-error 42601" : "error " + state;
      }
    }

    void close() {
      try {
        connection.close();
      } catch (final SQLException ignored) {
        // the container goes away anyway
      }
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
    for (final ParseSession each : extraSessions)
      each.close();
    parseSession.close();
    try {
      connection.close();
    } catch (final SQLException ignored) {
      // the container goes away anyway
    }
    container.stop();
  }
}
