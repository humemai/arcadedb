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
import com.arcadedb.query.postgresql.ast.EmptyStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.sql.executor.InternalResultSet;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.CollectionUtils;

import java.math.BigInteger;
import java.util.Map;
import java.util.Set;

/**
 * The {@code postgresql} query language: PostgreSQL-style SQL over a relational view of the schema, next to {@code sql} and
 * {@code opencypher} and with no effect on either. It reads only. A statement is read by the parser of the language
 * ({@link StatementParser}): text that is not PostgreSQL syntax is refused with SQLSTATE {@code 42601}, PostgreSQL syntax
 * outside the subset (every write, {@code WITH}, a subquery, a right join) with {@code 0A000}, and a {@code SELECT} of the
 * subset is a read. Running it comes with the binder and the evaluator of the later steps: until then the engine answers the
 * one statement {@code SELECT 1}.
 */
public class PostgreSQLQueryEngine implements QueryEngine {
  public static final String ENGINE_NAME = "postgresql";

  /** The name PostgreSQL gives the column of a select list item that has no name, as in {@code SELECT 1}. */
  static final String UNNAMED_COLUMN = "?column?";

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
   * A {@code SELECT} is a read. Everything else is refused: text that is not PostgreSQL syntax with SQLSTATE {@code 42601},
   * and a statement outside the subset, which includes the empty statement and every write, with SQLSTATE {@code 0A000} and
   * its position.
   */
  @Override
  public AnalyzedQuery analyze(final String query) {
    parse(query);
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
    final Statement statement = parse(query);

    // REMOVE WHEN S4 lands: the binder and the evaluator do not exist yet, and this answers exactly the one statement of the
    // first test, now read by the parser instead of matched as text
    if (!(statement instanceof SelectStatement select) || !isSelectOne(select))
      throw new PostgreSQLFeatureNotSupportedException(
          "This statement is not supported by the postgresql language yet, only SELECT 1 is (" + statement.position() + ")");

    final ResultInternal row = new ResultInternal(database);
    row.setProperty(UNNAMED_COLUMN, 1);
    return new InternalResultSet(row);
  }

  /**
   * @return the syntax tree of a {@code SELECT} or of an {@code EXPLAIN} of one
   *
   * @throws PostgreSQLSyntaxException               when the text is not PostgreSQL syntax
   * @throws PostgreSQLFeatureNotSupportedException when the text is outside the subset, or is the empty statement
   */
  private static Statement parse(final String query) {
    final Statement statement = StatementParser.parse(query);
    if (statement instanceof EmptyStatement)
      throw new PostgreSQLFeatureNotSupportedException(
          "An empty statement is not supported by the postgresql language (" + statement.position() + ")");
    return statement;
  }

  // REMOVE WHEN S4 lands: SELECT 1, and nothing else
  private static boolean isSelectOne(final SelectStatement select) {
    if (select.distinct() || !select.from().isEmpty() || select.where() != null || !select.groupBy().isEmpty()
        || select.having() != null || !select.orderBy().isEmpty() || select.limit() != null || select.offset() != null
        || select.items().size() != 1)
      return false;
    return select.items().get(0) instanceof SelectItem.Projection projection && projection.alias() == null
        && projection.expression() instanceof Expression.IntegerLiteral literal && BigInteger.ONE.equals(literal.value());
  }
}
