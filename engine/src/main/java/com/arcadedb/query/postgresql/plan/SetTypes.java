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

import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Numbers;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

import java.time.LocalDate;

/**
 * The type of a result column of {@code UNION}, {@code INTERSECT} and {@code EXCEPT}, and the conversion of the values of an
 * operand to it: {@code select_common_type} of PostgreSQL for two inputs. Two inputs of one type keep it. A column that is only a
 * string literal or {@code NULL} (type {@code unknown}) takes the type of the other operand, and is {@code text} when both are.
 * Numbers go to the one that is farther along {@code smallint}, {@code integer}, {@code bigint}, {@code numeric}, {@code real},
 * {@code double precision}, a date and a timestamp to the timestamp. Two string types (varchar and text convert to each other
 * implicitly) keep the type of the first, the left operand. Any other pair is SQLSTATE {@code 42804}.
 */
final class SetTypes {
  private SetTypes() {
  }

  static PgType resolve(final PgType left, final PgType right, final String operation) {
    if (left == PgType.UNKNOWN && right == PgType.UNKNOWN)
      return PgType.TEXT;
    if (left == PgType.UNKNOWN)
      return right;
    if (right == PgType.UNKNOWN || left == right)
      return left;
    if (left.isNumber() && right.isNumber())
      return rank(right) > rank(left) ? right : left;
    if (left.isString() && right.isString())
      return left;
    if (left.isDateTime() && right.isDateTime())
      return PgType.TIMESTAMP;
    throw PostgreSQLEvaluationException.mismatch(
        operation + " types " + left.displayName() + " and " + right.displayName() + " cannot be matched");
  }

  private static int rank(final PgType type) {
    return switch (type) {
      case INT2 -> 1;
      case INT4 -> 2;
      case INT8 -> 3;
      case NUMERIC -> 4;
      case FLOAT4 -> 5;
      case FLOAT8 -> 6;
      default -> 0;
    };
  }

  /** The value of a column of type {@code from} in the type {@code to} that {@link #resolve} chose. */
  static Object convert(final Object value, final PgType from, final PgType to) {
    if (value == null || from == to)
      return value;
    if (from == PgType.UNKNOWN)
      return Casts.parse((String) value, to);
    if (from.isNumber() && to.isNumber())
      return Numbers.widen(value, to);
    if (from == PgType.DATE && to == PgType.TIMESTAMP)
      return ((LocalDate) value).atStartOfDay();
    return value;
  }
}
