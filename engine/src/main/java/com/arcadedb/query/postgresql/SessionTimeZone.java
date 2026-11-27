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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The time zone of the session that the statement of this thread belongs to (fork issues #237 and #270), and the conversions that
 * depend on it. A server that has sessions, the Postgres wire, says it before each statement.
 * <p>
 * A {@code timestamp with time zone} is an instant, which the language holds at the offset zero. The zone matters only where a
 * local time is involved: a text without a zone, the casts from and to {@code date} and {@code timestamp}, the fields of
 * {@code extract}, the months and days of interval arithmetic, and the text of an answer. {@code CURRENT_DATE} and
 * {@code LOCALTIMESTAMP} read the clock in it.
 * <p>
 * Without a session, as through the engine API, an instant is read and written in UTC, and the clock is read in the time zone of
 * the Java virtual machine.
 */
public final class SessionTimeZone {
  private record Session(String value, TimeZoneSetting setting) {
  }

  private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();

  private SessionTimeZone() {
  }

  /**
   * The time zone of the session, as its {@code TimeZone} setting spells it, for the statements of this thread from now on.
   *
   * @throws com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException SQLSTATE {@code 22023} for a value that names no
   *                                                                          zone
   * @throws PostgreSQLFeatureNotSupportedException                           SQLSTATE {@code 0A000} for a zone that is not
   *                                                                          supported, see {@link TimeZoneSetting#read}
   */
  public static void set(final String value) {
    final Session session = SESSION.get();
    if (session != null && session.value().equals(value))
      return;
    // a value that cannot be read leaves no session of an earlier one behind
    SESSION.remove();
    SESSION.set(new Session(value, TimeZoneSetting.read(value)));
  }

  /** No session. */
  public static void clear() {
    SESSION.remove();
  }

  /** The zone in which an instant is read and written: that of the session, and UTC without one. */
  public static ZoneId ofInstants() {
    final Session session = SESSION.get();
    return session == null ? ZoneOffset.UTC : session.setting().zone();
  }

  /** The zone in which the clock gives a date or a wall clock: that of the session, and the Java virtual machine's without one. */
  public static ZoneId ofClock() {
    final Session session = SESSION.get();
    return session == null ? ZoneId.systemDefault() : session.setting().zone();
  }

  /**
   * The instant of a local time of the session, at the offset zero. As in PostgreSQL, a time that the clocks skip when they go
   * forward takes the offset from before the change, and a time that occurs twice when they go back takes the offset from after
   * it. For the second, {@code LocalDateTime.atZone} would take the other one.
   */
  public static OffsetDateTime instant(final LocalDateTime local) {
    final ZoneRules rules = ofInstants().getRules();
    final ZoneOffsetTransition change = rules.getTransition(local);
    final ZoneOffset offset = change == null ? rules.getOffset(local)
        : change.isGap() ? change.getOffsetBefore() : change.getOffsetAfter();
    return local.atOffset(offset).withOffsetSameInstant(ZoneOffset.UTC);
  }

  /** An instant at the offset that the session's zone has then: its local time, and what a server writes. */
  public static OffsetDateTime shown(final OffsetDateTime instant) {
    return instant.atZoneSameInstant(ofInstants()).toOffsetDateTime();
  }

  /** The size of an offset in seconds as PostgreSQL writes it, without the sign: {@code 09}, {@code 05:30}, {@code 08:27:52}. */
  public static String offsetDigits(final int seconds) {
    final int magnitude = Math.abs(seconds);
    final StringBuilder digits = new StringBuilder(String.format(Locale.ROOT, "%02d", magnitude / 3600));
    if (magnitude % 3600 != 0) {
      digits.append(String.format(Locale.ROOT, ":%02d", magnitude / 60 % 60));
      if (magnitude % 60 != 0)
        digits.append(String.format(Locale.ROOT, ":%02d", magnitude % 60));
    }
    return digits.toString();
  }

  /** A value of an answer, or a list of them, with every instant {@link #shown}. */
  static Object shownValue(final Object value) {
    if (value instanceof OffsetDateTime instant)
      return shown(instant);
    if (value instanceof List<?> list) {
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list)
        out.add(shownValue(item));
      return out;
    }
    return value;
  }
}
