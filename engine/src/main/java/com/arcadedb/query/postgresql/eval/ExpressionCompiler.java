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

import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.SessionTimeZone;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SetOperationStatement;
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.ast.WithStatement;
import com.arcadedb.query.postgresql.ast.TypeName;
import com.arcadedb.serializer.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * {@code text}, a date and a timestamp to {@code timestamp}. Anything else is no operator, see below.</li>
 * <li>{@code AND}, {@code OR}, {@code NOT} and the {@code WHERE} condition need {@code boolean}: {@code 42804} otherwise.</li>
 * <li>{@code CASE}, {@code COALESCE}, {@code GREATEST} and {@code LEAST} give their branches one type: the widest numeric type,
 * {@code text} for strings, {@code timestamp} for dates and timestamps ({@code 42804} when they cannot be matched).</li>
 * </ul>
 * A type, a function or an operator outside the subset is {@code 0A000}, and one that PostgreSQL does not have either is
 * {@code 42883} ({@link PostgreSQLBuiltins}), never a silent different answer.
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
     * Whether this query, or one around it, has a column of this unqualified name that the catalog lists: a column that the catalog
     * does not list, a hidden record id, gives way to such a column inside a subquery (fork issue #157).
     */
    default boolean shows(final Name column) {
      return false;
    }

    /** {@link #shows} of the columns of this query alone. */
    default boolean showsHere(final Name column) {
      return false;
    }

    /**
     * Called before every node is typed: a non-null answer replaces the node. A grouping query uses it to read a grouped
     * expression and an aggregate from the group row.
     */
    default Evaluable intercept(final Expression expression, final ExpressionCompiler compiler) {
      return null;
    }

    /** Plans a subquery of an expression; {@code null} where the clause has no subqueries. */
    default SubqueryPlanner subqueries() {
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

  /** {@link Scope#shows} of the scope of this compiler. */
  public boolean shows(final Name column) {
    return scope.shows(column);
  }

  /** {@link Scope#showsHere} of the scope of this compiler. */
  public boolean showsHere(final Name column) {
    return scope.showsHere(column);
  }

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
      case Expression.InSubquery e -> inSubquery(e);
      case Expression.Quantified e -> quantified(e);
      case Expression.ScalarSubquery e -> scalarSubquery(e);
      case Expression.Exists e -> exists(e);
      case Expression.Between e -> between(e);
      case Expression.Like e -> like(e);
      case Expression.Case e -> caseExpression(e);
      case Expression.Cast e -> cast(e);
      case Expression.FunctionCall e -> function(e);
      case Expression.ArrayConstructor e -> arrayConstructor(e);
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
    if (expression.type() != PgType.UNKNOWN)
      return expression;
    // a described parameter is text here only if no other place of the statement gives it a type
    if (expression instanceof Evaluable.Constant constant && constant.parameter() != null) {
      constant.parameter().inferFromOutput(PgType.TEXT);
      return new Evaluable.Constant(PgType.TEXT, null);
    }
    return implicit(expression, PgType.TEXT);
  }

  /**
   * When {@code expression} is a parameter of a described statement that has no type yet, its context converts it to
   * {@code type}, which is what a Describe then reports for it. Any other expression is left alone.
   */
  public static void inferParameter(final Evaluable expression, final PgType type) {
    if (expression instanceof Evaluable.Constant constant && constant.parameter() != null)
      constant.parameter().infer(type);
  }

  /** {@link #inferParameter} for a context that converts to the array type {@code typeName} ({@code _text}). */
  public static void inferArrayParameter(final Evaluable expression, final String typeName) {
    if (expression instanceof Evaluable.Constant constant && constant.parameter() != null)
      constant.parameter().inferArray(typeName);
  }

  public static boolean isAggregate(final Expression.FunctionCall call) {
    // an aggregate with OVER is a window function: the window stage computes it
    if (call.over() != null)
      return false;
    if (call.name().size() > 2 || (call.name().size() == 2 && !"pg_catalog".equals(call.name().get(0).value())))
      return false;
    return switch (call.name().get(call.name().size() - 1).value()) {
      case "count", "sum", "avg", "min", "max", "string_agg" -> true;
      default -> false;
    };
  }

  /** The longest {@code varchar(n)} PostgreSQL declares. */
  private static final int MAX_VARCHAR_LENGTH = 10_485_760;

  /** Aggregates of PostgreSQL that the language does not have: refused with {@code 0A000}, not reported as unknown functions. */
  private static final java.util.Set<String> UNSUPPORTED_AGGREGATES = java.util.Set.of("array_agg", "json_agg", "jsonb_agg",
      "json_object_agg", "jsonb_object_agg", "bool_and", "bool_or", "every", "bit_and", "bit_or", "bit_xor", "stddev",
      "stddev_pop", "stddev_samp", "variance", "var_pop", "var_samp", "corr", "covar_pop", "covar_samp", "mode",
      "percentile_cont", "percentile_disc");

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
    case Expression.InSubquery e -> out.add(e.operand());
    case Expression.Quantified e -> {
      out.add(e.operand());
      out.add(e.array());
    }
    case Expression.Exists e -> collectReferences(e.query(), out);
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
    case Expression.ArrayConstructor e -> out.addAll(e.elements());
    case Expression.SpecialFunction e -> out.addAll(e.args());
    default -> {
    }
    }
    return out;
  }

  /** Every column reference in a query, at any depth: the ones a correlated subquery can read from the query around it are among them. */
  private static void collectReferences(final Statement query, final List<Expression> out) {
    switch (query) {
    case SelectStatement select -> {
      for (final SelectItem item : select.items())
        if (item instanceof SelectItem.Projection projection)
          collectReferences(projection.expression(), out);
      for (final TableReference from : select.from())
        collectReferences(from, out);
      if (select.where() != null)
        collectReferences(select.where(), out);
      for (final Expression group : select.groupBy())
        collectReferences(group, out);
      if (select.having() != null)
        collectReferences(select.having(), out);
      for (final SortItem sort : select.orderBy())
        collectReferences(sort.expression(), out);
    }
    case SetOperationStatement set -> {
      collectReferences(set.left(), out);
      collectReferences(set.right(), out);
      for (final SortItem sort : set.orderBy())
        collectReferences(sort.expression(), out);
    }
    case WithStatement with -> {
      for (final WithStatement.Cte cte : with.ctes())
        collectReferences(cte.query(), out);
      collectReferences(with.body(), out);
    }
    default -> {
    }
    }
  }

  private static void collectReferences(final TableReference from, final List<Expression> out) {
    switch (from) {
    case TableReference.Join join -> {
      collectReferences(join.left(), out);
      collectReferences(join.right(), out);
      if (join.condition() != null)
        collectReferences(join.condition(), out);
    }
    case TableReference.Subquery subquery -> collectReferences(subquery.query(), out);
    default -> {
    }
    }
  }

  private static void collectReferences(final Expression expression, final List<Expression> out) {
    if (expression instanceof Expression.ColumnRef)
      out.add(expression);
    else
      for (final Expression child : children(expression))
        collectReferences(child, out);
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
    if (parameters.isDescribing())
      return parameters.describedParameter(reference.index());
    final Object value = parameters.get(reference.index());
    // a value of a parameter whose type the client did not declare is typed by the expression it is in, as a string literal is
    if (value instanceof UnknownText unknown)
      return new Evaluable.Constant(PgType.UNKNOWN, unknown.text());
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
      inferParameter(constant, target);
      if (constant.value() == null)
        return new Evaluable.Constant(target, null);
      return new Evaluable.Constant(target, Casts.parse((String) constant.value(), target));
    }
    if (from.isString() && target.isString())
      return expression;
    if (from.isNumber() && target.isNumber() && rank(from) < rank(target))
      return new Evaluable.Node(target, row -> Numbers.widen(expression.eval(row), target), expression);
    if (from == PgType.DATE && target == PgType.TIMESTAMP || target == PgType.TIMESTAMPTZ && from.isDateTime())
      return new Evaluable.Node(target, row -> Casts.cast(expression.eval(row), from, target, null), expression);
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
        result = PgType.commonDateTime(result, type);
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
    case IS_DISTINCT_FROM:
    case IS_NOT_DISTINCT_FROM:
      return comparison(operator, compile(e.left()), compile(e.right()));
    case CONCAT:
      return concat(compile(e.left()), compile(e.right()));
    case CONTAINS:
    case CONTAINED_BY:
    case OVERLAPS:
      return arrayContainment(operator, compile(e.left()), compile(e.right()));
    case L2_DISTANCE:
    case COSINE_DISTANCE:
    case NEGATIVE_INNER_PRODUCT:
      return VectorDistance.of(operator, compile(e.left()), compile(e.right()));
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
      common = PgType.commonDateTime(a, b);
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
    if (left.type() == PgType.ARRAY || right.type() == PgType.ARRAY)
      return arrayEquality(operator, left, right);
    final Unified unified = unify(operator.symbol(), left, right);
    final PgType type = unified.type();
    final Evaluable l = unified.left();
    final Evaluable r = unified.right();
    if (operator == Expression.BinaryOperator.IS_DISTINCT_FROM || operator == Expression.BinaryOperator.IS_NOT_DISTINCT_FROM) {
      // two NULLs are the same, a NULL and a value are not, and the answer is never NULL
      final boolean wantSame = operator == Expression.BinaryOperator.IS_NOT_DISTINCT_FROM;
      return new Evaluable.Node(PgType.BOOL, row -> {
        final Object x = l.eval(row);
        final Object y = r.eval(row);
        final boolean same = x == null || y == null ? x == null && y == null : Comparison.compare(type, x, y) == 0;
        return same == wantSame;
      }, l, r);
    }
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

  // ---- arrays ----

  /** Two array operands and the one element type that they have. */
  private record ArrayPair(PgType element, Evaluable left, Evaluable right) {
  }

  /**
   * The operands of an operator that PostgreSQL defines for two arrays of one element type ({@code anyarray}): an operand of no type
   * (a literal, a parameter with no declared type) is an array of the type of the other one.
   */
  private static ArrayPair arrayPair(final String operator, final Evaluable left, final Evaluable right) {
    final PgType a = left.type();
    final PgType b = right.type();
    if (a != PgType.ARRAY && a != PgType.UNKNOWN || b != PgType.ARRAY && b != PgType.UNKNOWN)
      throw PostgreSQLEvaluationException.noOperator(operator, a, b);
    final PgType first = declaredElement(left);
    final PgType second = declaredElement(right);
    if (first != null && second != null && textual(first) != textual(second)) {
      // noOperator cannot say this: it sees two arrays, for which PostgreSQL has the operator, and not their element types
      throw new PostgreSQLEvaluationException("42883", "operator does not exist: " + first.displayName() + "[] " + operator + " "
          + second.displayName() + "[]. Hint: No operator matches the given name and argument types. You might need to add explicit type casts.");
    }
    PgType element = first != null ? textual(first) : second != null ? textual(second) : null;
    if (element == null) {
      if (a == PgType.UNKNOWN && b == PgType.UNKNOWN)
        throw new PostgreSQLEvaluationException("42725", "operator is not unique: unknown " + operator + " unknown");
      element = PgType.TEXT;
    }
    if (!ValueInput.isElementType(element) || !Comparison.isComparable(element))
      throw PostgreSQLEvaluationException.unsupported(
          "The operator " + operator + " on arrays of " + element.displayName() + " is not supported");
    return new ArrayPair(element, a == PgType.UNKNOWN ? arrayOfUntyped(left, element) : left,
        b == PgType.UNKNOWN ? arrayOfUntyped(right, element) : right);
  }

  /** The element type that an array says it has, {@code null} for an operand of no type or an empty bound array. */
  private static PgType declaredElement(final Evaluable array) {
    if (array.type() != PgType.ARRAY)
      return null;
    final PgType named = ValueInput.elementType(array.typeName());
    if (named != null)
      return named;
    if (array instanceof Evaluable.Constant constant)
      return elementTypeOfValues(constant.value());
    throw PostgreSQLEvaluationException.unsupported(
        "The element type of this array is not known to the language: cast it, as in x::text[]");
  }

  /**
   * {@code =}, {@code <>} and {@code IS [NOT] DISTINCT FROM} of two arrays: the same length and equal elements, where, as
   * in PostgreSQL, a {@code NULL} element is equal to a {@code NULL} element only. The order of arrays is refused.
   */
  private static Evaluable arrayEquality(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    final boolean distinctness = operator == Expression.BinaryOperator.IS_DISTINCT_FROM
        || operator == Expression.BinaryOperator.IS_NOT_DISTINCT_FROM;
    final ArrayPair pair = arrayPair(operator.symbol(), left, right);
    if (!distinctness && operator != Expression.BinaryOperator.EQUAL && operator != Expression.BinaryOperator.NOT_EQUAL)
      throw PostgreSQLEvaluationException.unsupported("The operator " + operator.symbol() + " is not supported for arrays");
    final boolean whenSame = operator == Expression.BinaryOperator.EQUAL || operator == Expression.BinaryOperator.IS_NOT_DISTINCT_FROM;
    return new Evaluable.Node(PgType.BOOL, row -> {
      final List<Object> x = elements(pair.left().eval(row), pair.element());
      final List<Object> y = elements(pair.right().eval(row), pair.element());
      if (x == null || y == null)
        return distinctness ? (Object) ((x == null && y == null) == whenSame) : null;
      boolean same = x.size() == y.size();
      for (int i = 0; same && i < x.size(); i++) {
        final Object p = x.get(i);
        final Object q = y.get(i);
        same = p == null || q == null ? p == null && q == null : Comparison.compare(pair.element(), p, q) == 0;
      }
      return same == whenSame;
    }, pair.left(), pair.right());
  }

  /**
   * {@code @>}, {@code <@} and {@code &&} of two arrays: {@code NULL} when an array is {@code NULL}. A {@code NULL} element is
   * equal to nothing, so an array that has one is contained in no array.
   */
  private static Evaluable arrayContainment(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    final ArrayPair pair = arrayPair(operator.symbol(), left, right);
    return new Evaluable.Node(PgType.BOOL, row -> {
      final List<Object> x = elements(pair.left().eval(row), pair.element());
      final List<Object> y = elements(pair.right().eval(row), pair.element());
      if (x == null || y == null)
        return null;
      final boolean reversed = operator == Expression.BinaryOperator.CONTAINED_BY;
      final List<Object> holder = reversed ? y : x;
      final List<Object> wanted = reversed ? x : y;
      final boolean overlap = operator == Expression.BinaryOperator.OVERLAPS;
      for (final Object item : wanted)
        // one element that is held decides &&, one that is not decides @> and <@
        if (holds(holder, item, pair.element()) == overlap)
          return overlap;
      return !overlap;
    }, pair.left(), pair.right());
  }

  private static boolean holds(final List<Object> array, final Object value, final PgType element) {
    if (value == null)
      return false;
    for (final Object item : array)
      if (item != null && Comparison.compare(element, item, value) == 0)
        return true;
    return false;
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
    if (a == PgType.INTERVAL || b == PgType.INTERVAL)
      return intervalArithmetic(operator, x, y);
    if (a.isDateTime() || b.isDateTime())
      throw PostgreSQLEvaluationException.unsupported(
          "The operator " + symbol + " on " + a.displayName() + " and " + b.displayName() + " is not supported");
    throw PostgreSQLEvaluationException.noOperator(symbol, a, b);
  }

  /**
   * {@code date + interval}, {@code date - interval}, {@code timestamp + interval}, {@code timestamp - interval} and
   * {@code interval + date or timestamp}: the result is a {@code timestamp}, and a {@code timestamptz} for one, as in
   * PostgreSQL ({@code date '1998-12-01' - interval '90' day} is {@code 1998-09-02 00:00:00}). Any other operator on an interval is refused with {@code 0A000}. An
   * expression over constants is computed once.
   */
  private Evaluable intervalArithmetic(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    final boolean plus = operator == Expression.BinaryOperator.ADD;
    final boolean minus = operator == Expression.BinaryOperator.SUBTRACT;
    final PgType a = left.type();
    final PgType b = right.type();
    final Evaluable moment;
    final Evaluable span;
    if ((plus || minus) && a.isDateTime() && b == PgType.INTERVAL) {
      moment = left;
      span = right;
    } else if (plus && a == PgType.INTERVAL && b.isDateTime()) {
      moment = right;
      span = left;
    } else
      throw PostgreSQLEvaluationException.unsupported("The operator " + operator.symbol() + " on " + a.displayName() + " and "
          + b.displayName() + " is not supported: an interval is added to or subtracted from a date or a timestamp only");
    final boolean subtract = minus;
    final PgType type = moment.type() == PgType.TIMESTAMPTZ ? PgType.TIMESTAMPTZ : PgType.TIMESTAMP;
    final Evaluable result = new Evaluable.Node(type, row -> {
      final Object when = moment.eval(row);
      if (when == null)
        return null;
      final Object by = span.eval(row);
      if (by == null)
        return null;
      if (when instanceof OffsetDateTime instant)
        // the session is in UTC, so a day is 24 hours and a month is counted on the UTC calendar
        return Casts.instant(PgInterval.add(instant.toLocalDateTime(), (PgInterval) by, subtract));
      final LocalDateTime start = when instanceof LocalDate date ? date.atStartOfDay() : (LocalDateTime) when;
      return PgInterval.add(start, (PgInterval) by, subtract);
    }, moment, span);
    return result.isConstant() ? new Evaluable.Constant(type, result.eval(new Object[0])) : result;
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
  private Subquery planSubquery(final com.arcadedb.query.postgresql.ast.Statement query) {
    final SubqueryPlanner planner = scope.subqueries();
    if (planner == null)
      throw new PostgreSQLFeatureNotSupportedException("A subquery is not supported in this clause");
    return planner.plan(query);
  }

  /**
   * {@code operand [NOT] IN (subquery)}. The subquery is run once, on the first row. The answer is the one of the list form:
   * true when a row is equal, else {@code NULL} when the operand or a row is {@code NULL}, else false; an empty subquery gives false
   * even for a {@code NULL} operand. {@code NOT IN} is the negation, so a {@code NULL} row in the subquery makes it {@code NULL}
   * for every operand that has no equal row.
   */
  private Evaluable inSubquery(final Expression.InSubquery e) {
    final Evaluable compiled = compile(e.operand());
    // a described parameter with no type yet waits for the column of the subquery, which is what types it
    final boolean untyped = compiled instanceof Evaluable.Constant constant && constant.parameter() != null;
    final Evaluable resolved = untyped ? compiled : resolveOutput(compiled);
    final SubqueryPlanner planner = scope.subqueries();
    final SubqueryPlanner.CorrelatedRows correlated = planner == null ? null : planner.correlated(e.query(), this);
    final Subquery subquery = correlated != null ? null : planSubquery(e.query());
    final List<PgType> columnTypes = correlated != null ? correlated.types() : subquery.types();
    final List<String> columnTypeNames = correlated != null ? correlated.typeNames() : subquery.typeNames();
    if (columnTypes.size() != 1)
      throw new PostgreSQLEvaluationException("42601", "subquery has too many columns");
    final PgType innerType = columnTypes.get(0);
    final Evaluable operand = untyped ? implicit(resolved, innerType) : resolved;
    final boolean negated = e.negated();

    // the comparison of the operand with a row, typed as PostgreSQL types "operand = value"
    final Evaluable equal = comparison(Expression.BinaryOperator.EQUAL, new Evaluable.Slot(operand.type(), operand.typeName(), 0),
        new Evaluable.Slot(innerType, columnTypeNames.get(0), 1));

    final Function<Object, Object> key = hashKey(operand.type(), innerType);
    if (key != null && correlated == null) {
      final InSet set = new InSet(subquery, key);
      return new Evaluable.Node(PgType.BOOL, row -> {
        final Object value = operand.eval(row);
        set.load();
        if (set.empty)
          return negated;
        if (value == null)
          return null;
        if (set.keys.contains(key.apply(value)))
          return !negated;
        return set.hasNull ? null : (Object) negated;
      });
    }

    return new Evaluable.Node(PgType.BOOL, row -> {
      final Object value = operand.eval(row);
      final List<Object[]> rows = correlated != null ? correlated.rows(row) : subquery.rows();
      if (rows.isEmpty())
        return negated;
      boolean unknown = false;
      final Object[] pair = new Object[] { value, null };
      for (final Object[] inner : rows) {
        pair[1] = inner[0];
        final Object result = equal.eval(pair);
        if (Boolean.TRUE.equals(result))
          return !negated;
        if (result == null)
          unknown = true;
      }
      return unknown ? null : (Object) negated;
    });
  }

  /** {@code EXISTS (subquery)}: never {@code NULL}. The planner decides how the rows are found (see {@link SubqueryPlanner#exists}). */
  private Evaluable exists(final Expression.Exists e) {
    final SubqueryPlanner planner = scope.subqueries();
    if (planner == null)
      throw new PostgreSQLFeatureNotSupportedException("A subquery is not supported in this clause");
    return planner.exists(e.query(), this);
  }

  /**
   * {@code (subquery)} as a value: the one column of its one row, {@code NULL} for no row, SQLSTATE {@code 21000} for more than one
   * row. It is run once, when the first row asks for it.
   */
  private Evaluable scalarSubquery(final Expression.ScalarSubquery e) {
    final SubqueryPlanner planner = scope.subqueries();
    final SubqueryPlanner.CorrelatedRows correlated = planner == null ? null : planner.correlated(e.query(), this);
    if (correlated != null) {
      if (correlated.types().size() != 1)
        throw new PostgreSQLEvaluationException("42601", "subquery must return only one column");
      final PgType type = correlated.types().get(0);
      final String typeName = correlated.typeNames().get(0);
      return new Evaluable() {
        @Override
        public PgType type() {
          return type;
        }

        @Override
        public String typeName() {
          return typeName;
        }

        @Override
        public Object eval(final Object[] row) {
          final List<Object[]> rows = correlated.rows(row);
          if (rows.size() > 1)
            throw new PostgreSQLEvaluationException("21000", "more than one row returned by a subquery used as an expression");
          return rows.isEmpty() ? null : rows.get(0)[0];
        }
      };
    }
    final Subquery subquery = planSubquery(e.query());
    if (subquery.types().size() != 1)
      throw new PostgreSQLEvaluationException("42601", "subquery must return only one column");
    final PgType type = subquery.types().get(0);
    final String typeName = subquery.typeNames().get(0);
    return new Evaluable() {
      @Override
      public PgType type() {
        return type;
      }

      @Override
      public String typeName() {
        return typeName;
      }

      @Override
      public Object eval(final Object[] row) {
        final List<Object[]> rows = subquery.rows();
        if (rows.size() > 1)
          throw new PostgreSQLEvaluationException("21000", "more than one row returned by a subquery used as an expression");
        return rows.isEmpty() ? null : rows.get(0)[0];
      }
    };
  }

  /** The key under which two values of these types that are equal are the same, or {@code null} when equality needs a comparison. */
  private static Function<Object, Object> hashKey(final PgType left, final PgType right) {
    if (left.isInteger() && right.isInteger())
      return value -> ((Number) value).longValue();
    if (left.isString() && right.isString())
      return value -> value;
    if (left == right && (left.isDateTime() || left == PgType.BOOL))
      return value -> value;
    return null;
  }

  /** The rows of a subquery as a hash set of keys, loaded on the first use. */
  private static final class InSet {
    private final Subquery                subquery;
    private final Function<Object, Object> key;
    private Set<Object>                    keys;
    private boolean                        hasNull;
    private boolean                        empty;

    InSet(final Subquery subquery, final Function<Object, Object> key) {
      this.subquery = subquery;
      this.key = key;
    }

    synchronized void load() {
      if (keys != null)
        return;
      final Set<Object> loaded = new HashSet<>();
      final List<Object[]> rows = subquery.rows();
      empty = rows.isEmpty();
      for (final Object[] row : rows)
        if (row[0] == null)
          hasNull = true;
        else
          loaded.add(key.apply(row[0]));
      keys = loaded;
    }
  }

  private Evaluable inList(final Expression.InList e) {
    final Evaluable operand = compile(e.operand());
    final List<Evaluable> comparisons = new ArrayList<>();
    for (final Expression value : e.values())
      comparisons.add(comparison(Expression.BinaryOperator.EQUAL, operand, compile(value)));
    final Evaluable any = orChain(comparisons);
    return e.negated() ? negate(any) : any;
  }

  /**
   * {@code x op ANY (array)} and {@code x op ALL (array)} with the three-valued logic of PostgreSQL. A {@code NULL} array gives
   * {@code NULL}; an empty one gives false for {@code ANY} and true for {@code ALL}, even for a {@code NULL} operand. Otherwise
   * one true comparison decides {@code ANY} and one false comparison decides {@code ALL}, and without one the answer is
   * {@code NULL} when a comparison was {@code NULL}. An array of no type (a literal, a parameter with no declared type) is one of
   * the type of the operand.
   */
  private Evaluable quantified(final Expression.Quantified e) {
    final Evaluable compiledOperand = compile(e.operand());
    final Evaluable compiled = compile(e.array());
    final PgType from = compiled.type();
    if (from != PgType.UNKNOWN && from != PgType.ARRAY)
      throw new PostgreSQLEvaluationException("42809", "op ANY/ALL (array) requires array on right side");

    PgType element = from == PgType.ARRAY ? ValueInput.elementType(compiled.typeName()) : null;
    // an array that a caller binds as a Java collection or array says what it holds with its values
    if (element == null && compiled instanceof Evaluable.Constant constant && from == PgType.ARRAY)
      element = elementTypeOfValues(constant.value());
    if (element == null) {
      // a literal or a parameter with no type, and an empty array that nothing else types
      if (from == PgType.ARRAY && !(compiled instanceof Evaluable.Constant))
        throw PostgreSQLEvaluationException.unsupported(
            "The element type of this array is not known to the language: cast it, as in x::text[]");
      element = compiledOperand.type() == PgType.UNKNOWN ? PgType.TEXT : textual(compiledOperand.type());
    }
    if (!ValueInput.isElementType(element))
      throw PostgreSQLEvaluationException.unsupported("ANY and ALL over an array of " + element.displayName()
          + " are not supported: one dimension of booleans, numbers, text, dates or timestamps");

    final Evaluable operand = compiledOperand.type() == PgType.UNKNOWN ? implicit(compiledOperand, textual(element))
        : compiledOperand;
    if (operand == null)
      throw PostgreSQLEvaluationException.noOperator(e.operator().symbol(), compiledOperand.type(), element);
    final Evaluable array = from == PgType.UNKNOWN ? arrayOfUntyped(compiled, element) : compiled;
    // the comparison of the operand with an element, typed as PostgreSQL types "operand op element"
    final Evaluable compare = comparison(e.operator(), new Evaluable.Slot(operand.type(), operand.typeName(), 0),
        new Evaluable.Slot(element, 1));

    final PgType elementType = element;
    final boolean all = e.all();
    // = ANY and <> ALL over an array that no row changes are a lookup in a set
    final boolean membership = e.operator() == (all ? Expression.BinaryOperator.NOT_EQUAL : Expression.BinaryOperator.EQUAL);
    final Function<Object, Object> key = membership && array.isConstant() ? hashKey(operand.type(), elementType) : null;
    if (key != null) {
      final List<Object> items = elements(array.eval(new Object[0]), elementType);
      final Set<Object> keys = new HashSet<>();
      boolean hasNull = false;
      if (items != null)
        for (final Object item : items)
          if (item == null)
            hasNull = true;
          else
            keys.add(key.apply(item));
      final boolean unknown = hasNull;
      return new Evaluable.Node(PgType.BOOL, row -> {
        if (items == null)
          return null;
        if (items.isEmpty())
          return all;
        final Object value = operand.eval(row);
        if (value == null)
          return null;
        if (keys.contains(key.apply(value)))
          return !all;
        return unknown ? null : (Object) all;
      }, operand, array);
    }

    return new Evaluable.Node(PgType.BOOL, row -> {
      final List<Object> items = elements(array.eval(row), elementType);
      if (items == null)
        return null;
      if (items.isEmpty())
        return all;
      final Object[] pair = new Object[] { operand.eval(row), null };
      boolean unknown = false;
      for (final Object item : items) {
        pair[1] = item;
        final Object result = compare.eval(pair);
        if (result == null)
          unknown = true;
        else if ((Boolean) result != all)
          return !all;
      }
      return unknown ? null : (Object) all;
    }, operand, array);
  }

  /** A literal, a {@code NULL} or a parameter of no declared type as an array of {@code element}. */
  private static Evaluable arrayOfUntyped(final Evaluable expression, final PgType element) {
    if (!(expression instanceof Evaluable.Constant constant))
      throw PostgreSQLEvaluationException.unsupported(
          "The element type of this array is not known to the language: cast it, as in x::text[]");
    final String typeName = "_" + element.typeName();
    inferArrayParameter(constant, typeName);
    // a bad literal is an error before any row is read
    final Object value = ValueInput.toArray(constant.value(), element);
    return new Evaluable.Node(PgType.ARRAY, typeName, row -> value, constant);
  }

  /** The elements of an array value in the canonical class of {@code element}, {@code null} for the array {@code NULL}. */
  private static List<Object> elements(final Object array, final PgType element) {
    if (array == null)
      return null;
    final List<?> items = ValueInput.asList(array);
    if (items == null)
      throw PostgreSQLEvaluationException.mismatch("a value of class " + array.getClass().getName() + " is not an array");
    final List<Object> out = new ArrayList<>(items.size());
    for (final Object item : items)
      out.add(item == null || item == JSONObject.NULL ? null : Casts.fromEngine(item, element));
    return out;
  }

  /** The type that the values of a bound array have in common, {@code null} for an array with no value that says one. */
  private static PgType elementTypeOfValues(final Object array) {
    final List<?> items = array == null ? null : ValueInput.asList(array);
    if (items == null)
      return null;
    final List<Evaluable> values = new ArrayList<>(items.size());
    for (final Object item : items) {
      if (item == null || item == JSONObject.NULL)
        continue;
      final PgType type = PgType.ofJavaValue(item);
      if (!ValueInput.isElementType(type))
        throw PostgreSQLEvaluationException.unsupported(
            "An element of a bound array has the unsupported class " + item.getClass().getName());
      values.add(new Evaluable.Constant(type, null));
    }
    return values.isEmpty() ? null : commonType(values, "ARRAY");
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
    final String operator = (e.negated() ? "!~~" : "~~") + (e.ignoreCase() ? "*" : "");
    final Evaluable left = compile(e.operand());
    final Evaluable right = compile(e.pattern());
    final Evaluable operand = implicit(left, PgType.TEXT);
    final Evaluable pattern = implicit(right, PgType.TEXT);
    // the error names both operands as they are: PostgreSQL has this operator for bytea, so the pair decides the code
    if (operand == null || pattern == null)
      throw PostgreSQLEvaluationException.noOperator(operator, left.type(), right.type());
    final Evaluable escape = e.escape() == null ? null : likeOperand(compile(e.escape()), operator);
    final boolean negated = e.negated();
    final boolean ignoreCase = e.ignoreCase();
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
      return Text.like((String) text, (String) regex, escapeText, ignoreCase) != negated;
    }, inputs);
  }

  private static Evaluable likeOperand(final Evaluable expression, final String operator) {
    final Evaluable text = implicit(expression, PgType.TEXT);
    if (text == null)
      throw PostgreSQLEvaluationException.noOperator(operator, expression.type(), PgType.TEXT);
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
    if (target == PgType.INTERVAL)
      return intervalCast(e);
    if (name.arrayDimensions() > 0)
      return arrayCast(e, target);
    if (target == null || name.intervalFields() != null)
      throw PostgreSQLEvaluationException.unsupported(
          "The type " + String.join(".", name.name()) + " is not supported by the postgresql language");
    final int[] typmod = typeModifiers(name, target);
    final Evaluable operand = compile(e.operand());
    // a Java value bound as a parameter takes the type of the cast, as a text parameter does
    if (e.operand() instanceof Expression.ParameterRef && operand instanceof Evaluable.Constant constant && constant.value() != null
        && (target == PgType.JSONB && constant.type() == PgType.ARRAY || target == PgType.VECTOR && constant.type() == PgType.ARRAY))
      return new Evaluable.Constant(target,
          target == PgType.JSONB ? ValueInput.toJson(constant.value()) : Casts.cast(constant.value(), PgType.ARRAY, target, typmod));
    final PgType from = operand.type();
    final boolean possible = from == target || (target == PgType.VECTOR && from == PgType.ARRAY) || from == PgType.UNKNOWN || (target.isString() && !from.isOpaque()) || from.isString()
        || (from.isNumber() && target.isNumber()) || (from == PgType.BOOL && target == PgType.INT4)
        || (from == PgType.INT4 && target == PgType.BOOL) || (from.isDateTime() && target.isDateTime());
    // text, and a string literal, is read by the input function of any type; another type has no cast to jsonb or bytea
    if (!possible || (target.isOpaque() && from != target && from != PgType.UNKNOWN && !from.isString()))
      throw new PostgreSQLEvaluationException("42846", "cannot cast type " + from.displayName() + " to " + target.displayName());
    inferParameter(operand, target);
    if (operand instanceof Evaluable.Constant constant)
      return new Evaluable.Constant(target, Casts.cast(constant.value(), from, target, typmod));
    return new Evaluable.Node(target, row -> Casts.cast(operand.eval(row), from, target, typmod), operand);
  }

  /**
   * The type modifiers of a cast, checked as the {@code typmodin} function of the type checks them, {@code null} for none.
   * {@code numeric(p,s)}, {@code varchar(n)}, {@code timestamp(p)}, {@code timestamptz(p)} and {@code vector(n)} take one, and
   * {@link Casts#cast} applies it. Any other type with a modifier is {@code 42601}, so none is accepted and then ignored. A
   * timestamp precision above 6 is 6, as in PostgreSQL (which also warns).
   */
  private static int[] typeModifiers(final TypeName name, final PgType target) {
    if (name.modifiers().isEmpty())
      return null;
    if (target != PgType.NUMERIC && target != PgType.VARCHAR && target != PgType.TIMESTAMP && target != PgType.TIMESTAMPTZ
        && target != PgType.VECTOR)
      throw new PostgreSQLEvaluationException("42601",
          "type modifier is not allowed for type \"" + String.join(".", name.name()) + "\"");
    final int[] values = new int[name.modifiers().size()];
    for (int i = 0; i < values.length; i++)
      values[i] = integerModifier(name.modifiers().get(i));
    if (values.length > (target == PgType.NUMERIC ? 2 : 1))
      throw invalidModifier(target == PgType.NUMERIC ? "invalid NUMERIC type modifier" : "invalid type modifier");
    final int first = values[0];
    switch (target) {
    case NUMERIC -> {
      if (first < 1 || first > 1000)
        throw invalidModifier("NUMERIC precision " + first + " must be between 1 and 1000");
      if (values.length == 2 && (values[1] < -1000 || values[1] > 1000))
        throw invalidModifier("NUMERIC scale " + values[1] + " must be between -1000 and 1000");
    }
    case VARCHAR -> {
      if (first < 1)
        throw invalidModifier("length for type varchar must be at least 1");
      if (first > MAX_VARCHAR_LENGTH)
        throw invalidModifier("length for type varchar cannot exceed " + MAX_VARCHAR_LENGTH);
    }
    case TIMESTAMP, TIMESTAMPTZ -> {
      if (first < 0)
        throw invalidModifier("TIMESTAMP(" + first + ")" + (target == PgType.TIMESTAMPTZ ? " WITH TIME ZONE" : "")
            + " precision must not be negative");
      values[0] = Math.min(first, Casts.MAX_TIMESTAMP_PRECISION);
    }
    default -> {
      if (first < 1)
        throw invalidModifier("dimensions for type vector must be at least 1");
      if (first > VectorDistance.MAX_DIMENSIONS)
        throw invalidModifier("dimensions for type vector cannot exceed " + VectorDistance.MAX_DIMENSIONS);
    }
    }
    return values;
  }

  private static int integerModifier(final Expression modifier) {
    if (!(modifier instanceof Expression.IntegerLiteral literal) || literal.value().bitLength() > 31)
      throw PostgreSQLEvaluationException.unsupported("A type modifier has to be an integer constant");
    return literal.value().intValue();
  }

  private static PostgreSQLEvaluationException invalidModifier(final String message) {
    return new PostgreSQLEvaluationException("22023", message);
  }

  /**
   * A cast to an array of one dimension: {@code '{a,b}'::text[]}, {@code ARRAY[1,2]::float8[]}, {@code $1::int4[]}. The value is
   * a list, and the type name of the expression says its element type.
   */
  private Evaluable arrayCast(final Expression.Cast e, final PgType element) {
    final TypeName name = e.type();
    if (!ValueInput.isElementType(element) || name.arrayDimensions() > 1 || !name.modifiers().isEmpty()
        || name.intervalFields() != null)
      throw PostgreSQLEvaluationException.unsupported(
          "The type " + String.join(".", name.name()) + "[] is not supported by the postgresql language");
    final String typeName = "_" + element.typeName();
    if (e.operand() instanceof Expression.ArrayConstructor array && array.elements().isEmpty())
      return new Evaluable.Node(PgType.ARRAY, typeName, row -> List.of(), new Evaluable.Constant(PgType.UNKNOWN, null));
    final Evaluable operand = compile(e.operand());
    final PgType from = operand.type();
    if (from != PgType.UNKNOWN && !from.isString() && from != PgType.ARRAY && !(from == PgType.VECTOR && element == PgType.FLOAT4))
      throw new PostgreSQLEvaluationException("42846",
          "cannot cast type " + from.displayName() + " to " + element.displayName() + "[]");
    inferArrayParameter(operand, typeName);
    if (operand instanceof Evaluable.Constant constant) {
      // a bad literal is an error before any row is read
      final Object value = ValueInput.toArray(constant.value(), element);
      return new Evaluable.Node(PgType.ARRAY, typeName, row -> value, operand);
    }
    return new Evaluable.Node(PgType.ARRAY, typeName, row -> ValueInput.toArray(operand.eval(row), element), operand);
  }

  /** {@code ARRAY[a, b]}: the elements take their common type, as the branches of a {@code CASE} do. */
  private Evaluable arrayConstructor(final Expression.ArrayConstructor e) {
    if (e.elements().isEmpty())
      throw new PostgreSQLEvaluationException("42P18",
          "cannot determine type of empty array. Hint: Explicitly cast to the desired type, for example ARRAY[]::integer[]");
    final List<Evaluable> items = new ArrayList<>(e.elements().size());
    for (final Expression element : e.elements())
      items.add(compile(element));
    final PgType element = commonType(items, "ARRAY");
    if (!ValueInput.isElementType(element))
      throw PostgreSQLEvaluationException.unsupported("An array of " + element.displayName()
          + " is not supported: one dimension of booleans, numbers, text, bytea, dates or timestamps");
    final Evaluable[] converted = coerceAll(items, element, "ARRAY").toArray(new Evaluable[0]);
    return new Evaluable.Node(PgType.ARRAY, "_" + element.typeName(), row -> {
      final List<Object> out = new ArrayList<>(converted.length);
      for (final Evaluable item : converted)
        out.add(item.eval(row));
      return out;
    }, converted);
  }

  /**
   * {@code interval 'text'}, {@code interval 'N' day} and {@code CAST('text' AS interval)}: a string read by the input function
   * of {@code interval}, with the single field restriction of the literal, if any. A precision, an array, a range of fields
   * ({@code day to second}) and any operand that is not a string are refused.
   */
  private Evaluable intervalCast(final Expression.Cast e) {
    final TypeName name = e.type();
    final String field = name.intervalFields();
    if (name.arrayDimensions() > 0 || !name.modifiers().isEmpty() || !PgInterval.isSupportedField(field))
      throw PostgreSQLEvaluationException.unsupported("The type interval"
          + (field != null ? " " + field : name.arrayDimensions() > 0 ? "[]" : "(p)")
          + " is not supported by the postgresql language: only a single field (year, month, day, hour, minute, second) is read");
    final Evaluable operand = compile(e.operand());
    final PgType from = operand.type();
    if (from == PgType.INTERVAL && field == null)
      return operand;
    if (from != PgType.UNKNOWN && !from.isString())
      throw new PostgreSQLEvaluationException("42846", "cannot cast type " + from.displayName() + " to interval");
    inferParameter(operand, PgType.INTERVAL);
    if (operand instanceof Evaluable.Constant constant)
      return new Evaluable.Constant(PgType.INTERVAL, constant.value() == null ? null : PgInterval.parse((String) constant.value(), field));
    return new Evaluable.Node(PgType.INTERVAL, row -> {
      final Object value = operand.eval(row);
      return value == null ? null : PgInterval.parse((String) value, field);
    }, operand);
  }

  // ---- functions ----

  /** The types of the arguments of a call, for the error of a call that the language cannot type. */
  public PgType[] argumentTypes(final Expression.FunctionCall call) {
    return types(compileArguments(call.args()));
  }

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

  /** The arguments converted to the given types, one each; a call with other arguments is no function of the language. */
  private static Evaluable[] arguments(final String name, final Evaluable[] arguments, final PgType... types) {
    if (arguments.length != types.length)
      throw PostgreSQLEvaluationException.noFunction(name, types(arguments));
    final Evaluable[] out = new Evaluable[arguments.length];
    for (int i = 0; i < out.length; i++)
      out[i] = require(arguments[i], types[i], name, arguments);
    return out;
  }

  private Evaluable function(final Expression.FunctionCall call) {
    if (call.over() != null)
      throw new PostgreSQLEvaluationException("42P20", "window functions are not allowed in this context");
    if (call.name().size() > 2 || (call.name().size() == 2 && !"pg_catalog".equals(call.name().get(0).value())))
      throw PostgreSQLEvaluationException.unsupported("The function " + call.name().get(0).value() + "." + call.name().get(1).value()
          + " is not supported");
    final String name = call.name().get(call.name().size() - 1).value().toLowerCase(Locale.ROOT);
    if (isAggregate(call))
      throw new PostgreSQLEvaluationException("42803", "aggregate functions are not allowed in this context");
    if (UNSUPPORTED_AGGREGATES.contains(name))
      throw PostgreSQLEvaluationException.unsupported("The aggregate function " + name + " is not supported");
    if (call.distinct() || call.star())
      throw PostgreSQLEvaluationException.noFunction(name, argumentTypes(call));
    final Evaluable[] args = compileArguments(call.args());
    final PgType text = PgType.TEXT;
    final PgType int4 = PgType.INT4;
    switch (name) {
    case "gen_random_uuid": {
      // PostgreSQL returns a uuid, a type the language does not have: the value is the text form, 36 characters, a version 4 uuid,
      // which a text column takes and which compares and casts to text as the uuid does. Not strict and not constant: one per row
      if (args.length != 0) {
        final PgType[] types = new PgType[args.length];
        for (int i = 0; i < types.length; i++)
          types[i] = args[i].type();
        throw PostgreSQLEvaluationException.noFunction(name, types);
      }
      return new Evaluable.Node(text, row -> java.util.UUID.randomUUID().toString());
    }
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
      // numeric has its own root; any other number takes the one of double precision
      if (args.length == 1 && args[0].type() == PgType.NUMERIC)
        return strictCall(PgType.NUMERIC, args, v -> Numbers.sqrt((BigDecimal) v[0]));
      final Evaluable[] a = arguments(name, args, PgType.FLOAT8);
      return strictCall(PgType.FLOAT8, a, v -> {
        if ((Double) v[0] < 0.0)
          throw new PostgreSQLEvaluationException("2201F", "cannot take square root of a negative number");
        return Math.sqrt((Double) v[0]);
      });
    }
    case "now":
    case "transaction_timestamp":
      if (args.length != 0)
        throw PostgreSQLEvaluationException.noFunction(name, types(args));
      return currentTimestamp(Casts.MAX_TIMESTAMP_PRECISION);
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
    // the clock is read once for a transaction, so every row of the statement has the same value (fork issues #222 and #190)
    case CURRENT_DATE: {
      final LocalDate today = localTime("CURRENT_DATE").toLocalDate();
      return new Evaluable.Node(PgType.DATE, row -> today);
    }
    case CURRENT_TIMESTAMP:
      return currentTimestamp(precision(call));
    case LOCALTIMESTAMP: {
      final LocalDateTime now = Casts.roundTimestamp(localTime("LOCALTIMESTAMP"), precision(call));
      return new Evaluable.Node(PgType.TIMESTAMP, row -> now);
    }
    default:
      throw PostgreSQLEvaluationException.unsupported(call.kind().name() + " is not supported by the postgresql language yet");
    }
  }

  /** The digits of a second that {@code CURRENT_TIMESTAMP(p)} and {@code LOCALTIMESTAMP(p)} keep. */
  private int precision(final Expression.SpecialFunction call) {
    return call.args().isEmpty() ? Casts.MAX_TIMESTAMP_PRECISION
        : Math.min(integerModifier(call.args().get(0)), Casts.MAX_TIMESTAMP_PRECISION);
  }

  /** The time of the transaction as a time of the session (fork issue #237), which {@code reader} reads. */
  private LocalDateTime localTime(final String reader) {
    return LocalDateTime.ofInstant(parameters.time(), SessionTimeZone.zoneOf(reader));
  }

  /**
   * {@code now()} and its other names: the time of the transaction as a {@code timestamptz}, refused in a session in another
   * time zone than UTC, as every instant is.
   */
  private Evaluable currentTimestamp(final int precision) {
    final OffsetDateTime now = Casts.instant(
        Casts.roundTimestamp(LocalDateTime.ofInstant(parameters.time(), ZoneOffset.UTC), precision));
    return new Evaluable.Node(PgType.TIMESTAMPTZ, row -> now);
  }
}
