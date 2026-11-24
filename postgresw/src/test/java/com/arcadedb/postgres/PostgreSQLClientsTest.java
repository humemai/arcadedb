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
import com.arcadedb.query.postgresql.SmallFixture;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code psql} and {@code psycopg} reach the {@code postgresql} language (fork issue #12): each runs in a container of its own
 * and connects to the in-process server through {@code host.testcontainers.internal}. The language is chosen the way a client
 * chooses it, with the startup parameter {@code options} ({@code PGOPTIONS} for {@code psql}), and the clients are the ones a
 * PostgreSQL user has: the {@code psql} of the PostgreSQL 18 image, and the latest {@code psycopg} that {@code uv} installs.
 * <p>
 * Needs a Docker daemon: without one the tests are skipped. They run in the wire module job of
 * {@code pg-prototype-engine-tests.yml}, where there is one.
 */
class PostgreSQLClientsTest extends PostgresWireProtocolTestBase {
  private static final String PSQL_IMAGE   = System.getProperty("pgparity.image", "postgres:18");
  private static final String PYTHON_IMAGE = "ghcr.io/astral-sh/uv:python3.13-bookworm-slim";

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database of every test is the small fixture and nothing else, so a catalog lists its four tables only. */
  @Override
  protected void populateDatabase() {
    SmallFixture.createSchema(getDatabases()[0]);
    SmallFixture.load(getDatabases()[0]);
  }

  private static void requireDocker() {
    boolean available;
    try {
      available = DockerClientFactory.instance().isDockerAvailable();
    } catch (final RuntimeException e) {
      available = false;
    }
    assumeTrue(available, "a Docker daemon is needed to start the client containers");
  }

  /** A container that sits there so that a client can be run in it, which reaches the in-process server. */
  private GenericContainer<?> client(final String image) {
    Testcontainers.exposeHostPorts(getServerPostgresPort());
    return new GenericContainer<>(image).withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep", "infinity"));
  }

  private static List<String> lines(final String text) {
    return Arrays.stream(text.strip().split("\\R")).map(String::strip).filter(line -> !line.isEmpty()).toList();
  }

  // ---------------------------------------------------------------------------------------------------------------- psql

  @Test
  void psqlRunsTheLanguageWithTheOptionOfItsEnvironment() throws Exception {
    requireDocker();
    try (final GenericContainer<?> psql = client(PSQL_IMAGE)) {
      psql.start();

      final String join = "SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid";
      final Container.ExecResult rows = run(psql, "-q -A -t -F '|' -f /work/join.sql", "/work/join.sql", join + ";\n");
      assertThat(rows.getExitCode()).as(rows.getStderr()).isZero();
      assertThat(lines(rows.getStdout())).containsExactly("Ada|100", "Ada|101", "Bo|102", "Di|103", "Di|104");

      // the same text with no option is ArcadeDB SQL, which has no JOIN ... ON between types
      final Container.ExecResult sql = runWith(psql, "", "-q -A -t -F '|' -v ON_ERROR_STOP=1 -f /work/join.sql");
      assertThat(sql.getExitCode()).isNotZero();

      // a message of several statements, in one Query message
      final Container.ExecResult block = run(psql, "-q -A -t -c 'BEGIN; SELECT count(*) FROM person; COMMIT;'", null, null);
      assertThat(block.getExitCode()).as(block.getStderr()).isZero();
      assertThat(lines(block.getStdout())).containsExactly("5");

      // the extended protocol, with a value that has no declared type
      final Container.ExecResult bind = run(psql, "-q -A -t -f /work/bind.sql", "/work/bind.sql",
          "SELECT name FROM person WHERE pid = $1 AND city = $2 \\bind 2 Delft \\g\n");
      assertThat(bind.getExitCode()).as(bind.getStderr()).isZero();
      assertThat(lines(bind.getStdout())).containsExactly("Bo");

      // an error has its SQLSTATE and its position: psql shows the line with a caret under the place
      final Container.ExecResult error = run(psql, "-q -v VERBOSITY=verbose -c 'SELECT name FROM person WHERE'", null, null);
      assertThat(error.getExitCode()).isNotZero();
      assertThat(error.getStderr()).contains("42601").contains("syntax error at end of input").contains("LINE 1: SELECT name FROM person WHERE");

      // the catalog, as psql asks for it
      final Container.ExecResult tables = run(psql, "-q -A -t -F '|' -c '\\dt'", null, null);
      assertThat(tables.getExitCode()).as(tables.getStderr()).isZero();
      assertThat(tables.getStdout()).contains("|company|").contains("|orders|").contains("|person|").contains("|works_at|");
      final Container.ExecResult describe = run(psql, "-q -A -t -F '|' -c '\\d works_at'", null, null);
      assertThat(describe.getExitCode()).as(describe.getStderr()).isZero();
      assertThat(describe.getStdout()).contains("since");
    }
  }

  /** Runs {@code psql} with the language of the connection chosen by {@code PGOPTIONS}, after writing {@code content} to {@code path}. */
  private Container.ExecResult run(final GenericContainer<?> psql, final String arguments, final String path, final String content)
      throws Exception {
    if (path != null) {
      psql.execInContainer("mkdir", "-p", "/work");
      psql.copyFileToContainer(Transferable.of(content), path);
    }
    return runWith(psql, "-c arcadedb.language=postgresql", arguments);
  }

  private Container.ExecResult runWith(final GenericContainer<?> psql, final String options, final String arguments) throws Exception {
    final String command = "PGPASSWORD=" + DEFAULT_PASSWORD_FOR_TESTS + " PGSSLMODE=disable PGCONNECT_TIMEOUT=30"
        + (options.isEmpty() ? "" : " PGOPTIONS='" + options + "'") + " psql -X -h host.testcontainers.internal -p "
        + getServerPostgresPort() + " -U root -d " + getDatabaseName() + " " + arguments;
    return psql.execInContainer("sh", "-c", command);
  }

  // ------------------------------------------------------------------------------------------------------------ psycopg

  private static final String PSYCOPG_SCRIPT = """
      import json
      import psycopg

      CONNINFO = ("host=host.testcontainers.internal port=@PORT@ dbname=@DATABASE@ user=root password=@PASSWORD@ sslmode=disable "
                  "options='-c arcadedb.language=postgresql'")


      def show(label, value):
          print(label + "|" + json.dumps(value, default=str))


      with psycopg.connect(CONNINFO, autocommit=True) as conn:
          show("language", conn.execute("SHOW arcadedb.language").fetchall())
          show("join", conn.execute("SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid").fetchall())
          # a str has no declared type, an int is an int2
          show("param", conn.execute("SELECT name FROM person WHERE pid = %s AND city = %s", (2, "Delft")).fetchall())
          cur = conn.execute("SELECT oid, total, placed, tags FROM orders WHERE oid = 100")
          show("columns", [column.name for column in cur.description])
          show("row", cur.fetchall())
          cur = conn.execute("INSERT INTO person (pid, name, age, city) VALUES (%s, %s, %s, %s) RETURNING pid, name",
                             (90, "Py", 33, "Delft"))
          show("insert", [cur.fetchall(), cur.rowcount])
          show("update", conn.execute("UPDATE person SET age = age + 1 WHERE pid = %s", (90,)).rowcount)
          show("delete", conn.execute("DELETE FROM person WHERE pid = %s", (90,)).rowcount)
          try:
              conn.execute("SELECT nosuch FROM person")
          except psycopg.Error as e:
              show("undefined column", [e.sqlstate])
          try:
              conn.execute("SELECT name FROM person WHERE")
          except psycopg.Error as e:
              show("syntax", [e.sqlstate, int(e.diag.statement_position)])
          show("still usable", conn.execute("SELECT count(*) FROM person").fetchall())

      # a transaction block that is rolled back, and one that is committed
      with psycopg.connect(CONNINFO) as conn:
          conn.execute("INSERT INTO person (pid, name, age, city) VALUES (91, 'Tx', 1, 'Delft')")
          conn.rollback()
          show("after rollback", conn.execute("SELECT count(*) FROM person").fetchall())
          conn.execute("INSERT INTO person (pid, name, age, city) VALUES (92, 'Tx', 1, 'Delft')")
          conn.commit()
      with psycopg.connect(CONNINFO) as conn:
          show("after commit", conn.execute("SELECT count(*) FROM person").fetchall())
      """;

  @Test
  void psycopgRunsTheLanguageWithTheOptionOfItsConnection() throws Exception {
    requireDocker();
    try (final GenericContainer<?> python = client(PYTHON_IMAGE)) {
      python.start();
      python.execInContainer("mkdir", "-p", "/work");
      python.copyFileToContainer(Transferable.of(PSYCOPG_SCRIPT.replace("@PORT@", String.valueOf(getServerPostgresPort()))
          .replace("@DATABASE@", getDatabaseName()).replace("@PASSWORD@", DEFAULT_PASSWORD_FOR_TESTS)), "/work/clients.py");
      // the latest psycopg, from PyPI, with uv: the project rule is uv, and no pip
      final Container.ExecResult result = python.execInContainer("uv", "run", "--no-project", "--with", "psycopg[binary]", "python", "-I",
          "/work/clients.py");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();

      final List<String> out = lines(result.getStdout());
      assertThat(out).contains("language|[[\"postgresql\"]]",
          "join|[[\"Ada\", 100], [\"Ada\", 101], [\"Bo\", 102], [\"Di\", 103], [\"Di\", 104]]", "param|[[\"Bo\"]]",
          "columns|[\"oid\", \"total\", \"placed\", \"tags\"]", "row|[[100, \"25.50\", \"2026-01-05\", [\"gift\", \"express\"]]]",
          "insert|[[[90, \"Py\"]], 1]", "update|1", "delete|1", "undefined column|[\"42703\"]", "syntax|[\"42601\", 30]",
          "still usable|[[5]]", "after rollback|[[5]]", "after commit|[[6]]");
    }
  }
}
