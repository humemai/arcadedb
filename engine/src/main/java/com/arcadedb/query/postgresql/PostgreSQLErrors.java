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
package com.arcadedb.query.postgresql;

import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a client that speaks PostgreSQL's protocol needs from an error of the {@code postgresql} language: the SQLSTATE, the
 * message, and the position in the statement. The exceptions of the language each carry a SQLSTATE ({@link #sqlState}); the
 * messages of the parser end with {@code (line L, column C)}, which PostgreSQL does not put in the message but in a field of its
 * own, the character position, which {@link #position} reads off and {@link #message} takes out of the message.
 */
public final class PostgreSQLErrors {
  /** The words {@code SourcePosition.toString()} ends a message with. */
  private static final Pattern POSITION = Pattern.compile(" \\(line (\\d+), column (\\d+)\\)$");

  private PostgreSQLErrors() {
  }

  /**
   * @return the SQLSTATE of an error of the language, looking through the causes of {@code error}, or {@code null} when none of
   *     them is one
   */
  public static String sqlState(final Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof PostgreSQLSyntaxException e)
        return e.getSqlState();
      if (current instanceof PostgreSQLFeatureNotSupportedException e)
        return e.getSqlState();
      if (current instanceof PostgreSQLInvalidEscapeException e)
        return e.getSqlState();
      if (current instanceof PostgreSQLCatalogException e)
        return e.getSqlState();
      if (current instanceof PostgreSQLEvaluationException e)
        return e.getSqlState();
      if (current.getCause() == current)
        break;
    }
    return null;
  }

  /** @return the message of {@code error} without the {@code (line L, column C)} that ends the messages of the parser */
  public static String message(final Throwable error) {
    final String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    final Matcher matcher = POSITION.matcher(message);
    return matcher.find() ? message.substring(0, matcher.start()) : message;
  }

  /**
   * @param text the statement the error is about, exactly as it was handed to the language
   *
   * @return the position of the error in {@code text} as PostgreSQL reports it: counted in characters from 1, or 0 when the
   *     message names none
   */
  public static int position(final Throwable error, final String text) {
    final String message = error.getMessage();
    if (message == null || text == null)
      return 0;
    final Matcher matcher = POSITION.matcher(message);
    if (!matcher.find())
      return 0;
    final int line = Integer.parseInt(matcher.group(1));
    final int column = Integer.parseInt(matcher.group(2));
    // the lexer counts code points, and a line ends at a line feed
    int offset = 0;
    int currentLine = 1;
    final int length = text.codePointCount(0, text.length());
    while (currentLine < line && offset < length) {
      if (text.codePointAt(text.offsetByCodePoints(0, offset)) == '\n')
        ++currentLine;
      ++offset;
    }
    return currentLine == line ? offset + column : 0;
  }
}
