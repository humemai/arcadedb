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

import com.arcadedb.database.Database;
import com.arcadedb.query.postgresql.ValuesFixture;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The values of {@code jsonb}, arrays, {@code bytea} and {@code vector} as parameters of the {@code postgresql} language through
 * the JDBC driver (fork issues #29 and #12): the types that ParameterDescription announces, values of declared types, and texts
 * of no declared type.
 */
class PostgreSQLValuesWireTest extends PostgresWireProtocolTestBase {
  private static final String INSERT = "INSERT INTO memory (mid, tags, counts, meta, payload, embedding) VALUES (?, ?, ?, ?, ?, ?)";

  @Override
  protected void populateDatabase() {
    ValuesFixture.createSchema(getDatabases()[0]);
    ValuesFixture.load(getDatabases()[0]);
  }

  private Connection connect() throws SQLException {
    final Properties props = new Properties();
    props.setProperty("user", "root");
    props.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    props.setProperty("ssl", "false");
    props.setProperty("socketTimeout", "60");
    props.setProperty("options", "-c arcadedb.language=postgresql");
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), props);
  }

  @Test
  void theTypesOfTheParametersAreAnnounced() throws SQLException {
    try (final Connection connection = connect(); final PreparedStatement statement = connection.prepareStatement(INSERT)) {
      final ParameterMetaData parameters = statement.getParameterMetaData();
      final List<String> types = new ArrayList<>();
      for (int i = 1; i <= parameters.getParameterCount(); i++)
        types.add(parameters.getParameterTypeName(i));
      assertThat(types).containsExactly("int4", "_text", "_int4", "jsonb", "bytea", "_float4");
    }
  }

  @Test
  void textsOfNoDeclaredTypeAreReadByTheTypeOfTheColumn() throws SQLException {
    try (final Connection connection = connect(); final PreparedStatement statement = connection.prepareStatement(INSERT)) {
      statement.setInt(1, 2);
      statement.setObject(2, "{work,\"two words\"}", Types.OTHER);
      statement.setObject(3, "{}", Types.OTHER);
      // a text that is also an array literal: it has to arrive as the empty object
      statement.setObject(4, "{}", Types.OTHER);
      statement.setObject(5, "\\xdead", Types.OTHER);
      statement.setObject(6, "[1,0,0.5]", Types.OTHER);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    final Result row = stored(2);
    assertThat((List<Object>) row.getProperty("tags")).containsExactly("work", "two words");
    assertThat((int[]) row.getProperty("counts")).isEmpty();
    assertThat((Map<?, ?>) row.getProperty("meta")).isEmpty();
    assertThat((byte[]) row.getProperty("payload")).containsExactly(0xde, 0xad);
    assertThat((float[]) row.getProperty("embedding")).containsExactly(1f, 0f, 0.5f);
  }

  @Test
  void valuesOfDeclaredTypes() throws SQLException {
    try (final Connection connection = connect(); final PreparedStatement statement = connection.prepareStatement(INSERT)) {
      statement.setInt(1, 3);
      statement.setArray(2, connection.createArrayOf("text", new String[] { "work", "two words" }));
      statement.setArray(3, connection.createArrayOf("int4", new Integer[] { 1, 2 }));
      statement.setObject(4, "{\"k\": 2}", Types.OTHER);
      statement.setBytes(5, new byte[] { (byte) 0xde, (byte) 0xad });
      statement.setArray(6, connection.createArrayOf("float4", new Float[] { 1f, 0f, 0.5f }));
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
    final Result row = stored(3);
    assertThat((List<Object>) row.getProperty("tags")).containsExactly("work", "two words");
    assertThat((int[]) row.getProperty("counts")).containsExactly(1, 2);
    assertThat(((Number) ((Map<?, ?>) row.getProperty("meta")).get("k")).intValue()).isEqualTo(2);
    assertThat((byte[]) row.getProperty("payload")).containsExactly(0xde, 0xad);
    assertThat((float[]) row.getProperty("embedding")).containsExactly(1f, 0f, 0.5f);
  }

  /** An embedded document is written from its object and sent back as that object, with no @type or @cat (fork issue #145). */
  @Test
  void embeddedDocumentsReadAsTheObjectsThatWereWritten() throws SQLException {
    final Database database = getServerDatabase(0, getDatabaseName());
    database.transaction(() -> {
      database.getSchema().createDocumentType("address").createProperty("city", Type.STRING);
      final VertexType person = database.getSchema().createVertexType("person");
      person.createProperty("home", Type.EMBEDDED, "address");
      person.createProperty("past", Type.LIST, "address");
    });
    try (final Connection connection = connect(); final Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO person (home, past) VALUES ('{\"city\": \"Delft\"}', '[{\"city\": \"Gouda\"}]')");
      try (final java.sql.ResultSet rows = statement.executeQuery("SELECT home, past FROM person")) {
        assertThat(rows.next()).isTrue();
        assertThat(new JSONObject(rows.getString("home")).toMap()).isEqualTo(Map.of("city", "Delft"));
        assertThat(new JSONArray(rows.getString("past")).toList()).isEqualTo(List.of(Map.of("city", "Gouda")));
      }
      // an array in any jsonb column, a LIST OF MAP too, is sent as JSON and not as a PostgreSQL array
      statement.executeUpdate("UPDATE memory SET items = '[{\"a\": 1}]' WHERE mid = 1");
      try (final java.sql.ResultSet rows = statement.executeQuery("SELECT items FROM memory WHERE mid = 1")) {
        assertThat(rows.next()).isTrue();
        assertThat(new JSONArray(rows.getString("items")).toList()).isEqualTo(List.of(Map.of("a", 1)));
      }
    }
  }

  private Result stored(final int mid) {
    try (final ResultSet resultSet = getServerDatabase(0, getDatabaseName()).query("sql", "SELECT FROM memory WHERE mid = ?", mid)) {
      return resultSet.next();
    }
  }
}
