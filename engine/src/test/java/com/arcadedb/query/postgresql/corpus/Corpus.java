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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The two corpus files of the PostgreSQL-style SQL prototype, in {@code engine/src/test/resources/postgresql/corpus/}, and the
 * views of their rows. Their columns are written at the top of each file.
 * <ul>
 *   <li>{@code syntax.tsv}: {@code id}, {@code scope}, {@code statement}, {@code expected}. The accept and reject differential
 *   of the grammar (fork issue #3): the real server only parses each statement, and {@code expected} is {@code ok},
 *   {@code syntax-error 42601} or {@code error <SQLSTATE>} (the statement parsed and a later stage refused it).</li>
 *   <li>{@code answers.tsv}: {@code id}, {@code order}, {@code tol}, {@code status}, {@code statement}, {@code expected}. The
 *   answer parity of fork issue #2: the real server runs each statement on the {@code small} fixture, and {@code expected} is
 *   the JSON of its {@link Outcome}.</li>
 * </ul>
 * A test of the engine tree reads them with {@link #readSyntax()} and {@link #readAnswers()} (no Docker needed); the harness of
 * the {@code e2e} module reads and rewrites them by path.
 */
public final class Corpus {
  public static final String RESOURCE_DIRECTORY = "/postgresql/corpus/";
  public static final String SYNTAX_FILE        = "syntax.tsv";
  public static final String ANSWERS_FILE       = "answers.tsv";

  /** The prefix of the id of an answer case that the server must refuse: its {@code expected} is a SQLSTATE. */
  public static final String ERROR_PREFIX = "err-";

  private Corpus() {
  }

  public static CorpusFile readSyntax() throws IOException {
    return CorpusFile.readResource(RESOURCE_DIRECTORY + SYNTAX_FILE);
  }

  public static CorpusFile readAnswers() throws IOException {
    return CorpusFile.readResource(RESOURCE_DIRECTORY + ANSWERS_FILE);
  }

  public static List<SyntaxCase> syntaxCases(final CorpusFile file) {
    final List<SyntaxCase> cases = new ArrayList<>();
    for (final String[] row : file.rows)
      cases.add(new SyntaxCase(row));
    return cases;
  }

  public static List<AnswerCase> answerCases(final CorpusFile file) {
    final List<AnswerCase> cases = new ArrayList<>();
    for (final String[] row : file.rows)
      cases.add(new AnswerCase(row));
    return cases;
  }

  /**
   * A statement of the accept and reject differential. {@code scope} is {@code in} when the statement lies inside the subset
   * of the grammar and {@code out} when it does not. With the server's outcome the engine must: parse a statement the server
   * parsed ({@code ok} or {@code error ...}) and that is {@code in}; refuse a statement the server parsed and that is
   * {@code out} with SQLSTATE {@code 0A000}; refuse a statement the server rejected as {@code syntax-error 42601} with
   * {@code 42601}, whatever its scope.
   */
  public static final class SyntaxCase {
    public static final int ID = 0, SCOPE = 1, STATEMENT = 2, EXPECTED = 3;

    public final String[] row;

    public SyntaxCase(final String[] row) {
      this.row = row;
    }

    public String id() {
      return row[ID];
    }

    public boolean inside() {
      return "in".equals(row[SCOPE]);
    }

    public String statement() {
      return CorpusFile.unescape(row[STATEMENT]);
    }

    /** {@code ""} when the generator has not run on this case yet. */
    public String expected() {
      return row[EXPECTED];
    }

    public boolean syntaxError() {
      return expected().startsWith("syntax-error");
    }

    /** Whether the server parsed the statement: it accepted it or refused it at a later stage than the parser. */
    public boolean parsed() {
      return "ok".equals(expected()) || expected().startsWith("error ");
    }
  }

  /**
   * A statement of the answer parity. {@code order} is {@code ordered} when the statement has a total {@code ORDER BY}
   * and {@code multiset} otherwise; {@code tol} is {@code -} for the default floating point tolerance or a number;
   * {@code status} is {@code ok} when the engine is expected to answer like the server and {@code pending} when it does not
   * support the statement yet (the harness reports it as pending; a pending case that answers like the server fails the run,
   * so the flag has to be flipped).
   */
  public static final class AnswerCase {
    public static final int ID = 0, ORDER = 1, TOL = 2, STATUS = 3, STATEMENT = 4, EXPECTED = 5;

    public final String[] row;

    public AnswerCase(final String[] row) {
      this.row = row;
    }

    public String id() {
      return row[ID];
    }

    public boolean ordered() {
      return "ordered".equals(row[ORDER]);
    }

    public double tolerance() {
      return "-".equals(row[TOL]) ? Values.DEFAULT_TOLERANCE : Double.parseDouble(row[TOL]);
    }

    public boolean pending() {
      return "pending".equals(row[STATUS]);
    }

    public String statement() {
      return CorpusFile.unescape(row[STATEMENT]);
    }

    /** {@code ""} when the generator has not run on this case yet. */
    public String expected() {
      return row[EXPECTED];
    }

    /** Whether the server must refuse the statement (an {@code err-} case). */
    public boolean expectsRefusal() {
      return row[ID].startsWith(ERROR_PREFIX);
    }
  }
}
