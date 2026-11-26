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
import com.arcadedb.database.Document;
import com.arcadedb.database.Record;
import com.arcadedb.event.AfterRecordCreateListener;
import com.arcadedb.event.AfterRecordDeleteListener;
import com.arcadedb.event.AfterRecordUpdateListener;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.postgresql.PostgreSQLErrors;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.VertexType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * ArcadeDB's {@code postgresql} language on the side under test of the regression harness (fork issue #119), embedded.
 * <p>
 * Only the statements that are judged go through the language. The tables the reference has and this database has not, or has
 * otherwise, are copied from PostgreSQL through the engine's own API, never through the language: a type, its properties and
 * unique indexes, and its records. A copy is checked: its {@code information_schema} and its rows must equal the reference's, or
 * the table is blocked, with the reason, and a statement that uses it is not judged. A table with a constraint the copy does
 * not carry is blocked for writes only.
 * <p>
 * The language keeps its transaction block by thread, so everything runs on one thread, which also bounds the time of a
 * statement. The block follows the one of the reference. The harness changes the schema outside of a transaction: when a table
 * has to be made again while a block is open, the block is rolled back, the table is made, and a new block gets the rows that
 * the reference sees inside its own.
 */
final class RegressArcade implements RegressSubject {
  private static final String LANGUAGE = "postgresql";
  private static final int    MAX_ROWS = 200_000;
  /** The longest the harness waits for its own work on the engine thread. */
  private static final long   HARNESS_SECONDS = 600;

  private final long                timeoutSeconds;
  /** Tables that cannot be held, with the reason. */
  private final Map<String, String> blocked       = new LinkedHashMap<>();
  /** The state of the reference table at the time it was blocked: it is tried again when that changes. */
  private final Map<String, String> blockedState  = new HashMap<>();
  private final Map<String, String> writeLimits   = new HashMap<>();
  /** The definition of the reference table that this database holds. */
  private final Map<String, String> mirrored      = new HashMap<>();
  private final Set<String>         copied        = new HashSet<>();
  /** Tables whose records the open transaction changed. */
  private final Set<String>         inBlock       = new LinkedHashSet<>();
  /** Tables that the harness made again during the block of the reference: a rollback of the language leaves them. */
  private final Set<String>         rebuilt       = new LinkedHashSet<>();
  private final Set<String>         pending       = new LinkedHashSet<>();
  private final Set<String>         touched       = ConcurrentHashMap.newKeySet();
  private       ExecutorService     engine;
  private       ArcadeSide          side;
  private       boolean             everything    = true;

  RegressArcade(final long timeoutSeconds) {
    this.timeoutSeconds = timeoutSeconds;
    open();
  }

  private void open() {
    engine = Executors.newSingleThreadExecutor(task -> {
      final Thread thread = new Thread(task, "pgregress-engine");
      thread.setDaemon(true);
      return thread;
    });
    call(() -> {
      side = new ArcadeSide(RegressDatabase.NAME);
      database().getEvents().registerListener((AfterRecordCreateListener) this::note);
      database().getEvents().registerListener((AfterRecordUpdateListener) this::note);
      database().getEvents().registerListener((AfterRecordDeleteListener) this::note);
      return null;
    });
  }

  private Database database() {
    return side.database();
  }

  private void note(final Record record) {
    if (record instanceof Document document)
      touched.add(document.getTypeName());
  }

  /** Runs work of the harness on the engine thread. */
  private <T> T call(final Callable<T> work) {
    try {
      return engine.submit(work).get(HARNESS_SECONDS, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (final ExecutionException e) {
      throw new IllegalStateException("The harness failed on ArcadeDB: " + e.getCause(), e.getCause());
    } catch (final TimeoutException e) {
      throw new IllegalStateException("The harness got no answer from ArcadeDB", e);
    }
  }

  @Override
  public void connect(final String user) {
    call(() -> {
      if (database().isTransactionActive())
        database().rollback();
      return null;
    });
  }

  @Override
  public Answer execute(final RegressPsql.Send send, final Supplier<String> copyData) {
    final Set<String> before = call(this::typeNames);
    touched.clear();
    final Future<Answer> future = engine.submit(() -> run(send));
    final Answer answer;
    try {
      answer = future.get(timeoutSeconds, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (final ExecutionException e) {
      return Answer.crash(null, String.valueOf(e.getCause()));
    } catch (final TimeoutException e) {
      future.cancel(true);
      restart();
      return Answer.crash(null, "no answer after " + timeoutSeconds + " seconds");
    }
    final Set<String> after = call(this::typeNames);
    for (final String name : before)
      if (!after.contains(name))
        touched.add(name);
    for (final String name : after)
      if (!before.contains(name))
        touched.add(name);
    if (call(() -> database().isTransactionActive()))
      inBlock.addAll(touched);
    return answer;
  }

  /** A statement that does not end keeps its thread and its database: both are left behind, and new ones are filled again. */
  private void restart() {
    engine.shutdownNow();
    blocked.clear();
    blockedState.clear();
    writeLimits.clear();
    mirrored.clear();
    copied.clear();
    inBlock.clear();
    rebuilt.clear();
    pending.clear();
    touched.clear();
    everything = true;
    open();
  }

  private Answer run(final RegressPsql.Send send) {
    final Object[] parameters = send.parameters() == null ? new Object[0] : send.parameters().toArray();
    try (final ResultSet result = database().command(LANGUAGE, send.sql(), parameters)) {
      if (!(result instanceof PostgreSQLResultSet answer))
        return Answer.crash(null, "the answer is a " + result.getClass().getSimpleName());
      final List<String> keys = PostgreSQLResultSet.uniqueKeys(answer.columns());
      final List<List<Object>> rows = new ArrayList<>();
      while (answer.hasNext()) {
        final Result next = answer.next();
        final List<Object> row = new ArrayList<>(keys.size());
        for (final String key : keys) {
          final Object value = next.getProperty(key);
          row.add(Values.normalize(value));
        }
        rows.add(row);
      }
      if (!answer.returnsRows() || answer.columns().isEmpty() && rows.isEmpty())
        return new Answer(null, null, List.of(), answer.affectedRows(), null, null, false);
      final List<String> names = new ArrayList<>(keys.size());
      final List<String> types = new ArrayList<>(keys.size());
      for (final PostgreSQLResultSet.Column column : answer.columns()) {
        names.add(column.name());
        final String type = column.typeName();
        types.add(type.endsWith("[]") ? "_" + type.substring(0, type.length() - 2) : type);
      }
      return new Answer(names, types, rows, answer.affectedRows(), null, null, false);
    } catch (final Throwable t) {
      final String state = PostgreSQLErrors.sqlState(t);
      if (state == null || state.equals("XX000"))
        return Answer.crash(state, t.getClass().getSimpleName() + ": " + t.getMessage());
      return Answer.error(state, t.getMessage());
    }
  }

  private Set<String> typeNames() {
    final Set<String> names = new HashSet<>();
    for (final DocumentType type : database().getSchema().getTypes())
      names.add(type.getName());
    return names;
  }

  @Override
  public Set<String> touched() {
    return new LinkedHashSet<>(touched);
  }

  @Override
  public Shape shape(final RegressDatabase.Table table) {
    return call(() -> engineShape(table.key()));
  }

  private Shape engineShape(final String name) {
    if (!database().getSchema().existsType(name))
      return null;
    try {
      return Shape.read(this::query, RelationalCatalog.SCHEMA, name);
    } catch (final RuntimeException e) {
      return new Shape(List.of("information_schema cannot be read: " + e.getMessage()), List.of());
    }
  }

  private List<String[]> query(final String sql) {
    try (final ResultSet result = database().query(LANGUAGE, sql)) {
      final PostgreSQLResultSet answer = (PostgreSQLResultSet) result;
      final List<String> keys = PostgreSQLResultSet.uniqueKeys(answer.columns());
      final List<String[]> rows = new ArrayList<>();
      while (answer.hasNext()) {
        final Result next = answer.next();
        final String[] row = new String[keys.size()];
        for (int i = 0; i < row.length; i++) {
          final Object value = next.getProperty(keys.get(i));
          row[i] = value == null ? null : value.toString();
        }
        rows.add(row);
      }
      return rows;
    }
  }

  @Override
  public List<List<Object>> rows(final RegressDatabase.Table table) {
    return call(() -> engineRows(table));
  }

  private List<List<Object>> engineRows(final RegressDatabase.Table table) {
    if (!database().getSchema().existsType(table.key()))
      return null;
    final Set<String> columns = new HashSet<>();
    for (final RegressDatabase.Column column : table.columns())
      columns.add(column.name());
    final List<List<Object>> rows = new ArrayList<>();
    final Iterator<Record> records = database().iterateType(table.key(), false);
    while (records.hasNext()) {
      final Document document = records.next().asDocument();
      final List<Object> row = new ArrayList<>(columns.size());
      for (final RegressDatabase.Column column : table.columns()) {
        final Object value = document.get(column.name());
        row.add(Values.normalize(value));
      }
      // a property that is no column of the reference is a difference too
      for (final String property : document.getPropertyNames())
        if (!columns.contains(property) && !property.startsWith("@") && document.get(property) != null)
          row.add("also " + property);
      rows.add(row);
    }
    return rows;
  }

  @Override
  public void sync(final RegressDatabase reference, final Set<String> changed) {
    call(() -> {
      synchronize(reference, changed);
      return null;
    });
  }

  private void synchronize(final RegressDatabase reference, final Set<String> changed) {
    final char wanted = reference.transactionState();
    final Set<String> work = new LinkedHashSet<>(changed);
    work.addAll(touched);
    touched.clear();
    if (wanted == 'E') {
      // the reference can read nothing in a failed block: the tables are looked at when it has ended
      pending.addAll(work);
      fail();
      return;
    }
    work.addAll(pending);
    pending.clear();
    if (everything) {
      work.addAll(reference.tables().keySet());
      everything = false;
    }
    if (database().isTransactionActive()
        && (wanted == 'I' || failed() || work.stream().anyMatch(key -> needsSchemaChange(reference, key)))) {
      database().rollback();
      work.addAll(inBlock);
      inBlock.clear();
    }
    if (wanted == 'I') {
      inBlock.clear();
      rebuilt.clear();
    }
    final List<String> data = new ArrayList<>();
    for (final String key : work)
      if (needsSchemaChange(reference, key))
        rebuild(reference, key, wanted == 'T');
      else
        data.add(key);
    if (wanted == 'T')
      openBlock();
    for (final String key : data)
      refresh(reference, key, wanted == 'T');
  }

  private static String state(final RegressDatabase.Table table) {
    return table.signature() + " " + table.fingerprint();
  }

  private boolean needsSchemaChange(final RegressDatabase reference, final String key) {
    final RegressDatabase.Table table = reference.tables().get(key);
    if (table == null)
      return blocked.containsKey(key) || mirrored.containsKey(key) || database().getSchema().existsType(key);
    if (blocked.containsKey(key))
      return !state(table).equals(blockedState.get(key));
    return !database().getSchema().existsType(key) || !table.signature().equals(mirrored.get(key));
  }

  private void rebuild(final RegressDatabase reference, final String key, final boolean duringBlock) {
    final RegressDatabase.Table table = reference.tables().get(key);
    final Schema schema = database().getSchema();
    // a table that the language made itself stays when it equals the reference
    if (table != null && !blocked.containsKey(key) && !mirrored.containsKey(key) && schema.existsType(key)
        && difference(reference, table) == null) {
      mirrored.put(key, table.signature());
      return;
    }
    blocked.remove(key);
    blockedState.remove(key);
    writeLimits.remove(key);
    mirrored.remove(key);
    copied.remove(key);
    try {
      if (schema.existsType(key))
        schema.dropType(key);
      if (duringBlock)
        rebuilt.add(key);
      if (table == null)
        return;
      copy(reference, table);
      final String difference = difference(reference, table);
      if (difference != null)
        throw new RegressValues.Unsupported(difference);
      mirrored.put(key, table.signature());
      copied.add(key);
      if (!table.writeLimits().isEmpty())
        writeLimits.put(key, String.join(", ", table.writeLimits()));
    } catch (final RuntimeException e) {
      block(table, key, e);
      if (schema.existsType(key))
        schema.dropType(key);
    }
  }

  private void block(final RegressDatabase.Table table, final String key, final RuntimeException e) {
    blocked.put(key, e instanceof RegressValues.Unsupported ? e.getMessage()
        : e.getClass().getSimpleName() + ": " + e.getMessage());
    if (table != null)
      blockedState.put(key, state(table));
  }

  private void copy(final RegressDatabase reference, final RegressDatabase.Table table) {
    if (!table.key().equals(table.name()))
      throw new RegressValues.Unsupported("only tables of public and temporary tables are copied");
    if (table.nested())
      throw new RegressValues.Unsupported("inheritance and partitions are not copied");
    if (table.rowCount() < 0)
      throw new RegressValues.Unsupported("its rows cannot be read");
    if (table.rowCount() > MAX_ROWS)
      throw new RegressValues.Unsupported("it has more than " + MAX_ROWS + " rows");
    final List<RegressValues.Holder> holders = new ArrayList<>();
    for (final RegressDatabase.Column column : table.columns()) {
      final RegressValues.Holder holder = RegressValues.holder(column.type());
      if (holder == null)
        throw new RegressValues.Unsupported("column " + column.name() + " of type " + column.formatted() + " cannot be held");
      holders.add(holder);
    }
    final List<Object[]> values = values(reference, table);

    final VertexType type = database().getSchema().createVertexType(table.name());
    for (int i = 0; i < holders.size(); i++) {
      final RegressDatabase.Column column = table.columns().get(i);
      final RegressValues.Holder holder = holders.get(i);
      final Property property = holder.ofType() == null ? type.createProperty(column.name(), holder.type())
          : type.createProperty(column.name(), holder.type(), holder.ofType());
      if (column.notNull()) {
        property.setNotNull(true);
        property.setMandatory(true);
      }
    }
    for (final List<String> key : table.uniqueKeys())
      database().getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, table.name(), key.toArray(new String[0]));
    database().transaction(() -> insert(table, values));
  }

  private static List<Object[]> values(final RegressDatabase reference, final RegressDatabase.Table table) {
    final List<Object[]> values = new ArrayList<>();
    for (final String[] text : reference.textRows(table)) {
      final Object[] row = new Object[text.length];
      for (int i = 0; i < text.length; i++)
        row[i] = RegressValues.toEngine(table.columns().get(i).type(), text[i]);
      values.add(row);
    }
    return values;
  }

  private void insert(final RegressDatabase.Table table, final List<Object[]> rows) {
    for (final Object[] row : rows) {
      final MutableVertex vertex = database().newVertex(table.name());
      for (int i = 0; i < row.length; i++)
        if (row[i] != null)
          vertex.set(table.columns().get(i).name(), row[i]);
      vertex.save();
    }
  }

  /** @return how the table of this database differs from the reference's, {@code null} when it does not */
  private String difference(final RegressDatabase reference, final RegressDatabase.Table table) {
    final Shape expected = reference.shape(table);
    final Shape actual = engineShape(table.key());
    if (!expected.equals(actual))
      return "its information_schema differs: PostgreSQL " + expected + ", ArcadeDB " + actual;
    return RegressJudge.rows(reference.rows(table), engineRows(table));
  }

  /** Replaces the records of a table whose definition is the same when they differ. */
  private void refresh(final RegressDatabase reference, final String key, final boolean duringBlock) {
    final RegressDatabase.Table table = reference.tables().get(key);
    if (table == null || blocked.containsKey(key))
      return;
    try {
      if (RegressJudge.rows(reference.rows(table), engineRows(table)) == null)
        return;
      final List<Object[]> values = values(reference, table);
      database().transaction(() -> {
        final List<Record> old = new ArrayList<>();
        database().iterateType(table.name(), false).forEachRemaining(record -> old.add((Record) record));
        old.forEach(record -> record.asDocument().delete());
        insert(table, values);
      });
      copied.add(key);
      if (duringBlock)
        inBlock.add(key);
      final String difference = RegressJudge.rows(reference.rows(table), engineRows(table));
      if (difference != null)
        throw new RegressValues.Unsupported(difference);
      touched.clear();
    } catch (final RuntimeException e) {
      block(table, key, e);
    }
  }

  private void command(final String sql) {
    try (final ResultSet result = database().command(LANGUAGE, sql)) {
      // a statement about the session: nothing to read
    }
  }

  private boolean failed() {
    if (!database().isTransactionActive())
      return false;
    try (final ResultSet result = database().query(LANGUAGE, "SELECT 1")) {
      return false;
    } catch (final RuntimeException e) {
      return "25P02".equals(PostgreSQLErrors.sqlState(e));
    }
  }

  /** An open block of the language that has not failed. */
  private void openBlock() {
    if (database().isTransactionActive()) {
      if (!failed())
        return;
      database().rollback();
    }
    command("BEGIN");
  }

  /** A failed block of the language, as the reference has one. */
  private void fail() {
    if (!database().isTransactionActive())
      command("BEGIN");
    if (!failed())
      try {
        command("SELECT 1/0");
      } catch (final RuntimeException expected) {
        // the error is what fails the block
      }
  }

  @Override
  public String blocked(final String table, final boolean write) {
    final String reason = blocked.get(table);
    if (reason != null)
      return reason;
    if (write && writeLimits.containsKey(table))
      return "the copy does not carry " + writeLimits.get(table);
    return null;
  }

  @Override
  public boolean copied(final String table) {
    return copied.contains(table);
  }

  @Override
  public Set<String> rebuiltByHarness() {
    return rebuilt;
  }

  @Override
  public void close() {
    try {
      call(() -> {
        if (database().isTransactionActive())
          database().rollback();
        try {
          side.close();
        } catch (final IOException e) {
          System.out.println("pgregress: the temporary database was not removed: " + e);
        }
        return null;
      });
    } finally {
      engine.shutdownNow();
    }
  }
}
