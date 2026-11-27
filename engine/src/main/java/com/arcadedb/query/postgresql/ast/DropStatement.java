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
 * {@code DROP TABLE} and {@code DROP INDEX} ({@code DropStmt} of {@code gram.y}), fork issue #230.
 *
 * @param position where the statement starts
 * @param kind     what to drop
 * @param ifExists whether {@code IF EXISTS} is written
 * @param names    the names written, each of one or two parts, never empty
 * @param cascade  whether {@code CASCADE} is written; {@code RESTRICT} is what holds without it
 */
public record DropStatement(SourcePosition position, Kind kind, boolean ifExists, List<List<Name>> names, boolean cascade)
    implements Statement {

  /** The kinds of object that the language drops. */
  public enum Kind {
    TABLE, INDEX
  }

  public DropStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(kind, "kind");
    names = names.stream().map(List::copyOf).toList();
  }
}
