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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.exception.SchemaException;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.vector.LSMVectorIndex;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.EmbeddedValues;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The form in which a record holds a value of {@code jsonb}, of an array or of {@code vector}, by the type of the property. The
 * value comes in the canonical class of the column ({@code ValueInput}); what goes out reads back as the same value through this
 * language, through {@code sql} and through openCypher. A value that the property cannot hold as it is, so that it would read
 * back changed or not at all, is refused with {@code 0A000} and never stored differently (fork issue #29):
 * <ul>
 * <li>a {@code MAP} holds a JSON object and no array, and no top level key {@code @type}, which the engine reads as the name of an
 * embedded document type;</li>
 * <li>a {@code LIST OF MAP} holds an array of objects, a {@code LIST OF LIST} an array of arrays;</li>
 * <li>a typed {@code LIST} and the primitive arrays hold no null element;</li>
 * <li>{@code EMBEDDED OF T}, {@code LIST OF T} and {@code MAP OF T} for a document type {@code T} hold an object, an array of
 * objects and an object of objects, each stored as an embedded document of {@code T} that reads back as the same object
 * ({@link EmbeddedValues}, fork issue #145);</li>
 * <li>an {@code EMBEDDED} property without a declared type, a collection of links, and a list of dates are not written.</li>
 * </ul>
 * A vector that does not have the dimensions of its column ({@code vector(n)}) or of the {@code LSM_VECTOR} index of its property
 * is {@code 22000}, as pgvector refuses a value of the wrong dimensions for a {@code vector(n)} column.
 */
final class StoredValues {
  private StoredValues() {
  }

  /** Whether the column is of a type whose values need this form. */
  static boolean applies(final Column column) {
    final ColumnType type = column.type();
    return type.isArray() || type == ColumnType.JSONB || type == ColumnType.VECTOR;
  }

  static Object of(final Database database, final Table table, final Column column, final Object value) {
    final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
    if (property == null)
      return value;
    final String embedded = EmbeddedValues.documentType(property);
    if (embedded != null)
      return documents(database, column, property.getType(), embedded, value);
    final String ofType = property.getOfType();
    switch (property.getType()) {
    case MAP:
      return object(column, value);
    case LIST: {
      if (!(value instanceof List<?> list))
        throw unsupported(column, "it holds a list: only a JSON array can be stored in it, not an object");
      if (ofType == null)
        return new ArrayList<>(list);
      final Type element = Type.getTypeByName(ofType);
      if (column.type() == ColumnType.JSONB && element != Type.MAP && element != Type.LIST)
        throw unsupported(column, "it is a LIST OF " + ofType);
      if (column.type() == ColumnType.TEXT_ARRAY && element != Type.STRING)
        throw unsupported(column, "it is a LIST OF " + ofType);
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list) {
        if (item == null)
          throw unsupported(column, "a LIST OF " + ofType + " holds no null element");
        if (element == Type.MAP)
          out.add(object(column, item));
        else if (element == Type.LIST && !(item instanceof List<?>))
          throw unsupported(column, "every element of it is an array");
        else if (element == Type.SHORT)
          out.add((short) bounded(item, Short.MIN_VALUE, Short.MAX_VALUE, "smallint"));
        else if (element == Type.BYTE)
          out.add((byte) bounded(item, Byte.MIN_VALUE, Byte.MAX_VALUE, "tinyint"));
        else
          out.add(item);
      }
      return out;
    }
    case ARRAY_OF_SHORTS: {
      final List<?> list = noNulls(column, value);
      final short[] out = new short[list.size()];
      for (int i = 0; i < out.length; i++)
        out[i] = (short) bounded(list.get(i), Short.MIN_VALUE, Short.MAX_VALUE, "smallint");
      return out;
    }
    case ARRAY_OF_INTEGERS: {
      final List<?> list = noNulls(column, value);
      final int[] out = new int[list.size()];
      for (int i = 0; i < out.length; i++)
        out[i] = ((Number) list.get(i)).intValue();
      return out;
    }
    case ARRAY_OF_LONGS: {
      final List<?> list = noNulls(column, value);
      final long[] out = new long[list.size()];
      for (int i = 0; i < out.length; i++)
        out[i] = ((Number) list.get(i)).longValue();
      return out;
    }
    case ARRAY_OF_DOUBLES: {
      final List<?> list = noNulls(column, value);
      final double[] out = new double[list.size()];
      for (int i = 0; i < out.length; i++)
        out[i] = ((Number) list.get(i)).doubleValue();
      return out;
    }
    case ARRAY_OF_FLOATS: {
      final float[] vector = (float[]) value;
      checkDimensions(table, column, vector);
      // the record gets its own copy: the caller may change the array it passed as a parameter
      return vector.clone();
    }
    case EMBEDDED:
      throw unsupported(column, "it is an EMBEDDED document with no declared document type");
    default:
      return value;
    }
  }

  /**
   * The value of a property that embeds documents of the type {@code name}: an object, or, in a {@code LIST} or a {@code MAP}, an
   * array of objects or an object of objects. The engine turns each object into an embedded document when the property is set.
   */
  private static Object documents(final Database database, final Column column, final Type holder, final String name,
      final Object value) {
    final Schema schema = database.getSchema();
    if (!schema.existsType(name) || schema.getType(name) instanceof VertexType || schema.getType(name) instanceof EdgeType)
      throw unsupported(column, name + " is not a document type, so no document of it is embedded");
    if (holder == Type.LIST) {
      if (!(value instanceof List<?> list))
        throw unsupported(column, "it holds a list: only a JSON array can be stored in it, not an object");
      final List<Object> out = new ArrayList<>(list.size());
      for (final Object item : list)
        out.add(document(database, column, name, item));
      return out;
    }
    if (holder == Type.MAP) {
      final Map<String, Object> out = object(column, value);
      out.replaceAll((key, item) -> document(database, column, name, item));
      return out;
    }
    return document(database, column, name, value);
  }

  /**
   * The object of one embedded document of the type {@code name}. A document is built from it as the engine would, and the object is
   * refused when that document does not read back as the same object: a value that the type converts to another one, or a key that
   * the engine takes as its own, such as {@code @type}. The constraints of the type ({@code MANDATORY}, {@code MIN}) are checked
   * with those of the row.
   */
  private static Map<String, Object> document(final Database database, final Column column, final String name, final Object value) {
    if (!(value instanceof Map<?, ?> map))
      throw unsupported(column, "each embedded document of the type " + name + " is written from a JSON object");
    final Map<String, Object> object = new LinkedHashMap<>();
    for (final Map.Entry<?, ?> entry : map.entrySet())
      object.put((String) entry.getKey(), entry.getValue());
    final String difference;
    try {
      final Document built = ((DatabaseInternal) database).newEmbeddedDocument(null, name).fromMap(object);
      difference = difference(object, EmbeddedValues.object(built, name), name);
    } catch (final IllegalArgumentException | ValidationException | SchemaException e) {
      throw unsupported(column, "the type " + name + " cannot hold the object: " + e.getMessage());
    }
    if (difference != null)
      throw unsupported(column, "the object would not read back as written, because of the value at " + difference);
    return object;
  }

  /**
   * Where two JSON values differ, or {@code null} when they are the same: numbers by their value, objects by their keys in any
   * order.
   */
  private static String difference(final Object written, final Object read, final String path) {
    if (written instanceof Map<?, ?> map) {
      if (!(read instanceof Map<?, ?> other))
        return path;
      for (final Map.Entry<?, ?> entry : map.entrySet()) {
        final String at = path + "." + entry.getKey();
        final String found = other.containsKey(entry.getKey()) ? difference(entry.getValue(), other.get(entry.getKey()), at) : at;
        if (found != null)
          return found;
      }
      for (final Object key : other.keySet())
        if (!map.containsKey(key))
          return path + "." + key;
      return null;
    }
    if (written instanceof List<?> list) {
      if (!(read instanceof List<?> other) || other.size() != list.size())
        return path;
      for (int i = 0; i < list.size(); i++) {
        final String found = difference(list.get(i), other.get(i), path + "[" + i + "]");
        if (found != null)
          return found;
      }
      return null;
    }
    if (written instanceof Number number && read instanceof Number other)
      return new BigDecimal(number.toString()).compareTo(new BigDecimal(other.toString())) == 0 ? null : path;
    return Objects.equals(written, read) ? null : path;
  }

  private static Map<String, Object> object(final Column column, final Object value) {
    if (!(value instanceof Map<?, ?> map))
      throw unsupported(column, "it holds a MAP: only a JSON object can be stored in it, not an array");
    if (map.containsKey("@type"))
      throw unsupported(column, "a top level key \"@type\" would be read as the name of an embedded document type");
    final Map<String, Object> out = new LinkedHashMap<>();
    for (final Map.Entry<?, ?> entry : map.entrySet())
      out.put((String) entry.getKey(), entry.getValue());
    return out;
  }

  private static List<?> noNulls(final Column column, final Object value) {
    final List<?> list = (List<?>) value;
    for (final Object item : list)
      if (item == null)
        throw unsupported(column, "a primitive array holds no null element");
    return list;
  }

  private static long bounded(final Object item, final long minimum, final long maximum, final String type) {
    final long number = ((Number) item).longValue();
    if (number < minimum || number > maximum)
      throw new PostgreSQLEvaluationException("22003", type + " out of range");
    return number;
  }

  private static void checkDimensions(final Table table, final Column column, final float[] vector) {
    // the property of a column declared vector(n) has n as its least and its most number of values
    final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
    if (property != null && property.getMin() != null && property.getMin().equals(property.getMax())
        && !property.getMin().equals(Integer.toString(vector.length)))
      throw new PostgreSQLEvaluationException("22000",
          "expected " + property.getMin() + " dimensions, not " + vector.length);
    final TypeIndex index = table.documentType().getPolymorphicIndexByProperties(column.property());
    if (index == null || index.getType() != Schema.INDEX_TYPE.LSM_VECTOR)
      return;
    for (final IndexInternal bucket : index.getIndexesOnBuckets())
      if (bucket instanceof LSMVectorIndex vectors && vectors.getDimensions() > 0 && vectors.getDimensions() != vector.length)
        throw new PostgreSQLEvaluationException("22000",
            "expected " + vectors.getDimensions() + " dimensions, not " + vector.length);
  }

  private static RuntimeException unsupported(final Column column, final String reason) {
    return PostgreSQLEvaluationException.unsupported(
        "This value cannot be written to the column \"" + column.name() + "\" of type " + column.type() + " yet: " + reason);
  }
}
