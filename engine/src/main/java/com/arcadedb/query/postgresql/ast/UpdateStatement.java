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
 * {@code UPDATE [ONLY] table [[AS] alias] SET column = value [, ...] [FROM item [, ...]] [WHERE condition] [RETURNING ...]}:
 * {@code UpdateStmt} of {@code gram.y} without a multiple-column assignment and {@code WHERE CURRENT OF}, which are refused with
 * SQLSTATE 0A000.
 *
 * @param table       the table; {@code only} is true for {@code UPDATE ONLY t}: the rows of the table itself, without its subtypes
 * @param assignments one per {@code SET} item, in the order written
 * @param from        the {@code FROM} items, which the {@code SET} values and the condition may read; empty when there is none
 * @param where       the condition, or {@code null} when every row is updated
 * @param returning   the {@code RETURNING} list, empty when there is none
 */
public record UpdateStatement(SourcePosition position, TableReference.Table table, List<Assignment> assignments,
                              List<TableReference> from, Expression where, List<SelectItem> returning) implements WriteStatement {
  public UpdateStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(table, "table");
    assignments = List.copyOf(assignments);
    from = List.copyOf(from);
    returning = List.copyOf(returning);
    if (assignments.isEmpty())
      throw new IllegalArgumentException("An UPDATE has at least one assignment");
  }

  /** {@code column = value}: {@code set_clause} of {@code gram.y}. */
  public record Assignment(SourcePosition position, Name column, WriteValue value) implements Node {
    public Assignment {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(column, "column");
      Objects.requireNonNull(value, "value");
    }
  }
}
