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
package com.arcadedb.query.postgresql.corpus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed corpus files are well formed: the columns of their header, unique ids, the values of the closed columns, and
 * the form of every {@code expected} cell that the generator has filled. This runs without a server, so a hand edit that breaks
 * a file is found in the fast loop and not in the run of the harness. Whether the {@code expected} cells are still what the
 * real server says is the job of the harness ({@code PgParityIT} in the {@code e2e} module).
 */
class CorpusFilesTest {
  private static final Pattern ID        = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  /** A name, or the test of PostgreSQL and the line of a statement taken from it: select:93. */
  private static final Pattern SYNTAX_ID = Pattern.compile("[a-z0-9_]+([-:][a-z0-9_]+)*");
  private static final Pattern SQLSTATE  = Pattern.compile("[0-9A-Z]{5}");

  @Test
  void syntaxFileIsWellFormed() throws IOException {
    final CorpusFile file = Corpus.readSyntax();
    assertThat(file.columns).containsExactly("id", "pg18", "subset", "gap", "statement", "source", "detail");
    assertThat(file.rows).isNotEmpty();
    // after a run of the generator every entry has been asked of a server and the two columns agree with each other
    final boolean generated = file.headerValue("server") != null;

    final Set<String> ids = new HashSet<>();
    final Set<String> statements = new HashSet<>();
    for (final Corpus.SyntaxCase c : Corpus.syntaxCases(file)) {
      assertThat(c.id()).as("id of %s", c.statement()).matches(SYNTAX_ID);
      assertThat(ids.add(c.id())).as("unique id %s", c.id()).isTrue();
      assertThat(statements.add(c.statement())).as("unique statement of %s", c.id()).isTrue();
      assertThat(c.pg18()).as("pg18 of %s", c.id()).isIn("ok", "syntax-error", "feature-not-supported", Corpus.PENDING_SERVER);
      assertThat(c.subset()).as("subset of %s", c.id()).isIn("inside", "outside", "unknown");
      assertThat(c.gap()).as("gap of %s", c.id()).isNotEmpty();
      assertThat(c.source()).as("source of %s", c.id()).isNotEmpty();
      assertThat(c.statement()).as("statement of %s", c.id()).isNotEmpty();
      assertThat(c.detail()).as("detail of %s", c.id()).satisfiesAnyOf(d -> assertThat(d).isEqualTo("-"),
          d -> assertThat(d).matches(SQLSTATE));
      if (!generated)
        continue;
      assertThat(c.hasOutcome()).as("%s has an outcome from the server", c.id()).isTrue();
      switch (c.pg18()) {
      case "syntax-error" -> assertThat(c.detail()).as("detail of %s", c.id()).isEqualTo("42601");
      case "feature-not-supported" -> assertThat(c.detail()).as("detail of %s", c.id()).isEqualTo("0A000");
      default -> assertThat(c.detail()).as("detail of %s", c.id()).isNotIn("42601", "0A000");
      }
    }
  }

  @Test
  void answersFileIsWellFormed() throws IOException {
    final CorpusFile file = Corpus.readAnswers();
    assertThat(file.columns).containsExactly("id", "order", "tol", "status", "statement", "expected");
    assertThat(file.rows).isNotEmpty();

    final Set<String> ids = new HashSet<>();
    for (final Corpus.AnswerCase c : Corpus.answerCases(file)) {
      assertThat(c.id()).as("id of %s", c.statement()).matches(ID);
      assertThat(ids.add(c.id())).as("unique id %s", c.id()).isTrue();
      assertThat(c.row[Corpus.AnswerCase.ORDER]).as("order of %s", c.id()).isIn("ordered", "multiset");
      assertThat(c.row[Corpus.AnswerCase.STATUS]).as("status of %s", c.id()).isIn("ok", "pending");
      assertThat(c.tolerance()).as("tolerance of %s", c.id()).isPositive();
      assertThat(c.statement()).as("statement of %s", c.id()).isNotEmpty();

      if (!c.hasOutcome())
        continue;
      final Outcome outcome = Outcome.fromJson(c.expected());
      assertThat(outcome.isRefusal()).as("%s: a refusal exactly for an err- id", c.id()).isEqualTo(c.expectsRefusal());
      if (outcome.isRefusal())
        assertThat(outcome.sqlState).as("SQLSTATE of %s", c.id()).matches(SQLSTATE);
      else {
        assertThat(outcome.table.labels).as("columns of %s", c.id()).isNotEmpty();
        assertThat(outcome.table.types).as("types of %s", c.id()).hasSameSizeAs(outcome.table.labels);
        for (final List<Object> row : outcome.table.rows)
          assertThat(row).as("a row of %s", c.id()).hasSameSizeAs(outcome.table.labels);
        // an ordered statement has an ORDER BY, so the statement says so
        if (c.ordered())
          assertThat(c.statement().toUpperCase()).as("ordered %s has an ORDER BY", c.id()).contains("ORDER BY");
      }
    }
  }

  @Test
  void aFilledFileNamesTheServerThatProducedIt() throws IOException {
    final CorpusFile syntax = Corpus.readSyntax();
    final CorpusFile answers = Corpus.readAnswers();
    boolean filled = false;
    for (final Corpus.SyntaxCase c : Corpus.syntaxCases(syntax))
      if (c.hasOutcome() && "s3".equals(c.source()))
        filled = true;
    for (final Corpus.AnswerCase c : Corpus.answerCases(answers))
      if (c.hasOutcome())
        filled = true;
    if (filled)
      for (final CorpusFile file : List.of(syntax, answers)) {
        assertThat(file.headerValue("server")).as("server line of %s", file.name).startsWith("PostgreSQL 18");
        assertThat(file.headerValue("collation")).as("collation line of %s", file.name).isEqualTo("C");
        assertThat(file.headerValue("encoding")).as("encoding line of %s", file.name).isEqualTo("UTF8");
      }
  }
}
