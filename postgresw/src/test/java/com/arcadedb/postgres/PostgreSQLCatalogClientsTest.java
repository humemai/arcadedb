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
 * The relational catalog of the {@code postgresql} language seen by the clients of a PostgreSQL user (fork issue #12, step W2): the
 * {@code \dt} and {@code \d} meta-commands of {@code psql} 18, and the reflection of SQLAlchemy over {@code psycopg}. Each client
 * runs in a container of its own and connects to the in-process server through {@code host.testcontainers.internal}, the language
 * chosen the way a client chooses it, with the startup parameter {@code options}.
 * <p>
 * <b>SQLAlchemy, what works and what does not.</b> {@code get_columns} is answered. {@code get_table_names} lists the four tables and also
 * the names of the indexes (it filters with {@code relkind = ANY (ARRAY[...])}, an array constructor this catalog does not evaluate, so
 * the filter is not applied), and {@code get_foreign_keys} lists each key more than once (a left join of {@code pg_class} to
 * {@code pg_constraint} and {@code pg_description} that is not reduced to one row per constraint). {@code get_pk_constraint},
 * {@code get_unique_constraints} and {@code get_indexes} are refused: they unnest {@code conkey} with {@code unnest} and
 * {@code generate_subscripts} in a select list, aggregate with {@code array_agg ... ORDER BY}, and read {@code pg_opclass}. None of
 * that is a relational name: it is the evaluator of the wire catalog, and each is its own issue.
 * <p>
 * Needs a Docker daemon: without one the tests are skipped. They run in the wire module job of
 * {@code pg-prototype-engine-tests.yml}, where there is one.
 */
class PostgreSQLCatalogClientsTest extends PostgresWireProtocolTestBase {
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

  /** The lines of a text with the white space of each collapsed to one blank and the empty ones left out: what psql aligns with. */
  private static List<String> lines(final String text) {
    return Arrays.stream(text.strip().split("\\R")).map(line -> line.strip().replaceAll("\\s+", " ")).filter(line -> !line.isEmpty())
        .toList();
  }

  /** Runs {@code psql} with the language of the connection chosen by {@code PGOPTIONS}. */
  private Container.ExecResult psql(final GenericContainer<?> container, final String arguments) throws Exception {
    final String command = "PGPASSWORD=" + DEFAULT_PASSWORD_FOR_TESTS + " PGSSLMODE=disable PGCONNECT_TIMEOUT=30"
        + " PGOPTIONS='-c arcadedb.language=postgresql' psql -X -h host.testcontainers.internal -p " + getServerPostgresPort()
        + " -U root -d " + getDatabaseName() + " " + arguments;
    return container.execInContainer("sh", "-c", command);
  }

  // ---------------------------------------------------------------------------------------------------------------- psql

  @Test
  void psqlListsTheTablesAndDescribesThemWithTheirKeys() throws Exception {
    requireDocker();
    try (final GenericContainer<?> container = client(PSQL_IMAGE)) {
      container.start();

      final Container.ExecResult tables = psql(container, "-q -A -t -F '|' -c '\\dt'");
      assertThat(tables.getExitCode()).as(tables.getStderr()).isZero();
      assertThat(lines(tables.getStdout()).stream().map(line -> line.substring(0, line.lastIndexOf('|'))).toList())
          .containsExactly("public|company|table", "public|orders|table", "public|person|table", "public|works_at|table");

      final Container.ExecResult edge = psql(container, "-q -c '\\d works_at'");
      assertThat(edge.getExitCode()).as(edge.getStderr()).isZero();
      assertThat(lines(edge.getStdout())).containsSubsequence("Table \"public.works_at\"", "id | text | | not null | generated always as identity",
          "source_id | text | | not null |", "target_id | text | | not null |", "since | integer | | |", "Indexes:",
          "\"works_at_pkey\" PRIMARY KEY, btree (id)", "Foreign-key constraints:",
          "\"works_at_source_id_fkey\" FOREIGN KEY (source_id) REFERENCES person(id)",
          "\"works_at_target_id_fkey\" FOREIGN KEY (target_id) REFERENCES company(id)");

      final Container.ExecResult vertex = psql(container, "-q -c '\\d person'");
      assertThat(vertex.getExitCode()).as(vertex.getStderr()).isZero();
      assertThat(lines(vertex.getStdout())).containsSubsequence("Table \"public.person\"", "id | text | | not null | generated always as identity",
          "age | integer | | |", "city | character varying | | |", "name | character varying | | |", "pid | integer | | |", "Indexes:",
          "\"person_pkey\" PRIMARY KEY, btree (id)", "\"person_pid_key\" UNIQUE CONSTRAINT, btree (pid)", "Referenced by:",
          "TABLE \"orders\" CONSTRAINT \"orders_buyer_fkey\" FOREIGN KEY (buyer) REFERENCES person(id)",
          "TABLE \"works_at\" CONSTRAINT \"works_at_source_id_fkey\" FOREIGN KEY (source_id) REFERENCES person(id)");

      // a table with a link has the foreign key of it
      final Container.ExecResult document = psql(container, "-q -c '\\d orders'");
      assertThat(document.getExitCode()).as(document.getStderr()).isZero();
      assertThat(lines(document.getStdout())).containsSubsequence("Table \"public.orders\"", "id | text | | not null | generated always as identity",
          "\"orders_pkey\" PRIMARY KEY, btree (id)", "\"orders_oid_key\" UNIQUE CONSTRAINT, btree (oid)",
          "Foreign-key constraints:", "\"orders_buyer_fkey\" FOREIGN KEY (buyer) REFERENCES person(id)");
    }
  }

  // ------------------------------------------------------------------------------------------------------------ SQLAlchemy

  private static final String REFLECTION_SCRIPT = """
      import json
      import logging
      import sys

      from sqlalchemy import create_engine, inspect
      from sqlalchemy.engine import URL

      url = URL.create("postgresql+psycopg", username="root", password="@PASSWORD@", host="host.testcontainers.internal",
                       port=@PORT@, database="@DATABASE@",
                       query={"sslmode": "disable", "options": "-c arcadedb.language=postgresql"})
      # the statements SQLAlchemy sends, on stderr, which the test shows when a check fails
      logging.basicConfig(stream=sys.stderr)
      logging.getLogger("sqlalchemy.engine").setLevel(logging.INFO)
      engine = create_engine(url)
      inspector = inspect(engine)


      def show(label, function):
          # one line per call, so that one failure does not hide the others
          try:
              print(label + "|" + json.dumps(function(), default=str))
          except Exception as e:
              print(label + "|ERROR " + type(e).__name__ + " " + str(e).replace("\\n", " ")[:300])


      show("tables", lambda: inspector.get_table_names())
      show("columns", lambda: [[c["name"], str(c["type"]), c["nullable"]] for c in inspector.get_columns("works_at")])
      show("pk", lambda: [inspector.get_pk_constraint("works_at")["constrained_columns"],
                          inspector.get_pk_constraint("works_at")["name"]])
      show("fks", lambda: sorted([fk["name"], fk["constrained_columns"], fk["referred_table"], fk["referred_columns"]]
                                 for fk in inspector.get_foreign_keys("works_at")))
      show("orders fks", lambda: [[fk["name"], fk["constrained_columns"], fk["referred_table"], fk["referred_columns"]]
                                  for fk in inspector.get_foreign_keys("orders")])
      show("unique", lambda: [[u["name"], u["column_names"]] for u in inspector.get_unique_constraints("person")])
      show("indexes", lambda: sorted([i["name"], i["column_names"], i["unique"]] for i in inspector.get_indexes("person")))
      """;

  @Test
  void sqlalchemyReflectsTheTablesColumnsAndKeys() throws Exception {
    requireDocker();
    try (final GenericContainer<?> python = client(PYTHON_IMAGE)) {
      python.start();
      python.execInContainer("mkdir", "-p", "/work");
      python.copyFileToContainer(Transferable.of(REFLECTION_SCRIPT.replace("@PORT@", String.valueOf(getServerPostgresPort()))
          .replace("@DATABASE@", getDatabaseName()).replace("@PASSWORD@", DEFAULT_PASSWORD_FOR_TESTS)), "/work/reflect.py");
      // the latest SQLAlchemy and psycopg, from PyPI, with uv: the project rule is uv, and no pip
      final Container.ExecResult result = python.execInContainer("uv", "run", "--no-project", "--with", "sqlalchemy", "--with",
          "psycopg[binary]", "python", "-I", "/work/reflect.py");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();

      final List<String> out = Arrays.stream(result.getStdout().strip().split("\\R")).toList();

      // what SQLAlchemy reads from this catalog today
      assertThat(out).as(result.getStderr()).contains(
          "columns|[[\"id\", \"TEXT\", false], [\"source_id\", \"TEXT\", false], [\"target_id\", \"TEXT\", false], [\"since\", \"INTEGER\", true]]");
      // the four tables are in the list; the list has more, see the class comment
      assertThat(out).as(result.getStderr())
          .anyMatch(line -> line.startsWith("tables|[\"company\", \"orders\", \"person\", \"works_at\""));
      // both foreign keys are in the list, each of them more than once, see the class comment
      assertThat(out).as(result.getStderr()).anyMatch(line -> line.startsWith("fks|")
          && line.contains("[\"works_at_source_id_fkey\", [\"source_id\"], \"person\", [\"id\"]]")
          && line.contains("[\"works_at_target_id_fkey\", [\"target_id\"], \"company\", [\"id\"]]"));
      assertThat(out).as(result.getStderr()).anyMatch(line -> line.startsWith("orders fks|")
          && line.contains("[\"orders_buyer_fkey\", [\"buyer\"], \"person\", [\"id\"]]"));

      // KNOWN, see the class comment: the three that SQLAlchemy asks with a shape this catalog declines. When one of them is
      // answered this assertion fails, and the line moves up to the checks above
      assertThat(out).as(result.getStderr()).anyMatch(line -> line.startsWith("pk|ERROR"))
          .anyMatch(line -> line.startsWith("unique|ERROR")).anyMatch(line -> line.startsWith("indexes|ERROR"));
    }
  }
}
