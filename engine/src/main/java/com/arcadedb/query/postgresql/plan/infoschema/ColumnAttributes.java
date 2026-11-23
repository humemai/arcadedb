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
package com.arcadedb.query.postgresql.plan.infoschema;

import com.arcadedb.query.postgresql.catalog.ColumnType;

/**
 * What {@code information_schema.columns} says about the type of a column, by the rules of the views of PostgreSQL 18
 * ({@code information_schema.sql} of the server) applied to a column that has no length, precision or scale of its own, which is
 * the only kind of column the relational view has: {@code varchar} and {@code text} are unbounded, {@code numeric} has no
 * precision, and the datetime types have the default precision.
 *
 * @param dataType                 {@code data_type}: the SQL name of the type, {@code ARRAY} for an array
 * @param udtName                  {@code udt_name}: {@code pg_type.typname}, with an underscore in front of the element name for an array
 * @param characterOctetLength     {@code character_octet_length}: 2^30 for the unbounded character types
 * @param numericPrecision         {@code numeric_precision}: the bits of an integer or float type, {@code null} for {@code numeric}
 * @param numericPrecisionRadix    {@code numeric_precision_radix}: 2 for the binary types, 10 for {@code numeric}
 * @param numericScale             {@code numeric_scale}: 0 for the integer types, {@code null} for any other
 * @param datetimePrecision        {@code datetime_precision}: fractional digits of a date or timestamp
 */
record ColumnAttributes(String dataType, String udtName, Integer characterOctetLength, Integer numericPrecision,
    Integer numericPrecisionRadix, Integer numericScale, Integer datetimePrecision) {

  /** 2^30, what PostgreSQL reports as the octet length of a character type without a length. */
  private static final int UNBOUNDED_OCTETS = 1073741824;

  static ColumnAttributes of(final ColumnType type) {
    if (type.isArray())
      return new ColumnAttributes("ARRAY", "_" + type.elementType().typeName(), null, null, null, null, null);
    return switch (type) {
      case BOOL -> new ColumnAttributes("boolean", "bool", null, null, null, null, null);
      case INT2 -> new ColumnAttributes("smallint", "int2", null, 16, 2, 0, null);
      case INT4 -> new ColumnAttributes("integer", "int4", null, 32, 2, 0, null);
      case INT8 -> new ColumnAttributes("bigint", "int8", null, 64, 2, 0, null);
      case FLOAT4 -> new ColumnAttributes("real", "float4", null, 24, 2, null, null);
      case FLOAT8 -> new ColumnAttributes("double precision", "float8", null, 53, 2, null, null);
      case NUMERIC -> new ColumnAttributes("numeric", "numeric", null, null, 10, null, null);
      case DATE -> new ColumnAttributes("date", "date", null, null, null, null, 0);
      case TIMESTAMP -> new ColumnAttributes("timestamp without time zone", "timestamp", null, null, null, null, 6);
      case VARCHAR -> new ColumnAttributes("character varying", "varchar", UNBOUNDED_OCTETS, null, null, null, null);
      case TEXT -> new ColumnAttributes("text", "text", UNBOUNDED_OCTETS, null, null, null, null);
      case BYTEA -> new ColumnAttributes("bytea", "bytea", null, null, null, null, null);
      case JSONB -> new ColumnAttributes("jsonb", "jsonb", null, null, null, null, null);
      default -> new ColumnAttributes("USER-DEFINED", type.typeName(), null, null, null, null, null);
    };
  }

  /** The attributes of a column of one of the views, which has the domain's base type. */
  static ColumnAttributes of(final InfoType type) {
    return switch (type) {
      case SQL_IDENTIFIER -> new ColumnAttributes("name", "name", null, null, null, null, null);
      case CHARACTER_DATA -> new ColumnAttributes("character varying", "varchar", UNBOUNDED_OCTETS, null, null, null, null);
      case YES_OR_NO -> new ColumnAttributes("character varying", "varchar", 12, null, null, null, null);
      case CARDINAL_NUMBER -> new ColumnAttributes("integer", "int4", null, 32, 2, 0, null);
    };
  }

  /** {@code character_maximum_length}: only the three-character {@code yes_or_no} has one. */
  static Integer characterMaximumLength(final InfoType type) {
    return type == InfoType.YES_OR_NO ? 3 : null;
  }
}
