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
import com.arcadedb.query.postgresql.PostgreSQLCatalogException;
import com.arcadedb.query.postgresql.PostgreSQLFeatureNotSupportedException;
import com.arcadedb.query.postgresql.VecSmallFixture;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * The pgvector operators on the {@code vec-small} fixture of fork issue #11: the values of the three operators, the vector text
 * form as a literal, a cast and a parameter, and the choice between the index search and the exact scan, asserted on the plan.
 * The nearest-neighbour statements of the issue are the rows of its acceptance list.
 */
class VectorSearchPlanTest extends TestHelper {
  private static final String L2     = "SELECT iid FROM item ORDER BY embedding <-> '[1,0,0]' LIMIT 3";
  private static final String COSINE = "SELECT iid FROM item ORDER BY embedding <=> '[1,0,0]' LIMIT 3";
  private static final String IP     = "SELECT iid FROM item ORDER BY embedding <#> '[1,0,0]' LIMIT 3";

  @Override
  protected void beginTest() {
    VecSmallFixture.createSchema(database);
  }

  // ---- helpers ----

  private List<List<Object>> rows(final String sql, final Object... parameters) {
    try (final ResultSet resultSet = database.query("postgresql", sql, parameters)) {
      final List<List<Object>> out = new ArrayList<>();
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> values = new ArrayList<>();
        for (final String name : result.getPropertyNames())
          values.add(result.getProperty(name));
        out.add(values);
      }
      return out;
    }
  }

  private List<Object> column(final String sql, final Object... parameters) {
    final List<Object> out = new ArrayList<>();
    for (final List<Object> values : rows(sql, parameters))
      out.add(values.get(0));
    return out;
  }

  private String plan(final String sql, final Object... parameters) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql, parameters)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  private String state(final String sql, final Object... parameters) {
    try {
      rows(sql, parameters);
      return null;
    } catch (final PostgreSQLEvaluationException e) {
      return e.getSqlState();
    } catch (final PostgreSQLCatalogException e) {
      return e.getSqlState();
    } catch (final PostgreSQLFeatureNotSupportedException e) {
      return e.getSqlState();
    }
  }

  // ---- values of the operators ----

  @Test
  void theDistancesInTheSelectListAreTheValuesOfPgvector() {
    VecSmallFixture.load(database);
    final List<List<Object>> rows = rows("SELECT iid, embedding <-> '[1,0,0]', embedding <=> '[1,0,0]', embedding <#> '[1,0,0]'"
        + " FROM item ORDER BY iid");
    final double[][] expected = { { 0, 0, -1 }, { 1.414214, 1, 0 }, { 2.236068, 0.292893, -2 }, { 2, 2, 1 } };
    assertThat(rows).hasSize(4);
    for (int i = 0; i < 4; i++) {
      assertThat(rows.get(i).get(0)).isEqualTo(String.valueOf((char) ('a' + i)));
      for (int j = 0; j < 3; j++)
        assertThat((Double) rows.get(i).get(j + 1)).as("row %d operator %d", i, j).isCloseTo(expected[i][j], offset(1e-6));
    }
  }

  @Test
  void theColumnTypeIsVectorAndTheDistanceIsFloat8() {
    VecSmallFixture.load(database);
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql",
        "SELECT embedding, embedding <-> '[1,0,0]' AS d, '[1,2]'::vector AS v FROM item LIMIT 1")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName).containsExactly("vector", "float8", "vector");
    }
  }

  @Test
  void anOperandIsALiteralACastAParameterOrAColumn() {
    VecSmallFixture.load(database);
    final String by = "SELECT iid FROM item ORDER BY iid";
    assertThat(column("SELECT iid FROM item WHERE embedding <-> '[1,0,0]' < 1.5 ORDER BY iid")).containsExactly("a", "b");
    assertThat(column("SELECT iid FROM item WHERE embedding <-> '[1,0,0]'::vector < 1.5 ORDER BY iid")).containsExactly("a", "b");
    assertThat(column("SELECT iid FROM item WHERE embedding <-> $1 < 1.5 ORDER BY iid", (Object) new float[] { 1, 0, 0 }))
        .containsExactly("a", "b");
    // a text parameter is read with the input syntax of the type, as an unknown literal is
    assertThat(column("SELECT iid FROM item WHERE embedding <-> $1 < 1.5 ORDER BY iid", "[1,0,0]")).containsExactly("a", "b");
    // the constant on the left, and the vector of a column on both sides
    assertThat(column("SELECT iid FROM item WHERE '[1,0,0]' <-> embedding < 1.5 ORDER BY iid")).containsExactly("a", "b");
    assertThat(column("SELECT iid FROM item WHERE embedding <-> embedding = 0 ORDER BY iid")).containsExactly("a", "b", "c", "d");
    assertThat(column(by)).hasSize(4);
    assertThat(rows("SELECT '[1,2,3]'::vector <#> '[4,5,6]'")).containsExactly(List.of(-32.0));
  }

  @Test
  void aNullVectorGivesNullAndSortsLast() {
    VecSmallFixture.load(database);
    database.transaction(() -> database.newDocument("item").set("iid", "n").save());
    assertThat(column("SELECT embedding <-> '[1,0,0]' FROM item WHERE iid = 'n'")).containsExactly((Object) null);
    assertThat(column("SELECT iid FROM item ORDER BY embedding <-> '[1,0,0]'")).containsExactly("a", "b", "d", "c", "n");
  }

  @Test
  void aWrongDimensionIsDataExceptionAndABadOperandIsAnError() {
    VecSmallFixture.load(database);
    assertThat(state("SELECT embedding <-> '[1,0]' FROM item")).isEqualTo("22000");
    assertThat(state("SELECT '[1,2,3]'::vector <=> '[1,2]'")).isEqualTo("22000");
    assertThat(state("SELECT embedding <-> '[1,x]' FROM item")).isEqualTo("22P02");
    // no operator between an integer and a vector, or between two unknown literals
    assertThat(state("SELECT embedding <-> 1 FROM item")).isEqualTo("42883");
    assertThat(state("SELECT '[1,2]' <-> '[1,2]'")).isEqualTo("42883");
    assertThat(state("SELECT iid <-> '[1,0,0]' FROM item")).isEqualTo("42883");
    // a vector has no order or equality in this prototype
    assertThat(state("SELECT iid FROM item ORDER BY embedding")).isEqualTo("0A000");
  }

  @Test
  void aCosineZeroVectorIsNaNAndSortsAfterTheNumbers() {
    VecSmallFixture.load(database, new Object[][] { { "z", 0f, 0f, 0f }, { "a", 1f, 0f, 0f }, { "b", 0f, 1f, 0f } });
    final List<Object> values = column("SELECT embedding <=> '[1,0,0]' FROM item ORDER BY iid");
    // iid order is a, b, z: the zero vector z is last
    assertThat((Double) values.get(2)).isNaN();
    assertThat(column("SELECT iid FROM item ORDER BY embedding <=> '[1,0,0]'")).containsExactly("a", "b", "z");
  }

  // ---- without an index ----

  @Test
  void withoutAnIndexTheTableIsScannedAndSortedExactly() {
    VecSmallFixture.load(database);
    assertThat(column(L2)).containsExactly("a", "b", "d");
    assertThat(column(COSINE)).containsExactly("a", "c", "b");
    assertThat(column(IP)).containsExactly("c", "a", "b");
    assertThat(plan(L2)).contains("SCAN TYPE item (polymorphic)")
        .contains("SCAN + SORT exact distance (no index on item[embedding])").doesNotContain("VECTOR INDEX SEARCH");
  }

  // ---- with an index ----

  @Test
  void anIndexOfTheMetricAnswersTheStatementAndThePlanNamesIt() {
    VecSmallFixture.createIndex(database, "EUCLIDEAN");
    VecSmallFixture.load(database);
    assertThat(column(L2)).containsExactly("a", "b", "d");
    assertThat(plan(L2)).startsWith("+ VECTOR INDEX SEARCH item[embedding] k=3 (EUCLIDEAN)").doesNotContain("SCAN");
    assertThat(plan(L2)).contains("SORT 1 key(s)").contains("LIMIT");
  }

  @Test
  void cosineIndexAnswersTheCosineStatement() {
    VecSmallFixture.createIndex(database, "COSINE");
    VecSmallFixture.load(database);
    assertThat(column(COSINE)).containsExactly("a", "c", "b");
    assertThat(plan(COSINE)).startsWith("+ VECTOR INDEX SEARCH item[embedding] k=3 (COSINE)");
  }

  @Test
  void dotProductIndexAnswersTheNegativeInnerProductStatement() {
    VecSmallFixture.createIndex(database, "DOT_PRODUCT");
    // unit vectors, as the DOT_PRODUCT metric of the index assumes
    VecSmallFixture.load(database, new Object[][] { { "a", 1f, 0f, 0f }, { "b", 0f, 1f, 0f }, { "c", 0.6f, 0.8f, 0f },
        { "d", -1f, 0f, 0f } });
    assertThat(column(IP)).containsExactly("a", "c", "b");
    assertThat(plan(IP)).startsWith("+ VECTOR INDEX SEARCH item[embedding] k=3 (DOT_PRODUCT)");
  }

  @Test
  void theIndexSuppliesCandidatesAndTheDistanceInTheSelectListIsTheExactOne() {
    VecSmallFixture.createIndex(database, "EUCLIDEAN");
    VecSmallFixture.load(database);
    final List<List<Object>> rows = rows("SELECT iid, embedding <-> '[1,0,0]' FROM item ORDER BY embedding <-> '[1,0,0]' LIMIT 3");
    assertThat(rows).extracting(row -> row.get(0)).containsExactly("a", "b", "d");
    assertThat((Double) rows.get(1).get(1)).isCloseTo(Math.sqrt(2), offset(1e-6));
    assertThat((Double) rows.get(2).get(1)).isCloseTo(2.0, offset(1e-6));
  }

  @Test
  void aLimitWithAnOffsetAndAParameterAskTheIndexForBoth() {
    VecSmallFixture.createIndex(database, "EUCLIDEAN");
    VecSmallFixture.load(database);
    final String sql = "SELECT iid FROM item ORDER BY embedding <-> $1 LIMIT 2 OFFSET 1";
    assertThat(column(sql, (Object) new float[] { 1, 0, 0 })).containsExactly("b", "d");
    assertThat(plan(sql, (Object) new float[] { 1, 0, 0 })).startsWith("+ VECTOR INDEX SEARCH item[embedding] k=3 (EUCLIDEAN)");
    // the constant may be on the left
    assertThat(column("SELECT iid FROM item ORDER BY '[1,0,0]' <-> embedding LIMIT 1")).containsExactly("a");
  }

  // ---- when the index is not used ----

  @Test
  void anIndexOfAnotherMetricIsNotUsedAndTheAnswerIsTheSame() {
    VecSmallFixture.createIndex(database, "COSINE");
    VecSmallFixture.load(database);
    assertThat(column(L2)).containsExactly("a", "b", "d");
    assertThat(plan(L2)).contains("SCAN TYPE item").contains("SCAN + SORT exact distance (the index on item[embedding] uses COSINE, "
        + "the operator <-> needs EUCLIDEAN)");
  }

  @Test
  void aWhereClauseADescendingSortAndAMissingLimitAreExactScans() {
    VecSmallFixture.createIndex(database, "EUCLIDEAN");
    VecSmallFixture.load(database);
    assertThat(plan("SELECT iid FROM item WHERE iid <> 'a' ORDER BY embedding <-> '[1,0,0]' LIMIT 2"))
        .contains("SCAN + SORT exact distance (a WHERE clause is an exact scan)");
    assertThat(column("SELECT iid FROM item WHERE iid <> 'a' ORDER BY embedding <-> '[1,0,0]' LIMIT 2")).containsExactly("b", "d");
    assertThat(plan("SELECT iid FROM item ORDER BY embedding <-> '[1,0,0]'")).contains("SCAN + SORT exact distance (no constant LIMIT)");
    assertThat(plan("SELECT iid FROM item ORDER BY embedding <-> '[1,0,0]' DESC LIMIT 1")).doesNotContain("VECTOR INDEX SEARCH")
        .doesNotContain("SCAN + SORT exact distance");
    assertThat(column("SELECT iid FROM item ORDER BY embedding <-> '[1,0,0]' DESC LIMIT 1")).containsExactly("c");
  }

  @Test
  void anIndexThatHoldsFewerRowsThanTheLimitFallsBackToTheScan() {
    VecSmallFixture.createIndex(database, "EUCLIDEAN");
    VecSmallFixture.load(database);
    database.transaction(() -> database.newDocument("item").set("iid", "n").save());
    // five rows, one of them without a vector, which the index does not hold: the statement is still answered in full
    assertThat(column("SELECT iid FROM item ORDER BY embedding <-> '[1,0,0]' LIMIT 5")).containsExactly("a", "b", "d", "c", "n");
    assertThat(plan("SELECT iid FROM item ORDER BY embedding <-> '[1,0,0]' LIMIT 5")).startsWith("+ VECTOR INDEX SEARCH");
  }

  @Test
  void aQueryVectorOfAnotherDimensionIsAnErrorWithOrWithoutAnIndex() {
    VecSmallFixture.createIndex(database, "EUCLIDEAN");
    VecSmallFixture.load(database);
    assertThat(state("SELECT iid FROM item ORDER BY embedding <-> '[1,0]' LIMIT 3")).isEqualTo("22000");
  }
}
