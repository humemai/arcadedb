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

import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.SessionTimeZone;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.TypeName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;

import static com.arcadedb.query.postgresql.eval.EvalSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SQLSTATE of a date, a timestamp, or an interval text that the language does not take (fork issue #200): the code of
 * PostgreSQL 18 for a value out of range, {@code 22007} only for a text that PostgreSQL reads in no way either, and
 * {@code 0A000} for a form that PostgreSQL may read. The cases whose code is the one of PostgreSQL are also in the corpus
 * ({@code answers.tsv}, ids that start with {@code err-dt-} and {@code err-interval-}), which runs them on PostgreSQL 18; the
 * {@code 0A000} cases are only here, because the server answers them.
 */
class DateTimeTextTest {

  private static String date(final String text) {
    return sqlState(cast(s(text), "date"));
  }

  /** A parameter of a type that the language lacks: the error names the type as PostgreSQL does, and no Java class (#223). */
  @Test
  void aParameterOfATypeThatTheLanguageLacksIsRefusedByItsName() {
    assertThatThrownBy(() -> value(new Expression.ParameterRef(P, 1), LocalTime.of(10, 11)))
        .isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> assertThat(e.getSqlState()).isEqualTo("0A000"))
        .hasMessageContaining("(time): send it as text").hasMessageNotContaining("java.");
    assertThatThrownBy(() -> value(new Expression.ParameterRef(P, 1), OffsetTime.of(10, 11, 0, 0, ZoneOffset.UTC)))
        .hasMessageContaining("(time with time zone)").hasMessageNotContaining("java.");
  }

  private static String timestamp(final String text) {
    return sqlState(cast(s(text), "timestamp"));
  }

  private static String interval(final String text) {
    return sqlState(new Expression.Cast(P, s(text), new TypeName(P, List.of("interval"), List.of(), null, 0)));
  }

  @ParameterizedTest
  @ValueSource(strings = { "1/8/1999", "18/1/1999", "January 8, 99 BC", "08-Jan-99", "Jan-08-99", "08 Jan 99", "99-Jan-08",
      "08-01-1999", "08 01 99", "99 01 08", "19990108", "990108", "J2451187", "infinity", "-infinity", "epoch", "now", "today",
      "yesterday", "10000-01-01", "2202020-10-05", "2020-01-08 BC", "4714-11-24 BC", "5874897-12-31", "2020-01-08 04:05:06",
      "2020-01-08 +02" })
  void aDateInAFormThatPostgreSQLMayReadIsRefused(final String text) {
    assertThat(date(text)).as(text).isEqualTo("0A000");
  }

  @ParameterizedTest
  @ValueSource(strings = { "infinity", "-infinity", "epoch", "now", "tomorrow 12:00", "Feb 16 17:32:01 1997",
      "Feb 16 17:32:01 5097 BC", "294276-12-31 23:59:59", "2020-01-08 04:05:06 PST", "2020-01-08 04:05:06 GMT+02",
      "2020-01-08 04:05:06 America/New_York", "20200108T040506", "1999.01.08 04:05 PM" })
  void aTimestampInAFormThatPostgreSQLMayReadIsRefused(final String text) {
    assertThat(timestamp(text)).as(text).isEqualTo("0A000");
    assertThat(sqlState(cast(s(text), "timestamptz"))).as(text).isEqualTo("0A000");
  }

  /** A time zone after the time: a timestamp ignores it, and a timestamptz takes the offset from the time (fork issue #223). */
  @ParameterizedTest
  @CsvSource({ "2020-01-08T04:05:06Z, 2020-01-08T04:05:06", "2020-01-08 04:05:06z, 2020-01-08T04:05:06",
      "2020-01-08 04:05:06 UTC, 2020-01-08T04:05:06", "2020-01-08 04:05:06+02, 2020-01-08T02:05:06",
      "2020-01-08 04:05:06-0530, 2020-01-08T09:35:06", "2020-01-08 04:05:06 +05:30:15, 2020-01-07T22:34:51",
      "2020-01-08 04:05+200, 2020-01-08T02:05", "2020-01-08 23:00:00-15:59, 2020-01-09T14:59",
      "2020-01-08, 2020-01-08T00:00" })
  void aTimeZoneAfterATimeIsRead(final String text, final String utc) {
    assertThat(value(cast(s(text), "timestamptz"))).isEqualTo(LocalDateTime.parse(utc).atOffset(ZoneOffset.UTC));
    // the same fields without the zone
    final LocalDateTime written = (LocalDateTime) value(cast(s(text), "timestamp"));
    assertThat(written.toLocalDate()).isEqualTo(LocalDate.parse(text.substring(0, 10)));
  }

  @ParameterizedTest
  @ValueSource(strings = { "2020-01-08 04:05:06+16", "2020-01-08 04:05:06-02:60", "2020-01-08 04:05:06+02:00:60",
      "2020-01-08 04:05:06+9999" })
  void anOffsetThatPostgreSQLDoesNotReadIsOutOfRange(final String text) {
    assertThat(timestamp(text)).as(text).isEqualTo("22009");
    assertThat(sqlState(cast(s(text), "timestamptz"))).as(text).isEqualTo("22009");
  }

  @Test
  void aSessionInAnotherZoneIsRefusedWhereAnInstantIsMade() {
    try {
      SessionTimeZone.set("Asia/Seoul");
      assertThatThrownBy(() -> value(cast(s("2020-01-08 04:05:06Z"), "timestamptz")))
          .isInstanceOf(PostgreSQLFeatureNotSupportedException.class).hasMessageContaining("SET TIME ZONE 'UTC'");
      assertThat(timestamp("2020-01-08 04:05:06Z")).isNull();
      SessionTimeZone.set("Etc/UTC");
      assertThat(sqlState(cast(s("2020-01-08 04:05:06Z"), "timestamptz"))).isNull();
    } finally {
      SessionTimeZone.clear();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "garbage", "not a date", "January", "2020", "2020-01", "12:00:00", "99-08-Jan", "1999-08-Jan",
      "99 08 Jan", "99 Jan 08", "J J 1520447", "1995-08-06  J J J", "1995-08-06 epoch", "1995-08-06 -infinity", "today infinity",
      "-infinity infinity", "epoch 01:01:01", "now epoch", "Y2001M12D27H04M05S06.789", "2020-01-08é" })
  void aTextThatIsNoDateOrTimestampIsInvalid(final String text) {
    assertThat(date(text)).as(text).isEqualTo("22007");
    assertThat(timestamp(text)).as(text).isEqualTo("22007");
  }

  @Test
  void aDateOutsideTheRangeOfPostgreSQLIsOutOfRange() {
    for (final String text : List.of("4714-11-23 BC", "4715-01-01 BC", "5874898-01-01", "99999999999999999999-01-01", "0000-01-01",
        "10000-13-01", "10000-02-30"))
      assertThat(date(text)).as(text).isEqualTo("22008");
    for (final String text : List.of("4714-11-23 23:59:59 BC", "294277-01-01 00:00:00", "0000-01-01 10:00"))
      assertThat(timestamp(text)).as(text).isEqualTo("22008");
  }

  @ParameterizedTest
  @ValueSource(strings = { "infinity", "-infinity", "+infinity", "1-2", "-1-2", "2562047788.1:0:54.775807", "04:05.5", "P1Y",
      "1.5 years", "0.1 millennium", "1.5 decades" })
  void anIntervalInAFormThatPostgreSQLMayReadIsRefused(final String text) {
    assertThat(interval(text)).as(text).isEqualTo("0A000");
  }

  @ParameterizedTest
  @ValueSource(strings = { "2147483647 decades", "-2147483648 centuries", "2147483647 millennium", "1 decade 2147483647 years",
      "-1 century -2147483648 years", "2147483647 years 1 millennium", "2147483648 years", "-2147483649 years",
      "2147483648 months", "2147483648 days", "2147483647 weeks", "2562047789:00:00", "1:70", "1:00:61",
      "9223372036854775807 hours", "-2147483648 days ago" })
  void aFieldThatDoesNotFitIsAFieldOverflow(final String text) {
    assertThat(interval(text)).as(text).isEqualTo("22015");
  }

  @Test
  void yearsAndMonthsThatDoNotFitTogetherAreOutOfRange() {
    for (final String text : List.of("2147483647 years", "-2147483648 years", "1 year 2147483647 months", "178956971 years"))
      assertThat(interval(text)).as(text).isEqualTo("22008");
  }

  @ParameterizedTest
  @ValueSource(strings = { "1 day 1 day", "1 second 2 seconds", "10 milliseconds 20 milliseconds",
      "5.5 seconds 3 milliseconds", "1:20:05 5 microseconds", "1 hour 01:02", "123 11", "123 2:03 -2:04", "1 year 1 yr",
      "1 fortnight", "@ 30 eons ago", "badly formatted interval" })
  void aUnitGivenTwiceAndAnUnknownWordAreInvalid(final String text) {
    assertThat(interval(text)).as(text).isEqualTo("22007");
  }

  @Test
  void theUnitsAboveTheYearAndANumberBeforeATime() {
    assertThat(PgInterval.parse("1 millennium 2 centuries 3 decades 4 years", null)).isEqualTo(new PgInterval(1234 * 12, 0, 0));
    assertThat(PgInterval.parse("1 mil 1 c 1 dec", null)).isEqualTo(new PgInterval(1110 * 12, 0, 0));
    assertThat(PgInterval.parse("1 millenniums 1 cent 1 decs 2 mons", null)).isEqualTo(new PgInterval(1110 * 12 + 2, 0, 0));
    // a number before a time is a number of days
    assertThat(PgInterval.parse("1 2:03:04", null)).isEqualTo(new PgInterval(0, 1, 7_384_000_000L));
    assertThat(PgInterval.parse("-1 +02:03", null)).isEqualTo(new PgInterval(0, -1, 7_380_000_000L));
    assertThat(PgInterval.parse("1 week 1 day 1 millisecond 1 second", null)).isEqualTo(new PgInterval(0, 8, 1_001_000L));
  }

  @Test
  void aTimestampThatLeavesTheRangeIsNotAnswered() {
    final LocalDateTime start = LocalDateTime.of(2000, 1, 1, 0, 0);
    // before 4714-11-24 BC and after the year 294276
    assertThat(sqlState(bin("-", cast(s("2000-01-01"), "date"), span("2483590 days")))).isEqualTo("22008");
    assertThat(sqlState(bin("+", cast(s("2000-01-01"), "date"), span("300 millennium")))).isEqualTo("22008");
    // PostgreSQL answers these, and the language cannot write such a year
    assertThat(sqlState(bin("+", cast(s("2000-01-01"), "date"), span("10 millennium")))).isEqualTo("0A000");
    assertThat(sqlState(bin("-", cast(s("2000-01-01"), "date"), span("3 millennium")))).isEqualTo("0A000");
    assertThat(PgInterval.add(start, new PgInterval(12, 0, 0), false)).isEqualTo(LocalDateTime.of(2001, 1, 1, 0, 0));
  }

  private static Expression span(final String text) {
    return new Expression.Cast(P, s(text), new TypeName(P, List.of("interval"), List.of(), null, 0));
  }
}
