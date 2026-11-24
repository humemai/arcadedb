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
package com.arcadedb.query.postgresql.plan.join;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.postgresql.PostgreSQLReferenceException;
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Numbers;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.SubqueryPlanner;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.plan.RelationScope;
import com.arcadedb.query.postgresql.plan.RelationSource;
import com.arcadedb.query.postgresql.plan.SetTypes;
import com.arcadedb.query.postgresql.plan.TableSource;
import com.arcadedb.query.postgresql.plan.join.Pushdown.Conjunct;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plans the {@code FROM} clause of a statement that joins: {@code a [INNER] JOIN b ON ...}, {@code a LEFT [OUTER] JOIN b ON ...},
 * chains of them, and a comma list ({@code FROM a, b, c}, a chain of {@code INNER} joins whose conditions are the terms of the
 * {@code WHERE} clause). Joins are planned left deep, in the order the statement writes them: this step does not reorder.
 *
 * <p><b>Steps.</b> (1) The {@code FROM} items become a tree: a leaf per table (a {@link TableSource}) and a node per join, with
 * its {@code ON} terms. A relation name used twice is {@code 42712}; an {@code ON} term sees only the relations of its own join
 * ({@code 42P01} for another one), and has to be boolean. (2) The terms of the {@code WHERE} clause are placed by {@link Pushdown}:
 * single table terms go below the joins, the terms that mention both sides of an {@code INNER} join become its match condition,
 * and what PostgreSQL's rules keep above a {@code LEFT} join stays above (it is handed back as {@link Result#remaining()}). A
 * table that receives a term reads through the best access path of the single-table plan, so {@code WHERE o.oid = 102} on the
 * joined table is still an index lookup. (3) Each join picks its strategy.
 *
 * <p><b>Strategy.</b> The match condition is split into equalities between an expression over the left input and one over the
 * right input (the join keys, typed by the rule of the {@code =} operator, so {@code 42883} for {@code text = int4}) and the
 * residual (everything else, evaluated on the combined row). Then:
 * <ul>
 * <li>No key: {@code NESTED LOOP}, the right input in memory under the cap.</li>
 * <li>{@code INDEX NESTED LOOP}: the right input is a plain table scan (no access path of its own), a key of the condition is a
 * column of it that has an index of its own on exactly that property (or is {@code id}), the types of the key fit the lookup (two
 * integers or two strings), and the left input is not larger than the right table, by the row counts of the schema API. The right
 * table is read for each left row through the {@code FETCH FROM INDEX} or {@code READ RECORD BY ID} path of the single-table plan.
 * A table with subtypes that is read with its subtypes ({@code FROM t} without {@code ONLY}) has no index lookup in the
 * single-table plan either, because the index of a type covers its own buckets only, so such a join takes the hash join.</li>
 * <li>{@code HASH JOIN} otherwise. The build side is the right input of a {@code LEFT} join (the left side is kept in full and
 * has to be probed), and for an {@code INNER} join the input with the smaller estimated row count. The build side is kept in
 * memory under the cap of {@link BuildMemory}; over it the join stops with SQLSTATE {@code 53200}.</li>
 * </ul>
 * The row estimates use the type counts of the schema API and nothing else: a pushed condition divides the estimate by four and
 * an index lookup by ten, constants and not statistics.
 *
 * <p><b>Traversal (fork issue #8, bound start fork issue #52).</b> One hop through an edge table reads as
 * {@code v1 JOIN e ON e.source_id = v1.id JOIN v2 ON e.target_id = v2.id}, or the same with the two ends the other way round
 * ({@code e.target_id = v1.id} first), {@code INNER} or {@code LEFT}. Planned as two value joins it throws away what the vertex
 * holds, its edges. When the setting {@code arcadedb.postgresql.traversalRule} is on (the default) and the start is bound (see
 * below) the join planner plans each of the two joins as the step it is: the join of an edge table to a vertex id on the left is
 * a {@link JoinSource.Strategy#TRAVERSE} (for each left vertex, read it by its id and walk its stored edges of that type), and
 * the join of an edge endpoint on the left to the id of a vertex table on the right is a read of that vertex by its record id
 * for each edge row, whatever the sizes of the two inputs. The rule applies only where it gives the rows of the equality join:
 * the edge table has declared endpoint tables
 * ({@code EdgeEndpoints}) that the vertex table on the walked side matches (it is the declared table, a subtype of it, or a
 * supertype of it), an incoming walk needs the edge type and its subtypes to store the incoming side (a unidirectional type does
 * not), and an edge table that can hold lightweight edges is walked only from its declared source table, as its scan is. Anything
 * else keeps the hash join or the index nested loop of S5. The plan line names the step and the equivalent pattern.
 *
 * <p><b>The start has to be bound (fork issue #52).</b> A walk reads each start vertex by its record id and then its edges, so it
 * costs for each start vertex what the hash join costs once for all of them, and a join from every vertex of a table is slower
 * as a walk (the timed run of fork issue #13 measured 256 ms against 106 ms for one hop from all vertices). So the rule applies
 * only where the start is a small set that the statement names. The <i>start</i> of a join is its left input. It is <i>bound</i>
 * when:
 * <ul>
 * <li>the left input is a table that a term of the {@code WHERE} clause (or of an {@code ON} clause that the planner moves below
 * the join, see {@link Pushdown}) restricts to the rows of a key: {@code key = constant} or {@code key IN (constant, ...)}, where
 * the key is a column that is a primary key or a unique key of the table alone (the {@code id} of a table, a property with a
 * unique index on it alone), and a constant is a literal, a parameter or an expression of them. {@code NOT IN}, {@code OR},
 * ranges, an equality on a column that is not a key (an index that allows duplicates may return half of the table) and a term
 * that reads another table do not bind. The start is then at most as many vertices as there are constants;</li>
 * <li>or the left input is a join that is itself a walk from a bound start (a traversal, or the read of the far end of its
 * edges), possibly with terms applied on top: the second hop of a chain starts from the vertices the first hop reached, and a
 * chain of hops from one vertex stays a chain of walks.</li>
 * </ul>
 * Anything else is not bound, and the join is planned exactly as with the setting off: a table of all vertices, a start that is
 * only bound by a term on the far end ({@code WHERE c.cid = 10} on the table of the second join; the planner does not reorder),
 * a start that is bound by a join with another table ({@code orders o JOIN person p ON o.buyer = p.id JOIN works_at w ON
 * w.source_id = p.id WHERE o.oid = 102}: the left input is a join of two tables, not a walk), a start with a term on a column
 * that is not a key. The plan line of a join that the rule would have changed says why it did not, {@code [hash join: no bound
 * start]}, and the line of a walk says {@code [traversal: start bound by p.pid = 1]}. With the setting off the plan lines are
 * those of the plain joins, with no reason added.
 *
 * <p><b>Seams.</b> {@link #support} is the decision "an index, a link or an edge pattern supports this join, or nothing does",
 * with its reason; it is shown in the plan line of the join and is the place fork issue #9 raises its warning. The nodes of the
 * tree know the table behind every column ({@link Located}).
 */
public final class JoinPlanner {

  /** What the single-table planner offers to the join planner: the source of one table, and its access path for some terms. */
  public interface Tables {
    /** The source of one table of the {@code FROM} clause, read by a scan. */
    RelationSource table(TableReference.Table reference);

    /** Plans the subqueries of the expressions of the join conditions and of the terms placed below the joins. */
    SubqueryPlanner subqueries();

    /** The source of a subquery in the {@code FROM} clause: its rows, computed once. */
    RelationSource derived(TableReference.Subquery reference);

    /** The table read through the best access path that the terms (single table conditions) offer, as the single-table plan. */
    TableSource narrow(TableSource table, List<Expression> terms, ExpressionCompiler compiler);
  }

  /**
   * The planned {@code FROM} clause.
   *
   * @param source    the rows of the joined tables, with the terms that could be placed below the joins already applied
   * @param remaining the {@code WHERE} terms that PostgreSQL's rules keep above the joins: the caller applies them to the rows of
   *                  {@code source}
   */
  public record Result(RelationSource source, List<Expression> remaining) {
  }

  private record Built(RelationSource source, List<Conjunct> remaining) {
  }

  /** The table and the column behind one column of the rows of a node. */
  record Located(TableSource table, Column column) {
  }

  /** The two sides of one join key before they are cast to the common type, for the support decision and the index lookup. */
  private record Candidate(JoinSource.Key key, Evaluable rawLeft, Evaluable rawRight) {
  }

  private abstract static class Node {
    abstract List<RelationSource.Field> fields();

    /** The columns that an unqualified name reads: every field in order for a table, the merged columns first after a USING. */
    List<RelationSource.Output> outputs() {
      final List<RelationSource.Output> out = new ArrayList<>();
      for (int i = 0; i < fields().size(); i++)
        out.add(new RelationSource.Output(fields().get(i).name(), i, fields().get(i).type(), fields().get(i).typeName()));
      return out;
    }

    abstract Set<String> relations();

    /** The names of the tables behind the relations, for the warning. */
    abstract Set<String> tables();

    abstract Located locate(int slot);
  }

  private static final class Leaf extends Node {
    /** The rows of the leaf: a table, or a view of {@code information_schema}, which has no {@link #table}. */
    private final RelationSource source;
    /** The table behind the leaf, {@code null} for a view. */
    private final TableSource    table;
    private final String         relation;

    Leaf(final RelationSource source, final String relation) {
      this.source = source;
      this.table = source instanceof TableSource tableSource ? tableSource : null;
      this.relation = relation;
    }

    @Override
    List<RelationSource.Field> fields() {
      return source.columns();
    }

    @Override
    Set<String> relations() {
      return Set.of(relation);
    }

    @Override
    Set<String> tables() {
      // a view of information_schema has no table behind it
      return table == null ? Set.of() : Set.of(table.table().name());
    }

    @Override
    Located locate(final int slot) {
      // a view has no keys, links or edges: the join of a view is never supported by one
      return table == null ? null : new Located(table, table.table().columns().get(slot));
    }
  }

  /** One column of a {@code USING} list: where it is among the outputs of each input, and the type of the merged column. */
  private record Using(String name, int leftOutput, int rightOutput, PgType type, String typeName) {
  }

  private static final class JoinNode extends Node {
    private final TableReference.JoinType            type;
    private final Node                               left;
    private final Node                               right;
    private final List<Conjunct>                     on;
    private final List<RelationSource.Field>         fields;
    private final Set<String>                        relations;
    /** The columns of {@code USING}: the position in the outputs of the left and of the right input, and the merged column. */
    private final List<Using>                        using;

    JoinNode(final TableReference.JoinType type, final Node left, final Node right, final List<Conjunct> on) {
      this(type, left, right, on, List.of());
    }

    JoinNode(final TableReference.JoinType type, final Node left, final Node right, final List<Conjunct> on, final List<Using> using) {
      this.type = type;
      this.left = left;
      this.right = right;
      this.on = on;
      this.using = using;
      final List<RelationSource.Field> all = new ArrayList<>(left.fields());
      all.addAll(right.fields());
      this.fields = List.copyOf(all);
      final Set<String> names = new LinkedHashSet<>(left.relations());
      names.addAll(right.relations());
      this.relations = names;
    }

    @Override
    List<RelationSource.Field> fields() {
      return fields;
    }

    @Override
    Set<String> relations() {
      return relations;
    }

    @Override
    Set<String> tables() {
      final Set<String> names = new LinkedHashSet<>(left.tables());
      names.addAll(right.tables());
      return names;
    }

    /**
     * Without {@code USING} the columns of the left input then those of the right input. With it the merged columns first, in the
     * order of the list, then the columns of the left and the right input that are not in the list. A merged column reads the left
     * value, which is the value of both when the join keeps the row, and is the one that a {@code LEFT} join keeps without a
     * partner.
     */
    @Override
    List<RelationSource.Output> outputs() {
      final List<RelationSource.Output> leftOutputs = left.outputs();
      final List<RelationSource.Output> rightOutputs = right.outputs();
      final int leftColumns = left.fields().size();
      final Set<Integer> usedLeft = new java.util.HashSet<>();
      final Set<Integer> usedRight = new java.util.HashSet<>();
      final List<RelationSource.Output> out = new ArrayList<>();
      for (final Using column : using) {
        usedLeft.add(column.leftOutput);
        usedRight.add(column.rightOutput);
        out.add(new RelationSource.Output(column.name, leftOutputs.get(column.leftOutput).index(), column.type, column.typeName));
      }
      for (int i = 0; i < leftOutputs.size(); i++)
        if (!usedLeft.contains(i))
          out.add(leftOutputs.get(i));
      for (int i = 0; i < rightOutputs.size(); i++)
        if (!usedRight.contains(i)) {
          final RelationSource.Output o = rightOutputs.get(i);
          out.add(new RelationSource.Output(o.name(), o.index() + leftColumns, o.type(), o.typeName()));
        }
      return out;
    }

    @Override
    Located locate(final int slot) {
      final int leftColumns = left.fields().size();
      return slot < leftColumns ? left.locate(slot) : right.locate(slot - leftColumns);
    }
  }

  private final Database          database;
  private final RelationalCatalog catalog;
  private final Parameters        parameters;
  private final Tables            tables;
  private final long              capBytes;
  private final boolean           traversalRule;
  /** The sources whose rows come from a bound start (fork issue #52), with the text of the term that binds it. */
  private final Map<RelationSource, String> boundStarts = new IdentityHashMap<>();

  /**
   * @param capBytes the memory cap of every join, see {@link BuildMemory}
   */
  public JoinPlanner(final Database database, final RelationalCatalog catalog, final Parameters parameters, final Tables tables,
      final long capBytes) {
    this.database = database;
    this.catalog = catalog;
    this.parameters = parameters;
    this.tables = tables;
    this.capBytes = capBytes;
    this.traversalRule = database.getConfiguration().getValueAsBoolean(GlobalConfiguration.POSTGRESQL_TRAVERSAL_RULE);
  }

  /**
   * Plans the {@code FROM} items and places the terms of the {@code WHERE} clause.
   *
   * @param from  the {@code FROM} items: one join, or several items that are a comma list
   * @param where the {@code WHERE} clause, {@code null} for none
   */
  public Result plan(final List<TableReference> from, final Expression where) {
    TableReference root = from.get(0);
    for (int i = 1; i < from.size(); i++)
      root = new TableReference.Join(from.get(i).position(), root, TableReference.JoinType.INNER, from.get(i),
          new Expression.BooleanLiteral(from.get(i).position(), true));
    final Node tree = analyze(root);
    final List<Conjunct> incoming = where == null ? List.of() : Pushdown.terms(where, scope(tree));
    final Built built = build(tree, incoming);
    final List<Expression> remaining = new ArrayList<>();
    for (final Conjunct term : built.remaining())
      remaining.add(term.expression());
    return new Result(built.source(), remaining);
  }

  /** The conjunction of conditions as one, for a filter: it is {@code true} when every condition is. */
  public static Evaluable allOf(final List<Evaluable> conditions) {
    if (conditions.size() == 1)
      return conditions.get(0);
    final Evaluable[] all = conditions.toArray(new Evaluable[0]);
    return new Evaluable.Node(PgType.BOOL, row -> {
      for (final Evaluable condition : all)
        if (!Boolean.TRUE.equals(condition.eval(row)))
          return Boolean.FALSE;
      return Boolean.TRUE;
    }, all);
  }

  // ---- the tree ----

  private RelationScope scope(final Node node) {
    return new RelationScope(node.fields(), catalog, tables.subqueries(), node.outputs());
  }

  private ExpressionCompiler compiler(final Node node) {
    return new ExpressionCompiler(scope(node), parameters);
  }

  private Node analyze(final TableReference reference) {
    if (reference instanceof TableReference.Subquery subquery) {
      final RelationSource source = tables.derived(subquery);
      return new Leaf(source, source.columns().get(0).relation());
    }
    if (reference instanceof TableReference.Table table) {
      final RelationSource source = tables.table(table);
      final String name = source instanceof TableSource tableSource ? tableSource.table().name() : source.columns().get(0).relation();
      return new Leaf(source, table.alias() != null ? table.alias().value() : name);
    }
    final TableReference.Join join = (TableReference.Join) reference;
    final Node left = analyze(join.left());
    final Node right = analyze(join.right());
    for (final String name : right.relations())
      if (left.relations().contains(name))
        throw PostgreSQLReferenceException.duplicateAlias(name);
    final JoinNode partial = new JoinNode(join.type(), left, right, List.of());
    final RelationScope scope = scope(partial);
    final List<Using> using = new ArrayList<>();
    final Expression condition = join.using().isEmpty() ? join.condition() : usingCondition(join, left, right, using);
    new ExpressionCompiler(scope, parameters).compileCondition(condition, join.using().isEmpty() ? "JOIN/ON" : "JOIN/USING");
    final List<Conjunct> on = new ArrayList<>();
    for (final Conjunct term : Pushdown.terms(condition, scope))
      if (!(term.expression() instanceof Expression.BooleanLiteral literal && literal.value()))
        on.add(term);
    return new JoinNode(join.type(), left, right, on, using);
  }

  /**
   * {@code JOIN ... USING (a, b)}: the columns must be in both inputs (SQLSTATE {@code 42703}), once in each ({@code 42702}), and
   * their types must have a common type ({@code 42804}). The condition is the equality of each pair, written with the relation of
   * each column so that it reads the same whatever else is in scope; the merged column of each pair goes to {@code using}.
   */
  private Expression usingCondition(final TableReference.Join join, final Node left, final Node right, final List<Using> using) {
    Expression condition = null;
    for (final Name name : join.using()) {
      final int leftOutput = usingOutput(name, left, "left");
      final int rightOutput = usingOutput(name, right, "right");
      final RelationSource.Output leftColumn = left.outputs().get(leftOutput);
      final RelationSource.Output rightColumn = right.outputs().get(rightOutput);
      final PgType type = SetTypes.resolve(leftColumn.type(), rightColumn.type(), "JOIN/USING");
      final String typeName = leftColumn.type() == type ? leftColumn.typeName()
          : rightColumn.type() == type ? rightColumn.typeName() : type.typeName();
      using.add(new Using(name.value(), leftOutput, rightOutput, type, typeName));
      final Expression equal = new Expression.BinaryOp(name.position(), Expression.BinaryOperator.EQUAL,
          columnOf(left, leftColumn, name), columnOf(right, rightColumn, name));
      condition = condition == null ? equal
          : new Expression.BinaryOp(name.position(), Expression.BinaryOperator.AND, condition, equal);
    }
    return condition;
  }

  private static Expression.ColumnRef columnOf(final Node node, final RelationSource.Output output, final Name name) {
    final RelationSource.Field field = node.fields().get(output.index());
    return new Expression.ColumnRef(name.position(), List.of(new Name(name.position(), field.relation(), true)),
        new Name(name.position(), field.name(), true));
  }

  /** The position of the column among the outputs of one side of a {@code USING} join. */
  private static int usingOutput(final Name name, final Node side, final String which) {
    for (int pass = 0; pass < 2; pass++) {
      final List<Integer> matches = new ArrayList<>();
      final List<RelationSource.Output> outputs = side.outputs();
      for (int i = 0; i < outputs.size(); i++)
        if (pass == 0 ? outputs.get(i).name().equals(name.value())
            : !name.quoted() && outputs.get(i).name().equalsIgnoreCase(name.value()))
          matches.add(i);
      if (matches.size() == 1)
        return matches.get(0);
      if (matches.size() > 1)
        throw new PostgreSQLEvaluationException("42702",
            "common column name \"" + name.value() + "\" appears more than once in " + which + " table");
      if (name.quoted())
        break;
    }
    throw new PostgreSQLEvaluationException("42703",
        "column \"" + name.value() + "\" specified in USING clause does not exist in " + which + " table");
  }

  private Built build(final Node node, final List<Conjunct> incoming) {
    if (node instanceof Leaf leaf)
      return new Built(leafSource(leaf, incoming), List.of());
    final JoinNode join = (JoinNode) node;
    final Pushdown.Placement placement = Pushdown.place(join.type, join.left.relations(), join.right.relations(), incoming,
        join.on);
    final RelationSource left = withRemaining(join.left, build(join.left, placement.toLeft()));
    final RelationSource right = withRemaining(join.right, build(join.right, placement.toRight()));
    return new Built(joinSource(join, left, right, placement.matching()), placement.above());
  }

  /** The rows of a child, with the terms its own subtree could not take applied to its output. */
  private RelationSource withRemaining(final Node node, final Built built) {
    return built.remaining().isEmpty() ? built.source() : filtered(node, built.source(), built.remaining());
  }

  private RelationSource leafSource(final Leaf leaf, final List<Conjunct> pushed) {
    if (pushed.isEmpty())
      return leaf.source;
    if (leaf.table == null)
      return filtered(leaf, leaf.source, pushed);
    final List<Expression> terms = new ArrayList<>();
    for (final Conjunct term : pushed)
      terms.add(term.expression());
    final RelationSource source = filtered(leaf, tables.narrow(leaf.table, terms, compiler(leaf)), pushed);
    if (traversalRule) {
      final String key = boundKey(leaf, terms);
      if (key != null)
        boundStarts.put(source, key);
    }
    return source;
  }

  // ---- the bound start (fork issue #52) ----

  /**
   * The text of the term that binds the rows of a table to the rows of a key, or {@code null} when no term of {@code terms} does:
   * {@code key = constant} (either side) or {@code key IN (constant, ...)}, where the key is a column that is alone a primary key
   * or a unique key of the table, and a constant is a literal, a parameter or an expression of them (the same constants the
   * single-table plan takes for an index lookup).
   */
  private String boundKey(final Leaf leaf, final List<Expression> terms) {
    final ExpressionCompiler compiler = compiler(leaf);
    for (final Expression term : terms) {
      boolean bound = false;
      if (term instanceof Expression.BinaryOp op && op.operator() == Expression.BinaryOperator.EQUAL)
        bound = (isKey(leaf, op.left(), compiler) && isConstant(op.right(), compiler))
            || (isKey(leaf, op.right(), compiler) && isConstant(op.left(), compiler));
      else if (term instanceof Expression.InList in && !in.negated() && isKey(leaf, in.operand(), compiler)) {
        bound = true;
        for (final Expression value : in.values())
          bound &= isConstant(value, compiler);
      }
      if (bound) {
        final String text = AstPrinter.print(term);
        final String bare = text.startsWith("(") && text.endsWith(")") ? text.substring(1, text.length() - 1) : text;
        return bare.length() > 80 ? bare.substring(0, 77) + "..." : bare;
      }
    }
    return null;
  }

  /** Whether the expression is a column of the table that is a primary key or a unique key of the table alone. */
  private static boolean isKey(final Leaf leaf, final Expression expression, final ExpressionCompiler compiler) {
    if (!(expression instanceof Expression.ColumnRef) || !(compiler.compile(expression) instanceof Evaluable.Slot slot))
      return false;
    final Table table = leaf.table.table();
    return table.keyOn(List.of(table.columns().get(slot.index()).name())) != null;
  }

  /**
   * Whether the expression is a constant of the statement: made of literals, parameters, operators and casts only (a subquery or a
   * function call is never taken for one, so the check plans nothing twice) and constant once compiled.
   */
  private static boolean isConstant(final Expression expression, final ExpressionCompiler compiler) {
    return fixed(expression) && compiler.compile(expression).isConstant();
  }

  private static boolean fixed(final Expression expression) {
    return switch (expression) {
      case Expression.IntegerLiteral e -> true;
      case Expression.DecimalLiteral e -> true;
      case Expression.StringLiteral e -> true;
      case Expression.ParameterRef e -> true;
      case Expression.UnaryOp e -> fixed(e.operand());
      case Expression.BinaryOp e -> fixed(e.left()) && fixed(e.right());
      case Expression.Cast e -> fixed(e.operand());
      default -> false;
    };
  }

  /**
   * The text of the term that binds the start of a join whose left input is {@code left}, {@code null} when the start is not
   * bound: the left input is a table restricted to the rows of a key, or a walk that began there, under any filters.
   */
  private String boundStart(final RelationSource left) {
    RelationSource current = left;
    while (true) {
      final String key = boundStarts.get(current);
      if (key != null)
        return key;
      if (current instanceof FilteredSource filteredSource)
        current = filteredSource.inner();
      else
        return null;
    }
  }

  private RelationSource filtered(final Node node, final RelationSource source, final List<Conjunct> terms) {
    final ExpressionCompiler compiler = compiler(node);
    final List<Evaluable> conditions = new ArrayList<>();
    final List<String> texts = new ArrayList<>();
    final Set<String> relations = new LinkedHashSet<>();
    for (final Conjunct term : terms) {
      conditions.add(compiler.compileCondition(term.expression(), "WHERE"));
      texts.add(AstPrinter.print(term.expression()));
      relations.addAll(term.relations());
    }
    return new FilteredSource(source, allOf(conditions), String.join(", ", relations.isEmpty() ? node.relations() : relations),
        String.join(" AND ", texts), Math.max(1L, estimate(source) / 4));
  }

  // ---- one join ----

  private RelationSource joinSource(final JoinNode join, final RelationSource left, final RelationSource right,
      final List<Conjunct> matching) {
    final RelationScope nodeScope = scope(join);
    final ExpressionCompiler leftCompiler = compiler(join.left);
    final ExpressionCompiler rightCompiler = compiler(join.right);
    final ExpressionCompiler nodeCompiler = new ExpressionCompiler(nodeScope, parameters);

    final List<Candidate> candidates = new ArrayList<>();
    final List<Evaluable> residuals = new ArrayList<>();
    final List<String> texts = new ArrayList<>();
    for (final Conjunct term : matching) {
      texts.add(AstPrinter.print(term.expression()));
      final Candidate candidate = asKey(term, join, nodeScope, leftCompiler, rightCompiler, nodeCompiler);
      if (candidate != null)
        candidates.add(candidate);
      else
        residuals.add(nodeCompiler.compileCondition(term.expression(), "JOIN/ON"));
    }
    final List<JoinSource.Key> keys = new ArrayList<>();
    for (final Candidate candidate : candidates)
      keys.add(candidate.key());
    final Evaluable residual = residuals.isEmpty() ? null : allOf(residuals);
    final String condition = texts.isEmpty() ? "true" : String.join(" AND ", texts);

    final long leftRows = estimate(left);
    final long rightRows = estimate(right);
    final JoinSupport support = support(join, candidates);
    final boolean inner = join.type == TableReference.JoinType.INNER;

    // fork issue #52: the rule applies only where the start of the join is a small set that the statement names
    final String start = traversalRule ? boundStart(left) : null;
    JoinSource.Strategy strategy = strategyOf(keys);
    JoinSource.IndexProbe probe = null;
    JoinSource.Traversal traversal = null;
    boolean buildLeft = false;
    // the shape of a walk is there (the first hop, or the read of the far end of an edge) and only the start decides
    boolean walkShape = false;
    if (strategy == JoinSource.Strategy.HASH && traversalRule) {
      traversal = traversal(join, right, candidates);
      if (traversal != null) {
        walkShape = true;
        if (start != null)
          strategy = JoinSource.Strategy.TRAVERSE;
        else
          traversal = null;
      }
    }
    if (strategy == JoinSource.Strategy.HASH) {
      probe = indexProbe(join, right, candidates, leftRows, start != null);
      if (probe != null)
        strategy = JoinSource.Strategy.INDEX_NESTED_LOOP;
      else
        buildLeft = inner && leftRows < rightRows;
      walkShape |= traversalRule && readsFarEnd(join, right, candidates);
    }
    final boolean farEnd = strategy == JoinSource.Strategy.INDEX_NESTED_LOOP && start != null
        && edgeEnd(join, candidates.get(probe.key())) != null;

    final String header;
    final List<String> rightLines = new ArrayList<>(right.describeTree());
    switch (strategy) {
    case TRAVERSE:
      header = "TRAVERSE " + join.type + " ON " + condition + " (" + (traversal.direction() == Vertex.DIRECTION.OUT ? "out " : "in ")
          + traversal.edges().table().name() + " AS " + traversal.edges().relation() + " FROM " + traversal.from().relation()
          + ") = " + firstHopPattern(traversal);
      rightLines.set(0, "WALK " + (traversal.direction() == Vertex.DIRECTION.OUT ? "out" : "in") + " EDGES "
          + traversal.edges().table().name() + " AS " + traversal.edges().relation() + " of each left row");
      break;
    case INDEX_NESTED_LOOP: {
      if (farEnd)
        header = "TRAVERSE TO " + names(join.right) + " " + join.type + " ON " + condition + " (read " + names(join.right)
            + " by " + probe.column().name() + " from the edge) = "
            + secondHopPattern(left, edgeEnd(join, candidates.get(probe.key())), probe.table());
      else
        header = "INDEX NESTED LOOP " + join.type + " ON " + condition + " (probe " + names(join.right) + " by "
            + probe.column().name() + ")";
      rightLines.set(0, lookupLine(probe) + " for each left row");
      break;
    }
    case HASH:
      header = "HASH JOIN " + join.type + " ON " + condition + " (build " + names(buildLeft ? join.left : join.right) + ")";
      break;
    default:
      header = "NESTED LOOP " + join.type + " ON " + condition;
      break;
    }

    final long estimated;
    if (keys.isEmpty())
      estimated = leftRows > Long.MAX_VALUE / Math.max(1L, rightRows) ? Long.MAX_VALUE : leftRows * Math.max(1L, rightRows);
    else
      estimated = inner ? Math.max(leftRows, rightRows) : leftRows;
    final String reason;
    if (strategy == JoinSource.Strategy.TRAVERSE || farEnd)
      reason = " [traversal: start bound by " + start + "]";
    else if (walkShape)
      reason = " [" + (strategy == JoinSource.Strategy.HASH ? "hash join" : "index nested loop") + ": no bound start]";
    else
      reason = "";
    final JoinSource source = new JoinSource(strategy, join.type, left, right, keys, residual, buildLeft, probe, traversal, capBytes,
        header + " [" + describe(support) + "]" + reason, rightLines, support,
        warns(join, support) ? warningOf(join, condition) : null, estimated).withOutputs(join.outputs());
    if (strategy == JoinSource.Strategy.TRAVERSE || farEnd)
      boundStarts.put(source, start);
    return source;
  }

  /** The strategy a join without any key cannot avoid, and the one an equality join starts from. */
  private static JoinSource.Strategy strategyOf(final List<JoinSource.Key> keys) {
    return keys.isEmpty() ? JoinSource.Strategy.NESTED_LOOP : JoinSource.Strategy.HASH;
  }

  private static String names(final Node node) {
    return String.join(", ", node.relations());
  }

  private static String describe(final JoinSupport support) {
    return support.supported() ? "supported by " + support : "not supported: " + support.reason();
  }

  /** The plan line of one lookup of the right table. */
  private static String lookupLine(final JoinSource.IndexProbe probe) {
    final Evaluable placeholder = new Evaluable.Constant(PgType.UNKNOWN, null);
    final TableSource lookup = probe.column().kind() == Column.Kind.IDENTITY ? probe.table().withIdLookup(probe.column(), placeholder)
        : probe.table().withIndexLookup(probe.column(), placeholder);
    return lookup.describe();
  }

  /**
   * One term of the match condition as a join key, or {@code null} when it is not one: an equality between an expression that
   * mentions only the left input and one that mentions only the right input (in either order), whose types the {@code =} operator
   * accepts and have a common type the hash key can be built in.
   */
  private Candidate asKey(final Conjunct term, final JoinNode join, final RelationScope nodeScope,
      final ExpressionCompiler leftCompiler, final ExpressionCompiler rightCompiler, final ExpressionCompiler nodeCompiler) {
    if (!(term.expression() instanceof Expression.BinaryOp op) || op.operator() != Expression.BinaryOperator.EQUAL)
      return null;
    final Set<String> a = Pushdown.relations(op.left(), nodeScope);
    final Set<String> b = Pushdown.relations(op.right(), nodeScope);
    if (a.isEmpty() || b.isEmpty())
      return null;
    final Expression leftExpression;
    final Expression rightExpression;
    if (join.left.relations().containsAll(a) && join.right.relations().containsAll(b)) {
      leftExpression = op.left();
      rightExpression = op.right();
    } else if (join.right.relations().containsAll(a) && join.left.relations().containsAll(b)) {
      leftExpression = op.right();
      rightExpression = op.left();
    } else
      return null;
    final Evaluable x = leftCompiler.compile(leftExpression);
    final Evaluable y = rightCompiler.compile(rightExpression);
    nodeCompiler.comparison(Expression.BinaryOperator.EQUAL, x, y);
    final PgType common = commonType(x.type(), y.type());
    if (common == null)
      return null;
    final Evaluable castLeft = leftCompiler.coerce(x, common);
    final Evaluable castRight = rightCompiler.coerce(y, common);
    if (castLeft == null || castRight == null)
      return null;
    return new Candidate(new JoinSource.Key(castLeft, castRight, common), x, y);
  }

  /** The type the two sides of an equality are compared in, {@code null} for a pair this step does not hash. */
  private static PgType commonType(final PgType a, final PgType b) {
    if (a == PgType.UNKNOWN || b == PgType.UNKNOWN)
      return null;
    if (a.isNumber() && b.isNumber())
      return Numbers.common(a, b);
    if (a.isString() && b.isString())
      return PgType.TEXT;
    if (a.isDateTime() && b.isDateTime())
      return a == b ? a : PgType.TIMESTAMP;
    if (a == PgType.BOOL && b == PgType.BOOL)
      return PgType.BOOL;
    return null;
  }

  // ---- the index probe ----

  /**
   * The lookup an index nested loop uses, or {@code null} when the join cannot use one: the right input is a table scan (with or
   * without a pushed condition, which is then checked on every row the lookup returns), a key is a column of it that the
   * single-table plan can look up (a property with an index of its own, or {@code id}), the types of the key are two integers or
   * two strings, and the left input is not larger than the right table.
   */
  private JoinSource.IndexProbe indexProbe(final JoinNode join, final RelationSource right, final List<Candidate> candidates,
      final long leftRows, final boolean startBound) {
    RelationSource base = right;
    Evaluable filter = null;
    if (right instanceof FilteredSource filteredRight) {
      base = filteredRight.inner();
      filter = filteredRight.condition();
    }
    if (!(base instanceof TableSource table) || table.access() != TableSource.Access.SCAN)
      return null;
    final boolean smaller = leftRows <= Math.max(1L, tableRows(table));
    for (int i = 0; i < candidates.size(); i++) {
      final Candidate candidate = candidates.get(i);
      if (!(candidate.rawRight() instanceof Evaluable.Slot slot))
        continue;
      final Column column = table.table().columns().get(slot.index());
      // the end vertex of an edge row is read by its record id whatever the sizes (the traversal rule): the edge says where it is.
      // Only where the start is bound (fork issue #52): the edge rows of a table of all vertices are joined by the sizes
      final boolean followsAnEdge = traversalRule && startBound && table.table().kind() == Table.Kind.VERTEX
          && column.kind() == Column.Kind.IDENTITY && edgeEnd(join, candidate) != null;
      if (!smaller && !followsAnEdge)
        continue;
      final PgType columnType = slot.type();
      final PgType keyType = candidate.rawLeft().type();
      final boolean fits = (columnType.isInteger() && keyType.isInteger()) || (columnType.isString() && keyType.isString());
      if (!fits)
        continue;
      if ((column.kind() == Column.Kind.IDENTITY && columnType.isString()) || table.hasIndexOn(column))
        return new JoinSource.IndexProbe(table, column, i, filter);
    }
    return null;
  }

  /**
   * Whether the join has the shape of the read of the far end of an edge: the right input is a plain scan of a vertex table, and a
   * key joins its {@code id} to an endpoint of an edge table on the left. The rule reads the vertex by its record id for each edge
   * row when the start is bound; the plan line of a join that has the shape and not the start says so.
   */
  private boolean readsFarEnd(final JoinNode join, final RelationSource right, final List<Candidate> candidates) {
    final RelationSource base = right instanceof FilteredSource filteredRight ? filteredRight.inner() : right;
    if (!(base instanceof TableSource table) || table.access() != TableSource.Access.SCAN || table.table().kind() != Table.Kind.VERTEX)
      return false;
    for (final Candidate candidate : candidates)
      if (candidate.rawRight() instanceof Evaluable.Slot slot
          && table.table().columns().get(slot.index()).kind() == Column.Kind.IDENTITY && edgeEnd(join, candidate) != null)
        return true;
    return false;
  }

  // ---- traversal (fork issue #8) ----

  /**
   * The walk that gives the rows of this join, or {@code null} when the rule does not apply: the right input is an edge table
   * (with or without a pushed condition, which then filters the edges of each vertex) with declared endpoint tables, one key
   * of the condition joins its {@code source_id} or {@code target_id} to the {@code id} of a vertex table on the left, and the
   * walk gives the same rows as the equality join (see the class comment).
   */
  private JoinSource.Traversal traversal(final JoinNode join, final RelationSource right, final List<Candidate> candidates) {
    RelationSource base = right;
    Evaluable filter = null;
    if (right instanceof FilteredSource filteredRight) {
      base = filteredRight.inner();
      filter = filteredRight.condition();
    }
    if (!(base instanceof TableSource edges))
      return null;
    final Table edgeTable = edges.table();
    if (edgeTable.kind() != Table.Kind.EDGE || edgeTable.sourceTable() == null || edgeTable.targetTable() == null)
      return null;
    for (int i = 0; i < candidates.size(); i++) {
      final Candidate candidate = candidates.get(i);
      if (!(candidate.rawRight() instanceof Evaluable.Slot endpoint) || !(candidate.rawLeft() instanceof Evaluable.Slot start))
        continue;
      final Column end = edgeTable.columns().get(endpoint.index());
      if (!isEndpoint(end))
        continue;
      final Located vertex = join.left.locate(start.index());
      if (vertex == null)
        continue; // a view of information_schema has no vertex behind its columns
      final Table vertexTable = vertex.table().table();
      if (vertex.column().kind() != Column.Kind.IDENTITY || vertexTable.kind() != Table.Kind.VERTEX)
        continue;
      final boolean outgoing = end.kind() == Column.Kind.SOURCE;
      final String declared = outgoing ? edgeTable.sourceTable() : edgeTable.targetTable();
      if (!related(vertexTable.name(), declared))
        continue;
      String guard = null;
      if (edgeTable.edgeStorage() != Table.EdgeStorage.REGULAR) {
        // a scan of an edge table that can hold lightweight edges walks the declared source table, so a walk may not see more
        if (outgoing && !instanceOf(vertexTable.name(), declared))
          continue;
        if (!outgoing)
          guard = edgeTable.sourceTable();
      }
      if (!outgoing && !storesIncomingSide(edges.edgeTypeNames()))
        continue;
      return new JoinSource.Traversal(database, edges, outgoing ? Vertex.DIRECTION.OUT : Vertex.DIRECTION.IN, i, filter, guard,
          vertex.table());
    }
    return null;
  }

  private boolean instanceOf(final String type, final String superType) {
    return database.getSchema().getType(type).instanceOf(superType);
  }

  /** Whether one of the two types is the other or a subtype of it: the vertices of the one are vertices of the other too. */
  private boolean related(final String a, final String b) {
    return a.equals(b) || instanceOf(a, b) || instanceOf(b, a);
  }

  /**
   * Whether every one of these edge types keeps the incoming side on the vertex: a unidirectional type writes the outgoing
   * pointer only, so an incoming walk of it would answer nothing (the vertex API keeps that contract).
   */
  private boolean storesIncomingSide(final String[] edgeTypes) {
    final Schema schema = database.getSchema();
    for (final String name : edgeTypes) {
      final DocumentType type = schema.getType(name);
      if (!(type instanceof EdgeType edgeType) || !edgeType.isBidirectional())
        return false;
    }
    return true;
  }

  /** The edge endpoint column that a key of the left input reads, {@code null} when the key is not one. */
  private static Located edgeEnd(final JoinNode join, final Candidate candidate) {
    if (!(candidate.rawLeft() instanceof Evaluable.Slot slot))
      return null;
    final Located located = join.left.locate(slot.index());
    return located != null && located.table().table().kind() == Table.Kind.EDGE && isEndpoint(located.column()) ? located : null;
  }

  /** The pattern of the walk, as openCypher would write it: {@code MATCH (p:person)-[w:works_at]->()}. */
  private static String firstHopPattern(final JoinSource.Traversal walk) {
    final String vertex = "(" + walk.from().relation() + ":" + walk.from().table().name() + ")";
    final String edge = "[" + walk.edges().relation() + ":" + walk.edges().table().name() + "]";
    return "MATCH " + (walk.direction() == Vertex.DIRECTION.OUT ? vertex + "-" + edge + "->()" : vertex + "<-" + edge + "-()");
  }

  /**
   * The pattern of the read of the far end of an edge: joined to the walk that produced the edge rows it is the whole hop,
   * {@code MATCH (p:person)-[w:works_at]->(c:company)}; otherwise it is the edge and its far end.
   */
  private static String secondHopPattern(final RelationSource left, final Located edgeEnd, final TableSource far) {
    final String vertex = "(" + far.relation() + ":" + far.table().name() + ")";
    final TableSource edges = edgeEnd.table();
    final String edge = "[" + edges.relation() + ":" + edges.table().name() + "]";
    final boolean toFar = edgeEnd.column().kind() == Column.Kind.TARGET;
    final JoinSource.Traversal walk = walkOf(left, edges.relation());
    String near = "()";
    if (walk != null && (walk.direction() == Vertex.DIRECTION.OUT) == toFar)
      near = "(" + walk.from().relation() + ":" + walk.from().table().name() + ")";
    return "MATCH " + (toFar ? near + "-" + edge + "->" + vertex : vertex + "-" + edge + "->" + near);
  }

  /** The traversal below this source that produced the rows of the edge relation, {@code null} when there is none. */
  private static JoinSource.Traversal walkOf(final RelationSource source, final String edgeRelation) {
    RelationSource current = source;
    while (true) {
      if (current instanceof FilteredSource filtered)
        current = filtered.inner();
      else if (current instanceof JoinSource join) {
        final JoinSource.Traversal walk = join.traversal();
        if (walk != null && walk.edges().relation().equals(edgeRelation))
          return walk;
        current = join.left();
      } else
        return null;
    }
  }

  // ---- support (fork issue #9) ----

  /**
   * Whether an index, a link or an edge pattern supports the join, and why. Question 22 of the prototype decisions: the edge
   * pattern is an equality between {@code source_id} or {@code target_id} of an edge table and an {@code id}; a link is an
   * equality between a {@code LINK} property and an {@code id}; an index is an equality where one side is {@code id} or a
   * property with an index usable on that key. The first of these in that order that any key of the join matches is the answer;
   * when none matches the answer is {@link JoinSupport.Kind#NOTHING} and the reason says what was missing.
   */
  JoinSupport support(final JoinNode join, final List<Candidate> candidates) {
    if (candidates.isEmpty())
      return new JoinSupport(JoinSupport.Kind.NOTHING, "the condition has no equality between a column of " + names(join.left)
          + " and a column of " + names(join.right) + ", so every pair of rows is compared");
    JoinSupport best = null;
    for (final Candidate candidate : candidates) {
      if (!(candidate.rawLeft() instanceof Evaluable.Slot left) || !(candidate.rawRight() instanceof Evaluable.Slot right))
        continue;
      final Located leftColumn = join.left.locate(left.index());
      final Located rightColumn = join.right.locate(right.index());
      if (leftColumn == null || rightColumn == null)
        continue;
      final JoinSupport found = classify(leftColumn, rightColumn);
      if (found != null && (best == null || found.kind().ordinal() < best.kind().ordinal()))
        best = found;
    }
    if (best != null)
      return best;
    return new JoinSupport(JoinSupport.Kind.NOTHING,
        "no index, link or edge pattern on the equality of " + names(join.left) + " and " + names(join.right));
  }

  private static JoinSupport classify(final Located left, final Located right) {
    final Table leftTable = left.table().table();
    final Table rightTable = right.table().table();
    final Column leftColumn = left.column();
    final Column rightColumn = right.column();
    final String pair = leftTable.name() + "." + leftColumn.name() + " = " + rightTable.name() + "." + rightColumn.name();
    if ((isEndpoint(leftColumn) && leftTable.kind() == Table.Kind.EDGE && rightColumn.kind() == Column.Kind.IDENTITY)
        || (isEndpoint(rightColumn) && rightTable.kind() == Table.Kind.EDGE && leftColumn.kind() == Column.Kind.IDENTITY))
      return new JoinSupport(JoinSupport.Kind.EDGE, pair + " joins an edge endpoint to an id");
    if ((leftColumn.isLink() && rightColumn.kind() == Column.Kind.IDENTITY)
        || (rightColumn.isLink() && leftColumn.kind() == Column.Kind.IDENTITY))
      return new JoinSupport(JoinSupport.Kind.LINK, pair + " follows a link to its record");
    if (leftColumn.kind() == Column.Kind.IDENTITY || rightColumn.kind() == Column.Kind.IDENTITY)
      return new JoinSupport(JoinSupport.Kind.INDEX, pair + " reads a record by its id");
    if (left.table().hasIndexOn(leftColumn))
      return new JoinSupport(JoinSupport.Kind.INDEX, pair + ", the index on " + leftTable.name() + "." + leftColumn.name());
    if (right.table().hasIndexOn(rightColumn))
      return new JoinSupport(JoinSupport.Kind.INDEX, pair + ", the index on " + rightTable.name() + "." + rightColumn.name());
    return null;
  }

  private static boolean isEndpoint(final Column column) {
    return column.kind() == Column.Kind.SOURCE || column.kind() == Column.Kind.TARGET;
  }

  // ---- the warning (fork issue #9) ----

  /**
   * Whether the join gets the warning: nothing supports it, and both sides read tables. A view of {@code information_schema} is
   * not a part of the model that a relationship could be missing from, so a join with one never warns.
   */
  private static boolean warns(final JoinNode join, final JoinSupport support) {
    return !support.supported() && !join.left.tables().isEmpty() && !join.right.tables().isEmpty();
  }

  /**
   * The sentence for a join that nothing supports: the two sides, the condition, and what may be missing. The plan lists it and
   * the engine writes it to the log once per statement. When edge types with declared endpoints connect the two sides the
   * sentence names them, otherwise it says that none does and that a relationship may be missing.
   */
  private String warningOf(final JoinNode join, final String condition) {
    final Set<String> leftTables = join.left.tables();
    final Set<String> rightTables = join.right.tables();
    final String left = String.join(", ", leftTables);
    final String right = String.join(", ", rightTables);
    final StringBuilder text = new StringBuilder("The join of ").append(left).append(" and ").append(right).append(" on ")
        .append(condition).append(" has no edge, LINK or index behind it, so every pair of rows is compared.");
    final List<String> edges = connectingEdgeTypes(leftTables, rightTables);
    if (edges.isEmpty())
      text.append(" No edge type connects ").append(left).append(" and ").append(right)
          .append(", so a relationship may be missing from the model.");
    else
      text.append(edges.size() == 1 ? " The edge type " : " The edge types ").append(String.join(", ", edges))
          .append(edges.size() == 1 ? " connects them: join through it" : " connect them: join through one of them")
          .append(", or add an index on the join key.");
    return text.toString();
  }

  /** The edge types with declared endpoint tables that connect a table of one side to a table of the other, as {@code name (a to b)}. */
  private List<String> connectingEdgeTypes(final Set<String> leftTables, final Set<String> rightTables) {
    final Schema schema = database.getSchema();
    final List<String> found = new ArrayList<>();
    for (final DocumentType type : schema.getTypes()) {
      if (!(type instanceof EdgeType edgeType))
        continue;
      final String source = EdgeEndpoints.sourceTable(edgeType);
      final String target = EdgeEndpoints.targetTable(edgeType);
      if (source == null || target == null || !schema.existsType(source) || !schema.existsType(target))
        continue;
      if (connects(leftTables, rightTables, source, target) || connects(rightTables, leftTables, source, target))
        found.add(edgeType.getName() + " (" + source + " to " + target + ")");
    }
    Collections.sort(found);
    return found;
  }

  /** Whether a table of {@code from} is related to the source table of an edge type and one of {@code to} to its target table. */
  private boolean connects(final Set<String> from, final Set<String> to, final String source, final String target) {
    boolean fromSide = false;
    for (final String name : from)
      fromSide |= related(name, source);
    boolean toSide = false;
    for (final String name : to)
      toSide |= related(name, target);
    return fromSide && toSide;
  }

  // ---- estimates ----

  private long tableRows(final TableSource table) {
    return database.countType(table.table().name(), !table.only());
  }

  /** The estimated number of rows of a source: type counts of the schema API, and constant guesses for a condition and a lookup. */
  private long estimate(final RelationSource source) {
    if (source instanceof FilteredSource filteredSource)
      return filteredSource.estimatedRows();
    if (source instanceof JoinSource joinSource)
      return joinSource.estimatedRows();
    if (source instanceof TableSource table) {
      final long count = Math.max(1L, tableRows(table));
      return switch (table.access()) {
        case ID -> 1L;
        case INDEX -> Math.max(1L, count / 10);
        default -> count;
      };
    }
    return 1000L;
  }
}
