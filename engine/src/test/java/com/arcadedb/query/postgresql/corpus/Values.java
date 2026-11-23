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

import com.arcadedb.database.Identifiable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The normalisation rules of the parity comparison, in one place. A value read from PostgreSQL (through JDBC) or from ArcadeDB
 * (a property of a {@code Result}) is turned into one of a closed set, and two values are equal when their normal forms are:
 * <ul>
 *   <li>{@code null} is {@code null}, and {@code NULL} equals {@code NULL} (this is a comparison of results, not of SQL
 *   values);</li>
 *   <li>every integer and every {@code numeric} is a {@link BigDecimal} and numbers compare by value, so {@code 38.5} equals
 *   {@code 38.50} and a {@code Long} equals an {@code Integer};</li>
 *   <li>{@code float4} and {@code float8} are {@link Double}; a comparison with a {@link Double} on either side allows the
 *   tolerance of the statement (default {@link #DEFAULT_TOLERANCE}, relative to the larger of the two values and 1);</li>
 *   <li>text is compared exactly, and so is a boolean;</li>
 *   <li>a date or a timestamp is its ISO text ({@code 2026-01-05}), whatever date class the engine uses;</li>
 *   <li>a record id is its text ({@code #12:3});</li>
 *   <li>a list, an array and a set are a list; a map and a JSON object are a map with sorted keys.</li>
 * </ul>
 */
public final class Values {
  /** Floating point values are equal when they differ by at most this, relative to the larger of 1 and their magnitudes. */
  public static final double DEFAULT_TOLERANCE = 1e-9;

  private Values() {
  }

  public static Object normalize(final Object value) {
    if (value == null)
      return null;
    if (value instanceof Boolean)
      return value;
    if (value instanceof BigDecimal)
      return value;
    if (value instanceof BigInteger integer)
      return new BigDecimal(integer);
    if (value instanceof Double || value instanceof Float)
      return ((Number) value).doubleValue();
    if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte)
      return BigDecimal.valueOf(((Number) value).longValue());
    if (value instanceof CharSequence || value instanceof Character || value instanceof java.util.UUID)
      return value.toString();
    if (value instanceof java.sql.Timestamp timestamp)
      return timestamp.toLocalDateTime().toString();
    if (value instanceof java.sql.Date date)
      return date.toLocalDate().toString();
    if (value instanceof java.sql.Time time)
      return time.toLocalTime().toString();
    if (value instanceof java.util.Date date) {
      // the engine's DATE default is java.util.Date at midnight of the default zone
      final LocalDateTime local = Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime();
      return local.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? local.toLocalDate().toString() : local.toString();
    }
    if (value instanceof java.time.temporal.TemporalAccessor)
      return value.toString();
    if (value instanceof Identifiable identifiable)
      return identifiable.getIdentity().toString();
    if (value instanceof java.sql.Array array) {
      try {
        return normalize(array.getArray());
      } catch (final SQLException e) {
        throw new IllegalStateException("cannot read a SQL array", e);
      }
    }
    if (value instanceof Map<?, ?> map) {
      final Map<String, Object> sorted = new TreeMap<>();
      for (final Map.Entry<?, ?> entry : map.entrySet())
        sorted.put(String.valueOf(entry.getKey()), normalize(entry.getValue()));
      return sorted;
    }
    if (value instanceof Collection<?> collection) {
      final List<Object> list = new ArrayList<>(collection.size());
      for (final Object item : collection)
        list.add(normalize(item));
      return list;
    }
    if (value.getClass().isArray()) {
      final int length = java.lang.reflect.Array.getLength(value);
      final List<Object> list = new ArrayList<>(length);
      for (int i = 0; i < length; i++)
        list.add(normalize(java.lang.reflect.Array.get(value, i)));
      return list;
    }
    return value.toString();
  }

  /**
   * @param expected a normalised value
   * @param actual   a normalised value
   */
  public static boolean equal(final Object expected, final Object actual, final double tolerance) {
    if (expected == null || actual == null)
      return expected == null && actual == null;

    if (expected instanceof Double || actual instanceof Double) {
      if (isNumber(expected) && isNumber(actual)) {
        final double a = ((Number) expected).doubleValue();
        final double b = ((Number) actual).doubleValue();
        if (Double.isNaN(a) || Double.isNaN(b))
          return Double.isNaN(a) && Double.isNaN(b);
        if (Double.isInfinite(a) || Double.isInfinite(b))
          return a == b;
        return Math.abs(a - b) <= tolerance * Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
      }
      return false;
    }

    if (expected instanceof BigDecimal a && actual instanceof BigDecimal b)
      return a.compareTo(b) == 0;

    if (expected instanceof List<?> a && actual instanceof List<?> b) {
      if (a.size() != b.size())
        return false;
      for (int i = 0; i < a.size(); i++)
        if (!equal(a.get(i), b.get(i), tolerance))
          return false;
      return true;
    }

    if (expected instanceof Map<?, ?> a && actual instanceof Map<?, ?> b) {
      if (!a.keySet().equals(b.keySet()))
        return false;
      for (final Map.Entry<?, ?> entry : a.entrySet())
        if (!equal(entry.getValue(), b.get(entry.getKey()), tolerance))
          return false;
      return true;
    }

    return expected.equals(actual);
  }

  private static boolean isNumber(final Object value) {
    return value instanceof Number;
  }

  /** A value as it reads in a message about a difference. */
  public static String render(final Object value) {
    if (value == null)
      return "NULL";
    if (value instanceof BigDecimal decimal)
      return decimal.stripTrailingZeros().toPlainString();
    if (value instanceof String text)
      return "'" + text + "'";
    if (value instanceof List<?> list) {
      final StringBuilder out = new StringBuilder("[");
      for (int i = 0; i < list.size(); i++)
        out.append(i > 0 ? ", " : "").append(render(list.get(i)));
      return out.append(']').toString();
    }
    return String.valueOf(value);
  }
}
