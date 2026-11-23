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
package com.arcadedb.query.postgresql.plan.join;

import com.arcadedb.log.LogManager;
import com.arcadedb.query.postgresql.plan.RelationSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The warning for a join that no edge, link or index supports (fork issue #9). A join between two tables with nothing behind
 * it usually means that a relationship is missing from the model, and the maintainer asked for a warning "only the first time
 * if the same query is executed many times, so we avoid overflowing the logs". The join still runs and the rows are the same.
 *
 * <p><b>One warning per statement.</b> A statement is its fingerprint ({@code AstPrinter.fingerprint}): the parsed text with
 * every literal replaced by {@code ?}, whitespace and comments gone, together with the name of the database. So a client that
 * writes {@code WHERE p.age > 30} and then {@code WHERE p.age > 40} warns once, and the log never holds a literal of the data.
 * The first time a fingerprint arrives the warning is written to the logger of the engine at {@code WARNING}; after that it is
 * remembered and nothing is written. Checking and remembering a fingerprint is one step under a lock, so exactly one of two threads
 * that run the same new statement at the same time sees it as new and logs the warning.
 *
 * <p><b>Bounded.</b> The remembered fingerprints are a set of at most {@link #DEFAULT_CAP} entries (10,000), kept in order of
 * last use: a statement that runs again is moved to the newest end, and when a new fingerprint would be the 10,001st the one used
 * longest ago is forgotten. A hot statement therefore stays remembered however many other statements run, and a statement
 * forgotten after 10,000 others come in warns again if it returns. Many distinct statements cannot grow the set.
 *
 * <p>The set is shared by every database of the process ({@link #shared()}); the name of the database is part of the key.
 * {@code EXPLAIN} shows the same sentence in its plan and never writes it to the log, so it does not use up the one warning of
 * the statement that is run later.
 */
public final class JoinWarnings {
  /** The most fingerprints remembered at once. */
  public static final int DEFAULT_CAP = 10_000;

  private static final JoinWarnings SHARED = new JoinWarnings(DEFAULT_CAP);

  private final int                 cap;
  private final Map<String, Boolean> seen;

  /**
   * @param cap the most fingerprints remembered at once, at least 1
   */
  public JoinWarnings(final int cap) {
    if (cap < 1)
      throw new IllegalArgumentException("The cap of remembered statements is at least 1, not " + cap);
    this.cap = cap;
    this.seen = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
        return size() > JoinWarnings.this.cap;
      }
    };
  }

  /** The set of the process, which the {@code postgresql} language writes its warnings through. */
  public static JoinWarnings shared() {
    return SHARED;
  }

  /**
   * The sentences of the joins below a source that nothing supports, in the order the statement writes the joins: the same
   * sentences that the plan lists as {@code WARNING} lines.
   */
  public static List<String> of(final RelationSource source) {
    final List<String> sentences = new ArrayList<>();
    collect(source, sentences);
    return sentences;
  }

  private static void collect(final RelationSource source, final List<String> sentences) {
    if (source instanceof FilteredSource filtered)
      collect(filtered.inner(), sentences);
    else if (source instanceof JoinSource join) {
      collect(join.left(), sentences);
      collect(join.right(), sentences);
      if (join.warning() != null)
        sentences.add(join.warning());
    }
  }

  /**
   * Remembers a statement and tells whether it is new: true for the first time, false for every later time until it is
   * forgotten. Nothing is logged.
   *
   * @param database    the name of the database the statement runs in
   * @param fingerprint the statement as text with its literals replaced
   */
  public synchronized boolean first(final String database, final String fingerprint) {
    // put in access order moves a known key to the newest end, so a statement that keeps running is never the one evicted
    return seen.put(database + '\0' + fingerprint, Boolean.TRUE) == null;
  }

  /**
   * Writes one {@code WARNING} for a statement the first time it is seen, with the sentences of all its unsupported joins and
   * the fingerprint of the statement, and nothing for a statement that was warned about already.
   *
   * @return true when the warning was written
   */
  public boolean warn(final String database, final String fingerprint, final List<String> sentences) {
    if (sentences.isEmpty())
      return false;
    if (!first(database, fingerprint))
      return false;
    LogManager.instance().log(JoinWarnings.class, Level.WARNING, "%s",
        "In database '" + database + "': " + String.join(" ", sentences) + " Statement: " + fingerprint);
    return true;
  }

  /** The number of statements remembered now, never more than the cap. */
  public synchronized int size() {
    return seen.size();
  }

  /** Forgets every statement. */
  public synchronized void clear() {
    seen.clear();
  }
}
