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
import com.arcadedb.exception.AlreadyExistsException;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.index.TypeIndex;
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
import com.arcadedb.query.postgresql.catalog.DeclaredIndexes;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Sequences;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.CheckConstraint;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.ForeignKey;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
    if (TableDefinition.of(table.documentType()) == null)
      throw madeAnotherWay(table);

    boolean done = false;
    try {
      if (statement.actions().stream().anyMatch(AlterTablePlan::needsOneTransaction))
        // rows change or are checked after the schema has changed, so the statement is one transaction, which a failure rolls
        // back. It is not run again, because it changes the schema
        database.transaction(() -> apply(table), true, 1);
      else
        apply(table);
      done = true;
    } catch (final RuntimeException e) {
      throw WriteErrors.translate(e);
    } finally {
      if (!done)
        takeBack();
    }
    return answer("ALTER TABLE " + table.name());
  }

  /** Whether the action drops or renames a column, or adds a constraint. */
  private static boolean needsOneTransaction(final AlterTableStatement.Action action) {
    return switch (action) {
      case AlterTableStatement.DropColumn ignored -> true;
      case AlterTableStatement.RenameColumn ignored -> true;
      case AlterTableStatement.AddConstraint ignored -> true;
      case AlterTableStatement.AddColumn add -> add.column().constraints().stream()
          .anyMatch(constraint -> constraint.kind() != CreateTableStatement.Kind.NOT_NULL
              && constraint.kind() != CreateTableStatement.Kind.NULL && constraint.kind() != CreateTableStatement.Kind.DEFAULT);
      case AlterTableStatement.SetDefault ignored -> false;
      case AlterTableStatement.SetNotNull ignored -> false;
    };
  }

  private void apply(final Table found) {
    final String typeName = found.documentType().getName();
    // as PostgreSQL does, the columns are dropped first
    boolean reshaped = false;
    for (final AlterTableStatement.Action action : statement.actions())
      if (action instanceof AlterTableStatement.DropColumn drop) {
        dropColumn(typeName, drop);
        reshaped = true;
      } else if (action instanceof AlterTableStatement.RenameColumn rename) {
        renameColumn(typeName, rename);
        reshaped = true;
      }
    if (reshaped && statement.actions().stream().allMatch(action -> action instanceof AlterTableStatement.DropColumn
        || action instanceof AlterTableStatement.RenameColumn))
      return;
    final Table table = reshaped ? current(typeName) : found;
    final DocumentType type = table.documentType();
    final TableDefinition definition = TableDefinition.of(type);

    // every other action is read, and what the rows say about it checked, before it changes anything
    final Map<String, CreateTablePlan.Spec> added = new LinkedHashMap<>();
    final Map<String, State> changed = new LinkedHashMap<>();
    final Map<String, CreateTablePlan.Spec> defaults = new LinkedHashMap<>();
    final Map<String, Boolean> notNull = new LinkedHashMap<>();
    final List<CreateTableStatement.Constraint> constraints = new ArrayList<>();
    for (final AlterTableStatement.Action action : statement.actions())
      switch (action) {
      case AlterTableStatement.DropColumn ignored -> {
        // done above
      }
      case AlterTableStatement.RenameColumn ignored -> {
        // done above
      }
      case AlterTableStatement.AddColumn add -> readColumn(table, add, added, constraints);
      case AlterTableStatement.AddConstraint add -> constraints.add(add.constraint());
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
    // as PostgreSQL does: the keys first, so that a foreign key of the statement can refer to one of them
    constraints.sort(Comparator.comparingInt((CreateTableStatement.Constraint each) -> switch (each.kind()) {
      case PRIMARY_KEY -> 0;
      case UNIQUE -> 1;
      case CHECK -> 2;
      default -> 3;
    }));
    change(table, type, definition, added, defaults, notNull, required, constraints);
  }

  // ------------------------------------------------------------------------------------------------ reading

  private void readColumn(final Table table, final AlterTableStatement.AddColumn add,
      final Map<String, CreateTablePlan.Spec> added, final List<CreateTableStatement.Constraint> constraints) {
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
      // a constraint of the column is added as the constraint of the table that says the same
      case UNIQUE, PRIMARY_KEY, CHECK, FOREIGN_KEY -> constraints.add(new CreateTableStatement.Constraint(
          constraint.position(), constraint.kind(), constraint.name(),
          constraint.kind() == CreateTableStatement.Kind.CHECK ? List.of() : List.of(column.name()), constraint.value(),
          constraint.reference(), constraint.text()));
      case IDENTITY_ALWAYS, IDENTITY_BY_DEFAULT -> throw refused("ADD COLUMN of an identity column", constraint.position(),
          "Declare the identity column in CREATE TABLE");
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
      final Map<String, Boolean> notNull, final Set<String> required,
      final List<CreateTableStatement.Constraint> constraints) {
    // an undo may run after the transaction has ended: it looks the type and the property up again by name
    final String typeName = type.getName();
    final Supplier<DocumentType> now = () -> database.getSchema().getType(typeName);
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

    setCustomValue(typeName, TableDefinition.CUSTOM_KEY, changed(definition, added, defaults).toJson());

    for (final Map.Entry<String, Boolean> each : notNull.entrySet())
      setNotNull(typeName, each.getKey(), each.getValue());

    write(table, type, added, required);
    created.forEach((spec, property) -> {
      if (spec.notNull) {
        property.setMandatory(true);
        property.setNotNull(true);
      }
    });
    for (final CreateTableStatement.Constraint constraint : constraints)
      add(typeName, constraint);
  }

  /** Sets or removes {@code NOT NULL} on a property, and keeps how to take that back. */
  private void setNotNull(final String typeName, final String name, final boolean required) {
    final Property property = database.getSchema().getType(typeName).getPolymorphicPropertyIfExists(name);
    final boolean wasMandatory = property.isMandatory();
    final boolean wasNotNull = property.isNotNull();
    property.setMandatory(required);
    property.setNotNull(required);
    undo.push(() -> {
      final Property then = database.getSchema().getType(typeName).getPolymorphicPropertyIfExists(name);
      then.setMandatory(wasMandatory);
      then.setNotNull(wasNotNull);
    });
  }

  private Table current(final String typeName) {
    return RelationalCatalog.of(database).findTable(Identifier.quoted(typeName));
  }

  // ------------------------------------------------------------------------------------------------ columns that go or change

  /** What depends on a column from outside its table's own definition: keys that refer to it, by the type that declares them. */
  private record Referring(String declaring, ForeignKey key) {
  }

  /** The column that a drop or a rename names, which has to be a column that the type itself declares. */
  private Column ownColumn(final Table table, final Name name, final String missing) {
    final Column column = table.findColumn(CreateTablePlan.identifier(name));
    if (column == null)
      throw new PostgreSQLEvaluationException("42703", missing);
    if (column.kind() != Column.Kind.PROPERTY || table.documentType().getPropertyIfExists(column.property()) == null
        || !table.documentType().getSubTypes().isEmpty())
      throw refused("DROP or RENAME of the column " + column.name() + ", which the engine fills or another type shares,",
          statement.position(), "Leave the column in place");
    return column;
  }

  /**
   * {@code DROP COLUMN}: as PostgreSQL does, the keys, checks and indexes that involve the column go with it, and what refers to it
   * from elsewhere needs {@code CASCADE}. The value goes out of every record.
   */
  private void dropColumn(final String typeName, final AlterTableStatement.DropColumn drop) {
    final Table table = current(typeName);
    if (drop.ifExists() && table.findColumn(CreateTablePlan.identifier(drop.column())) == null)
      return;
    final Column column = ownColumn(table, drop.column(),
        "column \"" + drop.column().value() + "\" of relation \"" + table.name() + "\" does not exist");
    final String property = column.property();
    final DocumentType type = table.documentType();
    final TableDefinition definition = TableDefinition.of(type);
    if (!(type instanceof EdgeType) && type.getPropertyNames().size() <= 1)
      throw refused("DROP COLUMN of the only column of a table", statement.position(), "Write DROP TABLE " + table.name());

    final List<Referring> referring = referring(typeName, property);
    final List<String> links = links(typeName, property);
    if (!links.isEmpty() || !referring.isEmpty() && !drop.cascade()) {
      final List<String> found = new ArrayList<>();
      referring.forEach(each -> found.add("constraint " + each.key().name() + " on table " + each.declaring()));
      links.forEach(link -> found.add("link table " + link));
      if (drop.cascade())
        throw refused("DROP COLUMN CASCADE of a column that a link table refers to, whose rows cannot stay without their ends,",
            statement.position(), "Drop the link table first: DROP TABLE " + String.join(", ", links));
      throw new PostgreSQLEvaluationException("2BP01", "cannot drop column " + column.name() + " of table " + table.name()
          + " because other objects depend on it: " + String.join("; ", found) + ". Hint: "
          + (links.isEmpty() ? "Use DROP ... CASCADE to drop the dependent objects too."
          : "Drop the link table first: DROP TABLE " + String.join(", ", links)));
    }
    referring.forEach(each -> removeKey(each.declaring(), each.key().name()));
    for (final ForeignKey key : foreignKeys(type))
      if (key.columns().contains(property))
        removeKey(typeName, key.name());
    final List<CheckConstraint> checks = checks(type);
    final List<CheckConstraint> kept = checks.stream().filter(check -> !reads(type, check, property)).toList();
    if (kept.size() != checks.size())
      setCustomValue(typeName, CheckConstraint.CUSTOM_KEY, kept.isEmpty() ? null : CheckConstraint.toJson(kept));

    final Map<String, DeclaredIndexes.Index> declared = DeclaredIndexes.of(type);
    for (final IndexSnapshot index : indexesOf(type, property))
      drop(typeName, index);
    if (declared.values().removeIf(index -> index.columns().contains(property)))
      declare(typeName, declared);

    final Map<String, String> defaults = new LinkedHashMap<>(definition.defaults());
    defaults.remove(property);
    final Map<String, String> identity = new LinkedHashMap<>(definition.identity());
    identity.remove(property);
    final Table.Key primary = definition.primaryKey() != null && definition.primaryKey().columns().contains(property) ? null
        : definition.primaryKey();
    setCustomValue(typeName, TableDefinition.CUSTOM_KEY, new TableDefinition(without(definition.columns(), property),
        new LinkedHashSet<>(without(definition.textColumns(), property)), primary,
        definition.uniqueKeys().stream().filter(key -> !key.columns().contains(property)).toList(), defaults,
        definition.foreignKeys(), new LinkedHashSet<>(without(definition.timestamptzColumns(), property)), identity,
        new LinkedHashSet<>(without(definition.uuidColumns(), property))).toJson());

    final PropertySnapshot snapshot = PropertySnapshot.of(type.getPropertyIfExists(property));
    final String counter = Sequences.keyOf(snapshot.defaultDefinition());
    if (counter != null)
      replacedCounters.add(counter);
    type.dropProperty(property);
    undo.push(() -> snapshot.create(database.getSchema().getType(typeName), property));

    for (final Iterator<Record> records = database.iterateType(typeName, true); records.hasNext(); ) {
      final Document record = records.next().asDocument(true);
      if (record.has(property)) {
        final MutableDocument changed = record.modify();
        changed.remove(property);
        changed.save();
      }
    }
  }

  /**
   * {@code RENAME COLUMN}: the engine renames no property, so a new one takes the values, record by record, and the indexes, keys,
   * checks and links that name the column follow it under their own names.
   */
  private void renameColumn(final String typeName, final AlterTableStatement.RenameColumn rename) {
    final Table table = current(typeName);
    final Column column = ownColumn(table, rename.from(), "column \"" + rename.from().value() + "\" does not exist");
    final String from = column.property();
    final String to = rename.to().value();
    final DocumentType type = table.documentType();
    if (CreateTablePlan.SYSTEM_COLUMNS.contains(to))
      throw new PostgreSQLEvaluationException("42701", "column name \"" + to + "\" conflicts with a system column name");
    if (to.startsWith("@") || Column.RID.equals(to))
      throw refused("A column renamed to " + to, statement.position(), "Give the column another name");
    if (type.getPolymorphicPropertyIfExists(to) != null || table.findColumn(CreateTablePlan.identifier(rename.to())) != null
        && !table.findColumn(CreateTablePlan.identifier(rename.to())).property().equals(from))
      throw PostgreSQLEvaluationException.duplicateColumn(to, table.name());
    if (to.equals(from))
      throw PostgreSQLEvaluationException.duplicateColumn(to, table.name());
    for (final Iterator<Record> records = database.iterateType(typeName, true); records.hasNext(); )
      if (records.next().asDocument(true).has(to))
        throw refused("RENAME COLUMN to " + to + ", where a record already holds a property of that name that the table does"
            + " not declare,", statement.position(),
            "Remove the property first, in the sql language: {sql}UPDATE `" + typeName + "` REMOVE `" + to + "`");

    final TableDefinition definition = TableDefinition.of(type);
    // the checks and keys that name the column are taken off while the rows change, and put back under the new name
    final List<CheckConstraint> checks = checks(type);
    final List<CheckConstraint> renamedChecks = checks.stream().map(check -> reads(type, check, from) ?
        new CheckConstraint(check.name(), CheckExpression.renamed(check.expression(), from, to)) : check).toList();
    if (!checks.isEmpty())
      setCustomValue(typeName, CheckConstraint.CUSTOM_KEY, null);
    final Map<String, List<ForeignKey>> keys = new LinkedHashMap<>();
    for (final Referring each : referring(typeName, from))
      keys.putIfAbsent(each.declaring(), foreignKeys(database.getSchema().getType(each.declaring())));
    if (foreignKeys(type).stream().anyMatch(key -> key.columns().contains(from)))
      keys.putIfAbsent(typeName, foreignKeys(type));
    keys.keySet().forEach(declaring -> setCustomValue(declaring, ForeignKey.CUSTOM_KEY, null));
    final Map<String, DeclaredIndexes.Index> declared = DeclaredIndexes.of(type);
    final List<IndexSnapshot> indexes = indexesOf(type, from);
    for (final IndexSnapshot index : indexes)
      drop(typeName, index);

    final PropertySnapshot snapshot = PropertySnapshot.of(type.getPropertyIfExists(from));
    snapshot.withoutNotNull().create(type, to);
    undo.push(() -> database.getSchema().getType(typeName).dropProperty(to));
    setNotNull(typeName, from, false);
    for (final Iterator<Record> records = database.iterateType(typeName, true); records.hasNext(); ) {
      final Document record = records.next().asDocument(true);
      if (record.has(from)) {
        final MutableDocument changed = record.modify();
        changed.set(to, record.get(from));
        changed.remove(from);
        changed.save();
      }
    }
    database.getSchema().getType(typeName).dropProperty(from);
    undo.push(() -> snapshot.withoutNotNull().create(database.getSchema().getType(typeName), from));
    if (snapshot.mandatory() || snapshot.notNull()) {
      final Property made = database.getSchema().getType(typeName).getPropertyIfExists(to);
      made.setMandatory(snapshot.mandatory());
      made.setNotNull(snapshot.notNull());
    }

    final Map<String, String> defaults = new LinkedHashMap<>();
    definition.defaults().forEach((name, text) -> defaults.put(name.equals(from) ? to : name, text));
    final Map<String, String> identity = new LinkedHashMap<>();
    definition.identity().forEach((name, generation) -> identity.put(name.equals(from) ? to : name, generation));
    final Table.Key primary = definition.primaryKey() == null ? null : renamed(definition.primaryKey(), from, to);
    setCustomValue(typeName, TableDefinition.CUSTOM_KEY, new TableDefinition(renamed(definition.columns(), from, to),
        new LinkedHashSet<>(renamed(definition.textColumns(), from, to)), primary,
        definition.uniqueKeys().stream().map(key -> renamed(key, from, to)).toList(), defaults, definition.foreignKeys(),
        new LinkedHashSet<>(renamed(definition.timestamptzColumns(), from, to)), identity,
        new LinkedHashSet<>(renamed(definition.uuidColumns(), from, to))).toJson());

    for (final IndexSnapshot index : indexes) {
      final IndexSnapshot next = index.renamed(from, to);
      undo.push(() -> next.dropFrom(database.getSchema(), typeName));
      next.create(database.getSchema(), typeName);
    }
    if (!declared.isEmpty()) {
      declared.replaceAll((name, index) -> new DeclaredIndexes.Index(renamed(index.columns(), from, to), index.unique(),
          index.engine()));
      declare(typeName, declared);
    }
    keys.forEach((declaring, before) -> setCustomValue(declaring, ForeignKey.CUSTOM_KEY, ForeignKey.toJson(before.stream()
        .map(key -> new ForeignKey(key.name(),
            declaring.equals(typeName) ? renamed(key.columns(), from, to) : key.columns(), key.referencedType(),
            key.referencedType().equals(typeName) ? renamed(key.referencedColumns(), from, to) : key.referencedColumns(),
            key.onDelete(), key.onUpdate()))
        .toList())));
    if (!checks.isEmpty())
      setCustomValue(typeName, CheckConstraint.CUSTOM_KEY, CheckConstraint.toJson(renamedChecks));
    for (final DocumentType link : database.getSchema().getTypes()) {
      if (typeName.equals(link.getCustomValue(EdgeEndpoints.SOURCE_KEY))
          && from.equals(link.getCustomValue(EdgeEndpoints.SOURCE_COLUMN_KEY)))
        setCustomValue(link.getName(), EdgeEndpoints.SOURCE_COLUMN_KEY, to);
      if (typeName.equals(link.getCustomValue(EdgeEndpoints.TARGET_KEY))
          && from.equals(link.getCustomValue(EdgeEndpoints.TARGET_COLUMN_KEY)))
        setCustomValue(link.getName(), EdgeEndpoints.TARGET_COLUMN_KEY, to);
    }
  }

  /** The foreign keys, of the type itself too, that refer to the property of the type. */
  private List<Referring> referring(final String typeName, final String property) {
    final List<Referring> found = new ArrayList<>();
    for (final DocumentType each : database.getSchema().getTypes())
      for (final ForeignKey key : foreignKeys(each))
        if (key.referencedType().equals(typeName) && key.referencedColumns().contains(property))
          found.add(new Referring(each.getName(), key));
    return found;
  }

  /** The link tables with an end that refers to the property of the type. */
  private List<String> links(final String typeName, final String property) {
    final List<String> found = new ArrayList<>();
    for (final DocumentType each : database.getSchema().getTypes())
      if (typeName.equals(each.getCustomValue(EdgeEndpoints.SOURCE_KEY))
          && property.equals(each.getCustomValue(EdgeEndpoints.SOURCE_COLUMN_KEY))
          || typeName.equals(each.getCustomValue(EdgeEndpoints.TARGET_KEY))
          && property.equals(each.getCustomValue(EdgeEndpoints.TARGET_COLUMN_KEY)))
        found.add(each.getName());
    return found;
  }

  private static List<ForeignKey> foreignKeys(final DocumentType type) {
    final Object value = type.getCustomValue(ForeignKey.CUSTOM_KEY);
    return value == null ? List.of() : ForeignKey.fromJson(value.toString());
  }

  private static List<CheckConstraint> checks(final DocumentType type) {
    final Object value = type.getCustomValue(CheckConstraint.CUSTOM_KEY);
    return value == null ? List.of() : CheckConstraint.fromJson(value.toString());
  }

  private static boolean reads(final DocumentType type, final CheckConstraint check, final String property) {
    return CheckExpression.of(type, type, check.expression()).columnNames().contains(property);
  }

  private void removeKey(final String declaring, final String name) {
    final List<ForeignKey> left = foreignKeys(database.getSchema().getType(declaring)).stream()
        .filter(key -> !key.name().equals(name)).toList();
    setCustomValue(declaring, ForeignKey.CUSTOM_KEY, left.isEmpty() ? null : ForeignKey.toJson(left));
  }

  private static List<IndexSnapshot> indexesOf(final DocumentType type, final String property) {
    return type.getAllIndexes(false).stream().filter(index -> index.getPropertyNames().contains(property))
        .map(IndexSnapshot::of).toList();
  }

  private void drop(final String typeName, final IndexSnapshot index) {
    database.getSchema().dropIndex(index.name());
    undo.push(() -> index.create(database.getSchema(), typeName));
  }

  private void declare(final String typeName, final Map<String, DeclaredIndexes.Index> indexes) {
    final DocumentType type = database.getSchema().getType(typeName);
    final Object before = type.getCustomValue(DeclaredIndexes.CUSTOM_KEY);
    DeclaredIndexes.write(type, indexes);
    undo.push(() -> database.getSchema().getType(typeName).setCustomValue(DeclaredIndexes.CUSTOM_KEY, before));
  }

  private static List<String> without(final Collection<String> names, final String name) {
    return names.stream().filter(each -> !each.equals(name)).toList();
  }

  private static List<String> renamed(final Collection<String> names, final String from, final String to) {
    return names.stream().map(each -> each.equals(from) ? to : each).toList();
  }

  private static Table.Key renamed(final Table.Key key, final String from, final String to) {
    return new Table.Key(key.name(), renamed(key.columns(), from, to));
  }

  // ------------------------------------------------------------------------------------------------ constraints

  /**
   * Adds one constraint: it is registered first, so that no write of another session slips past it, and the rows are then read
   * against it. The catalog is built again for each, with the columns and keys that the statement has added by then.
   */
  private void add(final String typeName, final CreateTableStatement.Constraint constraint) {
    final RelationalCatalog current = RelationalCatalog.of(database);
    final Table table = current.findTable(Identifier.quoted(typeName));
    switch (constraint.kind()) {
    case PRIMARY_KEY, UNIQUE -> addKey(table, constraint);
    case CHECK -> addCheck(table, constraint);
    case FOREIGN_KEY -> addForeignKey(current, table, constraint);
    default -> throw new IllegalStateException("Not a constraint of a table: " + constraint.kind());
    }
  }

  /** The columns of a key, as the properties of the type: each a column of the table, written once. */
  private List<Column> keyColumns(final Table table, final CreateTableStatement.Constraint constraint, final String missing) {
    final List<Column> columns = new ArrayList<>();
    for (final Name name : constraint.columns()) {
      final Column column = table.findColumn(CreateTablePlan.identifier(name));
      if (column == null)
        throw new PostgreSQLEvaluationException("42703", "column \"" + name.value() + "\" " + missing);
      if (column.kind() != Column.Kind.PROPERTY)
        throw refused("A constraint over the column " + column.name() + ", which the engine fills,", constraint.position(),
            "Declare the constraint in CREATE TABLE");
      if (columns.contains(column))
        throw new PostgreSQLEvaluationException(constraint.kind() == CreateTableStatement.Kind.FOREIGN_KEY ? "42830" : "42701",
            "column \"" + name.value() + "\" appears twice in a constraint");
      columns.add(column);
    }
    return columns;
  }

  private void addKey(final Table table, final CreateTableStatement.Constraint constraint) {
    final boolean primary = constraint.kind() == CreateTableStatement.Kind.PRIMARY_KEY;
    final DocumentType type = table.documentType();
    final TableDefinition definition = TableDefinition.of(type);
    final List<Column> columns = keyColumns(table, constraint, "named in key does not exist");
    for (final Column column : columns)
      if (!RelationalCatalog.KEY_TYPES.contains(column.type()))
        throw refused("A key over a column of type " + column.type(), constraint.position(),
            "Keep the values unique over columns of other types");
    if (primary && definition.primaryKey() != null)
      throw new PostgreSQLEvaluationException("42P16",
          "multiple primary keys for table \"" + table.name() + "\" are not allowed");
    final List<String> properties = columns.stream().map(Column::property).toList();
    final String name = keyName(table, constraint,
        primary ? table.name() + "_pkey" : table.name() + "_" + String.join("_", properties) + "_key");
    final String typeName = type.getName();

    if (primary) {
      for (final Iterator<Record> records = database.iterateType(typeName, true); records.hasNext(); ) {
        final Document record = records.next().asDocument(true);
        for (final String property : properties)
          if (record.get(property) == null)
            throw containsNull(table, property);
      }
    }
    uniqueIndex(type, name, properties);
    // the index refuses a committed key that is there twice, and this one that the transaction wrote
    final Set<List<Object>> seen = new HashSet<>();
    for (final Iterator<Record> records = database.iterateType(typeName, true); records.hasNext(); ) {
      final Document record = records.next().asDocument(true);
      final List<Object> key = new ArrayList<>(properties.size());
      for (final String property : properties) {
        final Object value = record.get(property);
        if (value == null)
          break;
        key.add(value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value);
      }
      if (key.size() == properties.size() && !seen.add(key))
        throw notUnique(name, null);
    }
    if (primary)
      for (final String property : properties) {
        final Property declared = type.getPolymorphicPropertyIfExists(property);
        final boolean wasMandatory = declared.isMandatory();
        final boolean wasNotNull = declared.isNotNull();
        undo.push(() -> {
          final Property then = database.getSchema().getType(typeName).getPolymorphicPropertyIfExists(property);
          then.setMandatory(wasMandatory);
          then.setNotNull(wasNotNull);
        });
        declared.setMandatory(true);
        declared.setNotNull(true);
      }

    final Table.Key key = new Table.Key(name, properties);
    final List<Table.Key> unique = new ArrayList<>(definition.uniqueKeys());
    if (!primary)
      unique.add(key);
    setCustomValue(typeName, TableDefinition.CUSTOM_KEY,
        new TableDefinition(definition.columns(), definition.textColumns(), primary ? key : definition.primaryKey(), unique,
            definition.defaults(), definition.foreignKeys(), definition.timestamptzColumns(), definition.identity(),
            definition.uuidColumns()).toJson());
  }

  /** The unique index of a key: one that is there, else a new one, in place of an index over the same columns that is not. */
  private void uniqueIndex(final DocumentType type, final String name, final List<String> properties) {
    final String typeName = type.getName();
    final String[] columns = properties.toArray(new String[0]);
    final TypeIndex existing = type.getPolymorphicIndexByProperties(columns);
    if (existing != null) {
      if (existing.isUnique())
        return;
      if (existing.getType() != Schema.INDEX_TYPE.LSM_TREE)
        throw refused("A key over columns that have an index of another kind", statement.position(),
            "Drop the index first: {sql}DROP INDEX `" + existing.getName() + "`");
      undo.push(() -> database.getSchema().getType(typeName).createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, columns));
      database.getSchema().dropIndex(existing.getName());
    }
    // pushed before the index is made: a build that fails can leave a part of it
    undo.push(() -> {
      final TypeIndex made = database.getSchema().getType(typeName).getPolymorphicIndexByProperties(columns);
      if (made != null && made.isUnique())
        database.getSchema().dropIndex(made.getName());
    });
    try {
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, columns);
    } catch (final RuntimeException e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause())
        if (cause instanceof DuplicatedKeyException)
          throw notUnique(name, e);
      throw e;
    }
  }

  private void addCheck(final Table table, final CreateTableStatement.Constraint constraint) {
    final DocumentType type = table.documentType();
    final String typeName = type.getName();
    final Function<String, Column> declared = CheckExpression.declared(type, type);
    final CheckExpression compiled = CheckExpression.compile(table.name(), constraint.value(), column -> {
      if (type instanceof EdgeType && (Column.SOURCE_ID.equals(column) || Column.TARGET_ID.equals(column)))
        throw refused("A CHECK that reads an end of a link table", constraint.position(), "Check a column of the table");
      return declared.apply(column);
    });
    final Object value = type.getCustomValue(CheckConstraint.CUSTOM_KEY);
    final List<CheckConstraint> checks = new ArrayList<>(value == null ? List.of() : CheckConstraint.fromJson(value.toString()));
    final String name = constraintName(table, constraint, table.name()
        + (compiled.columnNames().size() == 1 ? "_" + compiled.columnNames().get(0) : "") + "_check");
    checks.add(new CheckConstraint(name, constraint.text()));
    setCustomValue(typeName, CheckConstraint.CUSTOM_KEY, CheckConstraint.toJson(checks));
    for (final Iterator<Record> records = database.iterateType(typeName, true); records.hasNext(); )
      if (compiled.isViolatedBy(records.next().asDocument(true)))
        throw new PostgreSQLEvaluationException("23514",
            "check constraint \"" + name + "\" of relation \"" + table.name() + "\" is violated by some row");
  }

  private void addForeignKey(final RelationalCatalog current, final Table table,
      final CreateTableStatement.Constraint constraint) {
    final DocumentType type = table.documentType();
    final String typeName = type.getName();
    final List<Column> columns = keyColumns(table, constraint, "referenced in foreign key constraint does not exist");
    final Map<String, CreateTablePlan.Spec> referring = new LinkedHashMap<>();
    for (final Column column : columns) {
      final Property property = type.getPolymorphicPropertyIfExists(column.property());
      referring.put(column.property(), new CreateTablePlan.Spec(column.property(), constraint.position(),
          new CreateTablePlan.Mapped(column.type(), property.getType(), property.getOfType(), 0, column.modifier())));
    }
    final List<String> properties = List.copyOf(referring.keySet());
    final String name = constraintName(table, constraint, table.name() + "_" + String.join("_", properties) + "_fkey");
    final CreateTablePlan keys = new CreateTablePlan(database, current, null);
    final ForeignKey key = keys.checkedKey(table.name(), referring, List.of(),
        new CreateTablePlan.ForeignKey(properties, name, constraint.reference()), name);

    final Object before = type.getCustomValue(ForeignKey.CUSTOM_KEY);
    final Set<String> indexes = new HashSet<>();
    type.getAllIndexes(false).forEach(index -> indexes.add(index.getName()));
    undo.push(() -> {
      final DocumentType then = database.getSchema().getType(typeName);
      then.setCustomValue(ForeignKey.CUSTOM_KEY, before);
      for (final TypeIndex index : new ArrayList<>(then.getAllIndexes(false)))
        if (!indexes.contains(index.getName()))
          database.getSchema().dropIndex(index.getName());
    });
    keys.createForeignKeys(type, List.of(key));

    // the rows that are there: one with a value in every referring column has to refer to a row
    final DocumentType referenced = database.getSchema().getType(key.referencedType());
    final TypeIndex unique = referenced.getAllIndexes(false).stream()
        .filter(index -> index.isUnique() && index.getPropertyNames().size() == key.referencedColumns().size()
            && index.getPropertyNames().containsAll(key.referencedColumns()))
        .findFirst().orElseThrow();
    final String[] order = unique.getPropertyNames().toArray(new String[0]);
    for (final Iterator<Record> records = database.iterateType(typeName, true); records.hasNext(); ) {
      final Document record = records.next().asDocument(true);
      final Object[] values = new Object[order.length];
      boolean complete = true;
      for (int i = 0; i < order.length; i++) {
        values[i] = record.get(key.columns().get(key.referencedColumns().indexOf(order[i])));
        complete &= values[i] != null;
      }
      if (complete && !database.lookupByKey(referenced.getName(), order, values).hasNext())
        throw new PostgreSQLEvaluationException("23503", "insert or update on table \"" + table.name()
            + "\" violates foreign key constraint \"" + name + "\"");
    }
  }

  /** The name of a key, which an index and so a relation has: the one written, else the default with a number when it is taken. */
  private String keyName(final Table table, final CreateTableStatement.Constraint constraint, final String otherwise) {
    if (constraint.name() != null) {
      final String name = constraint.name().value();
      if (relationNamed(name))
        throw PostgreSQLEvaluationException.duplicateRelation(
            database.getSchema().existsType(name) ? AlreadyExistsException.Kind.TYPE : AlreadyExistsException.Kind.INDEX, name);
      if (constraintNames(table.documentType()).contains(name))
        throw PostgreSQLEvaluationException.duplicateConstraint(name, table.name());
      return name;
    }
    final Set<String> taken = constraintNames(table.documentType());
    String name = otherwise;
    for (int next = 1; taken.contains(name) || relationNamed(name); next++)
      name = otherwise + next;
    return name;
  }

  /** The name of a check or a foreign key: one that no other constraint of the table has. */
  private static String constraintName(final Table table, final CreateTableStatement.Constraint constraint,
      final String otherwise) {
    final Set<String> taken = constraintNames(table.documentType());
    if (constraint.name() != null) {
      if (taken.contains(constraint.name().value()))
        throw PostgreSQLEvaluationException.duplicateConstraint(constraint.name().value(), table.name());
      return constraint.name().value();
    }
    String name = otherwise;
    for (int next = 1; taken.contains(name); next++)
      name = otherwise + next;
    return name;
  }

  private static Set<String> constraintNames(final DocumentType type) {
    final Set<String> names = new HashSet<>();
    final TableDefinition definition = TableDefinition.of(type);
    if (definition != null) {
      if (definition.primaryKey() != null)
        names.add(definition.primaryKey().name());
      definition.uniqueKeys().forEach(key -> names.add(key.name()));
      names.addAll(definition.foreignKeys().values());
    }
    final Object checks = type.getCustomValue(CheckConstraint.CUSTOM_KEY);
    if (checks != null)
      CheckConstraint.fromJson(checks.toString()).forEach(check -> names.add(check.name()));
    final Object keys = type.getCustomValue(ForeignKey.CUSTOM_KEY);
    if (keys != null)
      ForeignKey.fromJson(keys.toString()).forEach(key -> names.add(key.name()));
    return names;
  }

  /** Whether a table, an index that {@code CREATE INDEX} named, or a key has the name. */
  private boolean relationNamed(final String name) {
    if (database.getSchema().existsType(name))
      return true;
    for (final DocumentType type : database.getSchema().getTypes()) {
      if (DeclaredIndexes.of(type).containsKey(name))
        return true;
      final TableDefinition definition = TableDefinition.of(type);
      if (definition != null && (definition.primaryKey() != null && definition.primaryKey().name().equals(name)
          || definition.uniqueKeys().stream().anyMatch(key -> key.name().equals(name))))
        return true;
    }
    return false;
  }

  /** Changes a custom value of the type, and keeps how to take that back. */
  private void setCustomValue(final String typeName, final String key, final Object value) {
    final DocumentType type = database.getSchema().getType(typeName);
    final Object before = type.getCustomValue(key);
    if (before == null && value == null)
      return;
    type.setCustomValue(key, value);
    undo.push(() -> database.getSchema().getType(typeName).setCustomValue(key, before));
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

  private static PostgreSQLEvaluationException notUnique(final String name, final Throwable cause) {
    return new PostgreSQLEvaluationException("23505", "could not create unique index \"" + name + "\"", cause);
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
    if (statement.actions().get(0) instanceof AlterTableStatement.DropColumn drop) {
      final Column column = table.findColumn(CreateTablePlan.identifier(drop.column()));
      hint = "Drop the column in the sql language, where FORCE also drops its indexes: {sql}DROP PROPERTY `"
          + table.documentType().getName() + "`.`" + (column == null ? drop.column().value() : column.name()) + "` FORCE";
    } else if (statement.actions().get(0) instanceof AlterTableStatement.AddColumn add) {
      final CreateTablePlan.Mapped mapped = CreateTablePlan.type(add.column().type());
      hint = "Add the column in the sql language: {sql}CREATE PROPERTY `" + table.documentType().getName() + "`.`"
          + add.column().name().value() + "` " + mapped.engine().name() + (mapped.ofType() == null ? "" : " OF " + mapped.ofType());
    }
    return refused("ALTER TABLE on a type that CREATE TABLE of this language did not make", statement.position(), hint);
  }
}
