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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs one regression file and gives each of its statements one verdict (fork issue #119). The file runs twice on PostgreSQL,
 * on two servers and on a new database each time: the first run records what PostgreSQL answers, the second is the reference
 * that the side under test is compared with, statement by statement. A statement that PostgreSQL answers differently in the two
 * runs is out of scope.
 */
final class RegressRun {
  private static final Set<String> TRANSACTION = Set.of("begin", "start", "commit", "end", "rollback", "abort", "savepoint",
      "release");

  /**
   * The verdict of one statement.
   *
   * @param serverState  the SQLSTATE of PostgreSQL, {@code 00000} when it succeeded
   * @param subjectState the SQLSTATE of the side under test, {@code -} when the statement was not run there or ended without one
   * @param entry        the entry of the out-of-scope list, else empty
   * @param onCopy       judged, and it uses a table that the harness copied
   */
  record Line(String file, int line, RegressJudge.Verdict verdict, String serverState, String subjectState, String reason,
      String entry, boolean onCopy, String statement) {
  }

  /** What a run of one file gave. */
  static final class FileResult {
    final String     file;
    final List<Line> lines = new ArrayList<>();
    /** The statements that reached the server in the judged run, counted apart from the verdicts. */
    int    statements;
    int    commands;
    int    skipped;
    long   serverErrors;
    long   expectedErrors = -1;
    /** Why the run of the file did not finish, {@code null} when it did. */
    String failure;
    /** Whether the open transaction block of the file holds a write that was not judged. */
    private boolean blockedWrite;

    FileResult(final String file) {
      this.file = file;
    }

    long count(final RegressJudge.Verdict verdict) {
      return lines.stream().filter(line -> line.verdict() == verdict).count();
    }

    /** @throws IllegalStateException unless the verdicts add up to the statements of the file */
    void check() {
      long sum = 0;
      for (final RegressJudge.Verdict verdict : RegressJudge.Verdict.values())
        sum += count(verdict);
      if (sum != statements || lines.size() != statements)
        throw new IllegalStateException(file + ": " + sum + " verdicts for " + statements + " statements");
    }
  }

  /** The statement of the first run of PostgreSQL, what it answered, and what its tables held afterwards where they changed. */
  private record Recorded(String sql, RegressSubject.Answer answer, Map<String, String> changed) {
  }

  /** What to do with the steps of a script. */
  private interface Handler {
    RegressDatabase.Executed statement(RegressPsql.Send send, Supplier<String> copyData);

    void connect(String user);

    void load(String sql, String data);
  }

  private final Path                    regress;
  private final RegressServer           first;
  private final RegressServer           second;
  private final Supplier<RegressSubject> subjects;
  private final RegressOutOfScope       outOfScope = RegressOutOfScope.load();
  private final Map<String, List<String>> setups   = new HashMap<>();

  /**
   * @param regress  a directory with the layout of {@code src/test/regress}: {@code sql/}, and where there are,
   *                 {@code expected/} and {@code data/}
   * @param first    the server of the first run
   * @param second   the server of the judged run
   * @param subjects makes the side under test, one for each file
   */
  RegressRun(final Path regress, final RegressServer first, final RegressServer second,
      final Supplier<RegressSubject> subjects) {
    this.regress = regress;
    this.first = first;
    this.second = second;
    this.subjects = subjects;
    for (final String line : RegressOutOfScope.resource("/pgregress/setup.tsv").split("\n")) {
      if (line.isBlank() || line.startsWith("#"))
        continue;
      final String[] cells = line.split("\t");
      setups.put(cells[0], cells[1].equals("-") ? List.of() : List.of(cells[1].split(",")));
    }
  }

  /** The files that run before a file, to give it the tables and objects it expects; they are not judged. */
  List<String> setup(final String file) {
    return setups.getOrDefault(file, setups.get("*"));
  }

  FileResult run(final String file) {
    final FileResult result = new FileResult(file);
    try {
      final Map<String, Recorded> recorded = firstRun(file);
      judge(file, recorded, result);
      result.expectedErrors = expectedErrors(file);
    } catch (final RuntimeException e) {
      e.printStackTrace();
      result.failure = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    return result;
  }

  private Map<String, Recorded> firstRun(final String file) {
    final Map<String, Recorded> recorded = new HashMap<>();
    try (final RegressDatabase database = first.freshDatabase()) {
      for (final String before : setup(file))
        drive(psql(before), database, plain(database, null));
      database.changes();
      drive(psql(file), database, new Handler() {
        @Override
        public RegressDatabase.Executed statement(final RegressPsql.Send send, final Supplier<String> copyData) {
          final RegressDatabase.Executed executed = database.execute(send.sql(), send.parameters(), copyData);
          recorded.put(send.key(),
              new Recorded(send.sql(), executed.answer(), contents(database, database.changesAfter(send.sql()))));
          return executed;
        }

        @Override
        public void connect(final String user) {
          database.connect(user);
        }

        @Override
        public void load(final String sql, final String data) {
          database.execute(sql, null, () -> data);
        }
      });
    }
    return recorded;
  }

  /** Runs a script on a database without judging it, and on the side under test too when there is one. */
  private static Handler plain(final RegressDatabase database, final RegressSubject subject) {
    return new Handler() {
      @Override
      public RegressDatabase.Executed statement(final RegressPsql.Send send, final Supplier<String> copyData) {
        final String[] data = new String[1];
        final RegressDatabase.Executed executed = database.execute(send.sql(), send.parameters(), () -> {
          data[0] = copyData.get();
          return data[0];
        });
        if (subject != null)
          subject.setup(send, () -> data[0] == null ? "" : data[0]);
        return executed;
      }

      @Override
      public void connect(final String user) {
        database.connect(user);
        if (subject != null)
          subject.connect(user);
      }

      @Override
      public void load(final String sql, final String data) {
        database.execute(sql, null, () -> data);
        if (subject != null)
          subject.load(sql, data);
      }
    };
  }

  private void judge(final String file, final Map<String, Recorded> recorded, final FileResult result) {
    final Set<String> catalogRelations = second.catalogRelations();
    final Set<String> serverFunctions = new HashSet<>();
    try (final RegressDatabase database = second.freshDatabase(); final RegressSubject subject = subjects.get()) {
      for (final String before : setup(file)) {
        final Handler handler = plain(database, subject);
        drive(psql(before), database, new Handler() {
          @Override
          public RegressDatabase.Executed statement(final RegressPsql.Send send, final Supplier<String> copyData) {
            final String function = RegressOutOfScope.serverFunction(send.sql());
            if (function != null)
              serverFunctions.add(function);
            return handler.statement(send, copyData);
          }

          @Override
          public void connect(final String user) {
            handler.connect(user);
          }

          @Override
          public void load(final String sql, final String data) {
            handler.load(sql, data);
          }
        });
      }
      database.changes();
      subject.sync(database, new LinkedHashSet<>(database.tables().keySet()));

      final RegressPsql psql = psql(file);
      drive(psql, database, new Handler() {
        @Override
        public RegressDatabase.Executed statement(final RegressPsql.Send send, final Supplier<String> copyData) {
          return judgeStatement(file, send, copyData, database, subject, recorded, catalogRelations, serverFunctions, result);
        }

        @Override
        public void connect(final String user) {
          database.connect(user);
          subject.connect(user);
          subject.sync(database, database.changes());
        }

        @Override
        public void load(final String sql, final String data) {
          database.execute(sql, null, () -> data);
          subject.load(sql, data);
          subject.sync(database, database.changes());
        }
      });
      result.statements = psql.sent;
      result.commands = psql.commands;
      result.skipped = psql.skipped;
    }
  }

  private RegressDatabase.Executed judgeStatement(final String file, final RegressPsql.Send send,
      final Supplier<String> copyData, final RegressDatabase database, final RegressSubject subject,
      final Map<String, Recorded> recorded, final Set<String> catalogRelations, final Set<String> serverFunctions,
      final FileResult result) {
    final String sql = send.sql();
    final List<RegressSql.Token> tokens = RegressSql.tokens(sql);
    final Map<String, RegressDatabase.Table> before = database.tables();
    final Map<String, RegressDatabase.OtherRelation> others = database.others();
    RegressOutOfScope.Entry entry = outOfScope.match(sql, catalogRelations, serverFunctions);
    final String word = RegressSql.firstWord(tokens);

    // the relations the statement reads and writes: as the plan of PostgreSQL names them, and as its words do
    final Set<String> uses = new LinkedHashSet<>();
    final Set<String> writes = new LinkedHashSet<>();
    if (entry == null) {
      final RegressDatabase.Relations relations = database.relations(sql);
      // a query of the harness between BEGIN and SET TRANSACTION would make the server refuse the latter
      final boolean mayRead = !RegressDatabase.changesNothing(word);
      if (relations != null) {
        relations.all().stream().filter(before::containsKey).forEach(uses::add);
        relations.written().stream().filter(before::containsKey).forEach(writes::add);
        // the planner leaves out a table that it does not have to read, and names the tables of a view, not the view
        uses.addAll(database.resolve(RegressSql.tablePositions(tokens), mayRead));
      } else {
        uses.addAll(database.resolve(
            RegressSql.createsTable(tokens) ? RegressSql.sourcesOfCreateTable(tokens) : RegressSql.names(tokens), mayRead));
      }
      // from the words too: a rule can rewrite a write, so that the plan no longer names its target
      writes.addAll(database.resolve(RegressSql.writeTargets(tokens), mayRead));
    }
    String blocked = null;
    for (final String key : uses)
      if (blocked == null && why(subject, before, others, key, false) != null)
        blocked = "uses " + key + ": " + why(subject, before, others, key, false);
    for (final String key : writes)
      if (blocked == null && why(subject, before, others, key, true) != null)
        blocked = "writes " + key + ": " + why(subject, before, others, key, true);
    // what a blocked write would have changed is checked at the end of its transaction (fork issue #179)
    if (entry == null && blocked == null && result.blockedWrite && (word.equals("commit") || word.equals("end"))
        && database.transactionState() == 'T')
      blocked = "ends a transaction that holds a blocked write";
    final boolean onCopy = uses.stream().anyMatch(subject::copied) || writes.stream().anyMatch(subject::copied);

    final String[] data = new String[1];
    final RegressDatabase.Executed executed = database.execute(sql, send.parameters(), () -> {
      data[0] = copyData.get();
      return data[0];
    });
    final Supplier<String> sameData = () -> data[0] == null ? "" : data[0];
    final Set<String> changed = database.changesAfter(sql);
    final RegressSubject.Answer expected = executed.answer();
    if (expected.failed())
      result.serverErrors++;
    // whether the server could define it or not: the library of the suite's C code is not in the image
    final String function = RegressOutOfScope.serverFunction(sql);
    if (function != null)
      serverFunctions.add(function);
    final RegressOrder order = expected.columns() == null ? null : RegressOrder.of(sql);
    final List<List<Object>> keys = order == null ? null : order.keys(expected.columns(), expected.rows(), database::peek);

    if (entry == null && differs(recorded.get(send.key()), sql, expected, keys, contents(database, changed)))
      entry = outOfScope.entry(RegressOutOfScope.TWO_RUNS);
    // not after a write: the query would run on other contents the second time
    if (entry == null && changed.isEmpty() && numbersTiedRows(database, sql, tokens, expected))
      entry = outOfScope.entry(RegressOutOfScope.TIED_ROWS);
    if (entry == null && blocked == null)
      blocked = misplaced(database, subject, tokens, before, changed);
    if (entry == null && blocked == null && RegressSql.queriesInformationSchema(tokens)) {
      final String name = about(tokens, expected, subject.differing(database));
      if (name != null)
        blocked = "a query of information_schema about an object that differs between the sides: " + name;
    }

    final String serverState = expected.failed() ? expected.sqlState() : "00000";
    if (entry != null) {
      subject.setup(send, sameData);
      result.lines.add(new Line(file, send.line(), RegressJudge.Verdict.OUT_OF_SCOPE, serverState, "-", entry.why(), entry.id(),
          false, sql));
    } else if (blocked != null) {
      subject.setup(send, sameData);
      result.lines.add(
          new Line(file, send.line(), RegressJudge.Verdict.BLOCKED, serverState, "-", blocked, "", false, sql));
    } else {
      final RegressSubject.Answer actual = subject.execute(send, sameData);
      final boolean transaction = TRANSACTION.contains(word);
      // a statement that turns out to write a table which cannot be held is not judged either
      String late = null;
      if (!transaction)
        for (final String table : changed)
          if (late == null && before.containsKey(table) && subject.blocked(table, true) != null)
            late = "writes " + table + ": " + subject.blocked(table, true);
      if (late != null)
        result.lines.add(new Line(file, send.line(), RegressJudge.Verdict.BLOCKED, serverState, "-", late, "", false, sql));
      else {
        String difference = null;
        if (!expected.failed() && !actual.failed()) {
          final Set<String> touched = new LinkedHashSet<>(changed);
          touched.addAll(subject.touched());
          for (final String table : touched) {
            // a rollback cannot take back what the harness itself made
            if (difference != null || subject.blocked(table, false) != null
                || transaction && subject.rebuiltByHarness().contains(table))
              continue;
            difference = difference(table, database.tables().get(table), before.get(table), database, subject);
          }
        }
        final RegressJudge.Judgement judgement = RegressJudge.judge(expected, actual, difference, keys);
        final String state = actual.sqlState() != null ? actual.sqlState() : actual.crashed() ? "-" : "00000";
        result.lines.add(new Line(file, send.line(), judgement.verdict(), serverState, state, judgement.reason(), "", onCopy, sql));
      }
    }
    subject.sync(database, changed);
    if (result.lines.get(result.lines.size() - 1).verdict() == RegressJudge.Verdict.BLOCKED
        && (!writes.isEmpty() || !changed.isEmpty()))
      result.blockedWrite = true;
    // the transaction block has ended, or the statement was in none
    if (database.transactionState() == 'I')
      result.blockedWrite = false;
    return executed;
  }

  /** @return why a statement cannot read (or write) the relation of the reference that {@code key} names, else {@code null} */
  private static String why(final RegressSubject subject, final Map<String, RegressDatabase.Table> tables,
      final Map<String, RegressDatabase.OtherRelation> others, final String key, final boolean write) {
    if (tables.containsKey(key))
      return subject.blocked(key, write);
    final RegressDatabase.OtherRelation relation = others.get(key);
    return relation == null ? null : subject.blocked(relation);
  }

  /** Whether the query numbers rows that tie in the order of a window: PostgreSQL answers other rows with {@code rank()}. */
  private static boolean numbersTiedRows(final RegressDatabase database, final String sql, final List<RegressSql.Token> tokens,
      final RegressSubject.Answer expected) {
    if (expected.failed() || expected.columns() == null)
      return false;
    final String ranked = RegressSql.rankForRowNumber(sql, tokens);
    if (ranked == null)
      return false;
    final RegressSubject.Answer other = database.peek(ranked);
    return other != null && RegressJudge.rows(expected.rows(), other.rows()) != null;
  }

  /**
   * @return why the statement is not judged when it made a table outside of {@code public} without naming the schema, as after a
   *         {@code SET search_path} that the other side refused (fork issue #181), else {@code null}
   */
  private static String misplaced(final RegressDatabase database, final RegressSubject subject,
      final List<RegressSql.Token> tokens, final Map<String, RegressDatabase.Table> before, final Set<String> changed) {
    for (final String key : changed) {
      final RegressDatabase.Table table = database.tables().get(key);
      if (table == null || before.containsKey(key) || table.schema().equals("public") || table.schema().startsWith("pg_temp")
          || RegressSql.writesWithSchema(tokens, table.schema(), table.name())
          || !table.schema().equals(database.currentSchema()))
        continue;
      final String why = subject.misplaced(table);
      if (why != null)
        return "writes " + key + ": " + why;
    }
    return null;
  }

  private static final Set<String> NAMES_AN_OBJECT = Set.of("table_name", "table_schema", "view_name", "view_schema",
      "schema_name", "sequence_name", "sequence_schema", "foreign_table_name", "foreign_table_schema");

  /**
   * @return the object of {@code differing} that a query of {@code information_schema} is about: one that it writes as a string,
   *         or one that the answer of PostgreSQL names; {@code null} when there is none (fork issues #183 and #184)
   */
  private static String about(final List<RegressSql.Token> tokens, final RegressSubject.Answer expected,
      final Set<String> differing) {
    for (final RegressSql.Token token : tokens) {
      if (token.type() != RegressSql.Type.STRING || !token.text().startsWith("'") || token.text().length() < 2)
        continue;
      final String text = token.text().substring(1, token.text().length() - 1);
      if (differing.contains(text))
        return text;
    }
    if (expected.columns() != null)
      for (int i = 0; i < expected.columns().size(); i++)
        if (NAMES_AN_OBJECT.contains(expected.columns().get(i)))
          for (final List<Object> row : expected.rows())
            if (row.get(i) != null && differing.contains(String.valueOf(row.get(i))))
              return String.valueOf(row.get(i));
    return null;
  }

  /** How a table of the side under test differs from the reference after a statement, {@code null} when it does not. */
  private static String difference(final String key, final RegressDatabase.Table now, final RegressDatabase.Table before,
      final RegressDatabase database, final RegressSubject subject) {
    if (now == null) {
      if (before == null)
        return "it has a table " + key + " that PostgreSQL has not";
      return subject.shape(before) == null ? null : "it still has the table " + key;
    }
    final RegressSubject.Shape shape = subject.shape(now);
    if (shape == null)
      return "it has no table " + key;
    final RegressSubject.Shape expected = database.shape(now);
    if (!expected.equals(shape))
      return "table " + key + " differs in information_schema: PostgreSQL " + expected + ", got " + shape;
    final List<List<Object>> held = database.readableRows(now);
    if (held == null)
      return null; // PostgreSQL itself cannot read the table: there are no contents to compare with
    final String rows = RegressJudge.rows(held, subject.rows(now));
    return rows == null ? null : "table " + key + " differs: " + rows;
  }

  private static boolean differs(final Recorded recorded, final String sql, final RegressSubject.Answer second,
      final List<List<Object>> keys, final Map<String, String> changed) {
    // psql can put an answer of the server into a statement, an object id for one: then the text differs too
    if (recorded == null || !recorded.sql().equals(sql) || recorded.answer().failed() != second.failed())
      return true;
    if (second.failed())
      return !second.sqlState().equals(recorded.answer().sqlState());
    return RegressJudge.answers(second, recorded.answer(), keys) != null || !recorded.changed().equals(changed);
  }

  /** For each changed table what it holds now. */
  private static Map<String, String> contents(final RegressDatabase database, final Set<String> changed) {
    final Map<String, String> contents = new LinkedHashMap<>();
    for (final String key : changed) {
      final RegressDatabase.Table table = database.tables().get(key);
      contents.put(key, table == null ? "dropped" : table.fingerprint());
    }
    return contents;
  }

  private void drive(final RegressPsql psql, final RegressDatabase database, final Handler handler) {
    psql.standardStrings = database.standardStrings();
    for (RegressPsql.Step step = psql.next(); step != null; step = psql.next()) {
      if (step instanceof RegressPsql.Send send) {
        final String[] data = new String[1];
        final RegressDatabase.Executed executed = handler.statement(send, () -> {
          if (data[0] == null)
            data[0] = psql.copyData();
          return data[0];
        });
        final RegressSubject.Answer answer = executed.answer();
        psql.answered(send, answer.columns(), executed.text(), answer.sqlState());
        if (send.gexec() && executed.text() != null) {
          int number = 0;
          for (final String[] row : executed.text())
            for (final String cell : row)
              if (cell != null)
                handler.statement(psql.generated(send, cell, ++number), () -> "");
        }
      } else if (step instanceof RegressPsql.Connect connect)
        handler.connect(connect.user());
      else if (step instanceof RegressPsql.ClientCopy copy) {
        final String data = copy.data() != null ? copy.data() : clientFile(copy.file());
        if (data != null)
          handler.load(copy.sql(), data);
      }
      psql.standardStrings = database.standardStrings();
    }
  }

  /** A data file that {@code \copy} names, as the client reads it; {@code null} when the harness has none. */
  private String clientFile(final String file) {
    final String prefix = RegressServer.SOURCE_DIRECTORY + "/";
    if (!file.startsWith(prefix))
      return null;
    final Path path = regress.resolve(file.substring(prefix.length())).normalize();
    return path.startsWith(regress) && Files.isRegularFile(path) ? read(path) : null;
  }

  private RegressPsql psql(final String file) {
    return new RegressPsql(read(regress.resolve("sql").resolve(file + ".sql")),
        Map.of("PG_ABS_SRCDIR", RegressServer.SOURCE_DIRECTORY, "PG_ABS_BUILDDIR", RegressServer.BUILD_DIRECTORY, "PG_LIBDIR",
            "/usr/lib/postgresql/18/lib", "PG_DLSUFFIX", ".so"));
  }

  /** The lines of the expected output of the suite that report an error: a check of the setup, not of a verdict. */
  private long expectedErrors(final String file) {
    final Path expected = regress.resolve("expected").resolve(file + ".out");
    if (!Files.isRegularFile(expected))
      return -1;
    return read(expected).lines().filter(line -> line.startsWith("ERROR:  ")).count();
  }

  private static String read(final Path path) {
    try {
      // some files test other encodings: a byte that is no UTF-8 is replaced, not refused
      return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
