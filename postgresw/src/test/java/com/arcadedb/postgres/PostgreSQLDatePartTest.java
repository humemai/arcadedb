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
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code date_part} answers {@code double precision} and {@code EXTRACT} answers {@code numeric}, as on PostgreSQL 18: the same
 * type, the same value, and the same errors, over the PostgreSQL protocol.
 */
class PostgreSQLDatePartTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> COLUMNS = List.of("d", "ts", "tz");
  private static final List<String> FIELDS  = List.of("year", "month", "day", "quarter", "week", "isoyear", "dow", "isodow", "doy",
      "decade", "century", "millennium", "epoch", "hour", "minute", "second", "milliseconds", "microseconds");

  @Test
  void datePartIsDoublePrecision() throws Exception {
    try (final GenericContainer<?> container = startPostgres();
        final Connection postgres = postgres(container, "");
        final Connection arcade = arcade("")) {
      both(postgres, arcade, "CREATE TABLE moment (n int PRIMARY KEY, d date, ts timestamp, tz timestamptz)");
      both(postgres, arcade, "INSERT INTO moment VALUES (1, '2026-03-09', '2026-03-09 10:11:12.123456',"
          + " '2026-03-09 10:11:12.123456+00'), (2, '1969-12-31', '1969-12-31 23:59:59.999999', '1969-12-31 23:59:59.999999+00'),"
          + " (3, '2000-01-01', '2000-01-01 00:00:00.000001', '2000-01-01 00:00:00+00'),"
          + " (4, NULL, NULL, NULL), (5, '2024-12-30', '2024-12-30 07:08:09.5', '2024-12-30 07:08:09.1+00')");
      final List<String> queries = new ArrayList<>();
      for (final String column : COLUMNS)
        for (final String field : FIELDS) {
          queries.add("SELECT n, date_part('" + field + "', " + column + ") FROM moment ORDER BY n");
          // EXTRACT has no time field of a date
          if (!"d".equals(column) || !List.of("hour", "minute", "second", "milliseconds", "microseconds").contains(field))
            queries.add("SELECT n, EXTRACT(" + field + " FROM " + column + ") FROM moment ORDER BY n");
        }
      queries.add("SELECT date_part('year', ts)::int AS y, date_part('dow', d) + 1 AS next, date_part('epoch', ts) / 3600 AS h"
          + " FROM moment WHERE date_part('month', ts) = 3");
      queries.add("SELECT date_part('year', ts) AS y, count(*) FROM moment GROUP BY date_part('year', ts) ORDER BY 1");
      queries.add("SELECT floor(date_part('epoch', ts)), sum(date_part('day', d)), max(date_part('second', ts)) FROM moment"
          + " GROUP BY floor(date_part('epoch', ts)) ORDER BY 1");
      queries.add("SELECT n, trunc(date_part('epoch', ts)), trunc(date_part('second', ts)), round(date_part('second', ts)),"
          + " ceil(date_part('second', ts)) FROM moment ORDER BY n");
      for (final String query : queries)
        same(postgres, arcade, query);
      // double precision has no round with a number of digits, and an unknown unit is refused
      sameError(postgres, arcade, "SELECT round(date_part('second', ts), 2) FROM moment", "42883");
      sameError(postgres, arcade, "SELECT date_part('fortnight', ts) FROM moment", "22023");
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }
  }
}
