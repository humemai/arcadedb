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
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.OperationType;
import com.arcadedb.query.QueryEngine;
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.ast.DeleteStatement;
import com.arcadedb.query.postgresql.ast.EmptyStatement;
import com.arcadedb.query.postgresql.ast.ExplainStatement;
import com.arcadedb.query.postgresql.ast.ExplainWriteStatement;
import com.arcadedb.query.postgresql.ast.InsertStatement;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TransactionStatement;
import com.arcadedb.query.postgresql.ast.UpdateStatement;
import com.arcadedb.query.postgresql.ast.WriteStatement;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.postgresql.plan.QueryPlan;
import com.arcadedb.query.postgresql.plan.SelectPlanner;
import com.arcadedb.query.postgresql.plan.WriteErrors;
import com.arcadedb.query.postgresql.plan.WritePlanner;
import com.arcadedb.query.postgresql.plan.join.JoinWarnings;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.utility.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code postgresql} query language: PostgreSQL-style SQL over a relational view of the schema, next to {@code sql} and
 * {@code opencypher} and with no effect on either. It reads, and its parser reads the writes ({@code INSERT}, {@code UPDATE},
 * {@code DELETE}) and the transaction statements too. {@code INSERT}, {@code UPDATE} and {@code DELETE} run on document, vertex and
 * edge tables ({@link WritePlanner}, fork issue #29), and {@code BEGIN}, {@code COMMIT} and {@code ROLLBACK} are the transaction of
 * the database. A statement goes through three steps:
 * <ol>
 * <li>the parser ({@link StatementParser}): text that is not PostgreSQL syntax is refused with SQLSTATE {@code 42601},
 * PostgreSQL syntax outside the subset ({@code WITH}, a subquery, a right join, DDL) with {@code 0A000};</li>
 * <li>the planner ({@link SelectPlanner}): names are bound through the {@link com.arcadedb.query.postgresql.catalog.RelationalCatalog}
 * ({@code 42703}, {@code 42P01}, {@code 42702}), every expression is typed by the evaluator with PostgreSQL's rules
 * ({@code 42883}, {@code 42804}, {@code 22P02}) and the access path of the table is chosen;</li>
 * <li>the execution of the plan ({@link QueryPlan#execute}): the answer is a {@link PostgreSQLResultSet}, whose columns carry
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

  /** The analysis of a {@code SELECT} and of an {@code EXPLAIN}: a read, never schema, never a write. */
  private static final AnalyzedQuery READ = new Analysis(true, false, OperationType.READ);

  private static final AnalyzedQuery INSERT = new Analysis(false, false, OperationType.CREATE);

  private static final AnalyzedQuery UPDATE = new Analysis(false, false, OperationType.UPDATE);

  private static final AnalyzedQuery DELETE = new Analysis(false, false, OperationType.DELETE);

  /** {@code BEGIN}, {@code COMMIT} and {@code ROLLBACK} read and write nothing themselves: a request about the transaction. */
  private static final AnalyzedQuery TRANSACTION = new Analysis(false, true, OperationType.READ);

  private record Analysis(boolean idempotent, boolean transactionControl, OperationType operation) implements AnalyzedQuery {
    @Override
    public boolean isIdempotent() {
      return idempotent;
    }

    @Override
    public boolean isDDL() {
      return false;
    }

    @Override
    public boolean isTransactionControl() {
      return transactionControl;
    }

    @Override
    public Set<OperationType> getOperationTypes() {
      return CollectionUtils.singletonSet(operation);
    }
  }

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
  private final StatementCache   statements;

  protected PostgreSQLQueryEngine(final DatabaseInternal database) {
    this.database = database;
    this.statements = new StatementCache(database.getConfiguration().getValueAsInteger(GlobalConfiguration.POSTGRESQL_STATEMENT_CACHE));
  }

  /** The parsed statements this engine keeps (fork issue #76), for the tests. */
  StatementCache statements() {
    return statements;
  }

  @Override
  public String getLanguage() {
    return ENGINE_NAME;
  }

  /**
   * A {@code SELECT} and an {@code EXPLAIN} are reads, {@code INSERT}, {@code UPDATE} and {@code DELETE} the writes of their
   * name, {@code BEGIN}, {@code COMMIT} and {@code ROLLBACK} transaction control. Everything else is refused: text that is not
   * PostgreSQL syntax with SQLSTATE {@code 42601}, and a statement outside the subset, which includes the empty statement, with
   * SQLSTATE {@code 0A000} and its position.
   */
  @Override
  public AnalyzedQuery analyze(final String query) {
    return switch (parse(query)) {
      case InsertStatement ignored -> INSERT;
      case UpdateStatement ignored -> UPDATE;
      case DeleteStatement ignored -> DELETE;
      case TransactionStatement ignored -> TRANSACTION;
      default -> READ;
    };
  }

  @Override
  public ResultSet query(final String query, final ContextConfiguration configuration, final Map<String, Object> parameters) {
    return execute(query, Parameters.of(parameters), true);
  }

  @Override
  public ResultSet query(final String query, final ContextConfiguration configuration, final Object... parameters) {
    return execute(query, Parameters.of(parameters), true);
  }

  /**
   * A command runs every statement of the language. A write and a transaction statement are not idempotent, so
   * {@code Database.query} refuses them as it does for {@code sql}: they run through {@code command}.
   */
  @Override
  public ResultSet command(final String query, final ContextConfiguration configuration, final Map<String, Object> parameters) {
    return execute(query, Parameters.of(parameters), false);
  }

  @Override
  public ResultSet command(final String query, final ContextConfiguration configuration, final Object... parameters) {
    return execute(query, Parameters.of(parameters), false);
  }

  /**
   * The transaction block of one thread of this database. {@code BEGIN} opens it, and an error inside it fails it: every statement
   * after that is {@code 25P02} until {@code ROLLBACK} or {@code COMMIT}, and {@code COMMIT} of a failed block is a rollback, as in
   * PostgreSQL. A block exists only when this language opened the transaction: a transaction that the caller opened with
   * {@code Database.begin()} is the caller's, and a failed statement in it is taken back by the database and leaves it usable.
   */
  private static final class Block {
    private boolean open;
    private boolean failed;
  }

  private final ThreadLocal<Block> block = ThreadLocal.withInitial(Block::new);

  /** The engine keeps no state but the block of each thread, so one instance serves the database. */
  @Override
  public boolean isReusable() {
    return true;
  }

  /**
   * @param readOnly true for {@code query}, the read-only entry of the database as it is for {@code sql}: a write or a transaction
   *                 statement is refused there with SQLSTATE {@code 25006} (read_only_sql_transaction) and runs through
   *                 {@code command}, which is what {@link #analyze} says by reporting it as not idempotent
   */
  private ResultSet execute(final String query, final Parameters parameters, final boolean readOnly) {
    final Block state = block.get();
    if (state.open && !database.isTransactionActive()) {
      // the transaction ended in another way: the caller committed it, or the database closed it
      state.open = false;
      state.failed = false;
    }
    try {
      final Statement statement = parse(query);
      if (state.failed) {
        if (statement instanceof TransactionStatement transaction && transaction.kind() != TransactionStatement.Kind.BEGIN)
          return transaction(TransactionStatement.Kind.ROLLBACK, state);
        throw new PostgreSQLEvaluationException("25P02",
            "current transaction is aborted, commands ignored until end of transaction block");
      }
      if (readOnly && (statement instanceof WriteStatement || statement instanceof TransactionStatement))
        throw new PostgreSQLEvaluationException("25006", "cannot execute " + statementName(statement)
            + " in a read-only query: run it with command (" + statement.position() + ")");
      if (statement instanceof TransactionStatement transaction)
        return transaction(transaction.kind(), state);
      if (statement instanceof ExplainWriteStatement explain)
        return explain(new WritePlanner(database, parameters).plan(explain.statement()).steps());
      if (statement instanceof WriteStatement write)
        return new WritePlanner(database, parameters).plan(write).execute(database);
      final SelectPlanner planner = new SelectPlanner(database, parameters);
      if (statement instanceof ExplainStatement explain) {
        final QueryPlan explained = planner.planQuery(explain.select());
        return explain(explained.steps(), explained.warnings());
      }
      final QueryPlan plan = planner.planQuery(statement);
      // a join that no edge, link or index supports: one warning in the log for each distinct statement (fork issue #9)
      if (!plan.warnings().isEmpty() && database.getConfiguration().getValueAsBoolean(GlobalConfiguration.POSTGRESQL_JOIN_WARNING))
        JoinWarnings.shared().warn(database.getName(), AstPrinter.fingerprint(statement), plan.warnings());
      return plan.execute(database);
    } catch (final RuntimeException e) {
      if (state.open && !state.failed && database.isTransactionActive())
        state.failed = true;
      throw e;
    }
  }

  /**
   * {@code BEGIN} opens the transaction of the database, {@code COMMIT} commits it and {@code ROLLBACK} rolls it back; the
   * answer has no column and no row. As in PostgreSQL, a {@code BEGIN} inside a transaction, and a {@code COMMIT} or a
   * {@code ROLLBACK} with none, change nothing and are no error. A commit that the engine refuses because another transaction
   * changed a record first is SQLSTATE {@code 40001} (serialization_failure), which a client answers by running the block
   * again; the transaction is rolled back and the block is over.
   */
  private ResultSet transaction(final TransactionStatement.Kind kind, final Block state) {
    switch (kind) {
    case BEGIN -> {
      if (!database.isTransactionActive()) {
        database.begin();
        state.open = true;
      }
      state.failed = false;
    }
    case COMMIT -> {
      state.open = false;
      state.failed = false;
      if (database.isTransactionActive()) {
        try {
          database.commit();
        } catch (final RuntimeException e) {
          if (database.isTransactionActive()) {
            try {
              database.rollback();
            } catch (final RuntimeException suppressed) {
              e.addSuppressed(suppressed);
            }
          }
          throw WriteErrors.translate(e);
        }
      }
    }
    case ROLLBACK -> {
      state.open = false;
      state.failed = false;
      if (database.isTransactionActive())
        database.rollback();
    }
    }
    return new PostgreSQLResultSet(List.of(), new PostgreSQLResultSet.Plan(List.of(kind.name()))).withAffectedRows(0);
  }

  private static String statementName(final Statement statement) {
    return switch (statement) {
      case InsertStatement ignored -> "INSERT";
      case UpdateStatement ignored -> "UPDATE";
      case DeleteStatement ignored -> "DELETE";
      case ExplainWriteStatement ignored -> "EXPLAIN of a write";
      case TransactionStatement transaction -> transaction.kind().name();
      default -> throw new IllegalStateException("Not a write: " + statement);
    };
  }

  private PostgreSQLResultSet explain(final List<String> steps) {
    return explain(steps, List.of());
  }

  /** {@code EXPLAIN}: the plan, one line per row, and nothing is read or written. */
  private PostgreSQLResultSet explain(final List<String> steps, final List<String> warnings) {
    final List<PostgreSQLResultSet.Column> columns = List.of(new PostgreSQLResultSet.Column(PLAN_COLUMN, "text"));
    final PostgreSQLResultSet result = new PostgreSQLResultSet(columns, new PostgreSQLResultSet.Plan(steps), warnings);
    for (final String line : steps) {
      final ResultInternal row = new ResultInternal(database);
      row.setProperty(PLAN_COLUMN, line);
      result.add(row);
    }
    return result;
  }

  /**
   * The columns of the answer of {@code query}, found by planning it: nothing is read and nothing is written. It is what a client
   * asks with a Describe message before it binds values, a JDBC {@code PreparedStatement.getMetaData()} for one. Every name is
   * bound and every expression typed, so a statement with an unknown table or column, or a type error, is refused here with the
   * SQLSTATE that it would be refused with when it runs.
   *
   * @param parameterTypes the types of {@code $1}, {@code $2} and so on; a parameter with no type here, or {@link PgType#UNKNOWN},
   *                       takes its type from the expression it is in, as it does in PostgreSQL
   *
   * @return the columns in order, empty for a statement that returns no rows: {@code BEGIN}, {@code COMMIT} and {@code ROLLBACK}, and
   *     a write without {@code RETURNING}
   */
  public List<PostgreSQLResultSet.Column> describe(final String query, final PgType... parameterTypes) {
    final Statement statement = parse(query);
    final Parameters parameters = Parameters.describing(parameterTypes);
    if (statement instanceof TransactionStatement)
      return List.of();
    if (statement instanceof ExplainStatement || statement instanceof ExplainWriteStatement)
      return List.of(new PostgreSQLResultSet.Column(PLAN_COLUMN, "text"));
    if (statement instanceof WriteStatement write)
      return new WritePlanner(database, parameters).plan(write).columns();
    return new SelectPlanner(database, parameters).planQuery(statement).columns();
  }

  /**
   * @return the syntax tree of a statement of the language, the one kept from an earlier call with the same text when there is one
   *
   * @throws PostgreSQLSyntaxException               when the text is not PostgreSQL syntax
   * @throws PostgreSQLFeatureNotSupportedException when the text is outside the subset, or is the empty statement
   */
  private Statement parse(final String query) {
    final Statement cached = statements.get(query);
    if (cached != null)
      return cached;
    final Statement statement = StatementParser.parse(query);
    if (statement instanceof EmptyStatement)
      throw new PostgreSQLFeatureNotSupportedException(
          "An empty statement is not supported by the postgresql language (" + statement.position() + ")");
    statements.put(query, statement);
    return statement;
  }
}
