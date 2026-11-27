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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * field restriction of the literal ({@code interval '90' day} is 90 days, {@code interval '1' month} is one month, a bare number
 * in front of a time is a number of days, and another bare number without a restriction is a number of seconds). The ISO 8601
 * form ({@code P1Y2M}), {@code infinity}, {@code 1-2}, fractional years, months, weeks and days, and the range restrictions
 * ({@code day to second}) are refused with SQLSTATE {@code 0A000}, never answered differently. The errors are those of
 * PostgreSQL (fork issue #200): {@code 22007} for a text that is none, a unit given twice included, {@code 22015} for a field
 * that does not fit, and {@code 22008} for years and months that do not fit together.
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
  /** Forms that PostgreSQL reads and this parser does not: {@code 1-2} (years and months) and other layouts of a time. */
  private static final Pattern UNREAD = Pattern.compile("[+-]?[0-9]+-[0-9]+|[+-]?[0-9.]*:[0-9.:]*");

  /** The first timestamp of PostgreSQL, 4714-11-24 BC, and its last year. */
  private static final LocalDateTime FIRST_TIMESTAMP        = LocalDateTime.of(-4713, 11, 24, 0, 0);
  private static final int           LAST_YEAR_OF_TIMESTAMP = 294_276;

  // one bit for each unit of a text, which gives each of them once; a time, and seconds with a fraction, give several
  private static final int MICROSECOND = 1;
  private static final int MILLISECOND = 1 << 1;
  private static final int SECOND      = 1 << 2;
  private static final int MINUTE      = 1 << 3;
  private static final int HOUR        = 1 << 4;
  private static final int ALL_SECONDS = MICROSECOND | MILLISECOND | SECOND;
  private static final int A_TIME      = ALL_SECONDS | MINUTE | HOUR;

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
    final LocalDateTime result;
    try {
      final long sign = subtract ? -1L : 1L;
      result = start.plusMonths(sign * interval.months()).plusDays(sign * interval.days()).plus(sign * interval.micros(),
          ChronoUnit.MICROS);
    } catch (final DateTimeException | ArithmeticException e) {
      throw timestampOutOfRange();
    }
    if (result.isBefore(FIRST_TIMESTAMP) || result.getYear() > LAST_YEAR_OF_TIMESTAMP)
      throw timestampOutOfRange();
    // PostgreSQL answers these, and the text of such a year would not be the one it writes
    if (result.getYear() < 1 || result.getYear() > 9999)
      throw PostgreSQLEvaluationException.unsupported("A timestamp before the year 1 or after the year 9999 is not supported");
    return result;
  }

  /**
   * A {@code timestamptz} plus or minus this interval, as {@code timestamptz_pl_interval} computes it (fork issue #270): the
   * months on the calendar of the session, then the days on it, each time giving the local time back its instant, which a daylight
   * saving change can move; then the time part on the instant. A day is therefore 23, 24, or 25 hours there, and {@code 24 hours}
   * are always 24.
   *
   * @param start an instant at the offset zero
   */
  static OffsetDateTime add(final OffsetDateTime start, final PgInterval interval, final boolean subtract) {
    OffsetDateTime result = start;
    // an instant is moved through its local time only for a field that is not zero: a local time that occurs twice has one instant
    if (interval.months() != 0)
      result = SessionTimeZone.instant(
          add(SessionTimeZone.shown(result).toLocalDateTime(), new PgInterval(interval.months(), 0, 0L), subtract));
    if (interval.days() != 0)
      result = SessionTimeZone.instant(
          add(SessionTimeZone.shown(result).toLocalDateTime(), new PgInterval(0, interval.days(), 0L), subtract));
    return add(result.toLocalDateTime(), new PgInterval(0, 0, interval.micros()), subtract).atOffset(ZoneOffset.UTC);
  }

  private static PostgreSQLEvaluationException timestampOutOfRange() {
    return new PostgreSQLEvaluationException("22008", "timestamp out of range");
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
    if (rest.equals("infinity") || rest.equals("-infinity") || rest.equals("+infinity"))
      throw PostgreSQLEvaluationException.unsupported("An infinite interval is not supported");

    final List<String> tokens = new ArrayList<>(List.of(rest.split("\\s+")));
    boolean ago = false;
    if (tokens.size() > 1 && tokens.get(tokens.size() - 1).equals("ago")) {
      ago = true;
      tokens.remove(tokens.size() - 1);
    }

    // years, months and days are 32 bits wide each while the text is read, as in PostgreSQL: 22015 when one does not fit
    int years = 0;
    int months = 0;
    int days = 0;
    long micros = 0;
    int given = 0;
    try {
      for (int i = 0; i < tokens.size(); i++) {
        final String token = tokens.get(i);
        final Matcher time = TIME.matcher(token);
        if (time.matches()) {
          given = once(given, A_TIME, text);
          final long minutes = Long.parseLong(time.group(3));
          final long seconds = time.group(4) == null ? 0 : Long.parseLong(time.group(4));
          if (minutes > 59 || seconds > 60)
            throw outOfRange();
          long part = Math.addExact(Math.multiplyExact(Long.parseLong(time.group(2)), MICROS_PER_HOUR),
              minutes * MICROS_PER_MINUTE + seconds * MICROS_PER_SECOND);
          if (time.group(5) != null)
            part = Math.addExact(part, fractionToMicros(time.group(5)));
          micros = Math.addExact(micros, "-".equals(time.group(1)) ? -part : part);
          continue;
        }
        String quantity;
        String unit;
        final Matcher glued = GLUED.matcher(token);
        if (NUMBER.matcher(token).matches()) {
          quantity = token;
          final String next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
          if (next != null && TIME.matcher(next).matches())
            // a number in front of a time is a number of days
            unit = "day";
          else if (next != null && !NUMBER.matcher(next).matches())
            unit = tokens.get(++i);
          else
            unit = bareUnit(field);
        } else if (glued.matches()) {
          quantity = glued.group(1);
          unit = glued.group(2);
        } else if (UNREAD.matcher(token).matches())
          throw PostgreSQLEvaluationException.unsupported("This form of an interval is not supported: \"" + text + "\"");
        else
          throw invalid(text);

        final BigDecimal number = new BigDecimal(quantity.startsWith("+") ? quantity.substring(1) : quantity);
        final String name = unit(unit, text);
        given = once(given, bits(name, number), text);
        switch (name) {
        case "millennium" -> years = field(years, whole(number, unit, text), 1000);
        case "century" -> years = field(years, whole(number, unit, text), 100);
        case "decade" -> years = field(years, whole(number, unit, text), 10);
        case "year" -> years = field(years, whole(number, unit, text), 1);
        case "month" -> months = field(months, whole(number, unit, text), 1);
        case "week" -> days = field(days, whole(number, unit, text), 7);
        case "day" -> days = field(days, whole(number, unit, text), 1);
        case "hour" -> micros = Math.addExact(micros, scaled(number, MICROS_PER_HOUR));
        case "minute" -> micros = Math.addExact(micros, scaled(number, MICROS_PER_MINUTE));
        case "second" -> micros = Math.addExact(micros, scaled(number, MICROS_PER_SECOND));
        case "millisecond" -> micros = Math.addExact(micros, scaled(number, 1_000L));
        default -> micros = Math.addExact(micros, scaled(number, 1L));
        }
      }
      if (ago) {
        years = Math.negateExact(years);
        months = Math.negateExact(months);
        days = Math.negateExact(days);
        micros = Math.negateExact(micros);
      }
    } catch (final ArithmeticException | NumberFormatException e) {
      throw outOfRange();
    }
    // the years and the months together are the months of the value: PostgreSQL gives this overflow its own code
    final long total = years * 12L + months;
    if (total > Integer.MAX_VALUE || total < Integer.MIN_VALUE)
      throw new PostgreSQLEvaluationException("22008", "interval out of range");
    return restrict(new PgInterval((int) total, days, micros), field);
  }

  /** The units of the text so far with one more, which must not be one of them yet. */
  private static int once(final int given, final int unit, final String text) {
    if ((given & unit) != 0)
      throw invalid(text);
    return given | unit;
  }

  private static int bits(final String unit, final BigDecimal number) {
    return switch (unit) {
      case "microsecond" -> MICROSECOND;
      case "millisecond" -> MILLISECOND;
      // a fraction of a second gives the milliseconds and the microseconds too
      case "second" -> number.stripTrailingZeros().scale() > 0 ? ALL_SECONDS : SECOND;
      case "minute" -> MINUTE;
      case "hour" -> HOUR;
      case "day" -> 1 << 5;
      case "week" -> 1 << 6;
      case "month" -> 1 << 7;
      case "year" -> 1 << 8;
      case "decade" -> 1 << 9;
      case "century" -> 1 << 10;
      default -> 1 << 11;
    };
  }

  /** A field of 32 bits with the quantity times the scale added to it. */
  private static int field(final int value, final long quantity, final int scale) {
    return Math.addExact(value, Math.multiplyExact(Math.toIntExact(quantity), scale));
  }

  /** The unit of a bare number: the field of the literal, else the seconds of {@code interval '1'}. */
  private static String bareUnit(final String field) {
    return field == null ? "second" : field;
  }

  /** The name of a unit word as one of the units this parser keeps, or the error PostgreSQL gives for an unknown word. */
  private static String unit(final String word, final String text) {
    switch (word) {
    case "mil", "mils", "millennia", "millennium", "millenniums":
      return "millennium";
    case "c", "cent", "century", "centuries":
      return "century";
    case "dec", "decs", "decade", "decades":
      return "decade";
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
    case "ms", "msec", "msecs", "msecond", "mseconds", "millisecond", "milliseconds":
      return "millisecond";
    case "us", "usec", "usecs", "usecond", "useconds", "microsecond", "microseconds":
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
