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
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.ast.TypeName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builders for syntax trees and helpers to type and run them, so the tests of the evaluator read like the tables of the
 * PostgreSQL manual they come from, without the parser.
 */
final class EvalSupport {
  static final SourcePosition P = SourcePosition.UNKNOWN;

  private EvalSupport() {
  }

  // ---- trees ----

  static Expression i(final long value) {
    return new Expression.IntegerLiteral(P, BigInteger.valueOf(value));
  }

  static Expression big(final String value) {
    return new Expression.IntegerLiteral(P, new BigInteger(value));
  }

  static Expression dec(final String value) {
    return new Expression.DecimalLiteral(P, new BigDecimal(value));
  }

  static Expression s(final String value) {
    return new Expression.StringLiteral(P, value);
  }

  static Expression bool(final boolean value) {
    return new Expression.BooleanLiteral(P, value);
  }

  static Expression nul() {
    return new Expression.NullLiteral(P);
  }

  /** {@code NULL} or true or false as a boolean-typed expression. */
  static Expression tri(final Boolean value) {
    return value == null ? cast(nul(), "bool") : bool(value);
  }

  static Expression param(final int index) {
    return new Expression.ParameterRef(P, index);
  }

  static Expression col(final String name) {
    return new Expression.ColumnRef(P, List.of(), new Name(P, name, false));
  }

  static Expression bin(final Expression.BinaryOperator operator, final Expression left, final Expression right) {
    return new Expression.BinaryOp(P, operator, left, right);
  }

  static Expression not(final Expression operand) {
    return new Expression.UnaryOp(P, Expression.UnaryOperator.NOT, operand);
  }

  static Expression minus(final Expression operand) {
    return new Expression.UnaryOp(P, Expression.UnaryOperator.MINUS, operand);
  }

  static Expression isNull(final Expression operand, final boolean negated) {
    return new Expression.IsNull(P, operand, negated);
  }

  static Expression in(final Expression operand, final boolean negated, final Expression... values) {
    return new Expression.InList(P, operand, List.of(values), negated);
  }

  static Expression between(final Expression operand, final Expression low, final Expression high, final boolean negated) {
    return new Expression.Between(P, operand, low, high, negated);
  }

  static Expression like(final Expression operand, final Expression pattern, final Expression escape, final boolean negated) {
    return new Expression.Like(P, operand, pattern, escape, negated);
  }

  static Expression cast(final Expression operand, final String type, final int... modifiers) {
    final List<Expression> mods = new ArrayList<>();
    for (final int modifier : modifiers)
      mods.add(i(modifier));
    return new Expression.Cast(P, operand, new TypeName(P, List.of(type), mods, null, 0));
  }

  static Expression call(final String name, final Expression... args) {
    return new Expression.FunctionCall(P, List.of(new Name(P, name, false)), List.of(args), false, false);
  }

  static Expression special(final Expression.SpecialFunctionKind kind, final Expression... args) {
    return new Expression.SpecialFunction(P, kind, List.of(args));
  }

  /** {@code CASE WHEN c1 THEN r1 WHEN c2 THEN r2 ... ELSE otherwise END}; the pairs alternate condition and result. */
  static Expression caseOf(final Expression operand, final Expression otherwise, final Expression... pairs) {
    final List<Expression.WhenClause> whens = new ArrayList<>();
    for (int k = 0; k < pairs.length; k += 2)
      whens.add(new Expression.WhenClause(P, pairs[k], pairs[k + 1]));
    return new Expression.Case(P, operand, whens, otherwise);
  }

  static Expression.BinaryOperator op(final String symbol) {
    for (final Expression.BinaryOperator operator : Expression.BinaryOperator.values())
      if (operator.symbol().equals(symbol))
        return operator;
    throw new IllegalArgumentException(symbol);
  }

  static Expression bin(final String symbol, final Expression left, final Expression right) {
    return bin(op(symbol), left, right);
  }

  // ---- compile and run ----

  /** A scope of named slots; a column that is not in the map is an error. */
  static ExpressionCompiler.Scope scope(final Map<String, Evaluable.Slot> columns) {
    return reference -> {
      final Evaluable.Slot slot = columns.get(reference.column().value());
      if (slot == null)
        throw new IllegalArgumentException("no column " + reference.column().value());
      return slot;
    };
  }

  static Evaluable compile(final Expression expression, final Object... parameters) {
    return new ExpressionCompiler(scope(Map.of()), Parameters.of(parameters)).compile(expression);
  }

  static Evaluable compileWith(final Map<String, Evaluable.Slot> columns, final Expression expression) {
    return new ExpressionCompiler(scope(columns), Parameters.NONE).compile(expression);
  }

  /** The value of an expression over constants. */
  static Object value(final Expression expression, final Object... parameters) {
    return compile(expression, parameters).eval(new Object[0]);
  }

  static PgType typeOf(final Expression expression, final Object... parameters) {
    return compile(expression, parameters).type();
  }

  /** The SQLSTATE of the error that typing or evaluating the expression raises, {@code null} when there is none. */
  static String sqlState(final Expression expression, final Object... parameters) {
    try {
      compile(expression, parameters).eval(new Object[0]);
      return null;
    } catch (final PostgreSQLEvaluationException e) {
      return e.getSqlState();
    }
  }

  static String sqlState(final Runnable action) {
    try {
      action.run();
      return null;
    } catch (final PostgreSQLEvaluationException e) {
      return e.getSqlState();
    }
  }
}
