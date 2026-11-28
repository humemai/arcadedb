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

import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Expression.BinaryOperator;
import com.arcadedb.query.postgresql.ast.Expression.UnaryOperator;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Comparisons of two rows, written as the comparisons of their members that PostgreSQL defines them to be, with the same
 * three-valued answer: {@code (a, b) = (x, y)} is {@code a = x AND b = y}, {@code <>} is the {@code OR} of the {@code <>},
 * {@code IN} with a list of rows is the {@code OR} of the {@code =}, and {@code (a, b) < (x, y)} is
 * {@code a <= x AND (a < x OR (a = x AND b < y))}. The first term of an ordering is a range on the first member, which an index
 * can serve.
 * <p>
 * An ordering and a list of several rows read a member more than once, where PostgreSQL reads it once. So a member with a
 * subquery or a function that answers differently on each call is refused there.
 */
final class RowComparisons {
  /** The functions of PostgreSQL that answer differently on each call, whether the language has them or not. */
  private static final Set<String> VOLATILE = Set.of("random", "gen_random_uuid", "uuid_generate_v4", "nextval", "setval",
      "clock_timestamp", "timeofday");

  private RowComparisons() {
  }

  static Expression compare(final SourcePosition at, final BinaryOperator operator, final List<Expression> left,
      final List<Expression> right, final Token token) {
    sameWidth(left, right, token);
    if (operator == BinaryOperator.EQUAL || operator == BinaryOperator.NOT_EQUAL)
      return join(at, operator == BinaryOperator.EQUAL ? BinaryOperator.AND : BinaryOperator.OR, pairs(at, operator, left, right));
    left.forEach(member -> refuseWhatIsReadTwice(member, token));
    right.forEach(member -> refuseWhatIsReadTwice(member, token));
    return ordering(at, operator, left, right, 0);
  }

  static Expression in(final SourcePosition at, final List<Expression> left, final List<List<Expression>> rows,
      final boolean negated, final Token token) {
    if (rows.size() > 1)
      left.forEach(member -> refuseWhatIsReadTwice(member, token));
    final List<Expression> terms = new ArrayList<>(rows.size());
    for (final List<Expression> row : rows) {
      sameWidth(left, row, token);
      terms.add(join(at, BinaryOperator.AND, pairs(at, BinaryOperator.EQUAL, left, row)));
    }
    final Expression any = join(at, BinaryOperator.OR, terms);
    return negated ? new Expression.UnaryOp(at, UnaryOperator.NOT, any) : any;
  }

  private static void sameWidth(final List<Expression> left, final List<Expression> right, final Token token) {
    if (left.size() != right.size())
      throw Diagnostics.syntaxError("unequal number of entries in row expressions", token);
  }

  private static List<Expression> pairs(final SourcePosition at, final BinaryOperator operator, final List<Expression> left,
      final List<Expression> right) {
    final List<Expression> out = new ArrayList<>(left.size());
    for (int i = 0; i < left.size(); i++)
      out.add(new Expression.BinaryOp(at, operator, left.get(i), right.get(i)));
    return out;
  }

  /** The terms joined with {@code AND} or {@code OR} as a balanced tree, so that a long list does not make a deep one. */
  private static Expression join(final SourcePosition at, final BinaryOperator operator, final List<Expression> terms) {
    if (terms.size() == 1)
      return terms.get(0);
    final int half = terms.size() / 2;
    return new Expression.BinaryOp(at, operator, join(at, operator, terms.subList(0, half)),
        join(at, operator, terms.subList(half, terms.size())));
  }

  private static Expression ordering(final SourcePosition at, final BinaryOperator operator, final List<Expression> left,
      final List<Expression> right, final int member) {
    final Expression a = left.get(member);
    final Expression x = right.get(member);
    if (member == left.size() - 1)
      return new Expression.BinaryOp(at, operator, a, x);
    final boolean less = operator == BinaryOperator.LESS || operator == BinaryOperator.LESS_OR_EQUAL;
    final Expression tie = new Expression.BinaryOp(at, BinaryOperator.AND,
        new Expression.BinaryOp(at, BinaryOperator.EQUAL, a, x), ordering(at, operator, left, right, member + 1));
    final Expression decided = new Expression.BinaryOp(at, BinaryOperator.OR,
        new Expression.BinaryOp(at, less ? BinaryOperator.LESS : BinaryOperator.GREATER, a, x), tie);
    return new Expression.BinaryOp(at, BinaryOperator.AND,
        new Expression.BinaryOp(at, less ? BinaryOperator.LESS_OR_EQUAL : BinaryOperator.GREATER_OR_EQUAL, a, x), decided);
  }

  private static void refuseWhatIsReadTwice(final Expression member, final Token token) {
    final boolean subquery = member instanceof Expression.ScalarSubquery || member instanceof Expression.Exists
        || member instanceof Expression.InSubquery;
    if (subquery || member instanceof Expression.FunctionCall call && isVolatile(call.name()))
      throw Diagnostics.notSupported("A row comparison with a subquery or a volatile function in a row", token);
    ExpressionCompiler.children(member).forEach(child -> refuseWhatIsReadTwice(child, token));
  }

  private static boolean isVolatile(final List<Name> function) {
    return VOLATILE.contains(function.get(function.size() - 1).value().toLowerCase(Locale.ROOT));
  }
}
