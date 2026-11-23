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

import com.arcadedb.exception.CommandParsingException;
import com.arcadedb.exception.CommandSQLParsingException;
import com.arcadedb.query.postgresql.corpus.Corpus;
import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultComparator;
import com.arcadedb.query.postgresql.corpus.ResultTable;
import com.arcadedb.query.postgresql.corpus.Values;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The capability matrix of the PostgreSQL-style SQL prototype: the statements of {@code answers.tsv}, unchanged, run in three
 * places on the same loaded data (a real PostgreSQL 18, the {@code postgresql} language of the engine, ArcadeDB's own
 * {@code sql} language), and for each statement group how many statements each language answers like PostgreSQL 18, refuses or
 * fails on, and answers with different rows. It is a report: no count of it fails a build.
 * <p>
 * <b>The outcomes.</b> For the {@code postgresql} language: {@code SAME} (equal under the corpus comparison, which includes the
 * SQLSTATE of two refusals), {@code DIFFERENT} (it answered, and not like the server), {@code REFUSED} (SQLSTATE {@code 0A000}
 * for a statement that the server answers) and {@code ERROR} (any other refusal or a failure). For ArcadeDB SQL: {@code SAME}
 * (the same cells as PostgreSQL 18, under the rules below), {@code DIFFERENT} (rows came back and they are not the server's:
 * a silent wrong answer) and {@code ERROR} (an exception, or the statement names a column that ArcadeDB's model does not have),
 * with an error class.
 * <p>
 * <b>Comparison of ArcadeDB SQL.</b> Cells only: column labels and types are not compared (ArcadeDB SQL names an unnamed
 * expression by its text, and rows are maps), cells are normalised and compared as the corpus does ({@link Values}), the rows as
 * a sequence when the statement has a total {@code ORDER BY} and as a multiset otherwise. Columns are read by name from a map
 * row: when both sides have the same set of unique labels they are matched by label, otherwise by position. A {@code SELECT *}
 * on ArcadeDB SQL has no {@code id}, {@code source_id} or {@code target_id} column (the relational view of the prototype adds
 * them, the records have {@code @rid}, {@code @out} and {@code @in}): those columns of the server's answer are left out of the
 * comparison of a {@code SELECT *}, and the statement is listed as such. For a statement that PostgreSQL refuses (an {@code err-}
 * id), {@code SAME} means ArcadeDB SQL refuses it too (a SQLSTATE is not compared: ArcadeDB SQL has none) and {@code DIFFERENT}
 * means it answers rows where PostgreSQL refuses.
 * <p>
 * <b>Model columns.</b> A statement that names {@code id}, {@code source_id} or {@code target_id} cannot mean the same in ArcadeDB
 * SQL: if it raises an exception that is the exception's class, and if it answers (ArcadeDB SQL reads a property that does not
 * exist as {@code NULL}) and the rows differ, it is counted as {@code ERROR} of the class {@link #MODEL}, not as a wrong answer,
 * and listed with what it answered.
 * <p>
 * <b>The expressible view.</b> A second count over the statements that ArcadeDB SQL has the syntax for, by a rule on the text of
 * the statement ({@link #expressible(String)}): no {@code JOIN}, no second relation in the {@code FROM} list, columns qualified
 * with the names of at most one relation, no {@code IN (SELECT ...)}, no {@code NULLS FIRST} or {@code NULLS LAST}, no
 * {@code ILIKE}, no {@code ::} cast, no window function ({@code OVER (}), no {@code WITH}, no {@code information_schema} or
 * {@code pg_catalog}. It separates "cannot say it" from "says it and gets another answer".
 */
final class CapabilityMatrix {
  /** The columns that the relational view of the prototype has and ArcadeDB's records do not. */
  static final Set<String> MODEL_COLUMNS = Set.of("id", "source_id", "target_id");

  static final String SYNTAX      = "syntax error";
  static final String UNKNOWN     = "unknown table or column";
  static final String UNSUPPORTED = "unsupported construct";
  static final String RUNTIME     = "runtime error";
  static final String MODEL       = "unknown column (id, source_id or target_id is not in ArcadeDB's model)";
  static final List<String> ERROR_CLASSES = List.of(SYNTAX, UNKNOWN, UNSUPPORTED, RUNTIME, MODEL);

  private static final List<String> GROUP_ORDER = List.of("demo", "st", "join", "err", "is", "tpch");

  private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
  private static final Pattern MODEL_NAME     = Pattern.compile("\\b(id|source_id|target_id)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern QUALIFIER      = Pattern.compile("\\b([A-Za-z_]\\w*)\\.[A-Za-z_]\\w*");
  private static final Pattern SELECT_STAR    = Pattern.compile("^\\s*SELECT\\s+(DISTINCT\\s+)?\\*\\s+FROM\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern NOT_EXPRESSIBLE = Pattern.compile("\\bJOIN\\b|\\bIN\\s*\\(\\s*SELECT\\b|\\bNULLS\\s+(FIRST|LAST)\\b|"
      + "\\bILIKE\\b|::|\\bOVER\\s*\\(|^\\s*WITH\\b|\\b(information_schema|pg_catalog)\\.", Pattern.CASE_INSENSITIVE);
  private static final Set<String> FROM_END = Set.of("WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "INTERSECT",
      "EXCEPT", "WINDOW", "FETCH");

  enum Prototype {SAME, DIFFERENT, REFUSED, ERROR}

  enum Own {SAME, DIFFERENT, ERROR}

  /** The three outcomes of one statement and what is needed to explain them. */
  static final class Entry {
    String    id;
    String    group;
    String    statement;
    boolean   expressible;
    boolean   pgAnswers;
    String    pgOutcome;
    Prototype prototype;
    String    prototypeDetail = "";
    Own       own;
    /** One of {@link #ERROR_CLASSES} when {@code own} is {@code ERROR}, else {@code null}. */
    String    ownClass;
    /** The exception of ArcadeDB SQL as {@code Class: first line}, else empty. */
    String    ownException    = "";
    String    ownDetail       = "";
    /** ArcadeDB SQL answered rows where PostgreSQL refuses the statement. */
    boolean   permissive;
    /** Both refuse the statement, but ArcadeDB SQL only because the construct is a syntax error for it, not for the reason PostgreSQL has. */
    boolean   refusedBySyntax;
    /** ArcadeDB SQL is SAME and both answers hold no row: the weakest kind of SAME. */
    boolean   emptyBoth;
    /** The comparison left out the server's {@code id}, {@code source_id} or {@code target_id} column of a {@code SELECT *}. */
    boolean   modelColumnsIgnored;
  }

  record Comparison(String difference, boolean modelColumnsIgnored) {
  }

  private final List<Entry> entries = new ArrayList<>();

  /**
   * @param pg        what the real server did
   * @param prototype what the {@code postgresql} language did
   * @param own       what ArcadeDB's {@code sql} language did
   */
  void add(final Corpus.AnswerCase c, final Outcome pg, final Outcome prototype, final ArcadeSide.NativeRun own) {
    entries.add(classify(c, pg, prototype, own));
  }

  int size() {
    return entries.size();
  }

  // ---------------------------------------------------------------------------------------------------------------- classify

  static Entry classify(final Corpus.AnswerCase c, final Outcome pg, final Outcome prototype, final ArcadeSide.NativeRun own) {
    final Entry e = new Entry();
    e.id = c.id();
    e.group = group(c.id());
    e.statement = c.statement();
    final String masked = maskLiterals(e.statement);
    e.expressible = expressible(e.statement);
    e.pgAnswers = !pg.isRefusal();
    e.pgOutcome = pg.isRefusal() ? "refuses " + pg.sqlState : "answers " + pg.table.rows.size() + (pg.table.rows.size() == 1 ? " row" : " rows");

    // the postgresql language
    final String difference = ResultComparator.compare(pg, prototype, c.ordered(), c.tolerance());
    if (difference == null)
      e.prototype = Prototype.SAME;
    else if (prototype.isRefusal()) {
      e.prototypeDetail = "SQLSTATE " + prototype.sqlState + ": " + firstLine(prototype.message);
      e.prototype = !pg.isRefusal() && "0A000".equals(prototype.sqlState) ? Prototype.REFUSED : Prototype.ERROR;
    } else {
      e.prototype = Prototype.DIFFERENT;
      e.prototypeDetail = difference;
    }

    // ArcadeDB's own sql language
    if (own.error() != null) {
      e.ownException = describeError(own.error());
      if (pg.isRefusal()) {
        e.own = Own.SAME;
        e.refusedBySyntax = SYNTAX.equals(errorClass(own.error()));
        e.ownDetail = "both refuse: PostgreSQL " + pg.sqlState + ", ArcadeDB SQL " + e.ownException;
      } else {
        e.own = Own.ERROR;
        e.ownClass = errorClass(own.error());
        e.ownDetail = e.ownException;
      }
      return e;
    }
    if (pg.isRefusal()) {
      e.own = Own.DIFFERENT;
      e.permissive = true;
      e.ownDetail = "answers where PostgreSQL refuses with " + pg.sqlState + ": " + own.table().preview();
      return e;
    }
    final Comparison comparison = compareRows(pg.table, own.table(), e.statement, c.ordered(), c.tolerance());
    e.modelColumnsIgnored = comparison.modelColumnsIgnored();
    if (comparison.difference() == null) {
      e.own = Own.SAME;
      e.emptyBoth = pg.table.rows.isEmpty();
      return e;
    }
    final Set<String> named = modelNames(masked);
    if (!named.isEmpty()) {
      e.own = Own.ERROR;
      e.ownClass = MODEL;
      e.ownDetail = "names " + named + ", no exception, answered: " + comparison.difference();
    } else {
      e.own = Own.DIFFERENT;
      e.ownDetail = comparison.difference();
    }
    return e;
  }

  /** The group of an id: the part before the first hyphen ({@code st-where-gt} is {@code st}). */
  static String group(final String id) {
    final int hyphen = id.indexOf('-');
    return hyphen < 0 ? id : id.substring(0, hyphen);
  }

  static String maskLiterals(final String statement) {
    return STRING_LITERAL.matcher(statement).replaceAll("''");
  }

  /** The model columns that a statement names, outside its string literals. */
  static Set<String> modelNames(final String masked) {
    final Set<String> names = new TreeSet<>();
    final Matcher matcher = MODEL_NAME.matcher(masked);
    while (matcher.find())
      names.add(matcher.group(1).toLowerCase(Locale.ROOT));
    return names;
  }

  /**
   * The rule of the expressible view, on the text of the statement alone (string literals are masked first): none of
   * {@code JOIN}, {@code IN (SELECT}, {@code NULLS FIRST/LAST}, {@code ILIKE}, {@code ::}, {@code OVER (}, a leading {@code WITH},
   * {@code information_schema.} or {@code pg_catalog.}; no second relation in the top-level {@code FROM} list; columns qualified
   * with at most one name.
   */
  static boolean expressible(final String statement) {
    final String masked = maskLiterals(statement);
    if (NOT_EXPRESSIBLE.matcher(masked).find())
      return false;
    if (hasCommaInFrom(masked))
      return false;
    final Set<String> qualifiers = new LinkedHashSet<>();
    final Matcher matcher = QUALIFIER.matcher(masked);
    while (matcher.find())
      qualifiers.add(matcher.group(1).toLowerCase(Locale.ROOT));
    return qualifiers.size() < 2;
  }

  /** A comma at parenthesis depth 0 between a top-level {@code FROM} and the next clause: a second relation. */
  private static boolean hasCommaInFrom(final String masked) {
    final String text = masked.toUpperCase(Locale.ROOT);
    int depth = 0;
    boolean inFrom = false;
    for (int i = 0; i < text.length(); i++) {
      final char ch = text.charAt(i);
      if (ch == '(')
        depth++;
      else if (ch == ')')
        depth--;
      else if (depth == 0 && Character.isLetter(ch) && (i == 0 || !(Character.isLetterOrDigit(text.charAt(i - 1)) || text.charAt(i - 1) == '_'))) {
        int end = i;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_'))
          end++;
        final String word = text.substring(i, end);
        if (word.equals("FROM"))
          inFrom = true;
        else if (inFrom && FROM_END.contains(word))
          inFrom = false;
        i = end - 1;
      } else if (depth == 0 && inFrom && ch == ',')
        return true;
    }
    return false;
  }

  /** The class of an exception of ArcadeDB SQL, read from the exception types and messages of its cause chain. */
  static String errorClass(final Throwable error) {
    final StringBuilder text = new StringBuilder();
    boolean parsing = false;
    Throwable current = error;
    for (int depth = 0; current != null && depth < 10; depth++, current = current.getCause()) {
      if (current instanceof CommandParsingException)
        parsing = true;
      text.append(current.getClass().getSimpleName().toLowerCase(Locale.ROOT)).append(' ')
          .append(String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)).append(' ');
    }
    final String all = text.toString();
    if (parsing || all.contains("syntax error") || all.contains("parseexception"))
      return SYNTAX;
    if (all.contains("not supported") || all.contains("unsupported") || all.contains("not implemented")
        || (all.contains("function") && (all.contains("not found") || all.contains("unknown"))))
      return UNSUPPORTED;
    if (all.contains("not found") || all.contains("does not exist") || all.contains("cannot find") || all.contains("no such")
        || all.contains("unknown property") || all.contains("unknown type"))
      return UNKNOWN;
    return RUNTIME;
  }

  /** {@code ExceptionClass: first line of the message}, at most about 150 characters. */
  static String describeError(final Throwable error) {
    return error.getClass().getSimpleName() + ": " + firstLine(error.getMessage());
  }

  static String firstLine(final String text) {
    if (text == null)
      return "";
    String line = text.strip();
    final int end = line.indexOf('\n');
    if (end >= 0)
      line = line.substring(0, end).strip();
    return line.length() > 150 ? line.substring(0, 150) + "..." : line;
  }

  // ---------------------------------------------------------------------------------------------------------------- compare

  /**
   * The rows of ArcadeDB SQL against the rows of the server, cells only (see the class comment). {@code difference} is
   * {@code null} when they are equal, else the first differing cell, or the first differing count.
   */
  static Comparison compareRows(final ResultTable pg, final ResultTable own, final String statement, final boolean ordered,
      final double tolerance) {
    final int pgRows = pg.rows.size();
    final int ownRows = own.rows.size();
    if (pgRows != ownRows)
      return new Comparison("row count: PostgreSQL 18 has " + pgRows + ", ArcadeDB SQL has " + ownRows, false);
    if (pgRows == 0)
      return new Comparison(null, false);

    List<List<Object>> expected = pg.rows;
    List<String> labels = pg.labels;
    boolean ignored = false;
    final int pgWidth = pg.rows.get(0).size();
    final int ownWidth = own.rows.get(0).size();
    if (pgWidth != ownWidth) {
      final List<Integer> keep = starProjection(pg.labels, own.labels, statement);
      if (keep == null)
        return new Comparison("column count: PostgreSQL 18 has " + pgWidth + " " + pg.labels + ", ArcadeDB SQL has " + ownWidth + " "
            + own.labels, false);
      expected = project(pg.rows, keep);
      labels = new ArrayList<>();
      for (final int index : keep)
        labels.add(pg.labels.get(index));
      ignored = true;
    } else {
      final List<Integer> order = sameLabelsReordered(pg.labels, own.labels);
      if (order != null) {
        expected = project(pg.rows, order);
        labels = own.labels;
      }
    }

    final String difference = ResultComparator.compare(new ResultTable(null, null, expected), new ResultTable(null, null, own.rows),
        ordered, tolerance);
    if (difference == null)
      return new Comparison(null, ignored);
    final String cell = firstDifferingCell(expected, own.rows, labels, ordered, tolerance);
    return new Comparison(cell != null ? cell : difference, ignored);
  }

  /**
   * For a {@code SELECT *}: the indexes of the server's columns that ArcadeDB SQL's columns match by label, in ArcadeDB SQL's
   * order, when every server column that is left over is a model column; else {@code null}.
   */
  private static List<Integer> starProjection(final List<String> pgLabels, final List<String> ownLabels, final String statement) {
    if (pgLabels == null || ownLabels == null || !SELECT_STAR.matcher(statement).find())
      return null;
    final Map<String, Integer> index = new HashMap<>();
    for (int i = 0; i < pgLabels.size(); i++)
      if (index.put(pgLabels.get(i), i) != null)
        return null;
    final List<Integer> keep = new ArrayList<>();
    for (final String label : ownLabels) {
      final Integer found = index.get(label);
      if (found == null || keep.contains(found))
        return null;
      keep.add(found);
    }
    for (int i = 0; i < pgLabels.size(); i++)
      if (!keep.contains(i) && !MODEL_COLUMNS.contains(pgLabels.get(i)))
        return null;
    return keep;
  }

  /** When both sides have the same unique labels in another order: the server's column indexes in ArcadeDB SQL's order. */
  private static List<Integer> sameLabelsReordered(final List<String> pgLabels, final List<String> ownLabels) {
    if (pgLabels == null || ownLabels == null || pgLabels.size() != ownLabels.size() || pgLabels.equals(ownLabels))
      return null;
    if (new LinkedHashSet<>(pgLabels).size() != pgLabels.size() || new LinkedHashSet<>(ownLabels).size() != ownLabels.size())
      return null;
    final List<Integer> order = new ArrayList<>();
    for (final String label : ownLabels) {
      final int found = pgLabels.indexOf(label);
      if (found < 0)
        return null;
      order.add(found);
    }
    return order;
  }

  private static List<List<Object>> project(final List<List<Object>> rows, final List<Integer> columns) {
    final List<List<Object>> out = new ArrayList<>(rows.size());
    for (final List<Object> row : rows) {
      final List<Object> projected = new ArrayList<>(columns.size());
      for (final int column : columns)
        projected.add(row.get(column));
      out.add(projected);
    }
    return out;
  }

  private static boolean rowEqual(final List<Object> a, final List<Object> b, final double tolerance) {
    if (a.size() != b.size())
      return false;
    for (int i = 0; i < a.size(); i++)
      if (!Values.equal(a.get(i), b.get(i), tolerance))
        return false;
    return true;
  }

  /** The first cell on which the two answers differ: in row order for an ordered statement, else the first unmatched row. */
  private static String firstDifferingCell(final List<List<Object>> expected, final List<List<Object>> actual,
      final List<String> labels, final boolean ordered, final double tolerance) {
    if (ordered) {
      for (int i = 0; i < expected.size(); i++)
        if (!rowEqual(expected.get(i), actual.get(i), tolerance))
          return cell("row " + (i + 1), expected.get(i), actual.get(i), labels, tolerance);
      return null;
    }
    final List<List<Object>> unmatched = new ArrayList<>(actual);
    for (int r = 0; r < expected.size(); r++) {
      boolean found = false;
      for (int i = 0; i < unmatched.size(); i++)
        if (rowEqual(expected.get(r), unmatched.get(i), tolerance)) {
          unmatched.remove(i);
          found = true;
          break;
        }
      if (!found)
        return cell("row " + (r + 1) + " of PostgreSQL's rows, which no row of ArcadeDB SQL equals; the nearest unmatched one",
            expected.get(r), unmatched.get(0), labels, tolerance);
    }
    return null;
  }

  private static String cell(final String where, final List<Object> expected, final List<Object> actual, final List<String> labels,
      final double tolerance) {
    for (int j = 0; j < expected.size() && j < actual.size(); j++)
      if (!Values.equal(expected.get(j), actual.get(j), tolerance))
        return where + ", column " + (j + 1) + (labels != null && j < labels.size() ? " (" + labels.get(j) + ")" : "")
            + ": PostgreSQL 18 has " + Values.render(expected.get(j)) + ", ArcadeDB SQL has " + Values.render(actual.get(j));
    return where + ": " + Values.render(expected) + " against " + Values.render(actual);
  }

  // ---------------------------------------------------------------------------------------------------------------- render

  private List<String> groups() {
    final Set<String> found = new LinkedHashSet<>();
    for (final Entry e : entries)
      found.add(e.group);
    final List<String> ordered = new ArrayList<>();
    for (final String known : GROUP_ORDER)
      if (found.remove(known))
        ordered.add(known);
    ordered.addAll(found);
    return ordered;
  }

  /** The whole report, Markdown that also reads as plain text. */
  String render(final String server, final String arcadeVersion) {
    final List<Entry> subset = new ArrayList<>();
    for (final Entry e : entries)
      if (e.expressible)
        subset.add(e);

    final StringBuilder out = new StringBuilder();
    out.append("# Capability matrix of the PostgreSQL-style SQL prototype\n\n");
    out.append("Server: ").append(server).append("\n\n");
    out.append("ArcadeDB engine: ").append(arcadeVersion).append(", embedded, the `small` fixture of the engine tests\n\n");
    out.append("Corpus: `answers.tsv`, ").append(entries.size()).append(" statements, run unchanged in three places on the same data: ")
        .append("PostgreSQL 18, the `postgresql` language, ArcadeDB's own `sql` language\n\n");
    out.append("Reading the columns. `PostgreSQL 18 answers`: statements that the server answers with rows (the others it refuses). ")
        .append("`postgresql` SAME: equal under the corpus comparison (two refusals are equal when their SQLSTATE is), REFUSED: ")
        .append("SQLSTATE `0A000` for a statement the server answers, ERROR: any other refusal or failure, DIFFERENT: answered, ")
        .append("not like the server. ArcadeDB SQL SAME: the same cells as PostgreSQL 18 (labels and types not compared, ")
        .append("see `CapabilityMatrix`), DIFFERENT: rows came back and they are not PostgreSQL's, a silent wrong answer, ERROR: an ")
        .append("exception, or a statement that names `id`, `source_id` or `target_id`, which ArcadeDB's records do not have. ")
        .append("In the `err` group PostgreSQL refuses: ArcadeDB SQL SAME means it refuses too (no SQLSTATE is compared), ")
        .append("DIFFERENT means it answers rows.\n\n");

    out.append("## View 1: every statement\n\n");
    view(out, entries, true);
    out.append("## View 2: the statements that ArcadeDB SQL has the syntax for (").append(subset.size()).append(" of ")
        .append(entries.size()).append(")\n\n");
    out.append("The rule is mechanical, on the text: no `JOIN`, no second relation in the `FROM` list, columns qualified with ")
        .append("at most one name, no `IN (SELECT`, no `NULLS FIRST` or `NULLS LAST`, no `ILIKE`, no `::` cast, no window ")
        .append("function, no `WITH`, no `information_schema` or `pg_catalog`.\n\n");
    view(out, subset, false);
    return out.toString();
  }

  private void view(final StringBuilder out, final List<Entry> view, final boolean full) {
    // the group table
    out.append("| group | statements | PostgreSQL 18 answers | postgresql SAME | postgresql DIFFERENT | postgresql REFUSED | ")
        .append("postgresql ERROR | ArcadeDB SQL SAME | ArcadeDB SQL DIFFERENT | ArcadeDB SQL ERROR |\n");
    out.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
    for (final String group : groups()) {
      final List<Entry> in = new ArrayList<>();
      for (final Entry e : view)
        if (e.group.equals(group))
          in.add(e);
      if (!in.isEmpty())
        out.append(tableRow(group, in));
    }
    out.append(tableRow("all", view)).append('\n');

    // the silent wrong answers
    final List<Entry> different = new ArrayList<>();
    for (final Entry e : view)
      if (e.own == Own.DIFFERENT)
        different.add(e);
    out.append("### ArcadeDB SQL DIFFERENT: rows came back and they are not PostgreSQL's (").append(different.size())
        .append(")\n\n");
    if (different.isEmpty())
      out.append("None.\n\n");
    for (final Entry e : different)
      out.append("- `").append(e.id).append("`: `").append(e.statement).append("`\n  ").append(e.ownDetail).append('\n');
    if (!different.isEmpty())
      out.append('\n');

    // the error classes
    final Map<String, Integer> classes = new LinkedHashMap<>();
    for (final String name : ERROR_CLASSES)
      classes.put(name, 0);
    final Map<String, int[]> exceptions = new LinkedHashMap<>();
    final Map<String, String> firstOf = new HashMap<>();
    final List<Entry> model = new ArrayList<>();
    for (final Entry e : view)
      if (e.own == Own.ERROR) {
        classes.merge(e.ownClass, 1, Integer::sum);
        if (MODEL.equals(e.ownClass))
          model.add(e);
        if (!e.ownException.isEmpty()) {
          exceptions.computeIfAbsent(e.ownException, k -> new int[1])[0]++;
          firstOf.putIfAbsent(e.ownException, e.id);
        }
      }
    out.append("### ArcadeDB SQL ERROR classes\n\n| class | statements |\n| --- | ---: |\n");
    for (final Map.Entry<String, Integer> entry : classes.entrySet())
      out.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
    out.append('\n');
    if (full) {
      out.append("The exceptions behind the classes, with the first statement of each:\n\n| exception | statements | first |\n| --- | ---: | --- |\n");
      exceptions.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0])
          .forEach(entry -> out.append("| ").append(entry.getKey().replace("|", "\\|")).append(" | ").append(entry.getValue()[0])
              .append(" | `").append(firstOf.get(entry.getKey())).append("` |\n"));
      out.append('\n');
    }
    if (!model.isEmpty()) {
      out.append("Counted as ERROR although ArcadeDB SQL raised no exception: the statement names a column that its model does not ")
          .append("have, and the rows differ (").append(model.size()).append(").\n\n");
      for (final Entry e : model)
        out.append("- `").append(e.id).append("`: `").append(e.statement).append("`\n  ").append(e.ownDetail).append('\n');
      out.append('\n');
    }

    // what a SAME can hide, and the permissive answers
    final List<String> ignored = new ArrayList<>();
    final List<String> permissive = new ArrayList<>();
    final List<String> empty = new ArrayList<>();
    final List<String> refusedBySyntax = new ArrayList<>();
    final List<String> refusedOtherwise = new ArrayList<>();
    for (final Entry e : view) {
      if (e.modelColumnsIgnored && e.own == Own.SAME)
        ignored.add(e.id);
      if (e.permissive)
        permissive.add(e.id);
      if (e.emptyBoth)
        empty.add(e.id);
      if (!e.pgAnswers && e.own == Own.SAME)
        (e.refusedBySyntax ? refusedBySyntax : refusedOtherwise).add(e.id);
    }
    out.append("Counted SAME after leaving out the `id`, `source_id` or `target_id` column of PostgreSQL's `SELECT *`: ")
        .append(ignored.size()).append(ignored.isEmpty() ? "" : " " + ignored).append("\n\n");
    out.append("Counted SAME with no row on both sides, the weakest kind of SAME: ").append(empty.size())
        .append(empty.isEmpty() ? "" : " " + empty).append("\n\n");
    out.append("Of the DIFFERENT, answered where PostgreSQL refuses the statement: ").append(permissive.size())
        .append(permissive.isEmpty() ? "" : " " + permissive).append("\n\n");
    out.append("Of the SAME where PostgreSQL refuses the statement, ArcadeDB SQL refuses it with an exception of its own: ")
        .append(refusedOtherwise.size()).append(refusedOtherwise.isEmpty() ? "" : " " + refusedOtherwise)
        .append("; only because the construct is a syntax error for ArcadeDB SQL: ").append(refusedBySyntax.size())
        .append(refusedBySyntax.isEmpty() ? "" : " " + refusedBySyntax).append("\n\n");

    // the postgresql language, anything but SAME
    final List<Entry> notSame = new ArrayList<>();
    for (final Entry e : view)
      if (e.prototype != Prototype.SAME)
        notSame.add(e);
    out.append("### postgresql language, not SAME (").append(notSame.size()).append(")\n\n");
    if (notSame.isEmpty())
      out.append("None.\n\n");
    for (final Entry e : notSame)
      out.append("- `").append(e.id).append("` ").append(e.prototype).append(": ").append(e.prototypeDetail).append('\n');
    if (!notSame.isEmpty())
      out.append('\n');
  }

  private static String tableRow(final String group, final List<Entry> in) {
    int answers = 0;
    final int[] prototype = new int[Prototype.values().length];
    final int[] own = new int[Own.values().length];
    for (final Entry e : in) {
      if (e.pgAnswers)
        answers++;
      prototype[e.prototype.ordinal()]++;
      own[e.own.ordinal()]++;
    }
    return "| " + group + " | " + in.size() + " | " + answers + " | " + prototype[Prototype.SAME.ordinal()] + " | "
        + prototype[Prototype.DIFFERENT.ordinal()] + " | " + prototype[Prototype.REFUSED.ordinal()] + " | "
        + prototype[Prototype.ERROR.ordinal()] + " | " + own[Own.SAME.ordinal()] + " | " + own[Own.DIFFERENT.ordinal()] + " | "
        + own[Own.ERROR.ordinal()] + " |\n";
  }

  /** One line per statement: all three outcomes, for a reader who wants to count another way. */
  String tsv() {
    final StringBuilder out = new StringBuilder();
    out.append("id\tgroup\texpressible\tpostgresql_18\tpostgresql_language\tpostgresql_detail\tarcadedb_sql\tarcadedb_sql_class\t")
        .append("arcadedb_sql_detail\tstatement\n");
    for (final Entry e : entries)
      out.append(e.id).append('\t').append(e.group).append('\t').append(e.expressible).append('\t').append(e.pgOutcome).append('\t')
          .append(e.prototype).append('\t').append(plain(e.prototypeDetail)).append('\t').append(e.own).append('\t')
          .append(e.ownClass == null ? "-" : e.ownClass).append('\t').append(plain(e.ownDetail)).append('\t').append(plain(e.statement))
          .append('\n');
    return out.toString();
  }

  private static String plain(final String text) {
    return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
  }

  /** Writes {@code capability-matrix.md} (the report) and {@code capability-matrix.tsv} (one line per statement). */
  void write(final Path directory, final String server, final String arcadeVersion) throws IOException {
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("capability-matrix.md"), render(server, arcadeVersion), StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("capability-matrix.tsv"), tsv(), StandardCharsets.UTF_8);
  }

  // ---------------------------------------------------------------------------------------------------------------- self-test

  private static Corpus.AnswerCase answerCase(final String id, final String order, final String statement) {
    return new Corpus.AnswerCase(new String[] { id, order, "-", "ok", statement, "pending-server" });
  }

  private static ResultTable table(final List<String> labels, final Object... cells) {
    final List<List<Object>> rows = new ArrayList<>();
    final int width = labels.size();
    for (int i = 0; i < cells.length; i += width) {
      final List<Object> row = new ArrayList<>();
      for (int j = 0; j < width; j++)
        row.add(Values.normalize(cells[i + j]));
      rows.add(row);
    }
    return new ResultTable(labels, null, rows);
  }

  /**
   * The classifier on hand-made outcomes, so that a mistake of the harness shows as a failure of the run and not as a wrong
   * count.
   *
   * @return the checks that did not hold, empty when all did
   */
  static List<String> selfTest() {
    final List<String> failures = new ArrayList<>();
    final List<String> name = List.of("name");

    // the comparison of cells
    check(failures, "equal rows", compareRows(table(name, "Ada", "Bo"), table(name, "Ada", "Bo"), "SELECT name FROM person", true, 1e-9).difference() == null);
    check(failures, "labels are not compared", compareRows(table(List.of("who"), "Ada"), table(name, "Ada"), "SELECT name AS who FROM person", true, 1e-9).difference() == null);
    check(failures, "numbers by value", compareRows(table(List.of("n"), 3), table(List.of("n"), 3.0), "SELECT 3", true, 1e-9).difference() == null);
    final String cell = compareRows(table(List.of("who", "n"), "Ada", 1, "Bo", 2), table(List.of("a", "b"), "Ada", 1, "Bo", 3), "SELECT 1", true, 1e-9).difference();
    check(failures, "first differing cell: " + cell, cell != null && cell.startsWith("row 2, column 2 (n): PostgreSQL 18 has 2, ArcadeDB SQL has 3"));
    final String count = compareRows(table(name, "Ada", "Bo"), table(name, "Ada"), "SELECT 1", true, 1e-9).difference();
    check(failures, "row count: " + count, count != null && count.startsWith("row count"));
    check(failures, "multiset ignores order", compareRows(table(name, "Ada", "Bo"), table(name, "Bo", "Ada"), "SELECT 1", false, 1e-9).difference() == null);
    check(failures, "ordered does not", compareRows(table(name, "Ada", "Bo"), table(name, "Bo", "Ada"), "SELECT 1", true, 1e-9).difference() != null);
    check(failures, "duplicate row is a difference", compareRows(table(name, "Ada", "Ada", "Bo"), table(name, "Ada", "Bo", "Bo"), "SELECT 1", false, 1e-9).difference() != null);
    check(failures, "same labels in another order", compareRows(table(List.of("a", "b"), 1, 2), table(List.of("b", "a"), 2, 1), "SELECT a, b", true, 1e-9).difference() == null);
    final Comparison star = compareRows(table(List.of("id", "cid", "name"), "#1:0", 10, "Acme"), table(List.of("cid", "name"), 10, "Acme"),
        "SELECT * FROM company ORDER BY cid", true, 1e-9);
    check(failures, "SELECT * without the id column", star.difference() == null && star.modelColumnsIgnored());
    final String lost = compareRows(table(List.of("id", "cid", "name"), "#1:0", 10, "Acme"), table(List.of("cid"), 10), "SELECT * FROM company", true, 1e-9).difference();
    check(failures, "SELECT * that loses a real column: " + lost, lost != null && lost.startsWith("column count"));
    final String notStar = compareRows(table(List.of("id", "pid"), "#1:0", 1), table(List.of("pid"), 1), "SELECT id, pid FROM person", true, 1e-9).difference();
    check(failures, "a named id column is not left out: " + notStar, notStar != null && notStar.startsWith("column count"));

    // the verdicts
    final Corpus.AnswerCase names = answerCase("st-names", "ordered", "SELECT name FROM person ORDER BY pid");
    final Outcome answer = Outcome.answer(table(name, "Ada", "Bo"));
    final Outcome refusal0A = Outcome.refused("0A000", "not supported");
    Entry e = classify(names, answer, Outcome.answer(table(name, "Ada", "Bo")), new ArcadeSide.NativeRun(table(List.of("name"), "Ada", "Bo"), null));
    check(failures, "all three SAME", e.prototype == Prototype.SAME && e.own == Own.SAME && e.pgAnswers);
    e = classify(names, answer, refusal0A, new ArcadeSide.NativeRun(table(List.of("name"), "Ada", "Cy"), null));
    check(failures, "REFUSED and DIFFERENT", e.prototype == Prototype.REFUSED && e.own == Own.DIFFERENT && e.ownDetail.startsWith("row 2, column 1 (name)"));
    e = classify(names, answer, Outcome.refused("42703", "no column"), new ArcadeSide.NativeRun(null, new CommandSQLParsingException("Syntax error at 5")));
    check(failures, "other SQLSTATE is ERROR, a parse exception is a syntax error", e.prototype == Prototype.ERROR && e.own == Own.ERROR && SYNTAX.equals(e.ownClass));
    e = classify(names, answer, answer, new ArcadeSide.NativeRun(null, new IllegalArgumentException("Type with name 'x' was not found")));
    check(failures, "unknown type", UNKNOWN.equals(e.ownClass));
    e = classify(names, answer, answer, new ArcadeSide.NativeRun(null, new ArithmeticException("/ by zero")));
    check(failures, "runtime error", RUNTIME.equals(e.ownClass));
    e = classify(names, answer, answer, new ArcadeSide.NativeRun(null, new UnsupportedOperationException("x")));
    check(failures, "unsupported", UNSUPPORTED.equals(e.ownClass));
    final Corpus.AnswerCase err = answerCase("err-unknown-column", "multiset", "SELECT nosuch FROM person");
    final Outcome refused = Outcome.refused("42703", "column does not exist");
    e = classify(err, refused, Outcome.refused("42703", "x"), new ArcadeSide.NativeRun(null, new IllegalStateException("boom")));
    check(failures, "both refuse is SAME", e.own == Own.SAME && e.prototype == Prototype.SAME && !e.pgAnswers && e.ownClass == null
        && !e.refusedBySyntax);
    e = classify(err, refused, Outcome.refused("42703", "x"), new ArcadeSide.NativeRun(null, new CommandSQLParsingException("Syntax error")));
    check(failures, "both refuse, ArcadeDB SQL by a syntax error", e.own == Own.SAME && e.refusedBySyntax);
    e = classify(names, Outcome.answer(table(name)), Outcome.answer(table(name)), new ArcadeSide.NativeRun(table(name), null));
    check(failures, "SAME with no row on both sides", e.own == Own.SAME && e.emptyBoth);
    e = classify(err, refused, Outcome.refused("0A000", "x"), new ArcadeSide.NativeRun(table(List.of("nosuch"), (Object) null), null));
    check(failures, "answers where PostgreSQL refuses is DIFFERENT", e.own == Own.DIFFERENT && e.permissive && e.prototype == Prototype.ERROR);
    final Corpus.AnswerCase id = answerCase("st-person-id", "ordered", "SELECT id, pid FROM person ORDER BY pid");
    e = classify(id, Outcome.answer(table(List.of("id", "pid"), "#1:0", 1)), Outcome.answer(table(List.of("id", "pid"), "#1:0", 1)),
        new ArcadeSide.NativeRun(table(List.of("id", "pid"), (Object) null, 1), null));
    check(failures, "a named id answered as NULL is a model ERROR, not a wrong answer", e.own == Own.ERROR && MODEL.equals(e.ownClass));
    final Corpus.AnswerCase literal = answerCase("st-literal", "multiset", "SELECT 'id' AS tag");
    check(failures, "a string literal does not name a column", modelNames(maskLiterals(literal.statement())).isEmpty());

    // the expressible view
    check(failures, "plain select", expressible("SELECT name FROM person WHERE age > 30 ORDER BY pid LIMIT 2"));
    check(failures, "aggregate", expressible("SELECT city, count(*) AS n FROM person GROUP BY city ORDER BY city"));
    check(failures, "one qualifier is fine", expressible("SELECT p.name FROM person p WHERE p.age > 30"));
    check(failures, "a decimal is not a qualifier", expressible("SELECT 7.0 / 2 AS x"));
    check(failures, "a function FROM is not a second relation", expressible("SELECT oid, extract(month FROM placed) AS m FROM orders ORDER BY oid"));
    check(failures, "JOIN", !expressible("SELECT p.name FROM orders o JOIN person p ON o.pid = p.pid"));
    check(failures, "comma join", !expressible("SELECT name FROM person, company"));
    check(failures, "two qualifiers", !expressible("SELECT p.name, c.name FROM person p WHERE c.x = 1"));
    check(failures, "NULLS LAST", !expressible("SELECT name FROM person ORDER BY age NULLS LAST"));
    check(failures, "cast", !expressible("SELECT age::text FROM person"));
    check(failures, "IN subquery", !expressible("SELECT name FROM person WHERE pid IN (SELECT pid FROM orders)"));
    check(failures, "ILIKE", !expressible("SELECT name FROM person WHERE name ILIKE 'a%'"));
    check(failures, "window", !expressible("SELECT row_number() OVER (ORDER BY pid) FROM person"));
    check(failures, "CTE", !expressible("WITH t AS (SELECT 1) SELECT * FROM t"));
    check(failures, "information_schema", !expressible("SELECT table_name FROM information_schema.tables"));
    check(failures, "keywords inside a literal do not count", expressible("SELECT 'a JOIN b ::x' AS s"));
    return failures;
  }

  private static void check(final List<String> failures, final String what, final boolean holds) {
    if (!holds)
      failures.add(what);
  }
}
