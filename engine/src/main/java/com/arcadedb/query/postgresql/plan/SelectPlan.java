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
import com.arcadedb.query.postgresql.eval.Comparison;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.Numbers;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.sql.executor.ResultInternal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A planned {@code SELECT}: a {@link RelationSource}, then the steps PostgreSQL applies to its rows, in the order of its
 * evaluation: the {@code WHERE} filter, grouping and the {@code HAVING} filter, the select list, {@code DISTINCT},
 * {@code ORDER BY}, {@code OFFSET} and {@code LIMIT}. {@link #execute(Database)} runs it and answers a
 * {@link PostgreSQLResultSet}. Every expression is an {@link Evaluable} that was typed when the plan was built, so a type error
 * is raised before the first row is read.
 *
 * <p>A sort key is read from the output row ({@code ORDER BY 2}, {@code ORDER BY alias}) or computed from the input row and
 * kept behind the output values until the sort is done. Without {@code ORDER BY} and {@code DISTINCT}, and without grouping, the
 * read stops as soon as {@code OFFSET + LIMIT} rows are in.
 */
public final class SelectPlan {

  /** One key of the sort: where its value is in the row (output values first, then the extra keys), and its order. */
  record SortKey(int position, Comparator<Object> ordering) {
  }

  /** An aggregate function call of a grouping query. */
  record AggregateSpec(String name, PgType argumentType, PgType resultType, Evaluable argument, boolean distinct) {
  }

  private final RelationSource source;
  private final Evaluable      where;
  private final GroupStage     group;
  private final Evaluable      having;
  private final Evaluable[]    outputs;
  private final Evaluable[]    extras;
  private final List<PostgreSQLResultSet.Column> columns;
  private final boolean        distinct;
  private final List<SortKey>  sortKeys;
  private final Evaluable      limit;
  private final Evaluable      offset;
  private final List<String>   steps;
  private final List<String>   warnings;

  SelectPlan(final RelationSource source, final Evaluable where, final GroupStage group, final Evaluable having,
      final Evaluable[] outputs, final Evaluable[] extras, final List<PostgreSQLResultSet.Column> columns, final boolean distinct,
      final List<SortKey> sortKeys, final Evaluable limit, final Evaluable offset, final List<String> steps,
      final List<String> warnings) {
    this.source = source;
    this.where = where;
    this.group = group;
    this.having = having;
    this.outputs = outputs;
    this.extras = extras;
    this.columns = List.copyOf(columns);
    this.distinct = distinct;
    this.sortKeys = List.copyOf(sortKeys);
    this.limit = limit;
    this.offset = offset;
    this.steps = List.copyOf(steps);
    this.warnings = List.copyOf(warnings);
  }

  /** The lines of the plan, the way the source reads first. */
  public List<String> steps() {
    return steps;
  }

  /**
   * The warnings of the plan (fork issue #9): one sentence for each join that no edge, link or index supports. The plan lists
   * each as a {@code WARNING} line at its end; the engine writes them to the log once per statement.
   */
  public List<String> warnings() {
    return warnings;
  }

  public List<PostgreSQLResultSet.Column> columns() {
    return columns;
  }

  public RelationSource source() {
    return source;
  }

  public PostgreSQLResultSet execute(final Database database) {
    final Long limitValue = count(limit, "LIMIT", "2201W");
    final Long offsetValue = count(offset, "OFFSET", "2201X");
    final long skip = offsetValue == null ? 0L : offsetValue;
    final boolean sorted = !sortKeys.isEmpty();
    long cap = Long.MAX_VALUE;
    if (!sorted && !distinct && limitValue != null)
      cap = limitValue > Long.MAX_VALUE - skip ? Long.MAX_VALUE : skip + limitValue;

    final List<Object[]> rows = new ArrayList<>();
    if (cap > 0) {
      final Iterator<Object[]> input = inputRows();
      final Set<List<Object>> seen = distinct ? new HashSet<>() : null;
      while (input.hasNext()) {
        final Object[] in = input.next();
        final Object[] out = new Object[outputs.length + extras.length];
        for (int i = 0; i < outputs.length; i++)
          out[i] = outputs[i].eval(in);
        for (int j = 0; j < extras.length; j++)
          out[outputs.length + j] = extras[j].eval(in);
        if (seen != null && !seen.add(distinctKey(out, outputs.length)))
          continue;
        rows.add(out);
        if (rows.size() >= cap)
          break;
      }
    }

    if (sorted)
      rows.sort((a, b) -> {
        for (final SortKey key : sortKeys) {
          final int order = key.ordering().compare(a[key.position()], b[key.position()]);
          if (order != 0)
            return order;
        }
        return 0;
      });

    final int from = (int) Math.min(skip, (long) rows.size());
    final int to = limitValue == null || limitValue >= rows.size() - from ? rows.size() : from + (int) (long) limitValue;

    final List<String> keys = PostgreSQLResultSet.uniqueKeys(columns);
    final PostgreSQLResultSet result = new PostgreSQLResultSet(columns, new PostgreSQLResultSet.Plan(steps), warnings);
    for (int r = from; r < to; r++) {
      final ResultInternal row = new ResultInternal(database);
      for (int i = 0; i < outputs.length; i++)
        row.setProperty(keys.get(i), rows.get(r)[i]);
      result.add(row);
    }
    return result;
  }

  /** The rows the select list reads: the filtered rows of the source, or the rows of the groups that pass {@code HAVING}. */
  private Iterator<Object[]> inputRows() {
    Iterator<Object[]> rows = source.open();
    if (where != null)
      rows = filter(rows, where);
    if (group == null)
      return rows;
    final List<Object[]> groups = group.groups(rows);
    if (having != null)
      groups.removeIf(row -> !Boolean.TRUE.equals(having.eval(row)));
    return groups.iterator();
  }

  private static Iterator<Object[]> filter(final Iterator<Object[]> rows, final Evaluable condition) {
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

  /** The value of a {@code LIMIT} or {@code OFFSET}: {@code null} for none or for {@code NULL}, an error when negative. */
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

  /** What makes two rows the same for {@code DISTINCT} and {@code GROUP BY}: numbers by value, {@code NULL} equal to itself. */
  static List<Object> distinctKey(final Object[] row, final int count) {
    final List<Object> key = new ArrayList<>(count);
    for (int i = 0; i < count; i++)
      key.add(normalize(row[i]));
    return key;
  }

  static Object normalize(final Object value) {
    if (value instanceof BigDecimal decimal)
      return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    if (value instanceof Float || value instanceof Double)
      return ((Number) value).doubleValue() == 0.0 ? (Object) 0.0 : value;
    return value;
  }

  // ---- grouping ----

  /** Groups the rows by the key expressions and computes the aggregates: a group row is the keys, then the aggregates. */
  static final class GroupStage {
    private final Evaluable[]          keys;
    private final List<AggregateSpec>  specs;

    GroupStage(final Evaluable[] keys, final List<AggregateSpec> specs) {
      this.keys = keys;
      this.specs = List.copyOf(specs);
    }

    int keyCount() {
      return keys.length;
    }

    int aggregateCount() {
      return specs.size();
    }

    /**
     * Without a {@code GROUP BY} the whole input is one group, even when it is empty; with one, an empty input has no group.
     */
    List<Object[]> groups(final Iterator<Object[]> rows) {
      final Map<List<Object>, Object[]> keyValues = new LinkedHashMap<>();
      final Map<List<Object>, Accumulator[]> accumulators = new LinkedHashMap<>();
      while (rows.hasNext()) {
        final Object[] row = rows.next();
        final Object[] values = new Object[keys.length];
        for (int i = 0; i < keys.length; i++)
          values[i] = keys[i].eval(row);
        final List<Object> key = distinctKey(values, values.length);
        Accumulator[] group = accumulators.get(key);
        if (group == null) {
          group = newAccumulators();
          accumulators.put(key, group);
          keyValues.put(key, values);
        }
        for (int a = 0; a < specs.size(); a++) {
          final AggregateSpec spec = specs.get(a);
          group[a].add(spec.argument() == null ? Boolean.TRUE : spec.argument().eval(row));
        }
      }
      if (keys.length == 0 && accumulators.isEmpty()) {
        accumulators.put(List.of(), newAccumulators());
        keyValues.put(List.of(), new Object[0]);
      }
      final List<Object[]> out = new ArrayList<>(accumulators.size());
      for (final Map.Entry<List<Object>, Accumulator[]> entry : accumulators.entrySet()) {
        final Object[] row = new Object[keys.length + specs.size()];
        System.arraycopy(keyValues.get(entry.getKey()), 0, row, 0, keys.length);
        for (int a = 0; a < specs.size(); a++)
          row[keys.length + a] = entry.getValue()[a].result();
        out.add(row);
      }
      return out;
    }

    private Accumulator[] newAccumulators() {
      final Accumulator[] out = new Accumulator[specs.size()];
      for (int i = 0; i < out.length; i++)
        out[i] = Accumulator.create(specs.get(i));
      return out;
    }
  }

  /** The state of one aggregate in one group. */
  interface Accumulator {
    /** @param value the argument for a row, {@code TRUE} for {@code count(*)}; {@code null} is skipped by every aggregate */
    void add(Object value);

    Object result();

    static Accumulator create(final AggregateSpec spec) {
      final Accumulator accumulator = switch (spec.name()) {
        case "count" -> new Accumulator() {
          private long count;

          @Override
          public void add(final Object value) {
            if (value != null)
              ++count;
          }

          @Override
          public Object result() {
            return count;
          }
        };
        case "sum" -> new Accumulator() {
          private Object total;

          @Override
          public void add(final Object value) {
            if (value == null)
              return;
            final Object widened = Numbers.widen(value, spec.resultType());
            total = total == null ? widened : Numbers.add(spec.resultType(), total, widened);
          }

          @Override
          public Object result() {
            return total;
          }
        };
        case "avg" -> new Accumulator() {
          private final PgType sumType = spec.resultType() == PgType.FLOAT8 ? PgType.FLOAT8 : PgType.NUMERIC;
          private Object total;
          private long   count;

          @Override
          public void add(final Object value) {
            if (value == null)
              return;
            final Object widened = Numbers.widen(value, sumType);
            total = total == null ? widened : Numbers.add(sumType, total, widened);
            ++count;
          }

          @Override
          public Object result() {
            if (count == 0)
              return null;
            if (sumType == PgType.FLOAT8)
              return Numbers.divide(PgType.FLOAT8, total, (double) count);
            return Numbers.divideNumeric((BigDecimal) total, BigDecimal.valueOf(count));
          }
        };
        default -> new Accumulator() {
          private final boolean wantMax = spec.name().equals("max");
          private Object best;

          @Override
          public void add(final Object value) {
            if (value == null)
              return;
            if (best == null) {
              best = value;
              return;
            }
            final int order = Comparison.compare(spec.argumentType(), value, best);
            if (wantMax ? order > 0 : order < 0)
              best = value;
          }

          @Override
          public Object result() {
            return best;
          }
        };
      };
      if (!spec.distinct())
        return accumulator;
      final Set<Object> seen = new HashSet<>();
      return new Accumulator() {
        @Override
        public void add(final Object value) {
          if (value != null && seen.add(normalize(value)))
            accumulator.add(value);
        }

        @Override
        public Object result() {
          return accumulator.result();
        }
      };
    }
  }

  @Override
  public String toString() {
    return String.join("\n", steps) + " " + Arrays.toString(outputs);
  }
}
