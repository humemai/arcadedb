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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The page {@code docs/postgresql-examples.md}, read into its setup scripts and its operations for
 * {@link PostgreSQLExamplesPageTest}. The page is plain markdown, and these are its rules:
 * <ul>
 * <li>a {@code ###} heading is an operation; the operations of the sections {@code ## Transactions} and
 * {@code ## Errors a client sees} run over the wire protocol;</li>
 * <li>every code block and every table of an operation follows a line that ends with a colon and says what it is: the systems that
 * run the block, {@code ... refuses this text:}, {@code Check:}, {@code Result:} or {@code After:}, the last two with the names of
 * the systems when the table is a difference;</li>
 * <li>a line {@code <system>: no query. <why>} says that the page has no query for a system, which then needs a refused block;</li>
 * <li>the blocks of the section {@code ## The data} follow {@code ... setup, schema:} and {@code ... setup, data:};</li>
 * <li>the table of {@code ## Overview} is derived from the operations.</li>
 * </ul>
 * A statement ends with a semicolon at the end of a line. A block or a table that fits no rule is a problem, so nothing on the page
 * goes unrun.
 */
final class ExamplesPage {
  static final String FILE = "docs/postgresql-examples.md";

  private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE]-?\\d+)?");

  /** The four systems of the page. */
  enum Target {
    SQL("ArcadeDB `sql`", "`sql`", "sql"),
    POSTGRESQL("ArcadeDB `postgresql`", "`postgresql`", "postgresql"),
    POSTGRES_18("PostgreSQL 18", "PostgreSQL 18", null),
    CYPHER("ArcadeDB openCypher", "openCypher", "opencypher");

    private final String label;
    private final String shortLabel;
    private final String language;

    Target(final String label, final String shortLabel, final String language) {
      this.label = label;
      this.shortLabel = shortLabel;
      this.language = language;
    }

    String label() {
      return label;
    }

    String language() {
      return language;
    }

    static Set<Target> namedIn(final String line) {
      final Set<Target> named = EnumSet.noneOf(Target.class);
      for (final Target target : values())
        if (line.contains(target.label))
          named.add(target);
      return named;
    }
  }

  /** A table of the page, or an answer in the same shape: cells as text. */
  record Table(List<String> header, List<Map<String, String>> rows) {
    static Table state(final String sqlState) {
      return new Table(List.of("SQLSTATE"), List.of(Map.of("SQLSTATE", String.valueOf(sqlState))));
    }

    /**
     * Whether {@code observed} is this table: the same rows, in the same order when {@code ordered}, over the columns of this table.
     * A column that no observed row holds is {@code NULL} (a record without the property), a column that this table does not have is
     * a difference.
     */
    boolean matches(final Table observed, final boolean ordered) {
      if (rows.size() != observed.rows.size())
        return false;
      if (!rows.isEmpty() && !header.containsAll(observed.header))
        return false;
      final List<String> expected = new ArrayList<>(rows.stream().map(this::line).toList());
      final List<String> actual = new ArrayList<>(observed.rows.stream().map(this::line).toList());
      if (!ordered) {
        expected.sort(null);
        actual.sort(null);
      }
      return expected.equals(actual);
    }

    private String line(final Map<String, String> row) {
      return String.join(" | ", header.stream().map(column -> row.getOrDefault(column, "NULL")).toList());
    }

    String markdown() {
      if (header.isEmpty())
        return "(no rows, no columns)\n";
      final StringBuilder out = new StringBuilder("| " + String.join(" | ", header) + " |\n");
      out.append("|").append(" --- |".repeat(header.size())).append('\n');
      for (final Map<String, String> row : rows)
        out.append("| ").append(String.join(" | ", header.stream().map(c -> row.getOrDefault(c, "NULL").replace("|", "\\|")).toList()))
            .append(" |\n");
      return out.toString();
    }
  }

  /** One {@code ###} section of the page. */
  static final class Operation {
    private final String                    title;
    private final boolean                   wire;
    private final Map<Target, List<String>> statements      = new EnumMap<>(Target.class);
    private final Map<Target, List<String>> refused         = new EnumMap<>(Target.class);
    private final Map<Target, Table>        resultOverrides = new EnumMap<>(Target.class);
    private final Map<Target, Table>        afterOverrides  = new EnumMap<>(Target.class);
    private final Set<Target>               noQuery         = EnumSet.noneOf(Target.class);
    private       List<String>              check           = List.of();
    private       Table                     result;
    private       Table                     after;
    private       boolean                   sameSqlText;

    private Operation(final String title, final boolean wire) {
      this.title = title;
      this.wire = wire;
    }

    String title() {
      return title;
    }

    boolean wire() {
      return wire;
    }

    Map<Target, List<String>> statements() {
      return statements;
    }

    Map<Target, List<String>> refused() {
      return refused;
    }

    Map<Target, Table> resultOverrides() {
      return resultOverrides;
    }

    Map<Target, Table> afterOverrides() {
      return afterOverrides;
    }

    List<String> check() {
      return check;
    }

    Table result() {
      return result;
    }

    Table after() {
      return after;
    }

    /** Row order counts where the PostgreSQL 18 text has {@code ORDER BY}: in the check of a write, else in the statement. */
    boolean ordered() {
      final List<String> texts = check.isEmpty() ? statements.getOrDefault(Target.POSTGRES_18, List.of()) : check;
      return texts.stream().anyMatch(text -> text.contains("ORDER BY"));
    }
  }

  private final List<String>    arcadeSchema   = new ArrayList<>();
  private final List<String>    arcadeData     = new ArrayList<>();
  private final List<String>    postgresSchema = new ArrayList<>();
  private final List<String>    postgresData   = new ArrayList<>();
  private final List<Operation> operations     = new ArrayList<>();
  private final List<String>    problems       = new ArrayList<>();
  private       Table           overview;

  List<String> arcadeSchema() {
    return arcadeSchema;
  }

  List<String> arcadeData() {
    return arcadeData;
  }

  List<String> postgresSchema() {
    return postgresSchema;
  }

  List<String> postgresData() {
    return postgresData;
  }

  List<Operation> operations() {
    return operations;
  }

  /** What is wrong with the page itself, before anything runs. */
  List<String> problems() {
    return problems;
  }

  static ExamplesPage read() {
    for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
      final Path file = directory.resolve(FILE);
      if (Files.isRegularFile(file))
        try {
          final ExamplesPage page = new ExamplesPage();
          page.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
          page.validate();
          return page;
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
    }
    throw new IllegalStateException("No " + FILE + " above " + Path.of("").toAbsolutePath());
  }

  private void parse(final List<String> lines) {
    String section = "";
    Operation operation = null;
    String label = null;
    for (int i = 0; i < lines.size(); i++) {
      final String line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("<!--"))
        continue;
      if (line.startsWith("## ")) {
        section = line.substring(3);
        operation = null;
        label = null;
      } else if (line.startsWith("### ")) {
        operation = new Operation(line.substring(4), "Transactions".equals(section) || "Errors a client sees".equals(section));
        operations.add(operation);
        label = null;
      } else if (line.startsWith("```")) {
        final StringBuilder block = new StringBuilder();
        for (i++; i < lines.size() && !lines.get(i).startsWith("```"); i++)
          block.append(lines.get(i)).append('\n');
        block(section, operation, label, statements(block.toString(), i + 1), i + 1);
        label = null;
      } else if (line.startsWith("|")) {
        final List<String> table = new ArrayList<>();
        for (; i < lines.size() && lines.get(i).strip().startsWith("|"); i++)
          table.add(lines.get(i).strip());
        i--;
        table(section, operation, label, parseTable(table, i + 1), i + 1);
        label = null;
      } else if (operation != null && line.contains(": no query.") && Target.namedIn(line.substring(0, line.indexOf(':'))).size() == 1) {
        operation.noQuery.addAll(Target.namedIn(line.substring(0, line.indexOf(':'))));
        label = null;
      } else
        label = line.endsWith(":") ? line : null;
    }
  }

  private void block(final String section, final Operation operation, final String label, final List<String> statements,
      final int line) {
    if (label == null) {
      problems.add("line " + line + ": a code block without a line before it that says what it is");
      return;
    }
    if ("The data".equals(section) && label.contains(" setup, ")) {
      final boolean postgres = label.contains(Target.POSTGRES_18.label);
      if (label.endsWith(" setup, schema:"))
        (postgres ? postgresSchema : arcadeSchema).addAll(statements);
      else
        (postgres ? postgresData : arcadeData).addAll(statements);
      return;
    }
    if (operation == null) {
      problems.add("line " + line + ": a code block outside an operation");
      return;
    }
    if ("Check:".equals(label)) {
      if (!operation.check.isEmpty() || statements.size() != 1)
        problems.add("line " + line + ": an operation has one check of one statement");
      operation.check = statements;
      return;
    }
    final Set<Target> targets = Target.namedIn(label);
    if (targets.isEmpty()) {
      problems.add("line " + line + ": the line before the block names no system: " + label);
      return;
    }
    final boolean refusal = label.contains("refuses this text");
    if (!refusal && targets.contains(Target.SQL) && targets.contains(Target.POSTGRESQL))
      operation.sameSqlText = true;
    for (final Target target : targets) {
      if (refusal && target == Target.POSTGRES_18)
        problems.add("line " + line + ": a refused block is for an ArcadeDB language");
      if ((refusal ? operation.refused : operation.statements).put(target, statements) != null)
        problems.add("line " + line + ": a second block for " + target.label);
    }
  }

  private void table(final String section, final Operation operation, final String label, final Table table, final int line) {
    if ("Overview".equals(section) && operation == null) {
      overview = table;
      return;
    }
    if (operation == null || label == null || !(label.startsWith("Result") || label.startsWith("After"))) {
      problems.add("line " + line + ": a table that is no Result and no After of an operation");
      return;
    }
    final boolean result = label.startsWith("Result");
    final Set<Target> targets = Target.namedIn(label);
    if (targets.isEmpty()) {
      if ((result ? operation.result : operation.after) != null)
        problems.add("line " + line + ": a second common table");
      if (result)
        operation.result = table;
      else
        operation.after = table;
    } else {
      if (targets.contains(Target.POSTGRES_18))
        problems.add("line " + line + ": the common table is the answer of PostgreSQL 18, it cannot differ from itself");
      for (final Target target : targets)
        (result ? operation.resultOverrides : operation.afterOverrides).put(target, table);
    }
  }

  private List<String> statements(final String block, final int line) {
    final List<String> statements = new ArrayList<>();
    final StringBuilder current = new StringBuilder();
    for (final String text : block.split("\n")) {
      current.append(current.isEmpty() ? "" : "\n").append(text);
      if (text.stripTrailing().endsWith(";")) {
        final String statement = current.toString().strip();
        statements.add(statement.substring(0, statement.length() - 1));
        current.setLength(0);
      }
    }
    if (!current.toString().isBlank() || statements.isEmpty())
      problems.add("line " + line + ": a block whose last statement does not end with a semicolon");
    return statements;
  }

  private Table parseTable(final List<String> lines, final int line) {
    final List<String> header = cells(lines.getFirst());
    final List<Map<String, String>> rows = new ArrayList<>();
    for (int i = 2; i < lines.size(); i++) {
      final List<String> cells = cells(lines.get(i));
      if (cells.size() != header.size()) {
        problems.add("line " + line + ": a table row with " + cells.size() + " cells under " + header.size() + " columns");
        continue;
      }
      final Map<String, String> row = new LinkedHashMap<>();
      for (int c = 0; c < cells.size(); c++)
        row.put(header.get(c), NUMBER.matcher(cells.get(c)).matches() && !"SQLSTATE".equals(header.get(c))
            ? number(new BigDecimal(cells.get(c))) : cells.get(c));
      rows.add(row);
    }
    return new Table(header, rows);
  }

  private static List<String> cells(final String line) {
    final String inner = line.substring(1, line.length() - 1);
    return Arrays.stream(inner.split("(?<!\\\\)\\|", -1)).map(cell -> cell.strip().replace("\\|", "|")).toList();
  }

  /** A number as the tables of the page write it: no trailing zeros, at most six decimals. */
  static String number(final BigDecimal value) {
    final BigDecimal rounded = value.scale() > 6 ? value.setScale(6, RoundingMode.HALF_UP) : value;
    return rounded.signum() == 0 ? "0" : rounded.stripTrailingZeros().toPlainString();
  }

  static boolean looksLikeAValue(final String text) {
    return NUMBER.matcher(text).matches() || List.of("null", "true", "false").contains(text.toLowerCase(Locale.ROOT));
  }

  private void validate() {
    if (arcadeSchema.isEmpty() || arcadeData.isEmpty() || postgresSchema.isEmpty() || postgresData.isEmpty())
      problems.add("the section The data needs a schema block and a data block for ArcadeDB and for PostgreSQL 18");
    final List<Map<String, String>> derived = new ArrayList<>();
    final List<String> columns = List.of("Operation", "`sql` and `postgresql` text", "Answers differently", "No query in");
    for (final Operation operation : operations) {
      final String where = "[" + operation.title + "] ";
      for (final Target target : Target.values()) {
        if (operation.statements.containsKey(target) == operation.noQuery.contains(target))
          problems.add(where + target.label + " needs either a block or a line that says there is no query");
        if (operation.noQuery.contains(target) && !operation.refused.containsKey(target))
          problems.add(where + target.label + " has no query, so it needs a block that it refuses");
      }
      if (!operation.statements.containsKey(Target.POSTGRES_18))
        problems.add(where + "PostgreSQL 18 is the reference of every operation");
      final List<List<String>> blocks = operation.statements.values().stream().toList();
      for (int i = 0; i < blocks.size(); i++)
        for (int j = i + 1; j < blocks.size(); j++)
          if (blocks.get(i) != blocks.get(j) && blocks.get(i).equals(blocks.get(j)))
            problems.add(where + "two blocks with the same text: name their systems on one line");
      if (operation.check.isEmpty() ? operation.result == null || operation.after != null : operation.after == null)
        problems.add(where + "a read needs a Result table, a write a Check and an After table");
      if (operation.result == null && !operation.resultOverrides.isEmpty() || operation.after == null && !operation.afterOverrides.isEmpty())
        problems.add(where + "a difference without a common table");

      final Set<Target> different = EnumSet.noneOf(Target.class);
      different.addAll(operation.resultOverrides.keySet());
      different.addAll(operation.afterOverrides.keySet());
      final Map<String, String> row = new LinkedHashMap<>();
      row.put(columns.get(0), operation.title);
      row.put(columns.get(1), operation.sameSqlText ? "same" : operation.noQuery.contains(Target.SQL) ? "no `sql` query" : "different");
      row.put(columns.get(2), names(different));
      row.put(columns.get(3), names(operation.noQuery));
      derived.add(row);
    }
    final Table expected = new Table(columns, derived);
    if (overview == null || !overview.header().equals(columns) || !expected.matches(overview, true))
      problems.add("the table of Overview does not describe the operations. It should be:\n" + expected.markdown());
  }

  private static String names(final Set<Target> targets) {
    return targets.isEmpty() ? "none" : String.join(", ", targets.stream().map(target -> target.shortLabel).toList());
  }
}
