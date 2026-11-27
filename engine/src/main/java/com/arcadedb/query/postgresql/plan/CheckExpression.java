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

import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedColumnException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The expression of a check constraint (fork issue #227), ready to run on a record. It reads the columns of one table, and its
 * answer depends on nothing else: not on the clock and not on the session, because every language checks it. What PostgreSQL
 * refuses in a check is refused with its SQLSTATE; what PostgreSQL accepts and depends on more than the record is
 * {@code 0A000}.
 */
public final class CheckExpression {
  private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", "array_agg", "string_agg",
      "bool_and", "bool_or", "every", "json_agg", "jsonb_agg", "json_object_agg", "jsonb_object_agg");

  /** Functions whose answer depends on when or where they run. */
  private static final Set<String> VOLATILE = Set.of("now", "transaction_timestamp", "statement_timestamp", "clock_timestamp",
      "random", "gen_random_uuid", "uuidv4", "uuidv7", "nextval", "currval", "lastval", "timeofday");

  /** The functions whose answer depends on their arguments alone, which is all a check may call. */
  private static final Set<String> IMMUTABLE = Set.of("lower", "upper", "initcap", "reverse", "length", "char_length",
      "character_length", "octet_length", "strpos", "position", "btrim", "ltrim", "rtrim", "substring", "substr", "replace",
      "left", "right", "repeat", "concat", "concat_ws", "abs", "round", "trunc", "floor", "ceil", "ceiling", "sqrt", "mod",
      "power", "pow", "extract", "date_part", "jsonb_typeof", "jsonb_array_length", "jsonb_exists", "jsonb_exists_any",
      "jsonb_exists_all", "jsonb_extract_path", "jsonb_extract_path_text", "jsonb_build_object", "jsonb_build_array",
      "to_jsonb", "jsonb_strip_nulls", "jsonb_set");

  private static final Set<String> SYSTEM_COLUMNS = Set.of("tableoid", "xmin", "cmin", "xmax", "cmax", "ctid");

  private final List<Column> columns;
  private final PgType[]     types;
  private final Evaluable    condition;

  private CheckExpression(final List<Column> columns, final PgType[] types, final Evaluable condition) {
    this.columns = columns;
    this.types = types;
    this.condition = condition;
  }

  /** The names of the columns that the expression reads, each once, in the order of their first use. */
  public List<String> columnNames() {
    return columns.stream().map(Column::name).toList();
  }

  /** Whether the expression is false for the record: true and unknown meet a check. */
  public boolean isViolatedBy(final Document record) {
    final Object[] row = new Object[types.length];
    for (int i = 0; i < row.length; i++) {
      Object value = columns.get(i).read(record);
      if (value instanceof Identifiable link)
        value = link.getIdentity().toString();
      row[i] = Casts.fromColumn(value, columns.get(i).type(), types[i]);
    }
    return Boolean.FALSE.equals(condition.eval(row));
  }

  /**
   * The constraint of the type {@code declaring} as the records of {@code type}, that type or a subtype of it, have to meet it.
   *
   * @throws RuntimeException when the text is no expression that the type can hold
   */
  public static CheckExpression of(final DocumentType declaring, final DocumentType type, final String text) {
    if (!(StatementParser.parse("SELECT " + text) instanceof SelectStatement select) || select.items().size() != 1
        || !(select.items().get(0) instanceof SelectItem.Projection projection))
      throw new IllegalArgumentException("not an expression: " + text);
    return compile(declaring.getName(), projection.expression(), declared(declaring, type));
  }

  /** The columns that a check of the type {@code declaring} reads in the records of {@code type}, by name. */
  static Function<String, Column> declared(final DocumentType declaring, final DocumentType type) {
    final TableDefinition definition = TableDefinition.of(declaring);
    return name -> {
      final Property property = type.getPolymorphicPropertyIfExists(name);
      if (property == null)
        return null;
      ColumnType columnType = ColumnType.of(property.getType(), property.getOfType());
      if (definition != null) {
        if (columnType == ColumnType.VARCHAR && definition.textColumns().contains(name))
          columnType = ColumnType.TEXT;
        else if (columnType == ColumnType.VARCHAR && definition.uuidColumns().contains(name))
          columnType = ColumnType.UUID;
        else if (columnType == ColumnType.TIMESTAMP && definition.timestamptzColumns().contains(name))
          columnType = ColumnType.TIMESTAMPTZ;
      }
      return Column.declaration(name, columnType);
    };
  }

  /**
   * Compiles the expression of a check of the table {@code table}.
   *
   * @param declared the column of a name, {@code null} when the table has none that a check can read
   */
  static CheckExpression compile(final String table, final Expression expression, final Function<String, Column> declared) {
    walk(expression, CheckExpression::requireAllowed);

    final Map<String, Integer> slots = new LinkedHashMap<>();
    final List<Column> columns = new ArrayList<>();
    final ExpressionCompiler.Scope scope = reference -> {
      final String name = reference.column().value();
      final List<Name> qualifier = reference.qualifier();
      if (!qualifier.isEmpty() && (!qualifier.get(qualifier.size() - 1).value().equals(table)
          || qualifier.size() > 1 && !RelationalCatalog.SCHEMA.equals(qualifier.get(0).value())))
        throw new PostgreSQLUndefinedTableException(qualifier.get(qualifier.size() - 1).value());
      Integer slot = slots.get(name);
      if (slot == null) {
        final Column column = declared.apply(name);
        if (column == null) {
          if (SYSTEM_COLUMNS.contains(name))
            throw new PostgreSQLEvaluationException("42P10",
                "system column \"" + name + "\" reference in check constraint is invalid");
          if ("rid".equals(name))
            throw refused("A CHECK that reads the record id", "check a column of the table", reference.position());
          throw new PostgreSQLUndefinedColumnException(name, table, List.of(), false);
        }
        slot = columns.size();
        slots.put(name, slot);
        columns.add(column);
      }
      final int at = slot;
      return new Evaluable.Node(PgType.of(columns.get(at).type()), row -> row[at]);
    };
    final ExpressionCompiler compiler = new ExpressionCompiler(scope, Parameters.NONE);
    final Evaluable condition = compiler.compileCondition(expression, "CHECK");
    walk(expression, node -> requireImmutable(node, compiler));
    final PgType[] types = columns.stream().map(column -> PgType.of(column.type())).toArray(PgType[]::new);
    return new CheckExpression(columns, types, condition);
  }

  /** What no check holds, whatever its types: PostgreSQL's refusals, and the clock. */
  private static void requireAllowed(final Expression node) {
    switch (node) {
    case Expression.ParameterRef parameter ->
        throw new PostgreSQLEvaluationException("42P02", "there is no parameter $" + parameter.index());
    case Expression.Exists exists -> throw subquery();
    case Expression.ScalarSubquery scalar -> throw subquery();
    case Expression.InSubquery in -> throw subquery();
    case Expression.FunctionCall call -> {
      if (call.over() != null)
        throw new PostgreSQLEvaluationException("42P20", "window functions are not allowed in check constraints");
      final String name = functionName(call);
      if (AGGREGATES.contains(name))
        throw new PostgreSQLEvaluationException("42803", "aggregate functions are not allowed in check constraints");
      if (VOLATILE.contains(name))
        throw changing("The function " + name + "() in a CHECK", call.position());
    }
    case Expression.SpecialFunction special -> {
      switch (special.kind()) {
      case COALESCE, NULLIF, GREATEST, LEAST -> {
      }
      default -> throw changing(special.kind() + " in a CHECK", special.position());
      }
    }
    default -> {
    }
    }
  }

  /**
   * Once the types are known: a function the list does not name is refused, and so is a {@code timestamptz} value anywhere but in
   * a comparison of two columns, because every other use of one reads the time zone of a session.
   */
  private static void requireImmutable(final Expression node, final ExpressionCompiler compiler) {
    if (node instanceof Expression.FunctionCall call && !IMMUTABLE.contains(functionName(call)))
      throw changing("The function " + functionName(call) + "() in a CHECK", call.position());
    final List<Expression> children = children(node);
    if (children.stream().noneMatch(child -> compiler.compile(child).type() == PgType.TIMESTAMPTZ))
      return;
    final boolean comparison = node instanceof Expression.IsNull || node instanceof Expression.Between
        || node instanceof Expression.InList || node instanceof Expression.BinaryOp binary && isComparison(binary);
    if (!comparison || !(node instanceof Expression.IsNull) && !children.stream()
        .allMatch(child -> child instanceof Expression.ColumnRef && compiler.compile(child).type() == PgType.TIMESTAMPTZ))
      throw changing("A timestamptz value in a CHECK, other than a comparison of two timestamptz columns or IS NULL,",
          node.position());
  }

  private static boolean isComparison(final Expression.BinaryOp binary) {
    return Set.of("=", "<>", "!=", "<", "<=", ">", ">=").contains(binary.operator().symbol());
  }

  private static String functionName(final Expression.FunctionCall call) {
    return call.name().get(call.name().size() - 1).value().toLowerCase(Locale.ROOT);
  }

  private static void walk(final Expression node, final Consumer<Expression> visit) {
    visit.accept(node);
    for (final Expression child : children(node))
      walk(child, visit);
  }

  /** The expressions directly inside an expression; a subquery has none here. */
  private static List<Expression> children(final Expression node) {
    final List<Expression> children = new ArrayList<>();
    switch (node) {
    case Expression.UnaryOp e -> children.add(e.operand());
    case Expression.BinaryOp e -> {
      children.add(e.left());
      children.add(e.right());
    }
    case Expression.IsNull e -> children.add(e.operand());
    case Expression.InList e -> {
      children.add(e.operand());
      children.addAll(e.values());
    }
    case Expression.InSubquery e -> children.add(e.operand());
    case Expression.Quantified e -> {
      children.add(e.operand());
      children.add(e.array());
    }
    case Expression.Between e -> {
      children.add(e.operand());
      children.add(e.low());
      children.add(e.high());
    }
    case Expression.Like e -> {
      children.add(e.operand());
      children.add(e.pattern());
      if (e.escape() != null)
        children.add(e.escape());
    }
    case Expression.Case e -> {
      if (e.operand() != null)
        children.add(e.operand());
      for (final Expression.WhenClause when : e.whens()) {
        children.add(when.condition());
        children.add(when.result());
      }
      if (e.otherwise() != null)
        children.add(e.otherwise());
    }
    case Expression.Cast e -> children.add(e.operand());
    case Expression.FunctionCall e -> {
      children.addAll(e.args());
      for (final SortItem item : e.orderBy())
        children.add(item.expression());
      if (e.filter() != null)
        children.add(e.filter());
      if (e.over() != null) {
        children.addAll(e.over().partitionBy());
        for (final SortItem item : e.over().orderBy())
          children.add(item.expression());
      }
    }
    case Expression.ArrayConstructor e -> children.addAll(e.elements());
    case Expression.SpecialFunction e -> children.addAll(e.args());
    case Expression.IntegerLiteral e -> {
    }
    case Expression.DecimalLiteral e -> {
    }
    case Expression.StringLiteral e -> {
    }
    case Expression.BooleanLiteral e -> {
    }
    case Expression.NullLiteral e -> {
    }
    case Expression.ParameterRef e -> {
    }
    case Expression.ColumnRef e -> {
    }
    case Expression.Exists e -> {
    }
    case Expression.ScalarSubquery e -> {
    }
    }
    return children;
  }

  private static PostgreSQLEvaluationException subquery() {
    return new PostgreSQLEvaluationException("0A000", "cannot use subquery in check constraint");
  }

  private static PostgreSQLFeatureNotSupportedException changing(final String what, final SourcePosition position) {
    return refused(what, "leave it out of the CHECK, and check it in the program that writes: a CHECK is held for every"
        + " language, so its answer can depend on the row alone", position);
  }

  private static PostgreSQLFeatureNotSupportedException refused(final String what, final String instead,
      final SourcePosition position) {
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language (" + position + ")",
        Character.toUpperCase(instead.charAt(0)) + instead.substring(1));
  }
}
