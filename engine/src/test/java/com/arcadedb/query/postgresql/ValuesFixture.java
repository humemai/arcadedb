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

import com.arcadedb.database.Database;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The fixture of the written values of fork issue #29: a vertex type {@code memory} with one property of each kind that the view
 * shows as {@code jsonb}, an array, {@code bytea} or {@code vector}, and one row, and a vertex type {@code calendar} with a list of
 * dates and one of date times (fork issue #146). {@code postgresql/values-schema.sql} is the same tables and rows for the real
 * server, without the {@code embedding}, which needs pgvector.
 */
public final class ValuesFixture {
  public static final String MEMORY   = "memory";
  public static final String CALENDAR = "calendar";

  private ValuesFixture() {
  }

  public static void createSchema(final Database database) {
    database.transaction(() -> {
      final Schema schema = database.getSchema();
      final VertexType memory = schema.createVertexType(MEMORY);
      memory.createProperty("mid", Type.INTEGER);
      memory.createProperty("tags", Type.LIST, "STRING");
      memory.createProperty("labels", Type.LIST);
      memory.createProperty("flags", Type.LIST, "BOOLEAN");
      memory.createProperty("counts", Type.ARRAY_OF_INTEGERS);
      memory.createProperty("big", Type.LIST, "LONG");
      memory.createProperty("scores", Type.ARRAY_OF_DOUBLES);
      memory.createProperty("ratios", Type.LIST, "FLOAT");
      memory.createProperty("prices", Type.LIST, "DECIMAL");
      memory.createProperty("meta", Type.MAP);
      memory.createProperty("items", Type.LIST, "MAP");
      memory.createProperty("payload", Type.BINARY);
      memory.createProperty("embedding", Type.ARRAY_OF_FLOATS);
      final VertexType calendar = schema.createVertexType(CALENDAR);
      calendar.createProperty("cid", Type.INTEGER);
      calendar.createProperty("days", Type.LIST, "DATE");
      calendar.createProperty("moments", Type.LIST, "DATETIME_MICROS");
    });
  }

  public static void load(final Database database) {
    database.transaction(() -> {
      database.newVertex(MEMORY).set("mid", 1).set("tags", List.of("a", "b")).set("meta", Map.of("k", 1))
          .set("payload", new byte[] { 1, 2 }).save();
      database.newVertex(CALENDAR).set("cid", 1).set("days", List.of(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1)))
          .set("moments", List.of(LocalDateTime.of(2025, 12, 31, 23, 59, 59, 250_000_000))).save();
    });
  }
}
