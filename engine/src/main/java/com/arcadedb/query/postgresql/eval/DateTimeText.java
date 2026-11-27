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

import java.time.Month;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The error for a text of a date or a timestamp that is not in the one form the language reads, year-month-day with an optional
 * time (fork issue #200). PostgreSQL reads many more forms, so the code depends on what PostgreSQL 18 can do with the text:
 * <ul>
 * <li>{@code 22008}: year-month-day whose date is none of PostgreSQL's: no such day, before 4714-11-24 BC, or after the last
 * year of the type;</li>
 * <li>{@code 22007}: a text that PostgreSQL reads in no way: a word that is no part of a date, too few fields, a special word
 * such as {@code epoch} together with something else, ...;</li>
 * <li>{@code 0A000}: every other text, a form that PostgreSQL may read.</li>
 * </ul>
 * The order of the day and the month is the default of PostgreSQL, month first ({@code DateStyle} {@code ISO, MDY}).
 */
final class DateTimeText {
  /** The last year of a {@code date} and of a {@code timestamp} of PostgreSQL. */
  private static final long LAST_YEAR_OF_DATE      = 5_874_897L;
  private static final long LAST_YEAR_OF_TIMESTAMP = 294_276L;
  /** The first day of both, 4714-11-24 BC. */
  private static final long FIRST_YEAR_BC          = 4714L;

  private static final Pattern YEAR_MONTH_DAY =
      Pattern.compile("(\\d+)-(\\d{1,2})-(\\d{1,2})(?:[ t]\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?)?(\\s*bc)?");
  private static final Pattern WORD           = Pattern.compile("[a-z]+");
  private static final Pattern DIGITS         = Pattern.compile("\\d+");
  private static final Pattern TIME           = Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?");
  /** The name of a time zone, such as {@code America/New_York}. */
  private static final Pattern ZONE_NAME      = Pattern.compile("[a-z]/[a-z]");
  /** PostgreSQL takes {@code 99-08-} of {@code 99-08-Jan} as one field, which ends in a separator and so is no date. */
  private static final Pattern FIELD_ENDS_OPEN = Pattern.compile("^\\d+([-/.])\\d+\\1[a-z]");
  /** One or two short numbers in front of a word. */
  private static final Pattern NUMBERS_FIRST  = Pattern.compile("^(\\d{1,2})\\s+(?:(\\d{1,2})\\s+)?([a-z]+).*");

  private static final Set<String> MONTHS = Set.of("january", "jan", "february", "feb", "march", "mar", "april", "apr", "may",
      "june", "jun", "july", "jul", "august", "aug", "september", "sep", "sept", "october", "oct", "november", "nov", "december",
      "dec");
  /** Words that are a value, or the date of one, on their own. */
  private static final Set<String> SPECIAL = Set.of("epoch", "infinity", "now", "today", "tomorrow", "yesterday", "allballs");
  /** The other words of a date and time: days of the week, eras, markers, and the common abbreviations of time zones. */
  private static final Set<String> OTHER_WORDS = Set.of("sunday", "sun", "monday", "mon", "tuesday", "tue", "tues", "wednesday",
      "wed", "weds", "thursday", "thu", "thur", "thurs", "friday", "fri", "saturday", "sat", "ad", "bc", "am", "pm", "t", "j",
      "jd", "julian", "at", "on", "dst", "z", "zulu", "ut", "utc", "uct", "gmt", "est", "edt", "cst", "cdt", "mst", "mdt", "pst",
      "pdt", "akst", "akdt", "hst", "ast", "adt", "nst", "ndt", "wet", "west", "cet", "cest", "eet", "eest", "bst", "msk", "msd",
      "ist", "jst", "kst", "hkt", "awst", "acst", "acdt", "aest", "aedt", "nzst", "nzdt", "sast", "cat", "eat", "wat", "ict",
      "pkt");

  private DateTimeText() {
  }

  /** The error for {@code text}, which is not in the form the language reads, as a {@code date} or a {@code timestamp}. */
  static PostgreSQLEvaluationException unread(final String text, final PgType type) {
    final String s = text.strip().toLowerCase(Locale.ROOT);
    final String name = type == PgType.DATE ? "date" : "timestamp";
    final Matcher date = YEAR_MONTH_DAY.matcher(s);
    if (date.matches()) {
      if (!exists(date.group(1), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)), date.group(4) != null, type))
        return outOfRange(text);
    } else if (!mayBeRead(s))
      return new PostgreSQLEvaluationException("22007", "invalid input syntax for type " + name + ": \"" + text + "\"");
    return PostgreSQLEvaluationException.unsupported("This form of a " + name + " is not supported: \"" + text
        + "\". Write year-month-day, such as 2026-03-09, with a time as 10:11:12");
  }

  /** The error for a date or a time that PostgreSQL does not have. */
  static PostgreSQLEvaluationException outOfRange(final String text) {
    return new PostgreSQLEvaluationException("22008", "date/time field value out of range: \"" + text + "\"");
  }

  /** Whether PostgreSQL has this day. */
  private static boolean exists(final String digits, final int month, final int day, final boolean bc, final PgType type) {
    if (digits.length() > 9)
      return false;
    final long year = Long.parseLong(digits);
    if (year == 0 || month < 1 || month > 12)
      return false;
    // 1 BC is the year 0 of the calendar arithmetic, a leap year
    final long counted = bc ? 1 - year : year;
    final boolean leap = counted % 4 == 0 && (counted % 100 != 0 || counted % 400 == 0);
    if (day < 1 || day > Month.of(month).length(leap))
      return false;
    if (bc)
      return year < FIRST_YEAR_BC || year == FIRST_YEAR_BC && (month > 11 || month == 11 && day >= 24);
    return year <= (type == PgType.DATE ? LAST_YEAR_OF_DATE : LAST_YEAR_OF_TIMESTAMP);
  }

  /** Whether PostgreSQL may read the text, in lower case. {@code false} only where it certainly does not. */
  private static boolean mayBeRead(final String s) {
    if (s.chars().anyMatch(c -> c > 127))
      return false;
    if (ZONE_NAME.matcher(s).find())
      return true;
    int months = 0;
    int julian = 0;
    boolean special = false;
    boolean alone = false;
    final Matcher words = WORD.matcher(s);
    while (words.find()) {
      final String word = words.group();
      if (MONTHS.contains(word))
        ++months;
      else if (SPECIAL.contains(word)) {
        special = true;
        alone |= word.equals("epoch") || word.equals("infinity");
      } else if (!OTHER_WORDS.contains(word))
        return false;
      else if (word.equals("j"))
        ++julian;
    }
    if (alone)
      return s.equals("epoch") || s.equals("infinity") || s.equals("-infinity") || s.equals("+infinity");
    if (julian > 1 || FIELD_ENDS_OPEN.matcher(s).find())
      return false;
    // month first: two numbers before a month name are a month and a day already, and a number above 31 is taken as a month
    final Matcher first = NUMBERS_FIRST.matcher(s);
    if (first.matches() && MONTHS.contains(first.group(3)) && (first.group(2) != null || Integer.parseInt(first.group(1)) > 31))
      return false;
    if (special)
      return true;
    // a date has three fields, the numbers outside of a time and the month name, unless one number holds more than one
    int fields = months;
    final Matcher numbers = DIGITS.matcher(TIME.matcher(s).replaceAll(" "));
    while (numbers.find()) {
      if (julian == 1 || numbers.group().length() >= 6)
        return true;
      ++fields;
    }
    return fields >= 3;
  }
}
