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

import com.arcadedb.query.postgresql.eval.SubqueryPlanner;
import com.arcadedb.query.postgresql.plan.join.LateralRows;

import java.util.Iterator;
import java.util.List;

/** A {@code LATERAL} subquery that reads columns of the tables before it (fork issue #243): it is read for a row of them. */
final class LateralSubquerySource implements RelationSource, LateralRows {
  private final SubqueryPlanner.CorrelatedRows answers;
  private final List<Field>                    columns;
  private final String                         description;

  LateralSubquerySource(final SubqueryPlanner.CorrelatedRows answers, final List<Field> columns, final String description) {
    this.answers = answers;
    this.columns = List.copyOf(columns);
    this.description = description;
  }

  @Override
  public List<Field> columns() {
    return columns;
  }

  @Override
  public Iterator<Object[]> open() {
    // the join planner reads it for the rows of the tables before it only
    throw new IllegalStateException("A LATERAL subquery is read for a row of the tables before it");
  }

  @Override
  public Iterator<Object[]> rows(final Object[] before) {
    return answers.rows(before).iterator();
  }

  @Override
  public String describe() {
    return description;
  }
}
