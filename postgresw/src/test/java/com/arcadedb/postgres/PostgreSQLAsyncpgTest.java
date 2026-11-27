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
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A fourth client of the {@code postgresql} language (fork issue #12, step W3): {@code asyncpg}, the asynchronous driver of
 * Python, which speaks only the extended protocol, describes every statement before it runs it and reads the types of the columns
 * from the answer. It runs in a container, the latest version from PyPI with {@code uv}, and connects to the in-process server
 * through {@code host.testcontainers.internal}. One line of the script per check, so that one run says all that is wrong.
 * <p>
 * Needs a Docker daemon: without one the test is skipped. It runs in the wire module job of {@code pg-prototype-engine-tests.yml}.
 */
class PostgreSQLAsyncpgTest extends PostgresWireProtocolTestBase {
  private static final String PYTHON_IMAGE = "ghcr.io/astral-sh/uv:python3.13-bookworm-slim";
  private static final String PG_IMAGE     = System.getProperty("pgparity.image", "postgres:18");
  private static final String PG_PASSWORD  = "asyncpg";

  /** The shape of the small fixture as PostgreSQL spells it, with its rows. */
  private static byte[] schemaOfPostgreSQL() throws IOException {
    try (final InputStream in = PostgreSQLAsyncpgTest.class.getResourceAsStream("/pgwire/schema-pg.sql")) {
      return in.readAllBytes();
    }
  }

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
    assumeTrue(available, "a Docker daemon is needed to start the client container");
  }

  private static final String SCRIPT = """
      import asyncio
      import datetime
      import decimal
      import json

      import asyncpg

      # statements with parameters of no declared type, and the values for them. Run on this server and on PostgreSQL 18: the type of
      # every parameter, the types of the columns and the rows have to be the same
      READS = [
          ("SELECT name FROM person WHERE pid = $1", [2]),
          ("SELECT name FROM person WHERE name = $1", ["Bo"]),
          ("SELECT name FROM person WHERE $1 = age", [36]),
          ("SELECT name FROM person WHERE pid IN ($1, $2) ORDER BY pid", [1, 2]),
          ("SELECT name FROM person WHERE age BETWEEN $1 AND $2 ORDER BY pid", [30, 45]),
          ("SELECT name FROM person WHERE name LIKE $1 ORDER BY pid", ["B%"]),
          ("SELECT name FROM person ORDER BY pid LIMIT $1 OFFSET $2", [2, 1]),
          ("SELECT name FROM person WHERE pid = $1 + 1", [1]),
          ("SELECT $1 AS v", ["x"]),
          ("SELECT $1::int8 AS v", [7]),
          ("SELECT name FROM person WHERE city = $1 AND age > $2 ORDER BY pid", ["Delft", 40]),
          ("SELECT oid FROM orders WHERE total > $1 AND placed >= $2 ORDER BY oid",
           [decimal.Decimal("9.50"), datetime.date(2026, 1, 6)]),
          ("SELECT name FROM person WHERE pid IN (SELECT pid FROM orders WHERE oid = $1)", [102]),
          ("SELECT name FROM person WHERE $1 IN (SELECT pid FROM orders) AND pid = 1", [2]),
          ("SELECT name FROM person WHERE upper(name) = $1", ["BO"]),
          ("SELECT city FROM person GROUP BY city HAVING count(*) > $1 ORDER BY city", [1]),
          ("SELECT lag(name, $1) OVER (ORDER BY pid) AS l FROM person ORDER BY pid", [1]),
          ("SELECT coalesce(age, $1) AS a FROM person ORDER BY pid", [0]),
          ("SELECT name FROM person WHERE $1::int IS NULL OR pid = $1 ORDER BY pid", [3]),
          ("SELECT name FROM person WHERE pid = $1 UNION SELECT name FROM company WHERE cid = $2 ORDER BY name", [1, 20]),
          ("SELECT CASE WHEN age > $1 THEN $2 ELSE name END AS n FROM person ORDER BY pid", [40, "old"]),
          ("SELECT name || $1 AS n FROM person ORDER BY pid", ["!"]),
      ]

      # writes: only the types of the parameters are compared, nothing is run
      WRITES = [
          "INSERT INTO person (pid, name, age, city) VALUES ($1, $2, $3, $4)",
          "INSERT INTO person (pid, name) SELECT $1, $2",
          "INSERT INTO person (pid, name) VALUES ($1, $2) ON CONFLICT (pid) DO UPDATE SET age = $3",
          "UPDATE person SET age = $1 WHERE pid = $2",
          "UPDATE orders SET total = $1, placed = $2 WHERE oid = $3",
          "UPDATE person SET age = $1 FROM orders WHERE orders.pid = person.pid AND orders.oid = $2",
          "DELETE FROM person WHERE pid = $1",
      ]


      async def answer(conn, sql, values):
          try:
              statement = await conn.prepare(sql)
              out = {"parameters": [p.name for p in statement.get_parameters()]}
              if values is not None:
                  out["columns"] = [a.type.name for a in statement.get_attributes()]
                  out["rows"] = [list(r.values()) for r in await statement.fetch(*values)]
              return json.dumps(out, default=str)
          except Exception as e:
              return "ERROR " + type(e).__name__ + " " + str(e).replace("\\n", " ")[:300]


      def rows(records):
          return [list(r.values()) for r in records]


      async def show(label, function):
          # one line per call, so that one failure does not hide the others
          try:
              print(label + "|" + json.dumps(await function(), default=str))
          except Exception as e:
              print(label + "|ERROR " + type(e).__name__ + " " + str(e).replace("\\n", " ")[:300])


      async def main():
          conn = await asyncpg.connect(host="host.testcontainers.internal", port=@PORT@, user="root", password="@PASSWORD@",
                                       database="@DATABASE@", ssl=False)
          await conn.execute("SET arcadedb.language = 'postgresql'")

          async def language():
              return await conn.fetchval("SHOW arcadedb.language")

          async def join():
              return rows(await conn.fetch("SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid"))

          async def describe():
              statement = await conn.prepare("SELECT name, age FROM person WHERE pid = 2")
              return [[a.name, a.type.name] for a in statement.get_attributes()]

          async def parameter():
              statement = await conn.prepare("SELECT name FROM person WHERE pid = $1")
              return rows(await statement.fetch(2))

          async def written():
              inserted = await conn.execute("INSERT INTO person (pid, name, age, city) VALUES ($1, $2, $3, $4)", 92, "Pa", 7, "Delft")
              updated = await conn.execute("UPDATE person SET age = $1 WHERE pid = $2", 8, 92)
              read = rows(await conn.fetch("SELECT name, age, city FROM person WHERE pid = $1", 92))
              nulled = await conn.execute("UPDATE person SET city = $1 WHERE pid = $2", None, 92)
              city = await conn.fetchval("SELECT city FROM person WHERE pid = $1", 92)
              deleted = await conn.execute("DELETE FROM person WHERE pid = $1", 92)
              return [inserted, updated, read, nulled, city, deleted]

          async def compare():
              pg = await asyncpg.connect(host="host.testcontainers.internal", port=@PGPORT@, user="root", password="@PGPASSWORD@",
                                         database="@DATABASE@", ssl=False)
              count = 0
              for sql, values in READS + [(sql, None) for sql in WRITES]:
                  here = await answer(conn, sql, values)
                  there = await answer(pg, sql, values)
                  count += 1
                  if here != there or here.startswith("ERROR"):
                      print("differs|" + sql + "|here " + here + "|PostgreSQL " + there)
              await pg.close()
              return count

          async def constraints():
              return rows(await conn.fetch("SELECT conname, contype FROM pg_catalog.pg_constraint ORDER BY conname"))

          async def columns():
              return rows(await conn.fetch(
                  "SELECT a.attname, t.typname FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid "
                  "JOIN pg_catalog.pg_type t ON t.oid = a.atttypid WHERE c.relname = 'works_at' AND a.attnum > 0 "
                  "ORDER BY a.attnum"))

          async def tables():
              return rows(await conn.fetch(
                  "SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                  "WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY c.relname"))

          async def crud():
              inserted = await conn.execute("INSERT INTO person (pid, name, age, city) VALUES (91, 'As', 1, 'Delft')")
              updated = await conn.execute("UPDATE person SET age = 2 WHERE pid = 91")
              read = rows(await conn.fetch("SELECT name, age FROM person WHERE pid = 91"))
              deleted = await conn.execute("DELETE FROM person WHERE pid = 91")
              return [inserted, updated, read, deleted]

          async def error():
              try:
                  await conn.fetch("SELECT nosuch FROM person")
              except asyncpg.PostgresError as e:
                  return [e.sqlstate]
              return []

          await show("language", language)
          await show("join", join)
          await show("describe", describe)
          await show("param", parameter)
          await show("written", written)
          await show("compared", compare)
          await show("constraints", constraints)
          await show("columns", columns)
          await show("tables", tables)
          await show("crud", crud)
          await show("error", error)
          await show("still usable", lambda: conn.fetchval("SELECT count(*) FROM person"))
          await conn.close()


      asyncio.run(main())
      """;

  @Test
  void asyncpgRunsTheLanguageAndReadsItsCatalog() throws Exception {
    requireDocker();
    try (final GenericContainer<?> pg = new GenericContainer<>(PG_IMAGE).withEnv("POSTGRES_USER", "root")
        .withEnv("POSTGRES_PASSWORD", PG_PASSWORD).withEnv("POSTGRES_DB", getDatabaseName()).withExposedPorts(5432)
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        .withStartupTimeout(Duration.ofMinutes(3));
        final GenericContainer<?> python = new GenericContainer<>(PYTHON_IMAGE).withCreateContainerCmdModifier(
            cmd -> cmd.withEntrypoint("sleep", "infinity"))) {
      pg.start();
      pg.copyFileToContainer(Transferable.of(schemaOfPostgreSQL()), "/tmp/schema.sql");
      final Container.ExecResult load = pg.execInContainer("sh", "-c", "PGPASSWORD=" + PG_PASSWORD
          + " psql -X -q -v ON_ERROR_STOP=1 -h localhost -U root -d " + getDatabaseName() + " -f /tmp/schema.sql 2>&1");
      assertThat(load.getExitCode()).as(load.getStdout()).isZero();
      Testcontainers.exposeHostPorts(getServerPostgresPort(), pg.getMappedPort(5432));
      python.start();
      python.execInContainer("mkdir", "-p", "/work");
      python.copyFileToContainer(Transferable.of(SCRIPT.replace("@PORT@", String.valueOf(getServerPostgresPort()))
          .replace("@DATABASE@", getDatabaseName()).replace("@PASSWORD@", DEFAULT_PASSWORD_FOR_TESTS)
          .replace("@PGPORT@", String.valueOf(pg.getMappedPort(5432))).replace("@PGPASSWORD@", PG_PASSWORD)),
          "/work/asyncpg_check.py");
      // the latest asyncpg, from PyPI, with uv: the project rule is uv, and no pip
      final Container.ExecResult result = python.execInContainer("uv", "run", "--no-project", "--with", "asyncpg", "python", "-I",
          "/work/asyncpg_check.py");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();

      final List<String> out = Arrays.stream(result.getStdout().strip().split("\\R")).toList();
      assertThat(out).contains("language|\"postgresql\"",
          "join|[[\"Ada\", 100], [\"Ada\", 101], [\"Bo\", 102], [\"Di\", 103], [\"Di\", 104]]",
          "describe|[[\"name\", \"varchar\"], [\"age\", \"int4\"]]",
          "constraints|[[\"company_cid_key\", \"u\"], [\"company_pkey\", \"p\"], [\"orders_buyer_fkey\", \"f\"], "
              + "[\"orders_oid_key\", \"u\"], [\"orders_pkey\", \"p\"], [\"person_pid_key\", \"u\"], [\"person_pkey\", \"p\"], "
              + "[\"works_at_pkey\", \"p\"], [\"works_at_source_id_fkey\", \"f\"], [\"works_at_target_id_fkey\", \"f\"]]",
          "columns|[[\"rid\", \"text\"], [\"source_id\", \"text\"], [\"target_id\", \"text\"], [\"since\", \"int4\"]]",
          "tables|[[\"company\"], [\"orders\"], [\"person\"], [\"works_at\"]]",
          "crud|[\"INSERT 0 1\", \"UPDATE 1\", [[\"As\", 2]], \"DELETE 1\"]", "error|[\"42703\"]", "still usable|5",
          // asyncpg binds a prepared parameter by the type the server announces: the one the language inferred (fork issue #67)
          "param|[[\"Bo\"]]",
          "written|[\"INSERT 0 1\", \"UPDATE 1\", [[\"Pa\", 8, \"Delft\"]], \"UPDATE 1\", null, \"DELETE 1\"]",
          "compared|29");
      assertThat(out.stream().filter(line -> line.startsWith("differs|")).toList()).as("the same answers as PostgreSQL 18").isEmpty();
    }
  }
}
