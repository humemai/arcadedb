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
package com.arcadedb.query.postgresql;

import com.arcadedb.TestHelper;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code CREATE EXTENSION IF NOT EXISTS} of what is built in (fork issue #231): it runs and changes nothing. The comparison with
 * PostgreSQL 18 is {@code PostgreSQLCreateExtensionTest} of the {@code postgresw} module.
 */
class CreateExtensionTest extends TestHelper {
  @ParameterizedTest
  @ValueSource(strings = { "CREATE EXTENSION IF NOT EXISTS vector", "create extension if not exists VECTOR;",
      "CREATE EXTENSION IF NOT EXISTS \"vector\"", "CREATE EXTENSION IF NOT EXISTS pgcrypto",
      "CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public", "CREATE EXTENSION IF NOT EXISTS vector SCHEMA public",
      "CREATE EXTENSION IF NOT EXISTS vector WITH" })
  void aBuiltInExtensionRunsAndChangesNothing(final String statement) {
    final int types = database.getSchema().getTypes().size();

    for (int i = 0; i < 2; i++)
      try (final ResultSet answer = database.command("postgresql", statement)) {
        assertThat(answer.hasNext()).isFalse();
      }
    assertThat(database.getSchema().getTypes()).hasSize(types);
  }

  @Test
  void itRunsInATransactionBlockAndAsAQuery() {
    run("BEGIN");
    run("CREATE EXTENSION IF NOT EXISTS vector");
    run("COMMIT");
    database.query("postgresql", "CREATE EXTENSION IF NOT EXISTS vector").close();
  }

  @ParameterizedTest
  @CsvSource(delimiter = ';', quoteCharacter = '|', value = {
      "CREATE EXTENSION vector; 0A000",
      "CREATE EXTENSION IF NOT EXISTS \"Vector\"; 0A000",
      "CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"; 0A000",
      "CREATE EXTENSION IF NOT EXISTS plpgsql; 0A000",
      "CREATE EXTENSION IF NOT EXISTS vector VERSION '0.8.0'; 0A000",
      "CREATE EXTENSION IF NOT EXISTS vector CASCADE; 0A000",
      "CREATE EXTENSION IF NOT EXISTS vector SCHEMA extensions; 0A000",
      "CREATE EXTENSION; 42601",
      "CREATE EXTENSION IF NOT EXISTS; 42601",
      "EXPLAIN CREATE EXTENSION IF NOT EXISTS vector; 42601" })
  void everyOtherFormIsRefused(final String statement, final String sqlState) {
    assertThatThrownBy(() -> run(statement)).as(statement)
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(e.getMessage()).isEqualTo(sqlState));
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }
}
