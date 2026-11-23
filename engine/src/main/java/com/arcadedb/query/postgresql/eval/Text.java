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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Text rules of PostgreSQL: the collation, {@code LIKE} and {@code ILIKE}, and the text functions of the subset.
 *
 * <p><b>Collation.</b> PostgreSQL orders text by the collation of the database, which depends on the server. This language
 * orders by code point, which is the byte order of UTF-8 and therefore the {@code C} collation ({@code LC_COLLATE=C}):
 * {@code 'Z' < 'a'}, and every upper case letter sorts before every lower case one. The answer corpus of the prototype is
 * recorded from a PostgreSQL server that runs with the {@code C} collation. {@link String#compareTo} is not that order for
 * characters outside the Basic Multilingual Plane (it compares UTF-16 code units), so {@link #compare} walks code points.
 */
public final class Text {
  private Text() {
  }

  /** The {@code C} collation: code point order, equal to the byte order of the UTF-8 encoding. */
  public static int compare(final String a, final String b) {
    final int lengthA = a.length();
    final int lengthB = b.length();
    int i = 0;
    int j = 0;
    while (i < lengthA && j < lengthB) {
      final int cpA = a.codePointAt(i);
      final int cpB = b.codePointAt(j);
      if (cpA != cpB)
        return cpA < cpB ? -1 : 1;
      i += Character.charCount(cpA);
      j += Character.charCount(cpB);
    }
    if (i < lengthA)
      return 1;
    if (j < lengthB)
      return -1;
    return 0;
  }

  // ---- LIKE ----

  /**
   * {@code text LIKE pattern [ESCAPE escape]}. {@code %} matches any sequence of characters, {@code _} any single character, the
   * escape character makes the next character literal. The default escape is the backslash, an empty escape string turns the
   * escaping off.
   *
   * @param escape    {@code null} for the default, otherwise one character or the empty string
   * @param ignoreCase true for {@code ILIKE}
   */
  public static boolean like(final String text, final String pattern, final String escape, final boolean ignoreCase) {
    int escapeCodePoint = '\\';
    if (escape != null) {
      if (escape.isEmpty())
        escapeCodePoint = -1;
      else if (escape.codePointCount(0, escape.length()) == 1)
        escapeCodePoint = escape.codePointAt(0);
      else
        throw new PostgreSQLEvaluationException("22019", "invalid escape string. Hint: Escape string must be empty or one character.");
    }

    // the pattern as tokens: a literal code point, or one of two wildcards
    final int any = -2;
    final int one = -3;
    final List<Integer> tokens = new ArrayList<>();
    final int[] patternCodePoints = pattern.codePoints().toArray();
    for (int i = 0; i < patternCodePoints.length; i++) {
      final int cp = patternCodePoints[i];
      if (cp == escapeCodePoint) {
        if (i + 1 >= patternCodePoints.length)
          throw new PostgreSQLEvaluationException("22025", "LIKE pattern must not end with escape character");
        tokens.add(fold(patternCodePoints[++i], ignoreCase));
      } else if (cp == '%')
        tokens.add(any);
      else if (cp == '_')
        tokens.add(one);
      else
        tokens.add(fold(cp, ignoreCase));
    }

    final int[] input = text.codePoints().map(cp -> fold(cp, ignoreCase)).toArray();
    int t = 0;
    int p = 0;
    int starToken = -1;
    int starInput = 0;
    while (t < input.length) {
      if (p < tokens.size() && tokens.get(p) == any) {
        starToken = p++;
        starInput = t;
      } else if (p < tokens.size() && (tokens.get(p) == one || tokens.get(p) == input[t])) {
        ++p;
        ++t;
      } else if (starToken >= 0) {
        p = starToken + 1;
        t = ++starInput;
      } else
        return false;
    }
    while (p < tokens.size() && tokens.get(p) == any)
      ++p;
    return p == tokens.size();
  }

  private static int fold(final int codePoint, final boolean ignoreCase) {
    return ignoreCase ? Character.toLowerCase(codePoint) : codePoint;
  }

  // ---- functions ----

  public static String lower(final String text) {
    return text.toLowerCase(Locale.ROOT);
  }

  public static String upper(final String text) {
    return text.toUpperCase(Locale.ROOT);
  }

  /** {@code initcap}: the first letter of every word upper case and the rest lower case; a word is a run of letters and digits. */
  public static String initcap(final String text) {
    final StringBuilder out = new StringBuilder(text.length());
    boolean startOfWord = true;
    for (int i = 0; i < text.length();) {
      final int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.isLetterOrDigit(cp)) {
        out.appendCodePoint(startOfWord ? Character.toUpperCase(cp) : Character.toLowerCase(cp));
        startOfWord = false;
      } else {
        out.appendCodePoint(cp);
        startOfWord = true;
      }
    }
    return out.toString();
  }

  /** {@code length}, {@code char_length}: characters, not bytes and not UTF-16 units. */
  public static int length(final String text) {
    return text.codePointCount(0, text.length());
  }

  public static int octetLength(final String text) {
    return text.getBytes(StandardCharsets.UTF_8).length;
  }

  /**
   * {@code substring(text, start [, count])} with the 1-based, clamping rules of PostgreSQL: a start before 1 uses up the count
   * ({@code substring('hello', 0, 2)} is {@code 'h'}), a negative count is SQLSTATE {@code 22011}.
   *
   * @param count {@code null} for no count
   */
  public static String substring(final String text, final long start, final Long count) {
    final int n = length(text);
    long end = Long.MAX_VALUE;
    if (count != null) {
      if (count < 0)
        throw new PostgreSQLEvaluationException("22011", "negative substring length not allowed");
      end = start > Long.MAX_VALUE - count ? Long.MAX_VALUE : start + count;
    }
    final long from = Math.max(start, 1L);
    final long to = Math.min(end, (long) n + 1L);
    if (to <= from)
      return "";
    final int beginIndex = text.offsetByCodePoints(0, (int) (from - 1));
    final int endIndex = text.offsetByCodePoints(beginIndex, (int) (to - from));
    return text.substring(beginIndex, endIndex);
  }

  /** {@code position(needle in haystack)} and {@code strpos}: 1-based, 0 when absent, 1 for an empty needle. */
  public static int position(final String haystack, final String needle) {
    final int index = haystack.indexOf(needle);
    return index < 0 ? 0 : haystack.codePointCount(0, index) + 1;
  }

  /** {@code btrim}, {@code ltrim}, {@code rtrim}: removes the characters of {@code characters} (a space by default). */
  public static String trim(final String text, final String characters, final boolean leading, final boolean trailing) {
    int begin = 0;
    int end = text.length();
    if (leading)
      while (begin < end && characters.indexOf(text.charAt(begin)) >= 0)
        ++begin;
    if (trailing)
      while (end > begin && characters.indexOf(text.charAt(end - 1)) >= 0)
        --end;
    return text.substring(begin, end);
  }

  public static String replace(final String text, final String from, final String to) {
    return from.isEmpty() ? text : text.replace(from, to);
  }

  public static String reverse(final String text) {
    return new StringBuilder(text).reverse().toString();
  }

  /** {@code left(text, n)}: the first n characters, all but the last |n| for a negative n. */
  public static String left(final String text, final int n) {
    final int length = length(text);
    final int count = n >= 0 ? Math.min(n, length) : Math.max(length + n, 0);
    return text.substring(0, text.offsetByCodePoints(0, count));
  }

  /** {@code right(text, n)}: the last n characters, all but the first |n| for a negative n. */
  public static String right(final String text, final int n) {
    final int length = length(text);
    final int count = n >= 0 ? Math.min(n, length) : Math.max(length + n, 0);
    return text.substring(text.offsetByCodePoints(0, length - count));
  }

  public static String repeat(final String text, final int times) {
    if (times <= 0 || text.isEmpty())
      return "";
    if ((long) text.length() * times > 1_000_000_000L)
      throw new PostgreSQLEvaluationException("54000", "requested length too large");
    return text.repeat(times);
  }
}
