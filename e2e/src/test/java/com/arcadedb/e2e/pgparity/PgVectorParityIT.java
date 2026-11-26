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
package com.arcadedb.e2e.pgparity;

import com.arcadedb.query.postgresql.VecSmallFixture;
import com.arcadedb.query.postgresql.corpus.Corpus;
import com.arcadedb.query.postgresql.corpus.CorpusFile;
import com.arcadedb.query.postgresql.corpus.Outcome;
import com.arcadedb.query.postgresql.corpus.ResultComparator;
import com.arcadedb.query.postgresql.corpus.Values;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pgvector half of the answer parity (fork issue #11): the statements of {@code pgvector-doc.tsv} on a real PostgreSQL 18
 * with the pgvector extension and a {@code vector(3)} column, and on the {@code postgresql} language of ArcadeDB, both holding
 * the {@code vec-small} fixture. The cells of that file were worked out from the definitions of the operators; here the server
 * is compared with them and with the engine. A few values written into the column are read back the same way.
 * <p>
 * The image is {@code pgvector/pgvector:pg18} unless {@code -Dpgparity.pgvector.image=<name:tag>} says otherwise.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PgVectorParityIT {
  private static final String IMAGE    = System.getProperty("pgparity.pgvector.image", "pgvector/pgvector:pg18");
  private static final String LANGUAGE = "postgresql";

  private static PostgresServer pg;
  private static ArcadeSide     arcade;

  @BeforeAll
  static void start() throws Exception {
    pg = PostgresServer.start(IMAGE);
    assertEquals("C", pg.collation(), "the server must run with the C collation");
    pg.load("CREATE EXTENSION vector; CREATE TABLE item (iid varchar, embedding vector(3));"
        + "INSERT INTO item VALUES ('a','[1,0,0]'),('b','[0,1,0]'),('c','[2,2,0]'),('d','[-1,0,0]')");
    System.out.println("pgparity pgvector: " + pg.version() + ", vector "
        + pg.run("SELECT extversion FROM pg_extension WHERE extname = 'vector'").table.rows.get(0).get(0));
    arcade = new ArcadeSide("vecsmall");
    VecSmallFixture.createSchema(arcade.database());
    VecSmallFixture.load(arcade.database());
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      if (arcade != null)
        arcade.close();
    } finally {
      if (pg != null)
        pg.close();
    }
  }

  @Test
  @Order(1)
  void theOperatorsAnswerLikePgvector() throws Exception {
    final List<String> failures = new ArrayList<>();
    int equal = 0;
    for (final Corpus.AnswerCase c : Corpus.answerCases(CorpusFile.readResource("/postgresql/corpus/pgvector-doc.tsv"))) {
      final Outcome server = pg.run(c.statement());
      final Outcome engine = arcade.run(LANGUAGE, c.statement());
      final Outcome definition = Outcome.fromJson(c.expected());
      final String serverDifference = difference(definition, server, c.ordered(), c.tolerance());
      final String engineDifference = difference(server, engine, c.ordered(), c.tolerance());
      System.out.println("pgparity pgvector " + c.id() + " server-against-definition "
          + (serverDifference == null ? "equal" : "different") + " " + LANGUAGE + " "
          + (engineDifference == null ? "equal" : "different"));
      if (serverDifference != null)
        failures.add(c.id() + ": the server differs from the cells of the file: " + serverDifference);
      if (engineDifference != null)
        failures.add(c.id() + ": the engine differs from the server: " + engineDifference);
      if (serverDifference == null && engineDifference == null)
        equal++;
    }
    System.out.println("pgparity: pgvector: " + equal + " statements equal, " + failures.size() + " differences");
    assertTrue(failures.isEmpty(), String.join("\n", failures));
  }

  @Test
  @Order(2)
  void valuesWrittenIntoAVectorColumnReadBackLikePgvector() {
    final List<String> writes = List.of("INSERT INTO item (iid, embedding) VALUES ('e', '[1,1,1]')",
        "INSERT INTO item (iid, embedding) VALUES ('f', ARRAY[0,1,1])",
        "INSERT INTO item (iid, embedding) VALUES ('g', '[0.5, -2.25, 3]'::vector)", "INSERT INTO item (iid, embedding) VALUES ('h', NULL)",
        "UPDATE item SET embedding = '[0,0,2]' WHERE iid = 'a'");
    for (final String write : writes) {
      final Outcome server = pg.run(write);
      assertTrue(!server.isRefusal(), write + ": " + (server.isRefusal() ? server.describe() : ""));
      arcade.database().transaction(() -> {
        try (final ResultSet ignored = arcade.database().command(LANGUAGE, write)) {
          // the count is not read
        }
      });
    }
    final String read = "SELECT iid, embedding <-> '[0,0,0]', embedding <#> '[1,2,3]', embedding <=> '[1,2,3]' FROM item ORDER BY iid";
    final Outcome server = pg.run(read);
    assertEquals(8, server.table.rows.size());
    final String difference = difference(server, arcade.run(LANGUAGE, read), true, 0.000001);
    assertTrue(difference == null, read + ": " + difference);

    final String bad = "INSERT INTO item (iid, embedding) VALUES ('x', '[1,x,0]')";
    final Outcome refused = pg.run(bad);
    assertTrue(refused.isRefusal(), bad);
    try (final ResultSet ignored = arcade.database().command(LANGUAGE, bad)) {
      assertTrue(false, "the engine accepted " + bad);
    } catch (final RuntimeException e) {
      assertEquals(refused.sqlState, ArcadeSide.sqlState(e), bad);
    }
  }

  /** The cells of the two outcomes compared as in the corpus; two refusals are equal when their SQLSTATE is. */
  private static String difference(final Outcome expected, final Outcome actual, final boolean ordered, final double tolerance) {
    if (expected.isRefusal() || actual.isRefusal()) {
      if (expected.isRefusal() && actual.isRefusal() && expected.sqlState.equals(actual.sqlState))
        return null;
      return "expected " + (expected.isRefusal() ? expected.describe() : "an answer") + ", got "
          + (actual.isRefusal() ? actual.describe() : "an answer");
    }
    return ResultComparator.compare(Outcome.answer(expected.table.valuesOnly()), Outcome.answer(actual.table.valuesOnly()),
        ordered, tolerance);
  }
}
