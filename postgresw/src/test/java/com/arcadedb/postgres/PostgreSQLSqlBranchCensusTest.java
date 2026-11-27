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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The census of the places where {@code PostgresNetworkExecutor} tests for SQL by name (fork issue #12). Each of them is a
 * decision about the {@code postgresql} language, and a new one that nobody decided is a statement of that language handled as SQL
 * would handle it, silently. This test fails when a line of the executor that names {@code "sql"} is not in the table below, or a line
 * of the table is gone, so the person who adds a branch for SQL writes down what it means for the other language, here.
 * <p>
 * The key is the line as it stands in the source, trimmed: a change to such a line shows here too.
 */
class PostgreSQLSqlBranchCensusTest {
  private static final String SOURCE = "src/main/java/com/arcadedb/postgres/PostgresNetworkExecutor.java";

  private static final String SQL_ONLY_PARSE = "SQL only: this parses a statement with the ArcadeDB SQL grammar, which the postgresql language does not "
      + "read: its own engine parses it (at Parse, with 42601 and 0A000)";
  private static final String SQL_ONLY_COLUMNS = "SQL only: the postgresql language names and types its columns itself "
      + "(PostgresLanguageAnswer), before or after the run, so nothing is inferred or probed";

  /** Every line of the executor that names "sql" in code, and what is decided for the postgresql language. */
  private static final Map<String, String> DECISIONS = new TreeMap<>(Map.ofEntries(
      Map.entry("final Statement inner = \"sql\".equalsIgnoreCase(query.language) ? parseStatement(copy.getQuery()) : null;",
          "COPY: refused for the postgresql language before this line (refuseCopyInLanguage, 0A000), out of the first step"),
      Map.entry("parsedStatement = \"sql\".equalsIgnoreCase(query.language) ? parseStatement(query.query) : null;", SQL_ONLY_PARSE),
      Map.entry("recordPostgresProfile(profile, \"sql\", queryText);",
          "the metrics label of a statement that failed before its language was known"),
      Map.entry("final ResultSet resultSet = database.query(\"sql\", sampleQuery);", SQL_ONLY_COLUMNS),
      Map.entry("final SQLQueryEngine sqlEngine = (SQLQueryEngine) database.getQueryEngine(\"sql\");", SQL_ONLY_PARSE),
      Map.entry("return sqlEngine.parse(toEngineText(\"sql\", query), (DatabaseInternal) database);", SQL_ONLY_PARSE),
      Map.entry("if (language != null && !\"sql\".equalsIgnoreCase(language))", SQL_ONLY_COLUMNS),
      Map.entry("Map<String, PostgresType> columns = \"sql\".equalsIgnoreCase(language) ? getColumnsFromQuerySchema(queryText, parsed) : null;",
          SQL_ONLY_COLUMNS),
      Map.entry("final PostgresPortal portal = preparedStatement != null ? PostgresPortal.bindFrom(preparedStatement) : new PostgresPortal(\"\", \"sql\");",
          "the portal of a Bind that names no statement is empty and SQL: it runs nothing"),
      Map.entry("if (\"sql\".equalsIgnoreCase(portal.language)) {",
          "COPY at Parse: refused for the postgresql language before this line (refuseCopyInLanguage, 0A000)"),
      Map.entry("portal.sqlStatement = sqlEngine.parse(toEngineText(\"sql\", portal.copyStatement.getQuery()), (DatabaseInternal) database);",
          "COPY at Parse, SQL only: see the line before"),
      Map.entry("case \"sql\":", "the Parse of a SQL statement; the postgresql language has the case after it: transaction control is the wire's, "
          + "the rest is read by its own engine"),
      Map.entry("if (QUOTED_IDENTIFIERS && (\"sql\".equals(language) || \"sqlscript\".equals(language)) && !isSessionCommand(queryText))",
          "SQL only, decided in #12: double-quoted identifiers and string literals reach the postgresql language as written, "
              + "the rewriting is for the back-ticks of ArcadeDB SQL"),
      Map.entry("return \"sql\".equalsIgnoreCase(language) || \"sqlscript\".equalsIgnoreCase(language) ?",
          "SQL only, decided in #12: the literals of the postgresql language are read by its own lexer, as PostgreSQL reads them"),
      Map.entry("return \"sql\".equalsIgnoreCase(language) ? transactionControlOf(parseStatement(text)) : null;",
          "SQL only: the spellings the SQL grammar accepts for BEGIN and COMMIT; the wire's own PostgresTransactionStatement "
              + "recognizes PostgreSQL's for both languages, and for the postgresql language reads a comment as white space (fork "
              + "issue #236), decided in the branch before this line")));

  private static Path sourceFile() {
    Path directory = Path.of(System.getProperty("basedir", "")).toAbsolutePath();
    for (int i = 0; i < 4 && directory != null; i++, directory = directory.getParent()) {
      for (final String module : new String[] { "", "postgresw" }) {
        final Path candidate = directory.resolve(module).resolve(SOURCE);
        if (Files.exists(candidate))
          return candidate;
      }
    }
    throw new IllegalStateException("The source of the executor is not under " + System.getProperty("basedir") + ": " + SOURCE);
  }

  @Test
  void everyPlaceThatNamesSqlIsDecided() throws IOException {
    final var found = new ArrayList<String>();
    for (final String line : Files.readAllLines(sourceFile())) {
      final String trimmed = line.trim();
      if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
        continue;
      if (trimmed.contains("\"sql\"") || trimmed.contains("\"sqlscript\"") || trimmed.contains("PostgresQueryLanguages.SQL"))
        found.add(trimmed);
    }

    assertThat(found).as("a line of the executor names SQL and is not in the census: decide what it means for the postgresql language "
        + "and add it to DECISIONS").isSubsetOf(DECISIONS.keySet());
    assertThat(DECISIONS.keySet()).as("a decided line is gone or changed: remove it from DECISIONS or update it").isSubsetOf(found);
    assertThat(DECISIONS.values()).noneMatch(String::isBlank);
  }
}
