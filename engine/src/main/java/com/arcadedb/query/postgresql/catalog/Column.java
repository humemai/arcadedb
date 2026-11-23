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

import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.RID;
import com.arcadedb.graph.Edge;

import java.util.Objects;

/**
 * A column of a {@link Table}: a name, a PostgreSQL type, a nullability, and the way its value is read from a record, so the
 * planner does not need to know any of the mapping.
 * <p>
 * A column is either synthetic ({@link Kind#IDENTITY}, {@link Kind#SOURCE}, {@link Kind#TARGET}: the record id and the two
 * endpoints of an edge, which are not properties) or a declared property ({@link Kind#PROPERTY}). A property that records carry
 * and the schema does not declare is not a column. A declared property is shown under its own name, except when that name is one
 * of the synthetic columns of its table ({@code id}, and {@code source_id} and {@code target_id} on an edge table): then an
 * underscore is appended until the name is free, so a property {@code id} is the column {@code id_}.
 */
public final class Column {
  public enum Kind {
    /** {@code id}: the record id as text, {@code #12:3}. */
    IDENTITY,
    /** {@code source_id}: the record id of the out vertex of an edge, read from the edge record. */
    SOURCE,
    /** {@code target_id}: the record id of the in vertex of an edge, read from the edge record. */
    TARGET,
    /** A declared property. */
    PROPERTY
  }

  public static final String ID         = "id";
  public static final String SOURCE_ID  = "source_id";
  public static final String TARGET_ID  = "target_id";

  private final String     name;
  private final ColumnType type;
  private final boolean    nullable;
  private final Kind       kind;
  private final String     property;
  private final boolean    link;

  Column(final String name, final ColumnType type, final boolean nullable, final Kind kind, final String property,
      final boolean link) {
    this.name = Objects.requireNonNull(name);
    this.type = Objects.requireNonNull(type);
    this.nullable = nullable;
    this.kind = Objects.requireNonNull(kind);
    this.property = property;
    this.link = link;
  }

  /** The name of the column as the statement refers to it. */
  public String name() {
    return name;
  }

  public ColumnType type() {
    return type;
  }

  /** False for the synthetic columns, which every row has, and for a property declared {@code NOTNULL}. */
  public boolean nullable() {
    return nullable;
  }

  public Kind kind() {
    return kind;
  }

  public boolean isSynthetic() {
    return kind != Kind.PROPERTY;
  }

  /** The name of the property behind a {@link Kind#PROPERTY} column, which differs from {@link #name()} for {@code id_}; else {@code null}. */
  public String property() {
    return property;
  }

  /** Whether the column is a {@code LINK} property: its value is the target record id as text. */
  public boolean isLink() {
    return link;
  }

  /**
   * The value of this column in a row.
   * <ul>
   * <li>{@code id}: the record id as text, {@code #12:3}. It is {@code null} for an edge that has no record (a lightweight edge in
   * an edge table that also holds records, see {@link Table.EdgeStorage#MIXED}).</li>
   * <li>{@code source_id} and {@code target_id}: the record id of the out and the in vertex as text, read from the edge, with no
   * vertex loaded.</li>
   * <li>A {@code LINK} property: the record id of the target as text.</li>
   * <li>Any other property: the value as the record holds it, {@code null} when the record does not have the property (an absent
   * property and a {@code NULL} are the same thing). Converting it to the evaluator's value space is not the catalog's job.</li>
   * </ul>
   *
   * @param record the row: an edge for an edge table
   */
  public Object read(final Document record) {
    switch (kind) {
    case IDENTITY: {
      final RID rid = record.getIdentity();
      // a lightweight edge is no record: its position is negative and is shared by every edge of the type
      return rid == null || rid.getPosition() < 0 ? null : rid.toString();
    }
    case SOURCE:
      return edge(record).getOut().toString();
    case TARGET:
      return edge(record).getIn().toString();
    default: {
      final Object value = record.get(property);
      if (link && value instanceof Identifiable identifiable)
        return identifiable.getIdentity().toString();
      return value;
    }
    }
  }

  private static Edge edge(final Document record) {
    return record instanceof Edge edge ? edge : record.asEdge();
  }

  @Override
  public String toString() {
    return name + " " + type;
  }
}
