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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.testcontainers.containers.GenericContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading and filtering inside {@code jsonb} values (fork issue #232), and the equality of {@code jsonb} values, also for
 * {@code DISTINCT} and {@code GROUP BY} (fork issue #189), against PostgreSQL 18. A {@code jsonb} column of an answer is
 * compared as the JSON it holds; a text made from it is compared character by character.
 */
class PostgreSQLJsonbTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE f (id int PRIMARY KEY, meta jsonb)",
      "INSERT INTO f VALUES (1, '{\"s\": \"5\", \"a\": 1, \"b\": {\"c\": \"x\", \"d\": [1, 2.5, \"q\", null, true]},"
          + " \"l\": [\"p\", \"q\"], \"t\": true, \"n\": null, \"long key\": \"é \\\"quoted\\\" \\\\ \\n\"}')",
      "INSERT INTO f VALUES (2, '{\"a\": 2, \"b\": {\"c\": \"y\"}, \"l\": [], \"t\": false}'),"
          + " (3, '{\"a\": 1.0, \"b\": 5, \"l\": [1, [2, 3], {\"k\": \"v\"}]}'), (4, NULL),"
          + " (5, '{\"b\": {\"c\": \"x\"}, \"a\": 1}'), (6, '{\"a\": 1, \"b\": {\"c\": \"x\"}}'), (7, '{}')",
      "CREATE TABLE t (id int PRIMARY KEY, j jsonb)",
      "INSERT INTO t VALUES (1, '{\"a\": 1}'), (2, '{\"a\": 1.0}')");

  private static final List<String> QUERIES = List.of(
      // reading
      "SELECT id, meta->'a', meta->>'a', meta->'b'->>'c', meta->'b'->'c' FROM f ORDER BY id",
      "SELECT id, meta->'l'->0, meta->'l'->>-1, meta->'l'->9, meta->'b'->'d'->1, meta->'l'->'0', meta->0 FROM f ORDER BY id",
      "SELECT id, meta #> '{b,d,1}', meta #>> '{b,c}', meta #>> '{l,-1}', meta #>> '{b,d,3}', meta #> ARRAY['b', 'd', 'x'],"
          + " meta #> '{b,NULL}', meta #> '{}' IS NULL AS e FROM f ORDER BY id",
      "SELECT id, jsonb_extract_path_text(meta, 'a'), jsonb_extract_path(meta, 'b', 'c') FROM f ORDER BY id",
      "SELECT id, jsonb_typeof(meta) AS o, jsonb_typeof(meta->'a') AS a, jsonb_typeof(meta->'l') AS l,"
          + " jsonb_typeof(meta->'n') AS n, jsonb_typeof(meta->'t') AS t, jsonb_typeof(meta->'s') AS s FROM f ORDER BY id",
      "SELECT id, jsonb_array_length(meta->'l') FROM f ORDER BY id",
      "SELECT id, jsonb_exists(meta, 'a'), jsonb_exists_any(meta, ARRAY['x', 't']), jsonb_exists_all(meta, '{a,t}'),"
          + " jsonb_exists(meta->'l', 'p') AS e, jsonb_exists(meta->'s', '5') AS f FROM f ORDER BY id",
      // filtering
      "SELECT id FROM f WHERE meta->>'a' = '1' ORDER BY id",
      "SELECT id FROM f WHERE meta->'b'->>'c' IN ('x', 'z') ORDER BY id",
      "SELECT id FROM f WHERE (meta->>'a')::numeric > 1 ORDER BY id",
      "SELECT id FROM f WHERE meta @> '{\"a\": 1}' ORDER BY id",
      "SELECT id FROM f WHERE meta @> '{\"b\": {\"c\": \"x\"}}' ORDER BY id",
      "SELECT id FROM f WHERE meta @> '{\"l\": [\"q\"]}'::jsonb ORDER BY id",
      "SELECT id FROM f WHERE meta->'l' @> '\"p\"' ORDER BY id",
      "SELECT id FROM f WHERE '{\"a\": 2, \"b\": {\"c\": \"y\"}, \"l\": [], \"t\": false, \"z\": 1}'::jsonb @> meta ORDER BY id",
      "SELECT id FROM f WHERE meta <@ '{\"a\": 1, \"b\": {\"c\": \"x\"}, \"q\": 0}' ORDER BY id",
      "SELECT id FROM f WHERE meta = '{\"a\": 1, \"b\": {\"c\": \"x\"}}' ORDER BY id",
      "SELECT id FROM f WHERE meta <> '{}' ORDER BY id",
      "SELECT id FROM f WHERE meta->'a' = '1' ORDER BY id",
      "SELECT id FROM f WHERE meta->'a' <> '1' ORDER BY id",
      "SELECT id FROM f WHERE meta->'b' = '{\"c\": \"x\"}'::jsonb ORDER BY id",
      "SELECT id FROM f WHERE meta->'t' = 'true' ORDER BY id",
      "SELECT id FROM f WHERE (meta->'t')::boolean ORDER BY id",
      "SELECT id FROM f WHERE meta->'n' = 'null' ORDER BY id",
      "SELECT id FROM f WHERE meta->'n' IS NULL ORDER BY id",
      "SELECT id FROM f WHERE meta->>'n' IS NULL ORDER BY id",
      "SELECT id, meta->'a' IN ('1', '3') AS i, meta->'a' IS DISTINCT FROM '2' AS d FROM f ORDER BY id",
      "SELECT id FROM f WHERE meta->'a' IN (SELECT meta->'a' FROM f WHERE id = 5) ORDER BY id",
      "SELECT a.id, b.id FROM f a JOIN f b ON a.meta = b.meta AND a.id < b.id ORDER BY a.id, b.id",
      "SELECT a.id, b.id FROM f a JOIN f b ON a.meta->'a' = b.meta->'a' AND a.id < b.id ORDER BY a.id, b.id",
      // casts and text
      "SELECT id, meta::text, meta->>'b' AS b, meta #>> '{b,d}' AS d, meta->>'long key' AS k FROM f ORDER BY id",
      "SELECT id, (meta->'a')::int AS i, (meta->'a')::bigint AS b, (meta->'a')::numeric AS n, (meta->'a')::float8 AS f,"
          + " (meta->'t')::boolean AS t, (meta->'n')::int AS z FROM f ORDER BY id",
      "SELECT id, (meta->>'s')::int + 1 AS s, concat(meta->'b', '|', meta->>'s') AS c, 'x' || (meta->>'a') AS x FROM f ORDER BY id",
      "SELECT id, COALESCE(meta->'zz', '\"none\"') AS c, CASE meta->'a' WHEN '1' THEN 'one' ELSE 'other' END AS w FROM f ORDER BY id",
      "SELECT '\"x\"'::jsonb AS s, '5'::jsonb AS n, 'true'::jsonb AS b, 'null'::jsonb AS z, '[1, \"a\", {}]'::jsonb AS l,"
          + " '\"x\"'::jsonb ->> 0 AS e, '5'::jsonb -> 0 AS f, '5'::jsonb -> 'a' AS g, '5'::jsonb #>> '{}' AS h",
      "SELECT '{\"bb\": 1, \"a\": [], \"c\": {}, \"é\": \"\\u0001\"}'::jsonb::text AS t",
      // equality and containment of constants
      "SELECT '{\"a\": 1}'::jsonb = '{\"a\": 1.00}' AS a, '[1, 2]'::jsonb <> '[2, 1]' AS b,"
          + " 'null'::jsonb IS NOT DISTINCT FROM NULL AS c, '1'::jsonb = '\"1\"' AS d, '[1, [2]]'::jsonb @> '[[2]]' AS e,"
          + " '[1, [2]]'::jsonb @> '[2]' AS f, '{\"a\": [1, 2]}'::jsonb @> '{\"a\": 1}' AS g, '1'::jsonb @> '[1]' AS h,"
          + " '[1]'::jsonb @> '1' AS i, '[{\"a\": 1, \"b\": 2}]'::jsonb @> '[{\"b\": 2}]' AS j, '[]'::jsonb <@ '[1]' AS k,"
          + " '{\"a\": null}'::jsonb @> '{\"a\": null}' AS l, '[1, 2]'::jsonb @> '[2, 2, 1]' AS m, '{}'::jsonb @> '[]' AS n",
      // DISTINCT and GROUP BY (fork issue #189)
      "SELECT count(DISTINCT j) FROM t",
      "SELECT count(*) AS n FROM (SELECT DISTINCT j FROM t) AS d",
      "SELECT count(*) AS n FROM t GROUP BY j",
      "SELECT count(DISTINCT meta) AS m, count(DISTINCT meta->'a') AS a, count(meta->'n') AS n FROM f",
      "SELECT meta->'a' AS a, count(*) AS n FROM f GROUP BY meta->'a' ORDER BY 2 DESC",
      "SELECT count(*) AS n FROM f GROUP BY meta ORDER BY 1",
      // set operations that read a table: over the wire, one of constants alone does not reach the language (fork issue #278)
      "SELECT count(*) AS n FROM (SELECT j FROM t WHERE id = 1 UNION SELECT j FROM t WHERE id = 2) AS u",
      "SELECT count(*) AS n FROM (SELECT j->'a' AS a FROM t WHERE id = 1 UNION SELECT j->'a' FROM t WHERE id = 2) AS u",
      "SELECT count(*) AS n FROM (SELECT j FROM t EXCEPT SELECT '{\"a\": 1.00}'::jsonb) AS u",
      "SELECT count(*) AS n FROM (SELECT j FROM t INTERSECT SELECT '{\"a\": 1.000}'::jsonb) AS u",
      "SELECT id, count(*) OVER (PARTITION BY meta->'a') AS n FROM f ORDER BY id");

  @Test
  void jsonbValuesAreReadAndComparedAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres()) {
      try (final Connection postgres = postgres(server, ""); final Connection arcade = arcade("")) {
        for (final String statement : SETUP)
          both(postgres, arcade, statement);
        for (final String query : QUERIES)
          sameJson(postgres, arcade, query);
        sameError(postgres, arcade, "SELECT (meta->'s')::int FROM f WHERE id = 1", "22023");
        sameError(postgres, arcade, "SELECT (meta->'a')::boolean FROM f WHERE id = 1", "22023");
        sameError(postgres, arcade, "SELECT (meta->'b')::int FROM f WHERE id = 1", "22023");
        sameError(postgres, arcade, "SELECT jsonb_array_length(meta) FROM f WHERE id = 1", "22023");
        sameError(postgres, arcade, "SELECT jsonb_array_length(meta->'a') FROM f WHERE id = 1", "22023");
        sameError(postgres, arcade, "SELECT id FROM f WHERE meta->'a' = 1", "42883");
        sameError(postgres, arcade, "SELECT id FROM f WHERE meta->>'a' = 1", "42883");
        sameError(postgres, arcade, "SELECT id FROM f WHERE meta @> 'x'::text", "42883");
        sameError(postgres, arcade, "SELECT meta->>1.5 FROM f", "42883");
        sameError(postgres, arcade, "SELECT meta->'a'->>true FROM f", "42883");
        sameError(postgres, arcade, "SELECT id->'a' FROM f", "42883");
        sameError(postgres, arcade, "SELECT '{\"a\":}'::jsonb", "22P02");
        sameError(postgres, arcade, "SELECT id FROM f WHERE meta @> '{a}'", "22P02");
        sameError(postgres, arcade, "SELECT (meta->'a')::date FROM f", "42846");
        refused(arcade, "SELECT id FROM f ORDER BY meta->'a'", "ORDER BY a value of type jsonb");
        refused(arcade, "SELECT id FROM f WHERE meta->'a' > '1'", "not supported for type jsonb");
        refused(arcade, "INSERT INTO f VALUES (9, '5')", "A JSON scalar is not supported");
        refused(arcade, "INSERT INTO f (id, meta) SELECT 9, meta->'a' FROM f WHERE id = 1", "A JSON scalar is not supported");
        // what a statement reads out of a value it can write back
        both(postgres, arcade, "UPDATE f SET meta = meta->'b' WHERE id = 6");
        both(postgres, arcade, "INSERT INTO f (id, meta) SELECT 8, meta FROM f WHERE meta->'b'->>'c' = 'y'");
        sameJson(postgres, arcade, "SELECT id, meta FROM f ORDER BY id");
      }
      // parameters, in the text and in the binary format
      for (final String parameters : List.of("", "?prepareThreshold=-1"))
        try (final Connection postgres = postgres(server, parameters); final Connection arcade = arcade(parameters)) {
          for (final Object[] bound : List.of(
              new Object[] { "SELECT id FROM f WHERE meta @> ? ORDER BY id", jsonb("{\"a\": 1}") },
              new Object[] { "SELECT id FROM f WHERE meta @> ?::jsonb ORDER BY id", "{\"a\": 2}" },
              new Object[] { "SELECT id FROM f WHERE meta->'a' = ? ORDER BY id", jsonb("1") },
              new Object[] { "SELECT id FROM f WHERE meta->'s' = ? ORDER BY id", jsonb("\"5\"") },
              new Object[] { "SELECT id FROM f WHERE meta->'n' = ? ORDER BY id", jsonb("null") },
              new Object[] { "SELECT id FROM f WHERE meta->'l' = ? ORDER BY id", jsonb("[\"p\", \"q\"]") },
              new Object[] { "SELECT id FROM f WHERE meta->>? = '1' ORDER BY id", "a" },
              new Object[] { "SELECT id, meta->'l'->>? AS v FROM f ORDER BY id", 0 },
              new Object[] { "SELECT id, ?::jsonb AS v FROM f WHERE id = 1", "[true, \"é\"]" }))
            compare(bound[0] + " " + bound[1] + parameters, bound(postgres, (String) bound[0], bound[1]),
                bound(arcade, (String) bound[0], bound[1]));
        }
      python(server, """
          import json, sys
          import psycopg
          from psycopg.types.json import Jsonb

          with psycopg.connect(sys.argv[1], autocommit=True) as connection:
              connection.execute("SET TIME ZONE 'UTC'")
              for binary in (False, True):
                  cursor = connection.cursor(binary=binary)
                  cursor.execute("SELECT id, meta, meta->'a', meta->>'a', meta->'l', meta->'n', meta->'s' FROM f ORDER BY id")
                  for row in cursor.fetchall():
                      print(json.dumps(row, sort_keys=True))
                  for value in ({"a": 2}, {"l": ["q"]}):
                      cursor.execute("SELECT id FROM f WHERE meta @> %s ORDER BY id", [Jsonb(value)])
                      print(cursor.fetchall())
                  for value in (1, "5", True, [], None):
                      cursor.execute("SELECT id FROM f WHERE meta->'a' = %s OR meta->'s' = %s OR meta->'t' = %s"
                                     " OR meta->'l' = %s ORDER BY id", [Jsonb(value)] * 4)
                      print(value, cursor.fetchall())
          """);
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  /** The functions and operators that build and change a value, and the writes that use them. */
  private static final List<String> CHANGES = List.of(
      "SELECT id, meta || '{\"z\": 1, \"a\": \"new\"}'::jsonb AS m, meta->'l' || '[9]' AS l,"
          + " (meta->'a') || (meta->'b') AS ab FROM f ORDER BY id",
      "SELECT '[1]'::jsonb || '{\"a\": 1}' AS a, '1'::jsonb || '2' AS b, '{}'::jsonb || '1' AS c, '[]'::jsonb || '[]' AS d,"
          + " '{\"a\": 1}'::jsonb || '{}' AS e, '{\"a\": 1}'::jsonb || NULL AS f",
      // the minus binds closer than ->, so the value to take from is in parentheses
      "SELECT id, meta - 'a' AS k, meta - ARRAY['a', 'b'] AS ks, (meta->'l') - 0 AS i, (meta->'l') - -1 AS j,"
          + " (meta->'l') - 'p' AS s, meta #- '{b,c}' AS p, meta #- '{l,0}' AS q FROM f WHERE id < 4 ORDER BY id",
      "SELECT id, jsonb_set(meta, '{a}', '5') AS a, jsonb_set(meta, '{b,c}', '\"z\"') AS c, jsonb_set(meta, '{new}', '[1]') AS n,"
          + " jsonb_set(meta, '{new}', '1', false) AS m, jsonb_set(meta, ARRAY['l', '9'], '\"end\"') AS e,"
          + " jsonb_set(meta, '{l,-9}', '\"start\"') AS s, jsonb_set(meta, '{x,y}', '1') AS x FROM f WHERE id IN (1, 2, 6, 7)"
          + " ORDER BY id",
      "SELECT jsonb_set(meta, '{a}', NULL) AS a, jsonb_set(meta, '{a}', to_jsonb('text'::text)) AS b,"
          + " jsonb_set(meta, '{a}', to_jsonb(id)) AS c FROM f WHERE id = 1",
      "SELECT jsonb_strip_nulls('{\"a\": null, \"b\": {\"c\": null}, \"d\": [null, {\"e\": null}]}') AS s",
      "SELECT to_jsonb(5) AS i, to_jsonb(5.50) AS n, to_jsonb(1.5::float8) AS f, to_jsonb(100::float8) AS h,"
          + " to_jsonb('NaN'::float8) AS nan, to_jsonb(true) AS b, to_jsonb('a \"b\"'::text) AS t,"
          + " to_jsonb(date '2026-01-05') AS d, to_jsonb(timestamp '2026-01-05 10:11:12.5') AS ts,"
          + " to_jsonb(timestamptz '2026-01-05 10:11:12+02') AS tz, to_jsonb(ARRAY[1, NULL, 3]) AS arr,"
          + " to_jsonb(NULL::int) AS z",
      "SELECT to_jsonb(5.50)::text AS n, to_jsonb(timestamp '2026-01-05 10:11:12')::text AS ts",
      "SELECT jsonb_build_object('id', id, 'name', meta->>'s', 'meta', meta, 'l', meta->'l', 'none', NULL, 'half', 0.5,"
          + " 'id', id + 1) AS o, jsonb_build_object(id, true) AS k, jsonb_build_array(id, 'x', NULL, meta->'a', ARRAY['y']) AS a"
          + " FROM f ORDER BY id",
      "SELECT jsonb_build_object() AS o, jsonb_build_array() AS a, jsonb_build_object('d', date '2026-01-05') AS d",
      "SELECT jsonb_build_object('n', count(*), 'max', max(id)) AS o FROM f",
      "SELECT (jsonb_build_object('a', 1.50)->>'a') AS a, jsonb_build_array(2.5, 'é')::text AS b");

  private static final List<String> WRITES = List.of(
      "UPDATE f SET meta = jsonb_set(meta, '{a}', '7') WHERE id = 1",
      "UPDATE f SET meta = meta || '{\"verified\": true}'::jsonb WHERE id = 2",
      "UPDATE f SET meta = meta - 'l' WHERE id = 3",
      "UPDATE f SET meta = COALESCE(meta, '{}') || jsonb_build_object('n', id, 'price', 9.99) WHERE id = 4",
      "UPDATE f SET meta = meta #- '{b,c}' WHERE id = 5",
      "INSERT INTO f (id, meta) VALUES (10, jsonb_build_object('name', 'Ada', 'tags', jsonb_build_array('a', 'b')))",
      "UPDATE f SET meta = jsonb_strip_nulls(meta) WHERE id = 1");

  @Test
  void jsonbValuesAreBuiltAndChangedAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres()) {
      try (final Connection postgres = postgres(server, ""); final Connection arcade = arcade("")) {
        for (final String statement : SETUP)
          both(postgres, arcade, statement);
        for (final String query : CHANGES)
          sameJson(postgres, arcade, query);
        for (final String write : WRITES)
          both(postgres, arcade, write);
        sameJson(postgres, arcade, "SELECT id, meta FROM f ORDER BY id");
        sameJson(postgres, arcade, "SELECT id FROM f WHERE meta @> '{\"verified\": true}' OR meta->>'price' = '9.99' ORDER BY id");
        sameError(postgres, arcade, "SELECT '1'::jsonb - 'a'", "22023");
        sameError(postgres, arcade, "SELECT '{}'::jsonb - 0", "22023");
        sameError(postgres, arcade, "SELECT '1'::jsonb #- '{a}'", "22023");
        sameError(postgres, arcade, "SELECT jsonb_set('1', '{a}', '2')", "22023");
        sameError(postgres, arcade, "SELECT jsonb_set('[1]', '{a}', '2')", "22P02");
        sameError(postgres, arcade, "SELECT jsonb_set('{}', ARRAY[NULL]::text[], '2')", "22004");
        sameError(postgres, arcade, "SELECT jsonb_build_object('a')", "22023");
        sameError(postgres, arcade, "SELECT jsonb_build_object(NULL, 1)", "22023");
        sameError(postgres, arcade, "SELECT to_jsonb('a')", "42804");
        sameError(postgres, arcade, "SELECT meta - 1.5 FROM f", "42883");
        sameError(postgres, arcade, "SELECT jsonb_set(meta, '{a}', 5) FROM f", "42883");
        // an array is not stored into a jsonb column that this language declares, nor is a scalar
        refused(arcade, "UPDATE f SET meta = to_jsonb(id) WHERE id = 1", "A JSON scalar is not supported");
      }
      for (final String parameters : List.of("", "?prepareThreshold=-1"))
        try (final Connection postgres = postgres(server, parameters); final Connection arcade = arcade(parameters)) {
          for (final Object[] bound : List.of(
              new Object[] { "SELECT meta || ? AS v FROM f WHERE id = 2", jsonb("{\"p\": [1]}") },
              new Object[] { "SELECT jsonb_set(meta, '{a}', ?) AS v FROM f WHERE id = 2", jsonb("\"set\"") },
              new Object[] { "SELECT meta - ? AS v FROM f WHERE id = 2", "a" },
              new Object[] { "SELECT jsonb_build_object('k', ?::text) AS v", "w" },
              new Object[] { "SELECT to_jsonb(?::int) AS v", 3 }))
            compare(bound[0] + " " + bound[1] + parameters, bound(postgres, (String) bound[0], bound[1]),
                bound(arcade, (String) bound[0], bound[1]));
        }
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private static PGobject jsonb(final String text) throws SQLException {
    final PGobject value = new PGobject();
    value.setType("jsonb");
    value.setValue(text);
    return value;
  }

  private void sameJson(final Connection postgres, final Connection arcade, final String query) {
    compare(query, rows(postgres, query), rows(arcade, query));
  }

  private static String rows(final Connection connection, final String query) {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(query)) {
      return normalized(rows);
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }

  private static String bound(final Connection connection, final String query, final Object value) {
    try (final PreparedStatement statement = connection.prepareStatement(query)) {
      statement.setObject(1, value);
      try (final ResultSet rows = statement.executeQuery()) {
        return normalized(rows);
      }
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + "\n";
    }
  }

  /** The labels, the types and the rows of a result: a {@code jsonb} value as the JSON it holds, a number by its value. */
  private static String normalized(final ResultSet rows) throws SQLException {
    final ResultSetMetaData columns = rows.getMetaData();
    final StringBuilder out = new StringBuilder("    ");
    for (int i = 1; i <= columns.getColumnCount(); i++)
      out.append(columns.getColumnLabel(i)).append(':').append(columns.getColumnTypeName(i)).append('|');
    out.append('\n');
    while (rows.next()) {
      out.append("    ");
      for (int i = 1; i <= columns.getColumnCount(); i++) {
        String text = rows.getString(i);
        if (text != null) {
          if ("jsonb".equals(columns.getColumnTypeName(i)))
            text = canonical(JsonParser.parseString(text));
          else if (rows.getObject(i) instanceof Number number)
            text = new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        out.append(text == null ? "SQL NULL" : text).append('|');
      }
      out.append('\n');
    }
    return out.toString();
  }

  private static String canonical(final JsonElement element) {
    if (element.isJsonObject()) {
      final Map<String, String> members = new TreeMap<>();
      for (final Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet())
        members.put(member.getKey(), canonical(member.getValue()));
      return members.toString();
    }
    if (element.isJsonArray()) {
      final List<String> items = new ArrayList<>();
      for (final JsonElement item : element.getAsJsonArray())
        items.add(canonical(item));
      return items.toString();
    }
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber())
      return element.getAsBigDecimal().stripTrailingZeros().toPlainString();
    return element.toString();
  }
}
