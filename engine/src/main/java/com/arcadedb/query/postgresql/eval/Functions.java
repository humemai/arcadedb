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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.Locale;

/**
 * Numeric and date functions of the subset, on canonical values: {@code round}, {@code trunc}, {@code floor}, {@code ceil} and
 * {@code extract} (section 9.9 of the manual).
 */
public final class Functions {
  private Functions() {
  }

  /**
   * {@code round(numeric [, digits])}: half away from zero, a negative number of digits rounds to tens, hundreds and so on.
   */
  public static BigDecimal round(final BigDecimal value, final int digits) {
    final BigDecimal rounded = value.setScale(digits, RoundingMode.HALF_UP);
    // rounding up left of the point can give one digit more than numeric holds
    Numbers.checkIntegerDigits(rounded);
    return Numbers.normalizeScale(rounded);
  }

  /** {@code round(float8)}: half to even, as the C function {@code rint} that PostgreSQL calls. */
  public static double round(final double value) {
    return Math.rint(value);
  }

  public static BigDecimal truncate(final BigDecimal value, final int digits) {
    return Numbers.normalizeScale(value.setScale(digits, RoundingMode.DOWN));
  }

  public static BigDecimal floor(final BigDecimal value) {
    return value.setScale(0, RoundingMode.FLOOR);
  }

  public static BigDecimal ceil(final BigDecimal value) {
    return value.setScale(0, RoundingMode.CEILING);
  }

  /**
   * {@code extract(field FROM date or timestamp)}: {@code numeric}, as since PostgreSQL 14. {@code dow} is 0 for Sunday,
   * {@code isodow} is 7 for Sunday, {@code week} is the ISO week, {@code epoch} counts seconds from 1970-01-01 00:00:00 (UTC for
   * a {@code timestamptz}), {@code second} has microseconds as decimals. The time fields are not defined for a {@code date}. The
   * other fields of a {@code timestamptz} are those of its local time in the session's zone, and {@code timezone} is the offset
   * of that zone then, in seconds east of UTC (fork issue #270).
   *
   * @param value a {@link LocalDate}, a {@link LocalDateTime}, or an {@link OffsetDateTime} at the offset zero
   *              ({@code timestamptz})
   */
  public static BigDecimal extract(final String field, final Object value) {
    final boolean isDate = value instanceof LocalDate;
    final boolean zoned = value instanceof OffsetDateTime;
    final OffsetDateTime shown = zoned ? SessionTimeZone.shown((OffsetDateTime) value) : null;
    final LocalDateTime timestamp = isDate ? ((LocalDate) value).atStartOfDay()
        : zoned ? shown.toLocalDateTime() : (LocalDateTime) value;
    final LocalDate date = timestamp.toLocalDate();
    final String unit = field.toLowerCase(Locale.ROOT);
    if (zoned && (unit.equals("timezone") || unit.equals("timezone_hour") || unit.equals("timezone_minute"))) {
      final int offset = shown.getOffset().getTotalSeconds();
      return integer(unit.equals("timezone") ? offset : unit.equals("timezone_hour") ? offset / 3600 : offset / 60 % 60);
    }
    switch (unit) {
    case "year":
      return integer(date.getYear());
    case "month":
      return integer(date.getMonthValue());
    case "day":
      return integer(date.getDayOfMonth());
    case "quarter":
      return integer(date.get(IsoFields.QUARTER_OF_YEAR));
    case "week":
      return integer(date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    case "isoyear":
      return integer(date.get(IsoFields.WEEK_BASED_YEAR));
    case "dow":
      return integer(date.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : date.getDayOfWeek().getValue());
    case "isodow":
      return integer(date.getDayOfWeek().getValue());
    case "doy":
      return integer(date.getDayOfYear());
    case "decade":
      return integer(Math.floorDiv(date.getYear(), 10));
    case "century":
      return integer(date.getYear() > 0 ? (date.getYear() - 1) / 100 + 1 : -((-date.getYear()) / 100 + 1));
    case "millennium":
      return integer(date.getYear() > 0 ? (date.getYear() - 1) / 1000 + 1 : -((-date.getYear()) / 1000 + 1));
    case "epoch": {
      final long seconds = zoned ? shown.toEpochSecond() : timestamp.toEpochSecond(ZoneOffset.UTC);
      return BigDecimal.valueOf(seconds * 1_000_000L + timestamp.getNano() / 1000, 6);
    }
    case "hour":
    case "minute":
    case "second":
    case "milliseconds":
    case "microseconds":
      if (isDate)
        throw PostgreSQLEvaluationException.unsupported("unit \"" + field + "\" not supported for type date");
      if (unit.equals("hour"))
        return integer(timestamp.getHour());
      if (unit.equals("minute"))
        return integer(timestamp.getMinute());
      final long micros = timestamp.getSecond() * 1_000_000L + timestamp.getNano() / 1000;
      if (unit.equals("second"))
        return BigDecimal.valueOf(micros, 6);
      if (unit.equals("milliseconds"))
        return BigDecimal.valueOf(micros, 3);
      return BigDecimal.valueOf(micros);
    default:
      throw new PostgreSQLEvaluationException("22023",
          "unit \"" + field + "\" not recognized for type " + (isDate ? "date" : zoned ? "timestamp with time zone"
              : "timestamp without time zone"));
    }
  }

  /**
   * {@code date_part(field, date or timestamp)}: the field of {@link #extract} as {@code double precision}, computed as
   * PostgreSQL computes it. PostgreSQL reads a {@code date} here as the {@code timestamp} of its midnight, so the time fields of a
   * date are zero and not an error.
   */
  public static double datePart(final String field, final Object value) {
    final BigDecimal exact = extract(field, value instanceof LocalDate date ? date.atStartOfDay() : value);
    return switch (field.toLowerCase(Locale.ROOT)) {
      // whole seconds and the fraction are added as two doubles, which can differ in the last digit from the exact value
      case "second" -> {
        final long micros = exact.movePointRight(6).longValueExact();
        yield micros / 1_000_000L + micros % 1_000_000L / 1_000_000.0;
      }
      case "milliseconds" -> {
        final long micros = exact.movePointRight(3).longValueExact();
        yield micros / 1_000_000L * 1000.0 + micros % 1_000_000L / 1000.0;
      }
      default -> exact.doubleValue();
    };
  }

  private static BigDecimal integer(final long value) {
    return BigDecimal.valueOf(value);
  }
}
