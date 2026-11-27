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
import com.arcadedb.database.DatabaseInternal;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relational catalog that the language keeps while the schema does not change (fork issue #88): it is the same object until a
 * schema change of any kind, and a statement always sees the schema as it is when the statement starts, so a type, a property, an
 * index or the endpoints of an edge type that change between two calls of the same text change the answer of the second call.
 */
class CatalogCacheTest extends TestHelper {

  private PostgreSQLQueryEngine engine() {
    return (PostgreSQLQueryEngine) ((DatabaseInternal) database).getQueryEngine("postgresql");
  }

  private List<String> rows(final String text) {
    try (final ResultSet rs = database.query("postgresql", text)) {
      final List<String> out = new ArrayList<>();
      while (rs.hasNext())
        out.add(rs.next().toJSON().toString());
      return out;
    }
  }

  @Test
  void theSameCatalogIsGivenBackWhileTheSchemaIsUnchanged() {
    database.getSchema().createVertexType("c1").createProperty("a", Type.LONG);
    final var first = engine().catalog();

    rows("SELECT count(*) AS n FROM c1");
    assertThat(engine().catalog()).isSameAs(first);
    database.transaction(() -> database.newVertex("c1").set("a", 1L).save());
    assertThat(engine().catalog()).isSameAs(first);
  }

  @Test
  void aNewTypeAppearsAndADroppedTypeDisappearsForTheSameText() {
    assertThatThrownBy(() -> rows("SELECT count(*) AS n FROM c2")).isInstanceOf(PostgreSQLUndefinedTableException.class);
    final var before = engine().catalog();

    database.getSchema().createVertexType("c2");
    assertThat(engine().catalog()).isNotSameAs(before);
    assertThat(rows("SELECT count(*) AS n FROM c2")).hasSize(1);

    database.getSchema().dropType("c2");
    assertThatThrownBy(() -> rows("SELECT count(*) AS n FROM c2")).isInstanceOf(PostgreSQLUndefinedTableException.class);
  }

  @Test
  void aPropertyThatIsAddedOrDroppedIsSeenByTheNextCall() {
    database.getSchema().createVertexType("c3").createProperty("a", Type.LONG);
    assertThatThrownBy(() -> rows("SELECT b FROM c3")).isInstanceOf(PostgreSQLReferenceException.class);

    database.getSchema().getType("c3").createProperty("b", Type.STRING);
    assertThat(rows("SELECT b FROM c3")).isEmpty();

    database.getSchema().getType("c3").dropProperty("b");
    assertThatThrownBy(() -> rows("SELECT b FROM c3")).isInstanceOf(PostgreSQLReferenceException.class);
  }

  @Test
  void anIndexThatIsCreatedOrDroppedChangesThePlanOfTheNextCall() {
    database.getSchema().createVertexType("c4").createProperty("a", Type.LONG);
    final String explain = "EXPLAIN SELECT a FROM c4 WHERE a = 1";
    assertThat(String.join("\n", rows(explain))).contains("SCAN TYPE c4").doesNotContain("FETCH FROM INDEX");

    database.getSchema().createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "c4", "a");
    assertThat(String.join("\n", rows(explain))).contains("FETCH FROM INDEX c4[a]");

    database.getSchema().dropIndex(database.getSchema().getType("c4").getAllIndexes(false).iterator().next().getName());
    assertThat(String.join("\n", rows(explain))).contains("SCAN TYPE c4").doesNotContain("FETCH FROM INDEX");
  }

  @Test
  void theEndpointsOfAnEdgeTypeThatChangeAreSeenByTheNextCall() {
    database.getSchema().createVertexType("c5a");
    database.getSchema().createVertexType("c5b");
    database.getSchema().createEdgeType("c5e");
    assertThat(engine().catalog().table("c5e").sourceTable()).isNull();

    database.getSchema().getType("c5e").setCustomValue("source_table", "c5a");
    database.getSchema().getType("c5e").setCustomValue("target_table", "c5b");

    assertThat(engine().catalog().table("c5e").sourceTable()).isEqualTo("c5a");
    assertThat(engine().catalog().table("c5e").targetTable()).isEqualTo("c5b");
  }

  @Test
  void aRefusalIsTheSameWithAKeptCatalog() {
    for (int i = 0; i < 3; i++)
      assertThatThrownBy(() -> rows("TRUNCATE t")).isInstanceOf(PostgreSQLFeatureNotSupportedException.class);
  }

  @Test
  void aThreadThatChangesTheSchemaAndAThreadThatRunsStatements() throws Exception {
    database.getSchema().createVertexType("stable").createProperty("a", Type.LONG);
    database.transaction(() -> {
      for (long i = 0; i < 20; i++)
        database.newVertex("stable").set("a", i).save();
    });
    final ExecutorService pool = Executors.newFixedThreadPool(2);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicBoolean done = new AtomicBoolean();
    try {
      final Future<Integer> ddl = pool.submit(() -> {
        start.await();
        int seen = 0;
        for (int i = 0; i < 60; i++) {
          final String name = "ddl_" + i;
          database.getSchema().createVertexType(name).createProperty("x", Type.LONG);
          // the thread that made the change sees it in its very next call, whatever the other thread has cached
          if (rows("SELECT x FROM " + name).isEmpty())
            ++seen;
          database.getSchema().getType(name).createProperty("y", Type.STRING);
          if (rows("SELECT y FROM " + name).isEmpty())
            ++seen;
          database.getSchema().dropType(name);
          try {
            rows("SELECT x FROM " + name);
            return -1;
          } catch (final PostgreSQLUndefinedTableException expected) {
            ++seen;
          }
        }
        done.set(true);
        return seen;
      });
      final Future<Integer> readers = pool.submit(() -> {
        start.await();
        int calls = 0;
        while (!done.get()) {
          final long k = calls % 20;
          final List<String> got = rows("SELECT a FROM stable WHERE a = " + k);
          if (got.size() != 1)
            return -1;
          ++calls;
        }
        return calls;
      });
      start.countDown();
      assertThat(ddl.get()).isEqualTo(180);
      assertThat(readers.get()).isPositive();
    } finally {
      done.set(true);
      pool.shutdownNow();
    }
  }
}
