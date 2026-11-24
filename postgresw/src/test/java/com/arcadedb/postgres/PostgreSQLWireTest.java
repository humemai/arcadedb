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

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.query.postgresql.SmallFixture;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static com.arcadedb.postgres.PostgresWireMessages.WireMessage;
import static com.arcadedb.postgres.PostgresWireMessages.errorFields;
import static com.arcadedb.postgres.PostgresWireMessages.firstDataRowValue;
import static com.arcadedb.postgres.PostgresWireMessages.messageTypesOf;
import static com.arcadedb.postgres.PostgresWireMessages.readUntilReadyForQuery;
import static com.arcadedb.postgres.PostgresWireMessages.readyForQueryStatusOf;
import static com.arcadedb.postgres.PostgresWireMessages.sendSimpleQuery;
import static com.arcadedb.postgres.PostgresWireMessages.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The {@code postgresql} language over the PostgreSQL wire protocol with the JDBC driver of PostgreSQL (fork issue #12), on the
 * {@code small} fixture: the marker, the language of a connection ({@code options=-c arcadedb.language=postgresql}), the
 * columns of a statement before it runs, the values and their types, writes with their command tags, transactions, and the
 * SQLSTATE and the position of an error.
 */
class PostgreSQLWireTest extends PostgresWireProtocolTestBase {
  private static final String LANGUAGE_OPTION = "-c arcadedb.language=postgresql";

  private static final String JOIN = "SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid";
  private static final List<String> JOIN_ROWS = List.of("Ada|100", "Ada|101", "Bo|102", "Di|103", "Di|104");

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database of every test is the small fixture and nothing else, so a catalog lists its four tables only. */
  @Override
  protected void populateDatabase() {
    SmallFixture.createSchema(getDatabases()[0]);
    SmallFixture.load(getDatabases()[0]);
  }

  private Connection connect(final String options, final boolean simpleQueryMode) throws SQLException {
    final Properties props = new Properties();
    props.setProperty("user", "root");
    props.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    props.setProperty("ssl", "false");
    // A hang detector: a reply the server never sends fails the test instead of stalling the build
    props.setProperty("socketTimeout", "60");
    if (options != null)
      props.setProperty("options", options);
    if (simpleQueryMode)
      props.setProperty("preferQueryMode", "simple");
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), props);
  }

  private static List<String> rows(final ResultSet rs) throws SQLException {
    final List<String> rows = new ArrayList<>();
    final int columns = rs.getMetaData().getColumnCount();
    while (rs.next()) {
      final StringBuilder row = new StringBuilder();
      for (int i = 1; i <= columns; i++)
        row.append(i > 1 ? "|" : "").append(rs.getString(i));
      rows.add(row.toString());
    }
    return rows;
  }

  private static List<String> query(final Connection connection, final String sql) throws SQLException {
    try (final Statement st = connection.createStatement(); final ResultSet rs = st.executeQuery(sql)) {
      return rows(rs);
    }
  }

  // ---------------------------------------------------------------------------------------------- the language of a statement

  @Test
  void aMarkerRunsTheStatementInTheLanguage() throws Exception {
    for (final boolean simple : new boolean[] { false, true })
      try (final Connection connection = connect(null, simple)) {
        assertThat(query(connection, "{postgresql} " + JOIN)).as("simple=" + simple).isEqualTo(JOIN_ROWS);
      }
  }

  @Test
  void theLanguageOfAConnectionRunsTheStatementWithNoMarker() throws Exception {
    for (final boolean simple : new boolean[] { false, true }) {
      try (final Connection connection = connect(LANGUAGE_OPTION, simple)) {
        assertThat(query(connection, JOIN)).as("simple=" + simple).isEqualTo(JOIN_ROWS);
        assertThat(query(connection, "SHOW arcadedb.language")).containsExactly("postgresql");
        // a marker still wins: ArcadeDB SQL has no JOIN ... ON between types, and sees the type person as its own
        assertThat(query(connection, "{sql} SELECT name FROM person WHERE city = 'Delft' ORDER BY name")).containsExactly("Ada", "Bo");
      }
      // a second connection that did not choose it speaks sql
      try (final Connection other = connect(null, simple)) {
        assertThat(query(other, "SHOW arcadedb.language")).containsExactly("sql");
        assertThatThrownBy(() -> query(other, JOIN)).isInstanceOf(PSQLException.class);
      }
    }
  }

  @Test
  void theLanguageOfAConnectionCanBeSetAndResetWithSql() throws Exception {
    try (final Connection connection = connect(null, true)) {
      try (final Statement st = connection.createStatement()) {
        st.execute("SET arcadedb.language = 'postgresql'");
      }
      assertThat(query(connection, JOIN)).isEqualTo(JOIN_ROWS);
      assertThat(query(connection, "SHOW arcadedb.language")).containsExactly("postgresql");

      try (final Statement st = connection.createStatement()) {
        st.execute("RESET arcadedb.language");
      }
      assertThat(query(connection, "SHOW arcadedb.language")).containsExactly("sql");
      assertThatThrownBy(() -> query(connection, JOIN)).isInstanceOf(PSQLException.class);

      // a language the connection cannot use is refused with invalid_parameter_value, and the connection keeps what it had
      assertThatThrownBy(() -> {
        try (final Statement st = connection.createStatement()) {
          st.execute("SET arcadedb.language = 'gremlin'");
        }
      }).isInstanceOf(PSQLException.class).satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("22023"));
      assertThat(query(connection, "SHOW arcadedb.language")).containsExactly("sql");
    }
  }

  @Test
  void aConnectionThatStartsWithALanguageItCannotUseIsRefused() {
    assertThatThrownBy(() -> connect("-c arcadedb.language=gremlin", false)).isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("22023"));
    assertThatThrownBy(() -> connect("-c arcadedb.language=nosuch", false)).isInstanceOf(PSQLException.class)
        .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("22023"));
  }

  @Test
  void aMarkerOfALanguageTheSettingDoesNotListIsStillRefused() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false)) {
      assertThatThrownBy(() -> query(connection, "{gremlin} g.V()")).isInstanceOf(PSQLException.class)
          .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("0A000"));
      // and the connection is still usable
      assertThat(query(connection, "SELECT count(*) FROM person")).containsExactly("5");
    }
  }

  // ---------------------------------------------------------------------------------------------------- columns and values

  @Test
  void aPreparedStatementIsDescribedBeforeItRuns() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false)) {
      try (final PreparedStatement ps = connection.prepareStatement("SELECT name, age FROM person WHERE pid = ?")) {
        final ResultSetMetaData meta = ps.getMetaData();
        assertThat(meta.getColumnCount()).isEqualTo(2);
        assertThat(meta.getColumnName(1)).isEqualTo("name");
        assertThat(meta.getColumnTypeName(1)).isEqualTo("varchar");
        assertThat(meta.getColumnName(2)).isEqualTo("age");
        assertThat(meta.getColumnTypeName(2)).isEqualTo("int4");
      }

      // a statement that writes is described by its RETURNING list, and describing it writes nothing
      try (final PreparedStatement ps = connection.prepareStatement(
          "INSERT INTO person (pid, name, age, city) VALUES (?, ?, ?, ?) RETURNING pid, name")) {
        final ResultSetMetaData meta = ps.getMetaData();
        assertThat(meta.getColumnCount()).isEqualTo(2);
        assertThat(meta.getColumnName(1)).isEqualTo("pid");
        assertThat(meta.getColumnName(2)).isEqualTo("name");
      }
      assertThat(query(connection, "SELECT count(*) FROM person")).containsExactly("5");

      // a statement the language refuses is refused when it is described, with its SQLSTATE
      assertThatThrownBy(() -> connection.prepareStatement("SELECT nosuch FROM person").getMetaData())
          .isInstanceOf(PSQLException.class).satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42703"));
      assertThat(query(connection, "SELECT 1")).containsExactly("1");
    }
  }

  @Test
  void aBoundParameterReachesTheLanguageWithItsType() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false)) {
      try (final PreparedStatement ps = connection.prepareStatement("SELECT name FROM person WHERE pid = ? AND city = ?")) {
        ps.setInt(1, 2);
        ps.setString(2, "Delft");
        try (final ResultSet rs = ps.executeQuery()) {
          assertThat(rows(rs)).containsExactly("Bo");
        }
        ps.setInt(1, 3);
        try (final ResultSet rs = ps.executeQuery()) {
          assertThat(rows(rs)).isEmpty();
          assertThat(rs.getMetaData().getColumnName(1)).isEqualTo("name");
        }
      }
    }
  }

  @Test
  void quotedIdentifiersAndStringLiteralsReachTheLanguageAsWritten() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false)) {
      assertThat(query(connection, "SELECT \"name\" FROM \"person\" WHERE city = 'Delft' ORDER BY \"name\"")).containsExactly("Ada", "Bo");
      // a backslash is an ordinary character in a plain literal and a quote is doubled, as in PostgreSQL
      assertThat(query(connection, "SELECT 'a\\b' AS s, 'it''s' AS t")).containsExactly("a\\b|it's");
    }
  }

  @Test
  void valuesArriveWithTheTypesOfTheView() throws Exception {
    for (final boolean simple : new boolean[] { false, true })
      try (final Connection connection = connect(LANGUAGE_OPTION, simple); final Statement st = connection.createStatement();
          final ResultSet rs = st.executeQuery("SELECT oid, total, placed, tags, address FROM orders WHERE oid = 100")) {
        final ResultSetMetaData meta = rs.getMetaData();
        assertThat(meta.getColumnTypeName(1)).isEqualTo("int4");
        assertThat(meta.getColumnTypeName(2)).isEqualTo("numeric");
        assertThat(meta.getColumnTypeName(3)).isEqualTo("date");
        assertThat(meta.getColumnTypeName(4)).isIn("_text", "text[]");
        assertThat(meta.getColumnTypeName(5)).isEqualTo("jsonb");
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt(1)).isEqualTo(100);
        assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("25.50");
        assertThat(rs.getDate(3).toString()).isEqualTo("2026-01-05");
        assertThat((Object[]) rs.getArray(4).getArray()).containsExactly("gift", "express");
        assertThat(rs.getString(5)).contains("Delft").contains("1000AA");
        assertThat(rs.next()).isFalse();
      }
  }

  @Test
  void twoColumnsOfOneLabelAreBothAnnounced() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false); final Statement st = connection.createStatement();
        final ResultSet rs = st.executeQuery("SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id "
            + "JOIN company c ON c.id = w.target_id ORDER BY p.name, c.name")) {
      final ResultSetMetaData meta = rs.getMetaData();
      assertThat(meta.getColumnCount()).isEqualTo(2);
      assertThat(meta.getColumnLabel(1)).isEqualTo("name");
      assertThat(meta.getColumnLabel(2)).isEqualTo("name");
      assertThat(rows(rs)).containsExactly("Ada|Acme", "Ada|Acme", "Bo|Acme", "Cy|Birch", "Di|Birch");
    }
  }

  // ------------------------------------------------------------------------------------------- writes and transactions

  @Test
  void writesAnswerWithTheCommandTagsOfPostgreSQL() throws Exception {
    for (final boolean simple : new boolean[] { false, true })
      try (final Connection connection = connect(LANGUAGE_OPTION, simple)) {
        final String suffix = simple ? "s" : "e";
        try (final PreparedStatement insert = connection.prepareStatement("INSERT INTO person (pid, name, age, city) VALUES (?, ?, ?, ?)")) {
          insert.setInt(1, simple ? 61 : 60);
          insert.setString(2, "New" + suffix);
          insert.setInt(3, 20);
          insert.setString(4, "Delft");
          assertThat(insert.executeUpdate()).isEqualTo(1);
        }
        try (final Statement st = connection.createStatement()) {
          assertThat(st.executeUpdate("UPDATE person SET age = age + 1 WHERE pid >= 60")).isEqualTo(1);
          assertThat(st.executeUpdate("DELETE FROM person WHERE name = 'New" + suffix + "'")).isEqualTo(1);
          assertThat(st.executeUpdate("DELETE FROM person WHERE name = 'nobody'")).isZero();
          // with RETURNING it is a query: the rows, and the tag counts them
          try (final ResultSet rs = st.executeQuery("INSERT INTO person (pid, name, age, city) VALUES (70, 'Ret', 30, 'Delft'), "
              + "(71, 'Urn', 31, 'Delft') RETURNING pid, name")) {
            assertThat(rows(rs)).containsExactly("70|Ret", "71|Urn");
          }
          assertThat(st.executeUpdate("DELETE FROM person WHERE pid >= 70")).isEqualTo(2);
        }
        assertThat(query(connection, "SELECT count(*) FROM person")).containsExactly("5");
      }
  }

  @Test
  void aTransactionBlockCommitsAndRollsBack() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false); final Statement st = connection.createStatement()) {
      connection.setAutoCommit(false);
      st.executeUpdate("INSERT INTO person (pid, name, age, city) VALUES (80, 'Tx', 1, 'Delft')");
      assertThat(query(connection, "SELECT count(*) FROM person")).containsExactly("6");
      connection.rollback();
      assertThat(query(connection, "SELECT count(*) FROM person")).containsExactly("5");

      st.executeUpdate("INSERT INTO person (pid, name, age, city) VALUES (81, 'Tx', 1, 'Delft')");
      connection.commit();
      connection.setAutoCommit(true);
    }
    // read by another connection
    try (final Connection other = connect(LANGUAGE_OPTION, false)) {
      assertThat(query(other, "SELECT count(*) FROM person")).containsExactly("6");
    }
  }

  @Test
  void anErrorInABlockAbortsItUntilRollback() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false); final Statement st = connection.createStatement()) {
      connection.setAutoCommit(false);
      st.executeUpdate("INSERT INTO person (pid, name, age, city) VALUES (90, 'Gone', 1, 'Delft')");
      assertThatThrownBy(() -> query(connection, "SELECT nosuch FROM person")).isInstanceOf(PSQLException.class)
          .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("42703"));
      assertThatThrownBy(() -> query(connection, "SELECT 1")).isInstanceOf(PSQLException.class)
          .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("25P02"));
      connection.rollback();
      assertThat(query(connection, "SELECT count(*) FROM person")).containsExactly("5");
    }
  }

  @Test
  void aMessageOfSeveralStatementsRunsThemInOrderAndEndsOutsideTheBlock() throws Exception {
    final String block = "BEGIN; SELECT count(*) FROM person; COMMIT;";
    // the language of the connection, the marker on the first statement, and a SET in the message itself
    for (final String startup : new String[] { LANGUAGE_OPTION, null }) {
      onRawConnection(startup, (out, in) -> {
        sendSimpleQuery(out, startup == null ? "{postgresql} " + block : block);
        final List<WireMessage> answered = readUntilReadyForQuery(in);
        assertThat(messageTypesOf(answered)).doesNotContain('E');
        assertThat(firstDataRowValue(answered)).isEqualTo("5");
        assertThat(readyForQueryStatusOf(answered)).isEqualTo('I');
      });
    }
    onRawConnection(null, (out, in) -> {
      sendSimpleQuery(out, "SET arcadedb.language = 'postgresql'; BEGIN; SELECT count(*) FROM person; COMMIT;");
      final List<WireMessage> answered = readUntilReadyForQuery(in);
      assertThat(messageTypesOf(answered)).doesNotContain('E');
      assertThat(firstDataRowValue(answered)).isEqualTo("5");
      assertThat(readyForQueryStatusOf(answered)).isEqualTo('I');
    });
  }

  // ------------------------------------------------------------------------------------------------------------ errors

  @Test
  void aSyntaxErrorHasItsSqlStateAndItsPosition() throws Exception {
    onRawConnection(LANGUAGE_OPTION, (out, in) -> {
      sendSimpleQuery(out, "SELECT name FROM person WHERE");
      final List<WireMessage> answered = readUntilReadyForQuery(in);
      assertThat(sqlStateOf(answered)).isEqualTo("42601");
      final Map<Character, String> fields = errorFields(answered.stream().filter(m -> m.type() == 'E').findFirst().orElseThrow());
      assertThat(fields.get('M')).isEqualTo("syntax error at end of input");
      // PostgreSQL counts characters from 1, and points one past the last of the text
      assertThat(fields.get('P')).isEqualTo("30");

      // the same statement behind a marker and blanks: the position counts the text the client sent
      sendSimpleQuery(out, "  {postgresql}  SELECT name FROM person WHERE x =");
      final Map<Character, String> behind = errorFields(
          readUntilReadyForQuery(in).stream().filter(m -> m.type() == 'E').findFirst().orElseThrow());
      assertThat(behind.get('C')).isEqualTo("42601");
      assertThat(behind.get('P')).isEqualTo(String.valueOf("  {postgresql}  SELECT name FROM person WHERE x =".length() + 1));

      // a name the view does not have, a type the operator does not take, and a statement outside the subset
      sendSimpleQuery(out, "SELECT nosuch FROM person");
      assertThat(sqlStateOf(readUntilReadyForQuery(in))).isEqualTo("42703");
      sendSimpleQuery(out, "SELECT name FROM nosuch");
      assertThat(sqlStateOf(readUntilReadyForQuery(in))).isEqualTo("42P01");
      sendSimpleQuery(out, "SELECT name + 1 FROM person");
      assertThat(sqlStateOf(readUntilReadyForQuery(in))).isEqualTo("42883");
      sendSimpleQuery(out, "SELECT 1 / 0");
      assertThat(sqlStateOf(readUntilReadyForQuery(in))).isEqualTo("22012");
      sendSimpleQuery(out, "CREATE TABLE t (a int)");
      assertThat(sqlStateOf(readUntilReadyForQuery(in))).isEqualTo("0A000");
      sendSimpleQuery(out, "COPY person TO STDOUT");
      assertThat(sqlStateOf(readUntilReadyForQuery(in))).isEqualTo("0A000");

      // and the connection is in step after all of them
      sendSimpleQuery(out, "SELECT 2 AS two");
      assertThat(firstDataRowValue(readUntilReadyForQuery(in))).isEqualTo("2");
    });
  }

  @Test
  void anErrorOfThePreparedProtocolHasItsSqlState() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION, false)) {
      assertThatThrownBy(() -> query(connection, "SELECT name FROM person WHERE")).isInstanceOf(PSQLException.class)
          .satisfies(e -> {
            final PSQLException exception = (PSQLException) e;
            assertThat(exception.getSQLState()).isEqualTo("42601");
            assertThat(exception.getServerErrorMessage().getPosition()).isEqualTo(30);
          });
      try (final PreparedStatement ps = connection.prepareStatement("INSERT INTO person (pid, name) VALUES (?, ?)")) {
        ps.setInt(1, 1);
        ps.setString(2, "Dup");
        // pid has a unique index
        assertThatThrownBy(ps::executeUpdate).isInstanceOf(PSQLException.class)
            .satisfies(e -> assertThat(((PSQLException) e).getSQLState()).isEqualTo("23505"));
      }
      assertThat(query(connection, "SELECT count(*) FROM person")).containsExactly("5");
    }
  }

  // ------------------------------------------------------------------------------------------------------ raw connection

  private interface RawConnectionTest {
    void run(DataOutputStream out, DataInputStream in) throws Exception;
  }

  /** A connection of the plain protocol, with the {@code options} of the startup packet when there are any. */
  private void onRawConnection(final String options, final RawConnectionTest test) throws Exception {
    try (final Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", getServerPostgresPort()), 2000);
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());

      final ByteArrayOutputStream body = new ByteArrayOutputStream();
      writeCString(body, "user");
      writeCString(body, "root");
      writeCString(body, "database");
      writeCString(body, getDatabaseName());
      if (options != null) {
        writeCString(body, "options");
        writeCString(body, options);
      }
      body.write(0);
      final byte[] bytes = body.toByteArray();
      out.writeInt(4 + 4 + bytes.length);
      out.writeInt(196608); // protocol version 3.0
      out.write(bytes);
      out.flush();

      readMessage(in); // AuthenticationCleartextPassword
      sendPasswordMessage(out, DEFAULT_PASSWORD_FOR_TESTS);
      readMessageOfType(in, 'Z');

      assertTimeoutPreemptively(Duration.ofSeconds(60), () -> test.run(out, in));
    }
  }
}
