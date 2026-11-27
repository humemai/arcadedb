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
package com.arcadedb.postgres;

import com.arcadedb.database.Database;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Jsonb;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.serializer.json.JSONObject;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the wire needs to know about the answer of a statement of the {@code postgresql} language (fork issue #12), which the rows
 * alone do not say: the language describes its columns itself, with the labels and the PostgreSQL type names that PostgreSQL would
 * send, so the wire announces those and does not infer a type from the first rows. The columns come with the answer, or from
 * {@code PostgreSQLQueryEngine.describe} before the statement runs.
 * <p>
 * Two output columns may have one label ({@code SELECT p.name, c.name}), which a row, a map, cannot carry: the rows of the language
 * key the second one {@code name_1}. The wire announces both as {@code name}, with the key a catalog answer already uses for such a
 * column ({@link PostgresCatalog#announcedName}), and {@link #of} re-keys the rows to match.
 */
final class PostgresLanguageAnswer {
  private final List<PostgreSQLResultSet.Column> columns;
  private final boolean                          rowless;
  private final long                             affected;
  private final ResultSet                        rows;

  private PostgresLanguageAnswer(final List<PostgreSQLResultSet.Column> columns, final boolean rowless, final long affected,
      final ResultSet rows) {
    this.columns = columns;
    this.rowless = rowless;
    this.affected = affected;
    this.rows = rows;
  }

  /**
   * @return the answer of the language for {@code resultSet}, whose rows are keyed for the wire, or {@code null} when
   *     {@code resultSet} is not an answer of the {@code postgresql} language
   */
  static PostgresLanguageAnswer of(final ResultSet resultSet, final Database database) {
    if (!(resultSet instanceof PostgreSQLResultSet answer))
      return null;
    final List<PostgreSQLResultSet.Column> columns = answer.columns();
    final List<String> rowKeys = PostgreSQLResultSet.uniqueKeys(columns);
    final List<String> wireKeys = wireKeys(columns);
    final boolean rewritten = columns.stream()
        .anyMatch(column -> "jsonb".equals(column.typeName()) || isArray(column.typeName()));
    if (!rewritten && rowKeys.equals(wireKeys))
      return new PostgresLanguageAnswer(columns, !answer.returnsRows(), answer.affectedRows(), resultSet);

    // each row is copied as the wire reads it, when the wire reads it
    final ResultSet copies = new ResultSet() {
      @Override
      public boolean hasNext() {
        return resultSet.hasNext();
      }

      @Override
      public Result next() {
        final Result row = resultSet.next();
        final ResultInternal copy = new ResultInternal(database);
        for (int i = 0; i < rowKeys.size(); i++)
          copy.setProperty(wireKeys.get(i), written(columns.get(i).typeName(), row.getProperty(rowKeys.get(i))));
        return copy;
      }

      @Override
      public void close() {
        resultSet.close();
      }
    };
    return new PostgresLanguageAnswer(columns, !answer.returnsRows(), answer.affectedRows(), copies);
  }

  /**
   * A value of a column of the type {@code typeName} as the wire writes it for the language: a {@code jsonb} value as its text
   * ({@link #jsonb}) and an array as a {@link PostgresLanguageArray}. Any other value is returned as it is.
   */
  private static Object written(final String typeName, final Object value) {
    if (value == null)
      return null;
    if ("jsonb".equals(typeName))
      return jsonb(value);
    if (!isArray(typeName))
      return value;
    final String element = elementName(typeName);
    return PostgresLanguageArray.of(value, element == null ? null : PgType.byName(List.of(element)));
  }

  /**
   * A {@code jsonb} value as the text that the language gives it ({@code ::text}), which is PostgreSQL's: a space after each
   * {@code :} and {@code ,}, and the keys of an object in PostgreSQL's order. A string is the JSON text that it holds. A value
   * with a part that is no JSON value, such as a date that another language stored in a map, is returned as it is, and keeps
   * the text this protocol writes for it.
   */
  static Object jsonb(final Object value) {
    return !(value instanceof String) && isJson(value, 0) ? Jsonb.write(value) : value;
  }

  private static boolean isJson(final Object value, final int depth) {
    // deeper than the writer goes: it answers 54001, as the cast to text does
    if (depth > 1000)
      return true;
    return switch (value) {
      case null -> true;
      case String s -> true;
      case Number n -> true;
      case Boolean b -> true;
      case Jsonb.Scalar scalar -> isJson(scalar.value(), depth + 1);
      case Map<?, ?> map -> map.values().stream().allMatch(member -> isJson(member, depth + 1));
      case Collection<?> items -> items.stream().allMatch(item -> isJson(item, depth + 1));
      default -> value == JSONObject.NULL;
    };
  }

  private static boolean isArray(final String typeName) {
    return "array".equals(typeName) || elementName(typeName) != null;
  }

  /** The element type in the name of an array type ({@code text[]}, {@code _text}), {@code null} for a name that says none. */
  private static String elementName(final String typeName) {
    if (typeName == null)
      return null;
    if (typeName.endsWith("[]"))
      return typeName.substring(0, typeName.length() - 2);
    return typeName.startsWith("_") ? typeName.substring(1) : null;
  }

  ResultSet rows() {
    return rows;
  }

  List<PostgreSQLResultSet.Column> languageColumns() {
    return columns;
  }

  /** True for a write without {@code RETURNING}: no rows go to the client, only a command tag with {@link #affected()}. */
  boolean rowless() {
    return rowless;
  }

  long affected() {
    return affected;
  }

  /** The columns as the wire announces them, in order. */
  Map<String, PostgresType> wireColumns() {
    return wireColumns(columns);
  }

  static Map<String, PostgresType> wireColumns(final List<PostgreSQLResultSet.Column> columns) {
    final List<String> keys = wireKeys(columns);
    final Map<String, PostgresType> wire = new LinkedHashMap<>();
    for (int i = 0; i < keys.size(); i++)
      wire.put(keys.get(i), wireType(columns.get(i).typeName()));
    return wire;
  }

  /** The label of each column, and for a label that came before the same label plus a NUL and a number, see the class comment. */
  private static List<String> wireKeys(final List<PostgreSQLResultSet.Column> columns) {
    final List<String> keys = new ArrayList<>(columns.size());
    for (final PostgreSQLResultSet.Column column : columns) {
      String key = column.name();
      for (int n = 2; keys.contains(key); n++)
        key = column.name() + '\0' + n;
      keys.add(key);
    }
    return keys;
  }

  /**
   * The wire type of a type name of the language ({@code int4}, {@code text[]}, {@code _text}, {@code jsonb}). The language has two
   * types the wire has no OID for: {@code vector} (pgvector's type, an extension with an OID of its own) is announced as
   * {@code float4[]}, which is what the wire announces for an {@code ARRAY_OF_FLOATS} property, and {@code unknown}, the type of a
   * string constant nothing has decided yet, as {@code text}, which is what PostgreSQL does for it in a select list.
   */
  static PostgresType wireType(final String typeName) {
    if (typeName == null)
      return PostgresType.VARCHAR;
    switch (typeName) {
    case "vector":
      return PostgresType.ARRAY_REAL;
    case "array":
      return PostgresType.ARRAY_TEXT;
    case "unknown":
      return PostgresType.TEXT;
    default:
      break;
    }
    final String element = elementName(typeName);
    if (element != null) {
      final PostgresType scalar = PostgresType.byName(element);
      final PostgresType array = scalar != null && scalar.arrayCode != 0 ? PostgresType.byCode(scalar.arrayCode) : null;
      return array != null ? array : PostgresType.ARRAY_TEXT;
    }
    final PostgresType type = PostgresType.byName(typeName);
    return type != null ? type : PostgresType.VARCHAR;
  }

  /**
   * The type that the language gives a parameter that the client declared with the type {@code oid}, {@link PgType#UNKNOWN} when it
   * declared none (0) or one the language has no type for: the language takes it from the expression it is in.
   */
  static PgType parameterType(final long oid) {
    final PostgresType type = oid == 0 ? null : PostgresType.byCode((int) oid);
    final PgType pgType = type == null ? null : PgType.byName(List.of(type.typeName));
    // a parameter declared json takes the type of where it stands, a jsonb column among them, as it did before the language had json
    return pgType != null && pgType != PgType.JSON ? pgType : PgType.UNKNOWN;
  }

  /** Whether {@code oid} is the type {@code timestamptz} or {@code timestamptz[]}. */
  static boolean isInstant(final long oid) {
    return oid == PostgresType.TIMESTAMPTZ.code || oid == PostgresType.ARRAY_TIMESTAMPTZ.code;
  }

  /**
   * The value of a text parameter that the client declared as {@code timestamptz} or {@code timestamptz[]} (see
   * {@link #isInstant}), as the language reads it: a text without a zone is a time of the session (fork issue #270), which
   * {@code SessionTimeZone} holds for this thread.
   */
  static Object instants(final long oid, final String text) {
    if (oid == PostgresType.TIMESTAMPTZ.code)
      return Casts.parse(text, PgType.TIMESTAMPTZ);
    return typedArray(oid, PostgresArrayLiteral.parse(text, item -> Casts.parse(item, PgType.TIMESTAMPTZ)));
  }

  /**
   * A bound array in which no element says the type ({@code {}} or {@code {NULL}} of {@code text[]}), as a Java array of the class
   * of the declared type {@code oid}, so that the language keeps the type and answers {@code 42883} for a mismatch, as PostgreSQL
   * does. Any other value, and an array of a type that has no such class, is returned as it is.
   */
  static Object typedArray(final long oid, final Object value) {
    if (!(value instanceof Collection<?> items) || !items.stream().allMatch(Objects::isNull))
      return value;
    final PostgresType type = oid == 0 ? null : PostgresType.byCode((int) oid);
    if (type == null)
      return value;
    final Class<?> element = switch (type) {
      case ARRAY_TEXT, ARRAY_VARCHAR, ARRAY_BPCHAR -> String.class;
      case ARRAY_BOOLEAN -> Boolean.class;
      case ARRAY_SMALLINT -> Short.class;
      case ARRAY_INT -> Integer.class;
      case ARRAY_LONG -> Long.class;
      case ARRAY_NUMERIC -> BigDecimal.class;
      case ARRAY_REAL -> Float.class;
      case ARRAY_DOUBLE -> Double.class;
      case ARRAY_BYTEA -> byte[].class;
      case ARRAY_DATE -> LocalDate.class;
      case ARRAY_TIMESTAMP -> LocalDateTime.class;
      case ARRAY_TIMESTAMPTZ -> OffsetDateTime.class;
      default -> null;
    };
    return element == null ? value : Array.newInstance(element, items.size());
  }

  /**
   * The type OID that ParameterDescription announces for a parameter that the language typed as {@code type} (fork issues #67
   * and #29), 0 (unspecified) for a parameter nothing typed, for an array whose element type nothing decided and for
   * {@code interval}. A {@code vector} is announced as a {@code vector} column is, {@code real[]}. A client such as asyncpg binds
   * a value in the binary format of the type announced here.
   *
   * @param typeName the name of the type, which for an array carries the element type ({@code _text})
   */
  static long parameterOid(final PgType type, final String typeName) {
    return switch (type) {
      case BOOL, INT2, INT4, INT8, NUMERIC, FLOAT4, FLOAT8, TEXT, VARCHAR, DATE, TIMESTAMP, TIMESTAMPTZ, JSONB, JSON, BYTEA,
          UUID ->
          PostgresType.byName(type.typeName()).code;
      case VECTOR -> wireType(type.typeName()).code;
      case ARRAY -> typeName.startsWith("_") ? wireType(typeName).code : 0L;
      default -> 0L;
    };
  }

  /**
   * The tag of the CommandComplete of a statement of the language, read off its first word as PostgreSQL's tag is: the language
   * has no statement class the wire could be asked. {@code count} is the number of rows a {@code SELECT} returned or a write changed.
   */
  static String commandTag(final String text, final long count) {
    final String words = PostgresCommandTag.leadingWords(text);
    if (words.startsWith("INSERT ") || words.equals("INSERT"))
      return "INSERT 0 " + count;
    if (words.startsWith("UPDATE ") || words.equals("UPDATE"))
      return "UPDATE " + count;
    if (words.startsWith("DELETE ") || words.equals("DELETE"))
      return "DELETE " + count;
    if (words.startsWith("EXPLAIN"))
      return "EXPLAIN";
    if (words.startsWith("CREATE TABLE"))
      return "CREATE TABLE";
    if (words.startsWith("CREATE INDEX") || words.startsWith("CREATE UNIQUE INDEX"))
      return "CREATE INDEX";
    if (words.startsWith("CREATE EXTENSION"))
      return "CREATE EXTENSION";
    return "SELECT " + count;
  }
}
