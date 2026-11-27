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
import com.arcadedb.query.postgresql.PostgreSQLError;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedSchemaException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.TimeSeriesType;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.TypeModifier;
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
 * <li><b>The record id is {@code rid}</b> (fork issue #240, which replaces the choice of #38). The record id as text
 * ({@code #12:3}) is the column {@code rid} of every table, however its type was made, and {@code id} is only ever a property
 * that the type declares: a type without one has no column {@code id}. When the type declares a property {@code id}, that property
 * is the column {@code id}, and the primary key of its table when a unique index covers it alone; the record id is then a unique
 * key of the table. Otherwise the record id is the primary key.</li>
 * <li><b>The collision rule.</b> A property that has the name of a column the view adds wins its name: a type that declares a
 * property {@code rid} keeps it as the column {@code rid}, and its record id has no column. The edges that end in it still hold
 * its record ids in {@code source_id} and {@code target_id}, and there is no foreign key to it, because no column holds the value
 * to refer to. The one exception is the endpoint columns of an edge table: a property named {@code source_id} or
 * {@code target_id} is shown as {@code source_id_} or {@code target_id_}.</li>
 * <li>An edge table also has {@code source_id} and {@code target_id}, the record ids of the out and the in vertex read from the edge
 * record, whatever the endpoint types call their own key. A bidirectional edge is one row per edge record. A lightweight edge has
 * no record id column: see {@link Table.EdgeStorage}.</li>
 * <li>The foreign keys of an edge table come only from the endpoint tables declared on the edge type ({@link EdgeEndpoints}), never
 * from the data. A {@code LINK} property is a many-to-one foreign key to the table of its target type, under the name of the
 * property. Both refer to the record id column {@code rid} of the other table, since they hold record ids. An end of an edge
 * table that declares a column of the other table as well (fork issue #213, {@link EndKey}) refers to that column, which has to
 * be a unique key alone, and has its value and type; the engine still stores the link between the records. A declaration of a
 * column that cannot be followed leaves the table refused ({@link Table#broken()}) rather than showing record ids.</li>
 * <li>Only declared properties are columns. An unknown column is SQLSTATE {@code 42703} with a hint, and an absent property is
 * {@code NULL}.</li>
 * <li>The table of a supertype has the rows of its subtypes, {@code FROM ONLY} has its own.</li>
 * <li>Names are looked up by the rules of {@link Identifier}.</li>
 * <li><b>A table made by {@code CREATE TABLE}</b> (fork issue #118) has a {@link TableDefinition}, and is shown as it declared
 * itself: the columns in the declared order, {@code text} where it said so, its defaults, and the declared primary key and unique
 * constraints under their names, with no primary key when it declared none. Its record id {@code rid} comes last and is hidden
 * from {@code *} and from an {@code INSERT} without a column list ({@link Column#isHidden()}). The catalog lists it, as a unique
 * key, only when a foreign key refers to it ({@link Column#isListed()}). A type without a definition has its record id first, in
 * {@code *} and in the catalog.</li>
 * </ul>
 */
public final class RelationalCatalog {
  /** The schema every table is in. The name of the database is accepted as an alias of it. */
  public static final String SCHEMA = "public";

  /** How the text of the default of a {@code serial} column starts. */
  private static final String SEQUENCE_CALL = "nextval(";

  /** The types a column that an end of a link table refers to can have, those of the keys of {@code CREATE TABLE}. */
  public static final Set<ColumnType> KEY_TYPES = Set.of(ColumnType.BOOL, ColumnType.INT2, ColumnType.INT4, ColumnType.INT8,
      ColumnType.NUMERIC, ColumnType.FLOAT4, ColumnType.FLOAT8, ColumnType.TEXT, ColumnType.VARCHAR, ColumnType.DATE,
      ColumnType.TIMESTAMP, ColumnType.TIMESTAMPTZ, ColumnType.UUID);

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
      // the counters of the serial and identity columns are no table of the view (fork issue #221)
      if (!(type instanceof TimeSeriesType) && !Sequences.TYPE_NAME.equals(type.getName()))
        types.add(type);
    types.sort(Comparator.comparing(DocumentType::getName));

    final Set<String> tableNames = new HashSet<>();
    for (final DocumentType type : types)
      tableNames.add(type.getName());

    final Map<String, Draft> drafts = new LinkedHashMap<>();
    for (final DocumentType type : types)
      drafts.put(type.getName(), draft(type, tableNames));

    // the tables whose record id a foreign key refers to: they list it, see the class comment. And the tables whose key an end of
    // an edge table refers to
    final Set<String> referenced = new HashSet<>();
    final Set<String> referencedByKey = new HashSet<>();
    final Map<String, List<End>> ends = new HashMap<>();
    for (final Draft draft : drafts.values()) {
      if (draft.kind == Table.Kind.EDGE) {
        final List<End> pair = List.of(end(draft, Column.Kind.SOURCE, drafts), end(draft, Column.Kind.TARGET, drafts));
        ends.put(draft.name, pair);
        for (final End end : pair)
          if (end.key() != null)
            referencedByKey.add(end.key().table());
          else if (end.table() != null)
            referenced.add(end.table());
      }
      for (final String linkedType : draft.linkTargets.values()) {
        final DocumentType target = schema.getTypeOrNull(linkedType);
        if (target != null)
          referenced.add(target.getName());
      }
    }

    final List<Table> tables = new ArrayList<>(drafts.size());
    for (final Draft draft : drafts.values())
      tables.add(table(draft, schema, drafts, referenced.contains(draft.name), ends.get(draft.name),
          referencedByKey.stream().anyMatch(draft.type::instanceOf)));
    return new RelationalCatalog(database.getName(), tables);
  }

  /**
   * Whether the property alone is a unique key of the type: a unique key index (LSM tree or hash) of the type or of a supertype
   * over exactly this property. Only such a column can be what an end of a link table refers to (fork issue #213).
   */
  public static boolean hasUniqueKey(final DocumentType type, final String property) {
    for (final TypeIndex index : type.getAllIndexes(true)) {
      try {
        final Schema.INDEX_TYPE indexType = index.getType();
        if ((indexType == Schema.INDEX_TYPE.LSM_TREE || indexType == Schema.INDEX_TYPE.HASH) && index.isUniqueIfPresent()
            && index.getPropertyNames().equals(List.of(property)))
          return true;
      } catch (final RuntimeException e) {
        // an index that a concurrent DDL is creating or dropping is not a key of this snapshot
      }
    }
    return false;
  }

  /**
   * What one end of an edge table refers to.
   *
   * @param table  the vertex table, {@code null} when none is declared or the declaration cannot be followed
   * @param key    the key it refers to, {@code null} for the record id
   * @param column the column of that key as its table shows it, {@code null} for the record id
   * @param broken why a declaration of a key cannot be followed, {@code null} when it can or there is none
   */
  private record End(String table, EndKey key, Column column, String broken) {
  }

  /** The declaration of one end of an edge table, see {@link EdgeEndpoints}. */
  private static End end(final Draft draft, final Column.Kind kind, final Map<String, Draft> drafts) {
    final EdgeType type = (EdgeType) draft.type;
    final boolean source = kind == Column.Kind.SOURCE;
    final String declared = source ? EdgeEndpoints.sourceTable(type) : EdgeEndpoints.targetTable(type);
    final String columnKey = source ? EdgeEndpoints.SOURCE_COLUMN_KEY : EdgeEndpoints.TARGET_COLUMN_KEY;
    final String named = EdgeEndpoints.declared(type, columnKey);
    if (named == null)
      return new End(declaredVertexTable(declared, drafts), null, null, null);

    // a declared key is followed or the table is refused: record ids in its place would be another answer to the same statement
    final Draft target = declaredType(declared, drafts);
    if (target == null || target.kind != Table.Kind.VERTEX)
      return new End(null, null, null, "the edge type " + draft.name + " declares " + columnKey + " = '" + named + "', and "
          + (source ? EdgeEndpoints.SOURCE_KEY : EdgeEndpoints.TARGET_KEY) + " names no vertex type" + PostgreSQLError.HINT
          + "Remove the declaration, so that the end shows record ids, in ArcadeDB sql: " + removal(draft, columnKey));
    final Column column = columnNamed(target, named);
    if (column != null && column.kind() == Column.Kind.IDENTITY)
      return new End(target.referable == null ? null : target.name, null, null, null);

    final String deleteKey = source ? EdgeEndpoints.SOURCE_ON_DELETE_KEY : EdgeEndpoints.TARGET_ON_DELETE_KEY;
    final String updateKey = source ? EdgeEndpoints.SOURCE_ON_UPDATE_KEY : EdgeEndpoints.TARGET_ON_UPDATE_KEY;
    final String onDelete = EdgeEndpoints.declared(type, deleteKey);
    final String onUpdate = EdgeEndpoints.declared(type, updateKey);
    final Table.Action delete = onDelete == null ? Table.Action.CASCADE : Table.Action.named(onDelete);
    final Table.Action update = onUpdate == null ? Table.Action.NO_ACTION : Table.Action.named(onUpdate);
    // a rule that is none of these leaves the table refused, and the writes of the referenced table hold the key to NO ACTION
    final EndKey key = column == null || column.kind() != Column.Kind.PROPERTY ? null
        : new EndKey(target.name, column.property(), delete == null ? Table.Action.NO_ACTION : delete,
            update == Table.Action.CASCADE ? Table.Action.CASCADE : Table.Action.NO_ACTION);
    final boolean referable = key != null && !column.isLink() && KEY_TYPES.contains(column.type());
    if (!referable || !hasUniqueKey(target.type, column.property()))
      return new End(null, key, null, "the edge type " + draft.name + " declares " + columnKey + " = '" + named + "', and "
          + target.name + " (" + named + ") is no column with a unique index of its own" + PostgreSQLError.HINT
          + (referable ? uniqueIndexRepair(target, column) + ", or remove" : "Remove")
          + " the declaration, so that the end shows record ids, in ArcadeDB sql: " + removal(draft, columnKey));
    if (delete == null)
      return new End(null, key, null, "the edge type " + draft.name + " declares " + deleteKey + " = '" + onDelete
          + "', which is none of CASCADE, NO ACTION and RESTRICT" + PostgreSQLError.HINT
          + "Remove it, so that the rule is CASCADE, in ArcadeDB sql: " + removal(draft, deleteKey));
    if (update == null || update == Table.Action.RESTRICT)
      return new End(null, key, null, "the edge type " + draft.name + " declares " + updateKey + " = '" + onUpdate
          + "', which is neither CASCADE nor NO ACTION" + PostgreSQLError.HINT
          + "Remove it, so that the rule is NO ACTION, in ArcadeDB sql: " + removal(draft, updateKey));
    return new End(target.name, key, column, null);
  }

  /**
   * What makes a column a unique key again, as a part of a hint: the statement of this language, after the removal in {@code sql}
   * of an index over the column that is not unique, which would stand in its way.
   */
  private static String uniqueIndexRepair(final Draft table, final Column column) {
    final StringBuilder repair = new StringBuilder("Run ");
    for (final TypeIndex index : table.type.getAllIndexes(true))
      if (!index.isUnique() && index.getPropertyNames().equals(List.of(column.property())))
        repair.append("DROP INDEX `").append(index.getName()).append("` in ArcadeDB sql and then ");
    return repair.append("CREATE UNIQUE INDEX ON ").append(Identifier.written(table.name)).append(" (")
        .append(Identifier.written(column.name())).append(')').toString();
  }

  /** The statement of {@code sql} that removes a custom entry of an edge type, as the last part of a hint. */
  private static String removal(final Draft type, final String key) {
    return "{sql}ALTER TYPE `" + type.name + "` CUSTOM " + key + " = null";
  }

  /** The column of a table by the name a declaration wrote: exact first, else the one name that differs only in case. */
  private static Column columnNamed(final Draft table, final String name) {
    Column found = null;
    for (final Column column : table.columns)
      if (column.name().equals(name))
        return column;
    for (final Column column : table.columns)
      if (column.name().equalsIgnoreCase(name)) {
        if (found != null)
          return null;
        found = column;
      }
    return found;
  }

  /**
   * Gives the end column of an edge table that refers to a key the type of that key. A table that {@code CREATE TABLE} made says
   * itself whether a string end is {@code text} or {@code varchar}.
   */
  private static void referToKey(final Draft draft, final Column.Kind kind, final End end) {
    if (end.column() == null)
      return;
    for (int i = 0; i < draft.columns.size(); i++) {
      final Column column = draft.columns.get(i);
      if (column.kind() != kind)
        continue;
      ColumnType type = end.column().type();
      if ((type == ColumnType.TEXT || type == ColumnType.VARCHAR) && draft.definition != null)
        type = draft.definition.textColumns().contains(column.name()) ? ColumnType.TEXT : ColumnType.VARCHAR;
      // an end that has the type of its key has its length or precision too (fork issue #226)
      draft.columns.set(i, column.referringTo(end.key(), type)
          .withModifier(type == end.column().type() ? end.column().modifier() : null));
    }
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
    /** what {@code CREATE TABLE} declared, {@code null} for a type that it did not make */
    final TableDefinition    definition;

    Draft(final DocumentType type, final Table.Kind kind, final Table.EdgeStorage storage, final List<Column> columns,
        final Map<String, String> linkTargets, final Table.Key primaryKey, final List<Table.Key> uniqueKeys, final String referable,
        final List<String> superTables, final List<String> subTables, final TableDefinition definition) {
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
      this.definition = definition;
    }
  }

  private static Draft draft(final DocumentType type, final Set<String> tableNames) {
    final String name = type.getName();
    final Table.Kind kind = type instanceof EdgeType ? Table.Kind.EDGE : type instanceof VertexType ? Table.Kind.VERTEX : Table.Kind.DOCUMENT;
    final EdgeType edgeType = type instanceof EdgeType edge ? edge : null;
    final Table.EdgeStorage storage = edgeType == null ? null : storageOf(edgeType);

    final Map<String, Property> properties = declaredProperties(type);
    final TableDefinition definition = TableDefinition.of(type);
    // the record id is rid, see the class comment; a declared property wins the name it has
    final String identity = properties.containsKey(Column.RID) ? null : Column.RID;
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
      ColumnType columnType = ColumnType.of(property.getType(), property.getOfType());
      if (columnType == ColumnType.VARCHAR && definition != null && definition.textColumns().contains(property.getName()))
        columnType = ColumnType.TEXT;
      if (columnType == ColumnType.VARCHAR && definition != null && definition.uuidColumns().contains(property.getName()))
        columnType = ColumnType.UUID;
      if (columnType == ColumnType.TIMESTAMP && definition != null && definition.timestamptzColumns().contains(property.getName()))
        columnType = ColumnType.TIMESTAMPTZ;
      Column column = new Column(shown, columnType, !property.isNotNull(), Column.Kind.PROPERTY, property.getName(), link);
      // fork issue #226: the length of a varchar(n), the precision and scale of a numeric(p,s)
      if (columnType == ColumnType.VARCHAR || columnType == ColumnType.NUMERIC)
        column = column.withModifier(TypeModifier.of(property));
      if (definition != null) {
        // a default that a later statement dropped is not shown. The definition is asked, because reading the default of a
        // serial or identity column takes its next value (fork issue #221)
        final boolean counted = Sequences.keyOf(property.getDefaultValueDefinition()) != null;
        final String text = definition.defaults().get(property.getName());
        if (property.hasDefaultFillingInAValue() && (counted || text == null || !text.startsWith(SEQUENCE_CALL)))
          column = column.withDefaultText(text);
        final String generation = definition.identity().get(property.getName());
        if (generation != null && counted)
          column = column.withIdentity(generation);
      }
      columns.add(column);
      if (link && property.getOfType() != null && !property.getOfType().isBlank())
        linkTargets.put(shown, property.getOfType());
    }
    if (definition != null)
      inDeclaredOrder(columns, definition.columns());

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

    Table.Key primaryKey;
    if (definition != null) {
      // the declared keys that still have their unique index; the record id is added when a foreign key refers to the table. A
      // unique index that CREATE UNIQUE INDEX made is an index and no constraint, as in PostgreSQL
      primaryKey = null;
      final List<List<String>> indexed = DeclaredIndexes.of(type).values().stream().filter(DeclaredIndexes.Index::unique)
          .map(DeclaredIndexes.Index::columns).toList();
      final List<Table.Key> others = new ArrayList<>(uniqueKeys.size());
      for (final Table.Key key : uniqueKeys)
        if (definition.primaryKey() != null && key.columns().equals(definition.primaryKey().columns()))
          primaryKey = definition.primaryKey();
        else if (definition.uniqueName(key.columns(), null) != null || !indexed.contains(key.columns()))
          others.add(new Table.Key(definition.uniqueName(key.columns(), key.name()), key.columns()));
      uniqueKeys.clear();
      uniqueKeys.addAll(others);
    } else if (ownId != null && (hasRecordIds || identity == null)) {
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
      // the record id is the primary key: there is no property id, or no unique index covers it alone
      primaryKey = new Table.Key(name + "_pkey", List.of(identity));
    } else if (storage == Table.EdgeStorage.LIGHTWEIGHT && edgeType.isUnique() && subTables.isEmpty())
      // the identity of a lightweight edge is (type, out, in), and UNIQUE says there is one per pair. With subtypes, the same
      // pair can be in two of the types, so the table of the supertype cannot promise it
      primaryKey = new Table.Key(name + "_pkey", List.of(Column.SOURCE_ID, Column.TARGET_ID));
    else
      primaryKey = null;

    return new Draft(type, kind, storage, columns, linkTargets, primaryKey, uniqueKeys, hasRecordIds ? identity : null, superTables,
        subTables, definition);
  }

  /**
   * Orders the columns of a table made by {@code CREATE TABLE}: the declared ones as declared, then any that a later statement
   * added, as they were, then the record id.
   */
  private static void inDeclaredOrder(final List<Column> columns, final List<String> declared) {
    final Map<String, Integer> positions = new HashMap<>();
    for (final String name : declared)
      positions.put(name, positions.size());
    columns.sort(Comparator.comparingInt((Column column) -> column.kind() == Column.Kind.IDENTITY ? Integer.MAX_VALUE
        : positions.getOrDefault(column.name(), declared.size())));
  }

  private static List<DocumentType> withSuperTypes(final DocumentType type) {
    final List<DocumentType> types = new ArrayList<>();
    types.add(type);
    for (int i = 0; i < types.size(); i++)
      for (final DocumentType superType : types.get(i).getSuperTypes())
        if (!types.contains(superType))
          types.add(superType);
    return types;
  }

  /** The foreign keys a type declares, none for a value that is no list of keys: the engine refuses the writes of such a type. */
  private static List<com.arcadedb.schema.ForeignKey> declaredKeys(final DocumentType type) {
    final Object value = type.getCustomValue(com.arcadedb.schema.ForeignKey.CUSTOM_KEY);
    if (value == null)
      return List.of();
    try {
      return com.arcadedb.schema.ForeignKey.fromJson(value.toString());
    } catch (final RuntimeException e) {
      return List.of();
    }
  }

  /** The columns that show the properties, {@code null} when one of them is shown by no column. */
  private static List<String> columnsOf(final Draft draft, final List<String> properties) {
    final List<String> columns = new ArrayList<>(properties.size());
    for (final String property : properties) {
      for (final Column column : draft.columns)
        if (column.kind() == Column.Kind.PROPERTY && property.equals(column.property()))
          columns.add(column.name());
    }
    return columns.size() == properties.size() ? columns : null;
  }

  /** The foreign key of one end of an edge table: to its key, or to the record id, which the engine keeps by deleting the link. */
  private static Table.ForeignKey endForeignKey(final Draft draft, final Map<String, Draft> drafts, final End end,
      final String column, final Table.ForeignKey.Origin origin) {
    return new Table.ForeignKey(foreignKeyName(draft, column), List.of(column), end.table(),
        List.of(end.column() == null ? drafts.get(end.table()).referable : end.column().name()), origin, null, end.key());
  }

  private static Table table(final Draft draft, final Schema schema, final Map<String, Draft> drafts, final boolean referenced,
      final List<End> ends, final boolean referencedByKey) {
    final List<Table.ForeignKey> foreignKeys = new ArrayList<>();
    String sourceTable = null;
    String targetTable = null;
    Table.Ends declaration = Table.Ends.NONE;
    if (draft.kind == Table.Kind.EDGE) {
      final End source = ends.get(0);
      final End target = ends.get(1);
      sourceTable = source.table();
      targetTable = target.table();
      if (sourceTable != null)
        foreignKeys.add(endForeignKey(draft, drafts, source, Column.SOURCE_ID, Table.ForeignKey.Origin.EDGE_SOURCE));
      if (targetTable != null)
        foreignKeys.add(endForeignKey(draft, drafts, target, Column.TARGET_ID, Table.ForeignKey.Origin.EDGE_TARGET));
      referToKey(draft, Column.Kind.SOURCE, source);
      referToKey(draft, Column.Kind.TARGET, target);
      declaration = new Table.Ends(source.key(), target.key(), source.broken() != null ? source.broken() : target.broken());
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

    // the foreign keys the engine checks (fork issue #228), of the type and, as PostgreSQL inherits columns, of its supertypes
    for (final DocumentType declaring : withSuperTypes(draft.type))
      for (final com.arcadedb.schema.ForeignKey declared : declaredKeys(declaring)) {
        final Draft targetDraft = drafts.get(declared.referencedType());
        if (targetDraft == null)
          continue;
        final List<String> columns = columnsOf(draft, declared.columns());
        final List<String> referencedColumns = columnsOf(targetDraft, declared.referencedColumns());
        if (columns != null && referencedColumns != null)
          foreignKeys.add(new Table.ForeignKey(declared.name(), columns, targetDraft.name, referencedColumns,
              Table.ForeignKey.Origin.DECLARED, declared, null));
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

    List<Column> columns = draft.columns;
    List<Table.Key> uniqueKeys = draft.uniqueKeys;
    if (draft.definition != null && draft.referable != null) {
      columns = columns.stream()
          .map(column -> column.kind() == Column.Kind.IDENTITY ? column.hide(referenced) : column).toList();
      if (referenced) {
        uniqueKeys = new ArrayList<>(uniqueKeys);
        uniqueKeys.add(new Table.Key(draft.name + "_" + draft.referable + "_key", List.of(draft.referable)));
        uniqueKeys.sort(Comparator.comparing((Table.Key key) -> String.join(",", key.columns())));
      }
    }
    return new Table(draft.name, draft.kind, draft.type, columns, draft.primaryKey, uniqueKeys, foreignKeys,
        draft.superTables, draft.subTables, draft.storage, sourceTable, targetTable, walkFrom, declaration, referencedByKey);
  }

  /** The name of the foreign key of an end of an edge table: the declared one, else the one PostgreSQL would give it. */
  private static String foreignKeyName(final Draft draft, final String column) {
    final String declared = draft.definition == null ? null : draft.definition.foreignKeys().get(column);
    return declared != null ? declared : draft.name + "_" + column + "_fkey";
  }

  /**
   * The vertex table that a declared endpoint names, {@code null} when nothing is declared or the name is not a vertex table with a
   * record id column to refer to. The name is the type name: exact first, else the one name that differs only in case, so a declaration written in
   * the case of a statement still finds {@code Person}; ambiguity is no match, a declaration is never guessed.
   */
  private static String declaredVertexTable(final String declared, final Map<String, Draft> drafts) {
    final Draft draft = declaredType(declared, drafts);
    return draft != null && draft.kind == Table.Kind.VERTEX && draft.referable != null ? draft.name : null;
  }

  /** The type that a declared endpoint names, see {@link #declaredVertexTable}. */
  private static Draft declaredType(final String declared, final Map<String, Draft> drafts) {
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
    return draft;
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
