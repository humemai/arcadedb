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
 * An item of a {@code FROM} clause: {@code table_ref} of {@code gram.y}. Only a table name and a join of two references are in
 * the subset, and a subquery with an alias; a function call, {@code LATERAL} and {@code TABLESAMPLE} are refused with SQLSTATE
 * 0A000.
 */
public sealed interface TableReference extends Node {

  /**
   * A table name with an optional alias: {@code relation_expr opt_alias_clause}, where {@code relation_expr} is
   * {@code qualified_name} or {@code ONLY qualified_name}. A trailing {@code *} after the name ("and its descendants") is
   * accepted and means nothing, as it is the default.
   *
   * @param name  the table name, one part or schema and name (a three-part name is refused, as in PostgreSQL when the first part
   *              is not the current database)
   * @param only  true for {@code ONLY t}: the rows of the table itself, without the rows of its subtypes (question 12 of the
   *              decisions in issue #1)
   * @param alias the name after {@code AS} or the bare alias, or {@code null}
   */
  record Table(SourcePosition position, List<Name> name, boolean only, Name alias) implements TableReference {
    public Table {
      Objects.requireNonNull(position, "position");
      name = List.copyOf(name);
      if (name.isEmpty() || name.size() > 2)
        throw new IllegalArgumentException("A table name has one or two parts: " + name);
    }
  }

  /**
   * {@code (query) [AS] alias [(columns)]}: a derived table. Its rows are the rows of the query, read once when the statement
   * runs; its columns have the names of the select list, or the names of the list after the alias.
   *
   * @param query   a {@link SelectStatement}, a {@link SetOperationStatement} or a {@link WithStatement}
   * @param alias   the name of the derived table, or {@code null} when none is written (PostgreSQL 16 and later accept that)
   * @param columns the names after the alias in parentheses, empty when there is no list
   */
  record Subquery(SourcePosition position, Statement query, Name alias, List<Name> columns) implements TableReference {
    public Subquery {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(query, "query");
      columns = List.copyOf(columns);
    }
  }

  /** The kinds of join in the subset: {@code [INNER] JOIN} and {@code LEFT [OUTER] JOIN}, the {@code join_type} rule. */
  enum JoinType {
    INNER, LEFT
  }

  /**
   * {@code table_ref join_type JOIN table_ref join_qual}: {@code joined_table} of {@code gram.y}, with an {@code ON} condition
   * ({@code ON a_expr} of {@code join_qual}). Joins group to the left as in PostgreSQL: {@code a JOIN b ON x JOIN c ON y} is a
   * join whose left side is the join of {@code a} and {@code b}; a parenthesised join on the right keeps its own shape. A
   * parenthesis leaves no node. {@code CROSS}, {@code NATURAL}, {@code RIGHT}, {@code FULL} and {@code USING} are not in the
   * subset.
   *
   * @param condition the {@code ON} expression
   */
  record Join(SourcePosition position, TableReference left, JoinType type, TableReference right, Expression condition)
      implements TableReference {
    public Join {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(right, "right");
      Objects.requireNonNull(condition, "condition");
    }
  }
}
