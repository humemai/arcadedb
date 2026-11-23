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

import com.arcadedb.query.postgresql.TpchFixture;
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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TPC-H Q1, Q6, Q14 and Q19, unchanged, on the {@code postgresql} language of ArcadeDB and on a real PostgreSQL 18, on the same
 * rows, compared as complete multisets (fork issue #13, part 1). It is {@link PgParityIT} with another data set: the tables
 * {@code lineitem} and {@code part} of TPC-H at a small scale factor, which {@code .github/scripts/pg_tpch_data.py} writes as two
 * tab separated files in the directory named by {@code -Dpgparity.tpch.dir=<dir>} (the workflow {@code pg-parity-tests.yml}
 * generates them with DuckDB's {@code dbgen}, never committed). Without the property the class is skipped, so the {@code
 * java-e2e-tests} lane of {@code mvn-test.yml}, which has no generator step, runs nothing here.
 * <p>
 * PostgreSQL gets {@code pgparity/tpch-schema.sql} and the two files through {@code COPY}; ArcadeDB gets the document types of
 * {@code TpchFixture} and the same two files. {@link #dataAgrees()} compares the two loads before any statement runs. The
 * statements are {@code tpch.tsv} (the columns of {@code answers.tsv}); a statement is run as the file has it, byte for byte, and
 * a statement that fails on the engine is reported with the message of the engine, which names the construct.
 * <p>
 * <b>To regenerate</b> the expected cells of {@code tpch.tsv} from the server, start the workflow by hand with the input
 * {@code regenerate} on and take the file from its artifact, as for {@link PgParityIT}.
 * <p>
 * <b>The report</b> has one line per statement for the verdict, one for the plan of the engine ({@code EXPLAIN}, the access path
 * of each table and the join), and one for the time of one run on each side. The times are a smoke test on a shared CI runner, one
 * run, no warm-up: they are not a measurement.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgTpchParityIT {
  private static final String LANGUAGE = "postgresql";

  private static boolean        regenerate;
  private static Path           resources;
  private static Path           data;
  private static PostgresServer pg;
  private static ArcadeSide     arcade;
  private static final List<String> REPORT = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    final String given = System.getProperty("pgparity.tpch.dir");
    assumeTrue(given != null && !given.isEmpty(), "-Dpgparity.tpch.dir is not set: no TPC-H data was generated");
    data = Path.of(given);
    assumeTrue(Files.exists(data.resolve("lineitem.tsv")) && Files.exists(data.resolve("part.tsv")),
        "lineitem.tsv and part.tsv are not in " + data);
    regenerate = Boolean.getBoolean("pgparity.regenerate");
    resources = findResources();

    pg = PostgresServer.start();
    final String version = pg.version();
    REPORT.add("server: " + version);
    REPORT.add("collation: " + pg.collation());
    REPORT.add("regenerate: " + regenerate);
    System.out.println("pgparity tpch: " + version);
    assertEquals("C", pg.collation(), "the server must run with the C collation");
    assertEquals("UTF8", pg.encoding(), "the server must run with the UTF8 encoding");

    try (final var schema = PgTpchParityIT.class.getResourceAsStream("/pgparity/tpch-schema.sql")) {
      assertTrue(schema != null, "pgparity/tpch-schema.sql is not on the classpath");
      pg.load(new String(schema.readAllBytes(), StandardCharsets.UTF_8));
    }
    final long serverLineitems = pg.copyIn("lineitem", data.resolve("lineitem.tsv"));
    final long serverParts = pg.copyIn("part", data.resolve("part.tsv"));
    // the order of the lane: the index after the load, then the statistics the planner reads
    pg.load("CREATE INDEX li_shipdate ON lineitem (l_shipdate); ANALYZE lineitem; ANALYZE part");

    arcade = new ArcadeSide("tpch");
    TpchFixture.createSchema(arcade.database());
    final long[] loaded = TpchFixture.load(arcade.database(), data);
    final String rows = "rows: lineitem " + serverLineitems + " on the server and " + loaded[0] + " in ArcadeDB, part " + serverParts
        + " and " + loaded[1];
    REPORT.add(rows);
    System.out.println("pgparity tpch: " + rows);
    assertEquals(serverLineitems, loaded[0], "lineitem rows");
    assertEquals(serverParts, loaded[1], "part rows");
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      final Path directory = Path.of("target", "pgparity");
      Files.createDirectories(directory);
      Files.write(directory.resolve("report-tpch.txt"), REPORT, StandardCharsets.UTF_8);
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

  /**
   * The two loads hold the same rows: counts, sums, the smallest and the largest date, per table, asked of the server and of the
   * {@code postgresql} language of the engine, and the sums of the columns that the four statements read through the {@code sql}
   * language of the engine, which does not share the code under test.
   */
  @Test
  @Order(1)
  void dataAgrees() {
    final String[] statements = {
        "SELECT count(*), sum(l_orderkey), sum(l_partkey), sum(l_quantity), sum(l_extendedprice), sum(l_discount), sum(l_tax) FROM lineitem",
        "SELECT count(*), sum(p_partkey), sum(p_retailprice), sum(stock), sum(p_size) FROM part",
        "SELECT min(l_shipdate), max(l_shipdate), count(DISTINCT l_shipmode), count(DISTINCT l_shipinstruct) FROM lineitem",
        "SELECT count(DISTINCT p_type), count(DISTINCT p_brand), count(DISTINCT p_container) FROM part" };
    final List<String> failures = new ArrayList<>();
    for (final String sql : statements) {
      final Outcome server = pg.run(sql);
      final Outcome engine = arcade.run(LANGUAGE, sql);
      if (server.isRefusal() || engine.isRefusal())
        failures.add(sql + ": " + (server.isRefusal() ? "server " + server.describe() : "engine " + engine.describe()));
      else {
        final String difference = ResultComparator.compare(answer(server), answer(engine), true, Values.DEFAULT_TOLERANCE);
        if (difference != null)
          failures.add(sql + ": " + difference + "\n  server rows: " + server.table.rows + "\n  engine rows: " + engine.table.rows);
      }
    }
    final String[] sums = { "SELECT count(*), sum(l_orderkey), sum(l_partkey), sum(l_quantity), sum(l_extendedprice), sum(l_discount), sum(l_tax) FROM lineitem",
        "SELECT count(*), sum(p_partkey), sum(p_retailprice), sum(stock), sum(p_size) FROM part" };
    for (final String sql : sums) {
      final Outcome server = pg.run(sql);
      final Outcome engine = arcade.run("sql", sql);
      if (server.isRefusal() || engine.isRefusal())
        failures.add("sql: " + sql + ": " + (server.isRefusal() ? "server " + server.describe() : "engine " + engine.describe()));
      else {
        final String difference = ResultComparator.compare(answer(server), answer(engine), true, 1e-9);
        if (difference != null)
          failures.add("sql: " + sql + ": " + difference + "\n  server rows: " + server.table.rows + "\n  engine rows: " + engine.table.rows);
      }
    }
    assertTrue(failures.isEmpty(), "the two loads of the TPC-H data differ:\n" + String.join("\n", failures));
  }

  /**
   * The four statements of {@code tpch.tsv}, each on the server (its outcome must be an answer, and unless regenerating the one in
   * the file) and on the engine (equal to the server, or {@code pending}).
   */
  @Test
  @Order(2)
  void tpchStatements() throws Exception {
    final Path path = resources.resolve("postgresql/corpus").resolve(Corpus.TPCH_FILE);
    final CorpusFile file = CorpusFile.read(path);
    final List<String> corpusFailures = new ArrayList<>();
    final List<String> engineFailures = new ArrayList<>();
    int equal = 0;
    int pending = 0;
    int different = 0;
    int flip = 0;

    for (final String[] row : file.rows) {
      final Corpus.AnswerCase c = new Corpus.AnswerCase(row);
      final long serverStart = System.nanoTime();
      final Outcome server = pg.run(c.statement());
      final long serverMillis = (System.nanoTime() - serverStart) / 1_000_000;
      if (server.isRefusal())
        corpusFailures.add(c.id() + ": the server refused a statement that must answer: " + server.describe());

      if (regenerate)
        row[Corpus.AnswerCase.EXPECTED] = server.toJson();
      else if (!c.hasOutcome())
        corpusFailures.add(c.id() + ": the corpus has no outcome, run the generator");
      else {
        final String drift = ResultComparator.compare(Outcome.fromJson(c.expected()), server, c.ordered(), c.tolerance());
        if (drift != null)
          corpusFailures.add(c.id() + ": the server no longer answers what the corpus says: " + drift);
      }

      final long engineStart = System.nanoTime();
      final Outcome engine = arcade.run(LANGUAGE, c.statement());
      final long engineMillis = (System.nanoTime() - engineStart) / 1_000_000;
      final String difference = ResultComparator.compare(server, engine, c.ordered(), c.tolerance());
      final String verdict;
      if (difference == null && c.pending()) {
        verdict = "pending, but equal: flip the status to ok";
        ++flip;
        engineFailures.add(c.id() + ": pending, yet the engine answers like the server: flip status to ok in tpch.tsv");
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
      line("tpch", c.id(), verdict);
      line("tpch-rows", c.id(), "server " + (server.isRefusal() ? "refused" : server.table.rows.size() + " row(s)") + ", engine "
          + (engine.isRefusal() ? "refused: " + engine.describe() : engine.table.rows.size() + " row(s)"));
      line("tpch-time", c.id(), "one run on a shared CI runner, not a measurement: server " + serverMillis + " ms, engine "
          + engineMillis + " ms");
      final Outcome plan = arcade.run(LANGUAGE, "EXPLAIN " + c.statement());
      line("tpch-plan", c.id(), plan.isRefusal() ? "EXPLAIN refused: " + plan.describe() : String.valueOf(plan.table.rows));
    }

    final String summary = "tpch: " + file.rows.size() + " statements, " + equal + " equal, " + pending + " pending, " + different
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

  private static void line(final String file, final String id, final String verdict) {
    final String text = "pgparity " + file + " " + id + " " + LANGUAGE + " " + verdict;
    REPORT.add(text);
    System.out.println(text);
  }
}
