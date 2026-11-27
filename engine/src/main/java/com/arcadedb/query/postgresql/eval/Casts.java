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

import com.arcadedb.query.postgresql.SessionTimeZone;
import com.arcadedb.query.postgresql.catalog.ColumnType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The input and output functions of the types, and the casts between them (chapter 10.3 of the manual and {@code pg_cast}).
 * <ul>
 * <li>{@link #parse(String, PgType)} is the input function: what a string literal or a cast from text becomes. A text that is no
 * value of the type is SQLSTATE {@code 22P02}, a value out of range is {@code 22003}. A date and a timestamp are read as
 * year-month-day only, a timestamp with an optional {@code Z} or offset; what another text answers ({@code 22007},
 * {@code 22008}, or {@code 0A000}) is in {@link DateTimeText}.</li>
 * <li>{@link #toText(Object, PgType)} is the output function, with the spelling PostgreSQL writes: {@code true}, {@code 1e+20},
 * {@code 2026-03-09 10:00:00}.</li>
 * <li>{@link #cast(Object, PgType, PgType, int[])} is an explicit cast {@code x::t}: integers narrow with a range check,
 * {@code numeric} rounds half away from zero to an integer, floating point rounds half to even ({@code rint}), anything goes to
 * text and text goes to anything through the input function. A cast PostgreSQL does not have is {@code 42846}.</li>
 * <li>{@link #fromEngine(Object, PgType)} reads a value of a record or a parameter into the canonical class of a type. A
 * {@code DATE} that the engine hands out as {@link java.util.Date} is its UTC midnight, as the engine stores it.</li>
 * </ul>
 */
public final class Casts {
  private static final Pattern NUMBER    = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?");
  private static final Pattern DATE      = Pattern.compile("(\\d{1,4})-(\\d{1,2})-(\\d{1,2})");
  /** Year-month-day, a time, and after a time an optional zone: a name of UTC (group 8), or a sign and digits (groups 9, 10). */
  private static final Pattern TIMESTAMP = Pattern.compile(
      "(\\d{1,4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?"
          + "(?:\\s*(?:((?i:zulu|z|utc|uct|ut|gmt))|([+-])(\\d{1,2}:\\d{2}(?::\\d{2})?|\\d{1,4})))?)?");
  /** The most hours of an offset that PostgreSQL reads. */
  private static final int     MAX_OFFSET_HOURS = 15;

  /** The most digits of a second a timestamp keeps: microseconds. */
  static final int MAX_TIMESTAMP_PRECISION = 6;

  /** PostgreSQL counts the microseconds of a timestamp from this instant, and rounds a precision around it. */
  private static final LocalDateTime PG_EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0);

  private static final BigInteger SHORT_MIN = BigInteger.valueOf(Short.MIN_VALUE);
  private static final BigInteger SHORT_MAX = BigInteger.valueOf(Short.MAX_VALUE);
  private static final BigInteger INT_MIN   = BigInteger.valueOf(Integer.MIN_VALUE);
  private static final BigInteger INT_MAX   = BigInteger.valueOf(Integer.MAX_VALUE);
  private static final BigInteger LONG_MIN  = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger LONG_MAX  = BigInteger.valueOf(Long.MAX_VALUE);

  private Casts() {
  }

  // ---- output ----

  /** The text PostgreSQL's output function (and the cast to {@code text}) gives a value. */
  public static String toText(final Object value, final PgType type) {
    switch (type) {
    case BOOL:
      return ((Boolean) value) ? "true" : "false";
    case INT2:
    case INT4:
    case INT8:
      return Long.toString(((Number) value).longValue());
    case NUMERIC:
      return ((BigDecimal) value).toPlainString();
    case FLOAT4: {
      final float number = ((Number) value).floatValue();
      if (Float.isNaN(number) || Float.isInfinite(number) || number == 0.0f)
        return special(number);
      return formatShortest(new BigDecimal(Float.toString(number)));
    }
    case FLOAT8: {
      final double number = ((Number) value).doubleValue();
      if (Double.isNaN(number) || Double.isInfinite(number) || number == 0.0)
        return special(number);
      return formatShortest(new BigDecimal(Double.toString(number)));
    }
    case TEXT:
    case VARCHAR:
    case UNKNOWN:
      return (String) value;
    case DATE:
      return value.toString();
    case TIMESTAMP:
      return formatTimestamp((LocalDateTime) value);
    case TIMESTAMPTZ:
      return formatTimestamp(((OffsetDateTime) value).toLocalDateTime()) + "+00";
    case VECTOR:
      return VectorDistance.toText((float[]) value);
    default:
      throw PostgreSQLEvaluationException.unsupported("The text form of a value of type " + type.displayName()
          + " is not supported");
    }
  }

  private static String special(final double number) {
    if (Double.isNaN(number))
      return "NaN";
    if (Double.isInfinite(number))
      return number > 0 ? "Infinity" : "-Infinity";
    return 1.0 / number < 0 ? "-0" : "0";
  }

  /** The shortest digits that read back as the same number, in the layout of {@code float8out}: {@code 1e+20}, {@code 1.5e-07}. */
  private static String formatShortest(final BigDecimal value) {
    final BigDecimal stripped = value.stripTrailingZeros();
    final String digits = stripped.unscaledValue().abs().toString();
    final int exponent = digits.length() - stripped.scale() - 1;
    if (exponent >= -4 && exponent < 15)
      return stripped.toPlainString();
    final StringBuilder out = new StringBuilder();
    if (stripped.signum() < 0)
      out.append('-');
    out.append(digits.charAt(0));
    if (digits.length() > 1)
      out.append('.').append(digits, 1, digits.length());
    out.append('e').append(exponent < 0 ? '-' : '+');
    final int magnitude = Math.abs(exponent);
    if (magnitude < 10)
      out.append('0');
    return out.append(magnitude).toString();
  }

  private static String formatTimestamp(final LocalDateTime value) {
    final StringBuilder out = new StringBuilder();
    out.append(value.toLocalDate()).append(' ');
    out.append(String.format(Locale.ROOT, "%02d:%02d:%02d", value.getHour(), value.getMinute(), value.getSecond()));
    final int micros = value.getNano() / 1000;
    if (micros != 0) {
      String fraction = String.format(Locale.ROOT, "%06d", micros);
      int end = fraction.length();
      while (end > 0 && fraction.charAt(end - 1) == '0')
        --end;
      out.append('.').append(fraction, 0, end);
    }
    return out.toString();
  }

  // ---- input ----

  /** The input function of {@code type}: a value of its canonical class, or the error PostgreSQL raises. */
  public static Object parse(final String text, final PgType type) {
    switch (type) {
    case BOOL:
      return parseBoolean(text);
    case INT2:
    case INT4:
    case INT8:
      return parseInteger(text, type);
    case NUMERIC:
      return parseNumeric(text);
    case FLOAT4:
    case FLOAT8:
      return parseFloat(text, type);
    case TEXT:
    case VARCHAR:
    case UNKNOWN:
      return text;
    case DATE:
      return parseDate(text);
    case TIMESTAMP:
      return parseTimestamp(text);
    case TIMESTAMPTZ:
      return parseInstant(text);
    case VECTOR:
      return VectorDistance.parse(text);
    case JSONB:
      return ValueInput.toJson(text);
    case BYTEA:
      return ValueInput.parseBytea(text);
    default:
      throw PostgreSQLEvaluationException.unsupported("A value of type " + type.displayName() + " cannot be written as a literal");
    }
  }

  private static Boolean parseBoolean(final String text) {
    final String s = text.strip().toLowerCase(Locale.ROOT);
    if (!s.isEmpty()) {
      if ("true".startsWith(s) || "yes".startsWith(s) || "1".equals(s) || "on".equals(s))
        return Boolean.TRUE;
      if ("false".startsWith(s) || "no".startsWith(s) || "0".equals(s) || (s.length() > 1 && "off".startsWith(s)))
        return Boolean.FALSE;
    }
    throw PostgreSQLEvaluationException.invalidText(PgType.BOOL, text);
  }

  private static Object parseInteger(final String text, final PgType type) {
    final BigInteger value = integerText(text.strip());
    if (value == null)
      throw PostgreSQLEvaluationException.invalidText(type, text);
    if (!fits(value, type))
      throw PostgreSQLEvaluationException.outOfRange(text, type);
    return integer(value, type);
  }

  /**
   * An integer as the input functions of PostgreSQL read it since version 16: a sign, then decimal digits, or {@code 0x},
   * {@code 0o} or {@code 0b} and digits of that base, with single underscores between the digits and, after a prefix, before the
   * first one.
   *
   * @return the number, {@code null} for a text that is none
   */
  private static BigInteger integerText(final String text) {
    int start = text.startsWith("+") || text.startsWith("-") ? 1 : 0;
    int radix = 10;
    if (text.length() > start + 1 && text.charAt(start) == '0') {
      radix = switch (text.charAt(start + 1)) {
        case 'x', 'X' -> 16;
        case 'o', 'O' -> 8;
        case 'b', 'B' -> 2;
        default -> 10;
      };
      if (radix != 10)
        start += 2;
    }
    final StringBuilder digits = new StringBuilder(text.startsWith("-") ? "-" : "");
    boolean underscore = false;
    boolean digit = false;
    for (int i = start; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '_') {
        // not two in a row, and a decimal number does not begin with one
        if (underscore || radix == 10 && !digit)
          return null;
        underscore = true;
      } else if (c < 128 && Character.digit(c, radix) >= 0) {
        digits.append(c);
        digit = true;
        underscore = false;
      } else
        return null;
    }
    return digit && !underscore ? new BigInteger(digits.toString(), radix) : null;
  }

  private static BigDecimal parseNumeric(final String text) {
    final String stripped = text.strip();
    // an underscore between two digits of a decimal number says nothing
    final String s = stripped.indexOf('_') < 0 ? stripped : stripped.replaceAll("(?<=[0-9])_(?=[0-9])", "");
    if (!NUMBER.matcher(s).matches()) {
      // an integer in base 2, 8 or 16
      final BigInteger integer = integerText(stripped);
      if (integer != null)
        return new BigDecimal(integer);
      final String lower = s.toLowerCase(Locale.ROOT);
      if (lower.equals("nan") || lower.endsWith("infinity") || lower.endsWith("inf"))
        throw PostgreSQLEvaluationException.unsupported("NaN and Infinity are not supported as numeric");
      throw PostgreSQLEvaluationException.invalidText(PgType.NUMERIC, text);
    }
    return Numbers.normalizeScale(new BigDecimal(s));
  }

  private static Object parseFloat(final String text, final PgType type) {
    final String s = text.strip();
    final String lower = s.toLowerCase(Locale.ROOT);
    double value;
    if (lower.equals("nan"))
      value = Double.NaN;
    else if (lower.equals("infinity") || lower.equals("+infinity") || lower.equals("inf") || lower.equals("+inf"))
      value = Double.POSITIVE_INFINITY;
    else if (lower.equals("-infinity") || lower.equals("-inf"))
      value = Double.NEGATIVE_INFINITY;
    else {
      if (!NUMBER.matcher(s).matches())
        throw PostgreSQLEvaluationException.invalidText(type, text);
      // out of range: a number that becomes infinity, or zero though a digit before its exponent is none
      final boolean zero = s.split("[eE]", 2)[0].chars().noneMatch(c -> c >= '1' && c <= '9');
      if (type == PgType.FLOAT4) {
        final float narrow = Float.parseFloat(s);
        if (Float.isInfinite(narrow) || narrow == 0.0f && !zero)
          throw PostgreSQLEvaluationException.outOfRange(text, type);
        return narrow;
      }
      value = Double.parseDouble(s);
      if (Double.isInfinite(value) || value == 0.0 && !zero)
        throw PostgreSQLEvaluationException.outOfRange(text, type);
    }
    return type == PgType.FLOAT4 ? (Object) (float) value : (Object) value;
  }

  /**
   * PostgreSQL raises {@code 22003} where a conversion to {@code real} or {@code double precision} gives infinity or zero for a
   * number that is neither.
   */
  private static void checkConverted(final Number value, final double converted) {
    if (value instanceof BigDecimal number) {
      if (Double.isInfinite(converted) || converted == 0.0 && number.signum() != 0)
        throw new PostgreSQLEvaluationException("22003", "value out of range: " + (converted == 0.0 ? "underflow" : "overflow"));
    } else if (Double.isInfinite(converted) && !Double.isInfinite(value.doubleValue()))
      throw new PostgreSQLEvaluationException("22003", "value out of range: overflow");
    else if (converted == 0.0 && value.doubleValue() != 0.0)
      throw new PostgreSQLEvaluationException("22003", "value out of range: underflow");
  }

  private static LocalDate parseDate(final String text) {
    final Matcher matcher = DATE.matcher(text.strip());
    if (!matcher.matches())
      throw DateTimeText.unread(text, PgType.DATE);
    final int year = Integer.parseInt(matcher.group(1));
    // PostgreSQL has no year 0: 1 BC comes before the year 1
    if (year == 0)
      throw DateTimeText.outOfRange(text);
    try {
      return LocalDate.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    } catch (final DateTimeException e) {
      throw DateTimeText.outOfRange(text);
    }
  }

  /** A {@code timestamp}: PostgreSQL reads a time zone after it, and ignores it. */
  private static LocalDateTime parseTimestamp(final String text) {
    final Matcher matcher = timestampText(text, PgType.TIMESTAMP);
    // an offset that PostgreSQL does not read is an error here too
    offsetSeconds(matcher, text);
    return localTime(matcher, text);
  }

  /** A {@code timestamptz}: the time less its offset, and a time without a zone is one of the session, which is in UTC. */
  private static OffsetDateTime parseInstant(final String text) {
    final Matcher matcher = timestampText(text, PgType.TIMESTAMPTZ);
    final int offset = offsetSeconds(matcher, text);
    final LocalDateTime utc = localTime(matcher, text).minusSeconds(offset);
    if (utc.getYear() < 1 || utc.getYear() > 9999)
      throw PostgreSQLEvaluationException.unsupported("A timestamp with time zone that is before the year 1 or after the year"
          + " 9999 in UTC is not supported: \"" + text + "\". Write a later or an earlier time");
    return instant(utc);
  }

  /** The instant of a time in UTC, the one class a {@code timestamptz} has. */
  static OffsetDateTime instant(final LocalDateTime utc) {
    SessionTimeZone.requireUtc();
    return utc.atOffset(ZoneOffset.UTC);
  }

  private static Matcher timestampText(final String text, final PgType type) {
    final Matcher matcher = TIMESTAMP.matcher(text.strip());
    if (!matcher.matches())
      throw DateTimeText.unread(text, type);
    return matcher;
  }

  /** The seconds east of UTC of the zone of a timestamp text, 0 for none. */
  private static int offsetSeconds(final Matcher matcher, final String text) {
    if (matcher.group(9) == null)
      return 0;
    final String[] parts = matcher.group(10).split(":");
    int hours = Integer.parseInt(parts[0]);
    int minutes = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
    final int seconds = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
    // three or four digits with no colon are hours and minutes: +0530
    if (parts.length == 1 && parts[0].length() > 2) {
      minutes = hours % 100;
      hours /= 100;
    }
    if (hours > MAX_OFFSET_HOURS || minutes > 59 || seconds > 59)
      throw new PostgreSQLEvaluationException("22009", "time zone displacement out of range: \"" + text + "\"");
    final int total = hours * 3600 + minutes * 60 + seconds;
    return "-".equals(matcher.group(9)) ? -total : total;
  }

  private static LocalDateTime localTime(final Matcher matcher, final String text) {
    final int year = Integer.parseInt(matcher.group(1));
    if (year == 0)
      throw DateTimeText.outOfRange(text);
    try {
      final int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
      final int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
      final int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
      // timestamps have a precision of microseconds: further digits are rounded, as ParseFractionalSecond does (rint)
      final long micros = matcher.group(7) == null ? 0 : (long) Math.rint(Double.parseDouble("0." + matcher.group(7)) * 1_000_000);
      return LocalDateTime.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)), hour, minute, second)
          .plus(micros, ChronoUnit.MICROS);
    } catch (final DateTimeException e) {
      throw DateTimeText.outOfRange(text);
    }
  }

  // ---- casts ----

  /**
   * An explicit cast.
   *
   * @param value  the value, {@code null} for SQL {@code NULL}
   * @param from   the type of the value; {@link PgType#UNKNOWN} for a string literal
   * @param to     the target type
   * @param typmod the type modifiers of the target, {@code null} for none: {@code {precision, scale}} for {@code numeric}, the
   *               length for {@code varchar}, the precision for {@code timestamp}, the dimensions for {@code vector}
   */
  public static Object cast(final Object value, final PgType from, final PgType to, final int[] typmod) {
    if (value == null)
      return null;
    if (from == to)
      return applyModifier(value, to, typmod);
    if (to.isString())
      return applyModifier(from.isString() || from == PgType.UNKNOWN ? value : toText(value, from), to, typmod);
    if (from.isString() || from == PgType.UNKNOWN)
      return applyModifier(parse((String) value, to), to, typmod);

    switch (to) {
    case BOOL:
      if (from == PgType.INT4)
        return ((Integer) value) != 0;
      break;
    case INT2:
    case INT4:
    case INT8:
      if (from.isNumber())
        return toInteger(value, from, to);
      if (from == PgType.BOOL && to == PgType.INT4)
        return ((Boolean) value) ? 1 : 0;
      break;
    case NUMERIC:
      if (from.isNumber())
        return applyModifier(Numbers.toBigDecimal(value), to, typmod);
      break;
    case FLOAT4:
      if (from.isNumber()) {
        final float narrow = ((Number) value).floatValue();
        checkConverted((Number) value, narrow);
        return narrow;
      }
      break;
    case FLOAT8:
      if (from.isNumber()) {
        final double wide = ((Number) value).doubleValue();
        checkConverted((Number) value, wide);
        return wide;
      }
      break;
    case DATE:
      if (from == PgType.TIMESTAMP)
        return ((LocalDateTime) value).toLocalDate();
      if (from == PgType.TIMESTAMPTZ)
        return ((OffsetDateTime) value).toLocalDate();
      break;
    case TIMESTAMP:
      if (from == PgType.DATE)
        return ((LocalDate) value).atStartOfDay();
      if (from == PgType.TIMESTAMPTZ)
        return applyModifier(((OffsetDateTime) value).toLocalDateTime(), to, typmod);
      break;
    case TIMESTAMPTZ:
      if (from == PgType.DATE)
        return instant(((LocalDate) value).atStartOfDay());
      if (from == PgType.TIMESTAMP)
        return applyModifier(instant((LocalDateTime) value), to, typmod);
      break;
    case VECTOR:
      if (from == PgType.ARRAY)
        return applyModifier(ValueInput.toVector(value), to, typmod);
      break;
    default:
      break;
    }
    throw new PostgreSQLEvaluationException("42846", "cannot cast type " + from.displayName() + " to " + to.displayName());
  }

  private static Object toInteger(final Object value, final PgType from, final PgType to) {
    final BigInteger result;
    if (from.isInteger())
      result = BigInteger.valueOf(((Number) value).longValue());
    else if (from == PgType.NUMERIC)
      result = ((BigDecimal) value).setScale(0, RoundingMode.HALF_UP).toBigInteger();
    else {
      final double number = ((Number) value).doubleValue();
      if (Double.isNaN(number) || Double.isInfinite(number))
        throw PostgreSQLEvaluationException.outOfRange(to);
      result = new BigDecimal(Math.rint(number)).toBigInteger();
    }
    if (!fits(result, to))
      throw PostgreSQLEvaluationException.outOfRange(to);
    return integer(result, to);
  }

  private static Object applyModifier(final Object value, final PgType type, final int[] typmod) {
    if (typmod == null || value == null)
      return value;
    if (type == PgType.NUMERIC && typmod.length >= 1) {
      final int precision = typmod[0];
      final int scale = typmod.length > 1 ? typmod[1] : 0;
      final BigDecimal rounded = ((BigDecimal) value).setScale(scale, RoundingMode.HALF_UP);
      if (rounded.signum() != 0 && rounded.precision() - rounded.scale() > precision - scale)
        throw new PostgreSQLEvaluationException("22003", "numeric field overflow. A field with precision " + precision + ", scale "
            + scale + " must round to an absolute value less than 10^" + (precision - scale));
      // a negative scale rounds left of the point, and the result has no digits after it
      return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }
    if (type == PgType.TIMESTAMP && typmod.length >= 1)
      return roundTimestamp((LocalDateTime) value, typmod[0]);
    if (type == PgType.TIMESTAMPTZ && typmod.length >= 1)
      return instant(roundTimestamp(((OffsetDateTime) value).toLocalDateTime(), typmod[0]));
    if (type == PgType.VECTOR && typmod.length >= 1 && ((float[]) value).length != typmod[0])
      throw new PostgreSQLEvaluationException("22000", "expected " + typmod[0] + " dimensions, not " + ((float[]) value).length);
    if (type == PgType.VARCHAR && typmod.length >= 1) {
      final String text = (String) value;
      if (text.codePointCount(0, text.length()) > typmod[0])
        return text.substring(0, text.offsetByCodePoints(0, typmod[0]));
    }
    return value;
  }

  /**
   * A timestamp with {@code precision} digits of a second, as {@code AdjustTimestampForTypmod} rounds it: half away from zero
   * on the microseconds counted from 2000-01-01, so a half goes to the later instant after that date and to the earlier one
   * before it.
   */
  static LocalDateTime roundTimestamp(final LocalDateTime value, final int precision) {
    if (precision >= MAX_TIMESTAMP_PRECISION)
      return value;
    long unit = 1;
    for (int digit = precision; digit < MAX_TIMESTAMP_PRECISION; digit++)
      unit *= 10;
    final long micros = ChronoUnit.MICROS.between(PG_EPOCH, value);
    final long rounded = (Math.abs(micros) + unit / 2) / unit * unit;
    return PG_EPOCH.plus(micros < 0 ? -rounded : rounded, ChronoUnit.MICROS);
  }

  private static boolean fits(final BigInteger value, final PgType type) {
    switch (type) {
    case INT2:
      return value.compareTo(SHORT_MIN) >= 0 && value.compareTo(SHORT_MAX) <= 0;
    case INT4:
      return value.compareTo(INT_MIN) >= 0 && value.compareTo(INT_MAX) <= 0;
    default:
      return value.compareTo(LONG_MIN) >= 0 && value.compareTo(LONG_MAX) <= 0;
    }
  }

  private static Object integer(final BigInteger value, final PgType type) {
    switch (type) {
    case INT2:
      return value.shortValue();
    case INT4:
      return value.intValue();
    default:
      return value.longValue();
    }
  }

  // ---- values of records and parameters ----

  /**
   * A value of a column of a record, as {@link #fromEngine} reads it into {@code type}, the type of {@code column}. The elements of
   * a {@code date[]} and of a {@code timestamp[]} are read into the canonical class of the element type too, whatever date class the
   * record holds them in.
   */
  public static Object fromColumn(final Object raw, final ColumnType column, final PgType type) {
    if (raw instanceof List<?> list && (column == ColumnType.DATE_ARRAY || column == ColumnType.TIMESTAMP_ARRAY)) {
      final PgType element = PgType.of(column.elementType());
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list)
        out.add(fromEngine(item, element));
      return out;
    }
    return fromEngine(raw, type);
  }

  /**
   * A value of a record, a parameter or an aggregate in the canonical class of {@code type}. A type that no operator takes
   * ({@code jsonb}, {@code bytea}, arrays) keeps the value as the engine holds it.
   */
  public static Object fromEngine(final Object raw, final PgType type) {
    if (raw == null)
      return null;
    switch (type) {
    case BOOL:
      if (raw instanceof Boolean)
        return raw;
      if (raw instanceof Number number)
        return number.intValue() != 0;
      return parseBoolean(raw.toString());
    case INT2:
      return raw instanceof Number number ? (Object) number.shortValue() : parse(raw.toString(), type);
    case INT4:
      return raw instanceof Number number ? (Object) number.intValue() : parse(raw.toString(), type);
    case INT8:
      return raw instanceof Number number ? (Object) number.longValue() : parse(raw.toString(), type);
    case NUMERIC:
      return Numbers.normalizeScale(raw instanceof Number || raw instanceof BigDecimal ? Numbers.toBigDecimal(raw)
          : parseNumeric(raw.toString()));
    case FLOAT4:
      return raw instanceof Number number ? (Object) number.floatValue() : parse(raw.toString(), type);
    case FLOAT8:
      return raw instanceof Number number ? (Object) number.doubleValue() : parse(raw.toString(), type);
    case TEXT:
    case VARCHAR:
    case UNKNOWN:
      return raw.toString();
    case DATE:
      return toDate(raw);
    case TIMESTAMP:
      return toTimestamp(raw);
    case TIMESTAMPTZ:
      // a time that a record holds without a zone is one in UTC
      return raw instanceof String text ? parseInstant(text) : instant(toTimestamp(raw));
    case VECTOR:
      return VectorDistance.fromEngine(raw);
    case JSONB:
      return raw instanceof com.arcadedb.serializer.json.JSONObject object ? object.toMap() : raw;
    case ARRAY:
      return raw instanceof com.arcadedb.serializer.json.JSONArray array ? array.toList() : raw;
    default:
      return raw;
    }
  }

  private static LocalDate toDate(final Object raw) {
    if (raw instanceof LocalDate date)
      return date;
    if (raw instanceof java.sql.Date date)
      return date.toLocalDate();
    if (raw instanceof java.util.Date date)
      return Instant.ofEpochMilli(date.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
    return toTimestamp(raw).toLocalDate();
  }

  private static LocalDateTime toTimestamp(final Object raw) {
    if (raw instanceof LocalDateTime timestamp)
      return timestamp;
    if (raw instanceof LocalDate date)
      return date.atStartOfDay();
    if (raw instanceof java.util.Date date)
      return Instant.ofEpochMilli(date.getTime()).atZone(ZoneOffset.UTC).toLocalDateTime();
    if (raw instanceof Instant instant)
      return instant.atZone(ZoneOffset.UTC).toLocalDateTime();
    if (raw instanceof ZonedDateTime zoned)
      return zoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    if (raw instanceof java.time.OffsetDateTime offset)
      return offset.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    if (raw instanceof Number number)
      return Instant.ofEpochMilli(number.longValue()).atZone(ZoneOffset.UTC).toLocalDateTime();
    return parseTimestamp(raw.toString());
  }
}
