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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Comparator;

/**
 * The total order of each comparable type, the one {@code =}, {@code <}, {@code ORDER BY}, {@code MIN} and {@code MAX} use:
 * numbers by value, text by code point ({@link Text#compare}, the {@code C} collation), {@code false} before {@code true},
 * dates and timestamps by time. Both values are non-null and of the given type.
 */
public final class Comparison {
  private Comparison() {
  }

  /** Whether the type has the comparison operators and an order; the opaque types and the unknown type do not. */
  public static boolean isComparable(final PgType type) {
    return type.isNumber() || type.isString() || type.isDateTime() || type == PgType.BOOL || type == PgType.UUID;
  }

  public static int compare(final PgType type, final Object a, final Object b) {
    if (type.isNumber())
      return Numbers.compare(type, a, b);
    if (type.isString())
      return Text.compare((String) a, (String) b);
    switch (type) {
    case BOOL:
      return Boolean.compare((Boolean) a, (Boolean) b);
    case UUID:
      // hexadecimal digits in lower case, at fixed places: the order of the text is the order of the bytes
      return ((String) a).compareTo((String) b);
    case DATE:
      return ((LocalDate) a).compareTo((LocalDate) b);
    case TIMESTAMP:
      return ((LocalDateTime) a).compareTo((LocalDateTime) b);
    case TIMESTAMPTZ:
      return ((OffsetDateTime) a).compareTo((OffsetDateTime) b);
    default:
      throw PostgreSQLEvaluationException.unsupported("Comparing values of type " + type.displayName() + " is not supported");
    }
  }

  /**
   * The order of {@code ORDER BY} for one key: {@code NULL} is larger than every value, so it is last for {@code ASC} and first
   * for {@code DESC}, unless {@code NULLS FIRST} or {@code NULLS LAST} says otherwise.
   *
   * @param nullsFirst {@code null} for the default of the direction
   */
  public static Comparator<Object> ordering(final PgType type, final boolean descending, final Boolean nullsFirst) {
    final boolean nullsAtStart = nullsFirst != null ? nullsFirst : descending;
    return (a, b) -> {
      if (a == null || b == null) {
        if (a == null && b == null)
          return 0;
        return (a == null) == nullsAtStart ? -1 : 1;
      }
      final int order = compare(type, a, b);
      return descending ? -order : order;
    };
  }
}
