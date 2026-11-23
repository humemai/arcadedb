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
import com.arcadedb.query.postgresql.ast.TypeName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static com.arcadedb.query.postgresql.eval.EvalSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code interval} type as far as the date arithmetic of TPC-H needs it (fork issue #13), from section 8.5.4 "Interval
 * Input" and section 9.9 "Date/Time Functions and Operators" of the manual: {@code date + interval} and {@code date - interval}
 * are a {@code timestamp}, the months are added before the days, a day of the month that does not exist in the month it lands in
 * is the last day of that month, and a bare number takes its unit from the field of the literal. The same statements are run on
 * PostgreSQL 18 in the corpus ({@code answers.tsv}, ids that start with {@code interval-}).
 */
class IntervalTest {

  /** {@code interval 'text' field}, field {@code null} for none. */
  private static Expression interval(final String text, final String field) {
    return new Expression.Cast(P, s(text), new TypeName(P, List.of("interval"), List.of(), field, 0));
  }

  private static Expression date(final String text) {
    return cast(s(text), "date");
  }

  private static Expression timestamp(final String text) {
    return cast(s(text), "timestamp");
  }

  private static LocalDateTime at(final int year, final int month, final int day) {
    return LocalDateTime.of(year, month, day, 0, 0);
  }

  @Test
  void aDateMinusAnIntervalIsATimestamp() {
    // the first line of TPC-H Q1: date '1998-12-01' - interval '90' day
    final Expression expression = bin("-", date("1998-12-01"), interval("90", "day"));
    assertThat(typeOf(expression)).isEqualTo(PgType.TIMESTAMP);
    assertThat(value(expression)).isEqualTo(at(1998, 9, 2));
  }

  @Test
  void aDatePlusAMonthIsTheSameDayOfTheNextMonth() {
    // TPC-H Q14: date '1995-09-01' + interval '1' month
    assertThat(value(bin("+", date("1995-09-01"), interval("1", "month")))).isEqualTo(at(1995, 10, 1));
    assertThat(value(bin("+", date("1995-12-15"), interval("1", "month")))).isEqualTo(at(1996, 1, 15));
    assertThat(value(bin("+", date("1994-01-01"), interval("1", "year")))).isEqualTo(at(1995, 1, 1));
  }

  @Test
  void aDayThatDoesNotExistIsTheLastDayOfTheMonth() {
    assertThat(value(bin("+", date("2026-01-31"), interval("1", "month")))).isEqualTo(at(2026, 2, 28));
    assertThat(value(bin("+", date("2024-01-31"), interval("1", "month")))).isEqualTo(at(2024, 2, 29));
    assertThat(value(bin("-", timestamp("2026-03-31 10:30:00"), interval("1 month", null))))
        .isEqualTo(LocalDateTime.of(2026, 2, 28, 10, 30));
  }

  @Test
  void theMonthsAreAddedBeforeTheDays() {
    // months first: 30 January + 1 month is 28 February, and one day later is 1 March (days first would give 28 February)
    assertThat(value(bin("+", date("2026-01-30"), interval("1 month 1 day", null)))).isEqualTo(at(2026, 3, 1));
  }

  @Test
  void anIntervalAddsInEitherOrder() {
    assertThat(value(bin("+", interval("1 day 12 hours 30 minutes", null), date("2026-01-01"))))
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 12, 30));
    assertThat(value(bin("+", date("2026-01-01"), interval("01:02:03.5", null))))
        .isEqualTo(LocalDateTime.of(2026, 1, 1, 1, 2, 3, 500_000_000));
    assertThat(value(bin("+", date("2026-01-10"), interval("3 days ago", null)))).isEqualTo(at(2026, 1, 7));
    assertThat(value(bin("-", date("2026-01-10"), interval("3 days ago", null)))).isEqualTo(at(2026, 1, 13));
  }

  @Test
  void aNullIntervalGivesNull() {
    assertThat(value(bin("+", date("2026-01-01"), cast(nul(), "interval")))).isNull();
  }

  @Test
  void aBareNumberTakesTheUnitOfTheFieldOfTheLiteral() {
    assertThat(value(interval("90", "day"))).isEqualTo(new PgInterval(0, 90, 0));
    assertThat(value(interval("1", "year"))).isEqualTo(new PgInterval(12, 0, 0));
    assertThat(value(interval("2", "month"))).isEqualTo(new PgInterval(2, 0, 0));
    assertThat(value(interval("1", "hour"))).isEqualTo(new PgInterval(0, 0, 3_600_000_000L));
    assertThat(value(interval("1", null))).as("no field: seconds").isEqualTo(new PgInterval(0, 0, 1_000_000L));
  }

  @Test
  void theTextFormOfTheManual() {
    assertThat(PgInterval.parse("1 year 2 mons 3 days 04:05:06", null)).isEqualTo(new PgInterval(14, 3, 14_706_000_000L));
    assertThat(PgInterval.parse("@ 2 weeks", null)).isEqualTo(new PgInterval(0, 14, 0));
    assertThat(PgInterval.parse("1.5 hours", null)).isEqualTo(new PgInterval(0, 0, 5_400_000_000L));
    assertThat(PgInterval.parse("-1 day", null)).isEqualTo(new PgInterval(0, -1, 0));
    assertThat(PgInterval.parse("1day", null)).isEqualTo(new PgInterval(0, 1, 0));
  }

  @Test
  void aFieldRestrictionDropsTheSmallerFields() {
    assertThat(PgInterval.parse("1 year 2 months", "year")).isEqualTo(new PgInterval(12, 0, 0));
    assertThat(PgInterval.parse("1 day 2 hours", "day")).isEqualTo(new PgInterval(0, 1, 0));
    assertThat(PgInterval.parse("1 hour 30 minutes", "hour")).isEqualTo(new PgInterval(0, 0, 3_600_000_000L));
  }

  @Test
  void whatIsNotSupportedIsRefusedAndNeverAnsweredDifferently() {
    assertThat(sqlState(interval("x", null))).isEqualTo("22007");
    assertThat(sqlState(interval("1 fortnight", null))).isEqualTo("22007");
    assertThat(sqlState(interval("", null))).isEqualTo("22007");
    assertThat(sqlState(interval("1.5 days", null))).as("a fraction of a day").isEqualTo("0A000");
    assertThat(sqlState(interval("P1Y2M", null))).as("ISO 8601").isEqualTo("0A000");
    assertThat(sqlState(interval("1", "day to second"))).as("a range of fields").isEqualTo("0A000");
    assertThat(sqlState(bin("+", interval("1 day", null), interval("1 day", null)))).as("interval + interval").isEqualTo("0A000");
    assertThat(sqlState(bin("*", interval("1 day", null), i(2)))).as("interval * number").isEqualTo("0A000");
    assertThat(sqlState(bin("=", interval("1 day", null), interval("1 day", null)))).as("comparison").isEqualTo("0A000");
    assertThat(sqlState(cast(i(1), "interval"))).as("an integer is not read as an interval").isEqualTo("42846");
    assertThat(sqlState(bin("+", interval("99999999999999 years", null), date("2026-01-01")))).isEqualTo("22015");
  }
}
