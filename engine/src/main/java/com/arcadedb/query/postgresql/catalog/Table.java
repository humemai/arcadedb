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
package com.arcadedb.query.postgresql.catalog;

import com.arcadedb.query.postgresql.PostgreSQLAmbiguousIdentifierException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedColumnException;
import com.arcadedb.schema.DocumentType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A type of the schema as a table of the relational view. A {@code Table} is an immutable snapshot, built by
 * {@link RelationalCatalog}.
 * <ul>
 * <li>A document or vertex type is a table of {@code id} and one column per declared property.</li>
 * <li>An edge type is a table of {@code id}, {@code source_id}, {@code target_id} and one column per declared property, one row per
 * edge record whether or not the type is bidirectional. A lightweight edge has no record, so a table of lightweight edges has no
 * {@code id}: see {@link EdgeStorage}.</li>
 * <li>The columns are the synthetic ones first, then the properties of the supertypes in the order of the supertypes, then the
 * properties of the type itself, each group sorted by name. The engine keeps declared properties in a hash map and records no
 * declaration order, so this is the only order that is the same for every database and every run.</li>
 * <li>A table made by {@code CREATE TABLE} has its columns in the declared order, which its {@link TableDefinition} keeps, then
 * the record id, which is hidden: {@link #insertColumns()} leaves it out, and {@link #listedColumns()} has it only when a foreign
 * key refers to it.</li>
 * <li>The table of a supertype has the rows of its subtypes, and only the columns declared on it. {@code FROM ONLY t} has the rows of
 * {@code t} itself: {@link #superTables()} and {@link #subTables()} say how the tables are related, and {@link #documentType()} is
 * what a scan asks for the buckets of the type with and without its subtypes.</li>
 * <li>A subtype table has the columns of its supertypes too, and its foreign keys with them.</li>
 * </ul>
 */
public final class Table {
  public enum Kind {
    DOCUMENT, VERTEX, EDGE
  }

  /**
   * How the edges of an edge table are stored, which decides whether the table has an {@code id} and how a scan has to read it.
   * The rule is the engine's own ({@code EdgeType.holdsLightweightEdges}): a scan of the buckets of a type can miss edges that the
   * graph holds when the type or one of its subtypes stores them without a record.
   */
  public enum EdgeStorage {
    /** Every edge has a record: the table has {@code id}, its primary key, and a bucket scan reads all of it. */
    REGULAR,
    /** Every edge is lightweight: no {@code id}, only {@code source_id} and {@code target_id}, and only a walk reads all of it. */
    LIGHTWEIGHT,
    /** Some edges have a record and some do not (a type with a lightweight subtype, or a regular type with lightweight subtypes):
     * {@code id} is nullable and has no primary key, and only a walk reads all of it. */
    MIXED
  }

  /** How a scan has to read an edge table. */
  public enum EdgeScan {
    /** Scan the buckets of the edge type, polymorphic or not. */
    BUCKETS,
    /** Walk the outgoing edges of the vertices of {@link Table#walkFromTables()}: a bucket scan would miss lightweight edges. */
    VERTEX_WALK
  }

  /** A primary key or a unique key: a name, as PostgreSQL would name it, and the columns. */
  public record Key(String name, List<String> columns) {
  }

  /**
   * A foreign key: the {@code columns} of this table refer to the {@code referencedColumns} of the {@code referencedTable}, which
   * are a key of that table: the identity column ({@link Table#identityColumn()}), which is the primary key of a table that has no
   * property {@code id} of its own and a unique key of one that has. The columns of a foreign key hold record ids, so it refers to
   * the record id column under whatever name the table gives it ({@code id} or {@code rid}).
   */
  public record ForeignKey(String name, List<String> columns, String referencedTable, List<String> referencedColumns, Origin origin) {
    public enum Origin {
      /** A {@code LINK} property: a many-to-one reference to the type it links to. */
      LINK_PROPERTY,
      /** {@code source_id}, from the declared source table of the edge type. */
      EDGE_SOURCE,
      /** {@code target_id}, from the declared target table of the edge type. */
      EDGE_TARGET
    }
  }

  private final String              name;
  private final Kind                kind;
  private final DocumentType        documentType;
  private final List<Column>        columns;
  private final Map<String, Column> columnsByName;
  private final Key                 primaryKey;
  private final List<Key>           uniqueKeys;
  private final List<ForeignKey>    foreignKeys;
  private final List<String>        superTables;
  private final List<String>        subTables;
  private final EdgeStorage         edgeStorage;
  private final String              sourceTable;
  private final String              targetTable;
  private final List<String>        walkFromTables;
  private final Column              identityColumn;

  Table(final String name, final Kind kind, final DocumentType documentType, final List<Column> columns, final Key primaryKey,
      final List<Key> uniqueKeys, final List<ForeignKey> foreignKeys, final List<String> superTables, final List<String> subTables,
      final EdgeStorage edgeStorage, final String sourceTable, final String targetTable, final List<String> walkFromTables) {
    this.name = Objects.requireNonNull(name);
    this.kind = Objects.requireNonNull(kind);
    this.documentType = Objects.requireNonNull(documentType);
    this.columns = List.copyOf(columns);
    final Map<String, Column> byName = new LinkedHashMap<>();
    for (final Column column : columns)
      byName.put(column.name(), column);
    this.columnsByName = Map.copyOf(byName);
    this.primaryKey = primaryKey;
    this.uniqueKeys = List.copyOf(uniqueKeys);
    this.foreignKeys = List.copyOf(foreignKeys);
    this.superTables = List.copyOf(superTables);
    this.subTables = List.copyOf(subTables);
    this.edgeStorage = edgeStorage;
    this.sourceTable = sourceTable;
    this.targetTable = targetTable;
    this.walkFromTables = List.copyOf(walkFromTables);
    Column identity = null;
    for (final Column column : columns)
      if (column.kind() == Column.Kind.IDENTITY)
        identity = column;
    this.identityColumn = identity;
  }

  /** This table with only these of its foreign keys. */
  Table withForeignKeys(final List<ForeignKey> keys) {
    if (keys.size() == foreignKeys.size())
      return this;
    return new Table(name, kind, documentType, columns, primaryKey, uniqueKeys, keys, superTables, subTables, edgeStorage,
        sourceTable, targetTable, walkFromTables);
  }

  /** The name of the table: the name of its type, exactly. */
  public String name() {
    return name;
  }

  public Kind kind() {
    return kind;
  }

  /** The type behind the table, for the layer that reads its records: its buckets with and without its subtypes. */
  public DocumentType documentType() {
    return documentType;
  }

  /** The columns in order, see the class comment. */
  public List<Column> columns() {
    return columns;
  }

  /**
   * The column that holds the record id as text: {@code id}, or {@code rid} when the type has a property of its own named
   * {@code id} (that property is then the column {@code id}, the decision of fork issue #38). {@code null} when the table has none:
   * a lightweight edge table (no record), and a table whose type has both a property {@code id} and a property {@code rid}, where
   * the two names are the user's and the record id has no name in the view.
   */
  public Column identityColumn() {
    return identityColumn;
  }

  /**
   * The primary key or the unique key over exactly these columns, in this order; {@code null} when there is none. A record id that
   * the catalog does not list ({@link Column#isListed()}) is unique too, although no view lists it as a key.
   */
  public Key keyOn(final List<String> keyColumns) {
    if (primaryKey != null && primaryKey.columns().equals(keyColumns))
      return primaryKey;
    for (final Key key : uniqueKeys)
      if (key.columns().equals(keyColumns))
        return key;
    if (identityColumn != null && !identityColumn.isListed() && keyColumns.equals(List.of(identityColumn.name())))
      return new Key(name + "_" + identityColumn.name() + "_key", keyColumns);
    return null;
  }

  /** The columns that the catalog views show ({@link Column#isListed()}). */
  public List<Column> listedColumns() {
    return identityColumn != null && !identityColumn.isListed() ? columns.stream().filter(Column::isListed).toList() : columns;
  }

  /** The columns that an {@code INSERT} without a column list fills, in order: every column but a hidden one. */
  public List<Column> insertColumns() {
    return identityColumn != null && identityColumn.isHidden() ? columns.stream().filter(column -> !column.isHidden()).toList()
        : columns;
  }

  /**
   * The column the identifier names, {@code null} when there is none.
   *
   * @throws PostgreSQLAmbiguousIdentifierException when an unquoted name matches several columns that differ only in case
   */
  public Column findColumn(final Identifier identifier) {
    return Identifiers.resolve(identifier, columnsByName, PostgreSQLAmbiguousIdentifierException::column);
  }

  /**
   * The column the identifier names.
   *
   * @throws PostgreSQLUndefinedColumnException      (SQLSTATE {@code 42703}, with a hint) when the table has no such column, which
   *                                                 includes a property that records carry and the schema does not declare
   * @throws PostgreSQLAmbiguousIdentifierException  (SQLSTATE {@code 42702}) see {@link #findColumn}
   */
  public Column column(final Identifier identifier) {
    final Column column = findColumn(identifier);
    if (column == null)
      throw new PostgreSQLUndefinedColumnException(identifier.text(), name, columns.stream().map(Column::name).toList());
    return column;
  }

  /** {@link #column(Identifier)} of an unquoted name. */
  public Column column(final String unquotedName) {
    return column(Identifier.unquoted(unquotedName));
  }

  /**
   * The primary key, {@code null} when the table has none. For a document, a vertex and an edge with records: the property
   * {@code id} when the type has one with a unique index on it alone, else the identity column ({@code id} or {@code rid}, see
   * {@link #identityColumn()}); {@code (source_id, target_id)} for a lightweight edge table of a type declared {@code UNIQUE} that
   * has no subtypes; none for any other lightweight edge table, for a {@link EdgeStorage#MIXED} one and for a table that has no
   * identity column.
   */
  public Key primaryKey() {
    return primaryKey;
  }

  /**
   * The unique keys that are not the primary key: the unique indexes of the type on declared properties (key indexes only, not
   * full text, vector, geospatial or columnar ones), {@code (source_id, target_id)} for a regular edge type declared
   * {@code UNIQUE}, and the identity column when the primary key is the property {@code id}: the record id is unique too, and the
   * foreign keys of the edge tables and of the {@code LINK} properties refer to it.
   */
  public List<Key> uniqueKeys() {
    return uniqueKeys;
  }

  /**
   * The foreign keys, in the order {@code source_id}, {@code target_id}, then the {@code LINK} columns in column order.
   * The two keys of an edge table come only from the endpoint tables declared on the edge type ({@link EdgeEndpoints}), never from
   * the data: there is none for an endpoint that is not declared, or that names a type that is not a vertex type. A {@code LINK}
   * property gives one when the type it links to is declared and is a table with an identity column that is a key. Both refer to
   * the identity column of the other table ({@code rid} when that type has its own property {@code id}).
   */
  public List<ForeignKey> foreignKeys() {
    return foreignKeys;
  }

  /** The names of the tables of the direct supertypes. */
  public List<String> superTables() {
    return superTables;
  }

  /** The names of the tables of the direct subtypes: the rows of these are in this table too, and not in {@code FROM ONLY}. */
  public List<String> subTables() {
    return subTables;
  }

  /** How the edges are stored, {@code null} for a table that is not an edge table. */
  public EdgeStorage edgeStorage() {
    return edgeStorage;
  }

  /**
   * How a scan has to read the table, {@code null} for a table that is not an edge table: the buckets for an edge table with
   * records only, a walk over vertices as soon as a lightweight edge is possible, because a bucket scan would miss them.
   * This is data for the scan layer, which implements both.
   */
  public EdgeScan edgeScan() {
    return edgeStorage == null ? null : edgeStorage == EdgeStorage.REGULAR ? EdgeScan.BUCKETS : EdgeScan.VERTEX_WALK;
  }

  /**
   * The vertex tables whose outgoing edges a {@link EdgeScan#VERTEX_WALK} has to walk: the declared source table when there is one,
   * else every vertex table that has no vertex supertype (a walk over a supertype table reads its subtypes too). Empty when the
   * scan is {@link EdgeScan#BUCKETS} and for a table that is not an edge table.
   */
  public List<String> walkFromTables() {
    return walkFromTables;
  }

  /** The declared source table of an edge table, {@code null} when it is not declared (or not a vertex table). */
  public String sourceTable() {
    return sourceTable;
  }

  /** The declared target table of an edge table, {@code null} when it is not declared (or not a vertex table). */
  public String targetTable() {
    return targetTable;
  }

  @Override
  public String toString() {
    return kind + " " + name + " " + columns;
  }
}
