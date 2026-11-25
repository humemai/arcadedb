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

import com.arcadedb.database.Database;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.plan.join.BuildMemory;

import java.util.List;

/**
 * A planned query of any shape: a {@link SelectPlan}, or a set operation over two other plans. {@link #execute} answers the
 * statement; {@link #rows} gives the same rows as arrays, for the plan that reads them: a derived table, a common table
 * expression or a set operation.
 */
public interface QueryPlan {
  /** The name and the type name of every column of the answer. */
  List<PostgreSQLResultSet.Column> columns();

  /** The lines of the plan, the way the source reads first. */
  List<String> steps();

  /** The warnings of the plan: one sentence for each join that no edge, link or index supports. */
  List<String> warnings();

  PostgreSQLResultSet execute(Database database);

  /**
   * The type of every column as the evaluator computes with it. A plan that is an operand of a set operation keeps the type
   * {@code unknown} of a column that is only a string literal or {@code NULL}, so that the set operation can resolve it with
   * the other operand, as PostgreSQL does; every other plan has resolved it to {@code text}.
   */
  List<PgType> outputTypes();

  /**
   * The place that reads output column {@code index}, counted from 0, converts it to {@code type}: a parameter of a described
   * statement that is that whole column, and has no type yet, takes it.
   */
  void inferParameter(int index, PgType type);

  /**
   * The rows of the answer, each as wide as {@link #columns()}. The rows that stay in memory are charged to the memory when it
   * is not {@code null}, which stops the statement with SQLSTATE {@code 53200} when the cap is over.
   */
  List<Object[]> rows(BuildMemory memory);
}
