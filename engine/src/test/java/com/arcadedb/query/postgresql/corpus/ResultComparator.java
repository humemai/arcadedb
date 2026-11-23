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
package com.arcadedb.query.postgresql.corpus;

import java.util.ArrayList;
import java.util.List;

/**
 * The comparison of two outcomes of the parity corpus. The rules, all of them:
 * <ol>
 *   <li>Two answers: the column labels are equal (duplicates and order count), the PostgreSQL type names are equal, and the rows
 *   are equal. A side that reports no labels or no types ({@code null}) is not checked for that part.</li>
 *   <li>Rows compare as a sequence when the statement is marked {@code ordered} (its {@code ORDER BY} is a total order) and as
 *   a multiset otherwise: the same rows the same number of times. A row twice on one side and once on the other is a
 *   difference, although the set of distinct rows is equal.</li>
 *   <li>Cells compare by the rules of {@link Values}: by value for numbers, exactly for text, {@code NULL} equals {@code NULL},
 *   floating point within the tolerance of the statement.</li>
 *   <li>Two refusals are equal when their SQLSTATE is. An answer never equals a refusal.</li>
 * </ol>
 */
public final class ResultComparator {
  private ResultComparator() {
  }

  /**
   * @param expected  the outcome that counts as right (the server's)
   * @param actual    the outcome under test
   * @param ordered   whether row order is part of the answer
   * @param tolerance the tolerance for floating point values
   *
   * @return {@code null} when the outcomes are equal, else a one-line description of the first difference
   */
  public static String compare(final Outcome expected, final Outcome actual, final boolean ordered, final double tolerance) {
    if (expected.isRefusal() && actual.isRefusal())
      return expected.sqlState.equals(actual.sqlState) ? null :
          "SQLSTATE differs: expected " + expected.sqlState + ", got " + actual.sqlState;
    if (expected.isRefusal())
      return "expected a refusal with SQLSTATE " + expected.sqlState + ", got " + actual.describe();
    if (actual.isRefusal())
      return "expected an answer, got " + actual.describe();
    return compare(expected.table, actual.table, ordered, tolerance);
  }

  public static String compare(final ResultTable expected, final ResultTable actual, final boolean ordered,
      final double tolerance) {
    if (expected.labels != null && actual.labels != null && !expected.labels.equals(actual.labels))
      return "column labels differ: expected " + expected.labels + ", got " + actual.labels;
    if (expected.types != null && actual.types != null && !expected.types.equals(actual.types))
      return "column types differ: expected " + expected.types + ", got " + actual.types;

    final List<List<Object>> a = expected.rows;
    final List<List<Object>> b = actual.rows;

    if (a.size() != b.size())
      return "differs by " + Math.abs(a.size() - b.size()) + (Math.abs(a.size() - b.size()) == 1 ? " row" : " rows")
          + " (expected " + a.size() + ", got " + b.size() + ")";

    if (ordered) {
      for (int i = 0; i < a.size(); i++)
        if (!rowEqual(a.get(i), b.get(i), tolerance))
          return "row " + (i + 1) + " differs: expected " + Values.render(a.get(i)) + ", got " + Values.render(b.get(i));
      return null;
    }

    final List<List<Object>> unmatched = new ArrayList<>(b);
    final List<List<Object>> onlyExpected = new ArrayList<>();
    for (final List<Object> row : a) {
      boolean found = false;
      for (int i = 0; i < unmatched.size(); i++)
        if (rowEqual(row, unmatched.get(i), tolerance)) {
          unmatched.remove(i);
          found = true;
          break;
        }
      if (!found)
        onlyExpected.add(row);
    }
    if (onlyExpected.isEmpty())
      return null;
    return "differs by " + onlyExpected.size() + (onlyExpected.size() == 1 ? " row" : " rows") + " as a multiset: expected "
        + Values.render(onlyExpected.get(0)) + " is missing, got " + Values.render(unmatched.get(0)) + " instead";
  }

  private static boolean rowEqual(final List<Object> a, final List<Object> b, final double tolerance) {
    if (a.size() != b.size())
      return false;
    for (int i = 0; i < a.size(); i++)
      if (!Values.equal(a.get(i), b.get(i), tolerance))
        return false;
    return true;
  }
}
