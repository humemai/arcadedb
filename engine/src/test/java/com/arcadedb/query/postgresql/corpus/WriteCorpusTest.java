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
 * Runs every write of the write corpus ({@code writes.tsv}, recorded from the real PostgreSQL 18 server) through the
 * {@code postgresql} language on the {@code small} fixture and compares the answer of the statement and the tables it touched
 * with what the server did (fork issue #29). Each case runs in a transaction that is rolled back, as the harness runs it on the
 * server, so the cases are independent. {@code pending} cases are tried and skipped; one that now answers like the server fails
 * until its flag is flipped.
 */
class WriteCorpusTest extends TestHelper {
  private Map<String, String> ridAliases;

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
    ridAliases = ridAliases();
  }

  static Stream<Corpus.WriteCase> cases() throws IOException {
    return Corpus.writeCases(Corpus.readWrites()).stream();
  }

  @Test
  void theCorpusHasTheCasesOfEveryStatementKind() throws IOException {
    final List<Corpus.WriteCase> cases = Corpus.writeCases(Corpus.readWrites());
    assertThat(cases).extracting(Corpus.WriteCase::statement).anyMatch(s -> s.startsWith("INSERT")).anyMatch(s -> s.startsWith("UPDATE"))
        .anyMatch(s -> s.startsWith("DELETE"));
    assertThat(cases).filteredOn(Corpus.WriteCase::expectsRefusal).hasSizeGreaterThan(10);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void theEngineDoesWhatTheServerDid(final Corpus.WriteCase write) throws IOException {
    Assumptions.assumeTrue(write.hasOutcome(), "the generator has not run on this case");
    final WriteOutcome expected = WriteOutcome.fromJson(write.expected());
    final WriteOutcome actual = run(write);
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

  // ---- the engine side ----

  private WriteOutcome run(final Corpus.WriteCase write) {
    database.begin();
    try {
      Outcome result;
      try (final ResultSet resultSet = database.command("postgresql", write.statement())) {
        result = Outcome.answer(table(resultSet));
      } catch (final Throwable t) {
        result = Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
      }
      final List<Outcome> after = new ArrayList<>();
      for (final String check : write.checks()) {
        try (final ResultSet resultSet = database.query("postgresql", check)) {
          after.add(Outcome.answer(table(resultSet)));
        } catch (final Throwable t) {
          after.add(Outcome.refused(sqlState(t), String.valueOf(t.getMessage())));
        }
      }
      return new WriteOutcome(result, after);
    } finally {
      database.rollback();
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

  private Object alias(final Object value) {
    if (value instanceof String text) {
      final String mapped = ridAliases.get(text);
      return mapped != null ? mapped : value;
    }
    return value;
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
