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
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.IndexCursor;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The rows of one table. How it reads them is its access path, chosen by the planner from the {@code WHERE} clause:
 * <ul>
 * <li>{@link Access#SCAN}: every record of the type. {@code FROM t} reads the type and its subtypes (a polymorphic read),
 * {@code FROM ONLY t} reads the type alone. An edge table is read as the catalog says ({@link Table#edgeScan()}): the buckets
 * of the type, or, when a lightweight edge is possible and a bucket scan would miss it, a walk over the outgoing edges of the
 * vertices of {@link Table#walkFromTables()}.</li>
 * <li>{@link Access#INDEX}: the records an index returns for one key, for {@code WHERE col = $1} or a literal, on a property
 * with an index of its own on exactly that property. The table must not have subtypes unless it is read {@code ONLY}, because
 * the index of a type covers its own buckets.</li>
 * <li>{@link Access#ID}: one record by its record id, for {@code WHERE id = '#12:3'}. The record is read only if it belongs to
 * the table.</li>
 * </ul>
 * An access path only narrows what is read: the planner keeps the whole {@code WHERE} clause as a filter on top, so the answer
 * never depends on the path.
 */
public final class TableSource implements RelationSource {
  public enum Access {
    SCAN, INDEX, ID
  }

  /** The value of a key that no record of the column can have. */
  private static final Object NO_MATCH = new Object();
  private static final Object[] NO_ROW  = new Object[0];

  private final Database       database;
  private final Table          table;
  private final String         relation;
  private final boolean        only;
  private final List<Column>   catalogColumns;
  private final List<RelationSource.Field> columns;
  private final PgType[]       types;
  private final Access         access;
  private final Column         keyColumn;
  private final Evaluable      key;

  public TableSource(final Database database, final Table table, final String relation, final boolean only) {
    this(database, table, relation, only, Access.SCAN, null, null);
  }

  private TableSource(final Database database, final Table table, final String relation, final boolean only, final Access access,
      final Column keyColumn, final Evaluable key) {
    this.database = database;
    this.table = table;
    this.relation = relation;
    this.only = only;
    this.catalogColumns = table.columns();
    this.types = new PgType[catalogColumns.size()];
    final List<RelationSource.Field> described = new ArrayList<>();
    for (int i = 0; i < types.length; i++) {
      final Column column = catalogColumns.get(i);
      types[i] = PgType.of(column.type());
      final String typeName = column.type().isArray() ? "_" + column.type().elementType().typeName() : column.type().typeName();
      described.add(new RelationSource.Field(relation, column.name(), types[i], typeName));
    }
    this.columns = List.copyOf(described);
    this.access = access;
    this.keyColumn = keyColumn;
    this.key = key;
  }

  public Table table() {
    return table;
  }

  public boolean only() {
    return only;
  }

  public Access access() {
    return access;
  }

  /** The same table read through the index on the property of {@code column}, for the rows where it equals {@code key}. */
  public TableSource withIndexLookup(final Column column, final Evaluable key) {
    return new TableSource(database, table, relation, only, Access.INDEX, column, key);
  }

  /** The same table read as one record, the one whose id is {@code key}. */
  public TableSource withIdLookup(final Column column, final Evaluable key) {
    return new TableSource(database, table, relation, only, Access.ID, column, key);
  }

  /**
   * Whether a property can be read through an index on it: the type has an index of its own on exactly this property, the
   * table can be read through the index (see the class comment) and the property has a type whose key the lookup knows.
   */
  public boolean hasIndexOn(final Column column) {
    if (column.kind() != Column.Kind.PROPERTY || column.property() == null)
      return false;
    if (!only && !table.subTables().isEmpty())
      return false;
    final DocumentType type = table.documentType();
    final Property property = type.getPropertyIfExists(column.property());
    if (property == null || type.getIndexByProperties(column.property()) == null)
      return false;
    final Type propertyType = property.getType();
    return propertyType == Type.INTEGER || propertyType == Type.LONG || propertyType == Type.SHORT || propertyType == Type.STRING;
  }

  @Override
  public List<RelationSource.Field> columns() {
    return columns;
  }

  @Override
  public String describe() {
    final String name = table.name() + (relation.equals(table.name()) ? "" : " AS " + relation);
    switch (access) {
    case ID:
      return "READ RECORD BY ID " + name + " (" + keyColumn.name() + " = key)";
    case INDEX:
      return "FETCH FROM INDEX " + table.name() + "[" + keyColumn.property() + "] (" + keyColumn.name() + " = key)";
    default:
      if (table.kind() == Table.Kind.EDGE && table.edgeScan() == Table.EdgeScan.VERTEX_WALK)
        return "WALK EDGES " + name + " FROM " + String.join(", ", table.walkFromTables()) + (only ? " ONLY" : " (polymorphic)");
      return "SCAN TYPE " + name + (only ? " ONLY" : " (polymorphic)");
    }
  }

  @Override
  public Iterator<Object[]> open() {
    final Iterator<Document> records = openRecords();
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return records.hasNext();
      }

      @Override
      public Object[] next() {
        return row(records.next());
      }
    };
  }

  /**
   * The records of the rows, by the same access path as {@link #open()}: what {@code UPDATE} and {@code DELETE} need, because
   * they change the record behind a row. {@link #rowOf(Document)} gives the row of one of them.
   */
  public Iterator<Document> openRecords() {
    switch (access) {
    case ID:
      return readById();
    case INDEX:
      return readByIndex();
    default:
      return scan();
    }
  }

  /** The row of a record of this table, the values in the canonical classes of {@link PgType}. */
  public Object[] rowOf(final Document record) {
    return row(record);
  }

  // ---- reading ----

  private Object[] row(final Document record) {
    final Object[] values = new Object[types.length];
    for (int i = 0; i < values.length; i++)
      values[i] = Casts.fromEngine(catalogColumns.get(i).read(record), types[i]);
    return values;
  }

  /** Whether a record is a row of this table: of the type, or of a subtype unless the read is {@code ONLY}. */
  private boolean belongs(final Document record) {
    final String typeName = record.getTypeName();
    if (typeName == null || typeName.equals(table.name()))
      return true;
    return !only && database.getSchema().getType(typeName).instanceOf(table.name());
  }

  private Iterator<Document> scan() {
    if (table.kind() == Table.Kind.EDGE && table.edgeScan() == Table.EdgeScan.VERTEX_WALK)
      return walk();
    final Iterator<Record> records = database.iterateType(table.name(), !only);
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return records.hasNext();
      }

      @Override
      public Document next() {
        return (Document) records.next();
      }
    };
  }

  /** The type of the table and, unless the read is {@code ONLY}, all its subtypes: the edge types a walk asks for. */
  private List<String> typeNames() {
    final List<String> names = new ArrayList<>();
    names.add(table.name());
    if (!only)
      collectSubTypes(table.documentType(), names);
    return names;
  }

  private static void collectSubTypes(final DocumentType type, final List<String> names) {
    for (final DocumentType subType : type.getSubTypes()) {
      names.add(subType.getName());
      collectSubTypes(subType, names);
    }
  }

  /** The outgoing edges of the vertices a lightweight edge can start at: nothing else sees a lightweight edge. */
  private Iterator<Document> walk() {
    final String[] edgeTypes = typeNames().toArray(new String[0]);
    final Iterator<String> fromTables = table.walkFromTables().iterator();
    return new Iterator<>() {
      private Iterator<Record> vertices;
      private Iterator<Edge>   edges = Collections.emptyIterator();
      private Document         next;

      private boolean advance() {
        while (true) {
          if (edges.hasNext()) {
            final Edge edge = edges.next();
            if (belongs(edge)) {
              next = edge;
              return true;
            }
          } else if (vertices != null && vertices.hasNext()) {
            final Vertex vertex = (Vertex) vertices.next();
            edges = vertex.getEdges(Vertex.DIRECTION.OUT, edgeTypes).iterator();
          } else if (fromTables.hasNext())
            vertices = database.iterateType(fromTables.next(), true);
          else
            return false;
        }
      }

      @Override
      public boolean hasNext() {
        return next != null || advance();
      }

      @Override
      public Document next() {
        if (!hasNext())
          throw new NoSuchElementException();
        final Document result = next;
        next = null;
        return result;
      }
    };
  }

  private Iterator<Document> readById() {
    final Object value = key.eval(NO_ROW);
    if (!(value instanceof String text))
      return Collections.emptyIterator();
    final Record record;
    try {
      record = database.lookupByRID(new RID(text), true);
    } catch (final RuntimeException e) {
      // not a record id, or a record that does not exist: no row has this id
      return Collections.emptyIterator();
    }
    if (record instanceof Document document && belongs(document))
      return Collections.singletonList(document).iterator();
    return Collections.emptyIterator();
  }

  private Iterator<Document> readByIndex() {
    final Object value = key.eval(NO_ROW);
    if (value == null)
      return Collections.emptyIterator();
    final DocumentType type = table.documentType();
    final TypeIndex index = type.getIndexByProperties(keyColumn.property());
    if (index == null)
      return scan();
    final Object indexKey = toIndexKey(value, type.getProperty(keyColumn.property()).getType());
    if (indexKey == NO_MATCH)
      return Collections.emptyIterator();
    final List<Document> found = new ArrayList<>();
    final IndexCursor cursor = index.get(new Object[] { indexKey });
    while (cursor.hasNext()) {
      final Identifiable identifiable = cursor.next();
      final Record record = identifiable.getRecord();
      if (record instanceof Document document && belongs(document))
        found.add(document);
    }
    return found.iterator();
  }

  /** The key as the index of a property of this type holds it, or {@link #NO_MATCH} when no value of the type is equal. */
  private static Object toIndexKey(final Object value, final Type propertyType) {
    if (propertyType == Type.STRING)
      return value instanceof String ? value : NO_MATCH;
    final long number;
    if (value instanceof Number integral && !(value instanceof BigDecimal) && !(value instanceof Float)
        && !(value instanceof Double))
      number = integral.longValue();
    else
      return NO_MATCH;
    if (propertyType == Type.INTEGER)
      return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? (Object) (int) number : NO_MATCH;
    if (propertyType == Type.SHORT)
      return number >= Short.MIN_VALUE && number <= Short.MAX_VALUE ? (Object) (short) number : NO_MATCH;
    return number;
  }
}
