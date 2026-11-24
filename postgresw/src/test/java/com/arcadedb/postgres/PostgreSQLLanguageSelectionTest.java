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
package com.arcadedb.postgres;

import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a statement and a connection choose the {@code postgresql} language (fork issue #12): the marker, the default of a
 * connection, the offset a marker takes off the text, and how the types of the language are announced. No server: the parts of
 * the wire module that decide it.
 */
class PostgreSQLLanguageSelectionTest {
  private final PostgresQueryLanguages languages = new PostgresQueryLanguages("sqlscript,cypher,postgresql");

  @Test
  void aStatementWithNoMarkerGoesToTheDefaultOfTheConnection() {
    assertThat(languages.select("SELECT 1").language()).isEqualTo("sql");
    assertThat(languages.select("SELECT 1", null).language()).isEqualTo("sql");
    assertThat(languages.select("SELECT 1", "postgresql").language()).isEqualTo("postgresql");
    assertThat(languages.select("SELECT 1", "postgresql").text()).isEqualTo("SELECT 1");
    assertThat(languages.select("SELECT 1", "postgresql").offset()).isZero();
  }

  @Test
  void aMarkerWinsOverTheDefaultSqlIncluded() {
    final PostgresQueryLanguages.Selection sql = languages.select("{sql} SELECT 1", "postgresql");
    assertThat(sql.language()).isEqualTo("sql");
    assertThat(sql.text()).isEqualTo("SELECT 1");
    final PostgresQueryLanguages.Selection postgresql = languages.select("  /* c */ {PostgreSQL}\n  SELECT 1  ", null);
    assertThat(postgresql.language()).isEqualTo("postgresql");
    assertThat(postgresql.text()).isEqualTo("SELECT 1");
    // what the marker and the blanks after it took off the front, so a position in the text is a position in the statement sent
    assertThat(languages.select("  /* c */ {PostgreSQL}\n  SELECT 1  ").offset()).isEqualTo("  /* c */ {PostgreSQL}\n  ".length());
  }

  @Test
  void aTextThatOnlyLooksLikeAMarkerIsTheDefaultLanguagesToRead() {
    assertThat(languages.select("{1:2} SELECT 1", "postgresql").language()).isEqualTo("postgresql");
    assertThat(languages.select("{ postgresql } SELECT 1").language()).isEqualTo("sql");
  }

  @Test
  void aMarkerOfAnotherLanguageIsRefusedWhateverTheDefaultIs() {
    final PostgresQueryLanguages onlyPostgreSQL = new PostgresQueryLanguages("postgresql");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> onlyPostgreSQL.select("{cypher} MATCH (n) RETURN n", "postgresql"))
        .isInstanceOf(PostgresQueryLanguages.Refusal.class);
  }

  @Test
  void theLanguageOfAConnectionMustBeOneThatAMarkerMaySelect() {
    assertThat(languages.refusalOfSessionLanguage(null)).isNull();
    assertThat(languages.refusalOfSessionLanguage("")).isNull();
    assertThat(languages.refusalOfSessionLanguage("sql")).isNull();
    assertThat(languages.refusalOfSessionLanguage("PostgreSQL")).isNull();
    assertThat(languages.refusalOfSessionLanguage("gremlin")).contains("not permitted");
    assertThat(languages.refusalOfSessionLanguage("nosuch")).contains("not permitted");
    // permitted by the setting but no engine answers it
    assertThat(new PostgresQueryLanguages("nosuch").refusalOfSessionLanguage("nosuch")).contains("no query engine");
  }

  @Test
  void theWireAnswersTheStatementsOfPostgreSQLForItselfInTheTwoLanguagesThatSpeakThem() {
    assertThat(PostgresQueryLanguages.isWireLanguage("sql")).isTrue();
    assertThat(PostgresQueryLanguages.isWireLanguage("postgresql")).isTrue();
    assertThat(PostgresQueryLanguages.isWireLanguage("cypher")).isFalse();
    assertThat(PostgresQueryLanguages.isWireLanguage("sqlscript")).isFalse();
  }

  @Test
  void theTypesOfTheLanguageAreAnnouncedAsWireTypes() {
    assertThat(PostgresLanguageAnswer.wireType("int4")).isEqualTo(PostgresType.INTEGER);
    assertThat(PostgresLanguageAnswer.wireType("int8")).isEqualTo(PostgresType.LONG);
    assertThat(PostgresLanguageAnswer.wireType("varchar")).isEqualTo(PostgresType.VARCHAR);
    assertThat(PostgresLanguageAnswer.wireType("numeric")).isEqualTo(PostgresType.NUMERIC);
    assertThat(PostgresLanguageAnswer.wireType("jsonb")).isEqualTo(PostgresType.JSONB);
    assertThat(PostgresLanguageAnswer.wireType("text[]")).isEqualTo(PostgresType.ARRAY_TEXT);
    assertThat(PostgresLanguageAnswer.wireType("_int8")).isEqualTo(PostgresType.ARRAY_LONG);
    assertThat(PostgresLanguageAnswer.wireType("int4[]")).isEqualTo(PostgresType.ARRAY_INT);
    // no OID for pgvector's type, and the type of a constant that nothing decided
    assertThat(PostgresLanguageAnswer.wireType("vector")).isEqualTo(PostgresType.ARRAY_REAL);
    assertThat(PostgresLanguageAnswer.wireType("unknown")).isEqualTo(PostgresType.TEXT);
    assertThat(PostgresLanguageAnswer.wireType(null)).isEqualTo(PostgresType.VARCHAR);
  }

  @Test
  void twoColumnsWithOneLabelAreAnnouncedAlikeAndKeyedApart() {
    final var columns = List.of(new PostgreSQLResultSet.Column("name", "varchar"), new PostgreSQLResultSet.Column("name", "varchar"),
        new PostgreSQLResultSet.Column("n", "int8"));
    final var wire = PostgresLanguageAnswer.wireColumns(columns);
    assertThat(wire.keySet().stream().map(PostgresCatalog::announcedName)).containsExactly("name", "name", "n");
    assertThat(wire.keySet()).doesNotHaveDuplicates();
  }

  @Test
  void aParameterTypeTheClientDeclaredIsTheTypeOfTheLanguageOrUnknown() {
    assertThat(PostgresLanguageAnswer.parameterType(0)).isEqualTo(PgType.UNKNOWN);
    assertThat(PostgresLanguageAnswer.parameterType(PostgresType.INTEGER.code)).isEqualTo(PgType.INT4);
    assertThat(PostgresLanguageAnswer.parameterType(PostgresType.LONG.code)).isEqualTo(PgType.INT8);
    assertThat(PostgresLanguageAnswer.parameterType(PostgresType.VARCHAR.code)).isEqualTo(PgType.VARCHAR);
    assertThat(PostgresLanguageAnswer.parameterType(999999)).isEqualTo(PgType.UNKNOWN);
  }

  @Test
  void theCommandTagIsReadOffTheFirstWord() {
    assertThat(PostgresLanguageAnswer.commandTag("SELECT 1", 1)).isEqualTo("SELECT 1");
    assertThat(PostgresLanguageAnswer.commandTag("  -- c\nINSERT INTO t VALUES (1)", 1)).isEqualTo("INSERT 0 1");
    assertThat(PostgresLanguageAnswer.commandTag("update t set a = 1", 3)).isEqualTo("UPDATE 3");
    assertThat(PostgresLanguageAnswer.commandTag("DELETE FROM t", 0)).isEqualTo("DELETE 0");
    assertThat(PostgresLanguageAnswer.commandTag("EXPLAIN SELECT 1", 2)).isEqualTo("EXPLAIN");
    assertThat(PostgresLanguageAnswer.commandTag("WITH a AS (SELECT 1) SELECT * FROM a", 1)).isEqualTo("SELECT 1");
  }
}
