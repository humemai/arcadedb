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
package com.arcadedb.query.postgresql;

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.ForeignKeyException;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.ForeignKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The foreign keys the engine holds for every language (fork issue #228), declared on types that {@code sql} made: the checks at
 * commit, the referential actions, the schema changes they refuse, and two transactions that would break a key together.
 */
class ForeignKeyEngineTest extends TestHelper {

  private void schema(final ForeignKey.Action onDelete, final ForeignKey.Action onUpdate) {
    database.command("sql", "CREATE VERTEX TYPE parent");
    database.command("sql", "CREATE PROPERTY parent.id LONG");
    database.command("sql", "CREATE INDEX ON parent (id) UNIQUE");
    database.command("sql", "CREATE VERTEX TYPE child");
    database.command("sql", "CREATE PROPERTY child.id LONG");
    database.command("sql", "CREATE PROPERTY child.pid INTEGER");
    database.command("sql", "CREATE INDEX ON child (pid) NOTUNIQUE");
    database.getSchema().getType("child").setCustomValue(ForeignKey.CUSTOM_KEY, ForeignKey.toJson(
        List.of(new ForeignKey("child_pid_fkey", List.of("pid"), "parent", List.of("id"), onDelete, onUpdate))));
    write("sql", "INSERT INTO parent SET id = 1");
    write("sql", "INSERT INTO parent SET id = 2");
    write("sql", "INSERT INTO child SET id = 10, pid = 1");
  }

  private void write(final String language, final String statement) {
    database.transaction(() -> database.command(language, statement).close());
  }

  private long count(final String from) {
    try (final ResultSet result = database.query("sql", "SELECT count(*) AS c FROM " + from)) {
      return ((Number) result.next().getProperty("c")).longValue();
    }
  }

  /** The check a statement of the postgresql language runs at its end. */
  private void checkNow() {
    ((DatabaseInternal) database).getTransaction().checkForeignKeys();
  }

  private void refused(final String language, final String statement) {
    assertThatThrownBy(() -> write(language, statement)).hasStackTraceContaining("violates foreign key constraint");
  }

  @Test
  void sqlAndOpenCypherCannotBreakAKey() {
    schema(ForeignKey.Action.NO_ACTION, ForeignKey.Action.NO_ACTION);
    refused("sql", "INSERT INTO child SET id = 11, pid = 99");
    refused("sql", "UPDATE child SET pid = 99 WHERE id = 10");
    refused("sql", "DELETE FROM parent WHERE id = 1");
    refused("sql", "UPDATE parent SET id = 7 WHERE id = 1");
    refused("opencypher", "CREATE (:child {id: 11, pid: 99})");
    refused("opencypher", "MATCH (p:parent {id: 1}) DETACH DELETE p");
    refused("opencypher", "MATCH (p:parent {id: 1}) SET p.id = 7");
    assertThat(count("parent")).isEqualTo(2);
    assertThat(count("child WHERE pid = 1")).isEqualTo(1);
    // a null refers to nothing, and a row that nothing refers to can change and go
    write("sql", "INSERT INTO child SET id = 12, pid = null");
    write("sql", "UPDATE parent SET id = 8 WHERE id = 2");
    write("sql", "DELETE FROM parent WHERE id = 8");
    // the order inside a transaction does not matter, and both rows can go together
    database.transaction(() -> {
      database.command("sql", "INSERT INTO child SET id = 13, pid = 3").close();
      database.command("sql", "INSERT INTO parent SET id = 3").close();
    });
    database.transaction(() -> {
      database.command("sql", "DELETE FROM parent WHERE id = 3").close();
      database.command("sql", "DELETE FROM child WHERE id = 13").close();
    });
    // a row that was taken back leaves nothing to refuse
    database.transaction(() -> {
      database.command("sql", "INSERT INTO child SET id = 14, pid = 55").close();
      database.command("sql", "DELETE FROM child WHERE id = 14").close();
    });
    assertThat(count("child")).isEqualTo(2);
  }

  @Test
  void cascadeRunsForEveryLanguage() {
    schema(ForeignKey.Action.CASCADE, ForeignKey.Action.CASCADE);
    write("sql", "UPDATE parent SET id = 5 WHERE id = 1");
    assertThat(count("child WHERE pid = 5")).isEqualTo(1);
    write("opencypher", "MATCH (p:parent {id: 5}) DELETE p");
    assertThat(count("child")).isZero();
  }

  @Test
  void setNullAndRestrict() {
    schema(ForeignKey.Action.SET_NULL, ForeignKey.Action.RESTRICT);
    assertThatThrownBy(() -> write("sql", "UPDATE parent SET id = 5 WHERE id = 1")).hasStackTraceContaining(
        "violates RESTRICT setting");
    write("sql", "DELETE FROM parent WHERE id = 1");
    assertThat(count("child WHERE pid IS NULL")).isEqualTo(1);
  }

  @Test
  void aTypeThatAKeyNeedsStays() {
    schema(ForeignKey.Action.NO_ACTION, ForeignKey.Action.NO_ACTION);
    assertThatThrownBy(() -> database.getSchema().dropType("parent")).hasStackTraceContaining("depend on it");
    assertThatThrownBy(() -> database.getSchema().getType("parent").rename("other")).hasStackTraceContaining("refers to it");
    database.command("sql", "DROP INDEX `child[pid]`");
    assertThatThrownBy(() -> write("sql", "INSERT INTO child SET id = 11, pid = 1")).hasStackTraceContaining("cannot be checked");
    assertThatThrownBy(() -> write("sql", "DELETE FROM parent WHERE id = 1")).hasStackTraceContaining("cannot be checked");
    database.command("sql", "CREATE INDEX ON child (pid) NOTUNIQUE");
    write("sql", "INSERT INTO child SET id = 11, pid = 1");
    // without backticks, as the error messages write it
    database.command("sql", "ALTER TYPE child CUSTOM " + ForeignKey.CUSTOM_KEY + " = null");
    write("sql", "DELETE FROM parent WHERE id = 1");
    database.getSchema().dropType("parent");
  }

  /** Custom values are open to users: the names the engine acts on are under its own prefix (fork issue #313). */
  @Test
  void customValuesOfAUserWithTheNamesOfTheRulesAreNone() {
    database.command("sql", "CREATE DOCUMENT TYPE mine");
    database.command("sql", "CREATE PROPERTY mine.name STRING");
    database.command("sql", "ALTER TYPE mine CUSTOM foreignKeys = 'none of the engine'");
    database.command("sql", "ALTER TYPE mine CUSTOM checkConstraints = 'none either'");
    database.command("sql", "ALTER PROPERTY mine.name CUSTOM typeModifier = 'uppercase'");
    write("sql", "INSERT INTO mine SET name = 'a'");
    write("sql", "UPDATE mine SET name = 'b'");
    reopenDatabase();
    write("sql", "INSERT INTO mine SET name = 'c'");
    write("sql", "DELETE FROM mine WHERE name = 'b'");
    assertThat(count("mine")).isEqualTo(1);
    assertThat(database.getSchema().getType("mine").getCustomValue("foreignKeys")).isEqualTo("none of the engine");
  }

  /**
   * One transaction deletes a referenced record and another adds a record that refers to it. Each has checked its own view before
   * the other commits, so only the check under the commit locks can refuse the second one: both orders.
   */
  @Test
  void theSecondOfADeleteAndAnInsertIsRefusedInBothOrders() throws Exception {
    schema(ForeignKey.Action.NO_ACTION, ForeignKey.Action.NO_ACTION);
    final ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      // the delete commits first
      database.begin();
      database.command("sql", "INSERT INTO child SET id = 20, pid = 2").close();
      checkNow();
      other.submit(() -> write("sql", "DELETE FROM parent WHERE id = 2")).get(60, TimeUnit.SECONDS);
      assertThatThrownBy(() -> database.commit()).isInstanceOf(ConcurrentModificationException.class);
      assertThat(count("child WHERE pid = 2")).isZero();

      // the insert commits first
      write("sql", "INSERT INTO parent SET id = 3");
      database.begin();
      database.command("sql", "DELETE FROM parent WHERE id = 3").close();
      checkNow();
      other.submit(() -> write("sql", "INSERT INTO child SET id = 21, pid = 3")).get(60, TimeUnit.SECONDS);
      assertThatThrownBy(() -> database.commit()).isInstanceOf(ConcurrentModificationException.class);
      assertThat(count("parent WHERE id = 3")).isEqualTo(1);

      // two transactions that add records referring to one record do not conflict
      database.begin();
      database.command("sql", "INSERT INTO child SET id = 22, pid = 3").close();
      checkNow();
      other.submit(() -> write("sql", "INSERT INTO child SET id = 23, pid = 3")).get(60, TimeUnit.SECONDS);
      database.commit();
      assertThat(count("child WHERE pid = 3")).isEqualTo(3);
    } finally {
      if (database.isTransactionActive())
        database.rollback();
      other.shutdownNow();
    }
  }

  /** The same two writes at the same moment, many times: no record may be left referring to nothing. */
  @Test
  void concurrentDeletesAndInsertsNeverLeaveARecordReferringToNothing() throws Exception {
    schema(ForeignKey.Action.NO_ACTION, ForeignKey.Action.NO_ACTION);
    final ExecutorService threads = Executors.newFixedThreadPool(2);
    final List<Integer> broken = new ArrayList<>();
    try {
      for (int i = 100; i < 400; i++) {
        final int key = i;
        write("sql", "INSERT INTO parent SET id = " + key);
        final CyclicBarrier start = new CyclicBarrier(2);
        final Future<Boolean> delete = threads.submit(() -> attempt(start, "DELETE FROM parent WHERE id = " + key));
        final Future<Boolean> insert = threads.submit(
            () -> attempt(start, "INSERT INTO child SET id = " + key + ", pid = " + key));
        final boolean deleted = delete.get(60, TimeUnit.SECONDS);
        final boolean inserted = insert.get(60, TimeUnit.SECONDS);
        if (deleted && inserted || count("child WHERE pid = " + key) > count("parent WHERE id = " + key))
          broken.add(key);
      }
    } finally {
      threads.shutdownNow();
    }
    assertThat(broken).isEmpty();
  }

  /** @return whether the statement committed; a refusal by the key or a conflict is the only other answer */
  private boolean attempt(final CyclicBarrier start, final String statement) throws Exception {
    database.begin();
    try {
      database.command("sql", statement).close();
      start.await(30, TimeUnit.SECONDS);
      database.commit();
      return true;
    } catch (final ForeignKeyException | ConcurrentModificationException e) {
      return false;
    } finally {
      if (database.isTransactionActive())
        database.rollback();
    }
  }
}
