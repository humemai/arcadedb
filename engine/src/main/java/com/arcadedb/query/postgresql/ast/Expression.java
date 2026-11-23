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
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * A value expression: {@code a_expr} of {@code gram.y} with {@code b_expr} and {@code c_expr}, which differ only in how they
 * group. The parser has already applied the precedence and the associativity of PostgreSQL (the table of section 4.1.6 of the
 * manual), so the tree is the grouping: {@code a + b * c} is an {@link BinaryOp} of {@code +} over a {@link BinaryOp} of
 * {@code *}, and parentheses leave no node.
 *
 * <p>The nodes below are the whole subset. A construct of PostgreSQL that is not here (a subquery, {@code ROW}, an array, a
 * window function, {@code ILIKE}, {@code SIMILAR TO}, {@code IS DISTINCT FROM}, {@code IS TRUE}, an operator the subset does not
 * name, and the others the parser lists) is refused with SQLSTATE 0A000 before a tree exists.
 */
public sealed interface Expression extends Node {

  /**
   * An integer constant without a decimal point or exponent: {@code Iconst} of {@code gram.y}, and the {@code FCONST} that
   * PostgreSQL's scanner returns for an integer that does not fit in 32 bits. The type is not decided here: the evaluator gives
   * it the type PostgreSQL gives such a constant, {@code int4} if the value fits, otherwise {@code int8} if it fits, otherwise
   * {@code numeric}. A minus sign directly in front of a constant is folded into it, as {@code doNegate} does in
   * {@code gram.y}, so {@code -5} is one literal of value -5 and {@code -(5)} is too.
   */
  record IntegerLiteral(SourcePosition position, BigInteger value) implements Expression {
    public IntegerLiteral {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * A numeric constant with a decimal point or an exponent, such as {@code 1.5}, {@code .5} or {@code 1e3}: {@code FCONST} of
   * {@code gram.y}. Its type is {@code numeric}. The scale of the text is kept in the value ({@code 1.50} has scale 2).
   */
  record DecimalLiteral(SourcePosition position, BigDecimal value) implements Expression {
    public DecimalLiteral {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * A string constant: {@code Sconst} of {@code gram.y}, after the scanner has removed the quotes, read {@code ''} as one quote,
   * joined the pieces that are separated by a newline, and, for an {@code E'...'} constant, read the backslash escapes. A string
   * constant has the type {@code unknown} until the context decides, so a {@link Cast} of it is how {@code DATE '1995-03-15'}
   * is written.
   */
  record StringLiteral(SourcePosition position, String value) implements Expression {
    public StringLiteral {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(value, "value");
    }
  }

  /** {@code TRUE} or {@code FALSE}: the {@code TRUE_P} and {@code FALSE_P} alternatives of {@code AexprConst}. */
  record BooleanLiteral(SourcePosition position, boolean value) implements Expression {
    public BooleanLiteral {
      Objects.requireNonNull(position, "position");
    }
  }

  /** {@code NULL}: the {@code NULL_P} alternative of {@code AexprConst}. */
  record NullLiteral(SourcePosition position) implements Expression {
    public NullLiteral {
      Objects.requireNonNull(position, "position");
    }
  }

  /**
   * A parameter {@code $1}, {@code $2} and so on: {@code PARAM} of {@code gram.y}.
   *
   * @param index the number after the dollar sign, as written; whether it is in range is decided when the parameters are bound
   */
  record ParameterRef(SourcePosition position, int index) implements Expression {
    public ParameterRef {
      Objects.requireNonNull(position, "position");
    }
  }

  /**
   * A column reference with an optional qualifier: {@code columnref} of {@code gram.y} ({@code ColId} or {@code ColId
   * indirection}) without subscripts and without {@code .*}. {@code name}, {@code t.name} and {@code s.t.name} have an empty,
   * one-part and two-part qualifier. What the qualifier names (a table, an alias, a schema and a table) is decided by the binder.
   *
   * @param qualifier the names before the column, possibly empty, at most two
   * @param column    the column name
   */
  record ColumnRef(SourcePosition position, List<Name> qualifier, Name column) implements Expression {
    public ColumnRef {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(column, "column");
      qualifier = List.copyOf(qualifier);
    }
  }

  /** The unary operators of {@code a_expr}: {@code NOT a_expr}, {@code '+' a_expr} and {@code '-' a_expr}. */
  enum UnaryOperator {
    NOT("NOT"), PLUS("+"), MINUS("-");

    private final String symbol;

    UnaryOperator(final String symbol) {
      this.symbol = symbol;
    }

    /** The operator as it is written. */
    public String symbol() {
      return symbol;
    }
  }

  /**
   * A unary operation: the {@code NOT a_expr}, {@code '+' a_expr} and {@code '-' a_expr} alternatives of {@code a_expr}. Minus
   * in front of a constant is not a node but a negative literal (see {@link IntegerLiteral}). The node starts at the operator.
   */
  record UnaryOp(SourcePosition position, UnaryOperator operator, Expression operand) implements Expression {
    public UnaryOp {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(operand, "operand");
    }
  }

  /**
   * The binary operators of the subset. {@code <>} and {@code !=} are one operator, as in PostgreSQL. The three vector
   * operators of pgvector are PostgreSQL operators like any other ({@code Op} tokens in the grammar), so the parser maps their
   * text, it has no grammar rule of its own.
   */
  enum BinaryOperator {
    OR("OR"), AND("AND"),
    EQUAL("="), NOT_EQUAL("<>"), LESS("<"), LESS_OR_EQUAL("<="), GREATER(">"), GREATER_OR_EQUAL(">="),
    CONCAT("||"),
    ADD("+"), SUBTRACT("-"), MULTIPLY("*"), DIVIDE("/"), MODULO("%"), POWER("^"),
    /** pgvector {@code <->}, the Euclidean (L2) distance. */
    L2_DISTANCE("<->"),
    /** pgvector {@code <=>}, the cosine distance. */
    COSINE_DISTANCE("<=>"),
    /** pgvector {@code <#>}, the negative inner product. */
    NEGATIVE_INNER_PRODUCT("<#>");

    private final String symbol;

    BinaryOperator(final String symbol) {
      this.symbol = symbol;
    }

    /** The operator as it is written, {@code <>} for both spellings of not equal. */
    public String symbol() {
      return symbol;
    }
  }

  /**
   * A binary operation: the {@code a_expr AND a_expr}, {@code OR}, comparison, arithmetic, {@code ||} and generic operator
   * ({@code a_expr qual_Op a_expr}) alternatives of {@code a_expr}. The node starts at the operator, where PostgreSQL's
   * {@code A_Expr} has its location, so that an error about the operator points at it; the start of the whole expression is
   * the start of {@code left}.
   */
  record BinaryOp(SourcePosition position, BinaryOperator operator, Expression left, Expression right) implements Expression {
    public BinaryOp {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }
  }

  /**
   * {@code a_expr IS NULL}, {@code a_expr IS NOT NULL}, and the postfix {@code ISNULL} and {@code NOTNULL} that mean the same.
   *
   * @param negated true for {@code IS NOT NULL} and {@code NOTNULL}
   */
  record IsNull(SourcePosition position, Expression operand, boolean negated) implements Expression {
    public IsNull {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operand, "operand");
    }
  }

  /**
   * {@code a_expr [NOT] IN '(' expr_list ')'}: the {@code in_expr} alternative with a list. The form with a subquery is not in
   * the subset.
   *
   * @param values at least one expression
   * @param negated true for {@code NOT IN}
   */
  record InList(SourcePosition position, Expression operand, List<Expression> values, boolean negated) implements Expression {
    public InList {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operand, "operand");
      values = List.copyOf(values);
      if (values.isEmpty())
        throw new IllegalArgumentException("IN needs at least one value");
    }
  }

  /**
   * {@code EXISTS (select)}: true when the query has a row, never {@code NULL}. {@code NOT EXISTS} is {@code NOT} of it. The query
   * may read columns of the query around it, as equalities of its {@code WHERE} clause (a correlated subquery).
   *
   * @param query a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement}
   */
  record Exists(SourcePosition position, Statement query) implements Expression {
    public Exists {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(query, "query");
    }
  }

  /**
   * {@code (select)} used as a value: the one column of the one row of the query, {@code NULL} when it has no row. A query that
   * has more than one row is the error 21000 when it runs.
   *
   * @param query a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement} that has one column
   */
  record ScalarSubquery(SourcePosition position, Statement query) implements Expression {
    public ScalarSubquery {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(query, "query");
    }
  }

  /**
   * {@code a_expr [NOT] IN (select)}: true when the operand equals a row of the subquery, with the three-valued logic of
   * PostgreSQL: {@code NULL} when no row is equal and a comparison was {@code NULL}, false for an empty subquery.
   *
   * @param query   a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement} that has one column
   * @param negated true for {@code NOT IN}
   */
  record InSubquery(SourcePosition position, Expression operand, Statement query, boolean negated) implements Expression {
    public InSubquery {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operand, "operand");
      Objects.requireNonNull(query, "query");
    }
  }

  /**
   * {@code a_expr [NOT] BETWEEN [ASYMMETRIC] b_expr AND a_expr}. {@code BETWEEN SYMMETRIC} is not in the subset.
   *
   * @param negated true for {@code NOT BETWEEN}
   */
  record Between(SourcePosition position, Expression operand, Expression low, Expression high, boolean negated)
      implements Expression {
    public Between {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operand, "operand");
      Objects.requireNonNull(low, "low");
      Objects.requireNonNull(high, "high");
    }
  }

  /**
   * {@code a_expr [NOT] LIKE a_expr [ESCAPE a_expr]}. {@code ILIKE} and {@code SIMILAR TO} are not in the subset.
   *
   * @param escape the escape character expression, or {@code null} for the default
   * @param negated true for {@code NOT LIKE}
   */
  record Like(SourcePosition position, Expression operand, Expression pattern, Expression escape, boolean negated)
      implements Expression {
    public Like {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operand, "operand");
      Objects.requireNonNull(pattern, "pattern");
    }
  }

  /**
   * One {@code WHEN condition THEN result} of a {@link Case}: {@code when_clause} of {@code gram.y}. For the simple form of
   * {@code CASE} the condition is the value to compare with the operand.
   */
  record WhenClause(SourcePosition position, Expression condition, Expression result) implements Node {
    public WhenClause {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(condition, "condition");
      Objects.requireNonNull(result, "result");
    }
  }

  /**
   * {@code CASE [operand] when_clause_list [ELSE expr] END}: {@code case_expr} of {@code gram.y}.
   *
   * @param operand   the compared expression of the simple form {@code CASE x WHEN 1 THEN ...}, or {@code null} for the
   *                  searched form {@code CASE WHEN a THEN ...}
   * @param whens     at least one clause
   * @param otherwise the {@code ELSE} result, or {@code null} for none (which is {@code NULL})
   */
  record Case(SourcePosition position, Expression operand, List<WhenClause> whens, Expression otherwise) implements Expression {
    public Case {
      Objects.requireNonNull(position, "position");
      whens = List.copyOf(whens);
      if (whens.isEmpty())
        throw new IllegalArgumentException("CASE needs at least one WHEN");
    }
  }

  /**
   * A cast: {@code a_expr TYPECAST Typename} ({@code x::t}), {@code CAST '(' a_expr AS Typename ')'}, and the typed constants
   * of {@code AexprConst}, {@code ConstTypename Sconst} ({@code DATE '1995-03-15'}), {@code func_name Sconst} and
   * {@code ConstInterval Sconst opt_interval} ({@code INTERVAL '1' MONTH}), which PostgreSQL also reads as a cast of a string
   * constant. All of them are this one node. The node of {@code x::t} starts at the operand, like the others.
   */
  record Cast(SourcePosition position, Expression operand, TypeName type) implements Expression {
    public Cast {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(operand, "operand");
      Objects.requireNonNull(type, "type");
    }
  }

  /**
   * A function call: {@code func_application} of {@code gram.y} ({@code func_name '(' func_arg_list ')'}, {@code func_name '('
   * '*' ')'} and the {@code DISTINCT} and {@code ALL} forms), which also covers the aggregates {@code count}, {@code sum},
   * {@code avg}, {@code min} and {@code max}: whether the function is an aggregate is the catalog's business, not the
   * grammar's. The calls that SQL writes with keywords instead of commas are rewritten into a call of the function PostgreSQL
   * gives them, with the schema {@code pg_catalog} in the name: {@code EXTRACT(f FROM x)} is {@code pg_catalog.extract('f', x)},
   * {@code SUBSTRING(a FROM b FOR c)} is {@code pg_catalog.substring(a, b, c)}, {@code POSITION(a IN b)} is
   * {@code pg_catalog.position(b, a)}, and {@code TRIM(LEADING|TRAILING|BOTH [c] FROM s)} is {@code pg_catalog.ltrim},
   * {@code pg_catalog.rtrim} or {@code pg_catalog.btrim} of {@code s} and the optional {@code c}.
   *
   * @param name     the function name, one part or schema and name
   * @param args     the arguments, empty for {@code f()} and for {@code count(*)}
   * @param distinct true for {@code f(DISTINCT x)}
   * @param star     true for {@code count(*)}
   */
  record FunctionCall(SourcePosition position, List<Name> name, List<Expression> args, boolean distinct, boolean star)
      implements Expression {
    public FunctionCall {
      Objects.requireNonNull(position, "position");
      name = List.copyOf(name);
      args = List.copyOf(args);
      if (name.isEmpty())
        throw new IllegalArgumentException("A function has a name");
      if (star && (!args.isEmpty() || distinct))
        throw new IllegalArgumentException("f(*) has no arguments and no DISTINCT");
    }
  }

  /** The functions that PostgreSQL's grammar spells as keywords and evaluates in a way no ordinary function can. */
  enum SpecialFunctionKind {
    /** {@code COALESCE(a, b, ...)}: the first argument that is not null; arguments after it are not evaluated. */
    COALESCE,
    /** {@code NULLIF(a, b)}: null when the two are equal, otherwise {@code a}. */
    NULLIF,
    /** {@code GREATEST(a, b, ...)}. */
    GREATEST,
    /** {@code LEAST(a, b, ...)}. */
    LEAST,
    /** {@code CURRENT_DATE}, no arguments. */
    CURRENT_DATE,
    /** {@code CURRENT_TIME} or {@code CURRENT_TIME(p)}. */
    CURRENT_TIME,
    /** {@code CURRENT_TIMESTAMP} or {@code CURRENT_TIMESTAMP(p)}. */
    CURRENT_TIMESTAMP,
    /** {@code LOCALTIME} or {@code LOCALTIME(p)}. */
    LOCALTIME,
    /** {@code LOCALTIMESTAMP} or {@code LOCALTIMESTAMP(p)}. */
    LOCALTIMESTAMP
  }

  /**
   * A function of {@code func_expr_common_subexpr} in {@code gram.y} that is a keyword and not a function name:
   * {@code COALESCE}, {@code NULLIF}, {@code GREATEST} and {@code LEAST} with an argument list, and {@code CURRENT_DATE},
   * {@code CURRENT_TIME}, {@code CURRENT_TIMESTAMP}, {@code LOCALTIME} and {@code LOCALTIMESTAMP}, where the optional precision
   * is the one argument.
   *
   * @param args the arguments: at least one for {@code COALESCE} and {@code GREATEST} and {@code LEAST}, exactly two for
   *             {@code NULLIF}, none for {@code CURRENT_DATE}, at most the precision for the time functions
   */
  record SpecialFunction(SourcePosition position, SpecialFunctionKind kind, List<Expression> args) implements Expression {
    public SpecialFunction {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(kind, "kind");
      args = List.copyOf(args);
    }
  }
}
