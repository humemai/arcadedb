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

import com.arcadedb.query.postgresql.ast.Expression.BinaryOperator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three pgvector distances on small vectors, against values worked out by hand from the definitions of the operators, and
 * the text form of a vector with the errors pgvector gives.
 */
class VectorDistanceTest {
  private static final double TOLERANCE = 1e-9;

  private static double l2(final float[] a, final float[] b) {
    return VectorDistance.distance(BinaryOperator.L2_DISTANCE, a, b);
  }

  private static double cosine(final float[] a, final float[] b) {
    return VectorDistance.distance(BinaryOperator.COSINE_DISTANCE, a, b);
  }

  private static double negativeInnerProduct(final float[] a, final float[] b) {
    return VectorDistance.distance(BinaryOperator.NEGATIVE_INNER_PRODUCT, a, b);
  }

  @Test
  void euclideanDistanceIsTheLengthOfTheDifference() {
    assertThat(l2(new float[] { 1, 0, 0 }, new float[] { 1, 0, 0 })).isEqualTo(0.0);
    assertThat(l2(new float[] { 1, 0, 0 }, new float[] { 0, 1, 0 })).isCloseTo(Math.sqrt(2), org.assertj.core.data.Offset.offset(TOLERANCE));
    // a 3-4-5 triangle
    assertThat(l2(new float[] { 0, 0 }, new float[] { 3, 4 })).isEqualTo(5.0);
    assertThat(l2(new float[] { 1, 2, 3 }, new float[] { 4, 5, 6 })).isCloseTo(Math.sqrt(27), org.assertj.core.data.Offset.offset(TOLERANCE));
  }

  @Test
  void cosineDistanceIsOneMinusTheCosineOfTheAngle() {
    assertThat(cosine(new float[] { 1, 0, 0 }, new float[] { 2, 0, 0 })).isEqualTo(0.0);
    assertThat(cosine(new float[] { 1, 0, 0 }, new float[] { 0, 1, 0 })).isEqualTo(1.0);
    assertThat(cosine(new float[] { 1, 0, 0 }, new float[] { -1, 0, 0 })).isEqualTo(2.0);
    // [1,0,0] and [2,2,0]: the angle is 45 degrees, 1 - 1/sqrt(2)
    assertThat(cosine(new float[] { 1, 0, 0 }, new float[] { 2, 2, 0 })).isCloseTo(1 - 1 / Math.sqrt(2), org.assertj.core.data.Offset.offset(TOLERANCE));
    // [1,2,3] and [4,5,6]: 1 - 32 / sqrt(14 * 77)
    assertThat(cosine(new float[] { 1, 2, 3 }, new float[] { 4, 5, 6 })).isCloseTo(1 - 32 / Math.sqrt(1078),
        org.assertj.core.data.Offset.offset(TOLERANCE));
  }

  @Test
  void cosineSimilarityIsClampedSoTheDistanceStaysInZeroToTwo() {
    // 0.1f * 3 does not give back exactly the same direction in float: the raw ratio can be 1.0000001
    final float[] a = { 0.1f, 0.2f, 0.3f };
    final float[] b = { 0.3f, 0.6f, 0.9f };
    assertThat(cosine(a, b)).isBetween(0.0, 1e-6);
  }

  @Test
  void cosineOfAZeroVectorIsNaNAsInPgvector() {
    assertThat(cosine(new float[] { 0, 0, 0 }, new float[] { 1, 0, 0 })).isNaN();
    assertThat(cosine(new float[] { 0, 0, 0 }, new float[] { 0, 0, 0 })).isNaN();
    // the other two operators have a value for the zero vector
    assertThat(l2(new float[] { 0, 0, 0 }, new float[] { 1, 0, 0 })).isEqualTo(1.0);
    assertThat(negativeInnerProduct(new float[] { 0, 0, 0 }, new float[] { 1, 0, 0 })).isEqualTo(0.0);
  }

  @Test
  void negativeInnerProductIsTheDotProductWithTheSignChanged() {
    assertThat(negativeInnerProduct(new float[] { 1, 0, 0 }, new float[] { 1, 0, 0 })).isEqualTo(-1.0);
    assertThat(negativeInnerProduct(new float[] { 1, 0, 0 }, new float[] { 2, 2, 0 })).isEqualTo(-2.0);
    assertThat(negativeInnerProduct(new float[] { 1, 0, 0 }, new float[] { -1, 0, 0 })).isEqualTo(1.0);
    assertThat(negativeInnerProduct(new float[] { 1, 2, 3 }, new float[] { 4, 5, 6 })).isEqualTo(-32.0);
  }

  @Test
  void aDifferentDimensionIsDataExceptionWithPgvectorsText() {
    assertThatThrownBy(() -> l2(new float[] { 1, 2, 3 }, new float[] { 1, 2 })).isInstanceOf(PostgreSQLEvaluationException.class)
        .hasMessageContaining("different vector dimensions 3 and 2")
        .satisfies(e -> assertThat(((PostgreSQLEvaluationException) e).getSqlState()).isEqualTo("22000"));
    assertThatThrownBy(() -> cosine(new float[] { 1 }, new float[] { 1, 2 })).hasMessageContaining("different vector dimensions 1 and 2");
    assertThatThrownBy(() -> negativeInnerProduct(new float[] { 1, 2 }, new float[] { 1 })).hasMessageContaining("2 and 1");
  }

  @Test
  void aVectorIsReadFromItsTextAndWrittenBack() {
    assertThat(VectorDistance.parse("[1,0,0]")).containsExactly(1f, 0f, 0f);
    assertThat(VectorDistance.parse("  [ 1.5 , -2e1 ,.25 ]  ")).containsExactly(1.5f, -20f, 0.25f);
    assertThat(VectorDistance.toText(new float[] { 1f, 0.5f, -2f, 1e20f })).isEqualTo("[1,0.5,-2,1e+20]");
    assertThat(VectorDistance.toText(VectorDistance.parse("[0.1,0.2,0.3]"))).isEqualTo("[0.1,0.2,0.3]");
  }

  @Test
  void textThatIsNotAVectorHasPgvectorsSqlStates() {
    assertThat(stateOf("1,2,3")).isEqualTo("22P02");
    assertThat(stateOf("[1,2")).isEqualTo("22P02");
    assertThat(stateOf("[1,,2]")).isEqualTo("22P02");
    assertThat(stateOf("[a,b]")).isEqualTo("22P02");
    assertThat(stateOf("[]")).isEqualTo("22000");
    assertThat(stateOf("[NaN,1]")).isEqualTo("22003");
    assertThat(stateOf("[1,Infinity]")).isEqualTo("22003");
    assertThat(stateOf("[1e39]")).isEqualTo("22003");
    assertThat(stateOf("[" + "1,".repeat(VectorDistance.MAX_DIMENSIONS) + "1]")).isEqualTo("54000");
    assertThat(VectorDistance.parse("[" + "1,".repeat(VectorDistance.MAX_DIMENSIONS - 1) + "1]")).hasSize(VectorDistance.MAX_DIMENSIONS);
  }

  private static String stateOf(final String text) {
    try {
      VectorDistance.parse(text);
      return null;
    } catch (final PostgreSQLEvaluationException e) {
      return e.getSqlState();
    }
  }
}
