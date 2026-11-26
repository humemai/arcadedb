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
package com.arcadedb.query.postgresql.plan.join;

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.ast.SelectStatement;
import com.arcadedb.query.postgresql.eval.Parameters;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.parser.StatementParser;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.postgresql.plan.SelectPlan;
import com.arcadedb.query.postgresql.plan.SelectPlanner;
import com.arcadedb.query.sql.executor.Result;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The joins of fork issue #6 on the {@code small-noindex} fixture: the {@code small} fixture without any unique index, so that
 * nothing supports a join of {@code orders} to {@code person} on {@code pid}.
 */
class JoinPlanNoIndexTest extends TestHelper {

  private static final String FIRST = "SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid";

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database, false);
    SmallFixture.load(database);
  }

  @Test
  void theFirstStatementGivesTheSameRowsAsAHashJoinOfClassNothing() {
    final List<String> rows = new ArrayList<>();
    final String plan;
    try (final PostgreSQLResultSet resultSet = (PostgreSQLResultSet) database.query("postgresql", FIRST)) {
      plan = resultSet.getExecutionPlan().orElseThrow().prettyPrint(0, 2);
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        rows.add(result.getProperty("name") + " " + result.getProperty("oid"));
      }
    }
    assertThat(rows).containsExactly("Ada 100", "Ada 101", "Bo 102", "Di 103", "Di 104");
    assertThat(plan).contains("HASH JOIN INNER").contains("not supported:").doesNotContain("INDEX NESTED LOOP")
        .doesNotContain("supported by index");
    final JoinSource source = (JoinSource) planned(BuildMemory.FLOOR_CAP_BYTES).source();
    assertThat(source.support().kind()).isEqualTo(JoinSupport.Kind.NOTHING);
  }

  @Test
  void aBuildSideOverTheCapFailsWithTheLimitInTheMessageAndNoRow() {
    assertThatThrownBy(() -> planned(100L).execute(database)).isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> {
      assertThat(e.getSqlState()).isEqualTo("53200");
      assertThat(e.getMessage()).contains("out of memory").contains("more than 100 bytes").contains("Hint:");
    });
  }

  private SelectPlan planned(final long cap) {
    return new SelectPlanner(database, Parameters.NONE, cap).plan((SelectStatement) StatementParser.parse(FIRST));
  }
}
