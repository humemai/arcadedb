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

import java.util.ArrayList;
import java.util.List;

import static com.arcadedb.query.postgresql.eval.EvalSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three-valued logic and the comparison predicates, written as the tables of the PostgreSQL manual: section 9.2 "Comparison
 * Functions and Operators" (functions-comparison: the operators, {@code BETWEEN}, {@code IS [NOT] NULL},
 * {@code IS [NOT] DISTINCT FROM}), section 9.1 "Logical Operators" (functions-logical: the truth tables of {@code AND}, {@code OR}
 * and {@code NOT}) and section 9.25 "Row and Array Comparisons" for {@code IN} with a list that holds {@code NULL}.
 */
class LogicAndComparisonTest {
  private static final Boolean T = Boolean.TRUE;
  private static final Boolean F = Boolean.FALSE;
  private static final Boolean N = null;

  // functions-logical: the truth table of AND and OR, rows are (a, b, a AND b, a OR b)
  private static final Boolean[][] LOGIC_TABLE = {
      { T, T, T, T }, { T, F, F, T }, { T, N, N, T },
      { F, T, F, T }, { F, F, F, F }, { F, N, F, N },
      { N, T, N, T }, { N, F, F, N }, { N, N, N, N } };

  @Test
  void theTruthTableOfAndAndOr() {
    for (final Boolean[] row : LOGIC_TABLE) {
      assertThat(Logic.and(row[0], row[1])).as("%s AND %s", row[0], row[1]).isEqualTo(row[2]);
      assertThat(Logic.or(row[0], row[1])).as("%s OR %s", row[0], row[1]).isEqualTo(row[3]);

      // the same table through the typed expressions: NULL is NULL::boolean
      assertThat(value(bin("AND", tri(row[0]), tri(row[1])))).as("expr %s AND %s", row[0], row[1]).isEqualTo(row[2]);
      assertThat(value(bin("OR", tri(row[0]), tri(row[1])))).as("expr %s OR %s", row[0], row[1]).isEqualTo(row[3]);
    }
  }

  @Test
  void theTruthTableOfNot() {
    assertThat(Logic.not(T)).isEqualTo(F);
    assertThat(Logic.not(F)).isEqualTo(T);
    assertThat(Logic.not(N)).isNull();
    assertThat(value(not(tri(T)))).isEqualTo(F);
    assertThat(value(not(tri(F)))).isEqualTo(T);
    assertThat(value(not(tri(N)))).isNull();
  }

  @Test
  void aBareNullIsBooleanWhereABooleanIsNeeded() {
    assertThat(value(bin("AND", nul(), bool(false)))).isEqualTo(F);
    assertThat(value(bin("OR", nul(), bool(true)))).isEqualTo(T);
    assertThat(value(bin("AND", nul(), bool(true)))).isNull();
    assertThat(value(not(nul()))).isNull();
  }

  @Test
  void andOrNotWantBooleans() {
    assertThat(sqlState(bin("AND", bool(true), i(1)))).isEqualTo("42804");
    assertThat(sqlState(bin("OR", s("x"), bool(true)))).isEqualTo("22P02");
    assertThat(sqlState(not(i(1)))).isEqualTo("42804");
  }

  // functions-comparison: every operator with a NULL operand is NULL, every comparison of two values is TRUE or FALSE
  @Test
  void aComparisonWithNullIsNull() {
    for (final String symbol : new String[] { "=", "<>", "<", "<=", ">", ">=" }) {
      assertThat(value(bin(symbol, i(1), nul()))).as("1 %s NULL", symbol).isNull();
      assertThat(value(bin(symbol, nul(), i(1)))).as("NULL %s 1", symbol).isNull();
      assertThat(value(bin(symbol, nul(), nul()))).as("NULL %s NULL", symbol).isNull();
    }
  }

  @Test
  void theSixOperators() {
    // (a, b, =, <>, <, <=, >, >=)
    final Object[][] table = {
        { 1, 2, F, T, T, T, F, F },
        { 2, 2, T, F, F, T, F, T },
        { 3, 2, F, T, F, F, T, T } };
    final String[] symbols = { "=", "<>", "<", "<=", ">", ">=" };
    for (final Object[] row : table)
      for (int k = 0; k < symbols.length; k++)
        assertThat(value(bin(symbols[k], i((Integer) row[0]), i((Integer) row[1])))).as("%s %s %s", row[0], symbols[k], row[1])
            .isEqualTo(row[2 + k]);
  }

  @Test
  void isNullAndIsNotNullAreNeverNull() {
    assertThat(value(isNull(nul(), false))).isEqualTo(T);
    assertThat(value(isNull(i(1), false))).isEqualTo(F);
    assertThat(value(isNull(nul(), true))).isEqualTo(F);
    assertThat(value(isNull(i(1), true))).isEqualTo(T);
  }

  @Test
  void isDistinctFromTreatsTwoNullsAsTheSame() {
    // (a, b, a IS NOT DISTINCT FROM b); the equality of the non-null case is TRUE or FALSE
    assertThat(Logic.isNotDistinctFrom(1, 1, T)).isTrue();
    assertThat(Logic.isNotDistinctFrom(1, 2, F)).isFalse();
    assertThat(Logic.isNotDistinctFrom(null, null, N)).isTrue();
    assertThat(Logic.isNotDistinctFrom(1, null, N)).isFalse();
    assertThat(Logic.isNotDistinctFrom(null, 1, N)).isFalse();
  }

  // between: a BETWEEN x AND y is a >= x AND a <= y
  @Test
  void betweenIncludesBothBounds() {
    assertThat(value(between(i(5), i(1), i(10), false))).isEqualTo(T);
    assertThat(value(between(i(1), i(1), i(10), false))).as("bounds are included").isEqualTo(T);
    assertThat(value(between(i(10), i(1), i(10), false))).isEqualTo(T);
    assertThat(value(between(i(11), i(1), i(10), false))).isEqualTo(F);
    assertThat(value(between(i(5), i(10), i(1), false))).as("the bounds are not swapped").isEqualTo(F);
    assertThat(value(between(i(11), i(1), i(10), true))).isEqualTo(T);
    assertThat(value(between(i(5), i(1), i(10), true))).isEqualTo(F);
    // NULL bounds: unknown unless the other bound decides
    assertThat(value(between(i(5), nul(), i(10), false))).isNull();
    assertThat(value(between(i(5), nul(), i(3), false))).isEqualTo(F);
    assertThat(value(between(i(5), i(1), nul(), false))).isNull();
    assertThat(value(between(i(5), i(7), nul(), false))).isEqualTo(F);
    assertThat(value(between(nul(), i(1), i(10), false))).isNull();
    assertThat(value(between(i(5), nul(), i(10), true))).isNull();
  }

  // IN: x IN (a, b) is x = a OR x = b, so a NULL in the list makes "not found" unknown
  @Test
  void inWithNullSemantics() {
    assertThat(value(in(i(1), false, i(1), i(2)))).isEqualTo(T);
    assertThat(value(in(i(3), false, i(1), i(2)))).isEqualTo(F);
    assertThat(value(in(i(1), false, i(1), nul()))).as("found, the NULL does not matter").isEqualTo(T);
    assertThat(value(in(i(2), false, i(1), nul()))).as("not found, but the NULL might be it").isNull();
    assertThat(value(in(nul(), false, i(1)))).isNull();
    assertThat(value(in(nul(), false, nul()))).isNull();
    // NOT IN is NOT of IN
    assertThat(value(in(i(3), true, i(1), i(2)))).isEqualTo(T);
    assertThat(value(in(i(1), true, i(1), i(2)))).isEqualTo(F);
    assertThat(value(in(i(1), true, i(1), nul()))).isEqualTo(F);
    assertThat(value(in(i(2), true, i(1), nul()))).as("x NOT IN (1, NULL) is never true").isNull();
    // a list of strings and one of mixed number types
    assertThat(value(in(s("a"), false, s("a"), s("b")))).isEqualTo(T);
    assertThat(value(in(i(2), false, i(1), dec("2.0")))).isEqualTo(T);
  }

  @Test
  void booleansCompareAndOrder() {
    assertThat(value(bin("<", bool(false), bool(true)))).isEqualTo(T);
    assertThat(value(bin("=", bool(true), bool(true)))).isEqualTo(T);
    assertThat(value(bin("=", bool(true), s("t")))).as("an unknown string is read as a boolean").isEqualTo(T);
  }

  @Test
  void textComparesByCodePointInTheCCollation() {
    // 'Z' (90) sorts before 'a' (97) and 'B' (66) before 'a'; PostgreSQL with en_US would say the opposite
    assertThat(value(bin("<", s("Z"), s("a")))).isEqualTo(T);
    assertThat(value(bin("<", s("a"), s("B")))).isEqualTo(F);
    assertThat(value(bin("=", s("a"), s("A")))).isEqualTo(F);
    assertThat(value(bin("<", s("abc"), s("abd")))).isEqualTo(T);
    assertThat(value(bin("<", s("ab"), s("abc")))).as("a prefix is smaller").isEqualTo(T);
    assertThat(Text.compare("", "a")).isNegative();
  }

  @Test
  void codePointOrderIsNotTheOrderOfUtf16Units() {
    final String bmp = "～";            // U+FF5E, one UTF-16 unit
    final String supplementary = "😀"; // U+1F600, two units that start with a surrogate below U+FF5E
    assertThat(bmp.compareTo(supplementary)).as("what String.compareTo says").isPositive();
    assertThat(Text.compare(bmp, supplementary)).as("the byte order of UTF-8").isNegative();
    assertThat(Text.compare(supplementary, bmp)).isPositive();
  }

  @Test
  void orderingPutsNullLast() {
    final List<Object> values = new ArrayList<>(java.util.Arrays.asList(3, null, 1, 2));
    values.sort(Comparison.ordering(PgType.INT4, false, null));
    assertThat(values).as("ASC: NULL is larger than any value").containsExactly(1, 2, 3, null);
    values.sort(Comparison.ordering(PgType.INT4, true, null));
    assertThat(values).as("DESC: NULL first").containsExactly(null, 3, 2, 1);
    values.sort(Comparison.ordering(PgType.INT4, false, true));
    assertThat(values).as("ASC NULLS FIRST").containsExactly(null, 1, 2, 3);
    values.sort(Comparison.ordering(PgType.INT4, true, false));
    assertThat(values).as("DESC NULLS LAST").containsExactly(3, 2, 1, null);
  }

  @Test
  void floatingPointOrderPutsNanLastAndZeroEqual() {
    assertThat(Numbers.compareFloat(Double.NaN, 1.0)).isPositive();
    assertThat(Numbers.compareFloat(1.0, Double.NaN)).isNegative();
    assertThat(Numbers.compareFloat(Double.NaN, Double.NaN)).isZero();
    assertThat(Numbers.compareFloat(-0.0, 0.0)).isZero();
    final List<Double> values = new ArrayList<>(List.of(Double.NaN, 2.0, -1.0));
    values.sort((x, y) -> Numbers.compareFloat(x, y));
    assertThat(values.get(0)).isEqualTo(-1.0);
    assertThat(values.get(2)).isNaN();
  }

  @Test
  void comparingTypesThatDoNotMatchIsAnError() {
    assertThat(sqlState(bin("=", i(1), bool(true)))).isEqualTo("42883");
    assertThat(sqlState(bin("<", cast(s("2026-01-01"), "date"), i(1)))).isEqualTo("42883");
    assertThat(sqlState(bin("=", i(1), s("x")))).as("the unknown 'x' is read as an integer").isEqualTo("22P02");
    assertThat(value(bin("=", i(1), s("1")))).isEqualTo(T);
    assertThat(value(bin("=", s("1"), i(1)))).isEqualTo(T);
  }

  @Test
  void aComparisonIsBoolean() {
    final Expression comparison = bin("<", i(1), i(2));
    assertThat(typeOf(comparison)).isEqualTo(PgType.BOOL);
    assertThat(typeOf(bin("AND", comparison, comparison))).isEqualTo(PgType.BOOL);
    assertThat(typeOf(isNull(i(1), false))).isEqualTo(PgType.BOOL);
  }
}
