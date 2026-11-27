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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code varchar(n)} and {@code numeric(p,s)} columns of the {@code postgresql} language against PostgreSQL 18 (fork issue #226),
 * over JDBC: what a write stores, which writes are refused and with which SQLSTATE, the errors of {@code CREATE TABLE}, the
 * catalog, and writes of the {@code sql} language into such a column.
 */
class PostgreSQLTypeModifierTest extends PostgreSQLSideBySideTestBase {
  private static final List<String> TABLES = List.of(
      "CREATE TABLE m (id int PRIMARY KEY, name varchar(3), full_name character varying(5) NOT NULL DEFAULT 'ab',"
          + " price numeric(5,2) CHECK (price < 100), qty numeric(3) DEFAULT 1.5, total decimal(12,4) DEFAULT '0',"
          + " hundreds numeric(3,-2), tiny numeric(2,4), plain varchar, free numeric)",
      "CREATE TABLE k (code varchar(4) PRIMARY KEY, amount numeric(8,2) UNIQUE)",
      "CREATE TABLE c (id int PRIMARY KEY, code varchar(8) REFERENCES k (code), amount numeric(8,2) REFERENCES k (amount))",
      "CREATE TABLE u (name varchar(5) PRIMARY KEY)",
      "CREATE TABLE follows (source_id varchar(5) NOT NULL REFERENCES u (name), target_id varchar(5) NOT NULL REFERENCES u (name))");

  @Test
  void lengthAndPrecisionAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String table : TABLES)
          both(postgres, arcade, table);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        for (final String write : List.of("INSERT INTO m (id, name, price) VALUES (1, 'abc', 1.005)",
            "INSERT INTO m (id, name, full_name, price) VALUES (2, 'ab     ', 'éé€€x   ', -1.005)",
            "INSERT INTO m (id, name, price, qty, total) VALUES (3, '€€€', '99.994', 999.4, '12345678.00005')",
            "INSERT INTO m (id, hundreds, tiny) VALUES (4, 12345, 0.00994)",
            "INSERT INTO m (id, hundreds, tiny) VALUES (5, -49, 0.000004)",
            "INSERT INTO m (id, name, plain, free) VALUES (6, NULL, 'a much longer string', 1.23456789)",
            "INSERT INTO m (id, name, price) SELECT id + 10, name, price + 0.0051 FROM m WHERE id = 1",
            "UPDATE m SET price = 10.0 / 3, name = CAST('abcdef' AS varchar(3)) WHERE id = 6",
            "INSERT INTO m (id, price) VALUES (1, 2.675) ON CONFLICT (id) DO UPDATE SET price = excluded.price + 0.001",
            "INSERT INTO m (id, price) VALUES (7, CAST('7.777' AS numeric))", "UPDATE m SET name = DEFAULT, qty = DEFAULT WHERE id = 7",
            "INSERT INTO k VALUES ('ab', 1.5), ('abcd', 2)", "INSERT INTO k VALUES ('cd    ', 3.004)",
            "INSERT INTO c VALUES (1, 'cd  ', 3.001)", "INSERT INTO u VALUES ('ann'), ('bob')",
            "INSERT INTO follows VALUES ('ann', 'bob')"))
          both(postgres, arcade, write);

        for (final String tooLong : List.of("INSERT INTO m (id, name) VALUES (20, 'abcd')",
            "INSERT INTO m (id, name) VALUES (20, 'ab x')", "INSERT INTO m (id, full_name) VALUES (20, '123456')",
            "UPDATE m SET name = name || 'xyz' WHERE id = 1", "INSERT INTO m (id, name) SELECT 20, plain FROM m WHERE id = 6",
            "INSERT INTO m (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET name = 'toolong'",
            "INSERT INTO k VALUES ('abcde', 9)", "INSERT INTO follows VALUES ('annabel', 'bob')"))
          sameError(postgres, arcade, tooLong, "22001");
        for (final String overflow : List.of("INSERT INTO m (id, price) VALUES (20, 1000)",
            "INSERT INTO m (id, price) VALUES (20, 999.995)", "INSERT INTO m (id, qty) VALUES (20, '999.5')",
            "UPDATE m SET price = price * 1000 WHERE id = 1", "INSERT INTO m (id, hundreds) VALUES (20, 99950)",
            "INSERT INTO m (id, tiny) VALUES (20, 0.00995)", "INSERT INTO m (id, tiny) VALUES (20, 1)",
            "INSERT INTO m (id, total) SELECT 20, 100000000"))
          sameError(postgres, arcade, overflow, "22003");
        // the check reads the rounded value
        sameError(postgres, arcade, "INSERT INTO m (id, price) VALUES (20, 99.999)", "23514");
        // the keys compare the rounded and the cut values
        sameError(postgres, arcade, "INSERT INTO k VALUES ('ef', 1.499999)", "23505");
        sameError(postgres, arcade, "INSERT INTO k VALUES ('abcd  ', 7)", "23505");
        sameError(postgres, arcade, "INSERT INTO c VALUES (2, 'zz', 3)", "23503");

        bound(postgres, arcade);

        // the text of each number has the scale of its column
        sameValues(postgres, arcade, "SELECT id, name, full_name, price::text, qty::text, total::text, hundreds::text,"
            + " tiny::text, plain, free::text FROM m ORDER BY id");
        same(postgres, arcade, "SELECT name, price FROM m WHERE price = 1.01 ORDER BY id");
        sameValues(postgres, arcade, "SELECT code, amount::text FROM k ORDER BY code");
        sameValues(postgres, arcade, "SELECT id, code, amount::text FROM c ORDER BY id");
        sameValues(postgres, arcade, "SELECT count(*) FROM follows");

        sameValues(postgres, arcade, "SELECT table_name, column_name, data_type, character_maximum_length, character_octet_length,"
            + " numeric_precision, numeric_precision_radix, numeric_scale, column_default FROM information_schema.columns"
            + " WHERE table_name IN ('m', 'k', 'c', 'u') ORDER BY table_name, ordinal_position");
        for (final String table : List.of("m", "k", "u"))
          sameValues(postgres, arcade, "SELECT a.attname, format_type(a.atttypid, a.atttypmod), a.atttypmod FROM pg_attribute a"
              + " WHERE a.attrelid = '" + table + "'::regclass AND a.attnum > 0 ORDER BY a.attnum");

        for (final String[] error : new String[][] { { "CREATE TABLE e (a varchar(0))", "22023" },
            { "CREATE TABLE e (a varchar(10485761))", "22023" }, { "CREATE TABLE e (a numeric(0))", "22023" },
            { "CREATE TABLE e (a numeric(1001, 2))", "22023" }, { "CREATE TABLE e (a numeric(5, 1001))", "22023" },
            { "CREATE TABLE e (a numeric(1, 2, 3))", "22023" }, { "CREATE TABLE e (a varchar(1, 2))", "22023" } })
          sameError(postgres, arcade, error[0], error[1]);

        refused(arcade, "CREATE TABLE e (a char(3))", "Write varchar(n)");
        refused(arcade, "CREATE TABLE e (a varchar(2) DEFAULT 'abc')", "A DEFAULT that does not fit its column");
        refused(arcade, "CREATE TABLE e (a numeric(3,1) DEFAULT 1234)", "A DEFAULT that does not fit its column");
        refused(arcade, "CREATE TABLE e (a varchar(5)[])", "A type modifier on a column of type varchar[]");
        refused(arcade, "CREATE TABLE e (source_id varchar(9) NOT NULL REFERENCES u (name), target_id varchar(5) NOT NULL"
            + " REFERENCES u (name))", "An end of another length or precision than its key");
        sameValues(postgres, arcade, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'e'");

        // the sql language keeps the same limits, and rounds the same way
        run(arcade, "{sql} INSERT INTO m SET id = 30, name = 'xy     ', full_name = 'q', price = 1.125, qty = 2, total = 0");
        compare("a row written by sql", "    xy |1.13|\n", answer(arcade, "SELECT name, price::text FROM m WHERE id = 30", false)
            .replaceFirst("^[^\n]*\n", ""));
        assertThat(state(arcade, "{sql} INSERT INTO m SET id = 31, name = 'abcd', full_name = 'q'")).isEqualTo("22001");
        assertThat(state(arcade, "{sql} UPDATE m SET price = 5000 WHERE id = 30")).isEqualTo("22003");

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  /** Values bound to parameters are cut, rounded and refused as the constants are. */
  private void bound(final Connection postgres, final Connection arcade) {
    final StringBuilder expected = new StringBuilder();
    final StringBuilder actual = new StringBuilder();
    for (final Object[] row : new Object[][] { { 40, "pq   ", new BigDecimal("3.14159") }, { 41, "pqrs", BigDecimal.ONE },
        { 42, "p", new BigDecimal("1234.5") } }) {
      expected.append(insert(postgres, row)).append('\n');
      actual.append(insert(arcade, row)).append('\n');
    }
    compare("writes with parameters", expected.toString(), actual.toString());
  }

  private static String insert(final Connection connection, final Object[] row) {
    try (final PreparedStatement statement = connection.prepareStatement("INSERT INTO m (id, name, price) VALUES (?, ?, ?)")) {
      for (int i = 0; i < row.length; i++)
        statement.setObject(i + 1, row[i]);
      return "    " + statement.executeUpdate();
    } catch (final SQLException e) {
      return "    error " + e.getSQLState();
    }
  }
}
