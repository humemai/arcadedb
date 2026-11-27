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
package com.arcadedb.postgres;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code SET TIME ZONE} and {@code SET search_path} are never accepted without effect (fork issue #237): the clock of
 * {@code CURRENT_DATE} and {@code LOCALTIMESTAMP} is read in the session's time zone, and what the server cannot honour is
 * refused.
 */
class PostgreSQLSessionSettingsEffectTest extends PostgreSQLSessionAgainstPostgresTestBase {
  private static final List<String> ZONES = List.of("Asia/Seoul", "asia/kolkata", "America/Los_Angeles", "Etc/GMT+9", "UTC");

  @Test
  void theClockIsReadInTheTimeZoneOfTheSession() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { false, true })
        try (final Connection pg = postgres.connect(simple); final Connection own = arcade.connect(simple)) {
          for (final String zone : ZONES) {
            final String set = "SET TIME ZONE '" + zone + "'";
            assertThat(answer(own, set)).as(set).isEqualTo(answer(pg, set));
            assertSameClock(pg, own, set);
          }
          answer(pg, "SET timezone TO 'Asia/Seoul'");
          answer(own, "SET timezone TO 'Asia/Seoul'");
          assertSameClock(pg, own, "SET timezone TO");
          // a SET of a block that is rolled back is undone with it
          for (final String statement : List.of("BEGIN", "SET TIME ZONE 'America/Los_Angeles'", "ROLLBACK")) {
            answer(pg, statement);
            answer(own, statement);
          }
          assertSameClock(pg, own, "after ROLLBACK");
        }

      // the time zone that the driver names when it connects counts too
      final TimeZone before = TimeZone.getDefault();
      try {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
        try (final Connection pg = postgres.connect(false); final Connection own = arcade.connect(false)) {
          assertSameClock(pg, own, "the startup parameter");
          answer(pg, "SET TIME ZONE 'UTC'");
          answer(own, "SET TIME ZONE 'UTC'");
          assertSameClock(pg, own, "SET over the startup parameter");
          answer(pg, "RESET timezone");
          answer(own, "RESET timezone");
          assertSameClock(pg, own, "RESET");
        }
      } finally {
        TimeZone.setDefault(before);
      }
    });
  }

  /** An offset counts as in PostgreSQL, and a value that names no zone is refused as there (fork issue #270). */
  @Test
  void anOffsetCountsAndAValueThatNamesNoZoneIsRefused() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { false, true })
        try (final Connection pg = postgres.connect(simple); final Connection own = arcade.connect(simple)) {
          for (final String zone : List.of("9", "'+09:00'")) {
            final String set = "SET TIME ZONE " + zone;
            assertThat(answer(own, set)).as(set).isEqualTo(answer(pg, set));
            assertSameClock(pg, own, set);
          }
          final String none = "SET TIME ZONE 'Mars/Olympus'";
          assertThat(answer(own, none)).as(none).isEqualTo(answer(pg, none)).isEqualTo("error 22023");
          // the zone of before stays
          assertSameClock(pg, own, none);
        }
    });
  }

  @Test
  void aSearchPathThatLeavesOutPublicRefusesTheStatements() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { false, true })
        try (final Connection pg = postgres.connect(simple); final Connection own = arcade.connect(simple)) {
          final String table = "t" + (simple ? 1 : 2);
          for (final String statement : List.of("CREATE TABLE " + table + " (id bigint PRIMARY KEY)",
              "INSERT INTO " + table + " (id) VALUES (1)")) {
            answer(pg, statement);
            assertThat(answer(own, statement)).as(statement).doesNotStartWith("error");
          }
          final String read = "SELECT count(*) FROM " + table;
          for (final String path : List.of("SET search_path TO public", "SET search_path TO app, public",
              "SET search_path = 'public'", "RESET search_path")) {
            assertThat(answer(own, path)).as(path).isEqualTo(answer(pg, path));
            assertThat(answer(own, read)).as(path).isEqualTo(answer(pg, read)).isEqualTo("rows [1]");
          }
          final String away = "SET search_path TO no_such_schema";
          assertThat(answer(own, away)).isEqualTo(answer(pg, away));
          // PostgreSQL finds no table; here the statement is refused with what to send
          assertThat(answer(pg, read)).isEqualTo("error 42P01");
          assertThat(answer(own, read)).isEqualTo("error 0A000");
          assertThat(errorMessage(own, "INSERT INTO " + table + " (id) VALUES (2)")).contains("SET search_path TO public");
          answer(own, "SET search_path TO public");
          assertThat(answer(own, read)).isEqualTo("rows [1]");
        }
    });
  }

  /** CURRENT_DATE is that of PostgreSQL, and LOCALTIMESTAMP is the same time within the minutes that two statements can differ. */
  private static void assertSameClock(final Connection postgres, final Connection arcade, final String description)
      throws SQLException {
    assertThat(answer(arcade, "SELECT CURRENT_DATE")).as(description).isEqualTo(answer(postgres, "SELECT CURRENT_DATE"));
    final Timestamp expected = localTimestamp(postgres);
    final Timestamp actual = localTimestamp(arcade);
    assertThat(Duration.between(expected.toInstant(), actual.toInstant()).abs()).as(description + ": " + expected + " and " + actual)
        .isLessThan(Duration.ofMinutes(5));
  }

  private static Timestamp localTimestamp(final Connection connection) {
    final String answer = answer(connection, "SELECT LOCALTIMESTAMP");
    assertThat(answer).startsWith("rows [");
    return Timestamp.valueOf(answer.substring("rows [".length(), answer.length() - 1));
  }
}
