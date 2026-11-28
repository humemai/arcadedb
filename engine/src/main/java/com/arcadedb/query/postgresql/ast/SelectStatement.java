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
 * A query: {@code SelectStmt} of {@code gram.y}, the {@code select_no_parens} form that is a single {@code simple_select} with
 * its {@code sort_clause} and {@code select_limit}; a set operation is a {@link SetOperationStatement} and a query with common
 * table expressions a {@link WithStatement}. It holds the clauses in the order PostgreSQL evaluates their meaning:
 * {@code FROM}, {@code WHERE}, {@code GROUP BY}, {@code HAVING}, the select list, {@code DISTINCT}, {@code ORDER BY},
 * {@code OFFSET}, {@code LIMIT}. {@code FETCH FIRST n ROWS ONLY} is the {@code LIMIT}. Not in the subset, and refused with
 * SQLSTATE 0A000: {@code WITH RECURSIVE}, {@code TABLE}, {@code SELECT INTO}, {@code DISTINCT ON}, {@code WINDOW},
 * {@code GROUPING SETS}, {@code ROLLUP} and {@code CUBE}, {@code FETCH FIRST ... WITH TIES}, and the locking clause
 * ({@code FOR UPDATE}).
 *
 * @param distinct true for {@code SELECT DISTINCT}; {@code SELECT ALL} and nothing are false
 * @param items    the select list; empty only in the query of an {@code EXISTS}, where nobody reads a value
 * @param from     the {@code FROM} items separated by commas, empty when there is no {@code FROM} clause
 * @param where    the {@code WHERE} condition, or {@code null}
 * @param groupBy  the {@code GROUP BY} expressions, empty when there is no {@code GROUP BY} clause
 * @param having   the {@code HAVING} condition, or {@code null}
 * @param orderBy  the {@code ORDER BY} keys, empty when there is none
 * @param limit    the {@code LIMIT} count, or {@code null} for no {@code LIMIT} clause and for {@code LIMIT ALL}
 * @param offset   the {@code OFFSET} count, or {@code null}
 */
public record SelectStatement(SourcePosition position, boolean distinct, List<SelectItem> items, List<TableReference> from,
                              Expression where, List<Expression> groupBy, Expression having, List<SortItem> orderBy,
                              Expression limit, Expression offset) implements Statement {
  public SelectStatement {
    Objects.requireNonNull(position, "position");
    items = List.copyOf(items);
    from = List.copyOf(from);
    groupBy = List.copyOf(groupBy);
    orderBy = List.copyOf(orderBy);
  }
}
