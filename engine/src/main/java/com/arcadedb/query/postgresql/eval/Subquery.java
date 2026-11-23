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

import java.util.List;

/**
 * A subquery as an expression sees it: planned when the expression is typed, run the first time a row needs it, and kept for
 * every row after that. It has no reference to the row of the query around it: a subquery that reads a column of the outer query
 * is not supported, except as {@code EXISTS}.
 */
public interface Subquery {
  /** The type of each column of the answer. */
  List<PgType> types();

  /** The type name of each column of the answer, as the client sees it. */
  List<String> typeNames();

  /** The rows of the answer, computed once. The rows are held in memory under the cap of the planner (SQLSTATE {@code 53200}). */
  List<Object[]> rows();
}
