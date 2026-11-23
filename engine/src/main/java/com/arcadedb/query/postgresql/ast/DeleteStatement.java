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
 * {@code DELETE FROM [ONLY] table [[AS] alias] [WHERE condition] [RETURNING ...]}: {@code DeleteStmt} of {@code gram.y} without
 * {@code USING} and {@code WHERE CURRENT OF}, which are refused with SQLSTATE 0A000.
 *
 * @param table     the table; {@code only} is true for {@code DELETE FROM ONLY t}
 * @param where     the condition, or {@code null} when every row is deleted
 * @param returning the {@code RETURNING} list, empty when there is none
 */
public record DeleteStatement(SourcePosition position, TableReference.Table table, Expression where, List<SelectItem> returning)
    implements WriteStatement {
  public DeleteStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(table, "table");
    returning = List.copyOf(returning);
  }
}
