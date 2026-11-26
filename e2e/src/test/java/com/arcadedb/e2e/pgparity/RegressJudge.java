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
package com.arcadedb.e2e.pgparity;

import com.arcadedb.query.postgresql.corpus.Values;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The verdict rules of fork issue #119, in one place. They are written there, and in
 * {@code docs/postgresql-regression-rules.md}, before the first run; a change of them is a change of both.
 */
final class RegressJudge {
  static final String FEATURE_NOT_SUPPORTED = "0A000";

  enum Verdict {
    PASSED("passed"), REFUSED("refused"), WRONG("wrong"), BLOCKED("blocked"), OUT_OF_SCOPE("out of scope");

    final String text;

    Verdict(final String text) {
      this.text = text;
    }
  }

  record Judgement(Verdict verdict, String reason) {
  }

  private static final int GREEDY_LIMIT = 5_000;

  private RegressJudge() {
  }

  /**
   * The verdict of a judged statement.
   *
   * @param expected        the answer of PostgreSQL
   * @param actual          the answer of the side under test
   * @param tableDifference how the tables the statement touched differ afterwards, {@code null} when they do not
   * @param keys            for each row of the expected answer the values of its sort keys, {@code null} for a statement
   *                        without {@code ORDER BY}
   */
  static Judgement judge(final RegressSubject.Answer expected, final RegressSubject.Answer actual, final String tableDifference,
      final List<List<Object>> keys) {
    if (actual.crashed())
      return new Judgement(Verdict.WRONG, "crashed: " + actual.message());
    if (expected.failed()) {
      if (!actual.failed())
        return new Judgement(Verdict.WRONG, "succeeds where PostgreSQL raises " + expected.sqlState() + ": " + expected.message());
      if (actual.sqlState().equals(expected.sqlState()))
        return new Judgement(Verdict.PASSED, tableDifference == null ? "" : "same SQLSTATE, but " + tableDifference);
      if (actual.sqlState().equals(FEATURE_NOT_SUPPORTED))
        return new Judgement(Verdict.REFUSED, actual.message());
      return new Judgement(Verdict.WRONG, "another SQLSTATE than PostgreSQL: " + actual.message());
    }
    if (actual.failed())
      return new Judgement(Verdict.REFUSED, actual.message());
    String difference = answers(expected, actual, keys);
    if (difference == null)
      difference = tableDifference;
    return difference == null ? new Judgement(Verdict.PASSED, "") : new Judgement(Verdict.WRONG, difference);
  }

  /** @return how two successful answers differ, {@code null} when they are the same result */
  static String answers(final RegressSubject.Answer expected, final RegressSubject.Answer actual, final List<List<Object>> keys) {
    if ((expected.columns() == null) != (actual.columns() == null))
      return expected.columns() == null ? "PostgreSQL answers no rows and no columns, the other side does"
          : "PostgreSQL answers a result, the other side none";
    if (expected.affected() >= 0 && expected.affected() != actual.affected())
      return "rows changed: PostgreSQL " + expected.affected() + ", got " + actual.affected();
    if (expected.columns() == null)
      return null;
    if (!expected.columns().equals(actual.columns()))
      return "column names differ: PostgreSQL " + expected.columns() + ", got " + actual.columns();
    if (!expected.types().equals(actual.types()))
      return "column types differ: PostgreSQL " + expected.types() + ", got " + actual.types();
    final List<List<Object>> a = expected.rows();
    final List<List<Object>> b = actual.rows();
    if (a.size() != b.size())
      return "row counts differ: PostgreSQL " + a.size() + ", got " + b.size();
    if (keys == null)
      return rows(a, b);
    // rows that tie on the sort keys may come in any order: the answer is compared group by group
    int start = 0;
    for (int i = 1; i <= a.size(); i++)
      if (i == a.size() || !same(keys.get(start), keys.get(i))) {
        final String difference = rows(a.subList(start, i), b.subList(start, i));
        if (difference != null)
          return "the rows in the order of ORDER BY differ from row " + (start + 1) + ": " + difference;
        start = i;
      }
    return null;
  }

  private static boolean same(final List<Object> a, final List<Object> b) {
    if (a.size() != b.size())
      return false;
    for (int i = 0; i < a.size(); i++)
      if (!Values.equal(a.get(i), b.get(i), Values.DEFAULT_TOLERANCE))
        return false;
    return true;
  }

  /** @return how two sets of rows differ as multisets, {@code null} when they do not */
  static String rows(final List<List<Object>> expected, final List<List<Object>> actual) {
    if (expected == null || actual == null)
      return expected == actual ? null : "one side has no such table";
    if (expected.size() != actual.size())
      return "row counts differ: PostgreSQL " + expected.size() + ", got " + actual.size();
    final List<List<Object>> a = sorted(expected);
    final List<List<Object>> b = sorted(actual);
    final List<List<Object>> onlyExpected = new ArrayList<>();
    final List<List<Object>> onlyActual = new ArrayList<>();
    for (int i = 0; i < a.size(); i++)
      if (!same(a.get(i), b.get(i))) {
        onlyExpected.add(a.get(i));
        onlyActual.add(b.get(i));
      }
    // values within the tolerance of floating point can sort apart: match what is left one by one
    if (onlyExpected.size() <= GREEDY_LIMIT)
      onlyExpected.removeIf(row -> {
        for (int i = 0; i < onlyActual.size(); i++)
          if (same(row, onlyActual.get(i))) {
            onlyActual.remove(i);
            return true;
          }
        return false;
      });
    if (onlyExpected.isEmpty())
      return null;
    return "rows differ in " + onlyExpected.size() + ": PostgreSQL has " + shortened(Values.render(onlyExpected.get(0)))
        + ", got " + shortened(Values.render(onlyActual.get(0))) + " instead";
  }

  private static String shortened(final String text) {
    return text.length() <= 200 ? text : text.substring(0, 200) + "...";
  }

  private static List<List<Object>> sorted(final List<List<Object>> rows) {
    record Keyed(String key, List<Object> row) {
    }
    final List<Keyed> keyed = new ArrayList<>(rows.size());
    for (final List<Object> row : rows)
      keyed.add(new Keyed(canonical(row), row));
    keyed.sort(Comparator.comparing(Keyed::key));
    return keyed.stream().map(Keyed::row).toList();
  }

  /** A text that is the same for equal values: numbers by value to twelve digits, the keys of a map in order. */
  private static String canonical(final Object value) {
    if (value instanceof Double number && Double.isFinite(number))
      return canonical(BigDecimal.valueOf(number));
    if (value instanceof BigDecimal number)
      return number.round(new MathContext(12)).stripTrailingZeros().toPlainString();
    if (value instanceof List<?> list) {
      final StringBuilder text = new StringBuilder("[");
      for (final Object each : list)
        text.append(canonical(each)).append(',');
      return text.append(']').toString();
    }
    if (value instanceof Map<?, ?> map) {
      final Map<String, String> entries = new TreeMap<>();
      map.forEach((key, each) -> entries.put(String.valueOf(key), canonical(each)));
      return entries.toString();
    }
    return value instanceof String text ? "'" + text + "'" : String.valueOf(value);
  }
}
