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
import com.arcadedb.query.postgresql.IdRuleFixture;
import com.arcadedb.query.postgresql.catalog.SmallSchemaSql;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus of the {@code id} rule of fork issue #38 ({@code idrule.tsv}, recorded from the real PostgreSQL 18 server on the
 * tables that {@code IdRuleFixture} declares, loaded into the server from the relational view of the same database) through the
 * {@code postgresql} language of the engine. The rules are those of {@code AnswerCorpusTest}: {@code ok} must answer like the
 * server, an {@code err-} statement must be refused with the same SQLSTATE, and a {@code pending} statement is tried and skipped.
 * The record ids of the corpus are the ones {@code SmallSchemaSql.generate(database, true)} writes into the server, and the real
 * ids of the engine are translated to them before the comparison, as in the harness.
 */
class IdRuleCorpusTest extends TestHelper {
  private static final Pattern ID       = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final Pattern SQLSTATE = Pattern.compile("[0-9A-Z]{5}");

  private Map<String, String> ridAliases;

  @Override
  protected void beginTest() {
    IdRuleFixture.createSchema(database);
    IdRuleFixture.load(database);
    ridAliases = SmallSchemaSql.recordIdAliases(database);
  }

  static Stream<Corpus.AnswerCase> cases() throws IOException {
    return Corpus.answerCases(Corpus.readIdRule()).stream();
  }

  @Test
  void theFileIsWellFormed() throws IOException {
    final CorpusFile file = Corpus.readIdRule();
    assertThat(file.columns).containsExactly("id", "order", "tol", "status", "statement", "expected");
    assertThat(file.rows).isNotEmpty();
    final Set<String> ids = new HashSet<>();
    for (final Corpus.AnswerCase c : Corpus.answerCases(file)) {
      assertThat(c.id()).as("id of %s", c.statement()).matches(ID);
      assertThat(c.id().replaceFirst("^err-", "")).as("id of %s", c.statement()).startsWith("idr-");
      assertThat(ids.add(c.id())).as("unique id %s", c.id()).isTrue();
      assertThat(c.row[Corpus.AnswerCase.ORDER]).as("order of %s", c.id()).isIn("ordered", "multiset");
      assertThat(c.row[Corpus.AnswerCase.STATUS]).as("status of %s", c.id()).isIn("ok", "pending");
      assertThat(c.statement()).as("statement of %s", c.id()).isNotEmpty();
      if (!c.hasOutcome())
        continue;
      final Outcome outcome = Outcome.fromJson(c.expected());
      assertThat(outcome.isRefusal()).as("%s: a refusal exactly for an err- id", c.id()).isEqualTo(c.expectsRefusal());
      if (outcome.isRefusal())
        assertThat(outcome.sqlState).as("SQLSTATE of %s", c.id()).matches(SQLSTATE);
      else {
        assertThat(outcome.table.labels).as("columns of %s", c.id()).isNotEmpty();
        for (final List<Object> row : outcome.table.rows)
          assertThat(row).as("a row of %s", c.id()).hasSameSizeAs(outcome.table.labels);
        if (c.ordered())
          assertThat(c.statement().toUpperCase()).as("ordered %s has an ORDER BY", c.id()).contains("ORDER BY");
      }
    }
    if (file.rows.stream().anyMatch(row -> new Corpus.AnswerCase(row).hasOutcome())) {
      assertThat(file.headerValue("server")).startsWith("PostgreSQL 18");
      assertThat(file.headerValue("collation")).isEqualTo("C");
      assertThat(file.headerValue("encoding")).isEqualTo("UTF8");
    }
  }

  @Test
  void everyRowOfTheFixtureHasAFileId() {
    // 3 accounts, 2 topics, 3 follows, 3 interested, 4 posts, 2 memos, 2 badges, 2 twins
    assertThat(ridAliases).hasSize(21);
    assertThat(ridAliases.values()).contains("#1:0");
  }

  static Stream<Corpus.WriteCase> writes() throws IOException {
    return Corpus.writeCases(Corpus.readIdRuleWrites()).stream();
  }

  @Test
  void theWriteFileIsWellFormed() throws IOException {
    final CorpusFile file = Corpus.readIdRuleWrites();
    assertThat(file.columns).containsExactly("id", "status", "checks", "statement", "expected");
    assertThat(file.rows).isNotEmpty();
    final Set<String> ids = new HashSet<>();
    for (final Corpus.WriteCase c : Corpus.writeCases(file)) {
      assertThat(c.id()).as("id of %s", c.statement()).matches(ID);
      assertThat(c.id().replaceFirst("^err-", "")).startsWith("idrw-");
      assertThat(ids.add(c.id())).as("unique id %s", c.id()).isTrue();
      assertThat(c.row[Corpus.WriteCase.STATUS]).as("status of %s", c.id()).isIn("ok", "pending");
      assertThat(c.checks()).as("checks of %s", c.id()).isNotEmpty();
    }
    if (file.rows.stream().anyMatch(row -> new Corpus.WriteCase(row).hasOutcome())) {
      assertThat(file.headerValue("server")).startsWith("PostgreSQL 18");
      assertThat(file.headerValue("collation")).isEqualTo("C");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("writes")
  void theEngineDoesWhatTheServerDidOnAWrite(final Corpus.WriteCase write) {
    Assumptions.assumeTrue(write.hasOutcome(), "the generator has not run on this case");
    final WriteOutcome expected = WriteOutcome.fromJson(write.expected());
    final WriteOutcome actual = runWrite(write);
    final String difference = WriteOutcome.compare(expected, actual, Values.DEFAULT_TOLERANCE);
    if (write.pending()) {
      if (difference == null)
        throw new AssertionError(write.id() + " is pending, and the engine now does what the server did: flip its status to ok");
      System.out.println("PENDING " + write.id() + ": " + difference);
      Assumptions.assumeTrue(false, "pending: " + difference);
      return;
    }
    assertThat(difference).as("%s: %s (engine: %s)", write.id(), write.statement(), actual.result.describe()).isNull();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theEngineAnswersLikeTheServer(final Corpus.AnswerCase answer) throws IOException {
    final String collation = Corpus.readIdRule().headerValue("collation");
    assertThat(collation == null || "C".equals(collation)).as("the corpus is recorded under the C collation, the order of the evaluator")
        .isTrue();
    Assumptions.assumeTrue(answer.hasOutcome(), "the generator has not run on this case");

    final Outcome expected = Outcome.fromJson(answer.expected());
    final Outcome actual = run(answer.statement());
    final String difference = ResultComparator.compare(expected, actual, answer.ordered(), answer.tolerance());

    if (answer.pending()) {
      if (difference == null)
        throw new AssertionError(answer.id() + " is pending, and the engine now answers it like the server: flip its status to ok");
      System.out.println("PENDING " + answer.id() + ": " + difference);
      Assumptions.assumeTrue(false, "pending: " + difference);
      return;
    }
    assertThat(difference).as("%s: %s", answer.id(), answer.statement()).isNull();
  }

  // ---- the engine side ----

  private Outcome run(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      return Outcome.answer(table(resultSet));
    } catch (final Throwable t) {
      return Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
    }
  }

  private ResultTable table(final ResultSet resultSet) {
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
    return new ResultTable(labels, types, rows);
  }

  /** One write of {@code idrule-writes.tsv}: in a transaction that is rolled back, the statement and then each check. */
  private WriteOutcome runWrite(final Corpus.WriteCase write) {
    database.begin();
    try {
      Outcome result;
      try (final ResultSet resultSet = database.command("postgresql", write.statement())) {
        result = Outcome.answer(table(resultSet));
      } catch (final Throwable t) {
        result = Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
      }
      final List<Outcome> after = new ArrayList<>();
      for (final String check : write.checks())
        after.add(run(check));
      return new WriteOutcome(result, after);
    } finally {
      database.rollback();
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
}
