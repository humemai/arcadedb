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
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.eval.Comparison;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Numbers;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The window functions of a {@code SELECT} (fork issue #56): a step between the rows of the {@code FROM} clause ({@code WHERE}
 * applied) and the select list. It reads every input row, computes each window function for each row, and hands on the row with
 * one more value per function, which the select list and {@code ORDER BY} read as a slot after the columns of the input.
 *
 * <p>What it computes is what PostgreSQL computes with the default frame (the rows from the start of the partition to the last
 * peer of the current row; the whole partition when there is no {@code ORDER BY}): {@code row_number}, {@code rank},
 * {@code dense_rank}, {@code lag}, {@code lead}, {@code first_value}, {@code last_value}, and {@code count}, {@code sum},
 * {@code avg}, {@code min}, {@code max} as running aggregates. Two rows are peers when no {@code ORDER BY} key tells them apart.
 * The frame {@code ROWS UNBOUNDED PRECEDING} (also written {@code ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW}) ends at the current
 * row instead of its last peer. Any other frame, a named window, {@code FILTER}, {@code DISTINCT} and the other window functions are {@code 0A000}. The rows
 * of a partition keep their input order for ties, as a stable sort does.
 */
final class WindowStage {
  /** Window functions that PostgreSQL has and the prototype does not: {@code 0A000}, not {@code 42883}. */
  private static final Set<String> OTHER_WINDOW_FUNCTIONS = Set.of("ntile", "percent_rank", "cume_dist", "nth_value");
  private static final Set<String> NON_AGGREGATE_FUNCTIONS = Set.of("row_number", "rank", "dense_rank", "lag", "lead",
      "first_value", "last_value");
  /** The aggregates that collect their rows, which the language does not run over a window. */
  private static final Set<String> LIST_AGGREGATES = Set.of("string_agg", "array_agg", "jsonb_agg", "json_agg",
      "json_object_agg", "jsonb_object_agg");

  /** One window function call, typed. */
  record Call(String name, PgType resultType, String resultTypeName, Evaluable[] partition, Evaluable[] orderKeys,
              List<Comparator<Object>> orderings, Evaluable argument, Evaluable offset, Evaluable fallback, PgType fallbackTo,
              SelectPlan.AggregateSpec aggregate, boolean rowsFrame) {
    Call(final String name, final PgType resultType, final String resultTypeName, final Evaluable[] partition,
        final Evaluable[] orderKeys, final List<Comparator<Object>> orderings, final Evaluable argument, final Evaluable offset,
        final Evaluable fallback, final PgType fallbackTo, final SelectPlan.AggregateSpec aggregate) {
      this(name, resultType, resultTypeName, partition, orderKeys, orderings, argument, offset, fallback, fallbackTo, aggregate, false);
    }

    /** The same call with the frame {@code ROWS UNBOUNDED PRECEDING}: it ends at the current row, whatever its peers are. */
    Call withRowsFrame() {
      return new Call(name, resultType, resultTypeName, partition, orderKeys, orderings, argument, offset, fallback, fallbackTo,
          aggregate, true);
    }
  }

  private final int        width;
  private final List<Call> calls;

  WindowStage(final int width, final List<Call> calls) {
    this.width = width;
    this.calls = List.copyOf(calls);
  }

  int count() {
    return calls.size();
  }

  // ------------------------------------------------------------------------------------------------ typing

  /** Types a call with an {@code OVER} clause against the columns of the input row; {@code input} compiles over that row. */
  static Call call(final Expression.FunctionCall call, final ExpressionCompiler input) {
    final Call typed = typedCall(call, input);
    return call.over().rowsFrame() ? typed.withRowsFrame() : typed;
  }

  private static Call typedCall(final Expression.FunctionCall call, final ExpressionCompiler input) {
    final String name = call.name().get(call.name().size() - 1).value().toLowerCase(java.util.Locale.ROOT);
    final Expression.Window over = call.over();
    if (call.distinct())
      throw new PostgreSQLFeatureNotSupportedException("DISTINCT is not implemented for window functions");
    if (call.filter() != null && (NON_AGGREGATE_FUNCTIONS.contains(name) || OTHER_WINDOW_FUNCTIONS.contains(name)))
      throw new PostgreSQLFeatureNotSupportedException("FILTER is not implemented for non-aggregate window functions");
    if (!call.orderBy().isEmpty())
      throw new PostgreSQLFeatureNotSupportedException("aggregate ORDER BY is not implemented for window functions");

    final Evaluable[] partition = new Evaluable[over.partitionBy().size()];
    for (int i = 0; i < partition.length; i++) {
      partition[i] = input.resolveOutput(input.compile(over.partitionBy().get(i)));
      Comparison.requireEquality(partition[i].type());
    }
    final Evaluable[] orderKeys = new Evaluable[over.orderBy().size()];
    final List<Comparator<Object>> orderings = new ArrayList<>();
    for (int i = 0; i < orderKeys.length; i++) {
      final SortItem item = over.orderBy().get(i);
      orderKeys[i] = input.resolveOutput(input.compile(item.expression()));
      Comparison.refuseJsonOrder(orderKeys[i].type());
      if (!Comparison.isComparable(orderKeys[i].type()))
        throw new PostgreSQLFeatureNotSupportedException(
            "ORDER BY a value of type " + orderKeys[i].type().displayName() + " is not supported");
      final Boolean nullsFirst = item.nulls() == SortItem.Nulls.FIRST ? Boolean.TRUE
          : item.nulls() == SortItem.Nulls.LAST ? Boolean.FALSE : null;
      orderings.add(Comparison.ordering(orderKeys[i].type(), item.direction() == SortItem.Direction.DESC, nullsFirst));
    }

    switch (name) {
    case "row_number":
    case "rank":
    case "dense_rank":
      if (call.star() || !call.args().isEmpty())
        throw PostgreSQLEvaluationException.noFunction(name, input.argumentTypes(call));
      return new Call(name, PgType.INT8, PgType.INT8.typeName(), partition, orderKeys, orderings, null, null, null, null, null);
    case "lag":
    case "lead": {
      if (call.star() || call.args().isEmpty() || call.args().size() > 3)
        throw PostgreSQLEvaluationException.noFunction(name, input.argumentTypes(call));
      final Evaluable argument = input.resolveOutput(input.compile(call.args().get(0)));
      Evaluable offset = null;
      if (call.args().size() > 1) {
        offset = input.compile(call.args().get(1));
        if (!offset.type().isInteger() && offset.type() != PgType.UNKNOWN)
          throw PostgreSQLEvaluationException.noFunction(name, argument.type(), offset.type());
        ExpressionCompiler.inferParameter(offset, PgType.INT4);
      }
      Evaluable fallback = null;
      PgType fallbackTo = null;
      if (call.args().size() > 2) {
        fallback = input.compile(call.args().get(2));
        ExpressionCompiler.inferParameter(fallback, argument.type());
        final boolean nullConstant = fallback instanceof Evaluable.Constant constant && constant.value() == null;
        if (!nullConstant && fallback.type() != argument.type()) {
          if ((fallback.type().isString() || fallback.type() == PgType.UNKNOWN) && argument.type().isString())
            fallbackTo = null;
          else if (fallback.type().isInteger() && (argument.type() == PgType.INT8 || argument.type() == PgType.NUMERIC
              || argument.type().isFloat()))
            fallbackTo = argument.type();
          else
            throw new PostgreSQLFeatureNotSupportedException(
                "The default of " + name + " has to be of the type of its first argument (" + argument.type().displayName() + ")");
        }
      }
      return new Call(name, argument.type(), argument.typeName(), partition, orderKeys, orderings, argument, offset, fallback,
          fallbackTo, null);
    }
    case "first_value":
    case "last_value": {
      if (call.star() || call.args().size() != 1)
        throw PostgreSQLEvaluationException.noFunction(name, input.argumentTypes(call));
      final Evaluable argument = input.resolveOutput(input.compile(call.args().get(0)));
      return new Call(name, argument.type(), argument.typeName(), partition, orderKeys, orderings, argument, null, null, null, null);
    }
    case "count":
    case "sum":
    case "avg":
    case "min":
    case "max": {
      final SelectPlan.AggregateSpec spec = SelectPlanner.aggregateSpec(call, input);
      return new Call(name, spec.resultType(), spec.resultType().typeName(), partition, orderKeys, orderings, null, null, null, null,
          spec);
    }
    default:
      if (OTHER_WINDOW_FUNCTIONS.contains(name) || LIST_AGGREGATES.contains(name))
        throw new PostgreSQLFeatureNotSupportedException("The window function " + name + " is not supported");
      throw PostgreSQLEvaluationException.noFunction(name, input.argumentTypes(call));
    }
  }

  // ------------------------------------------------------------------------------------------------ running

  /** The input rows, each with the value of every window function after the columns it had. */
  List<Object[]> apply(final Iterator<Object[]> rows) {
    final List<Object[]> input = new ArrayList<>();
    while (rows.hasNext())
      input.add(rows.next());
    final List<Object[]> out = new ArrayList<>(input.size());
    for (final Object[] row : input)
      out.add(Arrays.copyOf(row, width + calls.size()));
    for (int k = 0; k < calls.size(); k++)
      compute(calls.get(k), input, out, width + k);
    return out;
  }

  private static int compareOrder(final Call call, final Object[] a, final Object[] b) {
    for (int i = 0; i < a.length; i++) {
      final int order = call.orderings().get(i).compare(a[i], b[i]);
      if (order != 0)
        return order;
    }
    return 0;
  }

  private static void compute(final Call call, final List<Object[]> input, final List<Object[]> out, final int slot) {
    final int n = input.size();
    final Map<List<Object>, List<Integer>> partitions = new LinkedHashMap<>();
    final Object[][] orderValues = new Object[n][];
    for (int i = 0; i < n; i++) {
      final Object[] row = input.get(i);
      final Object[] key = new Object[call.partition().length];
      for (int p = 0; p < key.length; p++)
        key[p] = call.partition()[p].eval(row);
      partitions.computeIfAbsent(SelectPlan.distinctKey(key, key.length), ignored -> new ArrayList<>()).add(i);
      final Object[] order = new Object[call.orderKeys().length];
      for (int o = 0; o < order.length; o++)
        order[o] = call.orderKeys()[o].eval(row);
      orderValues[i] = order;
    }

    for (final List<Integer> members : partitions.values()) {
      final Integer[] rows = members.toArray(new Integer[0]);
      if (call.orderKeys().length > 0)
        Arrays.sort(rows, (a, b) -> compareOrder(call, orderValues[a], orderValues[b]));
      final int m = rows.length;

      // the peer groups: the first and the last position of the group of each position, and the number of the group
      final int[] first = new int[m];
      final int[] last = new int[m];
      final int[] group = new int[m];
      int start = 0;
      int groups = 0;
      for (int p = 1; p <= m; p++)
        if (p == m || (call.orderKeys().length > 0 && compareOrder(call, orderValues[rows[p - 1]], orderValues[rows[p]]) != 0)) {
          for (int q = start; q < p; q++) {
            first[q] = start;
            last[q] = p - 1;
            group[q] = groups;
          }
          groups++;
          start = p;
        }

      if (call.rowsFrame())
        for (int q = 0; q < m; q++)
          last[q] = q;

      switch (call.name()) {
      case "row_number":
        for (int p = 0; p < m; p++)
          out.get(rows[p])[slot] = (long) p + 1;
        break;
      case "rank":
        for (int p = 0; p < m; p++)
          out.get(rows[p])[slot] = (long) first[p] + 1;
        break;
      case "dense_rank":
        for (int p = 0; p < m; p++)
          out.get(rows[p])[slot] = (long) group[p] + 1;
        break;
      case "lag":
      case "lead": {
        final int direction = call.name().equals("lag") ? -1 : 1;
        for (int p = 0; p < m; p++) {
          final Object[] current = input.get(rows[p]);
          final Object offset = call.offset() == null ? (Object) 1L : call.offset().eval(current);
          Object value = null;
          if (offset != null) {
            final long target = p + direction * ((Number) offset).longValue();
            if (target >= 0 && target < m)
              value = call.argument().eval(input.get(rows[(int) target]));
            else if (call.fallback() != null) {
              value = call.fallback().eval(current);
              if (value != null && call.fallbackTo() != null)
                value = Numbers.widen(value, call.fallbackTo());
            }
          }
          out.get(rows[p])[slot] = value;
        }
        break;
      }
      case "first_value":
        for (int p = 0; p < m; p++)
          out.get(rows[p])[slot] = call.argument().eval(input.get(rows[0]));
        break;
      case "last_value":
        for (int p = 0; p < m; p++)
          out.get(rows[p])[slot] = call.argument().eval(input.get(rows[last[p]]));
        break;
      default: {
        // an aggregate over the rows from the start of the partition to the last peer of the current row
        final SelectPlan.Accumulator accumulator = SelectPlan.Accumulator.create(call.aggregate());
        int p = 0;
        while (p < m) {
          final int end = last[p];
          for (int q = p; q <= end; q++)
            accumulator.addRow(call.aggregate(), input.get(rows[q]), null);
          final Object result = accumulator.result();
          for (int q = p; q <= end; q++)
            out.get(rows[q])[slot] = result;
          p = end + 1;
        }
        break;
      }
      }
    }
  }
}
