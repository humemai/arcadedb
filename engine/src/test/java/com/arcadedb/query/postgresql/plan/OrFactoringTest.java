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

import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.parser.StatementParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The terms that every branch of an {@code OR} has in common are taken out of it, as PostgreSQL does before it plans
 * ({@code process_duplicate_ors} in {@code prepqual.c}). Trees are compared by content: the position in the text does not count.
 */
class OrFactoringTest {

  private static Expression where(final String condition) {
    return ((SelectStatement) StatementParser.parse("SELECT 1 FROM t WHERE " + condition)).where();
  }

  private static void assertFactored(final String before, final String after) {
    assertThat(OrFactoring.factor(where(before))).isEqualTo(where(after));
  }

  @Test
  void aTermInEveryBranchComesOut() {
    assertFactored("(a = 1 AND b = 2) OR (a = 1 AND c = 3)", "a = 1 AND (b = 2 OR c = 3)");
  }

  @Test
  void theThreeBranchesOfQ19() {
    assertFactored("(k = 1 AND x = 1 AND y = 5) OR (k = 1 AND x = 2 AND y = 6) OR (k = 1 AND x = 3 AND y = 7)",
        "k = 1 AND ((x = 1 AND y = 5) OR (x = 2 AND y = 6) OR (x = 3 AND y = 7))");
  }

  @Test
  void severalCommonTermsComeOutInTheOrderOfTheFirstBranch() {
    assertFactored("(a = 1 AND b = 2 AND c = 3) OR (b = 2 AND a = 1 AND d = 4)", "a = 1 AND b = 2 AND (c = 3 OR d = 4)");
  }

  @Test
  void aBranchOfTheCommonTermsAloneLeavesNothingToOr() {
    assertFactored("a = 1 OR (a = 1 AND b = 2)", "a = 1");
  }

  @Test
  void aTermThatIsNotInEveryBranchStays() {
    assertFactored("(a = 1 AND b = 2) OR (a = 2 AND b = 3)", "(a = 1 AND b = 2) OR (a = 2 AND b = 3)");
    assertFactored("(a = 1 AND b = 2) OR (a = 1 AND c = 3) OR (d = 4)", "(a = 1 AND b = 2) OR (a = 1 AND c = 3) OR (d = 4)");
  }

  @Test
  void anOrUnderAnAndIsFactoredToo() {
    assertFactored("z = 0 AND ((a = 1 AND b = 2) OR (a = 1 AND c = 3))", "z = 0 AND (a = 1 AND (b = 2 OR c = 3))");
  }

  @Test
  void aConditionWithoutAnOrAndNoConditionAreUntouched() {
    assertFactored("a = 1 AND b = 2", "a = 1 AND b = 2");
    assertThat(OrFactoring.factor(null)).isNull();
  }
}
