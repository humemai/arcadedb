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
package com.arcadedb.query.postgresql.parser;

import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.PostgreSQLInvalidEscapeException;
import com.arcadedb.query.postgresql.PostgreSQLSyntaxException;
import com.arcadedb.query.postgresql.corpus.Corpus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accept and reject differential of issue #3 against the statements of the corpus file
 * {@code postgresql/corpus/syntax.tsv}. For each statement the parser answers one of three things (it accepts, it refuses
 * with 0A000, it refuses with 42601) and the answer has to agree with what the file records: what PostgreSQL 18 does with
 * the statement (the {@code pg18} column) and whether the supported subset covers it (the {@code subset} column). An entry whose
 * {@code pg18} is {@code pending-server} is not checked against PostgreSQL; its answer is printed. An entry with a reason in the
 * {@code gap} column is a known disagreement, and the test fails when it stops disagreeing, so the list can only shrink.
 *
 * <p>The parity harness of issue #2 reads the same file and owns the {@code pg18} column: it asks a real server and writes the
 * answer.
 */
class SyntaxCorpusTest {
  private enum Verdict {
    ACCEPTED, REFUSED_0A000, REFUSED_42601, REFUSED_OTHER, INTERNAL_ERROR
  }

  /** {@code line} is the number of the entry in the corpus, from 1. */
  private record Entry(int line, String id, String pg18, String subset, String gap, String statement, String source,
                       String detail) {
  }

  /** The corpus is read through the classes of the parity harness, which also write it: {@code Corpus}. */
  private static List<Entry> read() throws IOException {
    final List<Entry> entries = new ArrayList<>();
    int number = 0;
    for (final Corpus.SyntaxCase c : Corpus.syntaxCases(Corpus.readSyntax()))
      entries.add(new Entry(++number, c.id(), c.pg18(), c.subset(), c.gap(), c.statement(), c.source(), c.detail()));
    return entries;
  }

  private static Verdict verdictOf(final Entry entry, final StringBuilder detail) {
    try {
      StatementParser.parse(entry.statement());
      return Verdict.ACCEPTED;
    } catch (final PostgreSQLFeatureNotSupportedException e) {
      detail.append(e.getMessage());
      return Verdict.REFUSED_0A000;
    } catch (final PostgreSQLSyntaxException e) {
      detail.append(e.getMessage());
      return Verdict.REFUSED_42601;
    } catch (final PostgreSQLInvalidEscapeException e) {
      // refused with a SQLSTATE of its own, which the server wrote in the detail column
      detail.append(e.getSqlState()).append(": ").append(e.getMessage());
      return Verdict.REFUSED_OTHER;
    } catch (final RuntimeException | StackOverflowError e) {
      detail.append(e);
      return Verdict.INTERNAL_ERROR;
    }
  }

  /** @return why the verdict disagrees with the entry, or {@code null} when they agree */
  private static String disagreement(final Entry entry, final Verdict verdict, final String answer) {
    if (verdict == Verdict.INTERNAL_ERROR)
      return "the parser failed with an exception that is not one of the two refusals";
    switch (entry.subset()) {
    case "inside":
      if (verdict != Verdict.ACCEPTED)
        return "inside the subset, so it must parse";
      break;
    case "outside":
      if (verdict != Verdict.REFUSED_0A000)
        return "outside the subset, so it must be refused with 0A000";
      break;
    case "unknown":
      break;
    default:
      throw new IllegalStateException("Unknown subset value at line " + entry.line() + ": " + entry.subset());
    }
    switch (entry.pg18()) {
    case "ok":
      if (verdict == Verdict.REFUSED_42601)
        return "PostgreSQL 18 accepts it, so it must not be a syntax error";
      // the scanner of PostgreSQL refuses some text with a SQLSTATE that is not a syntax matter; the server wrote it
      if (verdict == Verdict.REFUSED_OTHER && !answer.startsWith(entry.detail() + ":"))
        return "PostgreSQL 18 answers " + entry.detail() + " and the parser another SQLSTATE";
      break;
    case "syntax-error":
      if (verdict != Verdict.REFUSED_42601)
        return "PostgreSQL 18 says syntax error, so it must be refused with 42601";
      break;
    case "feature-not-supported":
      if (verdict != Verdict.REFUSED_0A000)
        return "PostgreSQL 18 says feature not supported, so it must be refused with 0A000";
      break;
    case "pending-server":
      break;
    default:
      throw new IllegalStateException("Unknown pg18 value at line " + entry.line() + ": " + entry.pg18());
    }
    return null;
  }

  @Test
  void theParserAgreesWithTheCorpus() throws IOException {
    final List<Entry> entries = read();
    assertThat(entries).as("entries of the corpus").hasSizeGreaterThan(1000);

    final List<String> failures = new ArrayList<>();
    final Map<String, Integer> summary = new TreeMap<>();
    final Map<String, Integer> pending = new TreeMap<>();
    int gaps = 0;
    for (final Entry entry : entries) {
      final StringBuilder detail = new StringBuilder();
      final Verdict verdict = verdictOf(entry, detail);
      final String reason = disagreement(entry, verdict, detail.toString());
      final boolean isGap = !"-".equals(entry.gap());

      summary.merge("pg18=" + entry.pg18() + " subset=" + entry.subset() + " -> " + verdict, 1, Integer::sum);
      if ("pending-server".equals(entry.pg18()))
        pending.merge(entry.subset() + " -> " + verdict, 1, Integer::sum);

      final String description = entry.id() + " (line " + entry.line() + ", " + entry.source() + "): " + verdict + " " + detail
          + "\n      " + entry.statement().replace("\n", "\n      ");
      if (isGap) {
        ++gaps;
        if (reason == null)
          failures.add("A gap that no longer disagrees, remove its gap mark: " + description);
      } else if (reason != null) {
        failures.add(reason + ": " + description);
      }
    }

    System.out.println("Syntax corpus: " + entries.size() + " entries, " + gaps + " known gaps");
    summary.forEach((key, count) -> System.out.println("  " + count + "\t" + key));
    System.out.println("Entries still pending a server, as the parser answers them (informational):");
    pending.forEach((key, count) -> System.out.println("  " + count + "\t" + key));

    assertThat(failures).as("%d disagreements:\n%s", failures.size(), String.join("\n", failures)).isEmpty();
  }
}
