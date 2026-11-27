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
import com.arcadedb.database.Database;
import com.arcadedb.event.AfterRecordUpdateListener;
import com.arcadedb.event.BeforeRecordCreateListener;
import com.arcadedb.postgres.PostgresWireMessages.WireMessage;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.arcadedb.postgres.PostgresWireMessages.errorFields;
import static com.arcadedb.postgres.PostgresWireMessages.firstDataRowValue;
import static com.arcadedb.postgres.PostgresWireMessages.messageTypesOf;
import static com.arcadedb.postgres.PostgresWireMessages.readUntilReadyForQuery;
import static com.arcadedb.postgres.PostgresWireMessages.sendBind;
import static com.arcadedb.postgres.PostgresWireMessages.sendClose;
import static com.arcadedb.postgres.PostgresWireMessages.sendDescribe;
import static com.arcadedb.postgres.PostgresWireMessages.sendExecute;
import static com.arcadedb.postgres.PostgresWireMessages.sendParse;
import static com.arcadedb.postgres.PostgresWireMessages.sendSimpleQuery;
import static com.arcadedb.postgres.PostgresWireMessages.sendSync;
import static com.arcadedb.postgres.PostgresWireMessages.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An {@code INSERT}, {@code UPDATE}, or {@code DELETE} of the {@code postgresql} language that is a transaction of its own
 * and meets a write conflict is run again by the server (fork issue #239): concurrent autocommit writes all apply, as on
 * PostgreSQL 18. A statement that shares its transaction, and one on a database with a trigger, answers {@code 40001} as
 * before. The runs are bounded, and nothing of a run that was lost reaches the client.
 * <p>
 * The conflicts of the later tests are made on purpose: a record listener lets another thread commit a change to the same
 * row while the statement of the client is running, which is also how the runs are counted.
 */
class PostgreSQLAutocommitRetryTest extends PostgreSQLSessionAgainstPostgresTestBase {
  private static final int    SESSIONS = 2;
  private static final int    WRITES   = 200;
  private static final String ADD      = "UPDATE acct SET bal = bal + 100 WHERE id = 2";

  /** How many of the next runs of a client's write meet another transaction that commits first. */
  private final AtomicInteger conflicts = new AtomicInteger();
  /** The runs of a client's write of the watched row. */
  private final AtomicInteger runs      = new AtomicInteger();

  private interface RawTest {
    void run(DataOutputStream out, DataInputStream in) throws Exception;
  }

  private interface Messages {
    void write(DataOutputStream out) throws Exception;
  }

  // ------------------------------------------------------------------------------------------- next to PostgreSQL 18

  @Test
  void concurrentAutocommitWritesAllApplyAsOnPostgreSql() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { false, true })
        for (final Side side : List.of(postgres, arcade)) {
          final String description = (side == postgres ? "PostgreSQL" : "postgresql") + " simple=" + simple;
          final String suffix = simple ? "_simple" : "_extended";
          final String acct = "acct" + suffix;
          final String entry = "entry" + suffix;
          final String tally = "tally" + suffix;
          try (final Connection connection = side.connect(simple)) {
            for (final String statement : List.of("CREATE TABLE " + acct + " (id bigint PRIMARY KEY, bal int)",
                "INSERT INTO " + acct + " (id, bal) VALUES (2, 0)", "CREATE TABLE " + entry + " (id serial PRIMARY KEY, note text)",
                "CREATE TABLE " + tally + " (name text PRIMARY KEY, n int)"))
              assertThat(answer(connection, statement)).as(description + " " + statement).doesNotStartWith("error");
          }

          // the 400 updates of the issue, with bound values: pgjdbc names the statement after a few runs
          assertThat(together(side, simple, "UPDATE " + acct + " SET bal = bal + ? WHERE id = ?", 1, 2L)).as(description)
              .hasSize(SESSIONS * WRITES).containsOnly("count 1");
          assertThat(one(side, simple, "SELECT bal FROM " + acct + " WHERE id = 2")).as(description).isEqualTo("rows [400]");

          // every row of RETURNING reaches its client once: no value is missing and none comes twice
          assertThat(together(side, simple, "UPDATE " + acct + " SET bal = bal + 1 WHERE id = 2 RETURNING bal")).as(description)
              .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(401, 800).mapToObj(i -> "rows [" + i + "]").toList());

          // inserts that take the values of a counter, which a run that is lost does not give back
          assertThat(together(side, simple, "INSERT INTO " + entry + " (note) VALUES ('x')")).as(description)
              .containsOnly("count 1");
          assertThat(one(side, simple, "SELECT count(*) FROM " + entry)).as(description).isEqualTo("rows [400]");

          assertThat(together(side, simple, "INSERT INTO " + tally + " (name, n) VALUES ('a', 1) ON CONFLICT (name) "
              + "DO UPDATE SET n = " + tally + ".n + 1")).as(description).containsOnly("count 1");
          assertThat(one(side, simple, "SELECT n FROM " + tally + " WHERE name = 'a'")).as(description).isEqualTo("rows [400]");
        }
    });
  }

  // ------------------------------------------------------------------------------------------- when a run is repeated

  @Test
  void aRunThatIsLostSendsNothingAndTheNextOneAnswers() throws Exception {
    createAccounts();
    onRawConnection((out, in) -> {
      // as a driver that closes its portal before the Sync does: Describe of the portal, Execute, Close, Sync
      int before = balance(2);
      meet(2);
      sendParse(out, "", ADD + " RETURNING bal");
      sendBind(out, "", "");
      sendDescribe(out, 'P', "");
      sendExecute(out, "");
      sendClose(out, 'P', "");
      sendSync(out);
      List<WireMessage> answered = readUntilReadyForQuery(in);
      assertThat(messageTypesOf(answered)).containsExactly('1', '2', 'T', 'D', 'C', '3', 'Z');
      assertThat(firstDataRowValue(answered)).isEqualTo(String.valueOf(before + 102));
      assertThat(runs.get()).isEqualTo(3);
      assertThat(balance(2)).isEqualTo(before + 102);

      // a named statement that was described before, then Bind, Execute, Sync
      sendParse(out, "add", ADD + " RETURNING bal");
      sendDescribe(out, 'S', "add");
      sendSync(out);
      assertThat(messageTypesOf(readUntilReadyForQuery(in))).doesNotContain('E');
      before = balance(2);
      meet(2);
      sendBind(out, "", "add");
      sendExecute(out, "");
      sendSync(out);
      answered = readUntilReadyForQuery(in);
      assertThat(messageTypesOf(answered)).containsExactly('2', 'D', 'C', 'Z');
      assertThat(firstDataRowValue(answered)).isEqualTo(String.valueOf(before + 102));
      assertThat(runs.get()).isEqualTo(3);

      // the simple protocol
      before = balance(2);
      meet(2);
      sendSimpleQuery(out, ADD + " RETURNING bal");
      answered = readUntilReadyForQuery(in);
      assertThat(messageTypesOf(answered)).containsExactly('T', 'D', 'C', 'Z');
      assertThat(firstDataRowValue(answered)).isEqualTo(String.valueOf(before + 102));
      assertThat(runs.get()).isEqualTo(3);
    });
  }

  @Test
  void theRunsAreBoundedByTheirNumberAndByTheStatementTimeout() throws Exception {
    createAccounts();
    final Database database = getServerDatabase(0, getDatabaseName());
    onRawConnection((out, in) -> {
      database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_AUTOCOMMIT_RETRIES, 2);
      for (final Messages statement : List.<Messages>of(this::simpleReturning, this::extendedReturning)) {
        final int before = balance(2);
        meet(Integer.MAX_VALUE);
        statement.write(out);
        final List<WireMessage> answered = readUntilReadyForQuery(in);
        assertThat(sqlStateOf(answered)).isEqualTo("40001");
        assertThat(messageTypesOf(answered)).doesNotContain('D', 'C');
        // the first run and two more, and only the other transactions wrote
        assertThat(runs.get()).isEqualTo(3);
        assertThat(balance(2)).isEqualTo(before + 3);
      }

      database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_AUTOCOMMIT_RETRIES, 0);
      meet(Integer.MAX_VALUE);
      simpleReturning(out);
      assertThat(sqlStateOf(readUntilReadyForQuery(in))).isEqualTo("40001");
      assertThat(runs.get()).isEqualTo(1);

      database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_AUTOCOMMIT_RETRIES, 1_000_000);
      conflicts.set(0);
      sendSimpleQuery(out, "SET statement_timeout = 500");
      assertThat(messageTypesOf(readUntilReadyForQuery(in))).doesNotContain('E');
      for (final Messages statement : List.<Messages>of(this::simpleReturning, this::extendedReturning)) {
        meet(Integer.MAX_VALUE);
        statement.write(out);
        final List<WireMessage> answered = readUntilReadyForQuery(in);
        assertThat(sqlStateOf(answered)).isEqualTo("57014");
        assertThat(messageTypesOf(answered)).doesNotContain('D', 'C');
        assertThat(runs.get()).isGreaterThan(1);
      }
      conflicts.set(0);
    });
  }

  // ------------------------------------------------------------------------------------------- when it is not

  @Test
  void aStatementThatSharesItsTransactionIsNotRunAgain() throws Exception {
    createAccounts();

    // inside a block, which pgjdbc opens in the same round trip as the first statement
    for (final boolean simple : new boolean[] { false, true })
      try (final Connection connection = open(simple)) {
        final int before = balance(2);
        meet(1);
        connection.setAutoCommit(false);
        assertThat(answer(connection, ADD)).isEqualTo("count 1");
        assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class)
            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("40001"));
        assertThat(runs.get()).as("simple=" + simple).isEqualTo(1);
        assertThat(balance(2)).isEqualTo(before + 1);
      }

    onRawConnection((out, in) -> {
      // several statements of one simple query
      assertNothingOfItApplies(in, () -> sendSimpleQuery(out, ADD + "; UPDATE acct SET bal = bal + 100 WHERE id = 3"));

      // several Execute messages before one Sync
      assertNothingOfItApplies(in, () -> {
        for (final String statement : List.of(ADD, "UPDATE acct SET bal = bal + 100 WHERE id = 3")) {
          sendParse(out, "", statement);
          sendBind(out, "", "");
          sendExecute(out, "");
        }
        sendSync(out);
      });

      // the second Execute of a run is not run again either
      assertNothingOfItApplies(in, () -> {
        for (final String statement : List.of("UPDATE acct SET bal = bal + 100 WHERE id = 3", ADD)) {
          sendParse(out, "", statement);
          sendBind(out, "", "");
          sendExecute(out, "");
        }
        sendSync(out);
      });

      // an Execute whose answer the client asks for before it ends the run: what follows is not known
      assertNothingOfItApplies(in, () -> {
        sendParse(out, "", ADD);
        sendBind(out, "", "");
        sendExecute(out, "");
        out.writeByte('H');
        out.writeInt(4);
        sendSync(out);
      });
    });
  }

  @Test
  void aDatabaseWithATriggerRunsAStatementOnce() throws Exception {
    createAccounts();
    final Database database = getServerDatabase(0, getDatabaseName());
    database.command("sql", "CREATE TRIGGER audit BEFORE UPDATE ON TYPE acct EXECUTE SQL 'SELECT 1'");
    try (final Connection connection = open(false)) {
      final int before = balance(2);
      meet(1);
      assertThat(answer(connection, ADD)).isEqualTo("error 40001");
      assertThat(runs.get()).isEqualTo(1);
      assertThat(balance(2)).isEqualTo(before + 1);

      database.command("sql", "DROP TRIGGER audit");
      meet(1);
      assertThat(answer(connection, ADD)).isEqualTo("count 1");
      assertThat(runs.get()).isEqualTo(2);
      assertThat(balance(2)).isEqualTo(before + 102);
    }
  }

  @Test
  void onlyAnUpsertIsRunAgainAfterADuplicateKey() throws Exception {
    try (final Connection connection = open(false)) {
      assertThat(answer(connection, "CREATE TABLE tag (id serial PRIMARY KEY, name text UNIQUE, code text UNIQUE, n int)"))
          .doesNotStartWith("error");
    }
    final Database database = getServerDatabase(0, getDatabaseName());
    // the row that another transaction inserts between the look of the client's statement and its commit
    final String[] other = new String[1];
    database.getSchema().getType("tag").getEvents().registerListener((BeforeRecordCreateListener) record -> {
      if (Thread.currentThread() instanceof PostgresNetworkExecutor && conflicts.getAndDecrement() > 0)
        elsewhere("postgresql", other[0]);
      return true;
    });

    for (final boolean simple : new boolean[] { false, true })
      try (final Connection connection = open(simple)) {
        final String name = "'u" + simple + "'";
        other[0] = "INSERT INTO tag (name, n) VALUES (" + name + ", 10)";
        conflicts.set(1);
        assertThat(answer(connection, "INSERT INTO tag (name, n) VALUES (" + name + ", 1) ON CONFLICT (name) "
            + "DO UPDATE SET n = tag.n + 1 RETURNING n")).isEqualTo("rows [11]");

        // the counter went on: a run that was lost leaves a gap, and no value is given twice
        assertThat(answer(connection, "INSERT INTO tag (name, n) VALUES ('after" + simple + "', 0)")).isEqualTo("count 1");
        assertThat(answer(connection, "SELECT count(*) FROM tag WHERE name = " + name)).isEqualTo("rows [1]");

        // a plain INSERT fails, as on PostgreSQL, although another run could take another value of the counter
        other[0] = "INSERT INTO tag (name, n) VALUES ('p" + simple + "', 10)";
        conflicts.set(1);
        assertThat(answer(connection, "INSERT INTO tag (name, n) VALUES ('p" + simple + "', 1)")).isEqualTo("error 23505");
        assertThat(answer(connection, "SELECT n FROM tag WHERE name = 'p" + simple + "'")).isEqualTo("rows [10]");

        // so does an upsert whose duplicate is of another unique key than the one that it answers for
        other[0] = "INSERT INTO tag (name, code, n) VALUES ('o" + simple + "', 'c" + simple + "', 10)";
        conflicts.set(1);
        assertThat(answer(connection, "INSERT INTO tag (name, code, n) VALUES ('q" + simple + "', 'c" + simple + "', 1) "
            + "ON CONFLICT (name) DO NOTHING")).isEqualTo("error 23505");
        assertThat(answer(connection, "SELECT count(*) FROM tag WHERE name = 'q" + simple + "'")).isEqualTo("rows [0]");
      }
  }

  // ------------------------------------------------------------------------------------------- the look at the input

  @Test
  void onlyCloseMessagesAndTheExecuteOfADescribedPortalMayStandBeforeTheSync() throws Exception {
    assertThat(follows(null, out -> sendSync(out))).isTrue();
    assertThat(follows(null, out -> {
      sendClose(out, 'P', "");
      sendClose(out, 'S', "statement");
      sendSync(out);
    })).isTrue();
    assertThat(follows("p", out -> {
      sendExecute(out, "p");
      sendClose(out, 'P', "p");
      sendSync(out);
    })).isTrue();
    assertThat(follows("p", out -> sendSync(out))).isTrue();
    assertThat(follows("p", out -> {
      sendExecute(out, "other");
      sendSync(out);
    })).isFalse();
    assertThat(follows("p", out -> {
      sendExecute(out, "p");
      sendExecute(out, "p");
      sendSync(out);
    })).isFalse();
    assertThat(follows(null, out -> {
      sendExecute(out, "");
      sendSync(out);
    })).isFalse();
    assertThat(follows(null, out -> {
      sendParse(out, "", "SELECT 1");
      sendSync(out);
    })).isFalse();
    assertThat(follows(null, out -> {
      // more than is looked ahead at
      sendClose(out, 'S', "x".repeat(PostgresAutocommitRetry.LOOK_AHEAD));
      sendSync(out);
    })).isFalse();
    assertThat(follows(null, out -> {
    })).isFalse();
  }

  // ------------------------------------------------------------------------------------------- helpers

  private static boolean follows(final String described, final Messages messages) throws Exception {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    messages.write(new DataOutputStream(bytes));
    final DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes.toByteArray()), 16));
    final boolean follows = PostgresAutocommitRetry.onlySyncFollows(in, described);
    assertThat(in.readAllBytes()).as("nothing is consumed").isEqualTo(bytes.toByteArray());
    return follows;
  }

  private void simpleReturning(final DataOutputStream out) throws Exception {
    sendSimpleQuery(out, ADD + " RETURNING bal");
  }

  private void extendedReturning(final DataOutputStream out) throws Exception {
    sendParse(out, "", ADD + " RETURNING bal");
    sendBind(out, "", "");
    sendExecute(out, "");
    sendSync(out);
  }

  private interface Sender {
    void send() throws Exception;
  }

  /** The statements meet one conflict: the answer is 40001, they ran once, and only the other transaction wrote. */
  private void assertNothingOfItApplies(final DataInputStream in, final Sender statements) throws Exception {
    final int second = balance(2);
    final int third = balance(3);
    meet(1);
    statements.send();
    final List<WireMessage> answered = readUntilReadyForQuery(in);
    assertThat(messageTypesOf(answered)).contains('E');
    assertThat(errorFields(answered.stream().filter(m -> m.type() == 'E').findFirst().orElseThrow()).get('C')).isEqualTo("40001");
    assertThat(runs.get()).isEqualTo(1);
    assertThat(balance(2)).isEqualTo(second + 1);
    assertThat(balance(3)).isEqualTo(third);
  }

  /**
   * Two rows, and a listener that counts the runs of a client's write of row 2 and lets the next {@link #conflicts} of
   * them meet another transaction, which adds 1 to the row and commits first.
   */
  private void createAccounts() throws SQLException {
    try (final Connection connection = open(false)) {
      assertThat(answer(connection, "CREATE TABLE acct (id bigint PRIMARY KEY, bal int)")).doesNotStartWith("error");
      assertThat(answer(connection, "INSERT INTO acct (id, bal) VALUES (2, 0), (3, 0)")).isEqualTo("count 2");
    }
    getServerDatabase(0, getDatabaseName()).getSchema().getType("acct").getEvents()
        .registerListener((AfterRecordUpdateListener) record -> {
          // the writes of a client run on the thread of its connection, the other transaction on a thread of its own
          if (Thread.currentThread() instanceof PostgresNetworkExecutor
              && ((Number) record.asDocument().get("id")).longValue() == 2) {
            runs.incrementAndGet();
            if (conflicts.getAndDecrement() > 0)
              elsewhere("sql", "UPDATE acct SET bal = bal + 1 WHERE id = 2");
          }
        });
  }

  private void meet(final int others) {
    runs.set(0);
    conflicts.set(others);
  }

  /** Runs a statement in a transaction of another thread, which is committed when this returns. */
  private void elsewhere(final String language, final String statement) {
    final Database database = getServerDatabase(0, getDatabaseName());
    try {
      CompletableFuture.runAsync(() -> database.transaction(() -> {
        try (final com.arcadedb.query.sql.executor.ResultSet rows = database.command(language, statement)) {
          while (rows.hasNext())
            rows.next();
        }
      })).get(30, TimeUnit.SECONDS);
    } catch (final InterruptedException | ExecutionException | TimeoutException e) {
      throw new IllegalStateException(statement, e);
    }
  }

  private int balance(final long id) {
    final Database database = getServerDatabase(0, getDatabaseName());
    try (final com.arcadedb.query.sql.executor.ResultSet rows = database.query("sql", "SELECT bal FROM acct WHERE id = ?", id)) {
      return ((Number) rows.next().getProperty("bal")).intValue();
    }
  }

  /** What the statement answered to each of the sessions that ran it {@link #WRITES} times, all at once. */
  private static List<String> together(final Side side, final boolean simple, final String statement, final Object... values)
      throws Exception {
    final CyclicBarrier start = new CyclicBarrier(SESSIONS);
    final ExecutorService threads = Executors.newFixedThreadPool(SESSIONS);
    try {
      final List<Future<List<String>>> sessions = new ArrayList<>();
      for (int session = 0; session < SESSIONS; session++)
        sessions.add(threads.submit(() -> {
          try (final Connection connection = side.connect(simple);
              final PreparedStatement write = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++)
              write.setObject(i + 1, values[i]);
            start.await(60, TimeUnit.SECONDS);
            final List<String> answers = new ArrayList<>(WRITES);
            for (int i = 0; i < WRITES; i++)
              answers.add(run(write));
            return answers;
          }
        }));
      final List<String> answers = new ArrayList<>(SESSIONS * WRITES);
      for (final Future<List<String>> session : sessions)
        answers.addAll(session.get(180, TimeUnit.SECONDS));
      return answers;
    } finally {
      threads.shutdownNow();
    }
  }

  private static String run(final PreparedStatement write) {
    try {
      if (!write.execute())
        return "count " + write.getUpdateCount();
      try (final ResultSet rows = write.getResultSet()) {
        final List<String> answer = new ArrayList<>();
        while (rows.next())
          answer.add(rows.getString(1));
        return "rows " + answer;
      }
    } catch (final SQLException e) {
      return "error " + e.getSQLState() + " " + e.getMessage();
    }
  }

  private static String one(final Side side, final boolean simple, final String query) throws SQLException {
    try (final Connection connection = side.connect(simple)) {
      return answer(connection, query);
    }
  }

  private Connection open(final boolean simpleProtocol) throws SQLException {
    final Properties properties = new Properties();
    properties.setProperty("user", "root");
    properties.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    properties.setProperty("ssl", "false");
    properties.setProperty("socketTimeout", "60");
    properties.setProperty("options", "-c arcadedb.language=postgresql");
    if (simpleProtocol)
      properties.setProperty("preferQueryMode", "simple");
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), properties);
  }

  /** A connection of the plain protocol whose language is {@code postgresql}. */
  private void onRawConnection(final RawTest test) throws Exception {
    try (final Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", getServerPostgresPort()), 2000);
      socket.setSoTimeout(60_000);
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());

      final ByteArrayOutputStream body = new ByteArrayOutputStream();
      for (final String text : List.of("user", "root", "database", getDatabaseName(), "options", "-c arcadedb.language=postgresql"))
        writeCString(body, text);
      body.write(0);
      final byte[] bytes = body.toByteArray();
      out.writeInt(4 + 4 + bytes.length);
      out.writeInt(196608); // protocol version 3.0
      out.write(bytes);
      out.flush();

      readMessage(in); // AuthenticationCleartextPassword
      sendPasswordMessage(out, DEFAULT_PASSWORD_FOR_TESTS);
      readMessageOfType(in, 'Z');
      test.run(out, in);
    }
  }
}
