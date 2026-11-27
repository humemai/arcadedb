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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.ast.DeleteStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.InsertStatement;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.ast.UpdateStatement;
import com.arcadedb.query.postgresql.ast.WriteStatement;
import com.arcadedb.query.postgresql.ast.WriteValue;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.plan.infoschema.InformationSchema;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Property;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.Set;

/**
 * Turns an {@code INSERT}, an {@code UPDATE} or a {@code DELETE} on a document or a vertex table into a {@link WritePlan}
 * (fork issue #29). The rows are the records of the type: nothing is copied or converted, and the table view is written through
 * the same records that {@code sql} and Cypher read.
 *
 * <ul>
 * <li><b>INSERT</b> creates one record per row of {@code VALUES} or of the {@code SELECT}. A column that is not given, or given as
 * {@code DEFAULT}, takes the default of its property, else it is absent, which reads as {@code NULL}; an explicit {@code NULL} is
 * not replaced by the default. {@code id} is never settable ({@code 428C9}: the record id column, {@code id} or {@code rid} by the rule of fork issue #38; a property of the user that is
 * named {@code id} is an ordinary column and is set like any other), a column that the table does not have is
 * {@code 42703}, a property that is {@code NOTNULL} or {@code MANDATORY} and has no value is {@code 23502}, a unique index that
 * already holds the key is {@code 23505}, and a {@code LINK} that does not name an existing record of its type is {@code 23503}.</li>
 * <li><b>UPDATE</b> and <b>DELETE</b> find their rows with the access path of the read side ({@link SelectPlanner}: the record id
 * for {@code id = ...}, an index for {@code column = ...}, else a scan of the type, with its subtypes unless {@code ONLY}), and
 * keep the whole {@code WHERE} clause as a filter, so the path never changes the answer. The rows are found first and changed
 * after, so a statement sees the table as it was when it started. A vertex that is deleted takes its edges with it, as in
 * ArcadeDB, which is what {@code ON DELETE CASCADE} does.</li>
 * <li><b>ON CONFLICT</b> on an {@code INSERT} (document and vertex tables) is the upsert: a row that would break a unique index
 * is skipped ({@code DO NOTHING}) or changes the row that is there ({@code DO UPDATE}, which reads that row by the table name or its
 * alias and the proposed one as {@code EXCLUDED}); see {@link Upsert}.</li>
 * <li>An edge table is written through its two endpoint columns. {@code INSERT} creates one edge between the vertices named by
 * {@code source_id} and {@code target_id}, both required (else {@code 23502}), both an existing vertex (else {@code 23503}), and
 * for a type with declared endpoint tables a vertex of that table; the edge properties are given as for any record. A
 * lightweight edge type is written the same way and keeps no record, so it has no property to give. {@code UPDATE} changes edge
 * properties only: {@code source_id} and {@code target_id} are {@code 0A000} with the hint to delete the edge and insert it
 * again, and the record id column is {@code 428C9}. {@code DELETE} deletes the edges.</li>
 * </ul>
 * The answer is the number of rows in the column {@code count}, or the rows of {@code RETURNING}; see
 * {@link PostgreSQLResultSet#affectedRows()}.
 */
public final class WritePlanner {
  /** The value of a {@code DEFAULT} between the slots of a row. */
  private static final Object DEFAULT = new Object();

  private static final List<PostgreSQLResultSet.Column> COUNT_COLUMN = List.of(new PostgreSQLResultSet.Column("count", "int8"));

  /** One value of a row of {@code VALUES}: an expression of no row, or {@code null} for {@code DEFAULT}, and the type it counts as. */
  private record Slot(Evaluable value, PgType type) {
  }

  /** The columns and the expressions of a {@code RETURNING} list. */
  private record Returning(List<PostgreSQLResultSet.Column> columns, List<Evaluable> outputs) {
    boolean present() {
      return !outputs.isEmpty();
    }
  }

  /** A row of an {@code UPDATE} or a {@code DELETE} that the {@code WHERE} clause let through, with what it will be set to. */
  private record Match(Document record, Object[] values) {
  }

  private final Database           database;
  private final Parameters         parameters;
  private final SelectPlanner      selectPlanner;
  private final RelationalCatalog  catalog;

  public WritePlanner(final Database database, final Parameters parameters) {
    this(database, RelationalCatalog.of(database), parameters);
  }

  /** A planner over a catalog that the caller keeps while the schema does not change (fork issue #88). */
  public WritePlanner(final Database database, final RelationalCatalog catalog, final Parameters parameters) {
    this.database = database;
    this.parameters = parameters;
    this.selectPlanner = new SelectPlanner(database, catalog, parameters);
    this.catalog = selectPlanner.catalog();
  }

  public WritePlan plan(final WriteStatement statement) {
    if (statement instanceof InsertStatement insert)
      return insert(insert);
    if (statement instanceof UpdateStatement update)
      return update(update);
    return delete((DeleteStatement) statement);
  }

  // ------------------------------------------------------------------------------------------------ the table

  private static Identifier identifier(final Name name) {
    return new Identifier(name.value(), name.quoted());
  }

  private Table resolveTable(final TableReference.Table reference, final String verb) {
    final List<Name> name = reference.name();
    if (InformationSchema.resolve(reference, catalog, database) != null)
      throw new PostgreSQLEvaluationException("55000", "cannot change view \"" + name.get(name.size() - 1).value() + "\"");
    final Table table = name.size() == 1 ? catalog.table(identifier(name.get(0)))
        : catalog.table(identifier(name.get(0)), identifier(name.get(1)));
    return table;
  }

  private static String relationOf(final TableReference.Table reference, final Table table) {
    return reference.alias() != null ? reference.alias().value() : table.name();
  }

  // ------------------------------------------------------------------------------------------------ INSERT

  private WritePlan insert(final InsertStatement insert) {
    final Table table = resolveTable(insert.table(), "INSERT");
    final TableSource shape = new TableSource(database, table, relationOf(insert.table(), table), false);
    final ExpressionCompiler constants = new ExpressionCompiler(new RelationScope(List.of(), catalog, selectPlanner.subqueries()), parameters);

    final List<Column> targets = new ArrayList<>();
    for (final Name name : insert.columns()) {
      final Column column = table.column(identifier(name));
      if (targets.contains(column))
        throw new PostgreSQLEvaluationException("42701", "column \"" + column.name() + "\" specified more than once");
      targets.add(column);
    }
    final boolean explicitColumns = !targets.isEmpty();

    final List<String> steps = new ArrayList<>();
    List<Slot[]> valueRows = null;
    SelectPlan selectPlan = null;
    PgType[] selectTypes = null;
    final int width;

    if (insert.source() instanceof InsertStatement.DefaultValues) {
      width = 0;
      valueRows = new ArrayList<>();
      valueRows.add(new Slot[0]);
      steps.add("INSERT INTO " + table.name() + " DEFAULT VALUES");
    } else if (insert.source() instanceof InsertStatement.Values values) {
      width = values.rows().get(0).size();
      valueRows = new ArrayList<>();
      for (final List<WriteValue> row : values.rows()) {
        final Slot[] slots = new Slot[row.size()];
        for (int i = 0; i < slots.length; i++) {
          final WriteValue item = row.get(i);
          if (item.isDefault())
            slots[i] = new Slot(null, PgType.UNKNOWN);
          else {
            final Evaluable value = constants.compile(item.expression());
            slots[i] = new Slot(value, effectiveType(item.expression(), value));
          }
        }
        valueRows.add(slots);
      }
      steps.add("INSERT INTO " + table.name() + ": " + valueRows.size() + " row(s) of VALUES"
          + (table.kind() == Table.Kind.EDGE ? ", one edge each between source_id and target_id" : ""));
    } else {
      final InsertStatement.Query query = (InsertStatement.Query) insert.source();
      selectPlan = selectPlanner.planForInsert(query.select());
      width = selectPlan.columns().size();
      selectTypes = new PgType[width];
      for (int i = 0; i < width; i++) {
        final PgType named = PgType.byName(List.of(selectPlan.columns().get(i).typeName()));
        // an unknown output (a string literal or NULL) takes the type of the column it goes into
        selectTypes[i] = selectPlan.outputTypes().get(i) == PgType.UNKNOWN ? PgType.UNKNOWN : named != null ? named : PgType.ARRAY;
      }
      steps.add("INSERT INTO " + table.name() + " FROM");
      for (final String line : selectPlan.steps())
        steps.add("  " + line);
    }

    // no column list: the first columns of the table, as many as the rows have
    if (!explicitColumns && !(insert.source() instanceof InsertStatement.DefaultValues)) {
      final List<Column> all = table.insertColumns();
      if (width > all.size())
        throw new PostgreSQLEvaluationException("42601", "INSERT has more expressions than target columns");
      targets.addAll(all.subList(0, width));
    }
    if (!(insert.source() instanceof InsertStatement.DefaultValues) && explicitColumns) {
      if (width > targets.size())
        throw new PostgreSQLEvaluationException("42601", "INSERT has more expressions than target columns");
      if (width < targets.size())
        throw new PostgreSQLEvaluationException("42601", "INSERT has more target columns than expressions");
    }

    // what can go into each column, checked before a row is read
    if (valueRows != null)
      for (final Slot[] row : valueRows)
        for (int i = 0; i < row.length; i++)
          checkSlot(targets.get(i), row[i].value(), row[i].type());
    if (selectTypes != null)
      for (int i = 0; i < width; i++) {
        selectPlan.inferParameter(i, Assignments.typeOf(targets.get(i)));
        checkSlot(targets.get(i), new Evaluable.Slot(selectTypes[i], selectPlan.columns().get(i).typeName(), i), selectTypes[i]);
      }

    final Upsert upsert = insert.onConflict() == null ? null : new Upsert(insert.onConflict(), table, shape);
    if (upsert != null)
      steps.add(upsert.describe());

    final Returning returning = returning(insert.returning(), shape);
    if (returning.present())
      steps.add("RETURNING " + returning.columns().size() + " column(s)");

    final List<Slot[]> plannedRows = valueRows;
    final SelectPlan plannedSelect = selectPlan;
    final PgType[] plannedTypes = selectTypes;
    return new WritePlan(steps, returning.present() ? returning.columns() : List.of(), db -> {
      // the rows are all found before the first is written, so INSERT INTO t SELECT ... FROM t sees the table as it was
      final List<Object[]> rows = new ArrayList<>();
      if (plannedRows != null) {
        for (final Slot[] slots : plannedRows) {
          final Object[] values = new Object[slots.length];
          for (int i = 0; i < slots.length; i++)
            values[i] = slots[i].value() == null ? DEFAULT
                : Assignments.convert(slots[i].value().eval(new Object[0]), slots[i].type(), targets.get(i));
          rows.add(values);
        }
      } else {
        final List<String> keys = PostgreSQLResultSet.uniqueKeys(plannedSelect.columns());
        try (final ResultSet resultSet = plannedSelect.execute(db)) {
          while (resultSet.hasNext()) {
            final Result result = resultSet.next();
            final Object[] values = new Object[keys.size()];
            for (int i = 0; i < values.length; i++)
              values[i] = Assignments.convert(result.getProperty(keys.get(i)), plannedTypes[i], targets.get(i));
            rows.add(values);
          }
        }
      }

      final List<Object[]> returned = new ArrayList<>();
      long written = 0;
      if (table.kind() == Table.Kind.EDGE) {
        for (final Object[] values : rows) {
          final Edge edge = insertEdge(db, table, targets, values);
          if (returning.present())
            returned.add(project(returning, shape.rowOf(edge)));
        }
        return answer(db, returning, returned, rows.size(), steps);
      }
      for (final Object[] values : rows) {
        final MutableDocument record = table.kind() == Table.Kind.VERTEX ? db.newVertex(table.name())
            : db.newDocument(table.name());
        final Set<String> given = new HashSet<>();
        for (int i = 0; i < values.length; i++) {
          final Column column = targets.get(i);
          if (values[i] == DEFAULT)
            continue;
          given.add(column.property());
          if (values[i] != null)
            record.set(column.property(), toEngine(db, table, column, values[i]));
          else
            setNull(table, record, column);
        }
        applyDefaults(table, record, given);
        requireValues(table, record);
        record.validate();
        if (upsert != null) {
          final Document conflicting = upsert.find(db, table, record);
          if (conflicting != null) {
            final MutableDocument updated = upsert.doNothing() ? null : upsert.update(db, table, conflicting, record);
            if (updated != null) {
              written++;
              if (returning.present())
                returned.add(project(returning, shape.rowOf(updated)));
            }
            continue;
          }
        }
        checkUnique(db, record, null);
        record.save();
        if (upsert != null)
          upsert.inserted(table, db, record);
        written++;
        if (returning.present())
          returned.add(project(returning, shape.rowOf(record)));
      }
      return answer(db, returning, returned, written, steps);
    });
  }

  /**
   * The {@code ON CONFLICT} clause of an {@code INSERT}, planned. A row is in conflict when a unique index already holds its key: the
   * one index that the target names, or, with no target (which {@code DO NOTHING} allows), any unique index of the type. A key with
   * a {@code NULL} in it conflicts with nothing, as in PostgreSQL. {@code DO NOTHING} skips the row. {@code DO UPDATE} changes the
   * row that is there: its {@code SET} items and {@code WHERE} read it by the table name (or the alias) and the proposed row as
   * {@code excluded}, so a bare column name that both have is {@code 42702}, as in PostgreSQL. A row whose {@code WHERE} is not true is
   * left as it is and not counted. A row that would change what the same statement inserted or changed is {@code 21000}.
   *
   * <p>The rows that the statement itself inserted are kept by key, so a conflict inside one statement is found whether or not the
   * index shows a row of the open transaction.
   */
  private final class Upsert {
    private final List<Column>     target;
    private final TableSource      existing;
    private final TableSource      proposed;
    private final SetList          set;
    private final Evaluable        where;
    private final String           text;
    /** the rows this statement inserted, by index name and key: a second row with the key is a conflict with them */
    private final Map<String, Map<List<Object>, Document>> own     = new HashMap<>();
    /** the rows this statement inserted or changed under {@code DO UPDATE} */
    private final Set<RID>                                 touched = new HashSet<>();

    Upsert(final InsertStatement.OnConflict clause, final Table table, final TableSource shape) {
      if (table.kind() == Table.Kind.EDGE)
        throw new PostgreSQLFeatureNotSupportedException("ON CONFLICT on the edge table " + table.name() + " is not supported: "
            + "an edge is inserted between two vertices, delete it and insert it again (" + clause.position() + ")");
      target = new ArrayList<>();
      for (final Name name : clause.target()) {
        final Column column = table.column(identifier(name));
        if (!target.contains(column))
          target.add(column);
      }
      // a target has to name a unique index, as PostgreSQL's inference does, also for DO NOTHING and also for an empty table
      if (!target.isEmpty() && matching(table).isEmpty())
        throw new PostgreSQLEvaluationException("42P10",
            "there is no unique or exclusion constraint matching the ON CONFLICT specification");
      existing = shape;
      if (clause.doNothing()) {
        proposed = null;
        set = null;
        where = null;
      } else {
        proposed = new TableSource(database, table, "excluded", false);
        final List<RelationSource.Field> fields = new ArrayList<>(shape.columns());
        fields.addAll(proposed.columns());
        final ExpressionCompiler compiler = new ExpressionCompiler(new RelationScope(fields, catalog, selectPlanner.subqueries()), parameters);
        set = setList(clause.assignments(), table, compiler);
        where = clause.where() == null ? null : compiler.compileCondition(clause.where(), "WHERE");
      }
      text = "ON CONFLICT" + (target.isEmpty() ? "" : " (" + String.join(", ", target.stream().map(Column::name).toList()) + ")")
          + (clause.doNothing() ? " DO NOTHING" : " DO UPDATE SET " + set.columns().size() + " column(s)"
          + (where == null ? "" : " WHERE"));
    }

    String describe() {
      return text;
    }

    boolean doNothing() {
      return set == null;
    }

    /** The unique indexes whose key is the target, or every unique index when the clause has no target. */
    private List<TypeIndex> matching(final Table table) {
      final List<TypeIndex> found = new ArrayList<>();
      final Set<String> wanted = new HashSet<>();
      for (final Column column : target)
        wanted.add(column.kind() == Column.Kind.PROPERTY ? column.property() : "#" + column.name());
      for (final TypeIndex index : database.getSchema().getType(table.name()).getAllIndexes(true))
        if (index.isUnique() && (target.isEmpty() || new HashSet<>(index.getPropertyNames()).equals(wanted)))
          found.add(index);
      return found;
    }

    /** The row that holds the key of the proposed one, {@code null} when there is none. */
    Document find(final Database db, final Table table, final MutableDocument record) {
      for (final TypeIndex index : matching(table)) {
        final List<String> properties = index.getPropertyNames();
        final Object[] keys = new Object[properties.size()];
        boolean complete = true;
        for (int i = 0; i < keys.length; i++) {
          keys[i] = record.get(properties.get(i));
          complete &= keys[i] != null;
        }
        if (!complete)
          continue;
        final Document previous = own.getOrDefault(index.getName(), Map.of()).get(Arrays.asList(keys));
        final Document found;
        if (previous != null)
          found = previous;
        else {
          final IndexCursor cursor = index.get(keys);
          if (!cursor.hasNext())
            continue;
          found = cursor.next().asDocument();
        }
        if (!doNothing() && touched.contains(found.getIdentity()))
          throw new PostgreSQLEvaluationException("21000", "ON CONFLICT DO UPDATE command cannot affect row a second time");
        return found;
      }
      return null;
    }

    /** Remembers a row that the statement inserted, under the key of every unique index it can conflict on. */
    void inserted(final Table table, final Database db, final MutableDocument record) {
      for (final TypeIndex index : matching(table)) {
        final List<String> properties = index.getPropertyNames();
        final List<Object> keys = new ArrayList<>();
        for (final String property : properties)
          keys.add(record.get(property));
        if (keys.contains(null))
          continue;
        own.computeIfAbsent(index.getName(), name -> new HashMap<>()).put(keys, record);
      }
      if (!doNothing())
        touched.add(record.getIdentity());
    }

    /** {@code DO UPDATE}: the changed row, or {@code null} when the {@code WHERE} is not true and the row is left as it is. */
    MutableDocument update(final Database db, final Table table, final Document found, final MutableDocument record) {
      final Object[] there = existing.rowOf(found);
      final Object[] proposal = proposed.rowOf(record);
      final Object[] both = Arrays.copyOf(there, there.length + proposal.length);
      System.arraycopy(proposal, 0, both, there.length, proposal.length);
      if (where != null && !Boolean.TRUE.equals(where.eval(both)))
        return null;
      final Object[] values = new Object[set.columns().size()];
      for (int i = 0; i < values.length; i++)
        values[i] = set.values().get(i) == null ? DEFAULT
            : Assignments.convert(set.values().get(i).eval(both), set.types().get(i), set.columns().get(i));
      final MutableDocument changed = found.modify();
      final Set<String> properties = applySet(db, table, changed, set.columns(), values);
      requireValues(table, changed);
      changed.validate();
      checkUnique(db, changed, properties);
      changed.save();
      touched.add(changed.getIdentity());
      return changed;
    }
  }

  /** The {@code SET} list of an {@code UPDATE} or of a {@code DO UPDATE}: the columns, what they are set to and the type of each. */
  private record SetList(List<Column> columns, List<Evaluable> values, List<PgType> types) {
  }

  private SetList setList(final List<UpdateStatement.Assignment> assignments, final Table table, final ExpressionCompiler compiler) {
    final List<Column> columns = new ArrayList<>();
    final List<Evaluable> values = new ArrayList<>();
    final List<PgType> types = new ArrayList<>();
    for (final UpdateStatement.Assignment assignment : assignments) {
      final Column column = table.column(identifier(assignment.column()));
      if (columns.contains(column))
        throw new PostgreSQLEvaluationException("42601", "multiple assignments to the same column \"" + column.name() + "\"");
      if (column.kind() == Column.Kind.SOURCE || column.kind() == Column.Kind.TARGET)
        throw new PostgreSQLFeatureNotSupportedException("UPDATE of the column " + column.name() + " of the edge table " + table.name()
            + " is not supported: delete the edge and insert it again (" + assignment.position() + ")");
      if (column.isSynthetic() && !assignment.value().isDefault())
        throw new PostgreSQLEvaluationException("428C9", "column \"" + column.name() + "\" can only be updated to DEFAULT. Detail: Column \""
            + column.name() + "\" is an identity column defined as GENERATED ALWAYS.");
      Evaluable value = null;
      PgType type = PgType.UNKNOWN;
      if (!assignment.value().isDefault()) {
        value = compiler.compile(assignment.value().expression());
        type = effectiveType(assignment.value().expression(), value);
        Assignments.inferParameter(value, column);
        if (!(value instanceof Evaluable.Constant constant && constant.value() == null))
          Assignments.check(type, value.typeName(), column);
      }
      columns.add(column);
      values.add(value);
      types.add(type);
    }
    return new SetList(columns, values, types);
  }

  /** Sets the columns of a record to the values computed for them; the result is the properties that were set. */
  private Set<String> applySet(final Database db, final Table table, final MutableDocument record, final List<Column> columns,
      final Object[] values) {
    final Set<String> changed = new HashSet<>();
    for (int i = 0; i < columns.size(); i++) {
      final Column column = columns.get(i);
      if (column.isSynthetic())
        continue;
      Object value = values[i];
      if (value == DEFAULT) {
        final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
        value = property == null ? null : property.getDefaultValue();
        changed.add(column.property());
        if (value == null)
          setNull(table, record, column);
        else
          record.set(column.property(), value);
      } else {
        changed.add(column.property());
        if (value == null)
          setNull(table, record, column);
        else
          record.set(column.property(), toEngine(db, table, column, value));
      }
    }
    return changed;
  }

  /** The type a slot counts as: a text parameter is {@code unknown} as it is in PostgreSQL, which gives it the type of its column. */
  private static PgType effectiveType(final Expression expression, final Evaluable value) {
    if (expression instanceof Expression.ParameterRef && takesColumnType(value.type()))
      return PgType.UNKNOWN;
    return value.type();
  }

  /** The types of a bound value that the column decides: text, and the Java values of the types that have no Java class of their own. */
  private static boolean takesColumnType(final PgType type) {
    return type.isString() || type.isOpaque() || type == PgType.VECTOR;
  }

  /** The checks of one value against its column that need no row: {@code id}, the types the language does not write yet, the cast. */
  private static void checkSlot(final Column column, final Evaluable value, final PgType type) {
    if (value == null)
      return;
    if (column.kind() == Column.Kind.IDENTITY)
      throw new PostgreSQLEvaluationException("428C9", "cannot insert a non-DEFAULT value into column \"" + column.name()
          + "\". Detail: Column \"" + column.name() + "\" is an identity column defined as GENERATED ALWAYS. Hint: leave the column"
          + " out of the column list or write DEFAULT: the engine gives the record id of the new row");
    Assignments.inferParameter(value, column);
    if (value instanceof Evaluable.Constant constant && constant.value() == null)
      return;
    Assignments.check(type, value.typeName(), column);
  }

  /**
   * One edge of an {@code INSERT} on an edge table: between the vertices {@code source_id} and {@code target_id} name, with the
   * properties given. A lightweight edge type keeps no record, a regular one gets its record from the engine.
   */
  private Edge insertEdge(final Database db, final Table table, final List<Column> targets, final Object[] values) {
    String source = null;
    String target = null;
    final Map<String, Object> properties = new LinkedHashMap<>();
    final Set<String> given = new HashSet<>();
    for (int i = 0; i < values.length; i++) {
      final Column column = targets.get(i);
      if (values[i] == DEFAULT)
        continue;
      switch (column.kind()) {
      case SOURCE -> source = (String) values[i];
      case TARGET -> target = (String) values[i];
      case PROPERTY -> {
        given.add(column.property());
        if (values[i] != null)
          properties.put(column.property(), toEngine(db, table, column, values[i]));
      }
      default -> {
      }
      }
    }
    if (source == null)
      throw notNull(table, nameOf(table, Column.Kind.SOURCE));
    if (target == null)
      throw notNull(table, nameOf(table, Column.Kind.TARGET));

    for (final Column column : table.columns()) {
      if (column.kind() != Column.Kind.PROPERTY || given.contains(column.property()))
        continue;
      final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
      final Object value = property == null ? null : property.getDefaultValue();
      if (value != null)
        properties.put(column.property(), value);
    }
    requireValues(table, properties::get);

    final Vertex from = endpoint(db, table, nameOf(table, Column.Kind.SOURCE), source, table.sourceTable());
    final Vertex to = endpoint(db, table, nameOf(table, Column.Kind.TARGET), target, table.targetTable());
    final EdgeType type = (EdgeType) db.getSchema().getType(table.name());
    if (type.isLightweight()) {
      if (!properties.isEmpty())
        throw new PostgreSQLFeatureNotSupportedException("The edge type " + table.name() + " is lightweight: its edges have no record, "
            + "so no property can be given");
      return from.newLightEdge(table.name(), to);
    }
    final Object[] flat = new Object[properties.size() * 2];
    int next = 0;
    for (final Map.Entry<String, Object> property : properties.entrySet()) {
      flat[next++] = property.getKey();
      flat[next++] = property.getValue();
    }
    return from.newEdge(table.name(), to, flat);
  }

  /**
   * The name the catalog gives the synthetic column of this kind in this table. The executors never spell {@code id},
   * {@code source_id} or {@code target_id}: they find the record id, the source and the target by their kind, so a rule of the
   * catalog about which column carries which name (fork issue #38) changes no executor.
   */
  private static String nameOf(final Table table, final Column.Kind kind) {
    for (final Column column : table.columns())
      if (column.kind() == kind)
        return column.name();
    throw new IllegalStateException("The table " + table.name() + " has no column of the kind " + kind);
  }

  private static PostgreSQLEvaluationException notNull(final Table table, final String column) {
    return new PostgreSQLEvaluationException("23502",
        "null value in column \"" + column + "\" of relation \"" + table.name() + "\" violates not-null constraint");
  }

  /** The vertex that an endpoint column names: it has to exist, and to be of the declared endpoint table when there is one. */
  private static Vertex endpoint(final Database db, final Table table, final String column, final String text, final String declared) {
    Record found = null;
    try {
      found = db.lookupByRID(new RID(text), true);
    } catch (final RuntimeException notFound) {
      // reported below with the one SQLSTATE of a reference that has no target
    }
    if (!(found instanceof Vertex vertex) || (declared != null && !vertex.getTypeName().equals(declared)
        && !db.getSchema().getType(vertex.getTypeName()).instanceOf(declared)))
      throw new PostgreSQLEvaluationException("23503", "insert or update on table \"" + table.name() + "\" violates foreign key "
          + "constraint on column \"" + column + "\": the value \"" + text + "\" is not a vertex"
          + (declared == null ? "" : " of the table " + declared));
    return vertex;
  }

  // ------------------------------------------------------------------------------------------------ UPDATE

  /**
   * {@code UPDATE t SET ... FROM items WHERE ...}: the join of the target with the {@code FROM} items is a {@code SELECT} of the
   * record id of the target and of each {@code SET} value, planned by the select planner (so joins, derived tables and subqueries
   * are those of a query). A target row that no joined row reaches is not changed. A row reached by several is changed once, by the
   * first, as PostgreSQL does (it says that which one is used is not predictable). The values are all found before the first row is
   * written, so no row sees another's change. {@code RETURNING}, an edge table and a table without a record id column are refused.
   */
  private WritePlan updateFrom(final UpdateStatement update, final Table table) {
    if (table.kind() == Table.Kind.EDGE)
      throw new PostgreSQLFeatureNotSupportedException("UPDATE ... FROM on the edge table " + table.name() + " is not supported");
    if (!update.returning().isEmpty())
      throw new PostgreSQLFeatureNotSupportedException("RETURNING with UPDATE ... FROM is not supported");
    final Column identity = table.identityColumn();
    if (identity == null)
      throw new PostgreSQLFeatureNotSupportedException("UPDATE ... FROM needs the record id column, which " + table.name()
          + " does not have");

    final var position = update.position();
    final String relation = relationOf(update.table(), table);
    final List<SelectItem> items = new ArrayList<>();
    items.add(new SelectItem.Projection(position, new Expression.ColumnRef(position, List.of(new Name(position, relation, true)),
        new Name(position, identity.name(), true)), new Name(position, "__rid", true)));
    final List<Column> columns = new ArrayList<>();
    final List<Boolean> defaults = new ArrayList<>();
    final Set<Integer> parameterColumns = new HashSet<>();
    for (final UpdateStatement.Assignment assignment : update.assignments()) {
      final Column column = table.column(identifier(assignment.column()));
      if (columns.contains(column))
        throw new PostgreSQLEvaluationException("42601", "multiple assignments to the same column \"" + column.name() + "\"");
      if (column.isSynthetic() && !assignment.value().isDefault())
        throw new PostgreSQLEvaluationException("428C9", "column \"" + column.name() + "\" can only be updated to DEFAULT. Detail: Column \""
            + column.name() + "\" is an identity column defined as GENERATED ALWAYS.");
      columns.add(column);
      defaults.add(assignment.value().isDefault());
      if (!assignment.value().isDefault()) {
        // the value is planned as an output of a query: a bare parameter takes the type of its column before that
        if (assignment.value().expression() instanceof Expression.ParameterRef reference) {
          if (column.type().isArray())
            parameters.inferDescribedArray(reference.index(), Assignments.arrayName(column));
          else
            parameters.inferDescribed(reference.index(), Assignments.typeOf(column));
          parameterColumns.add(columns.size() - 1);
        }
        items.add(new SelectItem.Projection(position, assignment.value().expression(),
            new Name(position, "__set" + (items.size() - 1), true)));
      }
    }
    final List<TableReference> from = new ArrayList<>();
    from.add(update.table());
    from.addAll(update.from());
    final SelectPlan selectPlan = selectPlanner.plan(new SelectStatement(position, false, items, from, update.where(), List.of(), null,
        List.of(), null, null));

    final PgType[] types = new PgType[columns.size()];
    int next = 1;
    for (int i = 0; i < types.length; i++) {
      if (defaults.get(i))
        continue;
      final String typeName = selectPlan.columns().get(next++).typeName();
      final PgType type = PgType.byName(List.of(typeName));
      types[i] = type != null ? type : PgType.ARRAY;
      // a bare parameter takes the type of its column, as in the SET list of a plain UPDATE
      if (parameterColumns.contains(i) && takesColumnType(types[i]))
        types[i] = PgType.UNKNOWN;
      Assignments.check(types[i], typeName, columns.get(i));
    }
    final List<String> steps = new ArrayList<>();
    steps.add("UPDATE " + table.name() + " SET " + columns.size() + " column(s) FROM");
    for (final String line : selectPlan.steps())
      steps.add("  " + line);

    return new WritePlan(steps, List.of(), db -> {
      final Map<RID, Object[]> chosen = new LinkedHashMap<>();
      final List<String> keys = PostgreSQLResultSet.uniqueKeys(selectPlan.columns());
      try (final ResultSet resultSet = selectPlan.execute(db)) {
        while (resultSet.hasNext()) {
          final Result result = resultSet.next();
          final Object id = result.getProperty(keys.get(0));
          if (id == null)
            continue;
          final RID rid = new RID(id.toString());
          if (chosen.containsKey(rid))
            continue;
          final Object[] set = new Object[columns.size()];
          int slot = 1;
          for (int i = 0; i < set.length; i++)
            set[i] = defaults.get(i) ? DEFAULT : Assignments.convert(result.getProperty(keys.get(slot++)), types[i], columns.get(i));
          chosen.put(rid, set);
        }
      }
      for (final Map.Entry<RID, Object[]> match : chosen.entrySet()) {
        final MutableDocument record = db.lookupByRID(match.getKey(), true).asDocument().modify();
        final Set<String> changed = applySet(db, table, record, columns, match.getValue());
        requireValues(table, record);
        record.validate();
        checkUnique(db, record, changed);
        record.save();
      }
      return answer(db, new Returning(List.of(), List.of()), List.of(), chosen.size(), steps);
    });
  }

  private WritePlan update(final UpdateStatement update) {
    final Table table = resolveTable(update.table(), "UPDATE");
    if (!update.from().isEmpty())
      return updateFrom(update, table);
    TableSource source = new TableSource(database, table, relationOf(update.table(), table), update.table().only());
    final ExpressionCompiler compiler = new ExpressionCompiler(new RelationScope(source.columns(), catalog, selectPlanner.subqueries()),
        parameters);

    final Expression whereClause = OrFactoring.factor(update.where());
    final Evaluable where = whereClause == null ? null : compiler.compileCondition(whereClause, "WHERE");
    if (whereClause != null)
      source = selectPlanner.chooseAccessPath(source, whereClause, compiler);

    final SetList assigned = setList(update.assignments(), table, compiler);
    final List<Column> columns = assigned.columns();
    final List<Evaluable> values = assigned.values();
    final List<PgType> types = assigned.types();

    final TableSource found = source;
    final Returning returning = returning(update.returning(), found);
    final List<String> steps = new ArrayList<>();
    steps.add("UPDATE " + table.name() + " SET " + columns.size() + " column(s)");
    steps.addAll(found.describeTree().stream().map(line -> "  " + line).toList());
    if (where != null)
      steps.add("  FILTER WHERE");
    if (returning.present())
      steps.add("RETURNING " + returning.columns().size() + " column(s)");

    return new WritePlan(steps, returning.present() ? returning.columns() : List.of(), db -> {
      // find the rows first and compute what each is set to from the row as it is now, so no row sees another's change
      final List<Match> matches = new ArrayList<>();
      final Iterator<Document> records = found.openRecords();
      while (records.hasNext()) {
        final Document record = records.next();
        final Object[] row = found.rowOf(record);
        if (where != null && !Boolean.TRUE.equals(where.eval(row)))
          continue;
        final Object[] set = new Object[columns.size()];
        for (int i = 0; i < set.length; i++)
          set[i] = values.get(i) == null ? DEFAULT : Assignments.convert(values.get(i).eval(row), types.get(i), columns.get(i));
        matches.add(new Match(record, set));
      }

      final List<Object[]> returned = new ArrayList<>();
      for (final Match match : matches) {
        final MutableDocument record = match.record().modify();
        final Set<String> changed = applySet(db, table, record, columns, match.values());
        requireValues(table, record);
        record.validate();
        checkUnique(db, record, changed);
        record.save();
        if (returning.present())
          returned.add(project(returning, found.rowOf(record)));
      }
      return answer(db, returning, returned, matches.size(), steps);
    });
  }

  // ------------------------------------------------------------------------------------------------ DELETE

  private WritePlan delete(final DeleteStatement delete) {
    final Table table = resolveTable(delete.table(), "DELETE");
    TableSource source = new TableSource(database, table, relationOf(delete.table(), table), delete.table().only());
    final ExpressionCompiler compiler = new ExpressionCompiler(new RelationScope(source.columns(), catalog, selectPlanner.subqueries()),
        parameters);

    final Expression whereClause = OrFactoring.factor(delete.where());
    final Evaluable where = whereClause == null ? null : compiler.compileCondition(whereClause, "WHERE");
    if (whereClause != null)
      source = selectPlanner.chooseAccessPath(source, whereClause, compiler);

    final TableSource found = source;
    final Returning returning = returning(delete.returning(), found);
    final List<String> steps = new ArrayList<>();
    steps.add("DELETE FROM " + table.name() + (table.kind() == Table.Kind.VERTEX ? " (the edges of a deleted vertex go with it)" : ""));
    steps.addAll(found.describeTree().stream().map(line -> "  " + line).toList());
    if (where != null)
      steps.add("  FILTER WHERE");
    if (returning.present())
      steps.add("RETURNING " + returning.columns().size() + " column(s)");

    return new WritePlan(steps, returning.present() ? returning.columns() : List.of(), db -> {
      final List<Document> matched = new ArrayList<>();
      final List<Object[]> returned = new ArrayList<>();
      final Iterator<Document> records = found.openRecords();
      while (records.hasNext()) {
        final Document record = records.next();
        final Object[] row = found.rowOf(record);
        if (where != null && !Boolean.TRUE.equals(where.eval(row)))
          continue;
        matched.add(record);
        if (returning.present())
          returned.add(project(returning, row));
      }
      for (final Document record : matched) {
        if (record instanceof Edge edge)
          edge.delete();
        else
          db.deleteRecord(record);
      }
      return answer(db, returning, returned, matched.size(), steps);
    });
  }

  // ------------------------------------------------------------------------------------------------ values and checks

  /**
   * The value as the record holds it: a {@code LINK} is the record id, after the record has been found to exist; the values of
   * {@code jsonb}, arrays and {@code vector} as {@link StoredValues} has them.
   */
  private Object toEngine(final Database db, final Table table, final Column column, final Object value) {
    // an instant of a timestamptz column is held as its time in UTC
    if (value instanceof OffsetDateTime instant)
      return instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    if (StoredValues.applies(column))
      return StoredValues.of(db, table, column, value);
    if (!column.isLink())
      return value;
    final String text = (String) value;
    final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
    final String linked = property == null ? null : property.getOfType();
    Record target = null;
    RID rid = null;
    try {
      rid = new RID(text);
      target = db.lookupByRID(rid, true);
    } catch (final RuntimeException notFound) {
      // reported below with the one SQLSTATE of a reference that has no target
    }
    if (!(target instanceof Document document) || (linked != null && !document.getTypeName().equals(linked)
        && !db.getSchema().getType(document.getTypeName()).instanceOf(linked)))
      throw new PostgreSQLEvaluationException("23503", "insert or update on table \"" + table.name() + "\" violates foreign key "
          + "constraint on column \"" + column.name() + "\": the value \"" + text + "\" is not a record"
          + (linked == null ? "" : " of the type " + linked));
    return rid;
  }

  /**
   * {@code NULL} in a column: the property is absent, which reads as {@code NULL}. A property that has a default is given an
   * explicit null instead, because the engine fills an absent property with its default when it saves the record, and an explicit
   * {@code NULL} is not replaced by the default.
   */
  private static void setNull(final Table table, final MutableDocument record, final Column column) {
    final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
    if (property != null && property.getDefaultValue() != null)
      record.set(column.property(), null);
    else
      record.remove(column.property());
  }

  /** A column that is not given, or given as {@code DEFAULT}, takes the default of its property. */
  private static void applyDefaults(final Table table, final MutableDocument record, final Set<String> given) {
    for (final Column column : table.columns()) {
      if (column.kind() != Column.Kind.PROPERTY || given.contains(column.property()))
        continue;
      final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
      if (property == null)
        continue;
      final Object value = property.getDefaultValue();
      if (value != null)
        record.set(column.property(), value);
    }
  }

  /** A {@code NOTNULL} or {@code MANDATORY} property with no value: {@code 23502}, as a {@code NOT NULL} column has it. */
  private static void requireValues(final Table table, final MutableDocument record) {
    requireValues(table, record::get);
  }

  private static void requireValues(final Table table, final Function<String, Object> valueOf) {
    for (final Column column : table.columns()) {
      if (column.kind() != Column.Kind.PROPERTY || valueOf.apply(column.property()) != null)
        continue;
      final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
      if (!column.nullable() || (property != null && property.isMandatory()))
        throw new PostgreSQLEvaluationException("23502",
            "null value in column \"" + column.name() + "\" of relation \"" + table.name() + "\" violates not-null constraint");
    }
  }

  /**
   * A unique index that already holds the key of the record: {@code 23505} now, so that a statement inside a transaction fails
   * where PostgreSQL fails it and not at the commit. The engine checks again at the commit, which finds what two statements wrote
   * at once, and {@link WriteErrors} gives that the same SQLSTATE.
   *
   * @param changed the properties an {@code UPDATE} set, {@code null} for an {@code INSERT}: only an index on one of them is looked at
   */
  private static void checkUnique(final Database db, final MutableDocument record, final Set<String> changed) {
    final DocumentType type = db.getSchema().getType(record.getTypeName());
    for (final TypeIndex index : type.getAllIndexes(true)) {
      if (!index.isUnique())
        continue;
      final List<String> properties = index.getPropertyNames();
      if (changed != null && properties.stream().noneMatch(changed::contains))
        continue;
      final Object[] keys = new Object[properties.size()];
      boolean complete = true;
      for (int i = 0; i < keys.length; i++) {
        keys[i] = record.get(properties.get(i));
        complete &= keys[i] != null;
      }
      if (!complete)
        continue;
      final IndexCursor cursor = index.get(keys);
      while (cursor.hasNext()) {
        final Identifiable found = cursor.next();
        if (record.getIdentity() == null || !found.getIdentity().equals(record.getIdentity()))
          throw new PostgreSQLEvaluationException("23505", "duplicate key value violates unique constraint \"" + index.getName()
              + "\": the key already exists in " + type.getName(),
              new DuplicatedKeyException(index.getName(), Arrays.toString(keys), found.getIdentity()));
      }
    }
  }

  // ------------------------------------------------------------------------------------------------ RETURNING and the answer

  private Returning returning(final List<SelectItem> items, final TableSource shape) {
    final RelationScope scope = new RelationScope(shape.columns(), catalog);
    final ExpressionCompiler compiler = new ExpressionCompiler(scope, parameters);
    final List<Evaluable> outputs = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    for (final SelectItem item : items) {
      if (item instanceof SelectItem.Star star) {
        for (final Expression.ColumnRef reference : scope.expand(star)) {
          outputs.add(compiler.compile(reference));
          names.add(reference.column().value());
        }
      } else if (item instanceof SelectItem.Projection projection) {
        outputs.add(compiler.resolveOutput(compiler.compile(projection.expression())));
        names.add(projection.alias() != null ? projection.alias().value() : SelectPlanner.outputName(projection.expression()));
      }
    }
    final List<PostgreSQLResultSet.Column> columns = new ArrayList<>();
    for (int i = 0; i < outputs.size(); i++)
      columns.add(new PostgreSQLResultSet.Column(names.get(i), outputs.get(i).typeName()));
    return new Returning(columns, outputs);
  }

  private static Object[] project(final Returning returning, final Object[] row) {
    final Object[] out = new Object[returning.outputs().size()];
    for (int i = 0; i < out.length; i++)
      out[i] = returning.outputs().get(i).eval(row);
    return out;
  }

  private static PostgreSQLResultSet answer(final Database db, final Returning returning, final List<Object[]> returned,
      final long count, final List<String> steps) {
    final PostgreSQLResultSet result;
    if (returning.present()) {
      result = new PostgreSQLResultSet(returning.columns(), new PostgreSQLResultSet.Plan(steps));
      final List<String> keys = PostgreSQLResultSet.uniqueKeys(returning.columns());
      for (final Object[] values : returned) {
        final ResultInternal row = new ResultInternal(db);
        for (int i = 0; i < keys.size(); i++)
          row.setProperty(keys.get(i), values[i]);
        result.add(row);
      }
    } else {
      result = new PostgreSQLResultSet(COUNT_COLUMN, new PostgreSQLResultSet.Plan(steps)).rowless();
      final ResultInternal row = new ResultInternal(db);
      row.setProperty("count", count);
      result.add(row);
    }
    return result.withAffectedRows(count);
  }
}
