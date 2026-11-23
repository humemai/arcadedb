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
 * The empty statement: the empty alternative of {@code stmt} in {@code gram.y}. It is what {@code ;} and a text with only
 * whitespace and comments parse to, and PostgreSQL answers it with an empty query response. The language does not run it
 * (see {@code PostgreSQLQueryEngine}); the parser reports it so the caller can decide.
 */
public record EmptyStatement(SourcePosition position) implements Statement {
  public EmptyStatement {
    Objects.requireNonNull(position, "position");
  }
}
