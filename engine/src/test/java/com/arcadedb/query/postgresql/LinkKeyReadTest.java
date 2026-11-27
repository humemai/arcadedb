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

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Link tables whose ends refer to a unique column of the table at that end (fork issue #213): the end shows the value of that
 * column, the ordinary PostgreSQL join answers, and the join walks the stored links as the join on record ids does. The edge
 * types are declared in ArcadeDB {@code sql} with {@code source_column} and {@code target_column}.
 */
class LinkKeyReadTest extends TestHelper {

  @Override
  protected void beginTest() {
    database.command("sqlscript", """
        CREATE VERTEX TYPE being;
        CREATE PROPERTY being.id LONG;
        CREATE PROPERTY being.name STRING;
        CREATE VERTEX TYPE person EXTENDS being;
        CREATE INDEX ON person (id) UNIQUE;
        CREATE VERTEX TYPE robot EXTENDS being;
        CREATE VERTEX TYPE company;
        CREATE PROPERTY company.code STRING;
        CREATE PROPERTY company.name STRING;
        CREATE INDEX ON company (code) UNIQUE;
        CREATE EDGE TYPE knows CUSTOM source_table = 'person', source_column = 'id', target_table = 'person', target_column = 'id';
        CREATE PROPERTY knows.since INTEGER;
        CREATE EDGE TYPE works_at CUSTOM source_table = 'person', source_column = 'id', target_table = 'company', target_column = 'code';
        CREATE PROPERTY works_at.since INTEGER;
        CREATE EDGE TYPE met CUSTOM source_table = 'person', target_table = 'person';
        """);
    database.transaction(() -> database.command("sqlscript", """
        CREATE VERTEX person SET id = 1, name = 'Ada';
        CREATE VERTEX person SET id = 2, name = 'Bo';
        CREATE VERTEX person SET id = 3, name = 'Cy';
        CREATE VERTEX person SET id = 4, name = 'Di';
        CREATE VERTEX robot SET id = 2, name = 'R2';
        CREATE VERTEX company SET code = 'acme', name = 'Acme';
        CREATE VERTEX company SET code = 'birch', name = 'Birch';
        CREATE EDGE knows FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 2) SET since = 2020;
        CREATE EDGE knows FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 3) SET since = 2021;
        CREATE EDGE knows FROM (SELECT FROM person WHERE id = 2) TO (SELECT FROM person WHERE id = 3) SET since = 2022;
        CREATE EDGE knows FROM (SELECT FROM person WHERE id = 3) TO (SELECT FROM person WHERE id = 1) SET since = 2023;
        CREATE EDGE works_at FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM company WHERE code = 'acme') SET since = 2019;
        CREATE EDGE works_at FROM (SELECT FROM person WHERE id = 2) TO (SELECT FROM company WHERE code = 'acme') SET since = 2020;
        CREATE EDGE works_at FROM (SELECT FROM person WHERE id = 3) TO (SELECT FROM company WHERE code = 'birch') SET since = 2021;
        CREATE EDGE met FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 4);
        """));
  }

  private static List<Object> row(final Object... values) {
    return Arrays.asList(values);
  }

  private List<List<Object>> rows(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      final List<List<Object>> out = new ArrayList<>();
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> values = new ArrayList<>();
        for (final String name : result.getPropertyNames())
          values.add(result.getProperty(name));
        out.add(values);
      }
      return out;
    }
  }

  private List<String> types(final String sql) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql)) {
      return resultSet.columns().stream().map(PostgreSQLResultSet.Column::typeName).toList();
    }
  }

  private String plan(final String sql) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  /** The rows with the traversal rule on, which have to be those with the rule off. */
  private List<List<Object>> sameWithAndWithoutTheRule(final String sql) {
    final List<List<Object>> on = rows(sql);
    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, false);
    try {
      assertThat(rows(sql)).as(sql).containsExactlyInAnyOrderElementsOf(on);
    } finally {
      database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE, true);
    }
    return on;
  }

  private static String refusal(final ThrowingCallable call) {
    final Throwable thrown = catchThrowable(call);
    assertThat(thrown).as("a refusal").isNotNull();
    try {
      return thrown.getClass().getMethod("getSqlState").invoke(thrown) + " " + thrown.getMessage();
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError("not a refusal of the language: " + thrown, thrown);
    }
  }

  @Test
  void theEndsReadTheKeyOfTheRecordsTheyLink() {
    final String sql = "SELECT source_id, target_id, since FROM knows ORDER BY since";
    assertThat(rows(sql)).containsExactly(row(1L, 2L, 2020), row(1L, 3L, 2021), row(2L, 3L, 2022), row(3L, 1L, 2023));
    assertThat(types(sql)).containsExactly("int8", "int8", "int4");
    final String text = "SELECT source_id, target_id FROM works_at ORDER BY since";
    assertThat(rows(text)).containsExactly(row(1L, "acme"), row(2L, "acme"), row(3L, "birch"));
    assertThat(types(text)).containsExactly("int8", "varchar");
    assertThat(rows("SELECT target_id FROM knows WHERE source_id = 1 ORDER BY target_id")).containsExactly(row(2L), row(3L));
    assertThat(rows("SELECT source_id, count(*) FROM knows GROUP BY source_id ORDER BY source_id"))
        .containsExactly(row(1L, 2L), row(2L, 1L), row(3L, 1L));
  }

  @Test
  void theOrdinaryJoinWalksTheLinks() {
    final String sql = "SELECT q.name, k.source_id, k.target_id FROM person p JOIN knows k ON k.source_id = p.id "
        + "JOIN person q ON q.id = k.target_id WHERE p.id = 1 ORDER BY q.name";
    assertThat(sameWithAndWithoutTheRule(sql)).containsExactly(row("Bo", 1L, 2L), row("Cy", 1L, 3L));
    assertThat(plan(sql)).contains("TRAVERSE INNER").contains("TRAVERSE TO q")
        .contains("READ RECORD person AS q (the record at the end of the edge)").doesNotContain("WARNING");
  }

  @Test
  void anIncomingWalkAndTwoHops() {
    assertThat(sameWithAndWithoutTheRule("SELECT q.name FROM person p JOIN knows k ON k.target_id = p.id "
        + "JOIN person q ON q.id = k.source_id WHERE p.id = 3 ORDER BY q.name")).containsExactly(row("Ada"), row("Bo"));
    final String two = "SELECT r.name, b.since FROM person p JOIN knows a ON a.source_id = p.id JOIN person q ON q.id = a.target_id "
        + "JOIN knows b ON b.source_id = q.id JOIN person r ON r.id = b.target_id WHERE p.id = 1 ORDER BY b.since";
    assertThat(sameWithAndWithoutTheRule(two)).containsExactly(row("Cy", 2022), row("Ada", 2023));
    assertThat(plan(two).split("TRAVERSE TO", -1)).hasSize(3);
  }

  @Test
  void leftJoinsAndATextKey() {
    assertThat(sameWithAndWithoutTheRule("SELECT p.name, k.target_id, q.name FROM person p LEFT JOIN knows k ON k.source_id = p.id "
        + "LEFT JOIN person q ON q.id = k.target_id WHERE p.id = 4")).containsExactly(row("Di", null, null));
    assertThat(sameWithAndWithoutTheRule("SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id "
        + "JOIN company c ON c.code = w.target_id WHERE p.id IN (1, 3) ORDER BY p.name"))
        .containsExactly(row("Ada", "Acme"), row("Cy", "Birch"));
    assertThat(sameWithAndWithoutTheRule("SELECT p.name FROM company c JOIN works_at w ON w.target_id = c.code "
        + "JOIN person p ON p.id = w.source_id WHERE c.code = 'acme' ORDER BY p.name")).containsExactly(row("Ada"), row("Bo"));
  }

  @Test
  void aSupertypeOfTheDeclaredTableIsJoinedOnTheValue() {
    // being 2 is both the person Bo and the robot R2: PostgreSQL's join on the value finds both
    assertThat(sameWithAndWithoutTheRule("SELECT b.name FROM person p JOIN knows k ON k.source_id = p.id "
        + "JOIN being b ON b.id = k.target_id WHERE p.id = 1 ORDER BY b.name"))
        .containsExactly(row("Bo"), row("Cy"), row("R2"));
  }

  @Test
  void theRecordIdIsNotTheKey() {
    assertThat(refusal(() -> rows("SELECT q.name FROM knows k JOIN person q ON q.rid = k.target_id"))).startsWith("42883");
  }

  @Test
  void aLinkTableOnRecordIdsIsUnchanged() {
    assertThat(rows("SELECT q.name FROM person p JOIN met m ON m.source_id = p.rid JOIN person q ON q.rid = m.target_id "
        + "WHERE p.id = 1")).containsExactly(row("Di"));
    assertThat(types("SELECT source_id, target_id FROM met")).containsExactly("text", "text");
  }

  @Test
  void theCatalogShowsTheKey() {
    assertThat(rows("SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'works_at' "
        + "AND column_name IN ('source_id', 'target_id') ORDER BY column_name"))
        .containsExactly(row("source_id", "bigint", "NO"), row("target_id", "character varying", "NO"));
    assertThat(rows("SELECT rc.constraint_name, rc.unique_constraint_name, rc.delete_rule, pk.table_name, pk.column_name "
        + "FROM information_schema.referential_constraints rc JOIN information_schema.key_column_usage pk "
        + "ON pk.constraint_name = rc.unique_constraint_name WHERE rc.constraint_name LIKE 'works_at%' ORDER BY 1"))
        .containsExactly(row("works_at_source_id_fkey", "person_pkey", "CASCADE", "person", "id"),
            row("works_at_target_id_fkey", "company_code_key", "CASCADE", "company", "code"));
  }

  @Test
  void aDeclarationThatNamesNoUniqueColumnIsRefused() {
    database.command("sql", "CREATE EDGE TYPE likes CUSTOM source_table = 'person', source_column = 'name', "
        + "target_table = 'person', target_column = 'id'");
    assertThat(refusal(() -> rows("SELECT count(*) FROM likes"))).startsWith("55000")
        .contains("CREATE UNIQUE INDEX ON person (name)");
    // the other tables answer, and so does the catalog
    assertThat(rows("SELECT count(*) FROM knows")).containsExactly(row(4L));
    database.command("sql", "CREATE INDEX ON person (name) UNIQUE");
    assertThat(rows("SELECT count(*) FROM likes")).containsExactly(row(0L));
  }

  @Test
  void aLinkToARecordOfAnotherTableIsRefusedAndNeverShown() {
    database.transaction(() -> database.command("sql",
        "CREATE EDGE knows FROM (SELECT FROM person WHERE id = 4) TO (SELECT FROM company WHERE code = 'acme')"));
    assertThat(refusal(() -> rows("SELECT target_id FROM knows"))).startsWith("23503").contains("target_id");
    assertThat(refusal(() -> rows("SELECT q.name FROM person p JOIN knows k ON k.source_id = p.id "
        + "JOIN person q ON q.id = k.target_id WHERE p.id = 4"))).startsWith("23503");
    // a statement that does not read the end answers
    assertThat(rows("SELECT count(*) FROM knows")).containsExactly(row(5L));
    assertThat(rows("SELECT q.name FROM person p JOIN knows k ON k.source_id = p.id "
        + "JOIN person q ON q.id = k.target_id WHERE p.id = 2")).containsExactly(row("Cy"));
  }

  @Test
  void aLinkToARecordWithoutAKeyIsRefused() {
    database.transaction(() -> database.command("sqlscript", """
        CREATE VERTEX person SET name = 'Nil';
        CREATE EDGE knows FROM (SELECT FROM person WHERE id = 4) TO (SELECT FROM person WHERE name = 'Nil');
        """));
    assertThat(refusal(() -> rows("SELECT target_id FROM knows"))).startsWith("23503");
  }
}
