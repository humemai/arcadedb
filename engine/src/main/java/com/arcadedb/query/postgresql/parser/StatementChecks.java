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
package com.arcadedb.query.postgresql.parser;

import com.arcadedb.query.postgresql.grammar.PostgreSQLLexer;
import com.arcadedb.query.postgresql.grammar.PostgreSQLParser;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import java.util.ArrayList;
import java.util.List;

/**
 * What PostgreSQL rejects as a syntax error although its grammar reads the text: the checks that the scanner and the grammar
 * actions of PostgreSQL make on the value of a token or on the combination of two clauses. They run after the text has been read
 * and before a construct outside the subset is reported, so that text PostgreSQL itself refuses is a {@code 42601} and not a
 * {@code 0A000}.
 */
final class StatementChecks {
  private StatementChecks() {
  }

  static void run(final PostgreSQLParser.RootContext root, final CommonTokenStream stream) {
    final List<Token> tokens = new ArrayList<>();
    for (final Token token : stream.getTokens())
      if (token.getChannel() == Token.DEFAULT_CHANNEL && token.getType() != Token.EOF)
        tokens.add(token);

    unicodeEscapes(tokens);
    fetchWithTies(root, tokens);
  }

  // ------------------------------------------------------------------------------------------------ U&'...' strings

  /** The escapes of {@code U&'...'} and {@code U&"..."}, and the {@code UESCAPE} that follows one: {@code str_udeescape}. */
  private static void unicodeEscapes(final List<Token> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      final Token token = tokens.get(i);
      final int type = token.getType();
      if (type != PostgreSQLLexer.USCONST && type != PostgreSQLLexer.UIDENT)
        continue;

      int escape = '\\';
      if (i + 1 < tokens.size() && tokens.get(i + 1).getType() == PostgreSQLLexer.UESCAPE) {
        // UESCAPE must be followed by a string of one character that is not part of an escape
        final Token next = i + 2 < tokens.size() ? tokens.get(i + 2) : tokens.get(i + 1);
        if (next.getType() != PostgreSQLLexer.SCONST)
          throw Diagnostics.syntaxError("UESCAPE must be followed by a simple string literal", next);
        final String character = next.getText().substring(1, next.getText().length() - 1);
        if (character.codePointCount(0, character.length()) != 1)
          throw Diagnostics.syntaxError("invalid Unicode escape character", next);
        escape = character.codePointAt(0);
        if (Character.digit(escape, 16) >= 0 || escape == '+' || escape == '\'' || escape == '"'
            || Character.isWhitespace(escape))
          throw Diagnostics.syntaxError("invalid Unicode escape character", next);
      }

      final String raw = token.getText();
      final String quote = type == PostgreSQLLexer.USCONST ? "'" : "\"";
      final String content = raw.substring(3, raw.length() - 1).replace(quote + quote, quote);
      checkUnicodeEscapes(content, escape, token);
    }
  }

  private static void checkUnicodeEscapes(final String content, final int escape, final Token token) {
    final int length = content.length();
    int high = -1;
    int position = 0;
    while (position < length) {
      final int c = content.codePointAt(position);
      if (c != escape) {
        if (high >= 0)
          throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
        position += Character.charCount(c);
        continue;
      }
      final int after = position + Character.charCount(c);
      if (after < length && content.codePointAt(after) == escape) {
        // the escape character twice is the escape character itself
        if (high >= 0)
          throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
        position = after + Character.charCount(escape);
        continue;
      }
      final boolean longForm = after < length && content.charAt(after) == '+';
      final int digits = longForm ? 6 : 4;
      final int start = longForm ? after + 1 : after;
      if (start + digits > length || !hex(content, start, digits))
        throw Diagnostics.syntaxError("invalid Unicode escape", token);
      final int code = Integer.parseInt(content.substring(start, start + digits), 16);
      position = start + digits;
      if (high >= 0) {
        if (code < 0xDC00 || code > 0xDFFF)
          throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
        high = -1;
      } else if (code >= 0xD800 && code <= 0xDBFF) {
        high = code;
      } else if (code >= 0xDC00 && code <= 0xDFFF) {
        throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
      } else if (code == 0 || code > 0x10FFFF) {
        throw Diagnostics.syntaxError("invalid Unicode escape value", token);
      }
    }
    if (high >= 0)
      throw Diagnostics.syntaxError("invalid Unicode surrogate pair", token);
  }

  private static boolean hex(final String text, final int start, final int count) {
    for (int i = start; i < start + count; i++)
      if (Character.digit(text.charAt(i), 16) < 0)
        return false;
    return true;
  }

  // ------------------------------------------------------------------------------------------------ FETCH ... WITH TIES

  /** {@code WITH TIES} needs an {@code ORDER BY}, and does not go with {@code SKIP LOCKED}. */
  private static void fetchWithTies(final ParseTree tree, final List<Token> tokens) {
    final PostgreSQLParser.UnsupportedFetchClauseContext fetch = find(tree, PostgreSQLParser.UnsupportedFetchClauseContext.class);
    if (fetch == null || fetch.getToken(PostgreSQLParser.WITH, 0) == null)
      return;

    ParserRuleContext parent = fetch;
    while (parent != null && !(parent instanceof PostgreSQLParser.SelectStmtContext))
      parent = parent.getParent();
    final Token with = fetch.getToken(PostgreSQLParser.WITH, 0).getSymbol();
    if (parent instanceof PostgreSQLParser.SelectStmtContext select && select.sortClause() == null)
      throw Diagnostics.syntaxError("WITH TIES cannot be specified without ORDER BY clause", with);

    for (int i = 0; i + 1 < tokens.size(); i++)
      if (tokens.get(i).getType() == PostgreSQLParser.SKIP_KW && tokens.get(i + 1).getType() == PostgreSQLParser.LOCKED)
        throw Diagnostics.syntaxError("SKIP LOCKED and WITH TIES options cannot be used together", tokens.get(i));
  }

  private static <T extends ParseTree> T find(final ParseTree tree, final Class<T> type) {
    if (type.isInstance(tree))
      return type.cast(tree);
    for (int i = 0; i < tree.getChildCount(); i++) {
      final T found = find(tree.getChild(i), type);
      if (found != null)
        return found;
    }
    return null;
  }
}
