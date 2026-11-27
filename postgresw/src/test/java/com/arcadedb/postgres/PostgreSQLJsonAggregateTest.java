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
 * The type {@code json} that {@code json_build_object}, {@code json_build_array}, {@code json_agg}, and
 * {@code json_object_agg} answer, and {@code jsonb_object_agg}, against PostgreSQL 18, the text of every value compared byte
 * for byte (fork issue #234).
 */
class PostgreSQLJsonAggregateTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE f (id int PRIMARY KEY, name text, n int, big bigint, price numeric, score double precision, day date,"
          + " at timestamp, seen timestamptz, meta jsonb, tags text[], ok boolean)",
      "INSERT INTO f VALUES (1, 'ann \"a\" \\ ü', 10, 100, 59.90, 0.5, DATE '2026-01-02', TIMESTAMP '2026-01-02 10:00:00',"
          + " TIMESTAMPTZ '2026-01-02 10:00:00+00', '{\"b\": 1, \"a\": [1, 2]}', ARRAY['x', 'y'], true)",
      "INSERT INTO f VALUES (2, 'bob', NULL, NULL, 5.00, 1.5, NULL, NULL, NULL, NULL, NULL, false)",
      "INSERT INTO f VALUES (3, 'cy', 10, 300, NULL, NULL, NULL, NULL, NULL, '{\"b\": 2}', '{}', NULL)",
      "CREATE TABLE g (id int PRIMARY KEY, f_id int, content text, confidence real)",
      "INSERT INTO g VALUES (1, 1, 'tea', 0.9), (2, 1, 'coffee', 1), (3, 2, 'water', NULL)");

  private static final List<String> QUERIES = List.of(
      "SELECT json_agg(name ORDER BY id) AS a, json_agg(n ORDER BY id) AS b, json_agg(big ORDER BY id) AS c FROM f",
      "SELECT json_agg(price ORDER BY id) AS a, json_agg(score ORDER BY id) AS b, json_agg(day ORDER BY id) AS c,"
          + " json_agg(at ORDER BY id) AS d, json_agg(seen ORDER BY id) AS e, json_agg(ok ORDER BY id) AS f FROM f",
      "SELECT json_agg(meta ORDER BY id) AS a, json_agg(tags ORDER BY id) AS b FROM f",
      "SELECT json_agg(name) AS a FROM f WHERE false",
      "SELECT json_agg(DISTINCT n) AS a, json_agg(DISTINCT n ORDER BY n DESC) AS b FROM f",
      "SELECT json_build_object('id', id, 'name', name, 'price', price, 'score', score, 'day', day, 'at', at, 'seen', seen,"
          + " 'meta', meta, 'tags', tags, 'ok', ok) AS a FROM f ORDER BY id",
      "SELECT json_build_object(1, 2, true, 3, DATE '2026-01-02', 4, 1.50, 5) AS a, json_build_object() AS b,"
          + " json_build_array() AS c",
      "SELECT json_build_array(1, 'a', NULL, json_build_object('z', 1.50), '{\"x\":1}'::jsonb, ARRAY[1, 2],"
          + " ARRAY['a b', NULL], E'a\\n\\tb', 100000000000000000000.5, 1e20::float8, real '0.1', true) AS a",
      "SELECT json_build_object('content', content, 'confidence', confidence) AS a FROM g ORDER BY id",
      "SELECT f.name, json_agg(json_build_object('content', g.content, 'confidence', g.confidence) ORDER BY g.id) AS facts"
          + " FROM f JOIN g ON g.f_id = f.id GROUP BY f.name ORDER BY f.name",
      "SELECT f_id, json_agg(json_build_object('id', id, 'content', content) ORDER BY id) AS orders FROM g GROUP BY f_id"
          + " ORDER BY f_id",
      "SELECT f.name, coalesce(json_agg(g.id ORDER BY g.id) FILTER (WHERE g.id IS NOT NULL), '[]') AS ids FROM f"
          + " LEFT JOIN g ON g.f_id = f.id GROUP BY f.name ORDER BY f.name",
      "SELECT json_agg(json_build_array(id) ORDER BY id) AS a FROM g",
      "SELECT json_object_agg(name, n ORDER BY id) AS a, jsonb_object_agg(name, score ORDER BY id) AS b FROM f",
      "SELECT jsonb_object_agg(content, confidence ORDER BY id) AS a, json_object_agg(id, content ORDER BY id DESC) AS b FROM g",
      "SELECT jsonb_object_agg(f_id, content ORDER BY id) AS last_wins FROM g",
      "SELECT jsonb_object_agg(id, tags) AS a, jsonb_object_agg(day, 1) FILTER (WHERE day IS NOT NULL) AS b FROM f",
      "SELECT jsonb_object_agg(name, n) AS a, json_object_agg(name, n) AS b FROM f WHERE false",
      "SELECT jsonb_object_agg('a', 'b') AS a, json_object_agg('c', NULL) AS b",
      "SELECT jsonb_agg(json_build_object('b', id, 'a', 1) ORDER BY id) AS a FROM g",
      "SELECT to_jsonb(json_build_array(1, 'x')) AS a, jsonb_build_object('k', json_build_object('b', 1, 'a', 2)) AS b",
      "SELECT json_build_object('b', 1, 'a', 2)::text AS a, json_build_object('b', 1, 'a', 2)::jsonb AS b,"
          + " '{\"b\":1,  \"a\":2}'::json AS c, '{\"b\":1,\"a\":2}'::jsonb::json AS d, json_build_array(1) || 'x' AS e",
      "SELECT CASE WHEN true THEN json_build_array(1) ELSE '[]' END AS c, coalesce(NULL, json_build_array(2), '[]') AS d",
      "SELECT x.a FROM (SELECT id, json_build_object('a', id) AS a FROM f) x ORDER BY x.id",
      "SELECT json_build_object('a', 1) AS a UNION ALL SELECT '{}'::json",
      "SELECT id, (SELECT json_agg(g.content ORDER BY g.id) FROM g WHERE g.f_id = f.id) AS facts FROM f ORDER BY id");

  /** Statements that PostgreSQL refuses, with the SQLSTATE of both. */
  private static final List<String[]> ERRORS = List.of(
      new String[] { "SELECT json_build_object('a', 1) = json_build_object('a', 1)", "42883" },
      new String[] { "SELECT json_build_object('a', 1) IN (json_build_object('a', 1))", "42883" },
      new String[] { "SELECT DISTINCT json_build_object('a', id) FROM f", "42883" },
      new String[] { "SELECT json_build_object('a', id) FROM f ORDER BY 1", "42883" },
      new String[] { "SELECT count(*) FROM f GROUP BY json_build_object('a', id)", "42883" },
      new String[] { "SELECT count(DISTINCT json_build_object('a', id)) FROM f", "42883" },
      new String[] { "SELECT json_agg(DISTINCT json_build_object('a', id)) FROM f", "42883" },
      new String[] { "SELECT json_agg(name ORDER BY json_build_object('a', id)) FROM f", "42883" },
      new String[] { "SELECT max(json_build_object('a', id)) FROM f", "42883" },
      new String[] { "SELECT json_build_object('a', 1) UNION SELECT '{}'::json", "42883" },
      new String[] { "SELECT greatest(json_build_array(1), json_build_array(2))", "42883" },
      new String[] { "SELECT jsonb_typeof(json_build_array(1))", "42883" },
      new String[] { "SELECT coalesce(json_build_array(), '[]'::jsonb)", "42846" },
      new String[] { "SELECT coalesce(json_build_array(), 'a'::text)", "42804" },
      new String[] { "SELECT coalesce(json_agg(id), 'x') FROM f", "22P02" },
      new String[] { "SELECT '[1,'::json", "22P02" },
      new String[] { "SELECT json_build_object('a')", "22023" },
      new String[] { "SELECT json_build_object(NULL, 1)", "22004" },
      new String[] { "SELECT json_build_object(ARRAY[1], 1)", "22023" },
      new String[] { "SELECT json_object_agg(n, name) FROM f", "22004" },
      new String[] { "SELECT jsonb_object_agg(n, name) FROM f", "22023" },
      new String[] { "SELECT json_agg('a')", "42804" },
      new String[] { "SELECT json_agg(id, id) FROM f", "42883" },
      new String[] { "SELECT json_object_agg(name) FROM f", "42883" });

  @Test
  void jsonBuildersAndAggregatesAnswerAsPostgreSqlDoes() throws Exception {
    try (final GenericContainer<?> container = startPostgres();
        final Connection postgres = postgres(container, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SETUP)
        both(postgres, arcade, statement);
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
      for (final String query : QUERIES)
        same(postgres, arcade, query);
      for (final String[] error : ERRORS)
        sameError(postgres, arcade, error[0], error[1]);
      refused(arcade, "SELECT json_object_agg(DISTINCT name, n) FROM f", "in a subquery in FROM");
      refused(arcade, "SELECT json_build_object('a', 1) ->> 'a'", "is not supported");

      // a json value goes into a jsonb column and into a text column
      both(postgres, arcade, "INSERT INTO f (id, meta, name) VALUES (4, json_build_object('x', 1), json_build_object('y', 2))");
      same(postgres, arcade, "SELECT id, meta, name FROM f WHERE id = 4");
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }
}
