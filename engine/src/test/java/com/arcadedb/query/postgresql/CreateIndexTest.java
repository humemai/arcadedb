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
import com.arcadedb.exception.AlreadyExistsException;
import com.arcadedb.exception.ErrorCategory;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code CREATE INDEX} (fork issue #125): the index each accepted form makes and the SQLSTATE of each refusal. */
class CreateIndexTest extends TestHelper {
  @Override
  protected void beginTest() {
    database.command("sqlscript", """
        CREATE VERTEX TYPE person;
        CREATE PROPERTY person.id LONG;
        CREATE PROPERTY person.name STRING;
        CREATE PROPERTY person.tags LIST OF STRING;
        CREATE PROPERTY person.photo ARRAY_OF_FLOATS;""");
    run("CREATE TABLE memory (id int PRIMARY KEY, body text, embedding vector(3))");
    run("CREATE TABLE link (source_id text NOT NULL REFERENCES memory (rid) ON DELETE CASCADE,"
        + " target_id text NOT NULL REFERENCES memory (rid) ON DELETE CASCADE, weight int)");
  }

  @Test
  void theUniqueIndexOfTheIssueIsATreeIndex() {
    run("CREATE UNIQUE INDEX ON person (id)");
    run("INSERT INTO person (id) VALUES (1)");

    assertThat(database.getSchema().getType("person").getPolymorphicIndexByProperties("id").isUnique()).isTrue();
    assertState("INSERT INTO person (id) VALUES (1)", "23505");
  }

  @Test
  void aVectorIndexAnswersAsOneThatSqlMade() {
    run("CREATE TABLE other (id int PRIMARY KEY, embedding vector(3))");
    run("CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops)");
    database.command("sql",
        "CREATE INDEX ON other (embedding) LSM_VECTOR METADATA {\"dimensions\": 3, \"similarity\": \"EUCLIDEAN\"}").close();
    for (int i = 0; i < 30; i++) {
      final String values = "(" + i + ", '[" + i % 7 + "," + i % 5 + "," + i + "]')";
      run("INSERT INTO memory (id, embedding) VALUES " + values);
      run("INSERT INTO other (id, embedding) VALUES " + values);
    }

    assertThat(database.getSchema().getType("memory").getPolymorphicIndexByProperties("embedding").getType())
        .isEqualTo(Schema.INDEX_TYPE.LSM_VECTOR);
    final String nearest = "SELECT id FROM %s ORDER BY embedding <-> '[1,2,11]' LIMIT 5";
    assertThat(values("EXPLAIN " + nearest.formatted("memory"), "QUERY PLAN")).anySatisfy(
        line -> assertThat(String.valueOf(line)).contains("VECTOR INDEX SEARCH"));
    assertThat(values(nearest.formatted("memory"), "id")).hasSize(5)
        .containsExactlyInAnyOrderElementsOf(values(nearest.formatted("other"), "id"));
  }

  @ParameterizedTest
  @CsvSource({ "vector_cosine_ops, <=>", "vector_ip_ops, <#>" })
  void theOperatorClassSetsTheSimilarity(final String operatorClass, final String operator) {
    run("CREATE INDEX IF NOT EXISTS memory_nearest ON memory USING hnsw (embedding " + operatorClass
        + ") WITH (m = 16, ef_construction = 64)");
    run("CREATE INDEX IF NOT EXISTS memory_nearest ON memory USING hnsw (embedding " + operatorClass + ")");
    run("INSERT INTO memory (id, embedding) VALUES (1, '[1,0,0]'), (2, '[0,1,0]')");

    assertThat(values("EXPLAIN SELECT id FROM memory ORDER BY embedding " + operator + " '[1,0,0]' LIMIT 1", "QUERY PLAN"))
        .anySatisfy(line -> assertThat(String.valueOf(line)).contains("VECTOR INDEX SEARCH"));
  }

  @Test
  void namesAreKeptAndAUniqueIndexOfATableIsNoConstraint() {
    run("CREATE UNIQUE INDEX memory_body ON memory (body)");
    run("CREATE INDEX ON memory (body)");

    assertState("CREATE INDEX memory_body ON memory (id)", "42P07");
    assertState("CREATE INDEX memory_body_idx ON memory (id)", "42P07");
    assertState("CREATE INDEX memory_pkey ON memory (id)", "42P07");
    assertState("CREATE INDEX person ON memory (id)", "42P07");
    assertConflict("CREATE INDEX memory_body ON memory (id)", AlreadyExistsException.Kind.INDEX);
    assertConflict("CREATE INDEX person ON memory (id)", AlreadyExistsException.Kind.TYPE);
    run("CREATE INDEX IF NOT EXISTS memory_body ON memory (id)");
    assertThat(values("SELECT constraint_name FROM information_schema.table_constraints WHERE table_name = 'memory'"
        + " ORDER BY constraint_name", "constraint_name")).containsExactly("memory_pkey", "memory_rid_key");
    assertState("INSERT INTO memory (id, body) VALUES (1, 'a'), (2, 'a')", "23505");
  }

  @Test
  void anIndexOnAnEndOfAnEdgeKeepsItsNameOnly() {
    final int before = database.getSchema().getType("link").getAllIndexes(true).size();
    run("CREATE INDEX link_source ON link (source_id)");
    run("CREATE INDEX ON link (target_id, weight)");
    run("CREATE INDEX ON memory (rid)");

    assertThat(database.getSchema().getType("link").getAllIndexes(true)).hasSize(before);
    assertState("CREATE INDEX link_source ON link (target_id)", "42P07");
    assertState("CREATE UNIQUE INDEX ON link (source_id)", "0A000");
  }

  @Test
  void aUniqueIndexOverRowsThatRepeatAKeyIsNotMade() {
    run("INSERT INTO person (id) VALUES (1), (1)");

    assertState("CREATE UNIQUE INDEX ON person (id)", "23505");
    assertThat(database.getSchema().getType("person").getPolymorphicIndexByProperties("id")).isNull();
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', quoteCharacter = '"', value = {
      "CREATE INDEX ON nobody (id); 42P01",
      "CREATE INDEX ON person (nothing); 42703",
      "CREATE INDEX ON memory USING hnsw (embedding); 42704",
      "CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops) WITH (m = 1); 22023",
      "CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops) WITH (m = 40); 22023",
      "CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops) WITH (m = 8, m = 8); 22023",
      "CREATE INDEX person (id); 42601",
      "CREATE INDEX IF NOT EXISTS ON person (id); 42601" })
  void anErrorKeepsItsState(final String statement, final String sqlState) {
    assertState(statement, sqlState);
  }

  @ParameterizedTest
  @ValueSource(strings = { "CREATE INDEX ON person USING hash (id)", "CREATE INDEX ON person USING gin (tags)",
      "CREATE INDEX ON memory USING ivfflat (embedding vector_l2_ops)",
      "CREATE INDEX ON memory USING hnsw (embedding vector_l1_ops)",
      "CREATE INDEX ON person USING hnsw (photo vector_l2_ops)", "CREATE INDEX ON person USING hnsw (name vector_l2_ops)",
      "CREATE UNIQUE INDEX ON memory USING hnsw (embedding vector_l2_ops)",
      "CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops, id vector_l2_ops)",
      "CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops) WITH (ef_search = 10)",
      "CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops) WITH (m = 'x')",
      "CREATE INDEX ON person (id) WITH (fillfactor = 70)", "CREATE INDEX ON person (name text_pattern_ops)",
      "CREATE INDEX ON person (tags)", "CREATE INDEX ON person (id, id)", "CREATE INDEX ON person ((id + 1))",
      "CREATE INDEX ON person (lower(name))", "CREATE INDEX ON person (name COLLATE \"C\")", "CREATE INDEX ON person (id DESC)",
      "CREATE INDEX ON person (id NULLS FIRST)", "CREATE INDEX ON person (id) WHERE id > 1",
      "CREATE INDEX ON person (id) INCLUDE (name)", "CREATE UNIQUE INDEX ON person (id) NULLS NOT DISTINCT",
      "CREATE INDEX CONCURRENTLY ON person (id)", "CREATE INDEX ON information_schema.tables (table_name)",
      "DROP INDEX memory_pkey" })
  void whatTheEngineCannotDoIsNotSupportedWithItsPosition(final String statement) {
    assertThatThrownBy(() -> run(statement)).isInstanceOfSatisfying(PostgreSQLFeatureNotSupportedException.class, e -> {
      assertThat(e.getSqlState()).isEqualTo("0A000");
      assertThat(e.getMessage()).contains("(line 1, column ");
    });
  }

  @Test
  void aTransactionBlockRefusesIt() {
    run("BEGIN");
    assertState("CREATE INDEX ON person (id)", "0A000");
    run("ROLLBACK");
    assertThat(database.getSchema().getType("person").getPolymorphicIndexByProperties("id")).isNull();
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }

  private List<Object> values(final String statement, final String column) {
    final List<Object> values = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        values.add(row.getProperty(column));
      }
    }
    return values;
  }

  private void assertState(final String statement, final String sqlState) {
    assertThatThrownBy(() -> run(statement)).as(statement)
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(e.getMessage()).isEqualTo(sqlState));
  }

  /** A taken name is the conflict that every protocol answers (fork issue #160). */
  private void assertConflict(final String statement, final AlreadyExistsException.Kind holder) {
    assertThatThrownBy(() -> run(statement)).as(statement).satisfies(e -> {
      assertThat(ErrorCategory.of(e)).isEqualTo(ErrorCategory.ALREADY_EXISTS);
      assertThat(e.getCause()).isInstanceOfSatisfying(AlreadyExistsException.class,
          taken -> assertThat(taken.getKind()).isEqualTo(holder));
    });
  }
}
