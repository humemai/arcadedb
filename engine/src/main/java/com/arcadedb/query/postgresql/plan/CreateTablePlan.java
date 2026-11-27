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
import com.arcadedb.query.postgresql.ast.CreateTableStatement;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.ast.TypeName;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.Parameters;
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
 * {@code source_id} and {@code target_id}, each {@code text NOT NULL REFERENCES a_table (rid) ON DELETE CASCADE}, is an edge
 * type. Every other {@code REFERENCES} is refused, because the engine keeps no foreign key but the ends of an edge.
 * <p>
 * What a write has to keep goes into the schema, so it holds for every language: the property types, mandatory and not-null for
 * {@code NOT NULL}, a unique index for each {@code UNIQUE} and {@code PRIMARY KEY} (a unique edge type for a key over the two
 * ends), the defaults, and the dimensions of a {@code vector(n)} as the minimum and maximum size of its property. What only
 * describes the table is its {@link TableDefinition}.
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

  /** The type of a column, in the engine and in the view; {@code dimensions} is 0 unless the column is a {@code vector(n)}. */
  private record Mapped(ColumnType type, Type engine, String ofType, int dimensions) {
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
    Object                 defaultValue;
    String                 defaultText;

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

  private final Database             database;
  private final RelationalCatalog    catalog;
  private final CreateTableStatement statement;

  public CreateTablePlan(final Database database, final RelationalCatalog catalog, final CreateTableStatement statement) {
    this.database = database;
    this.catalog = catalog;
    this.statement = statement;
  }

  /** Makes the table; the answer has no column and no row. */
  public PostgreSQLResultSet execute() {
    final String table = tableName(statement.table(), statement.position());
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

    final Map<String, ForeignKey> ends = foreignKeys.isEmpty() ? Map.of() : ends(table, columns, foreignKeys);
    final Map<String, String> endTables = new LinkedHashMap<>();
    for (final String end : ENDS)
      if (ends.containsKey(end))
        endTables.put(end, referencedTable(table, ends.get(end).reference()));

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

    final TableDefinition definition = definition(table, columns, unique, ends);
    create(table, columns, unique, endTables, uniqueEnds, definition);
    return answer("CREATE TABLE " + table);
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
    final Spec spec = new Spec(name, column.position(), type(column.type()));
    if (columns.put(name, spec) != null)
      throw new PostgreSQLEvaluationException("42701", "column \"" + name + "\" specified more than once");

    boolean defaultSeen = false;
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
      case DEFAULT -> {
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

  /**
   * The two foreign keys of an edge table, by the column. Any foreign key that is not one of the two ends, as the one accepted
   * form writes it, refuses the statement.
   */
  private static Map<String, ForeignKey> ends(final String table, final Map<String, Spec> columns,
      final List<ForeignKey> foreignKeys) {
    final Map<String, ForeignKey> ends = new LinkedHashMap<>();
    for (final ForeignKey key : foreignKeys) {
      final CreateTableStatement.Reference reference = key.reference();
      final Spec spec = key.columns().size() == 1 ? columns.get(key.columns().get(0)) : null;
      if (spec == null || !ENDS.contains(spec.name) || ends.put(spec.name, key) != null || reference.columns().size() != 1
          || !Column.RID.equals(reference.columns().get(0).value())
          || reference.onDelete() != CreateTableStatement.Action.CASCADE
          || reference.onUpdate() != CreateTableStatement.Action.NO_ACTION)
        throw refusedReference(reference.position());
      if (spec.mapped.type() != ColumnType.TEXT || !spec.notNull || spec.literal != null)
        throw refusedReference(reference.position());
    }
    if (ends.size() != ENDS.size())
      throw refusedReference(foreignKeys.get(0).reference().position());
    return ends;
  }

  /** The vertex table that an end refers to by its record id, as the name of its type. */
  private String referencedTable(final String table, final CreateTableStatement.Reference reference) {
    final String name = tableName(reference.table(), reference.position());
    if (name.equals(table))
      throw refusedReference(reference.position());
    final Table referenced = catalog.findTable(identifier(reference.table().get(reference.table().size() - 1)));
    if (referenced == null)
      throw new PostgreSQLEvaluationException("42P01", "relation \"" + name + "\" does not exist");
    final Column rid = referenced.findColumn(identifier(reference.columns().get(0)));
    if (rid == null)
      throw new PostgreSQLEvaluationException("42703", "column \"rid\" referenced in foreign key constraint does not exist. Hint:"
          + " the record id of a table is rid when CREATE TABLE made the table or it has a column id of its own");
    if (referenced.kind() != Table.Kind.VERTEX || rid.kind() != Column.Kind.IDENTITY)
      throw refusedReference(reference.position());
    return referenced.name();
  }

  private static PostgreSQLFeatureNotSupportedException refusedReference(final SourcePosition position) {
    return unsupported("A foreign key other than the two ends of an edge table (source_id and target_id, each text NOT NULL"
        + " REFERENCES a table (rid) ON DELETE CASCADE)", position);
  }

  // ------------------------------------------------------------------------------------------------ types

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
      final Map<String, ForeignKey> ends) {
    final Set<String> names = new LinkedHashSet<>();
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
    for (final Spec spec : columns.values())
      if (spec.hasDefault)
        defaults.put(spec.name, spec.defaultText);
    return new TableDefinition(List.copyOf(columns.keySet()), text, primary, unique, defaults, foreignKeys, instants);
  }

  private void create(final String table, final Map<String, Spec> columns, final List<Key> keys,
      final Map<String, String> endTables, final boolean uniqueEnds, final TableDefinition definition) {
    final Schema schema = database.getSchema();
    final boolean edge = !endTables.isEmpty();
    final DocumentType type = edge ? schema.buildEdgeType().withName(table).withUnique(uniqueEnds).create()
        : schema.buildVertexType().withName(table).create();
    boolean made = false;
    try {
      for (final Spec spec : columns.values())
        if (!endTables.containsKey(spec.name))
          createProperty(type, spec);
      for (final Key key : keys)
        if (key.columns().stream().noneMatch(endTables::containsKey))
          type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, key.columns().toArray(new String[0]));
      if (edge) {
        type.setCustomValue(EdgeEndpoints.SOURCE_KEY, endTables.get(Column.SOURCE_ID));
        type.setCustomValue(EdgeEndpoints.TARGET_KEY, endTables.get(Column.TARGET_ID));
      }
      type.setCustomValue(TableDefinition.CUSTOM_KEY, definition.toJson());
      made = true;
    } finally {
      // the schema is not transactional: a table that could not be made whole is taken back
      if (!made)
        schema.dropType(table);
    }
  }

  private void createProperty(final DocumentType type, final Spec spec) {
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
    if (spec.hasDefault) {
      // a default that is a string is an expression of the sql language: it is written as a literal of that language
      final Object value = spec.defaultValue instanceof String text ? sqlLiteral(text) : spec.defaultValue;
      property.setDefaultValue(value);
      if (spec.defaultValue instanceof String text && !Objects.equals(text, property.getDefaultValue()))
        throw unsupported("This text as a DEFAULT", spec.literal.position());
    }
  }

  private static String sqlLiteral(final String text) {
    return "'" + text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
  }

  // ------------------------------------------------------------------------------------------------ errors

  private static PostgreSQLEvaluationException conflictingNull(final String table, final String column) {
    return new PostgreSQLEvaluationException("42601",
        "conflicting NULL/NOT NULL declarations for column \"" + column + "\" of table \"" + table + "\"");
  }

  private static PostgreSQLFeatureNotSupportedException unsupported(final String what, final SourcePosition position) {
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language (" + position + ")");
  }
}
