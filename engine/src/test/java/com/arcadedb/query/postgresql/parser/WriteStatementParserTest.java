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
import com.arcadedb.query.postgresql.PostgreSQLSyntaxException;
import com.arcadedb.query.postgresql.ast.AstPrinter;
import com.arcadedb.query.postgresql.ast.DeleteStatement;
import com.arcadedb.query.postgresql.ast.ExplainWriteStatement;
import com.arcadedb.query.postgresql.ast.InsertStatement;
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.ast.TransactionStatement;
import com.arcadedb.query.postgresql.ast.UpdateStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parser of the writes of the {@code postgresql} language (stage 1 of fork issue #29): {@code INSERT}, {@code UPDATE},
 * {@code DELETE} and the transaction statements parse to the tree they should, and what the first version leaves out is refused
 * with SQLSTATE 0A000 and its position. The statements are also in {@code syntax.tsv}, with what PostgreSQL 18 says of them.
 */
class WriteStatementParserTest {

  private static String print(final String text) {
    return AstPrinter.print(StatementParser.parse(text));
  }

  /** The column of the first occurrence of {@code word} in a one-line text, counted from 1, as the refusals count it. */
  private static int column(final String text, final String word) {
    final int index = text.indexOf(word);
    assertThat(index).as(word + " in " + text).isGreaterThanOrEqualTo(0);
    return index + 1;
  }

  // ------------------------------------------------------------------------------------------------ INSERT

  @Test
  void insertWithColumnsRowsDefaultAndReturning() {
    final Statement statement = StatementParser.parse("INSERT INTO person (pid, name) VALUES (1, 'Ada'), (2, DEFAULT) RETURNING pid");
    assertThat(statement).isInstanceOf(InsertStatement.class);
    final InsertStatement insert = (InsertStatement) statement;
    assertThat(insert.columns()).hasSize(2);
    assertThat(insert.table().only()).isFalse();
    assertThat(insert.source()).isInstanceOf(InsertStatement.Values.class);
    final InsertStatement.Values values = (InsertStatement.Values) insert.source();
    assertThat(values.rows()).hasSize(2);
    assertThat(values.rows().get(0).get(0).isDefault()).isFalse();
    assertThat(values.rows().get(1).get(1).isDefault()).isTrue();
    assertThat(insert.returning()).hasSize(1);
    assertThat(AstPrinter.print(statement)).isEqualTo(
        "INSERT INTO person (pid, name) VALUES (1, 'Ada'), (2, DEFAULT) RETURNING pid");
  }

  @Test
  void onConflictParsesToATreeThatPrintsAsItWasWritten() {
    assertThat(print("INSERT INTO t VALUES (1) ON CONFLICT DO NOTHING")).isEqualTo("INSERT INTO t VALUES (1) ON CONFLICT DO NOTHING");
    assertThat(print("insert into t (a, b) values (1, 2) on conflict (a, b) do nothing returning a")).isEqualTo(
        "INSERT INTO t (a, b) VALUES (1, 2) ON CONFLICT (a, b) DO NOTHING RETURNING a");

    final Statement statement = StatementParser.parse(
        "INSERT INTO t AS x (a) VALUES (1) ON CONFLICT (a) DO UPDATE SET b = excluded.b, c = 2 WHERE (x.c < 3) RETURNING a");
    final InsertStatement.OnConflict conflict = ((InsertStatement) statement).onConflict();
    assertThat(conflict).isNotNull();
    assertThat(conflict.target()).hasSize(1);
    assertThat(conflict.doNothing()).isFalse();
    assertThat(conflict.assignments()).hasSize(2);
    assertThat(conflict.where()).isNotNull();
    assertThat(AstPrinter.print(statement)).isEqualTo(
        "INSERT INTO t AS x (a) VALUES (1) ON CONFLICT (a) DO UPDATE SET b = excluded.b, c = 2 WHERE (x.c < 3) RETURNING a");
    assertThat(((InsertStatement) StatementParser.parse("INSERT INTO t VALUES (1)")).onConflict()).isNull();
  }

  @Test
  void doUpdateWithoutATargetIsASyntaxErrorAsInPostgreSQL() {
    assertThatThrownBy(() -> StatementParser.parse("INSERT INTO t VALUES (1) ON CONFLICT DO UPDATE SET a = 1"))
        .isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("ON CONFLICT DO UPDATE requires inference specification or constraint name");
  }

  @Test
  void insertForms() {
    assertThat(print("insert into t values (1)")).isEqualTo("INSERT INTO t VALUES (1)");
    assertThat(print("INSERT INTO public.t AS x (a) VALUES ($1) RETURNING *")).isEqualTo(
        "INSERT INTO public.t AS x (a) VALUES ($1) RETURNING *");
    assertThat(print("INSERT INTO t DEFAULT VALUES")).isEqualTo("INSERT INTO t DEFAULT VALUES");
    assertThat(print("INSERT INTO t DEFAULT VALUES RETURNING a, b AS c")).isEqualTo(
        "INSERT INTO t DEFAULT VALUES RETURNING a, b AS c");
    assertThat(print("INSERT INTO t SELECT a FROM u")).isEqualTo("INSERT INTO t SELECT a FROM u");
    assertThat(print("INSERT INTO t (a, b) SELECT a, b FROM u WHERE a > 1 ORDER BY a LIMIT 3;")).isEqualTo(
        "INSERT INTO t (a, b) SELECT a, b FROM u WHERE (a > 1) ORDER BY a LIMIT 3");
    assertThat(print("INSERT INTO \"Person\" (\"Name\") VALUES (-1 + 2)")).isEqualTo(
        "INSERT INTO \"Person\" (\"Name\") VALUES (((-1) + 2))");
  }

  @Test
  void anInsertIsOneNodeWithItsPosition() {
    final InsertStatement insert = (InsertStatement) StatementParser.parse("\n  INSERT INTO t VALUES (1)");
    assertThat(insert.position().line()).isEqualTo(2);
    assertThat(insert.position().column()).isEqualTo(3);
  }

  // ------------------------------------------------------------------------------------------------ UPDATE and DELETE

  @Test
  void updateAssignmentsWhereAndReturning() {
    final Statement statement = StatementParser.parse("UPDATE person SET age = age + 1, name = DEFAULT WHERE pid = 1 RETURNING *");
    assertThat(statement).isInstanceOf(UpdateStatement.class);
    final UpdateStatement update = (UpdateStatement) statement;
    assertThat(update.assignments()).hasSize(2);
    assertThat(update.assignments().get(0).column().value()).isEqualTo("age");
    assertThat(update.assignments().get(1).value().isDefault()).isTrue();
    assertThat(update.where()).isNotNull();
    assertThat(AstPrinter.print(statement)).isEqualTo(
        "UPDATE person SET age = (age + 1), name = DEFAULT WHERE (pid = 1) RETURNING *");
  }

  @Test
  void updateForms() {
    assertThat(print("update person set age = 1")).isEqualTo("UPDATE person SET age = 1");
    assertThat(print("UPDATE ONLY person p SET age = 1")).isEqualTo("UPDATE ONLY person AS p SET age = 1");
    assertThat(print("UPDATE person AS p SET age = p.age * 2 WHERE p.pid IN (1, 2)")).isEqualTo(
        "UPDATE person AS p SET age = (p.age * 2) WHERE (p.pid IN (1, 2))");
    assertThat(print("UPDATE t SET set = 1")).isEqualTo("UPDATE t SET set = 1");
    assertThat(print("UPDATE t SET a = NULL, b = $1;")).isEqualTo("UPDATE t SET a = NULL, b = $1");
  }

  @Test
  void deleteForms() {
    final Statement statement = StatementParser.parse("DELETE FROM person");
    assertThat(statement).isInstanceOf(DeleteStatement.class);
    assertThat(((DeleteStatement) statement).where()).isNull();
    assertThat(print("DELETE FROM person")).isEqualTo("DELETE FROM person");
    assertThat(print("delete from only person p where pid = $1 returning pid, name")).isEqualTo(
        "DELETE FROM ONLY person AS p WHERE (pid = $1) RETURNING pid, name");
    assertThat(print("DELETE FROM public.person AS p")).isEqualTo("DELETE FROM public.person AS p");
  }

  @Test
  void explainTakesAWrite() {
    final Statement statement = StatementParser.parse("EXPLAIN UPDATE person SET age = 1 WHERE pid = 1");
    assertThat(statement).isInstanceOf(ExplainWriteStatement.class);
    assertThat(AstPrinter.print(statement)).isEqualTo("EXPLAIN UPDATE person SET age = 1 WHERE (pid = 1)");
    assertThat(print("EXPLAIN DELETE FROM t")).isEqualTo("EXPLAIN DELETE FROM t");
    assertThat(print("EXPLAIN INSERT INTO t VALUES (1)")).isEqualTo("EXPLAIN INSERT INTO t VALUES (1)");
  }

  // ------------------------------------------------------------------------------------------------ transactions

  static Stream<String[]> transactionStatements() {
    return Stream.of(new String[] { "BEGIN", "BEGIN" }, new String[] { "begin work", "BEGIN" },
        new String[] { "BEGIN TRANSACTION;", "BEGIN" }, new String[] { "START TRANSACTION", "BEGIN" },
        new String[] { "COMMIT", "COMMIT" }, new String[] { "commit work", "COMMIT" }, new String[] { "COMMIT TRANSACTION", "COMMIT" },
        new String[] { "END", "COMMIT" }, new String[] { "END WORK", "COMMIT" }, new String[] { "ROLLBACK", "ROLLBACK" },
        new String[] { "ROLLBACK TRANSACTION", "ROLLBACK" }, new String[] { "ABORT", "ROLLBACK" },
        new String[] { "ABORT WORK;", "ROLLBACK" });
  }

  @ParameterizedTest
  @MethodSource("transactionStatements")
  void theTransactionStatementsAreThreeKinds(final String text, final String kind) {
    final Statement statement = StatementParser.parse(text);
    assertThat(statement).isInstanceOf(TransactionStatement.class);
    assertThat(((TransactionStatement) statement).kind().name()).isEqualTo(kind);
    assertThat(AstPrinter.print(statement)).isEqualTo(kind);
  }

  // ------------------------------------------------------------------------------------------------ what is refused

  static Stream<String[]> refusals() {
    return Stream.of(
        new String[] { "INSERT INTO t VALUES (1) ON CONFLICT ON CONSTRAINT c DO NOTHING", "ON CONFLICT ON CONSTRAINT is not supported",
            "ON CONSTRAINT" },
        new String[] { "INSERT INTO t VALUES (1) ON CONFLICT (lower(a)) DO NOTHING", "An expression in the target of ON CONFLICT is not supported",
            "lower(a)" },
        new String[] { "INSERT INTO t VALUES (1) ON CONFLICT (a) WHERE a > 0 DO NOTHING",
            "An index predicate in the target of ON CONFLICT is not supported", "a > 0" },
        new String[] { "INSERT INTO t VALUES (1) ON CONFLICT (a) DO UPDATE SET (a, b) = (1, 2)", "A multiple-column assignment is not supported",
            "(a, b)" },
        new String[] { "INSERT INTO t OVERRIDING SYSTEM VALUE VALUES (1)", "OVERRIDING is not supported", "OVERRIDING" },
        new String[] { "INSERT INTO t WITH x AS (SELECT 1) SELECT * FROM x", "WITH is not supported", "WITH" },
        new String[] { "INSERT INTO t (SELECT 1)", "A parenthesised query is not supported", "(SELECT" },
        new String[] { "INSERT INTO t (a.b) VALUES (1)", "A field selection or a subscript is not supported", ".b" },
        new String[] { "UPDATE t SET (a, b) = (1, 2)", "A multiple-column assignment is not supported", "(a, b)" },
        new String[] { "UPDATE t SET a = 1 WHERE CURRENT OF c", "WHERE CURRENT OF is not supported", "CURRENT" },
        new String[] { "DELETE FROM t USING u", "DELETE with USING is not supported", "USING" },
        new String[] { "DELETE FROM t WHERE CURRENT OF c", "WHERE CURRENT OF is not supported", "CURRENT" },
        new String[] { "BEGIN ISOLATION LEVEL SERIALIZABLE", "A transaction mode (ISOLATION LEVEL, READ ONLY, DEFERRABLE) is not supported",
            "ISOLATION" },
        new String[] { "START TRANSACTION READ ONLY", "A transaction mode (ISOLATION LEVEL, READ ONLY, DEFERRABLE) is not supported",
            "READ" },
        new String[] { "COMMIT AND CHAIN", "AND CHAIN is not supported", "AND" },
        new String[] { "ROLLBACK TO SAVEPOINT a", "ROLLBACK TO is not supported", "ROLLBACK" },
        new String[] { "COMMIT PREPARED 'x'", "COMMIT PREPARED is not supported", "COMMIT" },
        new String[] { "SAVEPOINT a", "SAVEPOINT is not supported", "SAVEPOINT" },
        new String[] { "RELEASE a", "RELEASE is not supported", "RELEASE" });
  }

  @ParameterizedTest
  @MethodSource("refusals")
  void whatTheFirstVersionLeavesOutIsRefusedWith0A000AndItsPosition(final String text, final String message, final String at) {
    assertThatThrownBy(() -> StatementParser.parse(text)).as(text).isInstanceOf(PostgreSQLFeatureNotSupportedException.class)
        .hasMessageContaining(message + " by the postgresql language (line 1, column " + column(text, at) + ")");
  }

  @ParameterizedTest
  @ValueSource(strings = { "INSERT INTO t VALUES (1), (2, 3)", "INSERT INTO ONLY t VALUES (1)", "INSERT INTO t VALUES ()",
      "INSERT INTO t () VALUES (1)", "INSERT INTO t a VALUES (1)", "INSERT t VALUES (1)", "INSERT INTO t", "UPDATE t SET",
      "UPDATE t", "UPDATE t SET a", "UPDATE t SET a = ", "DELETE t", "DELETE FROM", "BEGIN BEGIN", "COMMIT 1", "ROLLBACK WORK x" })
  void textThatIsNotPostgreSQLIsASyntaxError(final String text) {
    assertThatThrownBy(() -> StatementParser.parse(text)).as(text).isInstanceOf(PostgreSQLSyntaxException.class);
  }

  @Test
  void rowsOfDifferentLengthAreASyntaxErrorAtTheShortOrLongRow() {
    final String text = "INSERT INTO t VALUES (1), (2, 3)";
    assertThatThrownBy(() -> StatementParser.parse(text)).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("VALUES lists must all be the same length (line 1, column " + column(text, "(2, 3)") + ")");
  }

  @Test
  void aSecondStatementAfterAWriteIsASyntaxError() {
    assertThatThrownBy(() -> StatementParser.parse("DELETE FROM t; DELETE FROM u")).isInstanceOf(PostgreSQLSyntaxException.class)
        .hasMessageContaining("syntax error at or near \"DELETE\" (line 1, column 16)");
  }
}
