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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code EXISTS} with an empty select list, {@code FETCH FIRST}, row comparisons, and {@code DISTINCT ON} of the
 * {@code postgresql} language next to PostgreSQL 18, both over the PostgreSQL protocol (fork issue #244): the same columns, types, and rows, or the same SQLSTATE.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLSelectFormsTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SCHEMA = List.of(
      "CREATE TABLE f (id int PRIMARY KEY, n int, name text)",
      "CREATE TABLE facts (id int PRIMARY KEY, f_id int, score double precision)",
      "CREATE INDEX f_n ON f (n)",
      "INSERT INTO f VALUES (1, 10, 'b'), (2, 20, 'a'), (3, NULL, 'c'), (4, 20, NULL), (5, 30, 'a')",
      "INSERT INTO facts VALUES (1, 1, 0.5), (2, 1, 0.85), (3, 2, 0.85)");

  /** Pairs of values around the pair (20, 2), with a {@code NULL} on either member of either side. */
  private static final List<String> OPERANDS = List.of("(n, id)", "(20, 2)", "(20, NULL)", "(NULL, 2)");

  private static final List<String> ANSWERED = List.of(
      // EXISTS with an empty select list
      "SELECT EXISTS (SELECT FROM f WHERE id = 1)",
      "SELECT EXISTS (SELECT FROM f WHERE id = 99)",
      "SELECT EXISTS (SELECT FROM f)",
      "SELECT EXISTS (SELECT)",
      "SELECT EXISTS (SELECT WHERE false)",
      "SELECT NOT EXISTS (SELECT FROM f WHERE n > 100)",
      "SELECT id FROM f WHERE EXISTS (SELECT FROM facts WHERE facts.f_id = f.id) ORDER BY id",
      "SELECT id FROM f WHERE NOT EXISTS (SELECT FROM facts WHERE f_id = f.id AND score > 0.6) ORDER BY id",
      "SELECT id FROM f WHERE EXISTS (SELECT FROM facts WHERE facts.f_id < f.id) ORDER BY id",
      "SELECT EXISTS (SELECT FROM f GROUP BY n HAVING count(*) > 1)",
      "SELECT EXISTS (SELECT FROM f HAVING count(*) > 100)",
      "SELECT EXISTS (SELECT FROM f ORDER BY id OFFSET 5)",
      "SELECT EXISTS (SELECT FROM f LIMIT 0)",
      "SELECT EXISTS (WITH x AS (SELECT id FROM f) SELECT FROM x)",
      // FETCH FIRST
      "SELECT id FROM f ORDER BY id FETCH FIRST 2 ROWS ONLY",
      "SELECT id FROM f ORDER BY id FETCH NEXT 1 ROW ONLY",
      "SELECT id FROM f ORDER BY id FETCH FIRST ROW ONLY",
      "SELECT id FROM f ORDER BY id FETCH FIRST 0 ROWS ONLY",
      "SELECT id FROM f ORDER BY id FETCH FIRST (1 + 2) ROWS ONLY",
      "SELECT id FROM f ORDER BY id OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY",
      "SELECT id FROM f ORDER BY id FETCH FIRST 2 ROWS ONLY OFFSET 3",
      "SELECT id FROM f UNION ALL SELECT id FROM facts ORDER BY id FETCH FIRST 3 ROWS ONLY",
      "SELECT x.id FROM (SELECT id FROM f ORDER BY id DESC FETCH FIRST 2 ROWS ONLY) x ORDER BY x.id",
      // rows
      "SELECT id FROM f WHERE (n, id) < (20, 5) ORDER BY id",
      "SELECT id FROM f WHERE (n, id) > (20, 2) ORDER BY n, id",
      "SELECT id, score FROM facts WHERE (score, id) < (0.85, 100) ORDER BY score DESC, id DESC LIMIT 2",
      "SELECT id FROM f WHERE (name, n, id) >= ('a', 20, 4) ORDER BY id",
      "SELECT id FROM f WHERE (n, id) IN ((10, 1), (20, 4), (30, 1)) ORDER BY id",
      "SELECT id FROM f WHERE (n, id) NOT IN ((10, 1), (20, 4)) ORDER BY id",
      "SELECT id, (n, name) IN ((20, 'a'), (NULL, 'c')) FROM f ORDER BY id",
      "SELECT id, ((n, id)) = ((20, 2)) FROM f ORDER BY id",
      "SELECT id FROM f WHERE (n + 0, upper(name)) <= (20, 'A') ORDER BY id",
      "SELECT (1, 'a') < (1, 'b'), (2, NULL) < (1, 0), (1, NULL) < (1, 0), (1, 2, NULL) < (1, 3, 0)",
      "SELECT (1, NULL) = (2, NULL), (1, NULL) = (1, NULL), (1, NULL) <> (2, NULL)",
      // DISTINCT ON: the first row of each value of the keys, in the order of ORDER BY
      "SELECT DISTINCT ON (n > 0) n FROM f ORDER BY n > 0, n",
      "SELECT DISTINCT ON (n) n, id FROM f ORDER BY n, id",
      "SELECT DISTINCT ON (n) n, id FROM f ORDER BY n DESC, id DESC",
      "SELECT DISTINCT ON (n) n, id FROM f ORDER BY n NULLS FIRST, id",
      "SELECT DISTINCT ON (f.n) id FROM f ORDER BY n, f.id",
      "SELECT DISTINCT ON (1) n, id FROM f ORDER BY 1, 2 DESC",
      "SELECT DISTINCT ON (k) n AS k, id FROM f ORDER BY n, id",
      "SELECT DISTINCT ON (name, n) name, n, id FROM f ORDER BY n, name, id",
      "SELECT DISTINCT ON (n, name) id FROM f ORDER BY name",
      "SELECT DISTINCT ON (n, n) n FROM f",
      "SELECT DISTINCT ON (n) n FROM f ORDER BY n, n DESC",
      "SELECT DISTINCT ON (n + 1) id FROM f ORDER BY n + 1, id, n + 1",
      "SELECT DISTINCT ON (n) n, id FROM f ORDER BY n, id LIMIT 2 OFFSET 1",
      "SELECT DISTINCT ON (f_id) f_id, score, id FROM facts ORDER BY f_id, score DESC, id",
      "SELECT DISTINCT ON (f.id) f.id, facts.score FROM f JOIN facts ON facts.f_id = f.id ORDER BY f.id, facts.score DESC",
      "SELECT DISTINCT ON (c) n, count(*) AS c FROM f GROUP BY n ORDER BY c, n",
      "SELECT DISTINCT ON (count(*)) count(*), n FROM f GROUP BY n ORDER BY count(*), n",
      "SELECT DISTINCT ON (row_number() OVER (ORDER BY id) % 2) id FROM f ORDER BY row_number() OVER (ORDER BY id) % 2, id",
      "SELECT x.id FROM (SELECT DISTINCT ON (n) id FROM f ORDER BY n, id DESC) x ORDER BY x.id",
      "WITH last AS (SELECT DISTINCT ON (f_id) f_id, id FROM facts ORDER BY f_id, id DESC) SELECT * FROM last ORDER BY f_id",
      "SELECT EXISTS (SELECT DISTINCT ON (n) n FROM f ORDER BY n)",
      "SELECT id, (SELECT DISTINCT ON (facts.f_id) facts.id FROM facts WHERE facts.f_id = f.id ORDER BY facts.f_id, facts.id DESC)"
          + " FROM f ORDER BY f.id");

  /** Statements that both refuse with the same SQLSTATE. */
  private static final List<List<String>> ERRORS = List.of(
      List.of("SELECT (1, 2) < (1, 2, 3)", "42601"),
      List.of("SELECT id FROM f WHERE (n, id) IN ((1, 2), (1, 2, 3))", "42601"),
      List.of("SELECT EXISTS (SELECT FROM f ORDER BY 1)", "42P10"),
      List.of("SELECT id FROM f ORDER BY id FETCH FIRST -1 ROWS ONLY", "2201W"),
      List.of("SELECT id FROM f LIMIT 1 FETCH FIRST 1 ROWS ONLY", "42601"),
      List.of("SELECT DISTINCT ON (n) n, id FROM f ORDER BY id", "42P10"),
      List.of("SELECT DISTINCT ON (n) n, id FROM f ORDER BY id, n", "42P10"),
      List.of("SELECT DISTINCT ON (n, name) id FROM f ORDER BY n, id", "42P10"),
      List.of("SELECT DISTINCT ON (1) n, n AS m FROM f ORDER BY 2", "42P10"),
      List.of("SELECT DISTINCT ON (3) n, id FROM f", "42P10"),
      List.of("SELECT DISTINCT ON ('n') n FROM f", "42601"),
      List.of("SELECT DISTINCT ON (NULL) n FROM f", "42601"),
      List.of("SELECT n FROM f ORDER BY 'n'", "42601"),
      List.of("SELECT n FROM f ORDER BY 1.5", "42601"),
      List.of("SELECT DISTINCT ON n n FROM f", "42601"),
      List.of("SELECT DISTINCT ON (n) count(*) FROM f", "42803"));

  @Test
  void selectFormsAnswerAsPostgreSqlDoes() throws Exception {
    try (final GenericContainer<?> container = startPostgres();
        final Connection postgres = postgres(container, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SCHEMA)
        both(postgres, arcade, statement);
      for (final String statement : ANSWERED)
        same(postgres, arcade, statement);
      // every operator on every pair of operands: the three-valued answer of each row
      for (final String operator : List.of("=", "<>", "<", "<=", ">", ">="))
        for (final String left : OPERANDS)
          for (final String right : OPERANDS)
            same(postgres, arcade, "SELECT id, " + left + " " + operator + " " + right + " FROM f ORDER BY id");
      for (final List<String> error : ERRORS)
        sameError(postgres, arcade, error.get(0), error.get(1));
      // keyset paging with parameters, as a driver sends it
      final String page = "SELECT id FROM f WHERE (n, id) > (?, ?) ORDER BY n, id FETCH FIRST ? ROWS ONLY";
      compare(page, page(postgres, page), page(arcade, page));
      refused(arcade, "SELECT id FROM f ORDER BY n FETCH FIRST 1 ROWS WITH TIES", "WITH TIES");
      refused(arcade, "SELECT FROM f", "A select list with no item");
      refused(arcade, "SELECT (1, 2)", "A row constructor");
      refused(arcade, "SELECT id FROM f WHERE (n, id) = (SELECT n, id FROM f WHERE id = 1)", "A row constructor");
      refused(arcade, "SELECT id FROM f WHERE (n, id) IS NULL", "A row constructor");
      refused(arcade, "SELECT id FROM f WHERE (n, id) < ((SELECT 20), 5)", "A row comparison with");
      refused(arcade, "SELECT EXISTS (SELECT FROM f UNION SELECT FROM facts)", "A select list with no item");
      refused(arcade, "SELECT id FROM f WHERE EXISTS (SELECT DISTINCT ON (f_id) 1 FROM facts WHERE facts.f_id = f.id)",
          "A correlated EXISTS with DISTINCT ON");
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private static String page(final Connection connection, final String query) {
    try (final PreparedStatement statement = connection.prepareStatement(query)) {
      statement.setInt(1, 20);
      statement.setInt(2, 2);
      statement.setInt(3, 2);
      try (final ResultSet rows = statement.executeQuery()) {
        return text(rows, true);
      }
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }
}
