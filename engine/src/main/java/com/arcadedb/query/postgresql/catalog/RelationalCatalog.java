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

import com.arcadedb.database.Database;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.postgresql.PostgreSQLAmbiguousIdentifierException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedSchemaException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.TimeSeriesType;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import com.arcadedb.security.SecurityDatabaseUser;
import com.arcadedb.security.SecurityHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The relational view of a database: its types as tables of the schema {@value #SCHEMA}, with columns, primary keys, unique keys
 * and foreign keys. The binder, {@code information_schema} and the wire catalog all read this one answer, and nothing here depends
 * on the parser.
 * <p>
 * <b>The catalog is a snapshot, built per query.</b> {@link #of(Database)} reads the schema when it is called and the result never
 * changes afterwards: a type or property created after it is visible to the next {@code of} and not to this object. Nothing is
 * cached across calls, because the schema API has nothing to key a cache on: {@code Schema} has no version, and
 * {@code LocalSchema.getVersion()} counts the schema files written, while a save can be postponed after the change that needs it,
 * so it does not move with every change of the schema in memory. A snapshot costs a pass over the types and their properties,
 * which is small next to a query. A caller that holds one across several statements has chosen to ignore schema changes in
 * between.
 * <p>
 * Every type of the schema is a table except time series types (a different shape, no relational view yet). The rules, as decided
 * for the prototype:
 * <ul>
 * <li><b>The {@code id} rule</b> (fork issue #38, option B). A type that declares a property {@code id} has that property as its
 * column {@code id}, and the primary key of its table when a unique index covers that property alone; the record id as text
 * ({@code #12:3}) is then the column {@code rid}, a unique key of the table, and the primary key when the property {@code id} has
 * no unique index. A type with no property {@code id} has the record id as the column {@code id}, the primary key. So a table
 * loaded from a relational schema reads as that schema wrote it, and the record id is one name away.</li>
 * <li><b>The collision rule.</b> A property that has the name of a column the view adds wins its name, as the property {@code id}
 * does: a type that declares a property {@code rid} keeps it as the column {@code rid}, and its record id is {@code id} (when it
 * has no property {@code id}, as it was before). A type that declares both {@code id} and {@code rid} has no record id column
 * (both names are the user's); the edges that end in it still hold its record ids in {@code source_id} and {@code target_id}, and
 * there is no foreign key to it, because no column holds the value to refer to. The one exception is the endpoint columns of an
 * edge table: a property named {@code source_id} or {@code target_id} is shown as {@code source_id_} or {@code target_id_}, as
 * before.</li>
 * <li>An edge table also has {@code source_id} and {@code target_id}, the record ids of the out and the in vertex read from the edge
 * record, whatever the endpoint types call their own key. A bidirectional edge is one row per edge record. A lightweight edge has
 * no record id column: see {@link Table.EdgeStorage}.</li>
 * <li>The foreign keys of an edge table come only from the endpoint tables declared on the edge type ({@link EdgeEndpoints}), never
 * from the data. A {@code LINK} property is a many-to-one foreign key to the table of its target type, under the name of the
 * property. Both refer to the record id column of the other table ({@code id}, or {@code rid} when that type has its own
 * {@code id}), since they hold record ids.</li>
 * <li>Only declared properties are columns. An unknown column is SQLSTATE {@code 42703} with a hint, and an absent property is
 * {@code NULL}.</li>
 * <li>The table of a supertype has the rows of its subtypes, {@code FROM ONLY} has its own.</li>
 * <li>Names are looked up by the rules of {@link Identifier}.</li>
 * </ul>
 */
public final class RelationalCatalog {
  /** The schema every table is in. The name of the database is accepted as an alias of it. */
  public static final String SCHEMA = "public";

  private final String             databaseName;
  private final List<Table>        tables;
  private final Map<String, Table> tablesByName;

  private RelationalCatalog(final String databaseName, final List<Table> tables) {
    this.databaseName = databaseName;
    this.tables = List.copyOf(tables);
    final Map<String, Table> byName = new LinkedHashMap<>();
    for (final Table table : tables)
      byName.put(table.name(), table);
    this.tablesByName = Map.copyOf(byName);
  }

  /** Reads the schema of the database now and builds the catalog from it, see the class comment. */
  public static RelationalCatalog of(final Database database) {
    final Schema schema = database.getSchema();

    final List<DocumentType> types = new ArrayList<>();
    for (final DocumentType type : schema.getTypes())
      if (!(type instanceof TimeSeriesType))
        types.add(type);
    types.sort(Comparator.comparing(DocumentType::getName));

    final Set<String> tableNames = new HashSet<>();
    for (final DocumentType type : types)
      tableNames.add(type.getName());

    final Map<String, Draft> drafts = new LinkedHashMap<>();
    for (final DocumentType type : types)
      drafts.put(type.getName(), draft(type, tableNames));

    final List<Table> tables = new ArrayList<>(drafts.size());
    for (final Draft draft : drafts.values())
      tables.add(table(draft, schema, drafts));
    return new RelationalCatalog(database.getName(), tables);
  }

  /**
   * This catalog as the user bound to the database's context may see it: the tables of the types that user may read, and the
   * foreign keys between them. This catalog itself holds every type, since the binder resolves names against it and every user of
   * the database shares it, so whatever presents it to a caller ({@code information_schema}, the wire catalog) presents this one.
   * With no user bound it is this catalog.
   */
  public RelationalCatalog readableBy(final Database database) {
    final SecurityDatabaseUser user = SecurityHelper.currentUser(database);
    if (user == null)
      return this;

    final Set<String> readable = new HashSet<>();
    for (final DocumentType type : SecurityHelper.readableTypes(database, user))
      readable.add(type.getName());

    final List<Table> visible = new ArrayList<>(tables.size());
    for (final Table table : tables)
      if (readable.contains(table.name()))
        visible.add(table.withForeignKeys(
            table.foreignKeys().stream().filter(key -> readable.contains(key.referencedTable())).toList()));
    return new RelationalCatalog(databaseName, visible);
  }

  public String databaseName() {
    return databaseName;
  }

  /** Every table, sorted by name. */
  public List<Table> tables() {
    return tables;
  }

  /**
   * Whether the schema name is the schema of the tables: {@value #SCHEMA}, or the name of the database. Unquoted names follow the
   * rules of {@link Identifier}.
   */
  public boolean isSchema(final Identifier schema) {
    final Map<String, String> names = new HashMap<>();
    names.put(SCHEMA, SCHEMA);
    names.put(databaseName, SCHEMA);
    return Identifiers.resolve(schema, names, (text, candidates) -> new IllegalStateException(text + " matches " + candidates)) != null;
  }

  /**
   * @throws PostgreSQLUndefinedSchemaException (SQLSTATE {@code 3F000}) when the name is not {@link #isSchema}. The binder answers
   *                                            {@code information_schema} and {@code pg_catalog} before it asks the catalog.
   */
  public void requireSchema(final Identifier schema) {
    if (!isSchema(schema))
      throw new PostgreSQLUndefinedSchemaException(schema.text(), databaseName);
  }

  /**
   * The table the identifier names, {@code null} when there is none.
   *
   * @throws PostgreSQLAmbiguousIdentifierException when an unquoted name matches several tables that differ only in case
   */
  public Table findTable(final Identifier identifier) {
    return Identifiers.resolve(identifier, tablesByName, PostgreSQLAmbiguousIdentifierException::table);
  }

  /**
   * The table the identifier names.
   *
   * @throws PostgreSQLUndefinedTableException      (SQLSTATE {@code 42P01}) when there is none
   * @throws PostgreSQLAmbiguousIdentifierException (SQLSTATE {@code 42P09}) see {@link #findTable}
   */
  public Table table(final Identifier identifier) {
    final Table table = findTable(identifier);
    if (table == null)
      throw new PostgreSQLUndefinedTableException(identifier.text());
    return table;
  }

  /** {@link #table(Identifier)} of an unquoted name. */
  public Table table(final String unquotedName) {
    return table(Identifier.unquoted(unquotedName));
  }

  /** {@code schema.name}: {@link #requireSchema} and then {@link #table(Identifier)}. */
  public Table table(final Identifier schema, final Identifier name) {
    requireSchema(schema);
    return table(name);
  }

  // ---- building ----

  /** A table without its foreign keys, which need every table to exist first. */
  private static final class Draft {
    final DocumentType       type;
    final String             name;
    final Table.Kind         kind;
    final Table.EdgeStorage  storage;
    final List<Column>       columns;
    /** the type a LINK column links to, by column name */
    final Map<String, String> linkTargets;
    final Table.Key          primaryKey;
    final List<Table.Key>    uniqueKeys;
    /** the name of the record id column when it is a key that a foreign key can refer to, else {@code null} */
    final String             referable;
    final List<String>       superTables;
    final List<String>       subTables;

    Draft(final DocumentType type, final Table.Kind kind, final Table.EdgeStorage storage, final List<Column> columns,
        final Map<String, String> linkTargets, final Table.Key primaryKey, final List<Table.Key> uniqueKeys, final String referable,
        final List<String> superTables, final List<String> subTables) {
      this.type = type;
      this.name = type.getName();
      this.kind = kind;
      this.storage = storage;
      this.columns = columns;
      this.linkTargets = linkTargets;
      this.primaryKey = primaryKey;
      this.uniqueKeys = uniqueKeys;
      this.referable = referable;
      this.superTables = superTables;
      this.subTables = subTables;
    }
  }

  private static Draft draft(final DocumentType type, final Set<String> tableNames) {
    final String name = type.getName();
    final Table.Kind kind = type instanceof EdgeType ? Table.Kind.EDGE : type instanceof VertexType ? Table.Kind.VERTEX : Table.Kind.DOCUMENT;
    final EdgeType edgeType = type instanceof EdgeType edge ? edge : null;
    final Table.EdgeStorage storage = edgeType == null ? null : storageOf(edgeType);

    final Map<String, Property> properties = declaredProperties(type);
    // the id rule and its collision rule, see the class comment: a declared property wins the name it has
    final String identity = properties.containsKey(Column.ID) ? (properties.containsKey(Column.RID) ? null : Column.RID) : Column.ID;
    final List<Column> columns = new ArrayList<>();
    final Set<String> reserved = new HashSet<>();
    if (identity != null && storage != Table.EdgeStorage.LIGHTWEIGHT) {
      columns.add(new Column(identity, ColumnType.TEXT, storage == Table.EdgeStorage.MIXED, Column.Kind.IDENTITY, null, false));
      reserved.add(identity);
    }
    if (kind == Table.Kind.EDGE) {
      columns.add(new Column(Column.SOURCE_ID, ColumnType.TEXT, false, Column.Kind.SOURCE, null, false));
      columns.add(new Column(Column.TARGET_ID, ColumnType.TEXT, false, Column.Kind.TARGET, null, false));
      reserved.add(Column.SOURCE_ID);
      reserved.add(Column.TARGET_ID);
    }

    final Set<String> used = new HashSet<>(reserved);
    final Map<String, String> shownByProperty = new HashMap<>();
    final Map<String, String> linkTargets = new HashMap<>();
    for (final Property property : properties.values()) {
      String shown = property.getName();
      if (reserved.contains(shown)) {
        // the column of a declared property that has the name of a synthetic one gets underscores until its name is free
        do
          shown += "_";
        while (reserved.contains(shown) || properties.containsKey(shown) || used.contains(shown));
      }
      used.add(shown);
      shownByProperty.put(property.getName(), shown);

      final boolean link = property.getType() == Type.LINK;
      columns.add(new Column(shown, ColumnType.of(property.getType(), property.getOfType()), !property.isNotNull(), Column.Kind.PROPERTY,
          property.getName(), link));
      if (link && property.getOfType() != null && !property.getOfType().isBlank())
        linkTargets.put(shown, property.getOfType());
    }

    final List<String> superTables = tableNamesOf(type.getSuperTypes(), tableNames);
    final List<String> subTables = tableNamesOf(type.getSubTypes(), tableNames);

    final List<Table.Key> uniqueKeys = new ArrayList<>(
        uniqueKeys(type, name, shownByProperty, edgeType != null && storage == Table.EdgeStorage.REGULAR && edgeType.isUnique()));

    final boolean hasRecordIds = identity != null && (storage == null || storage == Table.EdgeStorage.REGULAR);
    // a unique index on the property id alone
    Table.Key ownId = null;
    if (properties.containsKey(Column.ID))
      for (final Table.Key key : uniqueKeys)
        if (key.columns().equals(List.of(Column.ID)))
          ownId = key;

    final Table.Key primaryKey;
    if (ownId != null && (hasRecordIds || identity == null)) {
      // the property id is the primary key, and the record id, when the view has a column for it, is a unique key of its own
      uniqueKeys.remove(ownId);
      if (hasRecordIds) {
        uniqueKeys.add(new Table.Key(name + "_" + identity + "_key", List.of(identity)));
        uniqueKeys.sort(Comparator.comparing((Table.Key key) -> String.join(",", key.columns())));
      }
      primaryKey = new Table.Key(name + "_pkey", List.of(Column.ID));
      // a primary key is NOT NULL, as it is in PostgreSQL
      for (int i = 0; i < columns.size(); i++)
        if (columns.get(i).kind() == Column.Kind.PROPERTY && Column.ID.equals(columns.get(i).name()))
          columns.set(i, columns.get(i).notNull());
    } else if (hasRecordIds) {
      // the record id is the primary key: id, or rid beside a property id that no unique index covers alone
      primaryKey = new Table.Key(name + "_pkey", List.of(identity));
    } else if (storage == Table.EdgeStorage.LIGHTWEIGHT && edgeType.isUnique() && subTables.isEmpty())
      // the identity of a lightweight edge is (type, out, in), and UNIQUE says there is one per pair. With subtypes, the same
      // pair can be in two of the types, so the table of the supertype cannot promise it
      primaryKey = new Table.Key(name + "_pkey", List.of(Column.SOURCE_ID, Column.TARGET_ID));
    else
      primaryKey = null;

    return new Draft(type, kind, storage, columns, linkTargets, primaryKey, uniqueKeys, hasRecordIds ? identity : null, superTables,
        subTables);
  }

  private static Table table(final Draft draft, final Schema schema, final Map<String, Draft> drafts) {
    final List<Table.ForeignKey> foreignKeys = new ArrayList<>();
    String sourceTable = null;
    String targetTable = null;
    if (draft.kind == Table.Kind.EDGE) {
      final EdgeType edgeType = (EdgeType) draft.type;
      sourceTable = declaredVertexTable(EdgeEndpoints.sourceTable(edgeType), drafts);
      targetTable = declaredVertexTable(EdgeEndpoints.targetTable(edgeType), drafts);
      if (sourceTable != null)
        foreignKeys.add(new Table.ForeignKey(draft.name + "_" + Column.SOURCE_ID + "_fkey", List.of(Column.SOURCE_ID), sourceTable,
            List.of(drafts.get(sourceTable).referable), Table.ForeignKey.Origin.EDGE_SOURCE));
      if (targetTable != null)
        foreignKeys.add(new Table.ForeignKey(draft.name + "_" + Column.TARGET_ID + "_fkey", List.of(Column.TARGET_ID), targetTable,
            List.of(drafts.get(targetTable).referable), Table.ForeignKey.Origin.EDGE_TARGET));
    }

    for (final Column column : draft.columns) {
      final String linkedType = draft.linkTargets.get(column.name());
      if (linkedType == null)
        continue;
      final DocumentType target = schema.getTypeOrNull(linkedType);
      final Draft targetDraft = target == null ? null : drafts.get(target.getName());
      // a link to a type that does not exist, or that has no record id column to refer to, is a column and not a foreign key
      if (targetDraft != null && targetDraft.referable != null)
        foreignKeys.add(new Table.ForeignKey(draft.name + "_" + column.name() + "_fkey", List.of(column.name()), targetDraft.name,
            List.of(targetDraft.referable), Table.ForeignKey.Origin.LINK_PROPERTY));
    }

    final List<String> walkFrom = new ArrayList<>();
    if (draft.storage != null && draft.storage != Table.EdgeStorage.REGULAR) {
      if (sourceTable != null)
        walkFrom.add(sourceTable);
      else
        // the drafts are in the order of the names, so is this
        for (final Draft other : drafts.values())
          if (other.kind == Table.Kind.VERTEX && other.superTables.isEmpty())
            walkFrom.add(other.name);
    }

    return new Table(draft.name, draft.kind, draft.type, draft.columns, draft.primaryKey, draft.uniqueKeys, foreignKeys,
        draft.superTables, draft.subTables, draft.storage, sourceTable, targetTable, walkFrom);
  }

  /**
   * The vertex table that a declared endpoint names, {@code null} when nothing is declared or the name is not a vertex table with a
   * record id column to refer to. The name is the type name: exact first, else the one name that differs only in case, so a declaration written in
   * the case of a statement still finds {@code Person}; ambiguity is no match, a declaration is never guessed.
   */
  private static String declaredVertexTable(final String declared, final Map<String, Draft> drafts) {
    if (declared == null)
      return null;
    Draft draft = drafts.get(declared);
    if (draft == null) {
      for (final Draft candidate : drafts.values())
        if (candidate.name.equalsIgnoreCase(declared)) {
          if (draft != null)
            return null;
          draft = candidate;
        }
    }
    return draft != null && draft.kind == Table.Kind.VERTEX && draft.referable != null ? draft.name : null;
  }

  private static Table.EdgeStorage storageOf(final EdgeType type) {
    if (!EdgeType.holdsLightweightEdges(type))
      return Table.EdgeStorage.REGULAR;
    return allLightweight(type) ? Table.EdgeStorage.LIGHTWEIGHT : Table.EdgeStorage.MIXED;
  }

  private static boolean allLightweight(final DocumentType type) {
    if (!(type instanceof EdgeType edge) || !edge.isLightweight())
      return false;
    for (final DocumentType subType : type.getSubTypes())
      if (!allLightweight(subType))
        return false;
    return true;
  }

  /**
   * The properties that are columns: the declared ones, the supertypes' first, then the type's own, each group by name. The
   * {@code @out} and {@code @in} properties that a {@code UNIQUE} edge type declares for its index are metadata, not columns.
   */
  private static Map<String, Property> declaredProperties(final DocumentType type) {
    final Map<String, Property> properties = new LinkedHashMap<>();
    for (final DocumentType superType : type.getSuperTypes())
      for (final Map.Entry<String, Property> inherited : declaredProperties(superType).entrySet())
        properties.putIfAbsent(inherited.getKey(), inherited.getValue());

    final List<Property> own = new ArrayList<>(type.getProperties());
    own.sort(Comparator.comparing(Property::getName));
    for (final Property property : own)
      if (!property.getName().startsWith("@"))
        properties.putIfAbsent(property.getName(), property);
    return properties;
  }

  private static List<String> tableNamesOf(final List<DocumentType> types, final Set<String> tableNames) {
    final List<String> names = new ArrayList<>(types.size());
    for (final DocumentType type : types)
      if (tableNames.contains(type.getName()))
        names.add(type.getName());
    names.sort(String::compareTo);
    return names;
  }

  /**
   * The unique keys: unique key indexes (LSM tree or hash, not full text, vector, geospatial or columnar) whose properties are all
   * columns, and the endpoint pair of a regular edge type declared UNIQUE.
   */
  private static List<Table.Key> uniqueKeys(final DocumentType type, final String table, final Map<String, String> shownByProperty,
      final boolean uniqueEndpoints) {
    final Set<List<String>> keys = new LinkedHashSet<>();
    for (final TypeIndex index : type.getAllIndexes(true)) {
      try {
        final Schema.INDEX_TYPE indexType = index.getType();
        if ((indexType != Schema.INDEX_TYPE.LSM_TREE && indexType != Schema.INDEX_TYPE.HASH) || !index.isUniqueIfPresent())
          continue;
        final List<String> columns = new ArrayList<>();
        for (final String property : index.getPropertyNames()) {
          final String shown = shownByProperty.get(property);
          if (shown == null) {
            columns.clear();
            break;
          }
          columns.add(shown);
        }
        if (!columns.isEmpty())
          keys.add(columns);
      } catch (final RuntimeException e) {
        // an index that a concurrent DDL is creating or dropping is not a key of this snapshot
      }
    }
    if (uniqueEndpoints)
      keys.add(List.of(Column.SOURCE_ID, Column.TARGET_ID));

    final List<List<String>> sorted = new ArrayList<>(keys);
    sorted.sort(Comparator.comparing((List<String> columns) -> String.join(",", columns)));
    final List<Table.Key> result = new ArrayList<>(sorted.size());
    for (final List<String> columns : sorted)
      result.add(new Table.Key(table + "_" + String.join("_", columns) + "_key", columns));
    return result;
  }
}
