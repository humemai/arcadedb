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
package com.arcadedb.query.postgresql;

import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A value of the {@code TimeZone} setting, read as {@code check_timezone} of PostgreSQL reads it (fork issue #270), in this order:
 * <ol>
 * <li>{@code INTERVAL '+05:30' HOUR TO MINUTE}: an offset east of UTC;</li>
 * <li>a number, such as {@code 9} or {@code -3.5}: hours east of UTC;</li>
 * <li>a name of the time zone database, in any case, such as {@code Asia/Seoul};</li>
 * <li>a POSIX offset, an abbreviation that may be empty and an offset, such as {@code +09:00}, {@code UTC+3} or
 * {@code <+0545>-05:45}, whose offset counts <b>west</b> of UTC: {@code +09:00} is nine hours behind.</li>
 * </ol>
 * Two things that PostgreSQL reads are refused: a POSIX rule with daylight saving time, and an offset of more than 18 hours.
 * The rules of the named zones are those of the time zone database of Java.
 *
 * @param name the value as PostgreSQL's {@code SHOW TimeZone} spells it
 * @param zone the zone that it names
 */
public record TimeZoneSetting(String name, ZoneId zone) {
  private static final Pattern INTERVAL = Pattern.compile(
      "(?i)interval\\s*'\\s*([+-]?)(\\d{1,3})(?::([0-5]?\\d)(?::([0-5]?\\d))?)?\\s*'(?:\\s+hour(?:\\s+to\\s+minute)?)?");
  private static final Pattern HOURS    = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");
  /** An abbreviation (group 1), an offset (groups 2 to 5), and what follows it (group 6): daylight saving time, or nothing. */
  private static final Pattern POSIX    = Pattern.compile(
      "(<[^<>]*>|[A-Za-z]*)([+-]?)(\\d{1,3})(?::(\\d{1,2})(?::(\\d{1,2}))?)?((?:[A-Za-z<,].*)?)");

  /** The most hours of an offset of a POSIX value that PostgreSQL reads. */
  private static final int MAX_POSIX_HOURS = 167;
  /** The most seconds of an offset that Java has, 18 hours. */
  private static final int MAX_SECONDS     = 18 * 3600;

  private static final Map<String, TimeZoneSetting> NAMES = new HashMap<>();

  static {
    for (final String zone : ZoneId.getAvailableZoneIds())
      register(zone, ZoneId.of(zone));
    // names of the time zone database, which the one of Java may leave out
    register("GMT", ZoneOffset.UTC);
    register("EST", ZoneOffset.ofHours(-5));
    register("MST", ZoneOffset.ofHours(-7));
    register("HST", ZoneOffset.ofHours(-10));
    register("ROC", ZoneId.of("Asia/Taipei"));
  }

  private static void register(final String name, final ZoneId zone) {
    NAMES.putIfAbsent(name.toLowerCase(Locale.ENGLISH), new TimeZoneSetting(name, zone));
  }

  /**
   * @param value the value of the setting, as a {@code SET} or the startup packet of a client gives it
   *
   * @throws PostgreSQLEvaluationException           SQLSTATE {@code 22023} for a value that PostgreSQL does not read either
   * @throws PostgreSQLFeatureNotSupportedException SQLSTATE {@code 0A000} for a zone that PostgreSQL reads and this class
   *                                                 does not
   */
  public static TimeZoneSetting read(final String value) {
    final String text = value.strip();
    final Matcher interval = INTERVAL.matcher(text);
    if (interval.matches())
      return offset(value, seconds(value, interval.group(1), interval.group(2), interval.group(3), interval.group(4)));
    if (HOURS.matcher(text).matches()) {
      final double seconds = Double.parseDouble(text) * 3600;
      if (Math.abs(seconds) >= (MAX_POSIX_HOURS + 1) * 3600)
        throw invalid(value);
      return offset(value, (int) seconds);
    }
    final TimeZoneSetting named = NAMES.get(text.toLowerCase(Locale.ENGLISH));
    if (named != null)
      return named;
    final Matcher posix = POSIX.matcher(text);
    if (!posix.matches())
      throw invalid(value);
    if (!posix.group(6).isEmpty())
      throw unsupported(value, "a POSIX rule with daylight saving time");
    // positive is west of UTC in a POSIX value
    final int west = seconds(value, posix.group(2), posix.group(3), posix.group(4), posix.group(5));
    if (Math.abs(west) > MAX_SECONDS)
      throw unsupported(value, "an offset of more than 18 hours");
    return new TimeZoneSetting(text.toUpperCase(Locale.ENGLISH), ZoneOffset.ofTotalSeconds(-west));
  }

  /** The seconds of a sign, hours, and the minutes and seconds that may be absent. */
  private static int seconds(final String value, final String sign, final String hours, final String minutes, final String seconds) {
    final int h = Integer.parseInt(hours);
    final int m = minutes == null ? 0 : Integer.parseInt(minutes);
    final int s = seconds == null ? 0 : Integer.parseInt(seconds);
    if (h > MAX_POSIX_HOURS || m > 59 || s > 59)
      throw invalid(value);
    final int total = h * 3600 + m * 60 + s;
    return "-".equals(sign) ? -total : total;
  }

  /** A zone of {@code east} seconds east of UTC, which PostgreSQL names as {@code <+09>-09}. */
  private static TimeZoneSetting offset(final String value, final int east) {
    if (Math.abs(east) > MAX_SECONDS)
      throw unsupported(value, "an offset of more than 18 hours");
    final String digits = SessionTimeZone.offsetDigits(east);
    final String name = east < 0 ? "<-" + digits + ">+" + digits : "<+" + digits + ">-" + digits;
    return new TimeZoneSetting(name, ZoneOffset.ofTotalSeconds(east));
  }

  private static PostgreSQLEvaluationException invalid(final String value) {
    return new PostgreSQLEvaluationException("22023", "invalid value for parameter \"TimeZone\": \"" + value + "\"");
  }

  private static PostgreSQLFeatureNotSupportedException unsupported(final String value, final String what) {
    return new PostgreSQLFeatureNotSupportedException("The TimeZone \"" + value + "\" is " + what + ", which the postgresql"
        + " language does not support", "Run SET TIME ZONE with a name such as 'Asia/Seoul'");
  }
}
