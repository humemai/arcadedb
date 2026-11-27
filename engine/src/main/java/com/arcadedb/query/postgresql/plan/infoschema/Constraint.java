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
package com.arcadedb.query.postgresql.plan.infoschema;

import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A constraint of the relational view, as the four constraint views of {@code information_schema} list it. The catalog already
 * names its keys the way PostgreSQL names the keys that a {@code CREATE TABLE} creates, so the names are a rule and not a counter:
 * <ul>
 * <li>primary key: {@code <table>_pkey};</li>
 * <li>unique key: {@code <table>_<column>_<column>_key}, the columns in the order of the key (the record id of a table whose property
 * {@code id} is its primary key is the unique key {@code <table>_rid_key});</li>
 * <li>foreign key: {@code <table>_<column>_fkey}, the column being the {@code LINK} property, {@code source_id} or
 * {@code target_id}.</li>
 * </ul>
 * The names depend on the table and the columns only, so they are the same on every call and every run. Two different types can
 * still produce one name ({@code a_b} with the property {@code c} and {@code a} with the property {@code b_c} both give a
 * unique key {@code a_b_c_key}); the views list both, as a server that was given the same two names would have refused to.
 *
 * @param name              the constraint name
 * @param table             the table that has the constraint
 * @param type              {@code PRIMARY KEY}, {@code UNIQUE} or {@code FOREIGN KEY}, as {@code table_constraints.constraint_type} spells it
 * @param columns           the constrained columns, in key order
 * @param referencedTable   the table a foreign key refers to, {@code null} for a primary or unique key
 * @param referencedColumns the columns of the referenced table, in the order of the referenced key; empty for a primary or unique key
 * @param referencedKey     the name of the primary or unique key that a foreign key refers to, {@code null} for a primary or unique key
 * @param cascadesOnDelete  whether deleting the referenced row deletes the referencing one, see {@link Table.ForeignKey#cascadesOnDelete()}
 * @param enforced          whether the engine holds the constraint, see {@link Table.ForeignKey#enforced()}; a primary or unique key is held
 */
record Constraint(String name, String table, String type, List<String> columns, String referencedTable,
    List<String> referencedColumns, String referencedKey, boolean cascadesOnDelete, boolean enforced) {

  static final String PRIMARY_KEY = "PRIMARY KEY";
  static final String UNIQUE      = "UNIQUE";
  static final String FOREIGN_KEY = "FOREIGN KEY";

  boolean isForeignKey() {
    return FOREIGN_KEY.equals(type);
  }

  private static Constraint key(final String table, final String type, final Table.Key key) {
    return new Constraint(key.name(), table, type, key.columns(), null, List.of(), null, false, true);
  }

  /**
   * Every constraint of the catalog: the tables in the order of the catalog (by name), and for each table its primary key, its
   * unique keys and its foreign keys, in the order the catalog gives them.
   */
  static List<Constraint> of(final RelationalCatalog catalog) {
    final Map<String, Table> byName = new HashMap<>();
    for (final Table table : catalog.tables())
      byName.put(table.name(), table);
    final List<Constraint> out = new ArrayList<>();
    for (final Table table : catalog.tables()) {
      if (table.primaryKey() != null)
        out.add(key(table.name(), PRIMARY_KEY, table.primaryKey()));
      for (final Table.Key key : table.uniqueKeys())
        out.add(key(table.name(), UNIQUE, key));
      for (final Table.ForeignKey foreignKey : table.foreignKeys()) {
        final Table referenced = byName.get(foreignKey.referencedTable());
        // a key an end refers to can be a unique index that CREATE UNIQUE INDEX made, which is no constraint
        final Table.Key key = referenced.keyOn(foreignKey.referencedColumns());
        out.add(new Constraint(foreignKey.name(), table.name(), FOREIGN_KEY, foreignKey.columns(), referenced.name(),
            foreignKey.referencedColumns(), key == null ? null : key.name(), foreignKey.cascadesOnDelete(), foreignKey.enforced()));
      }
    }
    return out;
  }
}
