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
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.Record;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.query.postgresql.ast.AlterTableStatement;
import com.arcadedb.query.postgresql.ast.CreateTableStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Sequences;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * {@code ALTER TABLE} (fork issue #230) on a table that {@code CREATE TABLE} made: {@code ADD COLUMN} with its {@code NOT NULL}
 * and {@code DEFAULT}, {@code SET} and {@code DROP DEFAULT}, and {@code SET} and {@code DROP NOT NULL}. The rows that exist end up
 * as PostgreSQL leaves them: an added column with a default is written into each of them (one value of the clock for all, a new
 * uuid for each), and one without reads {@code NULL}. The result is in the schema of the engine, so every language sees it.
 * <p>
 * One statement is all or nothing. What the rows say is checked before the schema changes; the rows are then written in one
 * transaction, and a failure takes the schema changes back. In a transaction block the rows are written in the transaction of the
 * block. The schema of the engine is not transactional: its changes are there at once, for other sessions too, and
 * {@link #takeBack} undoes them when the transaction does not commit.
 */
public final class AlterTablePlan {
  /** A column as the statement sees it after the actions before. */
  private static final class State {
    final String             property;
    final CreateTablePlan.Mapped mapped;
    final String             identity;
    boolean                  notNull;

    State(final String property, final CreateTablePlan.Mapped mapped, final String identity, final boolean notNull) {
      this.property = property;
      this.mapped = mapped;
      this.identity = identity;
      this.notNull = notNull;
    }
  }

  private final Database            database;
  private final RelationalCatalog   catalog;
  private final AlterTableStatement statement;
  private final Parameters          parameters;
  private final CreateTablePlan     declarations;
  /** how to take back each change of the schema, the last one first */
  private final Deque<Runnable>     undo             = new ArrayDeque<>();
  /** the counters of serial columns whose default the statement replaced, dropped once the change is final */
  private final List<String>        replacedCounters = new ArrayList<>();

  /** @param parameters the parameters at the time of the statement, which a default of the clock reads */
  public AlterTablePlan(final Database database, final RelationalCatalog catalog, final AlterTableStatement statement,
      final Parameters parameters) {
    this.database = database;
    this.catalog = catalog;
    this.statement = statement;
    this.parameters = parameters;
    this.declarations = new CreateTablePlan(database, catalog, null);
  }

  /** Changes the table; the answer has no column and no row. */
  public PostgreSQLResultSet execute() {
    final String name = declarations.tableName(statement.table(), statement.position());
    final Table table = catalog.findTable(CreateTablePlan.identifier(statement.table().get(statement.table().size() - 1)));
    if (table == null) {
      if (statement.ifExists())
        return answer("ALTER TABLE " + name + ": no such table, nothing to do");
      throw new PostgreSQLUndefinedTableException(name);
    }
    final DocumentType type = table.documentType();
    final TableDefinition definition = TableDefinition.of(type);
    if (definition == null)
      throw madeAnotherWay(table);

    // every action is read, and what the rows say about it checked, before anything changes
    final Map<String, CreateTablePlan.Spec> added = new LinkedHashMap<>();
    final Map<String, State> changed = new LinkedHashMap<>();
    final Map<String, CreateTablePlan.Spec> defaults = new LinkedHashMap<>();
    final Map<String, Boolean> notNull = new LinkedHashMap<>();
    for (final AlterTableStatement.Action action : statement.actions())
      switch (action) {
      case AlterTableStatement.AddColumn add -> readColumn(table, add, added);
      case AlterTableStatement.SetDefault set -> {
        final State column = column(table, set.column(), added, changed);
        if (column.identity != null)
          throw identityColumn(table, set.column());
        final CreateTablePlan.Spec spec = new CreateTablePlan.Spec(column.property, statement.position(), column.mapped);
        spec.literal = set.value() instanceof Expression.NullLiteral ? null : set.value();
        if (spec.literal != null)
          declarations.readDefault(spec);
        final CreateTablePlan.Spec own = added.get(column.property);
        if (own == null)
          defaults.put(column.property, spec);
        else {
          // a column of this statement: PostgreSQL fills the rows with the default of the definition, not with this one
          throw new PostgreSQLFeatureNotSupportedException("SET DEFAULT on a column that the same statement adds is not"
              + " supported by the postgresql language (" + statement.position() + ")",
              "Write the DEFAULT in the ADD COLUMN, or run a second ALTER TABLE");
        }
      }
      case AlterTableStatement.SetNotNull set -> {
        final State column = column(table, set.column(), added, changed);
        if (!set.notNull()) {
          if (table.primaryKey() != null && table.primaryKey().columns().contains(set.column().value()))
            throw new PostgreSQLEvaluationException("42P16",
                "column \"" + set.column().value() + "\" is in a primary key");
          if (column.identity != null)
            throw identityColumn(table, set.column());
        }
        column.notNull = set.notNull();
        final CreateTablePlan.Spec own = added.get(column.property);
        if (own == null)
          notNull.put(column.property, set.notNull());
        else
          own.notNull = set.notNull();
      }
      }

    final Set<String> required = new LinkedHashSet<>();
    notNull.forEach((property, value) -> {
      if (value)
        required.add(property);
    });
    checkRows(table, type, added, required);
    change(table, type, definition, added, defaults, notNull, required);
    return answer("ALTER TABLE " + table.name());
  }

  // ------------------------------------------------------------------------------------------------ reading

  private void readColumn(final Table table, final AlterTableStatement.AddColumn add,
      final Map<String, CreateTablePlan.Spec> added) {
    final CreateTableStatement.Column column = add.column();
    final String name = column.name().value();
    if (CreateTablePlan.SYSTEM_COLUMNS.contains(name))
      throw new PostgreSQLEvaluationException("42701", "column name \"" + name + "\" conflicts with a system column name");
    if (name.startsWith("@") || Column.RID.equals(name))
      throw refused("A column named " + name + " added to a table", column.position(), "Give the column another name");
    final Column existing = table.findColumn(CreateTablePlan.identifier(column.name()));
    if (added.containsKey(name) || table.documentType().getPolymorphicPropertyIfExists(name) != null
        || existing != null && existing.kind() != Column.Kind.IDENTITY) {
      if (add.ifNotExists())
        return;
      throw PostgreSQLEvaluationException.duplicateColumn(name, table.name());
    }
    if (CreateTablePlan.serialType(column.type()) != null)
      throw refused("ADD COLUMN of a serial column", column.position(), "Declare the serial column in CREATE TABLE");
    final CreateTablePlan.Spec spec = new CreateTablePlan.Spec(name, column.position(), CreateTablePlan.type(column.type()));
    boolean defaultSeen = false;
    for (final CreateTableStatement.Constraint constraint : column.constraints())
      switch (constraint.kind()) {
      case NOT_NULL, NULL -> {
        final boolean required = constraint.kind() == CreateTableStatement.Kind.NOT_NULL;
        if (required ? spec.nullable : spec.notNull)
          throw new PostgreSQLEvaluationException("42601",
              "conflicting NULL/NOT NULL declarations for column \"" + name + "\" of table \"" + table.name() + "\"");
        spec.notNull |= required;
        spec.nullable |= !required;
      }
      case DEFAULT -> {
        if (defaultSeen)
          throw new PostgreSQLEvaluationException("42601",
              "multiple default values specified for column \"" + name + "\" of table \"" + table.name() + "\"");
        defaultSeen = true;
        spec.literal = constraint.value() instanceof Expression.NullLiteral ? null : constraint.value();
      }
      case UNIQUE -> throw refused("ADD COLUMN with UNIQUE", constraint.position(),
          "Add the column without UNIQUE, then write CREATE UNIQUE INDEX ON " + table.name() + " (" + name + ")");
      default -> throw refused("ADD COLUMN with a primary key, a check, a reference, or an identity", constraint.position(),
          "Declare the column with its constraint in CREATE TABLE: ALTER TABLE adds a column with NOT NULL and DEFAULT only");
      }
    if (spec.literal != null)
      declarations.readDefault(spec);
    added.put(name, spec);
  }

  /** The column that an action names: one that the statement adds, else one of the table. */
  private State column(final Table table, final Name name, final Map<String, CreateTablePlan.Spec> added,
      final Map<String, State> changed) {
    final CreateTablePlan.Spec own = added.get(name.value());
    if (own != null)
      return changed.computeIfAbsent(own.name, property -> new State(property, own.mapped, null, own.notNull));
    final Column column = table.findColumn(CreateTablePlan.identifier(name));
    if (column == null)
      throw new PostgreSQLEvaluationException("42703",
          "column \"" + name.value() + "\" of relation \"" + table.name() + "\" does not exist");
    if (column.kind() != Column.Kind.PROPERTY)
      throw refused("A change of the column " + column.name() + ", which the engine fills,", statement.position(),
          "Leave the statement out");
    final Property property = table.documentType().getPolymorphicPropertyIfExists(column.property());
    return changed.computeIfAbsent(column.property(), key -> new State(key,
        new CreateTablePlan.Mapped(column.type(), property.getType(), property.getOfType(), 0, column.modifier()),
        column.identityGeneration(), !column.nullable()));
  }

  // ------------------------------------------------------------------------------------------------ the rows

  /** What the rows say about the statement, before the schema changes. */
  private void checkRows(final Table table, final DocumentType type, final Map<String, CreateTablePlan.Spec> added,
      final Set<String> required) {
    if (added.isEmpty() && required.isEmpty())
      return;
    database.transaction(() -> {
      for (final Iterator<Record> records = database.iterateType(type.getName(), true); records.hasNext(); ) {
        final Document record = records.next().asDocument(true);
        for (final CreateTablePlan.Spec spec : added.values()) {
          if (record.has(spec.name))
            // a property that the table did not declare: adding the column would show it, or write over it
            throw refused("ADD COLUMN " + spec.name + ", where a record already holds a property of that name that the table"
                + " does not declare,", spec.position,
                "Remove the property first, in the sql language: {sql}UPDATE `" + type.getName() + "` REMOVE `" + spec.name
                    + "`");
          if (spec.notNull && spec.literal == null)
            throw containsNull(table, spec.name);
        }
        for (final String property : required)
          if (!added.containsKey(property) && record.get(property) == null)
            throw containsNull(table, property);
      }
    });
  }

  /**
   * Takes back what the statement changed in the schema, the last change first: after a failure of the statement itself, or when
   * its transaction did not commit, which is also when another statement may have changed the same since. What cannot be taken
   * back is logged and the rest still is.
   */
  public void takeBack() {
    while (!undo.isEmpty())
      try {
        undo.pop().run();
      } catch (final RuntimeException e) {
        LogManager.instance().log(this, Level.WARNING, "Cannot take back a change of ALTER TABLE", e);
      }
  }

  /** Once the change is final, because it was made outside a transaction or its transaction committed. */
  public void finish() {
    undo.clear();
    Sequences.drop(database, replacedCounters);
    replacedCounters.clear();
  }

  private void change(final Table table, final DocumentType type, final TableDefinition definition,
      final Map<String, CreateTablePlan.Spec> added, final Map<String, CreateTablePlan.Spec> defaults,
      final Map<String, Boolean> notNull, final Set<String> required) {
    // an undo may run after the transaction has ended: it looks the type and the property up again by name
    final String typeName = type.getName();
    final Supplier<DocumentType> now = () -> database.getSchema().getType(typeName);
    boolean done = false;
    try {
      final Map<CreateTablePlan.Spec, Property> created = new LinkedHashMap<>();
      for (final CreateTablePlan.Spec spec : added.values()) {
        // NOT NULL is set once every row has its value
        final boolean mandatory = spec.notNull;
        spec.notNull = false;
        final Property property = declarations.createProperty(type, spec);
        spec.notNull = mandatory;
        created.put(spec, property);
        undo.push(() -> now.get().dropProperty(spec.name));
      }
      for (final CreateTablePlan.Spec spec : defaults.values()) {
        final Property property = type.getPolymorphicPropertyIfExists(spec.name);
        final Object before = property.getDefaultValueDefinition();
        final String counter = Sequences.keyOf(before);
        property.setDefaultValue(null);
        undo.push(() -> now.get().getPolymorphicPropertyIfExists(spec.name).setDefaultValue(before));
        CreateTablePlan.setDefault(property, spec);
        if (counter != null)
          replacedCounters.add(counter);
      }

      final Object before = type.getCustomValue(TableDefinition.CUSTOM_KEY);
      type.setCustomValue(TableDefinition.CUSTOM_KEY, changed(definition, added, defaults).toJson());
      undo.push(() -> now.get().setCustomValue(TableDefinition.CUSTOM_KEY, before));

      for (final Map.Entry<String, Boolean> each : notNull.entrySet()) {
        final Property property = type.getPolymorphicPropertyIfExists(each.getKey());
        final boolean wasMandatory = property.isMandatory();
        final boolean wasNotNull = property.isNotNull();
        property.setMandatory(each.getValue());
        property.setNotNull(each.getValue());
        undo.push(() -> {
          final Property then = now.get().getPolymorphicPropertyIfExists(each.getKey());
          then.setMandatory(wasMandatory);
          then.setNotNull(wasNotNull);
        });
      }

      write(table, type, added, required);

      created.forEach((spec, property) -> {
        if (spec.notNull) {
          property.setMandatory(true);
          property.setNotNull(true);
        }
      });
      done = true;
    } finally {
      if (!done)
        takeBack();
    }
  }

  /** Writes the default of each added column into the rows that exist, and looks again for the rows without a required value. */
  private void write(final Table table, final DocumentType type, final Map<String, CreateTablePlan.Spec> added,
      final Set<String> required) {
    final List<CreateTablePlan.Spec> filled = added.values().stream().filter(spec -> spec.literal != null).toList();
    if (filled.isEmpty() && required.isEmpty())
      return;
    final ExpressionCompiler compiler = new ExpressionCompiler(new RelationScope(List.of(), catalog), parameters);
    final List<Evaluable> values = filled.stream().map(spec -> compiler.compile(spec.literal)).toList();
    try {
      database.transaction(() -> {
        for (final Iterator<Record> records = database.iterateType(type.getName(), true); records.hasNext(); ) {
          final Document record = records.next().asDocument(true);
          for (final String property : required)
            if (!added.containsKey(property) && record.get(property) == null)
              throw containsNull(table, property);
          if (filled.isEmpty())
            continue;
          final MutableDocument changed = record.modify();
          for (int i = 0; i < filled.size(); i++) {
            final CreateTablePlan.Spec spec = filled.get(i);
            changed.set(spec.name, stored(table, spec, values.get(i)));
          }
          changed.save();
        }
      });
    } catch (final RuntimeException e) {
      throw WriteErrors.translate(e);
    }
  }

  /** One value of a default, as a record holds it. */
  private Object stored(final Table table, final CreateTablePlan.Spec spec, final Evaluable value) {
    final ColumnType type = spec.mapped.type();
    final Column column = Column.declaration(spec.name, type).withModifier(spec.mapped.modifier());
    final Object converted = Assignments.convert(value.eval(new Object[0]), value.type(), column);
    if (converted instanceof OffsetDateTime instant)
      return instant.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    return StoredValues.applies(column) ? StoredValues.of(database, table, column, converted) : converted;
  }

  // ------------------------------------------------------------------------------------------------ the definition

  private static TableDefinition changed(final TableDefinition definition, final Map<String, CreateTablePlan.Spec> added,
      final Map<String, CreateTablePlan.Spec> defaults) {
    final Set<String> columns = new LinkedHashSet<>(definition.columns());
    final Set<String> text = new LinkedHashSet<>(definition.textColumns());
    final Set<String> instants = new LinkedHashSet<>(definition.timestamptzColumns());
    final Set<String> uuids = new LinkedHashSet<>(definition.uuidColumns());
    final Map<String, String> texts = new LinkedHashMap<>(definition.defaults());
    final Map<String, String> identity = new LinkedHashMap<>(definition.identity());
    for (final CreateTablePlan.Spec spec : added.values()) {
      // what a column of this name that another language dropped left behind says nothing about the new one
      columns.remove(spec.name);
      text.remove(spec.name);
      instants.remove(spec.name);
      uuids.remove(spec.name);
      texts.remove(spec.name);
      identity.remove(spec.name);
      columns.add(spec.name);
      if (spec.mapped.type() == ColumnType.TEXT)
        text.add(spec.name);
      else if (spec.mapped.type() == ColumnType.UUID)
        uuids.add(spec.name);
      else if (spec.mapped.type() == ColumnType.TIMESTAMPTZ)
        instants.add(spec.name);
      if (spec.hasDefault)
        texts.put(spec.name, spec.defaultText);
    }
    defaults.forEach((name, spec) -> {
      if (spec.hasDefault)
        texts.put(name, spec.defaultText);
      else
        texts.remove(name);
    });
    return new TableDefinition(List.copyOf(columns), text, definition.primaryKey(), definition.uniqueKeys(), texts,
        definition.foreignKeys(), instants, identity, uuids);
  }

  // ------------------------------------------------------------------------------------------------ errors

  private static PostgreSQLResultSet answer(final String step) {
    return new PostgreSQLResultSet(List.of(), new PostgreSQLResultSet.Plan(List.of(step))).rowless().withAffectedRows(0);
  }

  private static PostgreSQLEvaluationException containsNull(final Table table, final String column) {
    return new PostgreSQLEvaluationException("23502",
        "column \"" + column + "\" of relation \"" + table.name() + "\" contains null values");
  }

  private static PostgreSQLEvaluationException identityColumn(final Table table, final Name column) {
    return new PostgreSQLEvaluationException("42601",
        "column \"" + column.value() + "\" of relation \"" + table.name() + "\" is an identity column");
  }

  private static PostgreSQLFeatureNotSupportedException refused(final String what, final SourcePosition position,
      final String hint) {
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language (" + position + ")",
        hint);
  }

  /** A type that another language made has no definition to change: the statement of {@code sql} that does the same. */
  private PostgreSQLFeatureNotSupportedException madeAnotherWay(final Table table) {
    String hint = "Change the type in the sql language, with CREATE PROPERTY or ALTER PROPERTY";
    if (statement.actions().get(0) instanceof AlterTableStatement.AddColumn add) {
      final CreateTablePlan.Mapped mapped = CreateTablePlan.type(add.column().type());
      hint = "Add the column in the sql language: {sql}CREATE PROPERTY `" + table.documentType().getName() + "`.`"
          + add.column().name().value() + "` " + mapped.engine().name() + (mapped.ofType() == null ? "" : " OF " + mapped.ofType());
    }
    return refused("ALTER TABLE on a type that CREATE TABLE of this language did not make", statement.position(), hint);
  }
}
