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

import com.arcadedb.exception.SchemaException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Jsonb;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.eval.ValueInput;
import com.arcadedb.schema.Property;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A constant {@code DEFAULT} of a {@code jsonb} or array column (fork issue #224). The property holds it as a map or list literal
 * of the {@code sql} language, so each record that any language creates takes a value of its own. The literal is evaluated once
 * when the table is made: a value that would not read back as written is refused, and the table is not made.
 */
final class ContainerDefaults {
  /** The element types of which an array default can hold elements; every array type can be empty. */
  private static final Set<ColumnType> ELEMENTS = Set.of(ColumnType.BOOL, ColumnType.INT4, ColumnType.INT8, ColumnType.FLOAT8,
      ColumnType.TEXT);

  private ContainerDefaults() {
  }

  /** {@code ARRAY[]::some_type}, the one array constructor that a default takes. */
  static boolean isEmptyArray(final Expression expression) {
    return expression instanceof Expression.Cast cast && cast.operand() instanceof Expression.ArrayConstructor array
        && array.elements().isEmpty();
  }

  /** Refuses a value that no record can hold, or that this language cannot write into the schema yet. */
  static void requireStorable(final ColumnType type, final Object value, final SourcePosition position) {
    if (type == ColumnType.JSONB) {
      // a property of type MAP holds an object, and no top level key that the engine reads as the name of a type (#288)
      if (!(Casts.fromColumn(value, type, PgType.JSONB) instanceof Map<?, ?>))
        throw refused("A DEFAULT on a jsonb column that is not a JSON object", "leave the DEFAULT out, or write DEFAULT '{}'",
            position);
      if (hasReservedKey(value))
        throw refused("A DEFAULT with a JSON key that starts with @", "leave the DEFAULT out and write the value in the INSERT",
            position);
      return;
    }
    final List<?> list = (List<?>) value;
    if (!list.isEmpty() && (!ELEMENTS.contains(type.elementType()) || list.stream().anyMatch(item -> item == null)))
      throw refused("A DEFAULT with elements on a column of type " + type + (ELEMENTS.contains(type.elementType()) ?
          ", one of them NULL," : ""), "write DEFAULT '{}', or leave the DEFAULT out and write the value in the INSERT", position);
    for (final Object item : list)
      if (item instanceof Double number && !Double.isFinite(number))
        throw refused("A DEFAULT that is not a finite number", "leave the DEFAULT out and write the value in the INSERT",
            position);
  }

  /** The default as {@code information_schema.columns.column_default} shows it, as PostgreSQL 18 writes it. */
  static String text(final ColumnType type, final Object value) {
    if (type == ColumnType.JSONB)
      return quoted(Jsonb.write(value)) + "::jsonb";
    final StringBuilder out = new StringBuilder("{");
    for (final Object item : (List<?>) value) {
      if (out.length() > 1)
        out.append(',');
      element(out, type.elementType(), item);
    }
    return quoted(out.append('}').toString()) + "::" + typeName(type);
  }

  /** One element as PostgreSQL writes it inside the text of an array. */
  private static void element(final StringBuilder out, final ColumnType type, final Object item) {
    if (type == ColumnType.BOOL) {
      out.append(Boolean.TRUE.equals(item) ? 't' : 'f');
      return;
    }
    final String text = type == ColumnType.TEXT ? (String) item
        : (String) Casts.cast(item, PgType.of(type), PgType.TEXT, null);
    final boolean quote = text.isEmpty() || "null".equals(text.toLowerCase(Locale.ROOT))
        || text.chars().anyMatch(c -> c == '"' || c == '\\' || c == '{' || c == '}' || c == ',' || c == ' ' || c >= 9 && c <= 13);
    if (!quote) {
      out.append(text);
      return;
    }
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '"' || c == '\\')
        out.append('\\');
      out.append(c);
    }
    out.append('"');
  }

  /** A string constant as PostgreSQL's catalog writes it with {@code standard_conforming_strings}, which is on. */
  static String quoted(final String text) {
    return "'" + text.replace("'", "''") + "'";
  }

  /** The name of a type as the catalog writes it after {@code ::}. */
  static String typeName(final ColumnType type) {
    if (type.isArray())
      return typeName(type.elementType()) + "[]";
    return switch (type) {
      case BOOL -> "boolean";
      case INT2 -> "smallint";
      case INT4 -> "integer";
      case INT8 -> "bigint";
      case FLOAT4 -> "real";
      case FLOAT8 -> "double precision";
      case VARCHAR -> "character varying";
      case TIMESTAMP -> "timestamp without time zone";
      case TIMESTAMPTZ -> "timestamp with time zone";
      default -> type.typeName().toLowerCase(Locale.ROOT);
    };
  }

  /** Sets the default of the property, and refuses it when what a record would take is not the value. */
  static void set(final Property property, final ColumnType type, final Object value, final SourcePosition position) {
    final StringBuilder literal = new StringBuilder();
    literal(literal, type == ColumnType.JSONB ? Casts.fromColumn(value, type, PgType.JSONB) : value);
    boolean same;
    try {
      property.setDefaultValue(literal.toString());
      final Object taken = property.getDefaultValue();
      if (type == ColumnType.JSONB)
        same = taken != null && Jsonb.write(value).equals(Jsonb.write(Casts.fromColumn(taken, type, PgType.JSONB)));
      else
        same = taken != null && value.equals(ValueInput.toArray(taken, PgType.of(type.elementType())));
    } catch (final SchemaException | PostgreSQLEvaluationException | IllegalArgumentException e) {
      same = false;
    }
    if (!same)
      throw refused("This value as a DEFAULT", "leave the DEFAULT out and write the value in the INSERT", position);
  }

  /** The value as a literal of the {@code sql} language. */
  private static void literal(final StringBuilder out, final Object value) {
    if (value instanceof Map<?, ?> map) {
      out.append('{');
      String separator = "";
      for (final Map.Entry<?, ?> entry : map.entrySet()) {
        out.append(separator).append(sqlLiteral((String) entry.getKey())).append(':');
        literal(out, entry.getValue());
        separator = ",";
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      String separator = "";
      for (final Object item : list) {
        out.append(separator);
        literal(out, item);
        separator = ",";
      }
      out.append(']');
    } else if (value instanceof String text)
      out.append(sqlLiteral(text));
    else if (value instanceof BigDecimal decimal)
      out.append(decimal.toPlainString());
    else if (value instanceof Number || value instanceof Boolean)
      out.append(value);
    else
      // the JSON null, in whatever class holds it
      out.append("null");
  }

  /** A string as a literal of the {@code sql} language. */
  static String sqlLiteral(final String text) {
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
  }

  private static boolean hasReservedKey(final Object value) {
    if (value instanceof Map<?, ?> map)
      return map.entrySet().stream()
          .anyMatch(entry -> entry.getKey().toString().startsWith("@") || hasReservedKey(entry.getValue()));
    return value instanceof List<?> list && list.stream().anyMatch(ContainerDefaults::hasReservedKey);
  }

  private static PostgreSQLFeatureNotSupportedException refused(final String what, final String instead,
      final SourcePosition position) {
    return new PostgreSQLFeatureNotSupportedException(
        what + " is not supported by the postgresql language (" + position + ")",
        Character.toUpperCase(instead.charAt(0)) + instead.substring(1));
  }
}
