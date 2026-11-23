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

import com.arcadedb.query.postgresql.IdRuleFixture;
import com.arcadedb.query.postgresql.catalog.SmallSchemaSql;
import com.arcadedb.query.postgresql.corpus.Corpus;
import com.arcadedb.query.postgresql.corpus.CorpusFile;
import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultComparator;
import com.arcadedb.query.postgresql.corpus.Values;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answer parity of the {@code id} rule (fork issue #38): the statements of {@code idrule.tsv} on the {@code postgresql}
 * language of ArcadeDB and on a real PostgreSQL 18, on the same tables. It is {@link PgParityIT} with another data set: ArcadeDB
 * holds {@code IdRuleFixture} (types with an own property {@code id}, with {@code rid}, with both, and with neither), and the
 * server gets the {@code CREATE TABLE} and {@code INSERT} statements that {@code SmallSchemaSql} writes from the relational view
 * of that database, with the record ids renumbered {@code #<table number>:<row number>}. The engine's real record ids are
 * translated to those before a result is compared. {@link #theTwoLoadsAgree()} compares the row counts of the two sides before
 * any statement runs.
 * <p>
 * <b>To regenerate</b> the expected cells of {@code idrule.tsv} from the server, start the workflow {@code pg-parity-tests.yml} by
 * hand with the input {@code regenerate} on and take the file from its artifact, as for {@link PgParityIT}. Nothing is typed.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgIdRuleParityIT {
  private static final String LANGUAGE = "postgresql";
  private static final String[] TABLES = { IdRuleFixture.ACCOUNT, IdRuleFixture.POST, IdRuleFixture.TOPIC, IdRuleFixture.FOLLOWS,
      IdRuleFixture.INTERESTED, IdRuleFixture.MEMO, IdRuleFixture.BADGE, IdRuleFixture.TWIN };

  private static boolean        regenerate;
  private static Path           resources;
  private static PostgresServer pg;
  private static ArcadeSide     arcade;
  private static final List<String> REPORT = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    regenerate = Boolean.getBoolean("pgparity.regenerate");
    resources = findResources();
    pg = PostgresServer.start();
    final String version = pg.version();
    REPORT.add("server: " + version);
    REPORT.add("collation: " + pg.collation());
    REPORT.add("regenerate: " + regenerate);
    System.out.println("pgparity idrule: " + version);
    assertEquals("C", pg.collation(), "the server must run with the C collation");
    assertEquals("UTF8", pg.encoding(), "the server must run with the UTF8 encoding");

    arcade = new ArcadeSide("idrule");
    IdRuleFixture.createSchema(arcade.database());
    IdRuleFixture.load(arcade.database());
    final String script = SmallSchemaSql.generate(arcade.database(), true);
    REPORT.add("server schema: " + script.lines().filter(line -> line.startsWith("CREATE TABLE")).count() + " tables, "
        + script.lines().filter(line -> line.startsWith("INSERT INTO")).count() + " rows");
    pg.load(script);
    arcade.setRidAliases(SmallSchemaSql.recordIdAliases(arcade.database()));
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      final Path directory = Path.of("target", "pgparity");
      Files.createDirectories(directory);
      Files.write(directory.resolve("report-idrule.txt"), REPORT, StandardCharsets.UTF_8);
    } finally {
      try {
        if (arcade != null)
          arcade.close();
      } finally {
        if (pg != null)
          pg.close();
      }
    }
  }

  /** {@code -Dpgparity.resources=<dir>}, else the test resources of the engine, found by walking up from the working directory. */
  private static Path findResources() {
    final String given = System.getProperty("pgparity.resources");
    if (given != null && !given.isEmpty())
      return Path.of(given);
    Path directory = Path.of("").toAbsolutePath();
    for (int depth = 0; directory != null && depth < 6; depth++) {
      final Path candidate = directory.resolve("engine/src/test/resources");
      if (Files.isDirectory(candidate.resolve("postgresql")))
        return candidate;
      directory = directory.getParent();
    }
    throw new IllegalStateException("engine/src/test/resources/postgresql not found above " + Path.of("").toAbsolutePath()
        + ": pass -Dpgparity.resources=<dir>");
  }

  private static Outcome answer(final Outcome outcome) {
    return Outcome.answer(outcome.table.valuesOnly());
  }

  /** The two loads hold the same rows: the count of every table, asked of the server and of the language of the engine. */
  @Test
  @Order(1)
  void theTwoLoadsAgree() {
    final List<String> failures = new ArrayList<>();
    for (final String table : TABLES) {
      final String sql = "SELECT count(*) FROM " + table;
      final Outcome server = pg.run(sql);
      final Outcome engine = arcade.run(LANGUAGE, sql);
      if (server.isRefusal() || engine.isRefusal())
        failures.add(sql + ": " + (server.isRefusal() ? "server " + server.describe() : "engine " + engine.describe()));
      else {
        final String difference = ResultComparator.compare(answer(server), answer(engine), true, Values.DEFAULT_TOLERANCE);
        if (difference != null)
          failures.add(sql + ": " + difference);
      }
    }
    assertTrue(failures.isEmpty(), "the two loads of the idrule fixture differ:\n" + String.join("\n", failures));
  }

  /**
   * The statements of {@code idrule.tsv}, each on the server (its outcome must have the shape its id promises and, unless
   * regenerating, be the one in the file) and on the engine (equal to the server, or pending).
   */
  @Test
  @Order(2)
  void idRuleStatements() throws Exception {
    final Path path = resources.resolve("postgresql/corpus").resolve(Corpus.IDRULE_FILE);
    final CorpusFile file = CorpusFile.read(path);
    final List<String> corpusFailures = new ArrayList<>();
    final List<String> engineFailures = new ArrayList<>();
    int equal = 0;
    int pending = 0;
    int different = 0;
    int flip = 0;

    for (final String[] row : file.rows) {
      final Corpus.AnswerCase c = new Corpus.AnswerCase(row);
      final Outcome server = pg.run(c.statement());
      if (server.isRefusal() != c.expectsRefusal())
        corpusFailures.add(c.id() + ": " + (c.expectsRefusal() ? "the server answered a statement that an err- id says it refuses: "
            : "the server refused a statement that its id says it answers: ") + server.describe());

      if (regenerate)
        row[Corpus.AnswerCase.EXPECTED] = server.toJson();
      else if (!c.hasOutcome())
        corpusFailures.add(c.id() + ": the corpus has no outcome, run the generator");
      else {
        final String drift = ResultComparator.compare(Outcome.fromJson(c.expected()), server, c.ordered(), c.tolerance());
        if (drift != null)
          corpusFailures.add(c.id() + ": the server no longer answers what the corpus says: " + drift);
      }

      final Outcome engine = arcade.run(LANGUAGE, c.statement());
      final String difference = ResultComparator.compare(server, engine, c.ordered(), c.tolerance());
      final String verdict;
      if (difference == null && c.pending()) {
        verdict = "pending, but equal: flip the status to ok";
        ++flip;
        engineFailures.add(c.id() + ": pending, yet the engine answers like the server: flip status to ok in idrule.tsv");
      } else if (difference == null) {
        verdict = "equal";
        ++equal;
      } else if (c.pending()) {
        verdict = "pending (" + difference + ")";
        ++pending;
      } else {
        verdict = "different (" + difference + ")";
        ++different;
        engineFailures.add(c.id() + ": " + difference);
      }
      line(c.id(), verdict);
    }

    final String summary = "idrule: " + file.rows.size() + " statements, " + equal + " equal, " + pending + " pending, " + different
        + " different, " + flip + " to flip";
    REPORT.add(summary);
    System.out.println("pgparity: " + summary);

    if (regenerate) {
      file.setHeaderValue("server", pg.version());
      file.setHeaderValue("collation", pg.collation());
      file.setHeaderValue("encoding", pg.encoding());
      file.write(path);
    }
    assertTrue(corpusFailures.isEmpty(), "the corpus and the server disagree:\n" + String.join("\n", corpusFailures));
    if (!regenerate)
      assertTrue(engineFailures.isEmpty(), "the engine and the server disagree:\n" + String.join("\n", engineFailures));
  }

  private static void line(final String id, final String verdict) {
    final String text = "pgparity idrule " + id + " " + LANGUAGE + " " + verdict;
    REPORT.add(text);
    System.out.println(text);
  }
}
