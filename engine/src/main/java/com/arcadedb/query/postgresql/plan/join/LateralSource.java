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
 * An inner or left join whose right input reads the row of the left one (fork issue #243): for each row of the left input, the
 * rows of the item for it, then the terms of the right input, then the join condition. A left join keeps a left row that has no
 * partner, with {@code NULL} in the columns of the right input. Nothing is kept but the rows of the item for one left row.
 */
final class LateralSource implements RelationSource {
  private final RelationSource left;
  private final RelationSource right;
  private final LateralRows    items;
  /** The terms that read the columns of the right input only, over its rows; {@code null} for none. */
  private final Evaluable      filter;
  /** The join condition over the joined row; {@code null} for none. */
  private final Evaluable      condition;
  private final boolean        keepsLeft;
  private final List<Field>    columns;
  private final List<Output>   outputs;

  LateralSource(final RelationSource left, final RelationSource right, final LateralRows items, final Evaluable filter,
      final Evaluable condition, final boolean keepsLeft, final List<Field> columns, final List<Output> outputs) {
    this.left = left;
    this.right = right;
    this.items = items;
    this.filter = filter;
    this.condition = condition;
    this.keepsLeft = keepsLeft;
    this.columns = columns;
    this.outputs = outputs;
  }

  RelationSource left() {
    return left;
  }

  @Override
  public List<Field> columns() {
    return columns;
  }

  @Override
  public List<Output> outputs() {
    return outputs;
  }

  @Override
  public Iterator<Object[]> open() {
    final Iterator<Object[]> outer = left.open();
    final int width = columns.size() - right.columns().size();
    return new Iterator<>() {
      private Object[]           current;
      private Iterator<Object[]> inner;
      private boolean            matched;
      private Object[]           next;

      @Override
      public boolean hasNext() {
        while (next == null) {
          if (inner == null) {
            if (!outer.hasNext())
              return false;
            current = outer.next();
            inner = items.rows(current);
            matched = false;
          }
          while (next == null && inner.hasNext()) {
            final Object[] found = inner.next();
            if (filter != null && !Boolean.TRUE.equals(filter.eval(found)))
              continue;
            final Object[] row = joined(found);
            if (condition == null || Boolean.TRUE.equals(condition.eval(row))) {
              matched = true;
              next = row;
            }
          }
          if (next == null) {
            inner = null;
            if (!matched && keepsLeft)
              next = joined(null);
          }
        }
        return true;
      }

      @Override
      public Object[] next() {
        if (!hasNext())
          throw new NoSuchElementException();
        final Object[] row = next;
        next = null;
        return row;
      }

      private Object[] joined(final Object[] found) {
        final Object[] row = new Object[columns.size()];
        System.arraycopy(current, 0, row, 0, width);
        if (found != null)
          System.arraycopy(found, 0, row, width, found.length);
        return row;
      }
    };
  }

  @Override
  public String describe() {
    return (keepsLeft ? "LEFT" : "INNER") + " LATERAL JOIN";
  }

  @Override
  public List<String> describeTree() {
    final List<String> lines = new ArrayList<>();
    lines.add(describe());
    for (final String line : left.describeTree())
      lines.add("  " + line);
    for (final String line : right.describeTree())
      lines.add("  " + line);
    return lines;
  }
}
