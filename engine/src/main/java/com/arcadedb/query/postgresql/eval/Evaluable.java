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
package com.arcadedb.query.postgresql.eval;

/**
 * A typed expression, ready to run: the result of {@link ExpressionCompiler} for one syntax tree. The type is known before any
 * row is read, as in PostgreSQL, and {@link #eval(Object[])} takes one row as an array of values in the canonical Java classes
 * of {@link PgType}.
 */
public interface Evaluable {
  /** The type of every value this expression returns. */
  PgType type();

  /**
   * The name of the type as the client sees it ({@code int4}, {@code _text}): the same as {@code type().typeName()} except for
   * an array column.
   */
  default String typeName() {
    return type().typeName();
  }

  /** The value for one row, {@code null} for SQL {@code NULL}. */
  Object eval(Object[] row);

  /** True when the value does not depend on the row: a constant, or an expression over constants only. */
  default boolean isConstant() {
    return false;
  }

  /** One row to value function. */
  @FunctionalInterface
  interface RowFunction {
    Object apply(Object[] row);
  }

  /** A constant of a type. */
  record Constant(PgType type, Object value) implements Evaluable {
    @Override
    public Object eval(final Object[] row) {
      return value;
    }

    @Override
    public boolean isConstant() {
      return true;
    }
  }

  /** A value read from a column of the row: {@code row[index]}. */
  record Slot(PgType type, String typeName, int index) implements Evaluable {
    public Slot(final PgType type, final int index) {
      this(type, type.typeName(), index);
    }

    @Override
    public Object eval(final Object[] row) {
      return row[index];
    }
  }

  /**
   * An operation over inputs. It is constant when every input is, so the planner can evaluate it once.
   */
  final class Node implements Evaluable {
    private final PgType      type;
    private final boolean     constant;
    private final RowFunction function;

    public Node(final PgType type, final RowFunction function, final Evaluable... inputs) {
      this.type = type;
      this.function = function;
      boolean allConstant = inputs.length > 0;
      for (final Evaluable input : inputs)
        allConstant &= input.isConstant();
      this.constant = allConstant;
    }

    @Override
    public PgType type() {
      return type;
    }

    @Override
    public Object eval(final Object[] row) {
      return function.apply(row);
    }

    @Override
    public boolean isConstant() {
      return constant;
    }
  }
}
