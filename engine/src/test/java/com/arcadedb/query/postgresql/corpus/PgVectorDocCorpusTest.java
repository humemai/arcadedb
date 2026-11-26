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
import com.arcadedb.query.postgresql.VecSmallFixture;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the statements of {@code pgvector-doc.tsv} through the {@code postgresql} language on the {@code vec-small} fixture and
 * compares them with the cells written from the definitions of the pgvector operators, by the rules of {@link ResultComparator}.
 * The cases are flagged {@code doc}: the file header names the section of the README that each cell comes from. The parity run
 * compares the same cells with a pgvector server ({@code PgVectorParityIT} of the e2e module).
 */
class PgVectorDocCorpusTest extends TestHelper {

  @Override
  protected void beginTest() {
    VecSmallFixture.createSchema(database);
    VecSmallFixture.load(database);
  }

  static Stream<Corpus.AnswerCase> cases() throws IOException {
    return Corpus.answerCases(CorpusFile.readResource("/postgresql/corpus/pgvector-doc.tsv")).stream();
  }

  @Test
  void everyCaseIsFlaggedDocAndHasItsCells() throws IOException {
    final List<Corpus.AnswerCase> all = cases().toList();
    assertThat(all).hasSizeGreaterThanOrEqualTo(8);
    for (final Corpus.AnswerCase answer : all) {
      assertThat(answer.row[Corpus.AnswerCase.STATUS]).as(answer.id()).isEqualTo("doc");
      assertThat(answer.hasOutcome()).as(answer.id()).isTrue();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theEngineAnswersLikeTheDefinition(final Corpus.AnswerCase answer) {
    final Outcome expected = Outcome.fromJson(answer.expected());
    final Outcome actual = run(answer.statement());
    assertThat(ResultComparator.compare(expected, actual, answer.ordered(), answer.tolerance())).as("%s: %s", answer.id(),
        answer.statement()).isNull();
  }

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
          row.add(Values.normalize(result.getProperty(key)));
        rows.add(row);
      }
      return Outcome.answer(new ResultTable(labels, types, rows));
    } catch (final Throwable t) {
      return Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
    }
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
