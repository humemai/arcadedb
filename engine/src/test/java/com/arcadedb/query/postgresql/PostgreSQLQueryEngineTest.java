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
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.OperationType;
import com.arcadedb.query.QueryEngine;
import com.arcadedb.query.QueryEngineManager;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The skeleton of the {@code postgresql} language: it is registered under its name, {@code db.query("postgresql", "SELECT 1")}
 * reaches the engine, {@code analyze} reports a read for a {@code SELECT} and refuses everything else with SQLSTATE
 * {@code 0A000}, and the existing languages are not touched.
 */
class PostgreSQLQueryEngineTest extends TestHelper {

  @Test
  void theLanguageIsRegisteredUnderItsName() {
    final QueryEngineManager manager = QueryEngineManager.getInstance();

    assertThat(manager.getAvailableLanguages()).contains("postgresql");
    // matched the way getEngine() matches a language, case-insensitively
    assertThat(manager.isLanguageRegistered("PostgreSQL")).isTrue();
    assertThat(engine()).isInstanceOf(PostgreSQLQueryEngine.class);
    assertThat(engine().getLanguage()).isEqualTo("postgresql");
  }

  @ParameterizedTest
  @ValueSource(strings = { "SELECT 1", "select 1", "SELECT 1;", "  SELECT   1 ; ", "\nSelect\t1\n" })
  void selectOneReachesTheEngineThroughTheDatabase(final String statement) {
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      assertThat(resultSet.hasNext()).isTrue();
      final Result row = resultSet.next();
      // the name PostgreSQL gives a select list item with no name
      assertThat(row.<Integer>getProperty("?column?")).isEqualTo(1);
      assertThat(row.getPropertyNames()).containsExactly("?column?");
      assertThat(resultSet.hasNext()).isFalse();
    }
  }

  @Test
  void aCommandOfSelectOneAnswersAsAQueryDoes() {
    try (final ResultSet resultSet = database.command("postgresql", "SELECT 1")) {
      assertThat(resultSet.hasNext()).isTrue();
      assertThat(resultSet.next().<Integer>getProperty("?column?")).isEqualTo(1);
      assertThat(resultSet.hasNext()).isFalse();
    }
  }

  @Test
  void analyzeReportsAReadForASelect() {
    final QueryEngine.AnalyzedQuery analyzed = engine().analyze("SELECT p.name FROM person p JOIN orders o ON o.buyer = p.id");

    assertThat(analyzed.isIdempotent()).isTrue();
    assertThat(analyzed.isDDL()).isFalse();
    assertThat(analyzed.getOperationTypes()).containsExactly(OperationType.READ);
  }

  @ParameterizedTest
  @ValueSource(strings = { "INSERT INTO person VALUES (1)", "update person set age = 1", "DELETE FROM person",
      "CREATE TABLE t (a int)", "DROP TABLE person", "WITH x AS (SELECT 1) SELECT * FROM x", "(SELECT 1)", "", "   " })
  void analyzeRefusesEverythingButASelectWithFeatureNotSupported(final String statement) {
    assertThatThrownBy(() -> engine().analyze(statement)).as(statement)
        .isInstanceOfSatisfying(PostgreSQLFeatureNotSupportedException.class,
            e -> assertThat(e.getSqlState()).isEqualTo("0A000"));
  }

  // since the grammar exists these are what PostgreSQL says of them, a syntax error, and no longer a refusal of the statement kind
  @ParameterizedTest
  @ValueSource(strings = { "SELECTX 1", "SELECT1", "{sql}SELECT 1" })
  void analyzeAnswersTextThatIsNotPostgreSQLWithASyntaxError(final String statement) {
    assertThatThrownBy(() -> engine().analyze(statement)).as(statement)
        .isInstanceOfSatisfying(PostgreSQLSyntaxException.class, e -> assertThat(e.getSqlState()).isEqualTo("42601"));
  }

  @Test
  void theRefusalNamesTheWordItsPositionAndTheWayOut() {
    assertThatThrownBy(() -> engine().analyze("\n  INSERT INTO person VALUES (1)"))
        .isInstanceOf(PostgreSQLFeatureNotSupportedException.class)
        .hasMessageContaining("INSERT is not supported by the postgresql language")
        .hasMessageContaining("line 2, column 3")
        .hasMessageContaining("{sql}");
  }

  @Test
  void aWriteIsRefusedByQueryAndByCommand() {
    assertThatThrownBy(() -> database.query("postgresql", "DELETE FROM person"))
        .isInstanceOfSatisfying(PostgreSQLFeatureNotSupportedException.class,
            e -> assertThat(e.getSqlState()).isEqualTo("0A000"));
    assertThatThrownBy(() -> database.command("postgresql", "INSERT INTO person VALUES (1)"))
        .isInstanceOfSatisfying(PostgreSQLFeatureNotSupportedException.class,
            e -> assertThat(e.getSqlState()).isEqualTo("0A000"));
  }

  // REMOVE WHEN S1 lands: until the grammar exists a SELECT other than SELECT 1 is a read the engine cannot answer yet
  @ParameterizedTest
  @ValueSource(strings = { "SELECT 2", "SELECT name FROM person", "SELECT 1 + 1" })
  void aSelectOtherThanSelectOneIsNotSupportedYet(final String statement) {
    assertThatThrownBy(() -> database.query("postgresql", statement)).as(statement)
        .isInstanceOfSatisfying(PostgreSQLFeatureNotSupportedException.class,
            e -> assertThat(e.getSqlState()).isEqualTo("0A000"))
        .hasMessageContaining("line 1, column 1");
  }

  @Test
  void theExistingLanguagesAreNotTouched() {
    for (final String[] statement : new String[][] { { "sql", "SELECT 1 AS one" }, { "opencypher", "RETURN 1 AS one" } })
      try (final ResultSet resultSet = database.query(statement[0], statement[1])) {
        final Number one = resultSet.next().getProperty("one");
        assertThat(one.intValue()).as(statement[0]).isEqualTo(1);
      }
  }

  private QueryEngine engine() {
    return ((DatabaseInternal) database).getQueryEngine("postgresql");
  }
}
