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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.TestHelper;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code INSERT}, {@code UPDATE} and {@code DELETE} on document and vertex tables of the {@code small} fixture (fork issue #29,
 * stage 2): what each statement does to the records, the SQLSTATE of each refusal, the access path of {@code UPDATE} and
 * {@code DELETE}, and that a record written through the table view is the same record that {@code sql} reads. The parity of the
 * answers with PostgreSQL 18 is the write corpus ({@code WriteCorpusTest}, {@code writes.tsv}).
 */
class WritePlanTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  // ---- helpers ----

  private long command(final String sql, final Object... parameters) {
    try (final ResultSet resultSet = database.command("postgresql", sql, parameters)) {
      final PostgreSQLResultSet answer = (PostgreSQLResultSet) resultSet;
      return answer.affectedRows();
    }
  }

  private List<Result> query(final String sql, final Object... parameters) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", sql, parameters)) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    return rows;
  }

  private Object value(final String sql, final Object... parameters) {
    final List<Result> rows = query(sql, parameters);
    assertThat(rows).as(sql).hasSize(1);
    return rows.get(0).getPropertyNames().stream().findFirst().map(rows.get(0)::getProperty).orElse(null);
  }

  private static void assertState(final Runnable statement, final String sqlState) {
    assertThatThrownBy(statement::run).isInstanceOfSatisfying(RuntimeException.class, e -> {
      try {
        assertThat(e.getClass().getMethod("getSqlState").invoke(e)).as(String.valueOf(e.getMessage())).isEqualTo(sqlState);
      } catch (final ReflectiveOperationException noState) {
        throw new AssertionError("no SQLSTATE on " + e, noState);
      }
    });
  }

  // ---- INSERT ----

  @Test
  void insertCreatesTheVertexThatSqlReadsToo() {
    assertThat(command("INSERT INTO person (pid, name, age, city) VALUES (6, 'Fay', 33, 'Delft')")).isEqualTo(1);

    assertThat(value("SELECT name FROM person WHERE pid = 6")).isEqualTo("Fay");
    try (final ResultSet resultSet = database.query("sql", "SELECT FROM person WHERE pid = 6")) {
      final Result record = resultSet.next();
      assertThat(record.isVertex()).isTrue();
      assertThat(record.<String>getProperty("city")).isEqualTo("Delft");
    }
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(6L);
  }

  @Test
  void insertWithRowsAnExpressionAndReturning() {
    try (final ResultSet resultSet = database.command("postgresql",
        "INSERT INTO company (cid, name) VALUES (40, upper('dune')), (50, 'Elm') RETURNING cid, name")) {
      final PostgreSQLResultSet answer = (PostgreSQLResultSet) resultSet;
      assertThat(answer.affectedRows()).isEqualTo(2);
      assertThat(answer.columns()).extracting(PostgreSQLResultSet.Column::name).containsExactly("cid", "name");
      final List<String> names = new ArrayList<>();
      while (resultSet.hasNext())
        names.add(resultSet.next().getProperty("name"));
      assertThat(names).containsExactlyInAnyOrder("DUNE", "Elm");
    }
  }

  @Test
  void aColumnThatIsNotGivenIsAbsentAndReadsAsNull() {
    command("INSERT INTO person (pid, name) VALUES (7, 'Gus')");
    final Result row = query("SELECT age, city FROM person WHERE pid = 7").get(0);
    assertThat((Object) row.getProperty("age")).isNull();
    assertThat((Object) row.getProperty("city")).isNull();
  }

  @Test
  void insertSelectReadsTheTableAsItWasBeforeTheStatement() {
    assertThat(command("INSERT INTO company (cid, name) SELECT pid + 100, name FROM person WHERE city = 'Leiden'")).isEqualTo(2);
    assertThat(command("INSERT INTO company (cid, name) SELECT cid + 1000, name FROM company")).isEqualTo(5);
    assertThat(value("SELECT count(*) FROM company")).isEqualTo(10L);
  }

  // ---- subqueries in writes, UPDATE ... FROM (fork issue #72) ----

  @Test
  void aSubqueryInTheWhereOfAnUpdateAndADelete() {
    // IN, NOT IN, a scalar subquery, EXISTS with an equality, all over the table as it was before the statement
    assertThat(command("UPDATE person SET age = 0 WHERE city IN (SELECT city FROM person WHERE pid = 1)")).isGreaterThan(0);
    assertThat(command("DELETE FROM person WHERE pid NOT IN (SELECT min(pid) FROM person GROUP BY city)")).isGreaterThan(0);
    assertThat(command("UPDATE company SET name = 'x' WHERE cid = (SELECT max(cid) FROM company)")).isEqualTo(1);
    assertThat(command("DELETE FROM company WHERE EXISTS (SELECT 1 FROM works_at w WHERE w.target_id = company.id)")).isGreaterThan(0);
    assertThat(command("UPDATE person SET age = (SELECT max(age) FROM person) WHERE pid = 1")).isEqualTo(1);
  }

  @Test
  void aScalarSubqueryInValuesGivesTheEndsOfAnEdge() {
    assertThat(command("INSERT INTO works_at (source_id, target_id, since) VALUES ((SELECT id FROM person WHERE pid = 5),"
        + " (SELECT id FROM company WHERE cid = 30), 2024)")).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM works_at WHERE since = 2024")).isEqualTo(1L);
  }

  @Test
  void anExistsWithAnInequalityOrAnOrAmongItsEqualities() {
    // the oldest of each city: no other person of the city is older (the residual term is tested on the rows the equality selects)
    assertThat(query("SELECT pid FROM person p WHERE NOT EXISTS (SELECT 1 FROM person q WHERE q.city = p.city AND q.age > p.age)"
        + " ORDER BY pid")).isNotEmpty();
    assertThat(command("DELETE FROM person p WHERE EXISTS (SELECT 1 FROM person q WHERE q.city = p.city AND (q.age > p.age OR"
        + " (q.age = p.age AND q.pid > p.pid)))")).isGreaterThan(0);
    // no equality at all, a term of the outer query alone, an OR of two columns
    assertThat(command("UPDATE company c SET name = 'k' WHERE EXISTS (SELECT 1 FROM works_at w WHERE w.target_id = c.id OR"
        + " w.source_id = c.id)")).isGreaterThanOrEqualTo(0);
    assertThat(value("SELECT count(*) FROM company c WHERE EXISTS (SELECT 1 FROM person q WHERE c.cid > 10 AND q.pid = 1)"))
        .isInstanceOf(Long.class);
    // a term that mixes both sides inside a function is refused, not answered wrongly
    assertState(() -> query("SELECT pid FROM person p WHERE EXISTS (SELECT 1 FROM person q WHERE q.city = p.city AND"
        + " abs(q.age - p.age) > 3)"), "0A000");
  }

  @Test
  void updateFromAJoinedTableAndFromADerivedTable() {
    command("INSERT INTO company (cid, name) VALUES (7, 'Seven')");
    assertThat(command("UPDATE person SET city = c.name FROM company c WHERE person.pid = c.cid / 10")).isGreaterThanOrEqualTo(0);
    assertThat(command("UPDATE person p SET age = t.n FROM (SELECT pid, row_number() OVER (ORDER BY pid) AS n FROM person) t"
        + " WHERE p.pid = t.pid")).isEqualTo(5);
    assertThat(value("SELECT age FROM person WHERE pid = 3")).isEqualTo(3);
    // a target row that no joined row reaches is not changed, one that several reach is changed once
    assertThat(command("UPDATE person p SET age = 99 FROM company c WHERE c.cid = 7 AND p.pid = 1")).isEqualTo(1);
    assertThat(command("UPDATE person p SET age = 98 FROM company c WHERE p.pid = 2")).isEqualTo(1);
    assertThat(value("SELECT age FROM person WHERE pid = 2")).isEqualTo(98);
    assertThat(command("UPDATE person p SET age = 1 FROM company c WHERE c.cid = 12345 AND p.pid = 1")).isEqualTo(0);
  }

  @Test
  void updateFromRefusesWhatItDoesNotDo() {
    assertState(() -> command("UPDATE person p SET age = 1 FROM company c WHERE p.pid = c.cid RETURNING p.pid"), "0A000");
    assertState(() -> command("UPDATE works_at SET since = 1 FROM person p WHERE p.pid = 1"), "0A000");
    assertState(() -> command("UPDATE person p SET id = '#1:1' FROM company c WHERE p.pid = c.cid"), "428C9");
  }

  @Test
  void aValueForTheGeneratedRecordIdIsRefusedWithTheDetailAndTheHintOfPostgreSql() {
    assertThatThrownBy(() -> command("INSERT INTO person (id, pid, name) VALUES ('#9:9', 20, 'X')"))
        .hasMessageContaining("cannot insert a non-DEFAULT value into column \"id\"")
        .hasMessageContaining("is an identity column defined as GENERATED ALWAYS").hasMessageContaining("leave the column out");
    // the way that works: leave it out, or DEFAULT
    assertThat(command("INSERT INTO person (pid, name) VALUES (20, 'X')")).isEqualTo(1);
    assertThat(command("INSERT INTO person (id, pid, name) VALUES (DEFAULT, 21, 'Y')")).isEqualTo(1);
    assertThat((String) value("SELECT id FROM person WHERE pid = 21")).startsWith("#");
  }

  @Test
  void genRandomUuidGivesAnotherUuidInEveryRow() {
    assertThat(command("INSERT INTO company (cid, name) SELECT pid + 100, gen_random_uuid() FROM person")).isEqualTo(5);
    final List<Result> names = query("SELECT name FROM company WHERE cid > 100");
    assertThat(names).hasSize(5);
    assertThat(names.stream().map(r -> (String) r.getProperty("name")).distinct()).hasSize(5)
        .allMatch(n -> n.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    assertThat(value("SELECT length(gen_random_uuid()::text)")).isEqualTo(36);
    assertState(() -> query("SELECT gen_random_uuid(1)"), "42883");
  }

  @Test
  void insertDefaultValuesAndTheWholeRowWithoutAColumnList() {
    assertThat(command("INSERT INTO company DEFAULT VALUES")).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM company WHERE cid IS NULL")).isEqualTo(1L);
    // no column list: the first columns in the order of the table, and id is the first of them: DEFAULT is the only value it takes
    assertThat(command("INSERT INTO company VALUES (DEFAULT, 60, 'Fir')")).isEqualTo(1);
    assertThat(value("SELECT name FROM company WHERE cid = 60")).isEqualTo("Fir");
    assertState(() -> command("INSERT INTO company VALUES ('#9:9', 61, 'Gum')"), "428C9");
    assertState(() -> command("INSERT INTO company VALUES (DEFAULT, 62, 'Gum', 'extra')"), "42601");
  }

  @Test
  void parametersAreBoundByNumber() {
    assertThat(command("INSERT INTO person (pid, name, age) VALUES ($1, $2, $3)", 8, "Hal", "41")).isEqualTo(1);
    assertThat(value("SELECT age FROM person WHERE pid = 8")).isEqualTo(41);
  }

  @Test
  void insertRefusesWithThePostgreSQLStates() {
    assertState(() -> command("INSERT INTO person (id, pid, name) VALUES ('#9:9', 20, 'X')"), "428C9");
    assertState(() -> command("INSERT INTO person (pid, nope) VALUES (21, 1)"), "42703");
    assertState(() -> command("INSERT INTO person (pid) VALUES (22, 'x')"), "42601");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (23)"), "42601");
    assertState(() -> command("INSERT INTO person (pid, pid) VALUES (24, 25)"), "42701");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES ('abc', 'X')"), "22P02");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (3000000000, 'X')"), "22003");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (26::text, 'X')"), "42804");
    assertState(() -> command("INSERT INTO person (pid, name, age) VALUES (27, 'X', true)"), "42804");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'Dup')"), "23505");
    assertState(() -> command("INSERT INTO nope (a) VALUES (1)"), "42P01");
    assertState(() -> command("INSERT INTO information_schema.tables (table_name) VALUES ('x')"), "55000");
    assertState(() -> command("INSERT INTO orders (oid, address) VALUES (300, '[1]')"), "0A000");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
  }

  @Test
  void aStatementThatFailsHalfWayChangesNothing() {
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (30, 'A'), (31, 'B'), (1, 'Dup')"), "23505");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (32, 'A'), (32, 'B')"), "23505");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
  }

  @Test
  void aLinkTakesTheRecordIdOfAnExistingRecordOfItsType() {
    final String ada = (String) value("SELECT id FROM person WHERE pid = 1");
    assertThat(command("INSERT INTO orders (oid, buyer) VALUES (200, $1)", ada)).isEqualTo(1);
    assertThat(value("SELECT buyer FROM orders WHERE oid = 200")).isEqualTo(ada);
    assertThat(command("INSERT INTO orders (oid, buyer) SELECT 201, id FROM person WHERE pid = 2")).isEqualTo(1);
    assertThat(command("INSERT INTO orders (oid, buyer) VALUES (202, NULL)")).isEqualTo(1);
    // a record that does not exist, and a record of another type
    assertState(() -> command("INSERT INTO orders (oid, buyer) VALUES (203, '#99:99')"), "23503");
    final String acme = (String) value("SELECT id FROM company WHERE cid = 10");
    assertState(() -> command("INSERT INTO orders (oid, buyer) VALUES (204, $1)", acme), "23503");
    assertState(() -> command("INSERT INTO orders (oid, buyer) VALUES (205, 'not a record id')"), "23503");
  }

  @Test
  void aPropertyDefaultNotNullAndMandatoryAreHonoured() {
    final DocumentType item = database.getSchema().createDocumentType("item");
    item.createProperty("sku", Type.STRING).setMandatory(true);
    item.createProperty("label", Type.STRING).setNotNull(true).setDefaultValue("'none'");
    item.createProperty("qty", Type.INTEGER).setDefaultValue("7");
    item.createProperty("note", Type.STRING);

    assertThat(command("INSERT INTO item (sku) VALUES ('a-1')")).isEqualTo(1);
    final Result row = query("SELECT sku, label, qty, note FROM item").get(0);
    assertThat(row.<String>getProperty("label")).isEqualTo("none");
    assertThat(row.<Integer>getProperty("qty")).isEqualTo(7);
    assertThat((Object) row.getProperty("note")).isNull();

    // DEFAULT is the default; an explicit NULL is not replaced by it
    assertThat(command("INSERT INTO item (sku, label, qty) VALUES ('a-2', DEFAULT, NULL)")).isEqualTo(1);
    assertThat(value("SELECT label FROM item WHERE sku = 'a-2'")).isEqualTo("none");
    assertThat((Object) query("SELECT qty FROM item WHERE sku = 'a-2'").get(0).getProperty("qty")).isNull();

    assertState(() -> command("INSERT INTO item (label) VALUES ('x')"), "23502");
    assertState(() -> command("INSERT INTO item (sku, label) VALUES ('a-3', NULL)"), "23502");
    assertState(() -> command("UPDATE item SET label = NULL WHERE sku = 'a-1'"), "23502");
    assertThat(command("UPDATE item SET qty = DEFAULT WHERE sku = 'a-2'")).isEqualTo(1);
    assertThat(value("SELECT qty FROM item WHERE sku = 'a-2'")).isEqualTo(7);
  }

  // ---- UPDATE ----

  @Test
  void updateChangesTheMatchedRecordsFromTheirOldValues() {
    assertThat(command("UPDATE person SET age = age + 1 WHERE city = 'Leiden'")).isEqualTo(2);
    assertThat(value("SELECT age FROM person WHERE pid = 4")).isEqualTo(30);
    assertThat((Object) query("SELECT age FROM person WHERE pid = 3").get(0).getProperty("age")).isNull();
    // every right side is read from the row as it was: the two columns swap
    assertThat(command("UPDATE person SET age = pid, pid = age WHERE pid = 4")).isEqualTo(1);
    final Result swapped = query("SELECT pid, age FROM person WHERE name = 'Di'").get(0);
    assertThat(swapped.<Integer>getProperty("pid")).isEqualTo(30);
    assertThat(swapped.<Integer>getProperty("age")).isEqualTo(4);
  }

  @Test
  void updateReturnsTheRowsAfterTheChangeAndSetsNull() {
    try (final ResultSet resultSet = database.command("postgresql",
        "UPDATE person SET city = NULL, age = 40 WHERE pid IN (1, 2) RETURNING pid, city, age")) {
      int rows = 0;
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        assertThat((Object) row.getProperty("city")).isNull();
        assertThat(row.<Integer>getProperty("age")).isEqualTo(40);
        ++rows;
      }
      assertThat(rows).isEqualTo(2);
    }
    assertThat(command("UPDATE person SET age = 1 WHERE pid = 999")).isEqualTo(0);
  }

  @Test
  void updateRefusesWithThePostgreSQLStatesAndLeavesTheTableAsItWas() {
    assertState(() -> command("UPDATE person SET id = '#9:9' WHERE pid = 1"), "428C9");
    assertState(() -> command("UPDATE person SET nope = 1"), "42703");
    assertState(() -> command("UPDATE person SET age = 1, age = 2"), "42601");
    assertState(() -> command("UPDATE person SET age = 'x' WHERE pid = 1"), "22P02");
    assertState(() -> command("UPDATE person SET pid = 2 WHERE pid = 1"), "23505");
    assertState(() -> command("UPDATE person SET age = 'x'::text WHERE pid = 1"), "42804");
    assertState(() -> command("UPDATE orders SET buyer = '#99:99' WHERE oid = 100"), "23503");
    assertState(() -> command("UPDATE person SET age = 1 WHERE age"), "42804");
    assertThat(value("SELECT age FROM person WHERE pid = 1")).isEqualTo(36);
    assertThat(value("SELECT count(*) FROM person WHERE pid = 1")).isEqualTo(1L);
  }

  // ---- DELETE ----

  @Test
  void deleteRemovesTheMatchedRecordsAndReturnsThem() {
    try (final ResultSet resultSet = database.command("postgresql", "DELETE FROM person WHERE pid = 5 RETURNING pid, name")) {
      final Result row = resultSet.next();
      assertThat(row.<Integer>getProperty("pid")).isEqualTo(5);
      assertThat(row.<String>getProperty("name")).isEqualTo("Ed");
    }
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(4L);
    assertThat(command("DELETE FROM person WHERE pid = 999")).isEqualTo(0);
    assertThat(command("DELETE FROM orders")).isEqualTo(6);
    assertThat(value("SELECT count(*) FROM orders")).isEqualTo(0L);
  }

  @Test
  void aDeletedVertexTakesItsEdgesWithIt() {
    assertThat(value("SELECT count(*) FROM works_at")).isEqualTo(5L);
    assertThat(command("DELETE FROM person WHERE pid = 3")).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM works_at")).isEqualTo(4L);
    // the edges of both ends: Birch has Di's edge left, and goes with it
    assertThat(command("DELETE FROM company WHERE cid = 20")).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM works_at")).isEqualTo(3L);
    try (final ResultSet resultSet = database.query("sql", "SELECT count(*) AS n FROM works_at")) {
      assertThat(resultSet.next().<Long>getProperty("n")).isEqualTo(3L);
    }
    assertThat(query("EXPLAIN DELETE FROM person WHERE pid = 1").get(0).<String>getProperty("QUERY PLAN"))
        .contains("the edges of a deleted vertex go with it");
  }

  // ---- the access path ----

  @Test
  void updateAndDeleteUseTheAccessPathOfTheReadSide() {
    final String ada = (String) value("SELECT id FROM person WHERE pid = 1");
    assertThat(plan("EXPLAIN UPDATE person SET age = 1 WHERE pid = 1")).anyMatch(line -> line.contains("FETCH FROM INDEX person[pid]"));
    assertThat(plan("EXPLAIN DELETE FROM person WHERE pid = 1 AND age > 1")).anyMatch(line -> line.contains("FETCH FROM INDEX person[pid]"))
        .anyMatch(line -> line.contains("FILTER WHERE"));
    assertThat(plan("EXPLAIN DELETE FROM person WHERE id = '" + ada + "'")).anyMatch(line -> line.contains("READ RECORD BY ID person"));
    assertThat(plan("EXPLAIN UPDATE person SET age = 1 WHERE age > 1")).anyMatch(line -> line.contains("SCAN TYPE person (polymorphic)"));
    assertThat(plan("EXPLAIN UPDATE ONLY person SET age = 1")).anyMatch(line -> line.contains("SCAN TYPE person ONLY"));
    assertThat(plan("EXPLAIN INSERT INTO person (pid) VALUES (1), (2)")).anyMatch(line -> line.contains("2 row(s) of VALUES"));
    // EXPLAIN writes nothing
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
    assertThat(value("SELECT age FROM person WHERE pid = 1")).isEqualTo(36);
  }

  private List<String> plan(final String sql) {
    final List<String> lines = new ArrayList<>();
    for (final Result row : query(sql))
      lines.add(row.getProperty("QUERY PLAN"));
    return lines;
  }

  // ---- inheritance ----

  @Test
  void aSupertypeTableWritesItsSubtypesAndOnlyLeavesThemAlone() {
    final VertexType student = database.getSchema().createVertexType("student");
    student.addSuperType("person");
    command("INSERT INTO student (pid, name, age) VALUES (60, 'Zed', 20)");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(6L);
    assertThat(command("UPDATE ONLY person SET age = 99")).isEqualTo(5);
    assertThat(value("SELECT age FROM student WHERE pid = 60")).isEqualTo(20);
    assertThat(command("DELETE FROM ONLY person")).isEqualTo(5);
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(1L);
    assertThat(command("DELETE FROM person")).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM student")).isEqualTo(0L);
  }

  // ---- transactions ----

  @Test
  void aStatementJoinsTheTransactionOfTheCallerAndOtherwiseIsOneTransaction() {
    database.begin();
    command("INSERT INTO person (pid, name) VALUES (70, 'Tx')");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(6L);
    database.rollback();
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);

    database.begin();
    command("INSERT INTO person (pid, name) VALUES (71, 'Tx')");
    database.commit();
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(6L);
    assertThat(database.isTransactionActive()).isFalse();
  }

  // ---- edge tables ----

  private long outgoing(final int pid, final String edgeType) {
    final Vertex vertex = (Vertex) database.lookupByRID(new com.arcadedb.database.RID((String) value("SELECT id FROM person WHERE pid = " + pid)), true);
    return vertex.countEdges(Vertex.DIRECTION.OUT, edgeType);
  }

  @Test
  void insertCreatesTheEdgeBetweenTheNamedVertices() {
    final String ed = (String) value("SELECT id FROM person WHERE pid = 5");
    final String cedar = (String) value("SELECT id FROM company WHERE cid = 30");
    assertThat(outgoing(5, "works_at")).isEqualTo(0);
    assertThat(command("INSERT INTO works_at (source_id, target_id, since) VALUES ($1, $2, 2024)", ed, cedar)).isEqualTo(1);
    // the edge is the same record that Cypher and sql walk: the engine sees it from the vertex
    assertThat(outgoing(5, "works_at")).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM works_at")).isEqualTo(6L);
    final Result row = query("SELECT source_id, target_id, since FROM works_at WHERE since = 2024").get(0);
    assertThat(row.<String>getProperty("source_id")).isEqualTo(ed);
    assertThat(row.<String>getProperty("target_id")).isEqualTo(cedar);
    try (final ResultSet resultSet = database.query("sql", "SELECT count(*) AS n FROM works_at WHERE since = 2024")) {
      assertThat(resultSet.next().<Long>getProperty("n")).isEqualTo(1L);
    }
  }

  @Test
  void insertEdgeReturnsTheEdgeAndAParallelEdgeIsAllowed() {
    try (final ResultSet resultSet = database.command("postgresql",
        "INSERT INTO works_at (source_id, target_id, since) SELECT p.id, c.id, 1 FROM person p, company c WHERE p.pid = 1 AND c.cid = 10 "
            + "RETURNING id, since")) {
      final Result row = resultSet.next();
      assertThat(row.<String>getProperty("id")).startsWith("#");
      assertThat(row.<Integer>getProperty("since")).isEqualTo(1);
    }
    assertThat(outgoing(1, "works_at")).isEqualTo(3);
  }

  @Test
  void insertEdgeRefusesWithThePostgreSQLStates() {
    final String ada = (String) value("SELECT id FROM person WHERE pid = 1");
    final String acme = (String) value("SELECT id FROM company WHERE cid = 10");
    assertState(() -> command("INSERT INTO works_at (target_id) VALUES ($1)", acme), "23502");
    assertState(() -> command("INSERT INTO works_at (source_id) VALUES ($1)", ada), "23502");
    assertState(() -> command("INSERT INTO works_at (source_id, target_id) VALUES (NULL, $1)", acme), "23502");
    assertState(() -> command("INSERT INTO works_at (source_id, target_id) VALUES ('#99:99', $1)", acme), "23503");
    assertState(() -> command("INSERT INTO works_at (source_id, target_id) VALUES ($1, 'nonsense')", ada), "23503");
    // the declared endpoint tables of works_at are person and company
    assertState(() -> command("INSERT INTO works_at (source_id, target_id) VALUES ($1, $1)", acme), "23503");
    assertState(() -> command("INSERT INTO works_at (source_id, target_id) VALUES ($1, $1)", ada), "23503");
    assertState(() -> command("INSERT INTO works_at (id, source_id, target_id) VALUES ('#9:9', $1, $2)", ada, acme), "428C9");
    assertThat(value("SELECT count(*) FROM works_at")).isEqualTo(5L);
  }

  @Test
  void anEdgeTypeWithNoDeclaredEndpointsTakesAnyTwoVertices() {
    database.getSchema().createEdgeType("related");
    final String ada = (String) value("SELECT id FROM person WHERE pid = 1");
    final String acme = (String) value("SELECT id FROM company WHERE cid = 10");
    assertThat(command("INSERT INTO related (source_id, target_id) VALUES ($1, $2)", ada, acme)).isEqualTo(1);
    assertThat(command("INSERT INTO related (source_id, target_id) VALUES ($2, $1)", ada, acme)).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM related")).isEqualTo(2L);
  }

  @Test
  void aLightweightEdgeIsWrittenAndDeletedLikeAnyOtherAndKeepsNoRecord() {
    final EdgeType knows = database.getSchema().buildEdgeType().withName("knows").withLightweight(true).create();
    assertThat(knows.isLightweight()).isTrue();
    final String ada = (String) value("SELECT id FROM person WHERE pid = 1");
    final String bo = (String) value("SELECT id FROM person WHERE pid = 2");
    assertThat(command("INSERT INTO knows (source_id, target_id) VALUES ($1, $2)", ada, bo)).isEqualTo(1);
    assertThat(outgoing(1, "knows")).isEqualTo(1);
    assertThat(value("SELECT count(*) FROM knows")).isEqualTo(1L);
    assertThat(command("DELETE FROM knows")).isEqualTo(1);
    assertThat(outgoing(1, "knows")).isEqualTo(0);
  }

  @Test
  void updateChangesTheEdgePropertiesAndNeverTheEnds() {
    assertThat(command("UPDATE works_at SET since = since + 1 WHERE since < 2021")).isEqualTo(2);
    assertThat(value("SELECT count(*) FROM works_at WHERE since = 2020")).isEqualTo(1L);
    assertThat(value("SELECT count(*) FROM works_at WHERE since = 2021")).isEqualTo(2L);
    assertState(() -> command("UPDATE works_at SET source_id = target_id"), "0A000");
    assertState(() -> command("UPDATE works_at SET target_id = source_id"), "0A000");
    assertState(() -> command("UPDATE works_at SET id = '#9:9'"), "428C9");
    assertThatThrownBy(() -> command("UPDATE works_at SET source_id = target_id")).hasMessageContaining("delete the edge and insert it again");
  }

  @Test
  void deleteRemovesTheEdgesAndKeepsTheVertices() {
    try (final ResultSet resultSet = database.command("postgresql", "DELETE FROM works_at WHERE since = 2019 RETURNING since")) {
      assertThat(resultSet.next().<Integer>getProperty("since")).isEqualTo(2019);
    }
    assertThat(value("SELECT count(*) FROM works_at")).isEqualTo(4L);
    assertThat(outgoing(1, "works_at")).isEqualTo(1);
    assertThat(command("DELETE FROM works_at")).isEqualTo(4);
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
    assertThat(value("SELECT count(*) FROM company")).isEqualTo(3L);
  }

  @Test
  void anEdgeRecordIsWrittenInTheTransactionOfTheCaller() {
    final String ed = (String) value("SELECT id FROM person WHERE pid = 5");
    final String cedar = (String) value("SELECT id FROM company WHERE cid = 30");
    database.begin();
    command("INSERT INTO works_at (source_id, target_id) VALUES ($1, $2)", ed, cedar);
    database.rollback();
    assertThat(value("SELECT count(*) FROM works_at")).isEqualTo(5L);
    assertThat(outgoing(5, "works_at")).isEqualTo(0);
  }

  @Test
  void aWriteIsRefusedByQueryAsItIsInSql() {
    // Database.query is the read-only entry, as for sql: a write runs through command, which the analysis says (not idempotent)
    assertState(() -> database.query("postgresql", "DELETE FROM person"), "25006");
    assertState(() -> database.query("postgresql", "INSERT INTO person (pid) VALUES (80)"), "25006");
    assertState(() -> database.query("postgresql", "BEGIN"), "25006");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
  }

  // ---- ON CONFLICT (the upsert; fork issue: ON CONFLICT of the gap list of #35) ----

  private List<Object> column(final String sql, final String name) {
    final List<Object> values = new ArrayList<>();
    for (final Result row : query(sql))
      values.add(row.getProperty(name));
    return values;
  }

  @Test
  void onConflictDoNothingSkipsTheRowThatBreaksAUniqueKey() {
    assertThat(command("INSERT INTO person (pid, name) VALUES (1, 'Dup') ON CONFLICT DO NOTHING")).isEqualTo(0);

    assertThat(value("SELECT name FROM person WHERE pid = 1")).isEqualTo("Ada");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
  }

  @Test
  void onConflictDoNothingWithATargetInsertsTheOtherRowsAndReturnsOnlyThem() {
    try (final ResultSet resultSet = database.command("postgresql",
        "INSERT INTO person (pid, name) VALUES (1, 'Dup'), (6, 'Fay') ON CONFLICT (pid) DO NOTHING RETURNING pid, name")) {
      final PostgreSQLResultSet answer = (PostgreSQLResultSet) resultSet;
      assertThat(answer.affectedRows()).isEqualTo(1);
      final List<Result> rows = new ArrayList<>();
      while (resultSet.hasNext())
        rows.add(resultSet.next());
      assertThat(rows).hasSize(1);
      assertThat((Object) rows.get(0).getProperty("name")).isEqualTo("Fay");
    }
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(6L);
  }

  @Test
  void onConflictDoNothingSeesTheRowThatTheSameStatementInserted() {
    assertThat(command("INSERT INTO person (pid, name) VALUES (7, 'first'), (7, 'second') ON CONFLICT DO NOTHING")).isEqualTo(1);

    assertThat(column("SELECT name FROM person WHERE pid = 7", "name")).containsExactly("first");
  }

  @Test
  void onConflictDoNothingWorksForInsertSelect() {
    // the cids 10, 20 and 30 are taken: pid * 10 gives 10, 20, 30, 40, 50
    assertThat(command("INSERT INTO company (cid, name) SELECT pid * 10, name FROM person ON CONFLICT DO NOTHING")).isEqualTo(2);

    assertThat(column("SELECT cid FROM company ORDER BY cid", "cid")).containsExactly(10, 20, 30, 40, 50);
  }

  @Test
  void aNullInTheKeyConflictsWithNothing() {
    // the unique key of company is cid: two rows with a NULL cid are both inserted, as in PostgreSQL
    assertThat(command("INSERT INTO company (name) VALUES ('x'), ('y') ON CONFLICT DO NOTHING")).isEqualTo(2);
  }

  @Test
  void onConflictDoUpdateChangesTheRowThatIsThereWithExcluded() {
    try (final ResultSet resultSet = database.command("postgresql",
        "INSERT INTO person (pid, name, age) VALUES (1, 'Ada L', 37) ON CONFLICT (pid) DO UPDATE "
            + "SET name = EXCLUDED.name, age = EXCLUDED.age RETURNING pid, name, age")) {
      final PostgreSQLResultSet answer = (PostgreSQLResultSet) resultSet;
      assertThat(answer.affectedRows()).isEqualTo(1);
      final Result row = resultSet.next();
      assertThat((Object) row.getProperty("name")).isEqualTo("Ada L");
      assertThat((Object) row.getProperty("age")).isEqualTo(37);
    }
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
    assertThat(value("SELECT city FROM person WHERE pid = 1")).isEqualTo("Delft");
  }

  @Test
  void onConflictDoUpdateReadsTheRowThatIsThereByTheTableNameOrTheAlias() {
    command("INSERT INTO person (pid, name, age) VALUES (1, 'x', 1) ON CONFLICT (pid) DO UPDATE SET age = person.age + EXCLUDED.age");
    assertThat(value("SELECT age FROM person WHERE pid = 1")).isEqualTo(37);

    command("INSERT INTO person AS p (pid, name, age) VALUES (2, 'x', 10) ON CONFLICT (pid) DO UPDATE SET age = p.age * EXCLUDED.age");
    assertThat(value("SELECT age FROM person WHERE pid = 2")).isEqualTo(410);
  }

  @Test
  void onConflictDoUpdateOfANullStaysNullThroughArithmetic() {
    command("INSERT INTO person (pid, name, age) VALUES (3, 'x', 5) ON CONFLICT (pid) DO UPDATE SET age = person.age + EXCLUDED.age");
    assertThat(value("SELECT age FROM person WHERE pid = 3")).isNull();

    command("INSERT INTO person (pid, name, age) VALUES (3, 'x', 5) ON CONFLICT (pid) DO UPDATE SET age = coalesce(person.age, 0) + EXCLUDED.age");
    assertThat(value("SELECT age FROM person WHERE pid = 3")).isEqualTo(5);
  }

  @Test
  void onConflictDoUpdateWhereLeavesTheRowThatFailsItAndDoesNotCountIt() {
    assertThat(command("INSERT INTO person (pid, name) VALUES (1, 'x') ON CONFLICT (pid) DO UPDATE SET name = EXCLUDED.name "
        + "WHERE person.age > 100")).isEqualTo(0);
    assertThat(value("SELECT name FROM person WHERE pid = 1")).isEqualTo("Ada");

    assertThat(command("INSERT INTO person (pid, name) VALUES (1, 'x') ON CONFLICT (pid) DO UPDATE SET name = EXCLUDED.name "
        + "WHERE person.age > 30")).isEqualTo(1);
    assertThat(value("SELECT name FROM person WHERE pid = 1")).isEqualTo("x");
  }

  @Test
  void onConflictDoUpdateInsertsTheRowsThatHaveNoConflict() {
    assertThat(command("INSERT INTO person (pid, name) VALUES (1, 'x'), (8, 'Hal') ON CONFLICT (pid) DO UPDATE "
        + "SET name = EXCLUDED.name")).isEqualTo(2);

    assertThat(value("SELECT name FROM person WHERE pid = 1")).isEqualTo("x");
    assertThat(value("SELECT name FROM person WHERE pid = 8")).isEqualTo("Hal");
  }

  @Test
  void anUpsertWritesTheRecordThatCypherReads() {
    command("INSERT INTO person (pid, name) VALUES (1, 'Ada L') ON CONFLICT (pid) DO UPDATE SET name = EXCLUDED.name");

    try (final ResultSet resultSet = database.query("opencypher", "MATCH (p:person {pid: 1}) RETURN p.name AS name")) {
      assertThat((Object) resultSet.next().getProperty("name")).isEqualTo("Ada L");
    }
  }

  @Test
  void aCompositeKeyIsTheTargetInAnyOrderOfItsColumns() {
    database.command("sql", "CREATE DOCUMENT TYPE fact");
    database.command("sql", "CREATE PROPERTY fact.subject STRING");
    database.command("sql", "CREATE PROPERTY fact.kind STRING");
    database.command("sql", "CREATE PROPERTY fact.fact_value STRING");
    database.command("sql", "CREATE INDEX ON fact (subject, kind) UNIQUE");
    command("INSERT INTO fact (subject, kind, fact_value) VALUES ('ada', 'city', 'Delft')");

    assertThat(command("INSERT INTO fact (subject, kind, fact_value) VALUES ('ada', 'city', 'Leiden') "
        + "ON CONFLICT (kind, subject) DO UPDATE SET fact_value = EXCLUDED.fact_value")).isEqualTo(1);
    assertThat(value("SELECT fact_value FROM fact WHERE subject = 'ada'")).isEqualTo("Leiden");
    assertThat(value("SELECT count(*) FROM fact")).isEqualTo(1L);
    // a target that is only a part of the key is no unique index
    assertState(() -> command("INSERT INTO fact (subject, kind) VALUES ('bo', 'city') ON CONFLICT (subject) DO NOTHING"), "42P10");
  }

  @Test
  void aTargetThatNoUniqueIndexMatchesIs42P10EvenForAnEmptyOrUntouchedTable() {
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (9, 'x') ON CONFLICT (name) DO NOTHING"), "42P10");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (9, 'x') ON CONFLICT (name) DO UPDATE SET age = 1"), "42P10");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
  }

  @Test
  void onConflictErrorsCarryTheSqlStateOfPostgreSQL() {
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'x') ON CONFLICT (nope) DO NOTHING"), "42703");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'x') ON CONFLICT DO UPDATE SET name = 'y'"), "42601");
    // a bare column name that the row there and the proposed row both have is ambiguous
    assertState(() -> command("INSERT INTO person (pid, name, age) VALUES (1, 'x', 1) ON CONFLICT (pid) DO UPDATE SET age = age + 1"),
        "42702");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'x') ON CONFLICT (pid) DO UPDATE SET nope = 1"), "42703");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'x') ON CONFLICT (pid) DO UPDATE SET id = 'a'"), "428C9");
    // a row that would change what the same statement inserted or changed
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'a'), (1, 'b') ON CONFLICT (pid) DO UPDATE "
        + "SET name = EXCLUDED.name"), "21000");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (9, 'a'), (9, 'b') ON CONFLICT (pid) DO UPDATE "
        + "SET name = EXCLUDED.name"), "21000");
    // the statement is one transaction: nothing of it stays
    assertThat(value("SELECT name FROM person WHERE pid = 1")).isEqualTo("Ada");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
  }

  @Test
  void aBreakOfAnIndexTheTargetDoesNotNameIsStill23505() {
    database.command("sql", "CREATE DOCUMENT TYPE tag");
    database.command("sql", "CREATE PROPERTY tag.a INTEGER");
    database.command("sql", "CREATE PROPERTY tag.b INTEGER");
    database.command("sql", "CREATE INDEX ON tag (a) UNIQUE");
    database.command("sql", "CREATE INDEX ON tag (b) UNIQUE");
    command("INSERT INTO tag (a, b) VALUES (1, 1)");

    // no target: either index is an arbiter, the row is skipped
    assertThat(command("INSERT INTO tag (a, b) VALUES (2, 1) ON CONFLICT DO NOTHING")).isEqualTo(0);
    // target a: only a is an arbiter, the break of b is an error
    assertState(() -> command("INSERT INTO tag (a, b) VALUES (2, 1) ON CONFLICT (a) DO NOTHING"), "23505");
    assertThat(command("INSERT INTO tag (a, b) VALUES (1, 5) ON CONFLICT (a) DO UPDATE SET b = EXCLUDED.b")).isEqualTo(1);
    assertState(() -> command("INSERT INTO tag (a, b) VALUES (3, 3), (1, 3) ON CONFLICT (a) DO UPDATE SET b = EXCLUDED.b"), "23505");
  }

  @Test
  void whatThePrototypeCannotRunOfOnConflictIsRefusedWith0A000() {
    assertState(() -> command("INSERT INTO person (pid) VALUES (1) ON CONFLICT ON CONSTRAINT person_pkey DO NOTHING"), "0A000");
    assertState(() -> command("INSERT INTO person (pid) VALUES (1) ON CONFLICT (lower(name)) DO NOTHING"), "0A000");
    assertState(() -> command("INSERT INTO person (pid) VALUES (1) ON CONFLICT (pid) WHERE pid > 0 DO NOTHING"), "0A000");
    assertState(() -> command("INSERT INTO works_at (source_id, target_id) VALUES ('#1:1', '#1:2') ON CONFLICT DO NOTHING"), "0A000");
  }

  @Test
  void explainShowsTheOnConflictStep() {
    try (final ResultSet resultSet = database.command("postgresql",
        "EXPLAIN INSERT INTO person (pid, name) VALUES (9, 'x') ON CONFLICT (pid) DO NOTHING")) {
      final StringBuilder text = new StringBuilder();
      while (resultSet.hasNext())
        text.append(resultSet.next().toString()).append('\n');
      assertThat(text.toString()).contains("ON CONFLICT (pid) DO NOTHING");
    }
  }
}
