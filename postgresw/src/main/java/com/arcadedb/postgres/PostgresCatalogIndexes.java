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
package com.arcadedb.postgres;

import com.arcadedb.index.TypeIndex;
import com.arcadedb.log.LogManager;
import com.arcadedb.query.postgresql.plan.CheckClause;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.logging.Level;

/**
 * The indexes of the database as PostgreSQL's catalog describes them (issue #10473): one {@code pg_index} row, one
 * {@code pg_class} row of kind {@code i} and the {@code pg_attribute} rows of its key columns per index of a type, and one
 * {@code pg_constraint} row of kind {@code u} per unique index.
 * <p>
 * <b>What an ArcadeDB index is.</b> A type has one index per declared key (a {@link TypeIndex}), spread over the buckets of the
 * type as one sub-index each. The sub-indexes are the storage layout, not something a client can address, so the catalog lists the
 * {@link TypeIndex} once, under the name ArcadeDB gives it ({@code Person[name]}), on the type that declares it. An index that
 * covers sub-types is not repeated on them, as an index of a parent table is not on its children in PostgreSQL.
 * <p>
 * <b>Unique indexes are unique constraints.</b> PostgreSQL tells a {@code CREATE UNIQUE INDEX} from the {@code UNIQUE} constraint
 * that owns one; ArcadeDB has a single way to declare uniqueness, the unique index, and nothing else that could be the
 * constraint. So each unique index is also a constraint of the same name, which is what makes {@code information_schema}'s
 * constraint views and psql's {@code UNIQUE CONSTRAINT} listing say what the schema enforces. There is no primary key, and no
 * foreign key or check constraint, in ArcadeDB, so no row says there is.
 * <p>
 * <b>A key that is no column is an expression.</b> A key over a property the table does not list as a column - one that is
 * {@code HIDDEN} - is what PostgreSQL calls an expression column of the index (issue #10829): numbered 0 in {@code indkey}, written
 * in {@code indexprs}, and still an attribute of the index, named {@code expr} as PostgreSQL names one, so that {@code relnatts}
 * counts the {@code pg_attribute} rows of an index as it does those of a table. A unique index with such a key is no constraint,
 * as a PostgreSQL unique index over an expression is not: a constraint is over columns.
 * <p>
 * <b>Access methods.</b> The LSM tree is a {@code btree}, the hash index a {@code hash}, the full-text index a {@code gin}, the
 * geospatial one a {@code gist} and the vector indexes an {@code hnsw}: the PostgreSQL index families a client knows by those
 * names, with the OIDs PostgreSQL gives the first four.
 *
 * @author Luca Garulli (l.garulli@arcadedata.com)
 */
final class PostgresCatalogIndexes {
  /**
   * The words PostgreSQL's {@code quote_ident} double-quotes: every keyword that is not "unreserved" in its keyword list
   * ({@code src/include/parser/kwlist.h} of PostgreSQL 17, the reserved ones, the ones that cannot name a column and the ones that
   * cannot name a type or a function), in lower case. The list grows with the releases of PostgreSQL; a word it lacks is printed
   * bare, which every release up to 17 reads the same.
   */
  private static final Set<String> QUOTED_KEYWORDS = Set.of(
      // reserved
      "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "both", "case", "cast", "check", "collate",
      "column", "constraint", "create", "current_catalog", "current_date", "current_role", "current_time", "current_timestamp",
      "current_user", "default", "deferrable", "desc", "distinct", "do", "else", "end", "except", "false", "fetch", "for",
      "foreign", "from", "grant", "group", "having", "in", "initially", "intersect", "into", "lateral", "leading", "limit",
      "localtime", "localtimestamp", "not", "null", "offset", "on", "only", "or", "order", "placing", "primary", "references",
      "returning", "select", "session_user", "some", "symmetric", "system_user", "table", "then", "to", "trailing", "true",
      "union", "unique", "user", "using", "variadic", "when", "where", "window", "with",
      // as a column name only
      "between", "bigint", "bit", "boolean", "char", "character", "coalesce", "dec", "decimal", "exists", "extract", "float",
      "greatest", "grouping", "inout", "int", "integer", "interval", "json", "json_array", "json_arrayagg", "json_exists",
      "json_object", "json_objectagg", "json_query", "json_scalar", "json_serialize", "json_table", "json_value", "least",
      "merge_action", "national", "nchar", "none", "normalize", "nullif", "numeric", "out", "overlay", "position", "precision",
      "real", "row", "setof", "smallint", "substring", "time", "timestamp", "treat", "trim", "values", "varchar", "xmlattributes",
      "xmlconcat", "xmlelement", "xmlexists", "xmlforest", "xmlnamespaces", "xmlparse", "xmlpi", "xmlroot", "xmlserialize",
      "xmltable",
      // as a type or function name
      "authorization", "binary", "collation", "concurrently", "cross", "current_schema", "freeze", "full", "ilike", "inner", "is",
      "isnull", "join", "left", "like", "natural", "notnull", "outer", "overlaps", "right", "similar", "tablesample", "verbose");

  /** An index access method: a {@code pg_am} row. */
  record AccessMethod(int oid, String name, String handler) {
  }

  static final AccessMethod BTREE = new AccessMethod(403, "btree", "bthandler");
  static final AccessMethod HASH  = new AccessMethod(405, "hash", "hashhandler");
  static final AccessMethod GIST  = new AccessMethod(783, "gist", "gisthandler");
  static final AccessMethod GIN   = new AccessMethod(2742, "gin", "ginhandler");
  /** A COLUMNAR index summarizes a column for facets and answers no lookup, the closest of the built-in methods. */
  static final AccessMethod BRIN  = new AccessMethod(3580, "brin", "brinhandler");
  /** PostgreSQL has no built-in vector index: the extensions that add one give it an OID of their own, so this one is ours. */
  static final AccessMethod HNSW  = new AccessMethod(9001, "hnsw", "hnswhandler");

  /** The index access methods this catalog uses, in the order of their OIDs. */
  static final List<AccessMethod> ACCESS_METHODS = List.of(BTREE, HASH, GIST, GIN, BRIN, HNSW);

  /**
   * One index of a type, with the OIDs the catalog gives it.
   *
   * @param constraintOid  the OID of the unique constraint of the same name, 0 for an index that is not unique or has an expression
   * @param attnums        for each key, the number of its column in the table ({@code pg_attribute.attnum}), 0 for a key that is
   *                       not a column of the type, which PostgreSQL calls an expression
   * @param attributeNames for each key, the name of the index's own attribute: the column's, or {@code expr} for an expression
   * @param primary        the index of the primary key of a table, which a statement of the {@code postgresql} language sees
   *                       (fork issue #12): {@code indisprimary}, and a {@code p} constraint instead of a {@code u} one
   * @param virtual        no ArcadeDB index is behind it: the key is the record id, which is unique by construction (decision of
   *                       fork issue #10, comment of 2026-11-24)
   */
  record Info(String name, DocumentType type, int tableOid, int oid, int constraintOid, boolean unique, boolean ready,
              AccessMethod method, String[] columns, int[] attnums, String[] attributeNames, boolean primary, boolean virtual) {
    boolean hasConstraint() {
      return constraintOid != 0;
    }

    boolean hasExpressions() {
      for (final int attnum : attnums)
        if (attnum == 0)
          return true;
      return false;
    }
  }

  /**
   * An {@code int2vector} or {@code oidvector}: the fixed-size array PostgreSQL stores an index's key columns, collations, operator
   * classes and options in. It is subscripted from 0, unlike every other array, and a client reads it as text, the values
   * separated by a space.
   */
  static final class Vector extends AbstractList<Integer> {
    private final int[] values;

    Vector(final int... values) {
      this.values = values;
    }

    @Override
    public Integer get(final int index) {
      return values[index];
    }

    @Override
    public int size() {
      return values.length;
    }

    @Override
    public String toString() {
      final StringBuilder text = new StringBuilder();
      for (int i = 0; i < values.length; i++) {
        if (i > 0)
          text.append(' ');
        text.append(values[i]);
      }
      return text.toString();
    }
  }

  private PostgresCatalogIndexes() {
  }

  /**
   * The indexes of {@code types} (already in name order), each type's own in name order, with their OIDs.
   * <p>
   * An OID is derived from the name, as a type's is, so that an index keeps its OID from one statement to the next while the
   * schema does not change; one that lands on an OID already given - to a type, an index or a constraint - moves to the next free
   * one, in this order, so that two relations are never told apart by nothing. Creating or dropping a type whose name collides
   * with an index's OID can therefore move that index to another: an OID is only as stable as the schema around it, which is as
   * far as a client holding one across statements can rely on it.
   *
   * @param typeOids the OIDs the types already have, which an index must not take
   */
  static List<Info> of(final List<DocumentType> types, final ToIntFunction<DocumentType> tableOid,
      final Function<DocumentType, List<String>> columnNames, final Set<Integer> typeOids, final int firstOid, final int oidSpace) {
    final Set<Integer> used = new HashSet<>(typeOids);
    final List<Info> infos = new ArrayList<>();

    for (final DocumentType type : types) {
      final List<TypeIndex> indexes = new ArrayList<>(type.getAllIndexes(false));
      indexes.sort(Comparator.comparing(TypeIndex::getName));
      if (indexes.isEmpty())
        continue;

      final Map<String, Integer> numbers = new HashMap<>();
      int ordinal = 0;
      for (final String column : columnNames.apply(type))
        numbers.put(column, ++ordinal);

      for (final TypeIndex index : indexes) {
        final Info info = describe(index, type, tableOid.applyAsInt(type), numbers, used, firstOid, oidSpace);
        if (info != null)
          infos.add(info);
      }
    }
    return infos;
  }

  private static Info describe(final TypeIndex index, final DocumentType type, final int tableOid, final Map<String, Integer> numbers,
      final Set<Integer> used, final int firstOid, final int oidSpace) {
    final String name;
    final boolean unique;
    final boolean ready;
    final AccessMethod method;
    final List<String> properties;
    try {
      // an index being dropped or built by a concurrent statement answers none of these
      name = index.getName();
      unique = index.isUniqueIfPresent();
      ready = index.isOnline();
      method = accessMethodOf(index.getType());
      properties = index.getPropertyNames();
    } catch (final RuntimeException e) {
      LogManager.instance().log(PostgresCatalogIndexes.class, Level.FINE, "Index of type '%s' left out of the catalog: %s", null,
          type.getName(), e.getMessage());
      return null;
    }

    final String[] columns = properties.toArray(new String[0]);
    final int[] attnums = new int[columns.length];
    for (int k = 0; k < columns.length; k++)
      attnums[k] = numbers.getOrDefault(columns[k], 0);

    boolean expression = false;
    for (final int attnum : attnums)
      expression |= attnum == 0;

    final int oid = freeOid("index:" + name, used, firstOid, oidSpace);
    // a unique index over an expression enforces uniqueness without being a constraint, which PostgreSQL defines over columns
    final int constraintOid = unique && !expression ? freeOid("constraint:" + name, used, firstOid, oidSpace) : 0;
    return new Info(name, type, tableOid, oid, constraintOid, unique, ready, method, columns, attnums,
        attributeNames(columns, attnums), false, false);
  }

  /**
   * A foreign key, as a {@code pg_constraint} row of kind {@code f}: it has no index of its own, the index it needs is the one of
   * the key it refers to.
   *
   * @param oid             the OID of the constraint
   * @param attnums         the numbers of the referencing columns in the table
   * @param referencedIndex the OID of the index of the key it refers to, {@code pg_constraint.conindid}
   * @param deleteRule      what deleting the referenced row does, as {@code information_schema} names it
   * @param updateRule      what a change of the referenced key does, as {@code information_schema} names it
   * @param enforced        whether the engine holds the key: not for a {@code LINK} property
   */
  record Reference(String name, DocumentType type, int tableOid, int oid, String[] columns, int[] attnums,
                   DocumentType referencedType, int referencedTableOid, String[] referencedColumns, int[] referencedAttnums,
                   int referencedIndex, String deleteRule, String updateRule, boolean enforced) {
  }

  /** The letter of {@code pg_constraint.confdeltype} and {@code confupdtype} for a rule. */
  private static String actionCode(final String rule) {
    return switch (rule) {
      case "RESTRICT" -> "r";
      case "CASCADE" -> "c";
      case "SET NULL" -> "n";
      default -> "a";
    };
  }

  /**
   * A check constraint of the relational view, as a {@code pg_constraint} row of kind {@code c} (fork issue #297).
   *
   * @param attnums    the numbers of the columns it reads, in the order of their first use
   * @param type       the table that has it, which declares it or inherits it
   * @param constraint the constraint as it is declared
   */
  record Check(int tableOid, int oid, int[] attnums, DocumentType type, CheckClause.Declared constraint) {
    /** The expression as PostgreSQL writes it, or as it was declared when {@link CheckClause} does not write its shape. */
    String written(final boolean pretty) {
      return CheckClause.of(constraint, type, pretty);
    }
  }

  /** {@code pg_constraint.conbin} of a check: the expression that {@code pg_get_expr()} prints, written when it is asked for. */
  record CheckBin(Check check) {
    @Override
    public String toString() {
      return check.written(false);
    }
  }

  /** The {@code pg_constraint} columns of a check constraint, name and value in turn. */
  static Object[] checkValues(final Check check, final int schemaOid) {
    final boolean local = check.constraint().declaring().getName().equals(check.type().getName());
    return new Object[] { "oid", check.oid(), "conname", check.constraint().name(), "connamespace", schemaOid, "contype", "c",
        "condeferrable", Boolean.FALSE, "condeferred", Boolean.FALSE, "conenforced", Boolean.TRUE, "convalidated", Boolean.TRUE,
        "conrelid", check.tableOid(), "contypid", 0, "conindid", 0, "conparentid", 0, "confrelid", 0, "confupdtype", " ",
        "confdeltype", " ", "confmatchtype", " ", "conislocal", local, "coninhcount", local ? 0 : 1, "connoinherit", Boolean.FALSE,
        "conkey", check.attnums().length == 0 ? null : numbers(check.attnums()), "conbin", new CheckBin(check) };
  }

  /**
   * The indexes, the foreign keys and the check constraints of a database as a statement of the {@code postgresql} language sees
   * them.
   */
  record Keys(List<Info> indexes, List<Reference> references, List<Check> checks) {
  }

  /**
   * The keys of the relational view (fork issue #10, decision of 2026-11-24): the primary key, the unique keys and the foreign
   * keys of each table, under the names the language gives them ({@code person_pkey}, {@code person_pid_key},
   * {@code works_at_source_id_fkey}), numbered by the position of the column in the table of the view. A key that a unique ArcadeDB
   * index backs is that index, so its method and readiness are the index's. The key of the record id has no index: the primary key
   * gets a virtual index of its name, which is what {@code psql \d} and JDBC {@code getPrimaryKeys} read, and a unique key over the
   * record id gets one too, because PostgreSQL has no foreign key without an index under the key it refers to. No other index is
   * made up. An index that is not a key of the view (not unique, full text, vector, a unique one over a column the view does not
   * list) is listed as it is, under its ArcadeDB name, as {@link #of} lists it.
   *
   * @param types    the types of the database in name order, which have a table
   * @param tables   the table of each type, by the name of the type
   * @param typeOids the OIDs the types already have, which an index must not take
   */
  static Keys ofRelational(final List<DocumentType> types, final Map<String, com.arcadedb.query.postgresql.catalog.Table> tables,
      final ToIntFunction<DocumentType> tableOid, final Set<Integer> typeOids, final int firstOid, final int oidSpace) {
    final Set<Integer> used = new HashSet<>(typeOids);
    final List<Info> infos = new ArrayList<>();
    final Map<String, Map<List<String>, Info>> keyIndexes = new HashMap<>();

    for (final DocumentType type : types) {
      final com.arcadedb.query.postgresql.catalog.Table table = tables.get(type.getName());
      if (table == null)
        continue;

      final Map<String, Integer> numbers = new HashMap<>();
      final Map<String, String> shownByProperty = new HashMap<>();
      int ordinal = 0;
      for (final com.arcadedb.query.postgresql.catalog.Column column : table.listedColumns()) {
        numbers.put(column.name(), ++ordinal);
        if (column.property() != null)
          shownByProperty.put(column.property(), column.name());
      }
      // the endpoints of a UNIQUE edge type are backed by an index over the two properties the view does not list as columns
      shownByProperty.put("@out", com.arcadedb.query.postgresql.catalog.Column.SOURCE_ID);
      shownByProperty.put("@in", com.arcadedb.query.postgresql.catalog.Column.TARGET_ID);

      final List<TypeIndex> indexes = new ArrayList<>(type.getAllIndexes(false));
      indexes.sort(Comparator.comparing(TypeIndex::getName));

      final List<com.arcadedb.query.postgresql.catalog.Table.Key> keys = new ArrayList<>();
      if (table.primaryKey() != null)
        keys.add(table.primaryKey());
      keys.addAll(table.uniqueKeys());

      // the unique index behind each key, by the columns it covers
      final Map<List<String>, TypeIndex> backing = new HashMap<>();
      final Set<TypeIndex> backs = new HashSet<>();
      for (final TypeIndex index : indexes)
        try {
          if (!index.isUniqueIfPresent()
              || index.getType() != Schema.INDEX_TYPE.LSM_TREE && index.getType() != Schema.INDEX_TYPE.HASH)
            continue;
          final List<String> shown = new ArrayList<>();
          for (final String property : index.getPropertyNames()) {
            final String column = shownByProperty.get(property);
            if (column == null) {
              shown.clear();
              break;
            }
            shown.add(column);
          }
          if (!shown.isEmpty() && keys.stream().anyMatch(key -> key.columns().equals(shown)) && backing.putIfAbsent(shown, index) == null)
            backs.add(index);
        } catch (final RuntimeException e) {
          // an index that a concurrent statement is creating or dropping is not a key of this snapshot
        }

      final Map<List<String>, Info> byColumns = new HashMap<>();
      keyIndexes.put(type.getName(), byColumns);
      for (final com.arcadedb.query.postgresql.catalog.Table.Key key : keys) {
        final TypeIndex index = backing.get(key.columns());
        final String[] columns = key.columns().toArray(new String[0]);
        final int[] attnums = new int[columns.length];
        for (int k = 0; k < columns.length; k++)
          attnums[k] = numbers.getOrDefault(columns[k], 0);
        boolean ready = true;
        AccessMethod method = BTREE;
        if (index != null)
          try {
            ready = index.isOnline();
            method = accessMethodOf(index.getType());
          } catch (final RuntimeException e) {
            // being dropped: the key stays, as a plain one
          }
        final boolean primary = key == table.primaryKey();
        final int oid = freeOid("index:" + key.name(), used, firstOid, oidSpace);
        final int constraintOid = freeOid("constraint:" + key.name(), used, firstOid, oidSpace);
        final Info info = new Info(key.name(), type, tableOid.applyAsInt(type), oid, constraintOid, true, ready, method, columns,
            attnums, attributeNames(columns, attnums), primary, index == null);
        infos.add(info);
        byColumns.put(key.columns(), info);
      }

      for (final TypeIndex index : indexes) {
        if (backs.contains(index))
          continue;
        final Info info = describeRelational(index, type, tableOid.applyAsInt(type), numbers, shownByProperty, used, firstOid,
            oidSpace);
        if (info != null)
          infos.add(info);
      }
    }

    final List<Reference> references = new ArrayList<>();
    for (final DocumentType type : types) {
      final com.arcadedb.query.postgresql.catalog.Table table = tables.get(type.getName());
      if (table == null)
        continue;
      for (final com.arcadedb.query.postgresql.catalog.Table.ForeignKey key : table.foreignKeys()) {
        final com.arcadedb.query.postgresql.catalog.Table referenced = tables.get(key.referencedTable());
        if (referenced == null)
          continue;
        final String[] columns = key.columns().toArray(new String[0]);
        final String[] referencedColumns = key.referencedColumns().toArray(new String[0]);
        final int[] attnums = numbersOf(table, columns);
        final int[] referencedAttnums = numbersOf(referenced, referencedColumns);
        final Info target = keyIndexes.getOrDefault(referenced.name(), Map.of()).get(key.referencedColumns());
        references.add(new Reference(key.name(), type, tableOid.applyAsInt(type), freeOid("constraint:" + key.name(), used, firstOid,
            oidSpace), columns, attnums, referenced.documentType(), tableOid.applyAsInt(referenced.documentType()),
            referencedColumns, referencedAttnums, target == null ? 0 : target.oid(), key.deleteRule(), key.updateRule(),
            key.enforced()));
      }
    }

    final List<Check> checks = new ArrayList<>();
    for (final DocumentType type : types) {
      final com.arcadedb.query.postgresql.catalog.Table table = tables.get(type.getName());
      if (table == null)
        continue;
      for (final CheckClause.Declared check : CheckClause.declaredOn(type))
        checks.add(new Check(tableOid.applyAsInt(type),
            freeOid("check:" + type.getName() + ":" + check.name(), used, firstOid, oidSpace),
            numbersOf(table, check.columns().toArray(new String[0])), type, check));
    }
    return new Keys(infos, references, checks);
  }

  private static int[] numbersOf(final com.arcadedb.query.postgresql.catalog.Table table, final String[] names) {
    final int[] numbers = new int[names.length];
    for (int k = 0; k < names.length; k++) {
      int ordinal = 0;
      for (final com.arcadedb.query.postgresql.catalog.Column column : table.listedColumns()) {
        ++ordinal;
        if (column.name().equals(names[k]))
          numbers[k] = ordinal;
      }
    }
    return numbers;
  }

  /** {@link #describe} for an index that is no key of the view: its keys are the columns of the view, named as it names them. */
  private static Info describeRelational(final TypeIndex index, final DocumentType type, final int tableOid,
      final Map<String, Integer> numbers, final Map<String, String> shownByProperty, final Set<Integer> used, final int firstOid,
      final int oidSpace) {
    final String name;
    final boolean unique;
    final boolean ready;
    final AccessMethod method;
    final List<String> properties;
    try {
      name = index.getName();
      unique = index.isUniqueIfPresent();
      ready = index.isOnline();
      method = accessMethodOf(index.getType());
      properties = index.getPropertyNames();
    } catch (final RuntimeException e) {
      LogManager.instance().log(PostgresCatalogIndexes.class, Level.FINE, "Index of type '%s' left out of the catalog: %s", null,
          type.getName(), e.getMessage());
      return null;
    }

    final String[] columns = new String[properties.size()];
    final int[] attnums = new int[columns.length];
    boolean expression = false;
    for (int k = 0; k < columns.length; k++) {
      final String shown = shownByProperty.get(properties.get(k));
      // a property the view does not list is an expression, as in the other view of the catalog
      columns[k] = shown != null ? shown : properties.get(k);
      attnums[k] = shown != null ? numbers.getOrDefault(shown, 0) : 0;
      expression |= attnums[k] == 0;
    }

    final int oid = freeOid("index:" + name, used, firstOid, oidSpace);
    final int constraintOid = unique && !expression ? freeOid("constraint:" + name, used, firstOid, oidSpace) : 0;
    return new Info(name, type, tableOid, oid, constraintOid, unique, ready, method, columns, attnums,
        attributeNames(columns, attnums), false, false);
  }

  /**
   * The names of the attributes of an index, as PostgreSQL's {@code ChooseIndexColumnNames} chooses them: a key over a column takes
   * the column's name, an expression {@code expr}, and a name already taken in the index gets the first free number appended -
   * {@code expr}, {@code expr1}, {@code expr2}. An expression's attribute does not carry the name of the property it reads, which
   * the table does not list as a column.
   */
  private static String[] attributeNames(final String[] columns, final int[] attnums) {
    final String[] names = new String[columns.length];
    final Set<String> taken = new HashSet<>();
    for (int k = 0; k < columns.length; k++) {
      final String base = attnums[k] == 0 ? "expr" : columns[k];
      String name = base;
      for (int suffix = 1; !taken.add(name); suffix++)
        name = base + suffix;
      names[k] = name;
    }
    return names;
  }

  /**
   * {@code pg_index.indexprs}: the expressions among the keys of an index, in order, as {@code pg_get_expr} prints a list of them -
   * separated by a comma - or null for an index of columns only, which is what a client tests to tell the two apart.
   */
  static String expressions(final Info index) {
    if (!index.hasExpressions())
      return null;
    final StringBuilder expressions = new StringBuilder();
    for (int k = 0; k < index.columns().length; k++)
      if (index.attnums()[k] == 0) {
        if (!expressions.isEmpty())
          expressions.append(", ");
        expressions.append(quoteIdentifier(index.columns()[k]));
      }
    return expressions.toString();
  }

  private static int freeOid(final String key, final Set<Integer> used, final int firstOid, final int oidSpace) {
    int oid = firstOid + Math.floorMod(key.hashCode(), oidSpace);
    while (!used.add(oid))
      oid = firstOid + (oid - firstOid + 1) % oidSpace;
    return oid;
  }

  static AccessMethod accessMethodOf(final Schema.INDEX_TYPE type) {
    return switch (type) {
      case LSM_TREE -> BTREE;
      case HASH -> HASH;
      case FULL_TEXT -> GIN;
      case GEOSPATIAL -> GIST;
      case LSM_VECTOR, LSM_SPARSE_VECTOR -> HNSW;
      case COLUMNAR -> BRIN;
    };
  }

  // ---------------------------------------------------------------- definitions

  /**
   * {@code pg_get_indexdef(index, column, pretty)}: the statement that creates the index when {@code column} is 0, the name of the
   * {@code column}th key otherwise, as PostgreSQL prints them. A pretty statement names the table without its schema, which is
   * how PostgreSQL prints a table the search path reaches; here the one schema always is.
   *
   * @return null for a column past the keys of the index
   */
  static String indexDefinition(final Info index, final String schema, final int column, final boolean pretty) {
    if (column < 0 || column > index.columns().length)
      return null;
    if (column > 0)
      return quoteIdentifier(index.columns()[column - 1]);

    final StringBuilder definition = new StringBuilder("CREATE ");
    if (index.unique())
      definition.append("UNIQUE ");
    definition.append("INDEX ").append(quoteIdentifier(index.name())).append(" ON ");
    if (!pretty)
      definition.append(quoteIdentifier(schema)).append('.');
    definition.append(quoteIdentifier(index.type().getName())).append(" USING ").append(index.method().name()).append(" (");
    for (int k = 0; k < index.columns().length; k++) {
      if (k > 0)
        definition.append(", ");
      definition.append(quoteIdentifier(index.columns()[k]));
    }
    return definition.append(')').toString();
  }

  /** {@code pg_get_constraintdef} of a unique or primary key constraint: {@code UNIQUE (a, b)} or {@code PRIMARY KEY (a)}. */
  static String constraintDefinition(final Info index) {
    final StringBuilder definition = new StringBuilder(index.primary() ? "PRIMARY KEY (" : "UNIQUE (");
    for (int k = 0; k < index.columns().length; k++) {
      if (k > 0)
        definition.append(", ");
      definition.append(quoteIdentifier(index.columns()[k]));
    }
    return definition.append(')').toString();
  }

  /**
   * An identifier as PostgreSQL's {@code quote_ident} prints it: bare when it is made of lower-case letters, digits and
   * underscores, does not start with a digit and is not a keyword that could be read as something else; double-quoted, with
   * its quotes doubled, otherwise.
   */
  static String quoteIdentifier(final String identifier) {
    boolean safe = !identifier.isEmpty() && (Character.isLowerCase(identifier.charAt(0)) && identifier.charAt(0) < 128
        || identifier.charAt(0) == '_');
    for (int i = 0; safe && i < identifier.length(); i++) {
      final char c = identifier.charAt(i);
      safe = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '$' && i > 0;
    }
    if (safe && !QUOTED_KEYWORDS.contains(identifier.toLowerCase(Locale.ENGLISH)))
      return identifier;
    return '"' + identifier.replace("\"", "\"\"") + '"';
  }

  // ---------------------------------------------------------------- rows

  /**
   * The {@code pg_index} columns of an index, name and value in turn.
   *
   * @param collations the collation of each key column, its {@code indcollation}
   */
  static Object[] indexValues(final Info index, final int[] collations) {
    final int[] zeros = new int[index.columns().length];
    return new Object[] { "indexrelid", index.oid(), "indrelid", index.tableOid(), "indnatts", index.columns().length,
        "indnkeyatts", index.columns().length, "indisunique", index.unique(), "indnullsnotdistinct", Boolean.FALSE,
        // only the index of the primary key of the relational view is one (see ofRelational): a unique index is not
        "indisprimary", index.primary(), "indisexclusion", Boolean.FALSE, "indimmediate", Boolean.TRUE, "indisclustered",
        Boolean.FALSE, "indisvalid", index.ready(), "indcheckxmin", Boolean.FALSE, "indisready", index.ready(), "indislive",
        Boolean.TRUE, "indisreplident", Boolean.FALSE, "indkey", new Vector(index.attnums()),
        // the operator classes PostgreSQL numbers have no equivalent here
        "indcollation", new Vector(collations), "indclass", new Vector(zeros), "indoption", new Vector(zeros), "indexprs", expressions(index),
        "indpred", null };
  }

  /** The {@code pg_class} columns of the relation an index is, name and value in turn. */
  static Object[] classValues(final Info index, final int schemaOid, final int ownerOid) {
    return new Object[] { "oid", index.oid(), "relname", index.name(), "relnamespace", schemaOid, "relowner", ownerOid, "relkind", "i",
        "relpersistence", "p", "relnatts", index.columns().length, "relhasindex", Boolean.FALSE, "relhasrules", Boolean.FALSE,
        "relhastriggers", Boolean.FALSE, "relhassubclass", Boolean.FALSE, "relispartition", Boolean.FALSE, "relisshared",
        Boolean.FALSE, "relispopulated", Boolean.TRUE, "relrowsecurity", Boolean.FALSE, "relforcerowsecurity", Boolean.FALSE,
        "relchecks", 0, "reltuples", -1, "relpages", 0, "relam", index.method().oid(), "reltype", 0, "relfilenode", index.oid(),
        "reltablespace", 0, "reloftype", 0, "reltoastrelid", 0, "relreplident", "n", "relrewrite", 0 };
  }

  /** The {@code pg_constraint} columns of the unique constraint of an index, name and value in turn. */
  static Object[] constraintValues(final Info index, final int schemaOid) {
    return new Object[] { "oid", index.constraintOid(), "conname", index.name(), "connamespace", schemaOid, "contype",
        index.primary() ? "p" : "u",
        "condeferrable", Boolean.FALSE, "condeferred", Boolean.FALSE, "conenforced", Boolean.TRUE, "convalidated", Boolean.TRUE,
        "conrelid", index.tableOid(),
        "contypid", 0, "conindid", index.oid(), "conparentid", 0, "confrelid", 0, "confupdtype", " ", "confdeltype", " ",
        "confmatchtype", " ", "conislocal", Boolean.TRUE, "coninhcount", 0, "connoinherit", Boolean.TRUE, "conkey",
        keyNumbers(index) };
  }

  /** The attribute numbers of the keys of an index, as the {@code int2[]} {@code conkey} is. */
  static List<Integer> keyNumbers(final Info index) {
    final List<Integer> numbers = new ArrayList<>(index.attnums().length);
    for (final int attnum : index.attnums())
      numbers.add(attnum);
    return numbers;
  }

  /** The {@code information_schema.table_constraints} columns of the unique constraint of an index. */
  static Object[] tableConstraintValues(final Info index, final String schema) {
    return new Object[] { "constraint_catalog", schema, "constraint_schema", schema, "constraint_name", index.name(),
        "table_catalog", schema, "table_schema", schema, "table_name", index.type().getName(), "constraint_type",
        index.primary() ? "PRIMARY KEY" : "UNIQUE", "is_deferrable", "NO", "initially_deferred", "NO", "enforced", "YES", "nulls_distinct", "YES" };
  }

  /** The {@code information_schema.key_column_usage} columns of the {@code position}th key (from 1) of a unique constraint. */
  static Object[] keyColumnValues(final Info index, final String schema, final int position) {
    return new Object[] { "constraint_catalog", schema, "constraint_schema", schema, "constraint_name", index.name(),
        "table_catalog", schema, "table_schema", schema, "table_name", index.type().getName(), "column_name",
        index.columns()[position - 1], "ordinal_position", position, "position_in_unique_constraint", null };
  }

  /**
   * The {@code pg_constraint} columns of a foreign key, name and value in turn. The match type is SIMPLE ({@code s}). The actions
   * and {@code conenforced} say what the engine and the language do, as the {@code referential_constraints} and
   * {@code table_constraints} views of the language do, and the key of a {@code LINK} property is not enforced, which PostgreSQL
   * also marks as not validated.
   */
  static Object[] referenceValues(final Reference key, final int schemaOid) {
    return new Object[] { "oid", key.oid(), "conname", key.name(), "connamespace", schemaOid, "contype", "f", "condeferrable",
        Boolean.FALSE, "condeferred", Boolean.FALSE, "conenforced", key.enforced(), "convalidated", key.enforced(), "conrelid",
        key.tableOid(), "contypid", 0, "conindid", key.referencedIndex(), "conparentid", 0, "confrelid", key.referencedTableOid(),
        "confupdtype", actionCode(key.updateRule()), "confdeltype", actionCode(key.deleteRule()), "confmatchtype", "s",
        "conislocal", Boolean.TRUE,
        "coninhcount", 0, "connoinherit", Boolean.TRUE, "conkey", numbers(key.attnums()), "confkey",
        numbers(key.referencedAttnums()) };
  }

  private static List<Integer> numbers(final int[] attnums) {
    final List<Integer> numbers = new ArrayList<>(attnums.length);
    for (final int attnum : attnums)
      numbers.add(attnum);
    return numbers;
  }

  /**
   * {@code pg_get_constraintdef} of a foreign key, as PostgreSQL prints it: {@code FOREIGN KEY (a) REFERENCES t(id)}, followed by
   * {@code ON UPDATE} and {@code ON DELETE} with their actions other than NO ACTION, and by {@code NOT ENFORCED} for a
   * {@code LINK} property.
   */
  static String referenceDefinition(final Reference key) {
    final StringBuilder definition = new StringBuilder("FOREIGN KEY (");
    for (int k = 0; k < key.columns().length; k++) {
      if (k > 0)
        definition.append(", ");
      definition.append(quoteIdentifier(key.columns()[k]));
    }
    definition.append(") REFERENCES ").append(quoteIdentifier(key.referencedType().getName())).append('(');
    for (int k = 0; k < key.referencedColumns().length; k++) {
      if (k > 0)
        definition.append(", ");
      definition.append(quoteIdentifier(key.referencedColumns()[k]));
    }
    definition.append(')');
    if (!"NO ACTION".equals(key.updateRule()))
      definition.append(" ON UPDATE ").append(key.updateRule());
    if (!"NO ACTION".equals(key.deleteRule()))
      definition.append(" ON DELETE ").append(key.deleteRule());
    if (!key.enforced())
      definition.append(" NOT ENFORCED");
    return definition.toString();
  }

  /** The {@code pg_am} columns of an access method. */
  static Object[] accessMethodValues(final AccessMethod method) {
    return new Object[] { "oid", method.oid(), "amname", method.name(), "amhandler", method.handler(), "amtype", "i" };
  }
}
