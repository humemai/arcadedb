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
package com.arcadedb.e2e.pgparity;

import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultComparator;
import com.arcadedb.query.postgresql.corpus.Values;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Link tables whose ends refer to a unique column of the table at that end (fork issue #213), on the {@code postgresql} language
 * and on a real PostgreSQL 18, over the same rows. Nothing is recorded in a file: each statement runs on both sides in one run,
 * and the two outcomes (the rows, or the SQLSTATE of a refusal) have to be equal.
 */
class PgLinkKeyParityIT {
  private static final String SERVER_SCHEMA = """
      CREATE TABLE person (id bigint PRIMARY KEY, name text NOT NULL);
      CREATE TABLE company (code varchar UNIQUE, name text);
      CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (id) ON DELETE CASCADE,
        target_id bigint NOT NULL REFERENCES person (id) ON DELETE CASCADE, since int);
      CREATE TABLE works_at (source_id bigint NOT NULL REFERENCES person (id) ON DELETE CASCADE,
        target_id varchar NOT NULL REFERENCES company (code) ON DELETE CASCADE, since int);
      INSERT INTO person VALUES (1, 'Ada'), (2, 'Bo'), (3, 'Cy'), (4, 'Di');
      INSERT INTO company VALUES ('acme', 'Acme'), ('birch', 'Birch');
      INSERT INTO knows VALUES (1, 2, 2020), (1, 3, 2021), (2, 3, 2022), (3, 1, 2023);
      INSERT INTO works_at VALUES (1, 'acme', 2019), (2, 'acme', 2020), (3, 'birch', 2021);
      """;

  private static final String ENGINE_SCHEMA = """
      CREATE VERTEX TYPE person;
      CREATE PROPERTY person.id LONG (mandatory true, notnull true);
      CREATE PROPERTY person.name STRING (mandatory true, notnull true);
      CREATE INDEX ON person (id) UNIQUE;
      CREATE VERTEX TYPE company;
      CREATE PROPERTY company.code STRING;
      CREATE PROPERTY company.name STRING;
      CREATE INDEX ON company (code) UNIQUE;
      CREATE EDGE TYPE knows CUSTOM source_table = 'person', source_column = 'id', target_table = 'person', target_column = 'id';
      CREATE PROPERTY knows.since INTEGER;
      CREATE EDGE TYPE works_at CUSTOM source_table = 'person', source_column = 'id', target_table = 'company', target_column = 'code';
      CREATE PROPERTY works_at.since INTEGER;
      """;

  private static final String ENGINE_ROWS = """
      CREATE VERTEX person SET id = 1, name = 'Ada';
      CREATE VERTEX person SET id = 2, name = 'Bo';
      CREATE VERTEX person SET id = 3, name = 'Cy';
      CREATE VERTEX person SET id = 4, name = 'Di';
      CREATE VERTEX company SET code = 'acme', name = 'Acme';
      CREATE VERTEX company SET code = 'birch', name = 'Birch';
      CREATE EDGE knows FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 2) SET since = 2020;
      CREATE EDGE knows FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 3) SET since = 2021;
      CREATE EDGE knows FROM (SELECT FROM person WHERE id = 2) TO (SELECT FROM person WHERE id = 3) SET since = 2022;
      CREATE EDGE knows FROM (SELECT FROM person WHERE id = 3) TO (SELECT FROM person WHERE id = 1) SET since = 2023;
      CREATE EDGE works_at FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM company WHERE code = 'acme') SET since = 2019;
      CREATE EDGE works_at FROM (SELECT FROM person WHERE id = 2) TO (SELECT FROM company WHERE code = 'acme') SET since = 2020;
      CREATE EDGE works_at FROM (SELECT FROM person WHERE id = 3) TO (SELECT FROM company WHERE code = 'birch') SET since = 2021;
      """;

  /** The reads, each compared as an ordered result: every one has an {@code ORDER BY} that decides the order of its rows. */
  private static final List<String> READS = List.of(
      "SELECT source_id, target_id, since FROM knows ORDER BY since",
      "SELECT source_id, target_id, since FROM works_at ORDER BY since",
      "SELECT q.name FROM person p JOIN knows k ON k.source_id = p.id JOIN person q ON q.id = k.target_id WHERE p.id = 1 ORDER BY q.name",
      "SELECT q.name, k.source_id, k.target_id FROM person p JOIN knows k ON k.source_id = p.id JOIN person q ON q.id = k.target_id "
          + "WHERE p.id IN (1, 2) ORDER BY k.since",
      "SELECT q.name, k.since FROM person p JOIN knows k ON k.target_id = p.id JOIN person q ON q.id = k.source_id WHERE p.id = 3 "
          + "ORDER BY k.since",
      "SELECT r.name, b.since FROM person p JOIN knows a ON a.source_id = p.id JOIN person q ON q.id = a.target_id "
          + "JOIN knows b ON b.source_id = q.id JOIN person r ON r.id = b.target_id WHERE p.id = 1 ORDER BY b.since",
      "SELECT p.name, k.target_id, q.name FROM person p LEFT JOIN knows k ON k.source_id = p.id "
          + "LEFT JOIN person q ON q.id = k.target_id WHERE p.id = 4 ORDER BY p.name",
      "SELECT p.name, count(q.id) AS n FROM person p LEFT JOIN knows k ON k.source_id = p.id "
          + "LEFT JOIN person q ON q.id = k.target_id GROUP BY p.name ORDER BY p.name",
      "SELECT p.name, c.name FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON c.code = w.target_id "
          + "ORDER BY p.name",
      "SELECT p.name FROM company c JOIN works_at w ON w.target_id = c.code JOIN person p ON p.id = w.source_id "
          + "WHERE c.code = 'acme' ORDER BY p.name",
      "SELECT target_id FROM knows WHERE source_id = 1 ORDER BY target_id",
      "SELECT source_id + 100 AS s, count(*) AS n FROM knows GROUP BY source_id ORDER BY source_id",
      "SELECT p.name, (SELECT count(*) FROM knows k WHERE k.source_id = p.id) AS n FROM person p ORDER BY p.name",
      "SELECT p.name FROM person p WHERE EXISTS (SELECT 1 FROM knows k WHERE k.target_id = p.id) ORDER BY p.name",
      "SELECT p.name, q.name FROM knows k JOIN person p ON p.id = k.source_id JOIN person q ON q.id = k.target_id ORDER BY k.since",
      "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name IN ('knows', 'works_at') "
          + "AND column_name IN ('source_id', 'target_id') ORDER BY table_name, column_name",
      "SELECT rc.constraint_name, rc.unique_constraint_name, rc.delete_rule, pk.table_name, pk.column_name "
          + "FROM information_schema.referential_constraints rc JOIN information_schema.key_column_usage pk "
          + "ON pk.constraint_name = rc.unique_constraint_name ORDER BY rc.constraint_name",
      "SELECT p.name FROM person p JOIN knows k ON k.source_id = p.name ORDER BY p.name");

  private static PostgresServer pg;
  private static ArcadeSide     arcade;

  @BeforeAll
  static void start() throws Exception {
    pg = PostgresServer.start();
    pg.load(SERVER_SCHEMA);
    arcade = new ArcadeSide("linkkey");
    arcade.database().command("sqlscript", ENGINE_SCHEMA);
    arcade.database().transaction(() -> arcade.database().command("sqlscript", ENGINE_ROWS));
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      if (arcade != null)
        arcade.close();
    } finally {
      if (pg != null)
        pg.close();
    }
  }

  @Test
  void readsAnswerLikeTheServer() {
    final List<String> failures = new ArrayList<>();
    for (final String sql : READS)
      compare(sql, pg.run(sql), arcade.run("postgresql", sql), failures);
    assertTrue(failures.isEmpty(), "the engine and the server disagree:\n" + String.join("\n", failures));
  }

  private static void compare(final String sql, final Outcome server, final Outcome engine, final List<String> failures) {
    final Outcome expected = server.isRefusal() ? server : Outcome.answer(server.table.valuesOnly());
    final String difference = ResultComparator.compare(expected, engine, true, Values.DEFAULT_TOLERANCE);
    System.out.println("pgparity linkkey " + (difference == null ? "equal " : "different ") + sql);
    if (difference != null)
      failures.add(sql + ": " + difference);
  }
}
