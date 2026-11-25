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
import com.arcadedb.query.postgresql.ValuesFixture;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The values corpus ({@code values-writes.tsv}, recorded from the real PostgreSQL 18 server) on the {@code values} fixture: the
 * writes of {@code jsonb}, arrays and {@code bytea} (fork issue #29), run and compared as {@link WriteCorpusTest} does.
 */
class ValuesCorpusTest extends TestHelper {
  @Override
  protected void beginTest() {
    ValuesFixture.createSchema(database);
    ValuesFixture.load(database);
  }

  static Stream<Corpus.WriteCase> cases() throws IOException {
    return Corpus.writeCases(Corpus.readValuesWrites()).stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theEngineDoesWhatTheServerDid(final Corpus.WriteCase write) throws IOException {
    Assumptions.assumeTrue(write.hasOutcome(), "the generator has not run on this case");
    final WriteOutcome expected = WriteOutcome.fromJson(write.expected());
    database.begin();
    try {
      Outcome result;
      try (final ResultSet resultSet = database.command("postgresql", write.statement())) {
        result = Outcome.answer(table(resultSet));
      } catch (final RuntimeException e) {
        result = Outcome.refused(sqlState(e), String.valueOf(e.getMessage()));
      }
      final List<Outcome> after = new ArrayList<>();
      for (final String check : write.checks())
        try (final ResultSet resultSet = database.query("postgresql", check)) {
          after.add(Outcome.answer(table(resultSet)));
        }
      final String difference = WriteOutcome.compare(expected, new WriteOutcome(result, after), Values.DEFAULT_TOLERANCE);
      assertThat(difference).as("%s: %s (engine: %s)", write.id(), write.statement(), result.describe()).isNull();
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
    while (answer.hasNext()) {
      final Result row = answer.next();
      final List<Object> cells = new ArrayList<>(keys.size());
      for (final String key : keys)
        cells.add(Values.normalize(row.getProperty(key)));
      rows.add(cells);
    }
    return new ResultTable(labels, types, rows);
  }

  private static String sqlState(final Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      try {
        final Object state = cause.getClass().getMethod("getSQLState").invoke(cause);
        if (state != null)
          return state.toString();
      } catch (final ReflectiveOperationException ignored) {
        // no SQLSTATE on this one: look at its cause
      }
    }
    return "XX000";
  }
}
