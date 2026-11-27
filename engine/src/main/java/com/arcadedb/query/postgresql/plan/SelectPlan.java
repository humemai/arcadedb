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
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Comparison;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Json;
import com.arcadedb.query.postgresql.eval.Jsonb;
import com.arcadedb.query.postgresql.eval.Numbers;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.eval.Text;
import com.arcadedb.query.postgresql.plan.join.BuildMemory;
import com.arcadedb.query.sql.executor.ResultInternal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;

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
public final class SelectPlan implements QueryPlan {

  /** One key of the sort: where its value is in the row (output values first, then the extra keys), and its order. */
  record SortKey(int position, Comparator<Object> ordering) {
  }

  /**
   * One aggregate call: of a grouping query, or of a window.
   *
   * @param resultTypeName the name of the result type, which for an array says the element type ({@code _int4})
   * @param delimiter      the second argument of {@code string_agg}, evaluated per row; {@code null} for the aggregates of one
   *                       argument
   * @param filter         the condition of {@code FILTER (WHERE ...)}: a row for which it is not true is not given to the
   *                       aggregate, and its arguments are not evaluated; {@code null} for none
   * @param orderKeys      the keys of the {@code ORDER BY} of the call, evaluated per row, empty for none
   * @param orderings      the order of each key
   */
  record AggregateSpec(String name, PgType argumentType, PgType resultType, String resultTypeName, Evaluable argument,
                       boolean distinct, Evaluable delimiter, Evaluable filter, Evaluable[] orderKeys,
                       List<Comparator<Object>> orderings) {
    AggregateSpec(final String name, final PgType argumentType, final PgType resultType, final Evaluable argument,
        final boolean distinct, final Evaluable delimiter) {
      this(name, argumentType, resultType, resultType.typeName(), argument, distinct, delimiter, null, new Evaluable[0], List.of());
    }

    /** This aggregate with the {@code FILTER} and the {@code ORDER BY} of its call. */
    AggregateSpec with(final Evaluable condition, final Evaluable[] keys, final List<Comparator<Object>> order) {
      return new AggregateSpec(name, argumentType, resultType, resultTypeName, argument, distinct, delimiter, condition, keys, order);
    }

    /** An aggregate whose result depends on the order its rows come in, so that DISTINCT has to give them in PostgreSQL's order. */
    boolean collects() {
      return "array_agg".equals(name) || "jsonb_agg".equals(name) || "json_agg".equals(name);
    }
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
  private final Supplier<List<String>> steps;
  private final List<String>   warnings;
  private final WindowStage    windows;
  /** The output columns that are columns of record ids. */
  private final BitSet         recordIds;

  SelectPlan(final RelationSource source, final Evaluable where, final GroupStage group, final Evaluable having,
      final Evaluable[] outputs, final Evaluable[] extras, final List<PostgreSQLResultSet.Column> columns, final boolean distinct,
      final List<SortKey> sortKeys, final Evaluable limit, final Evaluable offset, final Supplier<List<String>> steps,
      final List<String> warnings, final WindowStage windows, final BitSet recordIds) {
    this.windows = windows;
    this.recordIds = recordIds;
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
    this.steps = steps;
    this.warnings = List.copyOf(warnings);
  }

  /** The lines of the plan, the way the source reads first. */
  @Override
  public List<String> steps() {
    return List.copyOf(steps.get());
  }

  /**
   * The warnings of the plan (fork issue #9): one sentence for each join that no edge, link or index supports. The plan lists
   * each as a {@code WARNING} line at its end; the engine writes them to the log once per statement.
   */
  @Override
  public List<String> warnings() {
    return warnings;
  }

  @Override
  public List<PostgreSQLResultSet.Column> columns() {
    return columns;
  }

  public RelationSource source() {
    return source;
  }

  @Override
  public PostgreSQLResultSet execute(final Database database) {
    final List<Object[]> rows = rows(null);
    final List<String> keys = PostgreSQLResultSet.uniqueKeys(columns);
    final PostgreSQLResultSet result = new PostgreSQLResultSet(columns, new PostgreSQLResultSet.Plan(steps), warnings);
    for (final Object[] values : rows) {
      final ResultInternal row = new ResultInternal(database);
      for (int i = 0; i < outputs.length; i++)
        row.setProperty(keys.get(i), values[i]);
      result.add(row);
    }
    return result;
  }

  @Override
  public void inferParameter(final int index, final PgType type) {
    ExpressionCompiler.inferParameter(outputs[index], type);
  }

  @Override
  public boolean holdsRecordIds(final int index) {
    return recordIds.get(index);
  }

  @Override
  public List<PgType> outputTypes() {
    final List<PgType> types = new ArrayList<>(outputs.length);
    for (final Evaluable output : outputs)
      types.add(output.type());
    return types;
  }

  /**
   * The rows of the answer, each as wide as the select list. The rows that are kept while the statement runs (before the sort, and
   * the rows of the answer) are charged to the memory, when there is one: the caller that keeps the answer, a derived table or a set
   * operation, gives its own.
   */
  @Override
  public List<Object[]> rows(final BuildMemory memory) {
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
        if (memory != null)
          memory.charge(out);
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

    final List<Object[]> answer = new ArrayList<>(Math.max(0, to - from));
    for (int r = from; r < to; r++)
      answer.add(extras.length == 0 ? rows.get(r) : Arrays.copyOf(rows.get(r), outputs.length));
    return answer;
  }

  /** The rows the select list reads: the filtered rows of the source, or the rows of the groups that pass {@code HAVING}. */
  private Iterator<Object[]> inputRows() {
    Iterator<Object[]> rows = source.open();
    if (where != null)
      rows = filter(rows, where);
    if (group != null) {
      final List<Object[]> groups = group.groups(rows);
      if (having != null)
        groups.removeIf(row -> !Boolean.TRUE.equals(having.eval(row)));
      rows = groups.iterator();
    }
    return windows == null ? rows : windows.apply(rows).iterator();
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

  /**
   * What makes two rows the same for {@code DISTINCT} and {@code GROUP BY}: numbers by value, also those inside a {@code jsonb}
   * value or an array ({@link Jsonb#key}), {@code NULL} equal to itself.
   */
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
    if (value instanceof Map<?, ?> || value instanceof List<?> || value instanceof Jsonb.Scalar)
      return Jsonb.key(value);
    return value;
  }

  // ---- grouping ----

  /**
   * Groups the rows by the key expressions and computes the aggregates: a group row is the keys, then the aggregates. Every group
   * is kept in memory until the input ends, so each new group of a {@code GROUP BY} is charged to a {@link BuildMemory} under the
   * same cap as a join, and over it the statement stops with SQLSTATE {@code 53200}. What one group collects as it grows (the
   * values of a {@code DISTINCT} aggregate, the text of {@code string_agg}) is not counted.
   */
  static final class GroupStage {
    /** The estimated bytes of one group besides its key values: the key list and the two hash table entries. */
    private static final long GROUP_BYTES       = 128;
    /** The estimated bytes of the state of one aggregate in one group. */
    private static final long ACCUMULATOR_BYTES = 64;

    private final Evaluable[]          keys;
    private final List<AggregateSpec>  specs;
    private final long                 capBytes;

    GroupStage(final Evaluable[] keys, final List<AggregateSpec> specs, final long capBytes) {
      this.keys = keys;
      this.specs = List.copyOf(specs);
      this.capBytes = capBytes;
    }

    int keyCount() {
      return keys.length;
    }

    int aggregateCount() {
      return specs.size();
    }

    /**
     * Without a {@code GROUP BY} the whole input is one group, even when it is empty; with one, an empty input has no group.
     *
     * @throws PostgreSQLEvaluationException with SQLSTATE {@code 53200} when the groups of a {@code GROUP BY} are over the cap
     */
    List<Object[]> groups(final Iterator<Object[]> rows) {
      final BuildMemory memory = new BuildMemory(capBytes, "a GROUP BY",
          "the groups are kept in memory and do not spill to disk; group by fewer or shorter columns, narrow the input with a "
              + "WHERE condition, or raise the cap with the JVM property " + BuildMemory.CAP_PROPERTY + " (MiB)");
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
          // without a GROUP BY there is one group whatever the input, so nothing grows with it
          if (keys.length > 0) {
            memory.charge(values);
            memory.chargeBytes(GROUP_BYTES + ACCUMULATOR_BYTES * specs.size());
          }
          group = newAccumulators();
          accumulators.put(key, group);
          keyValues.put(key, values);
        }
        for (int a = 0; a < specs.size(); a++)
          group[a].addRow(specs.get(a), row, memory);
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

    /** The row of an aggregate of two arguments: {@code string_agg(value, delimiter)}. The others ignore the second one. */
    default void add(final Object value, final Object second) {
      add(value);
    }

    Object result();

    /**
     * One input row of the aggregate {@code spec}: its {@code FILTER} first, then its arguments.
     *
     * @param memory what an aggregate that keeps rows charges them to, {@code null} for none
     */
    default void addRow(final AggregateSpec spec, final Object[] row, final BuildMemory memory) {
      if (spec.filter() != null && !Boolean.TRUE.equals(spec.filter().eval(row)))
        return;
      add(spec.argument() == null ? Boolean.TRUE : spec.argument().eval(row),
          spec.delimiter() == null ? null : spec.delimiter().eval(row));
    }

    static Accumulator create(final AggregateSpec spec) {
      // an ORDER BY, and the order that DISTINCT gives to the rows of a list, need every row before the first is aggregated
      if (spec.orderKeys().length > 0 || spec.distinct() && spec.collects())
        return new SortedAccumulator(spec);
      final Accumulator accumulator = plain(spec);
      if (!spec.distinct())
        return accumulator;
      if (spec.name().equals("string_agg"))
        return new DistinctStringAgg();
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

    /** The accumulator of an aggregate over its rows as they come, without {@code DISTINCT}. */
    private static Accumulator plain(final AggregateSpec spec) {
      return switch (spec.name()) {
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
        case "string_agg" -> new Accumulator() {
          private StringBuilder joined;

          @Override
          public void add(final Object value) {
            add(value, null);
          }

          /** A null value is skipped and brings no delimiter; a null delimiter joins without one. The first value has none before it. */
          @Override
          public void add(final Object value, final Object delimiter) {
            if (value == null)
              return;
            if (joined == null)
              joined = new StringBuilder();
            else if (delimiter != null)
              joined.append((String) delimiter);
            joined.append((String) value);
          }

          @Override
          public Object result() {
            return joined == null ? null : joined.toString();
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
        // NULL is an element like any other, and no row at all is NULL and not an empty list
        case "array_agg", "jsonb_agg" -> new Accumulator() {
          private final boolean json = spec.name().equals("jsonb_agg");
          private List<Object> items;

          @Override
          public void add(final Object value) {
            if (items == null)
              items = new ArrayList<>();
            items.add(json && value != null ? Jsonb.of(value, spec.argument().type(), spec.argument().typeName()) : value);
          }

          @Override
          public Object result() {
            return items == null ? null : new ArrayList<>(items);
          }
        };
        case "json_agg" -> new Accumulator() {
          private StringBuilder out;

          @Override
          public void add(final Object value) {
            if (out == null)
              out = new StringBuilder("[");
            else {
              out.append(", ");
              // PostgreSQL starts a new line before an element that is an array
              if (value != null && spec.argument().type() == PgType.ARRAY)
                out.append("\n ");
            }
            Json.append(out, value, spec.argument().type(), spec.argument().typeName());
          }

          @Override
          public Object result() {
            return out == null ? null : new Json.Value(out + "]");
          }
        };
        case "json_object_agg" -> new Accumulator() {
          private StringBuilder out;

          @Override
          public void add(final Object key) {
            add(key, null);
          }

          @Override
          public void add(final Object key, final Object value) {
            if (key == null)
              throw new PostgreSQLEvaluationException("22004", "null value not allowed for object key");
            if (out == null)
              out = new StringBuilder("{ ");
            else
              out.append(", ");
            Json.appendKey(out, key, spec.argument().type(), spec.argument().typeName());
            out.append(" : ");
            Json.append(out, value, spec.delimiter().type(), spec.delimiter().typeName());
          }

          @Override
          public Object result() {
            return out == null ? null : new Json.Value(out + " }");
          }
        };
        // a name that comes twice keeps its last value, as jsonb does
        case "jsonb_object_agg" -> new Accumulator() {
          private Map<String, Object> members;

          @Override
          public void add(final Object key) {
            add(key, null);
          }

          @Override
          public void add(final Object key, final Object value) {
            if (key == null)
              throw new PostgreSQLEvaluationException("22023", "field name must not be null");
            if (members == null)
              members = new LinkedHashMap<>();
            final PgType type = spec.argument().type();
            final String name = type.isNumber() || type == PgType.BOOL ? Casts.toText(key, type)
                : String.valueOf(Jsonb.of(key, type, spec.argument().typeName()));
            members.put(name, value == null ? null : Jsonb.of(value, spec.delimiter().type(), spec.delimiter().typeName()));
          }

          @Override
          public Object result() {
            return members == null ? null : new LinkedHashMap<>(members);
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
    }
  }

  /**
   * An aggregate that sees its rows in an order: the one of its {@code ORDER BY}, and for {@code DISTINCT} the order of its
   * arguments after those keys (every key is one of them, as PostgreSQL requires), which is how PostgreSQL implements it: sort,
   * then skip a row equal to the one before. The rows are kept until the input ends, and are charged to the memory of the
   * grouping.
   */
  private static final class SortedAccumulator implements Accumulator {
    private final AggregateSpec  spec;
    /** The value, the second argument, then the keys of one row. */
    private final List<Object[]> rows = new ArrayList<>();

    SortedAccumulator(final AggregateSpec spec) {
      this.spec = spec;
    }

    @Override
    public void addRow(final AggregateSpec ignored, final Object[] row, final BuildMemory memory) {
      if (spec.filter() != null && !Boolean.TRUE.equals(spec.filter().eval(row)))
        return;
      final Evaluable[] keys = spec.orderKeys();
      final Object[] kept = new Object[2 + keys.length];
      kept[0] = spec.argument() == null ? Boolean.TRUE : spec.argument().eval(row);
      kept[1] = spec.delimiter() == null ? null : spec.delimiter().eval(row);
      for (int i = 0; i < keys.length; i++)
        kept[2 + i] = keys[i].eval(row);
      if (memory != null)
        memory.charge(kept);
      rows.add(kept);
    }

    @Override
    public void add(final Object value) {
      throw new IllegalStateException("A sorted aggregate reads its rows");
    }

    @Override
    public Object result() {
      final List<Object[]> sorted = new ArrayList<>(rows);
      final Comparator<Object> arguments = spec.distinct() ? Comparison.ordering(spec.argumentType(), false, null) : null;
      final Comparator<Object> delimiters = spec.distinct() && spec.delimiter() != null
          ? Comparison.ordering(PgType.TEXT, false, null) : null;
      sorted.sort((a, b) -> {
        for (int i = 0; i < spec.orderings().size(); i++) {
          final int order = spec.orderings().get(i).compare(a[2 + i], b[2 + i]);
          if (order != 0)
            return order;
        }
        if (arguments == null)
          return 0;
        final int order = arguments.compare(a[0], b[0]);
        return order != 0 || delimiters == null ? order : delimiters.compare(a[1], b[1]);
      });
      final Accumulator accumulator = Accumulator.plain(spec);
      final Set<List<Object>> seen = spec.distinct() ? new HashSet<>() : null;
      for (final Object[] row : sorted)
        if (seen == null || seen.add(Arrays.asList(normalize(row[0]), row[1])))
          accumulator.add(row[0], row[1]);
      return accumulator.result();
    }
  }

  /**
   * {@code string_agg(DISTINCT value, delimiter)}. PostgreSQL implements DISTINCT in an aggregate by sorting the argument rows
   * and skipping a row equal to the one before, so the pieces come in the order of the values (the {@code C} collation for text,
   * a {@code NULL} delimiter after the others), and not in the order of the input.
   */
  private static final class DistinctStringAgg implements Accumulator {
    private final Set<List<Object>> seen = new HashSet<>();
    private final List<String[]>    rows = new ArrayList<>();

    @Override
    public void add(final Object value) {
      add(value, null);
    }

    @Override
    public void add(final Object value, final Object delimiter) {
      if (value != null && seen.add(Arrays.asList(value, delimiter)))
        rows.add(new String[] { (String) value, (String) delimiter });
    }

    @Override
    public Object result() {
      if (rows.isEmpty())
        return null;
      rows.sort((a, b) -> {
        final int order = Text.compare(a[0], b[0]);
        if (order != 0)
          return order;
        if (a[1] == null)
          return b[1] == null ? 0 : 1;
        return b[1] == null ? -1 : Text.compare(a[1], b[1]);
      });
      final StringBuilder joined = new StringBuilder();
      for (int i = 0; i < rows.size(); i++) {
        if (i > 0 && rows.get(i)[1] != null)
          joined.append(rows.get(i)[1]);
        joined.append(rows.get(i)[0]);
      }
      return joined.toString();
    }
  }

  @Override
  public String toString() {
    return String.join("\n", steps()) + " " + Arrays.toString(outputs);
  }
}
