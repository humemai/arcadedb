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

import com.arcadedb.query.sql.executor.ExecutionPlan;
import com.arcadedb.query.sql.executor.ExecutionStep;
import com.arcadedb.query.sql.executor.InternalResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The answer of a {@code postgresql} query: ArcadeDB's rows, and the description of the columns the rows of a {@code Result}
 * cannot carry. PostgreSQL allows two output columns with one name ({@code SELECT p.name, c.name}), a {@code Result} is a map,
 * so the row gives the second and later duplicate a suffix ({@code name}, {@code name_1}, {@code name_2}) while {@link #columns()}
 * keeps the names as PostgreSQL labels them, in order, each with its PostgreSQL type name ({@code int4}, {@code varchar},
 * {@code _text}). The plan is {@link #getExecutionPlan()}: one step per line of {@link Plan#prettyPrint(int, int)}.
 */
public final class PostgreSQLResultSet extends InternalResultSet {

  /** The label and the type of one output column; the label is the name PostgreSQL would send. */
  public record Column(String name, String typeName) {
  }

  /** The plan of a query as text: how the table is read, then the steps applied to its rows. */
  public static final class Plan implements ExecutionPlan {
    private final Supplier<List<String>> later;
    private       List<String>           lines;

    public Plan(final List<String> lines) {
      this.later = null;
      this.lines = List.copyOf(lines);
    }

    /** A plan whose lines are built the first time they are read. */
    public Plan(final Supplier<List<String>> later) {
      this.later = later;
    }

    public synchronized List<String> lines() {
      if (lines == null)
        lines = List.copyOf(later.get());
      return lines;
    }

    @Override
    public List<ExecutionStep> getSteps() {
      final List<ExecutionStep> steps = new ArrayList<>();
      for (final String line : lines())
        steps.add(new Step(line));
      return steps;
    }

    @Override
    public String prettyPrint(final int depth, final int indent) {
      final String margin = " ".repeat(depth * indent);
      final StringBuilder out = new StringBuilder();
      for (final String line : lines()) {
        if (!out.isEmpty())
          out.append('\n');
        out.append(margin).append("+ ").append(line);
      }
      return out.toString();
    }

    @Override
    public Result toResult() {
      final ResultInternal result = new ResultInternal();
      result.setProperty("type", "PostgreSQLExecutionPlan");
      result.setProperty("prettyPrint", prettyPrint(0, 2));
      return result;
    }

    @Override
    public String toString() {
      return prettyPrint(0, 2);
    }
  }

  /** One line of a plan as a step. */
  private record Step(String description) implements ExecutionStep {
    @Override
    public String getName() {
      final int space = description.indexOf(' ');
      return space < 0 ? description : description.substring(0, space);
    }

    @Override
    public String getType() {
      return "PostgreSQLStep";
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public List<ExecutionStep> getSubSteps() {
      return List.of();
    }
  }

  private final List<Column> columns;
  private final List<String> warnings;
  private       long         affectedRows = -1;
  private       boolean      returnsRows  = true;

  public PostgreSQLResultSet(final List<Column> columns, final Plan plan) {
    this(columns, plan, List.of());
  }

  public PostgreSQLResultSet(final List<Column> columns, final Plan plan, final List<String> warnings) {
    this.columns = List.copyOf(columns);
    this.warnings = List.copyOf(warnings);
    setPlan(plan);
  }

  /**
   * The warnings of the statement: one sentence for each join that no edge, link or index supports (fork issue #9), the same
   * sentences that the plan lists as {@code WARNING} lines. Empty for a statement that has none.
   */
  public List<String> warnings() {
    return warnings;
  }

  /**
   * The rows an {@code INSERT}, {@code UPDATE} or {@code DELETE} changed, the number in the command tag of PostgreSQL; {@code -1}
   * for the answer of a {@code SELECT}. A write without {@code RETURNING} answers one row with this number in the column
   * {@code count}; with {@code RETURNING} the rows are the returned ones and this is their number.
   */
  public long affectedRows() {
    return affectedRows;
  }

  public PostgreSQLResultSet withAffectedRows(final long affected) {
    this.affectedRows = affected;
    return this;
  }

  /**
   * False for the answer of an {@code INSERT}, {@code UPDATE} or {@code DELETE} without {@code RETURNING}: its one row
   * {@code count} only repeats {@link #affectedRows()}, and PostgreSQL answers such a statement with a command tag and no row.
   * True for every other answer.
   */
  public boolean returnsRows() {
    return returnsRows;
  }

  /** Marks the answer as a write without {@code RETURNING}, see {@link #returnsRows()}. */
  public PostgreSQLResultSet rowless() {
    this.returnsRows = false;
    return this;
  }

  /** The output columns in order: the labels PostgreSQL sends and their PostgreSQL type names. */
  public List<Column> columns() {
    return columns;
  }

  /**
   * The keys of a row for these labels: a label that is already used gets {@code _1}, {@code _2} and so on until it is free.
   */
  public static List<String> uniqueKeys(final List<Column> columns) {
    final List<String> keys = new ArrayList<>(columns.size());
    for (final Column column : columns) {
      String key = column.name();
      int suffix = 0;
      while (keys.contains(key))
        key = column.name() + "_" + (++suffix);
      keys.add(key);
    }
    return keys;
  }
}
