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

import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLReferenceException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;

import java.util.List;

/**
 * The query around an {@code EXISTS} subquery, as the parts of the subquery that are planned on their own see it: they cannot read
 * its columns, which only the terms taken out as keys do. They still have to know which names it shows, because a column that a
 * table of the subquery hides gives way to such a name (fork issue #157): where PostgreSQL reads the column of the query around,
 * the statement is refused here, and never answered with the hidden column.
 */
final class UnreadableScope implements ExpressionCompiler.Scope {
  private final ExpressionCompiler       around;
  /** The scope further out that the subquery does read, {@code null} when there is none. */
  private final ExpressionCompiler.Scope beyond;

  UnreadableScope(final ExpressionCompiler around, final ExpressionCompiler.Scope beyond) {
    this.around = around;
    this.beyond = beyond;
  }

  /** Whether no column of any query around can be read, so that the subquery is planned as a query of its own. */
  boolean readsNothing() {
    return beyond == null;
  }

  @Override
  public boolean shows(final Name column) {
    return around.shows(column) || beyond != null && beyond.shows(column);
  }

  @Override
  public Evaluable column(final Expression.ColumnRef reference) {
    final List<Name> qualifier = reference.qualifier();
    final Name column = reference.column();
    if (qualifier.isEmpty() && around.showsHere(column))
      throw new PostgreSQLFeatureNotSupportedException("Reading the column " + column.value() + " of the query around an EXISTS"
          + " subquery from a join condition of it, from a subquery inside it, or from a term beside another EXISTS is not"
          + " supported by the postgresql language; a column of the subquery is read with its table name before it ("
          + reference.position() + ")");
    if (beyond != null)
      return beyond.column(reference);
    // what the subquery's own scope reports for a name that it does not have
    throw qualifier.isEmpty() ? PostgreSQLReferenceException.undefinedColumn(column.value(), null)
        : PostgreSQLReferenceException.missingFromEntry(qualifier.get(qualifier.size() - 1).value());
  }
}
