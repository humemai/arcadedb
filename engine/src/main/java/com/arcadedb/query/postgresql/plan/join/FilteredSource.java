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

import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.plan.RelationSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A source with a condition applied to its rows before anything above reads them: a {@code WHERE} term pushed below a join (see
 * {@link Pushdown}) or an {@code ON} term that only one side of a join mentions. The condition is the conjunction of the pushed
 * terms: a row passes when every term is {@code true}, and {@code NULL} and {@code false} drop it, as in {@code WHERE}.
 */
public final class FilteredSource implements RelationSource {
  private final RelationSource inner;
  private final Evaluable      condition;
  private final String         relations;
  private final String         text;
  private final long           estimatedRows;

  /**
   * @param relations the relation names the condition is pushed to, for the plan line
   * @param text      the condition as SQL text, for the plan line
   */
  FilteredSource(final RelationSource inner, final Evaluable condition, final String relations, final String text,
      final long estimatedRows) {
    this.inner = inner;
    this.condition = condition;
    this.relations = relations;
    this.text = text;
    this.estimatedRows = estimatedRows;
  }

  RelationSource inner() {
    return inner;
  }

  Evaluable condition() {
    return condition;
  }

  long estimatedRows() {
    return estimatedRows;
  }

  @Override
  public List<Field> columns() {
    return inner.columns();
  }

  @Override
  public String describe() {
    return inner.describe();
  }

  @Override
  public List<String> describeTree() {
    final List<String> lines = new ArrayList<>(inner.describeTree());
    lines.add("FILTER pushed to " + relations + ": " + text);
    return lines;
  }

  @Override
  public Iterator<Object[]> open() {
    final Iterator<Object[]> rows = inner.open();
    return new Iterator<>() {
      private Object[] next;

      @Override
      public boolean hasNext() {
        while (next == null && rows.hasNext()) {
          final Object[] candidate = rows.next();
          if (Boolean.TRUE.equals(condition.eval(candidate)))
            next = candidate;
        }
        return next != null;
      }

      @Override
      public Object[] next() {
        if (!hasNext())
          throw new NoSuchElementException();
        final Object[] result = next;
        next = null;
        return result;
      }
    };
  }
}
