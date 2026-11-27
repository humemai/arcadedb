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
 * {@code CREATE [UNIQUE] INDEX [[IF NOT EXISTS] name] ON [ONLY] table [USING method] (column [operator_class], ...)
 * [WITH (parameter = number, ...)]}: {@code IndexStmt} of {@code gram.y} over plain columns (fork issue #125). Everything else a
 * {@code CREATE INDEX} can say is refused with SQLSTATE 0A000 before a tree exists.
 *
 * @param position    where the statement starts
 * @param unique      whether the index is a unique one
 * @param ifNotExists whether an index of that name makes the statement do nothing; the statement then names it
 * @param name        the name of the index, {@code null} when the statement gives none
 * @param table       the name of the table: one part, or schema and name
 * @param method      the name after {@code USING}, {@code null} when there is none
 * @param elements    the columns of the index in order
 * @param parameters  the parameters after {@code WITH}, in order
 */
public record CreateIndexStatement(SourcePosition position, boolean unique, boolean ifNotExists, Name name, List<Name> table,
    Name method, List<Element> elements, List<Parameter> parameters) implements Statement {
  public CreateIndexStatement {
    Objects.requireNonNull(position, "position");
    table = List.copyOf(table);
    elements = List.copyOf(elements);
    parameters = List.copyOf(parameters);
  }

  /**
   * {@code index_elem} of {@code gram.y} over a column.
   *
   * @param operatorClass the name of the operator class, {@code null} when there is none
   */
  public record Element(SourcePosition position, Name column, Name operatorClass) implements Node {
  }

  /**
   * One parameter of {@code WITH}.
   *
   * @param value the integer written after the {@code =}
   */
  public record Parameter(SourcePosition position, Name name, long value) implements Node {
  }
}
