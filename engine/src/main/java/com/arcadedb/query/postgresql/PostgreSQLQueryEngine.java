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
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.TransactionContext;
import com.arcadedb.exception.CauseChain;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.exception.LockTimeoutException;
import com.arcadedb.exception.WALVersionGapException;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.OperationType;
import com.arcadedb.query.QueryEngine;
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.ast.AlterTableStatement;
import com.arcadedb.query.postgresql.ast.CreateExtensionStatement;
import com.arcadedb.query.postgresql.ast.CreateIndexStatement;
import com.arcadedb.query.postgresql.ast.CreateTableStatement;
import com.arcadedb.query.postgresql.ast.DeleteStatement;
import com.arcadedb.query.postgresql.ast.DropStatement;
import com.arcadedb.query.postgresql.ast.EmptyStatement;
import com.arcadedb.query.postgresql.ast.ExplainStatement;
import com.arcadedb.query.postgresql.ast.ExplainWriteStatement;
import com.arcadedb.query.postgresql.ast.InsertStatement;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TransactionStatement;
import com.arcadedb.query.postgresql.ast.UpdateStatement;
import com.arcadedb.query.postgresql.ast.WriteStatement;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Sequences;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.eval.TransactionClock;
import com.arcadedb.query.postgresql.parser.StatementHints;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.CreateIndexPlan;
import com.arcadedb.query.postgresql.plan.AlterTablePlan;
import com.arcadedb.query.postgresql.plan.CreateTablePlan;
import com.arcadedb.query.postgresql.plan.DropPlan;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.postgresql.plan.QueryPlan;
import com.arcadedb.query.postgresql.plan.SelectPlanner;
import com.arcadedb.query.postgresql.plan.WriteErrors;
import com.arcadedb.query.postgresql.plan.WritePlanner;
import com.arcadedb.query.postgresql.plan.join.JoinWarnings;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.utility.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * The {@code postgresql} query language: PostgreSQL-style SQL over a relational view of the schema, next to {@code sql} and
 * {@code opencypher} and with no effect on either. It reads, and its parser reads the writes ({@code INSERT}, {@code UPDATE},
 * {@code DELETE}) and the transaction statements too. {@code INSERT}, {@code UPDATE} and {@code DELETE} run on document, vertex and
 * edge tables ({@link WritePlanner}, fork issue #29), {@code BEGIN}, {@code COMMIT} and {@code ROLLBACK} are the transaction of
 * the database, {@code CREATE TABLE} makes a vertex or an edge type ({@link CreateTablePlan}, fork issue #118), and
 * {@code CREATE INDEX} a tree or a vector index ({@link CreateIndexPlan}, fork issue #125). A statement goes through three steps:
 * <ol>
 * <li>the parser ({@link StatementParser}): text that is not PostgreSQL syntax is refused with SQLSTATE {@code 42601},
 * PostgreSQL syntax outside the subset ({@code WITH RECURSIVE}, a {@code NATURAL} join, other DDL) with {@code 0A000};</li>
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

  /**
   * {@code CREATE TABLE} and {@code CREATE INDEX} change the schema: the servers run them on the leader and ask for the permission
   * to change it.
   */
  private static final AnalyzedQuery SCHEMA_CHANGE = new Analysis(false, false, OperationType.SCHEMA);

  private record Analysis(boolean idempotent, boolean transactionControl, OperationType operation) implements AnalyzedQuery {
    @Override
    public boolean isIdempotent() {
      return idempotent;
    }

    @Override
    public boolean isDDL() {
      return operation == OperationType.SCHEMA;
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

  /** The catalog as it was built, and the number of schema changes of the database that it was built after. */
  private record CatalogSnapshot(long schemaChanges, RelationalCatalog catalog) {
  }

  private volatile CatalogSnapshot catalogSnapshot;

  /**
   * The relational catalog of the schema as it is now (fork issue #88). It is a function of the schema alone, so it is kept and
   * given back until the schema changes. Every schema change of the database (a type, a property, an index, a custom value of a type,
   * from any thread, or applied from another server) ends by emptying the plan cache of {@code sql}, which counts the times it is
   * emptied; the count is read before the catalog is built, so a change that happens meanwhile leaves a catalog that is never given
   * back. The catalog of an older count that a slow thread stores late is replaced at the next call, which sees a count that differs.
   */
  RelationalCatalog catalog() {
    final long schemaChanges = database.getExecutionPlanCache().getInvalidationEpoch();
    final CatalogSnapshot kept = catalogSnapshot;
    if (kept != null && kept.schemaChanges() == schemaChanges)
      return kept.catalog();
    final RelationalCatalog built = RelationalCatalog.of(database);
    catalogSnapshot = new CatalogSnapshot(schemaChanges, built);
    return built;
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
      case CreateTableStatement ignored -> SCHEMA_CHANGE;
      case CreateIndexStatement ignored -> SCHEMA_CHANGE;
      case AlterTableStatement ignored -> SCHEMA_CHANGE;
      case DropStatement ignored -> SCHEMA_CHANGE;
      default -> READ;
    };
  }

  /**
   * A schema change that a replica would send to the leader out of a transaction block is refused (fork issue #316): there it
   * runs and commits at once, so a {@code ROLLBACK} would take nothing back, and a {@code DROP} would take the rows with it.
   */
  @Override
  public void checkSchemaChangeOutsideClientTransaction(final String query) {
    final Statement statement = parse(query);
    throw new PostgreSQLFeatureNotSupportedException(statementName(statement) + " inside a transaction block is not supported by"
        + " the postgresql language on a replica of a high availability cluster, because it would run on the leader at once,"
        + " outside the block (" + statement.position() + ")");
  }

  /** The key under which a server says that more statements share the transaction of the one it runs next. */
  private static final String FOLLOWED = "postgresql:followed";

  /**
   * For a server that runs several statements in a transaction that it opened, as the PostgreSQL protocol does for the messages
   * before one {@code Sync} (fork issue #335): says, before a schema change runs, whether more statements follow it in the
   * current transaction. The failure of one of them rolls the transaction back, which would not bring back what a {@code DROP}
   * dropped, so a {@code DROP} that more statements follow is refused before it drops anything.
   */
  public static void statementsFollow(final Database database, final boolean follow) {
    ((DatabaseInternal) database).getTransaction().setAttachment(FOLLOWED, follow);
  }

  /**
   * A schema change that more statements follow is refused on a replica (fork issue #335): the leader would run it as a
   * transaction of its own, which the failure of a later statement would not roll back.
   */
  public void checkSchemaChangeThatStatementsFollowOnReplica(final String query) {
    final Statement statement = parse(query);
    throw new PostgreSQLFeatureNotSupportedException(statementName(statement) + " followed by other statements of the same batch"
        + " or pipeline is not supported by the postgresql language on a replica of a high availability cluster, because it would"
        + " run on the leader at once (" + statement.position() + ")",
        "A failure of a later statement would not take it back: send the statement on its own, in autocommit, or connect to the"
            + " leader");
  }

  /**
   * A schema change that follows writes of its run is refused on a replica (fork issue #338): the writes wait there for the end
   * of the run, and the leader would commit the schema change before them.
   */
  public void checkSchemaChangeAfterWritesOnReplica(final String query) {
    final Statement statement = parse(query);
    throw new PostgreSQLFeatureNotSupportedException(statementName(statement) + " after other writes of the same batch or"
        + " pipeline is not supported by the postgresql language on a replica of a high availability cluster, because it would run"
        + " on the leader at once, before those writes commit (" + statement.position() + ")",
        "Send the statement on its own, in autocommit, or connect to the leader");
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

  /**
   * What a transaction owes the schema (fork issues #229 and #230). The schema of the engine is not transactional: what a
   * statement changed is there at once, so it is taken back when the transaction does not commit, and finished when it does.
   */
  private static final class Owed {
    /** how to take each change back, in the order the changes were made */
    private final List<Runnable> back      = new ArrayList<>();
    /** what is left to do for the changes once the transaction has committed */
    private final List<Runnable> finish    = new ArrayList<>();
    /** the tables that {@code CREATE TABLE} made in the transaction */
    private final Set<DocumentType> created = Collections.newSetFromMap(new IdentityHashMap<>());
  }

  /** The key under which a transaction holds what it owes. */
  private static final String OWED = "postgresql:schema";

  /** What is owed by transactions that have ended, until the next statement outside a transaction. */
  private final ConcurrentLinkedDeque<Runnable> ended = new ConcurrentLinkedDeque<>();

  /** The engine keeps the block of each thread and what ended transactions owe, so one instance serves the database. */
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
    // a transaction that the caller ended itself left its schema changes to this statement (fork issues #229 and #230)
    settle();
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
      if (readOnly && (statement instanceof WriteStatement || statement instanceof TransactionStatement
          || statement instanceof CreateTableStatement || statement instanceof CreateIndexStatement
          || statement instanceof AlterTableStatement || statement instanceof DropStatement))
        throw new PostgreSQLEvaluationException("25006", "cannot execute " + statementName(statement)
            + " in a read-only query: run it with command (" + statement.position() + ")");
      if (statement instanceof TransactionStatement transaction)
        return transaction(transaction.kind(), state);
      // what the extension would add is built in: nothing is read or changed, in a transaction block too (fork issue #231)
      if (statement instanceof CreateExtensionStatement extension)
        return new PostgreSQLResultSet(List.of(), new PostgreSQLResultSet.Plan(List.of("CREATE EXTENSION "
            + extension.name().value() + ": built in, nothing to do"))).rowless().withAffectedRows(0);
      if (statement instanceof CreateTableStatement create) {
        final CreateTablePlan plan = new CreateTablePlan(database, catalog(), create);
        final PostgreSQLResultSet made = plan.execute();
        if (plan.created() != null && database.isTransactionActive())
          dropIfRolledBack(plan.created());
        return made;
      }
      if (statement instanceof CreateIndexStatement create) {
        final CreateIndexPlan plan = new CreateIndexPlan(database, catalog(), create);
        final PostgreSQLResultSet made = plan.execute(table -> requireTableOfBlock(state, table, create));
        // a transaction that a server opened around the statement can still be ended by a later one of the same run (fork issue
        // #293): the index of a table that was there before is taken back then, and that of a table it made goes with the table
        if (plan.changed() != null && database.isTransactionActive() && !createdByTransaction(plan.changed()))
          owed().back.add(plan::takeBack);
        return made;
      }
      if (statement instanceof AlterTableStatement alter) {
        final AlterTablePlan plan = new AlterTablePlan(database, catalog(), alter, parameters.at(TransactionClock.now(database)));
        final PostgreSQLResultSet changed = plan.execute();
        if (database.isTransactionActive()) {
          // the rows are of the transaction; the schema is taken back by a rollback, and finished by a commit (fork issue #230)
          final Owed owed = owed();
          owed.back.add(plan::takeBack);
          owed.finish.add(plan::finish);
        } else
          plan.finish();
        return changed;
      }
      if (statement instanceof DropStatement drop) {
        // the schema is not transactional, and a rollback could not bring dropped rows back (fork issue #230); a transaction
        // that a server opened around one request is not a block, unless it has changes of its own
        if (database.isTransactionActive() && (state.open || !database.getTransaction().isImplicit()
            || database.getTransaction().hasChanges()))
          throw new PostgreSQLFeatureNotSupportedException("DROP " + drop.kind() + " inside a transaction block is not supported"
              + " by the postgresql language, because a rollback would not bring back what it drops (" + drop.position() + ")");
        // nor when the server says that statements follow it, whose failure rolls that transaction back (fork issue #335)
        final boolean followed = database.isTransactionActive()
            && Boolean.TRUE.equals(database.getTransaction().getAttachment(FOLLOWED));
        return new DropPlan(database, catalog(), drop, followed).execute();
      }
      // the time of the transaction, which every clock function and default of the statement answers (fork issue #222)
      final Parameters timed = parameters.at(TransactionClock.now(database));
      if (statement instanceof ExplainWriteStatement explain)
        return explain(new WritePlanner(database, catalog(), timed).plan(explain.statement()).steps());
      if (statement instanceof WriteStatement write)
        return inSessionZone(new WritePlanner(database, catalog(), timed).plan(write).execute(database));
      final SelectPlanner planner = new SelectPlanner(database, catalog(), timed);
      if (statement instanceof ExplainStatement explain) {
        final QueryPlan explained = planner.planQuery(explain.select());
        return explain(explained.steps(), explained.warnings());
      }
      final QueryPlan plan = planner.planQuery(statement);
      // a join that no edge, link or index supports: one warning in the log for each distinct statement (fork issue #9)
      if (!plan.warnings().isEmpty() && database.getConfiguration().getValueAsBoolean(GlobalConfiguration.POSTGRESQL_JOIN_WARNING))
        JoinWarnings.shared().warn(database.getName(), AstPrinter.fingerprint(statement), plan.warnings());
      return inSessionZone(plan.execute(database));
    } catch (final RuntimeException e) {
      if (state.open && !state.failed && database.isTransactionActive())
        state.failed = true;
      throw e;
    }
  }

  /**
   * The answer of a statement with every {@code timestamptz} at the offset of the session's time zone, which is how a server
   * writes it (fork issue #270). The rows are changed here, at the end, so that inside a statement an instant has one form.
   */
  private static PostgreSQLResultSet inSessionZone(final PostgreSQLResultSet answer) {
    final List<String> keys = PostgreSQLResultSet.uniqueKeys(answer.columns());
    final List<String> instants = new ArrayList<>();
    for (int i = 0; i < keys.size(); i++)
      if (answer.columns().get(i).typeName().contains("timestamptz"))
        instants.add(keys.get(i));
    if (instants.isEmpty())
      return answer;
    while (answer.hasNext()) {
      final ResultInternal row = (ResultInternal) answer.next();
      for (final String key : instants)
        row.setProperty(key, SessionTimeZone.shownValue(row.getProperty(key)));
    }
    answer.reset();
    return answer;
  }

  /**
   * What the current transaction owes the schema, made at its first change. The transaction tells how it ended while the database
   * is locked for that, where no schema change can be made, so what is owed is only noted there and done by {@link #settle}. A
   * conclusion that is neither a commit nor a rollback, and a transaction of a thread that died, leave the changes in place.
   */
  private Owed owed() {
    final TransactionContext transaction = database.getTransaction();
    if (transaction.getAttachment(OWED) instanceof Owed there)
      return there;
    final Owed owed = new Owed();
    final AtomicBoolean committed = new AtomicBoolean();
    transaction.addAfterCommitCallback(() -> committed.set(true));
    transaction.addAfterCompletionCallbackIfAbsent(OWED, () -> ended.add(() -> {
      if (committed.get())
        owed.finish.forEach(this::attempt);
      else
        // the last change first
        for (int i = owed.back.size() - 1; i >= 0; i--)
          attempt(owed.back.get(i));
    }));
    transaction.setAttachment(OWED, owed);
    return owed;
  }

  private void attempt(final Runnable change) {
    try {
      change.run();
    } catch (final RuntimeException e) {
      LogManager.instance().log(this, Level.WARNING, "Cannot complete a schema change of a transaction that ended", e);
    }
  }

  /**
   * A table that {@code CREATE TABLE} made in a transaction is there, and other sessions see it, as soon as it has run. When that
   * transaction does not commit, the table is dropped if it holds no record (fork issue #229).
   */
  private void dropIfRolledBack(final DocumentType table) {
    final Owed owed = owed();
    owed.created.add(table);
    owed.back.add(() -> dropIfEmpty(table));
  }

  /**
   * For a server that rolls a transaction back itself, as the PostgreSQL protocol does for {@code ROLLBACK}, for an error and for
   * a connection that ends: takes back the schema changes of the transaction. Nothing is thrown.
   */
  public static void afterRollback(final Database database) {
    try {
      if (database instanceof DatabaseInternal internal
          && internal.getQueryEngine(ENGINE_NAME) instanceof PostgreSQLQueryEngine engine)
        engine.settle();
    } catch (final RuntimeException e) {
      LogManager.instance().log(PostgreSQLQueryEngine.class, Level.WARNING,
          "Cannot take back the schema changes of a rolled back transaction", e);
    }
  }

  /** Does what ended transactions owe the schema, that of the transaction that ended last first. */
  private void settle() {
    if (ended.isEmpty() || database.isTransactionActive())
      return;
    Runnable next;
    while ((next = ended.pollLast()) != null)
      next.run();
  }

  private void dropIfEmpty(final DocumentType table) {
    final String name = table.getName();
    try {
      // no commit comes between the look and the drop: a table that another session has written by now stays
      final List<String> counters = database.executeInWriteLock(() -> {
        final Schema schema = database.getSchema();
        if (!schema.existsType(name) || schema.getType(name) != table || database.countType(name, false) > 0)
          return List.of();
        final List<String> keys = Sequences.keysOf(table);
        schema.dropType(name);
        return keys;
      });
      Sequences.drop(database, counters);
    } catch (final RuntimeException e) {
      LogManager.instance().log(this, Level.WARNING, "Cannot drop the table %s that a rolled back transaction made", e, name);
    }
  }

  /**
   * A transaction block can stay open for long, and other sessions see an index as soon as it is made. So {@code CREATE INDEX}
   * runs in a transaction block only on a table that the same transaction made, whose rollback drops the table with its indexes,
   * and is refused with SQLSTATE {@code 0A000} on any other (fork issue #277). A block is a transaction that this language
   * opened, or the caller: a transaction that a server opened around one request, as its auto-commit, is not one. There the
   * statement runs, and a rollback takes the index back (fork issue #293).
   */
  private void requireTableOfBlock(final Block state, final DocumentType table, final CreateIndexStatement create) {
    if (!database.isTransactionActive())
      return;
    if ((state.open || !database.getTransaction().isImplicit()) && !createdByTransaction(table))
      throw new PostgreSQLFeatureNotSupportedException("CREATE INDEX inside a transaction block is not supported by the"
          + " postgresql language on a table that the block did not create, because a rollback would not take the index back ("
          + create.position() + ")");
  }

  /** Whether {@code CREATE TABLE} made the table in the current transaction. */
  private boolean createdByTransaction(final DocumentType table) {
    return database.getTransaction().getAttachment(OWED) instanceof Owed owed && owed.created.contains(table);
  }

  /**
   * {@code BEGIN} opens the transaction of the database, {@code COMMIT} commits it and {@code ROLLBACK} rolls it back; the
   * answer has no column and no row. As in PostgreSQL, a {@code BEGIN} inside a transaction, and a {@code COMMIT} or a
   * {@code ROLLBACK} with none, change nothing and are no error. A commit that the engine refuses because another transaction
   * changed a record first is SQLSTATE {@code 40001} (serialization_failure), which a client answers by running the block
   * again; the transaction is rolled back and the block is over.
   */
  private ResultSet transaction(final TransactionStatement.Kind kind, final Block state) {
    // the wrapper of the database ends the transaction, so a high availability server replicates what a COMMIT wrote
    final DatabaseInternal transactions = database.getWrappedDatabaseInstance();
    switch (kind) {
    case BEGIN -> {
      if (!database.isTransactionActive()) {
        transactions.begin();
        state.open = true;
        // the clock of the transaction is read now
        TransactionClock.now(database);
      }
      state.failed = false;
    }
    case COMMIT -> {
      state.open = false;
      state.failed = false;
      if (database.isTransactionActive()) {
        try {
          transactions.commit();
        } catch (final RuntimeException e) {
          if (database.isTransactionActive()) {
            try {
              transactions.rollback();
            } catch (final RuntimeException suppressed) {
              e.addSuppressed(suppressed);
            }
          }
          settle();
          throw WriteErrors.translate(e);
        }
        settle();
      }
    }
    case ROLLBACK -> {
      state.open = false;
      state.failed = false;
      if (database.isTransactionActive())
        transactions.rollback();
      settle();
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
      case CreateTableStatement ignored -> "CREATE TABLE";
      case CreateIndexStatement ignored -> "CREATE INDEX";
      case AlterTableStatement ignored -> "ALTER TABLE";
      case DropStatement drop -> "DROP " + drop.kind();
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
   * What a Describe of a statement answers.
   *
   * @param columns        the columns in order, empty for a statement that returns no rows: {@code BEGIN}, {@code COMMIT} and
   *                       {@code ROLLBACK}, and a write without {@code RETURNING}
   * @param parameterTypes the type of {@code $1}, {@code $2} and so on, {@link PgType#UNKNOWN} for one that nothing types
   * @param parameterTypeNames the names of those types; an array that goes into a column or a cast is named with its element
   *                           type ({@code _text})
   */
  public record Description(List<PostgreSQLResultSet.Column> columns, List<PgType> parameterTypes,
      List<String> parameterTypeNames) {
  }

  /**
   * For a server that runs a write as a transaction of its own and may run it again (fork issue #239): the exception of the
   * engine that says another transaction got there first, so that a new transaction can succeed where {@code query} failed.
   * That is a concurrent modification, or a lock that the commit did not get in time. For an {@code INSERT ... ON CONFLICT} it
   * is also a duplicate of a key that the clause answers for: another transaction wrote the row after this one looked for it,
   * and the new transaction finds it and does what the clause says, where PostgreSQL waits for that row. Every other duplicate
   * is the answer of the statement, as in PostgreSQL.
   *
   * @param query the text of an {@code INSERT}, an {@code UPDATE}, or a {@code DELETE} that failed
   * @param error what it failed with, the commit included
   *
   * @return the exception in the cause chain of {@code error}, or {@code null} when running the statement again is no answer
   */
  public RuntimeException conflictOf(final String query, final Throwable error) {
    final ConcurrentModificationException changed = CauseChain.find(error, ConcurrentModificationException.class);
    if (changed != null)
      // a gap in the write-ahead log is a database that diverged, not another writer: running again does not mend it
      return changed instanceof WALVersionGapException ? null : changed;
    final LockTimeoutException locked = CauseChain.find(error, LockTimeoutException.class);
    if (locked != null)
      return locked;
    final DuplicatedKeyException duplicate = CauseChain.find(error, DuplicatedKeyException.class);
    if (duplicate != null && parse(query) instanceof InsertStatement insert && insert.onConflict() != null
        && answersFor(insert.onConflict(), duplicate))
      return duplicate;
    return null;
  }

  /** Whether the unique index that holds the duplicate is one that the {@code ON CONFLICT} clause answers for. */
  private boolean answersFor(final InsertStatement.OnConflict clause, final DuplicatedKeyException duplicate) {
    // with no target the clause answers for every unique index
    if (clause.target().isEmpty())
      return true;
    final Schema schema = database.getSchema();
    if (!schema.existsIndex(duplicate.getIndexName()))
      return false;
    final Set<String> key = new HashSet<>();
    for (final String property : schema.getIndexByName(duplicate.getIndexName()).getPropertyNames())
      key.add(property.toLowerCase(Locale.ROOT));
    final Set<String> target = new HashSet<>();
    for (final Name name : clause.target())
      target.add(name.value().toLowerCase(Locale.ROOT));
    return key.equals(target);
  }

  /** The columns of {@link #describeStatement}, for a caller that does not need the types of the parameters. */
  public List<PostgreSQLResultSet.Column> describe(final String query, final PgType... parameterTypes) {
    return describeStatement(query, parameterTypes).columns();
  }

  /**
   * The columns of the answer of {@code query} and the types of its parameters, found by planning it: nothing is read and nothing
   * is written. It is what a client asks with a Describe message before it binds values, a JDBC
   * {@code PreparedStatement.getMetaData()} for one. Every name is bound and every expression typed, so a statement with an unknown
   * table or column, or a type error, is refused here with the SQLSTATE that it would be refused with when it runs.
   *
   * @param parameterTypes the types of {@code $1}, {@code $2} and so on that the caller declares; a parameter with no type here, or
   *                       {@link PgType#UNKNOWN}, takes its type from the first expression that converts it, as it does in
   *                       PostgreSQL: the column it is compared with or written to, a cast, {@code bigint} for {@code LIMIT},
   *                       {@code text} when it is a whole output column and nothing else types it
   */
  public Description describeStatement(final String query, final PgType... parameterTypes) {
    final Statement statement = parse(query);
    final Parameters parameters = Parameters.describing(parameterTypes);
    List<PostgreSQLResultSet.Column> columns = plannedColumns(statement, parameters);
    final List<PgType> types = parameters.describedTypes();
    if (!types.equals(parameterTypes == null ? List.of() : Arrays.asList(parameterTypes)))
      // an expression can use a parameter before the one that types it: with every type known from the start the columns are
      // those of a run with values of these types
      columns = plannedColumns(statement, Parameters.describing(types.toArray(new PgType[0])));
    if (statement instanceof ExplainStatement || statement instanceof ExplainWriteStatement)
      columns = List.of(new PostgreSQLResultSet.Column(PLAN_COLUMN, "text"));
    // the names come from the first plan: in the second one every type is declared, so nothing infers it again
    return new Description(columns, types, parameters.describedTypeNames());
  }

  /** The columns of a statement planned with described parameters; for {@code EXPLAIN} those of the statement it explains. */
  private List<PostgreSQLResultSet.Column> plannedColumns(final Statement statement, final Parameters parameters) {
    if (statement instanceof TransactionStatement || statement instanceof CreateTableStatement
        || statement instanceof CreateIndexStatement || statement instanceof CreateExtensionStatement
        || statement instanceof AlterTableStatement || statement instanceof DropStatement)
      return List.of();
    if (statement instanceof ExplainWriteStatement explain)
      return new WritePlanner(database, catalog(), parameters).plan(explain.statement()).columns();
    if (statement instanceof WriteStatement write)
      return new WritePlanner(database, catalog(), parameters).plan(write).columns();
    final SelectPlanner planner = new SelectPlanner(database, catalog(), parameters);
    if (statement instanceof ExplainStatement explain)
      return planner.planQuery(explain.select()).columns();
    return planner.planQuery(statement).columns();
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
    final Statement statement;
    try {
      statement = StatementParser.parse(query);
    } catch (final PostgreSQLFeatureNotSupportedException e) {
      if (e.getStatement().isEmpty())
        throw e;
      // a statement of sql that the hint suggests names tables and columns as they are stored, where case counts
      throw new PostgreSQLFeatureNotSupportedException(e.getMessage().substring(0,
          e.getMessage().length() - PostgreSQLError.HINT.length() - e.getHint().length()),
          StatementHints.of(e.getStatement(), storedNames()), e.getStatement());
    }
    if (statement instanceof EmptyStatement)
      throw new PostgreSQLFeatureNotSupportedException(
          "An empty statement is not supported by the postgresql language (" + statement.position() + ")");
    statements.put(query, statement);
    return statement;
  }

  private StatementHints.Names storedNames() {
    return new StatementHints.Names() {
      @Override
      public String table(final String name) {
        final Table table = storedTable(name);
        return table == null ? name : table.name();
      }

      @Override
      public String column(final String tableName, final String name) {
        final Table table = storedTable(tableName);
        if (table != null)
          for (final Column column : table.columns())
            if (column.name().equalsIgnoreCase(name))
              return column.name();
        return name;
      }
    };
  }

  /** The table that the name stands for, {@code null} when none or several do. */
  private Table storedTable(final String name) {
    try {
      return catalog().findTable(Identifier.unquoted(name));
    } catch (final PostgreSQLCatalogException ambiguous) {
      return null;
    }
  }
}
