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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CREATE EXTENSION IF NOT EXISTS} of the {@code postgresql} language against PostgreSQL 18 with pgvector (fork issue
 * #231), over JDBC and with psycopg on its default connection.
 */
class PostgreSQLCreateExtensionTest extends PostgreSQLSideBySideTestBase {
  private static final String SCRIPT = """
      import sys
      import psycopg

      with psycopg.connect(sys.argv[1]) as conn:
          cursor = conn.cursor()
          for extension in ("vector", "pgcrypto"):
              cursor.execute("CREATE EXTENSION IF NOT EXISTS " + extension)
              print(cursor.statusmessage)
          cursor.execute("CREATE EXTENSION IF NOT EXISTS vector", prepare=True)
          print(cursor.statusmessage)
          cursor.execute("SELECT 1")
          print(cursor.fetchone()[0])
          conn.commit()
      """;

  @Test
  void theExtensionsOfASchemaScriptRunAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        // twice: the second time the extension is there on both servers
        for (int i = 0; i < 2; i++)
          for (final String statement : new String[] { "CREATE EXTENSION IF NOT EXISTS vector",
              "CREATE EXTENSION IF NOT EXISTS pgcrypto;", "create extension if not exists VECTOR with schema public" })
            both(postgres, arcade, statement);
        sameError(postgres, arcade, "CREATE EXTENSION IF NOT EXISTS", "42601");
        // PostgreSQL fails these two for an extension that is there, or one that it does not have: the language refuses them
        refused(arcade, "CREATE EXTENSION vector", "CREATE EXTENSION without IF NOT EXISTS is not supported");
        refused(arcade, "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"", "CREATE EXTENSION uuid-ossp is not supported");
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
      python(container, SCRIPT);
    }
  }
}
