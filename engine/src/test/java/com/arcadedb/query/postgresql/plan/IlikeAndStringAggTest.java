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
 * {@code ILIKE}, {@code NOT ILIKE} and {@code string_agg} on the {@code small} fixture (fork issue #79): the first constructs a
 * memory agent writes, a name lookup and a list of names. The answers are compared with PostgreSQL 18 by the answer corpus; the
 * SQLSTATE of what is refused is asserted here.
 */
class IlikeAndStringAggTest extends TestHelper {
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

  // ---- ILIKE ----

  @Test
  void ilikeIgnoresTheCaseOfBothSides() {
    assertThat(rows("SELECT name FROM person WHERE name ILIKE 'a%' ORDER BY pid")).containsExactly(row("Ada"));
    assertThat(rows("SELECT name FROM person WHERE name ILIKE '%D%' ORDER BY pid")).containsExactly(row("Ada"), row("Di"), row("Ed"));
    assertThat(rows("SELECT name FROM person WHERE 'ADA' ILIKE name ORDER BY pid")).containsExactly(row("Ada"));
    assertThat(rows("SELECT name FROM person WHERE name LIKE '%d%' ORDER BY pid")).containsExactly(row("Ada"), row("Ed"))
        .as("LIKE keeps the case");
    assertThat(rows("SELECT name FROM person WHERE name LIKE 'a%' ORDER BY pid")).isEmpty();
  }

  @Test
  void notIlikeNegatesAndKeepsNullOut() {
    assertThat(rows("SELECT name FROM person WHERE name NOT ILIKE '%D%' ORDER BY pid")).containsExactly(row("Bo"), row("Cy"));
    assertThat(rows("SELECT NULL::text ILIKE 'a', 'a' ILIKE NULL, NULL::text NOT ILIKE 'a'")).containsExactly(row("null", "null", "null"));
    assertThat(rows("SELECT name FROM person WHERE city NOT ILIKE 'DELFT' AND age IS NULL")).containsExactly(row("Cy"));
  }

  @Test
  void ilikeTakesTheEscapeClauseOfLike() {
    assertThat(rows("SELECT 'a%b' ILIKE 'A!%B' ESCAPE '!', 'axb' ILIKE 'A!%B' ESCAPE '!', 'AXB' NOT ILIKE 'a!%b' ESCAPE '!'"))
        .containsExactly(row("true", "false", "true"));
    assertThat(rows("SELECT 'a_b' ILIKE 'A\\_B', 'axb' ILIKE 'A\\_B', 'A\\B' ILIKE 'a\\b' ESCAPE ''"))
        .containsExactly(row("true", "false", "true"));
    assertThat(rows("SELECT 'a' ILIKE 'a\\'")).containsExactly(row("false"));
    assertState("SELECT 'ab' ILIKE 'a\\'", "22025");
    assertState("SELECT 'a' ILIKE 'a' ESCAPE 'ab'", "22025");
  }

  @Test
  void ilikeOnANonTextOperandIsRefusedAsPostgreSqlDoes() {
    assertState("SELECT pid FROM person WHERE pid ILIKE '1%'", "42883");
    assertState("SELECT pid FROM person WHERE pid NOT ILIKE '1%'", "42883");
    assertState("SELECT name FROM person WHERE name SIMILAR TO 'A%'", "0A000");
  }

  @Test
  void ilikeIsParsedAsAnOperatorOfItsOwn() {
    assertThat(AstPrinter.print(StatementParser.parse("SELECT a FROM t WHERE a ILIKE 'x' AND b NOT ILIKE 'y' ESCAPE '!'")))
        .contains("((a ILIKE 'x') AND (b NOT ILIKE 'y' ESCAPE '!'))");
  }

  /** The pattern before ESCAPE is a b_expr: an AND before the operator is not part of the pattern (it was, for LIKE, before #79). */
  @Test
  void aConjunctionBeforeALikeWithEscapeBindsLooserThanTheLike() {
    assertThat(AstPrinter.print(StatementParser.parse("SELECT a FROM t WHERE a LIKE 'x' AND b LIKE 'y' ESCAPE '!' OR c LIKE 'z'")))
        .contains("(((a LIKE 'x') AND (b LIKE 'y' ESCAPE '!')) OR (c LIKE 'z'))");
    assertThat(rows("SELECT name FROM person WHERE name LIKE 'A%' AND city LIKE 'Del!ft' ESCAPE '!' OR name ILIKE 'ED' ORDER BY pid"))
        .containsExactly(row("Ada"), row("Ed"));
    assertThat(rows("SELECT name FROM person WHERE name ILIKE 'a' || '%' ESCAPE '!' ORDER BY pid")).containsExactly(row("Ada"));
  }

  // ---- string_agg ----

  @Test
  void stringAggJoinsTheValuesInInputOrderWithAnUnknownDelimiter() {
    assertThat(rows("SELECT string_agg(name, ', ') FROM person")).containsExactly(row("Ada, Bo, Cy, Di, Ed"));
    assertThat(rows("SELECT city, string_agg(name, '+') FROM person GROUP BY city ORDER BY city"))
        .containsExactly(row("Delft", "Ada+Bo"), row("Leiden", "Cy+Di"), row("Utrecht", "Ed"));
    assertThat(rows("SELECT string_agg(name || ':' || city, ' | ') FROM person WHERE pid < 3")).containsExactly(row("Ada:Delft | Bo:Delft"));
  }

  @Test
  void stringAggSkipsNullsAndAnEmptyInputIsNull() {
    assertThat(rows("SELECT string_agg(CASE WHEN age IS NULL THEN NULL ELSE name END, ',') FROM person"))
        .containsExactly(row("Ada,Bo,Di,Ed"));
    assertThat(rows("SELECT string_agg(name, ',') FROM person WHERE pid > 99")).containsExactly(row("null"));
    assertThat(rows("SELECT string_agg(name, NULL) FROM person")).containsExactly(row("AdaBoCyDiEd"));
    assertThat(rows("SELECT string_agg(NULL, ',') FROM person")).containsExactly(row("null"));
  }

  @Test
  void stringAggDistinctSortsLikePostgreSql() {
    // PostgreSQL implements DISTINCT in an aggregate by sorting, so the pieces are in value order, not in input order
    assertThat(rows("SELECT string_agg(DISTINCT CASE WHEN pid < 3 THEN 'z' ELSE 'a' END, ',') FROM person"))
        .containsExactly(row("a,z"));
    assertThat(rows("SELECT string_agg(DISTINCT city, '/') FROM person")).containsExactly(row("Delft/Leiden/Utrecht"));
    assertThat(rows("SELECT count(DISTINCT city), string_agg(DISTINCT city, '/') FROM person")).containsExactly(row("3", "Delft/Leiden/Utrecht"));
  }

  @Test
  void stringAggInHavingAndOrderBy() {
    assertThat(rows("SELECT city FROM person GROUP BY city HAVING string_agg(name, ',') LIKE '%,%' ORDER BY city"))
        .containsExactly(row("Delft"), row("Leiden"));
    assertThat(rows("SELECT city FROM person GROUP BY city ORDER BY string_agg(name, ',') DESC"))
        .containsExactly(row("Utrecht"), row("Leiden"), row("Delft"));
  }

  @Test
  void stringAggArgumentsAreTypedAsPostgreSqlDoes() {
    assertState("SELECT string_agg(pid, ',') FROM person", "42883");
    assertState("SELECT string_agg(name, 1) FROM person", "42883");
    assertState("SELECT string_agg(name) FROM person", "42883");
    assertState("SELECT string_agg(*) FROM person", "42883");
    assertState("SELECT name FROM person WHERE string_agg(name, ',') = 'x'", "42803");
    assertState("SELECT string_agg(name, ',') OVER () FROM person", "0A000");
  }

  @Test
  void aggregatesTheLanguageDoesNotHaveAreRefusedAsNotSupported() {
    assertState("SELECT bool_and(age > 1) FROM person", "0A000");
    assertState("SELECT json_agg(name) FROM person", "0A000");
    assertState("SELECT no_such_aggregate(name) FROM person", "42883");
  }
}
