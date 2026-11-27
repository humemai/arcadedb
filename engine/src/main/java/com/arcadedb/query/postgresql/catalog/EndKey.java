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
package com.arcadedb.query.postgresql.catalog;

import com.arcadedb.database.RID;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

/**
 * The key that an end of a link table refers to when it is not the record id (fork issue #213): the column {@code source_id} or
 * {@code target_id} then shows the value of this property of the record at that end. The engine still stores the link between the
 * two records.
 *
 * @param table    the vertex table the end refers to, as the name of its type
 * @param property the property of that table the end refers to, alone a unique key of it
 * @param cascades whether deleting the record deletes the link ({@code ON DELETE CASCADE}), or is refused ({@code NO ACTION})
 */
public record EndKey(String table, String property, boolean cascades) {

  /** The record at the end of the link: {@code near} when it is that record, else the record read from the database. */
  public Vertex endOf(final Edge edge, final Column column, final Vertex near) {
    final boolean source = column.kind() == Column.Kind.SOURCE;
    final RID rid = source ? edge.getOut() : edge.getIn();
    if (near != null && rid.equals(near.getIdentity()))
      return near;
    try {
      return source ? edge.getOutVertex() : edge.getInVertex();
    } catch (final RecordNotFoundException gone) {
      throw noValue(edge, column, rid);
    }
  }

  /**
   * The value of the key in the record at an end. A link that another language wrote can end at a record of another table, or at
   * one without a value for the key: the column has no true value then, and neither {@code NULL} nor the value of another table's
   * property would be one, so the read is refused.
   */
  public Object read(final Edge edge, final Column column, final Vertex end) {
    final Object value = end.getType().instanceOf(table) ? end.get(property) : null;
    if (value == null)
      throw noValue(edge, column, end.getIdentity());
    return value;
  }

  private PostgreSQLEvaluationException noValue(final Edge edge, final Column column, final RID end) {
    return new PostgreSQLEvaluationException("23503", "the link " + edge.getIdentity() + " of table \"" + edge.getTypeName()
        + "\" has no value for " + column.name() + ": the record " + end + " at that end is no row of \"" + table
        + "\" with a value in \"" + property + "\" (another language wrote it). Hint: in ArcadeDB sql, give that record a value or"
        + " delete the link");
  }
}
