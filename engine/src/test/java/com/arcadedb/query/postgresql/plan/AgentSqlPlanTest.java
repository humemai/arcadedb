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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.corpus.CorpusFile;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second half of the measurement of fork issue #7 (the first is {@code AgentSqlFitTest}, the parser): what the engine does
 * with the statements of {@code postgresql/corpus/agent-sql.tsv} that the parser accepts, when it binds and plans them and runs
 * them on empty tables.
 * <p>
 * The schema is the one the models were given (the eight tables of {@code agent-sql-schema.sql}), declared in a database the way
 * the relational view reads it: a type per table in lower case (an unquoted name is folded to lower case, as PostgreSQL does)
 * and a declared property per column, so the {@code id} column of the LDBC tables is a declared property {@code id}, which the
 * view shows as {@code id_}, while {@code id} is the record id as text (the decision of fork issue #1). A statement that joins an
 * integer foreign key to {@code id} is therefore refused by the binder, which is what the measurement is for. The tables are
 * empty, so a statement that runs says only that it binds, plans and executes without an error, not that its rows are right.
 * <p>
 * Each accepted statement is run twice: as written, and with the word {@code id} read as the declared column ({@code id_}), which
 * is the answer to "what if the declared {@code id} were what an agent gets under that name". The second run changes no design
 * and no code: it only rewrites the text of the statement, to show how much of the refusal is the {@code id} decision.
 * <p>
 * It prints the outcome per group and verdict, the refusals by SQLSTATE and message, and every refused statement, and writes the
 * same to {@code target/pg-agent-fit/plan-report.md}. It never fails on the numbers.
 */
class AgentSqlPlanTest extends TestHelper {
  private static final String RESOURCE = "/postgresql/corpus/agent-sql.tsv";
  private static final List<String> GROUPS = List.of("single", "join", "multi_join", "graph");

  /** {@code sqlState} and {@code message} are the run as written, {@code whatIfState} and {@code whatIfMessage} the run with id read as id_. */
  private record Outcome(String id, String group, String duckdb, String statement, String sqlState, String message,
                         String whatIfState, String whatIfMessage) {
    boolean ran() {
      return sqlState.isEmpty();
    }

    boolean ranWhatIf() {
      return whatIfState.isEmpty();
    }
  }

  /** The eight tables of {@code agent-sql-schema.sql}: BIGINT is LONG, INTEGER is INTEGER and VARCHAR is STRING. */
  private void declareSchema() {
    final Schema schema = database.getSchema();
    table(schema, "person", "id", Type.LONG, "first_name", Type.STRING, "last_name", Type.STRING, "gender", Type.STRING, "birth_year",
        Type.INTEGER, "creation_year", Type.INTEGER, "city_id", Type.LONG);
    table(schema, "city", "id", Type.LONG, "name", Type.STRING, "country_id", Type.LONG);
    table(schema, "country", "id", Type.LONG, "name", Type.STRING);
    table(schema, "forum", "id", Type.LONG, "title", Type.STRING, "moderator_id", Type.LONG);
    table(schema, "tag", "id", Type.LONG, "name", Type.STRING, "tagclass_id", Type.LONG);
    table(schema, "tagclass", "id", Type.LONG, "name", Type.STRING);
    table(schema, "knows", "person1_id", Type.LONG, "person2_id", Type.LONG, "since_year", Type.INTEGER);
    table(schema, "hasinterest", "person_id", Type.LONG, "tag_id", Type.LONG);
  }

  private static void table(final Schema schema, final String name, final Object... propertiesAndTypes) {
    final DocumentType type = schema.createDocumentType(name);
    for (int i = 0; i < propertiesAndTypes.length; i += 2)
      type.createProperty((String) propertiesAndTypes[i], (Type) propertiesAndTypes[i + 1]);
  }

  @Test
  void measureWhatTheEngineDoesWithTheAcceptedStatements() throws IOException {
    declareSchema();
    final CorpusFile file = CorpusFile.readResource(RESOURCE);
    final int idColumn = file.column("id");
    final int statementColumn = file.column("statement");
    final int groupColumn = file.column("group");
    final int verdictColumn = file.column("duckdb");

    final List<Outcome> outcomes = new ArrayList<>();
    int refusedByTheParser = 0;
    for (final String[] row : file.rows) {
      final String statement = CorpusFile.unescape(row[statementColumn]);
      try {
        StatementParser.parse(statement);
      } catch (final RuntimeException e) {
        ++refusedByTheParser;
        continue;
      }
      final String[] asWritten = run(statement);
      final String[] whatIf = run(statement.replaceAll("(?i)\\bid\\b", "id_"));
      outcomes.add(new Outcome(row[idColumn], row[groupColumn], row[verdictColumn], statement, asWritten[0], asWritten[1], whatIf[0],
          whatIf[1]));
    }

    final String report = report(file.rows.size(), refusedByTheParser, outcomes);
    System.out.println(report);
    try {
      final Path directory = Path.of("target", "pg-agent-fit");
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("plan-report.md"), report, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      System.out.println("pg-agent-fit: the plan report file was not written: " + e);
    }
    assertThat(outcomes.size() + refusedByTheParser).isEqualTo(file.rows.size());
  }

  /** @return the SQLSTATE and the message of the refusal, both empty when the statement ran to its end */
  private String[] run(final String statement) {
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      while (resultSet.hasNext())
        resultSet.next();
      return new String[] { "", "" };
    } catch (final RuntimeException | StackOverflowError e) {
      return new String[] { sqlStateOf(e), e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()) };
    }
  }

  /** The SQLSTATE the exception carries, {@code ?????} when it has none. */
  private static String sqlStateOf(final Throwable e) {
    try {
      final Object state = e.getClass().getMethod("getSqlState").invoke(e);
      return state == null ? "?????" : state.toString();
    } catch (final ReflectiveOperationException | RuntimeException notThere) {
      return "?????";
    }
  }

  private static String report(final int total, final int refusedByTheParser, final List<Outcome> outcomes) {
    final StringBuilder out = new StringBuilder();
    final long ran = outcomes.stream().filter(Outcome::ran).count();
    final long correct = outcomes.stream().filter(o -> "correct".equals(o.duckdb())).count();
    final long correctRan = outcomes.stream().filter(o -> "correct".equals(o.duckdb()) && o.ran()).count();
    out.append("# agent-sql plan: the engine on the statements the parser accepts, tables empty\n\n");
    out.append("| | statements | ran | share |\n|---|---|---|---|\n");
    out.append("| of all ").append(total).append(" | ").append(outcomes.size()).append(" accepted by the parser | ").append(ran)
        .append(" | ").append(pct(ran, total)).append(" of all |\n");
    out.append("| of the accepted | ").append(outcomes.size()).append(" | ").append(ran).append(" | ")
        .append(pct(ran, outcomes.size())).append(" |\n");
    out.append("| correct on DuckDB, accepted | ").append(correct).append(" | ").append(correctRan).append(" | ")
        .append(pct(correctRan, correct)).append(" |\n\n");
    out.append("Refused by the parser (not in the table above): ").append(refusedByTheParser).append(".\n");

    out.append("\n## Per group, statements the parser accepts\n\n| group | accepted by the parser | ran as written | refused after the parser | ran with id read as id_ |\n|---|---|---|---|---|\n");
    for (final String group : GROUPS) {
      final List<Outcome> sub = outcomes.stream().filter(o -> group.equals(o.group())).toList();
      final long subRan = sub.stream().filter(Outcome::ran).count();
      final long subWhatIf = sub.stream().filter(Outcome::ranWhatIf).count();
      out.append("| ").append(group).append(" | ").append(sub.size()).append(" | ").append(subRan).append(" | ")
          .append(sub.size() - subRan).append(" | ").append(subWhatIf).append(" |\n");
    }
    final long whatIfRan = outcomes.stream().filter(Outcome::ranWhatIf).count();
    final long whatIfCorrect = outcomes.stream().filter(o -> "correct".equals(o.duckdb()) && o.ranWhatIf()).count();
    out.append("\nWith id read as the declared column (id_): ").append(whatIfRan).append(" of ").append(outcomes.size())
        .append(" accepted statements run (").append(pct(whatIfRan, outcomes.size())).append("), ").append(whatIfCorrect)
        .append(" of the ").append(correct).append(" accepted statements that were correct on DuckDB (").append(pct(whatIfCorrect, correct))
        .append(").\n");

    final Map<String, int[]> byMessage = new TreeMap<>();
    for (final Outcome o : outcomes)
      if (!o.ran())
        byMessage.computeIfAbsent(o.sqlState() + " " + normalise(o.message()), k -> new int[2])[o.duckdb().equals("correct") ? 0 : 1]++;
    out.append("\n## Refused after the parser, by SQLSTATE and message\n\n| SQLSTATE and message | correct statements | other |\n|---|---|---|\n");
    final List<Map.Entry<String, int[]>> sorted = new ArrayList<>(byMessage.entrySet());
    sorted.sort((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
    for (final Map.Entry<String, int[]> each : sorted)
      out.append("| ").append(cell(each.getKey())).append(" | ").append(each.getValue()[0]).append(" | ").append(each.getValue()[1])
          .append(" |\n");

    final Map<String, int[]> byWhatIf = new TreeMap<>();
    for (final Outcome o : outcomes)
      if (!o.ranWhatIf())
        byWhatIf.computeIfAbsent(o.whatIfState() + " " + normalise(o.whatIfMessage()), k -> new int[2])[o.duckdb().equals("correct") ? 0 : 1]++;
    out.append("\n## Refused after the parser with id read as id_, by SQLSTATE and message\n\n| SQLSTATE and message | correct statements | other |\n|---|---|---|\n");
    for (final Map.Entry<String, int[]> each : byWhatIf.entrySet())
      out.append("| ").append(cell(each.getKey())).append(" | ").append(each.getValue()[0]).append(" | ").append(each.getValue()[1])
          .append(" |\n");

    out.append("\n## Every refused statement (as written)\n\n");
    for (final Outcome o : outcomes)
      if (!o.ran())
        out.append("- ").append(o.id()).append(" (").append(o.group()).append(", ").append(o.duckdb()).append("): ")
            .append(oneLine(o.message(), 220)).append("\n    ").append(cell(oneLine(o.statement(), 240))).append('\n');
    out.append("\n## Every statement refused with id read as id_\n\n");
    for (final Outcome o : outcomes)
      if (!o.ranWhatIf())
        out.append("- ").append(o.id()).append(" (").append(o.group()).append(", ").append(o.duckdb()).append("): ")
            .append(oneLine(o.whatIfMessage(), 220)).append("\n    ").append(cell(oneLine(o.statement(), 240))).append('\n');
    return out.toString();
  }

  /** The message without what differs between statements: the names between double quotes and the numbers. */
  private static String normalise(final String message) {
    return oneLine(message.replaceAll("\"[^\"]*\"", "\"...\"").replaceAll("[0-9]+", "N"), 160);
  }

  private static String cell(final String text) {
    return "`" + text.replace("`", "'").replace("|", "/") + "`";
  }

  private static String oneLine(final String text, final int max) {
    final String line = text.replaceAll("\\s+", " ").trim();
    return line.length() <= max ? line : line.substring(0, max) + "...";
  }

  private static String pct(final long part, final long whole) {
    return whole == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole);
  }
}
