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
package com.arcadedb.query.postgresql.eval;

import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * The types the evaluator computes with. A value of each type has one Java class, the canonical form that every operator takes
 * and returns:
 *
 * <table>
 * <caption>PostgreSQL type and its Java class</caption>
 * <tr><td>{@code bool}</td><td>{@link Boolean}</td></tr>
 * <tr><td>{@code int2}, {@code int4}, {@code int8}</td><td>{@link Short}, {@link Integer}, {@link Long}</td></tr>
 * <tr><td>{@code numeric}</td><td>{@link BigDecimal}, finite only (no {@code NaN})</td></tr>
 * <tr><td>{@code float4}, {@code float8}</td><td>{@link Float}, {@link Double}</td></tr>
 * <tr><td>{@code text}, {@code varchar}</td><td>{@link String}</td></tr>
 * <tr><td>{@code uuid}</td><td>{@link String}: the 36 characters in lower case, whose order is the order of the bytes</td></tr>
 * <tr><td>{@code date}</td><td>{@link LocalDate}</td></tr>
 * <tr><td>{@code timestamp}</td><td>{@link LocalDateTime}</td></tr>
 * <tr><td>{@code timestamptz}</td><td>{@link OffsetDateTime} at the offset zero: an instant. In the rows of an answer it is at
 * the offset of the session's time zone (see {@code SessionTimeZone}), the offset zero without a session</td></tr>
 * <tr><td>{@code interval}</td><td>{@link PgInterval}, only as the operand of date and timestamp arithmetic</td></tr>
 * </table>
 *
 * SQL {@code NULL} is the Java {@code null} for every type. {@link #UNKNOWN} is the type of a string literal and of a bare
 * {@code NULL} before the context decides (PostgreSQL's {@code unknown}). A value of {@link #JSONB} is described by
 * {@link Jsonb}, which has its operators. {@link #BYTEA} and {@link #ARRAY} are read from a record and handed to the client
 * unchanged, and {@link ValueInput} reads the values a statement writes. {@link #VECTOR} is the pgvector type of a
 * {@code float[]} (an {@code ARRAY_OF_FLOATS} property): only the distance operators ({@link VectorDistance}) take it.
 */
public enum PgType {
  UNKNOWN("unknown"), BOOL("bool"), INT2("int2"), INT4("int4"), INT8("int8"), NUMERIC("numeric"), FLOAT4("float4"),
  FLOAT8("float8"), TEXT("text"), VARCHAR("varchar"), DATE("date"), TIMESTAMP("timestamp"), JSONB("jsonb"), BYTEA("bytea"),
  ARRAY("array"), VECTOR("vector"), INTERVAL("interval"), TIMESTAMPTZ("timestamptz"), UUID("uuid"), JSON("json");

  private final String typeName;

  PgType(final String typeName) {
    this.typeName = typeName;
  }

  /** The name of the type in {@code pg_type.typname}. */
  public String typeName() {
    return typeName;
  }

  /** The name an error message uses: {@code integer} for {@code int4}, the way PostgreSQL's {@code format_type} writes it. */
  public String displayName() {
    return switch (this) {
      case INT2 -> "smallint";
      case INT4 -> "integer";
      case INT8 -> "bigint";
      case FLOAT4 -> "real";
      case FLOAT8 -> "double precision";
      case BOOL -> "boolean";
      case TIMESTAMP -> "timestamp without time zone";
      case TIMESTAMPTZ -> "timestamp with time zone";
      case VARCHAR -> "character varying";
      case ARRAY -> "anyarray";
      default -> typeName;
    };
  }

  public boolean isInteger() {
    return this == INT2 || this == INT4 || this == INT8;
  }

  public boolean isFloat() {
    return this == FLOAT4 || this == FLOAT8;
  }

  /** The numeric category of PostgreSQL: integers, {@code numeric} and floating point. */
  public boolean isNumber() {
    return isInteger() || this == NUMERIC || isFloat();
  }

  public boolean isString() {
    return this == TEXT || this == VARCHAR;
  }

  public boolean isDateTime() {
    return this == DATE || this == TIMESTAMP || this == TIMESTAMPTZ;
  }

  /** The type that two different date and time types are compared and combined in, as PostgreSQL resolves them. */
  public static PgType commonDateTime(final PgType left, final PgType right) {
    return left == TIMESTAMPTZ || right == TIMESTAMPTZ ? TIMESTAMPTZ : TIMESTAMP;
  }

  /** A type with no order and no cast from a value of another type but text: the operators it has are its own. */
  public boolean isOpaque() {
    return this == JSONB || this == BYTEA || this == ARRAY || this == JSON;
  }

  /** The type of a column of the relational view. */
  public static PgType of(final ColumnType type) {
    return switch (type) {
      case BOOL -> BOOL;
      case INT2 -> INT2;
      case INT4 -> INT4;
      case INT8 -> INT8;
      case FLOAT4 -> FLOAT4;
      case FLOAT8 -> FLOAT8;
      case NUMERIC -> NUMERIC;
      case DATE -> DATE;
      case TIMESTAMP -> TIMESTAMP;
      case TIMESTAMPTZ -> TIMESTAMPTZ;
      case VARCHAR -> VARCHAR;
      case TEXT -> TEXT;
      case UUID -> UUID;
      case BYTEA -> BYTEA;
      case JSONB -> JSONB;
      case VECTOR -> VECTOR;
      default -> ARRAY;
    };
  }

  /**
   * The type a parameter has, taken from the Java value as the prototype decided: {@code null} is {@link #UNKNOWN}, an integer
   * class is the integer type of its width, a {@link String} is {@code text}. A class the language has no type for gives
   * {@code null}.
   */
  public static PgType ofJavaValue(final Object value) {
    if (value == null)
      return UNKNOWN;
    if (value instanceof Jsonb.Scalar || value instanceof JsonbText)
      return JSONB;
    if (value instanceof Json.Value)
      return JSON;
    if (value instanceof Boolean)
      return BOOL;
    if (value instanceof Byte || value instanceof Short)
      return INT2;
    if (value instanceof Integer)
      return INT4;
    if (value instanceof Long)
      return INT8;
    if (value instanceof BigInteger || value instanceof BigDecimal)
      return NUMERIC;
    if (value instanceof Float)
      return FLOAT4;
    if (value instanceof Double)
      return FLOAT8;
    if (value instanceof CharSequence || value instanceof Character)
      return TEXT;
    if (value instanceof java.util.UUID)
      return UUID;
    if (value instanceof float[])
      return VECTOR;
    if (value instanceof byte[])
      return BYTEA;
    if (value instanceof java.util.Map<?, ?> || value instanceof JSONObject)
      return JSONB;
    if (value instanceof java.util.Collection<?> || value instanceof JSONArray || value.getClass().isArray())
      return ARRAY;
    if (value instanceof java.sql.Date || value instanceof LocalDate)
      return DATE;
    if (value instanceof LocalDateTime || value instanceof java.util.Date)
      return TIMESTAMP;
    if (value instanceof ZonedDateTime || value instanceof OffsetDateTime || value instanceof Instant)
      return TIMESTAMPTZ;
    return null;
  }

  /**
   * The type a type name of a cast stands for, {@code null} when the language has no such type. The parser has normalised the
   * spellings ({@code integer} is {@code int4}), so this reads the catalog names, with or without {@code pg_catalog}.
   */
  public static PgType byName(final List<String> name) {
    String last = name.get(name.size() - 1).toLowerCase(Locale.ROOT);
    if (name.size() == 2 && !"pg_catalog".equals(name.get(0)))
      return null;
    if (name.size() > 2)
      return null;
    return switch (last) {
      case "bool", "boolean" -> BOOL;
      case "uuid" -> UUID;
      case "int2", "smallint" -> INT2;
      case "int4", "int", "integer" -> INT4;
      case "int8", "bigint" -> INT8;
      case "numeric", "decimal" -> NUMERIC;
      case "float4", "real" -> FLOAT4;
      case "float8" -> FLOAT8;
      case "text" -> TEXT;
      case "varchar" -> VARCHAR;
      case "date" -> DATE;
      case "timestamp" -> TIMESTAMP;
      case "timestamptz" -> TIMESTAMPTZ;
      case "vector" -> VECTOR;
      case "jsonb" -> JSONB;
      case "json" -> JSON;
      case "bytea" -> BYTEA;
      case "interval" -> INTERVAL;
      default -> null;
    };
  }
}
