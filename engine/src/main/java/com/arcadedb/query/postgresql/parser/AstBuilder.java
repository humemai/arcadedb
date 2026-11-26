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
package com.arcadedb.query.postgresql.parser;

import com.arcadedb.query.postgresql.PostgreSQLInvalidEscapeException;
import com.arcadedb.query.postgresql.ast.CreateTableStatement;
import com.arcadedb.query.postgresql.ast.DeleteStatement;
import com.arcadedb.query.postgresql.ast.EmptyStatement;
import com.arcadedb.query.postgresql.ast.ExplainStatement;
import com.arcadedb.query.postgresql.ast.ExplainWriteStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Expression.BinaryOperator;
import com.arcadedb.query.postgresql.ast.Expression.SpecialFunctionKind;
import com.arcadedb.query.postgresql.ast.Expression.UnaryOperator;
import com.arcadedb.query.postgresql.ast.InsertStatement;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SetOperationStatement;
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.ast.TransactionStatement;
import com.arcadedb.query.postgresql.ast.TypeName;
import com.arcadedb.query.postgresql.ast.UpdateStatement;
import com.arcadedb.query.postgresql.ast.WithStatement;
import com.arcadedb.query.postgresql.ast.WriteStatement;
import com.arcadedb.query.postgresql.ast.WriteValue;
import com.arcadedb.query.postgresql.grammar.PostgreSQLParser;
import com.arcadedb.query.postgresql.grammar.PostgreSQLParser.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the syntax tree from the parse tree. It runs only on a parse tree that has no construct outside the subset (the
 * caller has refused those), so it reads the alternatives of the grammar that the subset has, and a few refusals that only a
 * value can show: a name with three parts, an operator the language does not know, an empty quoted identifier.
 *
 * <p>It does what the actions of PostgreSQL's grammar do: it folds a minus sign into a literal that follows it, rewrites the
 * keyword functions into calls of {@code pg_catalog} functions, and normalises the type names.
 */
final class AstBuilder {
  Statement build(final RootContext root) {
    final StmtContext stmt = root.stmt();
    if (stmt instanceof SelectCommandContext command)
      return query(command.selectStmt());
    if (stmt instanceof InsertCommandContext command)
      return insert(command.insertStmt());
    if (stmt instanceof UpdateCommandContext command)
      return update(command.updateStmt());
    if (stmt instanceof DeleteCommandContext command)
      return delete(command.deleteStmt());
    if (stmt instanceof TransactionCommandContext command)
      return transaction(command.transactionStmt());
    if (stmt instanceof ExplainCommandContext command) {
      final ExplainStmtContext explain = command.explainStmt();
      if (explain.writeStmt() != null)
        return new ExplainWriteStatement(position(command.getStart()), write(explain.writeStmt()));
      return new ExplainStatement(position(command.getStart()), query(explain.selectStmt()));
    }
    if (stmt instanceof CreateTableCommandContext command)
      return createTable(command.createTableStmt());
    if (stmt instanceof EmptyCommandContext)
      return new EmptyStatement(position(stmt.getStart()));
    throw new IllegalStateException("A statement the parser did not refuse has no tree: " + stmt.getText());
  }

  // ------------------------------------------------------------------------------------------------ CREATE TABLE

  private CreateTableStatement createTable(final CreateTableStmtContext context) {
    final CreateTableElementsContext body = (CreateTableElementsContext) context.createTableBody();
    final List<CreateTableStatement.Column> columns = new ArrayList<>();
    final List<CreateTableStatement.Constraint> constraints = new ArrayList<>();
    for (final TableElementContext element : body.items) {
      if (element instanceof ColumnElementContext column) {
        final List<CreateTableStatement.Constraint> own = new ArrayList<>();
        for (final ColumnOptionContext option : column.constraints)
          own.add(columnConstraint(option));
        columns.add(new CreateTableStatement.Column(position(column.getStart()), name(column.column), typeName(column.typename()),
            own));
      } else
        constraints.add(tableConstraint(((ConstraintElementContext) element).tableConstraint()));
    }
    return new CreateTableStatement(position(context.getStart()), qualifiedName(context.table, context.table.getStart()),
        context.EXISTS() != null, columns, constraints);
  }

  private CreateTableStatement.Constraint columnConstraint(final ColumnOptionContext option) {
    final Name name = option.constraintName == null ? null : name(option.constraintName);
    final ColumnConstraintElemContext element = option.columnConstraintElem();
    final SourcePosition position = position(option.getStart());
    if (element instanceof ReferencesColumnConstraintContext references)
      return new CreateTableStatement.Constraint(position, CreateTableStatement.Kind.FOREIGN_KEY, name, List.of(), null,
          reference(references.references()));
    if (element instanceof DefaultConstraintContext value)
      return new CreateTableStatement.Constraint(position, CreateTableStatement.Kind.DEFAULT, name, List.of(),
          bExpression(value.bExpr()), null);
    final CreateTableStatement.Kind kind;
    if (element instanceof NotNullConstraintContext)
      kind = CreateTableStatement.Kind.NOT_NULL;
    else if (element instanceof NullConstraintContext)
      kind = CreateTableStatement.Kind.NULL;
    else if (element instanceof UniqueColumnConstraintContext)
      kind = CreateTableStatement.Kind.UNIQUE;
    else if (element instanceof PrimaryKeyColumnConstraintContext)
      kind = CreateTableStatement.Kind.PRIMARY_KEY;
    else
      throw new IllegalStateException("A constraint the parser did not refuse has no tree: " + element.getText());
    return new CreateTableStatement.Constraint(position, kind, name, List.of(), null, null);
  }

  private CreateTableStatement.Constraint tableConstraint(final TableConstraintContext context) {
    final Name name = context.constraintName == null ? null : name(context.constraintName);
    final TableConstraintElemContext element = context.tableConstraintElem();
    final SourcePosition position = position(context.getStart());
    if (element instanceof UniqueTableConstraintContext unique)
      return new CreateTableStatement.Constraint(position, CreateTableStatement.Kind.UNIQUE, name, names(unique.columns), null,
          null);
    if (element instanceof PrimaryKeyTableConstraintContext primary)
      return new CreateTableStatement.Constraint(position, CreateTableStatement.Kind.PRIMARY_KEY, name, names(primary.columns),
          null, null);
    final ForeignKeyTableConstraintContext foreign = (ForeignKeyTableConstraintContext) element;
    return new CreateTableStatement.Constraint(position, CreateTableStatement.Kind.FOREIGN_KEY, name, names(foreign.columns),
        null, reference(foreign.references()));
  }

  private List<Name> names(final List<ColIdContext> contexts) {
    final List<Name> names = new ArrayList<>(contexts.size());
    for (final ColIdContext context : contexts)
      names.add(name(context));
    return names;
  }

  private CreateTableStatement.Reference reference(final ReferencesContext context) {
    CreateTableStatement.Action onDelete = null;
    CreateTableStatement.Action onUpdate = null;
    for (final ReferenceOptionContext option : context.actions) {
      if (option instanceof OnDeleteOptionContext delete) {
        if (onDelete != null)
          throw Diagnostics.syntaxError(option.getStart());
        onDelete = action(delete.referenceAction());
      } else {
        if (onUpdate != null)
          throw Diagnostics.syntaxError(option.getStart());
        onUpdate = action(((OnUpdateOptionContext) option).referenceAction());
      }
    }
    return new CreateTableStatement.Reference(position(context.getStart()),
        qualifiedName(context.table, context.table.getStart()), names(context.columns),
        onDelete == null ? CreateTableStatement.Action.NO_ACTION : onDelete,
        onUpdate == null ? CreateTableStatement.Action.NO_ACTION : onUpdate);
  }

  private static CreateTableStatement.Action action(final ReferenceActionContext context) {
    if (context instanceof NoActionContext)
      return CreateTableStatement.Action.NO_ACTION;
    if (context instanceof RestrictActionContext)
      return CreateTableStatement.Action.RESTRICT;
    if (context instanceof CascadeActionContext)
      return CreateTableStatement.Action.CASCADE;
    return context instanceof SetNullActionContext ? CreateTableStatement.Action.SET_NULL
        : CreateTableStatement.Action.SET_DEFAULT;
  }

  // ------------------------------------------------------------------------------------------------------ writes

  private WriteStatement write(final WriteStmtContext context) {
    if (context.insertStmt() != null)
      return insert(context.insertStmt());
    if (context.updateStmt() != null)
      return update(context.updateStmt());
    return delete(context.deleteStmt());
  }

  private InsertStatement insert(final InsertStmtContext context) {
    final InsertTargetContext target = context.target;
    final Name alias = target.alias == null ? null : name(target.alias);
    final TableReference.Table table = new TableReference.Table(position(target.getStart()),
        qualifiedName(target.qualifiedName(), target.getStart()), false, alias);

    final List<Name> columns = new ArrayList<>();
    final InsertStatement.Source source;
    if (context.rest instanceof InsertDefaultValuesContext defaults) {
      source = new InsertStatement.DefaultValues(position(defaults.getStart()));
    } else {
      final InsertColumnsAndSourceContext rest = (InsertColumnsAndSourceContext) context.rest;
      if (rest.columns != null)
        for (final InsertColumnContext column : rest.columns.items)
          columns.add(name(column.colId()));
      if (rest.source instanceof InsertFromSelectContext query) {
        // INSERT ... SELECT reads one SELECT; a set operation or a WITH in the source is outside the subset
        if (!(query(query.selectStmt()) instanceof SelectStatement select))
          throw Diagnostics.notSupported("A set operation or a WITH in the source of an INSERT", query.getStart());
        source = new InsertStatement.Query(position(query.getStart()), select);
      } else if (rest.source instanceof InsertFromValuesContext values) {
        final List<List<WriteValue>> rows = new ArrayList<>();
        for (final ValuesRowContext row : values.valuesClause().rows) {
          final List<WriteValue> items = new ArrayList<>();
          for (final WriteValueContext item : row.items)
            items.add(writeValue(item));
          // PostgreSQL checks the rows against the first one while it reads the VALUES list
          if (!rows.isEmpty() && items.size() != rows.get(0).size())
            throw Diagnostics.syntaxError("VALUES lists must all be the same length", row.getStart());
          rows.add(items);
        }
        source = new InsertStatement.Values(position(values.getStart()), rows);
      } else {
        throw new IllegalStateException("An INSERT source the parser did not refuse has no tree: " + rest.source.getText());
      }
    }
    return new InsertStatement(position(context.getStart()), table, columns, source, onConflict(context.onConflictClause()),
        returning(context.returningClause()));
  }

  private InsertStatement.OnConflict onConflict(final OnConflictClauseContext context) {
    if (context == null)
      return null;
    final List<Name> target = new ArrayList<>();
    if (context.target instanceof ConflictConstraintContext constraint)
      throw Diagnostics.notSupported("ON CONFLICT ON CONSTRAINT", constraint.getStart());
    if (context.target instanceof ConflictColumnsContext columns) {
      if (columns.predicate != null)
        throw Diagnostics.notSupported("An index predicate in the target of ON CONFLICT", columns.predicate.getStart());
      for (final AExprContext item : columns.items) {
        // PostgreSQL reads an index expression here: only a plain column name is in the subset
        final Expression expression = expression(item);
        if (!(expression instanceof Expression.ColumnRef reference) || !reference.qualifier().isEmpty())
          throw Diagnostics.notSupported("An expression in the target of ON CONFLICT", item.getStart());
        target.add(reference.column());
      }
    }
    final List<UpdateStatement.Assignment> assignments = new ArrayList<>();
    Expression where = null;
    if (context.action instanceof ConflictUpdateContext update) {
      for (final SetClauseContext item : update.items) {
        if (!(item instanceof SetSingleContext single))
          throw Diagnostics.notSupported("A multiple-column assignment", item.getStart());
        assignments.add(new UpdateStatement.Assignment(position(single.getStart()), name(single.column), writeValue(single.value)));
      }
      if (update.condition != null)
        where = expression(update.condition);
      if (target.isEmpty())
        throw Diagnostics.syntaxError("ON CONFLICT DO UPDATE requires inference specification or constraint name", context.getStart());
    }
    return new InsertStatement.OnConflict(position(context.getStart()), target, assignments, where);
  }

  private UpdateStatement update(final UpdateStmtContext context) {
    final List<UpdateStatement.Assignment> assignments = new ArrayList<>();
    for (final SetClauseContext item : context.items) {
      if (!(item instanceof SetSingleContext single))
        throw new IllegalStateException("A multiple-column assignment should have been refused");
      assignments.add(new UpdateStatement.Assignment(position(single.getStart()), name(single.column), writeValue(single.value)));
    }
    final List<TableReference> from = new ArrayList<>();
    for (final TableRefContext table : context.fromItems)
      from.add(tableReference(table));
    final Expression where = context.writeWhereClause() == null ? null : expression(context.writeWhereClause().aExpr());
    return new UpdateStatement(position(context.getStart()), writeTarget(context.target), assignments, from, where,
        returning(context.returningClause()));
  }

  private DeleteStatement delete(final DeleteStmtContext context) {
    final Expression where = context.writeWhereClause() == null ? null : expression(context.writeWhereClause().aExpr());
    return new DeleteStatement(position(context.getStart()), writeTarget(context.target), where,
        returning(context.returningClause()));
  }

  /** The table of an {@code UPDATE} or a {@code DELETE}: {@code relation_expr_opt_alias} of {@code gram.y}. */
  private TableReference.Table writeTarget(final UpdateTargetContext target) {
    final RelationExprContext relation = target.relationExpr();
    final Name alias = target.alias == null ? null : name(target.alias);
    return new TableReference.Table(position(target.getStart()), qualifiedName(relation.qualifiedName(), relation.getStart()),
        has(relation, PostgreSQLParser.ONLY), alias);
  }

  private WriteValue writeValue(final WriteValueContext context) {
    if (context instanceof ExpressionValueContext value)
      return new WriteValue(position(context.getStart()), expression(value.aExpr()));
    return new WriteValue(position(context.getStart()), null);
  }

  private List<SelectItem> returning(final ReturningClauseContext context) {
    final List<SelectItem> items = new ArrayList<>();
    if (context != null)
      for (final TargetElContext item : context.targetList().items)
        items.add(selectItem(item));
    return items;
  }

  private TransactionStatement transaction(final TransactionStmtContext context) {
    final TransactionStatement.Kind kind;
    if (context instanceof BeginTransactionContext || context instanceof StartTransactionContext)
      kind = TransactionStatement.Kind.BEGIN;
    else if (context instanceof CommitTransactionContext)
      kind = TransactionStatement.Kind.COMMIT;
    else
      kind = TransactionStatement.Kind.ROLLBACK;
    return new TransactionStatement(position(context.getStart()), kind);
  }

  /** {@code qualified_name} of a table: one part or schema and name; a name with three parts is a cross-database reference. */
  private List<Name> qualifiedName(final QualifiedNameContext context, final Token start) {
    final List<Name> parts = new ArrayList<>();
    parts.add(name(context.first));
    for (final AttrNameContext part : context.rest)
      parts.add(name(part));
    if (parts.size() == 3)
      throw Diagnostics.notSupported("A cross-database table name", start);
    if (parts.size() > 3)
      throw Diagnostics.syntaxError("improper qualified name (too many dotted names)", start);
    return parts;
  }

  // ------------------------------------------------------------------------------------------------------ SELECT

  /**
   * A query: {@code select_no_parens} of {@code gram.y}. The {@code ORDER BY}, {@code OFFSET} and {@code LIMIT} that follow the
   * select clause belong to the whole of it: to a single {@code SELECT} (a node of its own), or to a set operation. A parenthesised
   * query that already has its own order or limit cannot get a second one, as in PostgreSQL.
   */
  private Statement query(final SelectStmtContext context) {
    if (context == null)
      throw new IllegalStateException("EXPLAIN without a SELECT should have been refused");

    final List<SortItem> orderBy = new ArrayList<>();
    if (context.sortClause() != null)
      for (final SortByContext item : context.sortClause().items)
        orderBy.add(sortItem((SortKeyContext) item));

    Expression limit = null;
    Expression offset = null;
    final SelectLimitContext selectLimit = context.selectLimit();
    if (selectLimit != null) {
      if (selectLimit.limitClause() != null && selectLimit.limitClause().aExpr() != null)
        limit = expression(selectLimit.limitClause().aExpr());
      final OffsetClauseContext offsetClause = selectLimit.offsetClause();
      if (offsetClause != null) {
        if (offsetClause.aExpr() != null)
          offset = expression(offsetClause.aExpr());
        else
          offset = fetchFirstValue(offsetClause.fetchFirstValue());
      }
    }

    final Statement body = withOrderAndLimit(queryExpression(context.queryExpr()), orderBy, limit, offset, context.getStart());
    final WithClauseContext with = context.withClause();
    if (with == null)
      return body;
    final List<WithStatement.Cte> ctes = new ArrayList<>();
    for (final CommonTableExprContext cte : with.items) {
      final List<Name> columns = new ArrayList<>();
      if (cte.columns != null)
        for (final ColIdContext column : cte.columns.names)
          columns.add(name(column));
      ctes.add(new WithStatement.Cte(name(cte.name), columns, query(cte.body)));
    }
    return new WithStatement(position(with.getStart()), ctes, body);
  }

  private Statement queryExpression(final QueryExprContext context) {
    if (context instanceof PrimaryQueryContext primary)
      return queryPrimary(primary.queryPrimary());
    if (context instanceof IntersectQueryContext intersect)
      return new SetOperationStatement(position(intersect.getStart()), SetOperationStatement.Kind.INTERSECT,
          intersect.setQuantifier() != null && has(intersect.setQuantifier(), PostgreSQLParser.ALL), queryExpression(intersect.l),
          queryExpression(intersect.r), List.of(), null, null);
    final UnionQueryContext union = (UnionQueryContext) context;
    return new SetOperationStatement(position(union.getStart()),
        union.op.getType() == PostgreSQLParser.UNION ? SetOperationStatement.Kind.UNION : SetOperationStatement.Kind.EXCEPT,
        union.setQuantifier() != null && has(union.setQuantifier(), PostgreSQLParser.ALL), queryExpression(union.l),
        queryExpression(union.r), List.of(), null, null);
  }

  private Statement queryPrimary(final QueryPrimaryContext context) {
    if (context instanceof SimpleQueryContext simple)
      return simpleSelect(simple.simpleSelect());
    if (context instanceof ParenQueryContext paren)
      return query(paren.selectStmt());
    if (context instanceof ValuesQueryPrimaryContext values)
      return valuesQuery(values.valuesQuery());
    throw new IllegalStateException("A query the parser did not refuse has no tree: " + context.getText());
  }

  /** The most rows of a {@code VALUES} query: each row is a branch of the {@code UNION ALL} that stands for the list. */
  private static final int MAX_VALUES_ROWS = 500;

  /**
   * {@code VALUES (a, b), (c, d)} is the query {@code SELECT a AS column1, b AS column2 UNION ALL SELECT c, d}: PostgreSQL types
   * each column of a {@code VALUES} list with the rules of {@code UNION} (an unknown literal is text unless the rows unify), and
   * names the columns {@code column1}, {@code column2}. The planner needs no node of its own for it.
   */
  private Statement valuesQuery(final ValuesQueryContext context) {
    if (context.rows.size() > MAX_VALUES_ROWS)
      throw Diagnostics.notSupported("A VALUES list of more than " + MAX_VALUES_ROWS + " rows", context.getStart());
    Statement result = null;
    int width = -1;
    for (final ValuesQueryRowContext row : context.rows) {
      if (width >= 0 && row.items.size() != width)
        throw Diagnostics.syntaxError("VALUES lists must all be the same length", row.getStart());
      final List<SelectItem> items = new ArrayList<>();
      for (final WriteValueContext item : row.items) {
        final WriteValue value = writeValue(item);
        if (value.isDefault())
          throw Diagnostics.syntaxError("DEFAULT is not allowed in this context", item.getStart());
        final SourcePosition position = position(item.getStart());
        items.add(new SelectItem.Projection(position, value.expression(),
            width < 0 ? new Name(position, "column" + (items.size() + 1), true) : null));
      }
      width = items.size();
      final SelectStatement select = new SelectStatement(position(row.getStart()), false, items, List.of(), null, List.of(), null,
          List.of(), null, null);
      result = result == null ? select
          : new SetOperationStatement(position(row.getStart()), SetOperationStatement.Kind.UNION, true, result, select, List.of(), null,
              null);
    }
    return result;
  }

  /** The order, the offset and the limit of a select clause, which must not have its own. */
  private static Statement withOrderAndLimit(final Statement body, final List<SortItem> orderBy, final Expression limit,
      final Expression offset, final Token start) {
    if (orderBy.isEmpty() && limit == null && offset == null)
      return body;
    return switch (body) {
      case SelectStatement select -> {
        checkNotYetGiven(!select.orderBy().isEmpty() && !orderBy.isEmpty(), select.limit() != null && limit != null,
            select.offset() != null && offset != null, start);
        yield new SelectStatement(select.position(), select.distinct(), select.items(), select.from(), select.where(),
            select.groupBy(), select.having(), orderBy.isEmpty() ? select.orderBy() : orderBy,
            limit == null ? select.limit() : limit, offset == null ? select.offset() : offset);
      }
      case SetOperationStatement set -> {
        checkNotYetGiven(!set.orderBy().isEmpty() && !orderBy.isEmpty(), set.limit() != null && limit != null,
            set.offset() != null && offset != null, start);
        yield new SetOperationStatement(set.position(), set.kind(), set.all(), set.left(), set.right(),
            orderBy.isEmpty() ? set.orderBy() : orderBy, limit == null ? set.limit() : limit,
            offset == null ? set.offset() : offset);
      }
      case WithStatement with -> new WithStatement(with.position(), with.ctes(),
          withOrderAndLimit(with.body(), orderBy, limit, offset, start));
      default -> throw new IllegalStateException("Not a query: " + body);
    };
  }

  private static void checkNotYetGiven(final boolean order, final boolean limit, final boolean offset, final Token start) {
    if (order)
      throw Diagnostics.syntaxError("multiple ORDER BY clauses not allowed", start);
    if (limit)
      throw Diagnostics.syntaxError("multiple LIMIT clauses not allowed", start);
    if (offset)
      throw Diagnostics.syntaxError("multiple OFFSET clauses not allowed", start);
  }

  /** One {@code SELECT} without its {@code ORDER BY}, {@code OFFSET} and {@code LIMIT}: {@code simple_select} of {@code gram.y}. */
  private SelectStatement simpleSelect(final SimpleSelectContext context) {
    final Token start = context.getStart();
    if (context.targetList() == null)
      throw Diagnostics.notSupported("A select list with no item", start);

    final boolean distinct = context.selectQuantifier() != null && has(context.selectQuantifier(), PostgreSQLParser.DISTINCT);

    final List<SelectItem> items = new ArrayList<>();
    for (final TargetElContext item : context.targetList().items)
      items.add(selectItem(item));

    final List<TableReference> from = new ArrayList<>();
    if (context.fromClause() != null)
      for (final TableRefContext table : context.fromClause().items)
        from.add(tableReference(table));

    final Expression where = context.whereClause() == null ? null : expression(context.whereClause().aExpr());

    final List<Expression> groupBy = new ArrayList<>();
    if (context.groupClause() != null)
      for (final GroupByItemContext item : context.groupClause().items)
        groupBy.add(expression(item.aExpr()));

    final Expression having = context.havingClause() == null ? null : expression(context.havingClause().aExpr());

    return new SelectStatement(position(start), distinct, items, from, where, groupBy, having, List.of(), null, null);
  }

  private SelectItem selectItem(final TargetElContext context) {
    if (context instanceof TargetStarContext)
      return new SelectItem.Star(position(context.getStart()), List.of());
    if (context instanceof TargetQualifiedStarContext star) {
      final QualifiedStarContext qualified = star.qualifiedStar();
      final List<Name> qualifier = new ArrayList<>();
      qualifier.add(name(qualified.first));
      for (final ColLabelContext part : qualified.rest)
        qualifier.add(name(part));
      if (qualifier.size() > 2)
        throw Diagnostics.notSupported("A cross-database reference", qualified.getStart());
      return new SelectItem.Star(position(context.getStart()), qualifier);
    }
    final TargetExpressionContext expression = (TargetExpressionContext) context;
    Name alias = null;
    if (expression.colLabel() != null)
      alias = name(expression.colLabel());
    else if (expression.bareColLabel() != null)
      alias = name(expression.bareColLabel());
    return new SelectItem.Projection(position(context.getStart()), expression(expression.aExpr()), alias);
  }

  private SortItem sortItem(final SortKeyContext context) {
    final SortItem.Direction direction = context.dir == null ? SortItem.Direction.DEFAULT
        : context.dir.getType() == PostgreSQLParser.ASC ? SortItem.Direction.ASC : SortItem.Direction.DESC;
    SortItem.Nulls nulls = SortItem.Nulls.DEFAULT;
    if (context.nullsOrder() != null)
      nulls = has(context.nullsOrder(), PostgreSQLParser.FIRST) ? SortItem.Nulls.FIRST : SortItem.Nulls.LAST;
    return new SortItem(position(context.getStart()), expression(context.aExpr()), direction, nulls);
  }

  /** The offset of {@code OFFSET n ROWS}: a {@code c_expr} or a signed number. */
  private Expression fetchFirstValue(final FetchFirstValueContext context) {
    if (context.cExpr() != null)
      return primary(context.cExpr());
    final Token sign = context.getStart();
    final Token number = context.getStop();
    final boolean negative = sign.getType() == PostgreSQLParser.MINUS;
    return number.getType() == PostgreSQLParser.ICONST ? integer(number, negative) : decimal(number, negative);
  }

  // ------------------------------------------------------------------------------------------------------ FROM

  private TableReference tableReference(final TableRefContext context) {
    if (context instanceof TableNameRefContext table) {
      final RelationExprContext relation = table.relationExpr();
      final List<Name> parts = qualifiedName(relation.qualifiedName(), relation.getStart());
      final Name alias = table.aliasClause() == null ? null : name(table.aliasClause().alias);
      return new TableReference.Table(position(context.getStart()), parts, has(relation, PostgreSQLParser.ONLY), alias);
    }
    if (context instanceof CrossJoinRefContext cross) {
      // a CROSS JOIN is an INNER join whose condition is true, as PostgreSQL plans it and as a comma list is planned here
      final TableReference left = tableReference(cross.tableRef(0));
      final TableReference right = tableReference(cross.tableRef(1));
      return new TableReference.Join(position(context.getStart()), left, TableReference.JoinType.INNER, right,
          new Expression.BooleanLiteral(position(cross.CROSS().getSymbol()), true));
    }
    if (context instanceof JoinRefContext join) {
      final TableReference left = tableReference(join.tableRef(0));
      final TableReference right = tableReference(join.tableRef(1));
      final TableReference.JoinType type = join.joinKind() == null ? TableReference.JoinType.INNER
          : has(join.joinKind(), PostgreSQLParser.LEFT) ? TableReference.JoinType.LEFT
          : has(join.joinKind(), PostgreSQLParser.RIGHT) ? TableReference.JoinType.RIGHT
          : has(join.joinKind(), PostgreSQLParser.FULL) ? TableReference.JoinType.FULL : TableReference.JoinType.INNER;
      final JoinQualContext qualification = join.joinQual();
      if (qualification.aExpr() != null)
        return new TableReference.Join(position(context.getStart()), left, type, right, expression(qualification.aExpr()));
      final List<Name> using = new ArrayList<>();
      for (final ColIdContext column : qualification.names)
        using.add(name(column));
      return new TableReference.Join(position(context.getStart()), left, type, right, null, using);
    }
    if (context instanceof DerivedRefContext derived) {
      final DerivedAliasContext alias = derived.derivedAlias();
      final List<Name> columns = new ArrayList<>();
      if (alias != null && alias.columns != null)
        for (final ColIdContext column : alias.columns.names)
          columns.add(name(column));
      return new TableReference.Subquery(position(context.getStart()), query(derived.selectStmt()),
          alias == null ? null : name(alias.alias), columns);
    }
    if (context instanceof ParenRefContext paren) {
      final TableReference inner = tableReference(paren.tableRef());
      // PostgreSQL has parentheses around a join only, not around a table
      if (!(inner instanceof TableReference.Join))
        throw Diagnostics.syntaxError(paren.getStop());
      return inner;
    }
    throw new IllegalStateException("A table reference the parser did not refuse has no tree: " + context.getText());
  }

  // ------------------------------------------------------------------------------------------------------ expressions

  private Expression expression(final AExprContext context) {
    if (context instanceof PrimaryExprContext e)
      return primary(e.cExpr());
    if (context instanceof TypecastExprContext e)
      return new Expression.Cast(position(e.getStart()), expression(e.aExpr()), typeName(e.typename()));
    if (context instanceof UnaryExprContext e)
      return unary(e.op, expression(e.aExpr()));
    if (context instanceof PowerExprContext e)
      return binary(BinaryOperator.POWER, e.CARET().getSymbol(), expression(e.l), expression(e.r));
    if (context instanceof MultiplicativeExprContext e)
      return binary(arithmetic(e.op), e.op, expression(e.l), expression(e.r));
    if (context instanceof AdditiveExprContext e)
      return binary(arithmetic(e.op), e.op, expression(e.l), expression(e.r));
    if (context instanceof OperatorExprContext e)
      return binary(operator(e.OP().getSymbol()), e.OP().getSymbol(), expression(e.l), expression(e.r));
    if (context instanceof InListExprContext e) {
      final List<Expression> values = new ArrayList<>();
      for (final AExprContext value : e.exprList().items)
        values.add(expression(value));
      return new Expression.InList(position(e.getStart()), expression(e.aExpr()), values, has(e, PostgreSQLParser.NOT));
    }
    if (context instanceof InSubqueryExprContext e)
      return new Expression.InSubquery(position(e.getStart()), expression(e.aExpr()), query(e.selectStmt()),
          has(e, PostgreSQLParser.NOT));
    if (context instanceof BetweenExprContext e)
      return new Expression.Between(position(e.getStart()), expression(e.aExpr(0)), bExpression(e.low), expression(e.high),
          has(e, PostgreSQLParser.NOT));
    if (context instanceof LikeEscapeExprContext e)
      return new Expression.Like(position(e.getStart()), expression(e.aExpr(0)), bExpression(e.pattern), expression(e.escape),
          has(e, PostgreSQLParser.NOT), e.op.getType() == PostgreSQLParser.ILIKE);
    if (context instanceof LikeExprContext e)
      return new Expression.Like(position(e.getStart()), expression(e.aExpr(0)), expression(e.pattern), null,
          has(e, PostgreSQLParser.NOT), e.op.getType() == PostgreSQLParser.ILIKE);
    if (context instanceof ComparisonExprContext e) {
      // the comparison operators do not associate: a < b < c is a syntax error in PostgreSQL
      if (e.l instanceof ComparisonExprContext)
        throw Diagnostics.syntaxError(e.op);
      return binary(comparison(e.op), e.op, expression(e.l), expression(e.r));
    }
    if (context instanceof IsDistinctExprContext e)
      return new Expression.BinaryOp(position(e.getStart()),
          has(e, PostgreSQLParser.NOT) ? Expression.BinaryOperator.IS_NOT_DISTINCT_FROM : Expression.BinaryOperator.IS_DISTINCT_FROM,
          expression(e.l), expression(e.r));
    if (context instanceof IsNullExprContext e)
      return new Expression.IsNull(position(e.getStart()), expression(e.aExpr()), has(e, PostgreSQLParser.NOT));
    if (context instanceof IsNullPostfixExprContext e)
      return new Expression.IsNull(position(e.getStart()), expression(e.aExpr()), e.op.getType() == PostgreSQLParser.NOTNULL);
    if (context instanceof NotExprContext e)
      return new Expression.UnaryOp(position(e.NOT().getSymbol()), UnaryOperator.NOT, expression(e.aExpr()));
    if (context instanceof AndExprContext e)
      return binary(BinaryOperator.AND, e.AND().getSymbol(), expression(e.l), expression(e.r));
    if (context instanceof OrExprContext e)
      return binary(BinaryOperator.OR, e.OR().getSymbol(), expression(e.l), expression(e.r));
    throw new IllegalStateException("An expression the parser did not refuse has no tree: " + context.getText());
  }

  /** {@code b_expr}: the operands of BETWEEN and POSITION. */
  private Expression bExpression(final BExprContext context) {
    if (context instanceof BPrimaryExprContext e)
      return primary(e.cExpr());
    if (context instanceof BTypecastExprContext e)
      return new Expression.Cast(position(e.getStart()), bExpression(e.bExpr()), typeName(e.typename()));
    if (context instanceof BUnaryExprContext e)
      return unary(e.op, bExpression(e.bExpr()));
    if (context instanceof BPowerExprContext e)
      return binary(BinaryOperator.POWER, e.CARET().getSymbol(), bExpression(e.l), bExpression(e.r));
    if (context instanceof BMultiplicativeExprContext e)
      return binary(arithmetic(e.op), e.op, bExpression(e.l), bExpression(e.r));
    if (context instanceof BAdditiveExprContext e)
      return binary(arithmetic(e.op), e.op, bExpression(e.l), bExpression(e.r));
    if (context instanceof BOperatorExprContext e)
      return binary(operator(e.OP().getSymbol()), e.OP().getSymbol(), bExpression(e.l), bExpression(e.r));
    if (context instanceof BComparisonExprContext e) {
      if (e.l instanceof BComparisonExprContext)
        throw Diagnostics.syntaxError(e.op);
      return binary(comparison(e.op), e.op, bExpression(e.l), bExpression(e.r));
    }
    throw new IllegalStateException("An expression the parser did not refuse has no tree: " + context.getText());
  }

  private Expression binary(final BinaryOperator operator, final Token token, final Expression left, final Expression right) {
    return new Expression.BinaryOp(position(token), operator, left, right);
  }

  /** Unary minus in front of a number is the negative number, as {@code doNegate} makes it in {@code gram.y}. */
  private Expression unary(final Token operator, final Expression operand) {
    if (operator.getType() == PostgreSQLParser.MINUS) {
      if (operand instanceof Expression.IntegerLiteral literal)
        return new Expression.IntegerLiteral(position(operator), literal.value().negate());
      if (operand instanceof Expression.DecimalLiteral literal)
        return new Expression.DecimalLiteral(position(operator), literal.value().negate());
      return new Expression.UnaryOp(position(operator), UnaryOperator.MINUS, operand);
    }
    return new Expression.UnaryOp(position(operator), UnaryOperator.PLUS, operand);
  }

  private static BinaryOperator arithmetic(final Token token) {
    return switch (token.getType()) {
      case PostgreSQLParser.PLUS -> BinaryOperator.ADD;
      case PostgreSQLParser.MINUS -> BinaryOperator.SUBTRACT;
      case PostgreSQLParser.STAR -> BinaryOperator.MULTIPLY;
      case PostgreSQLParser.SLASH -> BinaryOperator.DIVIDE;
      case PostgreSQLParser.PERCENT -> BinaryOperator.MODULO;
      default -> throw new IllegalStateException("Not an arithmetic operator: " + token.getText());
    };
  }

  private static BinaryOperator comparison(final Token token) {
    return switch (token.getType()) {
      case PostgreSQLParser.EQ -> BinaryOperator.EQUAL;
      case PostgreSQLParser.NE -> BinaryOperator.NOT_EQUAL;
      case PostgreSQLParser.LT -> BinaryOperator.LESS;
      case PostgreSQLParser.LE -> BinaryOperator.LESS_OR_EQUAL;
      case PostgreSQLParser.GT -> BinaryOperator.GREATER;
      case PostgreSQLParser.GE -> BinaryOperator.GREATER_OR_EQUAL;
      default -> throw new IllegalStateException("Not a comparison operator: " + token.getText());
    };
  }

  /** The text of a generic operator ({@code Op}) is mapped here, so a new operator needs no change of the grammar. */
  private static BinaryOperator operator(final Token token) {
    return switch (token.getText()) {
      case "||" -> BinaryOperator.CONCAT;
      case "<->" -> BinaryOperator.L2_DISTANCE;
      case "<=>" -> BinaryOperator.COSINE_DISTANCE;
      case "<#>" -> BinaryOperator.NEGATIVE_INNER_PRODUCT;
      default -> throw Diagnostics.notSupported("The operator " + token.getText(), token);
    };
  }

  private Expression primary(final CExprContext context) {
    if (context instanceof ColumnRefExprContext e) {
      final ColumnRefContext reference = e.columnRef();
      final List<Name> parts = new ArrayList<>();
      parts.add(name(reference.first));
      for (final AttrNameContext part : reference.rest)
        parts.add(name(part));
      if (parts.size() > 3)
        throw Diagnostics.notSupported("A cross-database column reference", reference.getStart());
      return new Expression.ColumnRef(position(e.getStart()), parts.subList(0, parts.size() - 1), parts.get(parts.size() - 1));
    }
    if (context instanceof ConstExprContext e)
      return constant(e.aexprConst());
    if (context instanceof ParamExprContext e) {
      final Token token = e.PARAM().getSymbol();
      try {
        return new Expression.ParameterRef(position(token), Integer.parseInt(token.getText().substring(1)));
      } catch (final NumberFormatException exception) {
        throw Diagnostics.syntaxError("parameter number too large", token);
      }
    }
    if (context instanceof ArrayExprContext e)
      return new Expression.ArrayConstructor(position(e.getStart()), e.exprList() == null ? List.of() : expressions(e.exprList()));
    if (context instanceof ExistsExprContext e)
      return new Expression.Exists(position(e.getStart()), query(e.selectStmt()));
    if (context instanceof ScalarSubqueryExprContext e)
      return new Expression.ScalarSubquery(position(e.getStart()), query(e.selectStmt()));
    if (context instanceof ParenExprContext e)
      return expression(e.aExpr());
    if (context instanceof CaseExprAltContext e)
      return caseExpression(e.caseExpr());
    if (context instanceof CastExprContext e)
      return new Expression.Cast(position(e.getStart()), expression(e.aExpr()), typeName(e.typename()));
    if (context instanceof FunctionCallExprContext e)
      return functionCall(e.funcApplication());
    if (context instanceof WindowCallExprContext e) {
      final Expression.FunctionCall call = (Expression.FunctionCall) functionCall(e.funcApplication());
      final OverClauseContext over = e.overClause();
      final List<Expression> partition = new ArrayList<>();
      for (final AExprContext item : over.partition)
        partition.add(expression(item));
      final List<SortItem> order = new ArrayList<>();
      if (over.sortClause() != null)
        for (final SortByContext item : over.sortClause().items)
          order.add(sortItem((SortKeyContext) item));
      return new Expression.FunctionCall(call.position(), call.name(), call.args(), call.distinct(), call.star(),
          new Expression.Window(position(over.getStart()), partition, order, over.rowsFrame() != null));
    }
    if (context instanceof VariadicSpecialExprContext e) {
      final SpecialFunctionKind kind = switch (e.fn.getType()) {
        case PostgreSQLParser.COALESCE -> SpecialFunctionKind.COALESCE;
        case PostgreSQLParser.GREATEST -> SpecialFunctionKind.GREATEST;
        default -> SpecialFunctionKind.LEAST;
      };
      return new Expression.SpecialFunction(position(e.getStart()), kind, expressions(e.exprList()));
    }
    if (context instanceof NullIfExprContext e)
      return new Expression.SpecialFunction(position(e.getStart()), SpecialFunctionKind.NULLIF,
          List.of(expression(e.l), expression(e.r)));
    if (context instanceof CurrentDateExprContext e)
      return new Expression.SpecialFunction(position(e.getStart()), SpecialFunctionKind.CURRENT_DATE, List.of());
    if (context instanceof TimeFunctionExprContext e) {
      final SpecialFunctionKind kind = switch (e.fn.getType()) {
        case PostgreSQLParser.CURRENT_TIME -> SpecialFunctionKind.CURRENT_TIME;
        case PostgreSQLParser.CURRENT_TIMESTAMP -> SpecialFunctionKind.CURRENT_TIMESTAMP;
        case PostgreSQLParser.LOCALTIME -> SpecialFunctionKind.LOCALTIME;
        default -> SpecialFunctionKind.LOCALTIMESTAMP;
      };
      final TerminalNode precision = e.getToken(PostgreSQLParser.ICONST, 0);
      return new Expression.SpecialFunction(position(e.getStart()), kind,
          precision == null ? List.of() : List.of(integer(precision.getSymbol(), false)));
    }
    if (context instanceof ExtractExprContext e)
      return catalogFunction(e.getStart(), "extract", List.of(extractField(e.extractArg()), expression(e.aExpr())));
    if (context instanceof PositionExprContext e)
      // position(substring IN string) is position(string, substring), as in gram.y
      return catalogFunction(e.getStart(), "position", List.of(bExpression(e.r), bExpression(e.l)));
    if (context instanceof SubstringFromExprContext e)
      return catalogFunction(e.getStart(), "substring", substringArguments(e.substrList()));
    if (context instanceof SubstringCallExprContext e) {
      final List<Expression> arguments = new ArrayList<>();
      if (e.funcArgList() != null)
        for (final FuncArgExprContext argument : e.funcArgList().items)
          arguments.add(expression(argument.aExpr()));
      return catalogFunction(e.getStart(), "substring", arguments);
    }
    if (context instanceof TrimExprContext e) {
      final String function = e.kind == null || e.kind.getType() == PostgreSQLParser.BOTH ? "btrim"
          : e.kind.getType() == PostgreSQLParser.LEADING ? "ltrim" : "rtrim";
      // trim(chars FROM string) is btrim(string, chars): the string list first, then the characters
      final List<Expression> arguments = new ArrayList<>(expressions(e.trimList().src));
      if (e.trimList().chars != null)
        arguments.add(expression(e.trimList().chars));
      return catalogFunction(e.getStart(), function, arguments);
    }
    throw new IllegalStateException("An expression the parser did not refuse has no tree: " + context.getText());
  }

  private Expression caseExpression(final CaseExprContext context) {
    final List<Expression.WhenClause> whens = new ArrayList<>();
    for (final WhenClauseContext when : context.whens)
      whens.add(new Expression.WhenClause(position(when.getStart()), expression(when.cond), expression(when.res)));
    return new Expression.Case(position(context.getStart()), context.operand == null ? null : expression(context.operand), whens,
        context.otherwise == null ? null : expression(context.otherwise));
  }

  private Expression functionCall(final FuncApplicationContext context) {
    final SourcePosition position = position(context.getStart());
    if (context instanceof FuncNoArgsContext noArguments)
      return new Expression.FunctionCall(position, functionName(noArguments.name), List.of(), false, false);
    if (context instanceof FuncStarContext star)
      return new Expression.FunctionCall(position, functionName(star.name), List.of(), false, true);

    final FuncWithArgsContext withArguments = (FuncWithArgsContext) context;
    final List<Expression> arguments = new ArrayList<>();
    for (final FuncArgExprContext argument : withArguments.args.items)
      arguments.add(expression(argument.aExpr()));
    final boolean distinct = withArguments.q != null && withArguments.q.getType() == PostgreSQLParser.DISTINCT;
    return new Expression.FunctionCall(position, functionName(withArguments.name), arguments, distinct, false);
  }

  private List<Name> functionName(final FuncNameContext context) {
    final List<Name> parts = new ArrayList<>();
    if (context instanceof SimpleFuncNameContext simple) {
      parts.add(name(simple.typeFunctionName()));
    } else {
      final QualifiedFuncNameContext qualified = (QualifiedFuncNameContext) context;
      parts.add(name(qualified.head));
      for (final ColLabelContext part : qualified.tail)
        parts.add(name(part));
    }
    return parts;
  }

  /** A call of the {@code pg_catalog} function that PostgreSQL's grammar rewrites a keyword function into. */
  private Expression catalogFunction(final Token start, final String function, final List<Expression> arguments) {
    final SourcePosition position = position(start);
    return new Expression.FunctionCall(position,
        List.of(new Name(position, "pg_catalog", false), new Name(position, function, false)), arguments, false, false);
  }

  private Expression extractField(final ExtractArgContext context) {
    final Token token = context.getStart();
    if (context.sconst() != null)
      return new Expression.StringLiteral(position(token), string(context.sconst()));
    return new Expression.StringLiteral(position(token), name(context).value());
  }

  private List<Expression> substringArguments(final SubstrListContext context) {
    if (context instanceof SubstrFromForContext e)
      return List.of(expression(e.a), expression(e.b), expression(e.c));
    if (context instanceof SubstrForFromContext e)
      return List.of(expression(e.a), expression(e.b), expression(e.c));
    if (context instanceof SubstrFromContext e)
      return List.of(expression(e.a), expression(e.b));
    if (context instanceof SubstrForContext e) {
      // substring(a FOR c) is substring(a, 1, c::int4), as in gram.y
      final SourcePosition position = position(e.getStart());
      return List.of(expression(e.a), new Expression.IntegerLiteral(position, BigInteger.ONE),
          new Expression.Cast(position, expression(e.c), new TypeName(position, List.of("int4"), List.of(), null, 0)));
    }
    throw new IllegalStateException("A substring the parser did not refuse has no tree: " + context.getText());
  }

  private List<Expression> expressions(final ExprListContext context) {
    final List<Expression> expressions = new ArrayList<>();
    for (final AExprContext item : context.items)
      expressions.add(expression(item));
    return expressions;
  }

  // ------------------------------------------------------------------------------------------------------ constants

  private Expression constant(final AexprConstContext context) {
    final Token start = context.getStart();
    if (context instanceof IntegerConstContext)
      return integer(start, false);
    if (context instanceof DecimalConstContext)
      return decimal(start, false);
    if (context instanceof StringConstContext e)
      return new Expression.StringLiteral(position(start), string(e.sconst()));
    if (context instanceof TrueConstContext)
      return new Expression.BooleanLiteral(position(start), true);
    if (context instanceof FalseConstContext)
      return new Expression.BooleanLiteral(position(start), false);
    if (context instanceof NullConstContext)
      return new Expression.NullLiteral(position(start));
    if (context instanceof GenericTypedConstContext e) {
      final List<String> type = new ArrayList<>();
      for (final Name part : functionName(e.funcName()))
        type.add(part.value());
      return new Expression.Cast(position(start), new Expression.StringLiteral(position(e.sconst().getStart()), string(e.sconst())),
          new TypeName(position(start), type, List.of(), null, 0));
    }
    if (context instanceof TypedConstContext e) {
      final ConstTypenameContext typename = e.constTypename();
      final TypeName type = typename.numericType() != null ? numericType(typename.numericType())
          : typename.characterType() != null ? characterType(typename.characterType()) : datetimeType(typename.datetimeType());
      return new Expression.Cast(position(start), new Expression.StringLiteral(position(e.sconst().getStart()), string(e.sconst())),
          type);
    }
    if (context instanceof IntervalConstContext e) {
      final String fields = e.intervalFields() == null ? null : intervalFields(e.intervalFields());
      return new Expression.Cast(position(start), new Expression.StringLiteral(position(e.sconst().getStart()), string(e.sconst())),
          new TypeName(position(start), List.of("interval"), List.of(), fields, 0));
    }
    if (context instanceof IntervalPrecisionConstContext e) {
      final TerminalNode precision = e.getToken(PostgreSQLParser.ICONST, 0);
      return new Expression.Cast(position(start), new Expression.StringLiteral(position(e.sconst().getStart()), string(e.sconst())),
          new TypeName(position(start), List.of("interval"), List.of(integer(precision.getSymbol(), false)), null, 0));
    }
    throw new IllegalStateException("A constant the parser did not refuse has no tree: " + context.getText());
  }

  private Expression integer(final Token token, final boolean negative) {
    final String text = token.getText().replace("_", "").toLowerCase(Locale.ROOT);
    BigInteger value;
    if (text.startsWith("0x"))
      value = new BigInteger(text.substring(2), 16);
    else if (text.startsWith("0o"))
      value = new BigInteger(text.substring(2), 8);
    else if (text.startsWith("0b"))
      value = new BigInteger(text.substring(2), 2);
    else
      value = new BigInteger(text);
    return new Expression.IntegerLiteral(position(token), negative ? value.negate() : value);
  }

  private Expression decimal(final Token token, final boolean negative) {
    final BigDecimal value = new BigDecimal(token.getText().replace("_", ""));
    return new Expression.DecimalLiteral(position(token), negative ? value.negate() : value);
  }

  /** The value of a string constant: standard, with backslash escapes ({@code E'...'}), or dollar quoted. */
  private String string(final SconstContext context) {
    final Token token = context.getStart();
    final String raw = token.getText();
    return switch (token.getType()) {
      case PostgreSQLParser.SCONST -> decodeQuoted(raw, 0, false, token);
      case PostgreSQLParser.ESTRING -> decodeQuoted(raw, 1, true, token);
      case PostgreSQLParser.DOLLAR_STRING -> {
        final int tagLength = raw.indexOf('$', 1) + 1;
        yield raw.substring(tagLength, raw.length() - tagLength);
      }
      default -> throw Diagnostics.notSupported("A Unicode escape string", token);
    };
  }

  /**
   * Reads one or more quoted pieces: {@code ''} is a quote, a backslash starts an escape in an {@code E} string, and pieces
   * separated by whitespace with a newline are joined. A Unicode escape that is the first half of a surrogate pair has to be
   * followed at once by the second half, as in the scanner of PostgreSQL.
   */
  private static String decodeQuoted(final String raw, final int from, final boolean escapes, final Token token) {
    final StringBuilder value = new StringBuilder();
    final int[] high = { -1 };
    int i = from;
    final int n = raw.length();
    while (i < n) {
      // raw.charAt(i) is the opening quote of a piece
      i++;
      while (i < n) {
        final char c = raw.charAt(i);
        final boolean unicodeAhead = escapes && c == '\\' && i + 1 < n && (raw.charAt(i + 1) == 'u' || raw.charAt(i + 1) == 'U');
        if (high[0] >= 0 && !unicodeAhead)
          throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
        if (c == '\'') {
          if (i + 1 < n && raw.charAt(i + 1) == '\'') {
            value.append('\'');
            i += 2;
          } else {
            i++;
            break;
          }
        } else if (escapes && c == '\\' && i + 1 < n) {
          i = escape(raw, i + 1, value, high, token);
        } else {
          value.append(c);
          i++;
        }
      }
      // between two pieces: whitespace and line comments, up to the next quote
      while (i < n && raw.charAt(i) != '\'') {
        if (raw.startsWith("--", i)) {
          while (i < n && raw.charAt(i) != '\n' && raw.charAt(i) != '\r')
            i++;
        } else {
          i++;
        }
      }
    }
    if (high[0] >= 0)
      throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
    return value.toString();
  }

  /** The escape after a backslash in an {@code E} string; returns the index after it. */
  private static int escape(final String raw, final int at, final StringBuilder value, final int[] high, final Token token) {
    final char c = raw.charAt(at);
    switch (c) {
    case 'b':
      value.append('\b');
      return at + 1;
    case 'f':
      value.append('\f');
      return at + 1;
    case 'n':
      value.append('\n');
      return at + 1;
    case 'r':
      value.append('\r');
      return at + 1;
    case 't':
      value.append('\t');
      return at + 1;
    case 'x': {
      // up to two hex digits; without one the escape is the letter itself
      int end = at + 1;
      while (end < raw.length() && end - (at + 1) < 2 && Character.digit(raw.charAt(end), 16) >= 0)
        end++;
      if (end == at + 1) {
        value.append('x');
        return end;
      }
      value.appendCodePoint(Integer.parseInt(raw.substring(at + 1, end), 16));
      return end;
    }
    case 'u':
      return unicode(raw, at + 1, 4, value, high, token);
    case 'U':
      return unicode(raw, at + 1, 8, value, high, token);
    default:
      if (c >= '0' && c <= '7') {
        // up to three octal digits
        int end = at;
        int octal = 0;
        while (end < raw.length() && end - at < 3 && raw.charAt(end) >= '0' && raw.charAt(end) <= '7')
          octal = octal * 8 + (raw.charAt(end++) - '0');
        value.appendCodePoint(octal);
        return end;
      }
      value.append(c);
      return at + 1;
    }
  }

  /** {@code \\uXXXX} and {@code \\UXXXXXXXX}: too few digits is an invalid escape sequence, an unpaired surrogate a syntax error. */
  private static int unicode(final String raw, final int from, final int digits, final StringBuilder value, final int[] high,
      final Token token) {
    final int end = from + digits;
    boolean hex = end <= raw.length();
    for (int i = from; hex && i < end; i++)
      hex = Character.digit(raw.charAt(i), 16) >= 0;
    if (!hex)
      throw new PostgreSQLInvalidEscapeException(
          "invalid Unicode escape: Unicode escapes must be \\uXXXX or \\UXXXXXXXX (" + Diagnostics.position(token) + ")");

    final long code = Long.parseLong(raw.substring(from, end), 16);
    if (high[0] >= 0) {
      if (code < 0xDC00 || code > 0xDFFF)
        throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
      value.appendCodePoint(Character.toCodePoint((char) high[0], (char) code));
      high[0] = -1;
    } else if (code >= 0xD800 && code <= 0xDBFF) {
      high[0] = (int) code;
    } else if (code >= 0xDC00 && code <= 0xDFFF) {
      throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
    } else if (code == 0 || code > 0x10FFFF) {
      throw Diagnostics.syntaxError("invalid Unicode escape value", token);
    } else {
      value.appendCodePoint((int) code);
    }
    return end;
  }

  // ------------------------------------------------------------------------------------------------------ types

  private TypeName typeName(final TypenameContext context) {
    final TypeName type = simpleType(context.simpleTypename());
    if (context.arrayBounds() == null)
      return type;
    final ArrayBoundsContext bounds = context.arrayBounds();
    final int dimensions = has(bounds, PostgreSQLParser.ARRAY) ? 1 : bounds.getTokens(PostgreSQLParser.LBRACKET).size();
    return new TypeName(type.position(), type.name(), type.modifiers(), type.intervalFields(), dimensions);
  }

  private TypeName simpleType(final SimpleTypenameContext context) {
    if (context instanceof GenericTypeNameContext e) {
      final GenericTypeContext generic = e.genericType();
      final List<String> parts = new ArrayList<>();
      parts.add(name(generic.first).value());
      for (final AttrNameContext part : generic.rest)
        parts.add(name(part).value());
      return new TypeName(position(context.getStart()), parts, modifiers(generic.typeModifiers()), null, 0);
    }
    if (context instanceof NumericTypeNameContext e)
      return numericType(e.numericType());
    if (context instanceof CharacterTypeNameContext e)
      return characterType(e.characterType());
    if (context instanceof DatetimeTypeNameContext e)
      return datetimeType(e.datetimeType());
    if (context instanceof IntervalTypeNameContext e) {
      final IntervalTypeContext interval = e.intervalType();
      final String fields = interval.intervalFields() == null ? null : intervalFields(interval.intervalFields());
      final TerminalNode precision = interval.getToken(PostgreSQLParser.ICONST, 0);
      return new TypeName(position(context.getStart()), List.of("interval"),
          precision == null ? List.of() : List.of(integer(precision.getSymbol(), false)), fields, 0);
    }
    throw new IllegalStateException("A type the parser did not refuse has no tree: " + context.getText());
  }

  private List<Expression> modifiers(final TypeModifiersContext context) {
    return context == null ? List.of() : expressions(context.exprList());
  }

  private TypeName numericType(final NumericTypeContext context) {
    final SourcePosition position = position(context.getStart());
    String name;
    List<Expression> modifiers = List.of();
    switch (context.getStart().getType()) {
    case PostgreSQLParser.INT, PostgreSQLParser.INTEGER -> name = "int4";
    case PostgreSQLParser.SMALLINT -> name = "int2";
    case PostgreSQLParser.BIGINT -> name = "int8";
    case PostgreSQLParser.REAL -> name = "float4";
    case PostgreSQLParser.BOOLEAN -> name = "bool";
    case PostgreSQLParser.DOUBLE -> name = "float8";
    case PostgreSQLParser.FLOAT -> {
      final TerminalNode precision = context.getToken(PostgreSQLParser.ICONST, 0);
      if (precision == null) {
        name = "float8";
      } else {
        final int bits = Integer.parseInt(precision.getText());
        if (bits < 1)
          throw Diagnostics.syntaxError("precision for type float must be at least 1 bit", precision.getSymbol());
        if (bits > 53)
          throw Diagnostics.syntaxError("precision for type float must be less than 54 bits", precision.getSymbol());
        name = bits <= 24 ? "float4" : "float8";
      }
    }
    default -> {
      name = "numeric";
      modifiers = modifiers(context.typeModifiers());
    }
    }
    return new TypeName(position, List.of(name), modifiers, null, 0);
  }

  private TypeName characterType(final CharacterTypeContext context) {
    final SourcePosition position = position(context.getStart());
    final TerminalNode length = context.getToken(PostgreSQLParser.ICONST, 0);
    final List<Expression> modifiers = length == null ? List.of() : List.of(integer(length.getSymbol(), false));
    if (context.getStart().getType() == PostgreSQLParser.VARCHAR || has(context, PostgreSQLParser.VARYING))
      return new TypeName(position, List.of("varchar"), modifiers, null, 0);
    // character without a length is character(1), as in gram.y
    return new TypeName(position, List.of("bpchar"),
        length == null ? List.of(new Expression.IntegerLiteral(position, BigInteger.ONE)) : modifiers, null, 0);
  }

  private TypeName datetimeType(final DatetimeTypeContext context) {
    final SourcePosition position = position(context.getStart());
    final boolean timestamp = context.getStart().getType() == PostgreSQLParser.TIMESTAMP;
    final boolean withTimeZone = has(context, PostgreSQLParser.WITH);
    final TerminalNode precision = context.getToken(PostgreSQLParser.ICONST, 0);
    return new TypeName(position, List.of((timestamp ? "timestamp" : "time") + (withTimeZone ? "tz" : "")),
        precision == null ? List.of() : List.of(integer(precision.getSymbol(), false)), null, 0);
  }

  /** {@code year to month}, {@code second(3)}: the words in lower case, parentheses without spaces. */
  private static String intervalFields(final IntervalFieldsContext context) {
    final List<Token> tokens = new ArrayList<>();
    tokens(context, tokens);
    final StringBuilder fields = new StringBuilder();
    for (final Token token : tokens) {
      final String text = token.getText().toLowerCase(Locale.ROOT);
      if (fields.length() > 0 && !"(".equals(text) && !")".equals(text) && fields.charAt(fields.length() - 1) != '(')
        fields.append(' ');
      fields.append(text);
    }
    return fields.toString();
  }

  private static void tokens(final ParseTree tree, final List<Token> tokens) {
    if (tree instanceof TerminalNode terminal) {
      tokens.add(terminal.getSymbol());
      return;
    }
    for (int i = 0; i < tree.getChildCount(); i++)
      tokens(tree.getChild(i), tokens);
  }

  // ------------------------------------------------------------------------------------------------------ names

  /** The identifier that a rule of the keyword classes matched: a single token. */
  private Name name(final ParserRuleContext context) {
    final Token token = context.getStart();
    final SourcePosition position = position(token);
    final String text = token.getText();
    switch (token.getType()) {
    case PostgreSQLParser.QUOTED_IDENT:
      if (text.length() == 2)
        throw Diagnostics.syntaxError("zero-length delimited identifier", token);
      return new Name(position, text.substring(1, text.length() - 1).replace("\"\"", "\""), true);
    case PostgreSQLParser.UIDENT:
      throw Diagnostics.notSupported("A Unicode escape identifier", token);
    default:
      return new Name(position, foldToLowerCase(text), false);
    }
  }

  /** PostgreSQL folds the ASCII letters of an unquoted identifier to lower case and leaves every other character as it is. */
  private static String foldToLowerCase(final String text) {
    final StringBuilder folded = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return folded.toString();
  }

  // ------------------------------------------------------------------------------------------------------ helpers

  private static boolean has(final ParserRuleContext context, final int tokenType) {
    return context.getToken(tokenType, 0) != null;
  }

  private static SourcePosition position(final Token token) {
    return Diagnostics.position(token);
  }
}
