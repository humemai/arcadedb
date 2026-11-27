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
 * @param onDelete what a delete of the record at the end does with the link: delete it, or be refused
 * @param onUpdate what a change of the key does: be refused ({@code NO ACTION}), or show on the link ({@code CASCADE})
 */
public record EndKey(String table, String property, Table.Action onDelete, Table.Action onUpdate) {

  /** The record at the end of the link: {@code near} when it is that record, else the record read from the database. */
  public Vertex endOf(final Edge edge, final Column column, final Vertex near) {
    final boolean source = column.kind() == Column.Kind.SOURCE;
    final RID rid = source ? edge.getOut() : edge.getIn();
    if (near != null && rid.equals(near.getIdentity()))
      return near;
    try {
      return source ? edge.getOutVertex() : edge.getInVertex();
    } catch (final RecordNotFoundException gone) {
      throw noValue(edge, column, rid, false);
    }
  }

  /**
   * The value of the key in the record at an end. A link that another language wrote can end at a record of another table, or at
   * one without a value for the key: the column has no true value then, and neither {@code NULL} nor the value of another table's
   * property would be one, so the read is refused.
   */
  public Object read(final Edge edge, final Column column, final Vertex end) {
    final boolean row = end.getType().instanceOf(table);
    final Object value = row ? end.get(property) : null;
    if (value == null)
      throw noValue(edge, column, end.getIdentity(), row);
    return value;
  }

  /**
   * The refusal of a read of an end without a value, {@code 23503}. It names the link, and its hint ends with the statement that
   * repairs it, as another language has to write it.
   *
   * @param row whether the record at the end is a row of the table, which lacks only the value of the key
   */
  private PostgreSQLEvaluationException noValue(final Edge edge, final Column column, final RID end, final boolean row) {
    final RID link = edge.getIdentity();
    final String delete;
    if (link.getPosition() >= 0)
      delete = "delete the link in ArcadeDB sql: {sql}DELETE FROM " + link;
    else
      // a lightweight edge is no record: it is named by its two ends
      delete = "delete the link in openCypher: {opencypher}MATCH (a)-[r:`" + edge.getTypeName() + "`]->(b) WHERE ID(a) = '"
          + edge.getOut() + "' AND ID(b) = '" + edge.getIn() + "' DELETE r";
    final String hint = row ?
        "Give the record a value for the key in ArcadeDB sql, UPDATE " + end + " SET `" + property + "` = <a new value>, or "
            + delete :
        "The record cannot have a key of \"" + table + "\": " + delete;
    return PostgreSQLEvaluationException.withHint("23503", "the link " + link + " of table \"" + edge.getTypeName()
        + "\" has no value for " + column.name() + ": the record " + end + " at that end is no row of \"" + table
        + "\" with a value in \"" + property + "\" (another language wrote it)", hint);
  }
}
