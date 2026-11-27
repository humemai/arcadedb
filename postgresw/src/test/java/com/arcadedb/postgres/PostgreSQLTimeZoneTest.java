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
import org.postgresql.util.PGobject;
import org.testcontainers.containers.GenericContainer;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code timestamptz}, {@code now()}, and the clock in sessions that are not in UTC, against PostgreSQL 18 over the wire (fork
 * issue #270): the zone from {@code SET TIME ZONE} and from the startup packet, in every form that names one, with the daylight
 * saving changes of 2026.
 */
class PostgreSQLTimeZoneTest extends PostgreSQLSideBySideTestBase {
  /** Names, a name in another case, offsets with minutes, a half hour daylight saving change, and every form of an offset. */
  private static final List<String> ZONES = List.of("'Asia/Seoul'", "'America/New_York'", "'europe/london'", "'Asia/Kathmandu'",
      "'Australia/Lord_Howe'", "'America/Santiago'", "'Pacific/Kiritimati'", "'Etc/GMT+9'", "'gmt'", "9", "-3.5", "'9'",
      "'+09:00'", "'-05:45'", "'UTC+3'", "'<+0545>-05:45'", "INTERVAL '+05:30' HOUR TO MINUTE", "'UTC'");

  /** Written in New York: local times around both changes, which a text without a zone gives, and instants with one. */
  private static final List<String> SETUP = List.of(
      "SET TIME ZONE 'America/New_York'",
      "CREATE TABLE z (id int PRIMARY KEY, at timestamptz, plain timestamp, d date)",
      "CREATE INDEX ON z (at)",
      "INSERT INTO z VALUES (1, '2026-03-08 01:59:59', '2026-03-08 01:59:59', '2026-03-08'),"
          + " (2, '2026-03-08 02:00:00', '2026-03-08 02:00:00', '2026-03-09'),"
          + " (3, '2026-03-08 02:30:00', '2026-03-08 02:30:00', '2026-11-01'),"
          + " (4, '2026-03-08 03:00:00', '2026-03-08 03:00:00', '2026-10-31'),"
          + " (5, '2026-11-01 00:59:59', '2026-11-01 00:59:59', '2026-04-05'),"
          + " (6, '2026-11-01 01:00:00', '2026-11-01 01:00:00', '2026-10-04'),"
          + " (7, '2026-11-01 01:30:00.123456', '2026-11-01 01:30:00', '2026-01-01'),"
          + " (8, '2026-11-01 02:00:00', '2026-11-01 02:00:00', '2026-07-01'),"
          + " (9, '2026-11-01 01:30:00-04', '2026-11-01 01:30:00-04', NULL),"
          + " (10, '2026-11-01 01:30:00-05', NULL, NULL),"
          + " (11, '2026-03-01 10:00:00Z', '2026-03-01 10:00:00Z', '2026-03-01'),"
          + " (12, '1900-01-01 12:00:00', '1900-01-01 12:00:00', '1900-01-01'),"
          + " (13, NULL, NULL, NULL)",
      "SET TIME ZONE 'Australia/Lord_Howe'",
      "INSERT INTO z (id, at) VALUES (20, '2026-10-04 02:15:00'), (21, '2026-04-05 01:45:00')",
      "SET TIME ZONE 'Asia/Seoul'",
      "INSERT INTO z (id, at) VALUES (30, '2026-03-02'), (31, date '2026-03-03'), (32, timestamp '2026-03-04 05:06:07')",
      "INSERT INTO z (id, plain) VALUES (33, timestamptz '2026-03-01 23:00:00Z')",
      "INSERT INTO z (id, d) VALUES (34, (timestamptz '2026-03-01 23:00:00Z')::date)",
      "SET TIME ZONE 9",
      "UPDATE z SET at = '2026-05-01 12:00:00' WHERE id = 33");

  private static final List<String> QUERIES = List.of(
      "SELECT id, at FROM z ORDER BY id",
      "SELECT id, at::text AS t, at::date AS d, at::timestamp AS p, at::timestamptz(0) AS r FROM z ORDER BY id",
      "SELECT id, plain::timestamptz AS a, d::timestamptz AS b FROM z ORDER BY id",
      "SELECT id, extract(year FROM at) AS y, extract(month FROM at) AS mo, extract(day FROM at) AS d,"
          + " extract(hour FROM at) AS h, extract(minute FROM at) AS mi, extract(dow FROM at) AS w, extract(epoch FROM at) AS e,"
          + " extract(timezone FROM at) AS z, extract(timezone_hour FROM at) AS zh, extract(timezone_minute FROM at) AS zm,"
          + " date_part('hour', at) AS p FROM z WHERE at IS NOT NULL ORDER BY id",
      "SELECT id, at + interval '1 day' AS a, at + interval '24 hours' AS b, at - interval '1 day' AS c,"
          + " at + interval '1 month' AS e, at - interval '8 months' AS f, at + interval '1 day 1 hour' AS g,"
          + " at + interval '30 minutes' AS h FROM z WHERE at IS NOT NULL ORDER BY id",
      "SELECT id FROM z WHERE at = '2026-11-01 01:30:00' ORDER BY id",
      "SELECT id FROM z WHERE at >= '2026-03-08 02:30:00' AND at < '2026-11-01 01:30:00' ORDER BY id",
      "SELECT id FROM z WHERE at > timestamp '2026-03-08 03:00:00' ORDER BY id",
      "SELECT id FROM z WHERE at >= date '2026-11-01' ORDER BY id",
      "SELECT id FROM z WHERE at = plain ORDER BY id",
      "SELECT id FROM z WHERE d = at::date ORDER BY id",
      "SELECT id FROM z WHERE at IN ('2026-03-02', '2026-03-01 10:00:00Z') ORDER BY id",
      "SELECT a.id AS a, b.id AS b FROM z a JOIN z b ON a.plain = b.at ORDER BY 1, 2",
      "SELECT max(at) AS newest, min(at) AS oldest FROM z",
      "WITH days AS (SELECT at::date AS day FROM z) SELECT day, count(*) AS n FROM days GROUP BY day ORDER BY day",
      "SELECT coalesce(at, date '2026-01-01') AS v FROM z WHERE id IN (1, 13) ORDER BY id",
      "SELECT at FROM z WHERE id = 1 UNION SELECT timestamp '2026-11-01 01:30:00' ORDER BY 1",
      "SELECT '2026-03-08 02:30:00'::timestamptz AS gap, '2026-11-01 01:30:00'::timestamptz AS twice,"
          + " '2026-06-01'::timestamptz AS day, '2026-06-01 10:00:00+02'::timestamptz AS zoned",
      "SELECT '2026-03-01 10:00:00'::timestamptz || ' text' AS t, ARRAY[timestamptz '2026-03-01 10:00:00'] AS a",
      "SELECT timestamptz '2026-11-01 00:30:00-04' + interval '1 hour' AS a,"
          + " timestamptz '2026-10-31 01:30:00-04' + interval '1 day' AS b");

  /** What the clock and its defaults answer, compared within one transaction, in which every clock function has one value. */
  private static final String CLOCK = "SELECT CURRENT_DATE = now()::date AS a, LOCALTIMESTAMP = now()::timestamp AS b,"
      + " CURRENT_TIMESTAMP = now() AS c, CURRENT_DATE AS d, extract(timezone FROM now()) AS z";

  private static final String DEFAULTS = "SELECT a = now() AS a, b = CURRENT_DATE::timestamptz AS b,"
      + " c = LOCALTIMESTAMP::timestamptz AS c, d = LOCALTIMESTAMP AS d, e = CURRENT_DATE AS e, f = now()::timestamp AS f FROM c"
      + " WHERE id = ";

  @Test
  void statementsAnswerAsPostgreSqlDoesInEveryZone() throws Exception {
    try (final GenericContainer<?> server = startPostgres();
        final Connection postgres = postgres(server, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SETUP)
        both(postgres, arcade, statement);
      both(postgres, arcade, "CREATE TABLE c (id int PRIMARY KEY, a timestamptz DEFAULT now(),"
          + " b timestamptz DEFAULT CURRENT_DATE, c timestamptz DEFAULT LOCALTIMESTAMP, d timestamp DEFAULT CURRENT_TIMESTAMP,"
          + " e date DEFAULT CURRENT_DATE, f timestamp DEFAULT now())");
      assertThat(failures).as(String.join("\n", failures)).isEmpty();

      int id = 0;
      for (final String zone : ZONES) {
        final String set = "SET TIME ZONE " + zone;
        both(postgres, arcade, set);
        final int before = failures.size();
        sameValues(postgres, arcade, "SHOW timezone");
        for (final String query : QUERIES)
          same(postgres, arcade, query);

        ++id;
        for (final Connection connection : List.of(postgres, arcade))
          connection.setAutoCommit(false);
        both(postgres, arcade, "INSERT INTO c (id) VALUES (" + id + ")");
        same(postgres, arcade, CLOCK);
        same(postgres, arcade, DEFAULTS + id);
        for (final Connection connection : List.of(postgres, arcade)) {
          connection.commit();
          connection.setAutoCommit(true);
        }
        for (int i = before; i < failures.size(); i++)
          failures.set(i, set + ": " + failures.get(i));
      }

      // the value of a SET of a block that is rolled back is undone, and SET LOCAL ends with the block
      for (final String statement : List.of("SET TIME ZONE 'Asia/Seoul'", "BEGIN", "SET TIME ZONE 'America/New_York'", "ROLLBACK",
          "BEGIN", "SET LOCAL TIME ZONE 'Asia/Kathmandu'")) {
        both(postgres, arcade, statement);
        same(postgres, arcade, "SELECT at FROM z WHERE id = 11");
      }
      both(postgres, arcade, "COMMIT");
      same(postgres, arcade, "SELECT at FROM z WHERE id = 11");

      for (final String zone : List.of("'Mars/Olympus'", "'KST'", "'Z'", "'09:75'"))
        sameError(postgres, arcade, "SET TIME ZONE " + zone, "22023");
      // a refused SET leaves the zone as it was
      same(postgres, arcade, "SELECT at FROM z WHERE id = 11");

      // what PostgreSQL reads and the language does not
      refused(arcade, "SET TIME ZONE 'EST5EDT4,M3.2.0,M11.1.0'", "Asia/Seoul");
      refused(arcade, "SET TIME ZONE 19", "Asia/Seoul");
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  /** The Java driver names the zone of its process when it connects, a custom zone of Java in the sign of POSIX. */
  @Test
  void theZoneOfTheStartupPacketCountsAsOnPostgreSql() throws Exception {
    final TimeZone before = TimeZone.getDefault();
    try (final GenericContainer<?> server = startPostgres()) {
      int table = 0;
      for (final String zone : List.of("Asia/Seoul", "America/New_York", "Asia/Kolkata", "GMT+09:00", "GMT-03:30")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try (final Connection postgres = postgres(server, ""); final Connection arcade = arcade("")) {
          // the helpers set UTC after they connect, and RESET goes back to the zone of the startup packet
          both(postgres, arcade, "RESET timezone");
          sameValues(postgres, arcade, "SHOW timezone");
          final String name = "s" + ++table;
          both(postgres, arcade, "CREATE TABLE " + name + " (id int PRIMARY KEY, at timestamptz, plain timestamp)");
          compare(zone + ": the values that the driver binds and reads", bindAndRead(postgres, name), bindAndRead(arcade, name));
          // the driver turns to the binary format of a prepared statement after the first run
          try (final Connection binaryPostgres = postgres(server, "?prepareThreshold=1");
              final Connection binaryArcade = arcade("?prepareThreshold=1")) {
            both(binaryPostgres, binaryArcade, "RESET timezone");
            compare(zone + ": in the binary format", read(binaryPostgres, name), read(binaryArcade, name));
          }
          same(postgres, arcade, "SELECT id, at, plain, now()::date = CURRENT_DATE AS today FROM " + name + " ORDER BY id");
        }
      }

      // a zone that names none fails the connection
      TimeZone.setDefault(new SimpleTimeZone(0, "Mars/Olympus"));
      final String expected = connectionState(() -> postgres(server, "").close());
      final String actual = connectionState(() -> arcade("").close());
      compare("a connection with a time zone that names none", "22023", actual);
      assertThat(expected).contains("22023");
    } finally {
      TimeZone.setDefault(before);
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private interface Connect {
    void run() throws Exception;
  }

  /** The SQLSTATE that connecting fails with, found through the causes of the exception. */
  private static String connectionState(final Connect connect) {
    try {
      connect.run();
      return "00000";
    } catch (final Exception e) {
      for (Throwable cause = e; cause != null; cause = cause.getCause())
        if (cause instanceof SQLException sql && sql.getSQLState() != null)
          return sql.getSQLState();
      return e.toString();
    }
  }

  private static String bindAndRead(final Connection connection, final String table) {
    try {
      try (final PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO " + table + " (id, at, plain) VALUES (?, ?, ?)")) {
        int id = 0;
        // a Timestamp carries the offset of the Java zone, in the gap and in the repeated hour of New York too
        for (final String local : List.of("2026-01-31 00:00:00", "2026-03-08 02:30:00", "2026-11-01 01:30:00")) {
          insert.setInt(1, ++id);
          insert.setTimestamp(2, Timestamp.valueOf(local));
          insert.setTimestamp(3, Timestamp.valueOf(local));
          insert.executeUpdate();
        }
        insert.setInt(1, ++id);
        insert.setObject(2, OffsetDateTime.of(2026, 2, 1, 9, 30, 15, 123_456_000, ZoneOffset.ofHours(9)));
        insert.setObject(3, OffsetDateTime.of(2026, 2, 1, 9, 30, 0, 0, ZoneOffset.ofHours(9)));
        insert.executeUpdate();
        insert.setInt(1, ++id);
        insert.setObject(2, LocalDateTime.of(2026, 11, 1, 1, 30));
        insert.setObject(3, LocalDateTime.of(2026, 11, 1, 1, 30));
        insert.executeUpdate();
        // text without a zone, of no declared type and declared as timestamptz: a time of the session
        insert.setInt(1, ++id);
        insert.setObject(2, "2026-11-01 01:30:00", Types.OTHER);
        insert.setObject(3, "2026-11-01 01:30:00", Types.OTHER);
        insert.executeUpdate();
        insert.setInt(1, ++id);
        insert.setObject(2, object("timestamptz", "2026-03-08 02:30:00"));
        insert.setNull(3, Types.TIMESTAMP);
        insert.executeUpdate();
      }
      return read(connection, table);
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }

  private static String read(final Connection connection, final String table) {
    final StringBuilder out = new StringBuilder();
    try {
      try (final PreparedStatement select = connection.prepareStatement("SELECT id, at, plain FROM " + table + " ORDER BY id")) {
        for (int run = 0; run < 3; run++)
          try (final ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              final OffsetDateTime at = rows.getObject(2, OffsetDateTime.class);
              out.append("    ").append(rows.getInt(1)).append('|').append(at == null ? null : at.toInstant()).append('|')
                  .append(rows.getTimestamp(2)).append('|').append(rows.getTimestamp(3)).append('\n');
            }
          }
      }
      for (final Object bound : List.of(Timestamp.valueOf("2026-03-08 02:30:00"), LocalDateTime.of(2026, 11, 1, 1, 30),
          OffsetDateTime.of(2026, 3, 1, 12, 30, 0, 0, ZoneOffset.ofHours(2)), object("timestamptz", "2026-11-01 01:30:00")))
        try (final PreparedStatement select = connection.prepareStatement(
            "SELECT id FROM " + table + " WHERE at >= ? ORDER BY id")) {
          select.setObject(1, bound);
          try (final ResultSet rows = select.executeQuery()) {
            out.append(text(rows, false));
          }
        }
      try (final PreparedStatement select = connection.prepareStatement(
          "SELECT id FROM " + table + " WHERE at = ANY(?) ORDER BY id")) {
        final Array times = connection.createArrayOf("timestamptz", new String[] { "2026-11-01 01:30:00", "2026-01-31 00:00:00" });
        select.setArray(1, times);
        try (final ResultSet rows = select.executeQuery()) {
          out.append(text(rows, false));
        }
      }
    } catch (final SQLException e) {
      out.append("    error ").append(e.getSQLState()).append(' ').append(e.getMessage()).append('\n');
    }
    return out.toString();
  }

  private static PGobject object(final String type, final String value) throws SQLException {
    final PGobject object = new PGobject();
    object.setType(type);
    object.setValue(value);
    return object;
  }

  private static final String SCRIPT = """
      import datetime as dt
      import sys
      import psycopg
      import sqlalchemy as sa

      CONNINFO = sys.argv[1]
      UTC = dt.timezone.utc


      def show(label, rows):
          print(label + "|" + str([tuple(str(value) for value in row) for row in rows]), flush=True)


      with psycopg.connect(CONNINFO, autocommit=True) as conn:
          conn.execute("CREATE TABLE py (id int PRIMARY KEY, at timestamptz, plain timestamp)")
          for number, zone in enumerate(["America/New_York", "Asia/Seoul", "Asia/Kathmandu"]):
              conn.execute("SET TIME ZONE '" + zone + "'")
              print(zone + "|" + conn.info.parameter_status("TimeZone") + "|" + str(conn.info.timezone), flush=True)
              aware = dt.datetime(2026, 11, 1, 5, 30, tzinfo=UTC)
              naive = dt.datetime(2026, 11, 1, 1, 30)
              insert = "INSERT INTO py (id, at, plain) VALUES (%s, %s, %s)"
              first = 10 * number
              conn.execute(insert, (first + 1, aware, aware))
              conn.execute(insert, (first + 2, naive, naive))
              conn.execute(insert, (first + 3, "2026-03-08 02:30:00", "2026-03-08 02:30:00"))
              conn.execute("INSERT INTO py (id, at, plain) VALUES (%b, %b, %b)", (first + 4, aware, naive))
              show("rows", conn.execute("SELECT id, at, plain FROM py ORDER BY id").fetchall())
              show("binary", conn.cursor(binary=True).execute("SELECT id, at, plain FROM py ORDER BY id").fetchall())
              show("after", conn.execute("SELECT id FROM py WHERE at > %s ORDER BY id", (naive,)).fetchall())
              show("after binary", conn.execute("SELECT id FROM py WHERE at > %b ORDER BY id", (aware,)).fetchall())
              show("clock", conn.execute("SELECT now()::date = CURRENT_DATE, LOCALTIMESTAMP = now()::timestamp").fetchall())

      # SQLAlchemy with its defaults
      engine = sa.create_engine("postgresql+psycopg://", creator=lambda: psycopg.connect(CONNINFO))
      table = sa.Table("py", sa.MetaData(), sa.Column("id", sa.Integer, primary_key=True),
                       sa.Column("at", sa.DateTime(timezone=True)), sa.Column("plain", sa.DateTime()))
      with engine.begin() as conn:
          conn.execute(sa.text("SET TIME ZONE 'Australia/Lord_Howe'"))
          conn.execute(table.insert().values(id=100, at=dt.datetime(2026, 10, 4, 2, 15), plain=dt.datetime(2026, 10, 4, 2, 15)))
          conn.execute(table.insert().values(id=101, at=dt.datetime(2026, 5, 1, tzinfo=UTC),
                                             plain=dt.datetime(2026, 5, 1, tzinfo=UTC)))
          show("sqlalchemy", conn.execute(sa.select(table).where(table.c.id >= 100).order_by(table.c.id)).fetchall())
          show("sqlalchemy now", conn.execute(sa.select(sa.func.now() > dt.datetime(2026, 1, 1))).fetchall())
      """;

  @Test
  void psycopgAndSqlAlchemyAnswerAsOnPostgreSqlInOtherZones() throws Exception {
    try (final GenericContainer<?> server = startPostgres()) {
      python(server, SCRIPT);
    }
  }
}
