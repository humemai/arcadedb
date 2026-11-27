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
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.InternalResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    if (rowKeys.equals(wireKeys))
      return new PostgresLanguageAnswer(columns, !answer.returnsRows(), answer.affectedRows(), resultSet);

    final InternalResultSet rekeyed = new InternalResultSet();
    try (resultSet) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        final ResultInternal copy = new ResultInternal(database);
        for (int i = 0; i < rowKeys.size(); i++)
          copy.setProperty(wireKeys.get(i), row.getProperty(rowKeys.get(i)));
        rekeyed.add(copy);
      }
    }
    return new PostgresLanguageAnswer(columns, !answer.returnsRows(), answer.affectedRows(), rekeyed);
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
    String element = null;
    if (typeName.endsWith("[]"))
      element = typeName.substring(0, typeName.length() - 2);
    else if (typeName.startsWith("_"))
      element = typeName.substring(1);
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
    return pgType != null ? pgType : PgType.UNKNOWN;
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
      case BOOL, INT2, INT4, INT8, NUMERIC, FLOAT4, FLOAT8, TEXT, VARCHAR, DATE, TIMESTAMP, JSONB, BYTEA ->
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
    return "SELECT " + count;
  }
}
