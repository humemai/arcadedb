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
import java.util.function.Supplier;

/**
 * <b>The seam of the join step (S5).</b> A relation source is what a {@code FROM} clause is, to everything after it: the filter,
 * the select list, the sort and the limit read rows from a source and know nothing of tables. {@link TableSource} is the source
 * of one table. A join is a source of two sources: {@code JoinSource.columns()} is the columns of the left source followed by
 * those of the right one, and its rows are the left row followed by the right row, so the scope that resolves names
 * ({@code RelationScope}) needs no change, because every column carries the name of its relation.
 *
 * <p>{@link SelectPlanner#planFrom} turns a {@code FROM} clause without a join into one source: {@link TableSource} for a
 * table. A join, or a list of tables, is planned by {@code plan.join.JoinPlanner} into a {@code JoinSource} over the sources of
 * its inputs (one {@link SelectPlanner#planTable} source per table), with the {@code WHERE} terms that can be applied below the
 * join already applied. Nothing after that changes.
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
   * @param hidden   whether the column is readable by its name only: {@code *} leaves it out, and an unqualified name stands for
   *                 another column of that name before it (the record id of a table made by {@code CREATE TABLE})
   * @param listed   whether the catalog views show the column, that is, whether the table has it in PostgreSQL's terms: one that is
   *                 not listed gives way to a column of its name that a query around a subquery has (fork issue #157)
   */
  record Field(String relation, String name, PgType type, String typeName, boolean hidden, boolean listed) {
    public Field(final String relation, final String name, final PgType type, final String typeName) {
      this(relation, name, type, typeName, false, true);
    }
  }

  /**
   * One column that a name can stand for: {@code name}, or {@code *}. A source that reads one table has one per column. The
   * result of {@code a JOIN b USING (x)} has the merged column {@code x} first, then the columns of {@code a} and of {@code b}
   * without their own {@code x}, which stay readable as {@code a.x} and {@code b.x} only.
   *
   * @param name     the name the column has for an unqualified reference
   * @param index    the position of the value in the row
   * @param type     the type the column has for the query that reads it (the common type of the two columns of a merged column)
   * @param typeName the name of the type as the client sees it
   */
  record Output(String name, int index, PgType type, String typeName) {
  }

  /** The columns of every row, in order. */
  List<Field> columns();

  /** The columns that an unqualified name or {@code *} reads, or {@code null} for every column of {@link #columns()} in order. */
  default List<Output> outputs() {
    return null;
  }

  /** Starts a read: the rows, each time from the beginning. */
  Iterator<Object[]> open();

  /** One line of the plan: how this source reads its rows. */
  String describe();

  /**
   * The lines of the plan of this source: {@link #describe()} for a source that reads one table, and for a join its own line
   * followed by the lines of its two inputs, indented by two spaces.
   */
  default List<String> describeTree() {
    return List.of(describe());
  }

  /**
   * The lines of {@link #describeTree()}, to be built when they are read. A statement is planned on every call and its plan text is
   * read only by {@code EXPLAIN} and by whoever asks a result set for its plan, so a table, a join and a filter build none before
   * then, and what they return holds no row and no run-time state. This default, for every other source, builds the lines now.
   */
  default Supplier<List<String>> planLines() {
    final List<String> lines = describeTree();
    return () -> lines;
  }

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
