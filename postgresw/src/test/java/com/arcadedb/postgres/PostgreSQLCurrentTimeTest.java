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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code now()}, {@code CURRENT_TIMESTAMP}, {@code transaction_timestamp()}, {@code LOCALTIMESTAMP}, and {@code CURRENT_DATE},
 * in statements and as defaults, against PostgreSQL 18 (fork issues #222 and #190): one value for each transaction. The values
 * of two servers differ, so the statements compare them with each other and the answers are equal.
 */
class PostgreSQLCurrentTimeTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE d (id int PRIMARY KEY, a timestamptz DEFAULT now(), b timestamp DEFAULT CURRENT_TIMESTAMP,"
          + " c date DEFAULT CURRENT_DATE, e timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP, f timestamp DEFAULT LOCALTIMESTAMP,"
          + " g timestamptz DEFAULT transaction_timestamp(), h timestamptz DEFAULT CURRENT_DATE, i date DEFAULT now())",
      "INSERT INTO d (id) VALUES (1)",
      "INSERT INTO d (id, a, b) VALUES (2, DEFAULT, DEFAULT), (3, NULL, NULL)",
      "UPDATE d SET b = DEFAULT WHERE id = 1",
      "UPDATE d SET a = now() WHERE id = 2");

  private static final List<String> QUERIES = List.of(
      "SELECT now() = current_timestamp AS a, current_timestamp = transaction_timestamp() AS b",
      "SELECT now() > timestamptz '2026-01-01 00:00:00Z' AS recent, now() - interval '1 day' < now() AS before",
      "SELECT localtimestamp = now()::timestamp AS a, current_date = now()::date AS b, localtimestamp = localtimestamp(6) AS c",
      "SELECT current_timestamp(0)::text LIKE '%.%' AS fraction, now()::text LIKE '%+00' AS utc",
      "SELECT count(*) AS n FROM (SELECT DISTINCT now() AS v, localtimestamp AS w, current_date AS x FROM d) s",
      "SELECT count(*) AS n FROM d WHERE e <= now() AND e > now() - interval '1 hour'",
      "SELECT id, a = e AS ae, e = g AS eg, b = f AS bf, c = e::date AS ce, h = c::timestamptz AS hc, i = c AS ic,"
          + " b = e::timestamp AS be, a IS NULL AS an FROM d ORDER BY id");

  private static final String DEFAULTS = "SELECT column_name, data_type, column_default FROM information_schema.columns"
      + " WHERE table_name = 'd' ORDER BY ordinal_position";

  @Test
  void theClockAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres();
        final Connection postgres = postgres(server, "");
        final Connection arcade = arcade("")) {
      for (final String statement : SETUP)
        both(postgres, arcade, statement);
      for (final String query : QUERIES)
        same(postgres, arcade, query);
      sameValues(postgres, arcade, DEFAULTS);
      final String shape = "SELECT now(), CURRENT_TIMESTAMP, current_timestamp(3), transaction_timestamp(), LOCALTIMESTAMP,"
          + " CURRENT_DATE, now() + interval '1 hour' AS later";
      compare(shape, columns(postgres, shape), columns(arcade, shape));

      compare("a transaction of BEGIN and COMMIT", block(postgres, 10, false), block(arcade, 10, false));
      compare("a transaction of a connection that does not commit by itself", block(postgres, 20, true), block(arcade, 20, true));
      compare("statements that commit by themselves", alone(postgres), alone(arcade));

      sameError(postgres, arcade, "CREATE TABLE x (a int DEFAULT now())", "42804");
      sameError(postgres, arcade, "SELECT now(1)", "42883");
      refused(arcade, "CREATE TABLE x (a text DEFAULT now())", "timestamptz");
      refused(arcade, "CREATE TABLE x (a timestamptz DEFAULT CURRENT_TIMESTAMP(3))", "DEFAULT CURRENT_TIMESTAMP");

      // the default holds for the sql language too, in UTC
      final Database database = getServerDatabase(0, getDatabaseName());
      database.transaction(() -> database.command("sql", "CREATE VERTEX d SET id = 30"));
      assertThat(answer(arcade, "SELECT count(*) AS n FROM d WHERE id = 30 AND a > now() - interval '1 minute' AND a <= now()"
          + " AND e > now() - interval '1 minute' AND c = current_date AND i = current_date", false)).isEqualTo("    n|\n    1|\n");

      // a date and a timestamp default take the time of the session (fork issue #237)
      both(postgres, arcade, "CREATE TABLE z (id int PRIMARY KEY, l timestamp DEFAULT CURRENT_TIMESTAMP,"
          + " m timestamp DEFAULT LOCALTIMESTAMP, d date DEFAULT CURRENT_DATE)");
      compare("defaults in the time zone of the session", inZone(postgres), inZone(arcade));
      run(arcade, "SET TIME ZONE 'Asia/Seoul'");
      refused(arcade, "SELECT now()", "SET TIME ZONE 'UTC'");
      refused(arcade, "INSERT INTO d (id) VALUES (40)", "SET TIME ZONE 'UTC'");
      run(arcade, "SET TIME ZONE 'UTC'");
    }
    assertThat(failures).as(String.join("\n", failures)).isEmpty();
  }

  private static String inZone(final Connection connection) {
    try {
      run(connection, "SET TIME ZONE 'Pacific/Kiritimati'");
      run(connection, "BEGIN");
      run(connection, "INSERT INTO z (id) VALUES (1)");
      final String answer = answer(connection,
          "SELECT l = localtimestamp AS l, m = l AS m, d = current_date AS d, d = l::date AS e FROM z", false);
      run(connection, "COMMIT");
      // fourteen hours ahead of UTC
      run(connection, "SET TIME ZONE 'UTC'");
      return answer + answer(connection, "SELECT l > localtimestamp + interval '13 hours' AS ahead FROM z", false);
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }

  /** The labels and the types of the columns of a query. */
  private static String columns(final Connection connection, final String query) {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(query)) {
      final ResultSetMetaData columns = rows.getMetaData();
      final List<String> out = new ArrayList<>();
      for (int i = 1; i <= columns.getColumnCount(); i++)
        out.add(columns.getColumnLabel(i) + ":" + columns.getColumnTypeName(i));
      return "    " + out + "\n";
    } catch (final SQLException e) {
      return "    error " + e.getSQLState() + " " + e.getMessage() + "\n";
    }
  }

  private static String one(final Connection connection, final String query) throws SQLException {
    try (final Statement run = connection.createStatement(); final ResultSet rows = run.executeQuery(query)) {
      rows.next();
      return rows.getString(1);
    }
  }

  /** One transaction: every statement reads the same time, which a default writes too, and the next transaction a later one. */
  private static String block(final Connection connection, final int id, final boolean driverBegins) {
    try {
      if (driverBegins)
        connection.setAutoCommit(false);
      else
        run(connection, "BEGIN");
      final String first = one(connection, "SELECT now()");
      Thread.sleep(50);
      final String second = one(connection, "SELECT CURRENT_TIMESTAMP");
      run(connection, "INSERT INTO d (id) VALUES (" + id + ")");
      Thread.sleep(50);
      final String written = one(connection, "SELECT a FROM d WHERE id = " + id);
      final String local = one(connection, "SELECT localtimestamp = now()::timestamp");
      if (driverBegins) {
        connection.commit();
        connection.setAutoCommit(true);
      } else
        run(connection, "COMMIT");
      final String later = one(connection, "SELECT now() > a FROM d WHERE id = " + id);
      return "    " + first.equals(second) + " " + first.equals(written) + " " + local + " " + later + "\n";
    } catch (final SQLException | InterruptedException e) {
      return "    error " + e.getMessage() + "\n";
    }
  }

  private static String alone(final Connection connection) {
    try {
      final String first = one(connection, "SELECT now()");
      Thread.sleep(20);
      return "    " + !first.equals(one(connection, "SELECT now()")) + "\n";
    } catch (final SQLException | InterruptedException e) {
      return "    error " + e.getMessage() + "\n";
    }
  }

  private static final String SCRIPT = """
      import sys
      import time
      import psycopg
      import sqlalchemy as sa

      CONNINFO = sys.argv[1]


      def show(label, *values):
          print(label + "|" + " ".join(str(value) for value in values), flush=True)


      with psycopg.connect(CONNINFO, autocommit=True) as conn:
          conn.execute("CREATE TABLE py (id int PRIMARY KEY, at timestamptz DEFAULT now(), note text)")
          first = conn.execute("SELECT now()").fetchone()[0]
          time.sleep(0.05)
          show("autocommit", conn.execute("SELECT now()").fetchone()[0] > first, first.utcoffset())

      with psycopg.connect(CONNINFO) as conn:
          first = conn.execute("SELECT now()").fetchone()[0]
          time.sleep(0.05)
          second = conn.execute("SELECT current_timestamp").fetchone()[0]
          conn.execute("INSERT INTO py (id) VALUES (1)")
          written = conn.execute("SELECT at FROM py WHERE id = 1").fetchone()[0]
          show("transaction", first == second, first == written)
          conn.commit()
          show("next transaction", conn.execute("SELECT now()").fetchone()[0] > first)
          show("parameter", conn.execute("SELECT id FROM py WHERE at <= %s", (second,)).fetchall())

      # SQLAlchemy with its defaults
      engine = sa.create_engine("postgresql+psycopg://", creator=lambda: psycopg.connect(CONNINFO))
      table = sa.Table("py", sa.MetaData(), sa.Column("id", sa.Integer, primary_key=True),
                       sa.Column("at", sa.DateTime(timezone=True), server_default=sa.func.now()), sa.Column("note", sa.Text()))
      with engine.begin() as conn:
          first = conn.execute(sa.select(sa.func.now())).scalar()
          time.sleep(0.05)
          show("func.now", conn.execute(sa.select(sa.func.current_timestamp())).scalar() == first, first.utcoffset())
          conn.execute(table.insert().values(id=2))
          conn.execute(table.update().where(table.c.id == 1).values(at=sa.func.now()))
          show("rows", conn.execute(sa.select(table.c.id, table.c.at == sa.func.now()).order_by(table.c.id)).fetchall())
          show("before now", conn.execute(sa.select(sa.func.count()).select_from(table).where(table.c.at <= sa.func.now())).scalar())
      """;

  @Test
  void psycopgAndSqlAlchemyReadTheClockAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> server = startPostgres()) {
      python(server, SCRIPT);
    }
  }
}
