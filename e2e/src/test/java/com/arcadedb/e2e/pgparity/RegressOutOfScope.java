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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The closed list of what is out of scope (fork issue #119), read from {@code pgregress/out-of-scope.tsv}: the only place that
 * says so. The first entry that matches a statement counts. One reason is measured, not matched: PostgreSQL answers
 * differently in two runs ({@link #TWO_RUNS}).
 */
final class RegressOutOfScope {
  static final String TWO_RUNS = "two-runs";

  private static final String CATALOG_RELATION = "@catalog-relation";
  private static final String SERVER_FUNCTION  = "@server-function";
  private static final String MEASURED         = "@two-runs";

  private static final Pattern DEFINES_SERVER_CODE = Pattern.compile(
      "^create (?:or replace )?(?:function|procedure) (?:[^ (]+ \\. )?\"?([^ \"(]+)\"? \\(.* language (?:c|internal)\\b");

  /** @param reason 1, 2 or 3: the reason of the issue */
  record Entry(String id, int reason, String pattern, String why) {
  }

  private final List<Entry>   entries;
  private final List<Pattern> patterns;

  private RegressOutOfScope(final List<Entry> entries) {
    this.entries = entries;
    this.patterns = entries.stream().map(entry -> entry.pattern().startsWith("@") ? null : Pattern.compile(entry.pattern()))
        .toList();
  }

  static RegressOutOfScope load() {
    final List<Entry> entries = new ArrayList<>();
    for (final String line : resource("/pgregress/out-of-scope.tsv").split("\n")) {
      if (line.isBlank() || line.startsWith("#") || line.startsWith("id\t"))
        continue;
      final String[] cells = line.split("\t");
      if (cells.length != 4 || !Set.of("1", "2", "3").contains(cells[1]))
        throw new IllegalStateException("An entry has an id, a reason from 1 to 3, a pattern and why: " + line);
      entries.add(new Entry(cells[0], Integer.parseInt(cells[1]), cells[2], cells[3]));
    }
    return new RegressOutOfScope(entries);
  }

  static String resource(final String name) {
    try (final InputStream in = RegressOutOfScope.class.getResourceAsStream(name)) {
      if (in == null)
        throw new IllegalStateException(name + " is missing from the test resources");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  List<Entry> entries() {
    return entries;
  }

  Entry entry(final String id) {
    return entries.stream().filter(entry -> entry.id().equals(id)).findFirst()
        .orElseThrow(() -> new IllegalStateException("No entry " + id));
  }

  /**
   * @param catalogRelations the tables and views of {@code pg_catalog}
   * @param serverFunctions  the functions that the statements so far defined with server-side C code
   *
   * @return the entry that puts the statement out of scope, {@code null} when none does
   */
  Entry match(final String sql, final Set<String> catalogRelations, final Set<String> serverFunctions) {
    final List<RegressSql.Token> tokens = RegressSql.tokens(sql);
    final String normalized = RegressSql.normalized(tokens);
    for (int i = 0; i < entries.size(); i++) {
      final boolean matches = switch (entries.get(i).pattern()) {
        case CATALOG_RELATION -> tokens.stream().anyMatch(token -> token.name() != null
            && catalogRelations.contains(token.name()));
        case SERVER_FUNCTION -> calls(tokens, serverFunctions);
        case MEASURED -> false;
        default -> patterns.get(i).matcher(normalized).find();
      };
      if (matches)
        return entries.get(i);
    }
    return null;
  }

  private static boolean calls(final List<RegressSql.Token> tokens, final Set<String> functions) {
    for (int i = 0; i + 1 < tokens.size(); i++)
      if (tokens.get(i).name() != null && functions.contains(tokens.get(i).name()) && tokens.get(i + 1).isSymbol("("))
        return true;
    return false;
  }

  /** @return the name of the function that the statement defines with server-side C code, else {@code null} */
  static String serverFunction(final String sql) {
    final Matcher matcher = DEFINES_SERVER_CODE.matcher(RegressSql.normalized(RegressSql.tokens(sql)));
    return matcher.find() ? matcher.group(1) : null;
  }
}
