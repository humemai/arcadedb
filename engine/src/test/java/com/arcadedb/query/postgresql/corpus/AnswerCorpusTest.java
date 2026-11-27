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

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every statement of the answer corpus ({@code answers.tsv}, recorded from the real PostgreSQL 18 server) through the
 * {@code postgresql} language on the {@code small} fixture and compares the rows, the column labels and the column type names
 * with the classes of the harness ({@link ResultComparator}, {@link Values}).
 *
 * <ul>
 * <li>{@code ok}: the engine has to answer like the server, or refuse with the same SQLSTATE.</li>
 * <li>{@code pending}: the engine does not support the statement yet. The test tries it and skips it; a pending statement that the
 * engine now answers like the server fails the test until its flag is flipped to {@code ok}, as in the parity harness.</li>
 * </ul>
 *
 * The corpus is recorded under the {@code C} collation, which is the order of the evaluator (code point order). The test refuses
 * to run on a corpus that says another collation, so a stale file fails loudly. The record ids of the corpus are those of the
 * fixture file, {@code #<table number>:<row number>}; the real ids of the engine are translated before the comparison, the way
 * the harness does it (table numbers 1 to 3 as in {@code small-schema.sql}: company, person, orders).
 */
class AnswerCorpusTest extends TestHelper {
  private Map<String, String> ridAliases;

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
    ridAliases = ridAliases();
  }

  static Stream<Corpus.AnswerCase> cases() throws IOException {
    return Corpus.answerCases(Corpus.readAnswers()).stream();
  }

  private static void requireCCollation() throws IOException {
    final String collation = Corpus.readAnswers().headerValue("collation");
    assertThat(collation).as("the corpus is recorded under this collation, the evaluator orders text by code point (the C collation)")
        .isEqualTo("C");
  }

  @Test
  void theCorpusIsRecordedUnderTheCCollation() throws IOException {
    requireCCollation();
  }

  @Test
  void theFixtureIdsAreTranslated() {
    assertThat(ridAliases).hasSize(14);
    assertThat(ridAliases.values()).contains("#1:0", "#2:4", "#3:5");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theEngineAnswersLikeTheServer(final Corpus.AnswerCase answer) throws IOException {
    requireCCollation();
    Assumptions.assumeTrue(answer.hasOutcome(), "the generator has not run on this case");

    final Outcome expected = Outcome.fromJson(answer.expected());
    final Outcome actual = run(answer.statement());
    final String difference = ResultComparator.compare(expected, actual, answer.ordered(), answer.tolerance());

    if (answer.pending()) {
      if (difference == null)
        throw new AssertionError(answer.id() + " is pending, and the engine now answers it like the server: flip its status to ok");
      // visible in the log of the run: what is still missing
      System.out.println("PENDING " + answer.id() + ": " + difference);
      Assumptions.assumeTrue(false, "pending: " + difference);
      return;
    }
    assertThat(difference).as("%s: %s", answer.id(), answer.statement()).isNull();
  }

  // ---- the engine side ----

  private Outcome run(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      final PostgreSQLResultSet answer = (PostgreSQLResultSet) resultSet;
      final List<String> labels = new ArrayList<>();
      final List<String> types = new ArrayList<>();
      for (final PostgreSQLResultSet.Column column : answer.columns()) {
        labels.add(column.name());
        types.add(column.typeName());
      }
      final List<String> keys = PostgreSQLResultSet.uniqueKeys(answer.columns());
      final List<List<Object>> rows = new ArrayList<>();
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> row = new ArrayList<>(keys.size());
        for (final String key : keys)
          row.add(alias(Values.normalize(result.getProperty(key))));
        rows.add(row);
      }
      return Outcome.answer(new ResultTable(labels, types, rows));
    } catch (final Throwable t) {
      return Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
    }
  }

  private Object alias(final Object value) {
    if (value instanceof String text) {
      final String mapped = ridAliases.get(text);
      return mapped != null ? mapped : value;
    }
    return value;
  }

  /** The SQLSTATE of the first exception of the cause chain that has {@code getSqlState()}, else {@code unknown}. */
  private static String sqlState(final Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 20; depth++) {
      try {
        final Object state = current.getClass().getMethod("getSqlState").invoke(current);
        if (state instanceof String text && !text.isEmpty())
          return text;
      } catch (final ReflectiveOperationException ignored) {
        // not this one: look at its cause
      }
      current = current.getCause();
    }
    return "unknown";
  }

  /** The real record id of every row of the three tables with ids in the corpus, to the id the fixture file gives it. */
  private Map<String, String> ridAliases() {
    final String[][] tables = { { "company", "cid", "1" }, { "person", "pid", "2" }, { "orders", "oid", "3" } };
    final Map<String, String> aliases = new HashMap<>();
    for (final String[] table : tables) {
      try (final ResultSet resultSet = database.query("postgresql", "SELECT rid FROM " + table[0] + " ORDER BY " + table[1])) {
        int row = 0;
        while (resultSet.hasNext())
          aliases.put(resultSet.next().getProperty("rid"), "#" + table[2] + ":" + row++);
      }
    }
    return aliases;
  }
}
