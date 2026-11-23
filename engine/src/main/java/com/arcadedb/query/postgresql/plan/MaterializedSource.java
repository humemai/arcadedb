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

import com.arcadedb.query.postgresql.plan.join.BuildMemory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The rows of a derived table or of a common table expression: the answer of a query, computed the first time a reader opens it
 * and kept for every later read, so that a name that the statement reads twice, or the inner side of a join that is read for each
 * outer row, runs its query once. The kept rows are charged to a {@link BuildMemory} of their own: a query whose answer is over
 * the cap stops with SQLSTATE {@code 53200}, and a hint, before it answers from part of its rows.
 *
 * <p>One {@link Materialization} serves every reference to a name; each reference is a {@link MaterializedSource} with its own
 * relation name and column names.
 */
final class MaterializedSource implements RelationSource {

  /** The query and its kept rows, shared by the references of one common table expression. */
  static final class Materialization {
    private final QueryPlan plan;
    private final long      capBytes;
    private final String    holder;
    private List<Object[]>  rows;

    Materialization(final QueryPlan plan, final long capBytes, final String holder) {
      this.plan = plan;
      this.capBytes = capBytes;
      this.holder = holder;
    }

    QueryPlan plan() {
      return plan;
    }

    synchronized List<Object[]> rows() {
      if (rows == null)
        rows = plan.rows(new BuildMemory(capBytes, holder, "the rows are kept in memory and do not spill to disk; narrow "
            + "the query with a WHERE condition or a LIMIT, or raise the cap with the JVM property " + BuildMemory.CAP_PROPERTY
            + " (MiB)"));
      return rows;
    }
  }

  private final Materialization materialization;
  private final List<Field>     columns;
  private final String          description;

  MaterializedSource(final Materialization materialization, final List<Field> columns, final String description) {
    this.materialization = materialization;
    this.columns = List.copyOf(columns);
    this.description = description;
  }

  @Override
  public List<Field> columns() {
    return columns;
  }

  @Override
  public Iterator<Object[]> open() {
    return materialization.rows().iterator();
  }

  @Override
  public String describe() {
    return description;
  }

  @Override
  public List<String> describeTree() {
    final List<String> lines = new ArrayList<>();
    lines.add(description);
    for (final String line : materialization.plan().steps())
      lines.add("  " + line);
    return lines;
  }
}
