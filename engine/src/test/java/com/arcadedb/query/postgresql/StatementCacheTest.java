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
import com.arcadedb.query.postgresql.ast.Statement;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parsed statements that the language keeps by text (fork issue #76): a text is parsed once, a refusal is never kept, and the
 * answer always follows the schema and the parameters of the call, because only the tree is kept and the planner binds names and
 * types expressions on every call.
 */
class StatementCacheTest extends TestHelper {

  private StatementCache cache() {
    return ((PostgreSQLQueryEngine) ((DatabaseInternal) database).getQueryEngine("postgresql")).statements();
  }

  private void table(final int rows) {
    database.getSchema().createVertexType("cache_t").createProperty("a", Type.LONG);
    database.transaction(() -> {
      for (int i = 0; i < rows; i++)
        database.newVertex("cache_t").set("a", (long) i).save();
    });
  }

  private long single(final String text, final Object... parameters) {
    try (final ResultSet rs = database.query("postgresql", text, parameters)) {
      final long value = ((Number) rs.next().getProperty("a")).longValue();
      assertThat(rs.hasNext()).isFalse();
      return value;
    }
  }

  @Test
  void aTextIsParsedOnceAndItsTreeIsKept() {
    final long hits = cache().hits();
    final long misses = cache().misses();

    for (int i = 0; i < 3; i++)
      try (final ResultSet rs = database.query("postgresql", "SELECT 1 AS a")) {
        assertThat(rs.next().<Integer>getProperty("a")).isEqualTo(1);
      }

    assertThat(cache().misses() - misses).isEqualTo(1);
    assertThat(cache().hits() - hits).isEqualTo(2);
    assertThat(cache().count()).isEqualTo(1);
  }

  @Test
  void aRefusedTextIsNeverKeptAndIsTheSameRefusalEachTime() {
    for (int i = 0; i < 2; i++) {
      assertThatThrownBy(() -> database.query("postgresql", "DROP TABLE t")).isInstanceOfSatisfying(
          PostgreSQLFeatureNotSupportedException.class, e -> assertThat(e.getSqlState()).isEqualTo("0A000"));
      assertThatThrownBy(() -> database.query("postgresql", "SELECTX 1")).isInstanceOfSatisfying(PostgreSQLSyntaxException.class,
          e -> assertThat(e.getSqlState()).isEqualTo("42601"));
      assertThatThrownBy(() -> database.query("postgresql", "  ")).isInstanceOf(PostgreSQLFeatureNotSupportedException.class);
    }

    assertThat(cache().count()).isZero();
  }

  @Test
  void theAnswerFollowsTheSchemaAndNotTheCache() {
    table(3);
    assertThat(single("SELECT count(*) AS a FROM cache_t")).isEqualTo(3);

    database.transaction(() -> database.newVertex("cache_t").set("a", 9L).save());
    // the same text, the tree from the cache, the rows as they are now
    assertThat(single("SELECT count(*) AS a FROM cache_t")).isEqualTo(4);

    database.getSchema().dropType("cache_t");
    // the same text again: the table is gone, and the cached tree does not bring it back
    assertThatThrownBy(() -> single("SELECT count(*) AS a FROM cache_t")).isInstanceOfSatisfying(PostgreSQLUndefinedTableException.class,
        e -> assertThat(e.getSqlState()).isEqualTo("42P01"));
  }

  @Test
  void parametersAreBoundOnEveryCall() {
    table(10);

    for (int k = 0; k < 10; k++)
      assertThat(single("SELECT a FROM cache_t WHERE a = $1", (long) k)).isEqualTo(k);

    // ten calls, one text: one parse
    assertThat(cache().count()).isEqualTo(1);
  }

  @Test
  void theCacheKeepsAtMostItsSizeAndDropsTheLeastRecentlyUsed() {
    final StatementCache small = new StatementCache(2);
    final Statement one = StatementParser.parse("SELECT 1");
    small.put("SELECT 1", one);
    small.put("SELECT 2", StatementParser.parse("SELECT 2"));
    assertThat(small.get("SELECT 1")).isSameAs(one);    // 1 is now the most recently used
    small.put("SELECT 3", StatementParser.parse("SELECT 3"));

    assertThat(small.count()).isEqualTo(2);
    assertThat(small.get("SELECT 2")).isNull();
    assertThat(small.get("SELECT 1")).isSameAs(one);
    assertThat(small.get("SELECT 3")).isNotNull();
  }

  @Test
  void aSizeOfZeroKeepsNothingAndALongTextIsNotKept() {
    final StatementCache off = new StatementCache(0);
    off.put("SELECT 1", StatementParser.parse("SELECT 1"));
    assertThat(off.get("SELECT 1")).isNull();
    assertThat(off.count()).isZero();

    final StatementCache on = new StatementCache(10);
    final String longText = "SELECT 1 AS a" + " ".repeat(StatementCache.MAX_TEXT_LENGTH);
    on.put(longText, StatementParser.parse(longText));
    assertThat(on.count()).isZero();
  }

  @Test
  void manyThreadsGetTheAnswerOfTheirOwnCalls() throws Exception {
    table(50);
    final ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      final List<Future<Boolean>> done = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        final int thread = t;
        done.add(pool.submit(() -> {
          for (int i = 0; i < 300; i++) {
            final long k = (thread * 7L + i) % 50;
            if (single("SELECT a FROM cache_t WHERE a = $1", k) != k)
              return false;
          }
          return true;
        }));
      }
      for (final Future<Boolean> f : done)
        assertThat(f.get()).isTrue();
    } finally {
      pool.shutdownNow();
    }
    assertThat(cache().count()).isEqualTo(1);
  }
}
