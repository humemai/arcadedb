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
import com.arcadedb.database.Database;
import com.arcadedb.database.Identifiable;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.graph.MutableVertex;
import com.arcadedb.query.opencypher.temporal.CypherDate;
import com.arcadedb.query.opencypher.temporal.CypherLocalDateTime;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The differential test of the two views of the same records (fork issue #8 asks for the comparison with {@code MATCH}): a
 * statement of the {@code postgresql} language and an openCypher query give the same rows, as a multiset, over vertex tables,
 * edge tables, joins through an edge table, subtypes, lightweight and unidirectional edges, and the values of the property types.
 * The number of rows is asserted too, so that two empty answers do not pass. {@link PostgreSQLCypherTest} covers writes.
 * <p>
 * The two views differ by design in two places, which are asserted here as well: a document table has no node in openCypher, and
 * a lightweight edge has no {@code id} in its table, while openCypher gives it the element id that all lightweight edges of its
 * type share.
 */
class PostgreSQLCypherDifferentialTest {

  @Nested
  class SmallSchema extends TestHelper {
    @Override
    protected void beginTest() {
      SmallFixture.createSchema(database);
      SmallFixture.load(database);
    }

    @Test
    void aVertexTableHasTheNodesOfItsLabel() {
      assertSameRows(database, 5, "SELECT id, pid, name, age, city FROM person",
          "MATCH (n:person) RETURN elementId(n) AS id, n.pid AS pid, n.name AS name, n.age AS age, n.city AS city");
      assertSameRows(database, 3, "SELECT id, cid, name FROM company",
          "MATCH (n:company) RETURN elementId(n) AS id, n.cid AS cid, n.name AS name");
    }

    @Test
    void anEdgeTableHasTheRelationshipsOfItsType() {
      assertSameRows(database, 5, "SELECT id, source_id, target_id, since FROM works_at",
          "MATCH (a)-[e:works_at]->(b) RETURN elementId(e) AS id, elementId(a) AS source_id, elementId(b) AS target_id, "
              + "e.since AS since");
    }

    @Test
    void aJoinThroughTheEdgeTableIsTheMatch() {
      assertSameRows(database, 5, "SELECT p.name AS person, c.name AS company, w.since AS since FROM person p "
              + "JOIN works_at w ON w.source_id = p.id JOIN company c ON w.target_id = c.id",
          "MATCH (p:person)-[w:works_at]->(c:company) RETURN p.name AS person, c.name AS company, w.since AS since");
    }

    @Test
    void aLeftJoinThroughTheEdgeTableIsTheOptionalMatch() {
      assertSameRows(database, 6, "SELECT p.name AS person, c.name AS company, w.since AS since FROM person p "
              + "LEFT JOIN works_at w ON w.source_id = p.id LEFT JOIN company c ON w.target_id = c.id",
          "MATCH (p:person) OPTIONAL MATCH (p)-[w:works_at]->(c:company) "
              + "RETURN p.name AS person, c.name AS company, w.since AS since");
      assertSameRows(database, 6, "SELECT c.name AS company, p.name AS person FROM company c "
              + "LEFT JOIN works_at w ON w.target_id = c.id LEFT JOIN person p ON w.source_id = p.id",
          "MATCH (c:company) OPTIONAL MATCH (c)<-[w:works_at]-(p:person) RETURN c.name AS company, p.name AS person");
    }

    @Test
    void aDocumentTableHasNoNodeInCypher() {
      assertThat(rows(database, "postgresql", "SELECT oid FROM orders")).hasSize(6);
      assertThat(rows(database, "opencypher", "MATCH (o:orders) RETURN o.oid AS oid")).isEmpty();
    }
  }

  @Nested
  class SubtypesAndEdgeStorage extends TestHelper {
    @Override
    protected void beginTest() {
      TraversalFixture.createSchema(database);
      TraversalFixture.load(database);
    }

    @Test
    void aTableOfASupertypeHasTheNodesOfItsSubtypes() {
      assertSameRows(database, 5, "SELECT id, pid, name FROM person",
          "MATCH (n:person) RETURN elementId(n) AS id, n.pid AS pid, n.name AS name");
      assertSameRows(database, 4, "SELECT id, pid, name FROM ONLY person",
          "MATCH (n:person) WHERE NOT n:employee RETURN elementId(n) AS id, n.pid AS pid, n.name AS name");
      assertSameRows(database, 1, "SELECT id, pid, name, grade FROM employee",
          "MATCH (n:employee) RETURN elementId(n) AS id, n.pid AS pid, n.name AS name, n.grade AS grade");
    }

    @Test
    void anEdgeTableOfASupertypeHasTheRelationshipsOfItsSubtypes() {
      final String returned = " RETURN elementId(e) AS id, elementId(a) AS source_id, elementId(b) AS target_id, e.since AS since";
      assertSameRows(database, 4, "SELECT id, source_id, target_id, since FROM works_at",
          "MATCH (a)-[e:works_at]->(b)" + returned);
      assertSameRows(database, 3, "SELECT id, source_id, target_id, since FROM ONLY works_at",
          "MATCH (a)-[e:works_at]->(b) WHERE type(e) = 'works_at'" + returned);
      assertSameRows(database, 1, "SELECT id, source_id, target_id, since FROM works_at_remote",
          "MATCH (a)-[e:works_at_remote]->(b)" + returned);
    }

    @Test
    void aJoinThroughAnEdgeTableReadsTheSubtypesOfBothEnds() {
      assertSameRows(database, 6, "SELECT p.name AS person, c.name AS company, w.since AS since FROM person p "
              + "LEFT JOIN works_at w ON w.source_id = p.id LEFT JOIN company c ON w.target_id = c.id",
          "MATCH (p:person) OPTIONAL MATCH (p)-[w:works_at]->(c:company) "
              + "RETURN p.name AS person, c.name AS company, w.since AS since");
    }

    @Test
    void aLightweightEdgeTableHasTheRelationshipsOfItsType() {
      assertSameRows(database, 4, "SELECT source_id, target_id FROM knows",
          "MATCH (a)-[:knows]->(b) RETURN elementId(a) AS source_id, elementId(b) AS target_id");
      assertSameRows(database, 4, "SELECT p.name AS person, f.name AS friend FROM person p "
              + "JOIN knows k ON k.source_id = p.id JOIN person f ON k.target_id = f.id",
          "MATCH (p:person)-[:knows]->(f:person) RETURN p.name AS person, f.name AS friend");
      assertSameRows(database, 4, "SELECT p.name AS person, f.name AS friend FROM person p "
              + "JOIN knows k ON k.target_id = p.id JOIN person f ON k.source_id = f.id",
          "MATCH (p:person)<-[:knows]-(f:person) RETURN p.name AS person, f.name AS friend");
    }

    @Test
    void aUnidirectionalEdgeTableIsReadFromBothEnds() {
      assertSameRows(database, 3, "SELECT id, source_id, target_id, weight FROM follows",
          "MATCH (a)-[e:follows]->(b) RETURN elementId(e) AS id, elementId(a) AS source_id, elementId(b) AS target_id, "
              + "e.weight AS weight");
      assertSameRows(database, 3, "SELECT p.name AS person, f.name AS follower, e.weight AS weight FROM person p "
              + "JOIN follows e ON e.target_id = p.id JOIN person f ON e.source_id = f.id",
          "MATCH (p:person)<-[e:follows]-(f:person) RETURN p.name AS person, f.name AS follower, e.weight AS weight");
    }
  }

  /** A regular edge type with a lightweight subtype: one table of edges with a record and edges without one. */
  @Nested
  class RegularAndLightweightEdgesInOneTable extends TestHelper {
    @Override
    protected void beginTest() {
      final Schema schema = database.getSchema();
      schema.createVertexType("person").createProperty("name", Type.STRING);
      final EdgeType rated = schema.createEdgeType("rated");
      rated.createProperty("stars", Type.INTEGER);
      schema.buildEdgeType().withName("liked").withSuperType("rated").withLightweight(true).create();

      database.transaction(() -> {
        final MutableVertex ann = database.newVertex("person").set("name", "Ann").save();
        final MutableVertex ben = database.newVertex("person").set("name", "Ben").save();
        final MutableVertex cat = database.newVertex("person").set("name", "Cat").save();
        ann.newEdge("rated", ben, "stars", 5);
        ann.newEdge("liked", cat);
        ben.newEdge("rated", cat, "stars", 2);
      });
    }

    @Test
    void theTableOfTheSupertypeHasBoth() {
      assertSameRows(database, 3, "SELECT source_id, target_id, stars FROM rated",
          "MATCH (a)-[e:rated]->(b) RETURN elementId(a) AS source_id, elementId(b) AS target_id, e.stars AS stars");
      // the lightweight edge has no id in the table
      assertSameRows(database, 3, "SELECT id, source_id, target_id FROM rated",
          "MATCH (a)-[e:rated]->(b) RETURN CASE WHEN type(e) = 'liked' THEN null ELSE elementId(e) END AS id, "
              + "elementId(a) AS source_id, elementId(b) AS target_id");
    }

    @Test
    void eachTypeAloneHasItsOwn() {
      assertSameRows(database, 2, "SELECT source_id, target_id, stars FROM ONLY rated",
          "MATCH (a)-[e:rated]->(b) WHERE type(e) = 'rated' "
              + "RETURN elementId(a) AS source_id, elementId(b) AS target_id, e.stars AS stars");
      assertSameRows(database, 1, "SELECT source_id, target_id FROM liked",
          "MATCH (a)-[:liked]->(b) RETURN elementId(a) AS source_id, elementId(b) AS target_id");
    }
  }

  @Nested
  class PropertyValues extends TestHelper {
    private static final List<String> PROPERTIES = List.of("v_bool", "v_byte", "v_short", "v_int", "v_long", "v_float",
        "v_double", "v_decimal", "v_string", "v_date", "v_datetime", "v_list", "v_map", "v_link");

    @Override
    protected void beginTest() {
      final VertexType sample = database.getSchema().createVertexType("sample");
      sample.createProperty("k", Type.INTEGER);
      sample.createProperty("v_bool", Type.BOOLEAN);
      sample.createProperty("v_byte", Type.BYTE);
      sample.createProperty("v_short", Type.SHORT);
      sample.createProperty("v_int", Type.INTEGER);
      sample.createProperty("v_long", Type.LONG);
      sample.createProperty("v_float", Type.FLOAT);
      sample.createProperty("v_double", Type.DOUBLE);
      sample.createProperty("v_decimal", Type.DECIMAL);
      sample.createProperty("v_string", Type.STRING);
      sample.createProperty("v_date", Type.DATE);
      sample.createProperty("v_datetime", Type.DATETIME);
      sample.createProperty("v_list", Type.LIST, "STRING");
      sample.createProperty("v_map", Type.MAP);
      sample.createProperty("v_link", Type.LINK, "sample");

      database.transaction(() -> {
        // every property absent: NULL in the table, null in openCypher
        final MutableDocument empty = database.newVertex("sample").set("k", 1).save();
        final MutableVertex full = database.newVertex("sample");
        full.set("k", 2);
        full.set("v_bool", true);
        full.set("v_byte", (byte) 7);
        full.set("v_short", (short) 300);
        full.set("v_int", 70_000);
        full.set("v_long", 5_000_000_000L);
        full.set("v_float", 1.5f);
        full.set("v_double", 2.25d);
        full.set("v_decimal", new BigDecimal("12.50"));
        full.set("v_string", "Ada's é");
        full.set("v_date", LocalDate.of(2026, 1, 5));
        full.set("v_datetime", LocalDateTime.of(2026, 1, 5, 10, 20, 30));
        full.set("v_list", List.of("a", "b"));
        full.set("v_map", Map.of("city", "Delft", "zip", "1000AA"));
        full.set("v_link", empty.getIdentity());
        full.save();
      });
    }

    @Test
    void everyPropertyReadsAsTheSameValue() {
      final StringBuilder table = new StringBuilder("SELECT k");
      final StringBuilder graph = new StringBuilder("MATCH (n:sample) RETURN n.k AS k");
      for (final String property : PROPERTIES) {
        table.append(", ").append(property);
        graph.append(", n.").append(property).append(" AS ").append(property);
      }
      assertSameRows(database, 2, table.append(" FROM sample").toString(), graph.toString());

      // none of the values of the full row was lost on both sides at once
      assertThat(rows(database, "postgresql", table + " WHERE k = 2").get(0)).doesNotContainValue("null");
    }
  }

  private static void assertSameRows(final Database database, final int count, final String sql, final String cypher) {
    final List<Map<String, String>> table = rows(database, "postgresql", sql);
    assertThat(table).as(sql).hasSize(count);
    assertThat(table).as("%s%nagainst %s", sql, cypher).containsExactlyInAnyOrderElementsOf(rows(database, "opencypher", cypher));
  }

  /** The rows as column name to value, with each value written the same way whatever class a language returns it in. */
  private static List<Map<String, String>> rows(final Database database, final String language, final String statement) {
    final List<Map<String, String>> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query(language, statement)) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        final Map<String, String> values = new TreeMap<>();
        for (final String name : row.getPropertyNames())
          values.put(name, canonical(row.getProperty(name)));
        rows.add(values);
      }
    }
    return rows;
  }

  /**
   * A value with its kind. A record id is text, as the table shows it. A value of a class that is not listed keeps the class
   * name, so that it shows in the difference.
   */
  private static String canonical(final Object value) {
    if (value == null)
      return "null";
    if (value instanceof Identifiable record)
      return "text " + record.getIdentity();
    if (value instanceof String text)
      return "text " + text;
    if (value instanceof Boolean)
      return "boolean " + value;
    if (value instanceof Number)
      return "number " + new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    if (value instanceof LocalDate || value instanceof CypherDate)
      return "date " + value;
    if (value instanceof LocalDateTime || value instanceof CypherLocalDateTime)
      return "timestamp " + value;
    if (value instanceof Map<?, ?> map) {
      final Map<String, String> entries = new TreeMap<>();
      map.forEach((key, entry) -> entries.put(String.valueOf(key), canonical(entry)));
      return "map " + entries;
    }
    if (value instanceof Collection<?> collection)
      return "list " + collection.stream().map(PostgreSQLCypherDifferentialTest::canonical).toList();
    return value.getClass().getName() + " " + value;
  }
}
