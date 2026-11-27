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
package com.arcadedb.remote;

import com.arcadedb.exception.ForeignKeyException;
import com.arcadedb.exception.TransactionException;
import com.arcadedb.server.BaseGraphServerTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fork issue #228: a write that a foreign key refuses reaches a caller of the Java driver as a {@link ForeignKeyException}, with
 * the SQLSTATE that tells a violated key from a restricted one. A refused statement raises it, and a refused commit carries it as
 * the cause of the {@link TransactionException} in which the driver reports a failed commit.
 */
class RemoteForeignKeyExceptionIT extends BaseGraphServerTest {
  private static final String DATABASE_NAME = "remote-database-foreign-keys";

  private RemoteDatabase remote;

  @Override
  protected boolean isCreateDatabases() {
    return false;
  }

  @Test
  void aRefusedWriteKeepsItsTypeAndStateOverTheDriver() {
    assertRefused(() -> remote.command("sql", "INSERT INTO child SET id = 2, pid = 99").close(), "23503");
    assertRefused(() -> remote.command("sql", "DELETE FROM parent WHERE id = 1").close(), "23001");
    assertRefused(() -> remote.command("postgresql", "INSERT INTO child (id, pid) VALUES (3, 99)").close(), "23503");
    // the commit of a transaction of the caller
    assertThatThrownBy(
        () -> remote.transaction(() -> remote.command("sql", "INSERT INTO child SET id = 4, pid = 99").close())).isExactlyInstanceOf(
        TransactionException.class).satisfies(e -> assertForeignKey(e.getCause(), "23503"));
  }

  private static void assertRefused(final ThrowingCallable write, final String sqlState) {
    assertThatThrownBy(write).satisfies(e -> assertForeignKey(e, sqlState));
  }

  private static void assertForeignKey(final Throwable refusal, final String sqlState) {
    assertThat(refusal).isExactlyInstanceOf(ForeignKeyException.class);
    assertThat(((ForeignKeyException) refusal).getSqlState()).isEqualTo(sqlState);
  }

  @BeforeEach
  public void beginTest() {
    super.beginTest();
    final RemoteServer server = new RemoteServer("127.0.0.1", getServerHttpPort(), "root", DEFAULT_PASSWORD_FOR_TESTS);
    if (!server.exists(DATABASE_NAME))
      server.create(DATABASE_NAME);
    remote = new RemoteDatabase("127.0.0.1", getServerHttpPort(), DATABASE_NAME, "root", DEFAULT_PASSWORD_FOR_TESTS);
    remote.command("postgresql", "CREATE TABLE parent (id int PRIMARY KEY)");
    remote.command("postgresql", "CREATE TABLE child (id int PRIMARY KEY, pid int REFERENCES parent (id) ON DELETE RESTRICT)");
    remote.command("postgresql", "INSERT INTO parent (id) VALUES (1)");
    remote.command("postgresql", "INSERT INTO child (id, pid) VALUES (1, 1)");
  }

  @AfterEach
  public void endTest() {
    if (remote != null)
      remote.close();
    final RemoteServer server = new RemoteServer("127.0.0.1", getServerHttpPort(), "root", DEFAULT_PASSWORD_FOR_TESTS);
    if (server.exists(DATABASE_NAME))
      server.drop(DATABASE_NAME);
    super.endTest();
  }
}
