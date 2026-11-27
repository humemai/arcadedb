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
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Record;
import com.arcadedb.exception.SchemaException;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.ast.DropStatement;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.catalog.DeclaredIndexes;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Sequences;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.ForeignKey;
import com.arcadedb.schema.LocalSchema;
import com.arcadedb.schema.TypeNameDependant;
import com.arcadedb.security.SecurityDatabaseUser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code DROP TABLE} and {@code DROP INDEX} (fork issue #230), with PostgreSQL's SQLSTATEs. Every name of a statement is
 * resolved and every dependency looked up before anything changes, so a statement that is refused leaves all as it was.
 * <p>
 * {@code DROP TABLE} drops a table that {@code CREATE TABLE} made. Its rows are deleted first, in a transaction of their own, so
 * that no edge is left at a vertex that is gone; then the type is dropped with its indexes and the counters of its serial and
 * identity columns. A table that a foreign key or a link table refers to is {@code 2BP01}; {@code CASCADE} removes the foreign
 * keys of other tables, as PostgreSQL does, and is refused for a link table, whose rows cannot stay without their ends.
 * <p>
 * {@code DROP INDEX} drops an index by the name {@code CREATE INDEX} gave it, or by the name {@code pg_indexes} lists it by. The
 * index of a primary key or a unique constraint is {@code 2BP01}, as is a unique index that a foreign key needs. An index of the
 * engine that serves another name, or the referring columns of a foreign key, stays; only the name goes.
 * <p>
 * The schema of the engine is not transactional and a rollback could not bring rows back, so the caller refuses both statements in
 * a transaction block.
 */
public final class DropPlan {
  /** One foreign key between columns that stands in the way of a drop: the type that declares it, and the key. */
  private record Dependency(DocumentType declaring, ForeignKey key) {
  }

  /** One name that {@code DROP INDEX} drops: the type of the index, and whether {@code CREATE INDEX} gave the name. */
  private record Found(DocumentType type, String name, DeclaredIndexes.Index declared) {
  }

  private final Database          database;
  private final RelationalCatalog catalog;
  private final DropStatement     statement;

  public DropPlan(final Database database, final RelationalCatalog catalog, final DropStatement statement) {
    this.database = database;
    this.catalog = catalog;
    this.statement = statement;
  }

  /** Drops what the statement names; the answer has no column and no row. */
  public PostgreSQLResultSet execute() {
    // asked before anything is looked up: a refusal names types and constraints
    ((DatabaseInternal) database).checkPermissionsOnDatabase(SecurityDatabaseUser.DATABASE_ACCESS.UPDATE_SCHEMA);
    return statement.kind() == DropStatement.Kind.TABLE ? dropTables() : dropIndexes();
  }

  // ------------------------------------------------------------------------------------------------ DROP TABLE

  private PostgreSQLResultSet dropTables() {
    final Map<String, DocumentType> dropped = new LinkedHashMap<>();
    for (final List<Name> written : statement.names()) {
      requireSchema(written);
      final Name last = written.get(written.size() - 1);
      final Table table = catalog.findTable(CreateTablePlan.identifier(last));
      if (table == null) {
        if (findIndex(last.value()) != null || tableWithKey(last.value()) != null)
          throw wrongKind(last.value(), "a table", "Use DROP INDEX to remove an index.");
        if (statement.ifExists())
          continue;
        throw missing("42P01", "table", last.value());
      }
      final DocumentType type = table.documentType();
      if (TableDefinition.of(type) == null)
        throw refused("DROP TABLE on a type that CREATE TABLE of this language did not make",
            "Delete its rows with DELETE FROM " + table.name() + ", then drop it in the sql language: {sql}DROP TYPE `"
                + type.getName() + "`");
      dropped.put(type.getName(), type);
    }
    if (dropped.isEmpty())
      return answer("DROP TABLE: no such table, nothing to do");

    final List<Dependency> keys = dependencies(dropped);

    final Deque<Runnable> undo = new ArrayDeque<>();
    final List<String> counters = new ArrayList<>();
    boolean done = false;
    try {
      // the foreign keys go first, so that no row takes another with it, and none is refused for being referred to
      removeKeys(keys, undo);
      for (final DocumentType type : dropped.values()) {
        counters.addAll(Sequences.keysOf(type));
        setCustomValue(type, ForeignKey.CUSTOM_KEY, null, undo);
      }
      // committed before the types are dropped: the rows of other tables that change with these (the edge lists of the
      // vertices at the ends of a link table) must not be rolled back once the files of these are gone
      database.transaction(() -> deleteRows(dropped.values()), false);
      // a link table before the vertex tables of its ends
      for (final DocumentType type : dropped.values().stream()
          .sorted(Comparator.comparing(each -> each instanceof EdgeType ? 0 : 1)).toList())
        database.getSchema().dropType(type.getName());
      done = true;
    } catch (final RuntimeException e) {
      throw WriteErrors.translate(e);
    } finally {
      if (!done)
        while (!undo.isEmpty())
          undo.pop().run();
    }
    Sequences.drop(database, counters);
    return answer("DROP TABLE " + String.join(", ", dropped.keySet()));
  }

  /**
   * The foreign keys of other tables that refer to a table of the statement, which {@code CASCADE} removes. Everything else that
   * depends on one is refused here: without {@code CASCADE} all of it, and with it what cannot be removed.
   */
  private List<Dependency> dependencies(final Map<String, DocumentType> dropped) {
    final List<Dependency> keys = new ArrayList<>();
    final List<String> found = new ArrayList<>();
    final Set<String> linkTables = new LinkedHashSet<>();
    for (final DocumentType type : database.getSchema().getTypes()) {
      if (dropped.containsKey(type.getName()))
        continue;
      for (final String end : List.of(EdgeEndpoints.SOURCE_KEY, EdgeEndpoints.TARGET_KEY)) {
        final Object referenced = type.getCustomValue(end);
        if (referenced != null && dropped.containsKey(referenced.toString())) {
          linkTables.add(type.getName());
          found.add("link table " + type.getName() + " depends on table " + referenced);
        }
      }
      for (final ForeignKey key : declaredKeys(type))
        if (dropped.containsKey(key.referencedType())) {
          keys.add(new Dependency(type, key));
          found.add("constraint " + key.name() + " on table " + type.getName() + " depends on table " + key.referencedType());
        }
      for (final DocumentType parent : type.getSuperTypes())
        if (dropped.containsKey(parent.getName()))
          throw dependent(parent.getName(), "type " + type.getName() + " depends on table " + parent.getName(),
              "Drop that type first, in the sql language: {sql}DROP TYPE `" + type.getName() + "`");
    }
    final LocalSchema schema = database.getSchema().getEmbedded();
    for (final String name : dropped.keySet())
      try {
        // views, continuous aggregates and the other objects of the sql language that keep the name of a type
        for (final TypeNameDependant dependant : schema.getTypeNameDependants())
          dependant.checkTypeDrop(schema, name, name);
      } catch (final SchemaException e) {
        throw dependent(name, e.getMessage(), "Drop what depends on the table first, in the sql language");
      }

    if (found.isEmpty())
      return keys;
    final String first = dropped.keySet().iterator().next();
    if (!statement.cascade())
      throw dependent(dropped.size() == 1 ? first : null, String.join("; ", found),
          linkTables.isEmpty() ? "Use DROP ... CASCADE to drop the dependent objects too."
              : "Drop the link table in the same statement: DROP TABLE " + String.join(", ", linkTables) + ", "
                  + String.join(", ", dropped.keySet()));
    if (!linkTables.isEmpty())
      throw refused("DROP TABLE CASCADE of a table that a link table refers to, whose rows cannot stay without their ends,",
          "Drop the link table in the same statement: DROP TABLE " + String.join(", ", linkTables) + ", "
              + String.join(", ", dropped.keySet()));
    return keys;
  }

  /** The rows of the tables: every edge, and every vertex that an edge still holds; the others go with their type. */
  private void deleteRows(final Iterable<DocumentType> types) {
    for (final DocumentType type : types)
      if (type instanceof EdgeType)
        for (final Iterator<Record> records = database.iterateType(type.getName(), false); records.hasNext(); )
          database.deleteRecord(records.next().asDocument(true));
    for (final DocumentType type : types)
      if (!(type instanceof EdgeType))
        for (final Iterator<Record> records = database.iterateType(type.getName(), false); records.hasNext(); ) {
          final Record record = records.next();
          if (record.asDocument(true) instanceof Vertex vertex
              && vertex.getEdges(Vertex.DIRECTION.BOTH).iterator().hasNext())
            database.deleteRecord(record.asDocument(true));
        }
  }

  // ------------------------------------------------------------------------------------------------ DROP INDEX

  private PostgreSQLResultSet dropIndexes() {
    final List<Found> found = new ArrayList<>();
    // as in PostgreSQL, every name is looked up before anything is said about what depends on one
    PostgreSQLEvaluationException ofKey = null;
    for (final List<Name> written : statement.names()) {
      requireSchema(written);
      final Name last = written.get(written.size() - 1);
      final String name = last.value();
      final Found index = findIndex(name);
      final Table keyed = index == null || index.declared() == null ? tableWithKey(name) : null;
      if (keyed != null) {
        if (ofKey == null)
          ofKey = keyIndex(name, keyed.name(), keyed.documentType());
        continue;
      }
      if (index == null) {
        if (catalog.findTable(CreateTablePlan.identifier(last)) != null)
          throw wrongKind(name, "an index", "Use DROP TABLE to remove a table.");
        if (statement.ifExists())
          continue;
        throw missing("42704", "index", name);
      }
      found.add(index);
    }
    if (ofKey != null)
      throw ofKey;
    if (found.isEmpty())
      return answer("DROP INDEX: no such index, nothing to do");

    // by type: the names of CREATE INDEX that go, and the indexes of the engine that go with them
    final Map<DocumentType, Set<String>> names = new LinkedHashMap<>();
    for (final Found each : found)
      names.computeIfAbsent(each.type(), type -> new LinkedHashSet<>()).add(each.name());
    final List<String> engine = new ArrayList<>();
    final List<Dependency> keys = new ArrayList<>();
    for (final Found each : found) {
      final DocumentType type = each.type();
      final TypeIndex index;
      if (each.declared() == null)
        index = type.getAllIndexes(false).stream().filter(one -> one.getName().equals(each.name())).findFirst().orElse(null);
      else
        index = each.declared().engine() ?
            type.getPolymorphicIndexByProperties(each.declared().columns().toArray(new String[0])) : null;
      if (index == null || engine.contains(index.getName()))
        continue;
      final List<String> properties = index.getPropertyNames();
      final TableDefinition definition = TableDefinition.of(type);
      final boolean ofKey = definition != null && (definition.primaryKey() != null
          && definition.primaryKey().columns().equals(properties)
          || definition.uniqueKeys().stream().anyMatch(key -> key.columns().equals(properties)));
      if (each.declared() == null) {
        if (definition == null)
          throw refused("DROP INDEX of an index of a type that CREATE TABLE of this language did not make",
              "Drop it in the sql language: {sql}DROP INDEX `" + index.getName() + "`");
        if (ofKey)
          throw keyIndex(each.name(), type.getName(), type);
      } else if (ofKey || DeclaredIndexes.of(type).entrySet().stream()
          .anyMatch(other -> !names.get(type).contains(other.getKey()) && other.getValue().engine()
              && other.getValue().columns().equals(each.declared().columns())))
        // a constraint or another name still stands for the index
        continue;
      final ForeignKey holder = holder(type, properties);
      if (holder != null) {
        if (each.declared() == null)
          throw refused("DROP INDEX of the index that the foreign key " + holder.name() + " reads", "Leave the index in place");
        continue;
      }
      if (index.isUnique())
        for (final Dependency dependency : referring(type, properties)) {
          if (!statement.cascade())
            throw new PostgreSQLEvaluationException("2BP01", "cannot drop index " + each.name()
                + " because other objects depend on it: constraint " + dependency.key().name() + " on table "
                + dependency.declaring().getName() + " depends on index " + each.name()
                + ". Hint: Use DROP ... CASCADE to drop the dependent objects too.");
          keys.add(dependency);
        }
      engine.add(index.getName());
    }

    final Deque<Runnable> undo = new ArrayDeque<>();
    boolean done = false;
    try {
      removeKeys(keys, undo);
      for (final Map.Entry<DocumentType, Set<String>> each : names.entrySet()) {
        final Object before = each.getKey().getCustomValue(DeclaredIndexes.CUSTOM_KEY);
        if (before == null)
          continue;
        DeclaredIndexes.remove(each.getKey(), each.getValue());
        undo.push(() -> each.getKey().setCustomValue(DeclaredIndexes.CUSTOM_KEY, before));
      }
      for (final String index : engine)
        database.getSchema().dropIndex(index);
      done = true;
    } finally {
      if (!done)
        while (!undo.isEmpty())
          undo.pop().run();
    }
    return answer("DROP INDEX " + String.join(", ", found.stream().map(Found::name).toList()));
  }

  /** The index of the name: one that {@code CREATE INDEX} named, else one of the engine on a table; {@code null} for neither. */
  private Found findIndex(final String name) {
    for (final DocumentType type : database.getSchema().getTypes()) {
      final DeclaredIndexes.Index declared = DeclaredIndexes.of(type).get(name);
      if (declared != null)
        return new Found(type, name, declared);
    }
    for (final Table table : catalog.tables())
      for (final TypeIndex index : table.documentType().getAllIndexes(false))
        if (index.getName().equals(name))
          return new Found(table.documentType(), name, null);
    return null;
  }

  /** The table with a primary key or a unique constraint of the name, whose index has it too; {@code null} when none has. */
  private Table tableWithKey(final String name) {
    for (final Table table : catalog.tables())
      if (table.primaryKey() != null && table.primaryKey().name().equals(name)
          || table.uniqueKeys().stream().anyMatch(key -> key.name().equals(name)))
        return table;
    return null;
  }

  /** The foreign key of the type that reads the index over {@code properties}, {@code null} when none does. */
  private ForeignKey holder(final DocumentType type, final List<String> properties) {
    for (final ForeignKey key : declaredKeys(type))
      if (key.columns().size() == properties.size() && Set.copyOf(key.columns()).equals(Set.copyOf(properties)))
        return key;
    return null;
  }

  /** The foreign keys that need a unique index of the type over {@code properties}, when no other index serves them. */
  private List<Dependency> referring(final DocumentType type, final List<String> properties) {
    final Set<String> columns = Set.copyOf(properties);
    if (type.getAllIndexes(false).stream().filter(
        index -> index.isUnique() && Set.copyOf(index.getPropertyNames()).equals(columns)).count() > 1)
      return List.of();
    final List<Dependency> found = new ArrayList<>();
    for (final DocumentType each : database.getSchema().getTypes())
      for (final ForeignKey key : declaredKeys(each))
        if (key.referencedType().equals(type.getName()) && Set.copyOf(key.referencedColumns()).equals(columns))
          found.add(new Dependency(each, key));
    return found;
  }

  // ------------------------------------------------------------------------------------------------ the schema

  private static List<ForeignKey> declaredKeys(final DocumentType type) {
    final Object value = type.getCustomValue(ForeignKey.CUSTOM_KEY);
    return value == null ? List.of() : ForeignKey.fromJson(value.toString());
  }

  /** Takes the keys out of the types that declare them. */
  private static void removeKeys(final List<Dependency> keys, final Deque<Runnable> undo) {
    final Map<DocumentType, Set<String>> byType = new LinkedHashMap<>();
    for (final Dependency each : keys)
      byType.computeIfAbsent(each.declaring(), type -> new LinkedHashSet<>()).add(each.key().name());
    byType.forEach((type, names) -> {
      final List<ForeignKey> left = declaredKeys(type).stream().filter(key -> !names.contains(key.name())).toList();
      setCustomValue(type, ForeignKey.CUSTOM_KEY, left.isEmpty() ? null : ForeignKey.toJson(left), undo);
    });
  }

  private static void setCustomValue(final DocumentType type, final String key, final Object value, final Deque<Runnable> undo) {
    final Object before = type.getCustomValue(key);
    if (before == null && value == null)
      return;
    type.setCustomValue(key, value);
    undo.push(() -> {
      if (type.getSchema().existsType(type.getName()))
        type.setCustomValue(key, before);
    });
  }

  // ------------------------------------------------------------------------------------------------ names and errors

  private void requireSchema(final List<Name> name) {
    if (name.size() == 2) {
      final String schema = name.get(0).value();
      if ("information_schema".equals(schema) || "pg_catalog".equals(schema))
        throw refused("DROP of an object of the schema " + schema, "Leave the statement out");
      catalog.requireSchema(CreateTablePlan.identifier(name.get(0)));
    } else if (name.size() > 2)
      throw refused("A name of three parts", "Write the name without a schema");
  }

  private PostgreSQLEvaluationException keyIndex(final String name, final String table, final DocumentType type) {
    return new PostgreSQLEvaluationException("2BP01", "cannot drop index " + name + " because constraint " + name + " on table "
        + table + " requires it. Hint: the language does not drop a constraint; the sql language drops its index: DROP INDEX of"
        + " one of the names that SELECT name FROM schema:indexes lists for the type " + type.getName());
  }

  private static PostgreSQLEvaluationException missing(final String state, final String kind, final String name) {
    return new PostgreSQLEvaluationException(state, kind + " \"" + name + "\" does not exist");
  }

  private static PostgreSQLEvaluationException wrongKind(final String name, final String kind, final String hint) {
    return new PostgreSQLEvaluationException("42809", "\"" + name + "\" is not " + kind + ". Hint: " + hint);
  }

  private static PostgreSQLEvaluationException dependent(final String table, final String detail, final String hint) {
    return new PostgreSQLEvaluationException("2BP01", (table == null ? "cannot drop desired object(s)" : "cannot drop table "
        + table) + " because other objects depend on it: " + detail + ". Hint: " + hint);
  }

  private PostgreSQLFeatureNotSupportedException refused(final String what, final String hint) {
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language ("
        + statement.position() + ")", hint);
  }

  private static PostgreSQLResultSet answer(final String step) {
    return new PostgreSQLResultSet(List.of(), new PostgreSQLResultSet.Plan(List.of(step))).rowless().withAffectedRows(0);
  }
}
