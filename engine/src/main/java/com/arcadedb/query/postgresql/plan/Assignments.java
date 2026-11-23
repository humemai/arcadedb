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

import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

/**
 * The rule that decides which value a column takes, as PostgreSQL's assignment casts have it: a string literal (and a text
 * parameter) is read with the input function of the type of the column ({@code 22P02} for a bad text, {@code 22003} out of
 * range); the numeric types convert to one another, with rounding to an integer and the range of the target checked; anything
 * converts to {@code text} and {@code varchar}; {@code date} and {@code timestamp} convert to one another. Everything else
 * (text to a number, a number to a boolean) is {@code 42804}, and needs a cast in the statement, as in PostgreSQL.
 *
 * <p>The types the evaluator does not compute with ({@code jsonb}, {@code bytea}, arrays, {@code vector}) take {@code NULL} and
 * {@code DEFAULT}; any other value is refused with {@code 0A000} until a later stage reads their input syntax.
 */
final class Assignments {
  private Assignments() {
  }

  static PgType typeOf(final Column column) {
    return PgType.of(column.type());
  }

  /** Whether the column takes only {@code NULL} and {@code DEFAULT} for now. */
  static boolean opaque(final Column column) {
    final PgType type = typeOf(column);
    return type.isOpaque() || type == PgType.VECTOR;
  }

  /**
   * Checks at plan time that a value of type {@code from} can go into the column, so that the error comes before any row is
   * read, and for an empty table too.
   */
  static void check(final PgType from, final Column column) {
    final PgType to = typeOf(column);
    if (opaque(column))
      throw PostgreSQLEvaluationException.unsupported(
          "A value for the column \"" + column.name() + "\" of type " + column.type() + " is not supported yet: it takes NULL and DEFAULT");
    if (from == to || from == PgType.UNKNOWN || to.isString() || (from.isString() && to.isString()))
      return;
    if (from.isNumber() && to.isNumber())
      return;
    if (from.isDateTime() && to.isDateTime())
      return;
    throw PostgreSQLEvaluationException.mismatch(
        "column \"" + column.name() + "\" is of type " + to.displayName() + " but expression is of type " + from.displayName());
  }

  /** The value in the class of the type of the column; {@code from} is {@link PgType#UNKNOWN} for a string literal. */
  static Object convert(final Object value, final PgType from, final Column column) {
    if (value == null)
      return null;
    final PgType to = typeOf(column);
    if (from == to)
      return value;
    if (from == PgType.UNKNOWN)
      return to.isString() ? value : Casts.parse((String) value, to);
    if (from.isString() && to.isString())
      return value;
    return Casts.cast(value, from, to, null);
  }
}
