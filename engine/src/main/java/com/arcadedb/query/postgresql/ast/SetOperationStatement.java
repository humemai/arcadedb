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
 * {@code left UNION | INTERSECT | EXCEPT [ALL | DISTINCT] right}: a set operation of {@code simple_select} in {@code gram.y}, with
 * the {@code ORDER BY}, {@code OFFSET} and {@code LIMIT} that apply to the whole result. {@code INTERSECT} binds tighter than
 * {@code UNION} and {@code EXCEPT}, which group to the left; the tree has the grouping, and a parenthesis leaves no node.
 *
 * @param kind    the operation
 * @param all     true for {@code ALL}: the rows are kept with their multiplicity; {@code DISTINCT} and nothing are false
 * @param left    a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement}
 * @param right   as {@code left}
 * @param orderBy the {@code ORDER BY} keys of the whole result, empty when there is none
 * @param limit   the {@code LIMIT} count, or {@code null}
 * @param offset  the {@code OFFSET} count, or {@code null}
 */
public record SetOperationStatement(SourcePosition position, Kind kind, boolean all, Statement left, Statement right,
                                    List<SortItem> orderBy, Expression limit, Expression offset) implements Statement {
  /** The three operations. */
  public enum Kind {
    UNION, INTERSECT, EXCEPT
  }

  public SetOperationStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    orderBy = List.copyOf(orderBy);
  }
}
