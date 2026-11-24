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
package com.arcadedb.query.postgresql;

import com.arcadedb.query.postgresql.ast.Statement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parsed statements of one database, by their text (fork issue #76). Parsing is a function of the text alone: the tree is made
 * of immutable records and holds no name that is bound, so one tree serves every call with that text, whatever the schema or the
 * parameters are; names are bound and expressions typed by the planner on every call, against the schema as it is then. A text that
 * is not accepted is never kept, so a refusal is the same refusal each time.
 * <p>
 * A least recently used cache of at most {@code size} texts ({@code arcadedb.postgresql.statementCache}, 0 keeps none). A text longer
 * than {@link #MAX_TEXT_LENGTH} is not kept: such a statement is a batch {@code INSERT} that is rarely run twice, and it would hold
 * the memory of its rows for as long as it stays.
 */
final class StatementCache {
  static final int MAX_TEXT_LENGTH = 4096;

  private final int                      size;
  private final Map<String, Statement>   entries;
  private long                           hits;
  private long                           misses;

  StatementCache(final int size) {
    this.size = Math.max(0, size);
    this.entries = new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<String, Statement> eldest) {
        return size() > StatementCache.this.size;
      }
    };
  }

  /** The tree of a text kept before, or {@code null}. */
  synchronized Statement get(final String text) {
    if (size == 0)
      return null;
    final Statement statement = entries.get(text);
    if (statement == null)
      ++misses;
    else
      ++hits;
    return statement;
  }

  /** Keeps the tree of a text that was accepted, unless the cache is off or the text is too long. */
  synchronized void put(final String text, final Statement statement) {
    if (size > 0 && text.length() <= MAX_TEXT_LENGTH)
      entries.put(text, statement);
  }

  synchronized int count() {
    return entries.size();
  }

  synchronized long hits() {
    return hits;
  }

  synchronized long misses() {
    return misses;
  }
}
