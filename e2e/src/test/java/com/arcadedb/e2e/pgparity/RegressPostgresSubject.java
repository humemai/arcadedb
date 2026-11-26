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

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * PostgreSQL on the side under test, for the self-test of the regression harness (fork issue #119): PostgreSQL against
 * PostgreSQL gives only passed and out of scope. It holds its tables by running every statement itself, those that are not
 * judged too, so nothing is copied.
 */
final class RegressPostgresSubject implements RegressSubject {
  private final RegressDatabase database;
  private       Set<String>     touched = Set.of();

  RegressPostgresSubject(final RegressServer server) {
    this.database = server.freshDatabase();
  }

  @Override
  public void setup(final RegressPsql.Send send, final Supplier<String> copyData) {
    database.execute(send.sql(), send.parameters(), copyData);
  }

  @Override
  public void load(final String sql, final String data) {
    database.execute(sql, null, () -> data);
  }

  @Override
  public void connect(final String user) {
    database.connect(user);
  }

  @Override
  public Answer execute(final RegressPsql.Send send, final Supplier<String> copyData) {
    database.changesAfter(send.sql());
    final Answer answer = database.execute(send.sql(), send.parameters(), copyData).answer();
    touched = database.changesAfter(send.sql());
    return answer;
  }

  @Override
  public Set<String> touched() {
    return touched;
  }

  @Override
  public Shape shape(final RegressDatabase.Table table) {
    final RegressDatabase.Table own = database.tables().get(table.key());
    return own == null ? null : database.shape(own);
  }

  @Override
  public List<List<Object>> rows(final RegressDatabase.Table table) {
    final RegressDatabase.Table own = database.tables().get(table.key());
    return own == null ? null : database.rows(own);
  }

  @Override
  public void close() {
    database.close();
  }
}
