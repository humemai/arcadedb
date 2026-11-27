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
import com.arcadedb.query.postgresql.catalog.EndKey;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Type;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;

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
  /**
   * Where a row keeps the record id of its record, for a walk that starts at it, or {@code -1}: only a table whose key an end of an
   * edge table refers to has the slot (fork issue #213).
   */
  private final int            ownRecordSlot;
  /**
   * By column, where a row of a walk keeps the record at an end that refers to a key, or {@code -1}: the join that reads that record
   * takes it from the row.
   */
  private final int[]          endRecordSlots;
  private final Access         access;
  private final Column         keyColumn;
  private final Evaluable      key;
  /** The columns a row holds, {@code null} for all of them; the others stay {@code null} (fork issue #85). */
  private final int[]          readColumns;
  /** The condition the scan applies before it reads the rest of a row, with the columns it needs, or {@code null}. */
  private final Evaluable      scanFilter;
  private final int[]          filterColumns;
  private final int[]          restColumns;
  /** The column values read from records since this source was built, for the tests. Not thread safe. */
  private long                 columnsRead;

  public TableSource(final Database database, final Table table, final String relation, final boolean only) {
    this(database, table, relation, only, Access.SCAN, null, null);
  }

  private TableSource(final Database database, final Table table, final String relation, final boolean only, final Access access,
      final Column keyColumn, final Evaluable key) {
    this(database, table, relation, only, access, keyColumn, key, null, null, null);
  }

  private TableSource(final Database database, final Table table, final String relation, final boolean only, final Access access,
      final Column keyColumn, final Evaluable key, final int[] readColumns, final Evaluable scanFilter, final int[] filterColumns) {
    this.database = database;
    this.table = table;
    this.relation = relation;
    this.only = only;
    if (table.broken() != null)
      throw new PostgreSQLEvaluationException("55000", table.broken());
    this.catalogColumns = table.columns();
    final int count = catalogColumns.size();
    int width = count;
    this.ownRecordSlot = table.isReferencedByKey() ? width++ : -1;
    this.endRecordSlots = new int[count];
    for (int i = 0; i < count; i++)
      endRecordSlots[i] = catalogColumns.get(i).endKey() != null ? width++ : -1;
    this.types = new PgType[width];
    final List<RelationSource.Field> described = new ArrayList<>(width);
    for (int i = 0; i < count; i++) {
      final Column column = catalogColumns.get(i);
      types[i] = PgType.of(column.type());
      final String typeName = column.type().isArray() ? "_" + column.type().elementType().typeName() : column.type().typeName();
      described.add(new RelationSource.Field(relation, column.name(), types[i], typeName, column.isHidden(), column.isListed()));
    }
    // the slots after the columns, which no statement can name: they hold records for the walks of the join planner
    for (int i = count; i < width; i++) {
      types[i] = PgType.UNKNOWN;
      described.add(new RelationSource.Field(relation, "\u0000" + i, PgType.UNKNOWN, "unknown", true, false));
    }
    this.columns = List.copyOf(described);
    this.access = access;
    this.keyColumn = keyColumn;
    this.key = key;
    this.readColumns = readColumns;
    this.scanFilter = scanFilter;
    this.filterColumns = filterColumns;
    if (scanFilter == null)
      this.restColumns = null;
    else {
      final BitSet rest = new BitSet();
      for (final int column : readColumns)
        rest.set(column);
      for (final int column : filterColumns)
        rest.clear(column);
      this.restColumns = rest.stream().toArray();
    }
  }

  /**
   * The same scan, whose rows hold only these columns, the others {@code null}: for a statement whose expressions read no other
   * column (fork issue #85). Only a scan is changed; the planner calls it when it knows every position its expressions read.
   */
  public TableSource withReadColumns(final BitSet columns) {
    return new TableSource(database, table, relation, only, access, keyColumn, key, columns.stream().toArray(), null, null);
  }

  /**
   * The same scan, which evaluates {@code condition} on a row that holds only {@code conditionColumns} and reads the rest of the
   * row, up to {@code columns}, only for a record that passes. The condition is true or the record is dropped, as in a {@code WHERE};
   * the caller leaves the condition out of the rows it takes from this source.
   */
  public TableSource withScanFilter(final Evaluable condition, final BitSet conditionColumns, final BitSet columns) {
    return new TableSource(database, table, relation, only, access, keyColumn, key, columns.stream().toArray(), condition,
        conditionColumns.stream().toArray());
  }

  /**
   * The same source, whose rows hold only the columns that have one of these names (lower case), the others {@code null}: for a
   * table that a join reads, when the statement names no other column (fork issue #90, {@link ColumnNames}).
   */
  public TableSource withColumnNames(final Set<String> names) {
    final BitSet columns = new BitSet();
    for (int i = 0; i < catalogColumns.size(); i++)
      if (names.contains(catalogColumns.get(i).name().toLowerCase(Locale.ROOT)))
        columns.set(i);
    return new TableSource(database, table, relation, only, access, keyColumn, key, columns.stream().toArray(), null, null);
  }

  /** The column values read from records since this source was built (for the tests). */
  public long columnsRead() {
    return columnsRead;
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

  /** The name the statement uses for this table: its alias, or the table name when there is no alias. */
  public String relation() {
    return relation;
  }

  /**
   * The row of a record of this table, the values of its columns in the evaluator's value space. The traversal of the join
   * planner reads the edges of a vertex and turns each into the row a scan of the edge table gives.
   */
  public Object[] rowOf(final Document record) {
    return readColumns == null ? row(record, (Vertex) null) : row(record, readColumns, null);
  }

  /**
   * {@link #rowOf(Document)} of an edge that a walk from the vertex {@code near} reached: that end is read from it, and the row keeps
   * the records at the ends that refer to a key.
   */
  public Object[] rowOfWalk(final Document edge, final Vertex near) {
    return readColumns == null ? row(edge, near) : row(edge, readColumns, near);
  }

  /** The position in a row of the record id of its record, {@code -1} when rows keep none. */
  public int ownRecordSlot() {
    return ownRecordSlot;
  }

  /** The position in a row of a walk of the record at this end, which refers to a key. */
  public int endRecordSlot(final Column end) {
    return endRecordSlots[catalogColumns.indexOf(end)];
  }

  /**
   * The rows that can match a join on the key of a record that a walk read: the row of that record when it is one of this table.
   * With no record, which the walk gives only for a link it did not read the end of, every row. The join compares the keys.
   */
  public Iterator<Object[]> openRecord(final Object record) {
    if (!(record instanceof Document document))
      return open();
    return belongs(document) ? Collections.singletonList(rowOf(document)).iterator() : Collections.emptyIterator();
  }

  /** The plan line of {@link #openRecord}. */
  public Supplier<String> recordLine() {
    final String name = table.name() + (relation.equals(table.name()) ? "" : " AS " + relation);
    return () -> "READ RECORD " + name + " (the record at the end of the edge)";
  }

  /** Whether a record is a row of this table: of the type, or of a subtype unless the read is {@code ONLY}. */
  public boolean holds(final Document record) {
    return belongs(record);
  }

  /** The edge types a read of this table covers: the type and, unless the read is {@code ONLY}, all its subtypes. */
  public String[] edgeTypeNames() {
    return typeNames().toArray(new String[0]);
  }

  /** The same table read through the index on the property of {@code column}, for the rows where it equals {@code key}. */
  public TableSource withIndexLookup(final Column column, final Evaluable key) {
    return new TableSource(database, table, relation, only, Access.INDEX, column, key, readColumns, null, null);
  }

  /** The same table read as one record, the one whose id is {@code key}. */
  public TableSource withIdLookup(final Column column, final Evaluable key) {
    return new TableSource(database, table, relation, only, Access.ID, column, key, readColumns, null, null);
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
    return describe(table, relation, only, access, keyColumn);
  }

  @Override
  public Supplier<List<String>> planLines() {
    final Supplier<String> line = line(access, keyColumn);
    return () -> List.of(line.get());
  }

  /** The plan line of a lookup of this table by {@code column}, built when it is read. */
  public Supplier<String> lookupLine(final Column column) {
    return line(column.kind() == Column.Kind.IDENTITY ? Access.ID : Access.INDEX, column);
  }

  /** A plan line built from the names alone: it keeps neither this source nor its keys. */
  private Supplier<String> line(final Access how, final Column column) {
    final Table named = table;
    final String as = relation;
    final boolean exact = only;
    return () -> describe(named, as, exact, how, column);
  }

  private static String describe(final Table table, final String relation, final boolean only, final Access access,
      final Column keyColumn) {
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
    if (scanFilter != null)
      return filtered(records);
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return records.hasNext();
      }

      @Override
      public Object[] next() {
        return rowOf(records.next());
      }
    };
  }

  /** The rows of the records that pass the scan filter: the condition's columns first, the rest of the row for a record that passes. */
  private Iterator<Object[]> filtered(final Iterator<Document> records) {
    return new Iterator<>() {
      private Object[] next;

      @Override
      public boolean hasNext() {
        while (next == null && records.hasNext()) {
          final Document record = records.next();
          final Object[] values = new Object[types.length];
          fill(values, record, filterColumns, null);
          if (Boolean.TRUE.equals(scanFilter.eval(values))) {
            fill(values, record, restColumns, null);
            keepRecordId(values, record);
            next = values;
          }
        }
        return next != null;
      }

      @Override
      public Object[] next() {
        if (!hasNext())
          throw new NoSuchElementException();
        final Object[] result = next;
        next = null;
        return result;
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

  // ---- reading ----

  private Object[] row(final Document record, final Vertex near) {
    final Object[] values = new Object[types.length];
    final int count = catalogColumns.size();
    for (int i = 0; i < count; i++)
      values[i] = read(values, record, i, near);
    columnsRead += count;
    keepRecordId(values, record);
    return values;
  }

  /** The row of a record with only these columns read, the others {@code null}. */
  private Object[] row(final Document record, final int[] columns, final Vertex near) {
    final Object[] values = new Object[types.length];
    fill(values, record, columns, near);
    keepRecordId(values, record);
    return values;
  }

  private void fill(final Object[] values, final Document record, final int[] columns, final Vertex near) {
    for (final int i : columns)
      values[i] = read(values, record, i, near);
    columnsRead += columns.length;
  }

  /** The value of one column, and for an end that refers to a key, in a row of a walk, the record at that end in its slot. */
  private Object read(final Object[] values, final Document record, final int i, final Vertex near) {
    final Column column = catalogColumns.get(i);
    final EndKey key = column.endKey();
    final Object value;
    if (key == null)
      value = column.read(record);
    else {
      final Edge edge = record instanceof Edge found ? found : record.asEdge();
      final Vertex end = key.endOf(edge, column, near);
      if (near != null)
        values[endRecordSlots[i]] = end;
      value = key.read(edge, column, end);
    }
    return Casts.fromColumn(value, column.type(), types[i]);
  }

  /** Puts the record id of a record in its slot, when rows have one. */
  void keepRecordId(final Object[] values, final Document record) {
    if (ownRecordSlot >= 0)
      values[ownRecordSlot] = record.getIdentity();
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
