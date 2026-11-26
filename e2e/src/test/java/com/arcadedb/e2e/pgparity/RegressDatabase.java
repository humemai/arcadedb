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
package com.arcadedb.e2e.pgparity;

import com.arcadedb.query.postgresql.corpus.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One session on one database of a PostgreSQL server, for the regression harness (fork issue #119): it runs the statements of a
 * file, and it looks at the database without changing it, inside a transaction (or a savepoint of the file's own transaction)
 * that it rolls back: which tables there are, what they hold, which of them a statement changed, and which ones it reads.
 */
final class RegressDatabase implements AutoCloseable {
  static final String NAME = "regression";

  /** @param type the name of the type in {@code pg_type}, {@code formatted} the type as declared */
  record Column(String name, String type, String formatted, boolean notNull) {
  }

  /**
   * A table of the database.
   *
   * @param key         the name for a table of {@code public} and for a temporary table, {@code schema.name} for any other table
   *                    and for a table that a temporary table of the same name hides
   * @param uniqueKeys  the columns of each primary key and unique constraint
   * @param writeLimits what the table has that decides what a write does and that the engine API cannot give a copy
   * @param nested      part of inheritance or partitioning
   * @param signature   everything above as one text: it changes when the definition does
   * @param fingerprint the number of rows and a hash of them: it changes when the contents do
   */
  record Table(String key, String schema, String name, List<Column> columns, List<List<String>> uniqueKeys,
      List<String> writeLimits, boolean nested, long rowCount, String signature, String fingerprint) {
  }

  /** The tables a statement reads or writes, as the plan of the server names them. */
  record Relations(Set<String> all, Set<String> written) {
  }

  /** The answer of a statement of a file, with the text of its rows as the server wrote them. */
  record Executed(RegressSubject.Answer answer, List<String[]> text) {
  }

  private static final String NEUTRAL = "SET LOCAL SESSION AUTHORIZATION DEFAULT; SET LOCAL ROLE NONE; "
      + "SET LOCAL search_path = pg_catalog; SET LOCAL datestyle = 'ISO, YMD'; SET LOCAL extra_float_digits = 1; "
      + "SET LOCAL bytea_output = 'hex'; SET LOCAL statement_timeout = 0; SET LOCAL lock_timeout = '10s'";

  private static final String USER_TABLES = "c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') "
      + "AND n.nspname !~ '^pg_toast' AND (n.nspname !~ '^pg_temp' OR n.oid = pg_my_temp_schema())";

  private static final Set<String> PLANNED = Set.of("select", "insert", "update", "delete", "merge", "with", "values", "table");

  private final String               host;
  private final int                  port;
  private final Map<Integer, String> typeNames = new HashMap<>();
  private       RegressWire          wire;
  private       Map<String, Table>   tables    = new LinkedHashMap<>();
  private       String               marker;
  private       boolean              writing;

  RegressDatabase(final String host, final int port) {
    this.host = host;
    this.port = port;
    this.wire = open("postgres");
    refresh();
  }

  private RegressWire open(final String user) {
    try {
      return new RegressWire(host, port, user, NAME);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A new session of the same database, as {@code user} or as the super user. */
  void connect(final String user) {
    wire.close();
    wire = open(user == null ? "postgres" : user);
    marker = null;
  }

  char transactionState() {
    return wire.transactionState();
  }

  boolean standardStrings() {
    return !"off".equals(wire.parameter("standard_conforming_strings"));
  }

  /** The tables as the last {@link #changes} saw them. */
  Map<String, Table> tables() {
    return tables;
  }

  /** Runs one statement of a file, in the session as the file left it. */
  Executed execute(final String sql, final List<String> parameters, final Supplier<String> copyData) {
    final RegressWire.Reply reply = parameters == null ? send(sql, copyData) : sendWith(sql, parameters);
    if (reply.failed())
      return new Executed(RegressSubject.Answer.error(reply.sqlState, reply.message), null);
    final long affected = affected(reply.tag);
    if (reply.columns == null || reply.columns.isEmpty() && reply.rows.isEmpty())
      return new Executed(new RegressSubject.Answer(null, null, List.of(), affected, null, null, false), null);
    final String dateStyle = wire.parameter("DateStyle");
    final List<String> names = new ArrayList<>(reply.columns.size());
    final List<String> types = new ArrayList<>(reply.columns.size());
    for (final RegressWire.Column column : reply.columns) {
      names.add(column.name());
      types.add(typeName(column.type()));
    }
    return new Executed(new RegressSubject.Answer(names, types, normalize(reply.rows, types, dateStyle), affected, null, null,
        false), reply.rows);
  }

  private static List<List<Object>> normalize(final List<String[]> text, final List<String> types, final String dateStyle) {
    final List<List<Object>> rows = new ArrayList<>(text.size());
    for (final String[] line : text) {
      final List<Object> row = new ArrayList<>(line.length);
      for (int i = 0; i < line.length; i++)
        row.add(RegressValues.fromServer(types.get(i), line[i], dateStyle));
      rows.add(row);
    }
    return rows;
  }

  private static long affected(final String tag) {
    if (tag == null)
      return -1;
    final String[] words = tag.split(" ");
    return switch (words[0]) {
      case "INSERT", "UPDATE", "DELETE", "MERGE" -> Long.parseLong(words[words.length - 1]);
      default -> -1;
    };
  }

  private String typeName(final int type) {
    String name = typeNames.get(type);
    if (name == null && wire.transactionState() != 'E') {
      probe(true, () -> {
        for (final String[] row : select("SELECT oid::text, typname::text FROM pg_type"))
          typeNames.put((int) Long.parseLong(row[0]), row[1]);
        return null;
      });
      name = typeNames.get(type);
    }
    return name != null ? name : "oid " + type;
  }

  /**
   * The tables whose definition or contents changed since the last call. Nothing is read when no transaction wrote since then:
   * the server of the harness has no automatic vacuum, so only the session of the file takes transaction ids.
   */
  Set<String> changes() {
    if (wire.transactionState() == 'E')
      return Set.of();
    final boolean inBlock = wire.transactionState() == 'T';
    final Supplier<String[]> read = () -> select(
        "SELECT pg_catalog.pg_snapshot_xmax(pg_catalog.pg_current_snapshot())::pg_catalog.text, "
            + "pg_catalog.pg_current_xact_id_if_assigned()::pg_catalog.text").get(0);
    final String[] now = inBlock ? probe(false, read) : read.get();
    final boolean writingNow = inBlock && now[1] != null;
    final String mark = now[0] + "/" + now[1];
    if (mark.equals(marker) && !writing && !writingNow)
      return Set.of();
    marker = mark;
    writing = writingNow;
    return refresh();
  }

  private Set<String> refresh() {
    final Map<String, Table> now = probe(true, this::describe);
    final Set<String> changed = new LinkedHashSet<>();
    for (final Table table : now.values()) {
      final Table before = tables.get(table.key());
      if (before == null || !before.signature().equals(table.signature()) || !Objects.equals(before.fingerprint(),
          table.fingerprint()))
        changed.add(table.key());
    }
    for (final String key : tables.keySet())
      if (!now.containsKey(key))
        changed.add(key);
    tables = now;
    return changed;
  }

  private Map<String, Table> describe() {
    final Map<String, List<String[]>> columns = new LinkedHashMap<>();
    for (final String[] row : select("SELECT c.oid::text, n.nspname::text, c.relname::text, (c.relpersistence = 't')::text, "
        + "(c.relkind = 'p' OR c.relispartition OR EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid "
        + "OR i.inhparent = c.oid))::text, a.attname::text, t.typname::text, format_type(a.atttypid, a.atttypmod), "
        + "a.attnotnull::text, (a.atthasdef OR a.attidentity <> '' OR a.attgenerated <> '')::text FROM pg_class c "
        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
        + "LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped "
        + "LEFT JOIN pg_type t ON t.oid = a.atttypid WHERE " + USER_TABLES + " ORDER BY n.nspname, c.relname, a.attnum"))
      columns.computeIfAbsent(row[0], oid -> new ArrayList<>()).add(row);

    final Map<String, List<String[]>> rules = new HashMap<>();
    for (final String[] row : select("SELECT con.conrelid::text, con.contype::text, coalesce((SELECT string_agg(a.attname, "
        + "chr(9) ORDER BY k.ord) FROM unnest(con.conkey) WITH ORDINALITY k(num, ord) JOIN pg_attribute a "
        + "ON a.attrelid = con.conrelid AND a.attnum = k.num), ''), pg_get_constraintdef(con.oid), con.condeferrable::text "
        + "FROM pg_constraint con WHERE con.conrelid <> 0 AND con.contype <> 'n' "
        + "UNION ALL SELECT confrelid::text, 'referenced', '', conname::text, 'false' FROM pg_constraint WHERE contype = 'f' "
        + "UNION ALL SELECT tgrelid::text, 'trigger', '', tgname::text, 'false' FROM pg_trigger WHERE NOT tgisinternal "
        + "UNION ALL SELECT ev_class::text, 'rule', '', rulename::text, 'false' FROM pg_rewrite WHERE rulename <> '_RETURN' "
        + "UNION ALL SELECT i.indrelid::text, 'unique index', '', pg_get_indexdef(i.indexrelid), 'false' FROM pg_index i "
        + "WHERE i.indisunique AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid "
        + "AND c.contype IN ('p', 'u')) "
        + "UNION ALL SELECT oid::text, 'row security', '', 'on', 'false' FROM pg_class WHERE relrowsecurity ORDER BY 1, 2, 4"))
      rules.computeIfAbsent(row[0], oid -> new ArrayList<>()).add(row);

    final Set<String> temporary = new LinkedHashSet<>();
    for (final List<String[]> of : columns.values())
      if ("true".equals(of.get(0)[3]))
        temporary.add(of.get(0)[2]);

    final List<Table> found = new ArrayList<>();
    final List<String> qualified = new ArrayList<>();
    for (final Map.Entry<String, List<String[]>> entry : columns.entrySet()) {
      final String[] first = entry.getValue().get(0);
      final String schema = first[1];
      final String name = first[2];
      final String key = "true".equals(first[3]) || schema.equals("public") && !temporary.contains(name) ? name
          : schema + "." + name;
      final List<Column> of = new ArrayList<>();
      final List<String> writeLimits = new ArrayList<>();
      final StringBuilder signature = new StringBuilder(schema).append('.').append(name).append(' ').append(first[4]);
      for (final String[] row : entry.getValue()) {
        if (row[5] == null)
          continue;
        of.add(new Column(row[5], row[6], row[7], "true".equals(row[8])));
        signature.append('|').append(row[5]).append(' ').append(row[7]).append(' ').append(row[8]).append(' ').append(row[9]);
        if ("true".equals(row[9]))
          writeLimits.add("a default, identity or generated column " + row[5]);
      }
      final List<List<String>> uniqueKeys = new ArrayList<>();
      for (final String[] rule : rules.getOrDefault(entry.getKey(), List.of())) {
        signature.append('|').append(rule[1]).append(' ').append(rule[3]);
        final boolean unique = rule[1].equals("p") || rule[1].equals("u");
        if (unique)
          uniqueKeys.add(List.of(rule[2].split("\t")));
        if (!unique || "true".equals(rule[4]))
          writeLimits.add(switch (rule[1]) {
            case "c" -> "a check constraint";
            case "f" -> "a foreign key";
            case "x" -> "an exclusion constraint";
            case "p", "u" -> "a deferrable key";
            case "referenced" -> "a foreign key that refers to it";
            default -> "a " + rule[1];
          });
      }
      found.add(new Table(key, schema, name, of, uniqueKeys, writeLimits, "true".equals(first[4]), 0, signature.toString(), null));
      qualified.add(identifier(schema) + "." + identifier(name));
    }

    final Map<String, Table> result = new LinkedHashMap<>();
    final List<String[]> fingerprints = fingerprints(qualified);
    for (int i = 0; i < found.size(); i++) {
      final Table table = found.get(i);
      final String[] print = fingerprints.get(i);
      result.put(table.key(), new Table(table.key(), table.schema(), table.name(), table.columns(), table.uniqueKeys(),
          table.writeLimits(), table.nested(), print == null ? -1 : Long.parseLong(print[1]), table.signature(),
          print == null ? "unreadable" : print[1] + ":" + print[2]));
    }
    return result;
  }

  /** For each table its position, its number of rows and a hash of them; {@code null} for a table that cannot be read. */
  private List<String[]> fingerprints(final List<String> qualified) {
    final List<String> queries = new ArrayList<>();
    for (int i = 0; i < qualified.size(); i++)
      queries.add("SELECT " + i + ", count(*)::text, coalesce(sum(hashtextextended(pgregress_row::text, 0)::numeric), 0)::text "
          + "FROM ONLY " + qualified.get(i) + " AS pgregress_row");
    final List<String[]> result = new ArrayList<>(Collections.<String[]>nCopies(qualified.size(), null));
    if (queries.isEmpty())
      return result;
    final List<String[]> all = tryToSelect(String.join(" UNION ALL ", queries));
    if (all != null)
      for (final String[] row : all)
        result.set(Integer.parseInt(row[0]), row);
    else
      for (int i = 0; i < queries.size(); i++) {
        final List<String[]> one = tryToSelect(queries.get(i));
        if (one != null)
          result.set(i, one.get(0));
      }
    return result;
  }

  private List<String[]> tryToSelect(final String sql) {
    run("SAVEPOINT pgregress_try");
    final RegressWire.Reply reply = send(sql, null);
    if (reply.failed()) {
      run("ROLLBACK TO SAVEPOINT pgregress_try");
      run("RELEASE SAVEPOINT pgregress_try");
      return null;
    }
    run("RELEASE SAVEPOINT pgregress_try");
    return reply.rows;
  }

  /** The rows of the table itself as the server writes them with the ISO date style. */
  List<String[]> textRows(final Table table) {
    final StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < table.columns().size(); i++)
      sql.append(i > 0 ? ", " : "").append(identifier(table.columns().get(i).name()));
    sql.append(" FROM ONLY ").append(identifier(table.schema())).append('.').append(identifier(table.name()));
    return probe(true, () -> select(sql.toString()));
  }

  /** The rows of the table in the normal form of the comparison. */
  List<List<Object>> rows(final Table table) {
    return normalize(textRows(table), table.columns().stream().map(Column::type).toList(), "ISO");
  }

  RegressSubject.Shape shape(final Table table) {
    return probe(true, () -> RegressSubject.Shape.read(this::select, table.schema(), table.name()));
  }

  /**
   * The tables of the plan of a statement, which the server makes without running the statement.
   *
   * @return {@code null} when the server gives no plan: a statement of another kind, or one that it refuses
   */
  Relations relations(final String sql) {
    if (wire.transactionState() == 'E' || !PLANNED.contains(RegressSql.firstWord(RegressSql.tokens(sql))))
      return null;
    return probe(false, () -> {
      final RegressWire.Reply reply = send("EXPLAIN (VERBOSE, COSTS OFF, FORMAT JSON) " + sql, null);
      if (reply.failed() || reply.rows.isEmpty())
        return null;
      final Relations relations = new Relations(new LinkedHashSet<>(), new LinkedHashSet<>());
      walk(Json.parse(reply.rows.get(0)[0]), relations);
      return relations;
    });
  }

  private void walk(final Object node, final Relations relations) {
    if (node instanceof List<?> list)
      list.forEach(each -> walk(each, relations));
    else if (node instanceof Map<?, ?> map) {
      final Object name = map.get("Relation Name");
      if (name != null) {
        final String key = keyOf(String.valueOf(map.get("Schema")), String.valueOf(name));
        relations.all().add(key);
        if ("ModifyTable".equals(map.get("Node Type")))
          relations.written().add(key);
      }
      map.values().forEach(each -> walk(each, relations));
    }
  }

  private String keyOf(final String schema, final String name) {
    for (final Table table : tables.values())
      if (table.schema().equals(schema) && table.name().equals(name))
        return table.key();
    return schema + "." + name;
  }

  /**
   * The answer of a query that the harness makes out of a statement of the file, in the settings of the file.
   *
   * @return {@code null} when the server refuses it
   */
  RegressSubject.Answer peek(final String sql) {
    if (wire.transactionState() == 'E')
      return null;
    return probe(false, () -> {
      final Executed executed = execute(sql, null, null);
      return executed.answer().failed() ? null : executed.answer();
    });
  }

  /** Runs {@code body} and takes back whatever it did: in a transaction of its own, or in a savepoint of the open one. */
  private <T> T probe(final boolean neutral, final Supplier<T> body) {
    final char state = wire.transactionState();
    if (state == 'E')
      throw new IllegalStateException("The transaction of the file has failed: nothing can be read in it");
    final boolean idle = state == 'I';
    run(idle ? "BEGIN" : "SAVEPOINT pgregress_probe");
    try {
      if (neutral)
        run(NEUTRAL);
      return body.get();
    } finally {
      run(idle ? "ROLLBACK" : "ROLLBACK TO SAVEPOINT pgregress_probe; RELEASE SAVEPOINT pgregress_probe");
    }
  }

  private List<String[]> select(final String sql) {
    final RegressWire.Reply reply = send(sql, null);
    if (reply.failed())
      throw new IllegalStateException("The harness could not read the server: " + reply.sqlState + " " + reply.message
          + " for: " + sql);
    return reply.rows;
  }

  private void run(final String sql) {
    select(sql);
  }

  private RegressWire.Reply send(final String sql, final Supplier<String> copyData) {
    try {
      return wire.query(sql, copyData);
    } catch (final IOException e) {
      throw new UncheckedIOException("The connection to the server failed on: " + sql, e);
    }
  }

  private RegressWire.Reply sendWith(final String sql, final List<String> parameters) {
    try {
      return wire.query(sql, parameters);
    } catch (final IOException e) {
      throw new UncheckedIOException("The connection to the server failed on: " + sql, e);
    }
  }

  static String identifier(final String name) {
    return '"' + name.replace("\"", "\"\"") + '"';
  }

  @Override
  public void close() {
    wire.close();
  }
}
