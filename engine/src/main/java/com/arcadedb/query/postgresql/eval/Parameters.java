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

import java.util.Map;

/**
 * The values bound to {@code $1}, {@code $2} and so on. They come as a positional array ({@code db.query("postgresql", sql, a,
 * b)}: {@code $1} is {@code a}) or as a map whose keys are the numbers, written {@code "1"} or {@code "$1"}. A {@code $n} with
 * no value is SQLSTATE {@code 42P02} (undefined_parameter), the code PostgreSQL gives for {@code SELECT $1} without a bound
 * value. The type of a parameter is taken from its Java value, see {@link PgType#ofJavaValue(Object)}.
 */
public final class Parameters {
  public static final Parameters NONE = new Parameters(new Object[0], new boolean[0]);

  private final Object[]  values;
  private final boolean[] present;

  private Parameters(final Object[] values, final boolean[] present) {
    this.values = values;
    this.present = present;
  }

  /** {@code $1} is {@code values[0]}. */
  public static Parameters of(final Object... values) {
    if (values == null || values.length == 0)
      return NONE;
    final boolean[] present = new boolean[values.length];
    java.util.Arrays.fill(present, true);
    return new Parameters(values.clone(), present);
  }

  /** Keys {@code "1"} or {@code "$1"}; any other key is ignored. */
  public static Parameters of(final Map<String, Object> named) {
    if (named == null || named.isEmpty())
      return NONE;
    int max = 0;
    for (final String key : named.keySet()) {
      final int index = indexOf(key);
      if (index > max)
        max = index;
    }
    final Object[] values = new Object[max];
    final boolean[] present = new boolean[max];
    for (final Map.Entry<String, Object> entry : named.entrySet()) {
      final int index = indexOf(entry.getKey());
      if (index > 0) {
        values[index - 1] = entry.getValue();
        present[index - 1] = true;
      }
    }
    return new Parameters(values, present);
  }

  private static int indexOf(final String key) {
    final String digits = key.startsWith("$") ? key.substring(1) : key;
    if (digits.isEmpty() || digits.length() > 6 || !digits.chars().allMatch(Character::isDigit))
      return -1;
    return Integer.parseInt(digits);
  }

  /** The number of the highest parameter that has a value. */
  public int size() {
    return values.length;
  }

  /**
   * The value of {@code $index}, counted from 1.
   *
   * @throws PostgreSQLEvaluationException {@code 42P02} when there is no such parameter
   */
  public Object get(final int index) {
    if (index < 1 || index > values.length || !present[index - 1])
      throw new PostgreSQLEvaluationException("42P02", "there is no parameter $" + index);
    return values[index - 1];
  }
}
