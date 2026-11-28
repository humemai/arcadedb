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
package com.arcadedb.query.postgresql.eval;

import com.arcadedb.query.postgresql.ast.Statement;

import java.util.List;

/** What the expression compiler asks the planner when it meets a subquery: plan it with the names that are visible here. */
public interface SubqueryPlanner {
  /**
   * An uncorrelated subquery: its columns and its rows, which do not depend on the row of the query around it.
   *
   * @param query a {@code SelectStatement}, a {@code SetOperationStatement} or a {@code WithStatement}
   */
  Subquery plan(Statement query);

  /**
   * {@code EXISTS (query)}, which may read columns of the query around it: the expression that is true for a row of the outer query
   * when the subquery has a row for it.
   *
   * @param query a {@code SelectStatement}, a {@code SetOperationStatement} or a {@code WithStatement}
   * @param outer the compiler of the outer query, which types the outer side of each correlation
   */
  Evaluable exists(Statement query, ExpressionCompiler outer);

  /**
   * A subquery that reads columns of the query around it ({@code (SELECT count(*) FROM t WHERE t.k = outer.k)}), as PostgreSQL runs
   * it: once for each row of the outer query, with the outer columns it reads fixed to that row's values. A row with the same values
   * as an earlier one gets the earlier answer.
   *
   * @param query a {@code SelectStatement}, a {@code SetOperationStatement} or a {@code WithStatement}
   * @param outer the compiler of the outer query, which types and reads the outer columns
   * @return {@code null} when the subquery reads no column of the outer query: {@link #plan} serves it
   */
  default CorrelatedRows correlated(final Statement query, final ExpressionCompiler outer) {
    return null;
  }

  /** The answer of a correlated subquery as a function of the row of the outer query. */
  interface CorrelatedRows {
    /** The type of each column of the answer. */
    List<PgType> types();

    /** The type name of each column of the answer, as the client sees it. */
    List<String> typeNames();

    /** The name of each column of the answer. */
    List<String> names();

    /** Whether a column of the answer holds record ids that it passes on (fork issue #240). */
    boolean holdsRecordIds(int column);

    /** The rows of the answer for this row of the outer query. The rows are held in memory under the cap of the planner ({@code 53200}). */
    List<Object[]> rows(Object[] outerRow);
  }
}
