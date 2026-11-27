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
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.query.postgresql.corpus.WriteOutcome;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The writes of link tables that refer to a unique column (fork issue #213): the same {@code CREATE TABLE} and {@code INSERT}
 * texts make the tables on the {@code postgresql} language and on a real PostgreSQL 18, then each write runs on both sides in a
 * transaction that is rolled back, with checks after it, and each refused {@code CREATE TABLE} on both. Nothing is recorded in a
 * file: the two outcomes of one run have to be equal.
 */
class PgLinkKeyWriteParityIT {
  private static final List<String> TABLES = List.of(
      "CREATE TABLE person (id bigint PRIMARY KEY, name text NOT NULL)",
      "CREATE TABLE company (code text UNIQUE, name text)",
      "CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL REFERENCES person, since int)",
      "CREATE TABLE works_at (source_id bigint NOT NULL REFERENCES person (id) ON DELETE CASCADE, "
          + "target_id varchar NOT NULL REFERENCES company (code) ON DELETE CASCADE, since int)",
      "CREATE TABLE follows (source_id bigint NOT NULL REFERENCES person (id) ON DELETE CASCADE, "
          + "target_id bigint NOT NULL REFERENCES person (id))",
      "CREATE TABLE likes (source_id bigint NOT NULL REFERENCES person (id) ON DELETE RESTRICT ON UPDATE CASCADE, "
          + "target_id bigint NOT NULL REFERENCES person (id) ON UPDATE CASCADE)");

  private static final List<String> ROWS = List.of(
      "INSERT INTO person (id, name) VALUES (1, 'Ada'), (2, 'Bo'), (3, 'Cy'), (4, 'Di'), (5, 'Ed'), (6, 'Fay'), (7, 'Gus'), "
          + "(8, 'Hal')",
      "INSERT INTO company VALUES ('acme', 'Acme'), ('birch', 'Birch')",
      "INSERT INTO knows (source_id, target_id, since) VALUES (1, 2, 2020), (1, 3, 2021), (2, 3, 2022)",
      "INSERT INTO works_at VALUES (1, 'acme', 2019), (3, 'birch', 2021)",
      "INSERT INTO follows VALUES (5, 6)",
      "INSERT INTO likes VALUES (7, 8)");

  private static final String LINKS   = "SELECT source_id, target_id, since FROM knows ORDER BY since, source_id, target_id";
  private static final String PERSONS = "SELECT id, name FROM person ORDER BY id";
  private static final String LIKES   = "SELECT source_id, target_id FROM likes";
  private static final String RULES   = "SELECT constraint_name, unique_constraint_name, update_rule, delete_rule "
      + "FROM information_schema.referential_constraints ORDER BY constraint_name";

  /** One write and the queries that read what it left. */
  private record Write(String statement, List<String> checks) {
    static Write of(final String statement, final String... checks) {
      return new Write(statement, List.of(checks));
    }
  }

  private static final List<Write> WRITES = List.of(
      Write.of("INSERT INTO knows (source_id, target_id, since) VALUES (3, '1', 2023) RETURNING source_id, target_id, since", LINKS),
      Write.of("INSERT INTO knows (source_id, target_id, since) SELECT 4, p.id, 2024 FROM person p WHERE p.name = 'Ada'", LINKS),
      Write.of("INSERT INTO knows (source_id, target_id) VALUES (9, 1)", LINKS),
      Write.of("INSERT INTO works_at VALUES (1, 'nobody', 1)"),
      Write.of("INSERT INTO knows (source_id, target_id) VALUES (NULL, 1)", LINKS),
      Write.of("INSERT INTO knows (target_id) VALUES (1)", LINKS),
      Write.of("INSERT INTO knows (source_id, target_id) VALUES ('x', 1)"),
      Write.of("INSERT INTO knows (source_id, target_id) SELECT name, id FROM person"),
      Write.of("UPDATE person SET id = 10 WHERE id = 1", PERSONS),
      Write.of("UPDATE person SET id = 30 WHERE id = 3", PERSONS),
      Write.of("UPDATE company SET code = 'x' WHERE code = 'acme'"),
      Write.of("UPDATE company SET code = NULL WHERE code = 'acme'"),
      Write.of("INSERT INTO person (id, name) VALUES (1, 'A') ON CONFLICT (id) DO UPDATE SET id = 11", PERSONS),
      Write.of("UPDATE person SET id = 10 FROM company c WHERE person.id = 1 AND c.code = 'acme'", PERSONS),
      Write.of("UPDATE person SET id = id, name = 'Ada L' WHERE id = 1", PERSONS, LINKS),
      Write.of("UPDATE person SET id = 40 WHERE id = 4", PERSONS),
      Write.of("UPDATE knows SET since = 2000 WHERE source_id = 2 RETURNING source_id, target_id, since", LINKS),
      Write.of("DELETE FROM person WHERE id = 1", PERSONS, LINKS),
      Write.of("DELETE FROM person WHERE id = 3", PERSONS, LINKS),
      Write.of("DELETE FROM person WHERE id = 4", PERSONS),
      Write.of("DELETE FROM person WHERE id = 6", PERSONS, "SELECT count(*) FROM follows"),
      Write.of("DELETE FROM person WHERE id IN (5, 6)", PERSONS, "SELECT count(*) FROM follows"),
      Write.of("DELETE FROM company WHERE code = 'birch'", "SELECT source_id, target_id FROM works_at ORDER BY since"),
      Write.of("DELETE FROM knows WHERE source_id = 2 RETURNING source_id, target_id", LINKS),
      // a statement that is refused for one row changes no other
      Write.of("DELETE FROM person WHERE id IN (4, 1)", PERSONS),
      Write.of("INSERT INTO knows (source_id, target_id) VALUES (4, 1), (4, 9)", LINKS),
      // ON UPDATE CASCADE shows the new key, ON DELETE RESTRICT refuses the delete
      Write.of("UPDATE person SET id = 70 WHERE id = 7", PERSONS, LIKES, RULES),
      Write.of("UPDATE person SET id = 80 WHERE id = 8", PERSONS, LIKES),
      Write.of("DELETE FROM person WHERE id = 7", PERSONS, LIKES),
      Write.of("DELETE FROM person WHERE id = 8", PERSONS, LIKES));

  /** {@code CREATE TABLE} statements that both sides refuse, with one SQLSTATE. */
  private static final List<String> REFUSED = List.of(
      bad("text NOT NULL REFERENCES person (name)"),
      bad("bigint NOT NULL REFERENCES person (nosuch)"),
      bad("bigint NOT NULL REFERENCES nobody (id)"),
      bad("bigint NOT NULL REFERENCES company"),
      bad("text NOT NULL REFERENCES person (id)"),
      bad("bigint NOT NULL REFERENCES person (id, name)"));

  private static String bad(final String end) {
    return "CREATE TABLE bad (source_id " + end + ", target_id bigint NOT NULL REFERENCES person (id))";
  }

  private static PostgresServer pg;
  private static ArcadeSide     arcade;

  @BeforeAll
  static void start() throws Exception {
    pg = PostgresServer.start();
    pg.load(String.join(";\n", TABLES) + ";\n" + String.join(";\n", ROWS) + ";");
    arcade = new ArcadeSide("linkkeywrites");
    for (final String statement : TABLES)
      arcade.database().command("postgresql", statement);
    for (final String statement : ROWS)
      arcade.database().transaction(() -> arcade.database().command("postgresql", statement));
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
  void writesDoWhatTheServerDoes() throws Exception {
    final List<String> failures = new ArrayList<>();
    for (final Write write : WRITES) {
      final WriteOutcome server = pg.runWrite(write.statement(), write.checks());
      final WriteOutcome engine = arcade.runWrite(write.statement(), write.checks());
      report(write.statement(), WriteOutcome.compare(server, engine, Values.DEFAULT_TOLERANCE), failures);
    }
    assertTrue(failures.isEmpty(), "the engine and the server disagree on writes:\n" + String.join("\n", failures));
  }

  @Test
  void refusedTablesAreRefusedWithTheServersCode() {
    final List<String> failures = new ArrayList<>();
    for (final String statement : REFUSED) {
      final Outcome server = pg.run(statement);
      String engine = "answered";
      try (final ResultSet ignored = arcade.database().command("postgresql", statement)) {
        // not expected: the statement has to be refused
      } catch (final Throwable t) {
        engine = ArcadeSide.sqlState(t);
      }
      report(statement, server.isRefusal() && server.sqlState.equals(engine) ? null
          : "server " + server.describe() + ", engine " + engine, failures);
    }
    assertTrue(failures.isEmpty(), "the engine and the server disagree on refusals:\n" + String.join("\n", failures));
  }

  private static void report(final String statement, final String difference, final List<String> failures) {
    System.out.println("pgparity linkkey " + (difference == null ? "equal " : "different ") + statement);
    if (difference != null)
      failures.add(statement + ": " + difference);
  }
}
