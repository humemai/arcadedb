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

import com.arcadedb.Constants;
import com.arcadedb.query.postgresql.ValuesFixture;
import com.arcadedb.query.postgresql.corpus.Corpus;
import com.arcadedb.query.postgresql.corpus.CorpusFile;
import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultComparator;
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.query.postgresql.corpus.WriteOutcome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The answer parity harness of the PostgreSQL-style SQL prototype (fork issue #2): the same statement on ArcadeDB's
 * {@code postgresql} language and on a real PostgreSQL 18, and the outcomes compared. It needs a container runtime (the server is
 * the official {@code postgres:18} image through Testcontainers) and runs in the workflow {@code pg-parity-tests.yml} and in the
 * {@code java-e2e-tests} lane of {@code mvn-test.yml}.
 * <p>
 * <b>The data.</b> PostgreSQL gets {@code engine/src/test/resources/postgresql/small-schema.sql}, the one file that describes the
 * {@code small} fixture for the server. ArcadeDB gets {@code SmallFixture} of the engine's test classes, reached through the
 * engine test-jar (a test dependency of this module). {@link #fixturesAgree()} compares the two builds (counts, sums, and the
 * rows that a {@code buyer} or an edge points to) so they cannot drift. The file numbers record ids {@code #<table
 * number>:<row number>} so that the committed corpus does not depend on ArcadeDB's buckets, and the harness translates the
 * engine's real record ids to those ({@code ArcadeSide.setRidAliases}). While the file is not on the branch the class is skipped.
 * <p>
 * <b>The corpus.</b> {@code engine/src/test/resources/postgresql/corpus/answers.tsv} (this harness) and {@code syntax.tsv} (the
 * accept and reject differential of the grammar). The columns are described at the top of each file, and the engine tests read
 * the same files through {@code Corpus}. The comparison rules are the classes {@code Values} and {@code ResultComparator}.
 * <p>
 * <b>To add a statement</b> append a line to {@code answers.tsv} (an id that is not used, {@code ordered} only when the
 * {@code ORDER BY} is a total order and {@code multiset} otherwise, {@code -} for the tolerance, {@code pending}, the statement,
 * and an empty last cell) and run the generator. An id that starts with {@code err-} is a statement the server must refuse. Add
 * a statement to {@code syntax.tsv} the same way, with scope {@code in} or {@code out}.
 * <p>
 * <b>To regenerate</b> run this class with {@code -Dpgparity.regenerate=true} (in CI: start the workflow by hand with the input
 * {@code regenerate} on and take the files from its artifact). The expected cells are then written from the server, never typed,
 * together with the version string and the collation of the server in the header. It is off by default and never on in CI. A
 * regenerated file is a diff to read before it is committed: a changed outcome is a PostgreSQL change or a corpus change.
 * <p>
 * <b>Pending.</b> A statement marked {@code pending} is run on the server for its outcome and tried on the engine, and reported
 * as pending whatever the engine does, never as failed. When the engine answers it like the server, the run fails until the
 * flag in {@code answers.tsv} is flipped to {@code ok}: the list of pending statements may only shrink, and a statement that is
 * not pending is a test that must pass.
 * <p>
 * <b>The report.</b> One line per case on the standard output, {@code pgparity <file> <id> <language> <verdict>}, and the same
 * in {@code target/pgparity/report.txt}.
 * <p>
 * <b>The capability matrix.</b> {@link #capabilityMatrix()} runs the answer corpus once more, unchanged, also on ArcadeDB's own
 * {@code sql} language, and counts per statement group how many statements each language answers like the server, refuses, fails
 * on or answers with other rows ({@link CapabilityMatrix}). It is a report and never fails on a count: it is printed and written to
 * {@code target/pgparity/capability-matrix.md} (tables) and {@code .tsv} (one line per statement).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgParityIT {
  private static final String LANGUAGE = "postgresql";

  private static boolean      regenerate;
  private static Path         resources;
  private static PostgresServer pg;
  private static ArcadeSide   arcade;
  private static final List<String> REPORT = new ArrayList<>();

  @BeforeAll
  static void start() throws Exception {
    regenerate = Boolean.getBoolean("pgparity.regenerate");
    resources = findResources();
    final Path schema = resources.resolve("postgresql/small-schema.sql");
    // the fixture file of the server is written by another step of the prototype (S2): without it there is nothing to compare
    assumeTrue(Files.exists(schema), "engine/src/test/resources/postgresql/small-schema.sql is not on this branch yet");
    pg = PostgresServer.start();
    final String version = pg.version();
    REPORT.add("server: " + version);
    REPORT.add("collation: " + pg.collation());
    REPORT.add("regenerate: " + regenerate);
    System.out.println("pgparity: " + version);
    // text order and comparison in the corpus are code point order: a server in another collation would write another corpus
    assertEquals("C", pg.collation(), "the server must run with the C collation");
    REPORT.add("SHOW lc_collate: " + (pg.showFailure == null ? "answered" : "refused (" + pg.showFailure + "), read from pg_database"));
    assertEquals("UTF8", pg.encoding(), "the server must run with the UTF8 encoding");
    REPORT.add("encoding: " + pg.encoding());

    pg.load(Files.readString(schema, StandardCharsets.UTF_8));
    arcade = new ArcadeSide();
    arcade.setRidAliases(ridAliases());
  }

  /**
   * The fixture file numbers its record ids {@code #<table number>:<row number>}; ArcadeDB's are real record ids, which depend
   * on the buckets a type got. Both sides list the rows of each table in the order of a unique key, and the n-th real id gets
   * the n-th fixture id.
   */
  private static Map<String, String> ridAliases() {
    final String[][] keys = { { "person", "pid" }, { "company", "cid" }, { "orders", "oid" }, { "works_at", "since" } };
    final Map<String, String> aliases = new HashMap<>();
    for (final String[] key : keys) {
      final Outcome server = pg.run("SELECT id FROM " + key[0] + " ORDER BY " + key[1]);
      final Outcome engine = arcade.run("sql", "SELECT @rid AS id FROM " + key[0] + " ORDER BY " + key[1]);
      assertTrue(!server.isRefusal() && !engine.isRefusal(), "record ids of " + key[0] + ": " + server.describe() + " / "
          + engine.describe());
      assertEquals(server.table.rows.size(), engine.table.rows.size(), "rows of " + key[0] + " on the two sides");
      for (int i = 0; i < server.table.rows.size(); i++)
        aliases.put((String) engine.table.rows.get(i).get(0), (String) server.table.rows.get(i).get(0));
    }
    return aliases;
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      final Path directory = Path.of("target", "pgparity");
      Files.createDirectories(directory);
      Files.write(directory.resolve("report.txt"), REPORT, StandardCharsets.UTF_8);
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

  private static Path corpusFile(final String name) {
    return resources.resolve("postgresql/corpus").resolve(name);
  }

  private static Outcome answer(final Outcome outcome) {
    return Outcome.answer(outcome.table.valuesOnly());
  }

  /** The loader check of fork issue #2: the server holds the five people of the fixture. */
  @Test
  @Order(1)
  void loaderCheck() {
    final Outcome outcome = pg.run("SELECT count(*), sum(age), min(age), max(age) FROM person");
    assertTrue(!outcome.isRefusal(), "the loader check was refused: " + outcome.describe());
    final List<Object> row = outcome.table.rows.get(0);
    final Object[] expected = { 5, 158, 29, 52 };
    for (int i = 0; i < expected.length; i++)
      assertTrue(Values.equal(Values.normalize(expected[i]), row.get(i), Values.DEFAULT_TOLERANCE),
          "loader check column " + (i + 1) + " is " + Values.render(row.get(i)) + ", the fixture has " + expected[i]);
  }

  /**
   * The self-test of the comparison, on the server: the same statement twice compares equal, with one row removed on one side
   * it differs by one row, and with one row duplicated on one side it differs although the distinct rows are equal.
   */
  @Test
  @Order(2)
  void comparisonSelfTest() {
    final String sql = "SELECT name FROM person ORDER BY pid";
    final Outcome first = pg.run(sql);
    final Outcome second = pg.run(sql);
    assertEquals(5, first.table.rows.size());
    assertNull(ResultComparator.compare(first, second, true, Values.DEFAULT_TOLERANCE));
    assertNull(ResultComparator.compare(first, second, false, Values.DEFAULT_TOLERANCE));

    final String removed = ResultComparator.compare(first, Outcome.answer(second.table.withoutRow(2)), false,
        Values.DEFAULT_TOLERANCE);
    assertTrue(removed != null && removed.startsWith("differs by 1 row "), "one row removed: " + removed);

    final String duplicated = ResultComparator.compare(first, Outcome.answer(second.table.withRowDuplicated(2)), false,
        Values.DEFAULT_TOLERANCE);
    assertTrue(duplicated != null && duplicated.startsWith("differs by 1 row "), "one row duplicated: " + duplicated);
  }

  /**
   * The two builds of the fixture hold the same data: row counts and sums per table through the {@code sql} language of the
   * engine, and the record ids that the server's {@code id} and {@code buyer} columns must carry.
   */
  @Test
  @Order(3)
  void fixturesAgree() {
    final String[][] pairs = {
        { "SELECT count(*), sum(pid), sum(age) FROM person", "SELECT count(*), sum(pid), sum(age) FROM person" },
        { "SELECT count(*), sum(cid) FROM company", "SELECT count(*), sum(cid) FROM company" },
        { "SELECT count(*), sum(since) FROM works_at", "SELECT count(*), sum(since) FROM works_at" },
        { "SELECT count(*), sum(oid), sum(total) FROM orders", "SELECT count(*), sum(oid), sum(total) FROM orders" },
        { "SELECT buyer FROM orders ORDER BY oid", "SELECT buyer FROM orders ORDER BY oid" },
        { "SELECT id, source_id, target_id FROM works_at ORDER BY since",
            "SELECT @rid AS id, @out AS source_id, @in AS target_id FROM works_at ORDER BY since" },
    };
    final List<String> failures = new ArrayList<>();
    for (final String[] pair : pairs) {
      final Outcome server = pg.run(pair[0]);
      final Outcome engine = arcade.run("sql", pair[1]);
      if (server.isRefusal() || engine.isRefusal())
        failures.add(pair[0] + ": " + (server.isRefusal() ? "server " : "engine ") + (server.isRefusal() ? server : engine).describe());
      else {
        final String difference = ResultComparator.compare(answer(server), answer(engine), true, Values.DEFAULT_TOLERANCE);
        if (difference != null)
          failures.add(pair[0] + ": " + difference + "\n  server rows: " + server.table.rows + "\n  engine rows: " + engine.table.rows);
      }
    }
    assertTrue(failures.isEmpty(), "the two builds of the small fixture differ:\n" + String.join("\n", failures));
  }

  /**
   * The accept and reject corpus: the server parses and describes each statement and never runs it. Regenerate writes the
   * {@code pg18} and {@code detail} columns of every entry (and reports where the server disagrees with the value that was there
   * before, which is a finding); otherwise the columns in the file must still be what the server says. The parser tests of the
   * engine (fork issue #3, {@code SyntaxCorpusTest}) enforce the differential against the parser.
   */
  @Test
  @Order(4)
  void syntaxCorpus() throws Exception {
    final Path path = corpusFile(Corpus.SYNTAX_FILE);
    final CorpusFile file = CorpusFile.read(path);
    final List<String> failures = new ArrayList<>();
    int changed = 0;
    for (final String[] row : file.rows) {
      final Corpus.SyntaxCase c = new Corpus.SyntaxCase(row);
      final String[] server;
      try {
        server = syntaxOutcome(pg.parseOutcome(c.statement()));
      } catch (final IllegalStateException driverFailure) {
        failures.add(c.id() + ": " + driverFailure.getMessage());
        continue;
      }
      if (regenerate) {
        if (c.hasOutcome() && !c.pg18().equals(server[0])) {
          ++changed;
          line("syntax-changed", c.id(), "the corpus said " + c.pg18() + ", the server says " + server[0] + " " + server[1]);
        }
        row[Corpus.SyntaxCase.PG18] = server[0];
        row[Corpus.SyntaxCase.DETAIL] = server[1];
      } else if (!c.hasOutcome())
        failures.add(c.id() + ": the corpus has no outcome, run the generator");
      else if (!c.pg18().equals(server[0]) || !c.detail().equals(server[1]))
        failures.add(c.id() + ": the corpus says " + c.pg18() + " " + c.detail() + ", the server now says " + server[0] + " "
            + server[1]);
    }
    final String summary = "syntax: " + file.rows.size() + " statements, " + changed + " whose pg18 the server changed";
    REPORT.add(summary);
    System.out.println("pgparity: " + summary);

    // the server only parsed them: the data must be untouched
    final Outcome people = pg.run("SELECT count(*) FROM person");
    assertEquals(0, new BigDecimal(5).compareTo((BigDecimal) people.table.rows.get(0).get(0)), "the syntax pass ran a statement");

    if (regenerate) {
      stamp(file);
      file.write(path);
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  /**
   * The agent SQL corpus (fork issue #7): the statements that language models wrote, in {@code agent-sql.tsv}, parsed and
   * described by the server and never run. They are written against the tables of {@code agent-sql-schema.sql}, which the server
   * gets in a database of its own (the {@code small} fixture has other tables), so that it binds their names: a statement that is
   * valid there comes back {@code ok}, one with an unknown table or column {@code ok} with the SQLSTATE of the later stage, as
   * for {@code syntax.tsv}. Regenerate writes the {@code pg18} and {@code detail} columns, nobody types them. The parser side is
   * measured by {@code AgentSqlFitTest} of the engine tests.
   */
  @Test
  @Order(6)
  void agentSqlCorpus() throws Exception {
    final Path path = corpusFile(Corpus.AGENT_SQL_FILE);
    final Path schema = corpusFile(Corpus.AGENT_SQL_SCHEMA);
    assumeTrue(Files.exists(path) && Files.exists(schema), "the agent SQL corpus is not on this branch yet");
    final PostgresServer.ParseSession session = pg.createDatabase("agentsql", Files.readString(schema, StandardCharsets.UTF_8));
    final CorpusFile file = CorpusFile.read(path);
    final List<String> failures = new ArrayList<>();
    int changed = 0;
    for (final String[] row : file.rows) {
      final Corpus.SyntaxCase c = new Corpus.SyntaxCase(row);
      final String[] server;
      try {
        server = syntaxOutcome(session.parseOutcome(c.statement()));
      } catch (final IllegalStateException driverFailure) {
        failures.add(c.id() + ": " + driverFailure.getMessage());
        continue;
      }
      if (regenerate) {
        if (c.hasOutcome() && !c.pg18().equals(server[0])) {
          ++changed;
          line("agent-sql-changed", c.id(), "the corpus said " + c.pg18() + ", the server says " + server[0] + " " + server[1]);
        }
        row[Corpus.SyntaxCase.PG18] = server[0];
        row[Corpus.SyntaxCase.DETAIL] = server[1];
      } else if (!c.hasOutcome())
        failures.add(c.id() + ": the corpus has no outcome, run the generator");
      else if (!c.pg18().equals(server[0]) || !c.detail().equals(server[1]))
        failures.add(c.id() + ": the corpus says " + c.pg18() + " " + c.detail() + ", the server now says " + server[0] + " "
            + server[1]);
    }
    final String summary = "agent sql: " + file.rows.size() + " statements, " + changed + " whose pg18 the server changed";
    REPORT.add(summary);
    System.out.println("pgparity: " + summary);

    if (regenerate) {
      stamp(file);
      file.write(path);
    }
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  /**
   * The server's parse outcome ({@code ok}, {@code syntax-error 42601}, {@code error <SQLSTATE>}) as the two columns of
   * {@code syntax.tsv}: {@code pg18} ({@code ok}, {@code syntax-error}, {@code feature-not-supported}) and {@code detail} (the
   * SQLSTATE, {@code -} when the server accepted the statement). A later stage that refused a parsed statement (an unknown
   * table) is still {@code ok}, with its SQLSTATE as the detail.
   */
  static String[] syntaxOutcome(final String parseOutcome) {
    if ("ok".equals(parseOutcome))
      return new String[] { "ok", "-" };
    if ("syntax-error 42601".equals(parseOutcome))
      return new String[] { "syntax-error", "42601" };
    final String state = parseOutcome.substring("error ".length());
    return new String[] { "0A000".equals(state) ? "feature-not-supported" : "ok", state };
  }

  /**
   * The answer corpus: every statement on the server (its outcome must have the shape its id promises and, unless regenerating,
   * must still be the one in the file) and on the engine (equal to the server, or pending).
   */
  @Test
  @Order(5)
  void answerCorpus() throws Exception {
    final Path path = corpusFile(Corpus.ANSWERS_FILE);
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
        engineFailures.add(c.id() + ": pending, yet the engine answers like the server: flip status to ok in answers.tsv");
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
      line("answers", c.id(), verdict);
    }

    final String summary = "answers: " + file.rows.size() + " statements, " + equal + " equal, " + pending + " pending, "
        + different + " different, " + flip + " to flip";
    REPORT.add(summary);
    System.out.println("pgparity: " + summary);

    if (regenerate) {
      stamp(file);
      file.write(path);
    }
    assertTrue(corpusFailures.isEmpty(), "the corpus and the server disagree:\n" + String.join("\n", corpusFailures));
    if (!regenerate)
      assertTrue(engineFailures.isEmpty(), "the engine and the server disagree:\n" + String.join("\n", engineFailures));
  }

  /**
   * The write corpus (fork issue #29): every write of {@code writes.tsv} on the server and on the engine, each in a transaction
   * that is rolled back, and the answer of the statement and the tables it touched compared as multisets. The server first gets
   * {@code small-writes.sql}, which makes its {@code id} generated and a deleted vertex take its edges, as the table view does.
   * Unless regenerating, the server must still do what the corpus says; regenerating writes the {@code expected} cells.
   */
  @Test
  @Order(9)
  void writeCorpus() throws Exception {
    final Path path = corpusFile(Corpus.WRITES_FILE);
    final Path guards = resources.resolve("postgresql/small-writes.sql");
    assumeTrue(Files.exists(path) && Files.exists(guards), "the write corpus is not on this branch yet");
    pg.load(Files.readString(guards, StandardCharsets.UTF_8));
    writeCases(path, arcade, "writes");
  }

  /**
   * The values corpus (fork issue #29): the writes of {@code jsonb}, arrays and {@code bytea} of {@code values-writes.tsv} on the
   * table of {@code values-schema.sql} on the server and on {@code ValuesFixture} in ArcadeDB, compared as the write corpus is.
   */
  @Test
  @Order(10)
  void valuesCorpus() throws Exception {
    pg.load(Files.readString(resources.resolve("postgresql/values-schema.sql"), StandardCharsets.UTF_8));
    try (final ArcadeSide values = new ArcadeSide("values")) {
      ValuesFixture.createSchema(values.database());
      ValuesFixture.load(values.database());
      writeCases(corpusFile(Corpus.VALUES_WRITES_FILE), values, "values");
    }
  }

  private void writeCases(final Path path, final ArcadeSide side, final String label) throws Exception {
    final CorpusFile file = CorpusFile.read(path);
    final List<String> corpusFailures = new ArrayList<>();
    final List<String> engineFailures = new ArrayList<>();
    int equal = 0;
    int pending = 0;
    int different = 0;

    for (final String[] row : file.rows) {
      final Corpus.WriteCase c = new Corpus.WriteCase(row);
      final WriteOutcome server = pg.runWrite(c.statement(), c.checks());
      if (server.result.isRefusal() != c.expectsRefusal())
        corpusFailures.add(c.id() + ": " + (c.expectsRefusal() ? "the server answered a statement that an err- id says it refuses: "
            : "the server refused a statement that its id says it answers: ") + server.result.describe());

      if (regenerate)
        row[Corpus.WriteCase.EXPECTED] = server.toJson();
      else if (!c.hasOutcome())
        corpusFailures.add(c.id() + ": the corpus has no outcome, run the generator");
      else {
        final String drift = WriteOutcome.compare(WriteOutcome.fromJson(c.expected()), server, Values.DEFAULT_TOLERANCE);
        if (drift != null)
          corpusFailures.add(c.id() + ": the server no longer does what the corpus says: " + drift);
      }

      final WriteOutcome engine = side.runWrite(c.statement(), c.checks());
      final String difference = WriteOutcome.compare(server, engine, Values.DEFAULT_TOLERANCE);
      final String verdict;
      if (difference == null && c.pending()) {
        verdict = "pending, but equal: flip the status to ok";
        engineFailures.add(c.id() + ": pending, yet the engine does what the server did: flip status to ok in " + path.getFileName());
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
      line(label, c.id(), verdict);
    }

    final String summary = label + ": " + file.rows.size() + " statements, " + equal + " equal, " + pending + " pending, " + different
        + " different";
    REPORT.add(summary);
    System.out.println("pgparity: " + summary);

    if (regenerate) {
      stamp(file);
      file.write(path);
    }
    assertTrue(corpusFailures.isEmpty(), "the write corpus and the server disagree:\n" + String.join("\n", corpusFailures));
    if (!regenerate)
      assertTrue(engineFailures.isEmpty(), "the engine and the server disagree on writes:\n" + String.join("\n", engineFailures));
  }

  /** The classifier of the capability matrix on hand-made outcomes: a mistake of the harness fails here, not as a wrong count. */
  @Test
  @Order(7)
  void capabilityClassifierSelfTest() {
    final List<String> failures = CapabilityMatrix.selfTest();
    assertTrue(failures.isEmpty(), "the capability matrix classifier is wrong:\n" + String.join("\n", failures));
  }

  /**
   * The capability matrix ({@link CapabilityMatrix}): every statement of the answer corpus, unchanged, on the real server, on the
   * {@code postgresql} language and on ArcadeDB's own {@code sql} language, all on the data of this run. A report, not a gate: no
   * count of it fails the run. It is printed, and written to {@code target/pgparity/capability-matrix.md} and {@code .tsv}.
   */
  @Test
  @Order(8)
  void capabilityMatrix() throws Exception {
    final CorpusFile file = CorpusFile.read(corpusFile(Corpus.ANSWERS_FILE));
    final CapabilityMatrix matrix = new CapabilityMatrix();
    for (final String[] row : file.rows) {
      final Corpus.AnswerCase c = new Corpus.AnswerCase(row);
      matrix.add(c, pg.run(c.statement()), arcade.run(LANGUAGE, c.statement()), arcade.runNative(c.statement()));
    }
    assertTrue(matrix.size() > 0 && matrix.size() == file.rows.size(), "the matrix holds " + matrix.size() + " of " + file.rows.size()
        + " statements");
    final String server = pg.version();
    final String arcadeVersion = Constants.getRawVersion();
    matrix.write(Path.of("target", "pgparity"), server, arcadeVersion);
    System.out.println(matrix.render(server, arcadeVersion));
    REPORT.add("capability matrix: " + matrix.size() + " statements, target/pgparity/capability-matrix.md");
  }

  private static void line(final String file, final String id, final String verdict) {
    final String text = "pgparity " + file + " " + id + " " + LANGUAGE + " " + verdict;
    REPORT.add(text);
    System.out.println(text);
  }

  private static void stamp(final CorpusFile file) throws Exception {
    file.setHeaderValue("server", pg.version());
    file.setHeaderValue("collation", pg.collation());
    file.setHeaderValue("encoding", pg.encoding());
  }
}
