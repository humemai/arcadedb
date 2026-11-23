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
import com.arcadedb.database.MutableDocument;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The {@code small} fixture of the PostgreSQL-style SQL prototype (fork issue #2), built in Java so that every step of the
 * prototype tests against the same database: five people, three companies, five {@code works_at} edges (Ada has two parallel
 * edges to Acme, Ed and Cedar have none) and six orders (one without a buyer). All names are lower case.
 * <p>
 * {@link #createSchema(Database)} declares every property a statement reads (the relational view shows declared properties
 * only), the unique indexes on {@code person.pid}, {@code company.cid} and {@code orders.oid} (none on {@code orders.pid}), and
 * the endpoint tables of {@code works_at} as {@link EdgeEndpoints} reads them. {@link #load(Database)} adds the rows.
 * <p>
 * Public, so that the tests of the sub-packages ({@code parser}, {@code catalog}, {@code plan}) use the same database.
 */
public final class SmallFixture {
  public static final String PERSON   = "person";
  public static final String COMPANY  = "company";
  public static final String WORKS_AT = "works_at";
  public static final String ORDERS   = "orders";

  private SmallFixture() {
  }

  public static void createSchema(final Database database) {
    createSchema(database, true);
  }

  /**
   * The schema of {@link #createSchema(Database)}, optionally without the three unique indexes: the {@code small-noindex}
   * variant of fork issue #9, where a join of {@code orders} to {@code person} on {@code pid} has no index behind it.
   *
   * @param indexes whether to create the unique indexes on {@code person.pid}, {@code company.cid} and {@code orders.oid}
   */
  public static void createSchema(final Database database, final boolean indexes) {
    final Schema schema = database.getSchema();

    final VertexType person = schema.createVertexType(PERSON);
    person.createProperty("pid", Type.INTEGER);
    person.createProperty("name", Type.STRING);
    person.createProperty("age", Type.INTEGER);
    person.createProperty("city", Type.STRING);
    if (indexes)
      schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, PERSON, "pid");

    final VertexType company = schema.createVertexType(COMPANY);
    company.createProperty("cid", Type.INTEGER);
    company.createProperty("name", Type.STRING);
    if (indexes)
      schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, COMPANY, "cid");

    final EdgeType worksAt = schema.createEdgeType(WORKS_AT);
    worksAt.createProperty("since", Type.INTEGER);
    worksAt.setCustomValue(EdgeEndpoints.SOURCE_KEY, PERSON);
    worksAt.setCustomValue(EdgeEndpoints.TARGET_KEY, COMPANY);

    final DocumentType orders = schema.createDocumentType(ORDERS);
    orders.createProperty("oid", Type.INTEGER);
    orders.createProperty("pid", Type.INTEGER);
    orders.createProperty("total", Type.DECIMAL);
    orders.createProperty("placed", Type.DATE);
    orders.createProperty("address", Type.MAP);
    orders.createProperty("tags", Type.LIST, "STRING");
    orders.createProperty("buyer", Type.LINK, PERSON);
    if (indexes)
      schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, ORDERS, "oid");
  }

  public static void load(final Database database) {
    database.transaction(() -> {
      final MutableVertex ada = person(database, 1, "Ada", 36, "Delft");
      final MutableVertex bo = person(database, 2, "Bo", 41, "Delft");
      final MutableVertex cy = person(database, 3, "Cy", null, "Leiden");
      final MutableVertex di = person(database, 4, "Di", 29, "Leiden");
      person(database, 5, "Ed", 52, "Utrecht");

      final MutableVertex acme = company(database, 10, "Acme");
      final MutableVertex birch = company(database, 20, "Birch");
      company(database, 30, "Cedar");

      ada.newEdge(WORKS_AT, acme, "since", 2019);
      ada.newEdge(WORKS_AT, acme, "since", 2022);
      bo.newEdge(WORKS_AT, acme, "since", 2020);
      cy.newEdge(WORKS_AT, birch, "since", 2021);
      di.newEdge(WORKS_AT, birch, "since", 2023);

      order(database, 100, ada, "25.50", LocalDate.of(2026, 1, 5), List.of("gift", "express"));
      order(database, 101, ada, "10.00", LocalDate.of(2026, 1, 7), List.of("gift"));
      order(database, 102, bo, "99.99", LocalDate.of(2026, 2, 1), List.of("express"));
      order(database, 103, di, "5.00", LocalDate.of(2026, 2, 3), List.of());
      order(database, 104, di, "7.25", LocalDate.of(2026, 3, 9), List.of("gift"));
      order(database, 105, null, "1.00", LocalDate.of(2026, 3, 10), List.of());
    });
  }

  private static MutableVertex person(final Database database, final int pid, final String name, final Integer age,
      final String city) {
    final MutableVertex vertex = database.newVertex(PERSON);
    vertex.set("pid", pid);
    vertex.set("name", name);
    if (age != null)
      vertex.set("age", age);
    vertex.set("city", city);
    vertex.save();
    return vertex;
  }

  private static MutableVertex company(final Database database, final int cid, final String name) {
    final MutableVertex vertex = database.newVertex(COMPANY);
    vertex.set("cid", cid);
    vertex.set("name", name);
    vertex.save();
    return vertex;
  }

  /**
   * An order of {@code buyer}, or of nobody: then {@code pid} and {@code buyer} are absent, which reads as {@code NULL}.
   */
  private static void order(final Database database, final int oid, final MutableVertex buyer, final String total,
      final LocalDate placed, final List<String> tags) {
    final MutableDocument order = database.newDocument(ORDERS);
    order.set("oid", oid);
    if (buyer != null) {
      order.set("pid", buyer.getInteger("pid"));
      order.set("buyer", buyer.getIdentity());
    }
    order.set("total", new BigDecimal(total));
    order.set("placed", placed);
    order.set("address", Map.of("city", buyer != null ? buyer.getString("city") : "Delft", "zip", "1000AA"));
    order.set("tags", tags);
    order.save();
  }
}
