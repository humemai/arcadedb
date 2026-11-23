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

import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultComparator;
import com.arcadedb.query.postgresql.corpus.Values;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PostgreSQL side of the parity harness on a tiny schema of its own ({@code pgparity/tiny-schema.sql}), so that it is tested
 * apart from the {@code small} fixture: how a server answer is read (types, labels, every kind of value), how a refusal is read,
 * and that the parse step parses and never runs.
 */
class PostgresServerIT {
  private static PostgresServer pg;

  @BeforeAll
  static void start() throws Exception {
    pg = PostgresServer.start();
    try (final InputStream in = PostgresServerIT.class.getResourceAsStream("/pgparity/tiny-schema.sql")) {
      assertNotNull(in, "pgparity/tiny-schema.sql is missing from the test resources");
      pg.load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @AfterAll
  static void stop() {
    if (pg != null)
      pg.close();
  }

  @Test
  void theServerIsPostgreSQL18() throws Exception {
    final String version = pg.version();
    System.out.println("pgparity: " + version);
    assertTrue(version.startsWith("PostgreSQL 18"), version);
  }

  @Test
  void textIsOrderedByCodePoint() throws Exception {
    assertEquals("C", pg.collation());
    assertEquals("UTF8", pg.encoding());
    // code point order: an upper case letter before a lower case one, punctuation by its code, a letter with an accent after z
    final Outcome outcome = pg.run(
        "SELECT 'a' < 'B' AS lower_first, 'B' < 'a' AS upper_first, 'a-b' < 'ab' AS dash_first, 'z' < '\u00e9' AS accent_last");
    assertFalse(outcome.isRefusal(), outcome.describe());
    assertEquals(List.of(Boolean.FALSE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE), outcome.table.rows.get(0));
  }

  @Test
  void everyKindOfValueIsRead() {
    final Outcome outcome = pg.run("SELECT n, label, price, ratio, day, tags, extra FROM tiny ORDER BY n");
    assertFalse(outcome.isRefusal(), outcome.describe());
    assertEquals(List.of("n", "label", "price", "ratio", "day", "tags", "extra"), outcome.table.labels);
    assertEquals(List.of("int4", "text", "numeric", "float8", "date", "_text", "jsonb"), outcome.table.types);
    assertEquals(2, outcome.table.rows.size());

    final List<Object> first = outcome.table.rows.get(0);
    assertTrue(Values.equal(new BigDecimal(1), first.get(0), Values.DEFAULT_TOLERANCE));
    assertEquals("one", first.get(1));
    assertTrue(Values.equal(new BigDecimal("38.5"), first.get(2), Values.DEFAULT_TOLERANCE));
    assertTrue(Values.equal(0.5d, first.get(3), Values.DEFAULT_TOLERANCE));
    assertEquals("2026-01-05", first.get(4));
    assertEquals(List.of("a", "b"), first.get(5));
    final Map<?, ?> extra = (Map<?, ?>) first.get(6);
    assertEquals("x", extra.get("j"));
    assertTrue(Values.equal(new BigDecimal(1), extra.get("k"), Values.DEFAULT_TOLERANCE));

    final List<Object> second = outcome.table.rows.get(1);
    for (int i = 1; i < second.size(); i++)
      assertNull(second.get(i), "column " + (i + 1));
  }

  @Test
  void anOutcomeSurvivesItsJsonForm() {
    final Outcome outcome = pg.run("SELECT n, label, price, ratio, day, tags, extra FROM tiny ORDER BY n");
    final Outcome back = Outcome.fromJson(outcome.toJson());
    assertNull(ResultComparator.compare(outcome, back, true, Values.DEFAULT_TOLERANCE));
  }

  @Test
  void numbersCompareByValueAcrossTypes() {
    final Outcome a = pg.run("SELECT 38.5::numeric AS v");
    final Outcome b = pg.run("SELECT 38.50::numeric AS v");
    final Outcome c = pg.run("SELECT 38.5::float8 AS v");
    assertNull(ResultComparator.compare(a, b, true, Values.DEFAULT_TOLERANCE));
    assertNull(ResultComparator.compare(Outcome.answer(a.table.valuesOnly()), Outcome.answer(c.table.valuesOnly()), true,
        Values.DEFAULT_TOLERANCE));
    // the type names differ, which a comparison with the types would report
    assertTrue(ResultComparator.compare(a, c, true, Values.DEFAULT_TOLERANCE).startsWith("column types differ"));
  }

  @Test
  void aRefusalKeepsItsSqlState() {
    assertEquals("42703", pg.run("SELECT nosuch FROM tiny").sqlState);
    assertEquals("42P01", pg.run("SELECT * FROM nosuch").sqlState);
    assertEquals("22012", pg.run("SELECT 1 / 0").sqlState);
  }

  @Test
  void theParseStepParsesAndNeverRuns() {
    assertEquals("ok", pg.parseOutcome("SELECT n FROM tiny"));
    assertEquals("ok", pg.parseOutcome("SELECT n FROM tiny;"));
    assertEquals("syntax-error 42601", pg.parseOutcome("SELECT * FORM tiny"));
    assertEquals("syntax-error 42601", pg.parseOutcome("SELECT 'unterminated"));
    assertEquals("error 42P01", pg.parseOutcome("SELECT * FROM nosuch"));
    assertEquals("error 42703", pg.parseOutcome("SELECT nosuch FROM tiny"));

    // statements that would change the data if they ran
    assertEquals("ok", pg.parseOutcome("DELETE FROM tiny"));
    assertEquals("ok", pg.parseOutcome("DROP TABLE tiny"));
    assertEquals("ok", pg.parseOutcome("TRUNCATE tiny"));
    final Outcome count = pg.run("SELECT count(*) FROM tiny");
    assertTrue(Values.equal(new BigDecimal(2), count.table.rows.get(0).get(0), Values.DEFAULT_TOLERANCE),
        "the parse step ran a statement: " + count.describe());
  }
}
