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
import com.arcadedb.database.Document;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * The PostgreSQL side of the {@code small} fixture: the {@code CREATE TABLE} statements and the {@code INSERT}s that make a real
 * PostgreSQL hold the same tables as the relational view of the Java fixture, generated from {@link RelationalCatalog} and the rows
 * of the database, so the two cannot drift. The parity harness of fork issue #2 loads the result into PostgreSQL 18.
 * <p>
 * Public, in the engine test tree, for the loader of that harness: {@link #generate(Database, boolean)} with {@code false} writes the
 * real record ids of a database, which is what a PostgreSQL that is compared with it needs. {@code SmallSchemaSqlTest} checks the
 * normalised form against {@code postgresql/small-schema.sql} of the test resources.
 * <p>
 * Tables come in the order of their foreign keys (referenced tables first, ties by name), so the file loads in one pass. It handles
 * the kinds of column the fixture has; an edge table without records, a {@code bytea} column and a cycle of foreign keys are
 * outside it. A name is written without quotes when it is {@code [a-z_][a-z0-9_]*} and quoted otherwise (reserved words are not
 * looked for, none of the fixture's names is one).
 */
public final class SmallSchemaSql {
  private SmallSchemaSql() {
  }

  /**
   * @param normalizeIds when true, the record ids (the {@code id} columns and every value that refers to one) are renumbered
   *                     {@code #<table number>:<row number>}, the table number counting from 1 in the order of the file and the row
   *                     number from 0 in the order of the records. The record ids of a database depend on the numbers of its files, so
   *                     a file that is checked in has to be written without them
   */
  public static String generate(final Database database, final boolean normalizeIds) {
    final List<Table> tables = inDependencyOrder(RelationalCatalog.of(database).tables());

    final Map<String, List<Document>> rows = new LinkedHashMap<>();
    for (final Table table : tables)
      rows.put(table.name(), scan(database, table));

    final Map<String, String> renumbered = new HashMap<>();
    if (normalizeIds)
      for (int t = 0; t < tables.size(); ++t) {
        final List<Document> tableRows = rows.get(tables.get(t).name());
        for (int r = 0; r < tableRows.size(); ++r)
          renumbered.put(tableRows.get(r).getIdentity().toString(), "#" + (t + 1) + ":" + r);
      }
    final UnaryOperator<String> id = rid -> renumbered.getOrDefault(rid, rid);

    final StringBuilder out = new StringBuilder();
    out.append("-- The PostgreSQL side of the small fixture of the PostgreSQL-style SQL prototype: the tables of the relational view\n");
    out.append("-- (RelationalCatalog) and the rows of the Java fixture (SmallFixture), written by SmallSchemaSql. Do not edit by hand:\n");
    out.append("-- SmallSchemaSqlTest fails when this file and the generator differ.\n");
    if (normalizeIds)
      out.append("-- The record ids are renumbered #<table number>:<row number>. The real ones come from SmallSchemaSql.generate(db, false).\n");
    out.append('\n');
    for (final Table table : tables) {
      createTable(out, table);
      for (final Document row : rows.get(table.name()))
        insert(out, table, row, id);
      out.append('\n');
    }
    return out.toString();
  }

  private static void createTable(final StringBuilder out, final Table table) {
    final List<String> lines = new ArrayList<>();
    for (final Column column : table.columns())
      lines.add("  " + name(column.name()) + " " + column.type().typeName() + (column.nullable() ? "" : " NOT NULL"));
    if (table.primaryKey() != null)
      lines.add("  CONSTRAINT " + name(table.primaryKey().name()) + " PRIMARY KEY (" + names(table.primaryKey().columns()) + ")");
    for (final Table.Key key : table.uniqueKeys())
      lines.add("  CONSTRAINT " + name(key.name()) + " UNIQUE (" + names(key.columns()) + ")");
    for (final Table.ForeignKey foreignKey : table.foreignKeys())
      lines.add("  CONSTRAINT " + name(foreignKey.name()) + " FOREIGN KEY (" + names(foreignKey.columns()) + ") REFERENCES "
          + name(foreignKey.referencedTable()) + " (" + names(foreignKey.referencedColumns()) + ")");
    out.append("CREATE TABLE ").append(name(table.name())).append(" (\n").append(String.join(",\n", lines)).append("\n);\n");
  }

  private static void insert(final StringBuilder out, final Table table, final Document row, final UnaryOperator<String> id) {
    final List<String> columns = new ArrayList<>();
    final List<String> values = new ArrayList<>();
    for (final Column column : table.columns()) {
      columns.add(name(column.name()));
      values.add(literal(column, row, id));
    }
    out.append("INSERT INTO ").append(name(table.name())).append(" (").append(String.join(", ", columns)).append(") VALUES (")
        .append(String.join(", ", values)).append(");\n");
  }

  private static List<Document> scan(final Database database, final Table table) {
    if (table.edgeStorage() != null && table.edgeStorage() != Table.EdgeStorage.REGULAR)
      throw new UnsupportedOperationException("the table " + table.name() + " holds edges without a record");
    final List<Document> rows = new ArrayList<>();
    final Iterator<Document> iterator = database.iterateType(table.name(), false);
    while (iterator.hasNext())
      rows.add(iterator.next());
    rows.sort(Comparator.comparingInt((Document row) -> row.getIdentity().getBucketId())
        .thenComparingLong(row -> row.getIdentity().getPosition()));
    return rows;
  }

  private static List<Table> inDependencyOrder(final List<Table> tables) {
    final Map<String, Table> remaining = new TreeMap<>();
    for (final Table table : tables)
      remaining.put(table.name(), table);
    final List<Table> ordered = new ArrayList<>();
    final List<String> done = new ArrayList<>();
    while (!remaining.isEmpty()) {
      Table next = null;
      for (final Table candidate : remaining.values()) {
        boolean ready = true;
        for (final Table.ForeignKey foreignKey : candidate.foreignKeys())
          ready &= foreignKey.referencedTable().equals(candidate.name()) || done.contains(foreignKey.referencedTable());
        if (ready) {
          next = candidate;
          break;
        }
      }
      if (next == null)
        throw new UnsupportedOperationException("a cycle of foreign keys: " + remaining.keySet());
      remaining.remove(next.name());
      ordered.add(next);
      done.add(next.name());
    }
    return ordered;
  }

  private static String literal(final Column column, final Document row, final UnaryOperator<String> id) {
    if (column.isSynthetic() || column.isLink()) {
      final Object rid = column.read(row);
      return rid == null ? "NULL" : text(id.apply((String) rid));
    }

    // the accessors that convert, because the way the engine holds a date or a decimal depends on its settings
    final String property = column.property();
    final Object value = switch (column.type()) {
      case DATE -> row.getLocalDate(property);
      case TIMESTAMP -> row.getLocalDateTime(property);
      case NUMERIC -> row.getDecimal(property);
      default -> column.read(row);
    };
    if (value == null)
      return "NULL";

    return switch (column.type()) {
      case TEXT, VARCHAR -> text(value.toString());
      case BOOL, INT2, INT4, INT8, FLOAT4, FLOAT8 -> value.toString();
      case NUMERIC -> ((BigDecimal) value).toPlainString();
      case DATE -> text(((LocalDate) value).toString());
      case TIMESTAMP -> text(((LocalDateTime) value).toString().replace('T', ' '));
      case JSONB -> text(json(value)) + "::jsonb";
      case BOOL_ARRAY, INT4_ARRAY, INT8_ARRAY, FLOAT4_ARRAY, FLOAT8_ARRAY, NUMERIC_ARRAY, TEXT_ARRAY -> array(column, value);
      default -> throw new UnsupportedOperationException("the type " + column.type() + " of column " + column.name());
    };
  }

  private static String array(final Column column, final Object value) {
    final List<String> elements = new ArrayList<>();
    for (final Object element : (Collection<?>) value)
      elements.add(column.type() == ColumnType.TEXT_ARRAY ? text(element.toString()) : element.toString());
    return "ARRAY[" + String.join(", ", elements) + "]::" + column.type().typeName();
  }

  /** JSON with the keys of every object in order, so the text does not depend on the order a map was filled in. */
  private static String json(final Object value) {
    if (value == null)
      return "null";
    if (value instanceof Document document)
      return json(document.toMap(false));
    if (value instanceof Map<?, ?> map) {
      final Map<String, Object> sorted = new TreeMap<>();
      for (final Map.Entry<?, ?> entry : map.entrySet())
        sorted.put(String.valueOf(entry.getKey()), entry.getValue());
      final List<String> members = new ArrayList<>();
      for (final Map.Entry<String, Object> entry : sorted.entrySet())
        members.add(json(entry.getKey()) + ":" + json(entry.getValue()));
      return "{" + String.join(",", members) + "}";
    }
    if (value instanceof Collection<?> collection) {
      final List<String> items = new ArrayList<>();
      for (final Object item : collection)
        items.add(json(item));
      return "[" + String.join(",", items) + "]";
    }
    if (value instanceof Number || value instanceof Boolean)
      return value.toString();
    return '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }

  private static String text(final String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String name(final String identifier) {
    return identifier.matches("[a-z_][a-z0-9_]*") ? identifier : '"' + identifier.replace("\"", "\"\"") + '"';
  }

  private static String names(final List<String> identifiers) {
    final List<String> written = new ArrayList<>();
    for (final String identifier : identifiers)
      written.add(name(identifier));
    return String.join(", ", written);
  }
}
