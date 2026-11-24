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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.TestHelper;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 11 golds of phase 3 of the LLM arm that the prototype refused before fork issues #72 (a subquery in a write, {@code UPDATE ...
 * FROM}, an {@code EXISTS} with a correlation that is not an equality): each is planned and run on tables that have the names and the
 * columns of the arm's schema (a few rows, so that every subquery has something to read). The answers are not compared here: the parity
 * corpus compares the shapes with PostgreSQL 18, and the arm's own gold check compares these statements on the real data.
 */
class PhaseThreeGoldsTest extends TestHelper {
  @Override
  protected void beginTest() {
    final Schema schema = database.getSchema();
    schema.createVertexType("Person").createProperty("id", Type.LONG);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "Person", "id");
    for (final String name : new String[] { "Memory", "MemoryArchive" }) {
      final var type = schema.createDocumentType(name);
      type.createProperty("id", Type.LONG);
      type.createProperty("agent", Type.STRING);
      type.createProperty("kind", Type.STRING);
      type.createProperty("content", Type.STRING);
      type.createProperty("importance", Type.INTEGER);
      type.createProperty("created", Type.LONG);
      type.createProperty("subject", Type.LONG);
      schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, name, "id");
    }
    final var knows = schema.createEdgeType("KNOWS");
    knows.createProperty("sinceYear", Type.INTEGER);
    knows.setCustomValue(com.arcadedb.query.postgresql.catalog.EdgeEndpoints.SOURCE_KEY, "Person");
    knows.setCustomValue(com.arcadedb.query.postgresql.catalog.EdgeEndpoints.TARGET_KEY, "Person");
    database.transaction(() -> {
      final var a = database.newVertex("Person").set("id", 933L).save();
      final var b = database.newVertex("Person").set("id", 4139L).save();
      final var c = database.newVertex("Person").set("id", 6597069777240L).save();
      database.newVertex("Person").set("id", 7L).save();
      a.newEdge("KNOWS", b, "sinceYear", 2010);
      a.newEdge("KNOWS", c, "sinceYear", 2011);
      for (int i = 1; i <= 8; i++) {
        database.newDocument("Memory").set("id", (long) i).set("agent", i % 2 == 0 ? "dave" : "carol").set("kind", "event")
            .set("content", i <= 4 ? "same" : "c" + i).set("importance", 1).set("created", 1700000000L + i).set("subject", i % 3 == 0 ? 7L : 933L)
            .save();
        if (i <= 3)
          database.newDocument("MemoryArchive").set("id", (long) i).set("agent", "dave").set("kind", "event").set("content", "same")
              .set("importance", 4).set("created", 1700000000L + i).save();
      }
    });
  }

  @Test
  void theElevenGoldsThatWereRefusedRun() {
    final String[][] golds = new String[][] {
        new String[] { "W05", "UPDATE \"Memory\" m SET importance = a.importance FROM \"MemoryArchive\" a WHERE m.content = a.content" },
        new String[] { "W06", "DELETE FROM \"Memory\" WHERE content IN (SELECT content FROM \"MemoryArchive\")" },
        new String[] { "E03", "DELETE FROM \"KNOWS\" WHERE source_id = (SELECT rid FROM \"Person\" WHERE id = 933) AND target_id = (SELECT rid FROM \"Person\" WHERE id = 4139)" },
        new String[] { "E04", "UPDATE \"KNOWS\" SET \"sinceYear\" = 2015 WHERE source_id = (SELECT rid FROM \"Person\" WHERE id = 933) AND target_id = (SELECT rid FROM \"Person\" WHERE id = 6597069777240)" },
        new String[] { "L03", "DELETE FROM \"Memory\" WHERE id IN (SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY agent ORDER BY created DESC, id DESC) AS rn FROM \"Memory\") t WHERE rn > 3)" },
        new String[] { "L04", "UPDATE \"Memory\" SET importance = 5 WHERE id IN (SELECT id FROM (SELECT id, row_number() OVER (PARTITION BY kind ORDER BY created DESC, id DESC) AS rn FROM \"Memory\") t WHERE rn = 1)" },
        new String[] { "R04", "UPDATE \"Memory\" m SET importance = t.c FROM (SELECT id, count(*) OVER (ORDER BY created) AS c FROM \"Memory\" WHERE agent = 'dave') t WHERE m.id = t.id" },
        new String[] { "X01", "DELETE FROM \"Memory\" WHERE id NOT IN (SELECT min(id) FROM \"Memory\" GROUP BY agent, kind, content)" },
        new String[] { "X02", "DELETE FROM \"Memory\" m WHERE EXISTS (SELECT 1 FROM \"Memory\" n WHERE n.agent = m.agent AND n.kind = m.kind AND n.content = m.content AND (n.created > m.created OR (n.created = m.created AND n.id > m.id)))" },
        new String[] { "X04", "UPDATE \"Memory\" m SET importance = 0 WHERE EXISTS (SELECT 1 FROM \"Memory\" n WHERE n.agent = m.agent AND n.kind = m.kind AND n.content = m.content AND n.id < m.id)" },
        new String[] { "J03", "DELETE FROM \"Memory\" WHERE subject IN (SELECT p.id FROM \"Person\" p WHERE NOT EXISTS (SELECT 1 FROM \"KNOWS\" k WHERE k.source_id = p.rid OR k.target_id = p.rid))" } };
    for (final String[] gold : golds)
      try (final ResultSet resultSet = database.command("postgresql", gold[1])) {
        assertThat((Object) resultSet).as(gold[0]).isNotNull();
        System.out.println("PHASE3-GOLD " + gold[0] + " ran, rows affected " + ((PostgreSQLResultSet) resultSet).affectedRows());
      }
  }
}
