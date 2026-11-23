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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a write did on one side: the answer of the statement (the rows of its {@code RETURNING}, or the number of rows it changed
 * as one row of the column {@code count}, or its SQLSTATE), and what each check of the case read afterwards. The {@code expected}
 * cell of {@code writes.tsv} is the JSON of one of these, as read from the real server.
 */
public final class WriteOutcome {
  public final Outcome       result;
  public final List<Outcome> after;

  public WriteOutcome(final Outcome result, final List<Outcome> after) {
    this.result = result;
    this.after = List.copyOf(after);
  }

  public String toJson() {
    final StringBuilder out = new StringBuilder("{\"result\":").append(result.toJson()).append(",\"after\":[");
    for (int i = 0; i < after.size(); i++)
      out.append(i > 0 ? "," : "").append(after.get(i).toJson());
    return out.append("]}").toString();
  }

  public static WriteOutcome fromJson(final String json) {
    final Object parsed = Json.parse(json);
    if (!(parsed instanceof Map<?, ?> map) || !map.containsKey("result") || !(map.get("after") instanceof List<?> list))
      throw new IllegalArgumentException("Not the JSON of a write outcome: " + json);
    final List<Outcome> after = new ArrayList<>();
    for (final Object item : list)
      after.add(Outcome.fromJson(Json.write(item)));
    return new WriteOutcome(Outcome.fromJson(Json.write(map.get("result"))), after);
  }

  /**
   * @return why the two differ, or {@code null} when they agree: the statement's answer and every table after it, as multisets
   */
  public static String compare(final WriteOutcome expected, final WriteOutcome actual, final double tolerance) {
    final String difference = ResultComparator.compare(expected.result, actual.result, false, tolerance);
    if (difference != null)
      return "the answer of the statement: " + difference;
    if (expected.after.size() != actual.after.size())
      return "the number of checks differs: " + expected.after.size() + " and " + actual.after.size();
    for (int i = 0; i < expected.after.size(); i++) {
      final String check = ResultComparator.compare(expected.after.get(i), actual.after.get(i), false, tolerance);
      if (check != null)
        return "the table read by check " + (i + 1) + " afterwards: " + check;
    }
    return null;
  }
}
