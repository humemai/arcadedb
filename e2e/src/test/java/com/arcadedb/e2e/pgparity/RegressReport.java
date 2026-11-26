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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The files a run of the regression harness writes (fork issue #119): for each regression file {@code <file>.tsv} with one line
 * for each statement, and {@code summary.md} with the counts.
 */
final class RegressReport {
  private static final String HEADER = "file\tline\tverdict\tpostgresql_sqlstate\ttested_sqlstate\treason\tout_of_scope_entry"
      + "\ton_copied_table\tstatement";

  private record Count(String title, ToLongFunction<RegressRun.FileResult> of) {
  }

  private static final List<Count> COUNTS = List.of(new Count("statements", result -> result.statements),
      new Count("passed", result -> result.count(RegressJudge.Verdict.PASSED)),
      new Count("refused, `0A000`", result -> refused(result, true)),
      new Count("refused, other code", result -> refused(result, false)),
      new Count("wrong", result -> result.count(RegressJudge.Verdict.WRONG)),
      new Count("blocked", result -> result.count(RegressJudge.Verdict.BLOCKED)),
      new Count("out of scope", result -> result.count(RegressJudge.Verdict.OUT_OF_SCOPE)),
      new Count("judged on a copied table", result -> result.lines.stream().filter(RegressRun.Line::onCopy).count()));

  private RegressReport() {
  }

  private static long refused(final RegressRun.FileResult result, final boolean featureNotSupported) {
    return result.lines.stream().filter(line -> line.verdict() == RegressJudge.Verdict.REFUSED
        && RegressJudge.FEATURE_NOT_SUPPORTED.equals(line.subjectState()) == featureNotSupported).count();
  }

  static String line(final RegressRun.Line line) {
    return String.join("\t", line.file(), String.valueOf(line.line()), line.verdict().text, line.serverState(),
        line.subjectState(), cell(line.reason()), line.entry(), line.onCopy() ? "yes" : "", cell(line.statement()));
  }

  private static String cell(final String text) {
    return text == null ? "" : text.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
  }

  static void write(final Path directory, final RegressRun.FileResult result) throws IOException {
    Files.createDirectories(directory);
    final List<String> lines = new ArrayList<>();
    lines.add(HEADER);
    result.lines.forEach(line -> lines.add(line(line)));
    Files.write(directory.resolve(result.file + ".tsv"), lines, StandardCharsets.UTF_8);
  }

  /** @param about lines that say what ran: the server, the files, the engine, the run */
  static String summary(final List<String> about, final List<RegressRun.FileResult> results, final RegressOutOfScope outOfScope) {
    final StringBuilder out = new StringBuilder();
    for (final String line : about)
      out.append("- ").append(line).append('\n');

    out.append("\n| file |");
    COUNTS.forEach(count -> out.append(' ').append(count.title()).append(" |"));
    out.append("\n| --- |").append(" ---: |".repeat(COUNTS.size())).append('\n');
    for (final RegressRun.FileResult result : results) {
      out.append("| `").append(result.file).append("` |");
      COUNTS.forEach(count -> out.append(' ').append(count.of().applyAsLong(result)).append(" |"));
      out.append('\n');
    }
    out.append("| all |");
    COUNTS.forEach(count -> out.append(' ').append(results.stream().mapToLong(count.of()).sum()).append(" |"));
    out.append('\n');

    out.append("\n| out of scope, entry | reason | statements |\n| --- | ---: | ---: |\n");
    for (final RegressOutOfScope.Entry entry : outOfScope.entries()) {
      final long count = results.stream().flatMap(result -> result.lines.stream())
          .filter(line -> line.entry().equals(entry.id())).count();
      if (count > 0)
        out.append("| `").append(entry.id()).append("` | ").append(entry.reason()).append(" | ").append(count).append(" |\n");
    }

    out.append("\n| file | `psql` commands | statements `psql` does not send | errors of PostgreSQL | `ERROR` lines of the "
        + "expected output | the run of the file failed |\n| --- | ---: | ---: | ---: | ---: | --- |\n");
    for (final RegressRun.FileResult result : results)
      out.append("| `").append(result.file).append("` | ").append(result.commands).append(" | ").append(result.skipped)
          .append(" | ").append(result.serverErrors).append(" | ").append(result.expectedErrors).append(" | ")
          .append(result.failure == null ? "" : result.failure.replace('\n', ' ').replace('|', '/')).append(" |\n");
    return out.toString();
  }
}
