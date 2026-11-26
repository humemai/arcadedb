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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs PostgreSQL's regression files and gives each statement a verdict (fork issue #119). The rules are in
 * {@code docs/postgresql-regression-rules.md}. The files are not part of this repository: {@code .github/scripts/pg_regress.sh}
 * fetches them at their tag and starts this class, which does nothing without them.
 * <ul>
 * <li>{@code -Dpgregress.dir=<dir>}: the directory {@code src/test/regress} of the PostgreSQL sources</li>
 * <li>{@code -Dpgregress.files=<names>}: the files, separated by commas, or {@code all}; default the eight files that the syntax
 * corpus draws on</li>
 * <li>{@code -Dpgregress.subject=postgresql}: PostgreSQL on both sides, where only passed and out of scope may come out</li>
 * <li>{@code -Dpgregress.timeout=<seconds>}: the time a statement has on ArcadeDB, default 30</li>
 * </ul>
 * It writes {@code target/pgregress/<subject>/<file>.tsv}, one line for each statement, and {@code summary.md}. It fails when
 * the run of a file does not finish and when the verdicts of a file do not add up to its statements, never on a verdict of
 * ArcadeDB.
 */
class PgRegressIT {
  private static final String EIGHT = "case,join,limit,select,select_distinct,select_having,select_implicit,strings";

  @Test
  void regressionFiles() throws Exception {
    final String directory = System.getProperty("pgregress.dir", "");
    assumeTrue(!directory.isBlank(), "no -Dpgregress.dir: the regression files are fetched by .github/scripts/pg_regress.sh");
    final Path regress = Path.of(directory).toAbsolutePath().normalize();
    final String subject = System.getProperty("pgregress.subject", "arcadedb");
    assertTrue(subject.equals("arcadedb") || subject.equals("postgresql"), "pgregress.subject is arcadedb or postgresql");
    final long timeout = Long.getLong("pgregress.timeout", 30L);
    final List<String> files = files(regress, System.getProperty("pgregress.files", ""));
    final Path output = Path.of("target", "pgregress", subject);

    final List<RegressRun.FileResult> results = new ArrayList<>();
    final List<String> problems = new ArrayList<>();
    try (final RegressServer first = RegressServer.start(regress, 0);
        final RegressServer second = RegressServer.start(regress, 1_000);
        final RegressServer third = subject.equals("postgresql") ? RegressServer.start(regress, 2_000) : null) {
      final Supplier<RegressSubject> subjects = third != null ? () -> new RegressPostgresSubject(third)
          : () -> new RegressArcade(timeout);
      final RegressRun run = new RegressRun(regress, first, second, subjects);
      final List<String> about = List.of("side under test: " + subject, "PostgreSQL: " + second.version(),
          "regression files: " + System.getProperty("pgregress.tag", "unknown"),
          "engine: " + System.getProperty("pgregress.engine", "unknown"),
          "CI run: " + System.getProperty("pgregress.run", "none"), "date: " + Instant.now());

      for (final String file : files) {
        System.out.println("pgregress: " + file);
        final RegressRun.FileResult result = run.run(file);
        results.add(result);
        RegressReport.write(output, result);
        // written after every file, so that a run that is cut off leaves its counts
        Files.writeString(output.resolve("summary.md"),
            RegressReport.summary(about, results, RegressOutOfScope.load()), StandardCharsets.UTF_8);
        if (result.failure != null)
          problems.add(file + ": " + result.failure);
        else
          result.check();
        if (third != null)
          result.lines.stream()
              .filter(line -> line.verdict() != RegressJudge.Verdict.PASSED && line.verdict() != RegressJudge.Verdict.OUT_OF_SCOPE)
              .forEach(line -> problems.add(RegressReport.line(line)));
      }
      System.out.println(RegressReport.summary(about, results, RegressOutOfScope.load()));
    }
    assertTrue(problems.isEmpty(), () -> problems.size() + " problems, the first:\n"
        + String.join("\n", problems.subList(0, Math.min(30, problems.size()))));
  }

  /** The files of the run: the named ones, or for {@code all} those of the schedule in its order and then any other. */
  private static List<String> files(final Path regress, final String wanted) throws IOException {
    if (wanted.isBlank())
      return List.of(EIGHT.split(","));
    if (!wanted.equals("all"))
      return List.of(wanted.split(","));
    final Set<String> files = new LinkedHashSet<>();
    for (final String line : Files.readAllLines(regress.resolve("parallel_schedule"), StandardCharsets.UTF_8))
      if (line.startsWith("test: "))
        files.addAll(List.of(line.substring(6).strip().split("\\s+")));
    try (final Stream<Path> paths = Files.list(regress.resolve("sql"))) {
      paths.map(path -> path.getFileName().toString()).filter(name -> name.endsWith(".sql"))
          .map(name -> name.substring(0, name.length() - 4)).sorted().forEach(files::add);
    }
    return List.copyOf(files);
  }
}
