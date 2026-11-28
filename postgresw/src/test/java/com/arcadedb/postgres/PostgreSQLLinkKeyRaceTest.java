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
 * A change of a referenced key against the insert of a link to it, in two sessions (fork issue #292), on PostgreSQL 18 and on the
 * {@code postgresql} language, both over the PostgreSQL protocol. Neither commits both.
 */
class PostgreSQLLinkKeyRaceTest extends PostgreSQLSideBySideTestBase {

  private static final String PERSON = "CREATE TABLE person (id int PRIMARY KEY)";
  private static final String KNOWS  =
      "CREATE TABLE knows (source_id int NOT NULL REFERENCES person (id), target_id int NOT NULL REFERENCES person)";

  /**
   * PostgreSQL makes the second session wait and refuses its statement with 23503. The language lets both statements run and
   * refuses the commit that comes second with 40001; run again, the statement gets 23503.
   */
  @Test
  void aKeyChangeAndAnInsertNeverBothCommit() throws Exception {
    final ExecutorService waiting = Executors.newSingleThreadExecutor();
    try (final GenericContainer<?> server = startPostgres(); final Connection postgres = postgres(server, "");
        final Connection postgresToo = postgres(server, ""); final Connection arcade = arcade("");
        final Connection arcadeToo = arcade("")) {
      both(postgres, arcade, PERSON);
      both(postgres, arcade, KNOWS);
      assertThat(failures).as(String.join("\n", failures)).isEmpty();

      int key = 100;
      for (final boolean sourceEnd : new boolean[] { true, false })
        for (final boolean insertRunsFirst : new boolean[] { true, false }) {
          final int changed = key++;
          final int other = key++;
          both(postgres, arcade, "INSERT INTO person VALUES (" + changed + "), (" + other + ")");
          final String insert = insert(sourceEnd, changed, other);
          final String update = update(changed);
          final String first = insertRunsFirst ? insert : update;
          final String second = insertRunsFirst ? update : insert;

          run(postgres, "BEGIN");
          run(postgresToo, "BEGIN");
          run(postgres, first);
          final Future<String> held = waiting.submit(() -> state(postgresToo, second));
          Thread.sleep(1500);
          assertThat(held.isDone()).as("PostgreSQL makes the second statement wait: " + second).isFalse();
          run(postgres, "COMMIT");
          assertThat(held.get(60, TimeUnit.SECONDS)).as("PostgreSQL: " + second).isEqualTo("23503");
          run(postgresToo, "ROLLBACK");

          // the same order of commits on the language: the first statement's transaction commits first
          run(arcade, "BEGIN");
          run(arcadeToo, "BEGIN");
          run(arcade, first);
          run(arcadeToo, second);
          assertThat(state(arcade, "COMMIT")).as("the first commit after " + first).isEqualTo("00000");
          assertThat(state(arcadeToo, "COMMIT")).as("the second commit after " + second).isEqualTo("40001");
          assertThat(state(arcadeToo, second)).as("again: " + second).isEqualTo("23503");

          // and the other order of commits, which PostgreSQL cannot reach: its second statement waits
          final int third = key++;
          final int fourth = key++;
          run(arcade, "INSERT INTO person VALUES (" + third + "), (" + fourth + ")");
          final String laterInsert = insert(sourceEnd, third, fourth);
          final String laterUpdate = update(third);
          run(arcade, "BEGIN");
          run(arcadeToo, "BEGIN");
          run(arcade, insertRunsFirst ? laterInsert : laterUpdate);
          run(arcadeToo, insertRunsFirst ? laterUpdate : laterInsert);
          assertThat(state(arcadeToo, "COMMIT")).as("the second statement commits first: " + second).isEqualTo("00000");
          assertThat(state(arcade, "COMMIT")).as("the first statement commits second: " + first).isEqualTo("40001");
          assertThat(state(arcade, insertRunsFirst ? laterInsert : laterUpdate)).as("again: " + first).isEqualTo("23503");
        }
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    } finally {
      waiting.shutdownNow();
    }
  }

  /** The two statements at the same moment, many times, each in autocommit: they never both succeed. */
  @Test
  void concurrentStatementsNeverBothSucceed() throws Exception {
    final ExecutorService threads = Executors.newFixedThreadPool(2);
    try (final Connection updates = arcade(""); final Connection inserts = arcade("")) {
      run(updates, PERSON);
      run(updates, KNOWS);
      final List<String> both = new ArrayList<>();
      for (int i = 0; i < 300; i++) {
        final int key = i;
        run(updates, "INSERT INTO person VALUES (" + key + ")");
        final CyclicBarrier start = new CyclicBarrier(2);
        final Future<String> updated = threads.submit(() -> {
          start.await(30, TimeUnit.SECONDS);
          return state(updates, update(key));
        });
        final Future<String> inserted = threads.submit(() -> {
          start.await(30, TimeUnit.SECONDS);
          return state(inserts, "INSERT INTO knows VALUES (" + key + ", " + key + ")");
        });
        final String update = updated.get(60, TimeUnit.SECONDS);
        final String insert = inserted.get(60, TimeUnit.SECONDS);
        assertThat(update).as("update " + key).isIn("00000", "23503", "40001");
        assertThat(insert).as("insert " + key).isIn("00000", "23503", "40001");
        if ("00000".equals(update) && "00000".equals(insert))
          both.add("key " + key);
      }
      assertThat(both).as("keys that changed while a link to them was inserted").isEmpty();
    } finally {
      threads.shutdownNow();
    }
  }

  private static String insert(final boolean sourceEnd, final int changed, final int other) {
    return "INSERT INTO knows VALUES (" + (sourceEnd ? changed + ", " + other : other + ", " + changed) + ")";
  }

  private static String update(final int key) {
    return "UPDATE person SET id = " + (key + 10_000) + " WHERE id = " + key;
  }
}
