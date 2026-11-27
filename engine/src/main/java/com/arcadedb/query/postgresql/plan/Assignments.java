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

import com.arcadedb.exception.ValidationException;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.eval.ValueInput;

/**
 * The rule that decides which value a column takes, as PostgreSQL's assignment casts have it: a string literal (and a text
 * parameter) is read with the input function of the type of the column ({@code 22P02} for a bad text, {@code 22003} out of
 * range); the numeric types convert to one another, with rounding to an integer and the range of the target checked; anything
 * converts to {@code text} and {@code varchar}; {@code date} and {@code timestamp} convert to one another. Everything else
 * (text to a number, a number to a boolean) is {@code 42804}, and needs a cast in the statement, as in PostgreSQL.
 *
 * <p>A column of {@code jsonb}, {@code bytea}, an array or {@code vector} takes a value of its own type or one of unknown type (a
 * string literal, a parameter), which {@link ValueInput} reads. An array also takes an array whose elements can be assigned to
 * its element type, and a {@code vector} takes an array of numbers, as pgvector's assignment casts do. How the record holds such
 * a value is the business of {@link StoredValues}.
 */
final class Assignments {
  private Assignments() {
  }

  static PgType typeOf(final Column column) {
    return PgType.of(column.type());
  }

  /** A parameter of a described statement that goes into the column has the type of the column. */
  static void inferParameter(final Evaluable value, final Column column) {
    if (column.type().isArray())
      ExpressionCompiler.inferArrayParameter(value, arrayName(column));
    else
      ExpressionCompiler.inferParameter(value, typeOf(column));
  }

  /** The name of the type of an array column, {@code _text}. */
  static String arrayName(final Column column) {
    return "_" + column.type().elementType().typeName();
  }

  /**
   * Checks at plan time that a value of type {@code from} can go into the column, so that the error comes before any row is
   * read, and for an empty table too.
   */
  static void check(final PgType from, final String fromTypeName, final Column column) {
    final PgType to = typeOf(column);
    if (to == PgType.ARRAY || to == PgType.VECTOR) {
      if (from == PgType.UNKNOWN || (from == to && to == PgType.VECTOR) || assignableArray(from, fromTypeName, column))
        return;
      final String source = from == PgType.ARRAY && ValueInput.elementType(fromTypeName) != null
          ? ValueInput.elementType(fromTypeName).displayName() + "[]" : from.displayName();
      throw PostgreSQLEvaluationException.mismatch(
          "column \"" + column.name() + "\" is of type " + column.type() + " but expression is of type " + source);
    }
    if (from == to || from == PgType.UNKNOWN || to.isString() || (from.isString() && to.isString()))
      return;
    if (from.isNumber() && to.isNumber())
      return;
    if (from.isDateTime() && to.isDateTime())
      return;
    throw PostgreSQLEvaluationException.mismatch(
        "column \"" + column.name() + "\" is of type " + to.displayName() + " but expression is of type " + from.displayName());
  }

  private static boolean assignableArray(final PgType from, final String fromTypeName, final Column column) {
    if (column.type() == ColumnType.VECTOR) {
      if (from != PgType.ARRAY)
        return false;
      final PgType element = ValueInput.elementType(fromTypeName);
      // pgvector has assignment casts from integer[], real[], double precision[] and numeric[]
      return element == null || (element.isNumber() && element != PgType.INT8);
    }
    final PgType target = PgType.of(column.type().elementType());
    if (from == PgType.VECTOR)
      return target == PgType.FLOAT4;
    if (from != PgType.ARRAY)
      return false;
    final PgType element = ValueInput.elementType(fromTypeName);
    return element == null || element == target || target.isString() || (element.isNumber() && target.isNumber())
        || (element.isDateTime() && target.isDateTime());
  }

  /**
   * The value in the class of the type of the column; {@code from} is {@link PgType#UNKNOWN} for a string literal and for a
   * parameter whose Java value the column types. A column with a length, or a precision and a scale, then takes it as the engine
   * does for every language (fork issue #226): a number rounded to the scale ({@code 22003} when it does not fit), a string of at
   * most that many characters, spaces over the limit cut off ({@code 22001} otherwise).
   */
  static Object convert(final Object value, final PgType from, final Column column) {
    final Object converted = converted(value, from, column);
    if (converted == null || column.modifier() == null)
      return converted;
    try {
      return column.modifier().assign(converted);
    } catch (final ValidationException e) {
      throw new PostgreSQLEvaluationException(WriteErrors.validationState(e.getKind()), e.getMessage(), e);
    }
  }

  private static Object converted(final Object value, final PgType from, final Column column) {
    if (value == null)
      return null;
    final PgType to = typeOf(column);
    switch (to) {
    case JSONB:
      return ValueInput.toJson(value);
    case BYTEA:
      return ValueInput.toBytes(value);
    case ARRAY:
      return ValueInput.toArray(value, PgType.of(column.type().elementType()));
    case VECTOR:
      return ValueInput.toVector(value);
    default:
      break;
    }
    if (from == to)
      return value;
    if (from == PgType.UNKNOWN) {
      if (!(value instanceof String text))
        throw PostgreSQLEvaluationException.mismatch("column \"" + column.name() + "\" is of type " + to.displayName()
            + " and the value of the parameter is not");
      return to.isString() ? value : Casts.parse(text, to);
    }
    if (from.isString() && to.isString())
      return value;
    return Casts.cast(value, from, to, null);
  }
}
