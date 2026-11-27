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
 * {@code ORDER BY} inside an aggregate call, {@code FILTER (WHERE ...)}, {@code array_agg} and {@code jsonb_agg}, against
 * PostgreSQL 18 (fork issue #234).
 */
class PostgreSQLAggregateModifierTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE f (id int PRIMARY KEY, name text, n int, big bigint, price numeric, score double precision, day date,"
          + " at timestamp, meta jsonb, tags text[], ok boolean)",
      "INSERT INTO f VALUES (1, 'ann', 10, 100, 59.90, 0.5, DATE '2026-01-02', TIMESTAMP '2026-01-02 10:00:00',"
          + " '{\"b\": 1, \"a\": [1, 2]}', ARRAY['x', 'y'], true)",
      "INSERT INTO f VALUES (2, 'bob', 20, NULL, 5.00, 1.5, NULL, NULL, NULL, NULL, false)",
      "INSERT INTO f VALUES (3, 'cy', NULL, 300, NULL, NULL, NULL, NULL, '{\"b\": 2}', '{}', NULL)",
      "INSERT INTO f VALUES (4, 'ann', 10, 400, 59.90, 2.5, DATE '2025-12-31', TIMESTAMP '2025-12-31 23:59:59', NULL, NULL,"
          + " true)",
      "CREATE TABLE g (id int PRIMARY KEY, f_id int, content text)",
      "INSERT INTO g VALUES (1, 1, 'tea'), (2, 1, 'coffee'), (3, 2, 'water')");

  private static final List<String> QUERIES = List.of(
      // ORDER BY inside an aggregate
      "SELECT array_agg(name ORDER BY id) AS a, array_agg(n ORDER BY n DESC, id) AS b, array_agg(name ORDER BY name DESC, id)"
          + " AS c FROM f",
      "SELECT array_agg(name ORDER BY n NULLS FIRST, id) AS a, array_agg(name ORDER BY n DESC NULLS LAST, id DESC) AS b FROM f",
      "SELECT array_agg(name ORDER BY upper(name) DESC, id * -1) AS a, array_agg(id ORDER BY 1, id) AS b FROM f",
      "SELECT array_agg(name ORDER BY id) AS a, array_agg(name ORDER BY id DESC) AS b, count(*) AS n FROM f",
      "SELECT string_agg(name, ',' ORDER BY id DESC) AS a, string_agg(name, '; ' ORDER BY name, id) AS b,"
          + " string_agg(name, ',' ORDER BY n) AS c FROM f",
      "SELECT count(id ORDER BY name) AS a, sum(n ORDER BY name) AS b, avg(n ORDER BY n) AS c, min(name ORDER BY id) AS d,"
          + " max(n ORDER BY n DESC) AS e FROM f",
      "SELECT f.name, array_agg(g.content ORDER BY g.id) AS facts FROM f JOIN g ON g.f_id = f.id GROUP BY f.name ORDER BY f.name",
      "SELECT f.name, string_agg(g.content, '; ' ORDER BY g.id) AS summary FROM f JOIN g ON g.f_id = f.id GROUP BY f.name"
          + " ORDER BY f.name",
      "SELECT jsonb_agg(name ORDER BY name) AS names FROM f",
      "SELECT name, jsonb_agg(id ORDER BY id DESC) AS ids FROM f GROUP BY name ORDER BY name",
      // every element type, NULL included
      "SELECT array_agg(big ORDER BY id) AS a, array_agg(price ORDER BY id) AS b, array_agg(score ORDER BY id) AS c,"
          + " array_agg(ok ORDER BY id) AS d FROM f",
      "SELECT array_agg(day ORDER BY id) AS a, array_agg(at ORDER BY id) AS b FROM f",
      "SELECT array_agg('x'::text) AS a, array_agg(id + 1 ORDER BY id) AS b FROM f",
      "SELECT jsonb_agg(n ORDER BY id) AS a, jsonb_agg(price ORDER BY id) AS b, jsonb_agg(score ORDER BY id) AS c,"
          + " jsonb_agg(day ORDER BY id) AS d, jsonb_agg(at ORDER BY id) AS e, jsonb_agg(ok ORDER BY id) AS f FROM f",
      "SELECT jsonb_agg(meta ORDER BY id) AS a, jsonb_agg(tags ORDER BY id) AS b FROM f",
      "SELECT jsonb_agg(jsonb_build_object('content', content, 'id', id) ORDER BY id) AS facts FROM g",
      // no row and one group
      "SELECT array_agg(n) AS a, jsonb_agg(n) AS b, string_agg(name, ',' ORDER BY id) AS c, count(*) AS d FROM f"
          + " WHERE false",
      "SELECT name, array_agg(n ORDER BY id) AS a FROM f WHERE false GROUP BY name",
      // DISTINCT
      "SELECT array_agg(DISTINCT name) AS a, array_agg(DISTINCT n) AS b, array_agg(DISTINCT price) AS c FROM f",
      "SELECT array_agg(DISTINCT name ORDER BY name DESC) AS a, array_agg(DISTINCT n ORDER BY n DESC) AS b,"
          + " array_agg(DISTINCT f.n ORDER BY n NULLS FIRST) AS c FROM f",
      "SELECT jsonb_agg(DISTINCT name) AS a, jsonb_agg(DISTINCT n ORDER BY n) AS b FROM f",
      "SELECT string_agg(DISTINCT name, ',' ORDER BY name DESC) AS a, count(DISTINCT name ORDER BY name) AS b,"
          + " sum(DISTINCT n ORDER BY n DESC) AS c FROM f",
      // FILTER
      "SELECT count(*) AS total, count(*) FILTER (WHERE n > 10) AS big, count(n) FILTER (WHERE name = 'zz') AS none,"
          + " sum(n) FILTER (WHERE ok) AS s, max(name) FILTER (WHERE id < 3) AS m, avg(n) FILTER (WHERE id <> 2) AS a FROM f",
      "SELECT name, count(*) FILTER (WHERE ok) AS yes, count(*) FILTER (WHERE NOT ok) AS no,"
          + " array_agg(id ORDER BY id) FILTER (WHERE n IS NOT NULL) AS ids FROM f GROUP BY name ORDER BY name",
      "SELECT count(*) FILTER (WHERE jsonb_exists(meta, 'b')) AS with_b, count(*) AS total FROM f",
      "SELECT sum(100 / n) FILTER (WHERE n <> 0) AS a, count(DISTINCT name) FILTER (WHERE id > 1) AS b,"
          + " string_agg(name, ',' ORDER BY id) FILTER (WHERE id > 1) AS c FROM f",
      "SELECT f.name, jsonb_agg(g.id ORDER BY g.id) FILTER (WHERE g.id IS NOT NULL) AS ids FROM f"
          + " LEFT JOIN g ON g.f_id = f.id GROUP BY f.name ORDER BY f.name",
      "SELECT f.name, coalesce(array_agg(g.id ORDER BY g.id) FILTER (WHERE g.id IS NOT NULL), '{}') AS ids FROM f"
          + " LEFT JOIN g ON g.f_id = f.id GROUP BY f.name ORDER BY f.name",
      "SELECT name, count(*) AS n FROM f GROUP BY name HAVING count(*) FILTER (WHERE ok) > 1 ORDER BY name",
      "SELECT name FROM f GROUP BY name ORDER BY count(*) FILTER (WHERE ok) DESC, name",
      "SELECT id, count(*) FILTER (WHERE ok) OVER () AS a, sum(n) FILTER (WHERE id <> 1) OVER (ORDER BY id) AS b FROM f"
          + " ORDER BY id",
      // inside other queries
      "SELECT id, (SELECT array_agg(g.content ORDER BY g.id) FROM g WHERE g.f_id = f.id) AS facts FROM f ORDER BY id",
      "SELECT id, (SELECT count(*) FILTER (WHERE g.f_id = f.id) FROM g) AS a FROM f ORDER BY id",
      "SELECT id, (SELECT jsonb_agg(g.id ORDER BY g.id) FILTER (WHERE g.content <> f.name) FROM g) AS a FROM f ORDER BY id",
      "WITH s AS (SELECT array_agg(name ORDER BY id) AS names FROM f) SELECT names FROM s",
      "SELECT x.names FROM (SELECT name, array_agg(id ORDER BY id) AS names FROM f GROUP BY name) x ORDER BY x.name",
      "SELECT CASE WHEN count(*) > 9 THEN array_agg(id ORDER BY id) ELSE '{7}' END AS a FROM f",
      "SELECT array_agg(name ORDER BY id) = ARRAY['ann', 'bob', 'cy', 'ann'] AS a, array_agg(id ORDER BY id) @> ARRAY[2] AS b"
          + " FROM f",
      "SELECT 'cy' = ANY(array_agg(name)) AS a FROM f");

  /** Statements that PostgreSQL refuses, with the SQLSTATE of both. */
  private static final List<String[]> ERRORS = List.of(
      new String[] { "SELECT array_agg(DISTINCT name ORDER BY id) FROM f", "42P10" },
      new String[] { "SELECT string_agg(DISTINCT name, ',' ORDER BY id) FROM f", "42P10" },
      new String[] { "SELECT upper(name ORDER BY id) FROM f", "42809" },
      new String[] { "SELECT upper(name) FILTER (WHERE true) FROM f", "42809" },
      new String[] { "SELECT count(*) FILTER (WHERE 1) FROM f", "42804" },
      new String[] { "SELECT count(*) FILTER (WHERE count(*) > 1) FROM f", "42803" },
      new String[] { "SELECT array_agg(count(*) ORDER BY 1) FROM f", "42803" },
      new String[] { "SELECT array_agg(name ORDER BY count(*)) FROM f", "42803" },
      new String[] { "SELECT count(*) FILTER (WHERE row_number() OVER () > 1) FROM f", "42P20" },
      new String[] { "SELECT id FROM f WHERE count(*) FILTER (WHERE ok) > 0", "42803" },
      new String[] { "SELECT array_agg(name ORDER BY id) OVER () FROM f", "0A000" },
      new String[] { "SELECT row_number() FILTER (WHERE true) OVER () FROM f", "0A000" },
      new String[] { "SELECT count(* ORDER BY id) FROM f", "42601" },
      new String[] { "SELECT count(*) FILTER (id > 1) FROM f", "42601" },
      new String[] { "SELECT array_agg(NULL) FROM f", "42725" },
      new String[] { "SELECT jsonb_agg('a') FROM f", "42804" },
      new String[] { "SELECT array_agg(name, n) FROM f", "42883" },
      new String[] { "SELECT array_agg(name ORDER BY nothing) FROM f", "42703" },
      new String[] { "SELECT name, array_agg(id ORDER BY id) FROM f", "42803" });

  @Test
  void orderByAndFilterInAggregatesAnswerAsPostgreSqlDoes() throws Exception {
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
      refused(arcade, "SELECT array_agg(meta ORDER BY id) FROM f", "Use jsonb_agg");
      refused(arcade, "SELECT array_agg(tags ORDER BY id) FROM f", "Use jsonb_agg");
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }
}
