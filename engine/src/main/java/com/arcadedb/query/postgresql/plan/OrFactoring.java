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

import com.arcadedb.query.postgresql.ast.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes the terms that every branch of an {@code OR} has in common out of the {@code OR}: {@code (A AND X) OR (A AND Y)}
 * becomes {@code A AND (X OR Y)}. It is the rewrite of {@code process_duplicate_ors} in PostgreSQL's {@code prepqual.c}, which
 * the server applies to every {@code WHERE} clause before it plans.
 *
 * <p>It matters for a join written as TPC-H Q19 writes it: {@code FROM lineitem, part WHERE (p_partkey = l_partkey AND ...) OR
 * (p_partkey = l_partkey AND ...) OR (p_partkey = l_partkey AND ...)}. The equality is in every branch, so after the rewrite it
 * is a term of its own, the join planner finds an equality between the two tables and builds a hash join on it, and only the rest
 * of the branches is left as a condition on the joined rows. Without it the join has no key and the plan is a nested loop over the
 * cross product.
 *
 * <p>The rewrite keeps the three-valued logic of SQL, because {@code AND} distributes over {@code OR} in it: a row gets
 * {@code TRUE}, {@code FALSE} or {@code NULL} before and after alike. Two terms are the same term when their trees are equal, a
 * position in the text not counting. If a branch consists of the common terms only, the whole {@code OR} is those terms
 * ({@code A OR (A AND X)} is {@code A}). The rewrite goes down through {@code AND} and {@code OR}; it does not look inside any
 * other expression.
 */
public final class OrFactoring {
  private OrFactoring() {
  }

  /** The condition with the common terms of every {@code OR} taken out; {@code null} stays {@code null}. */
  public static Expression factor(final Expression expression) {
    if (expression instanceof Expression.BinaryOp op) {
      if (op.operator() == Expression.BinaryOperator.AND) {
        final Expression left = factor(op.left());
        final Expression right = factor(op.right());
        return left == op.left() && right == op.right() ? op
            : new Expression.BinaryOp(op.position(), Expression.BinaryOperator.AND, left, right);
      }
      if (op.operator() == Expression.BinaryOperator.OR)
        return factorOr(op);
    }
    return expression;
  }

  private static Expression factorOr(final Expression.BinaryOp or) {
    final List<Expression> branches = new ArrayList<>();
    flatten(or, Expression.BinaryOperator.OR, branches);

    final List<List<Expression>> arms = new ArrayList<>();
    for (final Expression branch : branches) {
      final List<Expression> terms = new ArrayList<>();
      flatten(factor(branch), Expression.BinaryOperator.AND, terms);
      arms.add(terms);
    }

    // the terms of the first branch that every other branch has too
    final List<Expression> common = new ArrayList<>();
    for (final Expression term : arms.get(0)) {
      if (common.contains(term))
        continue;
      boolean everywhere = true;
      for (int i = 1; i < arms.size() && everywhere; i++)
        everywhere = arms.get(i).contains(term);
      if (everywhere)
        common.add(term);
    }

    if (common.isEmpty()) {
      final List<Expression> rebuilt = new ArrayList<>();
      for (final List<Expression> arm : arms)
        rebuilt.add(join(arm, Expression.BinaryOperator.AND, or));
      return join(rebuilt, Expression.BinaryOperator.OR, or);
    }

    final List<Expression> rests = new ArrayList<>();
    for (final List<Expression> arm : arms) {
      final List<Expression> rest = new ArrayList<>();
      for (final Expression term : arm)
        if (!common.contains(term))
          rest.add(term);
      if (rest.isEmpty())
        // a branch that is the common terms alone: the OR adds nothing to them
        return join(common, Expression.BinaryOperator.AND, or);
      rests.add(join(rest, Expression.BinaryOperator.AND, or));
    }
    final List<Expression> all = new ArrayList<>(common);
    all.add(join(rests, Expression.BinaryOperator.OR, or));
    return join(all, Expression.BinaryOperator.AND, or);
  }

  /** The operands of a chain of one operator, left to right: {@code (a AND b) AND c} gives a, b, c. */
  private static void flatten(final Expression expression, final Expression.BinaryOperator operator, final List<Expression> out) {
    if (expression instanceof Expression.BinaryOp op && op.operator() == operator) {
      flatten(op.left(), operator, out);
      flatten(op.right(), operator, out);
    } else
      out.add(expression);
  }

  /** The operands as a left deep chain of one operator, the shape the parser builds. */
  private static Expression join(final List<Expression> operands, final Expression.BinaryOperator operator,
      final Expression.BinaryOp origin) {
    Expression result = operands.get(0);
    for (int i = 1; i < operands.size(); i++)
      result = new Expression.BinaryOp(origin.position(), operator, result, operands.get(i));
    return result;
  }
}
