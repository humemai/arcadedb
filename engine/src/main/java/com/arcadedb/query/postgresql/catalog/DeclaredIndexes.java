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

import com.arcadedb.log.LogManager;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The indexes that {@code CREATE INDEX} of the {@code postgresql} language made on a type, by the names PostgreSQL knows them by
 * (fork issue #125). The engine names an index after its type and properties, and one index of the engine serves every
 * {@code CREATE INDEX} over the same columns, so the names are kept as JSON text in the custom value {@value #CUSTOM_KEY} of the
 * type. A name lives while its index does.
 */
public final class DeclaredIndexes {
  /** The custom value of a type that holds the names. */
  public static final String CUSTOM_KEY = "postgresql_indexes";

  /**
   * One name.
   *
   * @param columns the columns the statement named
   * @param unique  whether the statement said {@code UNIQUE}
   * @param engine  whether an index of the engine over these columns stands behind the name; not so for the ends of an edge
   */
  public record Index(List<String> columns, boolean unique, boolean engine) {
  }

  private DeclaredIndexes() {
  }

  /** The names of the type, without those whose index a later statement dropped. */
  public static Map<String, Index> of(final DocumentType type) {
    final Map<String, Index> indexes = new LinkedHashMap<>();
    final Object value = type.getCustomValue(CUSTOM_KEY);
    if (value == null)
      return indexes;
    try {
      final JSONObject json = new JSONObject(value.toString());
      for (final String name : json.keySet()) {
        final JSONObject entry = json.getJSONObject(name);
        final JSONArray array = entry.getJSONArray("columns");
        final List<String> columns = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++)
          columns.add(array.getString(i));
        final boolean engine = entry.getBoolean("engine");
        if (!engine || type.getPolymorphicIndexByProperties(columns.toArray(new String[0])) != null)
          indexes.put(name, new Index(columns, entry.getBoolean("unique"), engine));
      }
    } catch (final RuntimeException e) {
      // someone changed the value by hand
      LogManager.instance().log(DeclaredIndexes.class, Level.WARNING,
          "The custom value %s of the type %s cannot be read and is ignored", e, CUSTOM_KEY, type.getName());
      indexes.clear();
    }
    return indexes;
  }

  /** Keeps one more name on the type. */
  public static void add(final DocumentType type, final String name, final Index index) {
    final Map<String, Index> indexes = of(type);
    indexes.put(name, index);
    write(type, indexes);
  }

  /** Takes names off the type (fork issue #230). */
  public static void remove(final DocumentType type, final Collection<String> names) {
    final Map<String, Index> indexes = of(type);
    indexes.keySet().removeAll(names);
    write(type, indexes);
  }

  private static void write(final DocumentType type, final Map<String, Index> indexes) {
    final JSONObject json = new JSONObject();
    for (final Map.Entry<String, Index> entry : indexes.entrySet()) {
      final JSONArray columns = new JSONArray();
      for (final String column : entry.getValue().columns())
        columns.put(column);
      final JSONObject object = new JSONObject();
      object.put("columns", columns);
      object.put("unique", entry.getValue().unique());
      object.put("engine", entry.getValue().engine());
      json.put(entry.getKey(), object);
    }
    type.setCustomValue(CUSTOM_KEY, json.toString());
  }
}
