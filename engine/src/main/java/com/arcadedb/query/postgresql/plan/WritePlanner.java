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

import java.util.ArrayList;
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
    this.database = database;
    this.parameters = parameters;
    this.selectPlanner = new SelectPlanner(database, parameters);
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
    if (InformationSchema.resolve(reference, catalog) != null)
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
    final ExpressionCompiler constants = new ExpressionCompiler(new RelationScope(List.of(), catalog), parameters);

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
      selectPlan = selectPlanner.plan(query.select());
      width = selectPlan.columns().size();
      selectTypes = new PgType[width];
      for (int i = 0; i < width; i++) {
        final PgType type = PgType.byName(List.of(selectPlan.columns().get(i).typeName()));
        selectTypes[i] = type != null ? type : PgType.ARRAY;
      }
      steps.add("INSERT INTO " + table.name() + " FROM");
      for (final String line : selectPlan.steps())
        steps.add("  " + line);
    }

    // no column list: the first columns of the table, as many as the rows have
    if (!explicitColumns && !(insert.source() instanceof InsertStatement.DefaultValues)) {
      if (width > table.columns().size())
        throw new PostgreSQLEvaluationException("42601", "INSERT has more expressions than target columns");
      targets.addAll(table.columns().subList(0, width));
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
      for (int i = 0; i < width; i++)
        checkSlot(targets.get(i), new Evaluable.Slot(selectTypes[i], i), selectTypes[i]);

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
        checkUnique(db, record, null);
        record.save();
        if (returning.present())
          returned.add(project(returning, shape.rowOf(record)));
      }
      return answer(db, returning, returned, rows.size(), steps);
    });
  }

  /** The type a slot counts as: a text parameter is {@code unknown} as it is in PostgreSQL, which gives it the type of its column. */
  private static PgType effectiveType(final Expression expression, final Evaluable value) {
    if (expression instanceof Expression.ParameterRef && value.type().isString())
      return PgType.UNKNOWN;
    return value.type();
  }

  /** The checks of one value against its column that need no row: {@code id}, the types the language does not write yet, the cast. */
  private static void checkSlot(final Column column, final Evaluable value, final PgType type) {
    if (value == null)
      return;
    if (column.kind() == Column.Kind.IDENTITY)
      throw new PostgreSQLEvaluationException("428C9", "cannot insert a non-DEFAULT value into column \"" + column.name() + "\": the record id is generated");
    if (value instanceof Evaluable.Constant constant && constant.value() == null)
      return;
    Assignments.check(type, column);
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

  private WritePlan update(final UpdateStatement update) {
    final Table table = resolveTable(update.table(), "UPDATE");
    TableSource source = new TableSource(database, table, relationOf(update.table(), table), update.table().only());
    final ExpressionCompiler compiler = new ExpressionCompiler(new RelationScope(source.columns(), catalog), parameters);

    final Expression whereClause = OrFactoring.factor(update.where());
    final Evaluable where = whereClause == null ? null : compiler.compileCondition(whereClause, "WHERE");
    if (whereClause != null)
      source = selectPlanner.chooseAccessPath(source, whereClause, compiler);

    final List<Column> columns = new ArrayList<>();
    final List<Evaluable> values = new ArrayList<>();
    final List<PgType> types = new ArrayList<>();
    for (final UpdateStatement.Assignment assignment : update.assignments()) {
      final Column column = table.column(identifier(assignment.column()));
      if (columns.contains(column))
        throw new PostgreSQLEvaluationException("42601", "multiple assignments to the same column \"" + column.name() + "\"");
      if (column.kind() == Column.Kind.SOURCE || column.kind() == Column.Kind.TARGET)
        throw new PostgreSQLFeatureNotSupportedException("UPDATE of the column " + column.name() + " of the edge table " + table.name()
            + " is not supported: delete the edge and insert it again (" + assignment.position() + ")");
      if (column.isSynthetic() && !assignment.value().isDefault())
        throw new PostgreSQLEvaluationException("428C9", "column \"" + column.name() + "\" can only be updated to DEFAULT");
      Evaluable value = null;
      PgType type = PgType.UNKNOWN;
      if (!assignment.value().isDefault()) {
        value = compiler.compile(assignment.value().expression());
        type = effectiveType(assignment.value().expression(), value);
        if (!(value instanceof Evaluable.Constant constant && constant.value() == null))
          Assignments.check(type, column);
      }
      columns.add(column);
      values.add(value);
      types.add(type);
    }

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
        final Set<String> changed = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
          final Column column = columns.get(i);
          if (column.isSynthetic())
            continue;
          Object value = match.values()[i];
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
    final ExpressionCompiler compiler = new ExpressionCompiler(new RelationScope(source.columns(), catalog), parameters);

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

  /** The value as the record holds it: a {@code LINK} is the record id, after the record has been found to exist. */
  private Object toEngine(final Database db, final Table table, final Column column, final Object value) {
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
              + "\": the key already exists in " + type.getName());
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
