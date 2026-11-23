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
 * One item of a select list: {@code target_el} of {@code gram.y}.
 */
public sealed interface SelectItem extends Node {

  /**
   * An expression with an optional output name: {@code a_expr AS ColLabel}, {@code a_expr BareColLabel} and plain
   * {@code a_expr}. The node starts where the expression starts.
   *
   * @param alias the name after {@code AS} or the bare label, or {@code null} when there is none (the output name is then
   *              decided by the binder: the column name for a column reference, a function name, or {@code ?column?})
   */
  record Projection(SourcePosition position, Expression expression, Name alias) implements SelectItem {
    public Projection {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(expression, "expression");
    }
  }

  /**
   * {@code *} or {@code t.*}: the {@code '*'} alternative of {@code target_el} and the {@code columnref '.' '*'} form of
   * {@code indirection}.
   *
   * @param qualifier the names before {@code .*}, empty for a bare {@code *}, at most two ({@code s.t.*})
   */
  record Star(SourcePosition position, List<Name> qualifier) implements SelectItem {
    public Star {
      Objects.requireNonNull(position, "position");
      qualifier = List.copyOf(qualifier);
    }
  }
}
