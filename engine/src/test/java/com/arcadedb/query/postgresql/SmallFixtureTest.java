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

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.Identifiable;
import com.arcadedb.graph.Vertex;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code small} fixture builds: the types with their declared properties, the unique indexes, the declared endpoints of the
 * edge type, and the rows of fork issue #2, including the loader check {@code (5, 158, 29, 52)}.
 */
class SmallFixtureTest extends TestHelper {

  @Test
  void theSchemaDeclaresTheTypesPropertiesIndexesAndEdgeEndpoints() {
    SmallFixture.createSchema(database);
    final Schema schema = database.getSchema();

    final DocumentType person = schema.getType(SmallFixture.PERSON);
    assertThat(person.getPropertyNames()).containsExactlyInAnyOrder("pid", "name", "age", "city");
    assertThat(person.getProperty("pid").getType()).isEqualTo(Type.INTEGER);
    assertThat(person.getProperty("name").getType()).isEqualTo(Type.STRING);
    assertThat(person.getProperty("age").getType()).isEqualTo(Type.INTEGER);

    final DocumentType company = schema.getType(SmallFixture.COMPANY);
    assertThat(company.getPropertyNames()).containsExactlyInAnyOrder("cid", "name");

    final DocumentType orders = schema.getType(SmallFixture.ORDERS);
    assertThat(orders.getPropertyNames()).containsExactlyInAnyOrder("oid", "pid", "total", "placed", "address", "tags", "buyer");
    assertThat(orders.getProperty("total").getType()).isEqualTo(Type.DECIMAL);
    assertThat(orders.getProperty("placed").getType()).isEqualTo(Type.DATE);
    assertThat(orders.getProperty("address").getType()).isEqualTo(Type.MAP);
    assertThat(orders.getProperty("tags").getType()).isEqualTo(Type.LIST);
    assertThat(orders.getProperty("tags").getOfType()).isEqualTo("STRING");
    assertThat(orders.getProperty("buyer").getType()).isEqualTo(Type.LINK);
    assertThat(orders.getProperty("buyer").getOfType()).isEqualTo(SmallFixture.PERSON);

    final DocumentType worksAt = schema.getType(SmallFixture.WORKS_AT);
    assertThat(worksAt).isInstanceOf(EdgeType.class);
    assertThat(worksAt.getPropertyNames()).containsExactly("since");
    assertThat(EdgeEndpoints.sourceTable((EdgeType) worksAt)).isEqualTo("person");
    assertThat(EdgeEndpoints.targetTable((EdgeType) worksAt)).isEqualTo("company");
  }

  @Test
  void theUniqueIndexesAreOnPidCidAndOidAndNotOnOrdersPid() {
    SmallFixture.createSchema(database);
    final Schema schema = database.getSchema();

    assertThat(indexedProperties(schema.getType(SmallFixture.PERSON))).containsExactly(List.of("pid"));
    assertThat(indexedProperties(schema.getType(SmallFixture.COMPANY))).containsExactly(List.of("cid"));
    assertThat(indexedProperties(schema.getType(SmallFixture.ORDERS))).containsExactly(List.of("oid"));
    assertThat(indexedProperties(schema.getType(SmallFixture.WORKS_AT))).isEmpty();
    for (final String type : List.of(SmallFixture.PERSON, SmallFixture.COMPANY, SmallFixture.ORDERS))
      for (final TypeIndex index : schema.getType(type).getAllIndexes(true))
        assertThat(index.isUnique()).as(type).isTrue();
  }

  @Test
  void theLoadedRowsAreTheRowsOfIssue2() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);

    assertThat(database.countType(SmallFixture.PERSON, false)).isEqualTo(5);
    assertThat(database.countType(SmallFixture.COMPANY, false)).isEqualTo(3);
    assertThat(database.countType(SmallFixture.WORKS_AT, false)).isEqualTo(5);
    assertThat(database.countType(SmallFixture.ORDERS, false)).isEqualTo(6);

    // the loader check of the parity harness: count(*), sum(age), min(age), max(age) of person
    try (final ResultSet resultSet = database.query("sql",
        "SELECT count(*) AS n, sum(age) AS total, min(age) AS lo, max(age) AS hi FROM person")) {
      final Result row = resultSet.next();
      assertThat(row.<Number>getProperty("n").longValue()).isEqualTo(5);
      assertThat(row.<Number>getProperty("total").longValue()).isEqualTo(158);
      assertThat(row.<Number>getProperty("lo").longValue()).isEqualTo(29);
      assertThat(row.<Number>getProperty("hi").longValue()).isEqualTo(52);
    }
  }

  @Test
  void theEdgesAreTheParallelAndTheUnmatchedOnesTheIssueAsksFor() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);

    // Ada has two parallel edges to Acme, Ed has none
    assertThat(vertex("person", "pid", 1).countEdges(Vertex.DIRECTION.OUT, SmallFixture.WORKS_AT)).isEqualTo(2);
    assertThat(vertex("person", "pid", 5).countEdges(Vertex.DIRECTION.OUT, SmallFixture.WORKS_AT)).isZero();
    // Acme has three, Birch two, Cedar none
    assertThat(vertex("company", "cid", 10).countEdges(Vertex.DIRECTION.IN, SmallFixture.WORKS_AT)).isEqualTo(3);
    assertThat(vertex("company", "cid", 20).countEdges(Vertex.DIRECTION.IN, SmallFixture.WORKS_AT)).isEqualTo(2);
    assertThat(vertex("company", "cid", 30).countEdges(Vertex.DIRECTION.IN, SmallFixture.WORKS_AT)).isZero();
  }

  @Test
  void theOrdersCarryLinksAndNullsAsTheIssueAsksFor() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);

    // Cy has no age: an absent property, which reads as NULL
    assertThat(vertex("person", "pid", 3).getInteger("age")).isNull();

    // order 100 is Ada's: its buyer is her record, and it has no index on pid to find it by
    final Document ada = vertex("person", "pid", 1);
    final Document order100 = row("orders", "oid", 100);
    assertThat(order100.getInteger("pid")).isEqualTo(1);
    assertThat(((Identifiable) order100.get("buyer")).getIdentity()).isEqualTo(ada.getIdentity());
    assertThat(order100.getDecimal("total")).isEqualByComparingTo("25.50");
    assertThat(order100.getLocalDate("placed")).isEqualTo("2026-01-05");

    // order 105 has no buyer
    final Document order105 = row("orders", "oid", 105);
    assertThat(order105.getInteger("pid")).isNull();
    assertThat(order105.get("buyer")).isNull();
  }

  private static List<List<String>> indexedProperties(final DocumentType type) {
    return type.getAllIndexes(true).stream().map(TypeIndex::getPropertyNames).toList();
  }

  private Vertex vertex(final String type, final String key, final Object value) {
    return row(type, key, value).asVertex();
  }

  private Document row(final String type, final String key, final Object value) {
    try (final ResultSet resultSet = database.query("sql", "SELECT FROM " + type + " WHERE " + key + " = ?", value)) {
      final Document document = resultSet.next().getElement().get();
      assertThat(resultSet.hasNext()).as("one " + type + " with " + key + " = " + value).isFalse();
      return document;
    }
  }
}
