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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of {@code psql} that decides which statements reach a server (fork issue #119): variables, {@code \if},
 * {@code \gset}, {@code \gexec}, {@code \g} and its relatives, {@code \bind}, {@code \connect}, {@code \copy} and
 * {@code \quit}. Every other backslash command is counted and does nothing here: it is not a statement.
 */
final class RegressPsql {
  /** What the harness has to do next. */
  sealed interface Step permits Send, Connect, ClientCopy {
  }

  /**
   * A statement that reaches the server.
   *
   * @param key        stable across two runs of the same file: the line and the number of the statement on that line
   * @param parameters the values of {@code \bind}, {@code null} for a statement of the simple protocol
   * @param gsetPrefix not {@code null} when {@code \gset} stores the answer in variables
   * @param gexec      true when {@code \gexec} runs every cell of the answer as a statement
   */
  record Send(String key, int line, String sql, List<String> parameters, String gsetPrefix, boolean gexec) implements Step {
  }

  /** {@code \connect}: a new session, as {@code user} when one is given. */
  record Connect(String user) implements Step {
  }

  /** {@code \copy ... from}: data that the client loads. It is no statement and is not judged. */
  record ClientCopy(String sql, String file, String data) implements Step {
  }

  private record Branch(boolean parentActive, boolean active, boolean taken) {
  }

  private static final Pattern COPY_FROM = Pattern.compile("(?is)^(.*?)\\s+from\\s+(stdin|'(?:[^']|'')*'|\\S+)(.*)$");

  private final RegressScript        script;
  private final Map<String, String>  variables   = new HashMap<>();
  private final Map<String, String>  environment;
  private final Deque<Branch>        branches    = new ArrayDeque<>();
  private final Map<Integer, Integer> onLine     = new HashMap<>();
  private       List<String>         parameters;
  private       String               lastSql;
  private       boolean              quit;
  /** The statements that reach the server, those of {@code \gexec} included. */
  int     sent;
  /** The backslash commands of the file, counted apart from the statements. */
  int     commands;
  /** The statements that {@code psql} does not send: in a branch of {@code \if} that is not taken, or after {@code \quit}. */
  int     skipped;
  boolean standardStrings = true;

  RegressPsql(final String source, final Map<String, String> environment) {
    this.script = new RegressScript(source);
    this.environment = environment;
    variables.put("DBNAME", "regression");
    variables.put("USER", "postgres");
    variables.put("ENCODING", "UTF8");
    variables.put("VERSION_NUM", "180007");
    variables.put("SERVER_VERSION_NUM", "180007");
  }

  Step next() {
    while (true) {
      final RegressScript.Item item = script.next(standardStrings, variables);
      if (item == null)
        return null;
      if (item.kind() == RegressScript.Kind.STATEMENT) {
        if (quit || !active())
          skipped++;
        else
          return send(item.line(), item.text(), null, false);
      } else if (!quit) {
        final Step step = command(item);
        if (step != null)
          return step;
      }
    }
  }

  /** A statement that {@code \gexec} made out of a cell of the answer of {@code from}. */
  Send generated(final Send from, final String sql, final int number) {
    sent++;
    return new Send(from.key() + ".g" + number, from.line(), sql, null, null, false);
  }

  /** What {@code psql} keeps of an answer: the status variables, and for {@code \gset} one variable per column. */
  void answered(final Send send, final List<String> columns, final List<String[]> rows, final String sqlState) {
    variables.put("ERROR", String.valueOf(sqlState != null));
    variables.put("SQLSTATE", sqlState == null ? "00000" : sqlState);
    variables.put("ROW_COUNT", String.valueOf(rows == null ? 0 : rows.size()));
    if (send.gsetPrefix() == null || columns == null || rows == null || rows.size() != 1)
      return;
    for (int i = 0; i < columns.size(); i++) {
      final String name = send.gsetPrefix() + columns.get(i);
      if (rows.get(0)[i] == null)
        variables.remove(name);
      else
        variables.put(name, rows.get(0)[i]);
    }
  }

  /** The inline data of a {@code COPY ... FROM STDIN}, read when the server asks for it. */
  String copyData() {
    return script.copyData();
  }

  private Send send(final int line, final String sql, final String gsetPrefix, final boolean gexec) {
    final int number = onLine.merge(line, 1, Integer::sum);
    sent++;
    final Send send = new Send(line + "." + number, line, sql, parameters, gsetPrefix, gexec);
    parameters = null;
    lastSql = sql;
    return send;
  }

  private boolean active() {
    return branches.isEmpty() || branches.peek().active();
  }

  private Step command(final RegressScript.Item item) {
    commands++;
    final String text = item.text();
    int space = 0;
    while (space < text.length() && !Character.isWhitespace(text.charAt(space)))
      space++;
    final String name = text.substring(0, space);
    final String rest = text.substring(space).strip();
    switch (name) {
    case "if" -> {
      final boolean parent = active();
      final boolean condition = parent && truth(String.join("", arguments(rest)));
      branches.push(new Branch(parent, condition, condition));
      return null;
    }
    case "elif" -> {
      final Branch top = branches.poll();
      if (top != null) {
        final boolean condition = top.parentActive() && !top.taken() && truth(String.join("", arguments(rest)));
        branches.push(new Branch(top.parentActive(), condition, top.taken() || condition));
      }
      return null;
    }
    case "else" -> {
      final Branch top = branches.poll();
      if (top != null)
        branches.push(new Branch(top.parentActive(), top.parentActive() && !top.taken(), true));
      return null;
    }
    case "endif" -> {
      branches.poll();
      return null;
    }
    default -> {
    }
    }
    final boolean sends = switch (name) {
      case "g", "gx", "gset", "gexec", "crosstabview", "watch", "sendpipeline" -> true;
      default -> false;
    };
    if (!active()) {
      if (sends && script.takeBuffer() != null)
        skipped++;
      return null;
    }
    if (sends) {
      final RegressScript.Item buffered = script.takeBuffer();
      final String sql = buffered != null ? buffered.text() : lastSql;
      if (sql == null)
        return null;
      final List<String> arguments = name.equals("gset") ? arguments(rest) : List.of();
      final String prefix = name.equals("gset") ? arguments.isEmpty() ? "" : arguments.get(0) : null;
      return send(buffered != null ? buffered.line() : item.line(), sql, prefix, name.equals("gexec"));
    }
    switch (name) {
    case "set" -> {
      final List<String> arguments = arguments(rest);
      if (!arguments.isEmpty())
        variables.put(arguments.get(0), String.join("", arguments.subList(1, arguments.size())));
    }
    case "unset" -> arguments(rest).forEach(variables::remove);
    case "getenv" -> {
      final List<String> arguments = arguments(rest);
      if (arguments.size() == 2) {
        final String value = environment.get(arguments.get(1));
        if (value == null)
          variables.remove(arguments.get(0));
        else
          variables.put(arguments.get(0), value);
      }
    }
    case "bind" -> parameters = arguments(rest);
    // \gdesc describes the statement and \parse prepares it: it is not executed
    case "r", "reset", "gdesc", "parse" -> script.discard();
    case "q", "quit" -> quit = true;
    case "c", "connect" -> {
      final List<String> arguments = arguments(rest);
      return new Connect(arguments.size() > 1 && !arguments.get(1).equals("-") ? arguments.get(1) : null);
    }
    case "copy" -> {
      return clientCopy(rest);
    }
    default -> {
    }
    }
    return null;
  }

  private Step clientCopy(final String rest) {
    final Matcher matcher = COPY_FROM.matcher(rest);
    if (!matcher.matches())
      return null;
    final String from = matcher.group(2);
    final String sql = "COPY " + matcher.group(1) + " FROM STDIN" + matcher.group(3);
    if (from.equalsIgnoreCase("stdin"))
      return new ClientCopy(sql, null, script.copyData());
    final String file = from.startsWith("'") ? from.substring(1, from.length() - 1).replace("''", "'") : from;
    return new ClientCopy(sql, file, null);
  }

  private static boolean truth(final String value) {
    final String text = value.strip().toLowerCase(Locale.ROOT);
    if (text.isEmpty())
      return false;
    return "true".startsWith(text) || "yes".startsWith(text) || text.equals("on") || text.equals("1");
  }

  /** The arguments of a command: separated by blanks, with quotes and variables as {@code psql} reads them. */
  List<String> arguments(final String text) {
    final List<String> arguments = new ArrayList<>();
    final StringBuilder current = new StringBuilder();
    boolean started = false;
    int i = 0;
    while (i < text.length()) {
      final char c = text.charAt(i);
      if (Character.isWhitespace(c)) {
        if (started) {
          arguments.add(current.toString());
          current.setLength(0);
          started = false;
        }
        i++;
        continue;
      }
      started = true;
      if (c == '\'') {
        i++;
        while (i < text.length()) {
          final char inner = text.charAt(i);
          if (inner == '\\' && i + 1 < text.length()) {
            final char escaped = text.charAt(i + 1);
            current.append(escaped == 'n' ? '\n' : escaped == 't' ? '\t' : escaped == 'r' ? '\r' : escaped);
            i += 2;
          } else if (inner == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
            current.append('\'');
            i += 2;
          } else if (inner == '\'') {
            i++;
            break;
          } else {
            current.append(inner);
            i++;
          }
        }
      } else if (c == ':') {
        i = interpolate(text, i, current);
      } else {
        current.append(c);
        i++;
      }
    }
    if (started)
      arguments.add(current.toString());
    return arguments;
  }

  private int interpolate(final String text, final int from, final StringBuilder out) {
    int i = from + 1;
    final char quote = i < text.length() && (text.charAt(i) == '\'' || text.charAt(i) == '"') ? text.charAt(i) : 0;
    if (quote != 0)
      i++;
    int end = i;
    while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_'))
      end++;
    final String name = text.substring(i, end);
    final boolean closed = quote == 0 || end < text.length() && text.charAt(end) == quote;
    if (!name.isEmpty() && closed && variables.containsKey(name)) {
      out.append(quote == 0 ? variables.get(name) : RegressScript.quote(variables.get(name), quote));
      return quote == 0 ? end : end + 1;
    }
    out.append(':');
    return from + 1;
  }
}
