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

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The time zone of the session that the statement of this thread belongs to (fork issue #237), in which {@code CURRENT_DATE} and
 * {@code LOCALTIMESTAMP} read the clock. A server that has sessions, the Postgres wire, says it before each statement. Without
 * one, as through the engine API, the clock is read in the time zone of the Java virtual machine.
 * <p>
 * A zone is applied when it is a name of the time zone database, in any case, or a zero offset. Every other spelling that
 * PostgreSQL has ({@code 9}, {@code '+09:00'}, a POSIX rule, an interval) is refused where the clock is read, because Java
 * reads the sign of an offset the other way round.
 */
public final class SessionTimeZone {
  private static final ThreadLocal<String> NAME = new ThreadLocal<>();

  private static final Pattern ZERO_OFFSET = Pattern.compile("[+-]?0{1,2}(:?00)?");

  private static final Map<String, String> ZONES = new HashMap<>();

  static {
    for (final String zone : ZoneId.getAvailableZoneIds())
      ZONES.put(zone.toLowerCase(Locale.ENGLISH), zone);
  }

  private SessionTimeZone() {
  }

  /** The time zone of the session, as its {@code TimeZone} setting spells it, for the statements of this thread from now on. */
  public static void set(final String name) {
    NAME.set(name);
  }

  /** No session: the clock of this thread is that of the Java virtual machine again. */
  public static void clear() {
    NAME.remove();
  }

  /**
   * @param reader the expression that reads the clock, for the error
   *
   * @throws PostgreSQLFeatureNotSupportedException (SQLSTATE {@code 0A000}) when the session's time zone cannot be applied
   */
  public static ZoneId zoneOf(final String reader) {
    final String name = NAME.get();
    if (name == null)
      return ZoneId.systemDefault();
    if (ZERO_OFFSET.matcher(name.trim()).matches())
      return ZoneOffset.UTC;
    final String zone = ZONES.get(name.trim().toLowerCase(Locale.ENGLISH));
    if (zone == null)
      throw new PostgreSQLFeatureNotSupportedException(reader + " in the time zone \"" + name + "\" is not supported by the "
          + "postgresql language: send SET TIME ZONE with a name such as 'Asia/Seoul', or SET TIME ZONE 'UTC'");
    return ZoneId.of(zone);
  }
}
