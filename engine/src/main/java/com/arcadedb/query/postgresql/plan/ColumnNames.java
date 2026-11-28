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

import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SetOperationStatement;
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.ast.WithStatement;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The names that a query mentions as columns, anywhere in it: in the select list, {@code WHERE}, {@code GROUP BY}, {@code HAVING},
 * {@code ORDER BY}, the conditions and {@code USING} lists of its joins, the arguments of functions and window definitions, and in
 * every subquery inside it (fork issue #90). A table that a join reads need only read the columns that have one of these names, in
 * lower case: a column that no expression of the statement names cannot be read by it. The qualifier of a name is ignored, so the
 * set is a superset of what each table needs.
 * <p>
 * The answer is {@code null} (read every column) when the query has a {@code *} anywhere, or has a statement or an expression of a
 * kind that this walk does not know: the walk answers for the shapes it lists, and nothing else.
 */
final class ColumnNames {
  private ColumnNames() {
  }

  static Set<String> of(final Statement statement) {
    final Set<String> names = new HashSet<>();
    return statement(statement, names) ? names : null;
  }

  private static boolean statement(final Statement statement, final Set<String> names) {
    return switch (statement) {
      case SelectStatement select -> select(select, names);
      case SetOperationStatement set -> statement(set.left(), names) && statement(set.right(), names) && sorts(set.orderBy(), names)
          && expression(set.limit(), names) && expression(set.offset(), names);
      case WithStatement with -> with(with, names);
      default -> false;
    };
  }

  private static boolean with(final WithStatement with, final Set<String> names) {
    for (final WithStatement.Cte cte : with.ctes())
      if (!statement(cte.query(), names))
        return false;
    return statement(with.body(), names);
  }

  private static boolean select(final SelectStatement select, final Set<String> names) {
    for (final SelectItem item : select.items())
      switch (item) {
      case SelectItem.Projection projection -> {
        if (!expression(projection.expression(), names))
          return false;
      }
      default -> {
        return false;
      }
      }
    for (final TableReference reference : select.from())
      if (!reference(reference, names))
        return false;
    for (final Expression group : select.groupBy())
      if (!expression(group, names))
        return false;
    return expression(select.where(), names) && expression(select.having(), names) && sorts(select.orderBy(), names)
        && expression(select.limit(), names) && expression(select.offset(), names);
  }

  private static boolean reference(final TableReference reference, final Set<String> names) {
    return switch (reference) {
      case TableReference.Table ignored -> true;
      case TableReference.Subquery subquery -> statement(subquery.query(), names);
      case TableReference.Function function -> expression(function.call(), names);
      case TableReference.Join join -> {
        for (final Name using : join.using())
          names.add(using.value().toLowerCase(Locale.ROOT));
        yield reference(join.left(), names) && reference(join.right(), names) && expression(join.condition(), names);
      }
    };
  }

  private static boolean sorts(final List<SortItem> items, final Set<String> names) {
    for (final SortItem item : items)
      if (!expression(item.expression(), names))
        return false;
    return true;
  }

  private static boolean all(final List<Expression> expressions, final Set<String> names) {
    for (final Expression expression : expressions)
      if (!expression(expression, names))
        return false;
    return true;
  }

  private static boolean expression(final Expression expression, final Set<String> names) {
    if (expression == null)
      return true;
    return switch (expression) {
      case Expression.IntegerLiteral ignored -> true;
      case Expression.DecimalLiteral ignored -> true;
      case Expression.StringLiteral ignored -> true;
      case Expression.BooleanLiteral ignored -> true;
      case Expression.NullLiteral ignored -> true;
      case Expression.ParameterRef ignored -> true;
      case Expression.ColumnRef reference -> names.add(reference.column().value().toLowerCase(Locale.ROOT)) || true;
      case Expression.UnaryOp op -> expression(op.operand(), names);
      case Expression.BinaryOp op -> expression(op.left(), names) && expression(op.right(), names);
      case Expression.IsNull test -> expression(test.operand(), names);
      case Expression.InList in -> expression(in.operand(), names) && all(in.values(), names);
      case Expression.Quantified quantified -> expression(quantified.operand(), names) && expression(quantified.array(), names);
      case Expression.Exists exists -> statement(exists.query(), names);
      case Expression.ScalarSubquery scalar -> statement(scalar.query(), names);
      case Expression.InSubquery in -> expression(in.operand(), names) && statement(in.query(), names);
      case Expression.Between between -> expression(between.operand(), names) && expression(between.low(), names)
          && expression(between.high(), names);
      case Expression.Like like -> expression(like.operand(), names) && expression(like.pattern(), names)
          && expression(like.escape(), names);
      case Expression.Case when -> {
        for (final Expression.WhenClause clause : when.whens())
          if (!expression(clause.condition(), names) || !expression(clause.result(), names))
            yield false;
        yield expression(when.operand(), names) && expression(when.otherwise(), names);
      }
      case Expression.Cast cast -> expression(cast.operand(), names);
      case Expression.FunctionCall call -> all(call.args(), names) && sorts(call.orderBy(), names)
          && expression(call.filter(), names) && (call.over() == null
          || all(call.over().partitionBy(), names) && sorts(call.over().orderBy(), names));
      case Expression.ArrayConstructor array -> all(array.elements(), names);
      case Expression.SpecialFunction special -> all(special.args(), names);
      default -> false;
    };
  }
}
