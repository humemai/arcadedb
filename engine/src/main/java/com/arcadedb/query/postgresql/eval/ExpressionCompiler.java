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
import com.arcadedb.query.postgresql.ast.TypeName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Types a syntax tree the way PostgreSQL's parser does and turns it into an {@link Evaluable}. This is the evaluator of the
 * language: it knows nothing of the database, a {@link Scope} says what a column reference is.
 *
 * <p>The typing rules are those of chapter 10 of the manual:
 * <ul>
 * <li>An integer constant is {@code int4} if it fits, else {@code int8}, else {@code numeric}; a decimal constant is
 * {@code numeric}; a string constant and a bare {@code NULL} are {@code unknown} until the other operand gives them a type, and
 * a string constant that meets a type is read by the input function of that type at once, so {@code age = 'x'} is
 * {@code 22P02} before any row is read. An {@code unknown} that nothing resolves is {@code text}.</li>
 * <li>An operator needs both operands in one type: two numbers go to the type of {@link Numbers#common}, two strings to
 * {@code text}, a date and a timestamp to {@code timestamp}. Anything else is {@code 42883}.</li>
 * <li>{@code AND}, {@code OR}, {@code NOT} and the {@code WHERE} condition need {@code boolean}: {@code 42804} otherwise.</li>
 * <li>{@code CASE}, {@code COALESCE}, {@code GREATEST} and {@code LEAST} give their branches one type: the widest numeric type,
 * {@code text} for strings, {@code timestamp} for dates and timestamps ({@code 42804} when they cannot be matched).</li>
 * </ul>
 * A function or a type outside the subset is {@code 0A000} or {@code 42883}, never a silent different answer.
 */
public final class ExpressionCompiler {

  /** What the compiler asks its caller: what a column is, and an optional hook that replaces the typing of some nodes. */
  public interface Scope {
    /**
     * The expression that reads a column.
     *
     * @throws RuntimeException the exception of an unknown or ambiguous column, which has its own SQLSTATE
     */
    Evaluable column(Expression.ColumnRef reference);

    /**
     * Called before every node is typed: a non-null answer replaces the node. A grouping query uses it to read a grouped
     * expression and an aggregate from the group row.
     */
    default Evaluable intercept(final Expression expression, final ExpressionCompiler compiler) {
      return null;
    }
  }

  private record Unified(PgType type, Evaluable left, Evaluable right) {
  }

  private final Scope      scope;
  private final Parameters parameters;

  public ExpressionCompiler(final Scope scope, final Parameters parameters) {
    this.scope = scope;
    this.parameters = parameters;
  }

  // ---- entry points ----

  public Evaluable compile(final Expression expression) {
    final Evaluable replaced = scope.intercept(expression, this);
    if (replaced != null)
      return replaced;
    return switch (expression) {
      case Expression.IntegerLiteral e -> integerLiteral(e);
      case Expression.DecimalLiteral e -> new Evaluable.Constant(PgType.NUMERIC, Numbers.normalizeScale(e.value()));
      case Expression.StringLiteral e -> new Evaluable.Constant(PgType.UNKNOWN, e.value());
      case Expression.BooleanLiteral e -> new Evaluable.Constant(PgType.BOOL, e.value());
      case Expression.NullLiteral e -> new Evaluable.Constant(PgType.UNKNOWN, null);
      case Expression.ParameterRef e -> parameter(e);
      case Expression.ColumnRef e -> scope.column(e);
      case Expression.UnaryOp e -> unary(e);
      case Expression.BinaryOp e -> binary(e);
      case Expression.IsNull e -> isNull(e);
      case Expression.InList e -> inList(e);
      case Expression.Between e -> between(e);
      case Expression.Like e -> like(e);
      case Expression.Case e -> caseExpression(e);
      case Expression.Cast e -> cast(e);
      case Expression.FunctionCall e -> function(e);
      case Expression.SpecialFunction e -> special(e);
    };
  }

  /**
   * A condition of {@code WHERE}, {@code HAVING} or {@code ON}: it has to be {@code boolean}.
   *
   * @param clause the clause for the message
   */
  public Evaluable compileCondition(final Expression expression, final String clause) {
    final Evaluable compiled = compile(expression);
    final Evaluable condition = implicit(compiled, PgType.BOOL);
    if (condition == null)
      throw PostgreSQLEvaluationException.mismatch(
          "argument of " + clause + " must be type boolean, not type " + compiled.type().displayName());
    return condition;
  }

  /** The expression in the given type if an implicit cast leads there, else {@code null}. */
  public Evaluable coerce(final Evaluable expression, final PgType target) {
    return implicit(expression, target);
  }

  /** What an output column of type {@code unknown} becomes: {@code text}, as PostgreSQL resolves it. */
  public Evaluable resolveOutput(final Evaluable expression) {
    return expression.type() == PgType.UNKNOWN ? implicit(expression, PgType.TEXT) : expression;
  }

  public static boolean isAggregate(final Expression.FunctionCall call) {
    if (call.name().size() > 2 || (call.name().size() == 2 && !"pg_catalog".equals(call.name().get(0).value())))
      return false;
    return switch (call.name().get(call.name().size() - 1).value()) {
      case "count", "sum", "avg", "min", "max" -> true;
      default -> false;
    };
  }

  /** Whether the expression has an aggregate function call anywhere in it. */
  public static boolean containsAggregate(final Expression expression) {
    if (expression instanceof Expression.FunctionCall call && isAggregate(call))
      return true;
    for (final Expression child : children(expression))
      if (containsAggregate(child))
        return true;
    return false;
  }

  /** The direct sub-expressions of an expression. */
  public static List<Expression> children(final Expression expression) {
    final List<Expression> out = new ArrayList<>();
    switch (expression) {
    case Expression.UnaryOp e -> out.add(e.operand());
    case Expression.BinaryOp e -> {
      out.add(e.left());
      out.add(e.right());
    }
    case Expression.IsNull e -> out.add(e.operand());
    case Expression.InList e -> {
      out.add(e.operand());
      out.addAll(e.values());
    }
    case Expression.Between e -> {
      out.add(e.operand());
      out.add(e.low());
      out.add(e.high());
    }
    case Expression.Like e -> {
      out.add(e.operand());
      out.add(e.pattern());
      if (e.escape() != null)
        out.add(e.escape());
    }
    case Expression.Case e -> {
      if (e.operand() != null)
        out.add(e.operand());
      for (final Expression.WhenClause when : e.whens()) {
        out.add(when.condition());
        out.add(when.result());
      }
      if (e.otherwise() != null)
        out.add(e.otherwise());
    }
    case Expression.Cast e -> out.add(e.operand());
    case Expression.FunctionCall e -> out.addAll(e.args());
    case Expression.SpecialFunction e -> out.addAll(e.args());
    default -> {
    }
    }
    return out;
  }

  // ---- constants and parameters ----

  private Evaluable integerLiteral(final Expression.IntegerLiteral literal) {
    final BigInteger value = literal.value();
    if (value.bitLength() <= 31)
      return new Evaluable.Constant(PgType.INT4, value.intValue());
    if (value.bitLength() <= 63)
      return new Evaluable.Constant(PgType.INT8, value.longValue());
    return new Evaluable.Constant(PgType.NUMERIC, new BigDecimal(value));
  }

  private Evaluable parameter(final Expression.ParameterRef reference) {
    final Object value = parameters.get(reference.index());
    final PgType type = PgType.ofJavaValue(value);
    if (type == null)
      throw PostgreSQLEvaluationException.unsupported(
          "The value of parameter $" + reference.index() + " has the unsupported class " + value.getClass().getName());
    return new Evaluable.Constant(type, Casts.fromEngine(value, type));
  }

  // ---- implicit casts ----

  private static int rank(final PgType type) {
    return switch (type) {
      case INT2 -> 1;
      case INT4 -> 2;
      case INT8 -> 3;
      case NUMERIC -> 4;
      case FLOAT4 -> 5;
      case FLOAT8 -> 6;
      default -> 0;
    };
  }

  /** The expression converted to {@code target} by an implicit cast, or {@code null} when there is none. */
  private static Evaluable implicit(final Evaluable expression, final PgType target) {
    final PgType from = expression.type();
    if (from == target)
      return expression;
    if (from == PgType.UNKNOWN) {
      if (!(expression instanceof Evaluable.Constant constant))
        return null;
      if (constant.value() == null)
        return new Evaluable.Constant(target, null);
      return new Evaluable.Constant(target, Casts.parse((String) constant.value(), target));
    }
    if (from.isString() && target.isString())
      return expression;
    if (from.isNumber() && target.isNumber() && rank(from) < rank(target))
      return new Evaluable.Node(target, row -> Numbers.widen(expression.eval(row), target), expression);
    if (from == PgType.DATE && target == PgType.TIMESTAMP)
      return new Evaluable.Node(target, row -> {
        final Object value = expression.eval(row);
        return value == null ? null : ((LocalDate) value).atStartOfDay();
      }, expression);
    return null;
  }

  private static Evaluable require(final Evaluable expression, final PgType target, final String function,
      final Evaluable... arguments) {
    final Evaluable converted = implicit(expression, target);
    if (converted == null)
      throw PostgreSQLEvaluationException.noFunction(function, types(arguments));
    return converted;
  }

  private static PgType[] types(final Evaluable... arguments) {
    final PgType[] types = new PgType[arguments.length];
    for (int i = 0; i < arguments.length; i++)
      types[i] = arguments[i].type();
    return types;
  }

  /** The type that {@code select_common_type} gives the branches of a {@code CASE} and the like. */
  private static PgType commonType(final List<Evaluable> items, final String what) {
    PgType result = null;
    for (final Evaluable item : items) {
      final PgType type = item.type();
      if (type == PgType.UNKNOWN)
        continue;
      if (result == null || result == type)
        result = type;
      else if (result.isNumber() && type.isNumber())
        result = rank(type) > rank(result) ? type : result;
      else if (result.isString() && type.isString())
        result = PgType.TEXT;
      else if (result.isDateTime() && type.isDateTime())
        result = PgType.TIMESTAMP;
      else
        throw PostgreSQLEvaluationException.mismatch(
            what + " types " + result.displayName() + " and " + type.displayName() + " cannot be matched");
    }
    return result == null ? PgType.TEXT : result;
  }

  private List<Evaluable> coerceAll(final List<Evaluable> items, final PgType type, final String what) {
    final List<Evaluable> out = new ArrayList<>(items.size());
    for (final Evaluable item : items) {
      final Evaluable converted = implicit(item, type);
      if (converted == null)
        throw PostgreSQLEvaluationException.mismatch(
            what + " types " + type.displayName() + " and " + item.type().displayName() + " cannot be matched");
      out.add(converted);
    }
    return out;
  }

  // ---- operators ----

  private Evaluable unary(final Expression.UnaryOp e) {
    final Evaluable operand = compile(e.operand());
    switch (e.operator()) {
    case NOT: {
      final Evaluable condition = booleanOperand(operand, "NOT");
      return new Evaluable.Node(PgType.BOOL, row -> Logic.not((Boolean) condition.eval(row)), condition);
    }
    case MINUS: {
      final PgType type = operand.type();
      if (!type.isNumber())
        throw PostgreSQLEvaluationException.noOperator("-", type);
      return new Evaluable.Node(type, row -> {
        final Object value = operand.eval(row);
        return value == null ? null : Numbers.negate(type, value);
      }, operand);
    }
    default: {
      if (!operand.type().isNumber())
        throw PostgreSQLEvaluationException.noOperator("+", operand.type());
      return operand;
    }
    }
  }

  private static Evaluable booleanOperand(final Evaluable operand, final String operator) {
    final Evaluable converted = implicit(operand, PgType.BOOL);
    if (converted == null)
      throw PostgreSQLEvaluationException.mismatch(
          "argument of " + operator + " must be type boolean, not type " + operand.type().displayName());
    return converted;
  }

  private Evaluable binary(final Expression.BinaryOp e) {
    final Expression.BinaryOperator operator = e.operator();
    switch (operator) {
    case AND:
    case OR: {
      final Evaluable left = booleanOperand(compile(e.left()), operator.symbol());
      final Evaluable right = booleanOperand(compile(e.right()), operator.symbol());
      final boolean and = operator == Expression.BinaryOperator.AND;
      return new Evaluable.Node(PgType.BOOL, row -> {
        final Boolean a = (Boolean) left.eval(row);
        // a false AND and a true OR need no right operand
        if (and ? Boolean.FALSE.equals(a) : Boolean.TRUE.equals(a))
          return a;
        final Boolean b = (Boolean) right.eval(row);
        return and ? Logic.and(a, b) : Logic.or(a, b);
      }, left, right);
    }
    case EQUAL:
    case NOT_EQUAL:
    case LESS:
    case LESS_OR_EQUAL:
    case GREATER:
    case GREATER_OR_EQUAL:
      return comparison(operator, compile(e.left()), compile(e.right()));
    case CONCAT:
      return concat(compile(e.left()), compile(e.right()));
    case ADD:
    case SUBTRACT:
    case MULTIPLY:
    case DIVIDE:
    case MODULO:
    case POWER:
      return arithmetic(operator, compile(e.left()), compile(e.right()));
    default:
      throw PostgreSQLEvaluationException.unsupported(
          "The operator " + operator.symbol() + " is not supported by the postgresql language yet");
    }
  }

  private Unified unify(final String operator, final Evaluable left, final Evaluable right) {
    final PgType a = left.type();
    final PgType b = right.type();
    final PgType common;
    if (a == PgType.UNKNOWN && b == PgType.UNKNOWN)
      common = PgType.TEXT;
    else if (a == PgType.UNKNOWN)
      common = textual(b);
    else if (b == PgType.UNKNOWN || a == b)
      common = textual(a);
    else if (a.isNumber() && b.isNumber())
      common = Numbers.common(a, b);
    else if (a.isString() && b.isString())
      common = PgType.TEXT;
    else if (a.isDateTime() && b.isDateTime())
      common = PgType.TIMESTAMP;
    else
      throw PostgreSQLEvaluationException.noOperator(operator, a, b);
    if (!Comparison.isComparable(common))
      throw PostgreSQLEvaluationException.unsupported(
          "The operator " + operator + " is not supported for type " + common.displayName());
    return new Unified(common, implicit(left, common), implicit(right, common));
  }

  private static PgType textual(final PgType type) {
    return type == PgType.VARCHAR ? PgType.TEXT : type;
  }

  /** The comparison of two expressions, {@code operator} one of the six comparison operators. */
  public Evaluable comparison(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    final Unified unified = unify(operator.symbol(), left, right);
    final PgType type = unified.type();
    final Evaluable l = unified.left();
    final Evaluable r = unified.right();
    return new Evaluable.Node(PgType.BOOL, row -> {
      final Object x = l.eval(row);
      if (x == null)
        return null;
      final Object y = r.eval(row);
      if (y == null)
        return null;
      final int order = Comparison.compare(type, x, y);
      return switch (operator) {
        case EQUAL -> order == 0;
        case NOT_EQUAL -> order != 0;
        case LESS -> order < 0;
        case LESS_OR_EQUAL -> order <= 0;
        case GREATER -> order > 0;
        default -> order >= 0;
      };
    }, l, r);
  }

  private Evaluable concat(final Evaluable left, final Evaluable right) {
    final PgType a = left.type();
    final PgType b = right.type();
    final boolean textLike = a.isString() || a == PgType.UNKNOWN || b.isString() || b == PgType.UNKNOWN;
    if (!textLike)
      throw PostgreSQLEvaluationException.noOperator("||", a, b);
    final Evaluable l = asText(left);
    final Evaluable r = asText(right);
    return new Evaluable.Node(PgType.TEXT, row -> {
      final Object x = l.eval(row);
      if (x == null)
        return null;
      final Object y = r.eval(row);
      return y == null ? null : (String) x + (String) y;
    }, l, r);
  }

  /** A value as text for {@code ||} and {@code concat}: the output function of its type. */
  private static Evaluable asText(final Evaluable expression) {
    final PgType type = expression.type();
    if (type.isString() || type == PgType.UNKNOWN)
      return implicit(expression, PgType.TEXT);
    if (type.isOpaque())
      throw PostgreSQLEvaluationException.unsupported("The text form of a value of type " + type.displayName() + " is not supported");
    return new Evaluable.Node(PgType.TEXT, row -> {
      final Object value = expression.eval(row);
      return value == null ? null : Casts.toText(value, type);
    }, expression);
  }

  private Evaluable arithmetic(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    final String symbol = operator.symbol();
    if (left.type() == PgType.UNKNOWN && right.type() == PgType.UNKNOWN)
      throw new PostgreSQLEvaluationException("42725", "operator is not unique: unknown " + symbol + " unknown");
    Evaluable l = left;
    Evaluable r = right;
    // a string constant takes the type of a number it meets
    if (left.type() == PgType.UNKNOWN && right.type().isNumber())
      l = implicit(left, right.type());
    else if (right.type() == PgType.UNKNOWN && left.type().isNumber())
      r = implicit(right, left.type());
    final PgType a = l.type();
    final PgType b = r.type();
    if (a.isNumber() && b.isNumber())
      return numericArithmetic(operator, l, r);

    final boolean plus = operator == Expression.BinaryOperator.ADD;
    final boolean minus = operator == Expression.BinaryOperator.SUBTRACT;
    final Evaluable x = l;
    final Evaluable y = r;
    if ((plus || minus) && a == PgType.DATE && (b == PgType.INT2 || b == PgType.INT4))
      return new Evaluable.Node(PgType.DATE, row -> {
        final Object date = x.eval(row);
        final Object days = y.eval(row);
        return date == null || days == null ? null : shift((LocalDate) date, minus ? -((Number) days).longValue()
            : ((Number) days).longValue());
      }, x, y);
    if (plus && (a == PgType.INT2 || a == PgType.INT4) && b == PgType.DATE)
      return new Evaluable.Node(PgType.DATE, row -> {
        final Object days = x.eval(row);
        final Object date = y.eval(row);
        return date == null || days == null ? null : shift((LocalDate) date, ((Number) days).longValue());
      }, x, y);
    if (minus && a == PgType.DATE && b == PgType.DATE)
      return new Evaluable.Node(PgType.INT4, row -> {
        final Object first = x.eval(row);
        final Object second = y.eval(row);
        return first == null || second == null ? null
            : (Object) (int) ChronoUnit.DAYS.between((LocalDate) second, (LocalDate) first);
      }, x, y);
    if (a.isDateTime() || b.isDateTime())
      throw PostgreSQLEvaluationException.unsupported(
          "The operator " + symbol + " on " + a.displayName() + " and " + b.displayName() + " is not supported (no interval type)");
    throw PostgreSQLEvaluationException.noOperator(symbol, a, b);
  }

  private static LocalDate shift(final LocalDate date, final long days) {
    try {
      return date.plusDays(days);
    } catch (final DateTimeException e) {
      throw new PostgreSQLEvaluationException("22008", "date out of range");
    }
  }

  private Evaluable numericArithmetic(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    PgType common = Numbers.common(left.type(), right.type());
    if (operator == Expression.BinaryOperator.POWER && common != PgType.NUMERIC)
      common = PgType.FLOAT8;
    if (operator == Expression.BinaryOperator.MODULO && common.isFloat())
      throw PostgreSQLEvaluationException.noOperator("%", left.type(), right.type());
    final PgType type = common;
    final Evaluable x = implicit(left, type);
    final Evaluable y = implicit(right, type);
    return new Evaluable.Node(type, row -> {
      final Object p = x.eval(row);
      if (p == null)
        return null;
      final Object q = y.eval(row);
      if (q == null)
        return null;
      return switch (operator) {
        case ADD -> Numbers.add(type, p, q);
        case SUBTRACT -> Numbers.subtract(type, p, q);
        case MULTIPLY -> Numbers.multiply(type, p, q);
        case DIVIDE -> Numbers.divide(type, p, q);
        case MODULO -> Numbers.modulo(type, p, q);
        default -> Numbers.power(type, p, q);
      };
    }, x, y);
  }

  // ---- predicates ----

  private Evaluable isNull(final Expression.IsNull e) {
    final Evaluable operand = compile(e.operand());
    final boolean negated = e.negated();
    return new Evaluable.Node(PgType.BOOL, row -> (operand.eval(row) == null) != negated, operand);
  }

  private Evaluable orChain(final List<Evaluable> comparisons) {
    Evaluable result = comparisons.get(0);
    for (int i = 1; i < comparisons.size(); i++) {
      final Evaluable a = result;
      final Evaluable b = comparisons.get(i);
      result = new Evaluable.Node(PgType.BOOL, row -> {
        final Boolean x = (Boolean) a.eval(row);
        if (Boolean.TRUE.equals(x))
          return x;
        return Logic.or(x, (Boolean) b.eval(row));
      }, a, b);
    }
    return result;
  }

  private static Evaluable negate(final Evaluable condition) {
    return new Evaluable.Node(PgType.BOOL, row -> Logic.not((Boolean) condition.eval(row)), condition);
  }

  /** {@code x IN (a, b)} is {@code x = a OR x = b}, with the three-valued logic of those operators. */
  private Evaluable inList(final Expression.InList e) {
    final Evaluable operand = compile(e.operand());
    final List<Evaluable> comparisons = new ArrayList<>();
    for (final Expression value : e.values())
      comparisons.add(comparison(Expression.BinaryOperator.EQUAL, operand, compile(value)));
    final Evaluable any = orChain(comparisons);
    return e.negated() ? negate(any) : any;
  }

  /** {@code x BETWEEN a AND b} is {@code x >= a AND x <= b}. */
  private Evaluable between(final Expression.Between e) {
    final Evaluable operand = compile(e.operand());
    final Evaluable low = comparison(Expression.BinaryOperator.GREATER_OR_EQUAL, operand, compile(e.low()));
    final Evaluable high = comparison(Expression.BinaryOperator.LESS_OR_EQUAL, operand, compile(e.high()));
    final Evaluable inside = new Evaluable.Node(PgType.BOOL,
        row -> Logic.and((Boolean) low.eval(row), (Boolean) high.eval(row)), low, high);
    return e.negated() ? negate(inside) : inside;
  }

  private Evaluable like(final Expression.Like e) {
    final Evaluable operand = likeOperand(compile(e.operand()));
    final Evaluable pattern = likeOperand(compile(e.pattern()));
    final Evaluable escape = e.escape() == null ? null : likeOperand(compile(e.escape()));
    final boolean negated = e.negated();
    final Evaluable[] inputs = escape == null ? new Evaluable[] { operand, pattern } : new Evaluable[] { operand, pattern, escape };
    return new Evaluable.Node(PgType.BOOL, row -> {
      final Object text = operand.eval(row);
      if (text == null)
        return null;
      final Object regex = pattern.eval(row);
      if (regex == null)
        return null;
      String escapeText = null;
      if (escape != null) {
        final Object value = escape.eval(row);
        if (value == null)
          return null;
        escapeText = (String) value;
      }
      return Text.like((String) text, (String) regex, escapeText, false) != negated;
    }, inputs);
  }

  private static Evaluable likeOperand(final Evaluable expression) {
    final Evaluable text = implicit(expression, PgType.TEXT);
    if (text == null)
      throw PostgreSQLEvaluationException.noOperator("~~", expression.type(), PgType.TEXT);
    return text;
  }

  // ---- CASE ----

  private Evaluable caseExpression(final Expression.Case e) {
    final Evaluable operand = e.operand() == null ? null : compile(e.operand());
    final List<Evaluable> conditions = new ArrayList<>();
    final List<Evaluable> results = new ArrayList<>();
    for (final Expression.WhenClause when : e.whens()) {
      final Evaluable condition = compile(when.condition());
      if (operand == null)
        conditions.add(booleanOperandOf(condition, "CASE/WHEN"));
      else
        conditions.add(comparison(Expression.BinaryOperator.EQUAL, operand, condition));
      results.add(compile(when.result()));
    }
    results.add(e.otherwise() == null ? new Evaluable.Constant(PgType.UNKNOWN, null) : compile(e.otherwise()));
    final PgType type = commonType(results, "CASE");
    final List<Evaluable> coerced = coerceAll(results, type, "CASE");
    final Evaluable[] whens = conditions.toArray(new Evaluable[0]);
    final Evaluable[] thens = coerced.toArray(new Evaluable[0]);
    final List<Evaluable> inputs = new ArrayList<>(conditions);
    inputs.addAll(coerced);
    return new Evaluable.Node(type, row -> {
      for (int i = 0; i < whens.length; i++)
        if (Boolean.TRUE.equals(whens[i].eval(row)))
          return thens[i].eval(row);
      return thens[thens.length - 1].eval(row);
    }, inputs.toArray(new Evaluable[0]));
  }

  private static Evaluable booleanOperandOf(final Evaluable condition, final String what) {
    final Evaluable converted = implicit(condition, PgType.BOOL);
    if (converted == null)
      throw PostgreSQLEvaluationException.mismatch(
          "argument of " + what + " must be type boolean, not type " + condition.type().displayName());
    return converted;
  }

  // ---- cast ----

  private Evaluable cast(final Expression.Cast e) {
    final TypeName name = e.type();
    final PgType target = PgType.byName(name.name());
    if (target == null || name.arrayDimensions() > 0 || name.intervalFields() != null)
      throw PostgreSQLEvaluationException.unsupported("The type " + String.join(".", name.name())
          + (name.arrayDimensions() > 0 ? "[]" : "") + " is not supported by the postgresql language");
    int[] modifiers = null;
    if (!name.modifiers().isEmpty()) {
      modifiers = new int[name.modifiers().size()];
      for (int i = 0; i < modifiers.length; i++) {
        if (!(name.modifiers().get(i) instanceof Expression.IntegerLiteral literal) || literal.value().bitLength() > 31)
          throw PostgreSQLEvaluationException.unsupported("A type modifier has to be an integer constant");
        modifiers[i] = literal.value().intValue();
      }
    }
    final int[] typmod = modifiers;
    final Evaluable operand = compile(e.operand());
    final PgType from = operand.type();
    final boolean possible = from == target || from == PgType.UNKNOWN || (target.isString() && !from.isOpaque()) || from.isString()
        || (from.isNumber() && target.isNumber()) || (from == PgType.BOOL && target == PgType.INT4)
        || (from == PgType.INT4 && target == PgType.BOOL) || (from.isDateTime() && target.isDateTime());
    if (!possible || (target.isOpaque() && from != target))
      throw new PostgreSQLEvaluationException("42846", "cannot cast type " + from.displayName() + " to " + target.displayName());
    if (operand instanceof Evaluable.Constant constant)
      return new Evaluable.Constant(target, Casts.cast(constant.value(), from, target, typmod));
    return new Evaluable.Node(target, row -> Casts.cast(operand.eval(row), from, target, typmod), operand);
  }

  // ---- functions ----

  private Evaluable[] compileArguments(final List<Expression> arguments) {
    final Evaluable[] out = new Evaluable[arguments.size()];
    for (int i = 0; i < out.length; i++)
      out[i] = compile(arguments.get(i));
    return out;
  }

  /** A function that returns {@code NULL} when an argument is {@code NULL}. */
  private static Evaluable strictCall(final PgType type, final Evaluable[] arguments, final Function<Object[], Object> function) {
    return new Evaluable.Node(type, row -> {
      final Object[] values = new Object[arguments.length];
      for (int i = 0; i < values.length; i++) {
        values[i] = arguments[i].eval(row);
        if (values[i] == null)
          return null;
      }
      return function.apply(values);
    }, arguments);
  }

  /** The arguments converted to the given types, one each; a call with other arguments is {@code 42883}. */
  private static Evaluable[] arguments(final String name, final Evaluable[] arguments, final PgType... types) {
    if (arguments.length != types.length)
      throw PostgreSQLEvaluationException.noFunction(name, types(arguments));
    final Evaluable[] out = new Evaluable[arguments.length];
    for (int i = 0; i < out.length; i++)
      out[i] = require(arguments[i], types[i], name, arguments);
    return out;
  }

  private Evaluable function(final Expression.FunctionCall call) {
    if (call.name().size() > 2 || (call.name().size() == 2 && !"pg_catalog".equals(call.name().get(0).value())))
      throw PostgreSQLEvaluationException.unsupported("The function " + call.name().get(0).value() + "." + call.name().get(1).value()
          + " is not supported");
    final String name = call.name().get(call.name().size() - 1).value().toLowerCase(Locale.ROOT);
    if (isAggregate(call))
      throw new PostgreSQLEvaluationException("42803", "aggregate functions are not allowed in this context");
    if (call.distinct() || call.star())
      throw PostgreSQLEvaluationException.noFunction(name);
    final Evaluable[] args = compileArguments(call.args());
    final PgType text = PgType.TEXT;
    final PgType int4 = PgType.INT4;
    switch (name) {
    case "lower": {
      final Evaluable[] a = arguments(name, args, text);
      return strictCall(text, a, v -> Text.lower((String) v[0]));
    }
    case "upper": {
      final Evaluable[] a = arguments(name, args, text);
      return strictCall(text, a, v -> Text.upper((String) v[0]));
    }
    case "initcap": {
      final Evaluable[] a = arguments(name, args, text);
      return strictCall(text, a, v -> Text.initcap((String) v[0]));
    }
    case "reverse": {
      final Evaluable[] a = arguments(name, args, text);
      return strictCall(text, a, v -> Text.reverse((String) v[0]));
    }
    case "length":
    case "char_length":
    case "character_length": {
      final Evaluable[] a = arguments(name, args, text);
      return strictCall(int4, a, v -> Text.length((String) v[0]));
    }
    case "octet_length": {
      final Evaluable[] a = arguments(name, args, text);
      return strictCall(int4, a, v -> Text.octetLength((String) v[0]));
    }
    case "strpos":
    case "position": {
      final Evaluable[] a = arguments(name, args, text, text);
      return strictCall(int4, a, v -> Text.position((String) v[0], (String) v[1]));
    }
    case "btrim":
    case "ltrim":
    case "rtrim": {
      final Evaluable[] a = args.length == 1 ? arguments(name, args, text) : arguments(name, args, text, text);
      final boolean leading = !name.equals("rtrim");
      final boolean trailing = !name.equals("ltrim");
      return strictCall(text, a, v -> Text.trim((String) v[0], v.length > 1 ? (String) v[1] : " ", leading, trailing));
    }
    case "substring":
    case "substr": {
      final Evaluable[] a = args.length == 3 ? arguments(name, args, text, int4, int4) : arguments(name, args, text, int4);
      return strictCall(text, a, v -> Text.substring((String) v[0], ((Number) v[1]).longValue(),
          v.length > 2 ? Long.valueOf(((Number) v[2]).longValue()) : null));
    }
    case "replace": {
      final Evaluable[] a = arguments(name, args, text, text, text);
      return strictCall(text, a, v -> Text.replace((String) v[0], (String) v[1], (String) v[2]));
    }
    case "left": {
      final Evaluable[] a = arguments(name, args, text, int4);
      return strictCall(text, a, v -> Text.left((String) v[0], (Integer) v[1]));
    }
    case "right": {
      final Evaluable[] a = arguments(name, args, text, int4);
      return strictCall(text, a, v -> Text.right((String) v[0], (Integer) v[1]));
    }
    case "repeat": {
      final Evaluable[] a = arguments(name, args, text, int4);
      return strictCall(text, a, v -> Text.repeat((String) v[0], (Integer) v[1]));
    }
    case "concat":
    case "concat_ws":
      return concatFunction(name, args);
    case "abs": {
      if (args.length != 1 || !args[0].type().isNumber())
        throw PostgreSQLEvaluationException.noFunction(name, types(args));
      final PgType type = args[0].type();
      return strictCall(type, args, v -> Numbers.abs(type, v[0]));
    }
    case "round":
      return round(name, args);
    case "trunc": {
      final Evaluable[] a = args.length == 1 ? arguments(name, args, PgType.NUMERIC) : arguments(name, args, PgType.NUMERIC, int4);
      return strictCall(PgType.NUMERIC, a,
          v -> Functions.truncate((BigDecimal) v[0], v.length > 1 ? (Integer) v[1] : 0));
    }
    case "floor":
    case "ceil":
    case "ceiling": {
      final boolean floor = name.equals("floor");
      if (args.length == 1 && args[0].type() == PgType.NUMERIC)
        return strictCall(PgType.NUMERIC, args, v -> floor ? Functions.floor((BigDecimal) v[0]) : Functions.ceil((BigDecimal) v[0]));
      final Evaluable[] a = arguments(name, args, PgType.FLOAT8);
      return strictCall(PgType.FLOAT8, a, v -> floor ? Math.floor((Double) v[0]) : Math.ceil((Double) v[0]));
    }
    case "sqrt": {
      final Evaluable[] a = arguments(name, args, PgType.FLOAT8);
      return strictCall(PgType.FLOAT8, a, v -> {
        if ((Double) v[0] < 0.0)
          throw new PostgreSQLEvaluationException("2201F", "cannot take square root of a negative number");
        return Math.sqrt((Double) v[0]);
      });
    }
    case "mod":
      if (args.length != 2 || !args[0].type().isNumber() || !args[1].type().isNumber())
        throw PostgreSQLEvaluationException.noFunction(name, types(args));
      return numericArithmetic(Expression.BinaryOperator.MODULO, args[0], args[1]);
    case "power":
    case "pow":
      if (args.length != 2 || !args[0].type().isNumber() || !args[1].type().isNumber())
        throw PostgreSQLEvaluationException.noFunction(name, types(args));
      return numericArithmetic(Expression.BinaryOperator.POWER, args[0], args[1]);
    case "extract":
    case "date_part":
      return extract(name, args);
    default:
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    }
  }

  private Evaluable round(final String name, final Evaluable[] args) {
    if (args.length == 1) {
      if (args[0].type() == PgType.NUMERIC)
        return strictCall(PgType.NUMERIC, args, v -> Functions.round((BigDecimal) v[0], 0));
      // an integer or a float goes to float8, the preferred type of the numeric category, as in PostgreSQL
      final Evaluable[] a = arguments(name, args, PgType.FLOAT8);
      return strictCall(PgType.FLOAT8, a, v -> Functions.round((Double) v[0]));
    }
    final Evaluable[] a = arguments(name, args, PgType.NUMERIC, PgType.INT4);
    return strictCall(PgType.NUMERIC, a, v -> Functions.round((BigDecimal) v[0], (Integer) v[1]));
  }

  private Evaluable concatFunction(final String name, final Evaluable[] args) {
    final boolean withSeparator = name.equals("concat_ws");
    if (args.length < 1)
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    final Evaluable[] texts = new Evaluable[args.length];
    for (int i = 0; i < texts.length; i++)
      texts[i] = asText(args[i]);
    return new Evaluable.Node(PgType.TEXT, row -> {
      final StringBuilder out = new StringBuilder();
      String separator = "";
      int first = 0;
      if (withSeparator) {
        final Object value = texts[0].eval(row);
        if (value == null)
          return null;
        separator = (String) value;
        first = 1;
      }
      boolean any = false;
      for (int i = first; i < texts.length; i++) {
        final Object value = texts[i].eval(row);
        if (value == null)
          continue;
        if (any)
          out.append(separator);
        out.append((String) value);
        any = true;
      }
      return out.toString();
    }, texts);
  }

  private Evaluable extract(final String name, final Evaluable[] args) {
    if (args.length != 2 || !(args[0] instanceof Evaluable.Constant field) || !(field.value() instanceof String unit)
        || !args[1].type().isDateTime())
      throw PostgreSQLEvaluationException.noFunction(name, types(args));
    final Evaluable value = args[1];
    return new Evaluable.Node(PgType.NUMERIC, row -> {
      final Object v = value.eval(row);
      return v == null ? null : Functions.extract(unit, v);
    }, value);
  }

  // ---- COALESCE and the other keyword functions ----

  private Evaluable special(final Expression.SpecialFunction call) {
    final Evaluable[] args = compileArguments(call.args());
    switch (call.kind()) {
    case COALESCE: {
      final List<Evaluable> all = List.of(args);
      final PgType type = commonType(all, "COALESCE");
      final Evaluable[] coerced = coerceAll(all, type, "COALESCE").toArray(new Evaluable[0]);
      return new Evaluable.Node(type, row -> {
        for (final Evaluable item : coerced) {
          final Object value = item.eval(row);
          if (value != null)
            return value;
        }
        return null;
      }, coerced);
    }
    case NULLIF: {
      if (args.length != 2)
        throw PostgreSQLEvaluationException.noFunction("nullif", types(args));
      final Unified unified = unify("=", args[0], args[1]);
      final PgType type = unified.type();
      final Evaluable a = unified.left();
      final Evaluable b = unified.right();
      return new Evaluable.Node(type, row -> {
        final Object x = a.eval(row);
        if (x == null)
          return null;
        final Object y = b.eval(row);
        return y != null && Comparison.compare(type, x, y) == 0 ? null : x;
      }, a, b);
    }
    case GREATEST:
    case LEAST: {
      final List<Evaluable> all = List.of(args);
      final PgType type = commonType(all, call.kind().name());
      if (!Comparison.isComparable(type))
        throw PostgreSQLEvaluationException.unsupported(call.kind().name() + " of type " + type.displayName() + " is not supported");
      final Evaluable[] coerced = coerceAll(all, type, call.kind().name()).toArray(new Evaluable[0]);
      final boolean greatest = call.kind() == Expression.SpecialFunctionKind.GREATEST;
      return new Evaluable.Node(type, row -> {
        Object best = null;
        for (final Evaluable item : coerced) {
          final Object value = item.eval(row);
          if (value == null)
            continue;
          if (best == null || (greatest ? Comparison.compare(type, value, best) > 0 : Comparison.compare(type, value, best) < 0))
            best = value;
        }
        return best;
      }, coerced);
    }
    case CURRENT_DATE:
      return new Evaluable.Node(PgType.DATE, row -> LocalDate.now());
    case LOCALTIMESTAMP:
      return new Evaluable.Node(PgType.TIMESTAMP, row -> LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
    default:
      throw PostgreSQLEvaluationException.unsupported(call.kind().name() + " is not supported by the postgresql language yet");
    }
  }
}
