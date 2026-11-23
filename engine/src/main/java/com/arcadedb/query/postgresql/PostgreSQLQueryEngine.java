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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.OperationType;
import com.arcadedb.query.QueryEngine;
import com.arcadedb.query.sql.executor.InternalResultSet;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.CollectionUtils;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code postgresql} query language: PostgreSQL-style SQL over a relational view of the schema, next to {@code sql} and
 * {@code opencypher} and with no effect on either. It reads only. This is the skeleton the prototype builds on: it is
 * registered, it classifies a statement, it refuses what it does not support with SQLSTATE {@code 0A000}, and it answers the one
 * statement {@code SELECT 1} until the grammar exists.
 */
public class PostgreSQLQueryEngine implements QueryEngine {
  public static final String ENGINE_NAME = "postgresql";

  /** The name PostgreSQL gives the column of a select list item that has no name, as in {@code SELECT 1}. */
  static final String UNNAMED_COLUMN = "?column?";

  private static final String SELECT = "SELECT";

  // REMOVE WHEN S1 lands: the grammar does not exist yet, and this recognises exactly the one statement of the first test
  private static final Pattern SELECT_ONE = Pattern.compile("select\\s+1", Pattern.CASE_INSENSITIVE);

  /** The analysis of every statement the language accepts: a read, never schema, never a write. */
  private static final AnalyzedQuery READ = new AnalyzedQuery() {
    @Override
    public boolean isIdempotent() {
      return true;
    }

    @Override
    public boolean isDDL() {
      return false;
    }

    @Override
    public Set<OperationType> getOperationTypes() {
      return CollectionUtils.singletonSet(OperationType.READ);
    }
  };

  public static class PostgreSQLQueryEngineFactory implements QueryEngineFactory {
    @Override
    public String getLanguage() {
      return ENGINE_NAME;
    }

    @Override
    public QueryEngine getInstance(final DatabaseInternal database) {
      return new PostgreSQLQueryEngine(database);
    }
  }

  private final DatabaseInternal database;

  protected PostgreSQLQueryEngine(final DatabaseInternal database) {
    this.database = database;
  }

  @Override
  public String getLanguage() {
    return ENGINE_NAME;
  }

  /**
   * A {@code SELECT} is a read. Everything else is refused with SQLSTATE {@code 0A000}: the language does not write, and a
   * statement outside the subset says so with its position instead of a generic syntax error.
   */
  @Override
  public AnalyzedQuery analyze(final String query) {
    requireSelect(query);
    return READ;
  }

  @Override
  public ResultSet query(final String query, final ContextConfiguration configuration, final Map<String, Object> parameters) {
    return execute(query);
  }

  @Override
  public ResultSet query(final String query, final ContextConfiguration configuration, final Object... parameters) {
    return execute(query);
  }

  /**
   * The language reads only, so a command is a query: {@code SELECT} runs, a write is refused.
   */
  @Override
  public ResultSet command(final String query, final ContextConfiguration configuration, final Map<String, Object> parameters) {
    return execute(query);
  }

  @Override
  public ResultSet command(final String query, final ContextConfiguration configuration, final Object... parameters) {
    return execute(query);
  }

  private ResultSet execute(final String query) {
    final int start = requireSelect(query);

    // REMOVE WHEN S1 lands
    if (!isSelectOne(query))
      throw new PostgreSQLFeatureNotSupportedException(
          "This SELECT is not supported by the postgresql language yet, only SELECT 1 is (" + position(query, start) + ")");

    final ResultInternal row = new ResultInternal(database);
    row.setProperty(UNNAMED_COLUMN, 1);
    return new InternalResultSet(row);
  }

  /**
   * @return the offset of the first word of {@code query}, which is {@code SELECT}
   *
   * @throws PostgreSQLFeatureNotSupportedException when the statement is empty or does not start with {@code SELECT}
   */
  private static int requireSelect(final String query) {
    final int start = firstTokenOffset(query);
    if (start >= query.length())
      throw new PostgreSQLFeatureNotSupportedException(
          "An empty statement is not supported by the postgresql language (" + position(query, start) + ")");

    int end = start;
    while (end < query.length() && isWordChar(query.charAt(end)))
      ++end;
    if (end == start)
      // not a word: name the character
      end = start + 1;

    final String token = query.substring(start, end);
    if (!SELECT.equalsIgnoreCase(token))
      throw new PostgreSQLFeatureNotSupportedException(
          token + " is not supported by the postgresql language (" + position(query, start)
              + "): the language reads only, use the sql language for it (the {sql} marker on the wire)");
    return start;
  }

  // REMOVE WHEN S1 lands: the tokenizer of the grammar knows whitespace and comments, this knows whitespace
  private static int firstTokenOffset(final String query) {
    int offset = 0;
    while (offset < query.length() && Character.isWhitespace(query.charAt(offset)))
      ++offset;
    return offset;
  }

  private static boolean isWordChar(final char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  // REMOVE WHEN S1 lands
  private static boolean isSelectOne(final String query) {
    String text = query.strip();
    if (text.endsWith(";"))
      text = text.substring(0, text.length() - 1).stripTrailing();
    return SELECT_ONE.matcher(text).matches();
  }

  /**
   * Line and column, both counted from 1, of {@code offset} in {@code text}, in the words of every message that points at one.
   */
  private static String position(final String text, final int offset) {
    int line = 1;
    int column = 1;
    for (int i = 0; i < offset && i < text.length(); i++) {
      if (text.charAt(i) == '\n') {
        ++line;
        column = 1;
      } else
        ++column;
    }
    return "line " + line + ", column " + column;
  }
}
