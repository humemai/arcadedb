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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.eval.TableFunction;
import com.arcadedb.query.postgresql.plan.join.LateralRows;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The rows of a function in {@code FROM} (fork issue #243). Its arguments are compiled against the columns of the tables before
 * it, so it is read for a row of them ({@link #rows}); one that reads no column is also a source of its own.
 */
final class FunctionSource implements RelationSource, LateralRows {
  private static final Object[] NO_ROW = new Object[0];

  private final TableFunction function;
  private final boolean       ordinality;
  private final List<Field>   columns;
  private final String        description;

  private FunctionSource(final TableFunction function, final boolean ordinality, final List<Field> columns,
      final String description) {
    this.function = function;
    this.ordinality = ordinality;
    this.columns = List.copyOf(columns);
    this.description = description;
  }

  /**
   * @param before the compiler of the tables before the function, which its arguments may read
   */
  static FunctionSource of(final TableReference.Function reference, final ExpressionCompiler before) {
    final TableFunction function = TableFunction.compile(reference.call(), before);
    final String name = reference.call().name().get(reference.call().name().size() - 1).value();
    final String relation = reference.alias() != null ? reference.alias().value() : name;
    final List<String> names = new ArrayList<>();
    final List<PgType> types = new ArrayList<>();
    final List<String> typeNames = new ArrayList<>();
    for (final TableFunction.Column column : function.columns()) {
      names.add(function.namedByAlias() && reference.alias() != null ? reference.alias().value() : column.name());
      types.add(column.type());
      typeNames.add(column.typeName());
    }
    if (reference.ordinality()) {
      names.add("ordinality");
      types.add(PgType.INT8);
      typeNames.add(PgType.INT8.typeName());
    }
    final List<Name> given = reference.columns();
    if (given.size() > names.size())
      throw new PostgreSQLEvaluationException("42P10",
          "table \"" + relation + "\" has " + names.size() + " columns available but " + given.size() + " columns specified");
    final List<Field> fields = new ArrayList<>();
    for (int i = 0; i < names.size(); i++)
      fields.add(new Field(relation, i < given.size() ? given.get(i).value() : names.get(i), types.get(i), typeNames.get(i)));
    return new FunctionSource(function, reference.ordinality(), fields,
        "FUNCTION SCAN " + name + (relation.equals(name) ? "" : " AS " + relation));
  }

  @Override
  public List<Field> columns() {
    return columns;
  }

  @Override
  public Iterator<Object[]> open() {
    return rows(NO_ROW);
  }

  @Override
  public Iterator<Object[]> rows(final Object[] before) {
    final Iterator<Object[]> rows = function.rows(before);
    if (!ordinality)
      return rows;
    return new Iterator<>() {
      private long number;

      @Override
      public boolean hasNext() {
        return rows.hasNext();
      }

      @Override
      public Object[] next() {
        final Object[] row = rows.next();
        final Object[] numbered = new Object[row.length + 1];
        System.arraycopy(row, 0, numbered, 0, row.length);
        numbered[row.length] = ++number;
        return numbered;
      }
    };
  }

  @Override
  public String describe() {
    return description;
  }
}
