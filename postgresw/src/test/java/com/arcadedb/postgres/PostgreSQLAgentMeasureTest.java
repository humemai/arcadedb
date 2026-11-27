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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent measure (fork issue #265): the statements of {@code agentmeasure/corpus.sql}, as an LLM wrote them, through JDBC on
 * PostgreSQL 18 and on the {@code postgresql} language of an in-process server. Each statement runs once, as written, and gives one
 * line of {@code target/pg-agent-measure/<script>.jsonl} with what each side answered.
 * {@code .github/scripts/pg_agent_measure.py} judges the answers and writes the page {@code docs/postgresql-agent-measure.md}. This
 * class judges nothing: it fails when a script cannot be run to its end, never because of an answer.
 * <p>
 * Each script starts with an empty database on each side. A second database of the PostgreSQL server, the shadow, runs only what
 * the language ran and keeps the transaction state of the language. So an answer that differs from PostgreSQL's only because an
 * earlier statement was refused equals the answer of the shadow, and can be told from one that differs on its own. A statement
 * that the language refused is tried on the shadow and rolled back.
 * <p>
 * Runs only with {@code -Dpgagent.measure=true} (the workflow pg-agent-measure.yml), and needs a Docker daemon.
 */
@EnabledIfSystemProperty(named = "pgagent.measure", matches = "true")
class PostgreSQLAgentMeasureTest extends PostgresWireProtocolTestBase {
  private static final String  IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String  PASSWORD = "measure";
  private static final int     ROW_CAP  = 1000;
  private static final Path    REPORTS  = Path.of("target", "pg-agent-measure");
  private static final Pattern ENDS     = Pattern.compile("\\s*(COMMIT|END|ROLLBACK|ABORT)\\b", Pattern.CASE_INSENSITIVE);

  private static final class Item {
    String               group;
    String               id;
    String               tags;
    String               sql;
    String               pgSql;
    boolean              viaSql;
    boolean              rollback;
    final List<String[]> params = new ArrayList<>();
  }

  private interface Opener {
    Connection open() throws Exception;
  }

  /** One connection of one side, opened again when a statement closed it. */
  private static final class Session implements AutoCloseable {
    private final Opener     opener;
    private       Connection connection;

    private Session(final Opener opener) {
      this.opener = opener;
    }

    Connection connection() throws Exception {
      if (connection == null || connection.isClosed())
        connection = opener.open();
      return connection;
    }

    @Override
    public void close() {
      if (connection != null)
        try {
          connection.close();
        } catch (final SQLException ignored) {
          // the server goes away anyway
        }
    }
  }

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** Each script starts from an empty database. */
  @Override
  protected void populateDatabase() {
  }

  @Test
  void memory() throws Exception {
    measure("memory", List.of("memory"));
  }

  @Test
  void web() throws Exception {
    measure("web", List.of("web", "web-transactions", "web-discovery", "web-mistakes"));
  }

  @Test
  void ddlProbes() throws Exception {
    measure("ddl-probes", List.of("ddl-probes"));
  }

  @Test
  void features() throws Exception {
    measure("features", List.of("features"));
  }

  @Test
  void design() throws Exception {
    measure("design", List.of("design"));
  }

  @Test
  void followUps() throws Exception {
    measure("followups", List.of("followups"));
  }

  @Test
  void followUpsTwo() throws Exception {
    measure("followups2", List.of("followups2"));
  }

  private void measure(final String script, final List<String> groups) throws Exception {
    final List<Item> items = corpus().stream().filter(item -> groups.contains(item.group)).toList();
    assertThat(items).as("statements of " + script).isNotEmpty();
    Files.createDirectories(REPORTS);

    try (final GenericContainer<?> container = postgresContainer();
        final Writer out = Files.newBufferedWriter(REPORTS.resolve(script + ".jsonl"), StandardCharsets.UTF_8)) {
      container.start();
      final String server = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/";
      // the primary database has the name of the database of ArcadeDB, so that current_database() answers the same
      try (final Session postgres = new Session(() -> openPostgres(server + getDatabaseName()));
          final Session shadow = new Session(() -> openPostgres(server + "shadow"));
          final Session language = new Session(this::openLanguage)) {
        final Map<String, Object> created = exec(postgres.connection(), "CREATE DATABASE shadow", List.of());
        assertThat(created.get("ok")).as(String.valueOf(created)).isEqualTo(true);
        writeAbout(postgres.connection());

        for (final Item item : items) {
          System.out.println("pg-agent-measure " + item.id);
          final String onPostgres = item.pgSql != null ? item.pgSql : item.sql;
          final Map<String, Object> line = new LinkedHashMap<>();
          line.put("group", item.group);
          line.put("id", item.id);
          line.put("tags", item.tags);
          line.put("sql", item.sql);
          line.put("pg", wrapped(postgres.connection(), onPostgres, item));
          final Map<String, Object> answer = item.viaSql ? embedded(item.sql) : wrapped(language.connection(), item.sql, item);
          line.put("arc", answer);
          final boolean ran = Boolean.TRUE.equals(answer.get("ok"));
          line.put("shadow", ran || item.rollback ?
              wrapped(shadow.connection(), onPostgres, item) :
              probe(shadow.connection(), onPostgres, item));
          if (!item.viaSql)
            mirror(language.connection(), shadow.connection());
          out.write(json(line));
          out.write('\n');
          out.flush();
        }
      }
    }
  }

  private GenericContainer<?> postgresContainer() {
    return new GenericContainer<>(IMAGE)
        .withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", getDatabaseName(), "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off", "-c", "timezone=UTC")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
  }

  private static Connection openPostgres(final String url) throws Exception {
    final Properties properties = new Properties();
    properties.setProperty("user", "postgres");
    properties.setProperty("password", PASSWORD);
    properties.setProperty("socketTimeout", "90");
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

  private Connection openLanguage() throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    properties.setProperty("sslmode", "disable");
    properties.setProperty("socketTimeout", "90");
    properties.setProperty("options", "-c arcadedb.language=postgresql");
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), properties);
  }

  /** What was measured: the server, the commit, the run, and the time. The workflow gives the commit and the run. */
  private static void writeAbout(final Connection postgres) throws Exception {
    final String version;
    try (final Statement statement = postgres.createStatement(); final ResultSet rs = statement.executeQuery("SELECT version()")) {
      rs.next();
      version = rs.getString(1);
    }
    Files.writeString(REPORTS.resolve("about.tsv"), "key\tvalue\n"
        + "PostgreSQL\t" + version + "\n"
        + "engine\t" + environment("PGAGENT_COMMIT") + "\n"
        + "CI run\t" + environment("PGAGENT_RUN_URL") + "\n"
        + "date\t" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\n", StandardCharsets.UTF_8);
  }

  private static String environment(final String name) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static List<Item> corpus() throws IOException {
    final String text;
    try (final InputStream in = PostgreSQLAgentMeasureTest.class.getResourceAsStream("/agentmeasure/corpus.sql")) {
      text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    final List<Item> items = new ArrayList<>();
    String group = "";
    Item item = null;
    String block = null;
    final StringBuilder body = new StringBuilder();
    for (final String line : (text + "\n--# end\n").split("\n")) {
      if (!line.startsWith("--# ") && !line.startsWith("--@ ") && !line.startsWith("--! ")) {
        body.append(line).append('\n');
        continue;
      }
      if (item != null && block != null) {
        final String value = body.toString().trim();
        if (block.equals("sql"))
          item.sql = value;
        else
          item.pgSql = value;
      }
      body.setLength(0);
      block = null;
      final String[] words = line.substring(4).trim().split(" ", 3);
      if (line.startsWith("--# ")) {
        item = null;
        if (words[0].equals("group"))
          group = words[1];
      } else if (line.startsWith("--@ ")) {
        item = new Item();
        item.group = group;
        item.id = words[0];
        item.tags = line.substring(4).trim().substring(words[0].length()).trim();
        items.add(item);
        block = "sql";
      } else {
        switch (words[0]) {
        case "pg" -> block = "pg";
        case "via-sql" -> item.viaSql = true;
        case "rollback" -> item.rollback = true;
        case "param" -> item.params.add(new String[] { words[1], words[2] });
        default -> throw new IllegalStateException("Unknown directive: " + line);
        }
      }
    }
    return items;
  }

  /** The statement, between BEGIN and ROLLBACK when the corpus says that it must not change the data. */
  private static Map<String, Object> wrapped(final Connection connection, final String sql, final Item item) {
    if (!item.rollback)
      return exec(connection, sql, item.params);
    exec(connection, "BEGIN", List.of());
    final Map<String, Object> result = exec(connection, sql, item.params);
    exec(connection, "ROLLBACK", List.of());
    return result;
  }

  /** What the shadow answers to a statement that the language refused, with nothing of it kept. */
  private static Map<String, Object> probe(final Connection shadow, final String sql, final Item item) throws SQLException {
    final TransactionState state = state(shadow);
    // a failed block takes only its end or a return to a savepoint: they run, and mirror fails the block again
    if (state == TransactionState.FAILED)
      return ENDS.matcher(sql).lookingAt() ?
          exec(shadow, sql, item.params) :
          failure("25P02", "the shadow is in a failed transaction block");
    if (state == TransactionState.IDLE) {
      exec(shadow, "BEGIN", List.of());
      final Map<String, Object> result = exec(shadow, sql, item.params);
      exec(shadow, "ROLLBACK", List.of());
      return result;
    }
    // the end of the block is not tried: it would keep what the language may not have kept
    if (ENDS.matcher(sql).lookingAt()) {
      final Map<String, Object> result = new LinkedHashMap<>();
      result.put("ok", true);
      result.put("count", 0);
      return result;
    }
    exec(shadow, "SAVEPOINT measure_probe", List.of());
    final Map<String, Object> result = exec(shadow, sql, item.params);
    exec(shadow, "ROLLBACK TO SAVEPOINT measure_probe", List.of());
    return result;
  }

  /** Puts the shadow into the transaction state of the language: a failed block, or no block. */
  private static void mirror(final Connection language, final Connection shadow) throws SQLException {
    final TransactionState wanted = state(language);
    final TransactionState current = state(shadow);
    if (wanted == TransactionState.FAILED && current != TransactionState.FAILED) {
      if (current == TransactionState.IDLE)
        exec(shadow, "BEGIN", List.of());
      exec(shadow, "SELECT 1/0", List.of());
    } else if (wanted == TransactionState.IDLE && current != TransactionState.IDLE)
      exec(shadow, "ROLLBACK", List.of());
  }

  private static TransactionState state(final Connection connection) throws SQLException {
    return connection.unwrap(BaseConnection.class).getTransactionState();
  }

  private static Map<String, Object> exec(final Connection connection, final String sql, final List<String[]> params) {
    try {
      if (params.isEmpty()) {
        try (final Statement statement = connection.createStatement()) {
          statement.setEscapeProcessing(false);
          statement.setQueryTimeout(30);
          return outcome(statement, statement.execute(sql));
        }
      }
      try (final PreparedStatement statement = connection.prepareStatement(sql)) {
        for (int i = 0; i < params.size(); i++)
          bind(connection, statement, i + 1, params.get(i)[0], params.get(i)[1]);
        statement.setQueryTimeout(30);
        return outcome(statement, statement.execute());
      }
    } catch (final SQLException e) {
      final Map<String, Object> result = failure(e.getSQLState(), e.getMessage());
      if (e instanceof PSQLException p && p.getServerErrorMessage() != null) {
        final ServerErrorMessage server = p.getServerErrorMessage();
        result.put("text", server.getMessage());
        result.put("detail", server.getDetail());
        result.put("hint", server.getHint());
      }
      return result;
    } catch (final RuntimeException e) {
      // a statement that the driver cannot handle (COPY, for one) is an answer too, not a failure of the measure
      return failure("client", String.valueOf(e));
    }
  }

  private static Map<String, Object> failure(final String state, final String message) {
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", false);
    result.put("state", state);
    result.put("message", message);
    return result;
  }

  private static void bind(final Connection connection, final PreparedStatement statement, final int index, final String type,
      final String value) throws SQLException {
    switch (type) {
    case "long" -> statement.setLong(index, Long.parseLong(value));
    case "int" -> statement.setInt(index, Integer.parseInt(value));
    case "string" -> statement.setString(index, value);
    case "timestamp" -> statement.setTimestamp(index, Timestamp.valueOf(value));
    case "bigint-array" -> statement.setArray(index,
        connection.createArrayOf("bigint", Arrays.stream(value.split(",")).map(Long::valueOf).toArray()));
    case "jsonb" -> {
      final PGobject object = new PGobject();
      object.setType("jsonb");
      object.setValue(value);
      statement.setObject(index, object);
    }
    default -> throw new IllegalStateException(type);
    }
  }

  private static Map<String, Object> outcome(final Statement statement, final boolean hasRows) throws SQLException {
    final Map<String, Object> result = new LinkedHashMap<>();
    result.put("ok", true);
    if (hasRows) {
      try (final ResultSet rs = statement.getResultSet()) {
        final ResultSetMetaData metaData = rs.getMetaData();
        final int columns = metaData.getColumnCount();
        final List<Object> names = new ArrayList<>();
        final List<Object> types = new ArrayList<>();
        for (int i = 1; i <= columns; i++)
          names.add(metaData.getColumnLabel(i));
        final List<Object> rows = new ArrayList<>();
        int count = 0;
        while (rs.next()) {
          count++;
          if (count > ROW_CAP)
            continue;
          final List<Object> row = new ArrayList<>();
          for (int i = 1; i <= columns; i++) {
            try {
              row.add(rs.getString(i));
            } catch (final SQLException | RuntimeException e) {
              row.add("client cannot read the value: " + e.getMessage());
            }
          }
          rows.add(row);
        }
        for (int i = 1; i <= columns; i++) {
          try {
            types.add(metaData.getColumnTypeName(i));
          } catch (final SQLException | RuntimeException e) {
            types.add("client cannot read the type: " + e.getMessage());
          }
        }
        result.put("columns", names);
        result.put("types", types);
        result.put("rows", rows);
        result.put("count", count);
      }
    } else
      result.put("count", statement.getUpdateCount());
    final List<Object> warnings = new ArrayList<>();
    for (SQLWarning w = statement.getWarnings(); w != null; w = w.getNextWarning())
      warnings.add(w.getMessage());
    if (!warnings.isEmpty())
      result.put("warnings", warnings);
    return result;
  }

  /** One statement of ArcadeDB's own sql: a setup step of a probe that the postgresql language cannot do. */
  private Map<String, Object> embedded(final String sql) {
    final Database database = getServerDatabase(0, getDatabaseName());
    try {
      database.transaction(() -> database.command("sql", sql).close());
      final Map<String, Object> result = new LinkedHashMap<>();
      result.put("ok", true);
      return result;
    } catch (final RuntimeException e) {
      return failure("sql", String.valueOf(e.getMessage()));
    }
  }

  private static String json(final Object value) {
    final StringBuilder out = new StringBuilder();
    append(out, value);
    return out.toString();
  }

  private static void append(final StringBuilder out, final Object value) {
    if (value == null)
      out.append("null");
    else if (value instanceof Boolean || value instanceof Integer || value instanceof Long)
      out.append(value);
    else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (final Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first)
          out.append(',');
        first = false;
        append(out, String.valueOf(entry.getKey()));
        out.append(':');
        append(out, entry.getValue());
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0)
          out.append(',');
        append(out, list.get(i));
      }
      out.append(']');
    } else {
      out.append('"');
      final String text = value.toString();
      for (int i = 0; i < text.length(); i++) {
        final char c = text.charAt(i);
        if (c == '"' || c == '\\')
          out.append('\\').append(c);
        else if (c < 0x20)
          out.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 0xf, 16));
        else
          out.append(c);
      }
      out.append('"');
    }
  }
}
