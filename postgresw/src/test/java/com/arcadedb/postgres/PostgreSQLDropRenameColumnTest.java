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

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code DROP COLUMN} and {@code RENAME COLUMN} on the {@code postgresql} language against PostgreSQL 18 (fork issue #230), over
 * JDBC: what the rows hold afterwards, which keys, checks and indexes go or follow, what later writes do, the errors, a rollback,
 * a statement that fails while it writes the rows, and what {@code sql} and openCypher see.
 */
class PostgreSQLDropRenameColumnTest extends PostgreSQLSideBySideTestBase {
  private static final String CONSTRAINTS = "SELECT table_name, constraint_name, constraint_type FROM"
      + " information_schema.table_constraints WHERE table_schema = 'public' AND constraint_type <> 'CHECK'"
      + " ORDER BY table_name, constraint_name";
  private static final String CHECKS = "SELECT table_name, constraint_name, column_name FROM"
      + " information_schema.constraint_column_usage WHERE table_schema = 'public' AND constraint_name NOT LIKE '%not_null%'"
      + " ORDER BY table_name, constraint_name, column_name";
  private static final String COLUMNS = "SELECT table_name, column_name, data_type, is_nullable, column_default,"
      + " character_maximum_length FROM information_schema.columns WHERE table_schema = 'public'"
      + " ORDER BY table_name, ordinal_position";
  private static final List<String> SETUP = List.of(
      "CREATE TABLE acc (id int PRIMARY KEY, code text UNIQUE, name varchar(10) NOT NULL DEFAULT 'n', lo int, hi int,"
          + " note text, at date, n serial, UNIQUE (name, lo), CHECK (lo >= 0), CHECK (lo < hi),"
          + " CONSTRAINT named CHECK (name <> 'lo'))",
      "CREATE TABLE child (id int PRIMARY KEY, code text REFERENCES acc (code), acc int REFERENCES acc)",
      "CREATE INDEX acc_note_idx ON acc (note)", "CREATE INDEX ON acc (hi, note)",
      "INSERT INTO acc (id, code, name, lo, hi, note, at) VALUES (1, 'a', 'one', 1, 5, 'x', '2026-01-01'),"
          + " (2, 'b', 'two', 2, 6, NULL, NULL)",
      "INSERT INTO child VALUES (1, 'a', 1)");

  @Test
  void renameColumnAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String setup : SETUP)
          both(postgres, arcade, setup);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        for (final String[] error : new String[][] { { "ALTER TABLE acc RENAME COLUMN nosuch TO other", "42703" },
            { "ALTER TABLE acc RENAME COLUMN lo TO hi", "42701" }, { "ALTER TABLE acc RENAME lo TO xmin", "42701" },
            { "ALTER TABLE nowhere RENAME lo TO a", "42P01" } })
          sameError(postgres, arcade, error[0], error[1]);

        for (final String change : List.of("ALTER TABLE IF EXISTS nowhere RENAME COLUMN a TO b",
            "ALTER TABLE acc RENAME COLUMN code TO sku", "ALTER TABLE acc RENAME name TO title",
            "ALTER TABLE acc RENAME COLUMN lo TO low", "ALTER TABLE acc RENAME COLUMN id TO acc_id",
            "ALTER TABLE acc RENAME note TO \"Memo\"", "ALTER TABLE acc RENAME COLUMN n TO seq",
            "ALTER TABLE acc RENAME at TO seen", "ALTER TABLE public.child RENAME COLUMN acc TO parent",
            "INSERT INTO acc (acc_id, sku, low, hi) VALUES (3, 'c', 3, 7)"))
          both(postgres, arcade, change);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        sameValues(postgres, arcade, "SELECT * FROM acc ORDER BY acc_id");
        sameValues(postgres, arcade, "SELECT * FROM child ORDER BY id");
        sameValues(postgres, arcade, COLUMNS);
        sameValues(postgres, arcade, CONSTRAINTS);
        sameValues(postgres, arcade, CHECKS);
        sameError(postgres, arcade, "SELECT code FROM acc", "42703");
        // the keys and checks hold under the new names
        for (final String[] error : new String[][] { { "INSERT INTO acc (acc_id, hi) VALUES (1, 1)", "23505" },
            { "INSERT INTO acc (acc_id, sku) VALUES (4, 'a')", "23505" },
            { "INSERT INTO acc (acc_id, title, low) VALUES (4, 'one', 1)", "23505" },
            { "INSERT INTO acc (acc_id, title) VALUES (4, NULL)", "23502" },
            { "INSERT INTO acc (acc_id, title) VALUES (4, 'elevenchars')", "22001" },
            { "INSERT INTO acc (acc_id, low, hi) VALUES (4, 9, 3)", "23514" },
            { "UPDATE acc SET title = 'lo' WHERE acc_id = 1", "23514" },
            { "INSERT INTO child VALUES (2, 'zz', 1)", "23503" }, { "INSERT INTO child VALUES (2, 'a', 99)", "23503" },
            { "DROP INDEX acc_pkey, nosuch", "42704" } })
          sameError(postgres, arcade, error[0], error[1]);
        both(postgres, arcade, "DROP INDEX acc_note_idx");

        // sql and openCypher read the new names, and no old one
        compare("sql", "    0|3|\n", rows(arcade, "{sql} SELECT count(code) AS a, count(sku) AS b FROM acc"));
        compare("openCypher", "    one|\n", rows(arcade, "{opencypher} MATCH (a:acc) WHERE a.acc_id = 1 RETURN a.title AS t"));

        // a rollback gives the old names back, with their values
        postgres.setAutoCommit(false);
        arcade.setAutoCommit(false);
        both(postgres, arcade, "ALTER TABLE acc RENAME sku TO code");
        both(postgres, arcade, "ALTER TABLE acc RENAME hi TO high");
        both(postgres, arcade, "INSERT INTO acc (acc_id, code, low, high) VALUES (5, 'e', 1, 2)");
        sameValues(postgres, arcade, "SELECT acc_id, code, high FROM acc ORDER BY acc_id");
        postgres.rollback();
        arcade.rollback();
        sameValues(postgres, arcade, "SELECT * FROM acc ORDER BY acc_id");
        sameValues(postgres, arcade, COLUMNS);
        sameValues(postgres, arcade, CONSTRAINTS);
        sameValues(postgres, arcade, CHECKS);
        postgres.commit();
        arcade.commit();
        postgres.setAutoCommit(true);
        arcade.setAutoCommit(true);
        sameError(postgres, arcade, "INSERT INTO acc (acc_id, sku) VALUES (6, 'a')", "23505");
        sameError(postgres, arcade, "INSERT INTO acc (acc_id, low, hi) VALUES (6, 9, 3)", "23514");
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  @Test
  void dropColumnAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String setup : SETUP)
          both(postgres, arcade, setup);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        for (final String[] error : new String[][] { { "ALTER TABLE acc DROP COLUMN nosuch", "42703" },
            { "ALTER TABLE acc DROP COLUMN code", "2BP01" }, { "ALTER TABLE acc DROP COLUMN id RESTRICT", "2BP01" },
            { "ALTER TABLE nowhere DROP COLUMN a", "42P01" },
            { "ALTER TABLE acc DROP COLUMN note, DROP COLUMN nosuch", "42703" } })
          sameError(postgres, arcade, error[0], error[1]);
        sameValues(postgres, arcade, "SELECT * FROM acc ORDER BY id");
        sameValues(postgres, arcade, COLUMNS);

        // a rollback gives the columns back, with their values, keys, checks and indexes
        postgres.setAutoCommit(false);
        arcade.setAutoCommit(false);
        both(postgres, arcade, "INSERT INTO acc (id, code, name, lo, hi, note) VALUES (4, 'd', 'four', 4, 8, 'y')");
        both(postgres, arcade, "ALTER TABLE acc DROP COLUMN note, DROP COLUMN lo, DROP COLUMN n");
        both(postgres, arcade, "ALTER TABLE acc DROP COLUMN code CASCADE");
        sameValues(postgres, arcade, "SELECT * FROM acc ORDER BY id");
        sameValues(postgres, arcade, CONSTRAINTS);
        postgres.rollback();
        arcade.rollback();
        sameValues(postgres, arcade, "SELECT * FROM acc ORDER BY id");
        sameValues(postgres, arcade, COLUMNS);
        sameValues(postgres, arcade, CONSTRAINTS);
        sameValues(postgres, arcade, CHECKS);
        // an insert of the block into an index that the block drops, and the commit
        both(postgres, arcade, "INSERT INTO acc (id, code, name, lo, hi, note) VALUES (4, 'd', 'four', 4, 8, 'y')");
        both(postgres, arcade, "ALTER TABLE acc DROP COLUMN note");
        postgres.commit();
        arcade.commit();
        postgres.setAutoCommit(true);
        arcade.setAutoCommit(true);
        sameValues(postgres, arcade, "SELECT * FROM acc ORDER BY id");
        sameError(postgres, arcade, "SELECT note FROM acc", "42703");
        sameError(postgres, arcade, "INSERT INTO acc (id, code, hi, lo) VALUES (5, 'e', 1, 2)", "23514");
        sameError(postgres, arcade, "INSERT INTO acc (id, code) VALUES (5, 'a')", "23505");

        for (final String change : List.of("ALTER TABLE acc DROP COLUMN IF EXISTS note", "ALTER TABLE acc DROP lo",
            "INSERT INTO acc (id, code, name, hi) VALUES (6, 'f', 'one', -1)", "ALTER TABLE acc DROP COLUMN code CASCADE",
            "INSERT INTO child VALUES (2, 'zz', 1)", "ALTER TABLE acc DROP COLUMN n, DROP at, ADD COLUMN note int DEFAULT 4",
            "ALTER TABLE child DROP COLUMN acc", "INSERT INTO child VALUES (3, 'q')",
            "ALTER TABLE acc DROP COLUMN id CASCADE", "INSERT INTO acc (name) VALUES ('keyless')"))
          both(postgres, arcade, change);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        sameValues(postgres, arcade, "SELECT * FROM acc ORDER BY name, hi");
        sameValues(postgres, arcade, "SELECT * FROM child ORDER BY id");
        sameValues(postgres, arcade, COLUMNS);
        sameValues(postgres, arcade, CONSTRAINTS);
        sameValues(postgres, arcade, CHECKS);
        // the values are gone: a column of the same name starts empty, and sql and openCypher find none
        compare("sql", "    0|0|0|\n", rows(arcade,
            "{sql} SELECT count(code) AS codes, count(lo) AS lows, count(`at`) AS times FROM acc"));
        compare("openCypher", rows(postgres, "SELECT count(*), 0, 0, 0 FROM acc"), rows(arcade,
            "{opencypher} MATCH (a:acc) RETURN count(a) AS n, count(a.code) AS codes, count(a.lo) AS lows, count(a.`at`) AS times"));
        both(postgres, arcade, "ALTER TABLE acc ADD COLUMN lo int");
        sameValues(postgres, arcade, "SELECT count(lo), count(note) FROM acc");

        // what the language refuses says what to write
        run(arcade, "CREATE TABLE person (id int PRIMARY KEY, name text)");
        run(arcade, "CREATE TABLE knows (source_id int NOT NULL REFERENCES person (id),"
            + " target_id int NOT NULL REFERENCES person (id), since int)");
        compare("a column that links refer to", "2BP01", state(arcade, "ALTER TABLE person DROP COLUMN id"));
        refused(arcade, "ALTER TABLE person DROP COLUMN id CASCADE", "DROP TABLE knows");
        refused(arcade, "ALTER TABLE knows DROP COLUMN source_id", "Leave the column");
        run(arcade, "ALTER TABLE person RENAME id TO pid");
        run(arcade, "INSERT INTO person VALUES (1, 'a'), (2, 'b')");
        run(arcade, "INSERT INTO knows VALUES (1, 2, 2000)");
        compare("the link after the rename of its key", "    1|2|\n", rows(arcade, "SELECT source_id, target_id FROM knows"));
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  /**
   * The rows are rewritten and one of them is refused part way: a property that one row lacks is mandatory, so every later write
   * of that row fails. The {@code sql} statement refuses to set the flag over such a row, so the Java API of the server sets it.
   * The column, its values, key, check and index stay.
   */
  @Test
  void aStatementThatFailsWhileItRewritesTheRowsLeavesTheTableAsItWas() throws Exception {
    try (final Connection arcade = arcade("")) {
      run(arcade, "CREATE TABLE partial (id int PRIMARY KEY, v int UNIQUE CHECK (v > 0))");
      run(arcade, "CREATE INDEX partial_both ON partial (v, id)");
      for (int id = 1; id <= 40; id++)
        run(arcade, "INSERT INTO partial VALUES (" + id + ", " + id + ")");
      run(arcade, "{sql} CREATE PROPERTY partial.m STRING");
      run(arcade, "{sql} UPDATE partial SET m = 'x' WHERE id < 40");
      getServerDatabase(0, getDatabaseName()).getSchema().getType("partial").getProperty("m").setMandatory(true);
      final String values = rows(arcade, "SELECT id, v, m FROM partial ORDER BY id");
      final String constraints = rows(arcade, CONSTRAINTS);
      final String checks = rows(arcade, CHECKS);
      final String indexes = typeIndexes(arcade);

      for (final String change : List.of("ALTER TABLE partial DROP COLUMN v", "ALTER TABLE partial RENAME v TO w"))
        for (final boolean inBlock : new boolean[] { false, true }) {
          arcade.setAutoCommit(!inBlock);
          compare(change + ", in a block " + inBlock, "error", "00000".equals(state(arcade, change)) ? "runs" : "error");
          if (inBlock) {
            arcade.rollback();
            arcade.setAutoCommit(true);
          }
          compare("the rows after " + change, values, rows(arcade, "SELECT id, v, m FROM partial ORDER BY id"));
          compare("the constraints after " + change, constraints, rows(arcade, CONSTRAINTS));
          compare("the checks after " + change, checks, rows(arcade, CHECKS));
          compare("the indexes after " + change, indexes, typeIndexes(arcade));
          // the index that was built again finds the rows
          compare("a lookup by two columns after " + change, "    5|\n",
              rows(arcade, "{sql} SELECT id FROM partial WHERE v = 5 AND id = 5"));
          compare("the new name after " + change, "    0|\n",
              rows(arcade, "{sql} SELECT count(*) AS n FROM partial WHERE w IS NOT NULL"));
          compare("openCypher after " + change, "    40|40|0|\n", rows(arcade,
              "{opencypher} MATCH (p:partial) RETURN count(p) AS n, count(p.v) AS kept, count(p.w) AS renamed"));
          compare("the key after " + change, "23505", state(arcade, "INSERT INTO partial (id, v, m) VALUES (50, 1, 'x')"));
          compare("the check after " + change, "23514", state(arcade, "INSERT INTO partial (id, v, m) VALUES (50, -1, 'x')"));
        }

      // with the row repaired both run
      run(arcade, "{sql} UPDATE partial SET m = 'x' WHERE id = 40");
      run(arcade, "ALTER TABLE partial RENAME v TO w");
      compare("renamed", "    820|\n", rows(arcade, "SELECT sum(w) FROM partial"));
      run(arcade, "ALTER TABLE partial DROP COLUMN w");
      compare("dropped", "42703", state(arcade, "SELECT w FROM partial"));
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }
  }

  /** The names of the indexes of the types. Those of the buckets say when they were built, which building one again changes. */
  private static String typeIndexes(final Connection connection) {
    return rows(connection, "{sql} SELECT name FROM schema:indexes ORDER BY name").lines().filter(line -> line.contains("["))
        .collect(Collectors.joining("\n"));
  }

  /** The rows of an answer, without the header. */
  private static String rows(final Connection connection, final String query) {
    return answer(connection, query, false).replaceFirst("^[^\n]*\n", "");
  }
}
