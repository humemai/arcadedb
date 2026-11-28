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
package com.arcadedb.query.postgresql.eval;

import com.arcadedb.query.postgresql.ast.Expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * A function in {@code FROM} (fork issue #243): {@code generate_series} of numbers and of times, {@code unnest} of arrays, and
 * the functions of {@code jsonb} that return rows. Any other function that the language has returns one row of one column, as in
 * PostgreSQL. The rows come one at a time, so a long series is held in memory only by what reads it.
 */
public final class TableFunction {

  /** One column of the rows. */
  public record Column(String name, PgType type, String typeName) {
  }

  private interface Rows {
    Iterator<Object[]> of(Object[] row);
  }

  /** The functions that return a set of rows. */
  private static final Set<String> SET_RETURNING = Set.of("generate_series", "unnest", "jsonb_each", "jsonb_each_text",
      "jsonb_array_elements", "jsonb_array_elements_text", "jsonb_object_keys");

  private final List<Column> columns;
  private final boolean      namedByAlias;
  private final Rows         rows;

  private TableFunction(final List<Column> columns, final boolean namedByAlias, final Rows rows) {
    this.columns = List.copyOf(columns);
    this.namedByAlias = namedByAlias;
    this.rows = rows;
  }

  /** The columns of the rows, in order. */
  public List<Column> columns() {
    return columns;
  }

  /**
   * Whether the alias of the item in {@code FROM} names the column too: a function that returns one value has a column of the
   * alias, as in {@code FROM generate_series(1, 3) g}, and one with output parameters keeps their names.
   */
  public boolean namedByAlias() {
    return namedByAlias;
  }

  /** The rows for one row of what the arguments read; a {@code NULL} argument gives no row. */
  public Iterator<Object[]> rows(final Object[] input) {
    return rows.of(input);
  }

  /** The name of a call that is unqualified or in {@code pg_catalog}, in lower case, {@code null} for any other. */
  private static String name(final Expression.FunctionCall call) {
    if (call.name().size() > 2 || call.name().size() == 2 && !"pg_catalog".equals(call.name().get(0).value()))
      return null;
    return call.name().get(call.name().size() - 1).value().toLowerCase(Locale.ROOT);
  }

  /** Whether the call is of a function that returns a set of rows. */
  public static boolean isSetReturning(final Expression.FunctionCall call) {
    final String name = name(call);
    return name != null && call.over() == null && SET_RETURNING.contains(name);
  }

  /**
   * The function of a call, its arguments compiled by {@code compiler}.
   *
   * @throws RuntimeException the error of a call that the language cannot type
   */
  public static TableFunction compile(final Expression.FunctionCall call, final ExpressionCompiler compiler) {
    final String name = name(call);
    if (!isSetReturning(call)) {
      // a function that returns one value: one row
      final Evaluable value = compiler.resolveOutput(compiler.compile(call));
      return new TableFunction(List.of(new Column(name, value.type(), value.typeName())), true,
          row -> List.<Object[]>of(new Object[] { value.eval(row) }).iterator());
    }
    if (call.distinct() || call.star() || !call.orderBy().isEmpty() || call.filter() != null)
      throw PostgreSQLEvaluationException.unsupported("DISTINCT, *, ORDER BY and FILTER belong to an aggregate, and " + name
          + " is none: this call is not supported by the postgresql language");
    final Evaluable[] args = new Evaluable[call.args().size()];
    for (int i = 0; i < args.length; i++)
      args[i] = compiler.compile(call.args().get(i));
    return switch (name) {
      case "generate_series" -> series(name, args, compiler);
      case "unnest" -> unnest(name, args);
      default -> json(name, args, compiler);
    };
  }

  // ---- generate_series ----

  private static TableFunction series(final String name, final Evaluable[] args, final ExpressionCompiler compiler) {
    if (args.length < 2 || args.length > 3)
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    PgType first = args[0].type();
    PgType second = args[1].type();
    if (first.isDateTime() || second.isDateTime() || args.length == 3 && args[2].type() == PgType.INTERVAL)
      return timeSeries(name, args, compiler);
    if (first == PgType.UNKNOWN && second == PgType.UNKNOWN)
      throw new PostgreSQLEvaluationException("42725", "function " + name + "(unknown, unknown) is not unique");
    PgType type = PgType.INT4;
    for (final Evaluable arg : args) {
      final PgType found = arg.type();
      if (found == PgType.UNKNOWN)
        continue;
      if (!found.isNumber() || found.isFloat())
        throw PostgreSQLEvaluationException.noFunction(name, types(args));
      if (found == PgType.NUMERIC || found == PgType.INT8 && type != PgType.NUMERIC)
        type = found;
    }
    final Evaluable[] typed = new Evaluable[args.length];
    for (int i = 0; i < args.length; i++) {
      typed[i] = compiler.coerce(args[i], type);
      if (typed[i] == null)
        throw PostgreSQLEvaluationException.noFunction(name, types(args));
    }
    final PgType element = type;
    return new TableFunction(List.of(new Column(name, element, element.typeName())), true, row -> {
      final Object start = typed[0].eval(row);
      final Object stop = typed[1].eval(row);
      final Object step = typed.length == 3 ? typed[2].eval(row) : one(element);
      if (start == null || stop == null || step == null)
        return empty();
      if (element == PgType.NUMERIC)
        return numbers((BigDecimal) start, (BigDecimal) stop, (BigDecimal) step);
      return integers(((Number) start).longValue(), ((Number) stop).longValue(), ((Number) step).longValue(), element);
    });
  }

  private static Object one(final PgType type) {
    return type == PgType.NUMERIC ? BigDecimal.ONE : type == PgType.INT8 ? (Object) 1L : (Object) 1;
  }

  private static PostgreSQLEvaluationException zeroStep() {
    return new PostgreSQLEvaluationException("22023", "step size cannot equal zero");
  }

  private static Iterator<Object[]> integers(final long start, final long stop, final long step, final PgType type) {
    if (step == 0)
      throw zeroStep();
    return new Iterator<>() {
      private long    next = start;
      private boolean more = step > 0 ? start <= stop : start >= stop;

      @Override
      public boolean hasNext() {
        return more;
      }

      @Override
      public Object[] next() {
        if (!more)
          throw new NoSuchElementException();
        final long value = next;
        // the next value, unless it is past the end or past the range of bigint
        final long sum = value + step;
        more = ((value ^ sum) & (step ^ sum)) >= 0 && (step > 0 ? sum <= stop : sum >= stop);
        next = sum;
        return new Object[] { type == PgType.INT8 ? (Object) value : (Object) (int) value };
      }
    };
  }

  private static Iterator<Object[]> numbers(final BigDecimal start, final BigDecimal stop, final BigDecimal step) {
    if (step.signum() == 0)
      throw zeroStep();
    return new Iterator<>() {
      private BigDecimal next = start;

      @Override
      public boolean hasNext() {
        return step.signum() > 0 ? next.compareTo(stop) <= 0 : next.compareTo(stop) >= 0;
      }

      @Override
      public Object[] next() {
        if (!hasNext())
          throw new NoSuchElementException();
        final BigDecimal value = next;
        next = value.add(step);
        return new Object[] { value };
      }
    };
  }

  /** A series of timestamps: a date and an unknown literal are read as {@code timestamptz}, as PostgreSQL resolves the call. */
  private static TableFunction timeSeries(final String name, final Evaluable[] args, final ExpressionCompiler compiler) {
    if (args.length != 3)
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    final PgType type = args[0].type() == PgType.TIMESTAMP && args[1].type() != PgType.TIMESTAMPTZ && args[1].type() != PgType.DATE
        || args[1].type() == PgType.TIMESTAMP && args[0].type() == PgType.UNKNOWN ? PgType.TIMESTAMP : PgType.TIMESTAMPTZ;
    final Evaluable start = compiler.coerce(args[0], type);
    final Evaluable stop = compiler.coerce(args[1], type);
    final Evaluable step = compiler.coerce(args[2], PgType.INTERVAL);
    if (start == null || stop == null || step == null)
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    // value + step and the comparisons, over a row of the value, the step and the end
    final Evaluable value = new Evaluable.Slot(type, 0);
    final Evaluable add = compiler.arithmetic(Expression.BinaryOperator.ADD, value, new Evaluable.Slot(PgType.INTERVAL, 1));
    final Evaluable before = compiler.comparison(Expression.BinaryOperator.LESS_OR_EQUAL, value, new Evaluable.Slot(type, 2));
    final Evaluable after = compiler.comparison(Expression.BinaryOperator.GREATER_OR_EQUAL, value, new Evaluable.Slot(type, 2));
    return new TableFunction(List.of(new Column(name, type, type.typeName())), true, row -> {
      final Object from = start.eval(row);
      final Object to = stop.eval(row);
      final Object by = step.eval(row);
      if (from == null || to == null || by == null)
        return empty();
      final PgInterval interval = (PgInterval) by;
      final int direction = direction(interval);
      final Evaluable within = direction > 0 ? before : after;
      return new Iterator<>() {
        private Object next = from;

        @Override
        public boolean hasNext() {
          return next != null && Boolean.TRUE.equals(within.eval(new Object[] { next, interval, to }));
        }

        @Override
        public Object[] next() {
          if (!hasNext())
            throw new NoSuchElementException();
          final Object current = next;
          next = add.eval(new Object[] { current, interval, to });
          return new Object[] { current };
        }
      };
    });
  }

  /** 1 for a step forward, -1 for one backward. A step whose parts have both signs is refused: its direction is not obvious. */
  private static int direction(final PgInterval step) {
    final boolean forward = step.months() > 0 || step.days() > 0 || step.micros() > 0;
    final boolean backward = step.months() < 0 || step.days() < 0 || step.micros() < 0;
    if (forward && backward)
      throw PostgreSQLEvaluationException.unsupported("A step of generate_series with both positive and negative parts is not"
          + " supported by the postgresql language: write a step of one sign, as in interval '1 day'");
    if (!forward && !backward)
      throw zeroStep();
    return forward ? 1 : -1;
  }

  // ---- unnest ----

  /** {@code unnest(array, ...)}: one column for each array, a shorter one filled with {@code NULL}. */
  private static TableFunction unnest(final String name, final Evaluable[] args) {
    if (args.length == 0)
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    final PgType[] elements = new PgType[args.length];
    final List<Column> columns = new ArrayList<>(args.length);
    for (int i = 0; i < args.length; i++) {
      if (args[i].type() == PgType.UNKNOWN)
        throw new PostgreSQLEvaluationException("42725", "function unnest(unknown) is not unique. Hint: cast the argument, as in"
            + " unnest($1::text[])");
      if (args[i].type() != PgType.ARRAY)
        throw PostgreSQLEvaluationException.noFunction(name, types(args));
      final PgType element = ExpressionCompiler.declaredElement(args[i]);
      if (element == null || !ValueInput.isElementType(element))
        throw PostgreSQLEvaluationException.unsupported(
            "The element type of this array is not known to the language: cast it, as in x::text[]");
      elements[i] = element;
      columns.add(new Column(name, element, element.typeName()));
    }
    return new TableFunction(columns, args.length == 1, row -> {
      final List<List<Object>> values = new ArrayList<>(args.length);
      int longest = 0;
      for (int i = 0; i < args.length; i++) {
        final List<Object> items = ExpressionCompiler.elements(args[i].eval(row), elements[i]);
        values.add(items);
        if (items != null)
          longest = Math.max(longest, items.size());
      }
      final List<Object[]> out = new ArrayList<>(longest);
      for (int at = 0; at < longest; at++) {
        final Object[] tuple = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
          final List<Object> items = values.get(i);
          tuple[i] = items != null && at < items.size() ? items.get(at) : null;
        }
        out.add(tuple);
      }
      return out.iterator();
    });
  }

  // ---- jsonb ----

  private static TableFunction json(final String name, final Evaluable[] args, final ExpressionCompiler compiler) {
    final Evaluable argument = args.length != 1 ? null
        : args[0].type() == PgType.UNKNOWN ? compiler.coerce(args[0], PgType.JSONB) : args[0];
    if (argument == null || argument.type() != PgType.JSONB)
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    final boolean text = name.endsWith("_text");
    final PgType valueType = text ? PgType.TEXT : PgType.JSONB;
    final Column value = new Column("value", valueType, valueType.typeName());
    return switch (name) {
      case "jsonb_each", "jsonb_each_text" -> new TableFunction(
          List.of(new Column("key", PgType.TEXT, PgType.TEXT.typeName()), value), false, row -> {
        final Object json = view(argument.eval(row));
        if (json == null)
          return empty();
        if (!(json instanceof Map<?, ?> object))
          throw invalid("cannot call " + name + " on a non-object");
        final List<Object[]> out = new ArrayList<>(object.size());
        for (final String key : keys(object)) {
          final Object member = Jsonb.wrap(object.get(key));
          out.add(new Object[] { key, text ? Jsonb.asText(member) : member });
        }
        return out.iterator();
      });
      case "jsonb_array_elements", "jsonb_array_elements_text" -> new TableFunction(List.of(value), false, row -> {
        final Object json = view(argument.eval(row));
        if (json == null)
          return empty();
        if (!(json instanceof List<?> array))
          throw invalid("cannot extract elements from " + (json instanceof Map<?, ?> ? "an object" : "a scalar"));
        final List<Object[]> out = new ArrayList<>(array.size());
        for (final Object item : array) {
          final Object element = Jsonb.wrap(item);
          out.add(new Object[] { text ? Jsonb.asText(element) : element });
        }
        return out.iterator();
      });
      default -> new TableFunction(List.of(new Column(name, PgType.TEXT, PgType.TEXT.typeName())), true, row -> {
        final Object json = view(argument.eval(row));
        if (json == null)
          return empty();
        if (!(json instanceof Map<?, ?> object))
          throw invalid("cannot call " + name + " on " + (json instanceof List<?> ? "an array" : "a scalar"));
        final List<Object[]> out = new ArrayList<>(object.size());
        for (final String key : keys(object))
          out.add(new Object[] { key });
        return out.iterator();
      });
    };
  }

  /** One level of a {@code jsonb} value, {@code null} for SQL {@code NULL}; the JSON {@code null} is a value. */
  private static Object view(final Object value) {
    return value == null ? null : Jsonb.view(value);
  }

  /** The keys of an object in the order PostgreSQL keeps them. */
  private static List<String> keys(final Map<?, ?> object) {
    final List<String> keys = new ArrayList<>(object.size());
    for (final Object key : object.keySet())
      keys.add((String) key);
    keys.sort(Jsonb::compareKeys);
    return keys;
  }

  private static PostgreSQLEvaluationException invalid(final String message) {
    return new PostgreSQLEvaluationException("22023", message);
  }

  private static Iterator<Object[]> empty() {
    return List.<Object[]>of().iterator();
  }

  private static PgType[] types(final Evaluable[] args) {
    final PgType[] types = new PgType[args.length];
    for (int i = 0; i < args.length; i++)
      types[i] = args[i].type();
    return types;
  }
}
