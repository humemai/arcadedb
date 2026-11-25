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

import java.util.List;
import java.util.regex.Pattern;

/**
 * The pgvector distance operators {@code <->}, {@code <=>} and {@code <#>} over two {@code vector} values (a {@code float[]}),
 * and the text form of a {@code vector} ({@code '[1,2,3]'}). The values are pgvector's:
 * <ul>
 * <li>{@code <->} is the Euclidean distance, {@code sqrt(sum((a_i - b_i)^2))};</li>
 * <li>{@code <=>} is the cosine distance, {@code 1 - a.b / sqrt(|a|^2 |b|^2)} with the similarity clamped to [-1, 1] as
 * {@code vector_cosine_distance} does, so the distance is in [0, 2]. A zero vector gives {@code NaN} (0/0), as the source of
 * pgvector 0.8 does: its README says only that zero vectors are not indexed for cosine distance, and nothing about the value;</li>
 * <li>{@code <#>} is the negative inner product, {@code -(a.b)}.</li>
 * </ul>
 * The result is {@code float8} and is computed in double precision from the {@code float4} elements (pgvector accumulates in
 * {@code float4}, so the two differ in the last digits only). A {@code NULL} operand gives {@code NULL}. Two vectors of different
 * dimension are SQLSTATE {@code 22000} with pgvector's text {@code different vector dimensions 3 and 2}, which is in
 * {@code CheckDims} of pgvector's {@code vector.c} and not in its README.
 *
 * <p>An operand is a {@code vector}, or a constant that is text or of unknown type (a string literal, a text parameter), which is
 * read with the input syntax of the type: pgvector resolves the unknown literal of {@code embedding <-> '[1,0,0]'} to
 * {@code vector} the same way, and a Java caller that passes the query vector as a {@code String} parameter gets the same
 * reading. Any other operand is {@code 42883}.
 *
 * <p>The planner reads {@link #operator()}, {@link #left()} and {@link #right()} to find an ordered nearest-neighbour search.
 */
public final class VectorDistance implements Evaluable {
  /** The most dimensions a {@code vector} has in pgvector. */
  public static final int MAX_DIMENSIONS = 16000;

  private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");

  private final Expression.BinaryOperator operator;
  private final Evaluable                 left;
  private final Evaluable                 right;

  private VectorDistance(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    this.operator = operator;
    this.left = left;
    this.right = right;
  }

  /**
   * The expression for {@code left operator right}.
   *
   * @throws PostgreSQLEvaluationException {@code 42883} when an operand cannot be a vector, {@code 22P02} for a constant that is
   *                                       not the text of a vector
   */
  public static VectorDistance of(final Expression.BinaryOperator operator, final Evaluable left, final Evaluable right) {
    if (left.type() != PgType.VECTOR && right.type() != PgType.VECTOR)
      throw PostgreSQLEvaluationException.noOperator(operator.symbol(), left.type(), right.type());
    final Evaluable l = operand(left);
    final Evaluable r = operand(right);
    if (l == null || r == null)
      throw PostgreSQLEvaluationException.noOperator(operator.symbol(), left.type(), right.type());
    return new VectorDistance(operator, l, r);
  }

  /** The operand as a vector, {@code null} when it cannot be one. */
  private static Evaluable operand(final Evaluable operand) {
    final PgType type = operand.type();
    if (type == PgType.VECTOR)
      return operand;
    if (operand.isConstant() && (type == PgType.UNKNOWN || type.isString())) {
      final Object value = operand.eval(new Object[0]);
      ExpressionCompiler.inferParameter(operand, PgType.VECTOR);
      return new Evaluable.Constant(PgType.VECTOR, value == null ? null : parse((String) value));
    }
    return null;
  }

  public Expression.BinaryOperator operator() {
    return operator;
  }

  /** The left operand, as a vector. */
  public Evaluable left() {
    return left;
  }

  /** The right operand, as a vector. */
  public Evaluable right() {
    return right;
  }

  @Override
  public PgType type() {
    return PgType.FLOAT8;
  }

  @Override
  public boolean isConstant() {
    return left.isConstant() && right.isConstant();
  }

  @Override
  public Object eval(final Object[] row) {
    final float[] a = (float[]) left.eval(row);
    if (a == null)
      return null;
    final float[] b = (float[]) right.eval(row);
    if (b == null)
      return null;
    return distance(operator, a, b);
  }

  /** The value of the operator for two vectors. */
  public static double distance(final Expression.BinaryOperator operator, final float[] a, final float[] b) {
    checkDimensions(a, b);
    return switch (operator) {
      case L2_DISTANCE -> l2(a, b);
      case COSINE_DISTANCE -> cosine(a, b);
      case NEGATIVE_INNER_PRODUCT -> -dot(a, b);
      default -> throw new IllegalArgumentException(operator.symbol() + " is not a vector operator");
    };
  }

  public static boolean isVectorOperator(final Expression.BinaryOperator operator) {
    return operator == Expression.BinaryOperator.L2_DISTANCE || operator == Expression.BinaryOperator.COSINE_DISTANCE
        || operator == Expression.BinaryOperator.NEGATIVE_INNER_PRODUCT;
  }

  /** {@code 22000}: {@code different vector dimensions 3 and 2}. */
  public static void checkDimensions(final float[] a, final float[] b) {
    if (a.length != b.length)
      throw new PostgreSQLEvaluationException("22000", "different vector dimensions " + a.length + " and " + b.length);
  }

  public static double l2(final float[] a, final float[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++) {
      final double difference = (double) a[i] - (double) b[i];
      sum += difference * difference;
    }
    return Math.sqrt(sum);
  }

  public static double dot(final float[] a, final float[] b) {
    double sum = 0;
    for (int i = 0; i < a.length; i++)
      sum += (double) a[i] * (double) b[i];
    return sum;
  }

  public static double cosine(final float[] a, final float[] b) {
    double product = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      product += (double) a[i] * (double) b[i];
      normA += (double) a[i] * (double) a[i];
      normB += (double) b[i] * (double) b[i];
    }
    // sqrt(a * b) and not sqrt(a) * sqrt(b), as pgvector does; a zero vector is 0 / 0, which is NaN and stays NaN
    double similarity = product / Math.sqrt(normA * normB);
    if (similarity > 1)
      similarity = 1.0;
    else if (similarity < -1)
      similarity = -1.0;
    return 1.0 - similarity;
  }

  // ---- the text form ----

  /**
   * The input function of the {@code vector} type: {@code '[1,2,3]'}, spaces allowed around the brackets and the elements.
   *
   * @throws PostgreSQLEvaluationException {@code 22P02} for text that is not a vector, {@code 22000} for no element,
   *                                       {@code 54000} for more than {@value #MAX_DIMENSIONS}, {@code 22003} for a {@code NaN}
   *                                       or an infinite element
   */
  public static float[] parse(final String text) {
    final String s = text.strip();
    if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']')
      throw PostgreSQLEvaluationException.invalidText(PgType.VECTOR, text);
    final String inner = s.substring(1, s.length() - 1).strip();
    if (inner.isEmpty())
      throw new PostgreSQLEvaluationException("22000", "vector must have at least 1 dimension");
    final String[] parts = inner.split(",", -1);
    if (parts.length > MAX_DIMENSIONS)
      throw new PostgreSQLEvaluationException("54000", "vector cannot have more than " + MAX_DIMENSIONS + " dimensions");
    final float[] out = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      final String part = parts[i].strip();
      final String lower = part.toLowerCase(java.util.Locale.ROOT);
      if (lower.equals("nan") || lower.equals("+nan") || lower.equals("-nan"))
        throw new PostgreSQLEvaluationException("22003", "NaN not allowed in vector");
      if (lower.matches("[+-]?(inf|infinity)"))
        throw new PostgreSQLEvaluationException("22003", "infinite value not allowed in vector");
      if (!NUMBER.matcher(part).matches())
        throw PostgreSQLEvaluationException.invalidText(PgType.VECTOR, text);
      final float value = Float.parseFloat(part);
      if (Float.isInfinite(value))
        throw new PostgreSQLEvaluationException("22003", "\"" + part + "\" is out of range for type vector");
      out[i] = value;
    }
    return out;
  }

  /** The output function: {@code [1,0.5,-2]}, each element as {@code float4} is written. */
  public static String toText(final float[] vector) {
    final StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0)
        out.append(',');
      out.append(Casts.toText(vector[i], PgType.FLOAT4));
    }
    return out.append(']').toString();
  }

  /** A record or parameter value as a vector: {@code null}, a {@code float[]}, or an array or list of numbers. */
  public static float[] fromEngine(final Object raw) {
    if (raw == null)
      return null;
    if (raw instanceof float[] floats)
      return floats;
    if (raw instanceof double[] doubles) {
      final float[] out = new float[doubles.length];
      for (int i = 0; i < out.length; i++)
        out[i] = (float) doubles[i];
      return out;
    }
    final List<?> list = raw instanceof List<?> l ? l : raw instanceof Object[] array ? java.util.Arrays.asList(array) : null;
    if (list == null)
      throw PostgreSQLEvaluationException.unsupported("A value of class " + raw.getClass().getName() + " is not a vector");
    final float[] out = new float[list.size()];
    for (int i = 0; i < out.length; i++) {
      if (!(list.get(i) instanceof Number number))
        throw PostgreSQLEvaluationException.unsupported("A vector has numbers as elements");
      out[i] = number.floatValue();
    }
    return out;
  }
}
