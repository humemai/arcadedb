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
import com.arcadedb.query.postgresql.corpus.CorpusFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How much of the SQL that language models write falls inside the subset of the {@code postgresql} language (fork issue #7, the
 * roadmap of constructs). The statements are the 240 ANSI SQL generations of the experiment {@code sql-evidence} (two models, 40
 * questions, three samples, nothing deduplicated and nothing filtered) in {@code postgresql/corpus/agent-sql.tsv}, with the verdict
 * of the experiment (the rows compared with the reference rows on DuckDB) and what a real PostgreSQL 18 does with each statement.
 * <p>
 * This is a measurement, not a differential: the test PRINTS the distribution and fails only when the file is malformed. The
 * statement is parsed with {@link StatementParser#parse(String)}, so what it measures is the parser. A statement the parser accepts
 * can still be refused by the binder or the planner.
 * <p>
 * The report is printed to the standard output (the log of the job) and written to {@code target/pg-agent-fit/report.md}, which
 * the workflow {@code pg-prototype-engine-tests.yml} uploads as an artifact. Its sections: the outcomes per group, model and
 * verdict; the outcomes against what PostgreSQL 18 says; the statements that the experiment judged correct and that the parser
 * refuses, ranked by the word the refusal names; the {@code 42601} refusals (the parser gaps, which are bugs of ours) and the
 * statements the parser takes although the server does not; a greedy plan of the constructs that would unlock the most correct
 * statements, from a textual scan of each refused statement (a heuristic: it finds a construct that is present, not the one that
 * the parser reaches first); and the full list, one line per statement.
 */
class AgentSqlFitTest {
  private static final String RESOURCE = "/postgresql/corpus/agent-sql.tsv";

  private static final List<String> SYNTAX_COLUMNS = List.of("id", "pg18", "subset", "gap", "statement", "source", "detail");
  private static final List<String> GROUPS         = List.of("single", "join", "multi_join", "graph");

  private static final Pattern NOT_SUPPORTED = Pattern.compile(
      "^(.*?) is not supported by the postgresql language \\(line (\\d+), column (\\d+)\\)(.*)$", Pattern.DOTALL);

  private enum Kind {
    ACCEPTED("accepted"),
    REFUSED_0A000("0A000"),
    REFUSED_42601("42601"),
    REFUSED_OTHER("other SQLSTATE"),
    INTERNAL_ERROR("internal error");

    final String label;

    Kind(final String label) {
      this.label = label;
    }
  }

  private record Entry(String id, String pg18, String detail, String statement, String model, String group, String duckdb) {
    boolean correct() {
      return "correct".equals(duckdb);
    }
  }

  /** {@code word} is the word the refusal starts at, in upper case, empty when the parser accepted the statement. */
  private record Result(Entry entry, Kind kind, String message, String word, Set<String> features) {
  }

  /** The constructs the textual scan looks for, by name. A statement outside the subset has at least one of them. */
  private static final Map<String, Pattern> FEATURES = new LinkedHashMap<>();

  static {
    feature("WITH (CTE)", "\\A\\s*with\\b");
    feature("WITH RECURSIVE", "\\bwith\\s+recursive\\b");
    feature("UNION, INTERSECT, EXCEPT", "\\b(union|intersect|except)\\b");
    feature("subquery in FROM or JOIN", "\\b(from|join)\\s*\\(\\s*(select|with)\\b");
    feature("IN (SELECT ...)", "\\bin\\s*\\(\\s*select\\b");
    feature("EXISTS (...)", "\\bexists\\s*\\(");
    feature("scalar subquery", "[=<>+*/-]\\s*\\(\\s*select\\b|\\bselect\\s*\\(\\s*select\\b|,\\s*\\(\\s*select\\b[^)]*\\)\\s+as\\b");
    feature("window function (OVER)", "\\bover\\s*\\(|\\bover\\s+[a-z_]");
    feature("ILIKE", "\\bilike\\b");
    feature("DISTINCT ON", "\\bdistinct\\s+on\\b");
    feature("EXTRACT, date_part, date_trunc", "\\b(extract|date_part|date_trunc)\\s*\\(");
    feature("FILTER (WHERE ...)", "\\bfilter\\s*\\(\\s*where\\b");
    feature("JOIN ... USING", "\\busing\\s*\\(");
    feature("RIGHT, FULL, NATURAL JOIN", "\\b(natural|full|right)\\s+(outer\\s+)?join\\b|\\bnatural\\b");
    feature("LATERAL", "\\blateral\\b");
    feature("string_agg, array_agg", "\\b(string_agg|array_agg)\\s*\\(");
    feature("GROUP BY ALL, ROLLUP, CUBE, GROUPING SETS", "\\bgroup\\s+by\\s+(all|distinct)\\b|\\b(rollup|cube|grouping\\s+sets)\\b");
    feature("VALUES list", "\\bvalues\\s*\\(");
    feature("FETCH FIRST", "\\bfetch\\s+(first|next)\\b");
    feature("an explanation around the query (not a statement)", "\\A\\s*+(?!select\\b|with\\b|\\(|values\\b|table\\b|explain\\b|;)");
  }

  private static void feature(final String name, final String regex) {
    FEATURES.put(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
  }

  @Test
  void measureTheAgentSql() throws IOException {
    final CorpusFile file = CorpusFile.readResource(RESOURCE);
    assertThat(file.columns.subList(0, SYNTAX_COLUMNS.size())).as("the columns of syntax.tsv come first").isEqualTo(SYNTAX_COLUMNS);
    assertThat(file.columns.subList(SYNTAX_COLUMNS.size(), file.columns.size())).containsExactly("model", "group", "duckdb");
    assertThat(file.rows).as("statements of the corpus").isNotEmpty();

    final List<Entry> entries = new ArrayList<>();
    final Set<String> ids = new HashSet<>();
    for (final String[] row : file.rows) {
      final Entry entry = new Entry(row[0], row[1], row[6], CorpusFile.unescape(row[4]), row[7], row[8], row[9]);
      assertThat(ids.add(entry.id())).as("unique id %s", entry.id()).isTrue();
      assertThat(GROUPS).as("group of %s", entry.id()).contains(entry.group());
      assertThat(entry.duckdb()).as("verdict of %s", entry.id()).isIn("correct", "incorrect", "unscored");
      assertThat(entry.statement()).as("statement of %s", entry.id()).isNotEmpty();
      entries.add(entry);
    }

    final List<Result> results = new ArrayList<>();
    for (final Entry entry : entries)
      results.add(run(entry));

    final String report = report(file, results);
    System.out.println(report);
    try {
      final Path directory = Path.of("target", "pg-agent-fit");
      Files.createDirectories(directory);
      Files.writeString(directory.resolve("report.md"), report, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      System.out.println("pg-agent-fit: the report file was not written: " + e);
    }
    assertThat(results).hasSameSizeAs(entries);
  }

  private static Result run(final Entry entry) {
    final Set<String> features = features(entry.statement());
    try {
      StatementParser.parse(entry.statement());
      return new Result(entry, Kind.ACCEPTED, "", "", features);
    } catch (final PostgreSQLFeatureNotSupportedException e) {
      return new Result(entry, Kind.REFUSED_0A000, e.getMessage(), wordAt(entry.statement(), e.getMessage()), features);
    } catch (final PostgreSQLSyntaxException e) {
      return new Result(entry, Kind.REFUSED_42601, e.getMessage(), wordAt(entry.statement(), e.getMessage()), features);
    } catch (final PostgreSQLInvalidEscapeException e) {
      return new Result(entry, Kind.REFUSED_OTHER, e.getSqlState() + ": " + e.getMessage(), "", features);
    } catch (final RuntimeException | StackOverflowError e) {
      return new Result(entry, Kind.INTERNAL_ERROR, e.toString(), "", features);
    }
  }

  /** The word at the position the message names ({@code (line L, column C)} or {@code (...)} of a syntax error). */
  private static String wordAt(final String statement, final String message) {
    final Matcher m = Pattern.compile("\\(line (\\d+), column (\\d+)\\)").matcher(message);
    if (!m.find())
      return "";
    final int line = Integer.parseInt(m.group(1));
    final int column = Integer.parseInt(m.group(2));
    int offset = 0;
    for (int i = 1; i < line; i++) {
      final int next = statement.indexOf('\n', offset);
      if (next < 0)
        return "";
      offset = next + 1;
    }
    offset += column - 1;
    if (offset < 0 || offset >= statement.length())
      return "END OF INPUT";
    int end = offset;
    if (Character.isLetterOrDigit(statement.charAt(offset)) || statement.charAt(offset) == '_') {
      while (end < statement.length() && (Character.isLetterOrDigit(statement.charAt(end)) || statement.charAt(end) == '_'))
        end++;
    } else
      end = offset + 1;
    return statement.substring(offset, end).toUpperCase(Locale.ROOT);
  }

  /** The constructs of {@link #FEATURES} present in the statement, from its text without string constants and comments. */
  private static Set<String> features(final String statement) {
    final String text = statement.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\n]*", " ")
        .replaceAll("'(?:[^']|'')*'", "''").toLowerCase(Locale.ROOT);
    final Set<String> found = new TreeSet<>();
    for (final Map.Entry<String, Pattern> each : FEATURES.entrySet())
      if (each.getValue().matcher(text).find())
        found.add(each.getKey());
    return found;
  }

  private static String report(final CorpusFile file, final List<Result> results) {
    final StringBuilder out = new StringBuilder();
    final long correct = results.stream().filter(r -> r.entry().correct()).count();
    final long accepted = results.stream().filter(r -> r.kind() == Kind.ACCEPTED).count();
    final long correctAccepted = results.stream().filter(r -> r.entry().correct() && r.kind() == Kind.ACCEPTED).count();
    out.append("# agent-sql fit: ").append(results.size()).append(" statements, parsed with StatementParser.parse\n\n");
    out.append("server of the pg18 column: ").append(file.headerValue("server") == null ? "none, the generator has not run" : file.headerValue("server"))
        .append("\n\n");
    out.append("| | statements | accepted | share |\n|---|---|---|---|\n");
    out.append("| all | ").append(results.size()).append(" | ").append(accepted).append(" | ").append(pct(accepted, results.size()))
        .append(" |\n");
    out.append("| correct on DuckDB | ").append(correct).append(" | ").append(correctAccepted).append(" | ")
        .append(pct(correctAccepted, correct)).append(" |\n\n");
    final Set<String> distinct = new HashSet<>();
    for (final Result r : results)
      distinct.add(r.entry().statement());
    out.append("Distinct statement texts: ").append(distinct.size()).append(" of ").append(results.size()).append(".\n\n");

    out.append("## Outcome per group\n\n");
    distribution(out, "group", r -> r.entry().group(), results, GROUPS);
    out.append("\n## Outcome per model\n\n");
    distribution(out, "model", r -> r.entry().model(), results, List.of());
    out.append("\n## Outcome per verdict of the experiment (an incorrect query may be refused)\n\n");
    distribution(out, "duckdb", r -> r.entry().duckdb(), results, List.of("correct", "incorrect", "unscored"));
    out.append("\n## Outcome per group, correct statements only\n\n");
    distribution(out, "group", r -> r.entry().group(), results.stream().filter(r -> r.entry().correct()).toList(), GROUPS);
    out.append("\n## Outcome against what PostgreSQL 18 says (the pg18 column)\n\n");
    distribution(out, "pg18", r -> r.entry().pg18() + " " + r.entry().detail(), results, List.of());

    refusals(out, results, correct);
    gaps(out, results);
    plan(out, results, correct);

    out.append("\n## Every statement\n\n");
    out.append("| id | duckdb | pg18 | outcome | word | statement |\n|---|---|---|---|---|---|\n");
    for (final Result r : results)
      out.append("| ").append(r.entry().id()).append(" | ").append(r.entry().duckdb()).append(" | ").append(r.entry().pg18())
          .append(' ').append(r.entry().detail()).append(" | ").append(r.kind().label).append(" | ").append(cell(r.word()))
          .append(" | ").append(cell(oneLine(r.entry().statement(), 160))).append(" |\n");
    return out.toString();
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

  /** One table: a row per value of the key, a column per outcome. */
  private static void distribution(final StringBuilder out, final String dimension, final Function<Result, String> key,
      final List<Result> results, final List<String> order) {
    final Map<String, int[]> counts = new TreeMap<>((a, b) -> {
      final int ia = order.indexOf(a);
      final int ib = order.indexOf(b);
      if (ia >= 0 || ib >= 0)
        return Integer.compare(ia < 0 ? Integer.MAX_VALUE : ia, ib < 0 ? Integer.MAX_VALUE : ib);
      return a.compareTo(b);
    });
    final int[] total = new int[Kind.values().length];
    for (final Result r : results) {
      counts.computeIfAbsent(key.apply(r), k -> new int[Kind.values().length])[r.kind().ordinal()]++;
      total[r.kind().ordinal()]++;
    }
    out.append("| ").append(dimension).append(" | n |");
    for (final Kind kind : Kind.values())
      out.append(' ').append(kind.label).append(" |");
    out.append(" accepted share |\n|---|---|");
    out.append("---|".repeat(Kind.values().length)).append("---|\n");
    for (final Map.Entry<String, int[]> each : counts.entrySet())
      row(out, each.getKey(), each.getValue());
    row(out, "all", total);
  }

  private static void row(final StringBuilder out, final String label, final int[] counts) {
    int n = 0;
    for (final int c : counts)
      n += c;
    out.append("| ").append(label).append(" | ").append(n).append(" |");
    for (final int c : counts)
      out.append(' ').append(c).append(" |");
    out.append(' ').append(pct(counts[Kind.ACCEPTED.ordinal()], n)).append(" |\n");
  }

  /** The statements refused with 0A000, ranked by the word the refusal names; the correct ones are what the parser misses. */
  private static void refusals(final StringBuilder out, final List<Result> results, final long correct) {
    final Map<String, List<Result>> byWord = new TreeMap<>();
    for (final Result r : results)
      if (r.kind() == Kind.REFUSED_0A000)
        byWord.computeIfAbsent(r.word(), k -> new ArrayList<>()).add(r);
    final List<Map.Entry<String, List<Result>>> ranked = new ArrayList<>(byWord.entrySet());
    ranked.sort((a, b) -> {
      final int byCorrect = Long.compare(correctOf(b.getValue()), correctOf(a.getValue()));
      return byCorrect != 0 ? byCorrect : Integer.compare(b.getValue().size(), a.getValue().size());
    });
    out.append("\n## Refused with 0A000, by the word the refusal names (ranked by correct statements)\n\n");
    out.append("The refusal is at the first construct outside the subset, so a statement with two is counted under the first one.\n\n");
    out.append("| word | what the message calls it | statements | correct | share of ").append(correct)
        .append(" correct | incorrect or unscored |\n|---|---|---|---|---|---|\n");
    for (final Map.Entry<String, List<Result>> each : ranked) {
      final long c = correctOf(each.getValue());
      out.append("| ").append(cell(each.getKey())).append(" | ").append(construct(each.getValue())).append(" | ")
          .append(each.getValue().size()).append(" | ").append(c).append(" | ").append(pct(c, correct)).append(" | ")
          .append(each.getValue().size() - c).append(" |\n");
    }
    final long refusedCorrect = results.stream().filter(r -> r.entry().correct() && r.kind() == Kind.REFUSED_0A000).count();
    out.append("\nCorrect statements refused with 0A000: ").append(refusedCorrect).append(" of ").append(correct).append(" (")
        .append(pct(refusedCorrect, correct)).append(").\n");
  }

  private static long correctOf(final List<Result> list) {
    return list.stream().filter(r -> r.entry().correct()).count();
  }

  /** The most common construct description of the messages, with the words of a generic refusal cut at the first identifier. */
  private static String construct(final List<Result> list) {
    final Map<String, Integer> counts = new TreeMap<>();
    for (final Result r : list) {
      final Matcher m = NOT_SUPPORTED.matcher(r.message());
      counts.merge(m.matches() ? m.group(1) : r.message(), 1, Integer::sum);
    }
    final List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
    sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    final StringBuilder text = new StringBuilder();
    for (int i = 0; i < Math.min(3, sorted.size()); i++) {
      if (i > 0)
        text.append("; ");
      text.append(cell(sorted.get(i).getKey())).append(" x").append(sorted.get(i).getValue());
    }
    if (sorted.size() > 3)
      text.append("; and ").append(sorted.size() - 3).append(" more");
    return text.toString();
  }

  /** The 42601 refusals (against the server), the statements accepted where the server refuses, and the refusals of other kinds. */
  private static void gaps(final StringBuilder out, final List<Result> results) {
    out.append("\n## 42601: the parser says syntax error\n\n");
    final List<Result> syntax = results.stream().filter(r -> r.kind() == Kind.REFUSED_42601).toList();
    final long gap = syntax.stream().filter(r -> "ok".equals(r.entry().pg18())).count();
    final long agree = syntax.stream().filter(r -> "syntax-error".equals(r.entry().pg18())).count();
    out.append(syntax.size()).append(" statements: ").append(gap).append(" that PostgreSQL 18 accepts (parser gaps, bugs of ours), ")
        .append(agree).append(" that it also rejects as a syntax error, ").append(syntax.size() - gap - agree)
        .append(" with another or no server answer.\n\n");
    list(out, syntax);

    out.append("\n## Accepted by the parser where PostgreSQL 18 says syntax error\n\n");
    final List<Result> lenient = results.stream()
        .filter(r -> r.kind() == Kind.ACCEPTED && "syntax-error".equals(r.entry().pg18())).toList();
    out.append(lenient.size()).append(" statements.\n\n");
    list(out, lenient);

    out.append("\n## Refused with 0A000 where PostgreSQL 18 says syntax error\n\n");
    final List<Result> wrongClass = results.stream()
        .filter(r -> r.kind() == Kind.REFUSED_0A000 && "syntax-error".equals(r.entry().pg18())).toList();
    out.append(wrongClass.size()).append(" statements.\n\n");
    list(out, wrongClass);

    out.append("\n## Other refusals and internal errors\n\n");
    final List<Result> other = results.stream().filter(r -> r.kind() == Kind.REFUSED_OTHER || r.kind() == Kind.INTERNAL_ERROR)
        .toList();
    out.append(other.size()).append(" statements.\n\n");
    list(out, other);
  }

  private static void list(final StringBuilder out, final List<Result> list) {
    for (final Result r : list)
      out.append("- ").append(r.entry().id()).append(" (").append(r.entry().group()).append(", ").append(r.entry().duckdb())
          .append(", pg18 ").append(r.entry().pg18()).append(' ').append(r.entry().detail()).append("): ")
          .append(oneLine(r.message(), 200)).append("\n    ").append(cell(oneLine(r.entry().statement(), 240))).append('\n');
  }

  /**
   * A greedy plan over the correct statements that the parser does not accept: the constructs present in each (the textual
   * scan, or the word of the refusal when the scan finds none) and, step by step, the one that makes the most statements
   * complete, given those chosen before it. A statement counts as unlocked when every construct found in it is chosen. It is an
   * upper bound for the parser: a construct the scan does not know, or one the parser reaches later, can still refuse it.
   */
  private static void plan(final StringBuilder out, final List<Result> results, final long correct) {
    final List<Result> blocked = results.stream().filter(r -> r.entry().correct() && r.kind() != Kind.ACCEPTED).toList();
    final long accepted = results.stream().filter(r -> r.entry().correct() && r.kind() == Kind.ACCEPTED).count();
    final List<Set<String>> needs = new ArrayList<>();
    final Map<String, Integer> present = new TreeMap<>();
    final Map<String, Integer> sole = new TreeMap<>();
    for (final Result r : blocked) {
      final Set<String> set = new TreeSet<>(r.features());
      if (set.isEmpty())
        set.add(r.kind() == Kind.REFUSED_42601 ? "parser gap (42601) at " + r.word() : "parser refusal at " + r.word());
      needs.add(set);
      for (final String name : set)
        present.merge(name, 1, Integer::sum);
      if (set.size() == 1)
        sole.merge(set.iterator().next(), 1, Integer::sum);
    }
    out.append("\n## Constructs present in the ").append(blocked.size()).append(" correct statements the parser does not accept\n\n");
    out.append("| construct | statements that have it | statements that need only it | share of ").append(correct)
        .append(" correct |\n|---|---|---|---|\n");
    final List<Map.Entry<String, Integer>> byPresence = new ArrayList<>(present.entrySet());
    byPresence.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    for (final Map.Entry<String, Integer> each : byPresence)
      out.append("| ").append(each.getKey()).append(" | ").append(each.getValue()).append(" | ")
          .append(sole.getOrDefault(each.getKey(), 0)).append(" | ").append(pct(each.getValue(), correct)).append(" |\n");

    final Map<String, Integer> combinations = new TreeMap<>();
    for (final Set<String> set : needs)
      combinations.merge(String.join(" + ", set), 1, Integer::sum);
    final List<Map.Entry<String, Integer>> byCombination = new ArrayList<>(combinations.entrySet());
    byCombination.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
    out.append("\n## The constructs each of those statements needs together\n\n| constructs | statements |\n|---|---|\n");
    for (final Map.Entry<String, Integer> each : byCombination)
      out.append("| ").append(each.getKey()).append(" | ").append(each.getValue()).append(" |\n");

    out.append("\n## Greedy plan: what to build first\n\n");
    out.append("| step | build | statements it completes | accepted after the step | share of ").append(correct)
        .append(" correct |\n|---|---|---|---|---|\n");
    final Set<String> chosen = new TreeSet<>();
    final boolean[] done = new boolean[needs.size()];
    long unlocked = 0;
    int step = 0;
    out.append("| 0 | (today) | - | ").append(accepted).append(" | ").append(pct(accepted, correct)).append(" |\n");
    while (unlocked < needs.size()) {
      String best = null;
      int bestGain = -1;
      int bestPresence = -1;
      for (final String candidate : present.keySet()) {
        if (chosen.contains(candidate))
          continue;
        final Set<String> trial = new TreeSet<>(chosen);
        trial.add(candidate);
        int gain = 0;
        for (int i = 0; i < needs.size(); i++)
          if (!done[i] && trial.containsAll(needs.get(i)))
            gain++;
        final int appears = present.get(candidate);
        if (gain > bestGain || (gain == bestGain && appears > bestPresence)) {
          best = candidate;
          bestGain = gain;
          bestPresence = appears;
        }
      }
      if (best == null)
        break;
      chosen.add(best);
      for (int i = 0; i < needs.size(); i++)
        if (!done[i] && chosen.containsAll(needs.get(i))) {
          done[i] = true;
          unlocked++;
        }
      out.append("| ").append(++step).append(" | ").append(best).append(" | ").append(bestGain).append(" | ")
          .append(accepted + unlocked).append(" | ").append(pct(accepted + unlocked, correct)).append(" |\n");
    }
  }
}
