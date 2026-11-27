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
package com.arcadedb.query.postgresql;

import com.arcadedb.database.Database;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;

/**
 * The {@code generated} fixture of fork issue #78: the three kinds of table that have a column the engine generates, with the
 * column names of the {@code id} rule: the record id of every type is {@code rid}. {@code gen_note} (vertex) declares its own
 * {@code id}, its primary key; {@code gen_tag} (vertex) has none, so its record id is its primary key; {@code gen_link} (edge,
 * note to tag) has {@code rid} and the endpoints {@code source_id} and {@code target_id}. The PostgreSQL side is {@code generated-columns.sql}: the same
 * names with real {@code GENERATED ALWAYS AS IDENTITY} columns, which PostgreSQL has for integers only, so the corpus
 * {@code generated.tsv} reads the flags of the columns and never their type or a value. No rows.
 */
public final class GeneratedColumnsFixture {
  public static final String NOTE = "gen_note";
  public static final String TAG  = "gen_tag";
  public static final String LINK = "gen_link";

  private GeneratedColumnsFixture() {
  }

  public static void createSchema(final Database database) {
    final Schema schema = database.getSchema();

    final VertexType note = schema.createVertexType(NOTE);
    note.createProperty("id", Type.LONG);
    note.createProperty("body", Type.STRING);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, NOTE, "id");

    final VertexType tag = schema.createVertexType(TAG);
    tag.createProperty("label", Type.STRING);

    final EdgeType link = schema.createEdgeType(LINK);
    link.createProperty("since", Type.INTEGER);
    link.setCustomValue(EdgeEndpoints.SOURCE_KEY, NOTE);
    link.setCustomValue(EdgeEndpoints.TARGET_KEY, TAG);
  }
}
