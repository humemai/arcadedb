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
package com.arcadedb.query.postgresql.catalog;

import com.arcadedb.database.BasicDatabase;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.exception.SchemaException;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The counters behind the {@code serial} and identity columns of the {@code postgresql} language (fork issue #221). The engine
 * has no sequence, so each such column has one record of the document type {@value #TYPE_NAME}: the last value given and the
 * largest that the column holds. The default of its property is {@code postgresql_nextval('key')}, which the engine reads for
 * every record that is created without the property, so an insert of {@code sql} or openCypher that leaves the column out takes
 * the next value too. The text is no function of the {@code sql} language: only a default of a property is read as one.
 * <p>
 * A value is taken in a transaction of its own that commits at once. A rollback of the transaction that asked does not give it
 * back, so values increase and have gaps. In one server process a lock for each database puts those commits in a row, so two
 * sessions take different values and neither fails because of the other.
 */
public final class Sequences {
  /** The document type of the counters. The relational view does not show it as a table. */
  public static final String TYPE_NAME = "_postgresql_sequences";

  private static final String  KEY       = "key";
  private static final String  LAST      = "last";
  private static final String  MAXIMUM   = "maximum";
  private static final String  FUNCTION  = "postgresql_nextval(";
  private static final Pattern DEFAULT   = Pattern.compile("postgresql_nextval\\('([0-9a-f]{32})'\\)");
  private static final int     ATTEMPTS  = 10;

  /** By database path: held while a counter is read and committed. */
  private static final ConcurrentHashMap<String, ReentrantLock> LOCKS    = new ConcurrentHashMap<>();
  /** Held while the type of the counters is made. A thread that holds a database lock never asks for it. */
  private static final Object                                   NEW_TYPE = new Object();

  private Sequences() {
  }

  /** The default of a property that the counter {@code key} fills. */
  public static String defaultDefinition(final String key) {
    return FUNCTION + "'" + key + "')";
  }

  /** The key of the counter that the definition of a default reads, {@code null} for any other definition. */
  public static String keyOf(final Object definition) {
    if (!(definition instanceof String text) || !text.startsWith(FUNCTION))
      return null;
    final Matcher matcher = DEFAULT.matcher(text);
    return matcher.matches() ? matcher.group(1) : null;
  }

  /**
   * A new counter, which gives 1 first, committed at once. Makes the type of the counters when there is none.
   *
   * @param table   the table of the column, kept to tell the records apart; nothing reads it
   * @param column  the column, as {@code table}
   * @param maximum the largest value of the column
   *
   * @return the key of the counter
   */
  public static String create(final Database database, final String table, final String column, final long maximum) {
    makeType(database);
    final String key = UUID.randomUUID().toString().replace("-", "");
    committed(database, () -> database.newDocument(TYPE_NAME).set(KEY, key).set(LAST, 0L).set(MAXIMUM, maximum)
        .set("table_name", table).set("column_name", column).save());
    return key;
  }

  /** Removes the counters of a table that is taken back. */
  public static void drop(final Database database, final Collection<String> keys) {
    if (keys.isEmpty() || !database.getSchema().existsType(TYPE_NAME))
      return;
    committed(database, () -> {
      for (final String key : keys) {
        final IndexCursor found = database.lookupByKey(TYPE_NAME, KEY, key);
        if (found.hasNext())
          database.deleteRecord(found.next().asDocument());
      }
    });
  }

  /**
   * The next value of the counter, committed before it is given.
   *
   * @throws PostgreSQLEvaluationException with SQLSTATE {@code 2200H} when the counter has given the largest value of its column
   * @throws SchemaException               when the record of the counter is gone
   */
  public static long next(final Database database, final String key) {
    final long[] value = new long[1];
    committed(database, () -> {
      final MutableDocument counter = counter(database, key).modify();
      final long last = counter.getLong(LAST);
      final long maximum = counter.getLong(MAXIMUM);
      if (last >= maximum)
        throw new PostgreSQLEvaluationException("2200H", "nextval: reached maximum value of the sequence of column "
            + counter.getString("table_name") + "." + counter.getString("column_name") + " (" + maximum + ")");
      value[0] = last + 1;
      counter.set(LAST, value[0]).save();
    });
    return value[0];
  }

  private static Document counter(final Database database, final String key) {
    if (database.getSchema().existsType(TYPE_NAME)) {
      final IndexCursor found = database.lookupByKey(TYPE_NAME, KEY, key);
      if (found.hasNext())
        return found.next().asDocument();
    }
    throw new SchemaException(
        "The counter " + key + " of a serial or identity column is missing from the type " + TYPE_NAME);
  }

  private static void makeType(final Database database) {
    synchronized (NEW_TYPE) {
      final Schema schema = database.getSchema();
      final DocumentType type = schema.existsType(TYPE_NAME) ? schema.getType(TYPE_NAME)
          : schema.buildDocumentType().withName(TYPE_NAME).withTotalBuckets(1).create();
      type.getOrCreateProperty(KEY, Type.STRING);
      type.getOrCreateProperty(LAST, Type.LONG);
      type.getOrCreateProperty(MAXIMUM, Type.LONG);
      type.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, KEY);
    }
  }

  /**
   * Runs {@code work} in a transaction of its own, nested in the one of the caller when there is one, and commits it. The read
   * lock of the database is taken before the lock of the counters, so that a schema change that waits for the database cannot
   * come between a thread that holds the one and asks for the other.
   */
  private static void committed(final Database database, final BasicDatabase.TransactionScope work) {
    database.executeInReadLock(() -> {
      final ReentrantLock lock = LOCKS.computeIfAbsent(database.getDatabasePath(), path -> new ReentrantLock());
      lock.lock();
      try {
        // one level more than the caller has, also where the caller used all that a thread may nest
        final DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContextIfExists(database.getDatabasePath());
        final int allowed = context == null ? 0 : context.getMaxNested();
        final boolean full = context != null && database.getNestedTransactions() >= allowed;
        if (full)
          context.setMaxNested(allowed + 1);
        try {
          database.transaction(work, false, ATTEMPTS);
        } finally {
          if (full)
            context.setMaxNested(allowed);
        }
      } finally {
        lock.unlock();
      }
      return null;
    });
  }
}
