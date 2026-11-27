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
package com.arcadedb.postgres;

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.database.TransactionRetryPolicy;
import com.arcadedb.exception.TimeoutException;
import com.arcadedb.query.QueryEngine;
import com.arcadedb.query.RunningQuery;
import com.arcadedb.query.postgresql.PostgreSQLQueryEngine;
import com.arcadedb.query.postgresql.plan.WriteErrors;
import com.arcadedb.query.sql.executor.CommandTimeoutOverride;
import com.arcadedb.utility.RetryBackoff;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A write of the {@code postgresql} language that is a transaction of its own is run again by the server when another
 * transaction got there first (fork issue #239). PostgreSQL makes the second writer of a row wait; the engine refuses the
 * commit that comes last, and a client that sends one statement in autocommit expects neither an error nor a retry loop of
 * its own.
 * <p>
 * Each run is a new transaction, which {@link #run} begins, commits, and rolls back when the run fails. Nothing of a run
 * may reach the client before its commit, so a run only holds its answer. The runs stop at
 * {@link GlobalConfiguration#POSTGRESQL_AUTOCOMMIT_RETRIES}, at the statement timeout, and at a cancel. What is worth
 * another run, how often, and how long to wait are the engine's own rules ({@link TransactionRetryPolicy},
 * {@link RetryBackoff}). The loop of {@code Database.transaction} is not used: it reads no cancel and no timeout, and it
 * runs every duplicated key again, where PostgreSQL answers {@code 23505}.
 */
final class PostgresAutocommitRetry {
  /** The most input that is looked ahead at: a Sync, an Execute, and the Close messages between them are far smaller. */
  static final int LOOK_AHEAD = 8 * 1024;

  private PostgresAutocommitRetry() {
  }

  /**
   * Whether a statement of the {@code postgresql} language that is a whole transaction may be run by {@link #run}: an
   * {@code INSERT}, an {@code UPDATE}, or a {@code DELETE}, on a database where a run that is lost leaves nothing behind
   * that the next one repeats. A trigger can do work that a rollback does not take back, so a database with one runs every
   * statement once. A setting of {@code 0} retries turns this off.
   */
  static boolean mayRun(final DatabaseInternal database, final String query) {
    if (database.getConfiguration().getValueAsInteger(GlobalConfiguration.POSTGRESQL_AUTOCOMMIT_RETRIES) <= 0
        || database.getSchema().getTriggers().length > 0)
      return false;
    try {
      final QueryEngine.AnalyzedQuery analyzed = database.getQueryEngine(PostgresQueryLanguages.POSTGRESQL).analyze(query);
      return !analyzed.isIdempotent() && !analyzed.isTransactionControl() && !analyzed.isDDL();
    } catch (final RuntimeException refused) {
      // the statement is refused again, and answered, when it runs
      return false;
    }
  }

  /**
   * Runs {@code attempt} in a transaction and commits it, and again in a new one for as long as it fails on a conflict.
   * The thread has no transaction when this is called, and none when it returns or throws.
   *
   * @param query   the statement that {@code attempt} runs
   * @param attempt one run of the statement, which holds its answer and sends nothing
   *
   * @return what the run that was committed answered
   *
   * @throws RuntimeException the error of the last run, as the language reports it: {@code 40001} for a conflict
   * @throws TimeoutException when the statement timeout passed between two runs
   */
  static <T> T run(final DatabaseInternal database, final String query, final Supplier<T> attempt) {
    final ContextConfiguration configuration = database.getConfiguration();
    final int retries = configuration.getValueAsInteger(GlobalConfiguration.POSTGRESQL_AUTOCOMMIT_RETRIES);
    final int delayBase = configuration.getValueAsInteger(GlobalConfiguration.TX_RETRY_DELAY_BASE);
    final int delayCap = configuration.getValueAsInteger(GlobalConfiguration.TX_RETRY_DELAY);
    final TransactionRetryPolicy policy = TransactionRetryPolicy.onThisNode();
    final long start = System.nanoTime();

    for (int retry = 0; ; retry++) {
      database.begin();
      final TransactionContext transaction = database.getTransaction();
      // opened by this server and not by a client's BEGIN
      transaction.setImplicit(true);
      final long commits = transaction.getCommitCount();
      try {
        final T answer = attempt.get();
        database.commit();
        return answer;
      } catch (final RuntimeException e) {
        if (database.isTransactionActive())
          try {
            database.rollback();
          } catch (final RuntimeException suppressed) {
            e.addSuppressed(suppressed);
            throw WriteErrors.translate(e);
          }
        final RuntimeException conflict = ((PostgreSQLQueryEngine) database.getQueryEngine(PostgresQueryLanguages.POSTGRESQL))
            .conflictOf(query, e);
        if (conflict == null || retry >= retries || TransactionContext.isPartiallyCommitted(transaction, commits)
            || !policy.mayRetry(conflict))
          // most conflicts are found by the commit, whose error the language has not translated yet
          throw WriteErrors.translate(e);

        RetryBackoff.sleep(retry, delayBase, delayCap);
        if (Thread.currentThread().isInterrupted())
          throw WriteErrors.translate(e);
        final RunningQuery running = RunningQuery.current();
        if (running != null)
          running.checkNotTerminated("the statement");
        // read after a run, which publishes the statement_timeout of the session
        final long timeout = CommandTimeoutOverride.effectiveTimeout(database);
        // without a cause: the answer is the timeout (57014), not the conflict of the run before it
        if (timeout > 0 && System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(timeout))
          throw new TimeoutException("canceling statement due to statement timeout: it was run " + (retry + 1)
              + " time(s) and every run met a write conflict");
      }
    }
  }

  /**
   * Whether the statement that is being handled is the last of its extended-protocol run: only a Sync follows in the
   * input, behind any Close messages, which end a statement or a portal and run nothing. It waits for those messages,
   * which a client that expects an answer has to send: PostgreSQL answers at a Sync or a Flush. Nothing is consumed, the
   * input is put back as it was.
   *
   * @param input     the input of the connection, at the start of a message; it has to support {@code mark}
   * @param described the portal that a Describe is about to run, whose own Execute may come before the Sync; {@code null}
   *                  when the statement is being executed
   */
  static boolean onlySyncFollows(final InputStream input, final String described) throws IOException {
    input.mark(LOOK_AHEAD);
    try {
      final DataInputStream messages = new DataInputStream(input);
      String portal = described;
      int used = 0;
      while (true) {
        final int type = messages.read();
        if (type == 'S')
          return true;
        if (type != 'C' && (type != 'E' || portal == null))
          return false;
        final int length = messages.readInt();
        // the message, and the type of the one after it, have to stay within what can be put back
        if (length < 4 || length > LOOK_AHEAD - used - 2)
          return false;
        used += 1 + length;
        final byte[] body = new byte[length - 4];
        messages.readFully(body);
        if (type == 'E') {
          if (!portal.equals(firstString(body)))
            return false;
          portal = null;
        }
      }
    } finally {
      input.reset();
    }
  }

  /** The zero-terminated string that a message body starts with. */
  private static String firstString(final byte[] body) {
    int end = 0;
    while (end < body.length && body[end] != 0)
      end++;
    return new String(body, 0, end, DatabaseFactory.getDefaultCharset());
  }
}
