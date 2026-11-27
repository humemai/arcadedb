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
import com.arcadedb.query.postgresql.ast.SetOperationStatement;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.plan.join.BuildMemory;
import com.arcadedb.query.sql.executor.ResultInternal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A planned {@code UNION}, {@code INTERSECT} or {@code EXCEPT}: the rows of two other plans, converted to the result type of each
 * column ({@link SetTypes}), combined by the rule of the operation, then sorted, offset and limited as a whole. The column names
 * are those of the left operand, as in PostgreSQL.
 *
 * <p>Rows are compared the way {@code DISTINCT} compares them: numbers by value, {@code NULL} equal to {@code NULL}. Without
 * {@code ALL} the result has no duplicate; with it the multiplicities count: {@code UNION ALL} keeps every row of both,
 * {@code INTERSECT ALL} keeps a row as often as the smaller of its two counts, {@code EXCEPT ALL} as often as the left count
 * minus the right count. Everything is kept in memory, and charged to a {@link BuildMemory}: over the cap the operation stops with
 * SQLSTATE {@code 53200}.
 */
final class SetOperationPlan implements QueryPlan {
  private final SetOperationStatement.Kind     kind;
  private final boolean                        all;
  private final QueryPlan                      left;
  private final QueryPlan                      right;
  private final List<PgType>                   leftTypes;
  private final List<PgType>                   rightTypes;
  private final List<PgType>                   types;
  private final List<PostgreSQLResultSet.Column> columns;
  private final List<SelectPlan.SortKey>       sortKeys;
  private final Evaluable                      limit;
  private final Evaluable                      offset;
  private final List<String>                   steps;
  private final long                           capBytes;

  SetOperationPlan(final SetOperationStatement.Kind kind, final boolean all, final QueryPlan left, final QueryPlan right,
      final List<PgType> types, final List<PostgreSQLResultSet.Column> columns, final List<SelectPlan.SortKey> sortKeys,
      final Evaluable limit, final Evaluable offset, final long capBytes) {
    this.kind = kind;
    this.all = all;
    this.left = left;
    this.right = right;
    this.leftTypes = List.copyOf(left.outputTypes());
    this.rightTypes = List.copyOf(right.outputTypes());
    this.types = List.copyOf(types);
    this.columns = List.copyOf(columns);
    this.sortKeys = List.copyOf(sortKeys);
    this.limit = limit;
    this.offset = offset;
    this.capBytes = capBytes;

    final List<String> lines = new ArrayList<>();
    lines.add(kind + (all ? " ALL" : "") + " of 2 inputs");
    for (final String line : left.steps())
      lines.add("  " + line);
    for (final String line : right.steps())
      lines.add("  " + line);
    if (!this.sortKeys.isEmpty())
      lines.add("SORT " + this.sortKeys.size() + " key(s)");
    if (offset != null)
      lines.add("OFFSET");
    if (limit != null)
      lines.add("LIMIT");
    this.steps = List.copyOf(lines);
  }

  @Override
  public List<PostgreSQLResultSet.Column> columns() {
    return columns;
  }

  @Override
  public List<String> steps() {
    return steps;
  }

  @Override
  public List<String> warnings() {
    final List<String> warnings = new ArrayList<>(left.warnings());
    warnings.addAll(right.warnings());
    return warnings;
  }

  @Override
  public List<PgType> outputTypes() {
    return types;
  }

  @Override
  public boolean holdsRecordIds(final int index) {
    return left.holdsRecordIds(index) || right.holdsRecordIds(index);
  }

  @Override
  public void inferParameter(final int index, final PgType type) {
    left.inferParameter(index, type);
    right.inferParameter(index, type);
  }

  @Override
  public PostgreSQLResultSet execute(final Database database) {
    final List<Object[]> rows = rows(null);
    final List<String> keys = PostgreSQLResultSet.uniqueKeys(columns);
    final PostgreSQLResultSet result = new PostgreSQLResultSet(columns, new PostgreSQLResultSet.Plan(steps), warnings());
    for (final Object[] values : rows) {
      final ResultInternal row = new ResultInternal(database);
      for (int i = 0; i < keys.size(); i++)
        row.setProperty(keys.get(i), values[i]);
      result.add(row);
    }
    return result;
  }

  @Override
  public List<Object[]> rows(final BuildMemory memory) {
    final BuildMemory used = memory != null ? memory
        : new BuildMemory(capBytes, "a " + kind + " set operation", "the rows are kept in memory and do not spill to disk; narrow "
            + "the operands with a WHERE condition or a LIMIT, or raise the cap with the JVM property " + BuildMemory.CAP_PROPERTY
            + " (MiB)");
    final List<Object[]> leftRows = converted(left.rows(used), leftTypes);
    final List<Object[]> rightRows = converted(right.rows(used), rightTypes);

    List<Object[]> out = switch (kind) {
      case UNION -> union(leftRows, rightRows, used);
      case INTERSECT -> intersect(leftRows, rightRows, used);
      case EXCEPT -> except(leftRows, rightRows, used);
    };

    if (!sortKeys.isEmpty())
      out.sort((a, b) -> {
        for (final SelectPlan.SortKey key : sortKeys) {
          final int order = key.ordering().compare(a[key.position()], b[key.position()]);
          if (order != 0)
            return order;
        }
        return 0;
      });

    final Long limitValue = count(limit, "LIMIT", "2201W");
    final Long offsetValue = count(offset, "OFFSET", "2201X");
    final int from = (int) Math.min(offsetValue == null ? 0L : offsetValue, (long) out.size());
    final int to = limitValue == null || limitValue >= out.size() - from ? out.size() : from + (int) (long) limitValue;
    return from == 0 && to == out.size() ? out : new ArrayList<>(out.subList(from, to));
  }

  /** The rows with every value in the result type of its column; the arrays are the operand's own, so it is done in place. */
  private List<Object[]> converted(final List<Object[]> rows, final List<PgType> from) {
    boolean same = true;
    for (int i = 0; i < from.size(); i++)
      same &= from.get(i) == types.get(i);
    if (same)
      return rows;
    for (final Object[] row : rows)
      for (int i = 0; i < row.length; i++)
        row[i] = SetTypes.convert(row[i], from.get(i), types.get(i));
    return rows;
  }

  private static List<Object> keyOf(final Object[] row) {
    return SelectPlan.distinctKey(row, row.length);
  }

  private List<Object[]> union(final List<Object[]> leftRows, final List<Object[]> rightRows, final BuildMemory used) {
    final List<Object[]> out = new ArrayList<>(leftRows.size() + rightRows.size());
    if (all) {
      out.addAll(leftRows);
      out.addAll(rightRows);
      return out;
    }
    final Set<List<Object>> seen = new HashSet<>();
    for (final List<Object[]> side : List.of(leftRows, rightRows))
      for (final Object[] row : side)
        if (seen.add(keyOf(row))) {
          used.charge(row);
          out.add(row);
        }
    return out;
  }

  /** How many times each row of the right operand is there; a row is charged once, for its entry. */
  private static Map<List<Object>, long[]> counts(final List<Object[]> rows, final BuildMemory used) {
    final Map<List<Object>, long[]> counts = new HashMap<>();
    for (final Object[] row : rows) {
      final List<Object> key = keyOf(row);
      long[] count = counts.get(key);
      if (count == null) {
        used.charge(row);
        count = new long[1];
        counts.put(key, count);
      }
      count[0]++;
    }
    return counts;
  }

  private List<Object[]> intersect(final List<Object[]> leftRows, final List<Object[]> rightRows, final BuildMemory used) {
    final Map<List<Object>, long[]> counts = counts(rightRows, used);
    final List<Object[]> out = new ArrayList<>();
    for (final Object[] row : leftRows) {
      final long[] count = counts.get(keyOf(row));
      if (count == null)
        continue;
      if (all) {
        // as often as the smaller of the two counts
        if (count[0] > 0) {
          count[0]--;
          out.add(row);
        }
      } else if (count[0] >= 0) {
        // once: -1 marks a row that is already in
        count[0] = -1;
        out.add(row);
      }
    }
    return out;
  }

  private List<Object[]> except(final List<Object[]> leftRows, final List<Object[]> rightRows, final BuildMemory used) {
    final Map<List<Object>, long[]> counts = counts(rightRows, used);
    final List<Object[]> out = new ArrayList<>();
    final Set<List<Object>> emitted = new HashSet<>();
    for (final Object[] row : leftRows) {
      final List<Object> key = keyOf(row);
      final long[] count = counts.get(key);
      if (all) {
        // each row of the right operand takes away one equal row of the left
        if (count != null && count[0] > 0)
          count[0]--;
        else
          out.add(row);
      } else if (count == null && emitted.add(key)) {
        used.charge(row);
        out.add(row);
      }
    }
    return out;
  }

  private static Long count(final Evaluable expression, final String clause, final String negativeSqlState) {
    if (expression == null)
      return null;
    final Object value = expression.eval(new Object[0]);
    if (value == null)
      return null;
    final long number = ((Number) value).longValue();
    if (number < 0)
      throw new PostgreSQLEvaluationException(negativeSqlState, clause + " must not be negative");
    return number;
  }
}
