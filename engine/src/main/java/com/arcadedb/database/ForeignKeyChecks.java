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
package com.arcadedb.database;

import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.ForeignKeyException;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.schema.ForeignKey;
import com.arcadedb.schema.ForeignKeys;
import com.arcadedb.utility.IntHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the foreign keys of a transaction still have to check (fork issue #228). Every change of an index that holds a key leaves a
 * note: a referring record took a key, or a referenced record lost one. A note names a key value, and the check reads the state of
 * that value, so a write that was taken back leaves a note that finds nothing to refuse.
 * <ol>
 * <li>{@link #checkStatement()}, at the end of a statement of the {@code postgresql} language and at the start of every commit,
 * runs the referential actions and checks the view of the transaction: {@link ForeignKeyException}.</li>
 * <li>{@link #checkCommitted()}, inside the commit and under its file locks, reads the committed state again, because the page
 * versions do not see that one transaction removed a key while another added a record that refers to it. The commit of a
 * transaction that adds a referring record locks the bucket file of the referenced record ({@link #referencedFiles()}), which
 * a transaction that deletes or changes that record writes: the two commits run one after the other, and the second one is
 * refused with a {@link ConcurrentModificationException}.</li>
 * </ol>
 */
final class ForeignKeyChecks {
  /** The most rounds of referential actions one check runs: only a cycle of {@code ON UPDATE CASCADE} gets there. */
  private static final int MAX_ROUNDS = 1_000_000;

  private record Id(String type, String name, boolean referencedSide, TransactionIndexContext.ComparableKey value) {
  }

  /**
   * @param value   the key, in the types of the index that changed
   * @param removed for the referenced side, the records that lost the key
   */
  private record Note(ForeignKeys.Resolved key, boolean referencedSide, Object[] value, Set<RID> removed) {
  }

  /** The records that the changes of this transaction add to a key of an index, and take from it. */
  private record Changes(Set<RID> added, Set<RID> removed) {
  }

  private final DatabaseInternal        database;
  private final TransactionIndexContext changes;
  private final Map<Id, Note>           all     = new LinkedHashMap<>();
  private final Map<Id, Note>           pending = new LinkedHashMap<>();
  private final IntHashSet              files   = new IntHashSet();

  ForeignKeyChecks(final DatabaseInternal database, final TransactionIndexContext changes) {
    this.database = database;
    this.changes = changes;
  }

  void note(final ForeignKeys.Resolved key, final boolean referencedSide, final Object[] value, final RID rid) {
    for (final Object part : value)
      if (part == null)
        // MATCH SIMPLE: a key with a null is not checked
        return;
    final Id id = new Id(key.type(), key.key().name(), referencedSide, new TransactionIndexContext.ComparableKey(value));
    final Note note = all.computeIfAbsent(id, k -> new Note(key, referencedSide, value.clone(), new LinkedHashSet<>(1)));
    if (referencedSide && rid != null)
      note.removed().add(rid);
    pending.put(id, note);
  }

  /** Runs the referential actions of what changed since the last call, then checks it, in the view of the transaction. */
  void checkStatement() {
    final Map<Id, Note> checking = new LinkedHashMap<>();
    try {
      for (int round = 0; !pending.isEmpty(); round++) {
        if (round == MAX_ROUNDS)
          throw new ForeignKeyException("54001", "The referential actions of the foreign keys do not come to an end");
        final Map<Id, Note> batch = new LinkedHashMap<>(pending);
        pending.clear();
        checking.putAll(batch);
        act(batch.values());
      }
      for (final Note note : checking.values())
        verify(note);
    } catch (final RuntimeException e) {
      // a caller that goes on is refused again at its commit, unless what it wrote was taken back
      pending.putAll(checking);
      throw e;
    }
  }

  /** Every action of a round is read from the state before any of them is applied: two rows may swap their keys. */
  private void act(final Collection<Note> batch) {
    final List<Runnable> actions = new ArrayList<>();
    for (final Note note : batch) {
      if (!note.referencedSide())
        continue;
      final ForeignKey key = note.key().key();
      final TypeIndex referenced = note.key().referencedIndex();
      // each loss of a key is acted on once: a later one finds other records under the key
      final List<RID> removed = List.copyOf(note.removed());
      note.removed().clear();
      for (final RID rid : removed) {
        final Document row = load(rid);
        final Object[] now = row == null ? null : valueOf(row, referenced.getPropertyNames());
        if (now != null && holds(referenced, now, note.value()))
          continue;
        final ForeignKey.Action action = row == null ? key.onDelete() : key.onUpdate();
        if (action == ForeignKey.Action.NO_ACTION)
          continue;
        final List<RID> referring = find(note.key().referringIndex(), note.value(), Integer.MAX_VALUE);
        if (referring.isEmpty())
          continue;
        if (action == ForeignKey.Action.RESTRICT)
          throw new ForeignKeyException("23001", "update or delete on table \"" + key.referencedType()
              + "\" violates RESTRICT setting of foreign key constraint \"" + key.name() + "\" on table \"" + note.key().type()
              + "\": Key " + text(referenced.getPropertyNames(), note.value()) + " is referenced from table \"" + note.key().type()
              + "\".");
        // another row has taken the key of the deleted one: the referring rows refer to it
        if (row == null && !find(referenced, note.value(), 1).isEmpty())
          continue;
        final List<String> columns = note.key().referringIndex().getPropertyNames();
        for (final RID child : referring)
          if (action == ForeignKey.Action.CASCADE && row == null)
            actions.add(() -> delete(child));
          else
            actions.add(() -> assign(child, columns, action == ForeignKey.Action.SET_NULL ? null : now));
      }
    }
    for (final Runnable action : actions)
      action.run();
  }

  private void delete(final RID rid) {
    final Document row = load(rid);
    if (row != null)
      database.deleteRecord(row);
  }

  private void assign(final RID rid, final List<String> columns, final Object[] values) {
    final Document row = load(rid);
    if (row == null)
      return;
    final MutableDocument changed = row.modify();
    for (int i = 0; i < columns.size(); i++)
      changed.set(columns.get(i), values == null ? null : values[i]);
    changed.save();
  }

  private void verify(final Note note) {
    final ForeignKey key = note.key().key();
    if (find(note.key().referringIndex(), note.value(), 1).isEmpty()
        || !find(note.key().referencedIndex(), note.value(), 1).isEmpty())
      return;
    if (note.referencedSide())
      throw new ForeignKeyException("23503", "update or delete on table \"" + key.referencedType()
          + "\" violates foreign key constraint \"" + key.name() + "\" on table \"" + note.key().type() + "\": Key " + text(
          note.key().referencedIndex().getPropertyNames(), note.value()) + " is still referenced from table \"" + note.key().type()
          + "\".");
    throw new ForeignKeyException("23503", "insert or update on table \"" + note.key().type()
        + "\" violates foreign key constraint \"" + key.name() + "\": Key " + text(note.key().referringIndex().getPropertyNames(),
        note.value()) + " is not present in table \"" + key.referencedType() + "\".");
  }

  /**
   * The bucket files of the referenced records that the commit has to lock beside the ones it writes. Read in the view of the
   * transaction, so before the commit leaves the state in which it can write.
   */
  IntHashSet referencedFiles() {
    // a record that lost a key was written, so its file is locked already
    for (final Note note : all.values())
      if (!note.referencedSide() && !changesOf(note.key().referringIndex(), note.value()).added().isEmpty()
          && changesOf(note.key().referencedIndex(), note.value()).added().isEmpty())
        for (final RID rid : find(note.key().referencedIndex(), note.value(), Integer.MAX_VALUE))
          files.add(rid.getBucketId());
    return files;
  }

  /** The second check, under the file locks of the commit, against what is committed now. */
  void checkCommitted() {
    // a transaction of its own reads the pages as they are committed, whatever this one holds
    database.begin(Database.TRANSACTION_ISOLATION_LEVEL.READ_COMMITTED);
    try {
      for (final Note note : all.values())
        if (note.referencedSide())
          checkRemovedKey(note);
        else
          checkTakenKey(note);
    } finally {
      database.rollback();
    }
  }

  private void checkTakenKey(final Note note) {
    if (changesOf(note.key().referringIndex(), note.value()).added().isEmpty())
      return;
    final Changes referenced = changesOf(note.key().referencedIndex(), note.value());
    if (!referenced.added().isEmpty())
      // the referenced record is this transaction's own
      return;
    for (final RID rid : find(note.key().referencedIndex(), note.value(), Integer.MAX_VALUE))
      // a record in a file that is not locked can go away before this commit is over
      if (!referenced.removed().contains(rid) && files.contains(rid.getBucketId()))
        return;
    throw conflict(note);
  }

  private void checkRemovedKey(final Note note) {
    final ForeignKey key = note.key().key();
    final Changes referenced = changesOf(note.key().referencedIndex(), note.value());
    if (referenced.added().containsAll(referenced.removed()))
      return;
    final boolean restrict = key.onDelete() == ForeignKey.Action.RESTRICT || key.onUpdate() == ForeignKey.Action.RESTRICT;
    if (!restrict) {
      if (!referenced.added().isEmpty())
        return;
      for (final RID rid : find(note.key().referencedIndex(), note.value(), Integer.MAX_VALUE))
        if (!referenced.removed().contains(rid))
          return;
    }
    final Set<RID> removed = changesOf(note.key().referringIndex(), note.value()).removed();
    for (final RID rid : find(note.key().referringIndex(), note.value(), Integer.MAX_VALUE))
      if (!removed.contains(rid))
        throw conflict(note);
  }

  private static ConcurrentModificationException conflict(final Note note) {
    return new ConcurrentModificationException("A concurrent transaction changed a record that the foreign key '"
        + note.key().key().name() + "' of type '" + note.key().type() + "' depends on. Please retry the operation");
  }

  /** What this transaction has queued for a key of an index, over all the buckets of its type. */
  private Changes changesOf(final TypeIndex index, final Object[] value) {
    final Set<RID> added = new HashSet<>(2);
    final Set<RID> removed = new HashSet<>(2);
    for (final IndexInternal bucketIndex : index.getIndexesOnBuckets()) {
      final var keys = changes.getIndexKeys(bucketIndex.getName());
      if (keys == null)
        continue;
      final var entries = keys.get(new TransactionIndexContext.ComparableKey(bucketIndex.convertLookupKeys(value)));
      if (entries == null)
        continue;
      for (final TransactionIndexContext.IndexKey entry : entries.values()) {
        if (entry.operation == TransactionIndexContext.IndexKey.IndexKeyOperation.REMOVE) {
          if (entry.rid != null)
            removed.add(entry.rid);
        } else if (entry.rid != null)
          added.add(entry.rid);
        if (entry.oldRid != null)
          removed.add(entry.oldRid);
      }
    }
    return new Changes(added, removed);
  }

  /** The records that the current transaction reads under a key of an index, up to {@code limit}. */
  private List<RID> find(final TypeIndex index, final Object[] value, final int limit) {
    final List<RID> found = new ArrayList<>(1);
    try (final IndexCursor cursor = index.get(value)) {
      while (found.size() < limit && cursor.hasNext()) {
        final RID rid = cursor.next().getIdentity();
        if (load(rid) != null)
          found.add(rid);
      }
    } catch (final IllegalArgumentException e) {
      // a value that the types of the index cannot hold is the key of no record
      return List.of();
    }
    return found;
  }

  private Document load(final RID rid) {
    try {
      return (Document) database.lookupByRID(rid, true);
    } catch (final RecordNotFoundException e) {
      return null;
    }
  }

  private static Object[] valueOf(final Document row, final List<String> properties) {
    final Object[] value = new Object[properties.size()];
    for (int i = 0; i < value.length; i++) {
      value[i] = row.get(properties.get(i));
      if (value[i] == null)
        return value;
    }
    return value;
  }

  /** Whether the values a record has now are the key that a note names. */
  private static boolean holds(final TypeIndex index, final Object[] now, final Object[] key) {
    for (final Object part : now)
      if (part == null)
        return false;
    return new TransactionIndexContext.ComparableKey(index.convertLookupKeys(now)).equals(
        new TransactionIndexContext.ComparableKey(index.convertLookupKeys(key)));
  }

  private static String text(final List<String> columns, final Object[] value) {
    final String values = Arrays.toString(value);
    return "(" + String.join(", ", columns) + ")=(" + values.substring(1, values.length() - 1) + ")";
  }
}
