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
import com.arcadedb.query.postgresql.TpchFixture;
import com.arcadedb.query.postgresql.corpus.Corpus;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The four TPC-H statements of fork issue #13 (Q1, Q6, Q14 and Q19, the bytes of {@code tpch.tsv}, read from the corpus file and
 * never retyped here) on eight rows of {@code lineitem} and four of {@code part} whose sums can be done by hand, and the shape
 * of their plans. The rows PostgreSQL 18 gives on the generated TPC-H data are compared by {@code PgTpchParityIT}.
 *
 * <p>The rows are chosen so that every predicate has a row on each side of it: the last day of the range of Q1 (a {@code <=}), the
 * first day after the range of Q14 (a {@code <}), a discount of {@code 0.0625}, which is between {@code 0.05} and {@code 0.07}
 * and exact in binary, and one qualifying row for each of the three branches of Q19.
 */
class TpchQueriesTest extends TestHelper {

  @Override
  protected void beginTest() {
    TpchFixture.createSchema(database);
    database.transaction(() -> {
      TpchFixture.part(database, 1, 901.0, 100, "PROMO PLATED STEEL", "Brand#12", "SM BOX", 3);
      TpchFixture.part(database, 2, 902.0, 100, "STANDARD POLISHED TIN", "Brand#23", "MED BAG", 4);
      TpchFixture.part(database, 3, 903.0, 100, "PROMO BRUSHED COPPER", "Brand#34", "LG CASE", 2);
      TpchFixture.part(database, 4, 904.0, 100, "ECONOMY ANODIZED STEEL", "Brand#12", "JUMBO JAR", 3);

      final String person = "DELIVER IN PERSON";
      TpchFixture.lineitem(database, 1, 1, 5, 100.0, 0.0, "N", "O", LocalDate.of(1995, 9, 10), "AIR", 0.0, person);
      TpchFixture.lineitem(database, 1, 2, 15, 200.0, 0.5, "N", "O", LocalDate.of(1995, 9, 20), "AIR REG", 0.0, person);
      TpchFixture.lineitem(database, 2, 3, 25, 400.0, 0.0, "R", "F", LocalDate.of(1995, 10, 1), "AIR", 0.5, person);
      TpchFixture.lineitem(database, 2, 1, 5, 300.0, 0.25, "R", "F", LocalDate.of(1994, 3, 1), "RAIL", 0.0, "NONE");
      TpchFixture.lineitem(database, 3, 4, 30, 800.0, 0.0, "A", "F", LocalDate.of(1998, 9, 2), "AIR", 0.0, person);
      TpchFixture.lineitem(database, 3, 2, 10, 600.0, 0.5, "A", "F", LocalDate.of(1996, 1, 1), "AIR", 0.0, person);
      TpchFixture.lineitem(database, 4, 1, 10, 1000.0, 0.0625, "N", "O", LocalDate.of(1994, 6, 15), "MAIL", 0.0, "NONE");
      TpchFixture.lineitem(database, 5, 1, 5, 50.0, 0.0, "N", "O", LocalDate.of(1998, 9, 3), "TRUCK", 0.0, person);
    });
  }

  private static String statement(final String id) throws IOException {
    for (final Corpus.AnswerCase c : Corpus.answerCases(Corpus.readTpch()))
      if (c.id().equals(id))
        return c.statement();
    throw new IllegalArgumentException("no statement " + id);
  }

  private List<List<Object>> rows(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
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

  private String plan(final String sql) {
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", sql)) {
      return resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
    }
  }

  @Test
  void q1GroupsTheLineItemsShippedUpToTheLastDay() throws IOException {
    final List<List<Object>> rows = rows(statement("tpch-q1"));
    assertThat(rows).hasSize(3);
    // returnflag, linestatus, sum_qty, sum_base, sum_disc, sum_charge, avg_qty, avg_price, avg_disc, n
    assertThat(rows.get(0)).containsExactly("A", "F", 40.0, 1400.0, 1100.0, 1100.0, 20.0, 700.0, 0.25, 2L);
    assertThat(rows.get(1).subList(0, 6)).containsExactly("N", "O", 30.0, 1300.0, 1137.5, 1137.5);
    assertThat((Double) rows.get(1).get(6)).isEqualTo(10.0);
    assertThat((Double) rows.get(1).get(7)).isCloseTo(1300.0 / 3, within(1e-9));
    assertThat(rows.get(1).subList(8, 10)).containsExactly(0.1875, 3L);
    assertThat(rows.get(2)).containsExactly("R", "F", 30.0, 700.0, 625.0, 825.0, 15.0, 350.0, 0.125, 2L);
  }

  @Test
  void q6IsOneRowWithTheRevenueAndTheCountOfTheRowsThatPass() throws IOException {
    // only the row of 1994-06-15 passes: 1994, a discount from 0.05 to 0.07, a quantity under 24
    assertThat(rows(statement("tpch-q6"))).containsExactly(List.of(62.5, 1L));
  }

  @Test
  void q14IsThePercentageOfThePromoRevenueInSeptember1995() throws IOException {
    // 100 of 200 is promo: date '1995-09-01' + interval '1' month is 1995-10-01, which is not inside
    assertThat(rows(statement("tpch-q14"))).containsExactly(List.of(50.0));
  }

  @Test
  void q19AddsTheThreeBranchesOfTheOr() throws IOException {
    // 100 (branch 1) + 100 and 300 (branch 2) + 400 (branch 3)
    assertThat(rows(statement("tpch-q19"))).containsExactly(List.of(900.0));
  }

  @Test
  void q14AndQ19AreHashJoinsOnThePartKeyAndNeverACrossProduct() throws IOException {
    // Q19 has its join condition only inside the three branches of an OR: it is taken out of them, and the rest stays a filter
    for (final String id : List.of("tpch-q14", "tpch-q19"))
      assertThat(plan(statement(id))).as("plan of %s", id).contains("HASH JOIN").doesNotContain("NESTED LOOP");
  }

  @Test
  void q1AndQ6ReadOneTableWithoutAnIndex() throws IOException {
    for (final String id : List.of("tpch-q1", "tpch-q6"))
      assertThat(plan(statement(id))).as("plan of %s", id).doesNotContain("JOIN");
  }
}
