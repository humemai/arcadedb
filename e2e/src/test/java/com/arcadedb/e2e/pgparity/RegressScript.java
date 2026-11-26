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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splits a script into statements and backslash commands the way {@code psql} does (fork issue #119): a semicolon ends a
 * statement outside of strings, quoted identifiers, dollar quotes, comments, parentheses and the {@code BEGIN ... END} body of a
 * function; a backslash starts a {@code psql} command, which ends with its line or with the next backslash; {@code \;} ends a
 * statement too (the harness sends the two statements one after the other); {@code :name}, {@code :'name'} and {@code :"name"}
 * are replaced by the value of a variable that is set. Comments before a statement are not part of it.
 * <p>
 * It reads on demand, because what the text means depends on what ran before: the variables, the setting
 * {@code standard_conforming_strings}, and the data lines of a {@code COPY ... FROM STDIN}, which are read only when the server
 * asks for them.
 */
final class RegressScript {
  enum Kind {
    STATEMENT, COMMAND
  }

  /** @param text a statement as it is sent, or a command without its backslash */
  record Item(Kind kind, int line, String text) {
  }

  private final String        source;
  private final StringBuilder buffer = new StringBuilder();
  /** The first words of the statement, to know a {@code CREATE FUNCTION}, whose body can hold semicolons. */
  private final List<String>  words  = new ArrayList<>(4);
  private       int           position;
  private       int           line   = 1;
  private       int           bufferLine;
  private       int           parentheses;
  private       int           blocks;

  RegressScript(final String source) {
    this.source = source.replace("\r\n", "\n");
  }

  /** The next statement or command, {@code null} at the end of the script. */
  Item next(final boolean standardStrings, final Map<String, String> variables) {
    while (position < source.length()) {
      final char c = source.charAt(position);
      if (source.startsWith("--", position)) {
        keep(endOfLine(position));
      } else if (source.startsWith("/*", position)) {
        keep(RegressSql.endOfComment(source, position));
      } else if (c == '\'') {
        take(RegressSql.endOfQuoted(source, position, '\'', !standardStrings || afterEscapePrefix()));
      } else if (c == '"') {
        take(RegressSql.endOfQuoted(source, position, '"', false));
      } else if (c == '$') {
        dollar();
      } else if (c == '\\') {
        final Item item = backslash();
        if (item != null)
          return item;
      } else if (c == ':') {
        colon(variables);
      } else if (c == ';') {
        take(position + 1);
        if (parentheses == 0 && blocks == 0)
          return statement();
      } else if (RegressSql.isNameStart(c)) {
        word();
      } else if (Character.isWhitespace(c)) {
        keep(position + 1);
      } else {
        if (c == '(')
          parentheses++;
        else if (c == ')' && parentheses > 0)
          parentheses--;
        take(position + 1);
      }
    }
    return takeBuffer();
  }

  /** The statement in the buffer that no semicolon has ended, as {@code \g} sends it; {@code null} when there is none. */
  Item takeBuffer() {
    return buffer.isEmpty() ? null : statement();
  }

  /** Throws away the statement in the buffer, as {@code \r} does. */
  void discard() {
    takeBuffer();
  }

  /**
   * The data lines of a {@code COPY ... FROM STDIN}: the lines after the one that holds the statement, up to the line
   * {@code \.}, which is consumed and not returned.
   */
  String copyData() {
    final int end = source.indexOf('\n', position);
    if (end < 0) {
      advance(source.length());
      return "";
    }
    advance(end + 1);
    final StringBuilder data = new StringBuilder();
    while (position < source.length()) {
      final int endOfData = endOfLine(position);
      final String text = source.substring(position, endOfData);
      advance(Math.min(endOfData + 1, source.length()));
      if (text.equals("\\."))
        break;
      data.append(text).append('\n');
    }
    return data.toString();
  }

  private Item statement() {
    final Item item = new Item(Kind.STATEMENT, bufferLine, buffer.toString().stripTrailing());
    buffer.setLength(0);
    words.clear();
    parentheses = 0;
    blocks = 0;
    return item;
  }

  private int endOfLine(final int from) {
    final int end = source.indexOf('\n', from);
    return end < 0 ? source.length() : end;
  }

  private void advance(final int end) {
    for (int i = position; i < end; i++)
      if (source.charAt(i) == '\n')
        line++;
    position = end;
  }

  /** Consumes a part of the statement. */
  private void take(final int end) {
    append(source.substring(position, end));
    advance(end);
  }

  /** Consumes blank space or a comment: part of the statement once it has started, dropped before that. */
  private void keep(final int end) {
    if (!buffer.isEmpty())
      buffer.append(source, position, end);
    advance(end);
  }

  private void append(final String text) {
    if (buffer.isEmpty())
      bufferLine = line;
    buffer.append(text);
  }

  private boolean afterEscapePrefix() {
    final int length = buffer.length();
    return length > 0 && Character.toLowerCase(buffer.charAt(length - 1)) == 'e'
        && (length < 2 || !RegressSql.isNamePart(buffer.charAt(length - 2)));
  }

  private void dollar() {
    final boolean insideName = !buffer.isEmpty() && position > 0 && RegressSql.isNamePart(source.charAt(position - 1));
    final String delimiter = insideName ? null : RegressSql.dollarDelimiter(source, position);
    if (delimiter == null) {
      take(position + 1);
      return;
    }
    final int close = source.indexOf(delimiter, position + delimiter.length());
    take(close < 0 ? source.length() : close + delimiter.length());
  }

  private void word() {
    int end = position + 1;
    while (end < source.length() && RegressSql.isNamePart(source.charAt(end)))
      end++;
    if (parentheses == 0) {
      final String word = source.substring(position, end).toLowerCase(Locale.ROOT);
      if (buffer.isEmpty())
        words.clear();
      if (words.size() < 4)
        words.add(word);
      if (createsRoutine()) {
        if (word.equals("begin") || word.equals("case") && blocks > 0)
          blocks++;
        else if (word.equals("end") && blocks > 0)
          blocks--;
      }
    }
    take(end);
  }

  private boolean createsRoutine() {
    if (words.size() < 2 || !words.get(0).equals("create"))
      return false;
    if (isRoutine(words.get(1)))
      return true;
    return words.size() == 4 && words.get(1).equals("or") && words.get(2).equals("replace") && isRoutine(words.get(3));
  }

  private static boolean isRoutine(final String word) {
    return word.equals("function") || word.equals("procedure");
  }

  private Item backslash() {
    final char next = position + 1 < source.length() ? source.charAt(position + 1) : '\n';
    if (next == ';') {
      advance(position + 2);
      return takeBuffer();
    }
    if (next == ':') {
      append(":");
      advance(position + 2);
      return null;
    }
    if (next == '\\') {
      advance(position + 2);
      return null;
    }
    int end = position + 1;
    while (end < source.length() && !Character.isWhitespace(source.charAt(end))
        && (end == position + 1 || source.charAt(end) != '\\'))
      end++;
    final String name = source.substring(position + 1, end);
    // \copy and \! take the rest of the line as it is; the arguments of every other command end at a backslash outside quotes
    if (name.equals("copy") || name.equals("!"))
      end = endOfLine(end);
    else {
      char quote = 0;
      while (end < source.length() && source.charAt(end) != '\n') {
        final char c = source.charAt(end);
        if (quote != 0) {
          if (c == '\\' && quote == '\'' && end + 1 < source.length() && source.charAt(end + 1) != '\n')
            end++;
          else if (c == quote)
            quote = 0;
        } else if (c == '\'' || c == '"' || c == '`')
          quote = c;
        else if (c == '\\')
          break;
        end++;
      }
    }
    final Item item = new Item(Kind.COMMAND, line, source.substring(position + 1, end).strip());
    advance(end);
    return item;
  }

  private void colon(final Map<String, String> variables) {
    if (source.startsWith("::", position)) {
      take(position + 2);
      return;
    }
    final char next = position + 1 < source.length() ? source.charAt(position + 1) : 0;
    if (next == '\'' || next == '"') {
      final String name = variableName(position + 2);
      final int close = position + 2 + name.length();
      if (!name.isEmpty() && close < source.length() && source.charAt(close) == next && variables.containsKey(name)) {
        append(quote(variables.get(name), next));
        advance(close + 1);
        return;
      }
    } else if (source.startsWith(":{?", position)) {
      final String name = variableName(position + 3);
      final int close = position + 3 + name.length();
      if (!name.isEmpty() && close < source.length() && source.charAt(close) == '}') {
        append(variables.containsKey(name) ? "TRUE" : "FALSE");
        advance(close + 1);
        return;
      }
    } else {
      final String name = variableName(position + 1);
      if (!name.isEmpty() && variables.containsKey(name)) {
        append(variables.get(name));
        advance(position + 1 + name.length());
        return;
      }
    }
    take(position + 1);
  }

  private String variableName(final int from) {
    int end = from;
    while (end < source.length() && (Character.isLetterOrDigit(source.charAt(end)) || source.charAt(end) == '_'
        || source.charAt(end) > 127))
      end++;
    return source.substring(Math.min(from, source.length()), end);
  }

  /** A value as a string literal or as a quoted identifier, as {@code :'name'} and {@code :"name"} write it. */
  static String quote(final String value, final char quote) {
    final String doubled = value.replace(String.valueOf(quote), String.valueOf(quote) + quote);
    if (quote == '\'' && value.indexOf('\\') >= 0)
      return "E'" + doubled.replace("\\", "\\\\") + "'";
    return quote + doubled + quote;
  }
}
