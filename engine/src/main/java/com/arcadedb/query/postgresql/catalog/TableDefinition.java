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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * What a table made by {@code CREATE TABLE} of the {@code postgresql} language declares and the schema of the engine does not
 * record (fork issue #118). It is kept as JSON text in the custom value {@value #CUSTOM_KEY} of the type. A type that has it is
 * shown as the statement declared it: its columns in the declared order, its record id out of {@code *} and listed only when a
 * foreign key refers to it, and the declared primary key and no other. A type without it, one made with the {@code sql} language
 * for example, is shown by the rules of {@link RelationalCatalog} alone.
 * <p>
 * The definition only describes: what a write has to keep (not-null, unique keys, defaults, the dimensions of a vector) is in the
 * schema itself, so it holds for every language. What a later {@code sql} statement changes in the schema wins: a column that it
 * dropped is gone, one that it added follows the declared ones, and a key whose index it dropped is no key any more.
 *
 * @param columns     the declared column names in order; for an edge table the two ends are among them
 * @param textColumns the columns declared {@code text}: the engine's {@code STRING} is {@code varchar} otherwise
 * @param primaryKey  the declared primary key, {@code null} when the table has none
 * @param uniqueKeys  the declared unique constraints, with their names
 * @param defaults    for a column with a default, the text that {@code information_schema.columns.column_default} shows
 * @param foreignKeys the name of the foreign key of an end of an edge, by column name
 * @param timestamptzColumns the columns declared {@code timestamptz}: the engine's {@code DATETIME} is {@code timestamp}
 *                           otherwise, and it holds an instant as its time in UTC (fork issue #223)
 */
public record TableDefinition(List<String> columns, Set<String> textColumns, Table.Key primaryKey, List<Table.Key> uniqueKeys,
    Map<String, String> defaults, Map<String, String> foreignKeys, Set<String> timestamptzColumns) {
  /** The custom value of a type that holds its definition. */
  public static final String CUSTOM_KEY = "postgresql_table";

  /** A table with no {@code timestamptz} column. */
  public TableDefinition(final List<String> columns, final Set<String> textColumns, final Table.Key primaryKey,
      final List<Table.Key> uniqueKeys, final Map<String, String> defaults, final Map<String, String> foreignKeys) {
    this(columns, textColumns, primaryKey, uniqueKeys, defaults, foreignKeys, Set.of());
  }

  /** The definition of the type, {@code null} when it has none or the value cannot be read. */
  public static TableDefinition of(final DocumentType type) {
    final Object value = type.getCustomValue(CUSTOM_KEY);
    if (value == null)
      return null;
    try {
      final JSONObject json = new JSONObject(value.toString());
      final List<Table.Key> unique = new ArrayList<>();
      final JSONArray keys = json.getJSONArray("unique");
      for (int i = 0; i < keys.length(); i++)
        unique.add(key(keys.getJSONObject(i)));
      return new TableDefinition(strings(json.getJSONArray("columns")), new LinkedHashSet<>(strings(json.getJSONArray("text"))),
          json.has("primaryKey") ? key(json.getJSONObject("primaryKey")) : null, unique, map(json.getJSONObject("defaults")),
          map(json.getJSONObject("foreignKeys")),
          json.has("timestamptz") ? new LinkedHashSet<>(strings(json.getJSONArray("timestamptz"))) : Set.of());
    } catch (final RuntimeException e) {
      // someone changed the value by hand: the type is still a table, by the rules of a type without a definition
      LogManager.instance().log(TableDefinition.class, Level.WARNING,
          "The custom value %s of the type %s cannot be read and is ignored", e, CUSTOM_KEY, type.getName());
      return null;
    }
  }

  /** The text kept in the custom value of the type. */
  public String toJson() {
    final JSONObject json = new JSONObject();
    json.put("columns", array(columns));
    json.put("text", array(textColumns));
    if (primaryKey != null)
      json.put("primaryKey", object(primaryKey));
    final JSONArray unique = new JSONArray();
    for (final Table.Key key : uniqueKeys)
      unique.put(object(key));
    json.put("unique", unique);
    json.put("defaults", object(defaults));
    json.put("foreignKeys", object(foreignKeys));
    if (!timestamptzColumns.isEmpty())
      json.put("timestamptz", array(timestamptzColumns));
    return json.toString();
  }

  /** The declared name of the unique constraint over exactly these columns, {@code otherwise} when none was declared over them. */
  String uniqueName(final List<String> keyColumns, final String otherwise) {
    for (final Table.Key key : uniqueKeys)
      if (key.columns().equals(keyColumns))
        return key.name();
    return otherwise;
  }

  private static JSONArray array(final Iterable<String> values) {
    final JSONArray array = new JSONArray();
    for (final String value : values)
      array.put(value);
    return array;
  }

  private static JSONObject object(final Table.Key key) {
    final JSONObject json = new JSONObject();
    json.put("name", key.name());
    json.put("columns", array(key.columns()));
    return json;
  }

  private static JSONObject object(final Map<String, String> map) {
    final JSONObject json = new JSONObject();
    for (final Map.Entry<String, String> entry : map.entrySet())
      json.put(entry.getKey(), entry.getValue());
    return json;
  }

  private static Table.Key key(final JSONObject json) {
    return new Table.Key(json.getString("name"), strings(json.getJSONArray("columns")));
  }

  private static List<String> strings(final JSONArray array) {
    final List<String> values = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++)
      values.add(array.getString(i));
    return values;
  }

  private static Map<String, String> map(final JSONObject json) {
    final Map<String, String> map = new LinkedHashMap<>();
    for (final String name : json.keySet())
      map.put(name, json.getString(name));
    return map;
  }
}
