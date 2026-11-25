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

import com.arcadedb.query.postgresql.PostgreSQLErrors;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLInvalidEscapeException;
import com.arcadedb.query.postgresql.PostgreSQLSyntaxException;
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.ast.EmptyStatement;
import com.arcadedb.query.postgresql.ast.ExplainStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TableReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser of the {@code postgresql} language: each construct of the subset parses to the tree it should, the operators group
 * as the precedence table of PostgreSQL says, keywords and identifiers follow the scanner of PostgreSQL, and what the subset
 * does not cover is refused with the SQLSTATE and the position PostgreSQL clients expect. The corpus of PostgreSQL's own
 * statements is {@link SyntaxCorpusTest}.
 */
class StatementParserTest {

  private static SelectStatement select(final String text) {
    return (SelectStatement) StatementParser.parse(text);
  }

  /** The select list of a statement as the printer writes it: every operation in parentheses, which shows the grouping. */
  private static String print(final String text) {
    return AstPrinter.print(StatementParser.parse(text));
  }

  private static String grouping(final String expression) {
    return print("SELECT " + expression).substring("SELECT ".length());
  }

  // ------------------------------------------------------------------------------------------------ ? for a parameter

  /** A client or a language model that writes {@code ?}, as JDBC does, is told how this language writes parameters (fork #98). */
  @Test
  void aQuestionMarkWhereAParameterStandsSaysHowParametersAreWritten() {
    for (final String statement : List.of("SELECT name FROM person WHERE pid = ?", "SELECT name FROM person WHERE pid=?",
        "SELECT name FROM person WHERE pid IN (?, ?)", "SELECT name FROM person WHERE age BETWEEN ? AND ?",
        "SELECT name FROM person WHERE name LIKE ?", "SELECT name FROM person LIMIT ?", "SELECT ? FROM person",
        "INSERT INTO person (pid, name) VALUES (?, ?)", "UPDATE person SET age = ? WHERE pid = ?",
        "DELETE FROM person WHERE pid = ?", "EXPLAIN SELECT name FROM person WHERE pid <> ?"))
      assertThatThrownBy(() -> StatementParser.parse(statement)).as(statement).hasMessageContaining("$1, $2")
          .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).isIn("42601", "0A000"));

    final String statement = "SELECT name FROM person WHERE pid = ?";
    assertThatThrownBy(() -> StatementParser.parse(statement)).satisfies(e -> {
      assertThat(PostgreSQLErrors.sqlState(e)).isEqualTo("0A000");
      assertThat(PostgreSQLErrors.position(e, statement)).isEqualTo(statement.indexOf('?') + 1);
    });
  }

  @Test
  void anErrorThatIsNotAboutAQuestionMarkParameterKeepsItsMessage() {
    // a prefix operator, a ? in a string, an error before the ?, and a ? after an expression, where it is an operator
    for (final String statement : List.of("SELECT ~ 5", "SELECT '?' FROM FROM person", "SELECT name FROM FROM person WHERE pid = ?",
        "SELECT name FROM person WHERE address ? 'city' AND )"))
      assertThatThrownBy(() -> StatementParser.parse(statement)).as(statement).message().doesNotContain("$1, $2");
    assertThat(StatementParser.parse("SELECT '?', \"a?\" FROM person")).isNotNull();
  }

  // ------------------------------------------------------------------------------------------------ statements

  @Test
  void theDemoSelectHasOneJoinOfTwoTablesAndTwoSortKeys() {
    final SelectStatement statement = select(
        "SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid ORDER BY p.name, o.oid");

    assertThat(statement.items()).hasSize(2);
    assertThat(statement.from()).hasSize(1);
    assertThat(statement.orderBy()).hasSize(2);
    final TableReference.Join join = (TableReference.Join) statement.from().get(0);
    assertThat(join.type()).isEqualTo(TableReference.JoinType.LEFT);
    assertThat(AstPrinter.print(join.condition())).isEqualTo("(o.pid = p.pid)");
    assertThat(AstPrinter.print(statement)).isEqualTo(
        "SELECT p.name, o.oid FROM person AS p LEFT JOIN orders AS o ON (o.pid = p.pid) ORDER BY p.name, o.oid");
  }

  @Test
  void everyClauseIsKept() {
    assertThat(print(
        "SELECT DISTINCT a, count(*) AS n FROM t WHERE b > 1 GROUP BY a HAVING count(*) > 1 ORDER BY a, 2 DESC NULLS FIRST LIMIT 5 OFFSET 2"))
        .isEqualTo(
            "SELECT DISTINCT a, count(*) AS n FROM t WHERE (b > 1) GROUP BY a HAVING (count(*) > 1) ORDER BY a, 2 DESC NULLS FIRST LIMIT 5 OFFSET 2");
    assertThat(print("SELECT ALL a FROM t LIMIT ALL")).isEqualTo("SELECT a FROM t");
    assertThat(print("SELECT a FROM t OFFSET 3 LIMIT 4")).isEqualTo("SELECT a FROM t LIMIT 4 OFFSET 3");
    assertThat(print("SELECT a FROM t OFFSET 1 ROWS")).isEqualTo("SELECT a FROM t OFFSET 1");
    assertThat(select("SELECT a FROM t").limit()).isNull();
  }

  @Test
  void explainTakesASelect() {
    final Statement statement = StatementParser.parse("EXPLAIN SELECT name FROM person WHERE pid = $1");
    assertThat(statement).isInstanceOf(ExplainStatement.class);
    assertThat(AstPrinter.print(statement)).isEqualTo("EXPLAIN SELECT name FROM person WHERE (pid = $1)");
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "   ", ";", "-- only a comment", "/* a /* b */ c */", "/* x */ ;", "\n\n" })
  void textWithNoStatementIsTheEmptyStatement(final String text) {
    assertThat(StatementParser.parse(text)).isInstanceOf(EmptyStatement.class);
  }

  @ParameterizedTest
  @ValueSource(strings = { "SELECT 1 -- note", "/* a /* b */ c */ SELECT 1", "SELECT 1;", "SELECT 1 ;  -- done", "select 1" })
  void commentsAndTheFinalSemicolonAreOneStatement(final String text) {
    assertThat(print(text)).isEqualTo("SELECT 1");
  }

  @Test
  void aSecondStatementIsASyntaxError() {
    assertThatThrownBy(() -> StatementParser.parse("SELECT 1; SELECT 2")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("syntax error at or near \"SELECT\" (line 1, column 11)");
  }

  // ------------------------------------------------------------------------------------------------ keywords and names

  @Test
  void keywordsAreCaseInsensitive() {
    assertThat(print("sElEcT a fRoM t wHeRe a Is nUlL oRdEr bY a dEsC nUlLs fIrSt lImIt 1 oFfSeT 2")).isEqualTo(
        "SELECT a FROM t WHERE (a IS NULL) ORDER BY a DESC NULLS FIRST LIMIT 1 OFFSET 2");
  }

  @Test
  void unquotedNamesFoldToLowerCaseAndQuotedNamesDoNot() {
    final SelectStatement statement = select("SELECT Foo, \"Foo\", \"a\"\"b\" FROM T");
    assertThat(AstPrinter.print(statement)).isEqualTo("SELECT foo, \"Foo\", \"a\"\"b\" FROM t");
    final Expression.ColumnRef quoted = (Expression.ColumnRef) ((SelectItem.Projection) statement.items().get(2)).expression();
    assertThat(quoted.column().value()).isEqualTo("a\"b");
    assertThat(quoted.column().quoted()).isTrue();
  }

  @Test
  void aReservedWordIsAQuotedNameOrAnAfterDotNameButNotABareOne() {
    assertThat(print("SELECT \"left\", \"join\" FROM \"inner\"")).isEqualTo("SELECT \"left\", \"join\" FROM \"inner\"");
    assertThat(print("SELECT t.select, 1 AS select FROM t")).isEqualTo("SELECT t.select, 1 AS select FROM t");
    assertThat(print("SELECT time, abort FROM t")).isEqualTo("SELECT time, abort FROM t");
    // left can start a function call, so the statement fails at the word after it, as in PostgreSQL
    assertThatThrownBy(() -> StatementParser.parse("SELECT left FROM t")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("syntax error at or near \"FROM\" (line 1, column 13)");
    assertThatThrownBy(() -> StatementParser.parse("SELECT 1 FROM t x y")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("at or near \"y\"");
    assertThat(print("SELECT left('abc', 2), right('abc', 1)")).isEqualTo("SELECT left('abc', 2), right('abc', 1)");
  }

  @Test
  void aliasesWithAndWithoutAs() {
    assertThat(print("SELECT a x, b AS y, c AS \"Z\" FROM t u, v AS w")).isEqualTo(
        "SELECT a AS x, b AS y, c AS \"Z\" FROM t AS u, v AS w");
  }

  @Test
  void anEmptyQuotedIdentifierIsASyntaxError() {
    assertThatThrownBy(() -> StatementParser.parse("SELECT \"\" FROM t")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("zero-length delimited identifier (line 1, column 8)");
  }

  // ------------------------------------------------------------------------------------------------ FROM

  @Test
  void joinsGroupToTheLeft() {
    assertThat(print("SELECT * FROM a JOIN b ON x JOIN c ON y")).isEqualTo("SELECT * FROM a INNER JOIN b ON x INNER JOIN c ON y");
    final TableReference.Join outer = (TableReference.Join) select("SELECT * FROM a JOIN b ON x JOIN c ON y").from().get(0);
    assertThat(outer.left()).isInstanceOf(TableReference.Join.class);
    assertThat(outer.right()).isInstanceOf(TableReference.Table.class);
  }

  @Test
  void aParenthesisedJoinOnTheRightKeepsItsShape() {
    assertThat(print("SELECT * FROM a JOIN (b JOIN c ON y) ON x")).isEqualTo("SELECT * FROM a INNER JOIN (b INNER JOIN c ON y) ON x");
    // a join without its own condition cannot end, so the inner join takes the first ON, as PostgreSQL has it
    assertThat(print("SELECT * FROM a JOIN b JOIN c ON y ON x")).isEqualTo("SELECT * FROM a INNER JOIN (b INNER JOIN c ON y) ON x");
    assertThat(print("SELECT * FROM (a JOIN b ON x)")).isEqualTo("SELECT * FROM a INNER JOIN b ON x");
  }

  @Test
  void aParenthesisedTableIsASyntaxError() {
    assertThatThrownBy(() -> StatementParser.parse("SELECT * FROM (a)")).isInstanceOf(PostgreSQLSyntaxException.class);
  }

  @Test
  void joinKindsAndTableForms() {
    assertThat(print("SELECT * FROM a INNER JOIN b ON x LEFT OUTER JOIN c ON y LEFT JOIN d ON z")).isEqualTo(
        "SELECT * FROM a INNER JOIN b ON x LEFT JOIN c ON y LEFT JOIN d ON z");
    assertThat(print("SELECT * FROM a, b x, ONLY c, ONLY (d), s.t, e *")).isEqualTo(
        "SELECT * FROM a, b AS x, ONLY c, ONLY d, s.t, e");
    assertThat(print("SELECT *, t.*, s.t.* FROM t")).isEqualTo("SELECT *, t.*, s.t.* FROM t");
    assertThat(((TableReference.Table) select("SELECT * FROM ONLY person").from().get(0)).only()).isTrue();
  }

  // ------------------------------------------------------------------------------------------------ precedence

  static Stream<String[]> groupings() {
    return Stream.of(
        new String[] { "a OR b AND c", "(a OR (b AND c))" },
        new String[] { "a AND b OR c", "((a AND b) OR c)" },
        new String[] { "NOT a AND b", "((NOT a) AND b)" },
        new String[] { "NOT a = b", "(NOT (a = b))" },
        new String[] { "NOT a IS NULL", "(NOT (a IS NULL))" },
        new String[] { "a = b AND c = d", "((a = b) AND (c = d))" },
        new String[] { "a = b IS NULL", "((a = b) IS NULL)" },
        new String[] { "a IS NULL AND b", "((a IS NULL) AND b)" },
        new String[] { "a IS NOT NULL", "(a IS NOT NULL)" },
        new String[] { "a + b IS NULL", "((a + b) IS NULL)" },
        new String[] { "a ISNULL", "(a IS NULL)" },
        new String[] { "a NOTNULL", "(a IS NOT NULL)" },
        new String[] { "a + b * c - d", "((a + (b * c)) - d)" },
        new String[] { "a - b - c", "((a - b) - c)" },
        new String[] { "a / b / c", "((a / b) / c)" },
        new String[] { "a % b * c", "((a % b) * c)" },
        new String[] { "(a + b) * c", "((a + b) * c)" },
        new String[] { "2 ^ 3 ^ 2", "((2 ^ 3) ^ 2)" },
        new String[] { "a * b ^ c", "(a * (b ^ c))" },
        new String[] { "-a ^ b", "((- a) ^ b)" },
        new String[] { "-2 ^ 2", "((-2) ^ 2)" },
        new String[] { "a || b || c", "((a || b) || c)" },
        new String[] { "a || b + c", "(a || (b + c))" },
        new String[] { "a + b || c", "((a + b) || c)" },
        new String[] { "a = b || c", "(a = (b || c))" },
        new String[] { "a LIKE b || c", "(a LIKE (b || c))" },
        new String[] { "a BETWEEN 1 AND 2 AND b", "((a BETWEEN 1 AND 2) AND b)" },
        new String[] { "a BETWEEN 1 + 1 AND 2 * 3", "(a BETWEEN (1 + 1) AND (2 * 3))" },
        new String[] { "a NOT BETWEEN 1 AND 2", "(a NOT BETWEEN 1 AND 2)" },
        new String[] { "a IN (1, 2) AND b", "((a IN (1, 2)) AND b)" },
        new String[] { "a NOT IN (1)", "(a NOT IN (1))" },
        new String[] { "a LIKE 'x' AND b", "((a LIKE 'x') AND b)" },
        new String[] { "a NOT LIKE 'x' ESCAPE 'y'", "(a NOT LIKE 'x' ESCAPE 'y')" },
        new String[] { "a::int + 1", "(CAST(a AS \"int4\") + 1)" },
        new String[] { "-a::int", "(- CAST(a AS \"int4\"))" },
        new String[] { "(a = b) = c", "((a = b) = c)" },
        new String[] { "a <> b OR a != b", "((a <> b) OR (a <> b))" },
        new String[] { "a <= b AND a >= b AND a < b AND a > b", "((((a <= b) AND (a >= b)) AND (a < b)) AND (a > b))" });
  }

  @ParameterizedTest
  @MethodSource("groupings")
  void operatorsGroupAsPostgreSQLDoes(final String expression, final String grouped) {
    assertThat(grouping(expression)).isEqualTo(grouped);
  }

  @ParameterizedTest
  @ValueSource(strings = { "a = b = c", "a < b > c", "a < b = c", "a = b <> c" })
  void comparisonsDoNotAssociate(final String expression) {
    assertThatThrownBy(() -> StatementParser.parse("SELECT " + expression)).isInstanceOf(PostgreSQLSyntaxException.class);
  }

  // ------------------------------------------------------------------------------------------------ the scanner

  @Test
  void aRunOfOperatorCharactersIsOneOperatorAndTrailingSignsAreNot() {
    assertThat(grouping("a=-1")).isEqualTo("(a = (-1))");
    assertThat(grouping("a<-1")).isEqualTo("(a < (-1))");
    assertThat(grouping("1+-2")).isEqualTo("(1 + (-2))");
    assertThat(grouping("a<->b")).isEqualTo("(a <-> b)");
    assertThat(grouping("a <=> b")).isEqualTo("(a <=> b)");
    assertThat(grouping("a <#> b")).isEqualTo("(a <#> b)");
    assertThat(grouping("a||b")).isEqualTo("(a || b)");
    assertThat(grouping("x!=y")).isEqualTo("(x <> y)");
    final Expression.BinaryOp cosine = (Expression.BinaryOp) ((SelectItem.Projection) select(
        "SELECT embedding <=> '[1,0,0]' FROM item").items().get(0)).expression();
    assertThat(cosine.operator()).isEqualTo(Expression.BinaryOperator.COSINE_DISTANCE);
    assertThat(print("SELECT iid FROM item ORDER BY embedding <=> '[1,0,0]' LIMIT 3")).isEqualTo(
        "SELECT iid FROM item ORDER BY (embedding <=> '[1,0,0]') LIMIT 3");
  }

  @Test
  void anOperatorTheSubsetDoesNotNameIsRefusedWithItsPosition() {
    assertThatThrownBy(() -> StatementParser.parse("SELECT a @> b")).isInstanceOf(PostgreSQLFeatureNotSupportedException.class)
        .hasMessageContaining("The operator @> is not supported by the postgresql language (line 1, column 10)");
  }

  @Test
  void numbers() {
    assertThat(print("SELECT 1, 1.5, .5, 1e3, 0x1F, 1_000, 2147483648, 12345678901234567890")).isEqualTo(
        "SELECT 1, 1.5, 0.5, 1E+3, 31, 1000, 2147483648, 12345678901234567890");
    // a minus sign in front of a number is folded into it, in parentheses or not, as PostgreSQL does
    assertThat(print("SELECT -5, - 5, -(5), - -5, +5, -1.5")).isEqualTo("SELECT (-5), (-5), (-5), 5, (+ 5), (-1.5)");
    assertThat(((Expression.IntegerLiteral) ((SelectItem.Projection) select("SELECT -2147483648").items().get(0)).expression())
        .value().longValue()).isEqualTo(-2147483648L);
    assertThat(print("SELECT $1, $12")).isEqualTo("SELECT $1, $12");
    assertThatThrownBy(() -> StatementParser.parse("SELECT 123abc")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("at or near \"123abc\" (line 1, column 8)");
  }

  @Test
  void strings() {
    assertThat(stringValue("SELECT 'it''s'")).isEqualTo("it's");
    assertThat(stringValue("SELECT 'a\\b'")).isEqualTo("a\\b");
    assertThat(stringValue("SELECT E'a\\nb\\x41\\u0042\\101\\\\ \\''")).isEqualTo("a\nbABA\\ '");
    assertThat(stringValue("SELECT 'a'\n'b'")).isEqualTo("ab");
    assertThat(stringValue("SELECT 'a' -- remark\n 'b'")).isEqualTo("ab");
    assertThat(stringValue("SELECT $$x'y$$")).isEqualTo("x'y");
    assertThat(stringValue("SELECT $t$a$$b$t$")).isEqualTo("a$$b");
    assertThat(stringValue("SELECT 'héllo wörld'")).isEqualTo("héllo wörld");
    // two strings on one line are not joined
    assertThatThrownBy(() -> StatementParser.parse("SELECT 'a' 'b'")).isInstanceOf(PostgreSQLSyntaxException.class);
  }

  private static String stringValue(final String text) {
    return ((Expression.StringLiteral) ((SelectItem.Projection) select(text).items().get(0)).expression()).value();
  }

  // ------------------------------------------------------------------------------------------------ calls, casts, CASE

  static Stream<String[]> constructs() {
    return Stream.of(
        new String[] { "count(*)", "count(*)" },
        new String[] { "count(DISTINCT x)", "count(DISTINCT x)" },
        new String[] { "sum(a + b)", "sum((a + b))" },
        new String[] { "s.f(1, 2)", "s.f(1, 2)" },
        new String[] { "f()", "f()" },
        new String[] { "extract(year FROM d)", "pg_catalog.extract('year', d)" },
        new String[] { "substring(a FROM 1 FOR 2)", "pg_catalog.substring(a, 1, 2)" },
        new String[] { "substring(a FOR 2 FROM 1)", "pg_catalog.substring(a, 1, 2)" },
        new String[] { "substring(a FROM 1)", "pg_catalog.substring(a, 1)" },
        new String[] { "substring(a FOR 2)", "pg_catalog.substring(a, 1, CAST(2 AS \"int4\"))" },
        new String[] { "substring(a, 1, 2)", "pg_catalog.substring(a, 1, 2)" },
        new String[] { "trim(both 'x' FROM y)", "pg_catalog.btrim(y, 'x')" },
        new String[] { "trim(leading FROM y)", "pg_catalog.ltrim(y)" },
        new String[] { "trim(trailing 'x' FROM y)", "pg_catalog.rtrim(y, 'x')" },
        new String[] { "trim(y)", "pg_catalog.btrim(y)" },
        new String[] { "position('a' IN b)", "pg_catalog.position(b, 'a')" },
        new String[] { "coalesce(a, b, 1)", "COALESCE(a, b, 1)" },
        new String[] { "nullif(a, 1)", "NULLIF(a, 1)" },
        new String[] { "greatest(1, 2)", "GREATEST(1, 2)" },
        new String[] { "least(1, 2)", "LEAST(1, 2)" },
        new String[] { "current_date", "CURRENT_DATE" },
        new String[] { "current_timestamp(3)", "CURRENT_TIMESTAMP(3)" },
        new String[] { "localtime", "LOCALTIME" },
        new String[] { "CASE WHEN a THEN 1 ELSE 2 END", "CASE WHEN a THEN 1 ELSE 2 END" },
        new String[] { "CASE a WHEN 1 THEN 2 WHEN 3 THEN 4 END", "CASE a WHEN 1 THEN 2 WHEN 3 THEN 4 END" },
        new String[] { "x::numeric(10,2)", "CAST(x AS \"numeric\"(10, 2))" },
        new String[] { "CAST(x AS varchar(10))", "CAST(x AS \"varchar\"(10))" },
        new String[] { "x::int[]", "CAST(x AS \"int4\"[])" },
        new String[] { "x::int ARRAY", "CAST(x AS \"int4\"[])" },
        new String[] { "x::double precision", "CAST(x AS \"float8\")" },
        new String[] { "x::timestamp with time zone", "CAST(x AS \"timestamptz\")" },
        new String[] { "x::timestamp(3) without time zone", "CAST(x AS \"timestamp\"(3))" },
        new String[] { "x::character varying", "CAST(x AS \"varchar\")" },
        new String[] { "x::char", "CAST(x AS \"bpchar\"(1))" },
        new String[] { "x::bigint", "CAST(x AS \"int8\")" },
        new String[] { "x::pg_catalog.int4", "CAST(x AS \"pg_catalog\".\"int4\")" },
        new String[] { "x::\"MyType\"", "CAST(x AS \"MyType\")" },
        new String[] { "DATE '1995-03-15'", "CAST('1995-03-15' AS \"date\")" },
        new String[] { "INTERVAL '1' MONTH", "CAST('1' AS INTERVAL month)" },
        new String[] { "INTERVAL '1-2' YEAR TO MONTH", "CAST('1-2' AS INTERVAL year to month)" },
        new String[] { "INTERVAL '1' SECOND(3)", "CAST('1' AS INTERVAL second(3))" },
        new String[] { "INTERVAL '1 day'", "CAST('1 day' AS \"interval\")" },
        new String[] { "TIMESTAMP '2020-01-01 00:00:00'", "CAST('2020-01-01 00:00:00' AS \"timestamp\")" },
        new String[] { "int '5'", "CAST('5' AS \"int4\")" },
        new String[] { "NULL", "NULL" },
        new String[] { "TRUE", "TRUE" },
        new String[] { "FALSE", "FALSE" });
  }

  @ParameterizedTest
  @MethodSource("constructs")
  void constructsParseToTheTreePostgreSQLBuilds(final String expression, final String printed) {
    assertThat(grouping(expression)).isEqualTo(printed);
  }

  // ------------------------------------------------------------------------------------------------ positions

  @Test
  void everyNodeKnowsWhereItStarts() {
    final SelectStatement statement = select("SELECT a,\n  b + 1 FROM t");

    assertThat(statement.position().line()).isEqualTo(1);
    assertThat(statement.position().column()).isEqualTo(1);
    final SourcePosition first = ((SelectItem.Projection) statement.items().get(0)).expression().position();
    assertThat(first.line()).isEqualTo(1);
    assertThat(first.column()).isEqualTo(8);
    // an operator node starts at its operator
    final Expression second = ((SelectItem.Projection) statement.items().get(1)).expression();
    assertThat(second.position().line()).isEqualTo(2);
    assertThat(second.position().column()).isEqualTo(5);
    final SourcePosition left = ((Expression.BinaryOp) second).left().position();
    assertThat(left.line()).isEqualTo(2);
    assertThat(left.column()).isEqualTo(3);
    assertThat(statement.from().get(0).position().column()).isEqualTo(14);
    assertThat(statement.from().get(0).position().line()).isEqualTo(2);
  }

  // ------------------------------------------------------------------------------------------------ errors

  @Test
  void aSyntaxErrorNamesTheTokenTheLineAndTheColumn() {
    assertThatThrownBy(() -> StatementParser.parse("SELECT 1 2")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("syntax error at or near \"2\" (line 1, column 10)")
        .satisfies(e -> assertThat(((PostgreSQLSyntaxException) e).getSqlState()).isEqualTo("42601"));
    assertThatThrownBy(() -> StatementParser.parse("SELECT a\nFROM t\nWHERE a = = 1")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("syntax error at or near \"=\" (line 3, column 11)");
    assertThatThrownBy(() -> StatementParser.parse("SELECT 1 +")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("syntax error at end of input (line 1, column 11)");
    assertThatThrownBy(() -> StatementParser.parse("SELECT 1 FROM")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("syntax error at end of input (line 1, column 14)");
    assertThatThrownBy(() -> StatementParser.parse("SELECT 'abc")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("(line 1, column 8)");
    assertThatThrownBy(() -> StatementParser.parse("SELECTX 1")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("\"SELECTX\" (line 1, column 1)");
    assertThatThrownBy(() -> StatementParser.parse("SELECT {1}")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("\"{\" (line 1, column 8)");
  }

  static Stream<String[]> refusals() {
    return Stream.of(
        new String[] { "\n  create table a (x int)", "CREATE is not supported by the postgresql language (line 2, column 3)" },
        new String[] { "CREATE TABLE t (a int)", "CREATE is not supported" },
        new String[] { "DROP TABLE t", "DROP is not supported" },
        new String[] { "SET search_path = x", "SET is not supported" },
        new String[] { "WITH RECURSIVE x AS (SELECT 1) SELECT * FROM x", "WITH RECURSIVE is not supported by the postgresql language (line 1, column 6)" },
        new String[] { "WITH x AS (INSERT INTO t VALUES (1) RETURNING a) SELECT * FROM x", "A data-modifying statement in WITH is not supported" },
        new String[] { "TABLE t", "TABLE is not supported" },
        new String[] { "EXPLAIN ANALYZE SELECT 1", "EXPLAIN with an option is not supported by the postgresql language (line 1, column 9)" },
        new String[] { "EXPLAIN CREATE TABLE t (a int)", "CREATE is not supported" },
        new String[] { "SELECT * FROM a NATURAL JOIN b", "NATURAL JOIN is not supported" },
        new String[] { "SELECT * FROM a NATURAL FULL JOIN b", "NATURAL FULL JOIN is not supported" },
        new String[] { "SELECT * FROM a JOIN b USING (x) AS j", "A join alias (USING ... AS name) is not supported" },
        new String[] { "WITH x AS (SELECT 1) DELETE FROM t", "A WITH clause on INSERT, UPDATE, DELETE or MERGE is not supported" },
        new String[] { "SELECT * FROM generate_series(1, 3)", "is not supported" },
        new String[] { "SELECT * FROM a, LATERAL (SELECT 1) s", "LATERAL" },
        new String[] { "SELECT ARRAY[1, 2]", "ARRAY is not supported" },
        new String[] { "SELECT ROW(1, 2)", "ROW is not supported" },
        new String[] { "SELECT (1, 2)", "A row constructor is not supported" },
        new String[] { "SELECT count(*) OVER w FROM t", "OVER W is not supported by the postgresql language (line 1, column 17)" },
        new String[] { "SELECT count(*) FILTER (WHERE a) FROM t", "FILTER is not supported" },
        new String[] { "SELECT DISTINCT ON (a) a FROM t", "DISTINCT ON is not supported by the postgresql language (line 1, column 17)" },
        new String[] { "SELECT 1 INTO t", "SELECT INTO is not supported" },
        new String[] { "SELECT 1 FROM t WINDOW w AS ()", "A WINDOW clause is not supported" },
        new String[] { "SELECT 1 FOR UPDATE", "A locking clause (FOR UPDATE and the others) is not supported by the postgresql language (line 1, column 10)" },
        new String[] { "SELECT a FROM t GROUP BY ROLLUP (a)", "ROLLUP, CUBE, GROUPING SETS and the empty grouping set is not supported" },
        new String[] { "SELECT a FROM t LIMIT 1, 2", "LIMIT with two values is not supported by the postgresql language (line 1, column 24)" },
        new String[] { "SELECT a FROM t FETCH FIRST 1 ROWS ONLY", "FETCH FIRST is not supported" },
        new String[] { "SELECT a FROM t ORDER BY a USING <", "ORDER BY with USING is not supported" },
        new String[] { "SELECT a SIMILAR TO b", "SIMILAR TO is not supported by the postgresql language (line 1, column 10)" },
        new String[] { "SELECT a NOT SIMILAR TO b", "NOT SIMILAR TO is not supported" },
        new String[] { "SELECT a IS TRUE", "IS TRUE is not supported" },
        new String[] { "SELECT 1 = ANY (ARRAY[1])", "A comparison with ANY, SOME or ALL is not supported" },
        new String[] { "SELECT a BETWEEN SYMMETRIC 1 AND 2", "BETWEEN SYMMETRIC is not supported" },
        new String[] { "SELECT a COLLATE \"C\"", "COLLATE is not supported" },
        new String[] { "SELECT a AT TIME ZONE 'UTC'", "AT TIME ZONE is not supported" },
        new String[] { "SELECT B'101'", "A bit string constant is not supported" },
        new String[] { "SELECT U&'d\\0061t'", "A Unicode escape string is not supported" },
        new String[] { "SELECT a[1] FROM t", "A subscript is not supported" },
        new String[] { "SELECT f(VARIADIC a)", "VARIADIC is not supported" },
        new String[] { "SELECT f(a => 1)", "A named argument is not supported" },
        new String[] { "SELECT x::bit(3)", "BIT is not supported" },
        new String[] { "SELECT * FROM t AS x (a, b)", "A column alias list is not supported" },
        new String[] { "SELECT * FROM t TABLESAMPLE SYSTEM (10)", "TABLESAMPLE is not supported" },
        new String[] { "SELECT current_user", "CURRENT_USER is not supported" });
  }

  @ParameterizedTest
  @MethodSource("refusals")
  void whatThePostgreSQLGrammarHasAndTheSubsetDoesNotIsRefusedWith0A000(final String text, final String message) {
    assertThatThrownBy(() -> StatementParser.parse(text)).as(text).isInstanceOf(PostgreSQLFeatureNotSupportedException.class)
        .hasMessageContaining(message).satisfies(e -> assertThat(((PostgreSQLFeatureNotSupportedException) e).getSqlState())
            .isEqualTo("0A000"));
  }

  @Test
  void aStatementOutsideTheLanguageIsToldHowToRunIt() {
    assertThatThrownBy(() -> StatementParser.parse("CREATE TABLE a (x int)")).hasMessageContaining("{sql}");
    assertThatThrownBy(() -> StatementParser.parse("WITH RECURSIVE x AS (SELECT 1) SELECT 1")).hasMessageNotContaining("{sql}");
  }

  @Test
  void whicheverComesFirstInTheTextWins() {
    // a subquery after a real error is not what PostgreSQL would report: it stops at the error
    assertThatThrownBy(() -> StatementParser.parse("SELECT 1 + FROM (SELECT 2) s")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("\"FROM\" (line 1, column 12)");
    // a subquery before an error is a construct PostgreSQL accepts, and the grammar does not read beyond it
    assertThatThrownBy(() -> StatementParser.parse("SELECT * FROM generate_series(1, 3) s WHERE"))
        .isInstanceOf(PostgreSQLFeatureNotSupportedException.class).hasMessageContaining("(line 1, column 15)");
  }

  // ------------------------------------------------------------------------------------------------ round trip

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid ORDER BY p.name, o.oid",
      "SELECT \"left\", \"join\" FROM \"inner\"",
      "SELECT iid FROM item ORDER BY embedding <=> '[1,0,0]' LIMIT 3",
      "SELECT a <-> b, c <#> d, e || f FROM t",
      "SELECT DISTINCT a, count(*) AS n, count(DISTINCT b) FROM t WHERE x > -1 AND y IS NOT NULL GROUP BY a HAVING count(*) > 1 ORDER BY a DESC NULLS LAST, n LIMIT 5 OFFSET 2",
      "SELECT * FROM a JOIN (b LEFT JOIN c ON b.x = c.x) ON a.x = b.x, d",
      "SELECT CASE WHEN a BETWEEN 1 AND 2 THEN 'x' WHEN b NOT IN (1, 2) THEN 'y' ELSE 'z' END, CASE a WHEN 1 THEN 2 END FROM t",
      "SELECT a LIKE 'x%' ESCAPE '!', a NOT LIKE 'y' FROM t",
      "SELECT x::numeric(10, 2), CAST(y AS varchar(5)), z::int[][], DATE '1995-03-15', INTERVAL '1' MONTH, INTERVAL '1' DAY TO SECOND(2) FROM t",
      "SELECT extract(year FROM d), substring(a FROM 1 FOR 2), trim(both 'x' FROM y), position('a' IN b), coalesce(a, 1), nullif(a, 2) FROM t",
      "SELECT -5, - -5, -(5), +5, -1.5, 1e3, 0x1F, $1, 'it''s', E'a\\nb', $$x$$, TRUE, NULL, current_timestamp(3) FROM ONLY t",
      "SELECT - a, NOT a, (a + b) * c, a ^ b ^ c, a - (b - c) FROM s.t u",
      "EXPLAIN SELECT 1" })
  void printingATreeAndParsingThePrintGivesAnEqualTree(final String text) {
    final Statement tree = StatementParser.parse(text);
    final String printed = AstPrinter.print(tree);

    assertThat(StatementParser.parse(printed)).as(printed).isEqualTo(tree);
    assertThat(AstPrinter.print(StatementParser.parse(printed))).isEqualTo(printed);
  }

  @Test
  void equalContentAtAnotherPlaceIsAnEqualTree() {
    assertThat(select("SELECT a FROM t")).isEqualTo(select("SELECT\n\n  a\n    FROM t -- comment"));
    assertThat(select("SELECT a FROM t")).isNotEqualTo(select("SELECT b FROM t"));
    assertThat(List.of(select("SELECT 1"))).containsExactly(select(" SELECT 1 "));
  }

  // ------------------------------------------------------------------------------------------------ what PostgreSQL itself refuses

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT a FROM t FETCH FIRST 2 ROW WITH TIES",
      "SELECT a FROM t ORDER BY a FETCH FIRST 1 ROW WITH TIES FOR UPDATE SKIP LOCKED",
      "SELECT U&'wrong: \\061'", "SELECT U&'wrong: \\+0061'", "SELECT U&'wrong: +0061' UESCAPE +",
      "SELECT U&'wrong: +0061' UESCAPE '+'", "SELECT U&'wrong: \\db99'", "SELECT U&'wrong: \\db99xy'",
      "SELECT U&'wrong: \\db99\\\\'", "SELECT U&'wrong: \\db99\\0061'", "SELECT U&'wrong: \\+2FFFFF'",
      "SELECT E'wrong: \\udb99'", "SELECT E'wrong: \\udb99xy'", "SELECT E'wrong: \\udb99\\\\'",
      "SELECT E'wrong: \\udb99\\u0061'", "SELECT E'wrong: \\U0000db99\\U00000061'", "SELECT E'wrong: \\udc99'" })
  void textThatPostgreSQLRefusesIsASyntaxErrorEvenInsideAConstructTheSubsetDoesNotHave(final String text) {
    assertThatThrownBy(() -> StatementParser.parse(text)).as(text).isInstanceOf(PostgreSQLSyntaxException.class);
  }

  @Test
  void anEscapeThatPostgreSQLCallsInvalidIsNotASyntaxError() {
    assertThatThrownBy(() -> StatementParser.parse("SELECT E'wrong: \\u061'")).isInstanceOfSatisfying(
        PostgreSQLInvalidEscapeException.class, e -> assertThat(e.getSqlState()).isEqualTo("22025"));
    assertThatThrownBy(() -> StatementParser.parse("SELECT E'wrong: \\U0061'")).isInstanceOf(
        PostgreSQLInvalidEscapeException.class);
    // a hex escape with no digit is the letter, a surrogate pair is one character
    assertThat(stringValue("SELECT E'\\xZ'")).isEqualTo("xZ");
    assertThat(stringValue("SELECT E'\\ud83d\\ude00'")).isEqualTo("\uD83D\uDE00");
    assertThat(stringValue("SELECT E'\\U0001f600'")).isEqualTo("\uD83D\uDE00");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT U&'d\\0061t'", "SELECT U&'d!0061t' UESCAPE '!'", "SELECT 1 AS U&\"\\\" UESCAPE '!'",
      "SELECT * FROM (a JOIN b USING (i)) AS x WHERE a.t = 1", "SELECT * FROM (a JOIN b USING (i) AS x) AS xx WHERE x.i = 1",
      "SELECT * FROM (t1 a (x, y, z) CROSS JOIN t2 b (d, e)) AS tx (i, j, k, l, m)",
      "SELECT t1.q2, count(t2.*) FROM t1 LEFT JOIN (SELECT * FROM t3 OFFSET 0) t2 ON (t1.q2 = t2.q1) GROUP BY t1.q2",
      "SELECT 1 FROM (unnest(ARRAY[1, (SELECT 2)]) AS u(b) RIGHT JOIN (VALUES (1)) AS v(b) USING (b) FULL JOIN t i8 ON true) AS j, length(j::text) f",
      "SELECT j FROM i4 LEFT JOIN (LATERAL (VALUES (i4.f1)) AS v(a) CROSS JOIN i8) AS j ON (j.q1 = 123), i4b WHERE i4.f1 = 0",
      "SELECT * FROM x JOIN (y x CROSS JOIN z y(ff)) j ON q1 = f1",
      "SELECT v.* FROM (x LEFT JOIN (SELECT q1 FROM t) y ON x.q2 = y.q1), LATERAL (SELECT x.q1 UNION ALL SELECT x.q2) v(vx, vy)",
      "SELECT * FROM a JOIN LATERAL (SELECT 1 UNION ALL SELECT 2) ss ON true",
      "SELECT bar.* FROM (t1 JOIN t2 USING (a) AS foo) AS bar, t3 FOR UPDATE OF bar",
      "SELECT row_to_json(x.*) FROM a JOIN b USING (i) AS x WHERE a.t = 'one'" })
  void aConstructOutsideTheSubsetIsRefusedWhateverFollowsIt(final String text) {
    assertThatThrownBy(() -> StatementParser.parse(text)).as(text).isInstanceOf(PostgreSQLFeatureNotSupportedException.class);
  }
}
