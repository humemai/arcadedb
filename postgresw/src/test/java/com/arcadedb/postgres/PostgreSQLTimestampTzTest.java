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
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code timestamptz}, timestamp text with a time zone, and datetime parameters with one, against PostgreSQL 18 (fork issue
 * #223): the answers of statements over JDBC, the values that drivers bind and read, and the refusals of what the language
 * cannot answer as PostgreSQL does.
 */
class PostgreSQLTimestampTzTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE p29 (id int PRIMARY KEY, at timestamptz)",
      "CREATE TABLE t (id int PRIMARY KEY, at timestamp)",
      "CREATE TABLE w (id int PRIMARY KEY, at timestamp with time zone UNIQUE, plain timestamp without time zone)",
      "CREATE INDEX ON p29 (at)",
      "INSERT INTO t (id, at) VALUES (10, '2026-03-01T10:00:00')",
      "INSERT INTO t (id, at) VALUES (11, '2026-03-01T10:00:00.000Z')",
      "INSERT INTO t (id, at) VALUES (12, '2026-03-01T10:00:00+00:00')",
      "INSERT INTO t (id, at) VALUES (13, '2026-03-01 10:00:00+05:30'), (14, '2026-03-01 23:30:00 -07')",
      "INSERT INTO p29 VALUES (1, '2026-03-01T10:00:00Z'), (2, '2026-03-01 12:00:00+02'), (3, '2026-03-01 04:30:00-0530'),"
          + " (4, '2026-03-01 10:00:00'), (5, '2026-03-02'), (6, NULL), (7, '2026-03-01 10:00:00.1234567 UTC'),"
          + " (8, '2026-02-28 23:00:00.5-02:00'), (9, '2026-01-01T00:00:00z'), (10, '2026-01-01 00:00:00+15:59:59'),"
          + " (11, '2026-01-01 00:00 +1'), (12, '2026-01-01 00:00:00 +200')",
      "INSERT INTO w VALUES (1, '2026-03-01 10:00:00+01', '2026-03-01 10:00:00+01')",
      "UPDATE p29 SET at = '2026-04-01 00:00:00+02' WHERE id = 6",
      "UPDATE w SET plain = timestamptz '2026-05-01 10:00:00+01' WHERE id = 1");

  private static final List<String> QUERIES = List.of(
      "SELECT id, at FROM p29 ORDER BY id",
      "SELECT id, at FROM t ORDER BY id",
      "SELECT id, at, plain FROM w",
      "SELECT id FROM p29 WHERE at = '2026-03-01 11:00:00+01' ORDER BY id",
      "SELECT id FROM p29 WHERE at > timestamp '2026-03-01 09:00:00' ORDER BY id",
      "SELECT id FROM p29 WHERE at > date '2026-03-01' ORDER BY id",
      "SELECT id FROM p29 WHERE at BETWEEN '2026-01-01 00:00Z' AND '2026-01-02' ORDER BY id",
      "SELECT id FROM p29 WHERE at IN ('2026-03-01 10:00:00Z', '2026-03-02 00:00:00+00') ORDER BY id",
      "SELECT id FROM t WHERE at = timestamptz '2026-03-01 10:00:00Z' ORDER BY id",
      "SELECT id FROM t WHERE at = '2026-03-01 10:00:00+09' ORDER BY id",
      "SELECT '2026-03-01T10:00:00+05:00'::timestamptz AS a, '2026-03-01T10:00:00+05:00'::timestamp AS b,"
          + " CAST('2026-01-01' AS timestamp with time zone) AS c, TIMESTAMP WITH TIME ZONE '2026-01-01 08:00:00-08' AS d",
      "SELECT at::text AS a, at::date AS b, at::timestamp AS c, at::timestamptz AS d FROM p29 WHERE id = 8",
      "SELECT date '2026-01-02'::timestamptz AS a, timestamp '2026-01-02 03:04:05'::timestamptz AS b",
      "SELECT '2026-03-01 10:00:00.5555555+00'::timestamptz(3) AS a, '2026-03-01 10:00:00.5+00'::timestamptz(0) AS b",
      "SELECT at + interval '1 day' AS a, at - interval '90 minutes' AS b, at + interval '1 month' AS c FROM p29 WHERE id = 1",
      "SELECT date '2026-01-01' + interval '1 day' AS a, timestamptz '2026-01-01 00:00:00+00' + interval '1 hour' AS b",
      "SELECT extract(epoch FROM at) AS e, extract(hour FROM at) AS h, extract(day FROM at) AS d,"
          + " extract(timezone FROM at) AS z FROM p29 WHERE id = 8",
      "SELECT max(at) AS newest, min(at) AS oldest, count(at) AS n FROM p29",
      "SELECT CASE WHEN id = 1 THEN at ELSE timestamp '2000-01-01' END AS v FROM p29 WHERE id IN (1, 2) ORDER BY id",
      "SELECT coalesce(at, date '2000-01-01') AS v FROM p29 WHERE id < 3 ORDER BY id",
      "SELECT at FROM p29 WHERE id = 1 UNION SELECT timestamp '2000-01-01' ORDER BY 1",
      "SELECT DISTINCT at FROM p29 WHERE id < 3",
      "SELECT at, count(*) AS n FROM p29 WHERE id < 5 GROUP BY at ORDER BY at DESC",
      "SELECT t.id AS t, p.id AS p FROM t JOIN p29 p ON t.at = p.at ORDER BY 1, 2",
      "SELECT ARRAY[timestamptz '2026-03-01 10:00:00+02'] AS a");

  private static final List<String> COLUMNS = List.of(
      "SELECT column_name, data_type, udt_name, datetime_precision FROM information_schema.columns"
          + " WHERE table_name = 'w' ORDER BY ordinal_position",
      "SELECT a.attname, t.typname FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid = a.atttypid"
          + " JOIN pg_catalog.pg_class c ON c.oid = a.attrelid WHERE c.relname = 'w' AND a.attnum > 0 ORDER BY a.attnum");

  @Test
  void statementsAnswerAsPostgreSqlDoes() throws Exception {
    try (final GenericContainer<?> server = startPostgres();
        final Connection postgres = postgres(server, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SETUP)
        both(postgres, arcade, statement);
      for (final String query : QUERIES)
        same(postgres, arcade, query);
      for (final String query : COLUMNS)
        sameValues(postgres, arcade, query);

      sameError(postgres, arcade, "SELECT '2026-03-01 10:00:00+16'::timestamptz", "22009");
      sameError(postgres, arcade, "SELECT '2026-03-01 10:00:00+16'::timestamp", "22009");
      sameError(postgres, arcade, "SELECT '2026-03-01 10:00:00+02:75'::timestamptz", "22009");
      sameError(postgres, arcade, "SELECT '2026-02-30T10:00:00Z'::timestamptz", "22008");
      sameError(postgres, arcade, "SELECT 'abc'::timestamptz", "22007");
      sameError(postgres, arcade, "SELECT 1::timestamptz", "42846");
      sameError(postgres, arcade, "INSERT INTO p29 (id, at) VALUES (99, 5)", "42804");
      sameError(postgres, arcade, "INSERT INTO w (id, at) VALUES (2, '2026-03-01 09:00:00Z')", "23505");

      // what PostgreSQL reads and the language does not: the refusal says what to write
      refused(arcade, "SELECT '2026-03-01 10:00:00 Europe/Amsterdam'::timestamptz", "+02:00");
      refused(arcade, "SELECT '2026-03-01 10:00:00 PST'::timestamptz", "+02:00");
      refused(arcade, "CREATE TABLE a (at timestamptz[])", "timestamp[]");
      refused(arcade, "SELECT '0001-01-01 00:00:00+05'::timestamptz", "year");
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  @Test
  void theJdbcDriverBindsAndReadsInstants() throws Exception {
    try (final GenericContainer<?> server = startPostgres();
        final Connection postgres = postgres(server, "");
        final Connection arcade = arcade("")) {
      both(postgres, arcade, "CREATE TABLE j (id int PRIMARY KEY, at timestamptz, plain timestamp)");
      assertThat(failures).as(String.join("\n", failures)).isEmpty();

      compare("the values that the driver binds and reads", bindAndRead(postgres), bindAndRead(arcade));
      // the driver turns to the binary format of a prepared statement after the first run
      try (final Connection binaryPostgres = postgres(server, "?prepareThreshold=1");
          final Connection binaryArcade = arcade("?prepareThreshold=1")) {
        compare("the values in the binary format", read(binaryPostgres), read(binaryArcade));
      }

      // sessions in other zones, which show and read local times (fork issue #270)
      for (final String zone : List.of("Asia/Seoul", "America/New_York", "Etc/UTC")) {
        both(postgres, arcade, "SET TIME ZONE '" + zone + "'");
        same(postgres, arcade, "SELECT id, at, plain FROM j ORDER BY id");
        both(postgres, arcade, "DELETE FROM j");
        compare(zone + ": the values that the driver binds and reads", bindAndRead(postgres), bindAndRead(arcade));
      }
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private static String bindAndRead(final Connection connection) {
    try {
      try (final PreparedStatement insert = connection.prepareStatement("INSERT INTO j (id, at, plain) VALUES (?, ?, ?)")) {
        final ParameterMetaData parameters = insert.getParameterMetaData();
        final List<String> types = new ArrayList<>();
        for (int i = 1; i <= parameters.getParameterCount(); i++)
          types.add(parameters.getParameterTypeName(i));

        insert.setInt(1, 1);
        insert.setTimestamp(2, Timestamp.valueOf("2026-01-31 00:00:00"));
        insert.setTimestamp(3, Timestamp.valueOf("2026-01-31 00:00:00"));
        insert.executeUpdate();
        insert.setInt(1, 2);
        insert.setObject(2, OffsetDateTime.of(2026, 2, 1, 9, 30, 15, 123_456_000, ZoneOffset.ofHours(9)));
        insert.setObject(3, OffsetDateTime.of(2026, 2, 1, 9, 30, 0, 0, ZoneOffset.ofHours(9)));
        insert.executeUpdate();
        insert.setInt(1, 3);
        insert.setObject(2, LocalDateTime.of(2026, 3, 1, 10, 30));
        insert.setObject(3, LocalDateTime.of(2026, 3, 1, 10, 30));
        insert.executeUpdate();
        insert.setInt(1, 4);
        insert.setObject(2, "2026-04-01T10:00:00.000Z", Types.OTHER);
        insert.setObject(3, "2026-04-01T10:00:00+02:00", Types.OTHER);
        insert.executeUpdate();
        insert.setInt(1, 5);
        insert.setNull(2, Types.TIMESTAMP);
        insert.setNull(3, Types.TIMESTAMP);
        insert.executeUpdate();
        return "    " + types + "\n" + read(connection);
      }
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }

  private static String read(final Connection connection) {
    final StringBuilder out = new StringBuilder();
    try {
      try (final PreparedStatement select = connection.prepareStatement("SELECT id, at, plain FROM j ORDER BY id")) {
        for (int run = 0; run < 3; run++)
          try (final ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
              final OffsetDateTime at = rows.getObject(2, OffsetDateTime.class);
              final Timestamp instant = rows.getTimestamp(2);
              out.append("    ").append(rows.getInt(1)).append('|').append(at == null ? null : at.toInstant()).append('|')
                  .append(instant == null ? null : instant.toInstant()).append('|').append(rows.getTimestamp(3)).append('\n');
            }
          }
      }
      for (final String comparison : List.of("at > ?", "at = ?", "plain > ?"))
        try (final PreparedStatement select = connection.prepareStatement("SELECT id FROM j WHERE " + comparison + " ORDER BY id")) {
          select.setTimestamp(1, Timestamp.valueOf("2026-01-31 00:00:00"));
          try (final ResultSet rows = select.executeQuery()) {
            out.append(text(rows, false));
          }
          select.setObject(1, OffsetDateTime.of(2026, 3, 1, 12, 30, 0, 0, ZoneOffset.ofHours(2)));
          try (final ResultSet rows = select.executeQuery()) {
            out.append(text(rows, false));
          }
        }
    } catch (final SQLException e) {
      out.append("    error ").append(e.getSQLState()).append(' ').append(e.getMessage()).append('\n');
    }
    return out.toString();
  }

  private static final String SCRIPT = """
      import datetime as dt
      import sys
      import psycopg
      import sqlalchemy as sa

      CONNINFO = sys.argv[1]
      UTC = dt.timezone.utc
      NINE = dt.timezone(dt.timedelta(hours=9))


      def show(label, rows):
          print(label + "|" + str([tuple(str(value) for value in row) for row in rows]), flush=True)


      with psycopg.connect(CONNINFO, autocommit=True) as conn:
          conn.execute("CREATE TABLE py (id int PRIMARY KEY, at timestamptz, plain timestamp)")
          aware = dt.datetime(2026, 1, 31, 12, 0, tzinfo=UTC)
          ahead = dt.datetime(2026, 2, 1, 9, 30, 15, 123456, tzinfo=NINE)
          naive = dt.datetime(2026, 3, 1, 10, 0)
          insert = "INSERT INTO py (id, at, plain) VALUES (%s, %s, %s)"
          conn.execute(insert, (1, aware, aware))
          conn.execute(insert, (2, ahead, ahead))
          conn.execute(insert, (3, naive, naive))
          conn.execute(insert, (4, "2026-04-01T10:00:00.000Z", "2026-04-01T10:00:00+02:00"))
          conn.execute(insert, (5, None, None))
          conn.execute("INSERT INTO py (id, at, plain) VALUES (%b, %b, %b)", (6, ahead, ahead))
          conn.execute("INSERT INTO py (id, at, plain) VALUES (%t, %t, %t)", (7, ahead, ahead))
          show("rows", conn.execute("SELECT id, at, plain FROM py ORDER BY id").fetchall())
          show("binary", conn.cursor(binary=True).execute("SELECT id, at, plain FROM py ORDER BY id").fetchall())
          show("offset", [(row[0].utcoffset(),) for row in conn.execute("SELECT at FROM py WHERE id = 2").fetchall()])
          show("after", conn.execute("SELECT id FROM py WHERE at > %s ORDER BY id", (aware,)).fetchall())
          show("after binary", conn.execute("SELECT id FROM py WHERE at > %b ORDER BY id", (aware,)).fetchall())
          show("plain after", conn.execute("SELECT id FROM py WHERE plain > %s ORDER BY id", (aware,)).fetchall())
          show("equal", conn.execute("SELECT id FROM py WHERE at = %s ORDER BY id", (ahead,)).fetchall())

      # SQLAlchemy with its defaults: it casts each parameter to the type of its column
      engine = sa.create_engine("postgresql+psycopg://", creator=lambda: psycopg.connect(CONNINFO))
      table = sa.Table("py", sa.MetaData(), sa.Column("id", sa.Integer, primary_key=True),
                       sa.Column("at", sa.DateTime(timezone=True)), sa.Column("plain", sa.DateTime()))
      with engine.begin() as conn:
          conn.execute(table.insert().values(id=10, at=dt.datetime(2026, 5, 1, tzinfo=UTC),
                                             plain=dt.datetime(2026, 5, 1, tzinfo=UTC)))
          conn.execute(table.insert().values(id=11, at=dt.datetime(2026, 5, 2, 8, 0), plain=dt.datetime(2026, 5, 2, 8, 0)))
          conn.execute(table.update().where(table.c.id == 11).values(at=ahead))
      with engine.connect() as conn:
          since = dt.datetime(2026, 4, 1, tzinfo=UTC)
          show("sqlalchemy", conn.execute(sa.select(table).where(table.c.at >= since).order_by(table.c.id)).fetchall())
          show("sqlalchemy text", conn.execute(sa.text("SELECT id FROM py WHERE at < :at ORDER BY id"), {"at": since}).fetchall())
      """;

  @Test
  void psycopgAndSqlAlchemyAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres()) {
      python(server, SCRIPT);
    }
  }
}
