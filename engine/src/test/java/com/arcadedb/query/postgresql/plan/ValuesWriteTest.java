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
import com.arcadedb.query.postgresql.ValuesFixture;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The values of {@code jsonb}, arrays, {@code bytea} and {@code vector} that the {@code postgresql} language writes (fork issue
 * #29), as literals and as parameters: the record holds the value in the class of its property, and {@code sql} and openCypher read
 * it back. The comparison of the literals with PostgreSQL 18 is the values corpus ({@code values-writes.tsv}).
 */
class ValuesWriteTest extends TestHelper {
  @Override
  protected void beginTest() {
    ValuesFixture.createSchema(database);
    ValuesFixture.load(database);
  }

  private void command(final String sql, final Object... parameters) {
    database.transaction(() -> {
      try (final ResultSet resultSet = database.command("postgresql", sql, parameters)) {
        while (resultSet.hasNext())
          resultSet.next();
      }
    });
  }

  private Result single(final String language, final String query, final Object... parameters) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query(language, query, parameters)) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    assertThat(rows).as(query).hasSize(1);
    return rows.get(0);
  }

  private void assertState(final String sql, final String sqlState, final Object... parameters) {
    try {
      command(sql, parameters);
      fail("no error for " + sql);
    } catch (final RuntimeException e) {
      Throwable cause = e;
      while (cause != null && !(cause instanceof PostgreSQLEvaluationException))
        cause = cause.getCause();
      assertThat(cause).as("%s: %s", sql, e).isNotNull();
      assertThat(((PostgreSQLEvaluationException) cause).getSqlState()).as(sql + ": " + cause.getMessage()).isEqualTo(sqlState);
    }
  }

  private static List<?> list(final Object value) {
    if (value instanceof List<?> list)
      return list;
    final int length = java.lang.reflect.Array.getLength(value);
    final List<Object> out = new ArrayList<>(length);
    for (int i = 0; i < length; i++)
      out.add(java.lang.reflect.Array.get(value, i));
    return out;
  }

  /** Every language reads the same value of every property of the row {@code mid}. */
  private void assertStored(final int mid) {
    for (final String[] read : new String[][] { { "postgresql", "SELECT * FROM memory WHERE mid = " + mid },
        { "sql", "SELECT FROM memory WHERE mid = " + mid }, { "opencypher",
        "MATCH (m:memory) WHERE m.mid = " + mid + " RETURN m.tags AS tags, m.counts AS counts, m.big AS big, m.scores AS scores, "
            + "m.meta AS meta, m.items AS items, m.payload AS payload, m.embedding AS embedding" } }) {
      final Result row = single(read[0], read[1]);
      assertThat(list(row.getProperty("tags"))).as(read[0]).isEqualTo(List.of("work", "two words"));
      assertThat(list(row.getProperty("counts"))).as(read[0]).extracting(n -> ((Number) n).intValue()).containsExactly(1, 2);
      assertThat(list(row.getProperty("big"))).as(read[0]).extracting(n -> ((Number) n).longValue()).containsExactly(3000000000L);
      assertThat(list(row.getProperty("scores"))).as(read[0]).extracting(n -> ((Number) n).doubleValue()).containsExactly(0.5, 2.0);
      final Map<?, ?> meta = row.getProperty("meta");
      assertThat(meta.get("who")).as(read[0]).isEqualTo("Ada");
      assertThat(((Number) meta.get("n")).intValue()).as(read[0]).isEqualTo(3);
      assertThat(list(meta.get("list"))).as(read[0]).hasSize(2);
      assertThat(list(row.getProperty("items"))).as(read[0]).hasSize(1);
      assertThat((byte[]) row.getProperty("payload")).as(read[0]).containsExactly(0xde, 0xad);
      assertThat(list(row.getProperty("embedding"))).as(read[0]).extracting(n -> ((Number) n).floatValue())
          .containsExactly(1f, 0f, 0.5f);
    }
  }

  private static final String COLUMNS = "INSERT INTO memory (mid, tags, counts, big, scores, meta, items, payload, embedding) VALUES ";

  @Test
  void literalsReadBackTheSameThroughEveryLanguage() {
    command(COLUMNS + "(2, '{work,\"two words\"}', '{1,2}', ARRAY[3000000000], ARRAY[0.5, 2], "
        + "'{\"who\": \"Ada\", \"n\": 3, \"list\": [1, 2]}', '[{\"a\": 1}]', '\\xdead', '[1, 0, 0.5]')");
    assertStored(2);
  }

  @Test
  void castsReadBackTheSame() {
    command(COLUMNS + "(3, ARRAY['work', 'two words'], '{1,2}'::int4[], '{3000000000}'::int8[], '{0.5,2}'::float8[], "
        + "'{\"who\": \"Ada\", \"n\": 3, \"list\": [1, 2]}'::jsonb, '[{\"a\": 1}]'::jsonb, '\\xdead'::bytea, "
        + "ARRAY[1, 0, 0.5]::vector)");
    assertStored(3);
  }

  @Test
  void javaValuesOfParametersReadBackTheSame() {
    command(COLUMNS + "($1, $2, $3, $4, $5, $6, $7, $8, $9)", 4, List.of("work", "two words"), new int[] { 1, 2 },
        new Long[] { 3000000000L }, List.of(0.5, 2), Map.of("who", "Ada", "n", 3, "list", List.of(1, 2)),
        List.of(Map.of("a", 1)), new byte[] { (byte) 0xde, (byte) 0xad }, new float[] { 1f, 0f, 0.5f });
    assertStored(4);
  }

  @Test
  void textParametersReadBackTheSame() {
    command(COLUMNS + "($1, $2, $3, $4, $5, $6, $7, $8, $9)", 5, "{work,\"two words\"}", "{1,2}", "{3000000000}", "{0.5,2}",
        "{\"who\": \"Ada\", \"n\": 3, \"list\": [1, 2]}", "[{\"a\": 1}]", "\\xdead", "[1,0,0.5]");
    assertStored(5);
  }

  @Test
  void parametersWithACastAndAnUpdate() {
    command("INSERT INTO memory (mid) VALUES (6)");
    command("UPDATE memory SET tags = $1::text[], meta = $2::jsonb, embedding = $3::vector, counts = $4, big = $5, scores = $6, "
            + "items = $7, payload = $8::bytea WHERE mid = 6", List.of("work", "two words"),
        Map.of("who", "Ada", "n", 3, "list", List.of(1, 2)), List.of(1, 0, 0.5), List.of(1, 2), List.of(3000000000L),
        new double[] { 0.5, 2 }, List.of(Map.of("a", 1)), "\\xdead");
    assertStored(6);
  }

  @Test
  void aCopyOfARowKeepsItsValues() {
    command(COLUMNS + "(7, '{work,\"two words\"}', '{1,2}', '{3000000000}', '{0.5,2}', "
        + "'{\"who\": \"Ada\", \"n\": 3, \"list\": [1, 2]}', '[{\"a\": 1}]', '\\xdead', '[1,0,0.5]')");
    command("INSERT INTO memory (mid, tags, counts, big, scores, meta, items, payload, embedding) "
        + "SELECT 8, tags, counts, big, scores, meta, items, payload, embedding FROM memory WHERE mid = 7");
    assertStored(8);
  }

  @Test
  void aStoredEmbeddingIsFoundByTheVectorIndex() {
    database.transaction(() -> database.getSchema().buildTypeIndex(ValuesFixture.MEMORY, new String[] { "embedding" })
        .withLSMVectorType().withDimensions(3).withSimilarity("COSINE").create());
    command("INSERT INTO memory (mid, embedding) VALUES (10, '[1,0,0]'), (11, $1)", new float[] { 0f, 1f, 0f });
    final Result nearest = single("postgresql", "SELECT mid FROM memory ORDER BY embedding <=> $1 LIMIT 1", "[0,0.9,0.1]");
    assertThat(((Number) nearest.getProperty("mid")).intValue()).isEqualTo(11);
    assertState("INSERT INTO memory (mid, embedding) VALUES (12, '[1,0]')", "22000");
    assertState("UPDATE memory SET embedding = $1 WHERE mid = 10", "22000", new float[] { 1f });
  }

  @Test
  void numericArraysKeepTheirDigits() {
    command("INSERT INTO memory (mid, prices) VALUES (13, '{1.10,2}')");
    final List<?> prices = list(single("sql", "SELECT FROM memory WHERE mid = 13").getProperty("prices"));
    assertThat(prices).hasSize(2);
    assertThat((BigDecimal) prices.get(0)).isEqualByComparingTo("1.10");
  }

  @Test
  void whatARecordCannotHoldIsRefused() {
    // a MAP holds an object; PostgreSQL would take any JSON value
    assertState("INSERT INTO memory (mid, meta) VALUES (20, '[1]')", "0A000");
    assertState("INSERT INTO memory (mid, meta) VALUES (20, '1')", "0A000");
    assertState("INSERT INTO memory (mid, meta) VALUES (20, 'null')", "0A000");
    assertState("INSERT INTO memory (mid, meta) VALUES (20, '{\"@type\": \"memory\"}')", "0A000");
    assertState("INSERT INTO memory (mid, items) VALUES (20, '{\"a\": 1}')", "0A000");
    assertState("INSERT INTO memory (mid, items) VALUES (20, '[1]')", "0A000");
    // a typed list and a primitive array hold no null element
    assertState("INSERT INTO memory (mid, tags) VALUES (20, '{a,NULL}')", "0A000");
    assertState("INSERT INTO memory (mid, counts) VALUES (20, ARRAY[1, NULL])", "0A000");
    // one dimension
    assertState("INSERT INTO memory (mid, counts) VALUES (20, '{{1,2},{3,4}}')", "0A000");
    assertState("INSERT INTO memory (mid, counts) VALUES (20, ARRAY[ARRAY[1]])", "0A000");
    // pgvector's refusals
    assertState("INSERT INTO memory (mid, embedding) VALUES (20, '[]')", "22000");
    assertState("INSERT INTO memory (mid, embedding) VALUES (20, $1)", "22004", Arrays.asList(1, null));
    assertState("INSERT INTO memory (mid, embedding) VALUES (20, $1)", "22003", new float[] { Float.NaN });
    assertState("INSERT INTO memory (mid, embedding) VALUES (20, '{1,2}'::text[])", "42804");
    // a Java value that is not one of the column
    assertState("INSERT INTO memory (mid, payload) VALUES (20, $1)", "42804", List.of(1));
    assertState("INSERT INTO memory (mid) VALUES ($1)", "42804", List.of(1));
    assertThat(((Number) single("postgresql", "SELECT count(*) AS n FROM memory").getProperty("n")).longValue()).isEqualTo(1L);
  }

  /** A list of dates is {@code date[]} and a list of date times is {@code timestamp[]} (fork issue #146). */
  @Test
  void listsOfDatesAndDateTimesAreWrittenAndRead() {
    command("INSERT INTO calendar (cid, days, moments) VALUES (2, '{2026-01-05,2026-02-01}', '{\"2026-01-05 10:20:30.123456\"}')");
    command("INSERT INTO calendar (cid, days, moments) VALUES (3, ARRAY['2026-01-05'::date, '2026-02-01'], "
        + "ARRAY['2026-01-05 10:20:30.123456'::timestamp])");
    command("INSERT INTO calendar (cid, days, moments) VALUES ($1, $2, $3)", 4,
        List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1)),
        List.of(LocalDateTime.of(2026, 1, 5, 10, 20, 30, 123_456_000)));
    command("INSERT INTO calendar (cid, days, moments) VALUES ($1, $2, $3)", 5, "{2026-01-05,2026-02-01}",
        "{\"2026-01-05 10:20:30.123456\"}");

    for (int cid = 2; cid <= 5; cid++) {
      final Result row = single("postgresql", "SELECT days, moments FROM calendar WHERE cid = " + cid);
      assertThat(list(row.getProperty("days"))).as("row " + cid)
          .isEqualTo(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 1)));
      assertThat(list(row.getProperty("moments"))).as("row " + cid)
          .isEqualTo(List.of(LocalDateTime.of(2026, 1, 5, 10, 20, 30, 123_456_000)));
      // the record holds them as dates, so another language reads dates and not texts
      assertThat(list(single("sql", "SELECT FROM calendar WHERE cid = " + cid).getProperty("days"))).as("sql, row " + cid)
          .hasSize(2).noneMatch(day -> day instanceof String);
    }
  }

  @Test
  void whatAListOfDatesCannotHoldIsRefused() {
    // a typed list holds no null element
    assertState("INSERT INTO calendar (cid, days) VALUES (20, '{2026-01-05,NULL}')", "0A000");
    assertState("INSERT INTO calendar (cid, moments) VALUES (20, ARRAY['2026-01-05'::timestamp, NULL])", "0A000");
    // the input rules of the element type
    assertState("INSERT INTO calendar (cid, days) VALUES (20, '{abc}')", "22007");
    assertState("INSERT INTO calendar (cid, days) VALUES (20, '{2026-02-30}')", "22008");
    assertState("INSERT INTO calendar (cid, days) VALUES (20, ARRAY[1])", "42804");
    // a list of links stays text[] and stays refused
    database.transaction(() -> database.getSchema().getType("calendar").createProperty("seen", Type.LIST, "LINK"));
    assertState("INSERT INTO calendar (cid, seen) VALUES (20, '{#1:0}')", "0A000");
    assertThat(((Number) single("postgresql", "SELECT count(*) AS n FROM calendar").getProperty("n")).longValue()).isEqualTo(1L);
  }

  @Test
  void aNarrowArrayKeepsItsRange() {
    database.transaction(() -> database.getSchema().createDocumentType("visit").createProperty("small", Type.ARRAY_OF_SHORTS));
    assertState("INSERT INTO visit (small) VALUES ('{40000}')", "22003");
    command("INSERT INTO visit (small) VALUES ('{1,2}')");
    assertThat(list(single("sql", "SELECT FROM visit").getProperty("small"))).hasSize(2);
  }
}
