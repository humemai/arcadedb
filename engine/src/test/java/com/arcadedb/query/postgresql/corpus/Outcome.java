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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a statement did on one side: it answered with a {@link ResultTable}, or it was refused with a SQLSTATE. The {@code
 * expected} column of {@code answers.tsv} is the JSON of one of these, as read from the real server.
 */
public final class Outcome {
  public final ResultTable table;
  /** The SQLSTATE of the refusal, {@code null} when the statement answered. */
  public final String      sqlState;
  /** The message of the refusal, for a report; it is not compared and not stored in the corpus. */
  public final String      message;

  private Outcome(final ResultTable table, final String sqlState, final String message) {
    this.table = table;
    this.sqlState = sqlState;
    this.message = message;
  }

  public static Outcome answer(final ResultTable table) {
    return new Outcome(table, null, null);
  }

  public static Outcome refused(final String sqlState, final String message) {
    return new Outcome(null, sqlState, message);
  }

  public boolean isRefusal() {
    return table == null;
  }

  /** The text of the {@code expected} column. */
  public String toJson() {
    if (table != null)
      return table.toJson();
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("error", sqlState);
    return Json.write(map);
  }

  public static Outcome fromJson(final String json) {
    final Object parsed = Json.parse(json);
    if (parsed instanceof Map<?, ?> map && map.containsKey("error"))
      return refused(String.valueOf(map.get("error")), null);
    return answer(ResultTable.fromJson(json));
  }

  /** A short description for a report line. */
  public String describe() {
    if (table != null)
      return table.preview();
    return "refused " + sqlState + (message != null ? " (" + firstLine(message) + ")" : "");
  }

  private static String firstLine(final String text) {
    final int end = text.indexOf('\n');
    final String line = end < 0 ? text : text.substring(0, end);
    return line.length() > 120 ? line.substring(0, 120) + "..." : line;
  }
}
