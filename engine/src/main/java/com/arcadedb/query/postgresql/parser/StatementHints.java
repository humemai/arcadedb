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
package com.arcadedb.query.postgresql.parser;

import com.arcadedb.query.postgresql.PostgreSQLHints;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The hint of a statement that the language does not run (fork issue #241): the statement of this language or of the {@code sql}
 * language to write instead, or that there is none. A statement of {@code sql} is written with the names of the refused
 * statement and ends the hint, from its {@code {sql}} marker on, so it can be sent as it is. PostgreSQL's text does not run
 * there: {@code sql} has other statements for the schema.
 */
public final class StatementHints {

  /**
   * The names as the database stores them, for a statement of {@code sql}, where case counts: an unquoted {@code city} is the
   * table {@code City} here.
   */
  public interface Names {
    default String table(final String name) {
      return name;
    }

    default String column(final String table, final String column) {
      return column;
    }
  }

  /** The names as written: an unquoted one in lower case, as PostgreSQL folds it. */
  static final Names AS_WRITTEN = new Names() {
  };

  private static final String NO_WAY       = PostgreSQLHints.NO_WAY;
  private static final String LIST_INDEXES = "{sql}SELECT name FROM schema:indexes";

  private static final Map<String, String> TYPES = Map.ofEntries(Map.entry("bool", "BOOLEAN"), Map.entry("boolean", "BOOLEAN"),
      Map.entry("smallint", "SHORT"), Map.entry("int2", "SHORT"), Map.entry("int", "INTEGER"), Map.entry("integer", "INTEGER"),
      Map.entry("int4", "INTEGER"), Map.entry("bigint", "LONG"), Map.entry("int8", "LONG"), Map.entry("real", "FLOAT"),
      Map.entry("float4", "FLOAT"), Map.entry("double precision", "DOUBLE"), Map.entry("float8", "DOUBLE"),
      Map.entry("numeric", "DECIMAL"), Map.entry("decimal", "DECIMAL"), Map.entry("text", "STRING"),
      Map.entry("varchar", "STRING"), Map.entry("character varying", "STRING"), Map.entry("uuid", "STRING"),
      Map.entry("bytea", "BINARY"), Map.entry("date", "DATE"), Map.entry("timestamp", "DATETIME_MICROS"),
      Map.entry("jsonb", "MAP"), Map.entry("json", "MAP"));

  /** The words that end the type of a column in {@code ADD COLUMN}. */
  private static final Set<String> AFTER_A_TYPE = Set.of("NOT", "NULL", "DEFAULT", "PRIMARY", "UNIQUE", "REFERENCES", "CHECK",
      "CONSTRAINT", "COLLATE", "GENERATED", ",");

  private final List<String> words;
  private final Names        names;

  private StatementHints(final List<String> words, final Names names) {
    this.words = words;
    this.names = names;
  }

  /** @param words the tokens of the statement as written, without the semicolon */
  public static String of(final List<String> words, final Names names) {
    return new StatementHints(words, names).hint();
  }

  private String hint() {
    return switch (word(0)) {
      case "ALTER" -> "TABLE".equals(word(1)) ? alterTable() : NO_WAY;
      case "DROP" -> drop();
      case "CREATE" -> create();
      case "TRUNCATE" -> {
        final int at = "TABLE".equals(word(1)) ? 2 : 1;
        yield "Write DELETE FROM " + raw("ONLY".equals(word(at)) ? at + 1 : at);
      }
      case "MERGE" -> "Write INSERT ... ON CONFLICT (column) DO UPDATE SET ..., or an UPDATE and an INSERT in one transaction";
      case "SAVEPOINT", "RELEASE" ->
          "There are no savepoints: leave the statement out and run each unit of work in its own transaction";
      case "SET", "RESET", "SHOW", "DISCARD" -> "Leave the statement out: this language has no settings";
      case "COPY" -> "Load the rows with INSERT, many rows in one statement";
      case "VACUUM", "ANALYZE", "ANALYSE", "REINDEX", "CHECKPOINT", "CLUSTER" -> "Leave the statement out: there is nothing to do";
      case "LOCK" -> "Leave the statement out: nothing is locked. Of two transactions that change one row the later commit fails with"
          + " SQLSTATE 40001, run it again";
      case "DECLARE", "FETCH", "CLOSE", "MOVE" ->
          "Read the rows in pages with ORDER BY, LIMIT and OFFSET, or set a fetch size in your driver";
      case "PREPARE", "EXECUTE", "DEALLOCATE" -> "Use the prepared statements of your driver, with the parameters $1, $2 and so on";
      case "GRANT", "REVOKE", "REASSIGN", "SECURITY" -> NO_WAY + ": the ArcadeDB server manages users and permissions";
      case "DO", "CALL" -> NO_WAY + ": do the work in your program, in one transaction";
      default -> NO_WAY + ": the language runs SELECT, INSERT, UPDATE, DELETE, CREATE TABLE, CREATE INDEX and the transaction"
          + " statements";
    };
  }

  private String create() {
    final int at = "OR".equals(word(1)) ? 3 : 1;
    return switch (word(at)) {
      case "VIEW", "RECURSIVE" ->
          "Write the query of the view in a WITH clause of each statement that reads it: WITH v AS (SELECT ...) SELECT ... FROM v";
      case "MATERIALIZED" -> NO_WAY + ": the sql language has CREATE MATERIALIZED VIEW for a query that it reads";
      case "SCHEMA" -> "Leave the statement out and write table names without a schema: there is one schema, public";
      case "TYPE", "DOMAIN" -> NO_WAY + ": declare the column text or jsonb and check the values in your program";
      case "SEQUENCE" -> NO_WAY + ": make the keys in your program, or declare no id column and read the record id with"
          + " INSERT ... RETURNING id";
      case "FUNCTION", "PROCEDURE", "TRIGGER", "RULE", "AGGREGATE" -> NO_WAY + ": do the work in your program, in one transaction";
      case "USER", "ROLE", "GROUP", "DATABASE" -> NO_WAY + ": the ArcadeDB server manages users and databases";
      default -> NO_WAY;
    };
  }

  private String drop() {
    if ("INDEX".equals(word(1)))
      return "Drop it in the sql language with DROP INDEX `name`, one of the names listed by " + LIST_INDEXES;
    if (!"TABLE".equals(word(1)))
      return NO_WAY + ": the language makes no object of this kind, so it drops none";
    final boolean ifExists = "IF".equals(word(2));
    int at = ifExists ? 4 : 2;
    if (".".equals(word(at + 1)))
      at += 2;
    return "Empty the table with DELETE FROM " + raw(at) + ", which also deletes the edges at its rows, then drop it in the sql"
        + " language, outside a transaction block: {sql}DROP TYPE " + sqlName(names.table(name(at)))
        + (ifExists ? " IF EXISTS" : "");
  }

  private String alterTable() {
    int at = "IF".equals(word(2)) ? 4 : 2;
    if ("ONLY".equals(word(at)))
      at++;
    if (".".equals(word(at + 1)))
      at += 2;
    final String written = raw(at);
    final String table = names.table(name(at));
    final int next = at + 2;
    final String otherwise = NO_WAY + ": make a new table or column, copy the values, and drop the old one";
    return switch (word(at + 1)) {
      case "ADD" -> add(written, table, next);
      case "DROP" -> {
        if ("CONSTRAINT".equals(word(next)))
          yield "Drop its index in the sql language with DROP INDEX `name`, one of the names listed by " + LIST_INDEXES;
        int column = "COLUMN".equals(word(next)) ? next + 1 : next;
        final boolean ifExists = "IF".equals(word(column));
        if (ifExists)
          column += 2;
        yield "Drop the column in the sql language, outside a transaction block, where FORCE also drops its indexes:"
            + " {sql}DROP PROPERTY " + sqlName(table) + "." + sqlName(names.column(table, name(column)))
            + (ifExists ? " IF EXISTS" : "") + " FORCE";
      }
      case "RENAME" -> "TO".equals(word(next)) ?
          "Rename the table in the sql language, outside a transaction block: {sql}ALTER TYPE " + sqlName(table) + " NAME "
              + sqlName(name(next + 1)) :
          otherwise;
      default -> otherwise;
    };
  }

  private String add(final String written, final String table, final int from) {
    int at = from;
    final boolean column = "COLUMN".equals(word(at));
    if (column)
      at++;
    else if (Set.of("CONSTRAINT", "UNIQUE", "PRIMARY", "FOREIGN", "CHECK", "EXCLUDE").contains(word(at))) {
      if ("CONSTRAINT".equals(word(at)))
        at += 2;
      final int open = words.subList(Math.min(at, words.size()), words.size()).indexOf("(") + at;
      final int close = words.indexOf(")");
      if (("UNIQUE".equals(word(at)) || "PRIMARY".equals(word(at))) && open >= at && close > open)
        return "Write CREATE UNIQUE INDEX ON " + written + " (" + String.join(" ", words.subList(open + 1, close))
            .replace(" ,", ",") + ")";
      return NO_WAY + ": a check or a foreign key cannot be added to a table";
    }
    if ("IF".equals(word(at)))
      at += 3;
    final String name = name(at);
    final StringBuilder type = new StringBuilder();
    int end = at + 1;
    while (end < words.size() && !AFTER_A_TYPE.contains(word(end)) && !"(".equals(word(end)))
      type.append(type.isEmpty() ? "" : " ").append(word(end++).toLowerCase(Locale.ROOT));
    final String mapped = TYPES.get(type.toString());
    if (mapped == null)
      return "Add the column in the sql language with CREATE PROPERTY and a type that sql has";
    return "Add the column in the sql language, outside a transaction block"
        + (end < words.size() ? "; this statement leaves out what follows the type" : "") + ": {sql}CREATE PROPERTY "
        + sqlName(table) + "." + sqlName(name) + " " + mapped;
  }

  /** The word at {@code index} in upper case, empty past the end. */
  private String word(final int index) {
    return raw(index).toUpperCase(Locale.ROOT);
  }

  private String raw(final int index) {
    return index < words.size() ? words.get(index) : "";
  }

  /** The name that the word at {@code index} stands for: a quoted one as it is, any other in lower case. */
  private String name(final int index) {
    final String word = raw(index);
    return word.length() > 1 && word.startsWith("\"") && word.endsWith("\"") ?
        word.substring(1, word.length() - 1).replace("\"\"", "\"") :
        word.toLowerCase(Locale.ROOT);
  }

  private static String sqlName(final String name) {
    return "`" + name + "`";
  }
}
