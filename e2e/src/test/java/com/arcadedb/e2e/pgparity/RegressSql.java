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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The tokens of one statement, as much as the regression harness (fork issue #119) has to know about a statement without parsing
 * it: where its strings and comments are, how deep in parentheses a word is, which names it holds. It is no SQL parser and uses
 * none of the language under test.
 */
final class RegressSql {
  enum Type {
    WORD, QUOTED, STRING, NUMBER, SYMBOL
  }

  /**
   * @param text  the text as written; for a quoted identifier what is between the quotes, for a string the whole literal
   * @param start the offset of the first character in the statement
   * @param end   the offset after the last character
   * @param depth the number of parentheses that are open around the token; a parenthesis counts as outside of itself
   */
  record Token(Type type, String text, int start, int end, int depth) {
    boolean is(final String word) {
      return type == Type.WORD && text.equalsIgnoreCase(word);
    }

    boolean isSymbol(final String symbol) {
      return type == Type.SYMBOL && text.equals(symbol);
    }

    /** The name PostgreSQL looks up for an identifier, {@code null} for any other token. */
    String name() {
      if (type == Type.QUOTED)
        return text;
      return type == Type.WORD ? text.toLowerCase(Locale.ROOT) : null;
    }
  }

  private RegressSql() {
  }

  static List<Token> tokens(final String sql) {
    final List<Token> tokens = new ArrayList<>();
    final int length = sql.length();
    int depth = 0;
    int i = 0;
    while (i < length) {
      final char c = sql.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (sql.startsWith("--", i)) {
        final int end = sql.indexOf('\n', i);
        i = end < 0 ? length : end;
      } else if (sql.startsWith("/*", i)) {
        i = endOfComment(sql, i);
      } else if (c == '\'') {
        final boolean escapes = !tokens.isEmpty() && tokens.get(tokens.size() - 1).is("e")
            && tokens.get(tokens.size() - 1).end() == i;
        final int end = endOfQuoted(sql, i, '\'', escapes);
        tokens.add(new Token(Type.STRING, sql.substring(i, end), i, end, depth));
        i = end;
      } else if (c == '"') {
        final int end = endOfQuoted(sql, i, '"', false);
        final String inner = sql.substring(i + 1, Math.max(i + 1, end - 1)).replace("\"\"", "\"");
        tokens.add(new Token(Type.QUOTED, inner, i, end, depth));
        i = end;
      } else if (c == '$' && dollarDelimiter(sql, i) != null) {
        final String delimiter = dollarDelimiter(sql, i);
        final int close = sql.indexOf(delimiter, i + delimiter.length());
        final int end = close < 0 ? length : close + delimiter.length();
        tokens.add(new Token(Type.STRING, sql.substring(i, end), i, end, depth));
        i = end;
      } else if (isNameStart(c)) {
        int end = i + 1;
        while (end < length && isNamePart(sql.charAt(end)))
          end++;
        tokens.add(new Token(Type.WORD, sql.substring(i, end), i, end, depth));
        i = end;
      } else if (Character.isDigit(c)) {
        int end = i + 1;
        while (end < length && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '.' || sql.charAt(end) == '_'))
          end++;
        tokens.add(new Token(Type.NUMBER, sql.substring(i, end), i, end, depth));
        i = end;
      } else {
        final int end = sql.startsWith("::", i) ? i + 2 : i + 1;
        if (c == ')' && depth > 0)
          depth--;
        tokens.add(new Token(Type.SYMBOL, sql.substring(i, end), i, end, depth));
        if (c == '(')
          depth++;
        i = end;
      }
    }
    return tokens;
  }

  static boolean isNameStart(final char c) {
    return Character.isLetter(c) || c == '_' || c > 127;
  }

  static boolean isNamePart(final char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c > 127;
  }

  /** The offset after the block comment that starts at {@code from}; block comments nest, as in PostgreSQL. */
  static int endOfComment(final String sql, final int from) {
    int open = 0;
    int i = from;
    while (i < sql.length()) {
      if (sql.startsWith("/*", i)) {
        open++;
        i += 2;
      } else if (sql.startsWith("*/", i)) {
        open--;
        i += 2;
        if (open == 0)
          return i;
      } else
        i++;
    }
    return sql.length();
  }

  /** The offset after the string or quoted identifier that starts at {@code from}. */
  static int endOfQuoted(final String sql, final int from, final char quote, final boolean backslashEscapes) {
    int i = from + 1;
    while (i < sql.length()) {
      final char c = sql.charAt(i);
      if (backslashEscapes && c == '\\')
        i += 2;
      else if (c != quote)
        i++;
      else if (i + 1 < sql.length() && sql.charAt(i + 1) == quote)
        i += 2;
      else
        return i + 1;
    }
    return sql.length();
  }

  /** The delimiter of the dollar quoted string that starts at {@code from} ({@code $$}, {@code $body$}), else {@code null}. */
  static String dollarDelimiter(final String sql, final int from) {
    int i = from + 1;
    if (i < sql.length() && isNameStart(sql.charAt(i)))
      while (i < sql.length() && sql.charAt(i) != '$' && isNamePart(sql.charAt(i)))
        i++;
    return i < sql.length() && sql.charAt(i) == '$' ? sql.substring(from, i + 1) : null;
  }

  /**
   * The statement in one line for a pattern to match: words in lower case, one blank between tokens, and every string as
   * {@code '?'}, except the one after {@code language}, whose text stays (so {@code LANGUAGE 'C'} reads {@code language c}).
   */
  static String normalized(final List<Token> tokens) {
    final StringBuilder out = new StringBuilder();
    Token previous = null;
    for (final Token token : tokens) {
      if (!out.isEmpty())
        out.append(' ');
      switch (token.type()) {
      case WORD -> out.append(token.text().toLowerCase(Locale.ROOT));
      case QUOTED -> out.append('"').append(token.text()).append('"');
      case STRING -> {
        if (previous != null && previous.is("language") && token.text().length() >= 2)
          out.append(token.text().substring(1, token.text().length() - 1).toLowerCase(Locale.ROOT));
        else
          out.append("'?'");
      }
      default -> out.append(token.text());
      }
      previous = token;
    }
    return out.toString();
  }

  /** The first word of the statement in lower case, looking through opening parentheses; empty when there is none. */
  static String firstWord(final List<Token> tokens) {
    for (final Token token : tokens)
      if (!token.isSymbol("("))
        return token.type() == Type.WORD ? token.text().toLowerCase(Locale.ROOT) : "";
    return "";
  }

  /** Every name of the statement, with its schema where one is written before it. */
  static Set<String> names(final List<Token> tokens) {
    final Set<String> names = new LinkedHashSet<>();
    for (int i = 0; i < tokens.size(); i++) {
      final String name = tokens.get(i).name();
      if (name == null)
        continue;
      final String qualifier = i >= 2 && tokens.get(i - 1).isSymbol(".") ? tokens.get(i - 2).name() : null;
      names.add(qualifier == null ? name : qualifier + "." + name);
    }
    return names;
  }

  /** Whether the statement writes {@code schema.name}. */
  static boolean writesWithSchema(final List<Token> tokens, final String schema, final String name) {
    for (int i = 0; i + 2 < tokens.size(); i++)
      if (schema.equals(tokens.get(i).name()) && tokens.get(i + 1).isSymbol(".") && name.equals(tokens.get(i + 2).name()))
        return true;
    return false;
  }

  /** The name of the table that the statement creates with {@code ON COMMIT DELETE ROWS}, else {@code null}. */
  static String emptiedAtCommit(final List<Token> tokens) {
    if (!createsTable(tokens) || !normalized(tokens).contains(" on commit delete rows"))
      return null;
    int i = 1;
    while (i < tokens.size() && !tokens.get(i).is("table"))
      i++;
    i++;
    while (i < tokens.size() && (tokens.get(i).is("if") || tokens.get(i).is("not") || tokens.get(i).is("exists")))
      i++;
    if (i + 2 < tokens.size() && tokens.get(i + 1).isSymbol("."))
      i += 2;
    return i < tokens.size() ? tokens.get(i).name() : null;
  }

  private static final Set<String> QUERIES = Set.of("select", "with", "table", "values");

  /** Whether the statement is a query that names a view of {@code information_schema} with its schema. */
  static boolean queriesInformationSchema(final List<Token> tokens) {
    if (!QUERIES.contains(firstWord(tokens)))
      return false;
    for (int i = 0; i + 1 < tokens.size(); i++)
      if ("information_schema".equals(tokens.get(i).name()) && tokens.get(i + 1).isSymbol("."))
        return true;
    return false;
  }

  /**
   * The query with {@code rank()} in the place of every {@code row_number()} whose window orders its rows: both give the same
   * numbers unless rows tie in that order. {@code null} for any other statement.
   */
  static String rankForRowNumber(final String sql, final List<Token> tokens) {
    final String first = firstWord(tokens);
    if (!first.equals("select") && !first.equals("with"))
      return null;
    final StringBuilder ranked = new StringBuilder(sql);
    boolean found = false;
    // from the end, so that the offsets of the tokens before stay
    for (int i = tokens.size() - 5; i >= 0; i--)
      if (tokens.get(i).is("row_number") && tokens.get(i + 1).isSymbol("(") && tokens.get(i + 2).isSymbol(")")
          && tokens.get(i + 3).is("over") && tokens.get(i + 4).isSymbol("(") && holdsOrder(tokens, i + 4)) {
        ranked.replace(tokens.get(i).start(), tokens.get(i).end(), "rank");
        found = true;
      }
    return found ? ranked.toString() : null;
  }

  /** Whether the parentheses that open at {@code open} hold the word {@code ORDER}. */
  private static boolean holdsOrder(final List<Token> tokens, final int open) {
    for (int i = open + 1; i < tokens.size() && tokens.get(i).depth() > tokens.get(open).depth(); i++)
      if (tokens.get(i).is("order"))
        return true;
    return false;
  }

  private static final Set<String> BEFORE_A_TABLE = Set.of("from", "join", "into", "update", "only", "table");

  private static final Set<String> AFTER_A_FROM_LIST = Set.of("select", "where", "group", "having", "window", "order", "limit",
      "offset", "union", "intersect", "except", "set", "values", "returning");

  /**
   * The names that stand where a statement names a table: after {@code FROM}, {@code JOIN}, {@code INTO}, {@code UPDATE},
   * {@code ONLY} or {@code TABLE}, and after a comma of a {@code FROM} list. A name keeps its schema when it is written with one.
   */
  static Set<String> tablePositions(final List<Token> tokens) {
    final Set<String> names = new LinkedHashSet<>();
    final Set<Integer> inFromList = new HashSet<>();
    for (int i = 0; i < tokens.size(); i++) {
      final Token token = tokens.get(i);
      if (token.is("from"))
        inFromList.add(token.depth());
      else if (token.type() == Type.WORD && AFTER_A_FROM_LIST.contains(token.name()))
        inFromList.remove(token.depth());
      final boolean introduces = token.type() == Type.WORD && BEFORE_A_TABLE.contains(token.name())
          || token.isSymbol(",") && inFromList.contains(token.depth());
      if (!introduces || i + 1 >= tokens.size() || tokens.get(i + 1).name() == null)
        continue;
      if (i + 3 < tokens.size() && tokens.get(i + 2).isSymbol(".") && tokens.get(i + 3).name() != null)
        names.add(tokens.get(i + 1).name() + "." + tokens.get(i + 3).name());
      else
        names.add(tokens.get(i + 1).name());
    }
    return names;
  }

  /** Whether the statement is a {@code CREATE TABLE}, with {@code TEMP}, {@code UNLOGGED} and the like or without. */
  static boolean createsTable(final List<Token> tokens) {
    if (tokens.isEmpty() || !tokens.get(0).is("create"))
      return false;
    for (int i = 1; i < tokens.size() && i < 4; i++)
      if (tokens.get(i).is("table"))
        return true;
    return false;
  }

  /**
   * The tables a {@code CREATE TABLE} takes something from: those of {@link #tablePositions}, and the names after
   * {@code REFERENCES}, {@code LIKE} and {@code PARTITION OF}, and in the list of {@code INHERITS}. A column that has the name
   * of a table is not one of them.
   */
  static Set<String> sourcesOfCreateTable(final List<Token> tokens) {
    final Set<String> names = new LinkedHashSet<>(tablePositions(tokens));
    boolean inherits = false;
    for (int i = 0; i + 1 < tokens.size(); i++) {
      final Token token = tokens.get(i);
      if (token.is("inherits"))
        inherits = true;
      else if (inherits && token.isSymbol(")"))
        inherits = false;
      final boolean introduces = token.is("references") || token.is("like") || token.is("of")
          || inherits && (token.isSymbol("(") || token.isSymbol(","));
      if (!introduces || tokens.get(i + 1).name() == null)
        continue;
      if (i + 3 < tokens.size() && tokens.get(i + 2).isSymbol(".") && tokens.get(i + 3).name() != null)
        names.add(tokens.get(i + 1).name() + "." + tokens.get(i + 3).name());
      else
        names.add(tokens.get(i + 1).name());
    }
    return names;
  }

  /**
   * The names a statement writes to, read from its words: for the statements whose plan the server does not give. A name keeps
   * its schema when it is written with one.
   */
  static Set<String> writeTargets(final List<Token> tokens) {
    final Set<String> targets = new LinkedHashSet<>();
    final String first = firstWord(tokens);
    for (int i = 0; i < tokens.size(); i++) {
      final Token token = tokens.get(i);
      final boolean introduces = switch (first) {
        case "insert", "merge" -> token.is("into");
        case "update" -> token.is("update");
        case "delete" -> token.is("from") && i == 1;
        case "truncate" -> i == 0;
        case "copy" -> i == 0 && tokens.stream().anyMatch(each -> each.depth() == 0 && each.is("from"));
        default -> false;
      };
      if (!introduces)
        continue;
      int next = i + 1;
      while (next < tokens.size() && (tokens.get(next).is("only") || tokens.get(next).is("table")))
        next++;
      if (next >= tokens.size() || tokens.get(next).name() == null)
        continue;
      if (next + 2 < tokens.size() && tokens.get(next + 1).isSymbol(".") && tokens.get(next + 2).name() != null)
        targets.add(tokens.get(next).name() + "." + tokens.get(next + 2).name());
      else
        targets.add(tokens.get(next).name());
    }
    return targets;
  }
}
