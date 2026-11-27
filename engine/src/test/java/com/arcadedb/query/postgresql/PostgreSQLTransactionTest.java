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
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code BEGIN}, {@code COMMIT} and {@code ROLLBACK} of the {@code postgresql} language (fork issue #29, stage 4): they are the
 * transaction of the database, a statement outside a block is one transaction, an error inside a block fails it ({@code 25P02}),
 * and a commit that loses to another transaction is {@code 40001}.
 */
class PostgreSQLTransactionTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private void command(final String sql, final Object... parameters) {
    try (final ResultSet ignored = database.command("postgresql", sql, parameters)) {
      // the answer of a statement is not read here
    }
  }

  private long count(final String table) {
    try (final ResultSet resultSet = database.query("postgresql", "SELECT count(*) FROM " + table)) {
      return resultSet.next().<Long>getProperty("count");
    }
  }

  private static void assertState(final Runnable statement, final String sqlState) {
    assertThatThrownBy(statement::run).isInstanceOfSatisfying(RuntimeException.class, e -> {
      try {
        assertThat(e.getClass().getMethod("getSqlState").invoke(e)).as(String.valueOf(e.getMessage())).isEqualTo(sqlState);
      } catch (final ReflectiveOperationException noState) {
        throw new AssertionError("no SQLSTATE on " + e, noState);
      }
    });
  }

  @Test
  void aBlockIsOneTransactionThatRollbackUndoesAndCommitKeeps() {
    command("BEGIN");
    assertThat(database.isTransactionActive()).isTrue();
    command("INSERT INTO person (pid, name) VALUES (90, 'Tx')");
    command("UPDATE person SET age = 99 WHERE pid = 1");
    command("DELETE FROM person WHERE pid = 5");
    assertThat(count("person")).isEqualTo(5L);
    command("ROLLBACK");
    assertThat(database.isTransactionActive()).isFalse();
    assertThat(count("person")).isEqualTo(5L);
    try (final ResultSet resultSet = database.query("postgresql", "SELECT age FROM person WHERE pid = 1")) {
      assertThat(resultSet.next().<Integer>getProperty("age")).isEqualTo(36);
    }

    command("START TRANSACTION");
    command("INSERT INTO person (pid, name) VALUES (91, 'Tx')");
    command("COMMIT");
    assertThat(database.isTransactionActive()).isFalse();
    assertThat(count("person")).isEqualTo(6L);
  }

  @Test
  void theOtherSpellingsAreTheSameStatements() {
    command("BEGIN WORK");
    command("INSERT INTO person (pid, name) VALUES (92, 'Tx')");
    command("ABORT");
    assertThat(count("person")).isEqualTo(5L);
    command("BEGIN TRANSACTION");
    command("INSERT INTO person (pid, name) VALUES (93, 'Tx')");
    command("END TRANSACTION");
    assertThat(count("person")).isEqualTo(6L);
  }

  @Test
  void aStatementOutsideABlockIsOneTransactionAndAFailedOneChangesNothing() {
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (94, 'A'), (1, 'Dup')"), "23505");
    assertThat(database.isTransactionActive()).isFalse();
    assertThat(count("person")).isEqualTo(5L);
    command("INSERT INTO person (pid, name) VALUES (94, 'A')");
    assertThat(count("person")).isEqualTo(6L);
  }

  @Test
  void anErrorInABlockFailsItUntilRollback() {
    command("BEGIN");
    command("INSERT INTO person (pid, name) VALUES (95, 'Tx')");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'Dup')"), "23505");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (96, 'Tx')"), "25P02");
    assertState(() -> database.query("postgresql", "SELECT 1"), "25P02");
    assertThatThrownBy(() -> command("SELECT 1")).hasMessageContaining("current transaction is aborted, commands ignored until end of transaction block");
    command("ROLLBACK");
    assertThat(count("person")).isEqualTo(5L);
    // the block is over: the next statement runs
    command("INSERT INTO person (pid, name) VALUES (96, 'Tx')");
    assertThat(count("person")).isEqualTo(6L);
  }

  @Test
  void commitOfAFailedBlockIsARollback() {
    command("BEGIN");
    command("INSERT INTO person (pid, name) VALUES (97, 'Tx')");
    assertState(() -> command("UPDATE person SET pid = 2 WHERE pid = 1"), "23505");
    command("COMMIT");
    assertThat(database.isTransactionActive()).isFalse();
    assertThat(count("person")).isEqualTo(5L);
  }

  @Test
  void aSyntaxErrorOrAnUnknownTableFailsTheBlockToo() {
    command("BEGIN");
    assertState(() -> command("INSERT INTO nope (a) VALUES (1)"), "42P01");
    assertState(() -> command("SELECT 1"), "25P02");
    command("ROLLBACK");
    command("BEGIN");
    assertState(() -> command("SELEC 1"), "42601");
    assertState(() -> command("SELECT 1"), "25P02");
    command("ROLLBACK");
    command("BEGIN");
    // an index of a table that the block did not make is refused (fork issue #277)
    assertState(() -> command("CREATE INDEX ON person (name)"), "0A000");
    assertState(() -> command("SELECT 1"), "25P02");
    command("ROLLBACK");
    assertThat(count("person")).isEqualTo(5L);
  }

  @Test
  void beginInABlockAndCommitOrRollbackWithoutOneAreNoError() {
    command("COMMIT");
    command("ROLLBACK");
    command("BEGIN");
    command("BEGIN");
    command("INSERT INTO person (pid, name) VALUES (98, 'Tx')");
    command("COMMIT");
    assertThat(database.isTransactionActive()).isFalse();
    assertThat(count("person")).isEqualTo(6L);
    command("COMMIT");
  }

  @Test
  void aTransactionOfTheCallerIsTheCallersAndAFailedStatementInItLeavesItUsable() {
    database.begin();
    command("INSERT INTO person (pid, name) VALUES (99, 'Tx')");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'Dup')"), "23505");
    // not a block of the language: the database took the failed statement back and the transaction goes on
    command("INSERT INTO person (pid, name) VALUES (100, 'Tx')");
    assertThat(count("person")).isEqualTo(7L);
    database.rollback();
    assertThat(count("person")).isEqualTo(5L);
  }

  @Test
  void aCommitThatLosesToAnotherTransactionIs40001() throws InterruptedException {
    command("BEGIN");
    command("UPDATE person SET age = 50 WHERE pid = 1");

    final AtomicReference<Throwable> other = new AtomicReference<>();
    final Thread thread = new Thread(() -> {
      try {
        command("UPDATE person SET age = 60 WHERE pid = 1");
      } catch (final Throwable t) {
        other.set(t);
      }
    });
    thread.start();
    thread.join();
    assertThat(other.get()).isNull();

    assertState(() -> command("COMMIT"), "40001");
    // the block is over and the other transaction's value stands
    assertThat(database.isTransactionActive()).isFalse();
    try (final ResultSet resultSet = database.query("postgresql", "SELECT age FROM person WHERE pid = 1")) {
      assertThat(resultSet.next().<Integer>getProperty("age")).isEqualTo(60);
    }
  }

  @Test
  void theBlockOfOneThreadDoesNotFailTheStatementsOfAnother() throws InterruptedException {
    command("BEGIN");
    assertState(() -> command("INSERT INTO person (pid, name) VALUES (1, 'Dup')"), "23505");
    final AtomicReference<Throwable> other = new AtomicReference<>();
    final AtomicReference<Long> seen = new AtomicReference<>();
    final Thread thread = new Thread(() -> {
      try {
        seen.set(count("person"));
      } catch (final Throwable t) {
        other.set(t);
      }
    });
    thread.start();
    thread.join();
    assertThat(other.get()).isNull();
    assertThat(seen.get()).isEqualTo(5L);
    command("ROLLBACK");
  }

  @Test
  void anEdgeWrittenInABlockIsGoneAfterRollback() {
    command("BEGIN");
    command("INSERT INTO works_at (source_id, target_id, since) SELECT p.rid, c.rid, 2030 FROM person p, company c "
        + "WHERE p.pid = 5 AND c.cid = 30");
    assertThat(count("works_at")).isEqualTo(6L);
    command("ROLLBACK");
    assertThat(count("works_at")).isEqualTo(5L);
  }
}
