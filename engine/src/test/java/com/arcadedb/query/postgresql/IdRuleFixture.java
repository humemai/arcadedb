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
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;

/**
 * The {@code idrule} fixture of fork issues #38 and #240: one type for each case of the rule that says what the columns
 * {@code id} and {@code rid} are.
 * <ul>
 * <li>{@code account} (vertex) declares its own {@code id} with a unique index: {@code id} is the primary key, the record id is
 * {@code rid}, a unique key.</li>
 * <li>{@code post} (document) declares its own {@code id} too, and two {@code LINK} properties, {@code author} to {@code account}
 * and {@code topic} to a type that has no {@code id} of its own.</li>
 * <li>{@code topic} (vertex) has no property {@code id}: it has no column {@code id}, and its record id {@code rid} is the primary
 * key.</li>
 * <li>{@code follows} (edge, account to account) and {@code interested} (edge, account to topic) have no property {@code id}; their
 * {@code source_id} and {@code target_id} hold record ids and refer to {@code account.rid} and to {@code topic.rid}.</li>
 * <li>{@code memo} (document) declares {@code id} and nothing keeps it unique, so the record id {@code rid} is the primary
 * key and two rows share an {@code id}.</li>
 * <li>{@code badge} (document) declares a property {@code rid} and no {@code id}: the property keeps its name and the record id has
 * no column.</li>
 * <li>{@code twin} (document) declares both {@code id} and {@code rid}: both names are the user's and the record id has no column.</li>
 * </ul>
 * The PostgreSQL side of the parity harness is generated from the relational view of this database ({@code SmallSchemaSql}), so
 * the two sides cannot drift, and the corpus {@code idrule.tsv} is recorded from the real server on it. All names are lower case.
 */
public final class IdRuleFixture {
  public static final String ACCOUNT    = "account";
  public static final String POST       = "post";
  public static final String TOPIC      = "topic";
  public static final String FOLLOWS    = "follows";
  public static final String INTERESTED = "interested";
  public static final String MEMO       = "memo";
  public static final String BADGE      = "badge";
  public static final String TWIN       = "twin";

  private IdRuleFixture() {
  }

  public static void createSchema(final Database database) {
    final Schema schema = database.getSchema();

    final VertexType account = schema.createVertexType(ACCOUNT);
    account.createProperty("id", Type.LONG);
    account.createProperty("name", Type.STRING);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, ACCOUNT, "id");

    final VertexType topic = schema.createVertexType(TOPIC);
    topic.createProperty("label", Type.STRING);

    final EdgeType follows = schema.createEdgeType(FOLLOWS);
    follows.createProperty("since", Type.INTEGER);
    follows.setCustomValue(EdgeEndpoints.SOURCE_KEY, ACCOUNT);
    follows.setCustomValue(EdgeEndpoints.TARGET_KEY, ACCOUNT);

    final EdgeType interested = schema.createEdgeType(INTERESTED);
    interested.setCustomValue(EdgeEndpoints.SOURCE_KEY, ACCOUNT);
    interested.setCustomValue(EdgeEndpoints.TARGET_KEY, TOPIC);

    final DocumentType post = schema.createDocumentType(POST);
    post.createProperty("id", Type.INTEGER);
    post.createProperty("title", Type.STRING);
    post.createProperty("author", Type.LINK, ACCOUNT);
    post.createProperty("topic", Type.LINK, TOPIC);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, POST, "id");

    final DocumentType memo = schema.createDocumentType(MEMO);
    memo.createProperty("id", Type.STRING);
    memo.createProperty("body", Type.STRING);

    final DocumentType badge = schema.createDocumentType(BADGE);
    badge.createProperty("rid", Type.STRING);
    badge.createProperty("color", Type.STRING);

    final DocumentType twin = schema.createDocumentType(TWIN);
    twin.createProperty("id", Type.INTEGER);
    twin.createProperty("rid", Type.STRING);
    twin.createProperty("note", Type.STRING);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, TWIN, "id");
  }

  /** Three accounts (ids 11 to 13), two topics, four posts, three {@code follows}, three {@code interested}, and the small types. */
  public static void load(final Database database) {
    database.transaction(() -> {
      final MutableVertex ana = account(database, 11, "Ana");
      final MutableVertex bo = account(database, 12, "Bo");
      final MutableVertex cy = account(database, 13, "Cy");
      final MutableVertex db = topic(database, "db");
      final MutableVertex sql = topic(database, "sql");

      ana.newEdge(FOLLOWS, bo, "since", 2020);
      ana.newEdge(FOLLOWS, cy, "since", 2021);
      bo.newEdge(FOLLOWS, cy, "since", 2022);
      ana.newEdge(INTERESTED, db);
      bo.newEdge(INTERESTED, db);
      bo.newEdge(INTERESTED, sql);

      post(database, 1, "p1", ana, db);
      post(database, 2, "p2", ana, sql);
      post(database, 3, "p3", bo, null);
      post(database, 4, "p4", null, null);

      database.newDocument(MEMO).set("id", "a").set("body", "one").save();
      database.newDocument(MEMO).set("id", "a").set("body", "two").save();
      database.newDocument(BADGE).set("rid", "r1").set("color", "red").save();
      database.newDocument(BADGE).set("rid", "r2").set("color", "blue").save();
      database.newDocument(TWIN).set("id", 1).set("rid", "x").set("note", "n1").save();
      database.newDocument(TWIN).set("id", 2).set("rid", "y").set("note", "n2").save();
    });
  }

  private static MutableVertex account(final Database database, final long id, final String name) {
    return database.newVertex(ACCOUNT).set("id", id).set("name", name).save();
  }

  private static MutableVertex topic(final Database database, final String label) {
    return database.newVertex(TOPIC).set("label", label).save();
  }

  private static void post(final Database database, final int id, final String title, final MutableVertex author,
      final MutableVertex topic) {
    final var post = database.newDocument(POST).set("id", id).set("title", title);
    if (author != null)
      post.set("author", author.getIdentity());
    if (topic != null)
      post.set("topic", topic.getIdentity());
    post.save();
  }
}
