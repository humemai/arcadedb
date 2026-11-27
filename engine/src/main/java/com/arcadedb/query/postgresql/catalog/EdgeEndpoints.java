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
  /**
   * Custom metadata key of the column of the source table that {@code source_id} refers to (fork issue #213). Without it, or when
   * it names the record id, the end holds record ids.
   */
  public static final String SOURCE_COLUMN_KEY = "source_column";
  /** The same for {@code target_id}. */
  public static final String TARGET_COLUMN_KEY = "target_column";
  /**
   * Custom metadata key of the delete rule of an end that refers to a key: {@code CASCADE}, which is what the engine does and the
   * rule when the entry is absent, or {@code NO ACTION} or {@code RESTRICT}, which only the writes of the {@code postgresql}
   * language enforce.
   */
  public static final String SOURCE_ON_DELETE_KEY = "source_on_delete";
  /** The same for {@code target_id}. */
  public static final String TARGET_ON_DELETE_KEY = "target_on_delete";
  /**
   * Custom metadata key of the update rule of an end that refers to a key: {@code NO ACTION}, the rule when the entry is absent,
   * so that the writes of the {@code postgresql} language refuse a change of a key that links point at, or {@code CASCADE}, which
   * lets the links show the new key.
   */
  public static final String SOURCE_ON_UPDATE_KEY = "source_on_update";
  /** The same for {@code target_id}. */
  public static final String TARGET_ON_UPDATE_KEY = "target_on_update";

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

  /** The custom metadata entry of the edge type under this key as text, {@code null} when the type has none. */
  static String declared(final EdgeType type, final String key) {
    final Object value = type.getCustomValue(key);
    return value == null ? null : value.toString();
  }
}
