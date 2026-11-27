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
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two answers of the catalog side of a {@code postgresql} connection, compared with PostgreSQL 18 over the PostgreSQL protocol:
 * {@code SHOW search_path} answers the path of the session, and a cast to {@code regclass} of a name that no relation has is an
 * error, not an empty result.
 */
class PostgreSQLSearchPathAndRegclassTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> PATHS = List.of("SET search_path TO public", "SET search_path = 'public'",
      "SET search_path TO app, public", "SET search_path TO App , PUBLIC", "SET search_path TO \"$user\", public",
      "SET search_path TO \"Mixed\", public", "SET search_path = 'app, public'", "SET SESSION search_path = public;",
      "SET search_path TO DEFAULT", "SET SCHEMA 'public'", "RESET search_path");

  /** Each reads a table that exists, so the two servers give the same rows. */
  private static final List<String> FOUND = List.of(
      "SELECT attname FROM pg_catalog.pg_attribute WHERE attrelid = 'person'::regclass AND attnum > 0 AND attname <> 'rid'"
          + " ORDER BY attname",
      "SELECT attname FROM pg_attribute WHERE attrelid = 'public.person'::pg_catalog.regclass AND attname = 'name'",
      "SELECT attname FROM pg_attribute WHERE attrelid = CAST('PERSON' AS regclass) AND attname = 'id'",
      "SELECT relname FROM pg_class WHERE oid = 'person'::regclass",
      "SELECT relname FROM pg_class WHERE oid = to_regclass('nosuch')",
      "SELECT indisprimary FROM pg_index WHERE indexrelid = 'person_pkey'::regclass",
      "SELECT conname FROM pg_constraint WHERE conrelid = 'person'::regclass AND contype = 'p'");

  /** Each names a relation that neither server has. */
  private static final List<String> MISSING = List.of(
      "SELECT conname, contype, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'order_items'::regclass"
          + " ORDER BY conname",
      "SELECT attname FROM pg_catalog.pg_attribute WHERE attrelid = 'public.nosuch'::regclass",
      "SELECT attname FROM pg_attribute WHERE attrelid = CAST('nosuch' AS regclass)",
      "SELECT relname FROM pg_class WHERE oid = 'other.person'::regclass",
      "SELECT relname FROM pg_class WHERE oid = 'nosuch_pkey'::regclass",
      "SELECT attname FROM pg_attribute WHERE attrelid = '\"Person\"'::regclass");

  @Test
  void theSearchPathAndRegclassAnswerAsPostgreSQL() throws Exception {
    try (final GenericContainer<?> container = startPostgres();
        final Connection postgres = postgres(container, "");
        final Connection arcade = arcade("")) {
      same(postgres, arcade, "SHOW search_path");
      for (final String path : PATHS) {
        both(postgres, arcade, path);
        same(postgres, arcade, "SHOW search_path");
      }
      // SET LOCAL lasts until the transaction ends
      for (final String statement : List.of("BEGIN", "SET LOCAL search_path TO app, public"))
        both(postgres, arcade, statement);
      same(postgres, arcade, "SHOW search_path");
      both(postgres, arcade, "ROLLBACK");
      same(postgres, arcade, "SHOW search_path");

      both(postgres, arcade, "CREATE TABLE person (id bigint PRIMARY KEY, name text)");
      for (final String query : FOUND)
        sameValues(postgres, arcade, query);
      for (final String query : MISSING)
        sameError(postgres, arcade, query, "42P01");
      // a bound name is read when it arrives
      final String bound = "SELECT relname FROM pg_class WHERE oid = ?::regclass";
      assertThat(bound(arcade, bound, "nosuch")).isEqualTo(bound(postgres, bound, "nosuch")).isEqualTo("42P01");
      assertThat(bound(arcade, bound, "person")).isEqualTo(bound(postgres, bound, "person")).isEqualTo("00000");
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }
  }

  private static String bound(final Connection connection, final String statement, final String value) {
    try (final PreparedStatement prepared = connection.prepareStatement(statement)) {
      prepared.setString(1, value);
      prepared.executeQuery().close();
      return "00000";
    } catch (final SQLException e) {
      return e.getSQLState();
    }
  }
}
