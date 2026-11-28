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

import com.arcadedb.database.CommitCheck;
import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.database.TransactionIndexContext;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.EndKey;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Comparison;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.utility.IntHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The writes of the link tables whose ends refer to a key (fork issue #213): the row that a key names, and the rules of the
 * references. The engine keeps a link between two records and deletes it with either of them; it knows nothing of the keys. So
 * the rules are kept here, by the writes of this language only: a statement of {@code sql} or openCypher deletes a linked record
 * with its links, and changes a key that links show.
 * <p>
 * A statement is checked before it changes anything. A link at an end of a deleted record stops the delete when the rule of that
 * end is {@code NO ACTION} or {@code RESTRICT}, unless the delete takes the link away from its other end, whose rule is
 * {@code CASCADE}, as PostgreSQL checks the reference after the statement.
 * <p>
 * A concurrent transaction that links the record, deletes it, or changes its key is a conflict, which refuses the commit that
 * comes second: what the page versions of the engine do not see, the commit reads again under file locks ({@link AtCommit}, fork
 * issue #292).
 * <p>
 * The engine finds the links of a record from the record. A one-way (unidirectional) edge type keeps nothing in the record at
 * the target end, so for that end the links of the type are read once for each statement.
 */
final class LinkKeys {
  /**
   * An end of a link table that refers to a key.
   *
   * @param links      the link table
   * @param end        {@link Column.Kind#SOURCE} or {@link Column.Kind#TARGET}
   * @param key        what the end refers to
   * @param referenced the column of the key in the referenced table
   */
  private record Reference(Table links, Column.Kind end, EndKey key, Column referenced) {
    /** The name of the foreign key, as the catalog has it, and PostgreSQL's name for one that a broken table does not list. */
    String name() {
      final Table.ForeignKey.Origin origin = end == Column.Kind.SOURCE ? Table.ForeignKey.Origin.EDGE_SOURCE
          : Table.ForeignKey.Origin.EDGE_TARGET;
      for (final Table.ForeignKey foreignKey : links.foreignKeys())
        if (foreignKey.origin() == origin)
          return foreignKey.name();
      return links.name() + "_" + (end == Column.Kind.SOURCE ? Column.SOURCE_ID : Column.TARGET_ID) + "_fkey";
    }
  }

  /** A value that a statement is about to give a property of a record, as the record holds it. */
  record Change(Document row, String property, Object value) {
  }

  private final RelationalCatalog catalog;
  private final List<Reference>   references = new ArrayList<>();

  LinkKeys(final RelationalCatalog catalog) {
    this.catalog = catalog;
    for (final Table table : catalog.tables()) {
      if (table.kind() != Table.Kind.EDGE)
        continue;
      add(table, Column.Kind.SOURCE, table.ends().source());
      add(table, Column.Kind.TARGET, table.ends().target());
    }
  }

  private void add(final Table links, final Column.Kind end, final EndKey key) {
    if (key == null)
      return;
    final Table referenced = catalog.findTable(Identifier.quoted(key.table()));
    if (referenced == null)
      return;
    for (final Column column : referenced.columns())
      if (column.kind() == Column.Kind.PROPERTY && column.property().equals(key.property())) {
        references.add(new Reference(links, end, key, column));
        return;
      }
  }

  /** Whether no end of any link table refers to a key: then no write has anything to check. */
  boolean isEmpty() {
    return references.isEmpty();
  }

  /** Whether some end refers to a property of this name. */
  boolean isKey(final String property) {
    for (final Reference reference : references)
      if (reference.key().property().equals(property))
        return true;
    return false;
  }

  // ------------------------------------------------------------------------------------------------ INSERT

  /**
   * The row that the key of a new link names.
   *
   * @param links  the link table
   * @param column its end, which refers to a key
   * @param value  the value the statement gives the end, in the type of the column
   * @param stored the value as a record holds it
   *
   * @throws PostgreSQLEvaluationException {@code 23503} when no row of the referenced table has this key
   */
  Vertex row(final Database db, final Table links, final Column column, final Object value, final Object stored) {
    final EndKey key = column.endKey();
    final PgType type = PgType.of(column.type());
    final DocumentType referenced = db.getSchema().getType(key.table());
    for (final TypeIndex index : referenced.getAllIndexes(true)) {
      if (!index.isUnique() || !index.getPropertyNames().equals(List.of(key.property()))
          || index.getType() != Schema.INDEX_TYPE.LSM_TREE && index.getType() != Schema.INDEX_TYPE.HASH)
        continue;
      final IndexCursor cursor = index.get(new Object[] { stored });
      while (cursor.hasNext()) {
        final Identifiable found = cursor.next();
        // the index of a supertype holds the rows of other tables too, and an index that ignores case holds other keys
        if (found.getRecord() instanceof Vertex vertex && vertex.getType().instanceOf(key.table())
            && same(column, vertex.get(key.property()), stored)) {
          atCommit(db).found(new Found(vertex.getIdentity(), column, key.property(), stored, index));
          return vertex;
        }
      }
      break;
    }
    final String name = links.foreignKeys().stream().filter(foreignKey -> foreignKey.columns().equals(List.of(column.name())))
        .map(Table.ForeignKey::name).findFirst().orElse(links.name() + "_" + column.name() + "_fkey");
    throw new PostgreSQLEvaluationException("23503", "insert or update on table \"" + links.name()
        + "\" violates foreign key constraint \"" + name + "\". Detail: Key (" + column.name() + ")=("
        + Casts.toText(value, type) + ") is not present in table \"" + key.table() + "\".");
  }

  /** Whether two values that records hold are one value of the type of the column. */
  private static boolean same(final Column column, final Object left, final Object right) {
    if (left == null || right == null)
      return left == right;
    final PgType type = PgType.of(column.type());
    return Comparison.compare(type, Casts.fromColumn(left, column.type(), type), Casts.fromColumn(right, column.type(), type)) == 0;
  }

  // ------------------------------------------------------------------------------------------------ UPDATE

  /**
   * Refuses a statement that changes a key which links point at, for every end whose rule is not {@code ON UPDATE CASCADE}. A key
   * that is set to the value it has, and the key of a row that no link points at, change.
   *
   * @throws PostgreSQLEvaluationException {@code 23503} when a link points at a row whose key changes
   */
  void beforeUpdate(final Database db, final List<Change> changes) {
    for (final Reference reference : references) {
      if (reference.key().onUpdate() == Table.Action.CASCADE)
        continue;
      final Map<RID, Vertex> changed = new LinkedHashMap<>();
      for (final Change change : changes) {
        if (!change.property().equals(reference.key().property())
            || !change.row().getType().instanceOf(reference.key().table()))
          continue;
        if (!same(reference.referenced(), change.row().get(change.property()), change.value()))
          changed.put(change.row().getIdentity(), change.row().asVertex());
      }
      if (!changed.isEmpty()) {
        // the committed links first: one that another transaction commits after this is a conflict of the commit
        atCommit(db).keysChange(reference, changed.keySet());
        forEachLink(db, reference, changed, (vertex, link) -> {
          throw new PostgreSQLEvaluationException("23503", "update or delete on table \"" + reference.key().table()
              + "\" violates foreign key constraint \"" + reference.name() + "\" on table \"" + reference.links().name()
              + "\". Detail: Key (" + reference.referenced().name() + ")=(" + keyText(reference, vertex)
              + ") is still referenced from table \"" + reference.links().name() + "\".");
        });
      }
    }
  }

  // ------------------------------------------------------------------------------------------------ DELETE

  /**
   * Checks a delete of these records against the rules of the ends that refer to them.
   *
   * @return the links that the delete has to delete itself before the records, because the engine cannot find them from a record:
   *     those of a one-way edge type whose target end is {@code ON DELETE CASCADE}
   *
   * @throws PostgreSQLEvaluationException {@code 23503} when a link keeps a record under {@code NO ACTION}, {@code 23001} under
   *                                       {@code RESTRICT}
   */
  Collection<Edge> beforeDelete(final Database db, final Map<RID, Vertex> deleted) {
    final Map<String, Edge> own = new LinkedHashMap<>();
    for (final Reference reference : references) {
      final Map<RID, Vertex> rows = new LinkedHashMap<>();
      for (final Map.Entry<RID, Vertex> entry : deleted.entrySet())
        if (entry.getValue().getType().instanceOf(reference.key().table()))
          rows.put(entry.getKey(), entry.getValue());
      if (rows.isEmpty())
        continue;
      if (reference.key().onDelete() == Table.Action.CASCADE) {
        if (!isInTheRecord(db, reference))
          forEachLink(db, reference, rows, (vertex, link) -> own.put(identity(link), link));
      } else
        forEachLink(db, reference, rows, (vertex, link) -> {
          if (!goesWithItsOtherEnd(link, reference.end(), deleted))
            throw stillReferenced(reference, vertex);
        });
    }
    return own.values();
  }

  private static PostgreSQLEvaluationException stillReferenced(final Reference reference, final Vertex vertex) {
    final boolean restrict = reference.key().onDelete() == Table.Action.RESTRICT;
    return new PostgreSQLEvaluationException(restrict ? "23001" : "23503", "update or delete on table \""
        + reference.key().table() + "\" violates " + (restrict ? "RESTRICT setting of " : "") + "foreign key constraint \""
        + reference.name() + "\" on table \"" + reference.links().name() + "\". Detail: Key (" + reference.referenced().name()
        + ")=(" + keyText(reference, vertex) + ") is " + (restrict ? "" : "still ") + "referenced from table \""
        + reference.links().name() + "\".");
  }

  /** Whether the delete takes the link away from its other end: that record goes too, and the rule of that end is CASCADE. */
  private boolean goesWithItsOtherEnd(final Edge link, final Column.Kind end, final Map<RID, Vertex> deleted) {
    if (!deleted.containsKey(end == Column.Kind.SOURCE ? link.getIn() : link.getOut()))
      return false;
    final Table own = catalog.findTable(Identifier.quoted(link.getTypeName()));
    final EndKey other = own == null ? null : end == Column.Kind.SOURCE ? own.ends().target() : own.ends().source();
    // an end that holds record ids is deleted with its record
    return other == null || other.onDelete() == Table.Action.CASCADE;
  }

  /** The key of a row as PostgreSQL writes it in the detail of an error. */
  private static String keyText(final Reference reference, final Vertex vertex) {
    final Column column = reference.referenced();
    final Object value = vertex.get(reference.key().property());
    if (value == null)
      return "null";
    final PgType type = PgType.of(column.type());
    return Casts.toText(Casts.fromColumn(value, column.type(), type), type);
  }

  // ------------------------------------------------------------------------------------------------ the commit

  private static AtCommit atCommit(final Database db) {
    final DatabaseInternal database = (DatabaseInternal) db;
    return database.getTransaction().commitCheck(AtCommit.class.getName(), () -> new AtCommit(database));
  }

  /** A key by which a transaction found the row at an end of a new link. */
  private record Found(RID row, Column column, String property, Object key, TypeIndex index) {
  }

  /**
   * The rows whose key a transaction changes under one reference that does not follow the change.
   *
   * @param reference the reference
   * @param links     for each row, how many times each of its links was committed when the statement that changed the key ran
   */
  private record Moved(Reference reference, Map<RID, Map<String, Integer>> links) {
  }

  /**
   * What a transaction read about the keys of link tables, and a concurrent transaction can change under it (fork issue #292). The
   * page versions see neither side: a new link writes the lists of links, a change of a key writes the row. Both commits lock the
   * bucket file of the row, so they run one after the other, and the second one reads what the first one committed:
   * <ul>
   * <li>a new link: the row still has the key that the link names, unless the transaction gave it that key itself;</li>
   * <li>a changed key: the row has no committed link that it did not have when the statement ran. Those it had then are the ones
   * the transaction deleted before, since the statement found none in its own view.</li>
   * </ul>
   */
  private static final class AtCommit implements CommitCheck {
    private final DatabaseInternal        database;
    private final TransactionIndexContext changes;
    private final Map<List<Object>, Found> found = new LinkedHashMap<>();
    private final Map<String, Moved>       moved = new LinkedHashMap<>();

    private AtCommit(final DatabaseInternal database) {
      this.database = database;
      // of the transaction that is checked: the check itself runs in another one
      this.changes = database.getTransaction().getIndexChanges();
    }

    void found(final Found key) {
      found.putIfAbsent(List.of(key.row(), key.property(), key.key()), key);
    }

    /** Reads the committed links of the rows, before their keys change. A row that was read before keeps its first answer. */
    void keysChange(final Reference reference, final Collection<RID> rows) {
      final Moved change = moved.computeIfAbsent(reference.links().name() + '.' + reference.end(),
          name -> new Moved(reference, new LinkedHashMap<>()));
      final List<RID> unread = rows.stream().filter(row -> !change.links().containsKey(row)).toList();
      if (unread.isEmpty())
        return;
      database.begin(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
      try {
        final Map<RID, Map<String, Integer>> committed = links(change.reference(), unread);
        for (final RID row : unread)
          change.links().put(row, committed.getOrDefault(row, Map.of()));
      } finally {
        database.rollback();
      }
    }

    @Override
    public void filesToLock(final IntHashSet files) {
      for (final Found key : found.values())
        files.add(key.row().getBucketId());
      for (final Moved change : moved.values())
        for (final RID row : change.links().keySet())
          files.add(row.getBucketId());
    }

    @Override
    public void checkCommitted() {
      for (final Found key : found.values()) {
        if (changes.isKeyAdded(key.index(), new Object[] { key.key() }, key.row()))
          continue;
        final Vertex row = load(key.row());
        if (row == null || !same(key.column(), row.get(key.property()), key.key()))
          throw conflict("changed or deleted a row that a new link refers to");
      }
      for (final Moved change : moved.values())
        for (final Map.Entry<RID, Map<String, Integer>> now : links(change.reference(), change.links().keySet()).entrySet())
          for (final Map.Entry<String, Integer> link : now.getValue().entrySet())
            if (link.getValue() > change.links().get(now.getKey()).getOrDefault(link.getKey(), 0))
              throw conflict("linked a row of table \"" + change.reference().key().table() + "\" whose key changes");
    }

    @Override
    public String replicaRefusal() {
      return "A commit that starts on a replica cannot insert a link by its keys, or change a key that links refer to: run the"
          + " transaction on the leader";
    }

    /** The links of these rows that the current transaction reads, counted by link; a row it does not have has none. */
    private Map<RID, Map<String, Integer>> links(final Reference reference, final Collection<RID> rows) {
      final Map<RID, Vertex> there = new LinkedHashMap<>();
      for (final RID rid : rows) {
        final Vertex row = load(rid);
        if (row != null)
          there.put(rid, row);
      }
      final Map<RID, Map<String, Integer>> links = new HashMap<>();
      if (!there.isEmpty())
        forEachLink(database, reference, there,
            (row, link) -> links.computeIfAbsent(row.getIdentity(), rid -> new HashMap<>()).merge(identity(link), 1, Integer::sum));
      return links;
    }

    private Vertex load(final RID rid) {
      try {
        return database.lookupByRID(rid, true).asVertex();
      } catch (final RecordNotFoundException e) {
        return null;
      }
    }

    private static ConcurrentModificationException conflict(final String what) {
      return new ConcurrentModificationException("A concurrent transaction " + what + ". Please retry the operation");
    }
  }

  // ------------------------------------------------------------------------------------------------ the links of a record

  /** What tells one link from another: a lightweight link has no record of its own, so its ends count too. */
  private static String identity(final Edge link) {
    return link.getTypeName() + link.getIdentity() + link.getOut() + link.getIn();
  }

  /** The edge types whose links a link table shows: its type and every subtype. */
  private static List<String> typeNames(final DocumentType type, final List<String> names) {
    names.add(type.getName());
    for (final DocumentType subType : type.getSubTypes())
      typeNames(subType, names);
    return names;
  }

  /** Whether a record knows its links at this end: always at the source, and at the target for a two-way edge type. */
  private static boolean isInTheRecord(final Database db, final Reference reference) {
    if (reference.end() == Column.Kind.SOURCE)
      return true;
    for (final String name : typeNames(reference.links().documentType(), new ArrayList<>()))
      if (db.getSchema().getType(name) instanceof EdgeType type && !type.isBidirectional())
        return false;
    return true;
  }

  /** Gives {@code action} every link of the table of the reference that has one of these records at its end. */
  private static void forEachLink(final Database db, final Reference reference, final Map<RID, Vertex> rows,
      final BiConsumer<Vertex, Edge> action) {
    final String[] types = typeNames(reference.links().documentType(), new ArrayList<>()).toArray(new String[0]);
    if (isInTheRecord(db, reference)) {
      final Vertex.DIRECTION direction = reference.end() == Column.Kind.SOURCE ? Vertex.DIRECTION.OUT : Vertex.DIRECTION.IN;
      for (final Vertex vertex : rows.values())
        for (final Edge link : vertex.getEdges(direction, types))
          action.accept(vertex, link);
      return;
    }
    // the target end of a one-way type: read the links of the table, those without a record from the records they start at
    final BiConsumer<Edge, Map<RID, Vertex>> match = (link, targets) -> {
      final Vertex target = targets.get(link.getIn());
      if (target != null)
        action.accept(target, link);
    };
    if (reference.links().edgeScan() == Table.EdgeScan.BUCKETS) {
      final var records = db.iterateType(reference.links().name(), true);
      while (records.hasNext()) {
        final Record record = records.next();
        match.accept(record instanceof Edge edge ? edge : record.asEdge(), rows);
      }
    } else
      for (final String from : reference.links().walkFromTables()) {
        final var vertices = db.iterateType(from, true);
        while (vertices.hasNext())
          for (final Edge link : vertices.next().asVertex().getEdges(Vertex.DIRECTION.OUT, types))
            match.accept(link, rows);
      }
  }
}
