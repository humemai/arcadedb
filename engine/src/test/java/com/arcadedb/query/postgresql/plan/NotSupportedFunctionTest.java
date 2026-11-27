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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.PostgreSQLErrors;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A function or operator that PostgreSQL 18 has and the language lacks is {@code 0A000}, not supported (fork issue #198). A name
 * that PostgreSQL does not have either, and argument types that no function or operator of PostgreSQL takes, keep PostgreSQL's
 * code, {@code 42883}.
 */
class NotSupportedFunctionTest extends TestHelper {
  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  private void assertState(final String sql, final String sqlState) {
    assertThatThrownBy(() -> {
      try (final ResultSet resultSet = database.query("postgresql", sql)) {
        while (resultSet.hasNext())
          resultSet.next();
      }
    }).as(sql).satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(sql + ": " + e.getMessage()).isEqualTo(sqlState));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // functions that the language does not have at all
      "SELECT make_date(2013, 2, 30)",
      "SELECT to_date('1', 'YYYY')",
      "SELECT parse_ident(' ')",
      "SELECT jsonb_path_query('[1]', 'strict $[1]')",
      "SELECT pg_size_bytes('1')",
      "SELECT random()",
      "SELECT pg_catalog.ln(2.0)",
      // an implicit cast leads to the types of PostgreSQL's function: integer to numeric
      "SELECT lcm(1.5, 2)",
      // every argument of PostgreSQL's function has a default
      "SELECT make_interval()",
      // VARIADIC "any"
      "SELECT json_build_object('a', 1)",
      // a cast written as a call, to a type that the language lacks
      "SELECT regrole('x')",
      // aggregates and window functions
      "SELECT regr_count(1, 2) FROM person",
      "SELECT regr_count(DISTINCT 1, 2) FROM person",
      "SELECT regr_count(1, 2) OVER () FROM person",
      "SELECT stddev(age) OVER () FROM person",
      "SELECT rank(1) OVER () FROM person",
      // functions that the language has, with types that only PostgreSQL's function takes
      "SELECT substr('a'::bytea, 1, 1)",
      "SELECT abs('1')",
      "SELECT max(ARRAY[1, 2])",
      "SELECT sum(interval '1' day)",
      "SELECT extract(day FROM interval '1' day)",
      // operators
      "SELECT - interval '1' day",
      "SELECT '{\"a\": 1}'::jsonb - 'a'",
      "SELECT '{}'::jsonb || '{}'::jsonb",
      "SELECT 'a'::bytea LIKE 'a'::bytea" })
  void whatPostgreSQLHasAndTheLanguageLacksIsNotSupported(final String sql) {
    assertState(sql, "0A000");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      // names that PostgreSQL does not have
      "SELECT no_such_function(1)",
      "SELECT no_such_function(name) OVER () FROM person",
      "SELECT no_such_aggregate(DISTINCT name) FROM person",
      // a function of the pgvector extension, which the catalog of PostgreSQL alone does not have
      "SELECT l2_distance(1, 2)",
      // argument types that no function of PostgreSQL takes, for a function that the language has
      "SELECT lower(1)",
      "SELECT gen_random_uuid(1)",
      "SELECT concat()",
      "SELECT sum(name) FROM person",
      "SELECT string_agg(name) FROM person",
      "SELECT row_number(1) OVER () FROM person",
      // and for one that it does not have
      "SELECT make_date('a', 'b')",
      "SELECT make_date(1.5, 2, 3)",
      "SELECT regrole(1, 2)",
      // operators
      "SELECT 1 || 2",
      "SELECT 1 + true",
      "SELECT -true",
      "SELECT 1 LIKE '1'",
      "SELECT name FROM person WHERE name = 1" })
  void whatPostgreSQLRefusesTooKeepsItsCode(final String sql) {
    assertState(sql, "42883");
  }
}
