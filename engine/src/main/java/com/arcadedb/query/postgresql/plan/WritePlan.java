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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;

import java.util.List;

/**
 * The plan of an {@code INSERT}, an {@code UPDATE} or a {@code DELETE}: the lines {@code EXPLAIN} shows, and the run.
 *
 * <p>A statement is one transaction. When the caller has a transaction open, the statement runs in it and the caller commits or
 * rolls back (the database takes back what a failed statement wrote, so the transaction stays as it was before the statement).
 * Otherwise the statement opens one, commits it at the end and rolls it back on any error: a statement either changes every row
 * or none. The errors of the engine become SQLSTATEs through {@link WriteErrors}, the commit included, because that is where the
 * engine finds a unique key that two statements wrote at once and a concurrent update.
 */
public final class WritePlan {
  /** What a plan does once the transaction is there: the answer of the statement. */
  @FunctionalInterface
  interface Body {
    PostgreSQLResultSet run(Database database);
  }

  private final List<String>                       steps;
  private final List<PostgreSQLResultSet.Column> columns;
  private final Body                               body;

  WritePlan(final List<String> steps, final List<PostgreSQLResultSet.Column> columns, final Body body) {
    this.steps = List.copyOf(steps);
    this.columns = List.copyOf(columns);
    this.body = body;
  }

  /** The columns of the rows of the {@code RETURNING} list, empty for a statement without one: it answers with a tag alone. */
  public List<PostgreSQLResultSet.Column> columns() {
    return columns;
  }

  /** One line per step: how the rows are found, then what is done to them. */
  public List<String> steps() {
    return steps;
  }

  public PostgreSQLResultSet execute(final Database database) {
    final boolean own = !database.isTransactionActive();
    if (own)
      database.begin();
    try {
      final PostgreSQLResultSet result = body.run(database);
      // as PostgreSQL does at the end of a statement: the referential actions, and the foreign keys checked (fork issue #228)
      if (database instanceof DatabaseInternal internal)
        internal.getTransaction().checkForeignKeys();
      if (own)
        database.commit();
      return result;
    } catch (final RuntimeException e) {
      if (own && database.isTransactionActive()) {
        try {
          database.rollback();
        } catch (final RuntimeException suppressed) {
          e.addSuppressed(suppressed);
        }
      }
      throw WriteErrors.translate(e);
    }
  }
}
