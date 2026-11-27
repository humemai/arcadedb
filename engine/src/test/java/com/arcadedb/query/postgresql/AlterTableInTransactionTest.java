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
import com.arcadedb.query.postgresql.catalog.Sequences;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ALTER TABLE} in a transaction of the Java API (fork issue #230): the schema changes at once, and a transaction that
 * does not commit leaves the change to be taken back by the next statement of the language. Over the PostgreSQL protocol the
 * comparison with PostgreSQL 18 is {@code PostgreSQLAlterTableInBlockTest} of the {@code postgresw} module.
 */
class AlterTableInTransactionTest extends TestHelper {

  @Test
  void aRollbackIsTakenBackByTheNextStatement() {
    run("CREATE TABLE t (id serial PRIMARY KEY, v text DEFAULT 'a')");
    run("INSERT INTO t (v) VALUES ('one')");

    database.begin();
    run("ALTER TABLE t ADD COLUMN c int DEFAULT 3");
    run("ALTER TABLE t ALTER COLUMN v SET DEFAULT 'b', ALTER COLUMN v SET NOT NULL");
    run("ALTER TABLE t ALTER COLUMN id DROP DEFAULT");
    assertThat(number("SELECT c AS n FROM t")).isEqualTo(3);
    database.rollback();

    // the next statement takes the changes back, the last one first
    run("INSERT INTO t (id) VALUES (100)");
    assertThat(database.getSchema().getType("t").getPropertyIfExists("c")).isNull();
    assertThat(database.getSchema().getType("t").getProperty("v").isNotNull()).isFalse();
    assertThat(number("SELECT count(*) AS n FROM t WHERE v = 'a'")).isEqualTo(1);
    // the serial column still counts: its counter was only to be dropped by a commit
    run("INSERT INTO t (v) VALUES ('two')");
    assertThat(number("SELECT id AS n FROM t WHERE v = 'two'")).isEqualTo(2);
  }

  @Test
  void aCommitKeepsTheChangeAndDropsTheCounterOfAReplacedDefault() {
    run("CREATE TABLE t (id serial PRIMARY KEY, v text)");
    run("INSERT INTO t (v) VALUES ('one')");

    database.transaction(() -> {
      run("ALTER TABLE t ADD COLUMN c int DEFAULT 4");
      run("ALTER TABLE t ALTER COLUMN id DROP DEFAULT");
    });

    assertThat(number("SELECT c AS n FROM t")).isEqualTo(4);
    assertThat(number("SELECT count(*) AS n FROM t")).isEqualTo(1);
    try (final ResultSet counters = database.query("sql", "SELECT count(*) AS n FROM " + Sequences.TYPE_NAME)) {
      assertThat(counters.next().<Number>getProperty("n").longValue()).isZero();
    }
  }

  @Test
  void aFailedStatementLeavesNoColumnAndNoPartOfItsRows() {
    run("CREATE TABLE t (id int PRIMARY KEY, v text)");
    for (int id = 1; id <= 40; id++)
      run("INSERT INTO t VALUES (" + id + ", 'v')");
    database.command("sql", "CREATE PROPERTY t.m STRING").close();
    database.transaction(() -> database.command("sql", "UPDATE t SET m = 'x' WHERE id < 40").close());
    // the sql statement refuses the flag over a row that lacks the value, the Java API does not read the rows
    database.getSchema().getType("t").getProperty("m").setMandatory(true);

    database.begin();
    boolean failed = false;
    try {
      run("ALTER TABLE t ADD COLUMN w int DEFAULT 5");
    } catch (final RuntimeException e) {
      failed = true;
    }
    assertThat(failed).isTrue();
    assertThat(database.getSchema().getType("t").getPropertyIfExists("w")).isNull();
    // the transaction of the caller commits without a part of the statement, or refuses to commit
    try {
      database.commit();
    } catch (final RuntimeException e) {
      if (database.isTransactionActive())
        database.rollback();
    }
    try (final ResultSet written = database.query("sql", "SELECT count(*) AS n FROM t WHERE w IS NOT NULL")) {
      assertThat(written.next().<Number>getProperty("n").longValue()).isZero();
    }
    // the integrity check after the test accepts no row that lacks a mandatory property
    database.getSchema().getType("t").getProperty("m").setMandatory(false);
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }

  private long number(final String query) {
    try (final ResultSet rows = database.query("postgresql", query)) {
      return rows.next().<Number>getProperty("n").longValue();
    }
  }
}
