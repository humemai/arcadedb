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
package com.arcadedb.query.postgresql.catalog;

import com.arcadedb.schema.EdgeType;

/**
 * The endpoint tables of an edge type, which the schema does not record (an {@link EdgeType} knows bidirectional, lightweight
 * and unique, nothing else). They are declared, never inferred, in two custom metadata entries of the edge type, the form
 * {@code CREATE EDGE TYPE works_at CUSTOM source_table = 'person', target_table = 'company'} writes. An edge type without them
 * has no foreign key in the relational view. Every reader of the declaration goes through this class, so the foreign keys of the
 * catalog and the {@code source_id} and {@code target_id} paths of the planner cannot disagree.
 */
public final class EdgeEndpoints {
  /** Custom metadata key of the table the {@code source_id} column refers to. */
  public static final String SOURCE_KEY = "source_table";
  /** Custom metadata key of the table the {@code target_id} column refers to. */
  public static final String TARGET_KEY = "target_table";

  private EdgeEndpoints() {
  }

  /** The declared source table of the edge type, or {@code null} when none is declared. */
  public static String sourceTable(final EdgeType type) {
    return declared(type, SOURCE_KEY);
  }

  /** The declared target table of the edge type, or {@code null} when none is declared. */
  public static String targetTable(final EdgeType type) {
    return declared(type, TARGET_KEY);
  }

  private static String declared(final EdgeType type, final String key) {
    final Object value = type.getCustomValue(key);
    return value == null ? null : value.toString();
  }
}
