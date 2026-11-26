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
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recall of the index path of the three pgvector operators (fork issue #11): on 10,000 random unit vectors of 64 dimensions,
 * made from a fixed seed, the 10 nearest rows that an {@code LSM_VECTOR} index of the metric of the operator returns for 100
 * query vectors, against the 10 nearest rows of the exact scan. The index and the search have their default settings. The
 * threshold is a mean recall at 10 of at least 0.90 for each operator. The result changes from run to run on the same data, and
 * the values measured at these settings were 0.947 to 0.960, so the threshold leaves room for that variation.
 */
class VectorSearchRecallTest extends TestHelper {
  private static final int    ROWS       = 10_000;
  private static final int    DIMENSIONS = 64;
  private static final int    QUERIES    = 100;
  private static final int    K          = 10;
  private static final double THRESHOLD  = 0.90;
  private static final long   SEED       = 11L;

  @ParameterizedTest
  @CsvSource({ "<->, EUCLIDEAN", "<=>, COSINE", "<#>, DOT_PRODUCT" })
  void theIndexFindsTheNearestRowsOfTheExactScan(final String operator, final String similarity) {
    final Random random = new Random(SEED);
    final DocumentType sample = database.getSchema().createDocumentType("sample");
    sample.createProperty("sid", Type.INTEGER);
    sample.createProperty("embedding", Type.ARRAY_OF_FLOATS);
    database.transaction(() -> {
      for (int i = 0; i < ROWS; i++)
        database.newDocument("sample").set("sid", i).set("embedding", unitVector(random)).save();
    });
    database.transaction(() -> database.getSchema().buildTypeIndex("sample", new String[] { "embedding" }).withLSMVectorType()
        .withDimensions(DIMENSIONS).withSimilarity(similarity).create());

    final String order = " ORDER BY embedding " + operator + " $1 LIMIT " + K;
    final String indexed = "SELECT sid FROM sample" + order;
    // a WHERE clause keeps a statement off the index, so this one is the exact scan over the same rows
    final String exact = "SELECT sid FROM sample WHERE sid >= 0" + order;

    int found = 0;
    for (int q = 0; q < QUERIES; q++) {
      final float[] query = unitVector(random);
      if (q == 0) {
        assertThat(plan(indexed, query)).startsWith("+ VECTOR INDEX SEARCH sample[embedding] k=10 (" + similarity + ")");
        assertThat(plan(exact, query)).doesNotContain("VECTOR INDEX SEARCH");
      }
      final Set<Integer> nearest = nearest(exact, query);
      assertThat(nearest).hasSize(K);
      final Set<Integer> candidates = nearest(indexed, query);
      assertThat(candidates).hasSize(K);
      candidates.retainAll(nearest);
      found += candidates.size();
    }

    final double recall = found / (double) (QUERIES * K);
    System.out.printf(Locale.ROOT, "VECTOR RECALL %s %s: mean recall@%d = %.4f over %d queries%n", operator, similarity, K, recall,
        QUERIES);
    assertThat(recall).as("mean recall@%d of %s through the %s index", K, operator, similarity).isGreaterThanOrEqualTo(THRESHOLD);
  }

  private static float[] unitVector(final Random random) {
    final float[] vector = new float[DIMENSIONS];
    double squares = 0;
    for (int i = 0; i < DIMENSIONS; i++) {
      vector[i] = (float) random.nextGaussian();
      squares += vector[i] * vector[i];
    }
    final float norm = (float) Math.sqrt(squares);
    for (int i = 0; i < DIMENSIONS; i++)
      vector[i] /= norm;
    return vector;
  }

  private Set<Integer> nearest(final String sql, final float[] query) {
    try (final ResultSet resultSet = database.query("postgresql", sql, (Object) query)) {
      final Set<Integer> ids = new HashSet<>();
      while (resultSet.hasNext())
        ids.add(resultSet.next().getProperty("sid"));
      return ids;
    }
  }

  private String plan(final String sql, final float[] query) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql, (Object) query)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }
}
