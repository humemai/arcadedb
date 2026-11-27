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
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.postgresql.PostgreSQLCatalogException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.join.BuildMemory;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The plan of one table on the {@code small} fixture of fork issue #2: names bound through the catalog, the select list, the
 * filter, the sort with {@code NULL} as the largest value, {@code LIMIT} and {@code OFFSET}, parameters, {@code ONLY} and
 * inheritance, grouping, and which access path reads the table (asserted on the plan, never on timing).
 */
class SelectPlanTest extends TestHelper {

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  // ---- helpers ----

  private static BigDecimal dec(final String value) {
    return new BigDecimal(value).stripTrailingZeros();
  }

  private static List<Object> row(final Object... values) {
    return Arrays.asList(values);
  }

  /** The rows in column order, a numeric as its stripped value so that the scale of the text does not matter. */
  private List<List<Object>> rows(final String sql, final Object... parameters) {
    try (final ResultSet resultSet = database.query("postgresql", sql, parameters)) {
      final List<List<Object>> out = new ArrayList<>();
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> values = new ArrayList<>();
        for (final String name : result.getPropertyNames()) {
          final Object value = result.getProperty(name);
          values.add(value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value);
        }
        out.add(values);
      }
      return out;
    }
  }

  private List<Object> column(final String sql, final Object... parameters) {
    final List<Object> out = new ArrayList<>();
    for (final List<Object> values : rows(sql, parameters))
      out.add(values.get(0));
    return out;
  }

  private PostgreSQLResultSet run(final String sql, final Object... parameters) {
    return (PostgreSQLResultSet) database.query("postgresql", sql, parameters);
  }

  private String plan(final String sql, final Object... parameters) {
    try (final PostgreSQLResultSet resultSet = run(sql, parameters)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  /** The SQLSTATE the statement is refused with, {@code null} when it answers. */
  private String state(final String sql, final Object... parameters) {
    try {
      rows(sql, parameters);
      return null;
    } catch (final PostgreSQLCatalogException e) {
      return e.getSqlState();
    } catch (final PostgreSQLEvaluationException e) {
      return e.getSqlState();
    } catch (final PostgreSQLFeatureNotSupportedException e) {
      return e.getSqlState();
    }
  }

  // ---- select list and names ----

  @Test
  void aSelectWithoutFromIsOneRow() {
    assertThat(rows("SELECT 1, 'a', true")).containsExactly(row(1, "a", true));
    try (final PostgreSQLResultSet resultSet = run("SELECT 1, 'a', true")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::name).containsExactly("?column?", "?column?", "?column?");
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName).containsExactly("int4", "text", "bool");
      // a Result is a map: the second and later duplicate label gets a suffix
      assertThat(resultSet.next().getPropertyNames()).containsExactly("?column?", "?column?_1", "?column?_2");
    }
    assertThat(rows("SELECT 1 + 1 AS x")).containsExactly(row(2));
    assertThat(rows("SELECT 2147483647::bigint + 1 AS big")).containsExactly(row(2147483648L));
    assertThat(rows("SELECT 7 / 2 AS q, -7 / 2 AS n, 7 % 2 AS r, 7.0 / 2 AS d")).containsExactly(row(3, -3, 1, dec("3.5")));
  }

  @Test
  void projectionAndAliases() {
    assertThat(rows("SELECT name AS who, age + 1 AS next_age FROM person ORDER BY pid")).containsExactly(
        row("Ada", 37), row("Bo", 42), row("Cy", null), row("Di", 30), row("Ed", 53));
    assertThat(rows("SELECT 'x' AS tag, pid FROM person ORDER BY pid LIMIT 2")).containsExactly(row("x", 1), row("x", 2));
    assertThat(rows("SELECT name || ' (' || city || ')' AS label FROM person ORDER BY pid LIMIT 1")).containsExactly(row("Ada (Delft)"));
    assertThat(rows("SELECT name || age AS name_age FROM person ORDER BY pid")).extracting(values -> values.get(0))
        .containsExactly("Ada36", "Bo41", null, "Di29", "Ed52");
  }

  @Test
  void aStarExpandsToTheColumnsOfTheTable() {
    try (final PostgreSQLResultSet resultSet = run("SELECT * FROM company ORDER BY cid")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::name).containsExactly("id", "cid", "name");
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName).containsExactly("text", "int4", "varchar");
    }
    final List<List<Object>> companies = rows("SELECT * FROM company ORDER BY cid");
    assertThat(companies).hasSize(3);
    assertThat(companies.get(0).subList(1, 3)).containsExactly(10, "Acme");
    assertThat((String) companies.get(0).get(0)).startsWith("#");
    assertThat(rows("SELECT c.* FROM company AS c ORDER BY c.cid")).isEqualTo(companies);
    assertThat(rows("SELECT c.name FROM company c ORDER BY c.cid")).containsExactly(row("Acme"), row("Birch"), row("Cedar"));
    assertThat(state("SELECT company.name FROM company c")).as("the alias hides the table name").isEqualTo("42P01");
    assertThat(state("SELECT *")).as("no tables").isEqualTo("42601");
  }

  @Test
  void theColumnsOfTheResultCarryPostgreSQLTypeNames() {
    try (final PostgreSQLResultSet resultSet = run("SELECT * FROM orders ORDER BY oid")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::name)
          .containsExactly("id", "address", "buyer", "oid", "pid", "placed", "tags", "total");
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName)
          .containsExactly("text", "jsonb", "text", "int4", "int4", "date", "_text", "numeric");
      final Result first = resultSet.next();
      assertThat(first.<Object>getProperty("placed")).isEqualTo(LocalDate.of(2026, 1, 5));
      assertThat((BigDecimal) first.<BigDecimal>getProperty("total")).isEqualByComparingTo("25.5");
      assertThat(first.<Object>getProperty("tags")).isEqualTo(List.of("gift", "express"));
      assertThat(first.<Object>getProperty("oid")).isEqualTo(100);
    }
    try (final PostgreSQLResultSet resultSet = run("SELECT name, age::text, upper(name), CASE WHEN true THEN 1 END, "
        + "coalesce(age, 0), age + 1, count(*) FROM person GROUP BY name, age")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::name)
          .containsExactly("name", "age", "upper", "case", "coalesce", "?column?", "count");
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName)
          .containsExactly("varchar", "text", "text", "int4", "int4", "int4", "int8");
    }
    try (final PostgreSQLResultSet resultSet = run("SELECT name, name FROM person LIMIT 1")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::name).containsExactly("name", "name");
      assertThat(resultSet.next().getPropertyNames()).containsExactly("name", "name_1");
    }
  }

  // ---- WHERE ----

  @Test
  void whereUsesThreeValuedLogic() {
    assertThat(column("SELECT name FROM person WHERE age > 30 ORDER BY pid")).containsExactly("Ada", "Bo", "Ed");
    assertThat(column("SELECT name FROM person WHERE age >= 36 AND age <= 41 ORDER BY pid")).containsExactly("Ada", "Bo");
    assertThat(column("SELECT name FROM person WHERE age IS NULL ORDER BY pid")).containsExactly("Cy");
    assertThat(column("SELECT name FROM person WHERE age IS NOT NULL ORDER BY pid")).containsExactly("Ada", "Bo", "Di", "Ed");
    assertThat(column("SELECT name FROM person WHERE NOT (age > 30) ORDER BY pid")).as("NULL is not true, and not false").containsExactly("Di");
    assertThat(column("SELECT name FROM person WHERE age = NULL")).isEmpty();
    assertThat(column("SELECT name FROM person WHERE age NOT IN (36, NULL)")).isEmpty();
    assertThat(column("SELECT name FROM person WHERE city IN ('Delft', 'Utrecht') ORDER BY pid")).containsExactly("Ada", "Bo", "Ed");
    assertThat(column("SELECT name FROM person WHERE city NOT IN ('Delft', 'Utrecht') ORDER BY pid")).containsExactly("Cy", "Di");
    assertThat(column("SELECT name FROM person WHERE age BETWEEN 29 AND 41 ORDER BY pid")).containsExactly("Ada", "Bo", "Di");
    assertThat(column("SELECT name FROM person WHERE age NOT BETWEEN 29 AND 41 ORDER BY pid")).containsExactly("Ed");
    assertThat(column("SELECT name FROM person WHERE name LIKE 'A%' ORDER BY pid")).containsExactly("Ada");
    assertThat(column("SELECT name FROM person WHERE name LIKE '_o' ORDER BY pid")).containsExactly("Bo");
    assertThat(column("SELECT name FROM person WHERE name NOT LIKE '%d%' ORDER BY pid")).containsExactly("Bo", "Cy", "Di");
    assertThat(column("SELECT name FROM person WHERE name < 'C' ORDER BY pid")).containsExactly("Ada", "Bo");
    assertThat(column("SELECT name FROM person WHERE city = 'Delft' OR city = 'Leiden' AND age > 30 ORDER BY pid"))
        .as("AND binds tighter than OR").containsExactly("Ada", "Bo");
  }

  @Test
  void textOrdersByCodePointInTheCCollation() {
    assertThat(rows("SELECT 'a' < 'B' AS a_before_b, 'a' = 'A' AS same_case, 'Z' < 'a' AS z_before_a"))
        .containsExactly(row(false, false, true));
  }

  @Test
  void dateColumns() {
    assertThat(column("SELECT oid FROM orders WHERE placed >= DATE '2026-02-01' ORDER BY oid")).containsExactly(102, 103, 104, 105);
    assertThat(column("SELECT oid FROM orders WHERE placed = '2026-03-09'")).containsExactly(104);
    assertThat(column("SELECT placed + 1 FROM orders WHERE oid = 100")).containsExactly(LocalDate.of(2026, 1, 6));
    assertThat(column("SELECT extract(month FROM placed) FROM orders ORDER BY oid")).containsExactly(dec("1"), dec("1"), dec("2"),
        dec("2"), dec("3"), dec("3"));
    assertThat(column("SELECT oid FROM orders ORDER BY placed DESC, oid LIMIT 2")).containsExactly(105, 104);
  }

  @Test
  void numericColumns() {
    assertThat(rows("SELECT oid, total * 2 AS doubled, total + 0.5 AS plus FROM orders WHERE oid <= 102 ORDER BY oid"))
        .containsExactly(row(100, dec("51"), dec("26")), row(101, dec("20"), dec("10.5")), row(102, dec("199.98"), dec("100.49")));
    assertThat(rows("SELECT oid, round(total) AS r FROM orders ORDER BY oid")).containsExactly(row(100, dec("26")), row(101, dec("10")),
        row(102, dec("100")), row(103, dec("5")), row(104, dec("7")), row(105, dec("1")));
    assertThat(column("SELECT oid FROM orders WHERE total > 9.99 ORDER BY oid")).containsExactly(100, 101, 102);
    assertThat(column("SELECT oid FROM orders WHERE pid IS NULL")).containsExactly(105);
    assertThat(column("SELECT abs(age - 40) FROM person ORDER BY pid")).containsExactly(4, 1, null, 11, 12);
  }

  @Test
  void typeErrorsAreRaisedWithPostgreSQLsCodes() {
    assertThat(state("SELECT name FROM person WHERE age")).isEqualTo("42804");
    assertThat(state("SELECT name FROM person WHERE age = 'x'")).isEqualTo("22P02");
    assertThat(state("SELECT name FROM person WHERE name = 1")).as("varchar = integer has no operator").isEqualTo("42883");
    assertThat(state("SELECT name FROM person WHERE age = true")).isEqualTo("42883");
    assertThat(state("SELECT 1 / 0")).isEqualTo("22012");
    assertThat(state("SELECT 2147483647 + 1")).isEqualTo("22003");
    assertThat(state("SELECT 'abc'::integer")).isEqualTo("22P02");
    assertThat(state("SELECT sum(name) FROM person")).isEqualTo("42883");
    assertThat(state("SELECT name FROM person WHERE count(*) > 1")).isEqualTo("42803");
    assertThat(state("SELECT name, count(*) FROM person GROUP BY city")).isEqualTo("42803");
    assertThat(state("SELECT name FROM person LIMIT -1")).isEqualTo("2201W");
    assertThat(state("SELECT name FROM person OFFSET -1")).isEqualTo("2201X");
    assertThat(state("SELECT name FROM person LIMIT age")).isEqualTo("42P10");
    assertThat(state("SELECT name FROM person LIMIT 'x'")).isEqualTo("22P02");
  }

  // ---- names ----

  @Test
  void bindingErrors() {
    assertThat(state("SELECT nosuch FROM person")).isEqualTo("42703");
    assertThat(state("SELECT p.nosuch FROM person p")).isEqualTo("42703");
    assertThat(state("SELECT * FROM nosuch")).isEqualTo("42P01");
    assertThat(state("SELECT x.name FROM person")).isEqualTo("42P01");
    assertThat(state("SELECT name FROM nosuch.person")).isEqualTo("3F000");
    assertThat(state("SELECT nosuch")).as("no table, so no column").isEqualTo("42703");
  }

  @Test
  void anUnknownNameIsReportedWithWhatCanBeNamed() {
    assertThatThrownBy(() -> rows("SELECT nam FROM person")).isInstanceOf(PostgreSQLCatalogException.class)
        .hasMessageContaining("column \"nam\" does not exist")
        .hasMessageContaining("Hint: Perhaps you meant to reference the column \"person.name\"; "
            + "the columns of person are id, age, city, name, pid")
        .hasMessageNotContaining("CREATE PROPERTY");
    assertThatThrownBy(() -> rows("SELECT p.nam FROM person p")).hasMessageContaining("column \"p.nam\" does not exist")
        .hasMessageContaining("Perhaps you meant to reference the column \"p.name\"; "
            + "the columns of p are id, age, city, name, pid");
    assertThatThrownBy(() -> rows("SELECT (SELECT count(*) FROM orders o WHERE nope = 1) FROM person p"))
        .hasMessageContaining("the columns of o are ").hasMessageNotContaining("the columns of p are ");
    assertThatThrownBy(() -> rows("SELECT pid FROM person UNION SELECT oid FROM orders ORDER BY nosuch"))
        .hasMessageContaining("Hint: the columns of the UNION result are pid");
    assertThatThrownBy(() -> rows("SELECT name FROM persons")).isInstanceOf(PostgreSQLCatalogException.class)
        .hasMessageContaining("relation \"persons\" does not exist");
  }

  @Test
  void identifiersFollowQuestionSevenOfTheDecisions() {
    assertThat(column("SELECT NAME FROM PERSON ORDER BY PID LIMIT 1")).as("unquoted names are folded").containsExactly("Ada");
    assertThat(column("SELECT \"name\" FROM \"person\" ORDER BY pid LIMIT 1")).containsExactly("Ada");
    assertThat(state("SELECT \"NAME\" FROM person")).as("a quoted name matches exactly").isEqualTo("42703");
    assertThat(column("SELECT name FROM public.person ORDER BY pid LIMIT 1")).containsExactly("Ada");
    assertThat(column("SELECT p.name FROM public.person p ORDER BY pid LIMIT 1")).containsExactly("Ada");
  }

  // ---- ORDER BY, LIMIT, OFFSET ----

  @Test
  void orderByPutsNullLastForAscending() {
    assertThat(rows("SELECT name, age FROM person ORDER BY age, pid")).containsExactly(row("Di", 29), row("Ada", 36), row("Bo", 41),
        row("Ed", 52), row("Cy", null));
    assertThat(column("SELECT name FROM person ORDER BY age DESC, pid")).as("NULL is the largest value, so first when descending")
        .containsExactly("Cy", "Ed", "Bo", "Ada", "Di");
    assertThat(column("SELECT name FROM person ORDER BY age NULLS FIRST, pid")).containsExactly("Cy", "Di", "Ada", "Bo", "Ed");
    assertThat(column("SELECT name FROM person ORDER BY age DESC NULLS LAST, pid")).containsExactly("Ed", "Bo", "Ada", "Di", "Cy");
    assertThat(column("SELECT name FROM person ORDER BY age ASC NULLS LAST, pid")).containsExactly("Di", "Ada", "Bo", "Ed", "Cy");
  }

  @Test
  void orderByPositionAliasAndExpression() {
    assertThat(column("SELECT name, age FROM person ORDER BY 2 DESC NULLS LAST, 1")).containsExactly("Ed", "Bo", "Ada", "Di", "Cy");
    assertThat(column("SELECT name, age + 1 AS next FROM person ORDER BY next DESC NULLS LAST, name")).containsExactly("Ed", "Bo", "Ada",
        "Di", "Cy");
    assertThat(column("SELECT name FROM person ORDER BY length(name), name")).as("a key that is not in the select list")
        .containsExactly("Bo", "Cy", "Di", "Ed", "Ada");
    assertThat(rows("SELECT name, city FROM person ORDER BY city DESC, name")).containsExactly(row("Ed", "Utrecht"),
        row("Cy", "Leiden"), row("Di", "Leiden"), row("Ada", "Delft"), row("Bo", "Delft"));
    assertThat(state("SELECT name FROM person ORDER BY 2")).isEqualTo("42P10");
    assertThat(state("SELECT name, name FROM person ORDER BY name")).as("two output columns called name").isEqualTo("42702");
  }

  @Test
  void limitAndOffset() {
    assertThat(column("SELECT name FROM person ORDER BY pid LIMIT 2")).containsExactly("Ada", "Bo");
    assertThat(column("SELECT name FROM person ORDER BY pid LIMIT 2 OFFSET 1")).containsExactly("Bo", "Cy");
    assertThat(column("SELECT name FROM person ORDER BY pid OFFSET 3")).containsExactly("Di", "Ed");
    assertThat(column("SELECT name FROM person ORDER BY pid LIMIT 0")).isEmpty();
    assertThat(column("SELECT name FROM person ORDER BY pid LIMIT NULL")).hasSize(5);
    assertThat(column("SELECT name FROM person ORDER BY pid OFFSET NULL LIMIT 1")).containsExactly("Ada");
    assertThat(column("SELECT name FROM person ORDER BY pid OFFSET 10")).isEmpty();
    assertThat(column("SELECT name FROM person ORDER BY pid LIMIT 100")).hasSize(5);
    assertThat(column("SELECT name FROM person ORDER BY pid LIMIT $1 OFFSET $2", 2, 1)).containsExactly("Bo", "Cy");
    assertThat(column("SELECT name FROM person LIMIT 3")).as("no ORDER BY: the first rows are enough").hasSize(3);
    assertThat(column("SELECT name FROM person LIMIT 2 + 1")).hasSize(3);
  }

  @Test
  void distinct() {
    assertThat(column("SELECT DISTINCT city FROM person ORDER BY city")).containsExactly("Delft", "Leiden", "Utrecht");
    assertThat(column("SELECT DISTINCT city FROM person")).hasSize(3).containsExactlyInAnyOrder("Delft", "Leiden", "Utrecht");
    assertThat(column("SELECT DISTINCT city FROM person ORDER BY city DESC LIMIT 1")).containsExactly("Utrecht");
    assertThat(state("SELECT DISTINCT city FROM person ORDER BY age")).isEqualTo("42P10");
  }

  // ---- parameters ----

  @Test
  void parameters() {
    assertThat(column("SELECT name FROM person WHERE city = $1 ORDER BY pid", "Delft")).containsExactly("Ada", "Bo");
    assertThat(column("SELECT name FROM person WHERE pid = $1", 3)).containsExactly("Cy");
    assertThat(column("SELECT name FROM person WHERE pid = $1", 3L)).as("a bigint against an integer column").containsExactly("Cy");
    assertThat(column("SELECT name FROM person WHERE age > $1 AND city = $2 ORDER BY pid", 30, "Delft")).containsExactly("Ada", "Bo");
    assertThat(column("SELECT $1 + $2", 40, 2)).containsExactly(42);
    assertThat(column("SELECT $1", "x")).containsExactly("x");
    assertThat(column("SELECT name FROM person WHERE age = $1", new Object[] { null })).isEmpty();
    assertThat(state("SELECT name FROM person WHERE pid = $2", 1)).isEqualTo("42P02");
    assertThat(state("SELECT $1")).isEqualTo("42P02");
    try (final ResultSet resultSet = database.query("postgresql", "SELECT name FROM person WHERE pid = $1", Map.of("1", 2))) {
      assertThat(resultSet.next().<String>getProperty("name")).isEqualTo("Bo");
    }
    assertThat(state("SELECT name FROM person WHERE pid = $1", "3")).as("a text value against an integer column").isEqualTo("42883");
  }

  // ---- access paths ----

  @Test
  void anEqualityOnAnIndexedPropertyReadsThroughTheIndex() {
    assertThat(plan("SELECT name FROM person WHERE pid = 3")).contains("FETCH FROM INDEX person[pid]");
    assertThat(plan("SELECT name FROM person WHERE 3 = pid")).contains("FETCH FROM INDEX person[pid]");
    assertThat(plan("SELECT name FROM person WHERE pid = $1", 3)).contains("FETCH FROM INDEX person[pid]");
    assertThat(plan("SELECT name FROM person WHERE pid = 3 AND age > 30")).contains("FETCH FROM INDEX person[pid]").contains("FILTER WHERE");
    assertThat(plan("SELECT cid FROM company WHERE cid = 20")).contains("FETCH FROM INDEX company[cid]");
    assertThat(plan("SELECT oid FROM orders WHERE oid = 102")).contains("FETCH FROM INDEX orders[oid]");
    assertThat(column("SELECT name FROM person WHERE pid = 3")).containsExactly("Cy");
    assertThat(column("SELECT oid FROM orders WHERE oid = 102")).containsExactly(102);
    assertThat(column("SELECT name FROM person WHERE pid = 3 AND age > 30")).as("the filter still applies").isEmpty();
    assertThat(column("SELECT name FROM person WHERE pid = 99")).isEmpty();
    assertThat(column("SELECT name FROM person WHERE pid = 3000000000")).as("a key that no integer can have").isEmpty();
    assertThat(column("SELECT name FROM person WHERE pid = NULL")).isEmpty();
  }

  @Test
  void aPropertyWithoutAnIndexIsScanned() {
    assertThat(plan("SELECT name FROM person WHERE age = 36")).contains("SCAN TYPE person (polymorphic)").doesNotContain("INDEX");
    assertThat(plan("SELECT name FROM person WHERE name = 'Ada'")).contains("SCAN TYPE person").doesNotContain("INDEX");
    assertThat(plan("SELECT oid FROM orders WHERE pid = 1")).as("orders.pid has no index").contains("SCAN TYPE orders").doesNotContain("INDEX");
    assertThat(plan("SELECT name FROM person WHERE pid > 3")).as("only equality uses the index").contains("SCAN TYPE person");
    assertThat(plan("SELECT name FROM person WHERE pid = 3 OR pid = 4")).as("not a conjunction").contains("SCAN TYPE person");
    assertThat(plan("SELECT name FROM person WHERE pid = age")).contains("SCAN TYPE person");
    assertThat(column("SELECT name FROM person WHERE pid = 3 OR pid = 4 ORDER BY pid")).containsExactly("Cy", "Di");
  }

  @Test
  void theRecordIdIsAReadOfOneRecord() {
    final List<Object> ids = column("SELECT id FROM person ORDER BY pid");
    final String bo = (String) ids.get(1);
    assertThat(plan("SELECT pid FROM person WHERE id = $1", bo)).contains("READ RECORD BY ID person");
    assertThat(column("SELECT pid FROM person WHERE id = $1", bo)).containsExactly(2);
    assertThat(column("SELECT pid FROM person WHERE id = '" + bo + "'")).containsExactly(2);
    assertThat(column("SELECT pid FROM person WHERE id = '#999:0'")).isEmpty();
    assertThat(column("SELECT pid FROM person WHERE id = 'junk'")).isEmpty();
    assertThat(column("SELECT pid FROM person WHERE id = 'junk' OR pid = 1")).containsExactly(1);
    final String company = (String) column("SELECT id FROM company ORDER BY cid").get(0);
    assertThat(column("SELECT pid FROM person WHERE id = $1", company)).as("a record of another table is not a row").isEmpty();
    assertThat(column("SELECT cid FROM company WHERE id = $1", company)).containsExactly(10);
  }

  // ---- ONLY and inheritance ----

  @Test
  void onlyExcludesTheSubtypes() {
    final Schema schema = database.getSchema();
    schema.createVertexType("animal").createProperty("name", Type.STRING);
    schema.buildVertexType().withName("dog").withSuperType("animal").create().createProperty("breed", Type.STRING);
    database.transaction(() -> {
      database.newVertex("animal").set("name", "a").save();
      database.newVertex("dog").set("name", "d").set("breed", "x").save();
    });

    assertThat(column("SELECT name FROM animal ORDER BY name")).as("a supertype table holds its subtypes").containsExactly("a", "d");
    assertThat(column("SELECT name FROM ONLY animal ORDER BY name")).containsExactly("a");
    assertThat(rows("SELECT name, breed FROM dog")).containsExactly(row("d", "x"));
    assertThat(column("SELECT name FROM ONLY dog")).containsExactly("d");
    assertThat(state("SELECT breed FROM animal")).as("only the declared columns of the supertype").isEqualTo("42703");
    assertThat(plan("SELECT name FROM animal")).contains("SCAN TYPE animal (polymorphic)");
    assertThat(plan("SELECT name FROM ONLY animal")).contains("SCAN TYPE animal ONLY");
  }

  // ---- edges ----

  @Test
  void anEdgeTableIsReadFromItsBuckets() {
    final List<Object> people = column("SELECT id FROM person ORDER BY pid");
    final List<Object> companies = column("SELECT id FROM company ORDER BY cid");
    assertThat(rows("SELECT source_id, target_id, since FROM works_at ORDER BY since")).containsExactly(
        row(people.get(0), companies.get(0), 2019), row(people.get(1), companies.get(0), 2020),
        row(people.get(2), companies.get(1), 2021), row(people.get(0), companies.get(0), 2022),
        row(people.get(3), companies.get(1), 2023));
    assertThat(plan("SELECT since FROM works_at")).contains("SCAN TYPE works_at");
    assertThat(column("SELECT id FROM works_at")).hasSize(5);
  }

  @Test
  void aLightweightEdgeTableIsReadByWalkingTheVertices() {
    final Schema schema = database.getSchema();
    schema.createVertexType("lp");
    schema.createVertexType("lc");
    final EdgeType knows = schema.buildEdgeType().withName("lknows").withLightweight(true).create();
    knows.setCustomValue(EdgeEndpoints.SOURCE_KEY, "lp");
    knows.setCustomValue(EdgeEndpoints.TARGET_KEY, "lc");
    final String[] ends = new String[2];
    database.transaction(() -> {
      final MutableVertex a = database.newVertex("lp").save();
      final MutableVertex b = database.newVertex("lc").save();
      ends[0] = a.getIdentity().toString();
      ends[1] = b.getIdentity().toString();
      a.newEdge("lknows", b);
    });
    assertThat(rows("SELECT source_id, target_id FROM lknows")).containsExactly(row(ends[0], ends[1]));
    assertThat(plan("SELECT source_id FROM lknows")).contains("WALK EDGES lknows FROM lp");
    assertThat(state("SELECT id FROM lknows")).as("a lightweight edge has no id").isEqualTo("42703");
  }

  // ---- grouping ----

  @Test
  void aggregatesOverAllRows() {
    assertThat(rows("SELECT count(*) AS n, count(age) AS n_age, sum(age) AS s, avg(age) AS a, min(age) AS lo, max(age) AS hi FROM person"))
        .containsExactly(row(5L, 4L, 158L, dec("39.5"), 29, 52));
    try (final PostgreSQLResultSet resultSet = run("SELECT count(*), sum(age), avg(age), min(age), max(name), sum(pid) FROM person")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName)
          .containsExactly("int8", "int8", "numeric", "int4", "text", "int8");
    }
    assertThat(rows("SELECT count(*) AS n, sum(age) AS s, avg(age) AS a, min(age) AS lo FROM person WHERE age > 100"))
        .containsExactly(row(0L, null, null, null));
    assertThat(rows("SELECT sum(total), avg(total), min(placed), max(placed) FROM orders")).containsExactly(
        row(dec("148.74"), dec("24.79"), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 3, 10)));
    assertThat(((BigDecimal) rows("SELECT avg(pid) FROM person").get(0).get(0)).scale()).as("stripped").isLessThanOrEqualTo(0);
    assertThat(rows("SELECT count(DISTINCT city) AS n FROM person")).containsExactly(row(3L));
    assertThat(rows("SELECT min(name), max(name) FROM person")).containsExactly(row("Ada", "Ed"));
  }

  @Test
  void groupBy() {
    assertThat(rows("SELECT city, count(*) AS n FROM person GROUP BY city ORDER BY city")).containsExactly(row("Delft", 2L),
        row("Leiden", 2L), row("Utrecht", 1L));
    assertThat(rows("SELECT city, avg(age) AS a, count(age) AS c FROM person GROUP BY city ORDER BY city")).containsExactly(
        row("Delft", dec("38.5"), 2L), row("Leiden", dec("29"), 1L), row("Utrecht", dec("52"), 1L));
    assertThat(rows("SELECT city, count(*) AS n FROM person GROUP BY city HAVING count(*) > 1 ORDER BY city")).containsExactly(
        row("Delft", 2L), row("Leiden", 2L));
    assertThat(rows("SELECT age IS NULL AS unknown_age, count(*) AS n FROM person GROUP BY age IS NULL ORDER BY unknown_age"))
        .containsExactly(row(false, 4L), row(true, 1L));
    assertThat(rows("SELECT pid, count(*) AS n, sum(total) AS s FROM orders GROUP BY pid ORDER BY pid")).containsExactly(
        row(1, 2L, dec("35.5")), row(2, 1L, dec("99.99")), row(4, 2L, dec("12.25")), row(null, 1L, dec("1")));
    assertThat(rows("SELECT city, count(*) AS n FROM person GROUP BY city ORDER BY count(*) DESC, city")).containsExactly(
        row("Delft", 2L), row("Leiden", 2L), row("Utrecht", 1L));
    assertThat(rows("SELECT city, count(*) FROM person GROUP BY 1 ORDER BY 1 LIMIT 1")).containsExactly(row("Delft", 2L));
    assertThat(rows("SELECT city FROM person GROUP BY city ORDER BY city")).hasSize(3);
    assertThat(rows("SELECT city, count(*) FROM person WHERE age > 100 GROUP BY city")).as("no rows, no group").isEmpty();
    assertThat(rows("SELECT count(*) FROM person HAVING count(*) > 100")).isEmpty();
  }

  /** The rows of the answer of a statement planned with this memory cap. */
  private int answered(final String sql, final long cap) {
    int rows = 0;
    try (final ResultSet resultSet = new SelectPlanner(database, Parameters.NONE, cap).planQuery(StatementParser.parse(sql))
        .execute(database)) {
      while (resultSet.hasNext()) {
        resultSet.next();
        ++rows;
      }
    }
    return rows;
  }

  @Test
  void aGroupByOverTheCapStopsWithOutOfMemoryAndNeverAnswersFromPartOfItsInput() {
    final String byCity = "SELECT city, count(*) FROM person GROUP BY city";
    assertThatThrownBy(() -> answered(byCity, 100L)).isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> {
      assertThat(e.getSqlState()).isEqualTo("53200");
      assertThat(e.getMessage()).contains("out of memory").contains("a GROUP BY holds more than 100 bytes").contains("Hint:")
          .contains(BuildMemory.CAP_PROPERTY);
    });
    assertThat(answered(byCity, BuildMemory.FLOOR_CAP_BYTES)).as("the default cap holds the small fixture").isEqualTo(3);
  }

  @Test
  void theCapOfAGroupByCountsGroupsAndNotInputRows() {
    // five persons make two groups by "age IS NULL" and five by pid: the cap holds the first and not the second
    assertThat(answered("SELECT age IS NULL, count(*) FROM person GROUP BY age IS NULL", 1000L)).isEqualTo(2);
    assertThatThrownBy(() -> answered("SELECT pid, count(*) FROM person GROUP BY pid", 1000L)).isInstanceOfSatisfying(
        PostgreSQLEvaluationException.class, e -> assertThat(e.getSqlState()).isEqualTo("53200"));
    // without a GROUP BY there is one group whatever the input, so the cap does not apply
    assertThat(answered("SELECT count(*), sum(age) FROM person", 1L)).isEqualTo(1);
  }

  // ---- EXPLAIN ----

  @Test
  void explainAnswersThePlanAndReadsNothing() {
    try (final PostgreSQLResultSet resultSet = run("EXPLAIN SELECT name FROM person WHERE pid = 3 ORDER BY name LIMIT 1")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::name).containsExactly("QUERY PLAN");
      final List<String> lines = new ArrayList<>();
      while (resultSet.hasNext())
        lines.add(resultSet.next().getProperty("QUERY PLAN"));
      assertThat(lines).containsExactly("FETCH FROM INDEX person[pid] (pid = key)", "FILTER WHERE", "PROJECT 1 column(s)", "SORT 1 key(s)",
          "LIMIT");
    }
  }
}
