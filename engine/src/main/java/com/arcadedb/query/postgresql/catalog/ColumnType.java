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

import com.arcadedb.schema.Type;

/**
 * The PostgreSQL type of a column of the relational view, named as {@code pg_type.typname} names it ({@code int4}, {@code text}),
 * with {@code []} after the element name for an array ({@code text[]}).
 * <p>
 * There is one mapping from the schema to PostgreSQL types, and it is the one the Postgres wire module applies
 * ({@code PostgresType.getTypeFromArcade(Type, String ofType)}). The wire module depends on the engine, so the engine cannot call
 * it: the mapping is written down here and the test {@code ColumnTypeWireParityTest} of the {@code postgresw} module fails when the
 * two differ, except in the places this language decides differently, which the test lists:
 * <ul>
 * <li>{@code MAP}, {@code EMBEDDED} and a list that the wire advertises as {@code json} (a {@code LIST OF} an embedded type, a
 * nested list) are {@code jsonb}: it is the only JSON type with an equality operator, which {@code DISTINCT}, {@code GROUP BY} and
 * {@code ORDER BY} need.</li>
 * <li>{@code LINK} is {@code text}, not {@code varchar}: the value is the record id as text, the same kind of value as the
 * {@code id} column.</li>
 * </ul>
 * A declared {@code LIST OF <t>} is typed from {@code <t>} (a scalar list is an array), an undeclared {@code LIST} is
 * {@code text[]}.
 */
public enum ColumnType {
  BOOL("bool"),
  INT2("int2"),
  INT4("int4"),
  INT8("int8"),
  FLOAT4("float4"),
  FLOAT8("float8"),
  NUMERIC("numeric"),
  DATE("date"),
  TIMESTAMP("timestamp"),
  VARCHAR("varchar"),
  TEXT("text"),
  BYTEA("bytea"),
  JSONB("jsonb"),
  BOOL_ARRAY("bool[]", BOOL),
  INT4_ARRAY("int4[]", INT4),
  INT8_ARRAY("int8[]", INT8),
  FLOAT4_ARRAY("float4[]", FLOAT4),
  FLOAT8_ARRAY("float8[]", FLOAT8),
  NUMERIC_ARRAY("numeric[]", NUMERIC),
  BYTEA_ARRAY("bytea[]", BYTEA),
  TEXT_ARRAY("text[]", TEXT);

  private final String     typeName;
  private final ColumnType elementType;

  ColumnType(final String typeName) {
    this(typeName, null);
  }

  ColumnType(final String typeName, final ColumnType elementType) {
    this.typeName = typeName;
    this.elementType = elementType;
  }

  /** The type as PostgreSQL spells it in a {@code CREATE TABLE}: {@code int4}, {@code text[]}. */
  public String typeName() {
    return typeName;
  }

  public boolean isArray() {
    return elementType != null;
  }

  /** The element type of an array, {@code null} for any other type. */
  public ColumnType elementType() {
    return elementType;
  }

  @Override
  public String toString() {
    return typeName;
  }

  /**
   * The column type of a property of type {@code type}; {@code ofType} is the declared element type of a {@code LIST} (the name
   * after {@code OF}), {@code null} when undeclared, and is ignored for any other type. A {@code null} type is {@code varchar}.
   */
  public static ColumnType of(final Type type, final String ofType) {
    if (type == Type.LIST)
      return listOf(ofType);
    return of(type);
  }

  /** The column type of a property of type {@code type}, a {@code LIST} undeclared. */
  public static ColumnType of(final Type type) {
    if (type == null)
      return VARCHAR;

    return switch (type) {
      case BOOLEAN -> BOOL;
      case INTEGER -> INT4;
      case SHORT, BYTE -> INT2;
      case LONG -> INT8;
      case FLOAT -> FLOAT4;
      case DOUBLE -> FLOAT8;
      case STRING -> VARCHAR;
      case DATETIME, DATETIME_MICROS, DATETIME_NANOS, DATETIME_SECOND -> TIMESTAMP;
      case DATE -> DATE;
      case BINARY -> BYTEA;
      case LIST -> TEXT_ARRAY;
      case ARRAY_OF_SHORTS, ARRAY_OF_INTEGERS -> INT4_ARRAY;
      case ARRAY_OF_LONGS -> INT8_ARRAY;
      case ARRAY_OF_FLOATS -> FLOAT4_ARRAY;
      case ARRAY_OF_DOUBLES -> FLOAT8_ARRAY;
      case MAP, EMBEDDED -> JSONB;
      case LINK -> TEXT;
      case DECIMAL -> NUMERIC;
      default -> VARCHAR;
    };
  }

  private static ColumnType listOf(final String ofType) {
    if (ofType == null || ofType.isBlank())
      return TEXT_ARRAY;

    final Type element = Type.getTypeByName(ofType);
    if (element == null)
      // not a scalar type: the list holds embedded documents of a schema type
      return JSONB;

    return switch (element) {
      case BOOLEAN -> BOOL_ARRAY;
      case INTEGER, SHORT, BYTE -> INT4_ARRAY;
      case LONG -> INT8_ARRAY;
      case FLOAT -> FLOAT4_ARRAY;
      case DOUBLE -> FLOAT8_ARRAY;
      case DECIMAL -> NUMERIC_ARRAY;
      case BINARY -> BYTEA_ARRAY;
      // nested lists, maps, embedded documents and the primitive arrays are not rectangular PostgreSQL arrays
      case MAP, EMBEDDED, LIST, ARRAY_OF_SHORTS, ARRAY_OF_INTEGERS, ARRAY_OF_LONGS, ARRAY_OF_FLOATS, ARRAY_OF_DOUBLES -> JSONB;
      default -> TEXT_ARRAY;
    };
  }
}
