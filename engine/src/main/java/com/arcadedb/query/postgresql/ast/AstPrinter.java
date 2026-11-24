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
package com.arcadedb.query.postgresql.ast;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prints a syntax tree as text that the parser reads back into an equal tree (the round trip of issue #3). The text is not
 * meant to be pretty: every operation is in parentheses, so the grouping of the tree is the grouping of the text whatever the
 * precedence, a negative number is in parentheses, and a type name is quoted so that a normalised name such as {@code int4}
 * is read as that name.
 */
public final class AstPrinter {
  /** True while a fingerprint is printed: every number and text literal is then written as {@code ?}. */
  private static final ThreadLocal<Boolean> MASK_LITERALS = ThreadLocal.withInitial(() -> Boolean.FALSE);

  private AstPrinter() {
  }

  /**
   * The statement as text with every number and text literal replaced by {@code ?}, in the form {@link #print(Statement)} writes
   * it, so two statements that differ in whitespace, comments, the case of keywords or the values of their literals have one
   * fingerprint. It is the key of the once-per-statement warning, and it holds no value of the data. {@code NULL},
   * {@code TRUE}, {@code FALSE} and parameter references ({@code $1}) are kept.
   */
  public static String fingerprint(final Statement statement) {
    MASK_LITERALS.set(Boolean.TRUE);
    try {
      return print(statement);
    } finally {
      MASK_LITERALS.remove();
    }
  }

  public static String print(final Statement statement) {
    return switch (statement) {
      case EmptyStatement ignored -> "";
      case ExplainStatement explain -> "EXPLAIN " + print(explain.select());
      case ExplainWriteStatement explain -> "EXPLAIN " + print(explain.statement());
      case SelectStatement select -> print(select);
      case SetOperationStatement setOperation -> print(setOperation);
      case WithStatement with -> print(with);
      case InsertStatement insert -> print(insert);
      case UpdateStatement update -> print(update);
      case DeleteStatement delete -> print(delete);
      case TransactionStatement transaction -> transaction.kind().name();
    };
  }

  private static String print(final InsertStatement insert) {
    final StringBuilder text = new StringBuilder("INSERT INTO ").append(print(insert.table()));
    if (!insert.columns().isEmpty())
      text.append(" (").append(insert.columns().stream().map(AstPrinter::print).collect(Collectors.joining(", "))).append(')');
    switch (insert.source()) {
    case InsertStatement.DefaultValues ignored -> text.append(" DEFAULT VALUES");
    case InsertStatement.Values values -> text.append(" VALUES ").append(values.rows().stream()
        .map(row -> row.stream().map(AstPrinter::print).collect(Collectors.joining(", ", "(", ")")))
        .collect(Collectors.joining(", ")));
    case InsertStatement.Query query -> text.append(' ').append(print(query.select()));
    }
    if (insert.onConflict() != null) {
      final InsertStatement.OnConflict conflict = insert.onConflict();
      text.append(" ON CONFLICT");
      if (!conflict.target().isEmpty())
        text.append(" (").append(conflict.target().stream().map(AstPrinter::print).collect(Collectors.joining(", "))).append(')');
      if (conflict.doNothing())
        text.append(" DO NOTHING");
      else {
        text.append(" DO UPDATE SET ").append(conflict.assignments().stream()
            .map(assignment -> print(assignment.column()) + " = " + print(assignment.value())).collect(Collectors.joining(", ")));
        if (conflict.where() != null)
          text.append(" WHERE ").append(print(conflict.where()));
      }
    }
    return text.append(printReturning(insert.returning())).toString();
  }

  private static String print(final UpdateStatement update) {
    final StringBuilder text = new StringBuilder("UPDATE ").append(print(update.table())).append(" SET ");
    text.append(update.assignments().stream().map(assignment -> print(assignment.column()) + " = " + print(assignment.value()))
        .collect(Collectors.joining(", ")));
    if (!update.from().isEmpty())
      text.append(" FROM ").append(update.from().stream().map(AstPrinter::print).collect(Collectors.joining(", ")));
    if (update.where() != null)
      text.append(" WHERE ").append(print(update.where()));
    return text.append(printReturning(update.returning())).toString();
  }

  private static String print(final DeleteStatement delete) {
    final StringBuilder text = new StringBuilder("DELETE FROM ").append(print(delete.table()));
    if (delete.where() != null)
      text.append(" WHERE ").append(print(delete.where()));
    return text.append(printReturning(delete.returning())).toString();
  }

  private static String printReturning(final List<SelectItem> returning) {
    return returning.isEmpty() ? ""
        : " RETURNING " + returning.stream().map(AstPrinter::print).collect(Collectors.joining(", "));
  }

  private static String print(final WriteValue value) {
    return value.isDefault() ? "DEFAULT" : print(value.expression());
  }

  private static String print(final SelectStatement select) {
    final StringBuilder text = new StringBuilder("SELECT ");
    if (select.distinct())
      text.append("DISTINCT ");
    text.append(select.items().stream().map(AstPrinter::print).collect(Collectors.joining(", ")));
    if (!select.from().isEmpty())
      text.append(" FROM ").append(select.from().stream().map(AstPrinter::print).collect(Collectors.joining(", ")));
    if (select.where() != null)
      text.append(" WHERE ").append(print(select.where()));
    if (!select.groupBy().isEmpty())
      text.append(" GROUP BY ").append(printAll(select.groupBy()));
    if (select.having() != null)
      text.append(" HAVING ").append(print(select.having()));
    if (!select.orderBy().isEmpty())
      text.append(" ORDER BY ").append(select.orderBy().stream().map(AstPrinter::print).collect(Collectors.joining(", ")));
    if (select.limit() != null)
      text.append(" LIMIT ").append(print(select.limit()));
    if (select.offset() != null)
      text.append(" OFFSET ").append(print(select.offset()));
    return text.toString();
  }

  /** Every operand is in parentheses, so the grouping of the tree is the grouping of the text. */
  private static String print(final SetOperationStatement setOperation) {
    final StringBuilder text = new StringBuilder("(").append(print(setOperation.left())).append(") ")
        .append(setOperation.kind()).append(setOperation.all() ? " ALL" : "").append(" (").append(print(setOperation.right()))
        .append(')');
    if (!setOperation.orderBy().isEmpty())
      text.append(" ORDER BY ").append(setOperation.orderBy().stream().map(AstPrinter::print).collect(Collectors.joining(", ")));
    if (setOperation.limit() != null)
      text.append(" LIMIT ").append(print(setOperation.limit()));
    if (setOperation.offset() != null)
      text.append(" OFFSET ").append(print(setOperation.offset()));
    return text.toString();
  }

  private static String print(final WithStatement with) {
    final StringBuilder text = new StringBuilder("WITH ");
    for (int i = 0; i < with.ctes().size(); i++) {
      final WithStatement.Cte cte = with.ctes().get(i);
      if (i > 0)
        text.append(", ");
      text.append(print(cte.name()));
      if (!cte.columns().isEmpty())
        text.append(" (").append(cte.columns().stream().map(AstPrinter::print).collect(Collectors.joining(", "))).append(')');
      text.append(" AS (").append(print(cte.query())).append(')');
    }
    // the body is a query of its own: in parentheses it is read back whatever it holds
    return text.append(" (").append(print(with.body())).append(')').toString();
  }

  private static String print(final SelectItem item) {
    return switch (item) {
      case SelectItem.Projection projection ->
          print(projection.expression()) + (projection.alias() == null ? "" : " AS " + print(projection.alias()));
      case SelectItem.Star star -> star.qualifier().stream().map(AstPrinter::print).collect(Collectors.joining(".",
          "", star.qualifier().isEmpty() ? "*" : ".*"));
    };
  }

  private static String print(final TableReference reference) {
    return switch (reference) {
      case TableReference.Table table -> (table.only() ? "ONLY " : "")
          + table.name().stream().map(AstPrinter::print).collect(Collectors.joining("."))
          + (table.alias() == null ? "" : " AS " + print(table.alias()));
      case TableReference.Subquery subquery -> "(" + print(subquery.query()) + ")"
          + (subquery.alias() == null ? "" : " AS " + print(subquery.alias()))
          + (subquery.columns().isEmpty() ? "" : " (" + subquery.columns().stream().map(AstPrinter::print)
          .collect(Collectors.joining(", ")) + ")");
      case TableReference.Join join -> print(join.left()) + " " + join.type() + " JOIN "
          // a join on the right has to be in parentheses to keep its shape: joins group to the left
          + (join.right() instanceof TableReference.Join ? "(" + print(join.right()) + ")" : print(join.right()))
          + (join.using().isEmpty() ? " ON " + print(join.condition())
          : " USING (" + join.using().stream().map(AstPrinter::print).collect(Collectors.joining(", ")) + ")");
    };
  }

  private static String print(final SortItem item) {
    return print(item.expression()) + (item.direction() == SortItem.Direction.DEFAULT ? "" : " " + item.direction())
        + (item.nulls() == SortItem.Nulls.DEFAULT ? "" : " NULLS " + item.nulls());
  }

  private static String printWindow(final Expression.Window window) {
    final List<String> parts = new java.util.ArrayList<>();
    if (!window.partitionBy().isEmpty())
      parts.add("PARTITION BY " + printAll(window.partitionBy()));
    if (!window.orderBy().isEmpty())
      parts.add("ORDER BY " + window.orderBy().stream().map(AstPrinter::print).collect(Collectors.joining(", ")));
    return String.join(" ", parts);
  }

  private static String printAll(final List<Expression> expressions) {
    return expressions.stream().map(AstPrinter::print).collect(Collectors.joining(", "));
  }

  private static String print(final Name name) {
    return name.quoted() ? quote(name.value()) : name.value();
  }

  private static String quote(final String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private static String print(final TypeName type) {
    final StringBuilder text = new StringBuilder();
    if (type.intervalFields() != null) {
      // the field restriction only follows the keyword, not a quoted name
      text.append("INTERVAL ").append(type.intervalFields());
    } else {
      text.append(type.name().stream().map(AstPrinter::quote).collect(Collectors.joining(".")));
      if (!type.modifiers().isEmpty())
        text.append('(').append(printAll(type.modifiers())).append(')');
    }
    text.append("[]".repeat(type.arrayDimensions()));
    return text.toString();
  }

  public static String print(final Expression expression) {
    return switch (expression) {
      case Expression.IntegerLiteral literal -> MASK_LITERALS.get() ? "?"
          : literal.value().signum() < 0 ? "(" + literal.value() + ")" : literal.value().toString();
      case Expression.DecimalLiteral literal -> MASK_LITERALS.get() ? "?" : decimal(literal.value());
      case Expression.StringLiteral literal ->
          MASK_LITERALS.get() ? "?" : "'" + literal.value().replace("'", "''") + "'";
      case Expression.BooleanLiteral literal -> literal.value() ? "TRUE" : "FALSE";
      case Expression.NullLiteral ignored -> "NULL";
      case Expression.ParameterRef parameter -> "$" + parameter.index();
      case Expression.ColumnRef column -> {
        final String qualifier = column.qualifier().stream().map(AstPrinter::print).collect(Collectors.joining("."));
        yield (qualifier.isEmpty() ? "" : qualifier + ".") + print(column.column());
      }
      case Expression.UnaryOp unary -> "(" + unary.operator().symbol() + " " + print(unary.operand()) + ")";
      case Expression.BinaryOp binary ->
          "(" + print(binary.left()) + " " + binary.operator().symbol() + " " + print(binary.right()) + ")";
      case Expression.IsNull isNull -> "(" + print(isNull.operand()) + (isNull.negated() ? " IS NOT NULL)" : " IS NULL)");
      case Expression.InList in ->
          "(" + print(in.operand()) + (in.negated() ? " NOT IN (" : " IN (") + printAll(in.values()) + "))";
      case Expression.Exists exists -> "EXISTS (" + print(exists.query()) + ")";
      case Expression.ScalarSubquery subquery -> "(" + print(subquery.query()) + ")";
      case Expression.InSubquery in ->
          "(" + print(in.operand()) + (in.negated() ? " NOT IN (" : " IN (") + print(in.query()) + "))";
      case Expression.Between between -> "(" + print(between.operand()) + (between.negated() ? " NOT BETWEEN " : " BETWEEN ")
          + print(between.low()) + " AND " + print(between.high()) + ")";
      case Expression.Like like -> "(" + print(like.operand()) + (like.negated() ? " NOT LIKE " : " LIKE ") + print(like.pattern())
          + (like.escape() == null ? "" : " ESCAPE " + print(like.escape())) + ")";
      case Expression.Case caseExpression -> {
        final StringBuilder text = new StringBuilder("CASE");
        if (caseExpression.operand() != null)
          text.append(' ').append(print(caseExpression.operand()));
        for (final Expression.WhenClause when : caseExpression.whens())
          text.append(" WHEN ").append(print(when.condition())).append(" THEN ").append(print(when.result()));
        if (caseExpression.otherwise() != null)
          text.append(" ELSE ").append(print(caseExpression.otherwise()));
        yield text.append(" END").toString();
      }
      case Expression.Cast cast -> "CAST(" + print(cast.operand()) + " AS " + print(cast.type()) + ")";
      case Expression.FunctionCall call -> call.name().stream().map(AstPrinter::print).collect(Collectors.joining("."))
          + (call.star() ? "(*)" : "(" + (call.distinct() ? "DISTINCT " : "") + printAll(call.args()) + ")")
          + (call.over() == null ? "" : " OVER (" + printWindow(call.over()) + ")");
      case Expression.SpecialFunction function -> function.args().isEmpty() ? function.kind().name()
          : function.kind().name() + "(" + printAll(function.args()) + ")";
    };
  }

  /** A number with a decimal point or an exponent, so that it is read back as a decimal and not as an integer. */
  private static String decimal(final BigDecimal value) {
    final String text = value.toString();
    final String signed = text.contains(".") || text.contains("E") ? text : text + ".";
    return value.signum() < 0 ? "(" + signed + ")" : signed;
  }
}
