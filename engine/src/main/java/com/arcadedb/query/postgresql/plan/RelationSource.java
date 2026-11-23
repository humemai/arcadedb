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

import com.arcadedb.query.postgresql.eval.PgType;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * <b>The seam of the join step (S5).</b> A relation source is what a {@code FROM} clause is, to everything after it: the filter,
 * the select list, the sort and the limit read rows from a source and know nothing of tables. {@link TableSource} is the source
 * of one table. A join is a source of two sources: {@code JoinSource.columns()} is the columns of the left source followed by
 * those of the right one, and its rows are the left row followed by the right row, so the scope that resolves names
 * ({@code RelationScope}) needs no change, because every column carries the name of its relation.
 *
 * <p>Where S5 plugs in: {@link SelectPlanner#planFrom} turns the {@code FROM} items into one source. For a single table it
 * returns a {@link TableSource}; for a join or a list of tables it refuses with SQLSTATE {@code 0A000} today. S5 replaces that
 * branch with a join source built from {@link SelectPlanner#planTable} (one source per table) and the {@code ON} condition,
 * which it compiles with the {@code RelationScope} of the combined columns. Nothing after {@code planFrom} changes.
 *
 * <p>A row is an array with one value per column, in the canonical Java classes of {@link PgType}.
 */
public interface RelationSource {

  /**
   * One column of a source.
   *
   * @param relation the name the statement uses for the relation: its alias, or the table name when there is no alias; empty
   *                 for a source that has no name ({@code SELECT 1})
   * @param name     the column name
   * @param type     the type the evaluator computes with
   * @param typeName the name of the type as the client sees it ({@code int4}, {@code _text})
   */
  record Field(String relation, String name, PgType type, String typeName) {
  }

  /** The columns of every row, in order. */
  List<Field> columns();

  /** Starts a read: the rows, each time from the beginning. */
  Iterator<Object[]> open();

  /** One line of the plan: how this source reads its rows. */
  String describe();

  /** The source of {@code SELECT 1}: no column and one row. */
  static RelationSource singleRow() {
    return new RelationSource() {
      @Override
      public List<Field> columns() {
        return List.of();
      }

      @Override
      public Iterator<Object[]> open() {
        return new Iterator<>() {
          private boolean done;

          @Override
          public boolean hasNext() {
            return !done;
          }

          @Override
          public Object[] next() {
            if (done)
              throw new NoSuchElementException();
            done = true;
            return new Object[0];
          }
        };
      }

      @Override
      public String describe() {
        return "CONSTANT ROW";
      }
    };
  }
}
