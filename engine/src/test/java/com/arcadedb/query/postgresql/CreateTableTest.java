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
package com.arcadedb.query.postgresql;

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.OperationType;
import com.arcadedb.query.QueryEngine;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.VertexType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code CREATE TABLE} (fork issue #118): the type each accepted form makes, what the schema keeps for every language, and the
 * SQLSTATE of each refusal. The comparison of the answers with PostgreSQL 18 is {@code PostgreSQLCreateTableTest} of the
 * {@code postgresw} module.
 */
class CreateTableTest extends TestHelper {
  private static final String PERSON = "CREATE TABLE person (id bigint PRIMARY KEY, name text NOT NULL, city varchar DEFAULT 'Delft')";
  private static final String KNOWS = "CREATE TABLE knows (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,"
      + " target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int, PRIMARY KEY (source_id, target_id))";

  @Test
  void aTableIsAVertexTypeThatOpenCypherMatches() {
    run(PERSON);
    run("INSERT INTO person VALUES (1, 'Ada')");

    assertThat(database.getSchema().getType("person")).isInstanceOf(VertexType.class);
    assertThat(columns("SELECT * FROM person")).containsExactly("id", "name", "city");
    assertThat(lines("SELECT * FROM person")).containsExactly("1|Ada|Delft");
    assertThat(rows("SELECT rid FROM person").get(0).<String>getProperty("rid")).startsWith("#");
    try (final ResultSet matched = database.query("opencypher", "MATCH (p:person) RETURN p.name AS name")) {
      assertThat(matched.next().<String>getProperty("name")).isEqualTo("Ada");
    }
  }

  @Test
  void theSchemaKeepsTheConstraintsForEveryLanguage() {
    run(PERSON);
    database.transaction(() -> database.command("sql", "INSERT INTO person SET id = 1, name = 'Ada'").close());

    assertThat(lines("SELECT city FROM person")).containsExactly("Delft");
    assertThatThrownBy(() -> database.transaction(() -> database.command("sql", "INSERT INTO person SET id = 2").close()))
        .hasMessageContaining("name");
    assertThatThrownBy(() -> database.transaction(
        () -> database.command("sql", "INSERT INTO person SET id = 1, name = 'Bob'").close())).hasMessageContaining("Duplicated");
    assertState("INSERT INTO person (id, name) VALUES (1, 'Bob')", "23505");
    assertState("INSERT INTO person (id) VALUES (2)", "23502");
    assertState("INSERT INTO person (name) VALUES ('Cy')", "23502");
  }

  @Test
  void theCatalogDescribesTheDeclarationAfterAReopen() {
    run("CREATE TABLE t (b int, a text DEFAULT 'it''s \\ here', c int8 DEFAULT -5, d bool DEFAULT true, e numeric DEFAULT 1.5,"
        + " f float8 DEFAULT 2, g text[], h jsonb, i timestamp, j date, k bytea, l smallint, m real,"
        + " CONSTRAINT t_key PRIMARY KEY (b, a), UNIQUE (c))");
    reopenDatabase();
    run("INSERT INTO t (b) VALUES (1)");

    assertThat(lines("SELECT a, c, d, e, f FROM t")).containsExactly("it's \\ here|-5|true|1.5|2.0");
    assertThat(lines("SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns"
        + " WHERE table_name = 't' ORDER BY ordinal_position")).containsExactly(
        "b|integer|NO|null", "a|text|NO|'it''s \\ here'::text", "c|bigint|YES|'-5'::integer", "d|boolean|YES|true",
        "e|numeric|YES|1.5", "f|double precision|YES|2", "g|ARRAY|YES|null", "h|jsonb|YES|null",
        "i|timestamp without time zone|YES|null", "j|date|YES|null", "k|bytea|YES|null", "l|smallint|YES|null",
        "m|real|YES|null");
    assertThat(lines("SELECT constraint_name, constraint_type FROM information_schema.table_constraints"
        + " WHERE table_name = 't' ORDER BY constraint_name")).containsExactly("t_c_key|UNIQUE", "t_key|PRIMARY KEY");
    assertState("INSERT INTO t (b, a) VALUES (1, 'it''s \\ here')", "23505");
    run("INSERT INTO t (b, a, c) VALUES (2, 'x', NULL), (3, 'y', NULL)");
  }

  @Test
  void aTableThatDeclaresBothEndsIsAnEdgeType() {
    run(PERSON);
    run(KNOWS);
    assertThat(database.getSchema().getType("knows")).isInstanceOf(EdgeType.class);
    run("INSERT INTO person (id, name) VALUES (1, 'Ada'), (2, 'Bob')");
    run("INSERT INTO knows SELECT a.rid, b.rid, 2020 FROM person a, person b WHERE a.id = 1 AND b.id = 2");

    assertThat(columns("SELECT * FROM knows")).containsExactly("source_id", "target_id", "since");
    assertState("INSERT INTO knows SELECT a.rid, b.rid, 2021 FROM person a, person b WHERE a.id = 1 AND b.id = 2", "23505");
    try (final ResultSet matched = database.query("opencypher", "MATCH (:person {id: 1})-[k:knows]->(q:person) RETURN q.id AS id")) {
      assertThat(matched.next().<Long>getProperty("id")).isEqualTo(2L);
    }
    assertThat(lines("SELECT table_name, constraint_name, constraint_type FROM information_schema.table_constraints"
        + " ORDER BY table_name, constraint_name")).containsExactly("knows|knows_pkey|PRIMARY KEY",
        "knows|knows_source_id_fkey|FOREIGN KEY", "knows|knows_target_id_fkey|FOREIGN KEY", "person|person_pkey|PRIMARY KEY",
        "person|person_rid_key|UNIQUE");
    // the record id is listed once an edge table refers to the table, and stays out of *
    assertThat(lines("SELECT column_name FROM information_schema.columns WHERE table_name = 'person' ORDER BY ordinal_position"))
        .containsExactly("id", "name", "city", "rid");
    assertThat(columns("SELECT * FROM person")).containsExactly("id", "name", "city");

    run("DELETE FROM person WHERE id = 2");
    assertThat(lines("SELECT count(*) FROM knows")).containsExactly("0");
  }

  @Test
  void theEndsCanBeTableConstraintsWithNames() {
    run(PERSON);
    run("CREATE TABLE likes (id bigint PRIMARY KEY, source_id text NOT NULL, target_id text NOT NULL,"
        + " CONSTRAINT likes_from FOREIGN KEY (source_id) REFERENCES public.person (rid) ON DELETE CASCADE,"
        + " FOREIGN KEY (target_id) REFERENCES person (rid) ON UPDATE NO ACTION ON DELETE CASCADE)");

    assertThat(columns("SELECT * FROM likes")).containsExactly("id", "source_id", "target_id");
    assertThat(lines("SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = 'likes'"
        + " ORDER BY constraint_name")).containsExactly("likes_from", "likes_pkey", "likes_target_id_fkey");
  }

  @Test
  void aVectorKeepsItsDimensionsInEveryLanguage() {
    run("CREATE TABLE memory (id int PRIMARY KEY, embedding vector(3))");
    run("INSERT INTO memory VALUES (1, '[1,0,0]')");

    assertState("INSERT INTO memory VALUES (2, '[1,0]')", "22000");
    assertState("UPDATE memory SET embedding = '[1,0,0,0]'", "22000");
    assertThatThrownBy(() -> database.transaction(
        () -> database.command("sql", "INSERT INTO memory SET id = 3, embedding = [1.0, 2.0]").close())).hasMessageContaining(
        "embedding");
  }

  @Test
  void aNameThatIsTakenIsRefusedUnlessTheStatementAllowsIt() {
    database.getSchema().createVertexType("Person");

    assertState("CREATE TABLE person (a int)", "42P07");
    assertState("CREATE TABLE \"Person\" (a int)", "42P07");
    run("CREATE TABLE IF NOT EXISTS person (a int)");
    assertThat(database.getSchema().getType("Person").getPropertyNames()).isEmpty();
    run("CREATE TABLE other (a int CONSTRAINT other_a UNIQUE)");
    assertState("CREATE TABLE t (a int CONSTRAINT other UNIQUE)", "42P07");
    assertThat(database.getSchema().existsType("t")).isFalse();
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', quoteCharacter = '"', value = {
      "CREATE TABLE t (a int, a text); 42701",
      "CREATE TABLE t (xmin int); 42701",
      "CREATE TABLE t (a int, UNIQUE (b)); 42703",
      "CREATE TABLE t (a int, UNIQUE (a, a)); 42701",
      "CREATE TABLE t (a int PRIMARY KEY, b int PRIMARY KEY); 42P16",
      "CREATE TABLE t (a int NULL NOT NULL); 42601",
      "CREATE TABLE t (a int NULL PRIMARY KEY); 42601",
      "CREATE TABLE t (a int DEFAULT 1 DEFAULT 2); 42601",
      "CREATE TABLE t (a int,); 42601",
      "CREATE TABLE t (a int DEFAULT 'x'); 22P02",
      "CREATE TABLE t (a smallint DEFAULT '100000'); 22003",
      "CREATE TABLE t (a int DEFAULT true); 42804",
      "CREATE TABLE t (a vector(0)); 22023",
      "CREATE TABLE nowhere.t (a int); 3F000",
      "CREATE TABLE t (source_id text NOT NULL REFERENCES nobody (rid) ON DELETE CASCADE, target_id text NOT NULL REFERENCES nobody (rid) ON DELETE CASCADE); 42P01" })
  void anErrorOfPostgreSqlKeepsItsState(final String statement, final String sqlState) {
    assertState(statement, sqlState);
    assertThat(database.getSchema().existsType("t")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = { "CREATE TEMP TABLE t (a int)", "CREATE UNLOGGED TABLE t (a int)", "CREATE TABLE t ()",
      "CREATE TABLE t (a serial)", "CREATE TABLE t (a uuid)", "CREATE TABLE t (a timestamptz)", "CREATE TABLE t (a char(3))",
      "CREATE TABLE t (a varchar(5))", "CREATE TABLE t (a numeric(10, 2))", "CREATE TABLE t (a int[][])",
      "CREATE TABLE t (a int CHECK (a > 0))", "CREATE TABLE t (a int, CHECK (a > 0))",
      "CREATE TABLE t (a int GENERATED ALWAYS AS IDENTITY)", "CREATE TABLE t (a int COLLATE \"C\")",
      "CREATE TABLE t (a int UNIQUE DEFERRABLE)", "CREATE TABLE t (a int, UNIQUE (a) DEFERRABLE)",
      "CREATE TABLE t (a int UNIQUE NULLS NOT DISTINCT)", "CREATE TABLE t (a int) INHERITS (u)",
      "CREATE TABLE t (a int) PARTITION BY RANGE (a)", "CREATE TABLE t (a int) WITH (fillfactor = 70)",
      "CREATE TABLE t (LIKE u)", "CREATE TABLE t AS SELECT 1", "CREATE TABLE t (a) AS SELECT 1", "CREATE TABLE t OF u",
      "CREATE TABLE t PARTITION OF u FOR VALUES IN (1)", "CREATE TABLE t (a timestamp DEFAULT now())",
      "CREATE TABLE t (a int DEFAULT 1 + 1)", "CREATE TABLE t (a smallint DEFAULT 100000)", "CREATE TABLE t (a date DEFAULT '2020-01-01')", "CREATE TABLE t (a jsonb UNIQUE)",
      "CREATE TABLE t (a int REFERENCES person (id))", "CREATE TABLE t (a int REFERENCES person)",
      "CREATE TABLE t (a int, FOREIGN KEY (a) REFERENCES person (rid) ON DELETE CASCADE)",
      "CREATE TABLE t (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE)",
      "CREATE TABLE t (source_id text NOT NULL REFERENCES person (rid), target_id text NOT NULL REFERENCES person (rid))",
      "CREATE TABLE t (source_id text REFERENCES person (rid) ON DELETE CASCADE, target_id text REFERENCES person (rid) ON DELETE CASCADE)",
      "CREATE TABLE t (source_id text NOT NULL REFERENCES person (rid) MATCH FULL, target_id text NOT NULL)",
      "CREATE TABLE t (source_id text NOT NULL UNIQUE REFERENCES person (rid) ON DELETE CASCADE, target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE)" })
  void whatTheEngineCannotKeepIsNotSupportedWithItsPosition(final String statement) {
    run(PERSON);

    assertThatThrownBy(() -> run(statement)).isInstanceOfSatisfying(PostgreSQLFeatureNotSupportedException.class, e -> {
      assertThat(e.getSqlState()).isEqualTo("0A000");
      assertThat(e.getMessage()).contains("(line 1, column ");
    });
    assertThat(database.getSchema().existsType("t")).isFalse();
  }

  @Test
  void aTransactionBlockRefusesItAndAServerTransactionDoesNot() {
    run("BEGIN");
    assertState("CREATE TABLE t (a int)", "0A000");
    run("ROLLBACK");
    database.begin();
    assertState("CREATE TABLE t (a int)", "0A000");
    database.rollback();
    assertThat(database.getSchema().existsType("t")).isFalse();

    database.transaction(() -> {
      ((DatabaseInternal) database).getTransaction().setImplicit(true);
      run("CREATE TABLE t (a int PRIMARY KEY)");
    });
    assertThat(database.getSchema().existsType("t")).isTrue();
  }

  @Test
  void itIsASchemaChangeThatAQueryRefuses() {
    final QueryEngine.AnalyzedQuery analyzed = ((DatabaseInternal) database).getQueryEngine("postgresql")
        .analyze("CREATE TABLE t (a int)");

    assertThat(analyzed.isDDL()).isTrue();
    assertThat(analyzed.isIdempotent()).isFalse();
    assertThat(analyzed.getOperationTypes()).containsExactly(OperationType.SCHEMA);
    assertThatThrownBy(() -> database.query("postgresql", "CREATE TABLE t (a int)")).isInstanceOfSatisfying(
        PostgreSQLEvaluationException.class, e -> assertThat(e.getSqlState()).isEqualTo("25006"));
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }

  private List<Result> rows(final String statement) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    return rows;
  }

  private List<String> columns(final String statement) {
    return ((PostgreSQLQueryEngine) ((DatabaseInternal) database).getQueryEngine("postgresql")).describe(statement).stream()
        .map(column -> column.name()).toList();
  }

  private List<String> lines(final String statement) {
    final List<String> columns = columns(statement);
    return rows(statement).stream()
        .map(row -> String.join("|", columns.stream().map(column -> String.valueOf(row.<Object>getProperty(column))).toList()))
        .toList();
  }

  private void assertState(final String statement, final String sqlState) {
    assertThatThrownBy(() -> run(statement)).as(statement)
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(e.getMessage()).isEqualTo(sqlState));
  }
}
