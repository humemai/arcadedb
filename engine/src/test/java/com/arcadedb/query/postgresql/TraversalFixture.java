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
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;

/**
 * The tiny fixture of the traversal rule (fork issue #8): the shapes of edges that the {@code small} fixture does not have,
 * next to the two cases that it does ("a join over two parallel edges returns two rows" and "a row with no edge comes back
 * once with {@code NULL}"), which are repeated here so that this fixture alone reads as the specification of the rule.
 *
 * <ul>
 * <li>Five people: Ann, Ben, Cat, Dan, and Eve, who is an {@code employee} (a subtype of {@code person}). Eve and Dan have no
 * {@code works_at} edge. Three companies: Acme, Birch, and Cedar, which no edge points to.</li>
 * <li>{@code works_at}, a regular edge type with the endpoint tables {@code person} and {@code company}: Ann has two parallel
 * edges to Acme (2019 and 2022), Ben one to Acme (2020), and Cat one to Birch (2021) of the subtype {@code works_at_remote}, so
 * that {@code works_at} reads it and {@code ONLY works_at} does not.</li>
 * <li>{@code knows}, a lightweight edge type from {@code person} to {@code person}: Ann to Ben, Ann to Cat, Cat to Cat (a loop),
 * and Dan to Ann. A lightweight edge has no record, no {@code id} and no property.</li>
 * <li>{@code follows}, a unidirectional edge type from {@code person} to {@code person}: it stores the outgoing side only. Ann to
 * Ben twice (weights 1 and 3, parallel) and Ben to Ann (weight 2).</li>
 * </ul>
 * Public, so that tests of any sub-package of the prototype use the same database.
 */
public final class TraversalFixture {
  private TraversalFixture() {
  }

  public static void createSchema(final Database database) {
    final Schema schema = database.getSchema();

    final VertexType person = schema.createVertexType("person");
    person.createProperty("pid", Type.INTEGER);
    person.createProperty("name", Type.STRING);
    final VertexType employee = schema.buildVertexType().withName("employee").withSuperType("person").create();
    employee.createProperty("grade", Type.INTEGER);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "person", "pid");

    final VertexType company = schema.createVertexType("company");
    company.createProperty("cid", Type.INTEGER);
    company.createProperty("name", Type.STRING);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "company", "cid");

    final EdgeType worksAt = schema.createEdgeType("works_at");
    worksAt.createProperty("since", Type.INTEGER);
    worksAt.setCustomValue(EdgeEndpoints.SOURCE_KEY, "person");
    worksAt.setCustomValue(EdgeEndpoints.TARGET_KEY, "company");
    schema.buildEdgeType().withName("works_at_remote").withSuperType("works_at").create();

    final EdgeType knows = schema.buildEdgeType().withName("knows").withLightweight(true).create();
    knows.setCustomValue(EdgeEndpoints.SOURCE_KEY, "person");
    knows.setCustomValue(EdgeEndpoints.TARGET_KEY, "person");

    final EdgeType follows = schema.buildEdgeType().withName("follows").withBidirectional(false).create();
    follows.createProperty("weight", Type.INTEGER);
    follows.setCustomValue(EdgeEndpoints.SOURCE_KEY, "person");
    follows.setCustomValue(EdgeEndpoints.TARGET_KEY, "person");
  }

  public static void load(final Database database) {
    database.transaction(() -> {
      final MutableVertex ann = person(database, "person", 1, "Ann");
      final MutableVertex ben = person(database, "person", 2, "Ben");
      final MutableVertex cat = person(database, "person", 3, "Cat");
      final MutableVertex dan = person(database, "person", 4, "Dan");
      person(database, "employee", 5, "Eve");

      final MutableVertex acme = company(database, 10, "Acme");
      final MutableVertex birch = company(database, 20, "Birch");
      company(database, 30, "Cedar");

      ann.newEdge("works_at", acme, "since", 2019);
      ann.newEdge("works_at", acme, "since", 2022);
      ben.newEdge("works_at", acme, "since", 2020);
      cat.newEdge("works_at_remote", birch, "since", 2021);

      ann.newEdge("knows", ben);
      ann.newEdge("knows", cat);
      cat.newEdge("knows", cat);
      dan.newEdge("knows", ann);

      ann.newEdge("follows", ben, "weight", 1);
      ann.newEdge("follows", ben, "weight", 3);
      ben.newEdge("follows", ann, "weight", 2);
    });
  }

  private static MutableVertex person(final Database database, final String type, final int pid, final String name) {
    final MutableVertex vertex = database.newVertex(type);
    vertex.set("pid", pid);
    vertex.set("name", name);
    vertex.save();
    return vertex;
  }

  private static MutableVertex company(final Database database, final int cid, final String name) {
    final MutableVertex vertex = database.newVertex("company");
    vertex.set("cid", cid);
    vertex.set("name", name);
    vertex.save();
    return vertex;
  }
}
