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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A record id is not a key that a statement can treat as a number (fork issue #240): {@code max} and {@code min} of one are
 * refused, and a text that is no record id, compared with a column of record ids, is an error and not a statement that matches
 * nothing. PostgreSQL has a {@code bigint} column {@code rid} here, as the designs of the agent measure do, and answers what the
 * statement means; the language gives that answer or an error that says what to write.
 */
class PostgreSQLRecordIdTest extends PostgreSQLSessionAgainstPostgresTestBase {
  private static final List<String> POSTGRES_SETUP = List.of(
      "CREATE TABLE person (rid bigint GENERATED ALWAYS AS IDENTITY UNIQUE, id bigint PRIMARY KEY, name text)",
      "CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE,"
          + " target_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int)",
      "CREATE TABLE ticket (rid int, label text)");

  private static final List<String> LANGUAGE_SETUP = List.of(
      "CREATE TABLE person (id bigint PRIMARY KEY, name text)",
      "CREATE TABLE knows (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,"
          + " target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int)",
      "CREATE TABLE ticket (rid int, label text)");

  private static final List<String> WRITES = List.of(
      "INSERT INTO person (id, name) VALUES (1, 'Ada'), (2, 'Bo'), (3, 'Cy')",
      "INSERT INTO knows SELECT a.rid, b.rid, 2020 FROM person a, person b WHERE a.id = 1 AND b.id = 2",
      "INSERT INTO ticket VALUES (7, 'seven'), (12, 'twelve')");

  /** Both give the same answer: a record id used as one, and a column that is only named {@code rid}. */
  private static final List<String> SAME = List.of(
      "SELECT name FROM person WHERE rid = (SELECT rid FROM person WHERE id = 1)",
      "SELECT name FROM person WHERE rid IN (SELECT rid FROM person) ORDER BY name",
      "SELECT p.name, k.since FROM person p JOIN knows k ON k.source_id = p.rid",
      "SELECT count(rid), count(*), max(name), min(name) FROM person",
      "SELECT max(rid), min(rid) FROM ticket",
      "SELECT label FROM ticket WHERE rid = '7'",
      "SELECT max(rid) FROM (SELECT rid FROM ticket) q");

  /** A text that is no record id, compared with a column of record ids. */
  private static final List<String> NOT_RECORD_IDS = List.of(
      "SELECT name FROM person WHERE rid = '1'",
      "SELECT name FROM person WHERE '1' = rid",
      "SELECT name FROM person WHERE rid <> 'abc'",
      "SELECT name FROM person WHERE rid IN ('abc')",
      "SELECT name FROM person WHERE rid NOT IN ('#1:0', '2')",
      "SELECT name FROM person WHERE rid = ANY(ARRAY['1'])",
      "SELECT name FROM person p WHERE p.rid BETWEEN '1' AND '9'",
      "SELECT since FROM knows WHERE source_id = '1'",
      "SELECT since FROM knows k JOIN person p ON k.target_id = p.rid AND p.rid = '2'",
      "SELECT name FROM (SELECT rid AS r, name FROM person) q WHERE r = '1'",
      "UPDATE person SET name = 'z' WHERE rid = '1'",
      "DELETE FROM person WHERE rid = '12'");

  /** {@code max} and {@code min} of record ids. */
  private static final List<String> AGGREGATES = List.of(
      "SELECT max(rid) FROM person",
      "SELECT max(rid), min(rid) FROM person",
      "SELECT min(p.rid) FROM person p",
      "SELECT max(source_id) FROM knows",
      "SELECT name, max(rid) OVER () FROM person",
      "WITH q AS (SELECT rid FROM person) SELECT max(rid) FROM q");

  @Test
  void aRecordIdIsNeverTakenForTheKeyOfARow() throws Exception {
    compare((postgres, arcade) -> {
      for (final boolean simple : new boolean[] { false, true }) {
        try (final Connection pg = postgres.connect(simple); final Connection own = arcade.connect(simple)) {
          if (!simple) {
            POSTGRES_SETUP.forEach(statement -> assertThat(answer(pg, statement)).as(statement).doesNotStartWith("error"));
            LANGUAGE_SETUP.forEach(statement -> assertThat(answer(own, statement)).as(statement).doesNotStartWith("error"));
            for (final String write : WRITES)
              assertThat(answer(own, write)).as(write).isEqualTo(answer(pg, write)).startsWith("count ");
          }
          final List<String> failures = new ArrayList<>();
          for (final String statement : SAME) {
            final String expected = answer(pg, statement);
            final String actual = answer(own, statement);
            if (expected.startsWith("error") || !expected.equals(actual))
              failures.add(statement + "\n  PostgreSQL: " + expected + "\n  ArcadeDB:   " + actual);
          }
          for (final String statement : NOT_RECORD_IDS) {
            final String actual = answer(own, statement);
            final String message = errorMessage(own, statement);
            if (!"error 22P02".equals(actual) || !message.contains("type record id") || !message.contains("'#12:3'"))
              failures.add(statement + ": " + actual + " " + message);
          }
          for (final String statement : AGGREGATES) {
            assertThat(answer(pg, statement)).as(statement).doesNotStartWith("error");
            final String actual = answer(own, statement);
            final String message = errorMessage(own, statement);
            if (!"error 0A000".equals(actual) || !message.contains("no creation order") || !message.contains("row_number()"))
              failures.add(statement + ": " + actual + " " + message);
          }
          assertThat(failures).as(String.join("\n", failures)).isEmpty();
          // none of the writes that failed changed a row
          final String check = "SELECT id, name FROM person ORDER BY id";
          assertThat(answer(own, check)).isEqualTo(answer(pg, check)).isEqualTo("rows [1|Ada, 2|Bo, 3|Cy]");
          // a record id that no record has matches nothing, as a key that no row has does
          assertThat(answer(own, "SELECT name FROM person WHERE rid = '#999:999'")).isEqualTo("rows []");
        }
      }
      // a bound text is checked as a written one is, and a record id that a query returned reads its row
      try (final Connection own = arcade.connect(false)) {
        final String ada;
        try (final PreparedStatement byName = own.prepareStatement("SELECT rid FROM person WHERE name = ?")) {
          byName.setString(1, "Ada");
          try (final ResultSet rows = byName.executeQuery()) {
            assertThat(rows.next()).isTrue();
            ada = rows.getString(1);
          }
        }
        assertThat(bound(own, "SELECT name FROM person WHERE rid = ?", ada)).isEqualTo("rows [Ada]");
        assertThat(bound(own, "SELECT name FROM person WHERE rid = ?", "1")).isEqualTo("error 22P02");
        assertThat(bound(own, "SELECT name FROM person WHERE rid IN (?, ?)", ada, "Ada")).isEqualTo("error 22P02");
        assertThat(boundArray(own, "SELECT name FROM person WHERE rid = ANY(?)", ada)).isEqualTo("rows [Ada]");
        assertThat(boundArray(own, "SELECT name FROM person WHERE rid = ANY(?)", ada, "Ada")).isEqualTo("error 22P02");
      }
    });
  }

  private static String bound(final Connection connection, final String statement, final String... values) {
    try (final PreparedStatement prepared = connection.prepareStatement(statement)) {
      for (int i = 0; i < values.length; i++)
        prepared.setString(i + 1, values[i]);
      return names(prepared);
    } catch (final SQLException e) {
      return "error " + e.getSQLState();
    }
  }

  private static String boundArray(final Connection connection, final String statement, final String... values) {
    try (final PreparedStatement prepared = connection.prepareStatement(statement)) {
      prepared.setArray(1, connection.createArrayOf("text", values));
      return names(prepared);
    } catch (final SQLException e) {
      return "error " + e.getSQLState();
    }
  }

  private static String names(final PreparedStatement prepared) throws SQLException {
    final List<String> names = new ArrayList<>();
    try (final ResultSet rows = prepared.executeQuery()) {
      while (rows.next())
        names.add(rows.getString(1));
    }
    return "rows " + names;
  }
}
