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

import java.util.Objects;

/**
 * One key of an {@code ORDER BY}: {@code sortby} of {@code gram.y} ({@code a_expr opt_asc_desc opt_nulls_order}). The
 * {@code USING operator} form is not in the subset.
 *
 * @param expression the sort key; PostgreSQL also accepts an output column name or number here, which is the binder's business
 * @param direction  what was written; {@link Direction#DEFAULT} (nothing written) sorts like {@code ASC}
 * @param nulls      what was written; {@link Nulls#DEFAULT} (nothing written) puts nulls last for {@code ASC} and first for
 *                   {@code DESC}
 */
public record SortItem(SourcePosition position, Expression expression, Direction direction, Nulls nulls) implements Node {
  /** {@code opt_asc_desc}. */
  public enum Direction {
    DEFAULT, ASC, DESC
  }

  /** {@code opt_nulls_order}. */
  public enum Nulls {
    DEFAULT, FIRST, LAST
  }

  public SortItem {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(expression, "expression");
    Objects.requireNonNull(direction, "direction");
    Objects.requireNonNull(nulls, "nulls");
  }
}
