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
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.postgres.ExamplesPage.Operation;
import com.arcadedb.postgres.ExamplesPage.Table;
import com.arcadedb.postgres.ExamplesPage.Target;
import com.arcadedb.query.postgresql.corpus.Json;
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The page {@code docs/postgresql-examples.md} shows each operation in ArcadeDB's {@code sql}, in the {@code postgresql} language,
 * in PostgreSQL 18 and in openCypher. This test reads the page ({@link ExamplesPage}), runs every block of it, and fails unless the
 * answers are the ones the page states: the table without a system name is what PostgreSQL 18 answers and what every other system
 * with a query must answer, a table that names a system is a difference, which must be that system's answer and must still differ.
 * A block that the page says a language refuses must fail in that language. So the page cannot drift from what runs.
 * <p>
 * The operations of the sections "Transactions" and "Errors a client sees" run over the PostgreSQL wire protocol (the in-process
 * server for the three ArcadeDB languages, JDBC for PostgreSQL), the others on an embedded database. PostgreSQL 18 is the pgvector
 * image, for the vector search, with the {@code C} collation of the parity harness.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails. It runs in the wire module job
 * of {@code pg-prototype-engine-tests.yml}.
 */
class PostgreSQLExamplesPageTest extends PostgresWireProtocolTestBase {
  private static final String IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PASSWORD = "examples";

  private final List<String> failures = new ArrayList<>();
  private       Path         scratch;
  private       int          embeddedCount;

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database of the wire protocol holds the data of the page and nothing else. */
  @Override
  protected void populateDatabase() {
    final ExamplesPage page = ExamplesPage.read();
    final Database database = getDatabases()[0];
    page.arcadeSchema().forEach(statement -> database.command("sql", statement).close());
    database.transaction(() -> page.arcadeData().forEach(statement -> database.command("sql", statement).close()));
  }

  @Test
  void everyStatementOfThePageAnswersWhatThePageSays() throws Exception {
    requireDocker();
    final ExamplesPage page = ExamplesPage.read();
    failures.addAll(page.problems());
    scratch = Files.createTempDirectory("pg-examples");

    try (final GenericContainer<?> container = postgres()) {
      container.start();
      final String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/examples";
      try (final Connection postgres = connect(url); final Embedded shared = new Embedded(page)) {
        load(postgres, page);
        System.out.println("pg-examples: " + single(postgres, "SELECT version()") + ", pgvector "
            + single(postgres, "SELECT extversion FROM pg_extension WHERE extname = 'vector'"));
        for (final Operation operation : page.operations()) {
          System.out.println("\n=== pg-examples " + operation.title());
          for (final Target target : Target.values()) {
            if (operation.statements().containsKey(target))
              guarded(operation, target, () -> {
                if (operation.wire())
                  runWire(page, operation, target, url, postgres);
                else if (target == Target.POSTGRES_18)
                  runPostgres(page, operation, postgres);
                else
                  runEmbedded(page, operation, target, shared);
              });
            if (operation.refused().containsKey(target))
              guarded(operation, target, () -> runRefused(page, operation, target));
          }
        }
      }
    } finally {
      try (final Stream<Path> files = Files.walk(scratch)) {
        files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }

    assertThat(page.operations()).as("operations read from the page").hasSizeGreaterThan(40);
    assertThat(failures).as("differences between " + ExamplesPage.FILE + " and what runs:\n\n" + String.join("\n\n", failures)).isEmpty();
  }

  @FunctionalInterface
  private interface Run {
    void run() throws Exception;
  }

  private void guarded(final Operation operation, final Target target, final Run run) {
    try {
      run.run();
    } catch (final Throwable t) {
      fail(operation, target, "the run broke: " + t);
      t.printStackTrace(System.out);
    }
  }

  private void fail(final Operation operation, final Target target, final String what) {
    final String message = "[" + operation.title() + "] " + target.label() + ": " + what;
    System.out.println("FAIL " + message);
    failures.add(message);
  }

  // ------------------------------------------------------------------------------------------------------------- the checks

  /** Compares one answer with the table the page states for this system, or with the common one. */
  private void check(final Operation operation, final Target target, final String kind, final Table observed, final Table common,
      final Table override, final String detail) {
    System.out.println(kind + ", " + target.label() + (detail.isEmpty() ? "" : " (" + detail + ")") + ":\n" + observed.markdown());
    final boolean ordered = operation.ordered();
    final Table expected = override != null ? override : common;
    if (!expected.matches(observed, ordered))
      fail(operation, target, kind + " differs from the page.\npage:\n" + expected.markdown() + "observed:\n" + observed.markdown() + detail);
    if (override != null && common.matches(observed, ordered))
      fail(operation, target, kind + " is stated as a difference, but it is the common answer");
  }

  // ------------------------------------------------------------------------------------------------------------ embedded

  /** An embedded database with the data of the page. */
  private final class Embedded implements AutoCloseable {
    private final DatabaseFactory factory;
    private final Database        database;

    private Embedded(final ExamplesPage page) {
      factory = new DatabaseFactory(scratch.resolve("db" + embeddedCount++).toString());
      database = factory.create();
      page.arcadeSchema().forEach(statement -> database.command("sql", statement).close());
      database.transaction(() -> page.arcadeData().forEach(statement -> database.command("sql", statement).close()));
    }

    @Override
    public void close() {
      if (database.isTransactionActive())
        database.rollback();
      database.drop();
      factory.close();
    }
  }

  /** A read runs on the shared database; a write, which has a check, on a database of its own. */
  private void runEmbedded(final ExamplesPage page, final Operation operation, final Target target, final Embedded shared) {
    final boolean write = !operation.check().isEmpty();
    try (final Embedded own = write ? new Embedded(page) : null) {
      final Database database = write ? own.database : shared.database;
      Table answer = null;
      String error = "";
      for (final String statement : operation.statements().get(target)) {
        try {
          answer = execute(database, target.language(), statement);
        } catch (final Throwable t) {
          answer = Table.state(sqlState(t));
          error = t.getClass().getSimpleName() + ": " + t.getMessage();
          break;
        }
      }
      if (operation.result() != null)
        check(operation, target, "Result", answer, operation.result(), operation.resultOverrides().get(target), error);
      if (write) {
        final Table after = error.isEmpty() ? execute(database, "postgresql", operation.check().getFirst()) : answer;
        check(operation, target, "After", after, operation.after(), operation.afterOverrides().get(target), error);
      }
    }
  }

  /** One statement, in the transaction that is open or else in one of its own, and every row of its answer. */
  private Table execute(final Database database, final String language, final String statement) {
    final Supplier<Table> run = () -> {
      try (final ResultSet resultSet = isQuery(statement) ? database.query(language, statement)
          : database.command(language, statement)) {
        return table(resultSet);
      }
    };
    if (isTransactionStatement(statement) || database.isTransactionActive())
      return run.get();
    final Table[] answer = new Table[1];
    database.transaction(() -> answer[0] = run.get());
    return answer[0];
  }

  private static Table table(final ResultSet resultSet) {
    final List<PostgreSQLResultSet.Column> columns = resultSet instanceof PostgreSQLResultSet answer ? answer.columns() : null;
    final List<String> keys = columns == null ? null : PostgreSQLResultSet.uniqueKeys(columns);
    final Set<String> header = new LinkedHashSet<>();
    if (columns != null)
      columns.forEach(column -> header.add(column.name()));
    final List<Map<String, String>> rows = new ArrayList<>();
    while (resultSet.hasNext()) {
      final Result result = resultSet.next();
      final Map<String, String> row = new LinkedHashMap<>();
      if (columns != null)
        for (int i = 0; i < columns.size(); i++)
          row.put(columns.get(i).name(), render(result.getProperty(keys.get(i))));
      else
        for (final String name : result.getPropertyNames())
          if (!name.startsWith("@")) {
            header.add(name);
            row.put(name, render(result.getProperty(name)));
          }
      rows.add(row);
    }
    return new Table(new ArrayList<>(header), rows);
  }

  /** A block that the page says the language refuses: every statement of it fails, on a database of its own. */
  private void runRefused(final ExamplesPage page, final Operation operation, final Target target) {
    for (final String statement : operation.refused().get(target)) {
      try (final Embedded own = new Embedded(page)) {
        Table answer = null;
        try {
          answer = execute(own.database, target.language(), statement);
        } catch (final Throwable t) {
          System.out.println("Refused, " + target.label() + " (" + sqlState(t) + "): " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        if (answer != null)
          fail(operation, target, "the page says that this text is refused, but it ran: " + statement + "\n" + answer.markdown());
      }
    }
  }

  // ------------------------------------------------------------------------------------------------------------ PostgreSQL

  private static GenericContainer<?> postgres() {
    return new GenericContainer<>(IMAGE)
        .withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "examples", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(180))
        // the image logs this twice: once for the temporary server of the init scripts, once for the real one
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
  }

  private static Connection connect(final String url) throws Exception {
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

  /** A fresh schema with the tables and the rows of the page. */
  private static void load(final Connection postgres, final ExamplesPage page) throws SQLException {
    try (final Statement statement = postgres.createStatement()) {
      statement.execute("DROP SCHEMA public CASCADE");
      statement.execute("CREATE SCHEMA public");
      for (final String sql : page.postgresSchema())
        statement.execute(sql);
      for (final String sql : page.postgresData())
        statement.execute(sql);
    }
  }

  private static String single(final Connection connection, final String sql) throws SQLException {
    try (final Statement statement = connection.createStatement(); final java.sql.ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private void runPostgres(final ExamplesPage page, final Operation operation, final Connection postgres) throws SQLException {
    final boolean write = !operation.check().isEmpty();
    if (write)
      load(postgres, page);
    Table answer = null;
    String error = "";
    for (final String sql : operation.statements().get(Target.POSTGRES_18)) {
      try {
        answer = jdbc(postgres, sql);
      } catch (final SQLException e) {
        answer = Table.state(e.getSQLState());
        error = e.getMessage();
        break;
      }
    }
    if (operation.result() != null)
      check(operation, Target.POSTGRES_18, "Result", answer, operation.result(), null, error);
    if (write) {
      final Table after = error.isEmpty() ? jdbc(postgres, operation.check().getFirst()) : answer;
      check(operation, Target.POSTGRES_18, "After", after, operation.after(), null, error);
      load(postgres, page);
    }
  }

  /** One statement through JDBC: its rows, or the SQLSTATE {@code 00000} of a statement that answers no rows. */
  private static Table jdbc(final Connection connection, final String sql) throws SQLException {
    try (final Statement statement = connection.createStatement()) {
      statement.setEscapeProcessing(false);
      statement.setQueryTimeout(60);
      if (!statement.execute(sql))
        return Table.state("00000");
      try (final java.sql.ResultSet rs = statement.getResultSet()) {
        final ResultSetMetaData metaData = rs.getMetaData();
        final List<String> header = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++)
          header.add(metaData.getColumnLabel(i));
        final List<Map<String, String>> rows = new ArrayList<>();
        while (rs.next()) {
          final Map<String, String> row = new LinkedHashMap<>();
          for (int i = 1; i <= metaData.getColumnCount(); i++)
            row.put(header.get(i - 1), render(cell(rs, i, metaData.getColumnTypeName(i))));
          rows.add(row);
        }
        return new Table(header, rows);
      }
    }
  }

  private static Object cell(final java.sql.ResultSet rs, final int index, final String type) throws SQLException {
    switch (type) {
    case "json", "jsonb", "vector" -> {
      final String text = rs.getString(index);
      return text == null ? null : Json.parse(text);
    }
    case "bytea" -> {
      return rs.getBytes(index);
    }
    default -> {
      return rs.getObject(index);
    }
    }
  }

  // ------------------------------------------------------------------------------------------------------------ the wire

  /** The statements of an operation on one connection, as a client sends them, and what the client sees. */
  private void runWire(final ExamplesPage page, final Operation operation, final Target target, final String url,
      final Connection postgres) throws SQLException {
    final Database server = getServerDatabase(0, getDatabaseName());
    if (target == Target.POSTGRES_18)
      load(postgres, page);
    else {
      server.transaction(() -> {
        server.command("sql", "DELETE FROM person").close();
        server.command("sql", "DELETE FROM memory").close();
      });
      server.transaction(() -> page.arcadeData().forEach(statement -> server.command("sql", statement).close()));
    }

    final boolean write = !operation.check().isEmpty();
    Table answer = null;
    String errors = "";
    try (final Connection client = target == Target.POSTGRES_18 ? DriverManager.getConnection(url, "postgres", PASSWORD)
        : wireClient(target)) {
      for (final String statement : operation.statements().get(target)) {
        try {
          final Table rows = jdbc(client, statement);
          answer = isQuery(statement) ? rows : Table.state("00000");
        } catch (final SQLException e) {
          answer = Table.state(e.getSQLState());
          errors += statement + " -> " + e.getSQLState() + " " + e.getMessage() + "\n";
        }
      }
    }
    if (operation.result() != null)
      check(operation, target, "Result", answer, operation.result(), operation.resultOverrides().get(target), errors);
    if (write) {
      if (!errors.isEmpty())
        fail(operation, target, "a statement failed:\n" + errors);
      final Table after = target == Target.POSTGRES_18 ? jdbc(postgres, operation.check().getFirst())
          : execute(server, "postgresql", operation.check().getFirst());
      check(operation, target, "After", after, operation.after(), operation.afterOverrides().get(target), errors);
    }
  }

  private Connection wireClient(final Target target) throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    properties.setProperty("sslmode", "disable");
    // sql is the language of a connection that names none
    if (target != Target.SQL)
      properties.setProperty("options", "-c arcadedb.language=" + target.language());
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), properties);
  }

  // ------------------------------------------------------------------------------------------------------------ helpers

  private static boolean isTransactionStatement(final String statement) {
    return statement.equals("BEGIN") || statement.equals("COMMIT") || statement.equals("ROLLBACK");
  }

  /** A statement that only reads: it goes through {@code query}, and its rows are an answer. */
  private static boolean isQuery(final String statement) {
    final String text = " " + statement.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
    if (Stream.of(" INSERT ", " UPDATE ", " DELETE ", " CREATE ", " MERGE ", " SET ").anyMatch(text::contains))
      return false;
    return Stream.of(" SELECT ", " WITH ", " MATCH ", " UNWIND ", " RETURN ", " CALL ").anyMatch(text::startsWith);
  }

  /** The SQLSTATE of the first exception of the cause chain that has one, else {@code error}. */
  private static String sqlState(final Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 20; depth++) {
      try {
        final Object state = current.getClass().getMethod("getSqlState").invoke(current);
        if (state instanceof String text && !text.isEmpty())
          return text;
      } catch (final ReflectiveOperationException ignored) {
        // not this one: look at its cause
      }
      current = current.getCause();
    }
    return "error";
  }

  /** A value as a cell of a table of the page. A text that looks like a number, a boolean or NULL is quoted. */
  private static String render(final Object value) {
    if (value instanceof byte[] bytes)
      return "\\x" + HexFormat.of().formatHex(bytes);
    return renderNormalized(Values.normalize(value));
  }

  private static String renderNormalized(final Object value) {
    if (value == null)
      return "NULL";
    if (value instanceof Boolean)
      return value.toString();
    if (value instanceof BigDecimal decimal)
      return ExamplesPage.number(decimal);
    if (value instanceof Double number)
      return Double.isFinite(number) ? ExamplesPage.number(BigDecimal.valueOf(number)) : number.toString();
    if (value instanceof List<?> list)
      return "[" + String.join(", ", list.stream().map(PostgreSQLExamplesPageTest::renderNormalized).toList()) + "]";
    if (value instanceof Map<?, ?> map) {
      final Map<String, String> sorted = new TreeMap<>();
      map.forEach((key, item) -> sorted.put(String.valueOf(key), renderNormalized(item)));
      return "{" + String.join(", ", sorted.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList()) + "}";
    }
    final String text = value.toString();
    return ExamplesPage.looksLikeAValue(text) ? "'" + text + "'" : text;
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
}
