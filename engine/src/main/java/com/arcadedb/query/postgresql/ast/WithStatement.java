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
package com.arcadedb.query.postgresql.ast;

import java.util.List;
import java.util.Objects;

/**
 * {@code WITH name [(columns)] AS (query), ... body}: a query with common table expressions (the non-recursive {@code with_clause}
 * of {@code gram.y}). {@code WITH RECURSIVE}, a data-modifying statement in a {@code WITH}, and {@code SEARCH} and {@code CYCLE}
 * are refused with SQLSTATE 0A000.
 *
 * @param ctes the common table expressions in the order written, at least one; a later one can read an earlier one
 * @param body a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement}: the query the names are for
 */
public record WithStatement(SourcePosition position, List<Cte> ctes, Statement body) implements Statement {
  /**
   * One common table expression.
   *
   * @param name    the name the rest of the statement reads it by
   * @param columns the names after the name in parentheses, empty when there is no list
   * @param query   a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement}
   */
  public record Cte(Name name, List<Name> columns, Statement query) {
    public Cte {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(query, "query");
      columns = List.copyOf(columns);
    }
  }

  public WithStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(body, "body");
    ctes = List.copyOf(ctes);
    if (ctes.isEmpty())
      throw new IllegalArgumentException("A WITH clause has at least one common table expression");
  }
}
