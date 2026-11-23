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

import com.arcadedb.query.postgresql.ast.EmptyStatement;
import com.arcadedb.query.postgresql.ast.ExplainStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Expression.BinaryOperator;
import com.arcadedb.query.postgresql.ast.Expression.SpecialFunctionKind;
import com.arcadedb.query.postgresql.ast.Expression.UnaryOperator;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SortItem;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.ast.TypeName;
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
      return select(command.selectStmt());
    if (stmt instanceof ExplainCommandContext command)
      return new ExplainStatement(position(command.getStart()), select(command.explainStmt().selectStmt()));
    if (stmt instanceof EmptyCommandContext)
      return new EmptyStatement(position(stmt.getStart()));
    throw new IllegalStateException("A statement the parser did not refuse has no tree: " + stmt.getText());
  }

  // ------------------------------------------------------------------------------------------------------ SELECT

  private SelectStatement select(final SelectStmtContext context) {
    if (context == null)
      throw new IllegalStateException("EXPLAIN without a SELECT should have been refused");

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

    return new SelectStatement(position(start), distinct, items, from, where, groupBy, having, orderBy, limit, offset);
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
      final List<Name> parts = new ArrayList<>();
      parts.add(name(relation.qualifiedName().first));
      for (final AttrNameContext part : relation.qualifiedName().rest)
        parts.add(name(part));
      if (parts.size() == 3)
        throw Diagnostics.notSupported("A cross-database table name", relation.getStart());
      if (parts.size() > 3)
        throw Diagnostics.syntaxError("improper qualified name (too many dotted names)", relation.getStart());
      final Name alias = table.aliasClause() == null ? null : name(table.aliasClause().alias);
      return new TableReference.Table(position(context.getStart()), parts, has(relation, PostgreSQLParser.ONLY), alias);
    }
    if (context instanceof JoinRefContext join) {
      final TableReference left = tableReference(join.tableRef(0));
      final TableReference right = tableReference(join.tableRef(1));
      final TableReference.JoinType type = join.joinKind() != null && has(join.joinKind(), PostgreSQLParser.LEFT)
          ? TableReference.JoinType.LEFT : TableReference.JoinType.INNER;
      return new TableReference.Join(position(context.getStart()), left, type, right, expression(join.joinQual().aExpr()));
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
    if (context instanceof BetweenExprContext e)
      return new Expression.Between(position(e.getStart()), expression(e.aExpr(0)), bExpression(e.low), expression(e.high),
          has(e, PostgreSQLParser.NOT));
    if (context instanceof LikeEscapeExprContext e)
      return new Expression.Like(position(e.getStart()), expression(e.aExpr(0)), expression(e.pattern), expression(e.escape),
          has(e, PostgreSQLParser.NOT));
    if (context instanceof LikeExprContext e)
      return new Expression.Like(position(e.getStart()), expression(e.aExpr(0)), expression(e.pattern), null,
          has(e, PostgreSQLParser.NOT));
    if (context instanceof ComparisonExprContext e) {
      // the comparison operators do not associate: a < b < c is a syntax error in PostgreSQL
      if (e.l instanceof ComparisonExprContext)
        throw Diagnostics.syntaxError(e.op);
      return binary(comparison(e.op), e.op, expression(e.l), expression(e.r));
    }
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
    if (context instanceof ParenExprContext e)
      return expression(e.aExpr());
    if (context instanceof CaseExprAltContext e)
      return caseExpression(e.caseExpr());
    if (context instanceof CastExprContext e)
      return new Expression.Cast(position(e.getStart()), expression(e.aExpr()), typeName(e.typename()));
    if (context instanceof FunctionCallExprContext e)
      return functionCall(e.funcApplication());
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
   * separated by whitespace with a newline are joined.
   */
  private static String decodeQuoted(final String raw, final int from, final boolean escapes, final Token token) {
    final StringBuilder value = new StringBuilder();
    int i = from;
    final int n = raw.length();
    while (i < n) {
      // raw.charAt(i) is the opening quote of a piece
      i++;
      while (i < n) {
        final char c = raw.charAt(i);
        if (c == '\'') {
          if (i + 1 < n && raw.charAt(i + 1) == '\'') {
            value.append('\'');
            i += 2;
          } else {
            i++;
            break;
          }
        } else if (escapes && c == '\\' && i + 1 < n) {
          i = escape(raw, i + 1, value, token);
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
    return value.toString();
  }

  /** The escape after a backslash in an {@code E} string; returns the index after it. */
  private static int escape(final String raw, final int at, final StringBuilder value, final Token token) {
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
    case 'x':
      return digits(raw, at + 1, 16, 2, 1, value, token);
    case 'u':
      return digits(raw, at + 1, 16, 4, 4, value, token);
    case 'U':
      return digits(raw, at + 1, 16, 8, 8, value, token);
    default:
      if (c >= '0' && c <= '7')
        return digits(raw, at, 8, 3, 1, value, token);
      value.append(c);
      return at + 1;
    }
  }

  private static int digits(final String raw, final int from, final int radix, final int max, final int min,
      final StringBuilder value, final Token token) {
    int end = from;
    while (end < raw.length() && end - from < max && Character.digit(raw.charAt(end), radix) >= 0)
      end++;
    if (end - from < min)
      throw Diagnostics.syntaxError("invalid escape sequence in the string", token);
    final int codePoint = Integer.parseInt(raw.substring(from, end), radix);
    if (!Character.isValidCodePoint(codePoint))
      throw Diagnostics.syntaxError("invalid Unicode escape value", token);
    value.appendCodePoint(codePoint);
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
