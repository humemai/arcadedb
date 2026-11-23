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
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The value of PostgreSQL's {@code interval} type, kept the way the server keeps it: a count of months, a count of days and a
 * count of microseconds, three fields that are never folded into one another ({@code 1 month} is not {@code 30 days}, and
 * {@code 1 day} is not {@code 24 hours}), because adding them to a date or a timestamp gives different answers.
 *
 * <p>The prototype takes an interval as an operand of {@code date + interval}, {@code date - interval},
 * {@code timestamp + interval}, {@code timestamp - interval} and {@code interval + date or timestamp}, which is what the
 * date arithmetic of TPC-H needs, and nowhere else: it is neither compared, nor sent to a client, nor stored.
 *
 * <p>Input is the text form of section 8.5.4 of the manual in the {@code postgres} and {@code SQL standard} spellings that the
 * parser of {@code interval 'N' unit} produces: pairs of a quantity and a unit ({@code 1 year 2 months}, {@code 90 days},
 * {@code 1 day 12 hours}), {@code HH:MM[:SS[.ffffff]]}, a trailing {@code ago}, and a bare number whose unit comes from the
 * field restriction of the literal ({@code interval '90' day} is 90 days, {@code interval '1' month} is one month, and a bare
 * number without a restriction is a number of seconds). The ISO 8601 form ({@code P1Y2M}), fractional years, months, weeks and
 * days, and the range restrictions ({@code day to second}) are refused with SQLSTATE {@code 0A000}, never answered differently.
 *
 * @param months the months, a year being 12
 * @param days   the days, which are calendar days and not 24 hours
 * @param micros the time of day part in microseconds
 */
public record PgInterval(int months, int days, long micros) {
  public static final PgInterval ZERO = new PgInterval(0, 0, 0L);

  private static final long MICROS_PER_SECOND = 1_000_000L;
  private static final long MICROS_PER_MINUTE = 60L * MICROS_PER_SECOND;
  private static final long MICROS_PER_HOUR   = 60L * MICROS_PER_MINUTE;

  private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
  private static final Pattern GLUED  = Pattern.compile("([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+))([a-z]+)");
  private static final Pattern TIME   = Pattern.compile("([+-]?)([0-9]+):([0-9]{1,2})(?::([0-9]{1,2})(?:\\.([0-9]+))?)?");

  /** The field restrictions of a literal that the prototype reads: a single field, as in {@code interval '90' day}. */
  public static boolean isSupportedField(final String field) {
    return field == null || field.equals("year") || field.equals("month") || field.equals("day") || field.equals("hour")
        || field.equals("minute") || field.equals("second");
  }

  /** The interval with the opposite sign in every field. */
  public PgInterval negate() {
    try {
      return new PgInterval(Math.negateExact(months), Math.negateExact(days), Math.negateExact(micros));
    } catch (final ArithmeticException e) {
      throw outOfRange();
    }
  }

  /**
   * A timestamp plus or minus this interval, in the order of {@code timestamp_pl_interval}: the months first, with the day of the
   * month clamped to the last day of the month it lands in ({@code 31 January + 1 month} is 28 or 29 February), then the days,
   * then the time.
   */
  public static LocalDateTime add(final LocalDateTime start, final PgInterval interval, final boolean subtract) {
    try {
      final long sign = subtract ? -1L : 1L;
      return start.plusMonths(sign * interval.months()).plusDays(sign * interval.days()).plus(sign * interval.micros(),
          ChronoUnit.MICROS);
    } catch (final DateTimeException | ArithmeticException e) {
      throw new PostgreSQLEvaluationException("22008", "timestamp out of range");
    }
  }

  /**
   * The input function of {@code interval}.
   *
   * @param text  the text of the literal
   * @param field the field restriction of the literal in lower case ({@code day}), or {@code null} for none; see
   *              {@link #isSupportedField(String)}
   */
  public static PgInterval parse(final String text, final String field) {
    String rest = text.strip().toLowerCase(Locale.ROOT);
    if (rest.startsWith("@"))
      rest = rest.substring(1).strip();
    if (rest.isEmpty())
      throw invalid(text);
    if (rest.startsWith("p") || rest.startsWith("-p") || rest.startsWith("+p"))
      throw PostgreSQLEvaluationException.unsupported("The ISO 8601 form of an interval is not supported: \"" + text + "\"");

    final List<String> tokens = new ArrayList<>(List.of(rest.split("\\s+")));
    boolean ago = false;
    if (tokens.size() > 1 && tokens.get(tokens.size() - 1).equals("ago")) {
      ago = true;
      tokens.remove(tokens.size() - 1);
    }

    long months = 0;
    long days = 0;
    long micros = 0;
    try {
      for (int i = 0; i < tokens.size(); i++) {
        final String token = tokens.get(i);
        final Matcher time = TIME.matcher(token);
        if (time.matches()) {
          long part = Long.parseLong(time.group(2)) * MICROS_PER_HOUR + Long.parseLong(time.group(3)) * MICROS_PER_MINUTE;
          if (time.group(4) != null)
            part += Long.parseLong(time.group(4)) * MICROS_PER_SECOND;
          if (time.group(5) != null)
            part += fractionToMicros(time.group(5));
          micros = Math.addExact(micros, "-".equals(time.group(1)) ? -part : part);
          continue;
        }
        String quantity;
        String unit;
        final Matcher glued = GLUED.matcher(token);
        if (NUMBER.matcher(token).matches()) {
          quantity = token;
          if (i + 1 < tokens.size() && !NUMBER.matcher(tokens.get(i + 1)).matches() && !TIME.matcher(tokens.get(i + 1)).matches())
            unit = tokens.get(++i);
          else
            unit = bareUnit(field);
        } else if (glued.matches()) {
          quantity = glued.group(1);
          unit = glued.group(2);
        } else
          throw invalid(text);

        final BigDecimal number = new BigDecimal(quantity.startsWith("+") ? quantity.substring(1) : quantity);
        switch (unit(unit, text)) {
        case "year" -> months = Math.addExact(months, Math.multiplyExact(whole(number, unit, text), 12L));
        case "month" -> months = Math.addExact(months, whole(number, unit, text));
        case "week" -> days = Math.addExact(days, Math.multiplyExact(whole(number, unit, text), 7L));
        case "day" -> days = Math.addExact(days, whole(number, unit, text));
        case "hour" -> micros = Math.addExact(micros, scaled(number, MICROS_PER_HOUR));
        case "minute" -> micros = Math.addExact(micros, scaled(number, MICROS_PER_MINUTE));
        case "second" -> micros = Math.addExact(micros, scaled(number, MICROS_PER_SECOND));
        case "millisecond" -> micros = Math.addExact(micros, scaled(number, 1_000L));
        default -> micros = Math.addExact(micros, scaled(number, 1L));
        }
      }
    } catch (final ArithmeticException | NumberFormatException e) {
      throw outOfRange();
    }
    if (ago) {
      months = -months;
      days = -days;
      micros = -micros;
    }
    if (months > Integer.MAX_VALUE || months < Integer.MIN_VALUE || days > Integer.MAX_VALUE || days < Integer.MIN_VALUE)
      throw outOfRange();
    return restrict(new PgInterval((int) months, (int) days, micros), field);
  }

  /** The unit of a bare number: the field of the literal, else the seconds of {@code interval '1'}. */
  private static String bareUnit(final String field) {
    return field == null ? "second" : field;
  }

  /** The name of a unit word as one of the units this parser keeps, or the error PostgreSQL gives for an unknown word. */
  private static String unit(final String word, final String text) {
    switch (word) {
    case "y", "yr", "yrs", "year", "years":
      return "year";
    case "mon", "mons", "month", "months":
      return "month";
    case "w", "week", "weeks":
      return "week";
    case "d", "day", "days":
      return "day";
    case "h", "hr", "hrs", "hour", "hours":
      return "hour";
    case "m", "min", "mins", "minute", "minutes":
      return "minute";
    case "s", "sec", "secs", "second", "seconds":
      return "second";
    case "ms", "msec", "msecs", "millisecond", "milliseconds":
      return "millisecond";
    case "us", "usec", "usecs", "microsecond", "microseconds":
      return "microsecond";
    default:
      throw invalid(text);
    }
  }

  /** A quantity of years, months, weeks or days: whole, as the prototype does not carry a fraction down into smaller fields. */
  private static long whole(final BigDecimal number, final String unit, final String text) {
    if (number.stripTrailingZeros().scale() > 0)
      throw PostgreSQLEvaluationException.unsupported("A fractional number of " + unit + " in an interval is not supported: \"" + text
          + "\"");
    return number.longValueExact();
  }

  /** A quantity of a time unit in microseconds, rounded to one microsecond. */
  private static long scaled(final BigDecimal number, final long unitMicros) {
    return number.multiply(BigDecimal.valueOf(unitMicros)).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
  }

  /** The digits after the decimal point of a second as microseconds, rounded. */
  private static long fractionToMicros(final String digits) {
    return new BigDecimal("0." + digits).multiply(BigDecimal.valueOf(MICROS_PER_SECOND)).setScale(0, RoundingMode.HALF_EVEN)
        .longValueExact();
  }

  /**
   * The restriction of a single field ({@code interval '1 year 2 months' year} is one year): the fields smaller than the named
   * one are dropped, as {@code AdjustIntervalForTypmod} does.
   */
  private static PgInterval restrict(final PgInterval value, final String field) {
    if (field == null)
      return value;
    switch (field) {
    case "year":
      return new PgInterval(value.months() / 12 * 12, 0, 0L);
    case "month":
      return new PgInterval(value.months(), 0, 0L);
    case "day":
      return new PgInterval(value.months(), value.days(), 0L);
    case "hour":
      return new PgInterval(value.months(), value.days(), value.micros() / MICROS_PER_HOUR * MICROS_PER_HOUR);
    case "minute":
      return new PgInterval(value.months(), value.days(), value.micros() / MICROS_PER_MINUTE * MICROS_PER_MINUTE);
    default:
      return value;
    }
  }

  private static PostgreSQLEvaluationException invalid(final String text) {
    return new PostgreSQLEvaluationException("22007", "invalid input syntax for type interval: \"" + text + "\"");
  }

  private static PostgreSQLEvaluationException outOfRange() {
    return new PostgreSQLEvaluationException("22015", "interval field value out of range");
  }
}
