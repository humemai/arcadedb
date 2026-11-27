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
package com.arcadedb.server.http.handler.postgresql;

import com.arcadedb.database.Database;
import com.arcadedb.exception.AlreadyExistsException;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import com.arcadedb.serializer.json.JSONObject;
import com.arcadedb.server.BaseGraphServerTest;
import com.arcadedb.utility.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An error of a statement of the {@code postgresql} language over HTTP is the caller's: a 4xx answer that carries the SQLSTATE in
 * the member {@code sqlState}, which is what a client that repairs its statement and sends it again reads. A duplicate key and a
 * taken name are the 409 every language answers. No other language gets the member.
 */
class PostgreSQLHttpErrorTest extends BaseGraphServerTest {
  private static final String TABLE = "pg_err_person";

  @BeforeEach
  void createTable() {
    final Database database = getServerDatabase(0, getDatabaseName());
    database.transaction(() -> {
      final VertexType type = database.getSchema().createVertexType(TABLE);
      type.createProperty("pid", Type.INTEGER);
      type.createProperty("name", Type.STRING).setNotNull(true);
      database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, TABLE, "pid");
    });
    database.transaction(
        () -> database.command("postgresql", "INSERT INTO pg_err_person (pid, name) VALUES (1, 'Ada')").close());
  }

  @Test
  void aDuplicateKeyIsTheConflictOfEveryLanguageAndCarriesTheSqlState() throws Exception {
    final JSONObject answer = refused("command", "postgresql", "INSERT INTO pg_err_person (pid, name) VALUES (1, 'Bob')", 409);

    assertThat(answer.getString("sqlState", "")).isEqualTo("23505");
    assertThat(answer.getString("exception", "")).isEqualTo(DuplicatedKeyException.class.getName());
    assertThat(answer.getString("exceptionArgs", "")).startsWith(TABLE + "[pid]|");
    assertThat(getServerDatabase(0, getDatabaseName()).countType(TABLE, false)).isEqualTo(1);
  }

  @Test
  void aTakenNameIsTheConflictOfEveryLanguageAndCarriesTheSqlState() throws Exception {
    final JSONObject answer = refused("command", "postgresql", "CREATE TABLE pg_err_person (pid int)", 409);

    assertThat(answer.getString("sqlState", "")).isEqualTo("42P07");
    assertThat(answer.getString("exception", "")).isEqualTo(AlreadyExistsException.class.getName());
    assertThat(answer.getString("exceptionArgs", "")).isEqualTo("TYPE");
    assertThat(answer.getString("detail", "")).contains("pg_err_person");
  }

  @ParameterizedTest
  @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
      query   | SELECT 1 / 0                                    | 22012 | eval.PostgreSQLEvaluationException
      query   | SELECT 2147483647 + 1                           | 22003 | eval.PostgreSQLEvaluationException
      query   | SELECT 'x'::integer                             | 22P02 | eval.PostgreSQLEvaluationException
      command | INSERT INTO pg_err_person (pid) VALUES (2)      | 23502 | eval.PostgreSQLEvaluationException
      query   | SELEC 1                                         | 42601 | PostgreSQLSyntaxException
      query   | SELECT * FROM pg_err_nothing                    | 42P01 | PostgreSQLUndefinedTableException
      """)
  void aStatementErrorIsABadRequestThatCarriesTheSqlState(final String endpoint, final String statement, final String sqlState,
      final String exception) throws Exception {
    final JSONObject answer = refused(endpoint, "postgresql", statement, 400);

    assertThat(answer.getString("sqlState", "")).isEqualTo(sqlState);
    assertThat(answer.getString("exception", "")).isEqualTo("com.arcadedb.query.postgresql." + exception);
    assertThat(answer.getString("detail", "")).isNotEmpty();
  }

  @Test
  void anotherLanguageGetsNoSqlState() throws Exception {
    final JSONObject answer = refused("command", "sql", "INSERT INTO pg_err_person SET pid = 1, name = 'Bob'", 409);

    assertThat(answer.getString("exception", "")).isEqualTo(DuplicatedKeyException.class.getName());
    assertThat(answer.has("sqlState")).isFalse();
  }

  private JSONObject refused(final String endpoint, final String language, final String statement, final int status)
      throws Exception {
    final HttpURLConnection connection = (HttpURLConnection) new URI(
        getServerHttpUrl(0, "/api/v1/" + endpoint + "/" + getDatabaseName())).toURL().openConnection();
    connection.setRequestMethod("POST");
    connection.setRequestProperty("Authorization",
        "Basic " + Base64.getEncoder().encodeToString(("root:" + DEFAULT_PASSWORD_FOR_TESTS).getBytes()));
    formatPayload(connection, language, statement, null, Map.of());
    connection.connect();
    try {
      final int code = connection.getResponseCode();
      final String body;
      try (final InputStream in = code < 400 ? connection.getInputStream() : connection.getErrorStream()) {
        body = FileUtils.readStreamAsString(in, "UTF-8");
      }
      assertThat(code).as(statement + " answered " + body).isEqualTo(status);
      return new JSONObject(body);
    } finally {
      connection.disconnect();
    }
  }
}
