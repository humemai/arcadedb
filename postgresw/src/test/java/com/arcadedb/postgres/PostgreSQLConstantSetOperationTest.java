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
 * Set operations whose arms read no table, against PostgreSQL 18 (fork issue #278), on the extended and on the simple query
 * protocol. The catalog emulation of the wire, which compares the text of a literal and passes over its cast, answers none of them
 * in the {@code postgresql} language.
 */
class PostgreSQLConstantSetOperationTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> STATEMENTS = List.of(
      // the two statements of the issue
      "SELECT '{\"a\": 1}'::jsonb AS v UNION SELECT '{\"a\": 1.0}'::jsonb",
      "SELECT count(*) AS n FROM (SELECT '2'::jsonb AS v UNION SELECT '2.0'::jsonb) AS u",
      "SELECT '{\"a\": 1}'::jsonb AS v INTERSECT SELECT '{\"a\": 1.0}'::jsonb",
      "SELECT '{\"a\": 1}'::jsonb AS v EXCEPT SELECT '{\"a\": 1.0}'::jsonb",
      "SELECT '2'::int AS v UNION SELECT '02'::int",
      "SELECT count(*) AS n FROM (SELECT '2'::int AS v UNION ALL SELECT '02'::int) AS u",
      "(SELECT '1.50'::numeric AS v) UNION (SELECT '1.5'::numeric)",
      // a literal that holds the name of the catalog does not make the statement one of the catalog
      "SELECT '{\"pg_class\": 1}'::jsonb AS v UNION SELECT '{\"pg_class\": 1.0}'::jsonb",
      "SELECT 'information_schema' AS v INTERSECT SELECT 'information_schema'",
      "SELECT 'pg_catalog' AS v EXCEPT SELECT 'pg_type'",
      // and neither does the schema of a type
      "SELECT '{\"a\": 1}'::pg_catalog.jsonb AS v UNION SELECT '{\"a\": 1.0}'::pg_catalog.jsonb",
      "SELECT CAST('2' AS pg_catalog.int4) AS v UNION SELECT CAST('02' AS pg_catalog.int4)");

  /** The {@code SQLTables(SQL_ALL_TABLE_TYPES)} of psqlODBC, verbatim: it names no relation, and its rows have no order. */
  private static final String TABLE_TYPES = "select NULL, NULL, relkind from (select 'r' as relkind union select 'v'"
      + " union select 'm' union select 'f' union select 'p') as a";

  /** Set operations that name the catalog, quoted or not: the catalog goes on answering them. */
  private static final List<String> OF_THE_CATALOG = List.of(
      "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname = 'public' UNION SELECT 'public'",
      "SELECT \"nspname\" FROM \"pg_namespace\" WHERE \"nspname\" = 'public' UNION SELECT 'public'");

  @Test
  void setOperationsOfConstantsAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      for (final String mode : List.of("", "?preferQueryMode=simple"))
        try (final Connection postgres = postgres(container, mode); final Connection arcade = arcade(mode)) {
          for (final String statement : STATEMENTS)
            same(postgres, arcade, statement);
          compare(TABLE_TYPES, sorted(answer(postgres, TABLE_TYPES, true)), sorted(answer(arcade, TABLE_TYPES, true)));
          for (final String statement : OF_THE_CATALOG)
            sameValues(postgres, arcade, statement);
        }
      assertThat(failures).as(String.join("\n", failures)).isEmpty();
    }
  }

  private static String sorted(final String answer) {
    return String.join("\n", answer.lines().sorted().toList()) + "\n";
  }
}
