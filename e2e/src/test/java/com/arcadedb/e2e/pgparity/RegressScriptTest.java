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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the regression harness (fork issue #119) splits a script into statements, as {@code psql} does: a mistake here changes
 * every count. No server.
 */
class RegressScriptTest {
  private static List<RegressPsql.Send> sends(final RegressPsql psql) {
    final List<RegressPsql.Send> sends = new ArrayList<>();
    for (RegressPsql.Step step = psql.next(); step != null; step = psql.next())
      if (step instanceof RegressPsql.Send send)
        sends.add(send);
    return sends;
  }

  private static List<String> statements(final String script) {
    return sends(new RegressPsql(script, Map.of())).stream().map(RegressPsql.Send::sql).toList();
  }

  @Test
  void aSemicolonEndsAStatementOnlyOutsideOfQuotesCommentsAndParentheses() {
    assertThat(statements("SELECT 'a;b', \"c;d\" FROM t;\nSELECT 2;")).containsExactly("SELECT 'a;b', \"c;d\" FROM t;",
        "SELECT 2;");
    assertThat(statements("SELECT 'it''s;', E'a\\';b', 'c\\';\nSELECT 2;")).containsExactly("SELECT 'it''s;', E'a\\';b', 'c\\';",
        "SELECT 2;");
    assertThat(statements("SELECT $$a;b$$, $x$ ; $$ ; $x$, a$b FROM t WHERE c = $1;")).hasSize(1);
    assertThat(statements("SELECT 1 -- a; comment\n + /* ; /* nested; */ ; */ 2;")).hasSize(1);
    assertThat(statements("CREATE RULE r AS ON INSERT TO t DO ALSO (INSERT INTO u VALUES (1); DELETE FROM u);")).hasSize(1);
  }

  @Test
  void withoutStandardStringsABackslashEscapesInAPlainStringOnly() {
    final RegressPsql psql = new RegressPsql("SELECT 'a\\';b';\nSELECT U&' \\' UESCAPE '!';\nSELECT 2;", Map.of());
    psql.standardStrings = false;
    assertThat(sends(psql)).extracting(RegressPsql.Send::sql).containsExactly("SELECT 'a\\';b';",
        "SELECT U&' \\' UESCAPE '!';", "SELECT 2;");
  }

  @Test
  void theBodyOfAFunctionKeepsItsSemicolons() {
    assertThat(statements("""
        CREATE OR REPLACE FUNCTION f(a int) RETURNS int LANGUAGE sql
        BEGIN ATOMIC
          SELECT CASE WHEN a > 0 THEN 1 ELSE 2 END;
          SELECT 2;
        END;
        BEGIN;
        SELECT 3;
        END;
        """)).hasSize(4);
  }

  @Test
  void commentsAndBlankLinesBeforeAStatementAreNotPartOfIt() {
    final List<RegressPsql.Send> sends = sends(new RegressPsql("-- one\n\n/* two\n*/\nSELECT 1;  -- three\n\nSELECT 2", Map.of()));
    assertThat(sends).extracting(RegressPsql.Send::sql).containsExactly("SELECT 1;", "SELECT 2");
    assertThat(sends).extracting(RegressPsql.Send::line).containsExactly(5, 7);
    assertThat(sends).extracting(RegressPsql.Send::key).containsExactly("5.1", "7.1");
  }

  @Test
  void variablesAreReplacedOutsideOfQuotes() {
    final RegressPsql psql = new RegressPsql("""
        \\getenv dir PG_ABS_SRCDIR
        \\set file :dir '/data/x.data'
        \\set name 'my table'
        COPY t FROM :'file';
        SELECT :"name", ':name', a::text, a[1:2], :unset, :{?name}, :{?unset};
        """, Map.of("PG_ABS_SRCDIR", "/regress"));
    assertThat(sends(psql)).extracting(RegressPsql.Send::sql).containsExactly("COPY t FROM '/regress/data/x.data';",
        "SELECT \"my table\", ':name', a::text, a[1:2], :unset, TRUE, FALSE;");
    assertThat(psql.commands).isEqualTo(3);
    assertThat(psql.sent).isEqualTo(2);
  }

  @Test
  void backslashCommandsAreCountedApartAndSomeSendAStatement() {
    final RegressPsql psql = new RegressPsql("""
        \\d person
        SELECT 1 \\g
        SELECT 2
        \\gx
        SELECT 3\\; SELECT 4;
        SELECT 5 \\r
        SELECT $1, $2 \\bind 'a b' c \\g
        SELECT 6 \\gdesc
        """, Map.of());
    final List<RegressPsql.Send> sends = sends(psql);
    assertThat(sends).extracting(RegressPsql.Send::sql).containsExactly("SELECT 1", "SELECT 2", "SELECT 3", "SELECT 4;",
        "SELECT $1, $2");
    assertThat(sends.get(4).parameters()).containsExactly("a b", "c");
    assertThat(psql.commands).isEqualTo(7);
  }

  @Test
  void aBranchThatIsNotTakenSendsNothingAndGsetFillsVariables() {
    final RegressPsql psql = new RegressPsql("""
        SELECT false AS skip \\gset
        \\if :skip
          SELECT 'no';
          \\quit
        \\else
          SELECT 'yes';
        \\endif
        SELECT :skip;
        \\quit
        SELECT 'after';
        """, Map.of());
    final RegressPsql.Send first = (RegressPsql.Send) psql.next();
    assertThat(first.gsetPrefix()).isEmpty();
    psql.answered(first, List.of("skip"), List.<String[]>of(new String[] { "f" }), null);
    assertThat(sends(psql)).extracting(RegressPsql.Send::sql).containsExactly("SELECT 'yes';", "SELECT f;");
    assertThat(psql.sent).isEqualTo(3);
    assertThat(psql.skipped).isEqualTo(2);
  }

  @Test
  void theDataOfACopyIsReadWhenAskedFor() {
    final RegressPsql psql = new RegressPsql("COPY t FROM stdin;\n1\ta;b\n2\t\\N\n\\.\nSELECT 1;\n", Map.of());
    assertThat(((RegressPsql.Send) psql.next()).sql()).isEqualTo("COPY t FROM stdin;");
    assertThat(psql.copyData()).isEqualTo("1\ta;b\n2\t\\N\n");
    assertThat(sends(psql)).extracting(RegressPsql.Send::sql).containsExactly("SELECT 1;");
  }

  @Test
  void aClientCopyIsNoStatement() {
    final RegressPsql psql = new RegressPsql("\\copy t (a, b) from stdin with (format csv)\n1,2\n\\.\n", Map.of());
    final RegressPsql.ClientCopy copy = (RegressPsql.ClientCopy) psql.next();
    assertThat(copy.sql()).isEqualTo("COPY t (a, b) FROM STDIN with (format csv)");
    assertThat(copy.data()).isEqualTo("1,2\n");
    assertThat(psql.next()).isNull();
    assertThat(psql.sent).isZero();
  }

  @Test
  void theListOfWhatIsOutOfScope() {
    final RegressOutOfScope outOfScope = RegressOutOfScope.load();
    final Set<String> catalog = Set.of("pg_class");
    final Set<String> functions = Set.of("binary_coercible");
    assertThat(outOfScope.match("EXPLAIN (COSTS OFF) SELECT 1;", catalog, functions).id()).isEqualTo("explain");
    assertThat(outOfScope.match("SELECT * FROM pg_class;", catalog, functions).id()).isEqualTo("catalog-relation");
    assertThat(outOfScope.match("SELECT pg_catalog.lower('A');", catalog, functions).id()).isEqualTo("catalog-qualified");
    assertThat(outOfScope.match("SELECT ctid FROM t;", catalog, functions).id()).isEqualTo("system-column");
    assertThat(outOfScope.match("SELECT 'ctid pg_class explain';", catalog, functions)).isNull();
    assertThat(outOfScope.match("SELECT binary_coercible(1, 2);", catalog, functions).id()).isEqualTo("c-function-call");
    assertThat(outOfScope.match("DROP TABLESPACE x;", catalog, functions).id()).isEqualTo("tablespace");
    final String create = "CREATE FUNCTION f(int) RETURNS int AS 'lib', 'f' LANGUAGE C STRICT;";
    assertThat(outOfScope.match(create, catalog, functions).id()).isEqualTo("c-function");
    assertThat(RegressOutOfScope.serverFunction(create)).isEqualTo("f");
    assertThat(outOfScope.match("CREATE FUNCTION g() RETURNS int LANGUAGE sql AS 'SELECT 1';", catalog, functions)).isNull();
    assertThat(outOfScope.entries()).extracting(RegressOutOfScope.Entry::reason).containsOnly(1, 2, 3);
  }

  private static RegressSubject.Answer answer(final Object... values) {
    final List<List<Object>> rows = new ArrayList<>();
    for (final Object value : values)
      rows.add(List.of(value));
    return new RegressSubject.Answer(List.of("a"), List.of("text"), rows, -1, null, null, false);
  }

  private static RegressJudge.Verdict verdict(final RegressSubject.Answer expected, final RegressSubject.Answer actual) {
    return RegressJudge.judge(expected, actual, null, null).verdict();
  }

  @Test
  void theVerdictsOfTheIssue() {
    final RegressSubject.Answer rows = answer("x", "y");
    final RegressSubject.Answer syntax = RegressSubject.Answer.error("42601", "syntax");
    final RegressSubject.Answer unsupported = RegressSubject.Answer.error("0A000", "unsupported");
    assertThat(verdict(rows, answer("y", "x"))).isEqualTo(RegressJudge.Verdict.PASSED);
    assertThat(verdict(syntax, RegressSubject.Answer.error("42601", "other words"))).isEqualTo(RegressJudge.Verdict.PASSED);
    assertThat(verdict(rows, unsupported)).isEqualTo(RegressJudge.Verdict.REFUSED);
    assertThat(verdict(rows, syntax)).isEqualTo(RegressJudge.Verdict.REFUSED);
    assertThat(verdict(syntax, unsupported)).isEqualTo(RegressJudge.Verdict.REFUSED);
    assertThat(verdict(rows, answer("x", "z"))).isEqualTo(RegressJudge.Verdict.WRONG);
    assertThat(verdict(rows, answer("x", "x"))).isEqualTo(RegressJudge.Verdict.WRONG);
    assertThat(verdict(syntax, rows)).isEqualTo(RegressJudge.Verdict.WRONG);
    assertThat(verdict(unsupported, syntax)).isEqualTo(RegressJudge.Verdict.WRONG);
    assertThat(verdict(rows, RegressSubject.Answer.crash(null, "NullPointerException"))).isEqualTo(RegressJudge.Verdict.WRONG);
    assertThat(RegressJudge.judge(rows, rows, "table t differs", null).verdict()).isEqualTo(RegressJudge.Verdict.WRONG);
  }

  @Test
  void rowsThatTieOnTheSortKeysMayComeInAnyOrder() {
    final List<String> labels = List.of("name", "age");
    final List<List<Object>> rows = List.of(List.of("ada", "36"), List.of("cy", "36"), List.of("bob", "41"));
    final List<List<Object>> keys = RegressOrder.of("SELECT name, age FROM p ORDER BY age;").keys(labels, rows, sql -> null);
    final RegressSubject.Answer expected = new RegressSubject.Answer(labels, List.of("text", "text"), rows, -1, null, null, false);
    final RegressSubject.Answer tied = new RegressSubject.Answer(labels, expected.types(),
        List.of(rows.get(1), rows.get(0), rows.get(2)), -1, null, null, false);
    final RegressSubject.Answer moved = new RegressSubject.Answer(labels, expected.types(),
        List.of(rows.get(2), rows.get(0), rows.get(1)), -1, null, null, false);
    assertThat(RegressJudge.answers(expected, tied, keys)).isNull();
    assertThat(RegressJudge.answers(expected, moved, keys)).contains("ORDER BY");
    assertThat(RegressJudge.answers(expected, moved, null)).isNull();
  }

  @Test
  void aSortKeyThatIsNoColumnOfTheAnswerIsAskedFor() {
    final List<String> asked = new ArrayList<>();
    final RegressOrder order = RegressOrder.of("SELECT name FROM p ORDER BY abs(age) DESC NULLS LAST, name LIMIT 2");
    order.keys(List.of("name"), List.of(List.of("ada")), sql -> {
      asked.add(sql);
      return null;
    });
    assertThat(asked).containsExactly(
        "SELECT name , abs(age) AS pgregress_key0 FROM p ORDER BY abs(age) DESC NULLS LAST, name LIMIT 2");
    assertThat(RegressOrder.of("SELECT array_agg(a ORDER BY b) FROM t")).isNull();
    assertThat(RegressOrder.of("INSERT INTO t SELECT * FROM u ORDER BY 1")).isNull();
  }
}
