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
import com.arcadedb.query.postgresql.GeneratedColumnsFixture;
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
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The corpus of the generated columns of fork issue #78 ({@code generated.tsv}, recorded from the real PostgreSQL 18 server on
 * {@code generated-columns.sql}, whose columns are real {@code GENERATED ALWAYS AS IDENTITY} columns) through the
 * {@code postgresql} language of the engine on the tables of {@code GeneratedColumnsFixture}. The rules are those of
 * {@code AnswerCorpusTest}: {@code ok} must answer like the server, an {@code err-} statement must be refused with the same
 * SQLSTATE, and a {@code pending} statement is tried and skipped.
 */
class GeneratedColumnsCorpusTest extends TestHelper {
  private static final Pattern ID       = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final Pattern SQLSTATE = Pattern.compile("[0-9A-Z]{5}");

  @Override
  protected void beginTest() {
    GeneratedColumnsFixture.createSchema(database);
  }

  static Stream<Corpus.AnswerCase> cases() throws IOException {
    return Corpus.answerCases(Corpus.readGenerated()).stream();
  }

  @Test
  void theFileIsWellFormed() throws IOException {
    final CorpusFile file = Corpus.readGenerated();
    assertThat(file.columns).containsExactly("id", "order", "tol", "status", "statement", "expected");
    assertThat(file.rows).isNotEmpty();
    final Set<String> ids = new HashSet<>();
    for (final Corpus.AnswerCase c : Corpus.answerCases(file)) {
      assertThat(c.id()).as("id of %s", c.statement()).matches(ID);
      assertThat(c.id().replaceFirst("^err-", "")).as("id of %s", c.statement()).startsWith("gen-");
      assertThat(ids.add(c.id())).as("unique id %s", c.id()).isTrue();
      assertThat(c.row[Corpus.AnswerCase.ORDER]).as("order of %s", c.id()).isIn("ordered", "multiset");
      assertThat(c.row[Corpus.AnswerCase.STATUS]).as("status of %s", c.id()).isIn("ok", "pending");
      if (c.ordered())
        assertThat(c.statement().toUpperCase()).as("ordered %s has an ORDER BY", c.id()).contains("ORDER BY");
      if (!c.hasOutcome())
        continue;
      final Outcome outcome = Outcome.fromJson(c.expected());
      assertThat(outcome.isRefusal()).as("%s: a refusal exactly for an err- id", c.id()).isEqualTo(c.expectsRefusal());
      if (outcome.isRefusal())
        assertThat(outcome.sqlState).as("SQLSTATE of %s", c.id()).matches(SQLSTATE);
    }
    if (file.rows.stream().anyMatch(row -> new Corpus.AnswerCase(row).hasOutcome())) {
      assertThat(file.headerValue("server")).startsWith("PostgreSQL 18");
      assertThat(file.headerValue("collation")).isEqualTo("C");
      assertThat(file.headerValue("encoding")).isEqualTo("UTF8");
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theEngineAnswersLikeTheServer(final Corpus.AnswerCase answer) {
    Assumptions.assumeTrue(answer.hasOutcome(), "the generator has not run on this case");

    final Outcome expected = Outcome.fromJson(answer.expected());
    final Outcome actual = run(answer.statement());
    final String difference = ResultComparator.compare(expected, actual, answer.ordered(), answer.tolerance());

    if (answer.pending()) {
      if (difference == null)
        throw new AssertionError(answer.id() + " is pending, and the engine now answers it like the server: flip its status to ok");
      Assumptions.assumeTrue(false, "pending: " + difference);
      return;
    }
    assertThat(difference).as("%s: %s", answer.id(), answer.statement()).isNull();
  }

  private Outcome run(final String sql) {
    database.begin();
    try (final ResultSet resultSet = database.command("postgresql", sql)) {
      return Outcome.answer(table(resultSet));
    } catch (final Throwable t) {
      return Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
    } finally {
      database.rollback();
    }
  }

  private static ResultTable table(final ResultSet resultSet) {
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
        row.add(Values.normalize(result.getProperty(key)));
      rows.add(row);
    }
    return new ResultTable(labels, types, rows);
  }

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
