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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The sort keys of a query, so that rows which tie on them are accepted in any order (fork issues #119 and #51). A key that is
 * a column of the answer is read from the answer. Any other key is asked of PostgreSQL, with the statement widened by it. When
 * neither works, every row is its own group: the order has to be the same.
 */
final class RegressOrder {
  private static final Set<String> QUERIES = Set.of("select", "with", "values", "table");
  private static final Set<String> AFTER_ORDER = Set.of("limit", "offset", "fetch", "for");
  private static final Set<String> AFTER_SELECT_LIST = Set.of("where", "group", "having", "window", "order", "limit", "offset",
      "fetch", "for");

  private final String                         sql;
  private final List<RegressSql.Token>         tokens;
  /** The text of each sort key, without its direction. */
  private final List<List<RegressSql.Token>>   keys;

  private RegressOrder(final String sql, final List<RegressSql.Token> tokens, final List<List<RegressSql.Token>> keys) {
    this.sql = sql;
    this.tokens = tokens;
    this.keys = keys;
  }

  /** @return the order of the outermost query of the statement, {@code null} when it has none */
  static RegressOrder of(final String sql) {
    final List<RegressSql.Token> tokens = RegressSql.tokens(sql);
    if (!QUERIES.contains(RegressSql.firstWord(tokens)))
      return null;
    int by = -1;
    for (int i = 0; i + 1 < tokens.size(); i++)
      if (tokens.get(i).depth() == 0 && tokens.get(i).is("order") && tokens.get(i + 1).is("by"))
        by = i + 1;
    if (by < 0)
      return null;
    final List<List<RegressSql.Token>> keys = new ArrayList<>();
    List<RegressSql.Token> current = new ArrayList<>();
    for (int i = by + 1; i < tokens.size(); i++) {
      final RegressSql.Token token = tokens.get(i);
      if (token.depth() == 0 && (token.isSymbol(";") || token.type() == RegressSql.Type.WORD
          && AFTER_ORDER.contains(token.name())))
        break;
      if (token.depth() == 0 && token.isSymbol(",")) {
        keys.add(withoutDirection(current));
        current = new ArrayList<>();
      } else
        current.add(token);
    }
    keys.add(withoutDirection(current));
    keys.removeIf(List::isEmpty);
    return keys.isEmpty() ? null : new RegressOrder(sql, tokens, keys);
  }

  private static List<RegressSql.Token> withoutDirection(final List<RegressSql.Token> key) {
    int end = key.size();
    for (int i = 0; i < key.size(); i++)
      if (key.get(i).depth() == 0 && key.get(i).is("using")) {
        end = i;
        break;
      }
    if (end >= 2 && key.get(end - 2).is("nulls") && (key.get(end - 1).is("first") || key.get(end - 1).is("last")))
      end -= 2;
    if (end >= 1 && (key.get(end - 1).is("asc") || key.get(end - 1).is("desc")))
      end--;
    return key.subList(0, end);
  }

  /** The column of the answer that is the key, {@code -1} when none is. */
  private static int column(final List<RegressSql.Token> key, final List<String> labels) {
    if (key.size() != 1)
      return -1;
    final RegressSql.Token token = key.get(0);
    if (token.type() == RegressSql.Type.NUMBER) {
      final int position = token.text().chars().allMatch(Character::isDigit) ? Integer.parseInt(token.text()) : 0;
      return position >= 1 && position <= labels.size() ? position - 1 : -1;
    }
    final String name = token.name();
    return name != null && Collections.frequency(labels, name) == 1 ? labels.indexOf(name) : -1;
  }

  /**
   * The values of the sort keys of each row of the answer.
   *
   * @param labels the columns of the answer
   * @param rows   the rows of the answer
   * @param peek   the answer of another query on the same data, {@code null} when it is refused
   */
  List<List<Object>> keys(final List<String> labels, final List<List<Object>> rows,
      final Function<String, RegressSubject.Answer> peek) {
    final List<Integer> columns = new ArrayList<>(keys.size());
    for (final List<RegressSql.Token> key : keys)
      columns.add(column(key, labels));
    if (!columns.contains(-1))
      return rows.stream().map(row -> columns.stream().map(row::get).toList()).map(RegressOrder::asObjects).toList();

    final String widened = widened(columns);
    final RegressSubject.Answer wider = widened == null ? null : peek.apply(widened);
    if (wider == null || wider.columns() == null || wider.rows().size() != rows.size())
      return rows;
    final List<List<Object>> values = new ArrayList<>(rows.size());
    for (final List<Object> row : wider.rows()) {
      final List<Object> of = new ArrayList<>(columns.size());
      int extra = labels.size();
      for (final int column : columns)
        of.add(column >= 0 ? row.get(column) : row.get(extra++));
      values.add(of);
    }
    // the groups of the widened answer count only when each holds the rows of the answer at the same places
    final RegressSubject.Answer narrow = new RegressSubject.Answer(labels, Collections.nCopies(labels.size(), ""),
        wider.rows().stream().map(row -> row.subList(0, labels.size())).map(RegressOrder::asObjects).toList(), -1, null, null,
        false);
    final RegressSubject.Answer original = new RegressSubject.Answer(labels, Collections.nCopies(labels.size(), ""), rows, -1,
        null, null, false);
    return RegressJudge.answers(original, narrow, values) == null ? values : rows;
  }

  private static List<Object> asObjects(final List<Object> list) {
    return list;
  }

  /** The statement with the keys that are no column added to its select list, {@code null} when they cannot be. */
  private String widened(final List<Integer> columns) {
    int select = -1;
    for (int i = 0; i < tokens.size(); i++) {
      final RegressSql.Token token = tokens.get(i);
      if (token.depth() != 0)
        continue;
      if (token.is("union") || token.is("intersect") || token.is("except"))
        return null;
      if (select < 0 && token.is("select"))
        select = i;
    }
    if (select < 0 || select + 1 >= tokens.size() || tokens.get(select + 1).is("distinct"))
      return null;
    for (int i = select + 1; i < tokens.size(); i++) {
      final RegressSql.Token token = tokens.get(i);
      if (token.depth() != 0 || token.type() != RegressSql.Type.WORD)
        continue;
      final boolean from = token.is("from") && !tokens.get(i - 1).is("distinct");
      if (!from && !AFTER_SELECT_LIST.contains(token.name()))
        continue;
      final StringBuilder added = new StringBuilder();
      for (int key = 0; key < keys.size(); key++)
        if (columns.get(key) < 0) {
          final List<RegressSql.Token> of = keys.get(key);
          added.append(", ").append(sql, of.get(0).start(), of.get(of.size() - 1).end()).append(" AS pgregress_key").append(key);
        }
      return sql.substring(0, token.start()) + added + " " + sql.substring(token.start());
    }
    return null;
  }
}
