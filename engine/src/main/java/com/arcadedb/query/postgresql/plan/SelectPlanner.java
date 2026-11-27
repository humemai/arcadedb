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
import com.arcadedb.query.postgresql.PostgreSQLCatalogException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLReferenceException;
import com.arcadedb.query.postgresql.PostgreSQLSyntaxException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SetOperationStatement;
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.ast.WithStatement;
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
import com.arcadedb.query.postgresql.eval.Subquery;
import com.arcadedb.query.postgresql.eval.SubqueryPlanner;
import com.arcadedb.query.postgresql.eval.VectorDistance;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.plan.infoschema.InformationSchema;
import com.arcadedb.query.postgresql.plan.join.BuildMemory;
import com.arcadedb.query.postgresql.plan.join.JoinPlanner;
import com.arcadedb.query.postgresql.plan.join.JoinWarnings;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

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
 *
 * <p><b>Queries inside queries.</b> A derived table (a subquery in {@code FROM}) and a common table expression ({@code WITH}) are
 * planned by this class too, and read as a {@link MaterializedSource}: the answer of the inner query, computed once and kept in
 * memory under the same cap as a join. A set operation ({@code UNION}, {@code INTERSECT}, {@code EXCEPT}) is a
 * {@link SetOperationPlan} over two such plans. {@link #planQuery} is the entry for every shape of query.
 */
public class SelectPlanner {
  private final Database           database;
  private final RelationalCatalog  catalog;
  private final Parameters         parameters;
  private final long               joinMemoryCap;
  /** The common table expressions that are visible here, by name; a query that has a {@code WITH} gets a planner of its own. */
  private final Map<String, Cte>   ctes;
  /** The scope of the query around this one when this is a correlated subquery run for one row of it; {@code null} otherwise. */
  private final ExpressionCompiler.Scope outerScope;
  private       int                unnamedSubqueries;

  /** A common table expression of a {@code WITH}: its kept rows, and the names and types its readers see. */
  private record Cte(Name name, MaterializedSource.Materialization rows, List<String> names, List<PgType> types,
                     List<String> typeNames) {
  }

  public SelectPlanner(final Database database, final Parameters parameters) {
    this(database, parameters, BuildMemory.configuredCapBytes());
  }

  /**
   * @param joinMemoryCap the bytes of rows that one operator that keeps rows (a join, the groups of a {@code GROUP BY}, a derived
   *     table) may keep in memory before it stops with SQLSTATE {@code 53200}
   */
  public SelectPlanner(final Database database, final Parameters parameters, final long joinMemoryCap) {
    this(database, RelationalCatalog.of(database), parameters, joinMemoryCap, Map.of());
  }

  /** A planner over a catalog that the caller keeps while the schema does not change (fork issue #88). */
  public SelectPlanner(final Database database, final RelationalCatalog catalog, final Parameters parameters) {
    this(database, catalog, parameters, BuildMemory.configuredCapBytes(), Map.of());
  }

  private SelectPlanner(final Database database, final RelationalCatalog catalog, final Parameters parameters,
      final long joinMemoryCap, final Map<String, Cte> ctes) {
    this(database, catalog, parameters, joinMemoryCap, ctes, null);
  }

  private SelectPlanner(final Database database, final RelationalCatalog catalog, final Parameters parameters,
      final long joinMemoryCap, final Map<String, Cte> ctes, final ExpressionCompiler.Scope outerScope) {
    this.database = database;
    this.catalog = catalog;
    this.parameters = parameters;
    this.joinMemoryCap = joinMemoryCap;
    this.ctes = ctes;
    this.outerScope = outerScope;
  }

  /** A planner for a query inside this one that reads columns of the query around it through {@code outer}. */
  private SelectPlanner inside(final ExpressionCompiler.Scope outer) {
    return new SelectPlanner(database, catalog, parameters, joinMemoryCap, ctes, outer);
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
      return planRelation(table);
    if (from.size() == 1 && from.get(0) instanceof TableReference.Subquery subquery)
      return planDerived(subquery);
    throw new IllegalArgumentException("A FROM clause with a join is planned by the JoinPlanner");
  }

  private static boolean hasJoin(final List<TableReference> from) {
    return from.size() > 1 || (from.size() == 1 && from.get(0) instanceof TableReference.Join);
  }

  /** What an expression of a write asks for a subquery ({@code WHERE}, {@code SET}, {@code VALUES}): the planner of a {@code SELECT}. */
  public SubqueryPlanner subqueries() {
    return subqueryPlanner();
  }

  /** What the join planner needs from this planner: the source of one table, and the access path for the terms pushed to it. */
  private JoinPlanner.Tables joinTables() {
    return new JoinPlanner.Tables() {
      @Override
      public RelationSource table(final TableReference.Table reference) {
        return planRelation(reference);
      }

      @Override
      public SubqueryPlanner subqueries() {
        return subqueryPlanner();
      }

      @Override
      public RelationSource derived(final TableReference.Subquery reference) {
        return planDerived(reference);
      }

      @Override
      public ExpressionCompiler.Scope outer() {
        return outerScope;
      }

      @Override
      public TableSource narrow(final TableSource table, final List<Expression> terms, final ExpressionCompiler compiler) {
        return chooseAccessPath(table, terms, compiler);
      }
    };
  }

  /**
   * The source of one name of the {@code FROM} clause: a view of {@code information_schema}, which is answered before the tables
   * of the catalog, or else a table ({@link #planTable}). Every leaf of a join goes through here, so that the views can be joined
   * to tables and to each other.
   */
  protected RelationSource planRelation(final TableReference.Table reference) {
    // a name that a WITH defines hides a table of that name
    if (reference.name().size() == 1) {
      final Cte cte = ctes.get(reference.name().get(0).value());
      if (cte != null)
        return cteSource(cte, reference.alias() != null ? reference.alias().value() : cte.name().value());
    }
    final RelationSource view = InformationSchema.resolve(reference, catalog, database);
    return view != null ? view : planTable(reference);
  }

  /** The rows of one table of the {@code FROM} clause, read by a scan until the {@code WHERE} clause picks an index. */
  protected TableSource planTable(final TableReference.Table reference) {
    final List<Name> name = reference.name();
    final Table table = name.size() == 1 ? catalog.table(identifier(name.get(0)))
        : catalog.table(identifier(name.get(0)), identifier(name.get(1)));
    final String relation = reference.alias() != null ? reference.alias().value() : table.name();
    final TableSource source = new TableSource(database, table, relation, reference.only());
    return namedColumns == null ? source : source.withColumnNames(namedColumns);
  }

  /** The column names of the query being planned, {@code null} when its tables read every column (fork issue #90). */
  private Set<String> namedColumns;

  private static Identifier identifier(final Name name) {
    return new Identifier(name.value(), name.quoted());
  }

  // ---- the plan ----

  /**
   * The plan of a query of any shape: a {@code SELECT}, a set operation, or either with a {@code WITH}.
   *
   * @param statement a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement}
   */
  public QueryPlan planQuery(final Statement statement) {
    return planQuery(statement, false);
  }

  /**
   * @param operand true for an operand of a set operation: a column that is only a string literal or {@code NULL} then keeps the
   *                type {@code unknown}, which the set operation resolves together with the other operand
   */
  private QueryPlan planQuery(final Statement statement, final boolean operand) {
    return switch (statement) {
      case SelectStatement select -> plan(select, operand);
      case SetOperationStatement set -> planSetOperation(set);
      case WithStatement with -> planWith(with, operand);
      default -> throw new IllegalArgumentException("Not a query: " + statement);
    };
  }

  public SelectPlan plan(final SelectStatement select) {
    return plan(select, false);
  }

  /**
   * The plan of the {@code SELECT} of an {@code INSERT ... SELECT}: an output that is only a string literal or {@code NULL} keeps the
   * type {@code unknown}, and the column that takes it decides what it is, as PostgreSQL resolves it (a {@code NULL} is a
   * {@code bigint} for a {@code bigint} column, {@code '5'} is a number for a number column).
   */
  public SelectPlan planForInsert(final SelectStatement select) {
    return plan(select, true);
  }

  private SelectPlan plan(final SelectStatement select, final boolean keepUnknown) {
    // the tables of this query read only the columns that it names anywhere (fork issue #90); a nested query sets its own
    final Set<String> outerNames = namedColumns;
    namedColumns = ColumnNames.of(select);
    try {
      return planSelect(select, keepUnknown);
    } finally {
      namedColumns = outerNames;
    }
  }

  /**
   * The lines of a plan: those of the source, then one for each step applied to its rows, then the warnings. They are built when
   * they are read, from these values alone.
   *
   * @param exactReason why a nearest neighbour search is an exact scan, {@code null} for none
   * @param groupKeys   the number of group keys, -1 for a statement that does not group
   * @param windows     the number of window functions, 0 for none
   */
  private record PlanSteps(Supplier<List<String>> source, String exactReason, boolean where, int groupKeys, int aggregates,
      boolean having, int windows, int outputs, boolean distinct, int sortKeys, boolean offset, boolean limit,
      List<String> warnings) implements Supplier<List<String>> {
    @Override
    public List<String> get() {
      final List<String> steps = new ArrayList<>(source.get());
      if (exactReason != null)
        steps.add("SCAN + SORT exact distance (" + exactReason + ")");
      if (where)
        steps.add("FILTER WHERE");
      if (groupKeys >= 0)
        steps.add("AGGREGATE " + groupKeys + " group key(s), " + aggregates + " aggregate(s)");
      if (having)
        steps.add("FILTER HAVING");
      if (windows > 0)
        steps.add("WINDOW " + windows + " function(s)");
      steps.add("PROJECT " + outputs + " column(s)");
      if (distinct)
        steps.add("DISTINCT");
      if (sortKeys > 0)
        steps.add("SORT " + sortKeys + " key(s)");
      if (offset)
        steps.add("OFFSET");
      if (limit)
        steps.add("LIMIT");
      for (final String warning : warnings)
        steps.add("WARNING: " + warning);
      return steps;
    }
  }

  private SelectPlan planSelect(final SelectStatement select, final boolean keepUnknown) {
    // PostgreSQL takes the terms that every branch of an OR has in common out of it before it plans (prepqual.c)
    final Expression whereClause = OrFactoring.factor(select.where());
    final boolean grouping = !select.groupBy().isEmpty() || select.having() != null || hasAggregate(select);

    RelationSource source;
    JoinPlanner.Result joined = null;
    if (hasJoin(select.from())) {
      joined = new JoinPlanner(database, catalog, parameters, joinTables(), joinMemoryCap).plan(select.from(), whereClause);
      source = joined.source();
    } else
      source = planFrom(select.from());
    final RelationScope scope = new RelationScope(source.columns(), catalog, subqueryPlanner(), source.outputs(), outerScope);
    final ExpressionCompiler compiler = new ExpressionCompiler(scope, parameters);

    Evaluable where = null;
    if (whereClause != null) {
      where = compiler.compileCondition(whereClause, "WHERE");
      if (joined != null) {
        // the join planner applied the terms it could place below the joins; the others are the filter on top
        final List<Evaluable> above = new ArrayList<>();
        for (final Expression term : joined.remaining())
          above.add(compiler.compileCondition(term, "WHERE"));
        where = above.isEmpty() ? null : JoinPlanner.allOf(above);
      } else if (source instanceof TableSource tableSource)
        source = chooseAccessPath(tableSource, whereClause, compiler);
    }
    // the columns the WHERE clause reads, now that nothing else is compiled (fork issue #85)
    final BitSet whereColumns = scope.usedColumns();
    final boolean whereAskedForSubquery = scope.askedForSubquery();

    // grouping: the select list, HAVING and ORDER BY are then typed over the group rows
    SelectPlan.GroupStage group = null;
    GroupingScope groupingScope = null;
    ExpressionCompiler outputCompiler = compiler;
    if (grouping) {
      final List<Expression> groupAsts = groupExpressions(select);
      final List<Evaluable> groupEvaluables = new ArrayList<>();
      for (final Expression expression : groupAsts)
        groupEvaluables.add(compiler.compile(expression));
      groupingScope = new GroupingScope(groupAsts, groupEvaluables, compiler, subqueryPlanner());
      outputCompiler = new ExpressionCompiler(groupingScope, parameters);
    }

    // window functions: the select list and ORDER BY compile one to a slot after the input row, which the window stage fills;
    // WHERE, GROUP BY and HAVING refuse it (42P20), and a grouping query refuses it with 0A000
    WindowScope windowScope = null;
    ExpressionCompiler listCompiler = outputCompiler;
    if (!grouping) {
      windowScope = new WindowScope(scope, compiler, source.columns().size());
      listCompiler = new ExpressionCompiler(windowScope, parameters);
    }

    // the select list
    final List<Evaluable> outputs = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<Expression> asts = new ArrayList<>();
    for (final SelectItem item : select.items()) {
      if (item instanceof SelectItem.Star star) {
        for (final Expression.ColumnRef reference : scope.expand(star)) {
          outputs.add(listCompiler.compile(reference));
          names.add(reference.column().value());
          asts.add(reference);
        }
      } else if (item instanceof SelectItem.Projection projection) {
        final Evaluable compiled = listCompiler.compile(projection.expression());
        final Evaluable output = keepUnknown ? compiled : listCompiler.resolveOutput(compiled);
        if (output.type() == PgType.INTERVAL)
          throw new PostgreSQLFeatureNotSupportedException(
              "A result column of type interval is not supported: an interval is read only as an operand of date arithmetic");
        outputs.add(output);
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
        key = listCompiler.resolveOutput(listCompiler.compile(item.expression()));
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

    // ORDER BY <distance> LIMIT k: an index search when an LSM_VECTOR index of the metric exists, else the exact scan and sort
    String exactReason = null;
    final VectorDistance nearest = nearestNeighbourKey(select, grouping, sortKeys, outputs, extras);
    if (nearest != null) {
      if (where != null)
        exactReason = "a WHERE clause is an exact scan";
      else if (source instanceof TableSource tableSource) {
        final VectorSearchSource.Choice choice = VectorSearchSource.choose(database, tableSource, nearest, limit, offset);
        if (choice.source() != null)
          source = choice.source();
        else
          exactReason = choice.reason();
      } else
        exactReason = "the statement does not read one table";
    }

    if (groupingScope != null)
      group = groupingScope.stage(joinMemoryCap);

    final List<PostgreSQLResultSet.Column> columns = new ArrayList<>();
    for (int i = 0; i < outputs.size(); i++)
      columns.add(new PostgreSQLResultSet.Column(names.get(i), outputs.get(i).typeName()));

    // a join that no edge, link or index supports: the plan says so, and the engine writes the log line once per statement
    final List<String> warnings = JoinWarnings.of(source);
    final WindowStage windows = windowScope == null || windowScope.isEmpty() ? null : windowScope.stage();
    // the plan lines are built when they are read (EXPLAIN, the plan of a result set): a statement is planned on every call
    final PlanSteps steps = new PlanSteps(source.planLines(), exactReason, where != null, group == null ? -1 : group.keyCount(),
        group == null ? 0 : group.aggregateCount(), having != null, windows == null ? 0 : windows.count(), outputs.size(),
        select.distinct(), sortKeys.size(), offset != null, limit != null, warnings);

    // A scan of one table reads only the columns that the compiled expressions read, and applies the WHERE clause to a row that holds
    // only its own columns, reading the rest for the records that pass (fork issue #85). The plan lines above are those of the
    // statement, whatever the scan does. Not for a join, a subquery (either way), a window function or a source that is not a scan.
    Evaluable filter = where;
    if (joined == null && !readsAQueryAround() && windows == null && !scope.askedForSubquery() && source instanceof TableSource scan
        && scan.access() == TableSource.Access.SCAN) {
      if (where != null && !whereAskedForSubquery) {
        source = scan.withScanFilter(where, whereColumns, scope.usedColumns());
        filter = null;
      } else
        source = scan.withReadColumns(scope.usedColumns());
    }

    return new SelectPlan(source, filter, group, having, outputs.toArray(new Evaluable[0]), extras.toArray(new Evaluable[0]), columns,
        select.distinct(), sortKeys, limit, offset, steps, warnings, windows);
  }

  /** Whether an expression of this planner's query can read a column of a query around it. */
  private boolean readsAQueryAround() {
    return outerScope != null && !(outerScope instanceof UnreadableScope unreadable && unreadable.readsNothing());
  }

  // ---- queries inside queries ----

  /**
   * What the expressions of this {@code SELECT} ask for a subquery: it is planned with the common table expressions that are visible
   * here, run once when the first row needs it, and its rows are kept under the memory cap of the joins (SQLSTATE {@code 53200}).
   */
  private SubqueryPlanner subqueryPlanner() {
    return new SubqueryPlanner() {
      @Override
      public Subquery plan(final Statement query) {
        return planSubquery(query);
      }

      @Override
      public Evaluable exists(final Statement query, final ExpressionCompiler outer) {
        return planExists(query, outer);
      }

      @Override
      public CorrelatedRows correlated(final Statement query, final ExpressionCompiler outer) {
        return planCorrelated(query, outer);
      }
    };
  }

  /**
   * A subquery that reads columns of the query around it: planned once with placeholders to learn which columns it reads (none:
   * it is no correlated subquery, {@code null}), then planned again for each different row of values of those columns, run, and
   * remembered under the values. Each plan is the plan of a query whose outer columns are constants, so a key equality on an
   * indexed column is an index lookup. The answers kept are bounded in number; a subquery that reads many different rows of the
   * outer query costs one plan and one run for each.
   */
  private SubqueryPlanner.CorrelatedRows planCorrelated(final Statement query, final ExpressionCompiler outer) {
    final OuterBindings discovery = OuterBindings.discovering(outer);
    final QueryPlan probe = inside(discovery).planQuery(query, false);
    if (discovery.isEmpty())
      return null;
    if (query instanceof SelectStatement select)
      refuseAggregatesOfTheOuterQuery(select, discovery);
    final List<Evaluable> readers = discovery.readers();
    final List<PgType> types = probe.outputTypes();
    final List<String> typeNames = new ArrayList<>();
    for (final PostgreSQLResultSet.Column column : probe.columns())
      typeNames.add(column.typeName());
    final Map<List<Object>, List<Object[]>> answers = new java.util.LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(final Map.Entry<List<Object>, List<Object[]>> eldest) {
        return size() > MAX_REMEMBERED_ANSWERS;
      }
    };
    return new SubqueryPlanner.CorrelatedRows() {
      @Override
      public List<PgType> types() {
        return types;
      }

      @Override
      public List<String> typeNames() {
        return typeNames;
      }

      @Override
      public synchronized List<Object[]> rows(final Object[] outerRow) {
        final List<Object> key = new ArrayList<>(readers.size());
        for (final Evaluable reader : readers)
          key.add(SelectPlan.normalize(reader.eval(outerRow)));
        List<Object[]> rows = answers.get(key);
        if (rows == null) {
          final QueryPlan plan = inside(discovery.bind(outerRow)).planQuery(query, false);
          rows = new MaterializedSource.Materialization(plan, joinMemoryCap, "a correlated subquery").rows();
          answers.put(key, rows);
        }
        return rows;
      }
    };
  }

  /**
   * An aggregate whose arguments read only columns of the outer query ({@code (SELECT max(p.pid))}) is computed by the outer query in
   * PostgreSQL, over the rows of its group. Here the subquery would compute it over its own rows, which is another answer, so the
   * statement is refused.
   */
  private static void refuseAggregatesOfTheOuterQuery(final SelectStatement select, final OuterBindings outer) {
    final List<Expression> expressions = new ArrayList<>();
    for (final SelectItem item : select.items())
      if (item instanceof SelectItem.Projection projection)
        expressions.add(projection.expression());
    if (select.having() != null)
      expressions.add(select.having());
    for (final SortItem item : select.orderBy())
      expressions.add(item.expression());
    for (final Expression expression : expressions)
      if (aggregateOfTheOuterQuery(expression, outer))
        throw new PostgreSQLFeatureNotSupportedException(
            "An aggregate over columns of the outer query only, inside a subquery, is not supported");
  }

  private static boolean aggregateOfTheOuterQuery(final Expression expression, final OuterBindings outer) {
    if (expression instanceof Expression.FunctionCall call && ExpressionCompiler.isAggregate(call)) {
      final List<Expression.ColumnRef> references = new ArrayList<>();
      for (final Expression argument : call.args())
        referencesIn(argument, references);
      if (references.isEmpty())
        return false;
      for (final Expression.ColumnRef reference : references)
        if (!outer.reads(reference))
          return false;
      return true;
    }
    for (final Expression child : ExpressionCompiler.children(expression))
      if (aggregateOfTheOuterQuery(child, outer))
        return true;
    return false;
  }

  /** How many answers of a correlated subquery are kept, by the values of the outer columns it reads. */
  private static final int MAX_REMEMBERED_ANSWERS = 10_000;

  private Subquery planSubquery(final Statement query) {
    final QueryPlan plan = planQuery(query, false);
    final MaterializedSource.Materialization kept = new MaterializedSource.Materialization(plan, joinMemoryCap, "a subquery");
    final List<PgType> types = plan.outputTypes();
    final List<String> typeNames = new ArrayList<>();
    for (final PostgreSQLResultSet.Column column : plan.columns())
      typeNames.add(column.typeName());
    return new Subquery() {
      @Override
      public List<PgType> types() {
        return types;
      }

      @Override
      public List<String> typeNames() {
        return typeNames;
      }

      @Override
      public List<Object[]> rows() {
        return kept.rows();
      }
    };
  }

  // ---- EXISTS ----

  /** Which query a column reference belongs to, for the references of a term of the {@code WHERE} clause of a subquery. */
  private enum Side {
    NONE, INNER, OUTER, MIXED;

    /** The side of what reads this side and {@code other}. */
    Side and(final Side other) {
      return other == NONE || other == this ? this : this == NONE ? other : MIXED;
    }
  }

  /**
   * {@code EXISTS (query)}. A subquery that reads no column of the query around it is run once, with {@code LIMIT 1}, and the answer
   * is whether it had a row. A correlated one is a hash semi join: its {@code WHERE} terms that read the outer query must be
   * equalities between an expression of the subquery and an expression of the outer query; those are taken out, the subquery runs
   * once for the expressions of its side of them, and the rows are kept in a hash set (under the memory cap). An outer row is then a
   * probe: its keys are in the set or not, and a {@code NULL} key never is, because no equality with {@code NULL} is true.
   * {@code NOT EXISTS} is the negation, an anti join, and keeps the rows with a {@code NULL} key.
   *
   * @throws PostgreSQLFeatureNotSupportedException for any other correlation
   */
  private Evaluable planExists(final Statement query, final ExpressionCompiler outer) {
    // the subquery is planned apart from the query around it; the terms taken out as keys are compiled by that query's compiler
    final SelectPlanner apart = inside(new UnreadableScope(outer, outerScope));
    if (query instanceof SelectStatement select && !select.from().isEmpty() && select.where() != null) {
      final Evaluable correlated = apart.correlatedExists(select, outer);
      if (correlated != null)
        return correlated;
    }
    final Statement asked = query instanceof SelectStatement select && select.limit() == null && select.offset() == null
        ? new SelectStatement(select.position(), select.distinct(), existsItems(select, outer), select.from(), select.where(),
        select.groupBy(), select.having(), select.orderBy(),
        new Expression.IntegerLiteral(select.position(), java.math.BigInteger.ONE), null)
        : query;
    final Subquery subquery = apart.planSubquery(asked);
    return new Evaluable.Node(PgType.BOOL, row -> !subquery.rows().isEmpty());
  }

  /**
   * The select list of an {@code EXISTS} subquery, whose values nobody reads: a bare column name that the query around shows becomes a
   * constant. PostgreSQL accepts such a name whichever query it takes it from, and {@link UnreadableScope} would refuse it where a
   * table of the subquery hides a column of that name. Not for a grouping or {@code DISTINCT} query, where the name can matter.
   */
  private static List<SelectItem> existsItems(final SelectStatement select, final ExpressionCompiler outer) {
    if (select.distinct() || !select.groupBy().isEmpty() || select.having() != null || hasAggregate(select))
      return select.items();
    final List<SelectItem> items = new ArrayList<>(select.items().size());
    for (final SelectItem item : select.items())
      if (item instanceof SelectItem.Projection projection && projection.expression() instanceof Expression.ColumnRef reference
          && reference.qualifier().isEmpty() && outer.showsHere(reference.column()))
        items.add(new SelectItem.Projection(projection.position(),
            new Expression.IntegerLiteral(projection.position(), java.math.BigInteger.ONE), projection.alias()));
      else
        items.add(item);
    return items;
  }

  private Evaluable correlatedExists(final SelectStatement select, final ExpressionCompiler outer) {
    // the columns that the subquery's own FROM clause offers decide which references are its own and which are the outer query's
    final RelationSource innerSource = hasJoin(select.from())
        ? new JoinPlanner(database, catalog, parameters, joinTables(), joinMemoryCap).plan(select.from(), null).source()
        : planFrom(select.from());
    final RelationScope innerScope = new RelationScope(innerSource.columns(), catalog, subqueryPlanner(), innerSource.outputs());
    final Sides sides = new Sides(innerScope, outer);

    final List<Expression> local = new ArrayList<>();
    final List<Expression> innerKeys = new ArrayList<>();
    final List<Expression> outerKeys = new ArrayList<>();
    final List<Expression> residual = new ArrayList<>();
    for (final Expression term : conjuncts(select.where())) {
      if (hasExists(term)) {
        local.add(term);
        continue;
      }
      final Side whole = sides.of(term);
      if (whole == Side.NONE || whole == Side.INNER) {
        local.add(term);
        continue;
      }
      if (term instanceof Expression.BinaryOp equality && equality.operator() == Expression.BinaryOperator.EQUAL) {
        final Side left = sides.of(equality.left());
        final Side right = sides.of(equality.right());
        if (left == Side.INNER && right == Side.OUTER) {
          innerKeys.add(equality.left());
          outerKeys.add(equality.right());
          continue;
        }
        if (left == Side.OUTER && right == Side.INNER) {
          innerKeys.add(equality.right());
          outerKeys.add(equality.left());
          continue;
        }
      }
      // any other term that reads the outer query (a comparison, an OR, a term of the outer query alone) is tested on the rows that
      // the equalities select
      residual.add(term);
    }
    if (outerKeys.isEmpty() && residual.isEmpty())
      return null;

    if (!select.groupBy().isEmpty() || select.having() != null || select.limit() != null || select.offset() != null)
      throw new PostgreSQLFeatureNotSupportedException("A correlated EXISTS with GROUP BY, HAVING, LIMIT or OFFSET is not supported");
    for (final SelectItem item : select.items())
      if (item instanceof SelectItem.Projection projection && ExpressionCompiler.containsAggregate(projection.expression()))
        throw new PostgreSQLFeatureNotSupportedException("A correlated EXISTS with an aggregate is not supported");

    // the residual terms, with each maximal expression of the subquery's side and of the outer side taken out as a column of a
    // combined row: the outer ones first, then the subquery's
    final List<Expression> innerLeaves = new ArrayList<>();
    final List<Expression> outerLeaves = new ArrayList<>();
    final List<Expression> tests = new ArrayList<>();
    for (final Expression term : residual)
      tests.add(splitResidual(term, sides, innerLeaves, outerLeaves));

    // the subquery that gives the keys and the columns that the residual terms read: its own terms
    final List<SelectItem> items = new ArrayList<>();
    for (int i = 0; i < innerKeys.size(); i++)
      items.add(new SelectItem.Projection(select.position(), innerKeys.get(i), new Name(select.position(), "k" + i, true)));
    for (int i = 0; i < innerLeaves.size(); i++)
      items.add(new SelectItem.Projection(select.position(), innerLeaves.get(i), new Name(select.position(), "l" + i, true)));
    if (items.isEmpty())
      items.add(new SelectItem.Projection(select.position(), new Expression.IntegerLiteral(select.position(), java.math.BigInteger.ONE),
          new Name(select.position(), "one", true)));
    Expression rest = null;
    for (final Expression term : local)
      rest = rest == null ? term : new Expression.BinaryOp(select.position(), Expression.BinaryOperator.AND, rest, term);
    final QueryPlan plan = planQuery(new SelectStatement(select.position(), false, items, select.from(), rest, List.of(), null,
        List.of(), null, null), false);
    final MaterializedSource.Materialization kept = new MaterializedSource.Materialization(plan, joinMemoryCap, "an EXISTS subquery");

    final int count = outerKeys.size();
    final Evaluable[] outerEvaluables = new Evaluable[count];
    final List<Function<Object, Object>> innerToCommon = new ArrayList<>();
    final List<Function<Object, Object>> outerToCommon = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      final Evaluable key = outer.resolveOutput(outer.compile(outerKeys.get(i)));
      final PgType innerType = plan.outputTypes().get(i);
      final PgType outerType = key.type();
      // "inner = outer" is typed as PostgreSQL types it: no such operator is 42883
      outer.comparison(Expression.BinaryOperator.EQUAL, new Evaluable.Slot(innerType, plan.columns().get(i).typeName(), 0),
          new Evaluable.Slot(outerType, key.typeName(), 1));
      final PgType common = SetTypes.resolve(innerType, outerType, "EXISTS");
      outerEvaluables[i] = key;
      innerToCommon.add(value -> SelectPlan.normalize(SetTypes.convert(value, innerType, common)));
      outerToCommon.add(value -> SelectPlan.normalize(SetTypes.convert(value, outerType, common)));
    }

    // the terms that are not equalities, compiled over the combined row [outer leaves..., subquery leaves...]
    Evaluable test = null;
    final Evaluable[] outerLeafValues = new Evaluable[outerLeaves.size()];
    if (!tests.isEmpty()) {
      final List<RelationSource.Field> fields = new ArrayList<>();
      for (int i = 0; i < outerLeaves.size(); i++) {
        outerLeafValues[i] = outer.resolveOutput(outer.compile(outerLeaves.get(i)));
        fields.add(new RelationSource.Field("__exists", "__o" + i, outerLeafValues[i].type(), outerLeafValues[i].typeName()));
      }
      for (int i = 0; i < innerLeaves.size(); i++)
        fields.add(new RelationSource.Field("__exists", "__i" + i, plan.outputTypes().get(count + i),
            plan.columns().get(count + i).typeName()));
      Expression whole = null;
      for (final Expression term : tests)
        whole = whole == null ? term : new Expression.BinaryOp(select.position(), Expression.BinaryOperator.AND, whole, term);
      test = new ExpressionCompiler(new RelationScope(fields, catalog), parameters).compileCondition(whole, "WHERE");
    }
    final Evaluable residualTest = test;
    final int outerWidth = outerLeaves.size();

    final ExistsKeys probe = new ExistsKeys(kept, innerToCommon, innerLeaves.size());
    return new Evaluable.Node(PgType.BOOL, row -> {
      final List<Object> key = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        final Object value = outerEvaluables[i].eval(row);
        if (value == null)
          return Boolean.FALSE;
        key.add(outerToCommon.get(i).apply(value));
      }
      final List<Object[]> candidates = probe.load().get(key);
      if (candidates == null)
        return Boolean.FALSE;
      if (residualTest == null)
        return Boolean.TRUE;
      final Object[] combined = new Object[outerWidth + innerLeaves.size()];
      for (int i = 0; i < outerWidth; i++)
        combined[i] = outerLeafValues[i].eval(row);
      for (final Object[] leaves : candidates) {
        System.arraycopy(leaves, 0, combined, outerWidth, leaves.length);
        if (Boolean.TRUE.equals(residualTest.eval(combined)))
          return Boolean.TRUE;
      }
      return Boolean.FALSE;
    });
  }

  /**
   * A term of the {@code WHERE} clause of an {@code EXISTS} subquery that reads the outer query and is no key equality, rewritten
   * so that it reads columns of a combined row: each maximal expression that reads only the subquery's columns becomes {@code __i<k>}
   * (collected in {@code innerLeaves}), each that reads only the outer query's becomes {@code __o<j>} ({@code outerLeaves}), and the
   * operators between them stay: comparisons, {@code AND}, {@code OR}, {@code NOT}, {@code IS NULL}. A scalar or {@code IN}
   * subquery is part of one such expression, with every column that it reads ({@link Sides}).
   *
   * @throws PostgreSQLFeatureNotSupportedException for a term that mixes both sides inside any other expression
   */
  private Expression splitResidual(final Expression term, final Sides sides, final List<Expression> innerLeaves,
      final List<Expression> outerLeaves) {
    final Side side = sides.of(term);
    final var position = term.position();
    if (side == Side.NONE)
      return term;
    if (side == Side.INNER || side == Side.OUTER) {
      final List<Expression> leaves = side == Side.INNER ? innerLeaves : outerLeaves;
      leaves.add(term);
      return new Expression.ColumnRef(position, List.of(), new Name(position, (side == Side.INNER ? "__i" : "__o")
          + (leaves.size() - 1), true));
    }
    if (term instanceof Expression.BinaryOp binary)
      return new Expression.BinaryOp(position, binary.operator(), splitResidual(binary.left(), sides, innerLeaves, outerLeaves),
          splitResidual(binary.right(), sides, innerLeaves, outerLeaves));
    if (term instanceof Expression.UnaryOp unary)
      return new Expression.UnaryOp(position, unary.operator(), splitResidual(unary.operand(), sides, innerLeaves, outerLeaves));
    if (term instanceof Expression.IsNull isNull)
      return new Expression.IsNull(position, splitResidual(isNull.operand(), sides, innerLeaves, outerLeaves), isNull.negated());
    throw new PostgreSQLFeatureNotSupportedException("A correlated subquery is supported in EXISTS and NOT EXISTS when a term of its "
        + "WHERE clause that reads a column of the outer query is a comparison, AND, OR or NOT of expressions that each read the "
        + "subquery or the outer query, not both");
  }

  /**
   * The rows of a correlated subquery by their key, loaded on the first probe: for each key the values of the columns that the
   * residual terms read. A row with a {@code NULL} key matches nothing. Without key equalities every row is under the empty key.
   */
  private static final class ExistsKeys {
    private final MaterializedSource.Materialization rows;
    private final List<Function<Object, Object>>      toCommon;
    private final int                                 leaves;
    private       Map<List<Object>, List<Object[]>>   buckets;

    ExistsKeys(final MaterializedSource.Materialization rows, final List<Function<Object, Object>> toCommon, final int leaves) {
      this.rows = rows;
      this.toCommon = toCommon;
      this.leaves = leaves;
    }

    synchronized Map<List<Object>, List<Object[]>> load() {
      if (buckets == null) {
        final Map<List<Object>, List<Object[]>> loaded = new HashMap<>();
        for (final Object[] row : rows.rows()) {
          final List<Object> key = new ArrayList<>(toCommon.size());
          boolean complete = true;
          for (int i = 0; i < toCommon.size() && complete; i++)
            if (row[i] == null)
              complete = false;
            else
              key.add(toCommon.get(i).apply(row[i]));
          if (complete)
            loaded.computeIfAbsent(key, k -> new ArrayList<>()).add(java.util.Arrays.copyOfRange(row, toCommon.size(), toCommon.size() + leaves));
        }
        buckets = loaded;
      }
      return buckets;
    }
  }

  private static boolean hasExists(final Expression expression) {
    if (expression instanceof Expression.Exists)
      return true;
    for (final Expression child : ExpressionCompiler.children(expression))
      if (hasExists(child))
        return true;
    return false;
  }

  private static void referencesIn(final Expression expression, final List<Expression.ColumnRef> out) {
    if (expression instanceof Expression.ColumnRef reference)
      out.add(reference);
    else
      for (final Expression child : ExpressionCompiler.children(expression))
        referencesIn(child, out);
  }

  /**
   * Says which query the columns that an expression of an {@code EXISTS} subquery reads belong to: the subquery, the query
   * around it, both, or neither. The columns that a scalar or {@code IN} subquery of the expression reads of either query count
   * too (fork issue #164): such a subquery is planned with placeholders, and each name that neither it nor a query inside it has
   * is resolved for the two queries, so it is never looked up in the query around alone.
   */
  private final class Sides {
    private final RelationScope        inner;
    private final ExpressionCompiler   outer;
    /** What each subquery reads, found once: a term is asked about again for each of its parts. */
    private final Map<Statement, Side> subqueries = new IdentityHashMap<>();

    Sides(final RelationScope inner, final ExpressionCompiler outer) {
      this.inner = inner;
      this.outer = outer;
    }

    Side of(final Expression expression) {
      final SideScope scope = new SideScope(inner, outer);
      read(expression, scope);
      return scope.side;
    }

    private void read(final Expression expression, final SideScope scope) {
      if (expression instanceof Expression.ColumnRef reference) {
        scope.column(reference);
        return;
      }
      final Statement subquery = switch (expression) {
        case Expression.ScalarSubquery scalar -> scalar.query();
        case Expression.InSubquery in -> in.query();
        default -> null;
      };
      if (subquery != null)
        scope.side = scope.side.and(subqueries.computeIfAbsent(subquery, this::ofSubquery));
      // of an IN subquery this is its operand
      for (final Expression child : ExpressionCompiler.children(expression))
        read(child, scope);
    }

    private Side ofSubquery(final Statement subquery) {
      final SideScope scope = new SideScope(inner, outer);
      inside(OuterBindings.discovering(new ExpressionCompiler(scope, parameters))).planQuery(subquery, false);
      return scope.side;
    }
  }

  /** The two queries as one scope, which remembers in {@link #side} whose columns it was asked for. */
  private static final class SideScope implements ExpressionCompiler.Scope {
    private final RelationScope      inner;
    private final ExpressionCompiler outer;
    private       Side               side = Side.NONE;

    SideScope(final RelationScope inner, final ExpressionCompiler outer) {
      this.inner = inner;
      this.outer = outer;
    }

    @Override
    public boolean shows(final Name column) {
      return inner.shows(column) || outer.shows(column);
    }

    @Override
    public Evaluable column(final Expression.ColumnRef reference) {
      // a column that the subquery only hides gives way to one that the query around shows (fork issue #157)
      if (inner.givesWay(reference, outer))
        return around(reference);
      try {
        final Evaluable own = inner.column(reference);
        side = side.and(Side.INNER);
        return own;
      } catch (final PostgreSQLReferenceException notTheSubquerys) {
        try {
          return around(reference);
        } catch (final PostgreSQLReferenceException neither) {
          throw notTheSubquerys;
        }
      }
    }

    private Evaluable around(final Expression.ColumnRef reference) {
      final Evaluable column = outer.compile(reference);
      side = side.and(Side.OUTER);
      return column;
    }
  }

  /** The constants of {@code LIMIT} and {@code OFFSET}: no column is in scope. */
  private ExpressionCompiler constants() {
    return new ExpressionCompiler(reference -> {
      throw PostgreSQLReferenceException.invalidReference("argument of LIMIT or OFFSET must not contain variables");
    }, parameters);
  }

  /** {@code WITH}: each name is planned in order, and the ones after it can read it; the body reads all of them. */
  private QueryPlan planWith(final WithStatement with, final boolean operand) {
    final Map<String, Cte> scope = new HashMap<>(ctes);
    final SelectPlanner scoped = new SelectPlanner(database, catalog, parameters, joinMemoryCap, scope, outerScope);
    final Set<String> defined = new HashSet<>();
    for (final WithStatement.Cte definition : with.ctes()) {
      final String name = definition.name().value();
      if (!defined.add(name))
        throw PostgreSQLReferenceException.duplicateAlias(name);
      final QueryPlan plan = scoped.planQuery(definition.query(), false);
      final List<String> names = columnNames(plan, definition.columns(), "WITH query \"" + name + "\"");
      final List<String> typeNames = new ArrayList<>();
      for (final PostgreSQLResultSet.Column column : plan.columns())
        typeNames.add(column.typeName());
      scope.put(name, new Cte(definition.name(), new MaterializedSource.Materialization(plan, joinMemoryCap,
          "a common table expression"), names, plan.outputTypes(), typeNames));
    }
    return scoped.planQuery(with.body(), operand);
  }

  /** The names a reader sees: the ones of the select list, the first ones replaced by a list of names when there is one. */
  private static List<String> columnNames(final QueryPlan plan, final List<Name> given, final String what) {
    final int available = plan.columns().size();
    if (given.size() > available)
      throw new PostgreSQLEvaluationException("42P10",
          what + " has " + available + " columns available but " + given.size() + " columns specified");
    final List<String> names = new ArrayList<>(available);
    for (int i = 0; i < available; i++)
      names.add(i < given.size() ? given.get(i).value() : plan.columns().get(i).name());
    return names;
  }

  private static MaterializedSource cteSource(final Cte cte, final String relation) {
    final List<RelationSource.Field> fields = new ArrayList<>();
    for (int i = 0; i < cte.names().size(); i++)
      fields.add(new RelationSource.Field(relation, cte.names().get(i), cte.types().get(i), cte.typeNames().get(i)));
    final String name = cte.name().value();
    return new MaterializedSource(cte.rows(), fields, "SCAN CTE " + name + (relation.equals(name) ? "" : " AS " + relation));
  }

  /** A subquery in {@code FROM}: its answer is read once, and its columns carry the alias as their relation. */
  private RelationSource planDerived(final TableReference.Subquery reference) {
    final QueryPlan plan = planQuery(reference.query(), false);
    final String relation;
    if (reference.alias() != null)
      relation = reference.alias().value();
    else
      // PostgreSQL 16 and later name a subquery without an alias, so that two of them can stand in one FROM clause
      relation = unnamedSubqueries++ == 0 ? "unnamed_subquery" : "unnamed_subquery_" + (unnamedSubqueries - 1);
    final List<String> names = columnNames(plan, reference.columns(), "table \"" + relation + "\"");
    final List<RelationSource.Field> fields = new ArrayList<>();
    for (int i = 0; i < names.size(); i++)
      fields.add(new RelationSource.Field(relation, names.get(i), plan.outputTypes().get(i), plan.columns().get(i).typeName()));
    return new MaterializedSource(new MaterializedSource.Materialization(plan, joinMemoryCap, "a derived table"), fields,
        "SCAN SUBQUERY " + relation);
  }

  /**
   * {@code UNION}, {@code INTERSECT} and {@code EXCEPT}: both operands are planned first, then each result column gets the type
   * PostgreSQL resolves for its two inputs ({@link SetTypes}). {@code ORDER BY} of the whole names an output column, by name or
   * by position: an expression is refused, as PostgreSQL does.
   */
  private QueryPlan planSetOperation(final SetOperationStatement set) {
    final QueryPlan left = planQuery(set.left(), true);
    final QueryPlan right = planQuery(set.right(), true);
    final String operation = set.kind().name();
    if (left.columns().size() != right.columns().size())
      throw new PostgreSQLSyntaxException("each " + operation + " query must have the same number of columns");

    final List<PgType> types = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    final List<PostgreSQLResultSet.Column> columns = new ArrayList<>();
    for (int i = 0; i < left.columns().size(); i++) {
      final PgType leftType = left.outputTypes().get(i);
      final PgType rightType = right.outputTypes().get(i);
      final PgType type = SetTypes.resolve(leftType, rightType, operation);
      left.inferParameter(i, type);
      right.inferParameter(i, type);
      final String typeName = leftType == type ? left.columns().get(i).typeName()
          : rightType == type ? right.columns().get(i).typeName() : type.typeName();
      types.add(type);
      names.add(left.columns().get(i).name());
      columns.add(new PostgreSQLResultSet.Column(left.columns().get(i).name(), typeName));
    }

    final List<SelectPlan.SortKey> sortKeys = new ArrayList<>();
    for (final SortItem item : set.orderBy()) {
      final int position = outputIndex(item.expression(), names);
      if (position < 0) {
        if (item.expression() instanceof Expression.ColumnRef reference)
          throw PostgreSQLReferenceException.undefinedColumn(reference.column().value(),
              PostgreSQLCatalogException.unknownColumnHint(reference.column().value(), Map.of("", names),
                  PostgreSQLCatalogException.columnList("the " + operation + " result", names)));
        throw new PostgreSQLFeatureNotSupportedException("invalid " + operation + " ORDER BY clause: only result column names "
            + "and positions can be used, not expressions or functions");
      }
      if (!Comparison.isComparable(types.get(position)))
        throw new PostgreSQLFeatureNotSupportedException(
            "ORDER BY a value of type " + types.get(position).displayName() + " is not supported");
      final Boolean nullsFirst = item.nulls() == SortItem.Nulls.FIRST ? Boolean.TRUE
          : item.nulls() == SortItem.Nulls.LAST ? Boolean.FALSE : null;
      sortKeys.add(new SelectPlan.SortKey(position,
          Comparison.ordering(types.get(position), item.direction() == SortItem.Direction.DESC, nullsFirst)));
    }

    final ExpressionCompiler constants = constants();
    return new SetOperationPlan(set.kind(), set.all(), left, right, types, columns, sortKeys,
        countOf(set.limit(), "LIMIT", constants), countOf(set.offset(), "OFFSET", constants), joinMemoryCap);
  }

  /**
   * The distance of a statement that orders by exactly one pgvector distance, ascending, {@code null} for any other statement.
   * Only the shape is tested here; {@link VectorSearchSource#choose} decides whether an index can answer it.
   */
  private static VectorDistance nearestNeighbourKey(final SelectStatement select, final boolean grouping,
      final List<SelectPlan.SortKey> sortKeys, final List<Evaluable> outputs, final List<Evaluable> extras) {
    if (sortKeys.size() != 1 || select.orderBy().size() != 1 || grouping || select.distinct())
      return null;
    final SortItem item = select.orderBy().get(0);
    if (item.direction() == SortItem.Direction.DESC || item.nulls() == SortItem.Nulls.FIRST)
      return null;
    final int position = sortKeys.get(0).position();
    final Evaluable key = position < outputs.size() ? outputs.get(position) : extras.get(position - outputs.size());
    return key instanceof VectorDistance distance ? distance : null;
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

  /** The access path of the table for a {@code WHERE} clause; the write planner uses it for {@code UPDATE} and {@code DELETE}. */
  TableSource chooseAccessPath(final TableSource table, final Expression where, final ExpressionCompiler compiler) {
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
    private final SubqueryPlanner                 subqueries;
    private final List<SelectPlan.AggregateSpec>  specs = new ArrayList<>();
    private final List<Expression.FunctionCall>   calls = new ArrayList<>();

    GroupingScope(final List<Expression> groupAsts, final List<Evaluable> groupEvaluables, final ExpressionCompiler inputCompiler,
        final SubqueryPlanner subqueries) {
      this.groupAsts = groupAsts;
      this.groupEvaluables = groupEvaluables;
      this.inputCompiler = inputCompiler;
      this.subqueries = subqueries;
    }

    @Override
    public SubqueryPlanner subqueries() {
      return subqueries;
    }

    SelectPlan.GroupStage stage(final long capBytes) {
      return new SelectPlan.GroupStage(groupEvaluables.toArray(new Evaluable[0]), specs, capBytes);
    }

    @Override
    public boolean shows(final Name column) {
      return inputCompiler.shows(column);
    }

    @Override
    public boolean showsHere(final Name column) {
      return inputCompiler.showsHere(column);
    }

    @Override
    public Evaluable column(final Expression.ColumnRef reference) {
      // a column of the query around a correlated subquery is a constant for it, grouped or not
      try {
        if (inputCompiler.compile(reference) instanceof Evaluable.Constant outer)
          return outer;
      } catch (final PostgreSQLReferenceException notAColumn) {
        // the error below is the one this scope has always given
      }
      throw new PostgreSQLEvaluationException("42803", "column \"" + reference.column().value()
          + "\" must appear in the GROUP BY clause or be used in an aggregate function");
    }

    @Override
    public Evaluable intercept(final Expression expression, final ExpressionCompiler compiler) {
      if (expression instanceof Expression.FunctionCall window && window.over() != null)
        throw new PostgreSQLFeatureNotSupportedException(
            "A window function in a query with GROUP BY, HAVING or an aggregate is not supported");
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
      return aggregateSpec(call, inputCompiler);
    }
  }

  /** Types an aggregate call (a grouping query's, or a window aggregate's) against the compiler of its input row. */
  static SelectPlan.AggregateSpec aggregateSpec(final Expression.FunctionCall call, final ExpressionCompiler inputCompiler) {
    {
      final String name = call.name().get(call.name().size() - 1).value();
      if (name.equals("string_agg"))
        return stringAggSpec(call, inputCompiler);
      Evaluable argument = null;
      PgType argumentType = PgType.UNKNOWN;
      if (call.star()) {
        if (!name.equals("count"))
          throw PostgreSQLEvaluationException.noFunction(name);
      } else {
        if (call.args().size() != 1)
          throw PostgreSQLEvaluationException.noFunction(name, inputCompiler.argumentTypes(call));
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
      return new SelectPlan.AggregateSpec(name, argumentType, result, argument, call.distinct(), null);
    }
  }

  /**
   * {@code string_agg(value, delimiter)}: {@code text}. PostgreSQL has the two overloads {@code (text, text)} and
   * {@code (bytea, bytea)}; an {@code unknown} literal resolves to the string category, so {@code string_agg(name, ', ')} is the
   * text one. There is no implicit cast from a number to text: {@code string_agg(pid, ',')} is {@code 42883}, as in PostgreSQL.
   */
  private static SelectPlan.AggregateSpec stringAggSpec(final Expression.FunctionCall call, final ExpressionCompiler inputCompiler) {
    if (call.star() || call.args().size() != 2)
      throw PostgreSQLEvaluationException.noFunction("string_agg", inputCompiler.argumentTypes(call));
    final Evaluable value = inputCompiler.compile(call.args().get(0));
    final Evaluable delimiter = inputCompiler.compile(call.args().get(1));
    if (value.type() == PgType.BYTEA || delimiter.type() == PgType.BYTEA)
      throw PostgreSQLEvaluationException.unsupported("string_agg over bytea is not supported");
    final Evaluable text = inputCompiler.coerce(value, PgType.TEXT);
    final Evaluable separator = inputCompiler.coerce(delimiter, PgType.TEXT);
    if (text == null || separator == null)
      throw PostgreSQLEvaluationException.noFunction("string_agg", value.type(), delimiter.type());
    return new SelectPlan.AggregateSpec("string_agg", PgType.TEXT, PgType.TEXT, text, call.distinct(), separator);
  }

  /**
   * The scope of the select list and {@code ORDER BY} of a query without grouping: a call with {@code OVER} is typed here and
   * becomes a slot after the columns of the input row, which {@link WindowStage} fills; everything else is the scope of the input.
   */
  private static final class WindowScope implements ExpressionCompiler.Scope {
    private final ExpressionCompiler.Scope     base;
    private final ExpressionCompiler           input;
    private final int                          width;
    private final List<Expression.FunctionCall> seen  = new ArrayList<>();
    private final List<WindowStage.Call>       calls = new ArrayList<>();

    WindowScope(final ExpressionCompiler.Scope base, final ExpressionCompiler input, final int width) {
      this.base = base;
      this.input = input;
      this.width = width;
    }

    boolean isEmpty() {
      return calls.isEmpty();
    }

    WindowStage stage() {
      return new WindowStage(width, calls);
    }

    @Override
    public Evaluable column(final Expression.ColumnRef reference) {
      return base.column(reference);
    }

    @Override
    public boolean shows(final Name column) {
      return base.shows(column);
    }

    @Override
    public boolean showsHere(final Name column) {
      return base.showsHere(column);
    }

    @Override
    public SubqueryPlanner subqueries() {
      return base.subqueries();
    }

    @Override
    public Evaluable intercept(final Expression expression, final ExpressionCompiler compiler) {
      if (expression instanceof Expression.FunctionCall call && call.over() != null) {
        int known = seen.indexOf(call);
        if (known < 0) {
          calls.add(WindowStage.call(call, input));
          seen.add(call);
          known = calls.size() - 1;
        }
        final WindowStage.Call typed = calls.get(known);
        return new Evaluable.Slot(typed.resultType(), typed.resultTypeName(), width + known);
      }
      return base.intercept(expression, compiler);
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

  /** The name of the first column of a query: PostgreSQL names the column of a scalar subquery after it. */
  private static String firstColumnName(final Statement query) {
    return switch (query) {
      case SelectStatement select -> select.items().get(0) instanceof SelectItem.Projection projection
          ? (projection.alias() != null ? projection.alias().value() : outputName(projection.expression())) : "?column?";
      case SetOperationStatement set -> firstColumnName(set.left());
      case WithStatement with -> firstColumnName(with.body());
      default -> "?column?";
    };
  }

  /** The name PostgreSQL gives an output column that has no alias ({@code FigureColname} of {@code parse_target.c}). */
  static String outputName(final Expression expression) {
    if (expression instanceof Expression.ColumnRef reference)
      return reference.column().value();
    if (expression instanceof Expression.FunctionCall call)
      return call.name().get(call.name().size() - 1).value();
    if (expression instanceof Expression.SpecialFunction special)
      return special.kind().name().toLowerCase(Locale.ROOT);
    if (expression instanceof Expression.Case when)
      return keepsItsName(when.otherwise()) ? outputName(when.otherwise()) : "case";
    if (expression instanceof Expression.ArrayConstructor)
      return "array";
    if (expression instanceof Expression.Exists)
      return "exists";
    if (expression instanceof Expression.ScalarSubquery subquery)
      return firstColumnName(subquery.query());
    if (expression instanceof Expression.Cast cast)
      return keepsItsName(cast.operand()) ? outputName(cast.operand()) : cast.type().name().get(cast.type().name().size() - 1);
    return "?column?";
  }

  /**
   * Whether a cast around {@code expression}, or a {@code CASE} that has it as its {@code ELSE}, takes its name. A name that comes
   * from a cast or from a {@code CASE} itself gives way to the one around it; the name of a column, a function or a subquery, also
   * one below such a cast or {@code CASE}, stays.
   */
  private static boolean keepsItsName(final Expression expression) {
    if (expression instanceof Expression.Cast cast)
      return keepsItsName(cast.operand());
    if (expression instanceof Expression.Case when)
      return keepsItsName(when.otherwise());
    return expression instanceof Expression.ScalarSubquery || expression != null && !"?column?".equals(outputName(expression));
  }
}
