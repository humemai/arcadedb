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
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The columns of the query around a correlated subquery, as the scope of the subquery sees them: each is a constant of the type
 * that the outer query gives it. The subquery is planned twice. The first time ({@link #discovering}) the constants are placeholders
 * and the planner only learns which outer columns the subquery reads; the second time ({@link #bind}) they hold the values of one
 * row of the outer query, so the plan is the one for that row: an {@code outer.k} in {@code WHERE t.k = outer.k} is a key like a
 * parameter, and the table is read through an index on {@code k} when it has one.
 */
final class OuterBindings implements ExpressionCompiler.Scope {
  private final ExpressionCompiler            outer;
  private final Map<String, Evaluable>        reads;
  private final Object[]                      outerRow;

  private OuterBindings(final ExpressionCompiler outer, final Map<String, Evaluable> reads, final Object[] outerRow) {
    this.outer = outer;
    this.reads = reads;
    this.outerRow = outerRow;
  }

  static OuterBindings discovering(final ExpressionCompiler outer) {
    return new OuterBindings(outer, new LinkedHashMap<>(), null);
  }

  /** The scope for one row of the outer query: only the columns that {@link #discovering} saw are read, from that row. */
  OuterBindings bind(final Object[] row) {
    return new OuterBindings(outer, reads, row);
  }

  boolean isEmpty() {
    return reads.isEmpty();
  }

  /** Whether the subquery read this column from the outer query (the first planning saw it resolve there). */
  boolean reads(final Expression.ColumnRef reference) {
    return reads.containsKey(key(reference));
  }

  /** How to read each outer column that the subquery uses, from a row of the outer query, in the order they were first used. */
  List<Evaluable> readers() {
    return new ArrayList<>(reads.values());
  }

  private static String key(final Expression.ColumnRef reference) {
    return reference.qualifier().stream().map(name -> name.quoted() ? '"' + name.value() + '"' : name.value())
        .collect(Collectors.joining(".", "", ".")) + (reference.column().quoted() ? '"' + reference.column().value() + '"'
        : reference.column().value());
  }

  @Override
  public boolean shows(final Name column) {
    return outer.shows(column);
  }

  @Override
  public String recordIdColumn(final Expression expression) {
    return outer.recordIdColumn(expression);
  }

  @Override
  public Evaluable column(final Expression.ColumnRef reference) {
    final String key = key(reference);
    Evaluable read = reads.get(key);
    if (read == null) {
      if (outerRow != null)
        throw new PostgreSQLFeatureNotSupportedException(
            "A correlated subquery whose plan depends on the values of the outer row is not supported");
      read = outer.resolveOutput(outer.compile(reference));
      reads.put(key, read);
    }
    return new Evaluable.Constant(read.type(), outerRow == null ? null : read.eval(outerRow));
  }
}
