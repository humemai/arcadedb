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
package com.arcadedb.e2e.pgparity;

import com.arcadedb.database.Database;
import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultComparator;
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two acceptance items of fork issue #5 that a statement of {@code answers.tsv} cannot express: a statement with
 * {@code $n} parameters, and a table whose name is not all lower case. Each runs on a real PostgreSQL 18 and on the
 * {@code postgresql} language of ArcadeDB, and the answers are compared as in {@link PgParityIT}.
 * <p>
 * The server runs the byte-identical statement text as a prepared statement ({@code PREPARE ... AS <statement>}, then
 * {@code EXECUTE} with the values as literals); the engine gets the same text and the values as the parameters of
 * {@code database.query}.
 */
class PgParameterParityIT {
  private static final String LANGUAGE = "postgresql";

  private static PostgresServer pg;
  private static ArcadeSide     small;
  private static ArcadeSide     names;

  /** One statement, the values for {@code $1}, {@code $2} and so on, and the same values as SQL literals for the server. */
  private record Parameterised(String statement, List<Object> values, String literals, boolean ordered) {
  }

  @BeforeAll
  static void start() throws Exception {
    pg = PostgresServer.start();
    assertEquals("C", pg.collation(), "the server must run with the C collation");
    pg.load("CREATE TABLE person (pid int, name text, age int, city text);"
        + "INSERT INTO person VALUES (1,'Ada',36,'Delft'),(2,'Bo',41,'Delft'),(3,'Cy',NULL,'Leiden'),"
        + "(4,'Di',29,'Leiden'),(5,'Ed',52,'Utrecht')");
    small = new ArcadeSide();

    // two tables whose names differ only in case, and one that has no lower case twin
    pg.load("CREATE TABLE \"MixedCase\" (n int); INSERT INTO \"MixedCase\" VALUES (1),(2);"
        + "CREATE TABLE \"Twin\" (n int); INSERT INTO \"Twin\" VALUES (10);"
        + "CREATE TABLE twin (n int); INSERT INTO twin VALUES (20)");
    names = new ArcadeSide("names");
    final Database database = names.database();
    for (final String type : List.of("MixedCase", "Twin", "twin"))
      database.getSchema().createDocumentType(type).createProperty("n", Type.INTEGER);
    database.transaction(() -> {
      database.newDocument("MixedCase").set("n", 1).save();
      database.newDocument("MixedCase").set("n", 2).save();
      database.newDocument("Twin").set("n", 10).save();
      database.newDocument("twin").set("n", 20).save();
    });
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      if (small != null)
        small.close();
      if (names != null)
        names.close();
    } finally {
      if (pg != null)
        pg.close();
    }
  }

  @Test
  void theServerAndTheEngineHoldTheSamePersons() {
    assertSame(small, "SELECT pid, name, age, city FROM person ORDER BY pid", true);
  }

  @Test
  void statementsWithParametersAnswerLikeTheServer() throws Exception {
    final List<Parameterised> cases = List.of(
        new Parameterised("SELECT name FROM person WHERE pid = $1", List.of(4), "4", true),
        new Parameterised("SELECT name FROM person WHERE city = $1 AND age > $2 ORDER BY name", List.of("Delft", 36),
            "'Delft', 36", true),
        new Parameterised("SELECT name FROM person WHERE pid IN ($1, $2) ORDER BY name", List.of(1, 5), "1, 5", true),
        new Parameterised("SELECT pid + $1 FROM person WHERE name = $2", List.of(100, "Bo"), "100, 'Bo'", true),
        new Parameterised("SELECT name FROM person ORDER BY pid LIMIT $1 OFFSET $2", List.of(2, 1), "2, 1", true),
        new Parameterised("SELECT name FROM person WHERE age = $1 OR pid = $1 ORDER BY name", List.of(1), "1", true));
    final List<String> failures = new ArrayList<>();
    for (final Parameterised c : cases) {
      pg.load("PREPARE parity AS " + c.statement());
      final Outcome server = pg.run("EXECUTE parity(" + c.literals() + ")");
      pg.load("DEALLOCATE parity");
      final Outcome engine = small.run(LANGUAGE, c.statement(), c.values().toArray());
      assertTrue(!server.isRefusal(), c.statement() + ": the server refused: " + server.describe());
      assertTrue(!server.table.rows.isEmpty(), c.statement() + ": no row, so the comparison would show nothing");
      final String difference = engine.isRefusal() ? "engine " + engine.describe()
          : ResultComparator.compare(Outcome.answer(server.table.valuesOnly()), Outcome.answer(engine.table.valuesOnly()),
              c.ordered(), Values.DEFAULT_TOLERANCE);
      System.out.println("pgparity parameters " + c.statement() + " " + (difference == null ? "equal" : "different"));
      if (difference != null)
        failures.add(c.statement() + ": " + difference);
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  /** Question 7 of fork issue #1, where it agrees with PostgreSQL: a quoted name is exact, and a lower case twin wins. */
  @Test
  void namesThatAreNotLowerCaseResolveLikeTheServerWhenPostgreSQLFindsThem() {
    assertSame(names, "SELECT n FROM \"MixedCase\" ORDER BY n", true);
    assertSame(names, "SELECT n FROM \"Twin\"", false);
    assertSame(names, "SELECT n FROM Twin", false);
    assertSame(names, "SELECT n FROM TWIN", false);
  }

  /**
   * The one difference that question 7 of fork issue #1 decides: an unquoted name that PostgreSQL does not find, because the
   * table was created with a quoted mixed case name, is accepted when exactly one table differs from it only in case
   * (choice 6 of {@code docs/postgresql-differences.md}).
   */
  @Test
  void anUnquotedNameWithOneCaseInsensitiveMatchIsFoundWhereTheServerRefuses() {
    for (final String sql : List.of("SELECT n FROM MixedCase ORDER BY n", "SELECT n FROM mixedcase ORDER BY n")) {
      final Outcome server = pg.run(sql);
      assertTrue(server.isRefusal(), sql + ": the server answered");
      assertEquals("42P01", server.sqlState, sql);
      final Outcome engine = names.run(LANGUAGE, sql);
      assertTrue(!engine.isRefusal(), sql + ": " + (engine.isRefusal() ? engine.describe() : ""));
      assertEquals(2, engine.table.rows.size(), sql);
    }
  }

  private static void assertSame(final ArcadeSide side, final String sql, final boolean ordered) {
    final Outcome server = pg.run(sql);
    final Outcome engine = side.run(LANGUAGE, sql);
    assertTrue(!server.isRefusal(), sql + ": the server refused: " + server.describe());
    assertTrue(!engine.isRefusal(), sql + ": the engine refused: " + (engine.isRefusal() ? engine.describe() : ""));
    final String difference = ResultComparator.compare(Outcome.answer(server.table.valuesOnly()),
        Outcome.answer(engine.table.valuesOnly()), ordered, Values.DEFAULT_TOLERANCE);
    System.out.println("pgparity names " + sql + " " + (difference == null ? "equal" : "different"));
    assertTrue(difference == null, sql + ": " + difference);
  }
}
