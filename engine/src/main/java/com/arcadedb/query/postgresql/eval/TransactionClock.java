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

import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.postgresql.SessionTimeZone;
import com.arcadedb.query.postgresql.catalog.ClockDefault;
import com.arcadedb.query.postgresql.catalog.ColumnType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The time that {@code now()}, {@code CURRENT_TIMESTAMP}, {@code transaction_timestamp()}, {@code LOCALTIMESTAMP}, and
 * {@code CURRENT_DATE} answer (fork issues #222 and #190): as in PostgreSQL, one value for each transaction, read from the
 * clock when it begins. A statement outside a transaction is a transaction of its own.
 * <p>
 * The reading is kept by thread, with the number that the engine gives each begin of a transaction. A server that begins a
 * transaction for a client, as the PostgreSQL protocol does at {@code BEGIN}, calls {@link #now} then; for a transaction that
 * something else began, the first statement of the language in it reads the clock.
 */
public final class TransactionClock {
  private record Reading(long transaction, Instant instant) {
  }

  private static final ThreadLocal<Reading> KEPT = new ThreadLocal<>();

  private TransactionClock() {
  }

  /** The time of the transaction of this thread on {@code database}, to the microsecond. */
  public static Instant now(final DatabaseInternal database) {
    if (!database.isTransactionActive())
      return read();
    final long transaction = database.getTransaction().getBeginSequence();
    Reading reading = KEPT.get();
    if (reading == null || reading.transaction() != transaction) {
      reading = new Reading(transaction, read());
      KEPT.set(reading);
    }
    return reading.instant();
  }

  /**
   * What a default of the clock writes into a column of type {@code type}, as the record holds it: a date, or a wall clock. A
   * {@code date} and a {@code timestamp} take the time of the session (fork issue #237). A {@code timestamptz} takes the
   * instant, as its time in UTC; {@code CURRENT_DATE} gives it midnight of the session, and {@code LOCALTIMESTAMP} the instant
   * of that local time, as the casts of PostgreSQL do (fork issue #270).
   *
   * @param time the time of the transaction of the write
   */
  public static Object defaultValue(final Instant time, final ClockDefault kind, final ColumnType type) {
    if (type == ColumnType.TIMESTAMPTZ) {
      if (kind != ClockDefault.CURRENT_DATE && kind != ClockDefault.LOCALTIMESTAMP)
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
      final LocalDateTime local = LocalDateTime.ofInstant(time, SessionTimeZone.ofInstants());
      return SessionTimeZone.instant(kind == ClockDefault.CURRENT_DATE ? local.toLocalDate().atStartOfDay() : local)
          .toLocalDateTime();
    }
    final LocalDateTime now = LocalDateTime.ofInstant(time, SessionTimeZone.ofClock());
    if (type == ColumnType.DATE)
      return now.toLocalDate();
    return kind == ClockDefault.CURRENT_DATE ? now.toLocalDate().atStartOfDay() : now;
  }

  /** The clock, to the microsecond, as a timestamp keeps it. */
  static Instant read() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }
}
