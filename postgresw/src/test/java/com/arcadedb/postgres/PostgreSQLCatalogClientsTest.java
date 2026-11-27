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
 * The reflection of SQLAlchemy is checked call by call, and then as a whole with {@code MetaData.reflect()}, which is what an
 * application that maps the existing tables runs (fork issue #64).
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
      assertThat(lines(edge.getStdout())).containsSubsequence("Table \"public.works_at\"", "rid | text | | not null | generated always as identity",
          "source_id | text | | not null |", "target_id | text | | not null |", "since | integer | | |", "Indexes:",
          "\"works_at_pkey\" PRIMARY KEY, btree (rid)", "Foreign-key constraints:",
          "\"works_at_source_id_fkey\" FOREIGN KEY (source_id) REFERENCES person(rid) ON DELETE CASCADE",
          "\"works_at_target_id_fkey\" FOREIGN KEY (target_id) REFERENCES company(rid) ON DELETE CASCADE");

      final Container.ExecResult vertex = psql(container, "-q -c '\\d person'");
      assertThat(vertex.getExitCode()).as(vertex.getStderr()).isZero();
      assertThat(lines(vertex.getStdout())).containsSubsequence("Table \"public.person\"", "rid | text | | not null | generated always as identity",
          "age | integer | | |", "city | character varying | | |", "name | character varying | | |", "pid | integer | | |", "Indexes:",
          "\"person_pkey\" PRIMARY KEY, btree (rid)", "\"person_pid_key\" UNIQUE CONSTRAINT, btree (pid)", "Referenced by:",
          "TABLE \"orders\" CONSTRAINT \"orders_buyer_fkey\" FOREIGN KEY (buyer) REFERENCES person(rid) NOT ENFORCED",
          "TABLE \"works_at\" CONSTRAINT \"works_at_source_id_fkey\" FOREIGN KEY (source_id) REFERENCES person(rid) ON DELETE CASCADE");

      // a table with a link has the foreign key of it
      final Container.ExecResult document = psql(container, "-q -c '\\d orders'");
      assertThat(document.getExitCode()).as(document.getStderr()).isZero();
      assertThat(lines(document.getStdout())).containsSubsequence("Table \"public.orders\"", "rid | text | | not null | generated always as identity",
          "\"orders_pkey\" PRIMARY KEY, btree (rid)", "\"orders_oid_key\" UNIQUE CONSTRAINT, btree (oid)",
          "Foreign-key constraints:", "\"orders_buyer_fkey\" FOREIGN KEY (buyer) REFERENCES person(rid) NOT ENFORCED");
    }
  }

  // ------------------------------------------------------------------------------------------------------------ SQLAlchemy

  private static final String REFLECTION_SCRIPT = """
      import json
      import logging
      import sys

      from sqlalchemy import MetaData, UniqueConstraint, create_engine, inspect
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
      show("fk options", lambda: sorted([fk["name"], fk.get("options", {}).get("ondelete")]
                                        for table in ("orders", "works_at") for fk in inspector.get_foreign_keys(table)))
      show("unique", lambda: [[u["name"], u["column_names"]] for u in inspector.get_unique_constraints("person")])
      show("indexes", lambda: sorted([i["name"], i["column_names"], i["unique"]] for i in inspector.get_indexes("person")))
      show("views", lambda: inspector.get_view_names())
      show("schemas", lambda: sorted(inspector.get_schema_names()))
      show("has table", lambda: [inspector.has_table("person"), inspector.has_table("person_pid_key"),
                                 inspector.has_table("nosuch")])
      show("checks", lambda: inspector.get_check_constraints("person"))
      show("comment", lambda: inspector.get_table_comment("person"))
      show("options", lambda: inspector.get_table_options("person"))


      def reflect():
          metadata = MetaData()
          metadata.reflect(bind=engine)
          return [[table.name, [c.name for c in table.columns], [c.name for c in table.primary_key.columns],
                   sorted([key.parent.name, key.target_fullname] for key in table.foreign_keys),
                   sorted([u.name, [c.name for c in u.columns]] for u in table.constraints if isinstance(u, UniqueConstraint))]
                  for table in sorted(metadata.tables.values(), key=lambda t: t.name)]


      show("reflect", reflect)
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

      assertThat(out).as(result.getStderr()).contains(
          "tables|[\"company\", \"orders\", \"person\", \"works_at\"]",
          "columns|[[\"rid\", \"TEXT\", false], [\"source_id\", \"TEXT\", false], [\"target_id\", \"TEXT\", false], [\"since\", \"INTEGER\", true]]",
          "pk|[[\"rid\"], \"works_at_pkey\"]",
          // one entry for each key
          "fks|[[\"works_at_source_id_fkey\", [\"source_id\"], \"person\", [\"rid\"]], "
              + "[\"works_at_target_id_fkey\", [\"target_id\"], \"company\", [\"rid\"]]]",
          "orders fks|[[\"orders_buyer_fkey\", [\"buyer\"], \"person\", [\"rid\"]]]",
          "fk options|[[\"orders_buyer_fkey\", null], [\"works_at_source_id_fkey\", \"CASCADE\"], "
              + "[\"works_at_target_id_fkey\", \"CASCADE\"]]",
          "unique|[[\"person_pid_key\", [\"pid\"]]]",
          "indexes|[[\"person_pid_key\", [\"pid\"], true]]",
          "views|[]",
          "has table|[true, false, false]",
          "checks|[]",
          "comment|{\"text\": null}",
          "options|{}",
          "reflect|[[\"company\", [\"rid\", \"cid\", \"name\"], [\"rid\"], [], [[\"company_cid_key\", [\"cid\"]]]], "
              + "[\"orders\", [\"rid\", \"address\", \"buyer\", \"oid\", \"pid\", \"placed\", \"tags\", \"total\"], [\"rid\"], "
              + "[[\"buyer\", \"person.rid\"]], [[\"orders_oid_key\", [\"oid\"]]]], "
              + "[\"person\", [\"rid\", \"age\", \"city\", \"name\", \"pid\"], [\"rid\"], [], [[\"person_pid_key\", [\"pid\"]]]], "
              + "[\"works_at\", [\"rid\", \"source_id\", \"target_id\", \"since\"], [\"rid\"], "
              + "[[\"source_id\", \"person.rid\"], [\"target_id\", \"company.rid\"]], []]]");
      // the schemas of PostgreSQL's own are filtered out by SQLAlchemy's NOT LIKE ... ESCAPE
      assertThat(out).as(result.getStderr())
          .anyMatch(line -> line.startsWith("schemas|") && line.contains("\"public\"") && !line.contains("\"pg_"));
    }
  }

  // ---------------------------------------------------------------------- the test of fork issue #15: a PostgreSQL user

  /** The statements of the hand-written test: a join over the edge table, a report, a row, a relationship, and a delete. */
  private static final String USER_SQL = """
      SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.rid JOIN company c ON c.rid = w.target_id ORDER BY w.since;
      SELECT c.name, count(*) AS employees, min(w.since) AS first_year FROM company c JOIN works_at w ON w.target_id = c.rid GROUP BY c.name ORDER BY employees DESC, c.name;
      INSERT INTO person (pid, name, age, city) VALUES (6, 'Fay', 33, 'Delft') RETURNING pid, name;
      INSERT INTO works_at (source_id, target_id, since) SELECT p.rid, c.rid, 2026 FROM person p, company c WHERE p.pid = 6 AND c.name = 'Cedar' RETURNING since;
      SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.rid JOIN company c ON c.rid = w.target_id WHERE p.pid = 6;
      DELETE FROM person WHERE pid = 6;
      SELECT count(*) FROM works_at;
      """;

  @Test
  void aPostgreSQLUserWritesTheirOwnSqlInPsql() throws Exception {
    requireDocker();
    try (final GenericContainer<?> container = client(PSQL_IMAGE)) {
      container.start();
      container.copyFileToContainer(Transferable.of(USER_SQL), "/tmp/user.sql");

      final Container.ExecResult result = psql(container, "-q -A -t -F '|' -v ON_ERROR_STOP=1 -f /tmp/user.sql");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
      assertThat(result.getStderr()).isBlank();
      assertThat(lines(result.getStdout())).containsExactly(
          // the join over the edge table, Ada twice for her two edges
          "Ada|Acme|2019", "Bo|Acme|2020", "Cy|Birch|2021", "Ada|Acme|2022", "Di|Birch|2023",
          // the report
          "Acme|3|2019", "Birch|2|2021",
          // a new row, a new relationship, and the join that reads them
          "6|Fay", "2026", "Fay|Cedar",
          // the delete of the row takes its relationship with it
          "5");
    }
  }

  private static final String AUTOMAP_SCRIPT = """
      import json
      import logging
      import sys

      from sqlalchemy import Column, FetchedValue, Text, create_engine, select
      from sqlalchemy.engine import URL
      from sqlalchemy.ext.automap import automap_base
      from sqlalchemy.orm import Session

      url = URL.create("postgresql+psycopg", username="root", password="@PASSWORD@", host="host.testcontainers.internal",
                       port=@PORT@, database="@DATABASE@",
                       query={"sslmode": "disable", "options": "-c arcadedb.language=postgresql"})
      logging.basicConfig(stream=sys.stderr)
      logging.getLogger("sqlalchemy.engine").setLevel(logging.INFO)
      engine = create_engine(url)
      Base = automap_base()
      Base.prepare(autoload_with=engine)


      def show(label, function):
          # one line per step, so that one failure does not hide the others
          try:
              print(label + "|" + json.dumps(function(), default=str))
          except Exception as e:
              print(label + "|ERROR " + type(e).__name__ + " " + str(e).replace("\\n", " ")[:600])


      show("classes", lambda: sorted(Base.classes.keys()))
      Person = Base.classes.person
      Company = Base.classes.company
      Orders = Base.classes.orders
      WorksAt = Base.classes.works_at
      show("relationships", lambda: [[cls.__name__, sorted(r.key for r in cls.__mapper__.relationships)]
                                     for cls in (Company, Orders, Person, WorksAt)])


      def navigate():
          with Session(engine) as session:
              ada = session.scalars(select(Person).where(Person.pid == 1)).one()
              acme = session.scalars(select(Company).where(Company.cid == 10)).one()
              order = session.scalars(select(Orders).where(Orders.oid == 102)).one()
              return [sorted([w.company.name, w.since] for w in ada.works_at_collection),
                      sorted(w.person.name for w in acme.works_at_collection), order.person.name,
                      sorted(o.oid for o in ada.orders_collection)]


      show("navigate", navigate)


      def joined():
          with Session(engine) as session:
              query = (select(Person.name, Company.name, WorksAt.since)
                       .join(WorksAt, WorksAt.source_id == Person.rid).join(Company, Company.rid == WorksAt.target_id)
                       .order_by(WorksAt.since))
              return [list(row) for row in session.execute(query)]


      show("join", joined)


      def write(company, person, works_at, pid, name):
          with Session(engine) as session:
              cedar = session.scalars(select(company).where(company.cid == 30)).one()
              new = person(pid=pid, name=name, age=33, city="Delft")
              session.add(new)
              session.add(works_at(person=new, company=cedar, since=2026))
              session.commit()
          with Session(engine) as session:
              cedar = session.scalars(select(company).where(company.cid == 30)).one()
              return sorted([w.person.name, w.since] for w in cedar.works_at_collection)


      # the reflected record id has a default, so the ORM reads the new key back and gives it to the relationship
      show("write", lambda: write(Company, Person, WorksAt, 6, "Fay"))

      Declared = automap_base()


      class person(Declared):
          __tablename__ = "person"
          rid = Column(Text, primary_key=True, server_default=FetchedValue())


      class works_at(Declared):
          __tablename__ = "works_at"
          rid = Column(Text, primary_key=True, server_default=FetchedValue())


      Declared.prepare(autoload_with=engine)
      show("write with the keys declared", lambda: write(Declared.classes.company, person, works_at, 7, "Gil"))
      """;

  @Test
  void sqlalchemyAutomapGivesWorkingRelationships() throws Exception {
    requireDocker();
    try (final GenericContainer<?> python = client(PYTHON_IMAGE)) {
      python.start();
      python.execInContainer("mkdir", "-p", "/work");
      python.copyFileToContainer(Transferable.of(AUTOMAP_SCRIPT.replace("@PORT@", String.valueOf(getServerPostgresPort()))
          .replace("@DATABASE@", getDatabaseName()).replace("@PASSWORD@", DEFAULT_PASSWORD_FOR_TESTS)), "/work/automap.py");
      final Container.ExecResult result = python.execInContainer("uv", "run", "--no-project", "--with", "sqlalchemy", "--with",
          "psycopg[binary]", "python", "-I", "/work/automap.py");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();

      final List<String> out = Arrays.stream(result.getStdout().strip().split("\\R")).toList();
      System.out.println("automap: " + String.join("\nautomap: ", out));
      assertThat(out).as(result.getStderr()).hasSize(6).contains(
          "classes|[\"company\", \"orders\", \"person\", \"works_at\"]",
          "relationships|[[\"company\", [\"works_at_collection\"]], [\"orders\", [\"person\"]], "
              + "[\"person\", [\"orders_collection\", \"works_at_collection\"]], [\"works_at\", [\"company\", \"person\"]]]",
          "navigate|[[[\"Acme\", 2019], [\"Acme\", 2022]], [\"Ada\", \"Ada\", \"Bo\"], \"Bo\", [100, 101]]",
          "join|[[\"Ada\", \"Acme\", 2019], [\"Bo\", \"Acme\", 2020], [\"Cy\", \"Birch\", 2021], [\"Ada\", \"Acme\", 2022], [\"Di\", \"Birch\", 2023]]",
          // the mapped classes as automap builds them, nothing declared (fork issue #137), and then with the keys declared
          "write|[[\"Fay\", 2026]]",
          "write with the keys declared|[[\"Fay\", 2026], [\"Gil\", 2026]]");
    }
  }
}
