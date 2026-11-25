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
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.corpus.Corpus;
import com.arcadedb.query.postgresql.corpus.CorpusFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The round trip of issue #3 over the whole accepted corpus: every statement of every corpus file that the parser accepts (the
 * checks of the write cases too) is printed by {@link AstPrinter}, and the print parses to an equal tree that prints the same
 * text. A statement the parser refuses is not part of this test: {@link SyntaxCorpusTest} holds the refusals.
 */
class CorpusRoundTripTest {
  private static final List<String> FILES = List.of(Corpus.SYNTAX_FILE, Corpus.ANSWERS_FILE, Corpus.WRITES_FILE,
      Corpus.IDRULE_FILE, Corpus.IDRULE_WRITES_FILE, Corpus.VALUES_WRITES_FILE, Corpus.GENERATED_FILE, Corpus.TPCH_FILE,
      "pgvector-doc.tsv", Corpus.AGENT_SQL_FILE);

  /** @return the tree, or {@code null} when the parser refuses the text with one of its three refusals */
  private static Statement accepted(final String text) {
    try {
      return StatementParser.parse(text);
    } catch (final PostgreSQLFeatureNotSupportedException | PostgreSQLSyntaxException | PostgreSQLInvalidEscapeException e) {
      return null;
    }
  }

  /** @return why the print of the tree does not read back as the tree, or {@code null} when it does */
  private static String brokenRoundTrip(final Statement tree) {
    String printed = null;
    try {
      printed = AstPrinter.print(tree);
      final Statement again = StatementParser.parse(printed);
      if (!again.equals(tree))
        return "the print parses to another tree: " + printed;
      final String reprinted = AstPrinter.print(again);
      return reprinted.equals(printed) ? null : "the second print differs: " + printed + " / " + reprinted;
    } catch (final RuntimeException | StackOverflowError e) {
      return "the print is not read back: " + printed + " : " + e;
    }
  }

  private static List<String> statements(final CorpusFile file, final String[] row) {
    final List<String> texts = new ArrayList<>();
    texts.add(CorpusFile.unescape(row[file.column("statement")]));
    if (file.columns.contains("checks"))
      texts.addAll(new Corpus.WriteCase(row).checks());
    return texts;
  }

  @Test
  void printingATreeAndParsingThePrintGivesAnEqualTreeForEveryAcceptedStatement() throws IOException {
    final List<String> failures = new ArrayList<>();
    int total = 0;
    for (final String name : FILES) {
      final CorpusFile file = CorpusFile.readResource(Corpus.RESOURCE_DIRECTORY + name);
      int read = 0;
      int trees = 0;
      for (final String[] row : file.rows)
        for (final String text : statements(file, row)) {
          ++read;
          final Statement tree = accepted(text);
          if (tree == null)
            continue;
          ++trees;
          final String reason = brokenRoundTrip(tree);
          if (reason != null)
            failures.add(name + " " + row[0] + ": " + reason + "\n      " + text.replace("\n", "\n      "));
        }
      System.out.println("Round trip: " + name + ": " + trees + " accepted of " + read + " statements");
      assertThat(trees).as("accepted statements of %s", name).isPositive();
      if (Corpus.SYNTAX_FILE.equals(name))
        assertThat(trees).as("every entry that is inside the subset is accepted, so it is printed and read back").isEqualTo(
            (int) Corpus.syntaxCases(file).stream().filter(Corpus.SyntaxCase::inside).count());
      total += trees;
    }
    System.out.println("Round trip: " + total + " accepted statements in all, " + failures.size() + " that do not read back");
    assertThat(failures).as("%d statements do not read back:\n%s", failures.size(), String.join("\n", failures)).isEmpty();
  }
}
