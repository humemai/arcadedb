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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;

import static com.arcadedb.query.postgresql.eval.EvalSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text functions and operators (section 9.4 "String Functions and Operators", section 9.7 "Pattern Matching" for
 * {@code LIKE}), the explicit casts and the input and output of each type (sections 4.1.2.7, 8.1, 8.5, 8.6, 10.3),
 * {@code CASE}, {@code COALESCE}, {@code NULLIF}, {@code GREATEST} and {@code LEAST} (section 9.18) and the date arithmetic and
 * {@code extract} of section 9.9.
 */
class TextCastsAndFunctionsTest {

  // ---- LIKE (9.7.1) ----

  @Test
  void likeTable() {
    // (text, pattern, matches)
    final Object[][] table = {
        { "abc", "abc", true }, { "abc", "a%", true }, { "abc", "%c", true }, { "abc", "a_c", true }, { "abc", "a_", false },
        { "abc", "ABC", false }, { "abc", "%", true }, { "", "%", true }, { "", "", true }, { "abc", "", false },
        { "abc", "%b%", true }, { "abcabc", "%bc", true }, { "abcabc", "a%c", true }, { "ab", "a%b%c", false },
        { "a%c", "a\\%c", true }, { "abc", "a\\%c", false }, { "a_c", "a\\_c", true }, { "abc", "a\\_c", false },
        { "a\\c", "a\\\\c", true }, { "héllo", "h_llo", true }, { "😀", "_", true } };
    for (final Object[] row : table)
      assertThat(Text.like((String) row[0], (String) row[1], null, false)).as("'%s' LIKE '%s'", row[0], row[1]).isEqualTo(row[2]);
  }

  @Test
  void likeWithEscapeAndIlike() {
    assertThat(Text.like("a%b", "a!%b", "!", false)).isTrue();
    assertThat(Text.like("axb", "a!%b", "!", false)).isFalse();
    assertThat(Text.like("a\\b", "a\\b", "", false)).as("an empty escape turns escaping off").isTrue();
    assertThat(Text.like("ABC", "abc", null, true)).isTrue();
    assertThat(Text.like("ABC", "a%", null, true)).isTrue();
    assertThat(Text.like("ABC", "abc", null, false)).isFalse();
    assertThat(sqlState(() -> Text.like("ab", "a\\", null, false))).as("pattern ends with the escape, text is left").isEqualTo("22025");
    assertThat(Text.like("a", "a\\", null, false)).as("PostgreSQL raises it only when the match gets there").isFalse();
    assertThat(Text.like("b", "a\\", null, false)).isFalse();
    assertThat(sqlState(() -> Text.like("a", "a", "ab", false))).isEqualTo("22025");
  }

  @Test
  void likeThroughTheCompiler() {
    assertThat(value(like(s("Ada"), s("A%"), null, false))).isEqualTo(true);
    assertThat(value(like(s("Ada"), s("A%"), null, true))).isEqualTo(false);
    assertThat(value(like(s("Ada"), s("%d%"), null, false))).isEqualTo(true);
    assertThat(value(like(nul(), s("A%"), null, false))).isNull();
    assertThat(value(like(s("Ada"), nul(), null, false))).isNull();
    assertThat(value(like(s("a%b"), s("a!%b"), s("!"), false))).isEqualTo(true);
    assertThat(sqlState(like(i(1), s("1"), null, false))).as("integer LIKE").isEqualTo("42883");
  }

  // ---- text functions and || ----

  @Test
  void concatenationAndNull() {
    assertThat(value(bin("||", s("a"), s("b")))).isEqualTo("ab");
    assertThat(typeOf(bin("||", s("a"), s("b")))).isEqualTo(PgType.TEXT);
    assertThat(value(bin("||", s("a"), nul()))).isNull();
    assertThat(value(bin("||", s("a"), i(1)))).as("text || anynonarray").isEqualTo("a1");
    assertThat(value(bin("||", i(1), s("a")))).isEqualTo("1a");
    assertThat(value(bin("||", s("a"), bool(true)))).isEqualTo("atrue");
    assertThat(value(bin("||", s("v"), dec("1.50")))).isEqualTo("v1.50");
    assertThat(sqlState(bin("||", i(1), i(2)))).as("no operator on two integers").isEqualTo("42883");
  }

  @Test
  void textFunctions() {
    assertThat(value(call("lower", s("ÀBc")))).isEqualTo("àbc");
    assertThat(value(call("upper", s("àbc")))).isEqualTo("ÀBC");
    assertThat(value(call("initcap", s("hello wORLD-foo")))).isEqualTo("Hello World-Foo");
    assertThat(value(call("length", s("héllo")))).isEqualTo(5);
    assertThat(value(call("length", s("😀x")))).as("characters, not UTF-16 units").isEqualTo(2);
    assertThat(value(call("octet_length", s("héllo")))).isEqualTo(6);
    assertThat(value(call("reverse", s("abc")))).isEqualTo("cba");
    assertThat(value(call("replace", s("abcabc"), s("b"), s("X")))).isEqualTo("aXcaXc");
    assertThat(value(call("replace", s("abc"), s(""), s("X")))).isEqualTo("abc");
    assertThat(value(call("left", s("abcde"), i(2)))).isEqualTo("ab");
    assertThat(value(call("left", s("abcde"), i(-2)))).isEqualTo("abc");
    assertThat(value(call("right", s("abcde"), i(2)))).isEqualTo("de");
    assertThat(value(call("right", s("abcde"), i(-2)))).isEqualTo("cde");
    assertThat(value(call("repeat", s("ab"), i(3)))).isEqualTo("ababab");
    assertThat(value(call("lower", nul()))).isNull();
  }

  @Test
  void substringPositionAndTrim() {
    assertThat(value(call("substring", s("hello"), i(2), i(3)))).isEqualTo("ell");
    assertThat(value(call("substring", s("hello"), i(2)))).isEqualTo("ello");
    assertThat(value(call("substring", s("hello"), i(0), i(2)))).as("the count starts before 1").isEqualTo("h");
    assertThat(value(call("substring", s("hello"), i(-1), i(4)))).isEqualTo("he");
    assertThat(value(call("substring", s("hello"), i(10)))).isEqualTo("");
    assertThat(value(call("substring", s("hello"), i(4), i(100)))).isEqualTo("lo");
    assertThat(sqlState(call("substring", s("hello"), i(1), i(-1)))).isEqualTo("22011");
    // position(sub in str) arrives as position(str, sub)
    assertThat(value(call("position", s("hello"), s("lo")))).isEqualTo(4);
    assertThat(value(call("position", s("hello"), s("z")))).isEqualTo(0);
    assertThat(value(call("position", s("hello"), s("")))).isEqualTo(1);
    assertThat(value(call("strpos", s("hello"), s("l")))).isEqualTo(3);
    assertThat(value(call("btrim", s("  x  ")))).isEqualTo("x");
    assertThat(value(call("btrim", s("xyxhixyx"), s("xy")))).isEqualTo("hi");
    assertThat(value(call("ltrim", s("  x  ")))).isEqualTo("x  ");
    assertThat(value(call("rtrim", s("  x  ")))).isEqualTo("  x");
    assertThat(value(call("rtrim", s("xxhixx"), s("x")))).isEqualTo("xxhi");
  }

  @Test
  void concatAndConcatWsSkipNulls() {
    assertThat(value(call("concat", s("a"), nul(), i(1), bool(true)))).isEqualTo("a1true");
    assertThat(value(call("concat", nul()))).isEqualTo("");
    assertThat(typeOf(call("concat", s("a")))).isEqualTo(PgType.TEXT);
    assertThat(value(call("concat_ws", s(","), s("a"), nul(), s("b")))).isEqualTo("a,b");
    assertThat(value(call("concat_ws", nul(), s("a"), s("b")))).as("a NULL separator").isNull();
  }

  // ---- casts and the input and output of the types ----

  @Test
  void castTextToNumbers() {
    assertThat(value(cast(s("42"), "int4"))).isEqualTo(42);
    assertThat(value(cast(s(" 42 "), "int4"))).as("blanks around are ignored").isEqualTo(42);
    assertThat(value(cast(s("-7"), "int8"))).isEqualTo(-7L);
    assertThat(value(cast(s("+7"), "int2"))).isEqualTo((short) 7);
    assertThat(value(cast(s("1.50"), "numeric"))).isEqualTo(new BigDecimal("1.50"));
    assertThat(value(cast(s("1e3"), "numeric"))).isEqualTo(new BigDecimal("1000"));
    assertThat(value(cast(s("1.5"), "float8"))).isEqualTo(1.5);
    assertThat(value(cast(s("Infinity"), "float8"))).isEqualTo(Double.POSITIVE_INFINITY);
    assertThat((Double) value(cast(s("nan"), "float8"))).isNaN();
    assertThat(sqlState(cast(s("abc"), "int4"))).isEqualTo("22P02");
    assertThat(sqlState(cast(s("1.5"), "int4"))).as("not an integer").isEqualTo("22P02");
    assertThat(sqlState(cast(s(""), "int4"))).isEqualTo("22P02");
    assertThat(sqlState(cast(s("2147483648"), "int4"))).isEqualTo("22003");
    assertThat(sqlState(cast(s("32768"), "int2"))).isEqualTo("22003");
    assertThat(sqlState(cast(s("1e999"), "float8"))).isEqualTo("22003");
    assertThat(sqlState(cast(s("x"), "numeric"))).isEqualTo("22P02");
  }

  @Test
  void castBetweenNumbers() {
    assertThat(value(cast(dec("2.5"), "int4"))).as("numeric to integer rounds half away").isEqualTo(3);
    assertThat(value(cast(dec("-2.5"), "int4"))).isEqualTo(-3);
    assertThat(value(cast(cast(dec("2.5"), "float8"), "int4"))).as("float to integer rounds half to even").isEqualTo(2);
    assertThat(value(cast(cast(dec("3.5"), "float8"), "int4"))).isEqualTo(4);
    assertThat(sqlState(cast(i(40000), "int2"))).isEqualTo("22003");
    assertThat(sqlState(cast(dec("3000000000"), "int4"))).isEqualTo("22003");
    assertThat(sqlState(cast(cast(dec("1e30"), "float8"), "int8"))).isEqualTo("22003");
    assertThat(value(cast(i(3), "numeric"))).isEqualTo(new BigDecimal("3"));
    assertThat(value(cast(i(3), "float8"))).isEqualTo(3.0);
    assertThat(value(cast(big("9223372036854775807"), "int8"))).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void numericAndVarcharModifiers() {
    assertThat(value(cast(dec("123.456"), "numeric", 5, 2))).isEqualTo(new BigDecimal("123.46"));
    assertThat(value(cast(dec("0.005"), "numeric", 5, 2))).isEqualTo(new BigDecimal("0.01"));
    assertThat(sqlState(cast(dec("1234.5"), "numeric", 5, 2))).as("needs 6 digits").isEqualTo("22003");
    assertThat(value(cast(dec("999.99"), "numeric", 5, 2))).isEqualTo(new BigDecimal("999.99"));
    assertThat(value(cast(s("abcdef"), "varchar", 3))).as("an explicit cast truncates").isEqualTo("abc");
    assertThat(value(cast(s("ab"), "varchar", 3))).isEqualTo("ab");
  }

  @Test
  void castToText() {
    assertThat(value(cast(bool(true), "text"))).isEqualTo("true");
    assertThat(value(cast(bool(false), "text"))).isEqualTo("false");
    assertThat(value(cast(i(42), "text"))).isEqualTo("42");
    assertThat(value(cast(dec("1.50"), "text"))).as("numeric keeps its scale").isEqualTo("1.50");
    assertThat(value(cast(dec("-0.001"), "text"))).isEqualTo("-0.001");
    assertThat(value(cast(nul(), "text"))).isNull();
    assertThat(typeOf(cast(i(1), "text"))).isEqualTo(PgType.TEXT);
    assertThat(typeOf(cast(i(1), "varchar", 5))).isEqualTo(PgType.VARCHAR);
  }

  @Test
  void floatOutputFollowsFloat8out() {
    // (value, text) as PostgreSQL 12 and later print with extra_float_digits = 1
    final Object[][] table = {
        { 1.5, "1.5" }, { 100.0, "100" }, { 0.1, "0.1" }, { 123456789.125, "123456789.125" },
        { 100000000000000.0, "100000000000000" }, { 1e15, "1e+15" }, { 1.5e20, "1.5e+20" },
        { 0.0001, "0.0001" }, { 0.00001, "1e-05" }, { -2.5e-7, "-2.5e-07" }, { 0.0, "0" }, { -0.0, "-0" },
        { Double.NaN, "NaN" }, { Double.POSITIVE_INFINITY, "Infinity" }, { Double.NEGATIVE_INFINITY, "-Infinity" } };
    for (final Object[] row : table)
      assertThat(Casts.toText(row[0], PgType.FLOAT8)).as("%s", row[0]).isEqualTo(row[1]);
    assertThat(Casts.toText(0.1f, PgType.FLOAT4)).isEqualTo("0.1");
    assertThat(Casts.toText(16777216f, PgType.FLOAT4)).isEqualTo("16777216");
  }

  @Test
  void booleanInput() {
    for (final String yes : new String[] { "t", "true", "TRUE", "y", "yes", "on", "1", " tr ", "ye" })
      assertThat(value(cast(s(yes), "bool"))).as(yes).isEqualTo(true);
    for (final String no : new String[] { "f", "false", "n", "no", "off", "0", "of", "FALSE" })
      assertThat(value(cast(s(no), "bool"))).as(no).isEqualTo(false);
    for (final String bad : new String[] { "", "maybe", "o", "2", "truee" })
      assertThat(sqlState(cast(s(bad), "bool"))).as(bad).isEqualTo("22P02");
    assertThat(value(cast(i(5), "bool"))).as("only int4 casts to boolean").isEqualTo(true);
    assertThat(value(cast(i(0), "bool"))).isEqualTo(false);
    assertThat(value(cast(bool(true), "int4"))).isEqualTo(1);
    assertThat(sqlState(cast(bool(true), "int8"))).isEqualTo("42846");
    assertThat(sqlState(cast(cast(i(1), "int8"), "bool"))).isEqualTo("42846");
  }

  @Test
  void aCastTheLanguageDoesNotHaveIsRefused() {
    assertThat(sqlState(cast(i(1), "jsonb"))).isEqualTo("0A000");
    assertThat(sqlState(cast(i(1), "timestamptz"))).isEqualTo("0A000");
    assertThat(sqlState(cast(i(1), "date"))).isEqualTo("42846");
  }

  @Test
  void dateAndTimestampInputAndOutput() {
    assertThat(value(cast(s("2026-03-09"), "date"))).isEqualTo(LocalDate.of(2026, 3, 9));
    assertThat(value(cast(s("2026-3-9"), "date"))).isEqualTo(LocalDate.of(2026, 3, 9));
    assertThat(sqlState(cast(s("2026-13-01"), "date"))).isEqualTo("22008");
    assertThat(sqlState(cast(s("2026-02-30"), "date"))).isEqualTo("22008");
    assertThat(sqlState(cast(s("yesterday"), "date"))).isEqualTo("22007");
    assertThat(value(cast(s("2026-03-09 10:11:12"), "timestamp"))).isEqualTo(LocalDateTime.of(2026, 3, 9, 10, 11, 12));
    assertThat(value(cast(s("2026-03-09T10:11"), "timestamp"))).isEqualTo(LocalDateTime.of(2026, 3, 9, 10, 11));
    assertThat(value(cast(s("2026-03-09"), "timestamp"))).isEqualTo(LocalDateTime.of(2026, 3, 9, 0, 0));
    assertThat(value(cast(s("2026-03-09 10:11:12.5"), "timestamp"))).isEqualTo(LocalDateTime.of(2026, 3, 9, 10, 11, 12, 500_000_000));
    assertThat(value(cast(cast(s("2026-03-09 10:11:12"), "timestamp"), "text"))).isEqualTo("2026-03-09 10:11:12");
    assertThat(value(cast(cast(s("2026-03-09 10:11:12.5"), "timestamp"), "text"))).isEqualTo("2026-03-09 10:11:12.5");
    assertThat(value(cast(cast(s("2026-03-09"), "date"), "text"))).isEqualTo("2026-03-09");
    assertThat(value(cast(cast(s("2026-03-09 23:59:59"), "timestamp"), "date"))).isEqualTo(LocalDate.of(2026, 3, 9));
    assertThat(value(cast(cast(s("2026-03-09"), "date"), "timestamp"))).isEqualTo(LocalDateTime.of(2026, 3, 9, 0, 0));
  }

  @Test
  void dateArithmeticAndComparison() {
    final Expression day = cast(s("2026-03-09"), "date");
    assertThat(value(bin("+", day, i(1)))).isEqualTo(LocalDate.of(2026, 3, 10));
    assertThat(typeOf(bin("+", day, i(1)))).isEqualTo(PgType.DATE);
    assertThat(value(bin("+", i(30), day))).isEqualTo(LocalDate.of(2026, 4, 8));
    assertThat(value(bin("-", day, i(9)))).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(value(bin("-", cast(s("2026-03-10"), "date"), day))).as("date - date is an integer").isEqualTo(1);
    assertThat(typeOf(bin("-", day, day))).isEqualTo(PgType.INT4);
    assertThat(value(bin("<", day, cast(s("2026-03-10"), "date")))).isEqualTo(true);
    assertThat(value(bin("=", day, s("2026-03-09")))).as("an unknown string is read as a date").isEqualTo(true);
    assertThat(value(bin("<", day, cast(s("2026-03-09 10:00:00"), "timestamp")))).as("date to timestamp").isEqualTo(true);
    assertThat(sqlState(bin("=", day, s("not a date")))).isEqualTo("22007");
    assertThat(sqlState(bin("+", cast(s("2026-03-09 10:00:00"), "timestamp"), i(1)))).as("no interval type").isEqualTo("0A000");
  }

  @Test
  void extractFields() {
    final Expression day = cast(s("2026-03-09"), "date"); // a Monday
    final Object[][] table = {
        { "year", 2026 }, { "month", 3 }, { "day", 9 }, { "quarter", 1 }, { "dow", 1 }, { "isodow", 1 }, { "doy", 68 },
        { "week", 11 }, { "decade", 202 }, { "century", 21 }, { "millennium", 3 } };
    for (final Object[] row : table) {
      assertThat(value(call("extract", s((String) row[0]), day))).as("extract(%s)", row[0])
          .isEqualTo(new BigDecimal(String.valueOf(row[1])));
      assertThat(typeOf(call("extract", s((String) row[0]), day))).isEqualTo(PgType.NUMERIC);
    }
    assertThat(value(call("extract", s("dow"), cast(s("2026-03-08"), "date")))).as("Sunday is 0").isEqualTo(new BigDecimal("0"));
    assertThat(value(call("extract", s("isodow"), cast(s("2026-03-08"), "date")))).isEqualTo(new BigDecimal("7"));
    assertThat(value(call("extract", s("epoch"), cast(s("1970-01-02"), "date")))).isEqualTo(new BigDecimal("86400.000000"));
    final Expression moment = cast(s("2026-03-09 10:11:12.5"), "timestamp");
    assertThat(value(call("extract", s("hour"), moment))).isEqualTo(new BigDecimal("10"));
    assertThat(value(call("extract", s("minute"), moment))).isEqualTo(new BigDecimal("11"));
    assertThat(value(call("extract", s("second"), moment))).isEqualTo(new BigDecimal("12.500000"));
    assertThat(sqlState(call("extract", s("hour"), day))).as("not defined for a date").isEqualTo("0A000");
    assertThat(sqlState(call("extract", s("nosuch"), day))).isEqualTo("22023");
  }

  @Test
  void valuesOfRecordsReachTheCanonicalClasses() {
    assertThat(Casts.fromEngine((byte) 3, PgType.INT2)).isEqualTo((short) 3);
    assertThat(Casts.fromEngine(3L, PgType.INT4)).isEqualTo(3);
    assertThat(Casts.fromEngine(3, PgType.INT8)).isEqualTo(3L);
    assertThat(Casts.fromEngine(3, PgType.NUMERIC)).isEqualTo(new BigDecimal("3"));
    assertThat(Casts.fromEngine(new BigDecimal("1E+3"), PgType.NUMERIC)).isEqualTo(new BigDecimal("1000"));
    assertThat(Casts.fromEngine(1.5f, PgType.FLOAT8)).isEqualTo(1.5);
    assertThat(Casts.fromEngine('c', PgType.VARCHAR)).isEqualTo("c");
    assertThat(Casts.fromEngine(LocalDate.of(2026, 3, 9), PgType.DATE)).isEqualTo(LocalDate.of(2026, 3, 9));
    // the engine stores a DATE as a day and hands it out as the UTC midnight of that day
    assertThat(Casts.fromEngine(new Date(1773014400000L), PgType.DATE)).isEqualTo(LocalDate.of(2026, 3, 9));
    assertThat(Casts.fromEngine(LocalDateTime.of(2026, 3, 9, 1, 2, 3), PgType.TIMESTAMP)).isEqualTo(LocalDateTime.of(2026, 3, 9, 1, 2, 3));
    assertThat(Casts.fromEngine(new Date(1773014400000L + 3_723_000L), PgType.TIMESTAMP))
        .isEqualTo(LocalDateTime.of(2026, 3, 9, 1, 2, 3));
    assertThat(Casts.fromEngine(java.util.List.of("a"), PgType.ARRAY)).isEqualTo(java.util.List.of("a"));
    assertThat(Casts.fromEngine(null, PgType.INT4)).isNull();
  }

  // ---- CASE, COALESCE, NULLIF, GREATEST, LEAST ----

  @Test
  void caseExpressions() {
    final Expression otherwise = s("none");
    assertThat(value(caseOf(null, otherwise, bool(false), s("a"), bool(true), s("b")))).isEqualTo("b");
    assertThat(value(caseOf(null, otherwise, bool(false), s("a")))).isEqualTo("none");
    assertThat(value(caseOf(null, null, bool(false), s("a")))).as("no ELSE is NULL").isNull();
    assertThat(value(caseOf(i(2), null, i(1), s("one"), i(2), s("two")))).isEqualTo("two");
    assertThat(value(caseOf(nul(), s("else"), nul(), s("matched")))).as("NULL never equals NULL").isEqualTo("else");
    // a NULL condition is not true
    assertThat(value(caseOf(null, s("else"), tri(null), s("a")))).isEqualTo("else");
    // the type of the result: all strings are unknown, so text; numbers go to the widest
    assertThat(typeOf(caseOf(null, otherwise, bool(true), s("a")))).isEqualTo(PgType.TEXT);
    assertThat(typeOf(caseOf(null, dec("1.5"), bool(true), i(1)))).isEqualTo(PgType.NUMERIC);
    assertThat(typeOf(caseOf(null, null, bool(true), i(1)))).isEqualTo(PgType.INT4);
    assertThat(value(caseOf(null, dec("1.5"), bool(true), i(1)))).isEqualTo(new BigDecimal("1"));
    assertThat(sqlState(caseOf(null, bool(true), bool(true), i(1)))).as("integer and boolean").isEqualTo("42804");
    assertThat(sqlState(caseOf(null, null, i(1), i(1)))).as("a WHEN that is not boolean").isEqualTo("42804");
  }

  @Test
  void coalesceNullifGreatestLeast() {
    assertThat(value(special(Expression.SpecialFunctionKind.COALESCE, nul(), i(2), i(3)))).isEqualTo(2);
    assertThat(typeOf(special(Expression.SpecialFunctionKind.COALESCE, nul(), i(2)))).isEqualTo(PgType.INT4);
    assertThat(value(special(Expression.SpecialFunctionKind.COALESCE, nul(), nul()))).isNull();
    assertThat(typeOf(special(Expression.SpecialFunctionKind.COALESCE, nul(), nul()))).isEqualTo(PgType.TEXT);
    assertThat(value(special(Expression.SpecialFunctionKind.COALESCE, i(1), dec("2.5")))).isEqualTo(new BigDecimal("1"));
    assertThat(sqlState(special(Expression.SpecialFunctionKind.COALESCE, i(1), bool(true)))).isEqualTo("42804");
    assertThat(value(special(Expression.SpecialFunctionKind.NULLIF, i(1), i(1)))).isNull();
    assertThat(value(special(Expression.SpecialFunctionKind.NULLIF, i(1), i(2)))).isEqualTo(1);
    assertThat(value(special(Expression.SpecialFunctionKind.NULLIF, s("a"), s("a")))).isNull();
    assertThat(value(special(Expression.SpecialFunctionKind.NULLIF, nul(), i(1)))).isNull();
    assertThat(value(special(Expression.SpecialFunctionKind.GREATEST, i(1), nul(), i(3), i(2)))).as("NULLs are ignored").isEqualTo(3);
    assertThat(value(special(Expression.SpecialFunctionKind.LEAST, i(1), nul(), i(-3)))).isEqualTo(-3);
    assertThat(value(special(Expression.SpecialFunctionKind.GREATEST, i(1), dec("2.5")))).isEqualTo(new BigDecimal("2.5"));
    assertThat(value(special(Expression.SpecialFunctionKind.GREATEST, nul(), nul()))).isNull();
    assertThat(value(special(Expression.SpecialFunctionKind.GREATEST, s("a"), s("B")))).as("C collation").isEqualTo("a");
  }

  @Test
  void typesOfColumnsFlowThrough() {
    final Map<String, Evaluable.Slot> columns = Map.of("name", new Evaluable.Slot(PgType.VARCHAR, 0), "age", new Evaluable.Slot(PgType.INT4, 1));
    final Object[] ada = { "Ada", 36 };
    final Object[] cy = { "Cy", null };
    assertThat(compileWith(columns, col("name")).type()).isEqualTo(PgType.VARCHAR);
    // varchar || unknown is text; COALESCE of a varchar and an unknown stays varchar, NULLIF resolves "=" first and is text
    assertThat(compileWith(columns, bin("||", col("name"), s("!"))).type()).isEqualTo(PgType.TEXT);
    assertThat(compileWith(columns, special(Expression.SpecialFunctionKind.COALESCE, col("name"), s("x"))).type()).isEqualTo(PgType.VARCHAR);
    assertThat(compileWith(columns, special(Expression.SpecialFunctionKind.NULLIF, col("name"), s("Ada"))).type()).isEqualTo(PgType.TEXT);
    assertThat(compileWith(columns, special(Expression.SpecialFunctionKind.NULLIF, col("name"), s("Ada"))).eval(ada)).isNull();
    assertThat(compileWith(columns, bin("||", col("name"), col("age"))).eval(ada)).isEqualTo("Ada36");
    assertThat(compileWith(columns, bin("||", col("name"), col("age"))).eval(cy)).as("NULL propagates").isNull();
    assertThat(compileWith(columns, bin("+", col("age"), i(1))).eval(ada)).isEqualTo(37);
    assertThat(compileWith(columns, bin("+", col("age"), i(1))).eval(cy)).isNull();
    assertThat(compileWith(columns, bin(">", col("age"), i(30))).eval(cy)).isNull();
    assertThat(compileWith(columns, bin("AND", bin(">", col("age"), i(30)), bool(false))).eval(cy)).isEqualTo(false);
    assertThat(compileWith(columns, bin("<", col("name"), s("C"))).eval(ada)).isEqualTo(true);
    assertThat(compileWith(columns, isNull(col("age"), false)).eval(cy)).isEqualTo(true);
  }

  @Test
  void anUnresolvedUnknownBecomesTextInTheOutput() {
    final ExpressionCompiler compiler = new ExpressionCompiler(scope(Map.of()), Parameters.NONE);
    assertThat(compiler.resolveOutput(compiler.compile(s("a"))).type()).isEqualTo(PgType.TEXT);
    assertThat(compiler.resolveOutput(compiler.compile(nul())).type()).isEqualTo(PgType.TEXT);
    assertThat(compiler.resolveOutput(compiler.compile(nul())).eval(new Object[0])).isNull();
    assertThat(compiler.resolveOutput(compiler.compile(i(1))).type()).isEqualTo(PgType.INT4);
  }
}
