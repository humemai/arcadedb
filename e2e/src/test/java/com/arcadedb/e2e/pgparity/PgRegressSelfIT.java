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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-tests of the regression harness (fork issue #119) on a script of its own, {@code pgregress/self_test.sql}: PostgreSQL
 * against PostgreSQL gives only passed and out of scope, a planted wrong row and a planted extra row in a table are reported as
 * wrong, a planted refusal as refused, and the same script runs to its end with ArcadeDB on the side under test.
 */
class PgRegressSelfIT {
  private static final String FILE       = "self_test";
  private static final int    STATEMENTS = 27;

  private static RegressServer first;
  private static RegressServer second;
  private static RegressServer third;
  private static Path          regress;

  @BeforeAll
  static void start() throws Exception {
    regress = Files.createTempDirectory("pgregress-self");
    Files.createDirectories(regress.resolve("sql"));
    Files.writeString(regress.resolve("sql").resolve(FILE + ".sql"),
        RegressOutOfScope.resource("/pgregress/self_test.sql"), StandardCharsets.UTF_8);
    first = RegressServer.start(null, 0);
    second = RegressServer.start(null, 1_000);
    third = RegressServer.start(null, 2_000);
  }

  @AfterAll
  static void stop() throws IOException {
    for (final RegressServer server : new RegressServer[] { first, second, third })
      if (server != null)
        server.close();
    Files.deleteIfExists(regress.resolve("sql").resolve(FILE + ".sql"));
    Files.deleteIfExists(regress.resolve("sql"));
    Files.deleteIfExists(regress);
  }

  private static RegressRun.FileResult run(final Supplier<RegressSubject> subjects) throws IOException {
    final RegressRun.FileResult result = new RegressRun(regress, first, second, subjects).run(FILE);
    RegressReport.write(Path.of("target", "pgregress", "self-test"), result);
    result.lines.forEach(line -> System.out.println("pgregress: " + RegressReport.line(line)));
    assertThat(result.failure).isNull();
    result.check();
    assertThat(result.statements).isEqualTo(STATEMENTS);
    assertThat(result.skipped).isEqualTo(1);
    return result;
  }

  private static RegressRun.Line line(final RegressRun.FileResult result, final String part) {
    return result.lines.stream().filter(line -> line.statement().contains(part)).findFirst().orElseThrow();
  }

  private static void onlyPassedOrOutOfScope(final List<RegressRun.Line> lines) {
    assertThat(lines).allSatisfy(line -> assertThat(line.verdict()).as(RegressReport.line(line))
        .isIn(RegressJudge.Verdict.PASSED, RegressJudge.Verdict.OUT_OF_SCOPE));
  }

  @Test
  void postgresAgainstPostgresGivesOnlyPassedAndOutOfScope() throws Exception {
    final RegressRun.FileResult result = run(() -> new RegressPostgresSubject(third));

    onlyPassedOrOutOfScope(result.lines);
    assertThat(line(result, "SELECT now()").entry()).isEqualTo("two-runs");
    assertThat(line(result, "SELECT random()").entry()).isEqualTo("two-runs");
    assertThat(line(result, "EXPLAIN").entry()).isEqualTo("explain");
    assertThat(line(result, "ctid").entry()).isEqualTo("system-column");
    assertThat(line(result, "pg_class").entry()).isEqualTo("catalog-relation");
    assertThat(line(result, "TABLESPACE").entry()).isEqualTo("tablespace");
    assertThat(line(result, "SELECT 1/0").serverState()).isEqualTo("22012");
    assertThat(line(result, "SELECT 1/0").verdict()).isEqualTo(RegressJudge.Verdict.PASSED);
    assertThat(line(result, "sum(age)").verdict()).isEqualTo(RegressJudge.Verdict.PASSED);
  }

  @Test
  void aPlantedWrongRowIsReportedAsWrong() throws Exception {
    final RegressRun.FileResult result = run(() -> new Planted(new RegressPostgresSubject(third), "planted_row",
        (real, send) -> {
          final RegressSubject.Answer answer = real.execute(send, () -> "");
          return new RegressSubject.Answer(answer.columns(), answer.types(), List.of(List.of("eve")), answer.affected(), null,
              null, false);
        }));

    final RegressRun.Line planted = line(result, "planted_row");
    assertThat(planted.verdict()).isEqualTo(RegressJudge.Verdict.WRONG);
    assertThat(planted.reason()).contains("eve");
    onlyPassedOrOutOfScope(result.lines.stream().filter(each -> each != planted).toList());
  }

  @Test
  void aPlantedRefusalIsReportedAsRefused() throws Exception {
    final RegressRun.FileResult result = run(() -> new Planted(new RegressPostgresSubject(third), "planted_refusal",
        (real, send) -> RegressSubject.Answer.error(RegressJudge.FEATURE_NOT_SUPPORTED, "planted refusal")));

    final RegressRun.Line planted = line(result, "planted_refusal");
    assertThat(planted.verdict()).isEqualTo(RegressJudge.Verdict.REFUSED);
    assertThat(planted.subjectState()).isEqualTo(RegressJudge.FEATURE_NOT_SUPPORTED);
    onlyPassedOrOutOfScope(result.lines.stream().filter(each -> each != planted).toList());
  }

  @Test
  void aPlantedRowInATableIsReportedAsWrong() throws Exception {
    final RegressRun.FileResult result = run(() -> new Planted(new RegressPostgresSubject(third), "planted_write",
        (real, send) -> {
          real.execute(send, () -> "");
          return real.execute(send, () -> "");
        }));

    final RegressRun.Line planted = line(result, "planted_write");
    assertThat(planted.verdict()).isEqualTo(RegressJudge.Verdict.WRONG);
    assertThat(planted.reason()).contains("log");
  }

  @Test
  void theScriptRunsToItsEndOnArcadeDB() throws Exception {
    final RegressRun.FileResult result = run(() -> new RegressArcade(30));

    assertThat(line(result, "SELECT now()").verdict()).isEqualTo(RegressJudge.Verdict.OUT_OF_SCOPE);
    assertThat(line(result, "BEGIN").verdict()).isEqualTo(RegressJudge.Verdict.PASSED);
  }

  /** A side under test that answers one statement, the one that holds {@code marker}, otherwise than the side it wraps. */
  private record Planted(RegressSubject real, String marker,
      BiFunction<RegressSubject, RegressPsql.Send, RegressSubject.Answer> instead) implements RegressSubject {
    @Override
    public void setup(final RegressPsql.Send send, final Supplier<String> copyData) {
      real.setup(send, copyData);
    }

    @Override
    public Answer execute(final RegressPsql.Send send, final Supplier<String> copyData) {
      return send.sql().contains(marker) ? instead.apply(real, send) : real.execute(send, copyData);
    }

    @Override
    public Set<String> touched() {
      return real.touched();
    }

    @Override
    public Shape shape(final RegressDatabase.Table table) {
      return real.shape(table);
    }

    @Override
    public List<List<Object>> rows(final RegressDatabase.Table table) {
      return real.rows(table);
    }

    @Override
    public void close() {
      real.close();
    }
  }
}
