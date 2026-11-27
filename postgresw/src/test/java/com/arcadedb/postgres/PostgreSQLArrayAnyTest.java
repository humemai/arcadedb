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
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code x = ANY (array)}, {@code <> ALL}, and the other comparisons with {@code ANY}, {@code SOME}, and {@code ALL}, with arrays
 * that a statement writes, that a column holds, and that a driver binds ({@code id = ANY(?)}), against PostgreSQL 18 (fork issue
 * #233).
 */
class PostgreSQLArrayAnyTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE f (id int PRIMARY KEY, name text, tags text[], nums int[], big bigint)",
      "INSERT INTO f VALUES (1, 'ann', ARRAY['red', 'blue'], ARRAY[1, 2], 10), (2, 'bob', '{blue}', '{3}', 20),"
          + " (3, 'cy', '{}', NULL, NULL), (4, 'di', NULL, '{}', 40)");

  private static final List<String> QUERIES = List.of(
      "SELECT id FROM f WHERE id = ANY(ARRAY[1, 2]) ORDER BY id",
      "SELECT id FROM f WHERE id = ANY('{2,3}') ORDER BY id",
      "SELECT id FROM f WHERE id = ANY('{2,3}'::int[]) ORDER BY id",
      "SELECT id FROM f WHERE id = SOME (ARRAY[3]) ORDER BY id",
      "SELECT id FROM f WHERE id <> ALL(ARRAY[1, 2]) ORDER BY id",
      "SELECT id FROM f WHERE NOT (id = ANY('{1}'::int[])) ORDER BY id",
      "SELECT id FROM f WHERE 'red' = ANY(tags) ORDER BY id",
      "SELECT id FROM f WHERE name = ANY(ARRAY['ann', 'cy']) ORDER BY id",
      "SELECT id FROM f WHERE big = ANY(ARRAY[10, 40]) ORDER BY id",
      "SELECT id FROM f WHERE id = ANY(nums) ORDER BY id",
      "SELECT id, 'blue' = ANY(tags) AS a, 'blue' <> ALL(tags) AS b, name <> ANY(tags) AS c FROM f ORDER BY id",
      "SELECT id, 2 = ANY(nums) AS a, 3 > ALL(nums) AS b, 1 < ANY(nums) AS c, id >= ALL(nums) AS d, id <= ANY(nums) AS e"
          + " FROM f ORDER BY id",
      "SELECT 1 = ANY(ARRAY[1, NULL]) AS a, 2 = ANY(ARRAY[1, NULL]) AS b, 1 = ALL(ARRAY[1, NULL]) AS c,"
          + " 2 = ALL(ARRAY[1, NULL]) AS d, NULL = ANY(ARRAY[1]) AS e, NULL = ANY('{}'::int[]) AS f,"
          + " NULL = ALL('{}'::int[]) AS g, 1 = ANY(NULL) AS h",
      "SELECT 'a' = ANY('{a,b}') AS a, '2' = ANY(ARRAY[1, 2]) AS b, 2.5 = ANY(ARRAY[1, 2]) AS c, 2 = ANY(ARRAY[2.0]) AS d,"
          + " NULL <> ALL(ARRAY[1]) AS e, 5 <> ALL(ARRAY[1, NULL]) AS f, 1 <> ALL(ARRAY[1, NULL]) AS g",
      "SELECT date '2026-01-02' = ANY('{2026-01-01,2026-01-02}') AS a, true = ANY(ARRAY[false]) AS b",
      "SELECT id FROM f WHERE id = ANY(SELECT id FROM f WHERE id > 2) ORDER BY id",
      "SELECT id FROM f WHERE id <> ALL(SELECT id FROM f WHERE id > 2) ORDER BY id",
      "SELECT a.id, b.id FROM f a JOIN f b ON b.id = ANY(a.nums) ORDER BY a.id, b.id",
      "SELECT count(*) AS n FROM f WHERE id = ANY(ARRAY[id])");

  /** An array that the driver binds with {@code setArray}. */
  private record Bound(String type, Object... items) {
  }

  /** A statement with parameters and the values that are bound to them. */
  private record Call(String statement, Object... values) {
  }

  private static final List<Call> CALLS = List.of(
      new Call("SELECT name FROM f WHERE id = ANY(?) ORDER BY name", new Bound("int4", 1, 2)),
      new Call("SELECT name FROM f WHERE id = ANY(?) ORDER BY name", new Bound("int8", 2L, 4L)),
      new Call("SELECT name FROM f WHERE id = ANY(?) ORDER BY name", new Bound("int4")),
      new Call("SELECT name FROM f WHERE id <> ALL(?) ORDER BY name", new Bound("int4", 1, 2)),
      new Call("SELECT name FROM f WHERE id <> ALL(?) ORDER BY name", new Bound("int4")),
      new Call("SELECT name FROM f WHERE big = ANY(?) ORDER BY name", new Bound("int8", 20L, null)),
      new Call("SELECT name FROM f WHERE name = ANY(?) ORDER BY name", new Bound("text", "ann", "bob")),
      new Call("SELECT name FROM f WHERE name = ANY(?) ORDER BY name", new Bound("varchar", "cy")),
      new Call("SELECT name FROM f WHERE name = ANY(?) ORDER BY name", new Bound("text")),
      new Call("SELECT name FROM f WHERE id = ANY(?::int[]) ORDER BY name", new Bound("int4", 3)),
      new Call("SELECT name FROM f WHERE id = ANY(?) ORDER BY name", new Object[] { null }),
      new Call("SELECT name FROM f WHERE ? = ANY(tags) ORDER BY name", "red"),
      new Call("SELECT name FROM f WHERE ? = ANY(nums) ORDER BY name", 3),
      new Call("SELECT name FROM f WHERE id = ANY(?) AND name = ANY(?) ORDER BY name", new Bound("int4", 1, 2),
          new Bound("text", "bob")),
      // an array of another type is no operator, as in PostgreSQL, also when it has no element that says its type
      new Call("SELECT name FROM f WHERE id = ANY(?) ORDER BY name", new Bound("text", "1")),
      new Call("SELECT name FROM f WHERE id = ANY(?) ORDER BY name", new Bound("text")),
      new Call("SELECT name FROM f WHERE name <> ALL(?) ORDER BY name", new Bound("int4")),
      new Call("SELECT name FROM f WHERE id = ANY(?) ORDER BY name", new Bound("text", (Object) null)));

  /** Python lists, which psycopg binds as arrays, in the text and in the binary format. */
  private static final String SCRIPT = """
      import sys
      import psycopg

      calls = [
          ("SELECT name FROM f WHERE id = ANY(%s) ORDER BY name", ([1, 2],)),
          ("SELECT name FROM f WHERE id = ANY(%s) ORDER BY name", ([],)),
          ("SELECT name FROM f WHERE big = ANY(%s) ORDER BY name", ([20, 3000000000],)),
          ("SELECT name FROM f WHERE name = ANY(%s) ORDER BY name", (["ann", "di"],)),
          ("SELECT name FROM f WHERE id <> ALL(%s) ORDER BY name", ([1],)),
          ("SELECT id FROM f WHERE %s = ANY(tags) ORDER BY id", ("blue",)),
      ]
      with psycopg.connect(sys.argv[1], autocommit=True) as connection:
          for statement, values in calls:
              for binary in (False, True):
                  with connection.cursor(binary=binary) as cursor:
                      cursor.execute(statement, values)
                      print(cursor.fetchall())
          with connection.cursor() as cursor:
              for ids in ([1], [2, 3], [4], [1, 4], [], [2], [3]):
                  cursor.execute("SELECT name FROM f WHERE id = ANY(%b) ORDER BY name", (ids,), prepare=True)
                  print(cursor.fetchall())
      """;

  @Test
  void comparisonsWithAnyAndAllAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres()) {
      try (final Connection postgres = postgres(server, ""); final Connection arcade = arcade("")) {
        for (final String statement : SETUP)
          both(postgres, arcade, statement);
        for (final String query : QUERIES)
          same(postgres, arcade, query);

        sameError(postgres, arcade, "SELECT id FROM f WHERE id = ANY(name)", "42809");
        sameError(postgres, arcade, "SELECT id FROM f WHERE id = ANY(5)", "42809");
        sameError(postgres, arcade, "SELECT id FROM f WHERE name = ANY(ARRAY[1])", "42883");
        sameError(postgres, arcade, "SELECT id FROM f WHERE id = ANY(ARRAY['a'])", "42883");
        sameError(postgres, arcade, "SELECT id FROM f WHERE id = ANY('{a}')", "22P02");
        sameError(postgres, arcade, "SELECT id FROM f WHERE id = ANY(1, 2)", "42601");
        refused(arcade, "SELECT id FROM f WHERE id < ANY(SELECT id FROM f)", "EXISTS");

        both(postgres, arcade, "UPDATE f SET big = 11 WHERE id = ANY(ARRAY[1, 3])");
        both(postgres, arcade, "DELETE FROM f WHERE id = ANY('{4}') AND name = 'nobody'");
        same(postgres, arcade, "SELECT id, big FROM f ORDER BY id");
      }
      // as text, and with every statement prepared on the server and its values in the binary format
      for (final String parameters : List.of("", "?prepareThreshold=-1"))
        try (final Connection postgres = postgres(server, parameters); final Connection arcade = arcade(parameters)) {
          for (final Call call : CALLS)
            compare(call.statement() + " " + parameters, bound(postgres, call), bound(arcade, call));
          for (final String statement : List.of("SELECT name FROM f WHERE id = ANY(?)", "SELECT name FROM f WHERE big <> ALL(?)",
              "SELECT name FROM f WHERE name = ANY(?)"))
            compare("the parameter of " + statement, parameterType(postgres, statement), parameterType(arcade, statement));
        }
      python(server, SCRIPT);
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private static String bound(final Connection connection, final Call call) {
    try (final PreparedStatement statement = connection.prepareStatement(call.statement())) {
      for (int i = 0; i < call.values().length; i++) {
        final Object value = call.values()[i];
        if (value instanceof Bound array)
          statement.setArray(i + 1, connection.createArrayOf(array.type(), array.items()));
        else if (value == null)
          statement.setNull(i + 1, Types.ARRAY);
        else
          statement.setObject(i + 1, value);
      }
      try (final ResultSet rows = statement.executeQuery()) {
        return text(rows, false);
      }
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + "\n";
    }
  }

  private static String parameterType(final Connection connection, final String query) {
    try (final PreparedStatement statement = connection.prepareStatement(query)) {
      return "    " + statement.getParameterMetaData().getParameterTypeName(1) + "\n";
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }
}
