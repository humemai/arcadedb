/*
 * Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use file except in compliance with the License.
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
 * {@code EXPLAIN SELECT ...}: {@code ExplainStmt} of {@code gram.y} with no options. {@code EXPLAIN ANALYZE}, {@code EXPLAIN
 * VERBOSE}, the parenthesised option list, and an {@code EXPLAIN} of a statement that is not a {@code SELECT} are refused with
 * SQLSTATE 0A000.
 *
 * @param select the explained query: a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement}
 */
public record ExplainStatement(SourcePosition position, Statement select) implements Statement {
  public ExplainStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(select, "select");
  }
}
