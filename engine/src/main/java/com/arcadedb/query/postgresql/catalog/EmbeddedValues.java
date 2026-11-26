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

import com.arcadedb.database.Document;
import com.arcadedb.database.EmbeddedDocument;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.JsonSerializer;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The embedded documents of a property declared {@code EMBEDDED OF T}, {@code LIST OF T} or {@code MAP OF T} for a document type
 * {@code T}, as the {@code postgresql} language reads them (fork issue #145): the JSON object of the properties, without
 * {@code @type} and {@code @cat}, so that an object the language wrote reads back as written. A value takes the form the engine
 * gives it in JSON (a date is the text that the date format of the database writes). A document of another type than the declared
 * one, a subtype of it, keeps both keys: without them it would read, and be written back, as a document of {@code T}. A write of
 * an object with either key is refused.
 */
public final class EmbeddedValues {
  private EmbeddedValues() {
  }

  /** The type that {@code property} embeds, by its declaration, or {@code null} for a property that declares none. */
  public static String documentType(final Property property) {
    final Type type = property.getType();
    if (type != Type.EMBEDDED && type != Type.LIST && type != Type.MAP)
      return null;
    final String name = property.getOfType();
    return name == null || Type.getTypeByName(name) != null ? null : name;
  }

  /**
   * The value that {@code property} holds, with its embedded documents as JSON objects. Anything else, a value of a property that
   * declares no such type included, is returned as it is.
   */
  static Object read(final Property property, final Object value) {
    final String declared = property == null ? null : documentType(property);
    if (declared == null)
      return value;
    if (property.getType() == Type.LIST && value instanceof List<?> list) {
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list)
        out.add(object(item, declared));
      return out;
    }
    if (property.getType() == Type.MAP && value instanceof Map<?, ?> map) {
      final Map<Object, Object> out = new LinkedHashMap<>();
      for (final Map.Entry<?, ?> entry : map.entrySet())
        out.put(entry.getKey(), object(entry.getValue(), declared));
      return out;
    }
    return object(value, declared);
  }

  /**
   * An embedded document as a JSON object, without {@code @type} and {@code @cat} when its type is exactly {@code declared}; any
   * other value as it is.
   */
  public static Object object(final Object value, final String declared) {
    if (!(value instanceof EmbeddedDocument document))
      return value;
    final JSONObject json = new JsonSerializer(document.getDatabase()).serializeDocument(document);
    if (isOfType(document, declared))
      strip(json, document);
    return json.toMap();
  }

  private static boolean isOfType(final Object value, final String declared) {
    return value instanceof EmbeddedDocument document && declared.equals(document.getTypeName());
  }

  /** Takes {@code @type} and {@code @cat} off the JSON of a document, and off the embedded documents its type declares. */
  private static void strip(final JSONObject json, final Document document) {
    json.remove(Property.TYPE_PROPERTY);
    json.remove(Property.CAT_PROPERTY);
    for (final String name : document.getPropertyNames()) {
      final Property property = document.getType().getPolymorphicPropertyIfExists(name);
      final String declared = property == null ? null : documentType(property);
      if (declared == null)
        continue;
      final Object value = document.get(name);
      if (property.getType() == Type.LIST && value instanceof List<?> list) {
        final JSONArray array = json.getJSONArray(name);
        for (int i = 0; i < list.size(); i++)
          if (isOfType(list.get(i), declared))
            strip(array.getJSONObject(i), (Document) list.get(i));
      } else if (property.getType() == Type.MAP && value instanceof Map<?, ?> map) {
        final JSONObject object = json.getJSONObject(name);
        for (final Map.Entry<?, ?> entry : map.entrySet())
          if (isOfType(entry.getValue(), declared))
            strip(object.getJSONObject(String.valueOf(entry.getKey())), (Document) entry.getValue());
      } else if (isOfType(value, declared))
        strip(json.getJSONObject(name), (Document) value);
    }
  }
}
