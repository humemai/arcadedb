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
import com.arcadedb.database.RID;
import com.arcadedb.exception.CauseChain;
import com.arcadedb.exception.CommandExecutionException;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.exception.ErrorCategory;
import com.arcadedb.exception.NeedRetryException;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.plan.WriteErrors;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * How embedded code and every wire protocol that asks {@link ErrorCategory} see an error of the {@code postgresql} language: the
 * caller's error, with the SQLSTATE, and for a duplicate key, a concurrent update and a violated constraint the exception of the
 * engine in the cause chain.
 */
class PostgreSQLErrorCategoryTest extends TestHelper {

  @Override
  protected void beginTest() {
    database.transaction(() -> {
      final VertexType type = database.getSchema().createVertexType("person");
      type.createProperty("pid", Type.INTEGER);
      type.createProperty("name", Type.STRING).setNotNull(true);
      database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "person", "pid");
    });
    database.transaction(() -> database.command("postgresql", "INSERT INTO person (pid, name) VALUES (1, 'Ada')").close());
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
      SELECT 1 / 0                                          | 22012 | VALIDATION
      SELECT 2147483647 + 1                                 | 22003 | VALIDATION
      SELECT 'x'::integer                                   | 22P02 | VALIDATION
      INSERT INTO person (pid) VALUES (2)                   | 23502 | VALIDATION
      SELEC 1                                               | 42601 | PARSING
      SELECT * FROM pg_err_nothing                          | 42P01 | PARSING
      INSERT INTO person (pid, name) VALUES (1, 'Bob')      | 23505 | DUPLICATED_KEY
      """)
  void aStatementErrorIsTheCallersAndCarriesItsSqlState(final String statement, final String sqlState,
      final ErrorCategory category) {
    final Throwable error = catchThrowable(() -> database.command("postgresql", statement).stream().count());

    assertThat(PostgreSQLErrors.sqlState(error)).as(String.valueOf(error)).isEqualTo(sqlState);
    assertThat(ErrorCategory.of(error)).as(String.valueOf(error)).isEqualTo(category);
  }

  @Test
  void aWriteThroughTheReadOnlyEntryIsTheCallers() {
    final Throwable error = catchThrowable(() -> database.query("postgresql", "DELETE FROM person"));

    assertThat(PostgreSQLErrors.sqlState(error)).isEqualTo("25006");
    assertThat(ErrorCategory.of(error)).isEqualTo(ErrorCategory.VALIDATION);
  }

  @Test
  void aDuplicateKeyCarriesTheExceptionOfTheEngine() {
    final Throwable error = catchThrowable(
        () -> database.command("postgresql", "INSERT INTO person (pid, name) VALUES (1, 'Bob')"));

    assertThat(error).isInstanceOf(PostgreSQLEvaluationException.class);
    final DuplicatedKeyException duplicate = CauseChain.find(error, DuplicatedKeyException.class);
    assertThat(duplicate).isNotNull();
    assertThat(duplicate.getIndexName()).isEqualTo("person[pid]");
    assertThat(duplicate.getKeys()).isEqualTo("[1]");
    assertThat(duplicate.getCurrentIndexedRID()).isNotNull();
  }

  @Test
  void whatTheEngineRefusesAtACommitKeepsItsVerdict() {
    final Throwable duplicate = WriteErrors.translate(new DuplicatedKeyException("person[pid]", "[1]", new RID(1, 0)));
    assertThat(PostgreSQLErrors.sqlState(duplicate)).isEqualTo("23505");
    assertThat(ErrorCategory.of(duplicate)).isEqualTo(ErrorCategory.DUPLICATED_KEY);

    final Throwable conflict = WriteErrors.translate(new ConcurrentModificationException("the page changed"));
    assertThat(PostgreSQLErrors.sqlState(conflict)).isEqualTo("40001");
    assertThat(ErrorCategory.of(conflict)).isEqualTo(ErrorCategory.RETRY);
    assertThat(CauseChain.find(conflict, NeedRetryException.class)).isNotNull();

    final ValidationException refused = new ValidationException(ValidationException.Kind.MIN, "too small");
    final Throwable invalid = WriteErrors.translate(refused);
    assertThat(PostgreSQLErrors.sqlState(invalid)).isEqualTo("23514");
    assertThat(ErrorCategory.of(invalid)).isEqualTo(ErrorCategory.VALIDATION);
    assertThat(ErrorCategory.classifiedCause(invalid)).isSameAs(refused);
  }

  @Test
  void aRefusalForResourcesStaysAServerFault() {
    final PostgreSQLEvaluationException outOfMemory = new PostgreSQLEvaluationException("53200", "out of memory");

    assertThat(ErrorCategory.of(outOfMemory)).isEqualTo(ErrorCategory.SERVER);
    assertThat(ErrorCategory.classifiedCause(outOfMemory)).isNull();
  }

  @Test
  void aCommandExecutionExceptionOfAnotherLanguageKeepsItsVerdict() {
    assertThat(ErrorCategory.of(new CommandExecutionException("failed"))).isEqualTo(ErrorCategory.SERVER);
  }
}
