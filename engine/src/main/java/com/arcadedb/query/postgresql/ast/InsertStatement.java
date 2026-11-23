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
 * {@code INSERT INTO table [AS alias] [(column, ...)] source [RETURNING ...]}: {@code InsertStmt} of {@code gram.y} without
 * {@code WITH}, {@code OVERRIDING} and {@code ON CONFLICT}, which are refused with SQLSTATE 0A000.
 *
 * @param table     the table, with its alias if it has one ({@code only} is always false: {@code INSERT INTO ONLY} is not syntax)
 * @param columns   the column list; empty when the statement has none, which means every column in the declared order
 * @param source    where the rows come from
 * @param returning the {@code RETURNING} list, empty when there is none
 */
public record InsertStatement(SourcePosition position, TableReference.Table table, List<Name> columns, Source source,
                              List<SelectItem> returning) implements WriteStatement {
  public InsertStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(table, "table");
    Objects.requireNonNull(source, "source");
    columns = List.copyOf(columns);
    returning = List.copyOf(returning);
  }

  /** The rows of an {@code INSERT}. */
  public sealed interface Source extends Node {
  }

  /** {@code DEFAULT VALUES}: one row of the default of every column. */
  public record DefaultValues(SourcePosition position) implements Source {
    public DefaultValues {
      Objects.requireNonNull(position, "position");
    }
  }

  /**
   * {@code VALUES (...), (...)}: one list of values per row, every list as long as the first (the parser checks that: PostgreSQL
   * does too, as a syntax-level error of the VALUES list).
   */
  public record Values(SourcePosition position, List<List<WriteValue>> rows) implements Source {
    public Values {
      Objects.requireNonNull(position, "position");
      rows = rows.stream().map(List::copyOf).toList();
      if (rows.isEmpty())
        throw new IllegalArgumentException("A VALUES list has at least one row");
    }
  }

  /** {@code INSERT ... SELECT ...}: the rows of a query. */
  public record Query(SourcePosition position, SelectStatement select) implements Source {
    public Query {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(select, "select");
    }
  }
}
