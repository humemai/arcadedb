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
import com.arcadedb.query.postgresql.ast.ExplainStatement;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.postgresql.plan.SelectPlan;
import com.arcadedb.query.postgresql.plan.SelectPlanner;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code postgresql} query language: PostgreSQL-style SQL over a relational view of the schema, next to {@code sql} and
 * {@code opencypher} and with no effect on either. It reads only. A statement goes through three steps:
 * <ol>
 * <li>the parser ({@link StatementParser}): text that is not PostgreSQL syntax is refused with SQLSTATE {@code 42601},
 * PostgreSQL syntax outside the subset (every write, {@code WITH}, a subquery, a right join) with {@code 0A000};</li>
 * <li>the planner ({@link SelectPlanner}): names are bound through the {@link com.arcadedb.query.postgresql.catalog.RelationalCatalog}
 * ({@code 42703}, {@code 42P01}, {@code 42702}), every expression is typed by the evaluator with PostgreSQL's rules
 * ({@code 42883}, {@code 42804}, {@code 22P02}) and the access path of the table is chosen;</li>
 * <li>the execution of the plan ({@link SelectPlan#execute}): the answer is a {@link PostgreSQLResultSet}, whose columns carry
 * the PostgreSQL names and type names, and whose execution plan says how the table was read.</li>
 * </ol>
 * Parameters are {@code $1}, {@code $2} and so on: {@code db.query("postgresql", sql, a, b)} binds {@code $1} to {@code a}; a map
 * binds the keys {@code "1"} or {@code "$1"}. {@code EXPLAIN SELECT ...} answers the plan as rows of one column
 * {@code QUERY PLAN} and reads nothing.
 */
public class PostgreSQLQueryEngine implements QueryEngine {
  public static final String ENGINE_NAME = "postgresql";

  /** The name PostgreSQL gives the column of a select list item that has no name, as in {@code SELECT 1}. */
  static final String UNNAMED_COLUMN = "?column?";

  /** The column of an {@code EXPLAIN}, as PostgreSQL names it. */
  static final String PLAN_COLUMN = "QUERY PLAN";

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
    return execute(query, Parameters.of(parameters));
  }

  @Override
  public ResultSet query(final String query, final ContextConfiguration configuration, final Object... parameters) {
    return execute(query, Parameters.of(parameters));
  }

  /**
   * The language reads only, so a command is a query: {@code SELECT} runs, a write is refused.
   */
  @Override
  public ResultSet command(final String query, final ContextConfiguration configuration, final Map<String, Object> parameters) {
    return execute(query, Parameters.of(parameters));
  }

  @Override
  public ResultSet command(final String query, final ContextConfiguration configuration, final Object... parameters) {
    return execute(query, Parameters.of(parameters));
  }

  private ResultSet execute(final String query, final Parameters parameters) {
    final Statement statement = parse(query);
    final SelectPlanner planner = new SelectPlanner(database, parameters);
    if (statement instanceof ExplainStatement explain)
      return explain(planner.plan(explain.select()));
    return planner.plan((SelectStatement) statement).execute(database);
  }

  /** {@code EXPLAIN}: the plan, one line per row, and nothing is read. */
  private PostgreSQLResultSet explain(final SelectPlan plan) {
    final List<PostgreSQLResultSet.Column> columns = List.of(new PostgreSQLResultSet.Column(PLAN_COLUMN, "text"));
    final PostgreSQLResultSet result = new PostgreSQLResultSet(columns, new PostgreSQLResultSet.Plan(plan.steps()));
    for (final String line : plan.steps()) {
      final ResultInternal row = new ResultInternal(database);
      row.setProperty(PLAN_COLUMN, line);
      result.add(row);
    }
    return result;
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
}
