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
 * {@code EXPLAIN INSERT ...}, {@code EXPLAIN UPDATE ...} and {@code EXPLAIN DELETE ...}: the plan of a write, with no option, and
 * nothing is written. {@link ExplainStatement} is the {@code EXPLAIN} of a {@code SELECT}.
 *
 * @param statement the explained write
 */
public record ExplainWriteStatement(SourcePosition position, WriteStatement statement) implements Statement {
  public ExplainWriteStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(statement, "statement");
  }
}
