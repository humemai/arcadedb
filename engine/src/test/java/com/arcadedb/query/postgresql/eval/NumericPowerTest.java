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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static com.arcadedb.query.postgresql.eval.EvalSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code numeric ^ numeric} (fork issue #161): a result that {@code numeric} cannot hold is {@code 22003} before it is computed, the
 * bases {@code 1}, {@code -1} and {@code 0} take every integer exponent, and a result has the scale that PostgreSQL gives it. The
 * comparison with PostgreSQL 18 is in {@code PostgreSQLNumericPowerTest} of the {@code postgresw} module.
 */
class NumericPowerTest {

  @ParameterizedTest
  @CsvSource({
      "10.0, 2147483647", "117743296169.0, 1000000000", "10.0, 999999999", "10.0, 131072", "-1.5, 2147483647",
      "10.0, 10000000000" })
  void aResultThatNumericCannotHoldIsOutOfRange(final String base, final String exponent) {
    assertThat(sqlState(bin("^", dec(base), big(exponent)))).isEqualTo("22003");
  }

  @ParameterizedTest
  @CsvSource({
      "-1.0, 2147483646, 1", "-1.0, 2147483647, -1", "1.0, 2147483647, 1", "1.0, -5, 1", "-1.0, -3, -1", "-1.0, -2147483648, 1",
      "1.0, 1000000000000000000000, 1", "-1.0, 9999999999999999999999, -1", "-1.0, 10000000000000000000000, 1", "0.0, 0, 1",
      "0.0, 2147483647, 0", "0.0, 12345678901, 0", "0.0, 5, 0", "2.0, 0, 1" })
  void oneMinusOneAndZeroTakeEveryIntegerExponent(final String base, final String exponent, final String expected) {
    assertThat(value(bin("^", dec(base), big(exponent)))).isEqualTo(new BigDecimal(expected).setScale(16));
  }

  @Test
  void zeroToANegativePowerIsUndefined() {
    assertThat(sqlState(bin("^", dec("0.0"), i(-1)))).isEqualTo("2201F");
    assertThat(sqlState(bin("^", dec("0.0"), i(-10000000000L)))).isEqualTo("2201F");
  }

  /** At least 16 significant digits, at least the scale of the base, rounded half away from zero ({@code power_var_int}). */
  @ParameterizedTest
  @CsvSource({
      "2.5, 2, 6.2500000000000000", "-3.5, 7, -6433.9296875000000", "1.1, 30, 17.449402268886407",
      "2.0, 100, 1267650600228229401496703205376.0", "1.0000001, 100000000, 22026.454781577307",
      "0.9999999, 100000000, 0.00004539990706252413" })
  void aResultHasTheScaleOfPostgreSql(final String base, final String exponent, final String expected) {
    assertThat(value(bin("^", dec(base), big(exponent)))).isEqualTo(new BigDecimal(expected));
  }

  @Test
  void theLargestResultAndTheSmallest() {
    final BigDecimal largest = (BigDecimal) value(bin("^", dec("10.0"), i(131071)));
    assertThat(largest.compareTo(BigDecimal.ONE.scaleByPowerOfTen(131071))).isZero();
    assertThat(largest.scale()).isEqualTo(1);
    final BigDecimal tiny = (BigDecimal) value(bin("^", dec("0.5"), i(3000)));
    assertThat(tiny.scale()).isEqualTo(919);
    assertThat(tiny.unscaledValue().toString()).isEqualTo("8128548625557735");
    // smaller than the smallest scale: zero, without computing it
    for (final String exponent : new String[] { "2147483647", "10000000000" })
      assertThat(value(bin("^", dec("0.5"), big(exponent)))).isEqualTo(BigDecimal.ZERO.setScale(1000));
  }

  @Test
  void whatStaysOutsideTheSubset() {
    assertThat(sqlState(bin("^", dec("2.0"), dec("0.5")))).isEqualTo("0A000");
    assertThat(sqlState(bin("^", dec("2.0"), i(-1)))).isEqualTo("0A000");
    // an exponent above int4 whose result numeric can hold
    assertThat(sqlState(bin("^", dec("1.000000001"), big("3000000000")))).isEqualTo("0A000");
  }
}
