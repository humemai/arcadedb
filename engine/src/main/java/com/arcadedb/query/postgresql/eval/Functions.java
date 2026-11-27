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
   * this language), {@code second} has microseconds as decimals. The time fields are not defined for a {@code date}.
   *
   * @param value a {@link LocalDate}, a {@link LocalDateTime}, or an {@link OffsetDateTime} in UTC ({@code timestamptz})
   */
  public static BigDecimal extract(final String field, final Object value) {
    final boolean isDate = value instanceof LocalDate;
    final boolean zoned = value instanceof OffsetDateTime;
    final LocalDateTime timestamp = isDate ? ((LocalDate) value).atStartOfDay()
        : zoned ? ((OffsetDateTime) value).toLocalDateTime() : (LocalDateTime) value;
    final LocalDate date = timestamp.toLocalDate();
    final String unit = field.toLowerCase(Locale.ROOT);
    // the offset of the session, which is in UTC
    if (zoned && (unit.equals("timezone") || unit.equals("timezone_hour") || unit.equals("timezone_minute")))
      return integer(0);
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
      final long seconds = timestamp.toEpochSecond(ZoneOffset.UTC);
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

  private static BigDecimal integer(final long value) {
    return BigDecimal.valueOf(value);
  }
}
