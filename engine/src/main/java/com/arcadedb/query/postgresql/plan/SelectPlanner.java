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
import com.arcadedb.query.postgresql.PostgreSQLAmbiguousIdentifierException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLReferenceException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Comparison;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.plan.join.BuildMemory;
import com.arcadedb.query.postgresql.plan.join.JoinPlanner;
import com.arcadedb.query.postgresql.plan.join.JoinWarnings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@code SELECT} of the subset into a {@link SelectPlan}: binds the names, types every expression with the evaluator,
 * and picks how the table is read.
 *
 * <p>Access paths for one table, chosen from the top level {@code AND} terms of the {@code WHERE} clause (the whole clause stays
 * as a filter, so the path never changes the answer): {@code id = <constant>} reads one record by its record id;
 * {@code column = <constant>} on a property that has an index of its own reads through the index; anything else scans the type,
 * with its subtypes unless the statement says {@code ONLY}.
 *
 * <p><b>Joins (S5).</b> A {@code FROM} clause with a join or with several items is planned by the
 * {@link JoinPlanner}, which gets the {@code WHERE} clause as well because it moves the terms of it below the joins: it hands
 * back one {@link RelationSource} and the terms that stay above. Everything after that works on the columns of that source.
 */
public class SelectPlanner {
  private final Database           database;
  private final RelationalCatalog  catalog;
  private final Parameters         parameters;
  private final long               joinMemoryCap;

  public SelectPlanner(final Database database, final Parameters parameters) {
    this(database, parameters, BuildMemory.DEFAULT_CAP_BYTES);
  }

  /**
   * @param joinMemoryCap the bytes of rows that one join may keep in memory before it stops with SQLSTATE {@code 53200}
   */
  public SelectPlanner(final Database database, final Parameters parameters, final long joinMemoryCap) {
    this.database = database;
    this.catalog = RelationalCatalog.of(database);
    this.parameters = parameters;
    this.joinMemoryCap = joinMemoryCap;
  }

  public RelationalCatalog catalog() {
    return catalog;
  }

  // ---- FROM ----

  /** The source of a {@code FROM} clause that has no join: no item ({@code SELECT 1}) or one table. */
  protected RelationSource planFrom(final List<TableReference> from) {
    if (from.isEmpty())
      return RelationSource.singleRow();
    if (from.size() == 1 && from.get(0) instanceof TableReference.Table table)
      return planTable(table);
    throw new IllegalArgumentException("A FROM clause with a join is planned by the JoinPlanner");
  }

  private static boolean hasJoin(final List<TableReference> from) {
    return from.size() > 1 || (from.size() == 1 && from.get(0) instanceof TableReference.Join);
  }

  /** What the join planner needs from this planner: the source of one table, and the access path for the terms pushed to it. */
  private JoinPlanner.Tables joinTables() {
    return new JoinPlanner.Tables() {
      @Override
      public TableSource table(final TableReference.Table reference) {
        return planTable(reference);
      }

      @Override
      public TableSource narrow(final TableSource table, final List<Expression> terms, final ExpressionCompiler compiler) {
        return chooseAccessPath(table, terms, compiler);
      }
    };
  }

  /** The rows of one table of the {@code FROM} clause, read by a scan until the {@code WHERE} clause picks an index. */
  protected TableSource planTable(final TableReference.Table reference) {
    final List<Name> name = reference.name();
    final Table table = name.size() == 1 ? catalog.table(identifier(name.get(0)))
        : catalog.table(identifier(name.get(0)), identifier(name.get(1)));
    final String relation = reference.alias() != null ? reference.alias().value() : table.name();
    return new TableSource(database, table, relation, reference.only());
  }

  private static Identifier identifier(final Name name) {
    return new Identifier(name.value(), name.quoted());
  }

  // ---- the plan ----

  public SelectPlan plan(final SelectStatement select) {
    final boolean grouping = !select.groupBy().isEmpty() || select.having() != null || hasAggregate(select);

    RelationSource source;
    JoinPlanner.Result joined = null;
    if (hasJoin(select.from())) {
      joined = new JoinPlanner(database, catalog, parameters, joinTables(), joinMemoryCap).plan(select.from(), select.where());
      source = joined.source();
    } else
      source = planFrom(select.from());
    final RelationScope scope = new RelationScope(source.columns(), catalog);
    final ExpressionCompiler compiler = new ExpressionCompiler(scope, parameters);

    Evaluable where = null;
    if (select.where() != null) {
      where = compiler.compileCondition(select.where(), "WHERE");
      if (joined != null) {
        // the join planner applied the terms it could place below the joins; the others are the filter on top
        final List<Evaluable> above = new ArrayList<>();
        for (final Expression term : joined.remaining())
          above.add(compiler.compileCondition(term, "WHERE"));
        where = above.isEmpty() ? null : JoinPlanner.allOf(above);
      } else if (source instanceof TableSource tableSource)
        source = chooseAccessPath(tableSource, select.where(), compiler);
    }

    // grouping: the select list, HAVING and ORDER BY are then typed over the group rows
    SelectPlan.GroupStage group = null;
    GroupingScope groupingScope = null;
    ExpressionCompiler outputCompiler = compiler;
    if (grouping) {
      final List<Expression> groupAsts = groupExpressions(select);
      final List<Evaluable> groupEvaluables = new ArrayList<>();
      for (final Expression expression : groupAsts)
        groupEvaluables.add(compiler.compile(expression));
      groupingScope = new GroupingScope(groupAsts, groupEvaluables, compiler);
      outputCompiler = new ExpressionCompiler(groupingScope, parameters);
    }

    // the select list
    final List<Evaluable> outputs = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<Expression> asts = new ArrayList<>();
    for (final SelectItem item : select.items()) {
      if (item instanceof SelectItem.Star star) {
        for (final Expression.ColumnRef reference : scope.expand(star)) {
          outputs.add(outputCompiler.compile(reference));
          names.add(reference.column().value());
          asts.add(reference);
        }
      } else if (item instanceof SelectItem.Projection projection) {
        outputs.add(outputCompiler.resolveOutput(outputCompiler.compile(projection.expression())));
        names.add(projection.alias() != null ? projection.alias().value() : outputName(projection.expression()));
        asts.add(projection.expression());
      }
    }

    Evaluable having = null;
    if (select.having() != null)
      having = outputCompiler.compileCondition(select.having(), "HAVING");

    // ORDER BY: a key is an output column (by position or name) or an expression over the input row
    final List<SelectPlan.SortKey> sortKeys = new ArrayList<>();
    final List<Evaluable> extras = new ArrayList<>();
    for (final SortItem item : select.orderBy()) {
      int outputIndex = outputIndex(item.expression(), names);
      if (outputIndex < 0 && select.distinct())
        outputIndex = asts.indexOf(item.expression());
      final Evaluable key;
      final int position;
      if (outputIndex >= 0) {
        key = outputs.get(outputIndex);
        position = outputIndex;
      } else {
        if (select.distinct())
          throw PostgreSQLReferenceException.invalidReference("for SELECT DISTINCT, ORDER BY expressions must appear in select list");
        key = outputCompiler.resolveOutput(outputCompiler.compile(item.expression()));
        position = outputs.size() + extras.size();
        extras.add(key);
      }
      if (!Comparison.isComparable(key.type()))
        throw new PostgreSQLFeatureNotSupportedException(
            "ORDER BY a value of type " + key.type().displayName() + " is not supported");
      final Boolean nullsFirst = item.nulls() == SortItem.Nulls.FIRST ? Boolean.TRUE
          : item.nulls() == SortItem.Nulls.LAST ? Boolean.FALSE : null;
      sortKeys.add(new SelectPlan.SortKey(position,
          Comparison.ordering(key.type(), item.direction() == SortItem.Direction.DESC, nullsFirst)));
    }

    // LIMIT and OFFSET: constants, typed bigint
    final ExpressionCompiler constants = new ExpressionCompiler(reference -> {
      throw PostgreSQLReferenceException.invalidReference("argument of LIMIT or OFFSET must not contain variables");
    }, parameters);
    final Evaluable limit = countOf(select.limit(), "LIMIT", constants);
    final Evaluable offset = countOf(select.offset(), "OFFSET", constants);

    if (groupingScope != null)
      group = groupingScope.stage();

    final List<PostgreSQLResultSet.Column> columns = new ArrayList<>();
    for (int i = 0; i < outputs.size(); i++)
      columns.add(new PostgreSQLResultSet.Column(names.get(i), outputs.get(i).typeName()));

    final List<String> steps = new ArrayList<>();
    steps.addAll(source.describeTree());
    if (where != null)
      steps.add("FILTER WHERE");
    if (group != null)
      steps.add("AGGREGATE " + group.keyCount() + " group key(s), " + group.aggregateCount() + " aggregate(s)");
    if (having != null)
      steps.add("FILTER HAVING");
    steps.add("PROJECT " + outputs.size() + " column(s)");
    if (select.distinct())
      steps.add("DISTINCT");
    if (!sortKeys.isEmpty())
      steps.add("SORT " + sortKeys.size() + " key(s)");
    if (offset != null)
      steps.add("OFFSET");
    if (limit != null)
      steps.add("LIMIT");
    // a join that no edge, link or index supports: the plan says so, and the engine writes the log line once per statement
    final List<String> warnings = JoinWarnings.of(source);
    for (final String warning : warnings)
      steps.add("WARNING: " + warning);

    return new SelectPlan(source, where, group, having, outputs.toArray(new Evaluable[0]), extras.toArray(new Evaluable[0]), columns,
        select.distinct(), sortKeys, limit, offset, steps, warnings);
  }

  // ---- access path ----

  private static List<Expression> conjuncts(final Expression expression) {
    final List<Expression> out = new ArrayList<>();
    if (expression instanceof Expression.BinaryOp op && op.operator() == Expression.BinaryOperator.AND) {
      out.addAll(conjuncts(op.left()));
      out.addAll(conjuncts(op.right()));
    } else
      out.add(expression);
    return out;
  }

  private TableSource chooseAccessPath(final TableSource table, final Expression where, final ExpressionCompiler compiler) {
    return chooseAccessPath(table, conjuncts(where), compiler);
  }

  /** The access path for a list of {@code AND} terms; the join planner calls it with the terms it pushed to one table. */
  private TableSource chooseAccessPath(final TableSource table, final List<Expression> terms, final ExpressionCompiler compiler) {
    TableSource best = table;
    for (final Expression conjunct : terms) {
      if (!(conjunct instanceof Expression.BinaryOp op) || op.operator() != Expression.BinaryOperator.EQUAL)
        continue;
      final Evaluable left = compiler.compile(op.left());
      final Evaluable right = compiler.compile(op.right());
      TableSource candidate = accessFor(table, left, right);
      if (candidate == null)
        candidate = accessFor(table, right, left);
      if (candidate == null)
        continue;
      if (candidate.access() == TableSource.Access.ID)
        return candidate;
      if (best.access() == TableSource.Access.SCAN)
        best = candidate;
    }
    return best;
  }

  /** The table read through {@code column = key}, or {@code null} when that is not an access path. */
  private static TableSource accessFor(final TableSource table, final Evaluable columnSide, final Evaluable keySide) {
    if (!(columnSide instanceof Evaluable.Slot slot) || !keySide.isConstant())
      return null;
    final Column column = table.table().columns().get(slot.index());
    final PgType columnType = slot.type();
    final PgType keyType = keySide.type();
    final boolean unknownText = keyType == PgType.UNKNOWN && keySide.eval(new Object[0]) instanceof String;
    final boolean fits = (columnType.isInteger() && keyType.isInteger()) || (columnType.isString() && (keyType.isString() || unknownText));
    if (!fits)
      return null;
    if (column.kind() == Column.Kind.IDENTITY && columnType.isString())
      return table.withIdLookup(column, keySide);
    if (table.hasIndexOn(column))
      return table.withIndexLookup(column, keySide);
    return null;
  }

  // ---- grouping ----

  private static boolean hasAggregate(final SelectStatement select) {
    for (final SelectItem item : select.items())
      if (item instanceof SelectItem.Projection projection && ExpressionCompiler.containsAggregate(projection.expression()))
        return true;
    for (final SortItem item : select.orderBy())
      if (ExpressionCompiler.containsAggregate(item.expression()))
        return true;
    return false;
  }

  /** The {@code GROUP BY} expressions, a position standing for the select list item it names. */
  private static List<Expression> groupExpressions(final SelectStatement select) {
    final List<Expression> out = new ArrayList<>();
    for (final Expression expression : select.groupBy()) {
      if (expression instanceof Expression.IntegerLiteral position) {
        final int index = position.value().bitLength() > 31 ? 0 : position.value().intValue();
        if (index < 1 || index > select.items().size())
          throw PostgreSQLReferenceException.invalidReference("GROUP BY position " + position.value() + " is not in select list");
        if (!(select.items().get(index - 1) instanceof SelectItem.Projection projection))
          throw new PostgreSQLFeatureNotSupportedException("GROUP BY the position of a * item is not supported");
        out.add(projection.expression());
      } else
        out.add(expression);
    }
    return out;
  }

  /**
   * The scope of the expressions of a grouping query. An expression equal to a {@code GROUP BY} expression reads that key from
   * the group row, an aggregate call is computed per group and read from the group row, and any other column reference is the
   * error PostgreSQL gives: {@code 42803}.
   */
  private static final class GroupingScope implements ExpressionCompiler.Scope {
    private final List<Expression>                groupAsts;
    private final List<Evaluable>                 groupEvaluables;
    private final ExpressionCompiler              inputCompiler;
    private final List<SelectPlan.AggregateSpec>  specs = new ArrayList<>();
    private final List<Expression.FunctionCall>   calls = new ArrayList<>();

    GroupingScope(final List<Expression> groupAsts, final List<Evaluable> groupEvaluables, final ExpressionCompiler inputCompiler) {
      this.groupAsts = groupAsts;
      this.groupEvaluables = groupEvaluables;
      this.inputCompiler = inputCompiler;
    }

    SelectPlan.GroupStage stage() {
      return new SelectPlan.GroupStage(groupEvaluables.toArray(new Evaluable[0]), specs);
    }

    @Override
    public Evaluable column(final Expression.ColumnRef reference) {
      throw new PostgreSQLEvaluationException("42803", "column \"" + reference.column().value()
          + "\" must appear in the GROUP BY clause or be used in an aggregate function");
    }

    @Override
    public Evaluable intercept(final Expression expression, final ExpressionCompiler compiler) {
      for (int i = 0; i < groupAsts.size(); i++)
        if (groupAsts.get(i).equals(expression)) {
          final Evaluable key = groupEvaluables.get(i);
          return new Evaluable.Slot(key.type(), key.typeName(), i);
        }
      if (expression instanceof Expression.FunctionCall call && ExpressionCompiler.isAggregate(call))
        return aggregate(call);
      return null;
    }

    private Evaluable aggregate(final Expression.FunctionCall call) {
      int known = calls.indexOf(call);
      final SelectPlan.AggregateSpec spec;
      if (known >= 0)
        spec = specs.get(known);
      else {
        spec = specOf(call);
        calls.add(call);
        specs.add(spec);
        known = specs.size() - 1;
      }
      return new Evaluable.Slot(spec.resultType(), spec.resultType().typeName(), groupAsts.size() + known);
    }

    private SelectPlan.AggregateSpec specOf(final Expression.FunctionCall call) {
      final String name = call.name().get(call.name().size() - 1).value();
      Evaluable argument = null;
      PgType argumentType = PgType.UNKNOWN;
      if (call.star()) {
        if (!name.equals("count"))
          throw PostgreSQLEvaluationException.noFunction(name);
      } else {
        if (call.args().size() != 1)
          throw PostgreSQLEvaluationException.noFunction(name);
        argument = inputCompiler.resolveOutput(inputCompiler.compile(call.args().get(0)));
        argumentType = argument.type();
      }
      final PgType result;
      switch (name) {
      case "count":
        result = PgType.INT8;
        break;
      case "sum":
        if (argumentType == PgType.INT2 || argumentType == PgType.INT4)
          result = PgType.INT8;
        else if (argumentType == PgType.INT8 || argumentType == PgType.NUMERIC)
          result = PgType.NUMERIC;
        else if (argumentType.isFloat())
          result = argumentType;
        else
          throw PostgreSQLEvaluationException.noFunction(name, argumentType);
        break;
      case "avg":
        if (argumentType.isInteger() || argumentType == PgType.NUMERIC)
          result = PgType.NUMERIC;
        else if (argumentType.isFloat())
          result = PgType.FLOAT8;
        else
          throw PostgreSQLEvaluationException.noFunction(name, argumentType);
        break;
      default:
        if (!Comparison.isComparable(argumentType))
          throw PostgreSQLEvaluationException.noFunction(name, argumentType);
        result = argumentType.isString() ? PgType.TEXT : argumentType;
        break;
      }
      return new SelectPlan.AggregateSpec(name, argumentType, result, argument, call.distinct());
    }
  }

  // ---- ORDER BY, LIMIT and names ----

  /** The output column an {@code ORDER BY} item names by position or by output name, {@code -1} for neither. */
  private static int outputIndex(final Expression expression, final List<String> names) {
    if (expression instanceof Expression.IntegerLiteral position) {
      final int index = position.value().bitLength() > 31 ? 0 : position.value().intValue();
      if (index < 1 || index > names.size())
        throw PostgreSQLReferenceException.invalidReference("ORDER BY position " + position.value() + " is not in select list");
      return index - 1;
    }
    if (expression instanceof Expression.ColumnRef reference && reference.qualifier().isEmpty()) {
      int found = -1;
      for (int i = 0; i < names.size(); i++)
        if (names.get(i).equals(reference.column().value())) {
          if (found >= 0)
            throw PostgreSQLAmbiguousIdentifierException.column(reference.column().value(),
                List.of(names.get(found), names.get(i)));
          found = i;
        }
      return found;
    }
    return -1;
  }

  private Evaluable countOf(final Expression expression, final String clause, final ExpressionCompiler constants) {
    if (expression == null)
      return null;
    final Evaluable count = constants.compile(expression);
    final Evaluable coerced = constants.coerce(count, PgType.INT8);
    if (coerced != null)
      return coerced;
    if (count.type().isNumber()) {
      final PgType from = count.type();
      return new Evaluable.Node(PgType.INT8, row -> Casts.cast(count.eval(row), from, PgType.INT8, null), count);
    }
    throw PostgreSQLEvaluationException.mismatch(
        "argument of " + clause + " must be type bigint, not type " + count.type().displayName());
  }

  /** The name PostgreSQL gives an output column that has no alias ({@code FigureColname} of {@code parse_target.c}). */
  static String outputName(final Expression expression) {
    if (expression instanceof Expression.ColumnRef reference)
      return reference.column().value();
    if (expression instanceof Expression.FunctionCall call)
      return call.name().get(call.name().size() - 1).value();
    if (expression instanceof Expression.SpecialFunction special)
      return special.kind().name().toLowerCase(Locale.ROOT);
    if (expression instanceof Expression.Case)
      return "case";
    if (expression instanceof Expression.Cast cast) {
      final String inner = outputName(cast.operand());
      return "?column?".equals(inner) ? cast.type().name().get(cast.type().name().size() - 1) : inner;
    }
    return "?column?";
  }
}
