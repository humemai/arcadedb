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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The values of the {@code TimeZone} setting (fork issue #270). The wire test {@code PostgreSQLTimeZoneTest} compares them with
 * PostgreSQL 18.
 */
class TimeZoneSettingTest {

  /** The value, its name, and the seconds east of UTC that the zone has on 2026-01-15. */
  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
      UTC                              | UTC              | 0
      gmt                              | GMT              | 0
      Etc/UTC                          | Etc/UTC          | 0
      asia/kolkata                     | Asia/Kolkata     | 19800
      AMERICA/NEW_YORK                 | America/New_York | -18000
      Etc/GMT+9                        | Etc/GMT+9        | -32400
      EST                              | EST              | -18000
      EST5EDT                          | EST5EDT          | -18000
      9                                | <+09>-09         | 32400
      +9                               | <+09>-09         | 32400
      -8                               | <-08>+08         | -28800
      5.5                              | <+05:30>-05:30   | 19800
      0                                | <+00>-00         | 0
      INTERVAL '+05:45' HOUR TO MINUTE | <+05:45>-05:45   | 20700
      interval '-03:00'                | <-03>+03         | -10800
      +09:00                           | +09:00           | -32400
      -05:30                           | -05:30           | 19800
      gmt-09:00                        | GMT-09:00        | 32400
      UTC+3                            | UTC+3            | -10800
      <+0545>-05:45                    | <+0545>-05:45    | 20700
      """)
  void aValueIsReadAsPostgreSqlReadsIt(final String value, final String name, final int east) {
    final TimeZoneSetting setting = TimeZoneSetting.read(value);
    assertThat(setting.name()).isEqualTo(name);
    assertThat(setting.zone().getRules().getOffset(Instant.parse("2026-01-15T00:00:00Z")).getTotalSeconds()).isEqualTo(east);
  }

  @ParameterizedTest
  @ValueSource(strings = { "", "Mars/Olympus", "KST", "Z", "Asia/", "09:75", "+200" })
  void aValueThatNamesNoZoneIsInvalid(final String value) {
    assertThatThrownBy(() -> TimeZoneSetting.read(value)).hasMessage("invalid value for parameter \"TimeZone\": \"" + value + "\"")
        .isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> assertThat(e.getSqlState()).isEqualTo("22023"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "EST5EDT4,M3.2.0,M11.1.0", "CET-1CEST", "19", "+19:00", "INTERVAL '+100:00'" })
  void aZoneThatPostgreSqlReadsAndJavaDoesNotHaveIsRefused(final String value) {
    assertThatThrownBy(() -> TimeZoneSetting.read(value)).isInstanceOf(PostgreSQLFeatureNotSupportedException.class)
        .hasMessageContaining("Run SET TIME ZONE with a name");
  }
}
