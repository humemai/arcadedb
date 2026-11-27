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
import com.arcadedb.schema.TypeModifier;

import java.util.Objects;

/**
 * A column of a {@link Table}: a name, a PostgreSQL type, a nullability, and the way its value is read from a record, so the
 * planner does not need to know any of the mapping.
 * <p>
 * A column is either synthetic ({@link Kind#IDENTITY}, {@link Kind#SOURCE}, {@link Kind#TARGET}: the record id and the two
 * endpoints of an edge, which are not properties) or a declared property ({@link Kind#PROPERTY}). A property that records carry
 * and the schema does not declare is not a column. A declared property is shown under its own name, except when that name is an
 * end of an edge table ({@code source_id} or {@code target_id}): then an underscore is appended until the name is free, so such a
 * property {@code source_id} is the column {@code source_id_}.
 */
public final class Column {
  public enum Kind {
    /**
     * The record id as text, {@code #12:3}: the column {@code rid} (fork issue #240). It is never written: INSERT and UPDATE
     * refuse it with SQLSTATE {@code 428C9} (fork issue #29). A property named {@code id} is the user's data and an ordinary
     * {@link #PROPERTY} column, which a write sets like any other.
     */
    IDENTITY,
    /** {@code source_id}: the record id of the out vertex of an edge, read from the edge record. */
    SOURCE,
    /** {@code target_id}: the record id of the in vertex of an edge, read from the edge record. */
    TARGET,
    /** A declared property. */
    PROPERTY
  }

  public static final String ID         = "id";
  public static final String RID        = "rid";
  public static final String SOURCE_ID  = "source_id";
  public static final String TARGET_ID  = "target_id";

  private final String     name;
  private final ColumnType type;
  private final boolean    nullable;
  private final Kind       kind;
  private final String     property;
  private final boolean    link;
  private final boolean    hidden;
  private final boolean    listed;
  private final String     defaultText;
  private final String     identity;
  /** the key an end of a link table refers to, {@code null} for the record id and for every other column */
  private final EndKey     endKey;
  /** the length of a {@code varchar(n)} or the precision and scale of a {@code numeric(p,s)}, {@code null} for none */
  private final TypeModifier modifier;

  /** The generation of an identity column that takes no value of a statement. */
  public static final String ALWAYS     = "ALWAYS";
  /** The generation of an identity column that a statement may write. */
  public static final String BY_DEFAULT = "BY DEFAULT";

  Column(final String name, final ColumnType type, final boolean nullable, final Kind kind, final String property,
      final boolean link) {
    this(name, type, nullable, kind, property, link, false, true, null, null, null, null);
  }

  private Column(final String name, final ColumnType type, final boolean nullable, final Kind kind, final String property,
      final boolean link, final boolean hidden, final boolean listed, final String defaultText, final String identity,
      final EndKey endKey, final TypeModifier modifier) {
    this.endKey = endKey;
    this.modifier = modifier;
    this.name = Objects.requireNonNull(name);
    this.type = Objects.requireNonNull(type);
    this.nullable = nullable;
    this.kind = Objects.requireNonNull(kind);
    this.property = property;
    this.link = link;
    this.hidden = hidden;
    this.listed = listed;
    this.defaultText = defaultText;
    this.identity = identity;
  }

  /**
   * A column that no table holds yet: what {@code CREATE TABLE} reads a default value for, before the type and its property
   * exist.
   */
  public static Column declaration(final String name, final ColumnType type) {
    return new Column(name, type, true, Kind.PROPERTY, name, false);
  }

  /** The same column, not nullable: a property that is the primary key of its table. */
  Column notNull() {
    return new Column(name, type, false, kind, property, link, hidden, listed, defaultText, identity, endKey, modifier);
  }

  /** The same column, hidden: see {@link #isHidden()} and, for {@code listInCatalog}, {@link #isListed()}. */
  Column hide(final boolean listInCatalog) {
    return new Column(name, type, nullable, kind, property, link, true, listInCatalog, defaultText, identity, endKey, modifier);
  }

  /** The same column with the text that {@code column_default} shows for it. */
  Column withDefaultText(final String text) {
    return new Column(name, type, nullable, kind, property, link, hidden, listed, text, identity, endKey, modifier);
  }

  /** The same column as an identity column, {@value #ALWAYS} or {@value #BY_DEFAULT} (fork issue #221). */
  Column withIdentity(final String generation) {
    return new Column(name, type, nullable, kind, property, link, hidden, listed, defaultText, generation, endKey, modifier);
  }

  /** The same column with a length, or a precision and a scale (fork issue #226). */
  Column withModifier(final TypeModifier declared) {
    return new Column(name, type, nullable, kind, property, link, hidden, listed, defaultText, identity, endKey, declared);
  }

  /**
   * The length of a {@code varchar(n)} column, or the precision and scale of a {@code numeric(p,s)} column, {@code null} when the
   * column has neither (fork issue #226).
   */
  public TypeModifier modifier() {
    return modifier;
  }

  /**
   * {@code information_schema.columns.identity_generation}: {@value #ALWAYS} for the record id, what {@code CREATE TABLE}
   * declared for an identity column, {@code null} for any other column.
   */
  public String identityGeneration() {
    return kind == Kind.IDENTITY ? ALWAYS : identity;
  }

  /** Whether {@code CREATE TABLE} declared the column {@code serial}, {@code bigserial} or {@code smallserial}. */
  public boolean isSerial() {
    return defaultText != null && defaultText.startsWith("nextval(");
  }

  /** The same end of a link table, which refers to a key and has its type. */
  Column referringTo(final EndKey key, final ColumnType keyType) {
    return new Column(name, keyType, nullable, kind, property, link, hidden, listed, defaultText, identity, key, modifier);
  }

  /**
   * The key that this end of a link table refers to (fork issue #213), {@code null} for an end that holds record ids and for every
   * other column.
   */
  public EndKey endKey() {
    return endKey;
  }

  /**
   * Whether the column is readable by its name only: not part of {@code *} or of an {@code INSERT} without a column list. It is
   * the record id of a table made by {@code CREATE TABLE} (fork issue #118): the statement did not declare it.
   */
  public boolean isHidden() {
    return hidden;
  }

  /**
   * Whether the catalog views show the column: every column but the record id of a table made by {@code CREATE TABLE} that no
   * foreign key refers to.
   */
  public boolean isListed() {
    return listed;
  }

  /** The default as {@code information_schema.columns.column_default} shows it, {@code null} when the view shows none. */
  public String defaultText() {
    return defaultText;
  }

  /** The name of the column as the statement refers to it. */
  public String name() {
    return name;
  }

  public ColumnType type() {
    return type;
  }

  /**
   * False for the synthetic columns, which every row has, for a property declared {@code NOTNULL} and for a property {@code id}
   * that is the primary key of its table (a primary key is {@code NOT NULL} in PostgreSQL, so the view says so).
   */
  public boolean nullable() {
    return nullable;
  }

  public Kind kind() {
    return kind;
  }

  public boolean isSynthetic() {
    return kind != Kind.PROPERTY;
  }

  /**
   * Whether the engine gives the value of this column and a statement of this language cannot choose it: the record id
   * ({@link Kind#IDENTITY}), and a column declared {@code GENERATED ALWAYS AS IDENTITY}. The views report both as that, and a
   * value given for one is {@code 428C9}, as PostgreSQL refuses a value for such a column. The endpoints of an edge are not
   * generated: a statement names them.
   */
  public boolean isGeneratedAlways() {
    return ALWAYS.equals(identityGeneration());
  }

  /**
   * The name of the property behind a {@link Kind#PROPERTY} column, else {@code null}. It differs from {@link #name()} only for a
   * property that has the name of an endpoint column of an edge table ({@code source_id_} shows the property {@code source_id}).
   */
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
   * <li>The identity column {@code rid}: the record id as text, {@code #12:3}. It is {@code null} for an edge that has no record
   * (a lightweight edge in an edge table that also holds records, see {@link Table.EdgeStorage#MIXED}).</li>
   * <li>{@code source_id} and {@code target_id}: the record id of the out and the in vertex as text, read from the edge, with no
   * vertex loaded. An end that refers to a key ({@link #endKey()}) is the value of that key in the record at the end, which is
   * loaded for it.</li>
   * <li>A {@code LINK} property: the record id of the target as text.</li>
   * <li>The embedded documents of a property declared {@code EMBEDDED OF T}, {@code LIST OF T} or {@code MAP OF T}: the objects
   * of their properties ({@link EmbeddedValues}).</li>
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
    case TARGET: {
      final Edge edge = edge(record);
      if (endKey != null)
        return endKey.read(edge, this, endKey.endOf(edge, this, null));
      return (kind == Kind.SOURCE ? edge.getOut() : edge.getIn()).toString();
    }
    default: {
      final Object value = record.get(property);
      if (link && value instanceof Identifiable identifiable)
        return identifiable.getIdentity().toString();
      if (type == ColumnType.JSONB && value != null)
        return EmbeddedValues.read(record.getType().getPolymorphicPropertyIfExists(property), value);
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
