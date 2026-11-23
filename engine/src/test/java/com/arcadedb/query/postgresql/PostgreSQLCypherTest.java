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
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cypher and the {@code postgresql} language see the same records (fork issue #29, stage 5): what one writes the other reads, and
 * what one deletes is gone for the other. The data is the {@code small} fixture; nothing is copied or converted between the two.
 */
class PostgreSQLCypherTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private List<Result> rows(final String language, final String statement, final Object... parameters) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet resultSet = "postgresql".equals(language) && !statement.startsWith("SELECT")
        ? database.command(language, statement, parameters) : database.query(language, statement, parameters)) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    return rows;
  }

  private long cypherCount(final String match) {
    return rows("opencypher", match + " RETURN count(*) AS n").get(0).<Long>getProperty("n");
  }

  private long tableCount(final String table) {
    return rows("postgresql", "SELECT count(*) FROM " + table).get(0).<Long>getProperty("count");
  }

  @Test
  void aVertexInsertedThroughTheTableIsMatchedByCypher() {
    rows("postgresql", "INSERT INTO person (pid, name, age, city) VALUES (6, 'Fay', 33, 'Delft')");

    final List<Result> found = rows("opencypher", "MATCH (p:person {pid: 6}) RETURN p.name AS name, p.age AS age, p.city AS city");
    assertThat(found).hasSize(1);
    assertThat(found.get(0).<String>getProperty("name")).isEqualTo("Fay");
    assertThat(found.get(0).<Integer>getProperty("age")).isEqualTo(33);
    assertThat(cypherCount("MATCH (p:person)")).isEqualTo(6L);
  }

  @Test
  void aVertexCreatedByCypherIsARowOfTheTableAndTheTableChangesItBack() {
    rows("opencypher", "CREATE (:person {pid: 7, name: 'Gus', age: 20, city: 'Utrecht'})");
    assertThat(rows("postgresql", "SELECT name, age, city FROM person WHERE pid = 7")).hasSize(1);

    assertThat(rows("postgresql", "UPDATE person SET age = age + 1, city = 'Delft' WHERE pid = 7 RETURNING age").get(0)
        .<Integer>getProperty("age")).isEqualTo(21);
    final Result seen = rows("opencypher", "MATCH (p:person {pid: 7}) RETURN p.age AS age, p.city AS city").get(0);
    assertThat(seen.<Integer>getProperty("age")).isEqualTo(21);
    assertThat(seen.<String>getProperty("city")).isEqualTo("Delft");

    rows("postgresql", "DELETE FROM person WHERE pid = 7");
    assertThat(cypherCount("MATCH (p:person {pid: 7})")).isEqualTo(0L);
    assertThat(cypherCount("MATCH (p:person)")).isEqualTo(5L);
  }

  @Test
  void anEdgeCreatedByCypherIsARowOfTheEdgeTableAndDeletingTheRowRemovesTheEdge() {
    assertThat(cypherCount("MATCH (:person)-[w:works_at]->(:company)")).isEqualTo(5L);

    rows("opencypher", "MATCH (a:person {pid: 5}), (b:company {cid: 30}) CREATE (a)-[:works_at {since: 2031}]->(b)");
    assertThat(tableCount("works_at")).isEqualTo(6L);

    // the row has the record ids of the two vertices, the ids that the vertex tables show
    final Result edge = rows("postgresql", "SELECT id, source_id, target_id, since FROM works_at WHERE since = 2031").get(0);
    final String ed = rows("postgresql", "SELECT id FROM person WHERE pid = 5").get(0).getProperty("id");
    final String cedar = rows("postgresql", "SELECT id FROM company WHERE cid = 30").get(0).getProperty("id");
    assertThat(edge.<String>getProperty("source_id")).isEqualTo(ed);
    assertThat(edge.<String>getProperty("target_id")).isEqualTo(cedar);
    assertThat(rows("postgresql", "SELECT p.name, c.name AS company FROM works_at w JOIN person p ON w.source_id = p.id "
        + "JOIN company c ON w.target_id = c.id WHERE w.since = 2031").get(0).<String>getProperty("name")).isEqualTo("Ed");

    assertThat(rows("postgresql", "DELETE FROM works_at WHERE since = 2031 RETURNING since")).hasSize(1);
    assertThat(cypherCount("MATCH (:person)-[w:works_at]->(:company)")).isEqualTo(5L);
    assertThat(cypherCount("MATCH (:person {pid: 5})-[w:works_at]->(:company)")).isEqualTo(0L);
    assertThat(cypherCount("MATCH (p:person {pid: 5})")).isEqualTo(1L);
  }

  @Test
  void anEdgeInsertedThroughTheTableIsWalkedByCypher() {
    rows("postgresql", "INSERT INTO works_at (source_id, target_id, since) SELECT p.id, c.id, 2032 FROM person p, company c "
        + "WHERE p.pid = 5 AND c.cid = 30");

    final List<Result> walked = rows("opencypher",
        "MATCH (p:person {pid: 5})-[w:works_at]->(c:company) RETURN c.name AS company, w.since AS since");
    assertThat(walked).hasSize(1);
    assertThat(walked.get(0).<String>getProperty("company")).isEqualTo("Cedar");
    assertThat(walked.get(0).<Integer>getProperty("since")).isEqualTo(2032);

    rows("postgresql", "UPDATE works_at SET since = 2033 WHERE since = 2032");
    assertThat(rows("opencypher", "MATCH (:person {pid: 5})-[w:works_at]->(:company) RETURN w.since AS since").get(0)
        .<Integer>getProperty("since")).isEqualTo(2033);
  }

  @Test
  void deletingAVertexThroughTheTableRemovesItsEdgesForCypherToo() {
    assertThat(cypherCount("MATCH (:person {pid: 1})-[w:works_at]->(:company)")).isEqualTo(2L);
    rows("postgresql", "DELETE FROM person WHERE pid = 1");
    assertThat(cypherCount("MATCH (:person)-[w:works_at]->(:company)")).isEqualTo(3L);
    assertThat(cypherCount("MATCH (p:person {pid: 1})")).isEqualTo(0L);
    assertThat(tableCount("works_at")).isEqualTo(3L);
  }

  @Test
  void aTransactionBlockOfTheTableIsTheTransactionCypherSees() {
    rows("postgresql", "BEGIN");
    rows("postgresql", "INSERT INTO person (pid, name) VALUES (8, 'Hal')");
    assertThat(cypherCount("MATCH (p:person {pid: 8})")).isEqualTo(1L);
    rows("postgresql", "ROLLBACK");
    assertThat(cypherCount("MATCH (p:person {pid: 8})")).isEqualTo(0L);
  }
}
