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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultTable;
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ArcadeDB, embedded, holding the {@code small} fixture of the prototype. The fixture is {@code SmallFixture} of the engine's
 * test classes, reached through the engine test-jar that the {@code e2e} module has as a test dependency, so the schema and the
 * rows of the engine tests and of this harness are one build and cannot drift.
 */
final class ArcadeSide implements AutoCloseable {
  private final Path            directory;
  private final DatabaseFactory factory;
  private final Database        database;

  ArcadeSide() throws IOException {
    directory = Files.createTempDirectory("pgparity-arcadedb");
    factory = new DatabaseFactory(directory.resolve("small").toString());
    database = factory.create();
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  /**
   * Runs one statement in {@code language} and keeps everything it answered, or the SQLSTATE of its refusal. The engine reports
   * no column types yet, so the types of the table are {@code null}; a result without rows reports no labels either.
   */
  Outcome run(final String language, final String sql) {
    try (final ResultSet rs = database.query(language, sql)) {
      List<String> labels = null;
      final List<List<Object>> rows = new ArrayList<>();
      while (rs.hasNext()) {
        final Result result = rs.next();
        if (labels == null)
          labels = new ArrayList<>(result.getPropertyNames());
        final List<Object> row = new ArrayList<>(labels.size());
        for (final String label : labels) {
          final Object value = result.getProperty(label);
          row.add(Values.normalize(value));
        }
        rows.add(row);
      }
      return Outcome.answer(new ResultTable(labels, null, rows));
    } catch (final Throwable t) {
      return Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
    }
  }

  /**
   * The SQLSTATE an engine exception carries: the first exception of the cause chain that has a {@code getSqlState()} method
   * (found by name, so that the exceptions of the grammar and of the evaluator need no registration here), else {@code
   * unknown}.
   */
  static String sqlState(final Throwable error) {
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

  @Override
  public void close() throws IOException {
    try {
      database.drop();
    } finally {
      factory.close();
      try (final Stream<Path> files = Files.walk(directory)) {
        files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }
}
