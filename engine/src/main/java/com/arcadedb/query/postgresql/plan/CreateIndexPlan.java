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
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.ast.CreateIndexStatement;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SourcePosition;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.DeclaredIndexes;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.TypeLSMVectorIndexBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code CREATE INDEX} (fork issue #125). The default method and {@code btree} make the tree index of the engine, {@code hnsw} the
 * {@code LSM_VECTOR} index with the similarity of its operator class, and every other method is refused.
 * <ul>
 * <li>A vector index takes its dimensions from the column, which has to be declared {@code vector(n)}.</li>
 * <li>Of the parameters of pgvector, {@code ef_construction} is the beam width of the engine. {@code m} counts the neighbours of a
 * node on an upper layer, and twice as many on the base layer, while the degree of the engine is one number for every layer: the
 * engine's own rule for the same graph is {@code 2 * m}.</li>
 * <li>The engine reaches the edges of a vertex without an index, so an index on an end of an edge, or on a record id, only records
 * its name. An index over columns that have one records its name too: one index of the engine serves both.</li>
 * </ul>
 * The checks follow PostgreSQL's SQLSTATEs; what PostgreSQL accepts and the engine cannot do is {@code 0A000} with its position.
 */
public final class CreateIndexPlan {
  /** The types a column of a tree index can be of. */
  private static final List<ColumnType> TREE_TYPES = List.of(ColumnType.BOOL, ColumnType.INT2, ColumnType.INT4, ColumnType.INT8,
      ColumnType.NUMERIC, ColumnType.FLOAT4, ColumnType.FLOAT8, ColumnType.TEXT, ColumnType.VARCHAR, ColumnType.DATE,
      ColumnType.TIMESTAMP, ColumnType.TIMESTAMPTZ);

  /** The similarity of the engine behind each operator class of pgvector. */
  private static final Map<String, String> SIMILARITIES = Map.of("vector_l2_ops", "EUCLIDEAN", "vector_cosine_ops", "COSINE",
      "vector_ip_ops", "DOT_PRODUCT");

  /** pgvector's own defaults of {@code m} and {@code ef_construction}, which its check of the two uses. */
  private static final long DEFAULT_M               = 16;
  private static final long DEFAULT_EF_CONSTRUCTION = 64;

  private final Database             database;
  private final RelationalCatalog    catalog;
  private final CreateIndexStatement statement;

  public CreateIndexPlan(final Database database, final RelationalCatalog catalog, final CreateIndexStatement statement) {
    this.database = database;
    this.catalog = catalog;
    this.statement = statement;
  }

  /**
   * Makes the index; the answer has no column and no row.
   *
   * @param permitted asked about the table once its name, the name of the index and the columns hold, and before anything is
   *                  changed: it throws when the schema of that table may not be changed now. A statement that changes nothing
   *                  ({@code IF NOT EXISTS} of a taken name) does not ask.
   */
  public PostgreSQLResultSet execute(final Consumer<DocumentType> permitted) {
    final Table table = table();
    final String given = statement.name() == null ? null : statement.name().value();
    if (given != null && taken(given)) {
      if (statement.ifNotExists())
        return answer("CREATE INDEX " + given + ": the relation exists, nothing to do");
      throw PostgreSQLEvaluationException.duplicateRelation(
          database.getSchema().existsType(given) ? AlreadyExistsException.Kind.TYPE : AlreadyExistsException.Kind.INDEX, given);
    }

    final String method = statement.method() == null ? "btree" : statement.method().value();
    final boolean vector = "hnsw".equals(method);
    if (!vector && !"btree".equals(method))
      throw unsupported("The index method " + method, statement.method().position());

    final List<Column> columns = new ArrayList<>();
    final List<String> names = new ArrayList<>();
    for (final CreateIndexStatement.Element element : statement.elements()) {
      final Column column = table.findColumn(identifier(element.column()));
      if (column == null)
        throw new PostgreSQLEvaluationException("42703", "column \"" + element.column().value() + "\" does not exist");
      if (names.contains(column.name()))
        throw unsupported("An index that names a column twice", element.position());
      columns.add(column);
      names.add(column.name());
    }

    permitted.accept(database.getSchema().getType(table.name()));

    final boolean engine = vector ? vectorIndex(table, columns.get(0)) : treeIndex(table, columns);
    final String name = given != null ? given : freeName(table.name(), names);
    DeclaredIndexes.add(database.getSchema().getType(table.name()), name,
        new DeclaredIndexes.Index(engine ? columns.stream().map(Column::property).toList() : names, statement.unique(), engine));
    return answer("CREATE INDEX " + name + " ON " + table.name());
  }

  private static PostgreSQLResultSet answer(final String step) {
    return new PostgreSQLResultSet(List.of(), new PostgreSQLResultSet.Plan(List.of(step))).rowless().withAffectedRows(0);
  }

  // ------------------------------------------------------------------------------------------------ the table and the names

  private static Identifier identifier(final Name name) {
    return new Identifier(name.value(), name.quoted());
  }

  private Table table() {
    final List<Name> name = statement.table();
    if (name.size() == 2) {
      final String schema = name.get(0).value();
      if ("information_schema".equals(schema) || "pg_catalog".equals(schema))
        throw unsupported("An index on a table of the schema " + schema, statement.position());
      catalog.requireSchema(identifier(name.get(0)));
    }
    return catalog.table(identifier(name.get(name.size() - 1)));
  }

  /** Whether a type, a declared constraint or an index made by this statement has the name already. */
  private boolean taken(final String name) {
    final Schema schema = database.getSchema();
    if (schema.existsType(name))
      return true;
    for (final DocumentType type : schema.getTypes()) {
      if (DeclaredIndexes.of(type).containsKey(name))
        return true;
      final TableDefinition definition = TableDefinition.of(type);
      if (definition != null && (definition.primaryKey() != null && definition.primaryKey().name().equals(name)
          || definition.uniqueKeys().stream().anyMatch(key -> key.name().equals(name))))
        return true;
    }
    return false;
  }

  /** The name PostgreSQL gives an index that has none: the table, the columns and {@code idx}, with a number when it is taken. */
  private String freeName(final String table, final List<String> columns) {
    final String base = table + "_" + String.join("_", columns) + "_idx";
    String name = base;
    for (int next = 1; taken(name); next++)
      name = base + next;
    return name;
  }

  // ------------------------------------------------------------------------------------------------ the tree index

  /** @return whether an index of the engine stands behind the statement */
  private boolean treeIndex(final Table table, final List<Column> columns) {
    if (!statement.parameters().isEmpty())
      throw unsupported("A parameter of a tree index", statement.parameters().get(0).position());
    for (final CreateIndexStatement.Element element : statement.elements())
      if (element.operatorClass() != null)
        throw unsupported("An operator class of a tree index", element.operatorClass().position());

    final DocumentType type = database.getSchema().getType(table.name());
    if (columns.stream().anyMatch(Column::isSynthetic)) {
      // a record id is unique by itself, and the two ends of a unique edge type are unique together
      final boolean uniqueAlready = columns.size() == 1 && columns.get(0).kind() == Column.Kind.IDENTITY
          || columns.size() == 2 && columns.get(0).kind() == Column.Kind.SOURCE && columns.get(1).kind() == Column.Kind.TARGET
          && type instanceof EdgeType edge && edge.isUnique();
      if (statement.unique() && !uniqueAlready)
        throw unsupported("A unique index over an end of an edge or a record id that is not unique already", statement.position());
      return false;
    }

    for (int i = 0; i < columns.size(); i++)
      if (!TREE_TYPES.contains(columns.get(i).type()))
        throw unsupported("An index over a column of type " + columns.get(i).type(), statement.elements().get(i).position());
    final String[] properties = columns.stream().map(Column::property).toArray(String[]::new);

    final TypeIndex existing = type.getPolymorphicIndexByProperties(properties);
    if (existing != null) {
      if (statement.unique() && !existing.isUnique())
        throw unsupported("A unique index over columns that have an index that is not unique", statement.position());
      return true;
    }
    try {
      type.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, statement.unique(), properties);
    } catch (final RuntimeException e) {
      // an index that could not be built over the rows is not left behind
      final TypeIndex left = type.getPolymorphicIndexByProperties(properties);
      if (left != null)
        database.getSchema().dropIndex(left.getName());
      for (Throwable cause = e; cause != null; cause = cause.getCause())
        if (cause instanceof DuplicatedKeyException)
          throw new PostgreSQLEvaluationException("23505",
              "could not create unique index: the table " + table.name() + " holds a key more than once", e);
      throw e;
    }
    return true;
  }

  // ------------------------------------------------------------------------------------------------ the vector index

  private boolean vectorIndex(final Table table, final Column column) {
    final CreateIndexStatement.Element element = statement.elements().get(0);
    if (statement.unique())
      throw unsupported("A unique hnsw index", statement.position());
    if (statement.elements().size() != 1)
      throw unsupported("An hnsw index over several columns", statement.position());
    if (column.type() != ColumnType.VECTOR)
      throw unsupported("An hnsw index over a column that is not a vector", element.position());
    if (element.operatorClass() == null)
      throw new PostgreSQLEvaluationException("42704", "data type vector has no default operator class for access method"
          + " \"hnsw\". Hint: write vector_l2_ops, vector_cosine_ops or vector_ip_ops after the column");
    final String similarity = SIMILARITIES.get(element.operatorClass().value());
    if (similarity == null)
      throw unsupported("The operator class " + element.operatorClass().value(), element.operatorClass().position());

    final Map<String, Long> parameters = parameters();
    final DocumentType type = database.getSchema().getType(table.name());
    final Property property = type.getPolymorphicPropertyIfExists(column.property());
    if (property == null || property.getMin() == null || !property.getMin().equals(property.getMax()))
      throw unsupported("An hnsw index over a column without a number of dimensions (declare it vector(n) in CREATE TABLE)",
          element.position());
    final int dimensions = Integer.parseInt(property.getMin());

    if (type.getPolymorphicIndexByProperties(column.property()) != null)
      // the engine keeps one index over a property, and nothing here could say that it is the one this statement asks for
      throw unsupported("A second index over the column " + column.name(), element.position());

    database.transaction(() -> {
      final TypeLSMVectorIndexBuilder builder = database.getSchema()
          .buildTypeIndex(table.name(), new String[] { column.property() }).withLSMVectorType().withDimensions(dimensions)
          .withSimilarity(similarity);
      if (parameters.containsKey("m"))
        builder.withMaxConnections((int) (2 * parameters.get("m")));
      if (parameters.containsKey("ef_construction"))
        builder.withBeamWidth(parameters.get("ef_construction").intValue());
      builder.create();
    });
    return true;
  }

  /** The parameters of an hnsw index that mean something to the engine, with pgvector's ranges. */
  private Map<String, Long> parameters() {
    final Map<String, Long> parameters = new LinkedHashMap<>();
    for (final CreateIndexStatement.Parameter parameter : statement.parameters()) {
      final String name = parameter.name().value();
      final long least;
      final long most;
      switch (name) {
      case "m" -> {
        least = 2;
        most = 100;
      }
      case "ef_construction" -> {
        least = 4;
        most = 1000;
      }
      default -> throw unsupported("The index parameter " + name, parameter.position());
      }
      if (parameters.put(name, parameter.value()) != null)
        throw new PostgreSQLEvaluationException("22023", "parameter \"" + name + "\" specified more than once");
      if (parameter.value() < least || parameter.value() > most)
        throw new PostgreSQLEvaluationException("22023",
            "value " + parameter.value() + " out of bounds for option \"" + name + "\"");
    }
    if (parameters.getOrDefault("ef_construction", DEFAULT_EF_CONSTRUCTION) < 2 * parameters.getOrDefault("m", DEFAULT_M))
      throw new PostgreSQLEvaluationException("22023", "ef_construction must be greater than or equal to 2 * m");
    return parameters;
  }

  private static PostgreSQLFeatureNotSupportedException unsupported(final String what, final SourcePosition position) {
    return new PostgreSQLFeatureNotSupportedException(what + " is not supported by the postgresql language (" + position + ")");
  }
}
