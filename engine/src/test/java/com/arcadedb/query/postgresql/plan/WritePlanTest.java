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
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
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
    assertState(() -> command("INSERT INTO orders (oid, address) VALUES (300, '{}')"), "0A000");
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
    item.createProperty("label", Type.STRING).setNotNull(true).setDefaultValue("none");
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

  @Test
  void aWriteIsRefusedByQueryAsItIsInSql() {
    // Database.query is the read-only entry, as for sql: a write runs through command, which the analysis says (not idempotent)
    assertState(() -> database.query("postgresql", "DELETE FROM person"), "25006");
    assertState(() -> database.query("postgresql", "INSERT INTO person (pid) VALUES (80)"), "25006");
    assertState(() -> database.query("postgresql", "BEGIN"), "25006");
    assertThat(value("SELECT count(*) FROM person")).isEqualTo(5L);
  }
}
