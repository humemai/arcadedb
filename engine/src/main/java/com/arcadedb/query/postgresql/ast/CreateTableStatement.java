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
 * {@code CREATE TABLE [IF NOT EXISTS] name (element, ...)}: {@code CreateStmt} of {@code gram.y} with column definitions and the
 * table constraints {@code UNIQUE}, {@code PRIMARY KEY} and {@code FOREIGN KEY} (fork issue #118). Everything else a
 * {@code CREATE TABLE} can say is refused with SQLSTATE 0A000 before a tree exists. What the engine can keep of the tree is
 * decided when the statement runs.
 *
 * @param position    where the statement starts
 * @param table       the name of the new table: one part, or schema and name
 * @param ifNotExists whether a table of that name makes the statement do nothing
 * @param columns     the column definitions in order
 * @param constraints the constraints written as elements of their own, in order
 */
public record CreateTableStatement(SourcePosition position, List<Name> table, boolean ifNotExists, List<Column> columns,
    List<Constraint> constraints) implements Statement {
  public CreateTableStatement {
    Objects.requireNonNull(position, "position");
    table = List.copyOf(table);
    columns = List.copyOf(columns);
    constraints = List.copyOf(constraints);
  }

  /**
   * {@code columnDef} of {@code gram.y}.
   *
   * @param constraints the constraints written after the type, in order
   */
  public record Column(SourcePosition position, Name name, TypeName type, List<Constraint> constraints) implements Node {
    public Column {
      constraints = List.copyOf(constraints);
    }
  }

  /** What a constraint says. */
  public enum Kind {
    NOT_NULL, NULL, DEFAULT, UNIQUE, PRIMARY_KEY, FOREIGN_KEY
  }

  /**
   * {@code ColConstraint} or {@code TableConstraint} of {@code gram.y}.
   *
   * @param name      the name after {@code CONSTRAINT}, {@code null} when there is none
   * @param columns   the columns of a table constraint, empty for a constraint of a column
   * @param value     the expression of a {@code DEFAULT}, else {@code null}
   * @param reference what a foreign key refers to, else {@code null}
   */
  public record Constraint(SourcePosition position, Kind kind, Name name, List<Name> columns, Expression value,
      Reference reference) implements Node {
    public Constraint {
      Objects.requireNonNull(kind, "kind");
      columns = List.copyOf(columns);
    }
  }

  /** A referential action of {@code ON DELETE} or {@code ON UPDATE}. */
  public enum Action {
    NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT
  }

  /**
   * {@code REFERENCES table [(column, ...)] [ON DELETE action] [ON UPDATE action]}.
   *
   * @param columns  the referenced columns, empty when none are written
   * @param onDelete {@code NO_ACTION} when not written
   * @param onUpdate {@code NO_ACTION} when not written
   */
  public record Reference(SourcePosition position, List<Name> table, List<Name> columns, Action onDelete, Action onUpdate)
      implements Node {
    public Reference {
      table = List.copyOf(table);
      columns = List.copyOf(columns);
    }
  }
}
