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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code CREATE TABLE} and {@code CREATE INDEX} of the {@code postgresql} language against PostgreSQL 18 with pgvector (fork
 * issues #118 and #125): the same text runs on both over JDBC, and {@code SELECT *}, an {@code INSERT} without a column list, the columns and the keys of {@code information_schema} and
 * the SQLSTATE of each error have to be equal. The one text that differs is a table that an edge table refers to: PostgreSQL has
 * to declare the column {@code rid} that ArcadeDB gives every record.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLCreateTableTest extends PostgresWireProtocolTestBase {
  private static final String IMAGE    = System.getProperty("pgexamples.image", "pgvector/pgvector:pg18");
  private static final String PASSWORD = "createtable";

  private static final List<String> TABLES = List.of(
      "CREATE TABLE t1 (b int PRIMARY KEY, a text)",
      "CREATE TABLE t2 (id int, a text DEFAULT 'it''s', b bigint DEFAULT -5, c boolean DEFAULT 't', d numeric DEFAULT 1.50,"
          + " e double precision DEFAULT 2, f smallint DEFAULT '7', g int DEFAULT 5, h varchar DEFAULT 'x'::varchar, i real,"
          + " j date, k timestamp, l bytea, m jsonb, n int[], o text[], p bigint[], q boolean DEFAULT false, r int DEFAULT -1,"
          + " s bigint DEFAULT 10000000000, t double precision DEFAULT '1.5', u numeric DEFAULT '3', v int DEFAULT NULL,"
          + " w varchar NOT NULL DEFAULT 'w', x vector(3))",
      "CREATE TABLE t3 (a int, b int, c text, PRIMARY KEY (b, a), CONSTRAINT t3_c UNIQUE (c), UNIQUE (a, c))",
      "CREATE TABLE t4 (a int UNIQUE PRIMARY KEY, b int UNIQUE UNIQUE, c int NULL, d int CONSTRAINT d_given NOT NULL)",
      "CREATE TABLE t5 (cid int PRIMARY KEY, days date[], moments timestamp[] NOT NULL)",
      "CREATE TABLE IF NOT EXISTS t1 (other int)");

  private static final String KNOWS = "CREATE TABLE knows (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,"
      + " target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int, PRIMARY KEY (source_id, target_id))";
  private static final String TAGGED = "CREATE TABLE tagged (source_id text NOT NULL REFERENCES tag (rid) ON DELETE CASCADE,"
      + " target_id text NOT NULL REFERENCES tag (rid) ON DELETE CASCADE)";

  private static final List<String> INDEXES = List.of(
      "CREATE UNIQUE INDEX t1_a ON t1 (a)",
      "CREATE INDEX ON t1 (a, b)",
      "CREATE INDEX IF NOT EXISTS t1_a ON t1 (b)",
      "CREATE INDEX knows_source ON knows (source_id)",
      "CREATE INDEX ON t2 USING hnsw (x vector_l2_ops)",
      "CREATE TABLE near (id int PRIMARY KEY, e vector(3))",
      "INSERT INTO near VALUES (0, '[0,1,0]'), (1, '[1,1,0]'), (2, '[2,1,0]'), (3, '[3,1,0]'), (4, '[4,1,0]'), (5, '[5,1,0]'),"
          + " (6, '[6,1,0]'), (7, '[7,1,0]')",
      "CREATE INDEX ON near USING hnsw (e vector_l2_ops) WITH (m = 16, ef_construction = 64)");

  private static final List<String> ERRORS = List.of(
      "CREATE INDEX t1_a ON t1 (b)",
      "CREATE INDEX ON t1 (nosuch)",
      "CREATE INDEX ON nosuch (a)",
      "CREATE UNIQUE INDEX ON t4 (d)",
      "INSERT INTO t1 VALUES (9, 'x')",
      "CREATE INDEX ON near USING hnsw (e)",
      "CREATE INDEX ON near USING hnsw (e vector_cosine_ops) WITH (m = 1)",
      "CREATE TABLE t1 (a int)",
      "CREATE TABLE e (a int, a text)",
      "CREATE TABLE e (xmin int)",
      "CREATE TABLE e (a int, UNIQUE (b))",
      "CREATE TABLE e (a int, PRIMARY KEY (a, a))",
      "CREATE TABLE e (a int PRIMARY KEY, b int PRIMARY KEY)",
      "CREATE TABLE e (a int NULL NOT NULL)",
      "CREATE TABLE e (a int NULL PRIMARY KEY)",
      "CREATE TABLE e (a int DEFAULT 1 DEFAULT 2)",
      "CREATE TABLE e (a int,)",
      "CREATE TABLE e (a int DEFAULT 'x')",
      "CREATE TABLE e (a smallint DEFAULT '100000')",
      "CREATE TABLE e (a int DEFAULT true)",
      "CREATE TABLE e (a vector(0))",
      "CREATE TABLE nowhere.e (a int)",
      "CREATE TABLE e (source_id text NOT NULL REFERENCES nobody (rid) ON DELETE CASCADE,"
          + " target_id text NOT NULL REFERENCES nobody (rid) ON DELETE CASCADE)",
      "CREATE TABLE e (a int CONSTRAINT t1 UNIQUE)",
      "CREATE TABLE e (a int, b int, CONSTRAINT x UNIQUE (a), CONSTRAINT x UNIQUE (b))",
      "INSERT INTO t1 VALUES (NULL, 'x')",
      "INSERT INTO t1 VALUES (1, 'again')",
      "INSERT INTO t2 (x) VALUES ('[1,2]')",
      "INSERT INTO t5 (cid) VALUES (3)",
      "INSERT INTO t5 VALUES (4, '{abc}', '{}')");

  private final List<String> failures = new ArrayList<>();

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
  void createTableAnswersAsPostgreSqlDoes() throws Exception {
    requireDocker();
    try (final GenericContainer<?> container = new GenericContainer<>(IMAGE).withExposedPorts(5432)
        .withEnv(Map.of("POSTGRES_PASSWORD", PASSWORD, "POSTGRES_DB", "createtable", "POSTGRES_INITDB_ARGS",
            "--locale=C --encoding=UTF8"))
        .withCommand("postgres", "-c", "fsync=off")
        .withStartupTimeout(Duration.ofSeconds(180))
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))) {
      container.start();
      try (final Connection postgres = postgres(
          "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/createtable");
          final Connection arcade = arcade()) {
        run(postgres, "CREATE EXTENSION vector");

        for (final String table : TABLES) {
          run(postgres, table);
          run(arcade, table);
        }
        run(postgres, "CREATE TABLE person (id bigint PRIMARY KEY, name text,"
            + " rid text NOT NULL UNIQUE DEFAULT gen_random_uuid()::text)");
        run(arcade, "CREATE TABLE person (id bigint PRIMARY KEY, name text)");
        run(postgres, KNOWS);
        run(arcade, KNOWS);
        // a table without a column id (fork issue #151): the same edge table refers to its rid
        run(postgres, "CREATE TABLE tag (name text, rid text NOT NULL UNIQUE DEFAULT gen_random_uuid()::text)");
        run(arcade, "CREATE TABLE tag (name text)");
        run(postgres, TAGGED);
        run(arcade, TAGGED);

        for (final String write : List.of("INSERT INTO t1 VALUES (1, 'x'), (2, NULL)", "INSERT INTO t2 DEFAULT VALUES",
            "INSERT INTO t2 (id, v, x) VALUES (2, 3, '[1,2,3]')", "INSERT INTO t3 VALUES (1, 2, 'c')",
            "INSERT INTO t4 VALUES (1, NULL, NULL, 4), (2, NULL, 3, 4)", "INSERT INTO person VALUES (1, 'Ada'), (2, 'Bob')",
            "INSERT INTO knows SELECT a.rid, b.rid, 2020 FROM person a, person b WHERE a.id = 1 AND b.id = 2",
            "INSERT INTO t5 VALUES (1, '{2026-01-05,2026-02-01}', ARRAY['2026-01-05 10:20:30.123456'::timestamp])",
            "INSERT INTO t5 (cid, moments) VALUES (2, '{}')",
            "INSERT INTO tag (name) VALUES ('red'), ('blue')",
            "INSERT INTO tagged SELECT a.rid, b.rid FROM tag a, tag b WHERE a.name = 'red' AND b.name = 'blue'")) {
          run(postgres, write);
          run(arcade, write);
        }

        for (final String index : INDEXES) {
          run(postgres, index);
          run(arcade, index);
        }
        same(postgres, arcade, "SELECT id FROM near ORDER BY e <-> '[2.2,1,0]' LIMIT 4");

        for (final String table : List.of("t1", "t3", "t4"))
          same(postgres, arcade, "SELECT * FROM " + table + " ORDER BY 1");
        // the columns of t2 whose values both print alike
        same(postgres, arcade, "SELECT id, a, b, c, d, e, f, g, h, i, j, k, q, r, s, t, u, v, w FROM t2 ORDER BY id");
        same(postgres, arcade, "SELECT since FROM knows");
        sameArrays(postgres, arcade, "SELECT days, moments FROM t5 ORDER BY cid");
        same(postgres, arcade, "SELECT a.name AS source, b.name AS target FROM tag a JOIN tagged t ON t.source_id = a.rid"
            + " JOIN tag b ON t.target_id = b.rid");

        for (final String table : List.of("t1", "t2", "t3", "t4", "t5", "person", "knows", "tag", "tagged")) {
          // rid: PostgreSQL declares it, ArcadeDB lists the record id that it gives person and tag
          same(postgres, arcade, "SELECT column_name, ordinal_position, data_type, udt_name, is_nullable, column_default"
              + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '" + table
              + "' AND column_name <> 'rid' ORDER BY ordinal_position");
          same(postgres, arcade, "SELECT c.constraint_name, c.constraint_type, k.column_name, k.ordinal_position"
              + " FROM information_schema.table_constraints c JOIN information_schema.key_column_usage k"
              + " ON k.constraint_name = c.constraint_name AND k.table_name = c.table_name"
              + " WHERE c.table_name = '" + table + "' ORDER BY c.constraint_name, k.ordinal_position");
        }
        for (final String table : List.of("person", "tag"))
          same(postgres, arcade, "SELECT column_name, ordinal_position, data_type, is_nullable FROM information_schema.columns"
              + " WHERE table_name = '" + table + "' ORDER BY ordinal_position");
        same(postgres, arcade, "SELECT constraint_name, unique_constraint_name, match_option, update_rule, delete_rule"
            + " FROM information_schema.referential_constraints ORDER BY constraint_name");

        // a record id that ArcadeDB hides gives way to a column of the query around a subquery (fork issue #157)
        for (final String statement : List.of("CREATE TABLE memo (label text)", "CREATE TABLE ticket (rid int)",
            "INSERT INTO memo VALUES ('1')", "INSERT INTO ticket VALUES (7)")) {
          run(postgres, statement);
          run(arcade, statement);
        }
        for (final String query : List.of("SELECT name, (SELECT id FROM memo) AS pid FROM person ORDER BY name",
            "SELECT name FROM person WHERE (SELECT count(*) FROM memo WHERE id > 1) > 0",
            "SELECT name FROM person WHERE EXISTS (SELECT 1 FROM memo WHERE label = id::text)",
            "SELECT name FROM person WHERE NOT EXISTS (SELECT 1 FROM memo WHERE id = 1)",
            "SELECT name FROM person WHERE id::text IN (SELECT id::text FROM memo) ORDER BY name",
            "SELECT name, (SELECT count(*) FROM memo a, memo b WHERE id = 1) AS n FROM person ORDER BY name",
            "SELECT name, (SELECT (SELECT id FROM memo) FROM memo) AS pid FROM person ORDER BY name",
            "SELECT name, (SELECT count(*) FROM (SELECT label FROM memo WHERE id = 2) s) AS n FROM person ORDER BY name",
            "SELECT (SELECT rid FROM t2 LIMIT 1) AS r FROM ticket",
            // person.rid is listed, because knows refers to it: it is a column of its table on both servers
            "SELECT (SELECT rid <> '7' FROM person WHERE id = 1) AS other FROM ticket",
            "SELECT name FROM person WHERE rid = (SELECT rid FROM t2 LIMIT 1) ORDER BY name"))
          same(postgres, arcade, query);
        // within one FROM too a listed record id is a column of its table, and one that the catalog does not list gives way to it
        // (fork issue #163)
        for (final String query : List.of("SELECT name FROM person, memo WHERE rid = person.rid ORDER BY name",
            "SELECT name FROM memo JOIN person ON rid = person.rid ORDER BY name",
            "SELECT count(*) FROM person p, ticket t WHERE p.rid <> 'x' AND t.rid = 7"))
          same(postgres, arcade, query);
        for (final String ambiguous : List.of("SELECT rid FROM person, ticket", "SELECT rid FROM ticket, person",
            "SELECT 1 FROM person JOIN ticket ON rid = 7", "SELECT name FROM person, ticket WHERE rid = 7")) {
          final String expected = state(postgres, ambiguous);
          final String actual = state(arcade, ambiguous);
          if (!"42702".equals(expected) || !expected.equals(actual))
            failures.add(ambiguous + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
        }
        final String ungrouped = "SELECT name, count(*), (SELECT id FROM memo) FROM person GROUP BY name";
        assertThat(state(arcade, ungrouped)).isEqualTo(state(postgres, ungrouped)).isEqualTo("42803");

        for (final String statement : ERRORS) {
          final String expected = state(postgres, statement);
          final String actual = state(arcade, statement);
          if (!expected.equals(actual))
            failures.add(statement + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
        }

        run(postgres, "DELETE FROM person WHERE id = 2");
        run(arcade, "DELETE FROM person WHERE id = 2");
        same(postgres, arcade, "SELECT count(*) FROM knows");
      }
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
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

  private static void run(final Connection connection, final String statement) throws SQLException {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
    } catch (final SQLException e) {
      throw new SQLException(connection.getMetaData().getURL() + ": " + statement + ": " + e.getMessage(), e.getSQLState(), e);
    }
  }

  private static String state(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
      return "00000";
    } catch (final SQLException e) {
      return e.getSQLState();
    }
  }

  private void same(final Connection postgres, final Connection arcade, final String query) {
    final String expected = answer(postgres, query);
    final String actual = answer(arcade, query);
    if (!expected.equals(actual))
      failures.add(query + "\n  PostgreSQL:\n" + expected + "  ArcadeDB:\n" + actual);
  }

  /** An answer of array columns only: the two servers may print one array differently, so the elements are compared. */
  private void sameArrays(final Connection postgres, final Connection arcade, final String query) throws SQLException {
    final List<String> expected = arrays(postgres, query);
    final List<String> actual = arrays(arcade, query);
    if (!expected.equals(actual))
      failures.add(query + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
  }

  private static List<String> arrays(final Connection connection, final String query) throws SQLException {
    final List<String> out = new ArrayList<>();
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(query)) {
      while (rows.next())
        for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
          final java.sql.Array array = rows.getArray(i);
          out.add(rows.getMetaData().getColumnTypeName(i) + " "
              + (array == null ? "null" : Arrays.toString((Object[]) array.getArray())));
        }
    }
    return out;
  }

  /** The labels and the rows of an answer, a number by its value. */
  private static String answer(final Connection connection, final String query) {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(query)) {
      final ResultSetMetaData columns = rows.getMetaData();
      final StringBuilder out = new StringBuilder("    ");
      for (int i = 1; i <= columns.getColumnCount(); i++)
        out.append(columns.getColumnLabel(i)).append('|');
      out.append('\n');
      while (rows.next()) {
        out.append("    ");
        for (int i = 1; i <= columns.getColumnCount(); i++) {
          final Object value = rows.getObject(i);
          out.append(value instanceof Number ? new BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
              : String.valueOf(rows.getString(i))).append('|');
        }
        out.append('\n');
      }
      return out.toString();
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }
}
