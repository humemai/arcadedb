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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GROUP BY} an output name or a primary key in the {@code postgresql} language next to PostgreSQL 18, both over the
 * PostgreSQL protocol (fork issue #235): the same columns, types, and rows, or the same SQLSTATE.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLGroupByTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SCHEMA = List.of(
      "CREATE TABLE u (id int PRIMARY KEY, email text UNIQUE, n int, meta jsonb, raw bytea)",
      "CREATE TABLE o (id int PRIMARY KEY, u_id int, total int)",
      "CREATE TABLE pair (a int, b int, note text, PRIMARY KEY (a, b))",
      "INSERT INTO u VALUES (1, 'a@x', 5, '{\"k\": 1}', '\\x01'), (2, 'b@x', 20, NULL, NULL), (3, 'c@x', 20, '[3]', '\\x03'),"
          + " (4, 'd@x', NULL, '[]', '\\x04')",
      "INSERT INTO o VALUES (1, 1, 10), (2, 1, 30), (3, 3, 25)",
      "INSERT INTO pair VALUES (1, 1, 'x'), (1, 2, 'y'), (2, 1, 'z')");

  private static final List<String> ANSWERED = List.of(
      // an output name
      "SELECT n > 10 AS big, count(*) FROM u GROUP BY big ORDER BY big",
      "SELECT upper(email) AS e, count(*) FROM u GROUP BY e ORDER BY e",
      "SELECT n + 1 AS next, count(*) FROM u GROUP BY next HAVING count(*) > 1 ORDER BY next",
      // a column of FROM wins over an output of the same name
      "SELECT count(*) AS n FROM u GROUP BY n ORDER BY 1",
      // another name of a grouped column
      "SELECT t.n, count(*) FROM u t GROUP BY n ORDER BY n",
      "SELECT n, count(*) FROM u t GROUP BY t.n ORDER BY t.n",
      // a primary key
      "SELECT id, email, count(*) FROM u GROUP BY id ORDER BY id",
      "SELECT u.email, count(o.id) FROM u LEFT OUTER JOIN o ON o.u_id = u.id GROUP BY u.id ORDER BY u.email",
      "SELECT u.id, u.email, u.meta, u.raw, sum(o.total) FROM u JOIN o ON o.u_id = u.id GROUP BY u.id ORDER BY u.id",
      "SELECT u.*, count(o.id) FROM u LEFT JOIN o ON o.u_id = u.id GROUP BY u.id ORDER BY u.id",
      "SELECT email, upper(email), n + 1 FROM u GROUP BY u.id HAVING n > 0 ORDER BY n, email",
      "SELECT x.email FROM u AS x GROUP BY x.id ORDER BY x.email DESC",
      "SELECT u.id AS k, email FROM u GROUP BY k ORDER BY k",
      "SELECT a, b, note, count(*) FROM pair GROUP BY b, a ORDER BY a, b",
      "SELECT u.id, o.id, u.email, o.total FROM u JOIN o ON o.u_id = u.id GROUP BY u.id, o.id ORDER BY o.id",
      "SELECT u.id, (SELECT count(*) FROM o WHERE o.u_id = u.id AND o.total > u.n) FROM u GROUP BY u.id ORDER BY u.id",
      "SELECT DISTINCT ON (u.id) u.id, email FROM u GROUP BY u.id ORDER BY u.id");

  /** Statements that both refuse with the same SQLSTATE. */
  private static final List<List<String>> ERRORS = List.of(
      // a unique key is not a primary key
      List.of("SELECT email, n, count(*) FROM u GROUP BY email", "42803"),
      List.of("SELECT u.email, o.total FROM u JOIN o ON o.u_id = u.id GROUP BY u.id", "42803"),
      List.of("SELECT note FROM pair GROUP BY a", "42803"),
      List.of("SELECT email FROM (SELECT id, email FROM u) s GROUP BY id", "42803"),
      List.of("WITH s AS (SELECT id, email FROM u) SELECT email FROM s GROUP BY id", "42803"),
      List.of("SELECT email, count(*) FROM u", "42803"),
      List.of("SELECT count(*) AS c FROM u GROUP BY c", "42803"),
      List.of("SELECT n AS x, email AS x FROM u GROUP BY x", "42702"),
      List.of("SELECT n AS x, count(*) FROM u GROUP BY x + 1", "42703"));

  @Test
  void groupByAnswersAsPostgreSqlDoes() throws Exception {
    try (final GenericContainer<?> container = startPostgres();
        final Connection postgres = postgres(container, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SCHEMA)
        both(postgres, arcade, statement);
      for (final String statement : ANSWERED)
        same(postgres, arcade, statement);
      for (final List<String> error : ERRORS)
        sameError(postgres, arcade, error.get(0), error.get(1));
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }
}
