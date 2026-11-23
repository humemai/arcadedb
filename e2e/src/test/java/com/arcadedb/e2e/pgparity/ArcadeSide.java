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
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  /** Real record ids of this database to the ids the fixture file gives the same rows, see {@link #setRidAliases(Map)}. */
  private       Map<String, String> ridAliases = Map.of();

  ArcadeSide() throws IOException {
    directory = Files.createTempDirectory("pgparity-arcadedb");
    factory = new DatabaseFactory(directory.resolve("small").toString());
    database = factory.create();
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  /**
   * The corpus is committed, so its record ids must not depend on which bucket a record landed in: the fixture file of the
   * server numbers them {@code #<table number>:<row number>}, and the engine's real record ids are translated to those, in every
   * text cell that is exactly one of them, before a result is compared.
   */
  void setRidAliases(final Map<String, String> aliases) {
    this.ridAliases = Map.copyOf(aliases);
  }

  private Object alias(final Object value) {
    if (value instanceof String text) {
      final String mapped = ridAliases.get(text);
      return mapped != null ? mapped : value;
    }
    return value;
  }

  /**
   * Runs one statement in {@code language} and keeps everything it answered, or the SQLSTATE of its refusal. The engine reports
   * no column types yet, so the types of the table are {@code null}; a result without rows reports no labels either. The
   * {@code postgresql} language answers a {@code PostgreSQLResultSet}: its labels are the ones PostgreSQL would send, duplicates
   * included ({@code SELECT p.name, c.name} has two {@code name}), while a row is a map whose second duplicate has a suffix, so
   * the labels come from {@code columns()} and the values are read by the unique key of each column.
   */
  Outcome run(final String language, final String sql) {
    try (final ResultSet rs = database.query(language, sql)) {
      final List<String> described = rs instanceof PostgreSQLResultSet answer
          ? answer.columns().stream().map(PostgreSQLResultSet.Column::name).toList() : null;
      final List<String> describedKeys = rs instanceof PostgreSQLResultSet answer
          ? PostgreSQLResultSet.uniqueKeys(answer.columns()) : null;
      List<String> labels = null;
      List<String> keys = null;
      final List<List<Object>> rows = new ArrayList<>();
      while (rs.hasNext()) {
        final Result result = rs.next();
        if (labels == null) {
          labels = described != null ? described : new ArrayList<>(result.getPropertyNames());
          keys = describedKeys != null ? describedKeys : labels;
        }
        final List<Object> row = new ArrayList<>(keys.size());
        for (final String key : keys) {
          final Object value = result.getProperty(key);
          row.add(alias(Values.normalize(value)));
        }
        rows.add(row);
      }
      return Outcome.answer(new ResultTable(labels, null, rows));
    } catch (final Throwable t) {
      return Outcome.refused(sqlState(t), String.valueOf(t.getMessage()));
    }
  }

  /** What ArcadeDB's own {@code sql} language did with a statement: the table it answered, or the exception it threw. */
  record NativeRun(ResultTable table, Throwable error) {
  }

  /**
   * ArcadeDB's own {@code sql} language on the same database and the same data, for the capability matrix: the statement text
   * goes in unchanged (nothing is added or rewritten) through {@code database.query("sql", statement)}, embedded, so the row
   * limit that the HTTP layer adds does not exist. A row of this language is a map, so two columns with one name are one
   * column, and a record that {@code SELECT *} returns also carries metadata names that start with {@code @} ({@code @rid},
   * {@code @type}): those are not columns the statement selected and are left out, so rows compare on the selected columns
   * only. A column that a row does not hold, because the record has no such property, is {@code null} in that row. Cells are
   * normalised as in {@link #run}, record ids translated to the fixture file's.
   */
  NativeRun runNative(final String statement) {
    try (final ResultSet rs = database.query("sql", statement)) {
      final List<Map<String, Object>> maps = new ArrayList<>();
      final Set<String> labels = new LinkedHashSet<>();
      while (rs.hasNext()) {
        final Result result = rs.next();
        final Map<String, Object> map = new LinkedHashMap<>();
        for (final String name : result.getPropertyNames())
          if (!name.startsWith("@")) {
            map.put(name, alias(Values.normalize(result.getProperty(name))));
            labels.add(name);
          }
        maps.add(map);
      }
      final List<String> labelList = maps.isEmpty() ? null : new ArrayList<>(labels);
      final List<List<Object>> rows = new ArrayList<>(maps.size());
      for (final Map<String, Object> map : maps) {
        final List<Object> row = new ArrayList<>(labelList.size());
        for (final String label : labelList)
          row.add(map.get(label));
        rows.add(row);
      }
      return new NativeRun(new ResultTable(labelList, null, rows), null);
    } catch (final Throwable t) {
      return new NativeRun(null, t);
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
