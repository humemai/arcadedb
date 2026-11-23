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
package com.arcadedb.query.postgresql.corpus;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules of the parity comparison and the formats of the corpus, tested without a server. The same rules compare the engine
 * with PostgreSQL in the {@code e2e} harness, so a rule is written once and tested here.
 */
class ResultComparatorTest {
  private static final double TOL = Values.DEFAULT_TOLERANCE;

  private static ResultTable table(final List<String> labels, final List<String> types, final Object[]... rows) {
    final List<List<Object>> list = new ArrayList<>();
    for (final Object[] row : rows) {
      final List<Object> cells = new ArrayList<>();
      for (final Object cell : row)
        cells.add(Values.normalize(cell));
      list.add(cells);
    }
    return new ResultTable(labels, types, list);
  }

  private static ResultTable names(final String... names) {
    final Object[][] rows = new Object[names.length][];
    for (int i = 0; i < names.length; i++)
      rows[i] = new Object[] { names[i] };
    return table(List.of("name"), List.of("text"), rows);
  }

  @Test
  void equalResultsCompareEqual() {
    final ResultTable a = names("Ada", "Bo", "Cy", "Di", "Ed");
    assertThat(ResultComparator.compare(a, names("Ada", "Bo", "Cy", "Di", "Ed"), true, TOL)).isNull();
    assertThat(ResultComparator.compare(a, names("Ed", "Di", "Cy", "Bo", "Ada"), false, TOL)).isNull();
  }

  @Test
  void orderCountsOnlyWhenTheStatementIsOrdered() {
    final ResultTable a = names("Ada", "Bo");
    final ResultTable b = names("Bo", "Ada");
    assertThat(ResultComparator.compare(a, b, true, TOL)).startsWith("row 1 differs");
    assertThat(ResultComparator.compare(a, b, false, TOL)).isNull();
  }

  @Test
  void aMissingRowIsADifferenceOfOneRow() {
    final ResultTable a = names("Ada", "Bo", "Cy", "Di", "Ed");
    assertThat(ResultComparator.compare(a, a.withoutRow(2), false, TOL)).startsWith("differs by 1 row ");
    assertThat(ResultComparator.compare(a, a.withoutRow(2), true, TOL)).startsWith("differs by 1 row ");
  }

  @Test
  void aDuplicatedRowIsADifferenceAlthoughTheDistinctRowsAreEqual() {
    final ResultTable a = names("Ada", "Bo", "Cy");
    final ResultTable duplicated = a.withRowDuplicated(1);
    assertThat(ResultComparator.compare(a, duplicated, false, TOL)).startsWith("differs by 1 row ");
    assertThat(ResultComparator.compare(a, duplicated, true, TOL)).startsWith("differs by 1 row ");
  }

  @Test
  void aRowRepeatedOnOneSideAndAnotherLostOnTheOtherIsADifference() {
    // same number of rows, same distinct rows on neither side: only a multiset comparison sees it
    final ResultTable a = names("Ada", "Ada", "Bo");
    final ResultTable b = names("Ada", "Bo", "Bo");
    assertThat(ResultComparator.compare(a, b, false, TOL)).startsWith("differs by 1 row as a multiset");
  }

  @Test
  void numbersCompareByValue() {
    final ResultTable decimals = table(List.of("total"), null, new Object[] { new BigDecimal("38.5") });
    assertThat(ResultComparator.compare(decimals, table(List.of("total"), null, new Object[] { new BigDecimal("38.50") }), true, TOL))
        .isNull();
    assertThat(ResultComparator.compare(table(List.of("n"), null, new Object[] { 5 }),
        table(List.of("n"), null, new Object[] { 5L }), true, TOL)).isNull();
    assertThat(ResultComparator.compare(table(List.of("n"), null, new Object[] { 5 }),
        table(List.of("n"), null, new Object[] { new BigDecimal("5.0") }), true, TOL)).isNull();
    assertThat(ResultComparator.compare(table(List.of("n"), null, new Object[] { 5 }),
        table(List.of("n"), null, new Object[] { 6 }), true, TOL)).startsWith("row 1 differs");
  }

  @Test
  void floatingPointUsesTheTolerance() {
    final ResultTable exact = table(List.of("a"), null, new Object[] { new BigDecimal("39.5000000000000000") });
    assertThat(ResultComparator.compare(exact, table(List.of("a"), null, new Object[] { 39.5d }), true, TOL)).isNull();
    assertThat(ResultComparator.compare(exact, table(List.of("a"), null, new Object[] { 39.5000001d }), true, TOL))
        .startsWith("row 1 differs");
    assertThat(ResultComparator.compare(exact, table(List.of("a"), null, new Object[] { 39.5000001d }), true, 1e-6)).isNull();
    // an integer has no tolerance: 5 and 5.000000001 are different numbers
    assertThat(ResultComparator.compare(table(List.of("n"), null, new Object[] { 5 }),
        table(List.of("n"), null, new Object[] { new BigDecimal("5.000000001") }), true, TOL)).startsWith("row 1 differs");
  }

  @Test
  void nullEqualsNullAndNothingElse() {
    assertThat(ResultComparator.compare(table(List.of("a"), null, new Object[] { null }),
        table(List.of("a"), null, new Object[] { null }), true, TOL)).isNull();
    assertThat(ResultComparator.compare(table(List.of("a"), null, new Object[] { null }),
        table(List.of("a"), null, new Object[] { 0 }), true, TOL)).startsWith("row 1 differs");
    assertThat(ResultComparator.compare(table(List.of("a"), null, new Object[] { "" }),
        table(List.of("a"), null, new Object[] { null }), true, TOL)).startsWith("row 1 differs");
  }

  @Test
  void textIsExactAndDatesAreTheirIsoText() {
    assertThat(ResultComparator.compare(names("Ada"), names("ada"), true, TOL)).startsWith("row 1 differs");
    final ResultTable day = table(List.of("placed"), List.of("date"), new Object[] { LocalDate.of(2026, 1, 5) });
    assertThat(ResultComparator.compare(day, table(List.of("placed"), List.of("date"), new Object[] { "2026-01-05" }), true, TOL))
        .isNull();
    final Date midnight = Date.from(LocalDate.of(2026, 1, 5).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
    assertThat(ResultComparator.compare(day, table(List.of("placed"), List.of("date"), new Object[] { midnight }), true, TOL))
        .isNull();
  }

  @Test
  void listsAndMapsCompareElementWise() {
    final ResultTable a = table(List.of("tags", "address"), null,
        new Object[] { List.of("gift", "express"), Map.of("zip", "1000AA", "city", "Delft") });
    final ResultTable b = table(List.of("tags", "address"), null,
        new Object[] { new String[] { "gift", "express" }, Map.of("city", "Delft", "zip", "1000AA") });
    assertThat(ResultComparator.compare(a, b, true, TOL)).isNull();
    final ResultTable c = table(List.of("tags", "address"), null,
        new Object[] { List.of("express", "gift"), Map.of("city", "Delft", "zip", "1000AA") });
    assertThat(ResultComparator.compare(a, c, true, TOL)).startsWith("row 1 differs");
  }

  @Test
  void labelsAndTypesAreComparedWhereASideReportsThem() {
    final ResultTable server = table(List.of("name", "name"), List.of("text", "text"), new Object[] { "Ada", "Acme" });
    assertThat(ResultComparator.compare(server, table(List.of("name", "name_1"), List.of("text", "text"),
        new Object[] { "Ada", "Acme" }), true, TOL)).startsWith("column labels differ");
    assertThat(ResultComparator.compare(server, table(List.of("name", "name"), List.of("text", "varchar"),
        new Object[] { "Ada", "Acme" }), true, TOL)).startsWith("column types differ");
    // an engine that reports no types is not checked for them
    assertThat(ResultComparator.compare(server, table(List.of("name", "name"), null, new Object[] { "Ada", "Acme" }), true, TOL))
        .isNull();
    assertThat(ResultComparator.compare(server, server.valuesOnly(), true, TOL)).isNull();
  }

  @Test
  void refusalsCompareBySqlState() {
    final Outcome refused = Outcome.refused("42703", "column \"nosuch\" does not exist");
    assertThat(ResultComparator.compare(refused, Outcome.refused("42703", "other words"), false, TOL)).isNull();
    assertThat(ResultComparator.compare(refused, Outcome.refused("0A000", "x"), false, TOL))
        .isEqualTo("SQLSTATE differs: expected 42703, got 0A000");
    assertThat(ResultComparator.compare(refused, Outcome.answer(names("Ada")), false, TOL)).startsWith("expected a refusal");
    assertThat(ResultComparator.compare(Outcome.answer(names("Ada")), refused, false, TOL)).startsWith("expected an answer");
  }

  @Test
  void anOutcomeSurvivesItsJsonForm() {
    final ResultTable original = table(List.of("n", "f", "t", "d", "tags", "address", "missing"),
        List.of("int8", "float8", "text", "date", "_text", "jsonb", "text"),
        new Object[] { 5L, 39.5d, "tab\tquote\"back\\slash\nline", LocalDate.of(2026, 1, 5), List.of("a", "b"),
            Map.of("zip", "1000AA", "n", 3), null });
    final String json = original.toJson();
    assertThat(json).doesNotContain("\t").doesNotContain("\n");
    final Outcome back = Outcome.fromJson(Outcome.answer(original).toJson());
    assertThat(back.isRefusal()).isFalse();
    assertThat(back.table.labels).isEqualTo(original.labels);
    assertThat(back.table.types).isEqualTo(original.types);
    assertThat(ResultComparator.compare(original, back.table, true, TOL)).isNull();

    final Outcome refusal = Outcome.fromJson(Outcome.refused("22012", "division by zero").toJson());
    assertThat(refusal.isRefusal()).isTrue();
    assertThat(refusal.sqlState).isEqualTo("22012");
  }

  @Test
  void aCorpusFileKeepsItsHeaderAndEscapes() {
    final String statement = "SELECT 'a'\n'b'\t-- \\ x";
    final String text = "# first\n# server: PostgreSQL 18.0\nid\tstatement\texpected\n" + "one\t" + CorpusFile.escape(statement)
        + "\tok\n" + "two\tSELECT 1\t\n";
    final CorpusFile file = CorpusFile.parse("test", text);
    assertThat(file.comments).containsExactly("# first", "# server: PostgreSQL 18.0");
    assertThat(file.headerValue("server")).isEqualTo("PostgreSQL 18.0");
    assertThat(file.rows).hasSize(2);
    assertThat(CorpusFile.unescape(file.rows.get(0)[1])).isEqualTo(statement);
    assertThat(file.rows.get(1)[2]).isEmpty();
    assertThat(file.render()).isEqualTo(text);

    file.setHeaderValue("server", "PostgreSQL 18.1");
    file.setHeaderValue("collation", "en_US.utf8");
    assertThat(file.render()).startsWith("# first\n# server: PostgreSQL 18.1\n# collation: en_US.utf8\nid\t");
  }
}
