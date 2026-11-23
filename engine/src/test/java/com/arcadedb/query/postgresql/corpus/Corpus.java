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
 *   <li>{@code syntax.tsv}: {@code id}, {@code pg18}, {@code subset}, {@code gap}, {@code statement}, {@code source},
 *   {@code detail}. The accept and reject differential of the grammar (fork issue #3): the real server only parses and describes
 *   each statement, and {@code pg18} is {@code ok}, {@code syntax-error} or {@code feature-not-supported} with the SQLSTATE of
 *   the server in {@code detail} (an {@code ok} with {@code 42P01} parsed and was refused by a later stage).</li>
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
  /** The TPC-H statements of fork issue #13: the columns of {@code answers.tsv}, the data of {@code TpchFixture}. */
  public static final String TPCH_FILE          = "tpch.tsv";
  /** The statements of the {@code id} rule of fork issue #38: the columns of {@code answers.tsv}, the data of {@code IdRuleFixture}. */
  public static final String IDRULE_FILE        = "idrule.tsv";
  /** The statements that language models wrote (fork issue #7): the columns of {@code syntax.tsv}, then model, group and duckdb. */
  public static final String AGENT_SQL_FILE     = "agent-sql.tsv";
  /** The schema the server needs to bind the names of {@link #AGENT_SQL_FILE}; the harness loads it into a database of its own. */
  public static final String AGENT_SQL_SCHEMA   = "agent-sql-schema.sql";

  /** The value of {@code pg18} and of {@code expected} before the generator has asked a server: add a case with it. */
  public static final String PENDING_SERVER = "pending-server";

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

  public static CorpusFile readIdRule() throws IOException {
    return CorpusFile.readResource(RESOURCE_DIRECTORY + IDRULE_FILE);
  }

  public static CorpusFile readTpch() throws IOException {
    return CorpusFile.readResource(RESOURCE_DIRECTORY + TPCH_FILE);
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
   * A statement of the accept and reject differential. {@code subset} is {@code inside} when the statement lies inside the
   * subset of the grammar, {@code outside} when it does not and {@code unknown} when only the server's answer is checked. With
   * {@code pg18} read from the server the engine must: parse a statement that is {@code inside} and that the server did not
   * reject as a syntax error; refuse a statement that is {@code outside} with SQLSTATE {@code 0A000}; refuse a statement the
   * server rejected as {@code syntax-error} with {@code 42601}, and one it called {@code feature-not-supported} with
   * {@code 0A000}. A {@code gap} other than {@code -} is a known disagreement of the parser.
   */
  public static final class SyntaxCase {
    public static final int ID = 0, PG18 = 1, SUBSET = 2, GAP = 3, STATEMENT = 4, SOURCE = 5, DETAIL = 6;

    public final String[] row;

    public SyntaxCase(final String[] row) {
      this.row = row;
    }

    public String id() {
      return row[ID];
    }

    public String pg18() {
      return row[PG18];
    }

    public String subset() {
      return row[SUBSET];
    }

    public boolean inside() {
      return "inside".equals(row[SUBSET]);
    }

    public boolean outside() {
      return "outside".equals(row[SUBSET]);
    }

    /** @return {@code -} when the parser agrees with the entry, else the reason it does not */
    public String gap() {
      return row[GAP];
    }

    public boolean isGap() {
      return !"-".equals(row[GAP]);
    }

    public String statement() {
      return CorpusFile.unescape(row[STATEMENT]);
    }

    public String source() {
      return row[SOURCE];
    }

    /** The SQLSTATE the server answered, {@code -} when it accepted the statement without an error. */
    public String detail() {
      return row[DETAIL];
    }

    /** Whether a server has been asked: {@code pg18} is anything but {@code pending-server}. */
    public boolean hasOutcome() {
      return !PENDING_SERVER.equals(row[PG18]);
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

    /** {@code pending-server} (or empty) when the generator has not run on this case yet. */
    public String expected() {
      return row[EXPECTED];
    }

    /** Whether a server has been asked. */
    public boolean hasOutcome() {
      return !row[EXPECTED].isEmpty() && !PENDING_SERVER.equals(row[EXPECTED]);
    }

    /** Whether the server must refuse the statement (an {@code err-} case). */
    public boolean expectsRefusal() {
      return row[ID].startsWith(ERROR_PREFIX);
    }
  }
}
