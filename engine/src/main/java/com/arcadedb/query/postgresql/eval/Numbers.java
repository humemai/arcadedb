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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The numeric rules of PostgreSQL on Java values: the promotion of two numeric types to one (section 10.2 of the manual,
 * "Operators", and the implicit casts of table 10.x in {@code pg_cast}), the arithmetic of each type with its overflow and
 * division rules, and the comparison. Every method takes values already widened to the type it is given (see
 * {@link #widen(Object, PgType)}).
 *
 * <ul>
 * <li>Integer arithmetic is exact and raises SQLSTATE {@code 22003} on overflow of the type of the operands: {@code int4 + int4}
 * overflows at 32 bits, as in PostgreSQL, not at 64.</li>
 * <li>Division by zero is {@code 22012} for every type. Integer division truncates toward zero and the remainder takes the sign of
 * the dividend, as in Java.</li>
 * <li>{@code numeric} is a {@link BigDecimal}: sums keep the larger scale, products the sum of the scales, and a quotient has the
 * scale of {@code select_div_scale} in {@code numeric.c}: at least 16 significant digits, rounded half away from zero.</li>
 * <li>A {@code numeric} power has the scale, the overflow and the underflow of {@code power_var_int} in {@code numeric.c}.</li>
 * <li>Floating point follows IEEE except where PostgreSQL raises {@code 22003} for an overflow or an underflow of finite
 * operands and {@code 22012} for a division by zero. {@code NaN} equals {@code NaN} and is greater than every other value.</li>
 * </ul>
 */
public final class Numbers {
  /** {@code NUMERIC_MIN_SIG_DIGITS} of {@code numeric.c}. */
  private static final int        MIN_SIGNIFICANT_DIGITS = 16;
  /** {@code NUMERIC_MAX_DISPLAY_SCALE}. */
  private static final int        MAX_DISPLAY_SCALE      = 1000;
  /** {@code (NUMERIC_WEIGHT_MAX + 1) * DEC_DIGITS}: the most digits that a {@code numeric} has before its decimal point. */
  private static final int        MAX_INTEGER_DIGITS     = 32_768 * 4;
  /** {@code NUMERIC_MAX_RESULT_SCALE * 3}: the largest natural logarithm of a power whose exponent is not an {@code int4}. */
  private static final int        MAX_LONG_POWER_LOG     = 6_000;
  private static final double     LN_10                  = Math.log(10.0);
  private static final BigDecimal HALF                   = new BigDecimal("0.5");
  private static final BigDecimal TWO                    = BigDecimal.valueOf(2);
  private static final BigDecimal MAX_INT_EXPONENT       = BigDecimal.valueOf(Integer.MAX_VALUE);

  private Numbers() {
  }

  private static int i(final Object value) {
    return ((Number) value).intValue();
  }

  private static long l(final Object value) {
    return ((Number) value).longValue();
  }

  private static float f(final Object value) {
    return ((Number) value).floatValue();
  }

  private static double d(final Object value) {
    return ((Number) value).doubleValue();
  }

  /**
   * The type both operands are converted to for an operator or a comparison: the wider integer, {@code numeric} for an integer
   * with a {@code numeric}, {@code float8} for anything with floating point unless both are {@code float4}.
   *
   * @param a a numeric type
   * @param b a numeric type
   */
  public static PgType common(final PgType a, final PgType b) {
    if (a == b)
      return a;
    if (a.isInteger() && b.isInteger())
      return a.ordinal() > b.ordinal() ? a : b;
    if (a.isFloat() || b.isFloat())
      return PgType.FLOAT8;
    return PgType.NUMERIC;
  }

  /** The value in the Java class of {@code to}, which must be at least as wide as the type of the value (an implicit cast). */
  public static Object widen(final Object value, final PgType to) {
    if (value == null)
      return null;
    switch (to) {
    case INT2:
      return ((Number) value).shortValue();
    case INT4:
      return ((Number) value).intValue();
    case INT8:
      return ((Number) value).longValue();
    case NUMERIC:
      return toBigDecimal(value);
    case FLOAT4:
      return ((Number) value).floatValue();
    case FLOAT8:
      return ((Number) value).doubleValue();
    default:
      throw new IllegalArgumentException("Not a numeric type: " + to);
    }
  }

  /** A number as {@code numeric}: a float keeps the digits of its shortest text, as {@code float4::numeric} does. */
  public static BigDecimal toBigDecimal(final Object value) {
    if (value instanceof BigDecimal decimal)
      return decimal;
    if (value instanceof BigInteger integer)
      return new BigDecimal(integer);
    if (value instanceof Float || value instanceof Double) {
      final double number = ((Number) value).doubleValue();
      if (Double.isNaN(number) || Double.isInfinite(number))
        throw PostgreSQLEvaluationException.unsupported("NaN and Infinity are not supported as numeric");
      return new BigDecimal(value instanceof Float ? Float.toString(((Number) value).floatValue()) : Double.toString(number));
    }
    return BigDecimal.valueOf(((Number) value).longValue());
  }

  /** The value of a numeric of negative scale ({@code 1E+3}) written with scale 0, as PostgreSQL stores it. */
  public static BigDecimal normalizeScale(final BigDecimal value) {
    return value.scale() < 0 ? value.setScale(0) : value;
  }

  // ---- arithmetic ----

  public static Object add(final PgType type, final Object a, final Object b) {
    switch (type) {
    case INT2:
      return shortResult(i(a) + i(b));
    case INT4:
      try {
        return Math.addExact(i(a), i(b));
      } catch (final ArithmeticException e) {
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT4);
      }
    case INT8:
      try {
        return Math.addExact(l(a), l(b));
      } catch (final ArithmeticException e) {
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT8);
      }
    case NUMERIC:
      return ((BigDecimal) a).add((BigDecimal) b);
    case FLOAT4:
      return checkFloat(f(a) + f(b), f(a), f(b));
    case FLOAT8:
      return checkDouble(d(a) + d(b), d(a), d(b));
    default:
      throw new IllegalArgumentException("Not a numeric type: " + type);
    }
  }

  public static Object subtract(final PgType type, final Object a, final Object b) {
    switch (type) {
    case INT2:
      return shortResult(i(a) - i(b));
    case INT4:
      try {
        return Math.subtractExact(i(a), i(b));
      } catch (final ArithmeticException e) {
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT4);
      }
    case INT8:
      try {
        return Math.subtractExact(l(a), l(b));
      } catch (final ArithmeticException e) {
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT8);
      }
    case NUMERIC:
      return ((BigDecimal) a).subtract((BigDecimal) b);
    case FLOAT4:
      return checkFloat(f(a) - f(b), f(a), f(b));
    case FLOAT8:
      return checkDouble(d(a) - d(b), d(a), d(b));
    default:
      throw new IllegalArgumentException("Not a numeric type: " + type);
    }
  }

  public static Object multiply(final PgType type, final Object a, final Object b) {
    switch (type) {
    case INT2:
      return shortResult(i(a) * i(b));
    case INT4:
      try {
        return Math.multiplyExact(i(a), i(b));
      } catch (final ArithmeticException e) {
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT4);
      }
    case INT8:
      try {
        return Math.multiplyExact(l(a), l(b));
      } catch (final ArithmeticException e) {
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT8);
      }
    case NUMERIC:
      return ((BigDecimal) a).multiply((BigDecimal) b);
    case FLOAT4: {
      final float result = f(a) * f(b);
      checkFloat(result, f(a), f(b));
      if (result == 0.0f && f(a) != 0.0f && f(b) != 0.0f)
        throw floatRange("underflow");
      return result;
    }
    case FLOAT8: {
      final double result = d(a) * d(b);
      checkDouble(result, d(a), d(b));
      if (result == 0.0 && d(a) != 0.0 && d(b) != 0.0)
        throw floatRange("underflow");
      return result;
    }
    default:
      throw new IllegalArgumentException("Not a numeric type: " + type);
    }
  }

  public static Object divide(final PgType type, final Object a, final Object b) {
    switch (type) {
    case INT2:
      if (i(b) == 0)
        throw PostgreSQLEvaluationException.divisionByZero();
      return shortResult(i(a) / i(b));
    case INT4:
      if (i(b) == 0)
        throw PostgreSQLEvaluationException.divisionByZero();
      if (i(a) == Integer.MIN_VALUE && i(b) == -1)
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT4);
      return i(a) / i(b);
    case INT8:
      if (l(b) == 0L)
        throw PostgreSQLEvaluationException.divisionByZero();
      if (l(a) == Long.MIN_VALUE && l(b) == -1L)
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT8);
      return l(a) / l(b);
    case NUMERIC:
      return divideNumeric((BigDecimal) a, (BigDecimal) b);
    case FLOAT4: {
      if (f(b) == 0.0f)
        throw PostgreSQLEvaluationException.divisionByZero();
      final float result = f(a) / f(b);
      checkFloat(result, f(a), f(b));
      if (result == 0.0f && f(a) != 0.0f && !Float.isInfinite(f(b)))
        throw floatRange("underflow");
      return result;
    }
    case FLOAT8: {
      if (d(b) == 0.0)
        throw PostgreSQLEvaluationException.divisionByZero();
      final double result = d(a) / d(b);
      checkDouble(result, d(a), d(b));
      if (result == 0.0 && d(a) != 0.0 && !Double.isInfinite(d(b)))
        throw floatRange("underflow");
      return result;
    }
    default:
      throw new IllegalArgumentException("Not a numeric type: " + type);
    }
  }

  /** {@code %}: defined for integers and {@code numeric}; the sign of the result is the sign of the dividend. */
  public static Object modulo(final PgType type, final Object a, final Object b) {
    switch (type) {
    case INT2:
      if (i(b) == 0)
        throw PostgreSQLEvaluationException.divisionByZero();
      return shortResult(i(a) % i(b));
    case INT4:
      if (i(b) == 0)
        throw PostgreSQLEvaluationException.divisionByZero();
      // PostgreSQL answers 0 for INT_MIN % -1, where the division overflows
      return i(b) == -1 ? 0 : i(a) % i(b);
    case INT8:
      if (l(b) == 0L)
        throw PostgreSQLEvaluationException.divisionByZero();
      return l(b) == -1L ? 0L : l(a) % l(b);
    case NUMERIC: {
      final BigDecimal divisor = (BigDecimal) b;
      if (divisor.signum() == 0)
        throw PostgreSQLEvaluationException.divisionByZero();
      final BigDecimal dividend = (BigDecimal) a;
      final BigDecimal result = dividend.remainder(divisor);
      final int scale = Math.max(Math.max(dividend.scale(), divisor.scale()), 0);
      return result.scale() < scale ? result.setScale(scale) : result;
    }
    default:
      throw new IllegalArgumentException("No remainder for type " + type);
    }
  }

  /**
   * {@code ^}: {@code float8} for two numbers that are not both {@code numeric}, which the compiler converts first. For
   * {@code numeric} see {@link #powerNumeric}.
   */
  public static Object power(final PgType type, final Object a, final Object b) {
    if (type == PgType.NUMERIC)
      return powerNumeric((BigDecimal) a, (BigDecimal) b);
    final double x = d(a);
    final double y = d(b);
    if (x == 0.0 && y < 0.0)
      throw new PostgreSQLEvaluationException("2201F", "zero raised to a negative number is undefined");
    if (x < 0.0 && y != Math.rint(y))
      throw new PostgreSQLEvaluationException("2201F", "a negative number raised to a non-integer power yields a complex result");
    final double result = Math.pow(x, y);
    checkDouble(result, x, y);
    if (result == 0.0 && x != 0.0 && !Double.isInfinite(y))
      throw floatRange("underflow");
    return result;
  }

  /**
   * {@code numeric ^ numeric} for an integer exponent, as {@code power_var_int} of {@code numeric.c} answers it: at least 16
   * significant digits and at least the scale of either operand, rounded half away from zero, SQLSTATE {@code 22003} for a result
   * that {@code numeric} cannot hold, and zero for one below its smallest scale. Both limits are read from the logarithm of the
   * result before anything is computed, and every step of the computation keeps only the digits that the result needs, so a large
   * exponent costs little. The bases {@code 1}, {@code -1} and {@code 0} take every integer exponent.
   *
   * @throws PostgreSQLEvaluationException {@code 0A000} outside the subset: an exponent that is not an integer, a negative exponent
   *                                       of any other base, and an exponent above {@code int4} whose result {@code numeric} holds
   */
  private static BigDecimal powerNumeric(final BigDecimal base, final BigDecimal exponent) {
    if (exponent.stripTrailingZeros().scale() > 0)
      throw PostgreSQLEvaluationException.unsupported("numeric ^ numeric is supported for an integer exponent only");
    final int sign = base.signum();
    if (sign == 0 && exponent.signum() < 0)
      throw new PostgreSQLEvaluationException("2201F", "zero raised to a negative power is undefined");
    final BigDecimal magnitude = base.abs();
    final boolean one = magnitude.compareTo(BigDecimal.ONE) == 0;
    if (sign != 0 && !one && exponent.signum() < 0)
      throw PostgreSQLEvaluationException.unsupported(
          "numeric ^ numeric is supported for a negative exponent only when the base is 1, -1 or 0");
    // the decimal logarithm of the result; 0 stands for it where the result is 0 or has the magnitude 1
    final double weight = sign == 0 || one ? 0.0 : exponent.doubleValue() * log10(magnitude);
    final boolean aboveInt = exponent.compareTo(MAX_INT_EXPONENT) > 0;
    if (weight > (aboveInt ? MAX_LONG_POWER_LOG / LN_10 : MAX_INTEGER_DIGITS))
      throw numericOverflow();
    if (weight + 1 < -MAX_DISPLAY_SCALE)
      return BigDecimal.ZERO.setScale(MAX_DISPLAY_SCALE);
    final int operandScale = Math.max(Math.max(base.scale(), exponent.scale()), 0);
    final int scale = Math.min(Math.max(MIN_SIGNIFICANT_DIGITS - (int) weight, operandScale), MAX_DISPLAY_SCALE);

    final BigDecimal result;
    if (exponent.signum() == 0 || one)
      result = BigDecimal.ONE;
    else if (sign == 0)
      result = BigDecimal.ZERO;
    else if (aboveInt)
      throw PostgreSQLEvaluationException.unsupported("numeric ^ numeric with an exponent above 2147483647 is supported only when"
          + " the base is 1, -1 or 0, or the result is too large or too small for numeric");
    else {
      final int n = exponent.intValueExact();
      // the digits of the result, and as many more as the rounding of the steps can disturb
      final int digits = Math.max(scale + (int) weight + 1, 1) + (int) Math.log(n) + 8;
      result = raise(magnitude, n, new MathContext(digits, RoundingMode.HALF_UP));
    }
    final BigDecimal rounded = result.setScale(scale, RoundingMode.HALF_UP);
    if (rounded.precision() - rounded.scale() > MAX_INTEGER_DIGITS)
      throw numericOverflow();
    return sign < 0 && exponent.remainder(TWO).signum() != 0 ? rounded.negate() : rounded;
  }

  /** {@code base} to the power {@code n >= 1} by repeated squaring, every product rounded to the context. */
  private static BigDecimal raise(final BigDecimal base, final int n, final MathContext context) {
    BigDecimal square = base.round(context);
    BigDecimal result = (n & 1) != 0 ? square : BigDecimal.ONE;
    for (int bits = n >>> 1; bits != 0; bits >>>= 1) {
      square = square.multiply(square, context);
      if ((bits & 1) != 0)
        result = result.multiply(square, context);
    }
    return result;
  }

  /** The decimal logarithm of a positive number, near 1 taken from the distance to 1, which a {@code double} of it loses. */
  private static double log10(final BigDecimal value) {
    if (value.compareTo(HALF) > 0 && value.compareTo(TWO) < 0)
      return Math.log1p(value.subtract(BigDecimal.ONE).doubleValue()) / LN_10;
    final int exponent = value.precision() - value.scale() - 1;
    return Math.log10(value.movePointLeft(exponent).doubleValue()) + exponent;
  }

  public static Object negate(final PgType type, final Object a) {
    switch (type) {
    case INT2:
      if (i(a) == Short.MIN_VALUE)
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT2);
      return shortResult(-i(a));
    case INT4:
      if (i(a) == Integer.MIN_VALUE)
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT4);
      return -i(a);
    case INT8:
      if (l(a) == Long.MIN_VALUE)
        throw PostgreSQLEvaluationException.outOfRange(PgType.INT8);
      return -l(a);
    case NUMERIC:
      return ((BigDecimal) a).negate();
    case FLOAT4:
      return -f(a);
    case FLOAT8:
      return -d(a);
    default:
      throw new IllegalArgumentException("Not a numeric type: " + type);
    }
  }

  /** {@code abs}: the negative number of the smallest magnitude overflows, as in PostgreSQL. */
  public static Object abs(final PgType type, final Object a) {
    switch (type) {
    case INT2:
    case INT4:
    case INT8:
      return compare(type, a, widen(0, type)) < 0 ? negate(type, a) : a;
    case NUMERIC:
      return ((BigDecimal) a).abs();
    case FLOAT4:
      return Math.abs(f(a));
    case FLOAT8:
      return Math.abs(d(a));
    default:
      throw new IllegalArgumentException("Not a numeric type: " + type);
    }
  }

  /** The sign of a number: {@code -1}, {@code 0} or {@code 1}, in the type of the argument. */
  public static int signum(final PgType type, final Object a) {
    return type == PgType.NUMERIC ? ((BigDecimal) a).signum() : compare(type, a, widen(0, type));
  }

  /**
   * The quotient of two {@code numeric} values with the scale PostgreSQL gives it ({@code select_div_scale}): enough decimals
   * for 16 significant digits, but at least the scale of either operand, rounded half away from zero. {@code 10.0 / 3} is
   * {@code 3.3333333333333333} and {@code 1.0 / 3.0} is {@code 0.33333333333333333333}.
   */
  public static BigDecimal divideNumeric(final BigDecimal a, final BigDecimal b) {
    if (b.signum() == 0)
      throw PostgreSQLEvaluationException.divisionByZero();
    return a.divide(b, divisionScale(a, b), RoundingMode.HALF_UP);
  }

  /** {@code select_div_scale} of {@code numeric.c}, whose weights and digits are those of base 10000. */
  public static int divisionScale(final BigDecimal a, final BigDecimal b) {
    final int[] first = weightAndFirstDigit(a);
    final int[] second = weightAndFirstDigit(b);
    int quotientWeight = first[0] - second[0];
    if (first[1] <= second[1])
      --quotientWeight;
    int scale = MIN_SIGNIFICANT_DIGITS - quotientWeight * 4;
    scale = Math.max(scale, Math.max(a.scale(), 0));
    scale = Math.max(scale, Math.max(b.scale(), 0));
    return Math.min(scale, MAX_DISPLAY_SCALE);
  }

  /** The weight of the first non-zero base-10000 digit of a number and that digit; {@code 0, 0} for zero. */
  private static int[] weightAndFirstDigit(final BigDecimal value) {
    if (value.signum() == 0)
      return new int[] { 0, 0 };
    final BigDecimal magnitude = value.abs();
    final int decimalExponent = magnitude.precision() - magnitude.scale() - 1;
    final int weight = Math.floorDiv(decimalExponent, 4);
    final int digit = magnitude.movePointLeft(4 * weight).setScale(0, RoundingMode.FLOOR).intValue();
    return new int[] { weight, digit };
  }

  // ---- comparison ----

  /** Three-way comparison of two non-null values of the same numeric type. */
  public static int compare(final PgType type, final Object a, final Object b) {
    switch (type) {
    case INT2:
    case INT4:
    case INT8:
      return Long.compare(l(a), l(b));
    case NUMERIC:
      return ((BigDecimal) a).compareTo((BigDecimal) b);
    case FLOAT4:
    case FLOAT8:
      return compareFloat(d(a), d(b));
    default:
      throw new IllegalArgumentException("Not a numeric type: " + type);
    }
  }

  /** {@code -0} equals {@code 0}, {@code NaN} equals {@code NaN} and is greater than every number. */
  public static int compareFloat(final double x, final double y) {
    if (x < y)
      return -1;
    if (x > y)
      return 1;
    if (x == y)
      return 0;
    final boolean nanX = Double.isNaN(x);
    final boolean nanY = Double.isNaN(y);
    return nanX ? (nanY ? 0 : 1) : -1;
  }

  // ---- helpers ----

  private static Short shortResult(final int value) {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE)
      throw PostgreSQLEvaluationException.outOfRange(PgType.INT2);
    return (short) value;
  }

  private static Float checkFloat(final float result, final float a, final float b) {
    if (Float.isInfinite(result) && !Float.isInfinite(a) && !Float.isInfinite(b))
      throw floatRange("overflow");
    return result;
  }

  private static Double checkDouble(final double result, final double a, final double b) {
    if (Double.isInfinite(result) && !Double.isInfinite(a) && !Double.isInfinite(b))
      throw floatRange("overflow");
    return result;
  }

  private static PostgreSQLEvaluationException floatRange(final String what) {
    return new PostgreSQLEvaluationException("22003", "value out of range: " + what);
  }

  private static PostgreSQLEvaluationException numericOverflow() {
    return new PostgreSQLEvaluationException("22003", "value overflows numeric format");
  }
}
