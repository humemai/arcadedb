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
 * {@code ALTER TABLE} ({@code AlterTableStmt} of {@code gram.y}), fork issue #230: adding columns, and changing the default and
 * the {@code NOT NULL} of a column.
 *
 * @param position where the statement starts
 * @param table    the name of the table, one or two parts
 * @param ifExists whether {@code IF EXISTS} is written
 * @param actions  what to change, in the order written, never empty
 */
public record AlterTableStatement(SourcePosition position, List<Name> table, boolean ifExists, List<Action> actions)
    implements Statement {

  /** One change of a table. */
  public sealed interface Action permits AddColumn, SetDefault, SetNotNull {
  }

  /** {@code ADD [COLUMN] [IF NOT EXISTS]} a column definition. */
  public record AddColumn(boolean ifNotExists, CreateTableStatement.Column column) implements Action {
  }

  /** {@code ALTER [COLUMN] c SET DEFAULT value}, or {@code DROP DEFAULT} with a {@code null} value. */
  public record SetDefault(Name column, Expression value) implements Action {
  }

  /** {@code ALTER [COLUMN] c SET NOT NULL}, or {@code DROP NOT NULL}. */
  public record SetNotNull(Name column, boolean notNull) implements Action {
  }

  public AlterTableStatement {
    Objects.requireNonNull(position, "position");
    table = List.copyOf(table);
    actions = List.copyOf(actions);
  }
}
