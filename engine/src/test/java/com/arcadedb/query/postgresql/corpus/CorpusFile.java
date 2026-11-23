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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A corpus file of the PostgreSQL-style SQL prototype: tab separated values with a header of comment lines.
 * <ul>
 *   <li>A line that starts with {@code #} is a header line. The comment lines come first and are kept in order.
 *   {@code # server:} and {@code # collation:} lines are the version string and the collation of the real server that produced
 *   the {@code expected} column, written by the generator mode of the harness.</li>
 *   <li>The first other line names the columns, separated by one TAB.</li>
 *   <li>Every following non-empty line is one case with as many cells as there are columns.</li>
 *   <li>A statement cell escapes a backslash, a tab, a line feed and a carriage return as {@code \\}, {@code \t}, {@code \n} and
 *   {@code \r} (see {@link #escape(String)}). The {@code expected} cell is stored as it is: it never holds a raw tab or line
 *   break.</li>
 * </ul>
 * The file is plain text so that other repositories (the benchmark) can read the same cases.
 */
public final class CorpusFile {
  public final String       name;
  /** The header lines, each with its leading {@code #}. */
  public final List<String> comments = new ArrayList<>();
  public final List<String> columns;
  public final List<String[]> rows = new ArrayList<>();

  private CorpusFile(final String name, final List<String> columns) {
    this.name = name;
    this.columns = columns;
  }

  public static CorpusFile read(final Path path) throws IOException {
    return parse(path.getFileName().toString(), Files.readString(path, StandardCharsets.UTF_8));
  }

  /**
   * @param resource a classpath resource such as {@code /postgresql/corpus/syntax.tsv}
   */
  public static CorpusFile readResource(final String resource) throws IOException {
    try (final InputStream in = CorpusFile.class.getResourceAsStream(resource)) {
      if (in == null)
        throw new IOException("No resource " + resource + " on the classpath");
      return parse(resource, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  public static CorpusFile parse(final String name, final String text) {
    final List<String> comments = new ArrayList<>();
    List<String> columns = null;
    final List<String[]> rows = new ArrayList<>();
    int lineNumber = 0;
    for (final String line : text.split("\n", -1)) {
      ++lineNumber;
      if (line.isEmpty())
        continue;
      if (line.startsWith("#")) {
        comments.add(line);
        continue;
      }
      final String[] cells = line.split("\t", -1);
      if (columns == null) {
        columns = Arrays.asList(cells);
        continue;
      }
      if (cells.length != columns.size())
        throw new IllegalArgumentException(
            name + " line " + lineNumber + " has " + cells.length + " cells, the header has " + columns.size());
      rows.add(cells);
    }
    if (columns == null)
      throw new IllegalArgumentException(name + " has no column header");
    final CorpusFile file = new CorpusFile(name, columns);
    file.comments.addAll(comments);
    file.rows.addAll(rows);
    return file;
  }

  public int column(final String columnName) {
    final int index = columns.indexOf(columnName);
    if (index < 0)
      throw new IllegalArgumentException(name + " has no column " + columnName + ": " + columns);
    return index;
  }

  /**
   * @return the value of the header line {@code # key: value}, or {@code null}
   */
  public String headerValue(final String key) {
    final String prefix = "# " + key + ":";
    for (final String comment : comments)
      if (comment.startsWith(prefix))
        return comment.substring(prefix.length()).strip();
    return null;
  }

  /** Replaces the header line {@code # key: ...}, or adds it after the last header line. */
  public void setHeaderValue(final String key, final String value) {
    final String prefix = "# " + key + ":";
    final String line = prefix + " " + value;
    for (int i = 0; i < comments.size(); i++)
      if (comments.get(i).startsWith(prefix)) {
        comments.set(i, line);
        return;
      }
    comments.add(line);
  }

  public String render() {
    final StringBuilder out = new StringBuilder();
    for (final String comment : comments)
      out.append(comment).append('\n');
    out.append(String.join("\t", columns)).append('\n');
    for (final String[] row : rows)
      out.append(String.join("\t", row)).append('\n');
    return out.toString();
  }

  public void write(final Path path) throws IOException {
    Files.writeString(path, render(), StandardCharsets.UTF_8);
  }

  public static String escape(final String statement) {
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < statement.length(); i++) {
      final char c = statement.charAt(i);
      switch (c) {
      case '\\' -> out.append("\\\\");
      case '\t' -> out.append("\\t");
      case '\n' -> out.append("\\n");
      case '\r' -> out.append("\\r");
      default -> out.append(c);
      }
    }
    return out.toString();
  }

  public static String unescape(final String cell) {
    final StringBuilder out = new StringBuilder();
    for (int i = 0; i < cell.length(); i++) {
      final char c = cell.charAt(i);
      if (c != '\\' || i + 1 >= cell.length()) {
        out.append(c);
        continue;
      }
      final char next = cell.charAt(++i);
      switch (next) {
      case 't' -> out.append('\t');
      case 'n' -> out.append('\n');
      case 'r' -> out.append('\r');
      case '\\' -> out.append('\\');
      default -> out.append('\\').append(next);
      }
    }
    return out.toString();
  }
}
