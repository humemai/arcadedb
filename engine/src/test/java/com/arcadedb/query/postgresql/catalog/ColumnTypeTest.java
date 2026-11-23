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

import com.arcadedb.TestHelper;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping from the schema to PostgreSQL types, one assertion per kind of property. The same mapping is checked against the one
 * of the Postgres wire module by {@code ColumnTypeWireParityTest} in the {@code postgresw} module.
 */
class ColumnTypeTest extends TestHelper {

  @Test
  void everyTypeHasTheColumnTypeTheDecisionsName() {
    final Map<Type, ColumnType> expected = new EnumMap<>(Type.class);
    expected.put(Type.BOOLEAN, ColumnType.BOOL);
    expected.put(Type.INTEGER, ColumnType.INT4);
    expected.put(Type.SHORT, ColumnType.INT2);
    expected.put(Type.BYTE, ColumnType.INT2);
    expected.put(Type.LONG, ColumnType.INT8);
    expected.put(Type.FLOAT, ColumnType.FLOAT4);
    expected.put(Type.DOUBLE, ColumnType.FLOAT8);
    expected.put(Type.DECIMAL, ColumnType.NUMERIC);
    expected.put(Type.STRING, ColumnType.VARCHAR);
    expected.put(Type.DATE, ColumnType.DATE);
    expected.put(Type.DATETIME, ColumnType.TIMESTAMP);
    expected.put(Type.DATETIME_SECOND, ColumnType.TIMESTAMP);
    expected.put(Type.DATETIME_MICROS, ColumnType.TIMESTAMP);
    expected.put(Type.DATETIME_NANOS, ColumnType.TIMESTAMP);
    expected.put(Type.BINARY, ColumnType.BYTEA);
    expected.put(Type.LIST, ColumnType.TEXT_ARRAY);
    expected.put(Type.MAP, ColumnType.JSONB);
    expected.put(Type.EMBEDDED, ColumnType.JSONB);
    expected.put(Type.LINK, ColumnType.TEXT);
    expected.put(Type.ARRAY_OF_SHORTS, ColumnType.INT4_ARRAY);
    expected.put(Type.ARRAY_OF_INTEGERS, ColumnType.INT4_ARRAY);
    expected.put(Type.ARRAY_OF_LONGS, ColumnType.INT8_ARRAY);
    expected.put(Type.ARRAY_OF_FLOATS, ColumnType.VECTOR);
    expected.put(Type.ARRAY_OF_DOUBLES, ColumnType.FLOAT8_ARRAY);
    // the types the wire has no PostgreSQL name for are text there, so they are here
    expected.put(Type.OFFSET_TIME, ColumnType.VARCHAR);
    expected.put(Type.LOCAL_TIME, ColumnType.VARCHAR);
    expected.put(Type.ZONED_DATETIME, ColumnType.VARCHAR);
    expected.put(Type.DURATION, ColumnType.VARCHAR);

    for (final Type type : Type.values())
      assertThat(ColumnType.of(type)).as(type.name()).isEqualTo(expected.get(type));
    assertThat(expected).as("every type is listed").hasSize(Type.values().length);
    assertThat(ColumnType.of(null)).isEqualTo(ColumnType.VARCHAR);
  }

  @Test
  void aDeclaredListIsTypedFromItsElementAndAnUndeclaredOneIsTextArray() {
    assertThat(ColumnType.of(Type.LIST, null)).isEqualTo(ColumnType.TEXT_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "")).isEqualTo(ColumnType.TEXT_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "STRING")).isEqualTo(ColumnType.TEXT_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "DATE")).isEqualTo(ColumnType.TEXT_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "BOOLEAN")).isEqualTo(ColumnType.BOOL_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "INTEGER")).isEqualTo(ColumnType.INT4_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "SHORT")).isEqualTo(ColumnType.INT4_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "BYTE")).isEqualTo(ColumnType.INT4_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "LONG")).isEqualTo(ColumnType.INT8_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "FLOAT")).isEqualTo(ColumnType.FLOAT4_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "DOUBLE")).isEqualTo(ColumnType.FLOAT8_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "DECIMAL")).isEqualTo(ColumnType.NUMERIC_ARRAY);
    assertThat(ColumnType.of(Type.LIST, "BINARY")).isEqualTo(ColumnType.BYTEA_ARRAY);
    // a list of embedded documents or of lists is a JSON document, not a rectangular array
    assertThat(ColumnType.of(Type.LIST, "address_t")).isEqualTo(ColumnType.JSONB);
    assertThat(ColumnType.of(Type.LIST, "MAP")).isEqualTo(ColumnType.JSONB);
    assertThat(ColumnType.of(Type.LIST, "EMBEDDED")).isEqualTo(ColumnType.JSONB);
    assertThat(ColumnType.of(Type.LIST, "LIST")).isEqualTo(ColumnType.JSONB);
    // the element type of the declaration is ignored for a type that is not a list
    assertThat(ColumnType.of(Type.INTEGER, "STRING")).isEqualTo(ColumnType.INT4);
  }

  @Test
  void arraysNameTheirElementTypeAndTypeNamesAreThoseOfPgType() {
    assertThat(ColumnType.TEXT_ARRAY.isArray()).isTrue();
    assertThat(ColumnType.TEXT_ARRAY.elementType()).isEqualTo(ColumnType.TEXT);
    assertThat(ColumnType.INT4_ARRAY.typeName()).isEqualTo("int4[]");
    assertThat(ColumnType.JSONB.isArray()).isFalse();
    assertThat(ColumnType.JSONB.elementType()).isNull();
    for (final ColumnType type : ColumnType.values())
      if (type.isArray())
        assertThat(type.typeName()).isEqualTo(type.elementType().typeName() + "[]");
  }

  @Test
  void aTypeOfEveryKindOfPropertyGetsItsColumnType() {
    final DocumentType address = database.getSchema().createDocumentType("address_t");
    address.createProperty("street", Type.STRING);
    database.getSchema().createVertexType("person");

    final DocumentType all = database.getSchema().createDocumentType("everything");
    all.createProperty("c_bool", Type.BOOLEAN);
    all.createProperty("c_short", Type.SHORT);
    all.createProperty("c_int", Type.INTEGER);
    all.createProperty("c_long", Type.LONG);
    all.createProperty("c_float", Type.FLOAT);
    all.createProperty("c_double", Type.DOUBLE);
    all.createProperty("c_decimal", Type.DECIMAL);
    all.createProperty("c_string", Type.STRING);
    all.createProperty("c_date", Type.DATE);
    all.createProperty("c_datetime", Type.DATETIME);
    all.createProperty("c_binary", Type.BINARY);
    all.createProperty("c_map", Type.MAP);
    all.createProperty("c_embedded", Type.EMBEDDED, "address_t");
    all.createProperty("c_link", Type.LINK, "person");
    all.createProperty("c_list", Type.LIST);
    all.createProperty("c_list_string", Type.LIST, "STRING");
    all.createProperty("c_list_int", Type.LIST, "INTEGER");
    all.createProperty("c_list_decimal", Type.LIST, "DECIMAL");
    all.createProperty("c_list_embedded", Type.LIST, "address_t");
    all.createProperty("c_floats", Type.ARRAY_OF_FLOATS);

    final Table table = RelationalCatalog.of(database).table("everything");
    final Map<String, String> types = RelationalCatalogTest.typesOf(table);
    assertThat(types).containsEntry("id", "text").containsEntry("c_bool", "bool").containsEntry("c_short", "int2")
        .containsEntry("c_int", "int4").containsEntry("c_long", "int8").containsEntry("c_float", "float4")
        .containsEntry("c_double", "float8").containsEntry("c_decimal", "numeric").containsEntry("c_string", "varchar")
        .containsEntry("c_date", "date").containsEntry("c_datetime", "timestamp").containsEntry("c_binary", "bytea")
        .containsEntry("c_map", "jsonb").containsEntry("c_embedded", "jsonb").containsEntry("c_link", "text")
        .containsEntry("c_list", "text[]").containsEntry("c_list_string", "text[]").containsEntry("c_list_int", "int4[]")
        .containsEntry("c_list_decimal", "numeric[]").containsEntry("c_list_embedded", "jsonb")
        .containsEntry("c_floats", "vector");
    assertThat(types).hasSize(21);
  }
}
