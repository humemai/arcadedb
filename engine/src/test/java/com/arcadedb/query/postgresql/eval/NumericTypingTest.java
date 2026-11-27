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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static com.arcadedb.query.postgresql.eval.EvalSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Numeric typing and arithmetic: the type of a constant (section 4.1.2.6 of the manual), the promotion of two numeric types to
 * one (section 10.2, "Operators", and the implicit casts of the numeric category in 10.3), the overflow and division rules of
 * section 8.1 "Numeric Types" (integer overflow is an error, division of integers truncates), and the scale of a
 * {@code numeric} quotient (section 8.1.2).
 */
class NumericTypingTest {

  @Test
  void anIntegerConstantIsInt4ThenInt8ThenNumeric() {
    // 4.1.2.6: "initially assigned the type integer if its value fits, otherwise bigint, otherwise numeric"
    assertThat(typeOf(i(0))).isEqualTo(PgType.INT4);
    assertThat(typeOf(i(2147483647L))).isEqualTo(PgType.INT4);
    assertThat(typeOf(i(-2147483648L))).isEqualTo(PgType.INT4);
    assertThat(typeOf(i(2147483648L))).isEqualTo(PgType.INT8);
    assertThat(typeOf(i(-2147483649L))).isEqualTo(PgType.INT8);
    assertThat(typeOf(big("9223372036854775807"))).isEqualTo(PgType.INT8);
    assertThat(typeOf(big("-9223372036854775808"))).isEqualTo(PgType.INT8);
    assertThat(typeOf(big("9223372036854775808"))).isEqualTo(PgType.NUMERIC);
    assertThat(value(big("9223372036854775808"))).isEqualTo(new BigDecimal("9223372036854775808"));
    assertThat(typeOf(dec("1.5"))).isEqualTo(PgType.NUMERIC);
    assertThat(typeOf(dec("1e3"))).isEqualTo(PgType.NUMERIC);
    assertThat(value(dec("1e3"))).as("scale never negative").isEqualTo(new BigDecimal("1000"));
  }

  @Test
  void twoNumericTypesMeetInOne() {
    // pairs of (a, b, type of a + b)
    final Object[][] table = {
        { PgType.INT2, PgType.INT2, PgType.INT2 },
        { PgType.INT2, PgType.INT4, PgType.INT4 },
        { PgType.INT2, PgType.INT8, PgType.INT8 },
        { PgType.INT4, PgType.INT4, PgType.INT4 },
        { PgType.INT4, PgType.INT8, PgType.INT8 },
        { PgType.INT8, PgType.INT8, PgType.INT8 },
        { PgType.INT2, PgType.NUMERIC, PgType.NUMERIC },
        { PgType.INT4, PgType.NUMERIC, PgType.NUMERIC },
        { PgType.INT8, PgType.NUMERIC, PgType.NUMERIC },
        { PgType.NUMERIC, PgType.NUMERIC, PgType.NUMERIC },
        { PgType.FLOAT4, PgType.FLOAT4, PgType.FLOAT4 },
        { PgType.FLOAT4, PgType.FLOAT8, PgType.FLOAT8 },
        { PgType.FLOAT8, PgType.FLOAT8, PgType.FLOAT8 },
        // there is no operator on (integer, real): the candidates are resolved to double precision
        { PgType.INT4, PgType.FLOAT4, PgType.FLOAT8 },
        { PgType.INT8, PgType.FLOAT4, PgType.FLOAT8 },
        { PgType.NUMERIC, PgType.FLOAT4, PgType.FLOAT8 },
        { PgType.INT4, PgType.FLOAT8, PgType.FLOAT8 },
        { PgType.NUMERIC, PgType.FLOAT8, PgType.FLOAT8 } };
    for (final Object[] row : table) {
      final PgType a = (PgType) row[0];
      final PgType b = (PgType) row[1];
      final PgType expected = (PgType) row[2];
      assertThat(Numbers.common(a, b)).as("%s and %s", a, b).isEqualTo(expected);
      assertThat(Numbers.common(b, a)).as("%s and %s", b, a).isEqualTo(expected);
      final Map<String, Evaluable.Slot> columns = Map.of("a", new Evaluable.Slot(a, 0), "b", new Evaluable.Slot(b, 1));
      assertThat(compileWith(columns, bin("+", col("a"), col("b"))).type()).as("a + b for %s %s", a, b).isEqualTo(expected);
      assertThat(compileWith(columns, bin("<", col("a"), col("b"))).type()).isEqualTo(PgType.BOOL);
    }
  }

  @Test
  void mixedValuesAreComputedInTheCommonType() {
    final Map<String, Evaluable.Slot> columns = Map.of("a", new Evaluable.Slot(PgType.INT4, 0), "b", new Evaluable.Slot(PgType.INT8, 1),
        "n", new Evaluable.Slot(PgType.NUMERIC, 2), "f", new Evaluable.Slot(PgType.FLOAT4, 3));
    final Object[] row = { 7, 5_000_000_000L, new BigDecimal("2.50"), 1.5f };
    assertThat(compileWith(columns, bin("+", col("a"), col("b"))).eval(row)).isEqualTo(5_000_000_007L);
    assertThat(compileWith(columns, bin("*", col("a"), col("n"))).eval(row)).isEqualTo(new BigDecimal("17.50"));
    assertThat(compileWith(columns, bin("+", col("a"), col("f"))).eval(row)).isEqualTo(8.5);
    assertThat(compileWith(columns, bin("+", col("f"), col("f"))).eval(row)).isEqualTo(3.0f);
    assertThat(compileWith(columns, bin("<", col("a"), col("n"))).eval(row)).isEqualTo(false);
    assertThat(compileWith(columns, bin("=", col("n"), dec("2.5"))).eval(row)).as("2.50 equals 2.5").isEqualTo(true);
  }

  @Test
  void integerOverflowIsAnError() {
    assertThat(sqlState(bin("+", i(2147483647), i(1)))).isEqualTo("22003");
    assertThat(sqlState(bin("-", i(-2147483648L), i(1)))).isEqualTo("22003");
    assertThat(sqlState(bin("*", i(2147483647), i(2)))).isEqualTo("22003");
    assertThat(sqlState(minus(i(-2147483648L)))).as("negating the smallest int4").isEqualTo("22003");
    assertThat(sqlState(bin("/", i(-2147483648L), i(-1)))).isEqualTo("22003");
    assertThat(value(bin("%", i(-2147483648L), i(-1)))).as("PostgreSQL answers 0 here").isEqualTo(0);
    // int8 overflows at 64 bits, int2 at 16
    assertThat(sqlState(bin("+", big("9223372036854775807"), i(1)))).isEqualTo("22003");
    assertThat(sqlState(bin("+", cast(i(32767), "int2"), cast(i(1), "int2")))).isEqualTo("22003");
    assertThat(value(bin("+", cast(i(2147483647), "int8"), i(1)))).as("int8 + int4 is int8").isEqualTo(2147483648L);
    // the sum of two int4 values is checked as int4 even when it would fit in an int8
    assertThat(typeOf(bin("+", i(1), i(1)))).isEqualTo(PgType.INT4);
  }

  @Test
  void divisionOfIntegersTruncatesTowardZero() {
    assertThat(value(bin("/", i(7), i(2)))).isEqualTo(3);
    assertThat(value(bin("/", i(-7), i(2)))).isEqualTo(-3);
    assertThat(value(bin("/", i(7), i(-2)))).isEqualTo(-3);
    assertThat(typeOf(bin("/", i(7), i(2)))).isEqualTo(PgType.INT4);
    // the remainder has the sign of the dividend
    assertThat(value(bin("%", i(7), i(2)))).isEqualTo(1);
    assertThat(value(bin("%", i(-7), i(3)))).isEqualTo(-1);
    assertThat(value(bin("%", i(7), i(-3)))).isEqualTo(1);
  }

  @Test
  void divisionByZeroIsAnError() {
    assertThat(sqlState(bin("/", i(1), i(0)))).isEqualTo("22012");
    assertThat(sqlState(bin("%", i(1), i(0)))).isEqualTo("22012");
    assertThat(sqlState(bin("/", dec("1.0"), i(0)))).isEqualTo("22012");
    assertThat(sqlState(bin("/", cast(i(1), "float8"), i(0)))).isEqualTo("22012");
    assertThat(sqlState(bin("/", cast(i(1), "int8"), i(0)))).isEqualTo("22012");
  }

  @Test
  void aNumericQuotientHasAtLeastSixteenSignificantDigits() {
    // the scale of numeric.c select_div_scale: documented in 8.1.2 as "implementation-defined"; PostgreSQL 18 prints these
    assertThat(value(bin("/", dec("7.0"), i(2)))).isEqualTo(new BigDecimal("3.5000000000000000"));
    assertThat(((BigDecimal) value(bin("/", dec("7.0"), i(2)))).scale()).isEqualTo(16);
    assertThat(((BigDecimal) value(bin("/", dec("10.0"), i(3)))).toPlainString()).isEqualTo("3.3333333333333333");
    assertThat(((BigDecimal) value(bin("/", dec("1.0"), dec("3.0")))).toPlainString()).isEqualTo("0.33333333333333333333");
    assertThat(((BigDecimal) value(bin("/", dec("2.0"), dec("3.0")))).toPlainString()).as("rounded half away from zero")
        .isEqualTo("0.66666666666666666667");
    assertThat(((BigDecimal) value(bin("/", dec("100"), dec("3")))).toPlainString()).isEqualTo("33.3333333333333333");
    assertThat(((BigDecimal) value(bin("/", i(7), dec("2.0")))).toPlainString()).isEqualTo("3.5000000000000000");
    assertThat(((BigDecimal) value(bin("/", dec("15"), dec("5")))).toPlainString()).isEqualTo("3.0000000000000000");
    // the sum and the product keep the scales, as BigDecimal does
    assertThat(((BigDecimal) value(bin("+", dec("1.5"), dec("2.25")))).toPlainString()).isEqualTo("3.75");
    assertThat(((BigDecimal) value(bin("*", dec("1.5"), dec("2.25")))).toPlainString()).isEqualTo("3.375");
    assertThat(((BigDecimal) value(bin("*", dec("25.50"), i(2)))).toPlainString()).isEqualTo("51.00");
  }

  @Test
  void powerIsDoublePrecisionUnlessBothAreNumeric() {
    assertThat(typeOf(bin("^", i(2), i(3)))).isEqualTo(PgType.FLOAT8);
    assertThat(value(bin("^", i(2), i(3)))).isEqualTo(8.0);
    // a minus sign binds tighter than ^: -2 ^ 2 is (-2) ^ 2, and the parser folds -2 into the constant
    assertThat(value(bin("^", i(-2), i(2)))).isEqualTo(4.0);
    assertThat(typeOf(bin("^", dec("2.5"), dec("2")))).isEqualTo(PgType.NUMERIC);
    assertThat(value(bin("^", dec("2.5"), dec("2")))).isEqualTo(new BigDecimal("6.2500000000000000"));
    assertThat(sqlState(bin("^", cast(i(0), "float8"), i(-1)))).isEqualTo("2201F");
    assertThat(sqlState(bin("^", cast(i(-8), "float8"), cast(dec("0.5"), "float8")))).isEqualTo("2201F");
  }

  @Test
  void floatingPointOverflowAndUnderflow() {
    assertThat(sqlState(bin("*", cast(dec("1e300"), "float8"), cast(dec("1e300"), "float8")))).isEqualTo("22003");
    assertThat(sqlState(bin("*", cast(dec("1e-300"), "float8"), cast(dec("1e-300"), "float8")))).isEqualTo("22003");
    assertThat(value(bin("+", cast(dec("0.5"), "float8"), cast(dec("0.25"), "float8")))).isEqualTo(0.75);
    assertThat(sqlState(bin("%", cast(i(5), "float8"), cast(i(2), "float8")))).as("no % on floating point").isEqualTo("42883");
  }

  @Test
  void aStringConstantTakesTheNumericTypeItMeets() {
    assertThat(value(bin("+", i(1), s("2")))).isEqualTo(3);
    assertThat(value(bin("+", s("2"), dec("0.5")))).isEqualTo(new BigDecimal("2.5"));
    assertThat(sqlState(bin("+", i(1), s("x")))).isEqualTo("22P02");
    assertThat(sqlState(bin("+", i(1), s("2147483648")))).as("does not fit int4").isEqualTo("22003");
    assertThat(sqlState(bin("+", s("1"), s("2")))).as("unknown + unknown").isEqualTo("42725");
    assertThat(sqlState(bin("+", i(1), bool(true)))).isEqualTo("42883");
    assertThat(sqlState(bin("-", s("abc"), i(1)))).isEqualTo("22P02");
  }

  @Test
  void unaryMinusAndPlus() {
    assertThat(value(minus(bin("+", i(1), i(2))))).isEqualTo(-3);
    assertThat(value(minus(dec("1.5")))).isEqualTo(new BigDecimal("-1.5"));
    assertThat(sqlState(minus(bool(true)))).isEqualTo("42883");
  }

  @Test
  void absRoundFloorCeilTruncAndSqrt() {
    assertThat(value(call("abs", i(-5)))).isEqualTo(5);
    assertThat(typeOf(call("abs", i(-5)))).isEqualTo(PgType.INT4);
    assertThat(value(call("abs", dec("-2.50")))).isEqualTo(new BigDecimal("2.50"));
    assertThat(sqlState(call("abs", cast(i(-2147483648L), "int4")))).as("abs of the smallest int4").isEqualTo("22003");
    // round(numeric) is half away from zero, round(double) is half to even, as the C function rint
    assertThat(value(call("round", dec("2.5")))).isEqualTo(new BigDecimal("3"));
    assertThat(value(call("round", dec("-2.5")))).isEqualTo(new BigDecimal("-3"));
    assertThat(value(call("round", dec("25.50")))).isEqualTo(new BigDecimal("26"));
    assertThat(value(call("round", cast(dec("2.5"), "float8")))).isEqualTo(2.0);
    assertThat(value(call("round", cast(dec("3.5"), "float8")))).isEqualTo(4.0);
    assertThat(value(call("round", dec("1234.5678"), i(2)))).isEqualTo(new BigDecimal("1234.57"));
    assertThat(value(call("round", dec("1234.5678"), i(-2)))).isEqualTo(new BigDecimal("1200"));
    assertThat(typeOf(call("round", dec("1.5")))).isEqualTo(PgType.NUMERIC);
    assertThat(value(call("floor", dec("-1.5")))).isEqualTo(new BigDecimal("-2"));
    assertThat(value(call("ceil", dec("-1.5")))).isEqualTo(new BigDecimal("-1"));
    assertThat(value(call("ceiling", dec("1.2")))).isEqualTo(new BigDecimal("2"));
    assertThat(value(call("trunc", dec("-1.99")))).isEqualTo(new BigDecimal("-1"));
    assertThat(value(call("trunc", dec("1.999"), i(2)))).isEqualTo(new BigDecimal("1.99"));
    assertThat(value(call("sqrt", i(16)))).isEqualTo(4.0);
    assertThat(sqlState(call("sqrt", i(-1)))).isEqualTo("2201F");
    assertThat(value(call("mod", i(7), i(3)))).isEqualTo(1);
    assertThat(value(call("power", i(2), i(10)))).isEqualTo(1024.0);
  }

  @Test
  void aFunctionThatDoesNotExistIsAnError() {
    assertThat(sqlState(call("abs", s("x")))).isEqualTo("42883");
    assertThat(sqlState(call("nosuchfunction", i(1)))).isEqualTo("42883");
    assertThat(sqlState(call("lower", i(1)))).as("no implicit cast from integer to text").isEqualTo("42883");
  }

  @Test
  void parametersTakeTheTypeOfTheirValue() {
    assertThat(typeOf(param(1), 5)).isEqualTo(PgType.INT4);
    assertThat(typeOf(param(1), 5L)).isEqualTo(PgType.INT8);
    assertThat(typeOf(param(1), (short) 5)).isEqualTo(PgType.INT2);
    assertThat(typeOf(param(1), new BigDecimal("1.5"))).isEqualTo(PgType.NUMERIC);
    assertThat(typeOf(param(1), 1.5)).isEqualTo(PgType.FLOAT8);
    assertThat(typeOf(param(1), 1.5f)).isEqualTo(PgType.FLOAT4);
    assertThat(typeOf(param(1), "x")).isEqualTo(PgType.TEXT);
    assertThat(typeOf(param(1), true)).isEqualTo(PgType.BOOL);
    assertThat(typeOf(param(1), java.time.LocalDate.of(2026, 3, 9))).isEqualTo(PgType.DATE);
    assertThat(typeOf(param(1), new Object[] { null })).isEqualTo(PgType.UNKNOWN);
    assertThat(value(bin("+", param(1), param(2)), 40, 2)).isEqualTo(42);
    assertThat(value(bin("+", param(1), i(1)), (short) 5)).as("int2 + int4").isEqualTo(6);
  }

  @Test
  void aMissingParameterIsAnError() {
    assertThat(sqlState(param(1))).isEqualTo("42P02");
    assertThat(sqlState(bin("+", param(1), param(2)), 1)).isEqualTo("42P02");
    final Map<String, Object> named = Map.of("1", 10, "$2", 20);
    assertThat(Parameters.of(named).get(1)).isEqualTo(10);
    assertThat(Parameters.of(named).get(2)).isEqualTo(20);
    assertThat(sqlState(() -> Parameters.of(named).get(3))).isEqualTo("42P02");
  }

  @Test
  void anUnsupportedParameterClassIsRefused() {
    assertThat(sqlState(param(1), new Object[] { new Object() })).isEqualTo("0A000");
  }
}
