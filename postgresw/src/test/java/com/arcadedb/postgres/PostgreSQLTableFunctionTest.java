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
 * Functions in {@code FROM} and {@code LATERAL} of the {@code postgresql} language next to PostgreSQL 18, both over the
 * PostgreSQL protocol (fork issue #243): the same columns, types, and rows, or the same SQLSTATE.
 * <p>
 * Needs a Docker daemon: without one the test is skipped, except on GitHub Actions, where it fails.
 */
class PostgreSQLTableFunctionTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SCHEMA = List.of(
      "CREATE TABLE f (id int PRIMARY KEY, n int, tags text[], meta jsonb, day date)",
      "CREATE TABLE entities (id int PRIMARY KEY, name text)",
      "CREATE TABLE facts (id int PRIMARY KEY, entity_id int, content text)",
      "INSERT INTO f VALUES (1, 10, '{red,blue}', '{\"aliases\": [\"a\", \"b\"], \"city\": \"Delft\"}', '2024-01-02')",
      "INSERT INTO f VALUES (2, 20, '{blue}', '{\"a\": 2}', '2024-01-02')",
      "INSERT INTO f VALUES (3, NULL, '{}', '{}', '2024-01-04')",
      "INSERT INTO f VALUES (4, 2, NULL, NULL, NULL)",
      "INSERT INTO entities VALUES (1, 'Alice'), (2, 'Bob'), (3, 'Acme')",
      "INSERT INTO facts VALUES (1, 1, 'lives in Berlin'), (2, 2, 'dislikes meetings'), (3, 1, 'likes tea')");

  private static final List<String> ANSWERED = List.of(
      // generate_series of numbers
      "SELECT * FROM generate_series(1, 3) g(x)",
      "SELECT * FROM generate_series(1, 5) AS n",
      "SELECT g FROM generate_series(1, 10, 3) g",
      "SELECT * FROM generate_series(5, 1, -2)",
      "SELECT * FROM generate_series(1, 0)",
      "SELECT * FROM generate_series(1, NULL)",
      "SELECT * FROM generate_series(9223372036854775806, 9223372036854775807)",
      "SELECT * FROM generate_series(2147483646, 2147483647)",
      "SELECT * FROM generate_series(1.5, 3.0, 0.5) x",
      "SELECT * FROM pg_catalog.generate_series(1, 2)",
      "SELECT sum(g), count(*) FROM generate_series(1, 1000) g",
      "SELECT g FROM generate_series(1, 1000000000) g LIMIT 3",
      // generate_series of times
      "SELECT * FROM generate_series(TIMESTAMP '2024-01-31', TIMESTAMP '2024-05-01', INTERVAL '1 month') AS m",
      "SELECT * FROM generate_series(TIMESTAMP '2024-01-03', TIMESTAMP '2024-01-01', INTERVAL '-1 day') g",
      "SELECT d::date AS day FROM generate_series(DATE '2024-03-01', DATE '2024-03-04', INTERVAL '1 day') AS d",
      "SELECT * FROM generate_series('2024-01-01'::timestamptz, '2024-01-02'::timestamptz, '12 hours') g",
      "SELECT d::date AS day, count(f.id) FROM generate_series(DATE '2024-01-01', DATE '2024-01-05', interval '1 day') AS d"
          + " LEFT JOIN f ON f.day = d::date GROUP BY 1 ORDER BY 1",
      // unnest
      "SELECT t.x FROM unnest(ARRAY[1, 2, 3]) AS t(x)",
      "SELECT * FROM unnest(ARRAY['a', 'b'], ARRAY[1, 2, 3]) AS t(a, b)",
      "SELECT * FROM unnest('{}'::int[])",
      "SELECT * FROM unnest(ARRAY[1, NULL]) u",
      "SELECT * FROM unnest(ARRAY[10, 20]) WITH ORDINALITY",
      "SELECT * FROM unnest(ARRAY[10, 20]) WITH ORDINALITY AS u(v, i)",
      "SELECT f.id, t FROM f, unnest(f.tags) AS t ORDER BY f.id, t",
      "SELECT f.id, t.tag FROM f CROSS JOIN LATERAL unnest(tags) AS t(tag) ORDER BY f.id, t.tag",
      "SELECT f.id, t FROM f LEFT JOIN LATERAL unnest(f.tags) t ON true ORDER BY f.id, t",
      "SELECT tag, count(*) FROM f, unnest(tags) AS tag GROUP BY tag ORDER BY count(*) DESC, tag",
      // jsonb
      "SELECT id, k.key, k.value FROM f, jsonb_each(meta) k WHERE id = 2",
      "SELECT key, value FROM f, jsonb_each_text(meta) WHERE id = 1 ORDER BY key",
      "SELECT * FROM jsonb_each('{\"b\": 1, \"aa\": [1], \"c\": null}')",
      "SELECT * FROM jsonb_each_text('{\"b\": 1.5, \"aa\": {\"x\": \"y\"}, \"c\": null, \"d\": \"s\", \"e\": true}')",
      "SELECT value FROM jsonb_array_elements('[1, \"a\", null, {\"k\": 2}]')",
      "SELECT * FROM jsonb_array_elements_text('[1, \"a\", null, true]') e",
      "SELECT * FROM jsonb_object_keys('{\"zz\": 1, \"a\": 2}')",
      "SELECT * FROM jsonb_object_keys('{\"zz\": 1, \"a\": 2}') k",
      "SELECT a.value FROM f, jsonb_array_elements_text(meta->'aliases') AS a WHERE f.id = 1 ORDER BY 1",
      "SELECT f.id, k FROM f LEFT JOIN jsonb_object_keys(f.meta) k ON true ORDER BY f.id, k",
      // inside a subquery that reads the row around it
      "SELECT id FROM f WHERE EXISTS (SELECT 1 FROM unnest(f.tags) u WHERE u = 'red') ORDER BY id",
      "SELECT id FROM f WHERE NOT EXISTS (SELECT 1 FROM unnest(tags) u WHERE u = 'red') ORDER BY id",
      "SELECT id, (SELECT count(*) FROM unnest(f.tags)) AS n FROM f ORDER BY id",
      "SELECT id, (SELECT string_agg(a.value, ',' ORDER BY a.value) FROM jsonb_array_elements_text(f.meta->'aliases') a) AS s"
          + " FROM f ORDER BY id",
      // a function of one value in FROM gives one row
      "SELECT * FROM lower('AbC')",
      "SELECT l FROM lower('AbC') AS l",
      // LATERAL
      "SELECT e.name, x.content FROM entities e CROSS JOIN LATERAL (SELECT content FROM facts WHERE entity_id = e.id"
          + " ORDER BY id DESC LIMIT 1) x ORDER BY e.name",
      "SELECT e.name, latest.id FROM entities e LEFT JOIN LATERAL (SELECT id FROM facts WHERE entity_id = e.id ORDER BY id"
          + " DESC LIMIT 1) latest ON true ORDER BY e.name",
      "SELECT e.name, s.n, s.last FROM entities e, LATERAL (SELECT count(*) AS n, max(content) AS last FROM facts WHERE"
          + " entity_id = e.id) s ORDER BY e.name",
      "SELECT e.id, x.m FROM entities e, LATERAL (SELECT e.id * 2 AS m) x ORDER BY e.id",
      "SELECT * FROM entities e, LATERAL (SELECT 1 AS one) x ORDER BY e.id",
      "SELECT * FROM LATERAL (SELECT 1 AS one) x",
      "SELECT e.id, x.a FROM entities e CROSS JOIN LATERAL (VALUES (e.id), (e.id + 100)) AS x(a) ORDER BY 1, 2",
      "SELECT e.id, x.m FROM entities e JOIN LATERAL (SELECT e.id * 2 AS m) x ON x.m > 2 ORDER BY e.id",
      "SELECT e.id, x.m FROM entities e LEFT JOIN LATERAL (SELECT e.id * 2 AS m) x ON x.m > 2 ORDER BY e.id",
      "SELECT e.id, x.m FROM entities e LEFT JOIN LATERAL (SELECT e.id * 2 AS m) x ON true WHERE x.m > 2 ORDER BY e.id",
      "SELECT e.id, g FROM entities e, generate_series(1, e.id) g ORDER BY e.id, g",
      "SELECT e.id, g FROM entities e, generate_series(1, e.id) g WHERE g > 1 ORDER BY e.id, g",
      "SELECT e.id, g, x.content FROM entities e, generate_series(1, 3) g, facts x WHERE x.entity_id = e.id AND x.id = g"
          + " ORDER BY 1, 2",
      "SELECT * FROM generate_series(1, 3) a, LATERAL (SELECT a * 10 AS b) s ORDER BY a",
      "SELECT e.id, a, b FROM entities e, LATERAL (SELECT e.id + 1 AS a) x, LATERAL (SELECT x.a + 1 AS b) y ORDER BY e.id",
      "SELECT o.id, (SELECT count(*) FROM entities e, LATERAL (SELECT id FROM facts WHERE entity_id = e.id AND id >= o.id) s)"
          + " AS n FROM entities o ORDER BY o.id");

  private static final List<List<String>> ERRORS = List.of(
      List.of("SELECT * FROM generate_series(1, 3, 0)", "22023"),
      List.of("SELECT * FROM generate_series('1', '2')", "42725"),
      List.of("SELECT * FROM unnest('{1}')", "42725"),
      List.of("SELECT * FROM jsonb_each('[1]')", "22023"),
      List.of("SELECT * FROM jsonb_array_elements('{\"a\": 1}')", "22023"),
      List.of("SELECT * FROM jsonb_object_keys('[1]')", "22023"),
      List.of("SELECT * FROM jsonb_each('a'::text)", "42883"),
      List.of("SELECT * FROM generate_series(1, 2) AS t(a, b)", "42P10"),
      List.of("SELECT * FROM entities e, (SELECT e.id) x", "42P01"),
      List.of("SELECT * FROM entities e RIGHT JOIN LATERAL (SELECT e.id AS i) x ON true", "42P10"),
      List.of("SELECT * FROM entities e, LATERAL (SELECT e.missing) x", "42703"));

  @Test
  void functionsInFromAndLateralAnswerAsPostgreSqlDoes() throws Exception {
    try (final GenericContainer<?> container = startPostgres();
        final Connection postgres = postgres(container, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SCHEMA)
        both(postgres, arcade, statement);
      for (final String statement : ANSWERED)
        same(postgres, arcade, statement);
      for (final List<String> error : ERRORS)
        sameError(postgres, arcade, error.get(0), error.get(1));
      refused(arcade, "SELECT * FROM ROWS FROM (generate_series(1, 2))", "ROWS FROM");
      refused(arcade, "SELECT * FROM jsonb_to_recordset('[]') AS t(a int)", "column definition list");
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }
}
