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
import com.arcadedb.exception.AlreadyExistsException;
import com.arcadedb.query.postgresql.PostgreSQLAmbiguousIdentifierException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedColumnException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.query.postgresql.ast.CreateTableStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.ast.TypeName;
import com.arcadedb.query.postgresql.catalog.ClockDefault;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Sequences;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code CREATE TABLE} (fork issue #118). A table is a vertex type. A table that declares both ends of an edge, the columns
 * {@code source_id} and {@code target_id}, each {@code NOT NULL} with a {@code REFERENCES} to a vertex table, is an edge type. An
 * end refers to a column that is alone a unique key of that table, with its type (fork issue #213; {@code ON DELETE} NO ACTION,
 * RESTRICT or CASCADE, {@code ON UPDATE} NO ACTION or CASCADE), or to the record id, as
 * {@code text NOT NULL REFERENCES a_table (rid) ON DELETE CASCADE}. Every other {@code REFERENCES} is a foreign key between
 * columns, which the engine checks for every language (fork issue #228): it needs a primary key or a unique index over the
 * referenced columns, and the table gets an index over the referring ones.
 * <p>
 * What a write has to keep goes into the schema, so it holds for every language: the property types, mandatory and not-null for
 * {@code NOT NULL}, a unique index for each {@code UNIQUE} and {@code PRIMARY KEY} (a unique edge type for a key over the two
 * ends), the defaults, and the dimensions of a {@code vector(n)} as the minimum and maximum size of its property. A
 * {@code serial} column and an identity column (fork issue #221) are an integer property whose default takes the next value of a
 * counter of their own ({@link Sequences}). What only describes the table is its {@link TableDefinition}, the generation of an
 * identity column among it.
 * <p>
 * The whole statement is checked before the schema changes, and a change that fails takes the new type back. The checks follow
 * PostgreSQL's SQLSTATEs; what PostgreSQL accepts and the engine cannot keep is {@code 0A000} with its position.
 */
public final class CreateTablePlan {
  private static final Set<String> SYSTEM_COLUMNS = Set.of("tableoid", "xmin", "cmin", "xmax", "cmax", "ctid");

  /** The types a key can be over. */
  private static final Set<ColumnType> KEY_TYPES = Set.of(ColumnType.BOOL, ColumnType.INT2, ColumnType.INT4, ColumnType.INT8,
      ColumnType.NUMERIC, ColumnType.FLOAT4, ColumnType.FLOAT8, ColumnType.TEXT, ColumnType.VARCHAR, ColumnType.DATE,
      ColumnType.TIMESTAMP, ColumnType.TIMESTAMPTZ);

  /** The types a column with a default can have. */
  private static final Set<ColumnType> DEFAULT_TYPES = Set.of(ColumnType.BOOL, ColumnType.INT2, ColumnType.INT4, ColumnType.INT8,
      ColumnType.NUMERIC, ColumnType.FLOAT4, ColumnType.FLOAT8, ColumnType.TEXT, ColumnType.VARCHAR);

  /** The most dimensions pgvector allows a {@code vector}. */
  private static final int MAX_DIMENSIONS = 16000;

  private static final List<String> ENDS = List.of(Column.SOURCE_ID, Column.TARGET_ID);

  /** The types an identity column can have, each with the largest value it holds. */
  private static final Map<ColumnType, Long> COUNTED_TYPES = Map.of(ColumnType.INT2, (long) Short.MAX_VALUE, ColumnType.INT4,
      (long) Integer.MAX_VALUE, ColumnType.INT8, Long.MAX_VALUE);

  /** The types of which a foreign key can refer from one to another, and those PostgreSQL compares besides (fork issue #228). */
  private static final Set<ColumnType> INTEGERS = Set.of(ColumnType.INT2, ColumnType.INT4, ColumnType.INT8);
  private static final Set<ColumnType> TEXTS    = Set.of(ColumnType.TEXT, ColumnType.VARCHAR);
  private static final Set<ColumnType> FLOATS   = Set.of(ColumnType.FLOAT4, ColumnType.FLOAT8);
  private static final Set<ColumnType> NUMBERS  = Set.of(ColumnType.NUMERIC, ColumnType.FLOAT4, ColumnType.FLOAT8);
  private static final Set<ColumnType> TIMES    = Set.of(ColumnType.DATE, ColumnType.TIMESTAMP, ColumnType.TIMESTAMPTZ);

  /** The type of a column, in the engine and in the view; {@code dimensions} is 0 unless the column is a {@code vector(n)}. */
  private record Mapped(ColumnType type, Type engine, String ofType, int dimensions) {
  }

  /** How a column that the engine counts was declared. */
  private enum Counted {
    SERIAL, ALWAYS, BY_DEFAULT
  }

  /** A column as it will be made. */
  private static final class Spec {
    final String           name;
    final SourcePosition   position;
    final Mapped           mapped;
    boolean                notNull;
    boolean                nullable;
    /** the literal after {@code DEFAULT}, {@code null} when there is no default */
    Expression             literal;
    boolean                hasDefault;
    /** the default reads the clock */
    boolean                clock;
    Object                 defaultValue;
    String                 defaultText;
    /** {@code serial} or identity, {@code null} for any other column */
    Counted                counted;

    Spec(final String name, final SourcePosition position, final Mapped mapped) {
      this.name = name;
      this.position = position;
      this.mapped = mapped;
    }
  }

  /** A {@code UNIQUE} or {@code PRIMARY KEY}, with the name the statement gave it, {@code null} when it gave none. */
  private record Key(boolean primary, String name, List<String> columns, SourcePosition position) {
  }

  /** A foreign key as written: of one column, or of several for a table constraint. */
  private record ForeignKey(List<String> columns, String name, CreateTableStatement.Reference reference) {
  }

  /** What one end of a link table refers to: the vertex table, the column of its key ({@code null} for the record id), the rules. */
  private record End(String table, String column, Table.Action onDelete, Table.Action onUpdate) {
  }

  private final Database             database;
  private final RelationalCatalog    catalog;
  private final CreateTableStatement statement;
  private       DocumentType         created;

  public CreateTablePlan(final Database database, final RelationalCatalog catalog, final CreateTableStatement statement) {
    this.database = database;
    this.catalog = catalog;
    this.statement = statement;
  }

  /** The type that {@link #execute} made, {@code null} before it and when the table was there ({@code IF NOT EXISTS}). */
  public DocumentType created() {
    return created;
  }

  /** Makes the table; the answer has no column and no row. */
  public PostgreSQLResultSet execute() {
    final String table = tableName(statement.table(), statement.position());
    if (Sequences.TYPE_NAME.equals(table))
      throw unsupported("A table named " + table + ", which holds the counters of serial and identity columns,",
          statement.position());
    if (exists(statement.table().get(statement.table().size() - 1))) {
      if (statement.ifNotExists())
        return answer("CREATE TABLE " + table + ": the table exists, nothing to do");
      throw PostgreSQLEvaluationException.duplicateRelation(AlreadyExistsException.Kind.TYPE, table);
    }
    if (statement.columns().isEmpty())
      throw unsupported("A table with no column", statement.position());

    final Map<String, Spec> columns = new LinkedHashMap<>();
    final List<Key> keys = new ArrayList<>();
    final List<ForeignKey> foreignKeys = new ArrayList<>();
    for (final CreateTableStatement.Column column : statement.columns())
      readColumn(table, column, columns, keys, foreignKeys);
    for (final CreateTableStatement.Constraint constraint : statement.constraints())
      readTableConstraint(constraint, columns, keys, foreignKeys);

    final Key primary = primaryKey(table, keys);
    if (primary != null)
      for (final String column : primary.columns()) {
        final Spec spec = columns.get(column);
        if (spec.nullable)
          throw conflictingNull(table, column);
        spec.notNull = true;
      }

    // foreign keys over source_id and over target_id declare a link table; every other one is a key the engine checks
    final boolean linkTable = ENDS.stream()
        .allMatch(end -> foreignKeys.stream().anyMatch(key -> key.columns().equals(List.of(end))));
    final List<ForeignKey> endKeys = linkTable ?
        foreignKeys.stream().filter(key -> key.columns().size() == 1 && ENDS.contains(key.columns().get(0))).toList() :
        List.of();
    if (linkTable)
      for (final ForeignKey key : foreignKeys)
        if (key.columns().size() > 1 && key.columns().stream().anyMatch(ENDS::contains))
          throw unsupported("A foreign key over several columns, where an end of a link table refers to one column,",
              key.reference().position());
    final Map<String, ForeignKey> ends = ends(endKeys);
    final Map<String, End> endTables = new LinkedHashMap<>();
    for (final String end : ENDS)
      if (ends.containsKey(end))
        endTables.put(end, referencedEnd(table, columns.get(end), ends.get(end)));

    final List<Key> unique = distinctKeys(keys, primary);
    boolean uniqueEnds = false;
    for (final Key key : unique) {
      if (!ends.isEmpty() && key.columns().stream().anyMatch(ends::containsKey)) {
        if (!key.columns().equals(ENDS))
          throw unsupported("A key over an end of an edge other than (source_id, target_id)", key.position());
        uniqueEnds = true;
        continue;
      }
      for (final String column : key.columns())
        if (!KEY_TYPES.contains(columns.get(column).mapped.type()))
          throw unsupported("A key over a column of type " + columns.get(column).mapped.type(), key.position());
    }

    for (final Spec spec : columns.values())
      if (spec.literal != null)
        readDefault(spec);

    final Set<String> keyNames = new LinkedHashSet<>();
    final List<com.arcadedb.schema.ForeignKey> checked = new ArrayList<>();
    for (final ForeignKey key : foreignKeys)
      if (!endKeys.contains(key))
        checked.add(checkedKey(table, columns, unique, key, keyName(table, key, keyNames)));

    final TableDefinition definition = definition(table, columns, unique, ends, keyNames);
    create(table, columns, unique, endTables, uniqueEnds, definition, checked);
    return answer("CREATE TABLE " + table);
  }

  // ------------------------------------------------------------------------------------------------ foreign keys

  /** The name of a foreign key: the one the statement gives, else the one PostgreSQL would give, with a number when it is taken. */
  private static String keyName(final String table, final ForeignKey key, final Set<String> taken) {
    if (key.name() != null) {
      if (!taken.add(key.name()))
        throw PostgreSQLEvaluationException.duplicateConstraint(key.name(), table);
      return key.name();
    }
    final String base = table + "_" + String.join("_", key.columns()) + "_fkey";
    String name = base;
    for (int next = 1; !taken.add(name); next++)
      name = base + next;
    return name;
  }

  /**
   * A foreign key between columns, which the engine checks (fork issue #228): it refers to the columns of a primary key or a unique
   * index, of the table itself or of another one, with types that compare as the referring ones do.
   */
  private com.arcadedb.schema.ForeignKey checkedKey(final String table, final Map<String, Spec> columns, final List<Key> unique,
      final ForeignKey key, final String name) {
    final CreateTableStatement.Reference reference = key.reference();
    final com.arcadedb.schema.ForeignKey.Action onDelete = action(reference.onDelete(), reference.position());
    final com.arcadedb.schema.ForeignKey.Action onUpdate = action(reference.onUpdate(), reference.position());
    String target = tableName(reference.table(), reference.position());
    final List<String> properties = new ArrayList<>();
    final List<ColumnType> types = new ArrayList<>();
    if (target.equals(table)) {
      final List<String> written = new ArrayList<>(reference.columns().stream().map(Name::value).toList());
      if (written.isEmpty())
        written.addAll(unique.stream().filter(Key::primary).findFirst().orElseThrow(() -> noPrimaryKey(table)).columns());
      for (final String column : written) {
        if (!columns.containsKey(column))
          throw new PostgreSQLUndefinedColumnException(column, table, List.copyOf(columns.keySet()), false);
        properties.add(column);
        types.add(columns.get(column).mapped.type());
      }
      if (unique.stream().noneMatch(each -> sameColumns(each.columns(), written)))
        throw noUniqueKey(table);
    } else {
      final Table referenced = catalog.findTable(identifier(reference.table().get(reference.table().size() - 1)));
      if (referenced == null)
        throw new PostgreSQLUndefinedTableException(target);
      target = referenced.name();
      if (reference.columns().isEmpty() && referenced.primaryKey() == null)
        throw noPrimaryKey(target);
      final List<Identifier> named = reference.columns().isEmpty() ?
          referenced.primaryKey().columns().stream().map(column -> new Identifier(column, true)).toList() :
          reference.columns().stream().map(CreateTablePlan::identifier).toList();
      for (final Identifier each : named) {
        final Column column = referenced.column(each);
        if (column.kind() != Column.Kind.PROPERTY)
          // the record id: only the two ends of a link table refer to it
          throw refusedReference(reference.position());
        properties.add(column.property());
        types.add(column.type());
      }
      if (database.getSchema().getType(target).getAllIndexes(false).stream()
          .noneMatch(index -> index.isUnique() && sameColumns(index.getPropertyNames(), properties)))
        throw noUniqueKey(target);
    }
    if (properties.size() != key.columns().size())
      throw new PostgreSQLEvaluationException("42830", "number of referencing and referenced columns for foreign key disagree");
    for (int i = 0; i < properties.size(); i++) {
      final ColumnType referring = columns.get(key.columns().get(i)).mapped.type();
      checkTypes(name, referring, types.get(i), reference.position());
      if (!KEY_TYPES.contains(referring))
        throw unsupported("A key over a column of type " + referring, reference.position());
    }
    return new com.arcadedb.schema.ForeignKey(name, key.columns(), target, properties, onDelete, onUpdate);
  }

  private static boolean sameColumns(final List<String> key, final List<String> columns) {
    return key.size() == columns.size() && Set.copyOf(columns).size() == columns.size() && key.containsAll(columns);
  }

  /** Refuses types that PostgreSQL cannot compare, and those it compares that an index of the engine cannot. */
  private static void checkTypes(final String name, final ColumnType referring, final ColumnType referenced,
      final SourcePosition position) {
    if (referring == referenced || INTEGERS.contains(referring) && INTEGERS.contains(referenced)
        || TEXTS.contains(referring) && TEXTS.contains(referenced))
      return;
    final boolean postgresCompares = INTEGERS.contains(referring) && NUMBERS.contains(referenced)
        || referring == ColumnType.NUMERIC && FLOATS.contains(referenced)
        || FLOATS.contains(referring) && FLOATS.contains(referenced) || TIMES.contains(referring) && TIMES.contains(referenced);
    final String types = PgType.of(referring).displayName() + " and " + PgType.of(referenced).displayName();
    if (postgresCompares)
      throw unsupported("A foreign key between columns of the types " + types, position);
    throw new PostgreSQLEvaluationException("42804",
        "foreign key constraint \"" + name + "\" cannot be implemented: the key columns are of incompatible types: " + types);
  }

  private static com.arcadedb.schema.ForeignKey.Action action(final CreateTableStatement.Action action,
      final SourcePosition position) {
    return switch (action) {
      case NO_ACTION -> com.arcadedb.schema.ForeignKey.Action.NO_ACTION;
      case RESTRICT -> com.arcadedb.schema.ForeignKey.Action.RESTRICT;
      case CASCADE -> com.arcadedb.schema.ForeignKey.Action.CASCADE;
      case SET_NULL -> com.arcadedb.schema.ForeignKey.Action.SET_NULL;
      case SET_DEFAULT -> throw unsupported("The referential action SET DEFAULT", position);
    };
  }

  private static PostgreSQLEvaluationException noPrimaryKey(final String table) {
    return new PostgreSQLEvaluationException("42704", "there is no primary key for referenced table \"" + table + "\"");
  }

  private static PostgreSQLEvaluationException noUniqueKey(final String table) {
    return new PostgreSQLEvaluationException("42830",
        "there is no unique constraint matching given keys for referenced table \"" + table + "\"");
  }

  /** Adds the index over the referring columns of each key, unless one is there, and declares the keys. */
  private void createForeignKeys(final DocumentType type, final List<com.arcadedb.schema.ForeignKey> checked) {
    if (checked.isEmpty())
      return;
    for (final com.arcadedb.schema.ForeignKey key : checked) {
      final List<String> keyOrder = database.getSchema().getType(key.referencedType()).getAllIndexes(false).stream()
          .filter(index -> index.isUnique() && sameColumns(index.getPropertyNames(), key.referencedColumns()))
          .findFirst().orElseThrow(() -> noUniqueKey(key.referencedType())).getPropertyNames();
      final List<String> properties = key.columnsInKeyOrder(keyOrder);
      if (type.getAllIndexes(false).stream().noneMatch(index -> index.getPropertyNames().equals(properties)))
        type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, properties.toArray(new String[0]));
    }
    type.setCustomValue(com.arcadedb.schema.ForeignKey.CUSTOM_KEY, com.arcadedb.schema.ForeignKey.toJson(checked));
  }

  private static PostgreSQLResultSet answer(final String step) {
    return new PostgreSQLResultSet(List.of(), new PostgreSQLResultSet.Plan(List.of(step))).rowless().withAffectedRows(0);
  }

  // ------------------------------------------------------------------------------------------------ names

  private static Identifier identifier(final Name name) {
    return new Identifier(name.value(), name.quoted());
  }

  private String tableName(final List<Name> name, final SourcePosition position) {
    if (name.size() == 2) {
      final String schema = name.get(0).value();
      if ("information_schema".equals(schema) || "pg_catalog".equals(schema))
        throw unsupported("A table in the schema " + schema, position);
      catalog.requireSchema(identifier(name.get(0)));
    }
    return name.get(name.size() - 1).value();
  }

  /** Whether the name stands for a table already, by the rules the language looks names up with, or for any type. */
  private boolean exists(final Name name) {
    try {
      return catalog.findTable(identifier(name)) != null || database.getSchema().existsType(name.value());
    } catch (final PostgreSQLAmbiguousIdentifierException severalMatch) {
      return true;
    }
  }

  // ------------------------------------------------------------------------------------------------ columns and constraints

  private void readColumn(final String table, final CreateTableStatement.Column column, final Map<String, Spec> columns,
      final List<Key> keys, final List<ForeignKey> foreignKeys) {
    final String name = column.name().value();
    if (SYSTEM_COLUMNS.contains(name))
      throw new PostgreSQLEvaluationException("42701", "column name \"" + name + "\" conflicts with a system column name");
    if (name.startsWith("@"))
      throw unsupported("A column name that starts with @", column.position());
    final Mapped serial = serialType(column.type());
    final Spec spec = new Spec(name, column.position(), serial != null ? serial : type(column.type()));
    if (columns.put(name, spec) != null)
      throw new PostgreSQLEvaluationException("42701", "column \"" + name + "\" specified more than once");
    if (serial != null) {
      spec.counted = Counted.SERIAL;
      spec.notNull = true;
    }

    // serial is a default of its own
    boolean defaultSeen = serial != null;
    for (final CreateTableStatement.Constraint constraint : column.constraints()) {
      final String given = constraint.name() == null ? null : constraint.name().value();
      switch (constraint.kind()) {
      case NOT_NULL -> {
        if (spec.nullable)
          throw conflictingNull(table, name);
        spec.notNull = true;
      }
      case NULL -> {
        if (spec.notNull)
          throw conflictingNull(table, name);
        spec.nullable = true;
      }
      case IDENTITY_ALWAYS, IDENTITY_BY_DEFAULT -> {
        if (spec.counted == Counted.ALWAYS || spec.counted == Counted.BY_DEFAULT)
          throw new PostgreSQLEvaluationException("42601",
              "multiple identity specifications for column \"" + name + "\" of table \"" + table + "\"");
        if (defaultSeen)
          throw defaultAndIdentity(table, name);
        if (!COUNTED_TYPES.containsKey(spec.mapped.type()))
          throw new PostgreSQLEvaluationException("22023", "identity column type must be smallint, integer, or bigint");
        if (spec.nullable)
          throw conflictingNull(table, name);
        spec.notNull = true;
        spec.counted = constraint.kind() == CreateTableStatement.Kind.IDENTITY_ALWAYS ? Counted.ALWAYS : Counted.BY_DEFAULT;
      }
      case DEFAULT -> {
        if (spec.counted == Counted.ALWAYS || spec.counted == Counted.BY_DEFAULT)
          throw defaultAndIdentity(table, name);
        if (defaultSeen)
          throw new PostgreSQLEvaluationException("42601",
              "multiple default values specified for column \"" + name + "\" of table \"" + table + "\"");
        defaultSeen = true;
        spec.literal = constraint.value();
      }
      case UNIQUE -> keys.add(new Key(false, given, List.of(name), constraint.position()));
      case PRIMARY_KEY -> keys.add(new Key(true, given, List.of(name), constraint.position()));
      case FOREIGN_KEY -> foreignKeys.add(new ForeignKey(List.of(name), given, constraint.reference()));
      }
    }
  }

  private static void readTableConstraint(final CreateTableStatement.Constraint constraint, final Map<String, Spec> columns,
      final List<Key> keys, final List<ForeignKey> foreignKeys) {
    final String given = constraint.name() == null ? null : constraint.name().value();
    final List<String> names = new ArrayList<>();
    for (final Name column : constraint.columns()) {
      final String name = column.value();
      if (!columns.containsKey(name))
        throw new PostgreSQLEvaluationException("42703", "column \"" + name + "\" named in key does not exist");
      if (names.contains(name))
        throw new PostgreSQLEvaluationException(constraint.kind() == CreateTableStatement.Kind.FOREIGN_KEY ? "42830" : "42701",
            "column \"" + name + "\" appears twice in a constraint");
      names.add(name);
    }
    if (constraint.kind() == CreateTableStatement.Kind.FOREIGN_KEY)
      foreignKeys.add(new ForeignKey(names, given, constraint.reference()));
    else
      keys.add(new Key(constraint.kind() == CreateTableStatement.Kind.PRIMARY_KEY, given, names, constraint.position()));
  }

  private static Key primaryKey(final String table, final List<Key> keys) {
    Key primary = null;
    for (final Key key : keys)
      if (key.primary()) {
        if (primary != null)
          throw new PostgreSQLEvaluationException("42P16", "multiple primary keys for table \"" + table + "\" are not allowed");
        primary = key;
      }
    return primary;
  }

  /**
   * The keys that become an index, the primary key first: as PostgreSQL does, a constraint over the columns of one that is there
   * already adds nothing, but for the name it gives a constraint that has none.
   */
  private static List<Key> distinctKeys(final List<Key> keys, final Key primary) {
    final Map<List<String>, Key> distinct = new LinkedHashMap<>();
    if (primary != null)
      distinct.put(primary.columns(), primary);
    for (final Key key : keys) {
      final Key kept = distinct.putIfAbsent(key.columns(), key);
      if (kept != null && kept.name() == null && key.name() != null)
        distinct.put(key.columns(), new Key(kept.primary(), key.name(), kept.columns(), kept.position()));
    }
    return new ArrayList<>(distinct.values());
  }

  // ------------------------------------------------------------------------------------------------ the ends of an edge

  /** The two foreign keys of a link table, by the column: each end has one. */
  private static Map<String, ForeignKey> ends(final List<ForeignKey> endKeys) {
    final Map<String, ForeignKey> ends = new LinkedHashMap<>();
    for (final ForeignKey key : endKeys)
      if (ends.put(key.columns().get(0), key) != null)
        throw refusedReference(key.reference().position());
    return ends;
  }

  /**
   * What the foreign key of an end refers to (fork issue #213), checked in PostgreSQL's order and with its SQLSTATEs: the table
   * ({@code 42P01}), the columns ({@code 42703}, {@code 42704} when none is written and there is no primary key, {@code 42830}
   * for their number), a unique key ({@code 42830}) and the type ({@code 42804}). What PostgreSQL accepts and the engine cannot
   * keep is {@code 0A000}, with the column definition to write. A reference to the record id keeps the one form it had.
   */
  private End referencedEnd(final String table, final Spec spec, final ForeignKey key) {
    final CreateTableStatement.Reference reference = key.reference();
    final String name = tableName(reference.table(), reference.position());
    if (name.equals(table))
      throw refusedReference(reference.position());
    final Table referenced = catalog.findTable(identifier(reference.table().get(reference.table().size() - 1)));
    if (referenced == null)
      throw new PostgreSQLUndefinedTableException(name);
    final Column column = referencedColumn(referenced, reference);
    if (referenced.kind() != Table.Kind.VERTEX)
      throw refusedReference(reference.position());
    if (column.kind() == Column.Kind.IDENTITY) {
      if (spec.mapped.type() != ColumnType.TEXT || !spec.notNull || spec.literal != null || spec.counted != null
          || reference.onDelete() != CreateTableStatement.Action.CASCADE
          || reference.onUpdate() != CreateTableStatement.Action.NO_ACTION)
        throw refusedReference(reference.position());
      return new End(referenced.name(), null, Table.Action.CASCADE, Table.Action.NO_ACTION);
    }
    if (column.kind() != Column.Kind.PROPERTY || !RelationalCatalog.hasUniqueKey(referenced.documentType(), column.property()))
      throw noUniqueKey(referenced.name());
    if (column.isLink() || !RelationalCatalog.KEY_TYPES.contains(column.type()))
      throw unsupported("A foreign key to a column of type " + column.type(), reference.position());
    final ColumnType own = spec.mapped.type();
    if (own != column.type() && !(TEXTS.contains(own) && TEXTS.contains(column.type()))) {
      if (!comparable(own, column.type()))
        throw new PostgreSQLEvaluationException("42804", "foreign key constraint \""
            + (key.name() != null ? key.name() : table + "_" + spec.name + "_fkey") + "\" cannot be implemented: key columns \""
            + spec.name + "\" and \"" + column.name() + "\" are of incompatible types: " + typeName(own) + " and "
            + typeName(column.type()));
      throw unsupportedEnd("An end of another type than its key", spec, referenced, column, reference);
    }
    if (!spec.notNull)
      throw unsupportedEnd("A nullable end of a link table", spec, referenced, column, reference);
    if (spec.literal != null || spec.counted != null)
      throw unsupportedEnd("A DEFAULT on an end of a link table", spec, referenced, column, reference);
    final Table.Action onDelete = switch (reference.onDelete()) {
      case NO_ACTION -> Table.Action.NO_ACTION;
      case RESTRICT -> Table.Action.RESTRICT;
      case CASCADE -> Table.Action.CASCADE;
      case SET_NULL, SET_DEFAULT -> throw refusedAction("ON DELETE", reference.onDelete(),
          "leave ON DELETE out, or write ON DELETE CASCADE or ON DELETE RESTRICT", reference.position());
    };
    final Table.Action onUpdate = switch (reference.onUpdate()) {
      case NO_ACTION -> Table.Action.NO_ACTION;
      case CASCADE -> Table.Action.CASCADE;
      case RESTRICT, SET_NULL, SET_DEFAULT -> throw refusedAction("ON UPDATE", reference.onUpdate(),
          "leave ON UPDATE out, or write ON UPDATE CASCADE", reference.position());
    };
    return new End(referenced.name(), column.name(), onDelete, onUpdate);
  }

  /** The one column that a foreign key refers to: the one written, else the primary key of the table. */
  private static Column referencedColumn(final Table referenced, final CreateTableStatement.Reference reference) {
    final List<Column> found = new ArrayList<>();
    if (reference.columns().isEmpty()) {
      final Table.Key primary = referenced.primaryKey();
      if (primary == null)
        throw noPrimaryKey(referenced.name());
      for (final String name : primary.columns())
        found.add(referenced.column(Identifier.quoted(name)));
    } else
      for (final Name name : reference.columns()) {
        final Column column = referenced.findColumn(identifier(name));
        if (column == null)
          throw new PostgreSQLEvaluationException("42703", "column \"" + name.value()
              + "\" referenced in foreign key constraint does not exist. Hint: refer to a column that is alone the primary key"
              + " or a unique key of the table, or to rid, the record id, with ON DELETE CASCADE");
        found.add(column);
      }
    if (found.size() != 1)
      throw new PostgreSQLEvaluationException("42830", "number of referencing and referenced columns for foreign key disagree");
    return found.get(0);
  }

  /** Whether PostgreSQL accepts a foreign key of a column of type {@code own} to a key of another type {@code key}. */
  private static boolean comparable(final ColumnType own, final ColumnType key) {
    return INTEGERS.contains(own) && (INTEGERS.contains(key) || NUMBERS.contains(key))
        || own == ColumnType.NUMERIC && FLOATS.contains(key) || FLOATS.contains(own) && FLOATS.contains(key)
        || TIMES.contains(own) && TIMES.contains(key);
  }

  /** A type as a statement writes it and PostgreSQL's errors name it. */
  private static String typeName(final ColumnType type) {
    return switch (type) {
      case BOOL -> "boolean";
      case INT2 -> "smallint";
      case INT4 -> "integer";
      case INT8 -> "bigint";
      case FLOAT4 -> "real";
      case FLOAT8 -> "double precision";
      default -> type.typeName().toLowerCase(Locale.ROOT);
    };
  }

  /** A refusal of an end that refers to a key, whose hint is the column definition the language keeps. */
  private static PostgreSQLFeatureNotSupportedException unsupportedEnd(final String what, final Spec spec, final Table referenced,
      final Column column, final CreateTableStatement.Reference reference) {
    final StringBuilder definition = new StringBuilder(Identifier.written(spec.name)).append(' ').append(typeName(column.type()))
        .append(" NOT NULL REFERENCES ").append(Identifier.written(referenced.name())).append(" (")
        .append(Identifier.written(column.name())).append(')');
    if (reference.onDelete() == CreateTableStatement.Action.CASCADE)
      definition.append(" ON DELETE CASCADE");
    else if (reference.onDelete() == CreateTableStatement.Action.RESTRICT)
      definition.append(" ON DELETE RESTRICT");
    if (reference.onUpdate() == CreateTableStatement.Action.CASCADE)
      definition.append(" ON UPDATE CASCADE");
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language ("
        + reference.position() + ")", "Write " + definition);
  }

  private static PostgreSQLFeatureNotSupportedException refusedAction(final String clause,
      final CreateTableStatement.Action action, final String instead, final SourcePosition position) {
    return new PostgreSQLFeatureNotSupportedException(clause + " " + action.name().replace('_', ' ')
        + " on an end of a link table is not supported by the postgresql language (" + position + ")",
        Character.toUpperCase(instead.charAt(0)) + instead.substring(1));
  }

  private static PostgreSQLFeatureNotSupportedException refusedReference(final SourcePosition position) {
    return unsupported("A foreign key other than the two ends of an edge table (source_id and target_id, each NOT NULL and"
        + " referring to a unique column of a table, or text NOT NULL REFERENCES a table (rid) ON DELETE CASCADE)", position);
  }

  // ------------------------------------------------------------------------------------------------ types

  /**
   * The type of a column declared {@code serial}, {@code bigserial} or {@code smallserial}, {@code null} for any other type: as in
   * PostgreSQL, the word is no type but a short form of a column definition, which only an unqualified name without a modifier
   * and without {@code []} is.
   */
  private static Mapped serialType(final TypeName type) {
    if (type.name().size() != 1 || !type.modifiers().isEmpty() || type.arrayDimensions() != 0)
      return null;
    return switch (type.name().get(0)) {
      case "smallserial", "serial2" -> scalarType("int2");
      case "serial", "serial4" -> scalarType("int4");
      case "bigserial", "serial8" -> scalarType("int8");
      default -> null;
    };
  }

  /** The default of a {@code serial} column as PostgreSQL 18 shows it, with the sequence it would make. */
  private static String serialDefault(final String table, final String column) {
    String sequence = table + "_" + column + "_seq";
    if (!sequence.matches("[a-z_][a-z0-9_$]*"))
      sequence = "\"" + sequence.replace("\"", "\"\"") + "\"";
    return "nextval('" + sequence.replace("'", "''") + "'::regclass)";
  }

  private static Mapped type(final TypeName type) {
    final List<String> parts = type.name();
    final String name = parts.get(parts.size() - 1);
    if (parts.size() > 2 || parts.size() == 2 && !"pg_catalog".equals(parts.get(0)) && !RelationalCatalog.SCHEMA.equals(parts.get(0))
        || type.arrayDimensions() > 1)
      throw unsupportedType(type);
    final boolean array = type.arrayDimensions() == 1;
    if (!type.modifiers().isEmpty()) {
      // the only modifier that writes keep: the dimensions of a vector
      if (array || !"vector".equals(name) || type.modifiers().size() != 1
          || !(type.modifiers().get(0) instanceof Expression.IntegerLiteral size))
        throw unsupported("A type modifier on a column of type " + name, type.position());
      if (size.value().signum() <= 0 || size.value().bitLength() > 31 || size.value().intValue() > MAX_DIMENSIONS)
        throw new PostgreSQLEvaluationException("22023", "the dimensions of a vector are between 1 and " + MAX_DIMENSIONS);
      return new Mapped(ColumnType.VECTOR, Type.ARRAY_OF_FLOATS, null, size.value().intValue());
    }
    if (array && "timestamptz".equals(name))
      throw new PostgreSQLFeatureNotSupportedException("A column of type timestamptz[] is not supported by the postgresql"
          + " language: write timestamp[] and keep its times in UTC (" + type.position() + ")");
    final Mapped mapped = array ? arrayType(name) : scalarType(name);
    if (mapped == null)
      throw unsupportedType(type);
    return mapped;
  }

  private static Mapped scalarType(final String name) {
    return switch (name) {
      case "bool" -> new Mapped(ColumnType.BOOL, Type.BOOLEAN, null, 0);
      case "int2" -> new Mapped(ColumnType.INT2, Type.SHORT, null, 0);
      case "int4" -> new Mapped(ColumnType.INT4, Type.INTEGER, null, 0);
      case "int8" -> new Mapped(ColumnType.INT8, Type.LONG, null, 0);
      case "float4" -> new Mapped(ColumnType.FLOAT4, Type.FLOAT, null, 0);
      case "float8" -> new Mapped(ColumnType.FLOAT8, Type.DOUBLE, null, 0);
      case "numeric" -> new Mapped(ColumnType.NUMERIC, Type.DECIMAL, null, 0);
      case "text" -> new Mapped(ColumnType.TEXT, Type.STRING, null, 0);
      case "varchar" -> new Mapped(ColumnType.VARCHAR, Type.STRING, null, 0);
      case "bytea" -> new Mapped(ColumnType.BYTEA, Type.BINARY, null, 0);
      case "date" -> new Mapped(ColumnType.DATE, Type.DATE, null, 0);
      case "timestamp" -> new Mapped(ColumnType.TIMESTAMP, Type.DATETIME_MICROS, null, 0);
      // the instant is held as its time in UTC, and the definition of the table says that the column is one
      case "timestamptz" -> new Mapped(ColumnType.TIMESTAMPTZ, Type.DATETIME_MICROS, null, 0);
      case "jsonb" -> new Mapped(ColumnType.JSONB, Type.MAP, null, 0);
      case "vector" -> new Mapped(ColumnType.VECTOR, Type.ARRAY_OF_FLOATS, null, 0);
      default -> null;
    };
  }

  private static Mapped arrayType(final String element) {
    return switch (element) {
      case "bool" -> new Mapped(ColumnType.BOOL_ARRAY, Type.LIST, "BOOLEAN", 0);
      case "int4" -> new Mapped(ColumnType.INT4_ARRAY, Type.ARRAY_OF_INTEGERS, null, 0);
      case "int8" -> new Mapped(ColumnType.INT8_ARRAY, Type.ARRAY_OF_LONGS, null, 0);
      case "float4" -> new Mapped(ColumnType.FLOAT4_ARRAY, Type.LIST, "FLOAT", 0);
      case "float8" -> new Mapped(ColumnType.FLOAT8_ARRAY, Type.ARRAY_OF_DOUBLES, null, 0);
      case "numeric" -> new Mapped(ColumnType.NUMERIC_ARRAY, Type.LIST, "DECIMAL", 0);
      case "bytea" -> new Mapped(ColumnType.BYTEA_ARRAY, Type.LIST, "BINARY", 0);
      case "text" -> new Mapped(ColumnType.TEXT_ARRAY, Type.LIST, "STRING", 0);
      case "date" -> new Mapped(ColumnType.DATE_ARRAY, Type.LIST, "DATE", 0);
      // the elements keep what a timestamp column keeps
      case "timestamp" -> new Mapped(ColumnType.TIMESTAMP_ARRAY, Type.LIST, "DATETIME_MICROS", 0);
      default -> null;
    };
  }

  private static PostgreSQLFeatureNotSupportedException unsupportedType(final TypeName type) {
    return unsupported("A column of type " + String.join(".", type.name()) + "[]".repeat(type.arrayDimensions()), type.position());
  }

  // ------------------------------------------------------------------------------------------------ defaults

  /** Reads the constant after {@code DEFAULT} as the value of the column, and as PostgreSQL shows it in the catalog. */
  private void readDefault(final Spec spec) {
    Expression literal = spec.literal;
    if (literal instanceof Expression.NullLiteral)
      return;
    final ClockDefault clock = clockDefault(literal);
    if (clock != null) {
      readClockDefault(spec, clock);
      return;
    }
    // 'text'::the_type_of_the_column says what the bare string says
    if (literal instanceof Expression.Cast cast && cast.operand() instanceof Expression.StringLiteral operand
        && cast.type().modifiers().isEmpty() && type(cast.type()).type() == spec.mapped.type())
      literal = operand;
    if (!(literal instanceof Expression.StringLiteral || literal instanceof Expression.IntegerLiteral
        || literal instanceof Expression.DecimalLiteral || literal instanceof Expression.BooleanLiteral))
      throw unsupported("A DEFAULT that is not a constant", literal.position());
    final ColumnType type = spec.mapped.type();
    if (!DEFAULT_TYPES.contains(type))
      throw unsupported("A DEFAULT on a column of type " + type, literal.position());

    final Column column = Column.declaration(spec.name, type);
    final Evaluable compiled = new ExpressionCompiler(new RelationScope(List.of(), catalog),
        Parameters.of(new Object[0])).compile(literal);
    Assignments.check(compiled.type(), compiled.typeName(), column);
    final Object value;
    try {
      value = Assignments.convert(compiled.eval(new Object[0]), compiled.type(), column);
    } catch (final PostgreSQLEvaluationException e) {
      // PostgreSQL reads a bare string when it creates the table, and any other constant only when a row takes the default
      if (literal instanceof Expression.StringLiteral)
        throw e;
      throw unsupported("A DEFAULT that does not fit the column", literal.position());
    }
    if (value instanceof Double number && !Double.isFinite(number) || value instanceof Float real && !Float.isFinite(real))
      throw unsupported("A DEFAULT that is not a finite number", literal.position());
    // the schema keeps a number as a double when it is read back
    if (value instanceof BigDecimal decimal && BigDecimal.valueOf(decimal.doubleValue()).compareTo(decimal) != 0)
      throw unsupported("A numeric DEFAULT with more digits than a double keeps", literal.position());
    spec.hasDefault = true;
    spec.defaultValue = value;
    spec.defaultText = defaultText(literal, type, value);
  }

  /** The default of the clock that {@code expression} is, {@code null} for any other expression (fork issue #222). */
  private static ClockDefault clockDefault(final Expression expression) {
    if (expression instanceof Expression.SpecialFunction special) {
      final ClockDefault clock = switch (special.kind()) {
        case CURRENT_DATE -> ClockDefault.CURRENT_DATE;
        case CURRENT_TIMESTAMP -> ClockDefault.CURRENT_TIMESTAMP;
        case LOCALTIMESTAMP -> ClockDefault.LOCALTIMESTAMP;
        default -> null;
      };
      if (clock != null && !special.args().isEmpty())
        throw new PostgreSQLFeatureNotSupportedException("A DEFAULT with a precision is not supported by the postgresql language:"
            + " write DEFAULT " + clock.text() + " (" + expression.position() + ")");
      return clock;
    }
    if (expression instanceof Expression.FunctionCall call && call.args().isEmpty() && !call.distinct() && !call.star()
        && call.over() == null
        && (call.name().size() == 1 || call.name().size() == 2 && "pg_catalog".equals(call.name().get(0).value()))) {
      final String name = call.name().get(call.name().size() - 1).value().toLowerCase(Locale.ROOT);
      if ("now".equals(name))
        return ClockDefault.NOW;
      if ("transaction_timestamp".equals(name))
        return ClockDefault.TRANSACTION_TIMESTAMP;
    }
    return null;
  }

  private static void readClockDefault(final Spec spec, final ClockDefault clock) {
    final ColumnType type = spec.mapped.type();
    if (type != ColumnType.DATE && type != ColumnType.TIMESTAMP && type != ColumnType.TIMESTAMPTZ) {
      if (type == ColumnType.TEXT || type == ColumnType.VARCHAR)
        throw new PostgreSQLFeatureNotSupportedException("A DEFAULT of " + clock.text() + " on a column of type " + type
            + " is not supported by the postgresql language: give the column the type timestamptz (" + spec.literal.position() + ")");
      final PgType from = clock == ClockDefault.CURRENT_DATE ? PgType.DATE
          : clock == ClockDefault.LOCALTIMESTAMP ? PgType.TIMESTAMP : PgType.TIMESTAMPTZ;
      throw PostgreSQLEvaluationException.mismatch("column \"" + spec.name + "\" is of type " + PgType.of(type).displayName()
          + " but default expression is of type " + from.displayName());
    }
    spec.hasDefault = true;
    spec.clock = true;
    spec.defaultValue = ClockDefault.SCHEMA_DEFAULT;
    spec.defaultText = clock.text();
  }

  /**
   * The text of a default as PostgreSQL 18 writes it: a bare string is a constant of the type of the column, a number or a boolean
   * a constant of its own type.
   */
  private static String defaultText(final Expression literal, final ColumnType type, final Object value) {
    if (literal instanceof Expression.BooleanLiteral bool)
      return Boolean.toString(bool.value());
    if (literal instanceof Expression.IntegerLiteral integer) {
      final int bits = integer.value().bitLength();
      return constant(integer.value().toString(), bits < 32 ? ColumnType.INT4 : bits < 64 ? ColumnType.INT8 : ColumnType.NUMERIC);
    }
    if (literal instanceof Expression.DecimalLiteral decimal)
      return constant(decimal.value().toPlainString(), ColumnType.NUMERIC);
    final String text;
    if (value instanceof BigDecimal decimal)
      text = decimal.toPlainString();
    else if (value instanceof Double || value instanceof Float)
      text = new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    else
      text = String.valueOf(value);
    return constant(text, type);
  }

  private static String constant(final String text, final ColumnType type) {
    if (type == ColumnType.BOOL)
      return text;
    if (type == ColumnType.INT4 && !text.startsWith("-"))
      return text;
    if (type == ColumnType.NUMERIC && Character.isDigit(text.charAt(0)) && text.indexOf('.') >= 0)
      return text;
    final String name = switch (type) {
      case INT2 -> "smallint";
      case INT4 -> "integer";
      case INT8 -> "bigint";
      case FLOAT4 -> "real";
      case FLOAT8 -> "double precision";
      case VARCHAR -> "character varying";
      default -> type.typeName().toLowerCase(Locale.ROOT);
    };
    return "'" + text.replace("'", "''") + "'::" + name;
  }

  // ------------------------------------------------------------------------------------------------ the schema

  private TableDefinition definition(final String table, final Map<String, Spec> columns, final List<Key> keys,
      final Map<String, ForeignKey> ends, final Set<String> keyNames) {
    final Set<String> names = new LinkedHashSet<>(keyNames);
    final Set<String> text = new LinkedHashSet<>();
    final Set<String> instants = new LinkedHashSet<>();
    for (final Spec spec : columns.values())
      if (spec.mapped.type() == ColumnType.TEXT)
        text.add(spec.name);
      else if (spec.mapped.type() == ColumnType.TIMESTAMPTZ)
        instants.add(spec.name);
    Table.Key primary = null;
    final List<Table.Key> unique = new ArrayList<>();
    for (final Key key : keys) {
      final String name = key.name() != null ? key.name()
          : key.primary() ? table + "_pkey" : table + "_" + String.join("_", key.columns()) + "_key";
      // a key is an index, and an index is a relation of its own in PostgreSQL
      final boolean typeName = key.name() != null && (name.equals(table) || database.getSchema().existsType(name));
      if (typeName || !names.add(name))
        throw PostgreSQLEvaluationException.duplicateRelation(
            typeName ? AlreadyExistsException.Kind.TYPE : AlreadyExistsException.Kind.INDEX, name);
      if (key.primary())
        primary = new Table.Key(name, key.columns());
      else
        unique.add(new Table.Key(name, key.columns()));
    }
    final Map<String, String> foreignKeys = new LinkedHashMap<>();
    for (final Map.Entry<String, ForeignKey> end : ends.entrySet()) {
      final String name = end.getValue().name() != null ? end.getValue().name() : table + "_" + end.getKey() + "_fkey";
      if (!names.add(name))
        throw PostgreSQLEvaluationException.duplicateConstraint(name, table);
      foreignKeys.put(end.getKey(), name);
    }
    final Map<String, String> defaults = new LinkedHashMap<>();
    final Map<String, String> identity = new LinkedHashMap<>();
    for (final Spec spec : columns.values())
      if (spec.hasDefault)
        defaults.put(spec.name, spec.defaultText);
      else if (spec.counted == Counted.SERIAL)
        defaults.put(spec.name, serialDefault(table, spec.name));
      else if (spec.counted != null)
        identity.put(spec.name, spec.counted == Counted.ALWAYS ? Column.ALWAYS : Column.BY_DEFAULT);
    return new TableDefinition(List.copyOf(columns.keySet()), text, primary, unique, defaults, foreignKeys, instants, identity);
  }

  private void create(final String table, final Map<String, Spec> columns, final List<Key> keys,
      final Map<String, End> endTables, final boolean uniqueEnds, final TableDefinition definition,
      final List<com.arcadedb.schema.ForeignKey> checked) {
    final Schema schema = database.getSchema();
    final boolean edge = !endTables.isEmpty();
    final DocumentType type = edge ? schema.buildEdgeType().withName(table).withUnique(uniqueEnds).create()
        : schema.buildVertexType().withName(table).create();
    boolean made = false;
    final List<String> counters = new ArrayList<>();
    try {
      for (final Spec spec : columns.values())
        if (!endTables.containsKey(spec.name)) {
          final Property property = createProperty(type, spec);
          if (spec.counted != null) {
            final String counter = Sequences.create(database, table, spec.name, COUNTED_TYPES.get(spec.mapped.type()));
            counters.add(counter);
            property.setDefaultValue(Sequences.defaultDefinition(counter));
          }
        }
      for (final Key key : keys)
        if (key.columns().stream().noneMatch(endTables::containsKey))
          type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, key.columns().toArray(new String[0]));
      if (edge) {
        declare(type, endTables.get(Column.SOURCE_ID), EdgeEndpoints.SOURCE_KEY, EdgeEndpoints.SOURCE_COLUMN_KEY,
            EdgeEndpoints.SOURCE_ON_DELETE_KEY, EdgeEndpoints.SOURCE_ON_UPDATE_KEY);
        declare(type, endTables.get(Column.TARGET_ID), EdgeEndpoints.TARGET_KEY, EdgeEndpoints.TARGET_COLUMN_KEY,
            EdgeEndpoints.TARGET_ON_DELETE_KEY, EdgeEndpoints.TARGET_ON_UPDATE_KEY);
      }
      type.setCustomValue(TableDefinition.CUSTOM_KEY, definition.toJson());
      createForeignKeys(type, checked);
      made = true;
      created = type;
    } finally {
      // the schema is not transactional: a table that could not be made whole is taken back
      if (!made) {
        schema.dropType(table);
        Sequences.drop(database, counters);
      }
    }
  }

  /**
   * Declares one end on the edge type ({@link EdgeEndpoints}): its table, and for an end that refers to a key, the column and the
   * rules that differ from what an absent entry says.
   */
  private static void declare(final DocumentType type, final End end, final String tableKey, final String columnKey,
      final String onDeleteKey, final String onUpdateKey) {
    type.setCustomValue(tableKey, end.table());
    if (end.column() == null)
      return;
    type.setCustomValue(columnKey, end.column());
    if (end.onDelete() != Table.Action.CASCADE)
      type.setCustomValue(onDeleteKey, end.onDelete().sql());
    if (end.onUpdate() != Table.Action.NO_ACTION)
      type.setCustomValue(onUpdateKey, end.onUpdate().sql());
  }

  private Property createProperty(final DocumentType type, final Spec spec) {
    final Mapped mapped = spec.mapped;
    final Property property = mapped.ofType() == null ? type.createProperty(spec.name, mapped.engine())
        : type.createProperty(spec.name, mapped.engine(), mapped.ofType());
    if (spec.notNull) {
      property.setMandatory(true);
      property.setNotNull(true);
    }
    if (mapped.dimensions() > 0) {
      property.setMin(Integer.toString(mapped.dimensions()));
      property.setMax(Integer.toString(mapped.dimensions()));
    }
    if (spec.clock)
      // an expression of the sql language, which reads the clock for each record that another language creates
      property.setDefaultValue(ClockDefault.SCHEMA_DEFAULT);
    else if (spec.hasDefault) {
      // a default that is a string is an expression of the sql language: it is written as a literal of that language
      final Object value = spec.defaultValue instanceof String text ? sqlLiteral(text) : spec.defaultValue;
      property.setDefaultValue(value);
      if (spec.defaultValue instanceof String text && !Objects.equals(text, property.getDefaultValue()))
        throw unsupported("This text as a DEFAULT", spec.literal.position());
    }
    return property;
  }

  private static String sqlLiteral(final String text) {
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
  }

  // ------------------------------------------------------------------------------------------------ errors

  private static PostgreSQLEvaluationException defaultAndIdentity(final String table, final String column) {
    return new PostgreSQLEvaluationException("42601",
        "both default and identity specified for column \"" + column + "\" of table \"" + table + "\"");
  }

  private static PostgreSQLEvaluationException conflictingNull(final String table, final String column) {
    return new PostgreSQLEvaluationException("42601",
        "conflicting NULL/NOT NULL declarations for column \"" + column + "\" of table \"" + table + "\"");
  }

  private static PostgreSQLFeatureNotSupportedException unsupported(final String what, final SourcePosition position) {
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language (" + position + ")");
  }
}
