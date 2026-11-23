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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One complete result: the column labels (duplicates allowed, as PostgreSQL allows them), the PostgreSQL type name of each column
 * and the rows, every cell normalised by {@link Values#normalize(Object)}.
 * <p>
 * {@code labels} and {@code types} are {@code null} when a side does not report them (the engine reports no types yet, and a
 * result without rows tells no labels): the comparison then skips that part and says so.
 */
public final class ResultTable {
  public final List<String>       labels;
  public final List<String>       types;
  public final List<List<Object>> rows;

  public ResultTable(final List<String> labels, final List<String> types, final List<List<Object>> rows) {
    this.labels = labels;
    this.types = types;
    this.rows = rows;
  }

  /** The same rows with no labels and no types: for a comparison of values only. */
  public ResultTable valuesOnly() {
    return new ResultTable(null, null, rows);
  }

  /** A copy without row {@code index}: the self-test of the comparison. */
  public ResultTable withoutRow(final int index) {
    final List<List<Object>> copy = new ArrayList<>(rows);
    copy.remove(index);
    return new ResultTable(labels, types, copy);
  }

  /** A copy with row {@code index} twice: the self-test of the comparison. */
  public ResultTable withRowDuplicated(final int index) {
    final List<List<Object>> copy = new ArrayList<>(rows);
    copy.add(index, rows.get(index));
    return new ResultTable(labels, types, copy);
  }

  public String toJson() {
    final Map<String, Object> map = new LinkedHashMap<>();
    if (labels != null)
      map.put("columns", labels);
    if (types != null)
      map.put("types", types);
    map.put("rows", rows);
    return Json.write(map);
  }

  /**
   * @param json the object written by {@link #toJson()}
   */
  @SuppressWarnings("unchecked")
  public static ResultTable fromJson(final String json) {
    final Object parsed = Json.parse(json);
    if (!(parsed instanceof Map<?, ?> map))
      throw new IllegalArgumentException("A result is a JSON object: " + json);
    final List<String> labels = strings(map.get("columns"));
    final List<String> types = strings(map.get("types"));
    final List<List<Object>> rows = new ArrayList<>();
    final Object rawRows = map.get("rows");
    if (!(rawRows instanceof List<?> list))
      throw new IllegalArgumentException("A result has a list of rows: " + json);
    for (final Object rawRow : list) {
      final List<Object> row = new ArrayList<>(((List<Object>) rawRow));
      // a float column is a double, so that the comparison allows its tolerance on both sides
      if (types != null)
        for (int i = 0; i < row.size() && i < types.size(); i++)
          if (("float4".equals(types.get(i)) || "float8".equals(types.get(i))) && row.get(i) instanceof BigDecimal decimal)
            row.set(i, decimal.doubleValue());
      rows.add(row);
    }
    return new ResultTable(labels, types, rows);
  }

  private static List<String> strings(final Object value) {
    if (value == null)
      return null;
    final List<String> strings = new ArrayList<>();
    for (final Object item : (List<?>) value)
      strings.add((String) item);
    return strings;
  }

  /** The first rows, for a message. */
  public String preview() {
    final StringBuilder out = new StringBuilder();
    if (labels != null)
      out.append(labels).append(' ');
    out.append(rows.size()).append(rows.size() == 1 ? " row" : " rows");
    for (int i = 0; i < rows.size() && i < 3; i++) {
      out.append(i == 0 ? ": " : ", ");
      out.append(Values.render(rows.get(i)));
    }
    if (rows.size() > 3)
      out.append(", ...");
    return out.toString();
  }
}
