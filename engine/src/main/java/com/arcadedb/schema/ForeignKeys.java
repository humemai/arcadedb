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
package com.arcadedb.schema;

import com.arcadedb.database.Document;
import com.arcadedb.database.Record;
import com.arcadedb.exception.ForeignKeyException;
import com.arcadedb.index.TypeIndex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The foreign keys of a schema (fork issue #228), each with the two indexes that hold it, as the types and indexes were when the
 * schema last changed them. A key whose indexes or referenced type are gone cannot be checked: every write of its two types is
 * refused until it can, so that no row breaks it unseen.
 */
public final class ForeignKeys {
  /**
   * A key of a type with its indexes.
   *
   * @param type            the referring type, which declares the key
   * @param key             the declaration
   * @param referringIndex  the index of the referring type over the referring columns, in the order of the referenced index
   * @param referencedIndex the unique index of the referenced type over the referenced columns
   */
  public record Resolved(String type, ForeignKey key, TypeIndex referringIndex, TypeIndex referencedIndex) {
  }

  /** What an index is for a key: the index of the referenced columns ({@code referencedSide}) or of the referring ones. */
  public record Role(Resolved key, boolean referencedSide) {
  }

  /** A declaration that cannot be checked: the types whose writes it refuses, and why. */
  private record Broken(String type, String referencedType, String reason) {
  }

  private final long                    serial;
  private final List<Resolved>          keys   = new ArrayList<>();
  private final List<Broken>            broken = new ArrayList<>();
  private final Map<String, List<Role>> roles  = new HashMap<>();

  private ForeignKeys(final long serial) {
    this.serial = serial;
  }

  static ForeignKeys of(final long serial, final Collection<? extends DocumentType> types) {
    final ForeignKeys result = new ForeignKeys(serial);
    Map<String, DocumentType> byName = null;
    for (final DocumentType type : types) {
      final Object value = type.getCustomValue(ForeignKey.CUSTOM_KEY);
      if (value == null)
        continue;
      if (byName == null) {
        byName = new LinkedHashMap<>();
        for (final DocumentType each : types)
          byName.put(each.getName(), each);
      }
      final List<ForeignKey> declared;
      try {
        declared = ForeignKey.fromJson(value.toString());
      } catch (final RuntimeException e) {
        result.broken.add(new Broken(type.getName(), null,
            "The custom value " + ForeignKey.CUSTOM_KEY + " of type '" + type.getName() + "' is not a list of foreign keys"));
        continue;
      }
      for (final ForeignKey key : declared)
        result.resolve(type, key, byName.get(key.referencedType()));
    }
    return result;
  }

  private void resolve(final DocumentType type, final ForeignKey key, final DocumentType referenced) {
    final String what = "The foreign key '" + key.name() + "' of type '" + type.getName() + "' cannot be checked: ";
    if (referenced == null) {
      broken.add(new Broken(type.getName(), key.referencedType(), what + "the type '" + key.referencedType() + "' does not exist"));
      return;
    }
    TypeIndex referencedIndex = null;
    for (final TypeIndex index : referenced.getAllIndexes(false))
      if (answersEquality(index) && index.isUnique() && index.getPropertyNames().size() == key.referencedColumns().size() && index.getPropertyNames()
          .containsAll(key.referencedColumns()) && Set.copyOf(key.referencedColumns()).size() == key.referencedColumns().size()) {
        referencedIndex = index;
        break;
      }
    if (referencedIndex == null) {
      broken.add(new Broken(type.getName(), referenced.getName(), what + "the type '" + referenced.getName()
          + "' has no unique index over " + key.referencedColumns() + ": create it again"));
      return;
    }
    final List<String> columns = key.columnsInKeyOrder(referencedIndex.getPropertyNames());
    TypeIndex referringIndex = null;
    for (final TypeIndex index : type.getAllIndexes(false))
      if (answersEquality(index) && index.getPropertyNames().equals(columns)) {
        referringIndex = index;
        break;
      }
    if (referringIndex == null) {
      broken.add(new Broken(type.getName(), referenced.getName(),
          what + "the type '" + type.getName() + "' has no index over " + columns + ": create it again"));
      return;
    }
    final Resolved resolved = new Resolved(type.getName(), key, referringIndex, referencedIndex);
    keys.add(resolved);
    roles.computeIfAbsent(referringIndex.getName(), name -> new ArrayList<>(2)).add(new Role(resolved, false));
    roles.computeIfAbsent(referencedIndex.getName(), name -> new ArrayList<>(2)).add(new Role(resolved, true));
  }

  private static boolean answersEquality(final TypeIndex index) {
    return index.getType() == Schema.INDEX_TYPE.LSM_TREE || index.getType() == Schema.INDEX_TYPE.HASH;
  }

  long serial() {
    return serial;
  }

  /** Whether the schema declares no key at all, which is all a write of such a database asks. */
  public boolean isEmpty() {
    return keys.isEmpty() && broken.isEmpty();
  }

  /** The keys that a type index holds, empty for most. */
  public List<Role> rolesOf(final String typeIndexName) {
    final List<Role> found = roles.get(typeIndexName);
    return found == null ? List.of() : found;
  }

  /** Refuses a write of a record of a type that a key which cannot be checked refers from or to. */
  public void checkWritable(final Record record) {
    if (broken.isEmpty() || !(record instanceof Document document))
      return;
    final DocumentType type = document.getType();
    if (type == null)
      return;
    for (final Broken each : broken)
      if (type.instanceOf(each.type()) || each.referencedType() != null && type.instanceOf(each.referencedType()))
        throw new ForeignKeyException("55000", each.reason() + ", or remove the key with ALTER TYPE `" + each.type()
            + "` CUSTOM " + ForeignKey.CUSTOM_KEY + " = null (sql)");
  }

  /** Refuses the drop of a type that another type refers to. */
  void checkDroppable(final String typeName) {
    for (final Resolved each : keys)
      if (each.key().referencedType().equals(typeName) && !each.type().equals(typeName))
        throw new ForeignKeyException("2BP01", "cannot drop table " + typeName + " because other objects depend on it: constraint "
            + each.key().name() + " on table " + each.type() + " depends on table " + typeName);
  }

  /** Refuses the rename of a type that a key refers to, because the declaration holds the name. */
  void checkRenamable(final String typeName) {
    for (final Resolved each : keys)
      if (each.key().referencedType().equals(typeName))
        throw new ForeignKeyException("2BP01", "cannot rename type " + typeName + " because the foreign key " + each.key().name()
            + " of type " + each.type() + " refers to it by name");
  }
}
