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

import com.arcadedb.ContextConfiguration;
import com.arcadedb.GlobalConfiguration;
import com.arcadedb.database.Database;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.server.ha.raft.BaseRaftHATest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static com.arcadedb.postgres.PostgresWireMessages.WireMessage;
import static com.arcadedb.postgres.PostgresWireMessages.messageTypesOf;
import static com.arcadedb.postgres.PostgresWireMessages.readUntilReadyForQuery;
import static com.arcadedb.postgres.PostgresWireMessages.readyForQueryStatusOf;
import static com.arcadedb.postgres.PostgresWireMessages.sendSimpleQuery;
import static com.arcadedb.postgres.PostgresWireProtocolTestBase.readMessage;
import static com.arcadedb.postgres.PostgresWireProtocolTestBase.readMessageOfType;
import static com.arcadedb.postgres.PostgresWireProtocolTestBase.sendPasswordMessage;
import static com.arcadedb.postgres.PostgresWireProtocolTestBase.sendStartupMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A Postgres client connected to an HA follower: a write it sends is forwarded to the leader, or refused naming the
 * leader when the forward cannot reach it, on both the simple and the extended query protocol; inside a transaction the
 * client opened, or the implicit transaction of a multi-statement Query message, the writes run on the follower and
 * commit or roll back as one block.
 */
@Tag("slow")
class PostgresFollowerForwardingIT extends BaseRaftHATest {
  // One Postgres port per server, drawn free from the same ledger as the Raft ports
  private final int[] postgresPorts = allocateFixturePorts(3);

  @Override
  protected void onServerConfiguration(final ContextConfiguration config) {
    super.onServerConfiguration(config);
    final int index = serverIndexOf(config);
    config.setValue(GlobalConfiguration.SERVER_PLUGINS.getKey(), "Postgres:com.arcadedb.postgres.PostgresProtocolPlugin");
    config.setValue(GlobalConfiguration.POSTGRES_PORT.getKey(), String.valueOf(postgresPorts[index]));
    config.setValue(GlobalConfiguration.POSTGRES_LANGUAGES.getKey(), "sqlscript,cypher,opencypher,postgresql");
  }

  @Override
  protected int getServerCount() {
    return 3;
  }

  /**
   * Fork issue #316: a follower does not send a schema change of a {@code postgresql} transaction block to the leader, where it
   * would commit at once and a {@code ROLLBACK} would not reach it. Outside a block the leader runs it, as before.
   */
  @Test
  @Timeout(180)
  void aSchemaChangeInsideAPostgresqlBlockIsRefusedOnAFollower() throws Exception {
    final int leader = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("postgresql", "CREATE TABLE facts316 (id int PRIMARY KEY, note text)");
    leaderDb.command("postgresql", "CREATE INDEX facts316_note ON facts316 (note)");
    leaderDb.command("postgresql", "INSERT INTO facts316 VALUES (1, 'a')");
    waitForAllServers();
    final int follower = firstFollowerOf(leader);

    for (final String queryMode : new String[] { "simple", "extended" })
      try (final Connection connection = connect(follower, queryMode, "postgresql");
          final Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        for (final String change : List.of("DROP TABLE facts316", "DROP INDEX facts316_note",
            "CREATE INDEX ON facts316 (note, id)", "ALTER TABLE facts316 ADD COLUMN more int", "CREATE TABLE more316 (id int)")) {
          final Throwable refused = catchThrowable(() -> statement.execute(change));
          assertThat(refused).as(queryMode + ": " + change).isInstanceOf(SQLException.class)
              .hasMessageContaining("inside a transaction block is not supported by the postgresql language");
          assertThat(((SQLException) refused).getSQLState()).as(queryMode + ": " + change).isEqualTo("0A000");
          connection.rollback();
        }
      }

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++) {
      final Database db = getServerDatabase(i, getDatabaseName());
      assertThat(db.getSchema().existsType("more316")).as("server %d: the refused table", i).isFalse();
      assertThat(db.getSchema().getType("facts316").existsProperty("more")).as("server %d: the refused column", i).isFalse();
      assertThat(db.getSchema().getType("facts316").getAllIndexes(false)).as("server %d: the indexes", i).hasSize(2);
      try (final ResultSet rows = db.query("postgresql", "SELECT count(*) AS n FROM facts316")) {
        assertThat(rows.next().<Number>getProperty("n").longValue()).as("server %d: the rows", i).isEqualTo(1);
      }
    }

    try (final Connection connection = connect(follower, "simple", "postgresql");
        final Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE facts316");
    }
    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(getServerDatabase(i, getDatabaseName()).getSchema().existsType("facts316")).as("server %d: dropped", i).isFalse();
  }

  /**
   * Fork issue #335: a follower does not send the leader a {@code postgresql} schema change that further statements of its run
   * follow, where it would commit at once and the failure of one of them would not take it back. On its own the leader runs it.
   */
  @Test
  @Timeout(180)
  void aSchemaChangeThatStatementsFollowIsRefusedOnAFollower() throws Exception {
    final int leader = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("postgresql", "CREATE TABLE facts335 (id int PRIMARY KEY, note text)");
    leaderDb.command("postgresql", "INSERT INTO facts335 VALUES (1, 'a')");
    waitForAllServers();
    final int follower = firstFollowerOf(leader);

    try (final Connection connection = connect(follower, "extended", "postgresql");
        final Statement statement = connection.createStatement()) {
      for (final String change : List.of("DROP TABLE facts335", "ALTER TABLE facts335 DROP COLUMN note",
          "CREATE INDEX ON facts335 (note)", "CREATE TABLE more335 (id int)")) {
        final Throwable refused = catchThrowable(() -> statement.execute(change + "; SELECT nope FROM facts335"));
        assertThat(refused).as(change).isInstanceOf(SQLException.class).hasMessageContaining("followed by other statements");
        assertThat(((SQLException) refused).getSQLState()).as(change).isEqualTo("0A000");
      }
    }

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++) {
      final Database db = getServerDatabase(i, getDatabaseName());
      assertThat(db.getSchema().existsType("more335")).as("server %d: the refused table", i).isFalse();
      assertThat(db.getSchema().existsType("facts335")).as("server %d: the table", i).isTrue();
      assertThat(db.getSchema().getType("facts335").existsProperty("note")).as("server %d: the column", i).isTrue();
      assertThat(db.getSchema().getType("facts335").getAllIndexes(false)).as("server %d: the indexes", i).hasSize(1);
      try (final ResultSet rows = db.query("postgresql", "SELECT count(*) AS n FROM facts335")) {
        assertThat(rows.next().<Number>getProperty("n").longValue()).as("server %d: the rows", i).isEqualTo(1);
      }
    }

    try (final Connection connection = connect(follower, "extended", "postgresql");
        final Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE facts335");
    }
    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(getServerDatabase(i, getDatabaseName()).getSchema().existsType("facts335")).as("server %d: dropped", i).isFalse();
  }

  /**
   * Fork issue #338: the writes of the messages before one {@code Sync}, here a JDBC batch, are one transaction on a follower
   * too. Each one forwarded to the leader would commit there at once, out of reach of the failure of a later one.
   */
  @Test
  @Timeout(180)
  void aFailedBatchOnAFollowerCommitsNothing() throws Exception {
    final int leader = requireLeaderIndex();
    createNotes(leader, "batch338");
    final int follower = firstFollowerOf(leader);

    try (final Connection connection = connect(follower, "extended", "postgresql");
        final Statement statement = connection.createStatement()) {
      for (final String write : List.of("INSERT INTO batch338 VALUES (3, 'c')", "UPDATE batch338 SET note = 'changed' WHERE id = 1",
          "DELETE FROM batch338 WHERE id = 2", "INSERT INTO batch338 VALUES (4, NULL)"))
        statement.addBatch(write);
      final Throwable failed = catchThrowable(statement::executeBatch);

      assertNotesOnEveryServer("batch338", "1 a", "2 b");
      assertThat(failed).isInstanceOf(SQLException.class);
      assertThat(((SQLException) failed).getSQLState()).as(failed.getMessage()).isEqualTo("23502");

      // the connection goes on, and a batch that succeeds reaches every server
      try (final PreparedStatement insert = connection.prepareStatement("INSERT INTO batch338 VALUES (?, ?)")) {
        for (int id = 5; id <= 7; id++) {
          insert.setInt(1, id);
          insert.setString(2, "n" + id);
          insert.addBatch();
        }
        assertThat(insert.executeBatch()).containsExactly(1, 1, 1);
      }
    }
    assertNotesOnEveryServer("batch338", "1 a", "2 b", "5 n5", "6 n6", "7 n7");
  }

  /** Fork issue #338, as a psycopg pipeline sends it; a run that succeeds reads what it wrote before its {@code Sync}. */
  @Test
  @Timeout(180)
  void aFailedPipelineOnAFollowerCommitsNothing() throws Exception {
    final int leader = requireLeaderIndex();
    createNotes(leader, "piped338");
    final int follower = firstFollowerOf(leader);

    final String failed = psycopg(follower, """
        import sys
        import psycopg

        with psycopg.connect(sys.argv[1], autocommit=True) as conn:
            try:
                with conn.pipeline():
                    conn.execute("INSERT INTO piped338 VALUES (3, 'c')")
                    conn.execute("UPDATE piped338 SET note = %s WHERE id = %s", ("changed", 1))
                    conn.execute("DELETE FROM piped338 WHERE id = 2")
                    conn.execute("INSERT INTO piped338 VALUES (4, NULL)")
                print("ran")
            except psycopg.Error as e:
                print(e.sqlstate)
        """);
    assertNotesOnEveryServer("piped338", "1 a", "2 b");
    assertThat(failed).isEqualTo("23502\n");

    final String succeeded = psycopg(follower, """
        import sys
        import psycopg

        with psycopg.connect(sys.argv[1], autocommit=True) as conn:
            with conn.pipeline():
                conn.execute("INSERT INTO piped338 VALUES (5, 'e')")
                conn.execute("UPDATE piped338 SET note = 'f' WHERE id = 5")
                # the answer is asked for before the Sync
                print(conn.execute("SELECT note FROM piped338 WHERE id = 5").fetchone()[0])
                conn.execute("DELETE FROM piped338 WHERE id = 1")
            print("ran")
        """);
    assertThat(succeeded).isEqualTo("f\nran\n");
    assertNotesOnEveryServer("piped338", "2 b", "5 f");
  }

  /**
   * Fork issue #338: the values of a {@code serial} column that runs on a follower take, each in a commit of its own, are those
   * of the one counter that the leader reads.
   */
  @Test
  @Timeout(180)
  void theRunsOfAFollowerShareTheCounterOfASerialColumn() throws Exception {
    final int leader = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("postgresql", "CREATE TABLE counted338 (id serial PRIMARY KEY, note text)");
    waitForAllServers();

    try (final Connection connection = connect(firstFollowerOf(leader), "extended", "postgresql");
        final Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO counted338 (note) VALUES ('a'); INSERT INTO counted338 (note) VALUES ('b')");
      waitForAllServers();
      leaderDb.command("postgresql", "INSERT INTO counted338 (note) VALUES ('c')");
      waitForAllServers();
      statement.execute("INSERT INTO counted338 (note) VALUES ('d'); INSERT INTO counted338 (note) VALUES ('e')");
    }
    assertNotesOnEveryServer("counted338", "1 a", "2 b", "3 c", "4 d", "5 e");
  }

  /**
   * Fork issue #338: a schema change that follows writes of its run is refused on a follower, because the leader would commit it
   * while the writes still wait for the {@code Sync}. After reads only it runs.
   */
  @Test
  @Timeout(180)
  void aSchemaChangeAfterWritesOfItsRunIsRefusedOnAFollower() throws Exception {
    final int leader = requireLeaderIndex();
    createNotes(leader, "ordered338");

    try (final Connection connection = connect(firstFollowerOf(leader), "extended", "postgresql");
        final Statement statement = connection.createStatement()) {
      final Throwable refused = catchThrowable(
          () -> statement.execute("INSERT INTO ordered338 VALUES (3, 'c'); CREATE TABLE refused338 (id int)"));
      assertThat(refused).isInstanceOf(SQLException.class)
          .hasMessageContaining("after other writes of the same batch or pipeline");
      assertThat(((SQLException) refused).getSQLState()).isEqualTo("0A000");

      statement.execute("SELECT 1; CREATE TABLE after338 (id int)");
    }
    assertNotesOnEveryServer("ordered338", "1 a", "2 b");
    for (int i = 0; i < getServerCount(); i++) {
      final Database db = getServerDatabase(i, getDatabaseName());
      assertThat(db.getSchema().existsType("refused338")).as("server %d: the refused table", i).isFalse();
      assertThat(db.getSchema().existsType("after338")).as("server %d: the table made after a read", i).isTrue();
    }
  }

  /**
   * Fork issue #338: a commit that starts on a replica cannot check a foreign key, so a run of several writes that has one to
   * check is refused as a whole at its {@code Sync}. A write on its own still goes to the leader.
   */
  @Test
  @Timeout(180)
  void aRunThatHasAForeignKeyToCheckIsRefusedWholeOnAFollower() throws Exception {
    final int leader = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("postgresql", "CREATE TABLE parent338 (id int PRIMARY KEY)");
    leaderDb.command("postgresql", "CREATE TABLE child338 (id int PRIMARY KEY, note text, parent int REFERENCES parent338 (id))");
    leaderDb.command("postgresql", "INSERT INTO parent338 VALUES (1)");
    waitForAllServers();

    try (final Connection connection = connect(firstFollowerOf(leader), "extended", "postgresql");
        final Statement statement = connection.createStatement()) {
      statement.addBatch("INSERT INTO child338 VALUES (1, 'a', 1)");
      statement.addBatch("INSERT INTO child338 VALUES (2, 'b', 1)");
      final Throwable refused = catchThrowable(statement::executeBatch);
      assertNotesOnEveryServer("child338");
      assertThat(refused).isInstanceOf(SQLException.class)
          .hasMessageContaining("cannot write what a foreign key has to check");

      statement.clearBatch();
      statement.execute("INSERT INTO child338 VALUES (3, 'c', 1)");
    }
    assertNotesOnEveryServer("child338", "3 c");
  }

  /** A table of two notes, {@code 1 a} and {@code 2 b}, that every server has. */
  /**
   * One INSERT for each kind of value that psycopg binds, then an UPDATE and a DELETE with values of no declared type. Each
   * statement is a run of its own in autocommit, which a follower forwards to the leader. It prints {@code ok} or the SQLSTATE.
   */
  private static final String BOUND_VALUES = """
      import datetime as dt
      import sys
      import uuid
      from decimal import Decimal

      import psycopg
      from psycopg.types.json import Json, Jsonb
      from psycopg.types.numeric import Float4, Int2, Int8

      run = "@RUN@"
      aware = dt.datetime(2026, 11, 29, 10, 11, 12, 123456, tzinfo=dt.timezone.utc)
      naive = aware.replace(tzinfo=None)
      day = dt.date(2026, 11, 29)

      # name, column, expression, value
      cases = [
          ("str", "shown", "%s", "changed"),
          ("str-as-integer", "shown", "(7 = %s)::text", "7"),
          ("str-binary", "shown", "%b", "binary"),
          ("str-array", "ta", "%s", ["a", "b,c"]),
          ("str-array-empty", "ta", "%s", []),
          ("str-array-literal", "ta", "%s", "{a,b}"),
          ("json", "j", "%s", Json({"k": 1})),
          ("json-binary", "shown", "%b::text", Json({"k": 1})),
          ("jsonb", "j", "%s", Jsonb({"k": 1})),
          ("jsonb-string", "shown", "%s::text", Jsonb("x")),
          ("jsonb-binary", "j", "%b", Jsonb({"k": [1, None]})),
          ("bool", "shown", "%s::text", True),
          ("int2", "i4", "%s", Int2(5)),
          ("int8-product", "shown", "(%s * 2000000000)::text", Int8(5)),
          ("float4-product", "shown", "(%s * 3)::text", Float4(0.1)),
          ("float8-nan", "f8", "%s", float("nan")),
          ("float8-infinity", "shown", "%s::text", float("-inf")),
          ("numeric-scale", "shown", "%s::text", Decimal("10.50")),
          ("numeric", "n", "%s", Decimal("12345678901234567890.123456789")),
          ("numeric-array", "na", "%s", [Decimal("1.50")]),
          ("int-array", "ia", "%s", [1, 2]),
          ("bytea", "by", "%s", b"\\x00\\x01\\xff"),
          ("bytea-binary", "by", "%b", b"\\x00\\x01\\xff"),
          ("bytea-array", "bya", "%s", [b"\\x01"]),
          ("date", "d", "%s", day),
          ("date-shown", "shown", "%s::text", day),
          ("date-array", "da", "%s", [day]),
          ("timestamp", "ts", "%s", naive),
          ("timestamp-binary", "shown", "%b::text", naive),
          ("timestamp-array", "tsa", "%s", [naive]),
          ("timestamptz", "shown", "%s::text", aware),
          ("timestamptz-binary", "shown", "%b::text", aware),
          ("uuid", "u", "%s", uuid.UUID(int=341)),
          ("uuid-shown", "shown", "%s::text", uuid.UUID(int=341)),
          ("time", "shown", "%s::text", dt.time(10, 11, 12)),
          ("timetz", "shown", "%s::text", dt.time(10, 11, 12, tzinfo=dt.timezone.utc)),
          ("interval", "shown", "%s::text", dt.timedelta(days=1, seconds=2)),
          ("null", "shown", "%s", None),
      ]

      with psycopg.connect(sys.argv[1], autocommit=True) as conn:

          def attempt(name, sql, values):
              try:
                  conn.execute(sql, values)
                  print(name, "ok")
              except psycopg.Error as e:
                  print(name, e.sqlstate)
                  print(name, e, file=sys.stderr)

          for name, column, expression, value in cases:
              attempt(name, f"INSERT INTO bound341 (id, {column}) VALUES ('{run}:{name}', {expression})", (value,))
          attempt("update", "UPDATE bound341 SET shown = %s WHERE id = %s AND i4 = %s", ("changed", run + ":update", "7"))
          attempt("delete", "DELETE FROM bound341 WHERE id = %s AND i4 = %s", (run + ":delete", "7"))
      """;

  /**
   * Fork issue #341: a value that a client binds to a write of the {@code postgresql} language through a follower arrives at
   * the leader as the value it is when the client is connected to the leader, or is refused as it is there. Every case runs
   * once against the leader and once against a follower, and the answers and the stored rows are compared.
   */
  @Test
  @Timeout(240)
  void theBoundValuesOfAWriteOnAFollowerAreThoseOfAWriteOnTheLeader() throws Exception {
    final int leader = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("postgresql", "CREATE TABLE bound341 (id text PRIMARY KEY, shown text, i4 int, f8 float8, n numeric, "
        + "d date, ts timestamp, u uuid, by bytea, j jsonb, ta text[], ia int[], na numeric[], bya bytea[], da date[], "
        + "tsa timestamp[])");
    for (final String run : List.of("leader", "follower"))
      leaderDb.command("postgresql",
          "INSERT INTO bound341 (id, shown, i4) VALUES ('" + run + ":update', 'a', 7), ('" + run + ":delete', 'a', 7)");
    waitForAllServers();
    final int follower = firstFollowerOf(leader);

    final String onLeader = psycopg(leader, BOUND_VALUES.replace("@RUN@", "leader"));
    final String onFollower = psycopg(follower, BOUND_VALUES.replace("@RUN@", "follower"));
    waitForAllServers();

    for (int i = 0; i < getServerCount(); i++) {
      final Map<String, String> expected = outcomes(i, "leader", onLeader);
      final Map<String, String> actual = outcomes(i, "follower", onFollower);
      final List<String> differences = new ArrayList<>();
      expected.forEach((name, outcome) -> {
        if (!outcome.equals(actual.get(name)))
          differences.add("\n" + name + ": on the leader <" + outcome + ">, through the follower <" + actual.get(name) + ">");
      });
      assertThat(differences).as("server %d", i).isEmpty();
      assertThat(actual.keySet()).isEqualTo(expected.keySet());
      // the two runs do not merely agree on a failure: only the types the language does not have are refused
      expected.forEach((name, outcome) -> assertThat(outcome).as(name)
          .startsWith(List.of("time", "timetz", "interval").contains(name) ? "0A000" : "ok"));
      assertThat(actual.get("str")).isEqualTo("ok {shown=String [changed]}");
    }
  }

  /** What each case of a run printed, and the values that its row of {@code bound341} holds on a server. */
  private Map<String, String> outcomes(final int serverIndex, final String run, final String printed) {
    final Map<String, String> rows = new TreeMap<>();
    try (final ResultSet result = getServerDatabase(serverIndex, getDatabaseName()).query("sql", "SELECT FROM bound341")) {
      while (result.hasNext()) {
        final Result row = result.next();
        final String id = row.getProperty("id");
        if (id.startsWith(run + ":"))
          rows.put(id.substring(run.length() + 1), values(row, "id"));
      }
    }
    final Map<String, String> outcomes = new TreeMap<>();
    for (final String line : printed.split("\n")) {
      final String[] parts = line.split(" ", 2);
      outcomes.put(parts[0], parts[1] + " " + rows.getOrDefault(parts[0], "no row"));
    }
    return outcomes;
  }

  /** The values of a row, each with its class, but for the property that names the row. */
  private static String values(final Result row, final String key) {
    final Map<String, String> values = new TreeMap<>();
    for (final String name : row.getPropertyNames()) {
      final Object value = row.getProperty(name);
      if (value != null && !name.equals(key) && !name.startsWith("@"))
        // the items of a list too: a byte[] in one would print as its identity
        values.put(name, value.getClass().getSimpleName() + " "
            + Arrays.deepToString(new Object[] { value instanceof List<?> items ? items.toArray() : value }));
    }
    return values.toString();
  }

  private void createNotes(final int leader, final String table) {
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("postgresql", "CREATE TABLE " + table + " (id int PRIMARY KEY, note text NOT NULL)");
    leaderDb.command("postgresql", "INSERT INTO " + table + " VALUES (1, 'a'), (2, 'b')");
    waitForAllServers();
  }

  /** The rows of the table, as {@code id note}, on every server once it has applied what the leader committed. */
  private void assertNotesOnEveryServer(final String table, final String... notes) {
    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++) {
      final List<String> found = new ArrayList<>();
      try (final ResultSet rows = getServerDatabase(i, getDatabaseName()).query("postgresql",
          "SELECT id, note FROM " + table + " ORDER BY id")) {
        while (rows.hasNext()) {
          final Result row = rows.next();
          found.add(row.<Number>getProperty("id") + " " + row.<String>getProperty("note"));
        }
      }
      assertThat(found).as("server %d: the rows of %s", i, table).containsExactly(notes);
    }
  }

  /** Runs a psycopg script, in a container, against one server and returns what it printed. */
  private String psycopg(final int serverIndex, final String script) throws Exception {
    PostgreSQLSideBySideTestBase.requireDocker();
    Testcontainers.exposeHostPorts(postgresPorts[serverIndex]);
    try (final GenericContainer<?> python = new GenericContainer<>(PostgreSQLSideBySideTestBase.PYTHON_IMAGE)
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep", "infinity"))) {
      python.start();
      python.execInContainer("mkdir", "-p", "/work");
      python.copyFileToContainer(Transferable.of(script), "/work/clients.py");
      final Container.ExecResult result = python.execInContainer("uv", "run", "--no-project", "--with", "psycopg[binary]", "python",
          "-I", "/work/clients.py", "host=host.testcontainers.internal port=" + postgresPorts[serverIndex] + " dbname="
              + getDatabaseName() + " user=root password=" + DEFAULT_PASSWORD_FOR_TESTS
              + " sslmode=disable options='-c arcadedb.language=postgresql'");
      assertThat(result.getExitCode()).as(result.getStdout() + "\n" + result.getStderr()).isZero();
      return result.getStdout();
    }
  }

  /**
   * Fork issue #326: the rows that the {@code postgresql} language writes reach every server, whether the statement is a
   * transaction of its own or a part of a block, run on the leader or sent to a follower.
   */
  @Test
  @Timeout(180)
  void theRowsThatThePostgresqlLanguageWritesReachEveryServer() throws Exception {
    final int leader = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("postgresql", "CREATE TABLE written326 (id int PRIMARY KEY, note text)");
    leaderDb.command("postgresql", "INSERT INTO written326 VALUES (1, 'a'), (2, 'b')");
    leaderDb.command("postgresql", "UPDATE written326 SET note = 'c' WHERE id = 1");
    leaderDb.command("postgresql", "DELETE FROM written326 WHERE id = 2");
    leaderDb.command("postgresql", "BEGIN");
    leaderDb.command("postgresql", "INSERT INTO written326 VALUES (3, 'd')");
    leaderDb.command("postgresql", "COMMIT");
    waitForAllServers();

    try (final Connection connection = connect(firstFollowerOf(leader), "simple", "postgresql");
        final Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO written326 VALUES (4, 'e')");
      connection.setAutoCommit(false);
      statement.execute("INSERT INTO written326 VALUES (5, 'f')");
      connection.commit();
    }
    waitForAllServers();

    for (int i = 0; i < getServerCount(); i++) {
      final List<String> found = new ArrayList<>();
      try (final ResultSet rows = getServerDatabase(i, getDatabaseName()).query("postgresql",
          "SELECT id, note FROM written326 ORDER BY id")) {
        while (rows.hasNext()) {
          final Result row = rows.next();
          found.add(row.<Number>getProperty("id") + " " + row.<String>getProperty("note"));
        }
      }
      assertThat(found).as("server %d: the rows", i).containsExactly("1 c", "3 d", "4 e", "5 f");
    }
  }

  /*
   * Issue #9870: a real follower refusing a real Postgres client's write. Issue #9845 kept the SQLSTATE at {@code 40001},
   * the wire having no redirect code, and named the leader in the message, tested on the mapping helpers alone; this
   * drives it through the JDBC driver, the executor's error arms and the cluster's own refusal, on both the simple and the
   * extended query protocol, which reach the database through different arms.
   */
  private static final String LEADER_REFUSAL_TYPE = "Issue9870Pg";

  // Regression: #9870
  /**
   * The simple protocol goes through the follower's forward, which a correctly configured cluster answers by running the
   * write on the leader. The follower is put in the state a same-host cluster that declares no HTTP ports is in (see
   * {@link BaseRaftHATest#refuseForwardedWritesOn}): the leader address it resolves is its own, so it refuses the forward,
   * naming the leader, rather than POSTing the write back to itself.
   */
  @Test
  @Timeout(120)
  void aWriteOnTheSimpleQueryProtocolNamesTheLeader() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, LEADER_REFUSAL_TYPE);
    final Runnable restore = refuseForwardedWritesOn(follower);
    try (final Connection connection = connect(follower, "simple"); final Statement statement = connection.createStatement()) {
      final Throwable refused = catchThrowable(() -> statement.execute("INSERT INTO " + LEADER_REFUSAL_TYPE + " SET name = 'simple'"));
      assertRefusalNaming(refused, getRaftPlugin(follower).getRaftHAServer().getLeaderName());
    } finally {
      restore.run();
    }
  }

  // Regression: #9870
  /**
   * The extended protocol reaches the same forward since issue #9954 (it used to run a parsed statement on the node the
   * client reached, so a schema change was refused by the follower's schema layer instead), so it needs the same
   * injection as the simple-protocol case above and is refused the same way, naming the leader.
   */
  @Test
  @Timeout(120)
  void aSchemaChangeOnTheExtendedQueryProtocolNamesTheLeader() throws Exception {
    final int follower = firstFollowerOf(requireLeaderIndex());
    final Runnable restore = refuseForwardedWritesOn(follower);
    try (final Connection connection = connect(follower, "extended");
        final PreparedStatement statement = connection.prepareStatement("CREATE DOCUMENT TYPE Issue9870PgExtended")) {
      final Throwable refused = catchThrowable(statement::execute);
      assertRefusalNaming(refused, getRaftPlugin(follower).getRaftHAServer().getLeaderName());
    } finally {
      restore.run();
    }
  }

  /** {@code leader} is how the refusal names the leader: its server name for a refused forward, its address otherwise. */
  private static void assertRefusalNaming(final Throwable refused, final String leader) {
    assertThat(leader).as("the follower must know which node leads").isNotBlank();
    assertThat(refused).as("the follower must refuse the write").isInstanceOf(SQLException.class);
    assertThat(((SQLException) refused).getSQLState())
        .as("the Postgres wire has no redirect code: the refusal stays the retryable serialization_failure (issue #9845)")
        .isEqualTo("40001");
    assertThat(refused.getMessage())
        .as("the message is the only place a Postgres client or an operator can find the leader in")
        .contains("The leader is server '" + leader + "'");
  }

  /*
   * Issue #9954: the Postgres extended query protocol ran a follower's parsed write or schema change locally, where the
   * simple query protocol forwards it to the leader through {@code RaftReplicatedDatabase.command(...)}. The same statement
   * succeeded or failed on a follower depending only on the driver's query mode. Every test here uses
   * {@code preferQueryMode=extended}, pgjdbc's default and what every driver that binds parameters sends.
   */
  private static final String EXTENDED_FORWARD_TYPE = "Issue9954Pg";

  // Regression: #9954
  /**
   * A prepared INSERT reaches the follower's forward: with the forward made to refuse (the #6191 self-address refusal),
   * the write is refused naming the leader, exactly as the same INSERT is over the simple protocol. Before the fix it
   * ran on the follower and committed through Raft from there, so it succeeded.
   */
  @Test
  @Timeout(120)
  void aPreparedWriteGoesThroughTheFollowersForward() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXTENDED_FORWARD_TYPE);
    final Runnable restore = refuseForwardedWritesOn(follower);
    try (final Connection connection = connect(follower, "extended");
        final PreparedStatement statement = connection.prepareStatement("INSERT INTO " + EXTENDED_FORWARD_TYPE + " SET name = ?")) {
      statement.setString(1, "refused");
      final Throwable refused = catchThrowable(statement::execute);
      assertThat(refused).as("the write must reach the forward, which refuses it").isInstanceOf(SQLException.class);
      assertThat(((SQLException) refused).getSQLState()).isEqualTo("40001");
      assertThat(refused.getMessage()).contains("The leader is server '" + getRaftPlugin(follower).getRaftHAServer().getLeaderName() + "'");

      // The refusal leaves the connection usable: the next statement on it is answered
      try (final PreparedStatement read = connection.prepareStatement("SELECT count(*) AS c FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = ?")) {
        read.setString(1, "refused");
        try (final java.sql.ResultSet rs = read.executeQuery()) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getLong("c")).isZero();
        }
      }
    } finally {
      restore.run();
    }

    try (final ResultSet rs = getServerDatabase(leader, getDatabaseName()).query("sql",
        "SELECT count(*) AS c FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = 'refused'")) {
      assertThat(rs.next().<Number>getProperty("c").longValue()).as("a refused forward must not have written locally").isZero();
    }
  }

  // Regression: #9954
  /** A prepared UPDATE and DELETE reach the follower's forward too, the other two writes a driver binds parameters to. */
  @Test
  @Timeout(120)
  void aPreparedUpdateAndDeleteGoThroughTheFollowersForward() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXTENDED_FORWARD_TYPE);
    final String leaderName = getRaftPlugin(follower).getRaftHAServer().getLeaderName();
    final Runnable restore = refuseForwardedWritesOn(follower);
    try (final Connection connection = connect(follower, "extended")) {
      for (final String sql : new String[] { "UPDATE " + EXTENDED_FORWARD_TYPE + " SET n = 1 WHERE name = ?", "DELETE FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = ?" }) {
        try (final PreparedStatement statement = connection.prepareStatement(sql)) {
          statement.setString(1, "any");
          final Throwable refused = catchThrowable(statement::execute);
          assertThat(refused).as("'%s' must reach the forward, which refuses it", sql).isInstanceOf(SQLException.class);
          assertThat(((SQLException) refused).getSQLState()).isEqualTo("40001");
          assertThat(refused.getMessage()).contains("The leader is server '" + leaderName + "'");
        }
      }
    } finally {
      restore.run();
    }
  }

  // Regression: #9954
  /** A prepared INSERT with a bound parameter, forwarded for real, lands on the leader with the parameter's value. */
  @Test
  @Timeout(120)
  void aPreparedWriteWithABoundParameterIsWrittenByTheLeader() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXTENDED_FORWARD_TYPE);
    try (final Connection connection = connect(follower, "extended");
        final PreparedStatement statement = connection.prepareStatement("INSERT INTO " + EXTENDED_FORWARD_TYPE + " SET name = ?, n = ?")) {
      statement.setString(1, "forwarded");
      statement.setInt(2, 42);
      statement.execute();
      // A second forwarded write on the same connection: the first left no implicit transaction behind to trip it
      statement.setString(1, "forwardedAgain");
      statement.setInt(2, 43);
      statement.execute();
    }

    try (final ResultSet rs = getServerDatabase(leader, getDatabaseName()).query("sql",
        "SELECT n FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = 'forwarded'")) {
      assertThat(rs.hasNext()).as("the forwarded write must be on the leader").isTrue();
      assertThat(rs.next().<Number>getProperty("n").intValue()).isEqualTo(42);
    }
    try (final ResultSet rs = getServerDatabase(leader, getDatabaseName()).query("sql",
        "SELECT n FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = 'forwardedAgain'")) {
      assertThat(rs.hasNext()).as("the second forwarded write must be on the leader").isTrue();
      assertThat(rs.next().<Number>getProperty("n").intValue()).isEqualTo(43);
    }
  }

  // Regression: #9954
  /**
   * A prepared schema change on a follower is forwarded and succeeds, as over the simple protocol. Before the fix it ran
   * on the follower and was refused by the schema layer ("Changes to the schema must be executed on the leader server").
   */
  @Test
  @Timeout(120)
  void aPreparedSchemaChangeOnAFollowerSucceeds() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = firstFollowerOf(leader);
    final String type = EXTENDED_FORWARD_TYPE + "Ddl";
    try (final Connection connection = connect(follower, "extended");
        final PreparedStatement statement = connection.prepareStatement("CREATE DOCUMENT TYPE " + type)) {
      statement.execute();
    }
    assertThat(getServerDatabase(leader, getDatabaseName()).getSchema().existsType(type))
        .as("the schema change must have run on the leader").isTrue();
    waitForAllServers();
    assertThat(getServerDatabase(follower, getDatabaseName()).getSchema().existsType(type))
        .as("and replicated back to the follower").isTrue();
  }

  // Regression: #9954
  /**
   * Inside an explicit transaction block a prepared write is NOT forwarded: the forward would commit it on the leader as
   * a request of its own, so a ROLLBACK could not undo it (issue #10040, which the simple protocol still has). It runs in
   * the follower's own transaction instead, which a ROLLBACK discards and a COMMIT replicates through Raft.
   */
  @Test
  @Timeout(120)
  void aPreparedWriteInsideAnExplicitBlockKeepsTheBlockAtomic() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXTENDED_FORWARD_TYPE);
    try (final Connection connection = connect(follower, "extended")) {
      connection.setAutoCommit(false);
      try (final PreparedStatement statement = connection.prepareStatement("INSERT INTO " + EXTENDED_FORWARD_TYPE + " SET name = ?")) {
        statement.setString(1, "rolledBack");
        statement.execute();
        connection.rollback();

        statement.setString(1, "committed");
        statement.execute();
        connection.commit();
      }
    }

    waitForAllServers();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    try (final ResultSet rs = leaderDb.query("sql", "SELECT count(*) AS c FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = 'rolledBack'")) {
      assertThat(rs.next().<Number>getProperty("c").longValue()).as("a rolled-back write must not reach the leader").isZero();
    }
    try (final ResultSet rs = leaderDb.query("sql", "SELECT count(*) AS c FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = 'committed'")) {
      assertThat(rs.next().<Number>getProperty("c").longValue()).as("a committed write must reach the leader").isEqualTo(1L);
    }
  }

  // Regression: #9954
  /** A prepared read stays on the follower: it still answers with the forward refusing every write. */
  @Test
  @Timeout(120)
  void aPreparedReadStillRunsOnTheFollower() throws Exception {
    final int leader = requireLeaderIndex();
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("sql", "CREATE DOCUMENT TYPE " + EXTENDED_FORWARD_TYPE + " IF NOT EXISTS");
    leaderDb.transaction(() -> leaderDb.command("sql", "INSERT INTO " + EXTENDED_FORWARD_TYPE + " SET name = 'read'"));
    waitForAllServers();
    final int follower = firstFollowerOf(leader);

    final Runnable restore = refuseForwardedWritesOn(follower);
    try (final Connection connection = connect(follower, "extended");
        final PreparedStatement statement = connection.prepareStatement("SELECT name FROM " + EXTENDED_FORWARD_TYPE + " WHERE name = ?")) {
      statement.setString(1, "read");
      try (final java.sql.ResultSet rs = statement.executeQuery()) {
        assertThat(rs.next()).as("the read must be answered locally").isTrue();
        assertThat(rs.getString("name")).isEqualTo("read");
      }
    } finally {
      restore.run();
    }
  }

  /*
   * Issue #10040: a follower forwarded every write it received to the leader, where it committed as a request of its own,
   * even inside a transaction the client had opened with BEGIN. A ROLLBACK then discarded an empty local transaction and
   * the row stayed on the leader, and a block of several writes was not atomic. Driven over the Postgres simple query
   * protocol, the shape of the report: the write now runs in the client's transaction on the follower, and its COMMIT
   * replicates it through Raft.
   *
   * @author Luca Garulli (l.garulli@arcadedata.com)
   */
  private static final String EXPLICIT_TRANSACTION_TYPE = "Issue10040Pg";

  // Regression: #10040
  @Test
  @Timeout(180)
  void aRollbackOnAFollowerUndoesTheWritesOfItsBlock() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXPLICIT_TRANSACTION_TYPE);

    try (final Connection connection = connect(follower, "simple"); final Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false); // pgjdbc sends BEGIN with the first statement
      statement.execute("INSERT INTO " + EXPLICIT_TRANSACTION_TYPE + " SET name = 'rb'");
      statement.execute("INSERT INTO " + EXPLICIT_TRANSACTION_TYPE + " SET name = 'rb'");
      connection.rollback();
    }

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(count(i, EXPLICIT_TRANSACTION_TYPE, "rb")).as("server %d must hold no row of the rolled back block", i).isZero();
  }

  // Regression: #10040
  @Test
  @Timeout(180)
  void aCommitOnAFollowerPublishesTheWholeBlockEverywhere() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXPLICIT_TRANSACTION_TYPE);

    try (final Connection connection = connect(follower, "simple"); final Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("INSERT INTO " + EXPLICIT_TRANSACTION_TYPE + " SET name = 'block'");
      statement.execute("INSERT INTO " + EXPLICIT_TRANSACTION_TYPE + " SET name = 'block'");
      // inside the block, the follower already sees its own writes: they are in its transaction, not on the leader
      try (final java.sql.ResultSet rs = statement.executeQuery("SELECT count(@rid) AS c FROM " + EXPLICIT_TRANSACTION_TYPE + " WHERE name = 'block'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getLong(1)).isEqualTo(2L);
      }
      assertThat(count(leader, EXPLICIT_TRANSACTION_TYPE, "block")).as("nothing reaches the leader before COMMIT").isZero();
      connection.commit();
    }

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(count(i, EXPLICIT_TRANSACTION_TYPE, "block")).as("server %d must hold the committed block", i).isEqualTo(2L);
  }

  // Regression: #10040
  /**
   * A write that runs on the follower can add a name to the schema dictionary, which every node must agree on: the
   * reason non-idempotent statements were forwarded in the first place (#4039). The leader writes a new name of its own
   * first, so the follower's dictionary page is the one the leader moved on from unless the follower applied it. The
   * block must either commit with every node agreeing on both names, or fail with a retryable conflict, never diverge.
   */
  @Test
  @Timeout(180)
  void aNewPropertyNameWrittenOnAFollowerIsAgreedByEveryNode() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXPLICIT_TRANSACTION_TYPE);

    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.transaction(() -> leaderDb.command("sql", "INSERT INTO " + EXPLICIT_TRANSACTION_TYPE + " SET name = 'leader', leaderOnlyProp = 1"));

    boolean committed = false;
    for (int attempt = 0; attempt < 5 && !committed; attempt++) {
      try (final Connection connection = connect(follower, "simple"); final Statement statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute("INSERT INTO " + EXPLICIT_TRANSACTION_TYPE + " SET name = 'dict', followerOnlyProp = 'x'");
        connection.commit();
        committed = true;
      } catch (final SQLException e) {
        // a stale dictionary page is refused by the leader as the retryable serialization_failure, the same as any conflict
        assertThat(e.getSQLState()).as("a conflict must be retryable: %s", e.getMessage()).isEqualTo("40001");
      }
    }
    assertThat(committed).as("the block must commit within a few retries").isTrue();

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++) {
      final Database db = getServerDatabase(i, getDatabaseName());
      try (final ResultSet rs = db.query("sql", "SELECT followerOnlyProp FROM " + EXPLICIT_TRANSACTION_TYPE + " WHERE name = 'dict'")) {
        assertThat(rs.next().<String>getProperty("followerOnlyProp")).as("server %d", i).isEqualTo("x");
      }
      try (final ResultSet rs = db.query("sql", "SELECT leaderOnlyProp FROM " + EXPLICIT_TRANSACTION_TYPE + " WHERE name = 'leader'")) {
        assertThat(rs.next().<Number>getProperty("leaderOnlyProp").intValue()).as("server %d", i).isEqualTo(1);
      }
    }
  }

  // Regression: #10040
  /** Outside a block nothing changes: an auto-commit write is still forwarded and committed on the leader. */
  @Test
  @Timeout(180)
  void anAutoCommitWriteOnAFollowerIsStillCommitted() throws Exception {
    final int leader = requireLeaderIndex();
    final int follower = prepareFollower(leader, EXPLICIT_TRANSACTION_TYPE);

    try (final Connection connection = connect(follower, "simple"); final Statement statement = connection.createStatement()) {
      statement.execute("INSERT INTO " + EXPLICIT_TRANSACTION_TYPE + " SET name = 'auto'");
    }

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(count(i, EXPLICIT_TRANSACTION_TYPE, "auto")).as("server %d must hold the auto-committed row", i).isEqualTo(1L);
  }

  /*
   * Issue #11483 on an HA follower: the statements of one simple Query message run in a single implicit transaction, and a
   * follower forwards a write to the leader as a request of its own unless the transaction it runs in is the client's
   * (issue #10040). Left unmarked, the implicit transaction of a message let a write commit on the leader before a later
   * statement of the same message failed, and the rollback that followed found nothing to undo.
   */
  private static final String MULTI_STATEMENT_TYPE = "Issue11483Ha";

  // Regression: #11483
  @Test
  @Timeout(180)
  void aFailureLaterInTheMessageUndoesAWriteSentToAFollower() throws Exception {
    final int follower = prepareFollower(requireLeaderIndex(), MULTI_STATEMENT_TYPE);

    try (final Socket socket = connectSocket(follower)) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());
      authenticate(out, in);

      sendSimpleQuery(out, "INSERT INTO " + MULTI_STATEMENT_TYPE + " SET name = 'undone'; SET x");
      final List<WireMessage> response = readUntilReadyForQuery(in);
      assertThat(messageTypesOf(response)).contains('E');
      assertThat(readyForQueryStatusOf(response)).isEqualTo('I');
    }

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(count(i, MULTI_STATEMENT_TYPE, "undone")).as("server %d must hold no row of the failed message", i).isZero();
  }

  // Regression: #11483
  @Test
  @Timeout(180)
  void aMessageThatSucceedsOnAFollowerIsCommittedEverywhere() throws Exception {
    final int follower = prepareFollower(requireLeaderIndex(), MULTI_STATEMENT_TYPE);

    try (final Socket socket = connectSocket(follower)) {
      final DataOutputStream out = new DataOutputStream(socket.getOutputStream());
      final DataInputStream in = new DataInputStream(socket.getInputStream());
      authenticate(out, in);

      sendSimpleQuery(out, "INSERT INTO " + MULTI_STATEMENT_TYPE + " SET name = 'kept'; INSERT INTO " + MULTI_STATEMENT_TYPE + " SET name = 'kept'");
      final List<WireMessage> response = readUntilReadyForQuery(in);
      assertThat(messageTypesOf(response)).doesNotContain('E');
      assertThat(readyForQueryStatusOf(response)).isEqualTo('I');
    }

    waitForAllServers();
    for (int i = 0; i < getServerCount(); i++)
      assertThat(count(i, MULTI_STATEMENT_TYPE, "kept")).as("server %d must hold both rows of the message", i).isEqualTo(2L);
  }

  private void authenticate(final DataOutputStream out, final DataInputStream in) throws Exception {
    sendStartupMessage(out, "root", getDatabaseName());
    readMessage(in); // AuthenticationCleartextPassword
    sendPasswordMessage(out, DEFAULT_PASSWORD_FOR_TESTS);
    readMessageOfType(in, 'Z'); // drain AuthenticationOk/BackendKeyData/ParameterStatus.../ReadyForQuery
  }

  /** Creates the target type through the leader and waits for the follower to see it, then returns the follower. */
  private int prepareFollower(final int leader, final String type) {
    final Database leaderDb = getServerDatabase(leader, getDatabaseName());
    leaderDb.command("sql", "CREATE DOCUMENT TYPE " + type + " IF NOT EXISTS");
    waitForAllServers();
    return firstFollowerOf(leader);
  }

  private long count(final int serverIndex, final String type, final String name) {
    final Database db = getServerDatabase(serverIndex, getDatabaseName());
    try (final ResultSet rs = db.query("sql", "SELECT count(@rid) AS c FROM " + type + " WHERE name = ?", name)) {
      return rs.next().<Number>getProperty("c").longValue();
    }
  }

  private Connection connect(final int serverIndex, final String queryMode) throws SQLException {
    return connect(serverIndex, queryMode, null);
  }

  /** @param language the query language of the connection, or null for the default of the protocol */
  private Connection connect(final int serverIndex, final String queryMode, final String language) throws SQLException {
    final Properties props = new Properties();
    props.setProperty("user", "root");
    props.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    props.setProperty("sslmode", "disable");
    props.setProperty("preferQueryMode", queryMode);
    if (language != null)
      props.setProperty("options", "-c arcadedb.language=" + language);
    return DriverManager.getConnection("jdbc:postgresql://localhost:" + postgresPorts[serverIndex] + "/" + getDatabaseName(), props);
  }

  private Socket connectSocket(final int serverIndex) throws Exception {
    final Socket socket = new Socket();
    socket.connect(new InetSocketAddress("localhost", postgresPorts[serverIndex]), 2000);
    return socket;
  }
}
