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
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code VALUES} as a query (fork issue #77): {@code FROM (VALUES (...), (...)) AS t(a, b)}, {@code IN (VALUES ...)}, a {@code WITH}
 * query and a statement of its own. The columns are typed as {@code UNION} types them and named {@code column1}, {@code column2}.
 * The answers are compared with PostgreSQL 18 by the answer corpus.
 */
class ValuesQueryTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private List<List<String>> rows(final String sql) {
    final List<List<String>> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        final List<String> cells = new ArrayList<>();
        for (final String name : row.getPropertyNames())
          cells.add(String.valueOf((Object) row.getProperty(name)));
        rows.add(cells);
      }
    }
    return rows;
  }

  private List<String> names(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      return new ArrayList<>(resultSet.next().getPropertyNames());
    }
  }

  private static List<String> row(final String... cells) {
    return List.of(cells);
  }

  private void assertState(final String sql, final String sqlState) {
    assertThatThrownBy(() -> rows(sql)).as(sql).isInstanceOfSatisfying(RuntimeException.class, e -> {
      try {
        assertThat(e.getClass().getMethod("getSqlState").invoke(e)).as(String.valueOf(e.getMessage())).isEqualTo(sqlState);
      } catch (final ReflectiveOperationException noState) {
        throw new AssertionError("no SQLSTATE on " + e, noState);
      }
    });
  }

  @Test
  void aConstantTableInFromWithColumnAliases() {
    assertThat(rows("SELECT a, b FROM (VALUES (1, 'x'), (2, 'y')) AS t(a, b) ORDER BY a")).containsExactly(row("1", "x"), row("2", "y"));
    assertThat(names("SELECT * FROM (VALUES (1, 'x')) AS t(a, b)")).containsExactly("a", "b");
  }

  @Test
  void theColumnsAreNamedColumnNWithoutAList() {
    assertThat(names("SELECT * FROM (VALUES (1, 'x')) AS t")).containsExactly("column1", "column2");
    assertThat(names("VALUES (1, 'x')")).containsExactly("column1", "column2");
    assertThat(rows("SELECT t.column2 FROM (VALUES (1, 'x'), (2, 'y')) t ORDER BY t.column1 DESC")).containsExactly(row("y"), row("x"));
  }

  @Test
  void theColumnsAreTypedLikeAUnion() {
    assertThat(rows("SELECT a::text FROM (VALUES (1), (2.5)) v(a) ORDER BY a")).containsExactly(row("1"), row("2.5"));
    // an unknown literal is text unless the other rows say otherwise, so it joins with a text column and not with a number
    assertThat(rows("SELECT p.name FROM person p JOIN (VALUES ('Ada'), ('Ed')) v(n) ON v.n = p.name ORDER BY p.pid"))
        .containsExactly(row("Ada"), row("Ed"));
    assertThat(rows("SELECT p.name FROM person p JOIN (VALUES (1), (3)) v(k) ON v.k = p.pid ORDER BY p.pid"))
        .containsExactly(row("Ada"), row("Cy"));
    assertState("SELECT p.name FROM person p JOIN (VALUES ('1'), ('3')) v(k) ON v.k = p.pid", "42883");
    assertState("SELECT * FROM (VALUES (1), ('x')) v(a)", "22P02");
    assertThat(rows("SELECT a FROM (VALUES (NULL), (NULL)) v(a)")).containsExactly(row("null"), row("null"));
  }

  @Test
  void valuesInOtherPlaces() {
    assertThat(rows("SELECT name FROM person WHERE pid IN (VALUES (1), (2)) ORDER BY pid")).containsExactly(row("Ada"), row("Bo"));
    assertThat(rows("WITH v(a) AS (VALUES (1), (2), (3)) SELECT sum(a) FROM v")).containsExactly(row("6"));
    assertThat(rows("VALUES (2), (1)")).containsExactly(row("2"), row("1"));
    assertThat(rows("VALUES (2), (1), (3) ORDER BY 1 DESC LIMIT 2")).containsExactly(row("3"), row("2"));
    assertThat(rows("VALUES (1) UNION ALL VALUES (1)")).containsExactly(row("1"), row("1"));
    assertThat(rows("SELECT * FROM (VALUES (1 + 1, upper('a'))) v(a, b)")).containsExactly(row("2", "A"));
  }

  @Test
  void insertSelectFromAValuesList() {
    database.transaction(() -> {
      try (final ResultSet ignored = database.command("postgresql",
          "INSERT INTO company (cid, name) SELECT c, n FROM (VALUES (70, 'Zed'), (80, 'Yan')) v(c, n)")) {
        // the count row is not needed
      }
    });
    assertThat(rows("SELECT cid, name FROM company WHERE cid > 60 ORDER BY cid")).containsExactly(row("70", "Zed"), row("80", "Yan"));
  }

  @Test
  void whatIsWrongOrOutsideTheLanguageIsRefused() {
    assertState("SELECT * FROM (VALUES (1, 2), (3)) v(a, b)", "42601");
    assertState("SELECT * FROM (VALUES (DEFAULT)) v(a)", "42601");
    assertState("SELECT * FROM (TABLE person) v", "0A000");
    assertState("SELECT * FROM person p, LATERAL (VALUES (p.pid)) v(a)", "0A000");
    final StringBuilder big = new StringBuilder("SELECT count(*) FROM (VALUES (0)");
    for (int i = 1; i <= 500; i++)
      big.append(", (").append(i).append(')');
    assertState(big.append(") v(a)").toString(), "0A000");
  }

  @Test
  void aValuesQueryIsPrintedAsTheUnionThatStandsForIt() {
    assertThat(AstPrinter.print(StatementParser.parse("VALUES (1, 'a'), (2, 'b')")))
        .isEqualTo(AstPrinter.print(StatementParser.parse("SELECT 1 AS \"column1\", 'a' AS \"column2\" UNION ALL SELECT 2, 'b'")));
  }
}
