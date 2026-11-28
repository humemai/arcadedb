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

import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.schema.CheckConstraint;
import com.arcadedb.schema.DocumentType;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The text of a check constraint as PostgreSQL 18 writes it back (fork issue #297): {@code pg_get_constraintdef},
 * {@code pg_get_expr} and {@code information_schema.check_constraints}. PostgreSQL writes the expression as it analysed it, not
 * the text it was given: each conversion it added is there, {@code BETWEEN} is two comparisons and {@code IN} is {@code = ANY}.
 * This class writes the shapes it knows to be written that way. Every other expression is given as it was declared, without
 * the casts and parentheses that PostgreSQL adds ({@code docs/postgresql-differences.md}), so that no statement that reads
 * the schema fails on a check.
 */
public final class CheckClause {
  /**
   * A check constraint that the records of a type have to meet.
   *
   * @param declaring the type that declares it, the type itself or one of its supertypes
   * @param columns   the names of the columns it reads, in the order of their first use; empty when it cannot be checked
   */
  public record Declared(String name, DocumentType declaring, String expression, List<String> columns) {
  }

  /** How a part of an expression binds, which decides the parentheses around it. */
  private enum Shape {
    WORD, ARRAY, CALL, CONVERSION, OPERATOR, NEGATION, AND, OR, NOT, NULL_TEST, ANY
  }

  /** A part of the analysed expression: {@code text} is a whole word, an operator, a function name or the name of a type. */
  private record Part(Shape shape, PgType type, String text, List<Part> parts) {
  }

  private static final Pattern DECIMAL   = Pattern.compile("[0-9]+\\.[0-9]+");
  private static final Pattern DATE      = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Pattern TIMESTAMP = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}");
  private static final Pattern UUID      = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** Words that {@code quote_ident} puts in double quotes although a statement may name a table with them. */
  private static final Set<String> COLUMN_KEYWORDS = Set.of("between", "bigint", "bit", "boolean", "char", "character",
      "coalesce", "dec", "decimal", "exists", "extract", "float", "greatest", "grouping", "inout", "int", "integer", "interval",
      "json", "json_array", "json_arrayagg", "json_exists", "json_object", "json_objectagg", "json_query", "json_scalar",
      "json_serialize", "json_table", "json_value", "least", "merge_action", "national", "nchar", "none", "normalize", "nullif",
      "numeric", "out", "overlay", "position", "precision", "real", "row", "setof", "smallint", "substring", "time", "timestamp",
      "treat", "trim", "values", "varchar", "xmlattributes", "xmlconcat", "xmlelement", "xmlexists", "xmlforest",
      "xmlnamespaces", "xmlparse", "xmlpi", "xmlroot", "xmlserialize", "xmltable", "is");

  private static final Set<String> TEXT_FUNCTIONS = Set.of("length", "char_length", "character_length", "octet_length", "lower",
      "upper");
  private static final Set<String> TRIM_FUNCTIONS = Set.of("btrim", "ltrim", "rtrim");

  private final Function<String, Column> columns;

  private CheckClause(final Function<String, Column> columns) {
    this.columns = columns;
  }

  /** The check constraints of a type, its own and those it inherits. */
  public static List<Declared> declaredOn(final DocumentType type) {
    final List<Declared> out = new ArrayList<>();
    collect(type, type, new HashSet<>(), out);
    return out;
  }

  private static void collect(final DocumentType type, final DocumentType declaring, final Set<String> seen,
      final List<Declared> out) {
    if (!seen.add(declaring.getName()))
      return;
    final Object value = declaring.getCustomValue(CheckConstraint.CUSTOM_KEY);
    if (value != null)
      try {
        for (final CheckConstraint check : CheckConstraint.fromJson(value.toString())) {
          List<String> read = List.of();
          try {
            read = CheckExpression.of(declaring, type, check.expression()).columnNames();
          } catch (final RuntimeException broken) {
            // a constraint that cannot be checked is listed without its columns: the writes of its type say why
          }
          out.add(new Declared(check.name(), declaring, check.expression(), read));
        }
      } catch (final RuntimeException unreadable) {
        // someone changed the value by hand: the writes of the type say so
      }
    for (final DocumentType superType : declaring.getSuperTypes())
      collect(type, superType, seen, out);
  }

  /**
   * The expression of a check constraint of {@code type} as PostgreSQL writes it. An expression with a shape that this class
   * does not write, and one that cannot be checked, is given as it was declared.
   *
   * @param pretty true for the form of {@code pg_get_constraintdef(oid, true)}, which keeps only the parentheses it needs
   */
  public static String of(final Declared check, final DocumentType type, final boolean pretty) {
    try {
      final CheckClause clause = new CheckClause(CheckExpression.declared(check.declaring(), type));
      // a check that cannot be checked has no analysed form
      CheckExpression.of(check.declaring(), type, check.expression());
      final SelectStatement select = (SelectStatement) StatementParser.parse("SELECT " + check.expression());
      return write(clause.part(((SelectItem.Projection) select.items().get(0)).expression()), pretty);
    } catch (final RuntimeException notWritten) {
      return check.expression();
    }
  }

  // ---------------------------------------------------------------- writing

  private static String write(final Part part, final boolean pretty) {
    final List<Part> parts = part.parts();
    return switch (part.shape()) {
      case WORD -> part.text();
      case ARRAY -> "ARRAY[" + list(parts, pretty) + "]";
      case CALL -> part.text() + "(" + list(parts, pretty) + ")";
      case CONVERSION -> (pretty ? operand(parts.get(0), part, true) : "(" + write(parts.get(0), false) + ")") + "::" + part.text();
      case OPERATOR -> wrap(
          operand(parts.get(0), part, pretty) + " " + part.text() + " " + operand(parts.get(1), part, pretty), pretty);
      case NEGATION, NOT -> wrap(part.text() + " " + operand(parts.get(0), part, pretty), pretty);
      case AND, OR -> wrap(parts.stream().map(each -> operand(each, part, pretty))
          .collect(Collectors.joining(" " + part.text() + " ")), pretty);
      case NULL_TEST -> wrap(operand(parts.get(0), part, pretty) + " " + part.text(), pretty);
      case ANY -> wrap(
          operand(parts.get(0), part, pretty) + " " + part.text() + " (" + operand(parts.get(1), part, pretty) + ")", pretty);
    };
  }

  private static String list(final List<Part> parts, final boolean pretty) {
    return parts.stream().map(each -> write(each, pretty)).collect(Collectors.joining(", "));
  }

  private static String wrap(final String text, final boolean pretty) {
    return pretty ? text : "(" + text + ")";
  }

  private static String operand(final Part part, final Part parent, final boolean pretty) {
    return pretty && !simple(part, parent) ? "(" + write(part, true) + ")" : write(part, pretty);
  }

  /** {@code isSimpleNode} of PostgreSQL's {@code ruleutils.c}: whether the pretty form needs no parentheses around a part. */
  private static boolean simple(final Part part, final Part parent) {
    final boolean boolParent = parent.shape() == Shape.AND || parent.shape() == Shape.OR || parent.shape() == Shape.NOT;
    return switch (part.shape()) {
      case WORD, ARRAY, CALL, CONVERSION -> true;
      case OPERATOR, NEGATION -> parent.shape() == Shape.OPERATOR || parent.shape() == Shape.NEGATION ?
          bindsTighter(part, parent) :
          boolParent || parent.shape() == Shape.CALL || parent.shape() == Shape.ARRAY;
      case NULL_TEST -> boolParent || parent.shape() == Shape.CALL || parent.shape() == Shape.ARRAY;
      case AND, NOT -> parent.shape() == Shape.AND || parent.shape() == Shape.OR || parent.shape() == Shape.CALL;
      case OR -> parent.shape() == Shape.OR || parent.shape() == Shape.CALL;
      case ANY -> false;
    };
  }

  private static boolean bindsTighter(final Part part, final Part parent) {
    if (part.shape() != Shape.OPERATOR || parent.shape() != Shape.OPERATOR)
      return false;
    final int own = priority(part.text());
    final int outer = priority(parent.text());
    if (own == 0 || outer == 0 || own < outer)
      return false;
    return own > outer || parent.parts().get(0) == part;
  }

  private static int priority(final String operator) {
    return switch (operator) {
      case "+", "-" -> 1;
      case "*", "/", "%" -> 2;
      default -> 0;
    };
  }

  // ---------------------------------------------------------------- analysing

  private Part part(final Expression node) {
    return switch (node) {
      case Expression.ColumnRef reference -> column(reference.column().value());
      case Expression.IntegerLiteral literal -> integer(literal.value());
      case Expression.DecimalLiteral literal -> decimal(literal.value().toPlainString());
      case Expression.BooleanLiteral literal -> word(PgType.BOOL, Boolean.toString(literal.value()));
      case Expression.UnaryOp unary -> unary(unary);
      case Expression.BinaryOp binary -> binary(binary);
      case Expression.IsNull test -> new Part(Shape.NULL_TEST, PgType.BOOL, test.negated() ? "IS NOT NULL" : "IS NULL",
          List.of(part(test.operand())));
      case Expression.Between between -> {
        final Part low = comparison(between.negated() ? "<" : ">=", between.operand(), between.low());
        final Part high = comparison(between.negated() ? ">" : "<=", between.operand(), between.high());
        yield new Part(between.negated() ? Shape.OR : Shape.AND, PgType.BOOL, between.negated() ? "OR" : "AND", List.of(low, high));
      }
      case Expression.InList in -> in(in);
      case Expression.Like like -> {
        if (like.escape() != null)
          throw refused();
        final String operator = (like.negated() ? "!" : "") + (like.ignoreCase() ? "~~*" : "~~");
        yield operator(PgType.BOOL, operator, text(like.operand()), text(like.pattern()));
      }
      case Expression.FunctionCall call -> call(call);
      case Expression.SpecialFunction special -> special(special);
      default -> throw refused();
    };
  }

  private Part column(final String name) {
    final Column column = columns.apply(name);
    if (column == null)
      throw refused();
    final String written = COLUMN_KEYWORDS.contains(name) ? Identifier.quoted(name).toString() : Identifier.written(name);
    return word(PgType.of(column.type()), written);
  }

  private static Part word(final PgType type, final String text) {
    return new Part(Shape.WORD, type, text, List.of());
  }

  private Part integer(final BigInteger value) {
    if (value.bitLength() < 32)
      return value.signum() < 0 ? word(PgType.INT4, "'" + value + "'::integer") : word(PgType.INT4, value.toString());
    if (value.bitLength() < 64)
      return word(PgType.INT8, "'" + value + "'::bigint");
    throw refused();
  }

  private Part decimal(final String value) {
    if (value.startsWith("-") && DECIMAL.matcher(value.substring(1)).matches())
      return word(PgType.NUMERIC, "'" + value + "'::numeric");
    if (!DECIMAL.matcher(value).matches())
      throw refused();
    return word(PgType.NUMERIC, value);
  }

  private Part unary(final Expression.UnaryOp unary) {
    if (unary.operator() == Expression.UnaryOperator.NOT)
      return new Part(Shape.NOT, PgType.BOOL, "NOT", List.of(part(unary.operand())));
    if (unary.operator() != Expression.UnaryOperator.MINUS)
      throw refused();
    // a minus in front of a number is part of the number
    if (unary.operand() instanceof Expression.IntegerLiteral literal)
      return integer(literal.value().negate());
    if (unary.operand() instanceof Expression.DecimalLiteral literal)
      return decimal(literal.value().negate().toPlainString());
    final Part operand = part(unary.operand());
    if (!isNumber(operand.type()))
      throw refused();
    return new Part(Shape.NEGATION, operand.type(), "-", List.of(operand));
  }

  private Part binary(final Expression.BinaryOp binary) {
    final Expression.BinaryOperator operator = binary.operator();
    return switch (operator) {
      case AND, OR -> {
        final List<Part> operands = new ArrayList<>();
        chain(binary, operator, operands);
        yield new Part(operator == Expression.BinaryOperator.AND ? Shape.AND : Shape.OR, PgType.BOOL, operator.symbol(), operands);
      }
      case EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL ->
          comparison(operator == Expression.BinaryOperator.NOT_EQUAL ? "<>" : operator.symbol(), binary.left(), binary.right());
      case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO -> arithmetic(operator.symbol(), part(binary.left()), part(binary.right()));
      case CONCAT -> {
        if (binary.left() instanceof Expression.StringLiteral && binary.right() instanceof Expression.StringLiteral)
          throw refused();
        yield operator(PgType.TEXT, operator.symbol(), text(binary.left()), text(binary.right()));
      }
      case JSON_FIELD, JSON_FIELD_TEXT -> {
        final Part left = part(binary.left());
        if (left.type() != PgType.JSONB)
          throw refused();
        final Part key = binary.right() instanceof Expression.IntegerLiteral literal ? integer(literal.value()) : text(binary.right());
        yield operator(operator == Expression.BinaryOperator.JSON_FIELD ? PgType.JSONB : PgType.TEXT, operator.symbol(), left, key);
      }
      default -> throw refused();
    };
  }

  /** {@code a AND b AND c} is one list for PostgreSQL's grammar, along its left side only. */
  private void chain(final Expression node, final Expression.BinaryOperator operator, final List<Part> operands) {
    if (node instanceof Expression.BinaryOp binary && binary.operator() == operator) {
      chain(binary.left(), operator, operands);
      operands.add(part(binary.right()));
    } else
      operands.add(part(node));
  }

  private static Part operator(final PgType type, final String symbol, final Part left, final Part right) {
    return new Part(Shape.OPERATOR, type, symbol, List.of(left, right));
  }

  private Part comparison(final String operator, final Expression left, final Expression right) {
    Part first;
    Part second;
    if (left instanceof Expression.StringLiteral literal) {
      second = part(right);
      first = literal(literal, comparedAs(second.type()));
      second = convert(second, first.type() == PgType.TEXT ? PgType.TEXT : second.type());
    } else if (right instanceof Expression.StringLiteral literal) {
      first = part(left);
      second = literal(literal, comparedAs(first.type()));
      first = convert(first, second.type() == PgType.TEXT ? PgType.TEXT : first.type());
    } else {
      first = part(left);
      second = part(right);
      if (isNumber(first.type()) && isNumber(second.type())) {
        final Part[] both = numbers(first, second);
        first = both[0];
        second = both[1];
      } else if (isText(first.type()) && isText(second.type())) {
        first = convert(first, PgType.TEXT);
        second = convert(second, PgType.TEXT);
      } else if (first.type() != second.type() || !Set.of(PgType.BOOL, PgType.DATE, PgType.TIMESTAMP, PgType.TIMESTAMPTZ,
          PgType.UUID, PgType.JSONB).contains(first.type()))
        throw refused();
    }
    return operator(PgType.BOOL, operator, first, second);
  }

  /** The type a quoted constant takes beside a value of {@code type}: PostgreSQL compares a {@code varchar} as {@code text}. */
  private static PgType comparedAs(final PgType type) {
    return type == PgType.VARCHAR ? PgType.TEXT : type;
  }

  /** Two numbers as the operator that PostgreSQL picks takes them: the integer types mix, and so do the two floating types. */
  private Part[] numbers(final Part first, final Part second) {
    if (isInteger(first.type()) && isInteger(second.type()) || isFloat(first.type()) && isFloat(second.type()))
      return new Part[] { first, second };
    final PgType common = isFloat(first.type()) || isFloat(second.type()) ? PgType.FLOAT8 : PgType.NUMERIC;
    return new Part[] { isFloat(first.type()) ? first : convert(first, common),
        isFloat(second.type()) ? second : convert(second, common) };
  }

  private Part arithmetic(final String operator, final Part left, final Part right) {
    if (!isNumber(left.type()) || !isNumber(right.type()))
      throw refused();
    Part[] both = numbers(left, right);
    if ("%".equals(operator)) {
      if (isFloat(both[0].type()) || isFloat(both[1].type()))
        throw refused();
      // there is no remainder of two different integer types
      final PgType wider = wider(both[0].type(), both[1].type());
      both = new Part[] { convert(both[0], wider), convert(both[1], wider) };
    }
    final PgType type = isInteger(both[0].type()) ? wider(both[0].type(), both[1].type()) :
        both[0].type() == both[1].type() ? both[0].type() : PgType.FLOAT8;
    return operator(type, operator, both[0], both[1]);
  }

  private static PgType wider(final PgType first, final PgType second) {
    if (first == second)
      return first;
    if (first == PgType.INT8 || second == PgType.INT8)
      return PgType.INT8;
    return PgType.INT4;
  }

  private Part in(final Expression.InList in) {
    if (in.values().size() == 1)
      return comparison(in.negated() ? "<>" : "=", in.operand(), in.values().get(0));
    Part operand = part(in.operand());
    PgType element = operand.type();
    final List<Part> values = new ArrayList<>(in.values().size());
    if (isInteger(operand.type()) || operand.type() == PgType.NUMERIC) {
      for (final Expression value : in.values()) {
        if (!isNumberLiteral(value))
          throw refused();
        values.add(part(value));
      }
      PgType common = operand.type() == PgType.INT2 ? PgType.INT4 : operand.type();
      for (final Part value : values)
        if (value.type() == PgType.NUMERIC)
          common = PgType.NUMERIC;
        else if (value.type() == PgType.INT8 && common != PgType.NUMERIC)
          common = PgType.INT8;
      for (int i = 0; i < values.size(); i++)
        values.set(i, convert(values.get(i), common));
      element = common;
      if (common == PgType.NUMERIC)
        operand = convert(operand, common);
    } else {
      for (final Expression value : in.values()) {
        if (!(value instanceof Expression.StringLiteral literal))
          throw refused();
        values.add(literal(literal, element));
      }
    }
    Part array = new Part(Shape.ARRAY, PgType.ARRAY, "", values);
    if (element == PgType.VARCHAR) {
      operand = convert(operand, PgType.TEXT);
      array = new Part(Shape.CONVERSION, PgType.ARRAY, "text[]", List.of(array));
    }
    return new Part(Shape.ANY, PgType.BOOL, in.negated() ? "<> ALL" : "= ANY", List.of(operand, array));
  }

  private Part call(final Expression.FunctionCall call) {
    if (call.name().size() != 1 || call.distinct() || call.star() || call.over() != null || call.filter() != null
        || !call.orderBy().isEmpty())
      throw refused();
    final String name = call.name().get(0).value().toLowerCase(Locale.ROOT);
    final List<Expression> args = call.args();
    if (TEXT_FUNCTIONS.contains(name) && args.size() == 1)
      return new Part(Shape.CALL, name.endsWith("length") ? PgType.INT4 : PgType.TEXT, name, List.of(text(args.get(0))));
    if (TRIM_FUNCTIONS.contains(name) && (args.size() == 1 || args.size() == 2))
      return new Part(Shape.CALL, PgType.TEXT, name, args.stream().map(this::text).toList());
    if (args.size() == 1) {
      final Part argument = part(args.get(0));
      if ("abs".equals(name) && isNumber(argument.type()))
        return new Part(Shape.CALL, argument.type(), name, List.of(argument));
      if (argument.type() == PgType.JSONB && ("jsonb_typeof".equals(name) || "jsonb_array_length".equals(name)))
        return new Part(Shape.CALL, "jsonb_typeof".equals(name) ? PgType.TEXT : PgType.INT4, name, List.of(argument));
    }
    throw refused();
  }

  /** {@code COALESCE}, {@code GREATEST} and {@code LEAST} over values of one type, and constants that take it. */
  private Part special(final Expression.SpecialFunction special) {
    switch (special.kind()) {
    case COALESCE, GREATEST, LEAST -> {
    }
    default -> throw refused();
    }
    PgType type = null;
    for (final Expression argument : special.args())
      if (!(argument instanceof Expression.StringLiteral) && !isNumberLiteral(argument)) {
        final PgType own = part(argument).type();
        if (type != null && type != own)
          throw refused();
        type = own;
      }
    if (type == null)
      throw refused();
    final List<Part> parts = new ArrayList<>(special.args().size());
    for (final Expression argument : special.args())
      if (argument instanceof Expression.StringLiteral literal)
        parts.add(literal(literal, type));
      else if (isNumberLiteral(argument)) {
        final Part value = part(argument);
        final boolean takes = switch (type) {
          case INT4 -> value.type() == PgType.INT4;
          case INT8 -> isInteger(value.type());
          case NUMERIC, FLOAT8 -> true;
          default -> false;
        };
        if (!takes)
          throw refused();
        parts.add(convert(value, type));
      } else
        parts.add(part(argument));
    return new Part(Shape.CALL, type, special.kind().name(), parts);
  }

  /** A value of a text function or operator: a text, a {@code varchar} as one, or a quoted constant. */
  private Part text(final Expression node) {
    if (node instanceof Expression.StringLiteral literal)
      return literal(literal, PgType.TEXT);
    final Part part = part(node);
    if (!isText(part.type()))
      throw refused();
    return convert(part, PgType.TEXT);
  }

  /** A quoted constant of a type, for the types whose constants are written back as they were given. */
  private Part literal(final Expression.StringLiteral literal, final PgType type) {
    final String value = literal.value();
    final boolean asGiven = switch (type) {
      case TEXT, VARCHAR -> value.indexOf('\\') < 0;
      case DATE -> DATE.matcher(value).matches() && parses(() -> LocalDate.parse(value));
      case TIMESTAMP -> TIMESTAMP.matcher(value).matches() && parses(() -> LocalDateTime.parse(value.replace(' ', 'T')));
      case UUID -> UUID.matcher(value).matches();
      default -> false;
    };
    if (!asGiven)
      throw refused();
    return word(type, "'" + value.replace("'", "''") + "'::" + typeName(type));
  }

  private static boolean parses(final Runnable parse) {
    try {
      parse.run();
      return true;
    } catch (final DateTimeParseException e) {
      return false;
    }
  }

  private Part convert(final Part part, final PgType to) {
    if (part.type() == to)
      return part;
    return new Part(Shape.CONVERSION, to, typeName(to), List.of(part));
  }

  private String typeName(final PgType type) {
    return switch (type) {
      case BOOL -> "boolean";
      case INT2 -> "smallint";
      case INT4 -> "integer";
      case INT8 -> "bigint";
      case NUMERIC -> "numeric";
      case FLOAT4 -> "real";
      case FLOAT8 -> "double precision";
      case TEXT -> "text";
      case VARCHAR -> "character varying";
      case DATE -> "date";
      case TIMESTAMP -> "timestamp without time zone";
      case TIMESTAMPTZ -> "timestamp with time zone";
      case UUID -> "uuid";
      case JSONB -> "jsonb";
      default -> throw refused();
    };
  }

  private static boolean isNumberLiteral(final Expression node) {
    final Expression value =
        node instanceof Expression.UnaryOp unary && unary.operator() == Expression.UnaryOperator.MINUS ? unary.operand() : node;
    return value instanceof Expression.IntegerLiteral || value instanceof Expression.DecimalLiteral;
  }

  private static boolean isInteger(final PgType type) {
    return type == PgType.INT2 || type == PgType.INT4 || type == PgType.INT8;
  }

  private static boolean isFloat(final PgType type) {
    return type == PgType.FLOAT4 || type == PgType.FLOAT8;
  }

  private static boolean isNumber(final PgType type) {
    return isInteger(type) || isFloat(type) || type == PgType.NUMERIC;
  }

  private static boolean isText(final PgType type) {
    return type == PgType.TEXT || type == PgType.VARCHAR;
  }

  /** Ends the writing of an expression that {@link #of} then gives as it was declared. */
  private static PostgreSQLFeatureNotSupportedException refused() {
    return new PostgreSQLFeatureNotSupportedException("The expression of the CHECK is not written as PostgreSQL writes it");
  }
}
