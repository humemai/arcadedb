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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Foreign keys between ordinary tables (fork issue #228): the same statements on PostgreSQL 18 and on the {@code postgresql}
 * language, both over the PostgreSQL protocol, and two sessions that delete a referenced row and insert one that refers to it.
 */
class PostgreSQLForeignKeyTest extends PostgreSQLSideBySideTestBase {

  private static final List<String> SCHEMA = List.of(//
      "CREATE TABLE users (id bigint PRIMARY KEY, email text UNIQUE)",
      "CREATE TABLE products (id int PRIMARY KEY, sku text NOT NULL UNIQUE)",
      "CREATE TABLE orders (id bigint PRIMARY KEY, user_id bigint NOT NULL REFERENCES users (id))",
      "CREATE TABLE order_items (id bigint PRIMARY KEY, order_id bigint REFERENCES orders ON DELETE CASCADE,"
          + " product_id bigint REFERENCES products (id), sku text,"
          + " CONSTRAINT item_sku FOREIGN KEY (sku) REFERENCES products (sku) ON UPDATE CASCADE ON DELETE SET NULL)",
      "CREATE TABLE notes (id int PRIMARY KEY, author text REFERENCES users (email) ON DELETE RESTRICT ON UPDATE RESTRICT)",
      "CREATE TABLE tags (id int PRIMARY KEY, owner bigint REFERENCES users ON DELETE SET NULL ON UPDATE CASCADE)",
      "CREATE TABLE staff (id int PRIMARY KEY, boss int REFERENCES staff ON DELETE CASCADE)",
      "CREATE TABLE chain (id int PRIMARY KEY, prev int REFERENCES chain (id))",
      "CREATE TABLE pairs (a int, b text, PRIMARY KEY (a, b))",
      "CREATE TABLE uses (id int PRIMARY KEY, x text, y int, FOREIGN KEY (y, x) REFERENCES pairs (a, b))");

  private static final List<String> ROWS = List.of(//
      "INSERT INTO users VALUES (1, 'ann@example.com'), (2, 'bob@example.com'), (3, 'cy@example.com'), (4, 'dee@example.com')",
      "INSERT INTO products VALUES (1, 'A'), (2, 'B')", "INSERT INTO orders VALUES (10, 1), (11, 2)",
      "INSERT INTO order_items VALUES (100, 10, 1, 'A'), (101, 11, 2, 'B'), (102, NULL, NULL, NULL)",
      "INSERT INTO notes VALUES (1, 'ann@example.com'), (2, NULL), (3, 'dee@example.com')",
      "INSERT INTO tags VALUES (1, 2), (2, 3)",
      // one statement adds rows that refer to each other, in either order
      "INSERT INTO staff VALUES (2, 1), (1, NULL), (3, 2), (4, 4)",
      "INSERT INTO chain VALUES (3, 2), (2, 1), (1, NULL)", "INSERT INTO pairs VALUES (1, 'p')",
      "INSERT INTO uses VALUES (1, 'p', 1), (2, 'q', NULL), (3, NULL, 9)");

  private static final List<String> REFUSED_ROWS = List.of(//
      "INSERT INTO order_items VALUES (200, 9999, 1, 'A')", "INSERT INTO orders VALUES (12, 99)",
      "UPDATE orders SET user_id = 99 WHERE id = 10", "DELETE FROM users WHERE email = 'bob@example.com'",
      "UPDATE users SET id = 7 WHERE id = 1", "DELETE FROM products WHERE id = 1",
      "INSERT INTO notes VALUES (9, 'nobody@example.com')", "DELETE FROM chain WHERE id = 1",
      "UPDATE chain SET id = 8 WHERE id = 2", "INSERT INTO uses VALUES (4, 'p', 2)", "DELETE FROM pairs",
      "INSERT INTO staff VALUES (9, 8)");

  /** What {@code RESTRICT} refuses, with whatever SQLSTATE PostgreSQL 18 gives it. */
  private static final List<String> RESTRICTED = List.of("DELETE FROM users WHERE id = 4",
      "UPDATE users SET email = 'x@example.com' WHERE id = 4");

  private static final List<String> ACCEPTED = List.of(//
      // the same key again changes nothing
      "UPDATE users SET id = 1, email = 'ann@example.com' WHERE id = 1",
      // referred to and referring rows go together
      "DELETE FROM chain WHERE id IN (1, 2, 3)",
      // CASCADE, SET NULL, and ON UPDATE CASCADE
      "DELETE FROM staff WHERE id = 1", "UPDATE products SET sku = 'A2' WHERE id = 1", "UPDATE users SET id = 33 WHERE id = 3",
      "DELETE FROM users WHERE id = 33", "DELETE FROM orders WHERE id = 11", "DELETE FROM products WHERE id = 2",
      "UPDATE users SET email = 'bobby@example.com' WHERE id = 2");

  private static final List<String> TABLES = List.of("users", "products", "orders", "order_items", "notes", "tags", "staff",
      "chain", "pairs", "uses");

  @Test
  void foreignKeysAnswerAsPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres(); final Connection postgres = postgres(server, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SCHEMA)
        both(postgres, arcade, statement);
      for (final String statement : ROWS)
        both(postgres, arcade, statement);
      assertThat(failures).as(String.join("\n", failures)).isEmpty();

      for (final String statement : REFUSED_ROWS)
        sameError(postgres, arcade, statement, "23503");
      for (final String statement : RESTRICTED) {
        final String expected = state(postgres, statement);
        assertThat(expected).as(statement).isNotEqualTo("00000");
        compare(statement, expected, state(arcade, statement));
      }
      sameRows(postgres, arcade);

      // a failed check fails the block, as any error does
      for (final String statement : List.of("BEGIN", "INSERT INTO orders VALUES (13, 1)"))
        both(postgres, arcade, statement);
      sameError(postgres, arcade, "INSERT INTO orders VALUES (14, 99)", "23503");
      sameError(postgres, arcade, "SELECT 1", "25P02");
      both(postgres, arcade, "COMMIT");
      sameRows(postgres, arcade);

      for (final String statement : ACCEPTED) {
        both(postgres, arcade, statement);
        sameRows(postgres, arcade);
      }

      catalog(postgres, arcade);
      refusals(postgres, arcade);
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }
  }

  private void sameRows(final Connection postgres, final Connection arcade) {
    for (final String table : TABLES)
      same(postgres, arcade, "SELECT * FROM " + table + " ORDER BY " + ("pairs".equals(table) ? "a, b" : "id"));
  }

  private void catalog(final Connection postgres, final Connection arcade) {
    sameValues(postgres, arcade, "SELECT constraint_name, table_name, constraint_type, is_deferrable, enforced"
        + " FROM information_schema.table_constraints WHERE constraint_type = 'FOREIGN KEY' ORDER BY constraint_name");
    sameValues(postgres, arcade, "SELECT constraint_name, unique_constraint_name, match_option, update_rule, delete_rule"
        + " FROM information_schema.referential_constraints ORDER BY constraint_name");
    sameValues(postgres, arcade, "SELECT k.constraint_name, k.column_name, k.ordinal_position, k.position_in_unique_constraint"
        + " FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r"
        + " ON r.constraint_name = k.constraint_name ORDER BY k.constraint_name, k.ordinal_position");
    sameValues(postgres, arcade, "SELECT u.table_name, u.column_name, u.constraint_name"
        + " FROM information_schema.constraint_column_usage u JOIN information_schema.referential_constraints r"
        + " ON r.constraint_name = u.constraint_name ORDER BY u.constraint_name, u.column_name");
  }

  private void refusals(final Connection postgres, final Connection arcade) {
    sameError(postgres, arcade, "CREATE TABLE bad (a int REFERENCES nobody (id))", "42P01");
    sameError(postgres, arcade, "CREATE TABLE bad (a int REFERENCES users (nothing))", "42703");
    sameError(postgres, arcade, "CREATE TABLE bad (a text REFERENCES order_items (sku))", "42830");
    sameError(postgres, arcade, "CREATE TABLE bad (a int, b int, FOREIGN KEY (a, b) REFERENCES users (id))", "42830");
    sameError(postgres, arcade, "CREATE TABLE bad (a text REFERENCES users (id))", "42804");
    sameError(postgres, arcade, "CREATE TABLE bad (id int, a int REFERENCES bad)", "42704");
    refused(arcade, "CREATE TABLE bad (a bigint REFERENCES users DEFERRABLE INITIALLY DEFERRED)", "DEFERRABLE");
    refused(arcade, "CREATE TABLE bad (a bigint REFERENCES users ON DELETE SET DEFAULT)", "SET DEFAULT");
    refused(arcade, "CREATE TABLE bad (a text REFERENCES users (rid))", "foreign key");
  }

  /**
   * A parent delete and a child insert, in both orders. PostgreSQL makes the second session wait and refuses its statement with
   * 23503; the language lets the statement run and refuses the commit that comes second with 40001. Neither commits both.
   */
  @Test
  void aDeleteAndAnInsertNeverBothCommit() throws Exception {
    final ExecutorService waiting = Executors.newSingleThreadExecutor();
    try (final GenericContainer<?> server = startPostgres(); final Connection postgres = postgres(server, "");
        final Connection postgresToo = postgres(server, ""); final Connection arcade = arcade("");
        final Connection arcadeToo = arcade("")) {
      both(postgres, arcade, "CREATE TABLE parent (id int PRIMARY KEY)");
      both(postgres, arcade, "CREATE TABLE child (id int PRIMARY KEY, parent_id int REFERENCES parent)");
      both(postgres, arcade, "INSERT INTO parent VALUES (1), (2), (3)");
      assertThat(failures).as(String.join("\n", failures)).isEmpty();

      final String delete = "DELETE FROM parent WHERE id = ";
      final String insert = "INSERT INTO child VALUES (";
      for (final boolean deleteFirst : new boolean[] { true, false }) {
        final int key = deleteFirst ? 1 : 2;
        final String first = deleteFirst ? delete + key : insert + key + ", " + key + ")";
        final String second = deleteFirst ? insert + key + ", " + key + ")" : delete + key;

        run(postgres, "BEGIN");
        run(postgresToo, "BEGIN");
        run(postgres, first);
        final Future<String> held = waiting.submit(() -> state(postgresToo, second));
        Thread.sleep(1500);
        assertThat(held.isDone()).as("PostgreSQL makes the second statement wait: " + second).isFalse();
        run(postgres, "COMMIT");
        assertThat(held.get(60, TimeUnit.SECONDS)).as("PostgreSQL: " + second).isEqualTo("23503");
        run(postgresToo, "ROLLBACK");

        run(arcade, "BEGIN");
        run(arcadeToo, "BEGIN");
        run(arcade, first);
        run(arcadeToo, second);
        assertThat(state(arcade, "COMMIT")).as("the first commit after " + first).isEqualTo("00000");
        assertThat(state(arcadeToo, "COMMIT")).as("the second commit after " + second).isEqualTo("40001");
        // run again, the second transaction gets the answer PostgreSQL gave
        assertThat(state(arcadeToo, second)).as("again: " + second).isEqualTo("23503");
      }
      same(postgres, arcade, "SELECT id FROM parent ORDER BY id");
      same(postgres, arcade, "SELECT id, parent_id FROM child ORDER BY id");

      // two inserts that refer to one row do not conflict
      for (final String statement : List.of("BEGIN", insert + "31, 3)"))
        run(arcade, statement);
      for (final String statement : List.of("BEGIN", insert + "32, 3)", "COMMIT"))
        run(arcadeToo, statement);
      assertThat(state(arcade, "COMMIT")).isEqualTo("00000");
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    } finally {
      waiting.shutdownNow();
    }
  }

  /** The two statements at the same moment, many times, each in autocommit: no row may be left referring to nothing. */
  @Test
  void concurrentStatementsNeverLeaveARowReferringToNothing() throws Exception {
    final ExecutorService threads = Executors.newFixedThreadPool(2);
    try (final Connection deletes = arcade(""); final Connection inserts = arcade("")) {
      run(deletes, "CREATE TABLE parent (id int PRIMARY KEY)");
      run(deletes, "CREATE TABLE child (id int PRIMARY KEY, parent_id int REFERENCES parent)");
      final List<String> both = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        final int key = i;
        run(deletes, "INSERT INTO parent VALUES (" + key + ")");
        final CyclicBarrier start = new CyclicBarrier(2);
        final Future<String> deleted = threads.submit(() -> {
          start.await(30, TimeUnit.SECONDS);
          return state(deletes, "DELETE FROM parent WHERE id = " + key);
        });
        final Future<String> inserted = threads.submit(() -> {
          start.await(30, TimeUnit.SECONDS);
          return state(inserts, "INSERT INTO child VALUES (" + key + ", " + key + ")");
        });
        final String delete = deleted.get(60, TimeUnit.SECONDS);
        final String insert = inserted.get(60, TimeUnit.SECONDS);
        assertThat(delete).as("delete " + key).isIn("00000", "23503", "40001");
        assertThat(insert).as("insert " + key).isIn("00000", "23503", "40001");
        if ("00000".equals(delete) && "00000".equals(insert))
          both.add("key " + key);
      }
      assertThat(both).as("a delete and an insert that both committed").isEmpty();
      assertThat(answer(deletes,
          "SELECT count(*) FROM child c LEFT JOIN parent p ON p.id = c.parent_id WHERE p.id IS NULL", false)).endsWith("    0|\n");
    } finally {
      threads.shutdownNow();
    }
  }
}
