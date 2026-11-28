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
package com.arcadedb.schema;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A foreign key of a type (fork issue #228): the values of {@code columns} of every record of the type are the values of
 * {@code referencedColumns} of a record of {@code referencedType}, unless one of them is null. The engine holds it for every
 * language, see {@link com.arcadedb.database.ForeignKeyChecks}.
 * <p>
 * The declaration is the custom value {@link #CUSTOM_KEY} of the referring type: the text of a JSON array of the keys. The referenced
 * columns need a unique index of the referenced type over exactly those properties, and the referring columns an index of the
 * referring type over the same columns in the order of that unique index (any index over them, unique or not).
 *
 * @param columns           the referring properties
 * @param referencedColumns the referenced properties, one for each referring one, in the same order
 */
public record ForeignKey(String name, List<String> columns, String referencedType, List<String> referencedColumns, Action onDelete,
    Action onUpdate) {

  /**
   * The custom value of a type that holds its foreign keys. Custom values are open to users, so the ones the engine acts on have
   * the prefix {@code arcadedb_}, which keeps them apart from a name of a user, such as {@code foreignKeys} (fork issue #313).
   * The prefix has no dot, so {@code sql} reads the name as it is written.
   */
  public static final String CUSTOM_KEY = "arcadedb_foreignKeys";

  /** What happens to the referring records when a referenced record is deleted or its key changes. */
  public enum Action {
    NO_ACTION, RESTRICT, CASCADE, SET_NULL
  }

  public ForeignKey {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(referencedType, "referencedType");
    Objects.requireNonNull(onDelete, "onDelete");
    Objects.requireNonNull(onUpdate, "onUpdate");
    columns = List.copyOf(columns);
    referencedColumns = List.copyOf(referencedColumns);
    if (columns.isEmpty() || columns.size() != referencedColumns.size())
      throw new IllegalArgumentException("A foreign key needs the same number of referring and referenced columns: " + name);
  }

  /** The referring columns in the order of the properties of the referenced unique index. */
  public List<String> columnsInKeyOrder(final List<String> keyProperties) {
    final List<String> ordered = new ArrayList<>(keyProperties.size());
    for (final String property : keyProperties)
      ordered.add(columns.get(referencedColumns.indexOf(property)));
    return ordered;
  }

  /** The text of the custom value that holds {@code keys}. */
  public static String toJson(final List<ForeignKey> keys) {
    final JSONArray array = new JSONArray();
    for (final ForeignKey key : keys) {
      final JSONObject json = new JSONObject();
      json.put("name", key.name);
      json.put("columns", new JSONArray(key.columns));
      json.put("table", key.referencedType);
      json.put("references", new JSONArray(key.referencedColumns));
      json.put("onDelete", key.onDelete.name());
      json.put("onUpdate", key.onUpdate.name());
      array.put(json);
    }
    return array.toString();
  }

  /** The keys that a custom value holds. Fails with a runtime exception for a value that is no such text. */
  public static List<ForeignKey> fromJson(final String text) {
    final JSONArray array = new JSONArray(text);
    final List<ForeignKey> keys = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++) {
      final JSONObject json = array.getJSONObject(i);
      keys.add(new ForeignKey(json.getString("name"), strings(json.getJSONArray("columns")), json.getString("table"),
          strings(json.getJSONArray("references")), Action.valueOf(json.getString("onDelete", Action.NO_ACTION.name())),
          Action.valueOf(json.getString("onUpdate", Action.NO_ACTION.name()))));
    }
    return keys;
  }

  private static List<String> strings(final JSONArray array) {
    final List<String> strings = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++)
      strings.add(array.getString(i));
    return strings;
  }
}
