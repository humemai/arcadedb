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
package com.arcadedb.query.postgresql.plan.join;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.TestHelper;
import com.arcadedb.log.LogManager;
import com.arcadedb.log.Logger;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The warning for a join that no edge, link or index supports (fork issue #9), on the {@code small-noindex} variant of the small
 * fixture (the schema without its unique indexes, where {@code orders.pid = person.pid} has nothing behind it) and on the
 * {@code small} fixture (the same schema with the index on {@code person.pid}, where it has). The logger of the engine is
 * replaced by one that keeps what is written at {@code WARNING}.
 */
class JoinWarningTest extends TestHelper {
  private static final String UNSUPPORTED = "SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid";

  private Logger         previous;
  private CapturingLogger logger;

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database, false);
    SmallFixture.load(database);
    JoinWarnings.shared().clear();
    previous = LogManager.instance().getLogger();
    logger = new CapturingLogger();
    LogManager.instance().setLogger(logger);
  }

  @AfterEach
  void restoreTheLogger() {
    LogManager.instance().setLogger(previous);
    JoinWarnings.shared().clear();
  }

  /** Keeps the messages the warning class wrote at WARNING, as formatted text. */
  private static final class CapturingLogger implements Logger {
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Override
    public void log(final Object requester, final Level level, final String message, final Throwable exception, final String context,
        final Object arg1, final Object arg2, final Object arg3, final Object arg4, final Object arg5, final Object arg6,
        final Object arg7, final Object arg8, final Object arg9, final Object arg10, final Object arg11, final Object arg12,
        final Object arg13, final Object arg14, final Object arg15, final Object arg16, final Object arg17) {
      keep(requester, level, message, new Object[] { arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12,
          arg13, arg14, arg15, arg16, arg17 });
    }

    @Override
    public void log(final Object requester, final Level level, final String message, final Throwable exception, final String context,
        final Object... args) {
      keep(requester, level, message, args);
    }

    private void keep(final Object requester, final Level level, final String message, final Object[] args) {
      if (requester == JoinWarnings.class && level == Level.WARNING && message != null)
        warnings.add(args == null || args.length == 0 ? message : message.formatted(args));
    }

    @Override
    public void flush() {
    }
  }

  // ---- helpers ----

  private List<List<Object>> rows(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      final List<List<Object>> out = new ArrayList<>();
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> values = new ArrayList<>();
        for (final String name : result.getPropertyNames())
          values.add(result.getProperty(name));
        out.add(values);
      }
      return out;
    }
  }

  private List<String> explain(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", "EXPLAIN " + sql)) {
      final List<String> lines = new ArrayList<>();
      while (resultSet.hasNext())
        lines.add(resultSet.next().getProperty("QUERY PLAN"));
      return lines;
    }
  }

  private void addTheIndexOfSmall() {
    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "person", "pid");
  }

  // ---- once per statement ----

  @Test
  void aJoinThatNothingSupportsLogsOneWarningWhateverTheNumberOfRuns() {
    final List<List<Object>> first = rows(UNSUPPORTED);
    assertThat(first).hasSize(5);
    assertThat(rows(UNSUPPORTED)).isEqualTo(first);
    assertThat(rows(UNSUPPORTED)).isEqualTo(first);
    assertThat(logger.warnings).hasSize(1);
    assertThat(logger.warnings.get(0)).contains("orders").contains("person").contains("o.pid = p.pid")
        .contains("No edge type connects orders and person").contains("a relationship may be missing")
        .contains("Statement: SELECT");
  }

  @Test
  void aHundredRunsOfOneStatementLogOnceAndAnotherStatementLogsItsOwn() {
    for (int i = 0; i < 100; i++)
      rows(UNSUPPORTED);
    assertThat(logger.warnings).hasSize(1);
    rows("SELECT o.oid FROM orders o JOIN person p ON o.pid = p.pid");
    assertThat(logger.warnings).hasSize(2);
    for (int i = 0; i < 100; i++)
      rows("SELECT o.oid FROM orders o JOIN person p ON o.pid = p.pid");
    assertThat(logger.warnings).hasSize(2);
  }

  @Test
  void theSameStatementWithOtherLiteralsOrOtherSpacingIsTheSameStatement() {
    rows("SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE p.age > 30 LIMIT 3");
    rows("select p.name from orders o join person p on o.pid = p.pid where p.age > 40 limit 1  -- another run");
    rows("SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE p.age > 50.5 LIMIT 9");
    assertThat(logger.warnings).hasSize(1);
    assertThat(logger.warnings.get(0)).doesNotContain("p.age > 30").doesNotContain("p.age > 40")
        .contains("(p.age > ?)").contains("LIMIT ?");
    rows("SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid WHERE p.city = 'Delft'");
    assertThat(logger.warnings).as("another condition is another statement").hasSize(2);
  }

  @Test
  void aStatementWithTwoUnsupportedJoinsLogsOneWarningThatNamesBoth() {
    rows("SELECT a.name FROM person a JOIN person b ON a.city = b.city JOIN person c ON b.city = c.city");
    assertThat(logger.warnings).hasSize(1);
    assertThat(logger.warnings.get(0).split("The join of ", -1)).hasSize(3);
  }

  // ---- what logs nothing ----

  @Test
  void aJoinThatAnIndexAnEdgeOrALinkSupportsLogsNothing() {
    addTheIndexOfSmall();
    assertThat(rows(UNSUPPORTED)).hasSize(5);
    rows("SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id");
    rows("SELECT p.name FROM orders o JOIN person p ON o.buyer = p.id WHERE o.oid = 102");
    rows("SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid");
    assertThat(logger.warnings).isEmpty();
  }

  @Test
  void theEdgeJoinLogsNothingEvenWithoutAnyIndex() {
    rows("SELECT p.name, c.name FROM person p LEFT JOIN works_at w ON w.source_id = p.id LEFT JOIN company c ON w.target_id = c.id");
    rows("SELECT c.name FROM company c, works_at w WHERE w.target_id = c.id");
    assertThat(logger.warnings).isEmpty();
  }

  @Test
  void withTheSwitchOffNothingIsLoggedAndTheRowsAreTheSame() {
    final List<List<Object>> with = rows(UNSUPPORTED);
    assertThat(logger.warnings).hasSize(1);
    JoinWarnings.shared().clear();
    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_JOIN_WARNING, false);
    assertThat(rows(UNSUPPORTED)).isEqualTo(with);
    assertThat(logger.warnings).as("nothing new").hasSize(1);
    database.getConfiguration().setValue(GlobalConfiguration.POSTGRESQL_JOIN_WARNING, true);
    assertThat(GlobalConfiguration.POSTGRESQL_JOIN_WARNING.getKey()).isEqualTo("arcadedb.postgresql.joinWarning");
    assertThat(GlobalConfiguration.POSTGRESQL_JOIN_WARNING.getDefValue()).isEqualTo(true);
  }

  @Test
  void aJoinWithAViewOfInformationSchemaLogsNothing() {
    assertThat(rows("SELECT count(*) FROM information_schema.tables t JOIN information_schema.columns c ON t.table_name = c.table_name"))
        .hasSize(1);
    assertThat(rows("SELECT count(*) FROM person p JOIN information_schema.tables t ON t.table_name = p.name")).hasSize(1);
    assertThat(logger.warnings).isEmpty();
    assertThat(explain("SELECT count(*) FROM person p JOIN information_schema.tables t ON t.table_name = p.name"))
        .noneMatch(line -> line.startsWith("WARNING"));
  }

  // ---- the plan ----

  @Test
  void explainShowsTheSentenceAndLogsNothingSoTheStatementStillWarnsWhenItRuns() {
    final List<String> lines = explain(UNSUPPORTED);
    assertThat(lines).anyMatch(line -> line.startsWith("WARNING: The join of orders and person on (o.pid = p.pid)"));
    assertThat(logger.warnings).isEmpty();
    rows(UNSUPPORTED);
    assertThat(logger.warnings).hasSize(1);
    final String sentence = lines.stream().filter(line -> line.startsWith("WARNING: ")).findFirst().orElseThrow().substring(9);
    assertThat(logger.warnings.get(0)).contains(sentence);
  }

  @Test
  void theResultCarriesTheWarningInItsPlanAndInWarnings() {
    try (final PostgreSQLResultSet result = (PostgreSQLResultSet) database.query("postgresql", UNSUPPORTED)) {
      assertThat(result.warnings()).hasSize(1);
      assertThat(result.getExecutionPlan().orElseThrow().prettyPrint(0, 2)).contains("WARNING: " + result.warnings().get(0));
    }
    addTheIndexOfSmall();
    try (final PostgreSQLResultSet result = (PostgreSQLResultSet) database.query("postgresql", UNSUPPORTED)) {
      assertThat(result.warnings()).isEmpty();
      assertThat(result.getExecutionPlan().orElseThrow().prettyPrint(0, 2)).doesNotContain("WARNING");
    }
  }

  @Test
  void theSentenceNamesTheEdgeTypesThatConnectTheTwoTablesWhenThereAreSome() {
    final List<String> lines = explain("SELECT p.name FROM person p JOIN company c ON p.name = c.name");
    assertThat(lines).anyMatch(line -> line.startsWith("WARNING: ") && line.contains("The edge type works_at (person to company) connects them")
        && line.contains("join through it") && !line.contains("No edge type connects"));
  }

  // ---- the fingerprint ----

  @Test
  void theFingerprintReplacesLiteralsAndKeepsEverythingElse() {
    final String a = AstPrinter.fingerprint(StatementParser.parse("SELECT name FROM person WHERE age > 30 AND city = 'Delft' LIMIT 5"));
    final String b = AstPrinter.fingerprint(StatementParser.parse("select  name\nfrom person /* c */ where age > 99.5 and city = 'x''y' limit 7"));
    assertThat(a).isEqualTo(b).isEqualTo("SELECT name FROM person WHERE ((age > ?) AND (city = ?)) LIMIT ?");
    assertThat(AstPrinter.fingerprint(StatementParser.parse("SELECT name FROM person WHERE age > $1 AND city IS NULL")))
        .contains("$1").contains("IS NULL");
    assertThat(AstPrinter.print(StatementParser.parse("SELECT 30"))).as("printing outside a fingerprint keeps the literals")
        .isEqualTo("SELECT 30");
  }

  // ---- the bound ----

  @Test
  void theRememberedStatementsAreBoundedAndTheOneUsedLongestAgoIsForgottenFirst() {
    final JoinWarnings small = new JoinWarnings(3);
    assertThat(small.first("db", "a")).isTrue();
    assertThat(small.first("db", "b")).isTrue();
    assertThat(small.first("db", "c")).isTrue();
    assertThat(small.first("db", "a")).as("a is known, and is now the newest").isFalse();
    assertThat(small.first("db", "d")).as("d is new and pushes out b, the one used longest ago").isTrue();
    assertThat(small.size()).isEqualTo(3);
    assertThat(small.first("db", "a")).as("a stayed remembered, it kept running").isFalse();
    assertThat(small.first("db", "b")).as("b was forgotten, so it warns again").isTrue();
    assertThat(small.size()).isEqualTo(3);
    assertThat(small.first("other", "a")).as("the database is part of the key").isTrue();
  }

  @Test
  void afterTenThousandDistinctStatementsTheSharedSetHoldsAtMostTheCap() {
    assertThat(JoinWarnings.DEFAULT_CAP).isEqualTo(10_000);
    final JoinWarnings shared = JoinWarnings.shared();
    for (int i = 0; i < 10_000; i++)
      assertThat(shared.first("db", "statement " + i)).isTrue();
    assertThat(shared.size()).isEqualTo(10_000);
    for (int i = 10_000; i < 10_500; i++)
      shared.first("db", "statement " + i);
    assertThat(shared.size()).isEqualTo(10_000);
    assertThat(shared.first("db", "statement 10499")).as("the newest is remembered").isFalse();
    assertThat(shared.first("db", "statement 0")).as("the oldest was forgotten").isTrue();
  }

  @Test
  void aCapBelowOneIsRefused() {
    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new JoinWarnings(0));
  }

  // ---- threads ----

  @Test
  void twoThreadsThatRunTheSameNewStatementAtOnceLogOneWarning() throws Exception {
    final JoinWarnings warnings = new JoinWarnings(100);
    final int threads = 16;
    final CountDownLatch ready = new CountDownLatch(threads);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final AtomicInteger written = new AtomicInteger();
    for (int t = 0; t < threads; t++)
      new Thread(() -> {
        ready.countDown();
        try {
          go.await();
          if (warnings.warn("db", "SELECT x FROM a JOIN b ON a.k = b.k", List.of("The join of a and b has nothing behind it.")))
            written.incrementAndGet();
        } catch (final InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      }).start();
    ready.await();
    go.countDown();
    done.await();
    assertThat(written.get()).isEqualTo(1);
    assertThat(logger.warnings).hasSize(1);
  }
}
