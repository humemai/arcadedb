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
package com.arcadedb.query.postgresql.plan.join;

import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.plan.RelationScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a condition of a join query is evaluated. A condition is a top level {@code AND} term (a conjunct) of a {@code WHERE} or
 * {@code ON} clause, and the relations it mentions decide the node of the join tree that can evaluate it. The rule is the one
 * PostgreSQL's planner applies; it is written down here because a wrong placement changes the answer, not only the speed.
 *
 * <p>At a join node with a left input and a right input, an incoming {@code WHERE} term and each term of the node's own
 * {@code ON} clause is placed like this:
 *
 * <table>
 * <caption>Placement of a conjunct at a join node</caption>
 * <tr><th>term</th><th>{@code INNER} join</th><th>{@code LEFT} join</th></tr>
 * <tr><td>{@code WHERE}, mentions only the left input (or nothing)</td><td>pushed into the left input</td>
 * <td>pushed into the left input (the preserved side: the term filters rows that are all kept or all dropped)</td></tr>
 * <tr><td>{@code WHERE}, mentions only the right input</td><td>pushed into the right input</td>
 * <td><b>stays above the join</b>: the right input is the null-supplying side, and a term on it is evaluated on the padded rows
 * (a row of the left input with no match has {@code NULL} there), so pushing it below would keep rows the term drops</td></tr>
 * <tr><td>{@code WHERE}, mentions both inputs</td><td>joins the match condition of the node (an equality becomes a join key, the
 * rest a residual)</td><td>stays above the join</td></tr>
 * <tr><td>{@code ON}, mentions only the left input</td><td>pushed into the left input</td>
 * <td><b>part of the match condition</b>: it decides whether a left row has a partner, it must not drop the left row</td></tr>
 * <tr><td>{@code ON}, mentions only the right input</td><td>pushed into the right input</td><td>pushed into the right input (it
 * only limits which right rows can be partners)</td></tr>
 * <tr><td>{@code ON}, mentions both inputs or no relation</td><td colspan="2">part of the match condition of the node: an
 * equality between an expression over the left input and an expression over the right input is a join key, anything else is the
 * residual that is evaluated on the combined row</td></tr>
 * </table>
 *
 * <p>The descent stops at a null-supplying input: a term is never passed into the right input of a {@code LEFT} join, only an
 * {@code ON} term of that join, so a {@code WHERE} term never moves across the padding. The term that stays above a {@code LEFT}
 * join is applied to the rows of that join, below the next join up if there is one (it mentions only relations of its own
 * subtree) or as the {@code WHERE} filter of the statement. PostgreSQL turns a {@code LEFT} join into an {@code INNER} join when
 * a {@code WHERE} term on the null-supplying side rejects {@code NULL}; this step does not: the term stays above the
 * {@code LEFT} join, which gives the same rows without the faster plan.
 *
 * <p>A term that mentions no relation ({@code WHERE false}) is placed with the left input, which is valid for both join types.
 * A comma list ({@code FROM a, b}) is a chain of {@code INNER} joins with no {@code ON} term, so the {@code WHERE} terms that
 * mention both sides become its match condition, which is what PostgreSQL does with them.
 */
public final class Pushdown {
  private Pushdown() {
  }

  /**
   * One term of an {@code AND} chain and the relations it reads.
   *
   * @param expression the term
   * @param relations  the names (alias or table name) of the relations whose columns it mentions, in order of appearance
   */
  public record Conjunct(Expression expression, Set<String> relations) {
    /** True when every relation the term mentions is in {@code side}; a term that mentions none is within every side. */
    boolean within(final Set<String> side) {
      return side.containsAll(relations);
    }

    boolean mentionsAny() {
      return !relations.isEmpty();
    }
  }

  /**
   * The placement of the terms of one join node.
   *
   * @param toLeft   terms pushed into the left input
   * @param toRight  terms pushed into the right input
   * @param matching terms that decide which row pairs match: join keys and the residual
   * @param above    terms that stay above this node
   */
  public record Placement(List<Conjunct> toLeft, List<Conjunct> toRight, List<Conjunct> matching, List<Conjunct> above) {
  }

  /**
   * Places the incoming {@code WHERE} terms and the {@code ON} terms of a join node by the table of the class comment.
   *
   * @param incoming the {@code WHERE} terms this node may evaluate, each within the relations of the node
   * @param on       the {@code ON} terms of the node
   */
  public static Placement place(final TableReference.JoinType type, final Set<String> left, final Set<String> right,
      final List<Conjunct> incoming, final List<Conjunct> on) {
    final boolean inner = type == TableReference.JoinType.INNER;
    final List<Conjunct> toLeft = new ArrayList<>();
    final List<Conjunct> toRight = new ArrayList<>();
    final List<Conjunct> matching = new ArrayList<>();
    final List<Conjunct> above = new ArrayList<>();
    for (final Conjunct term : incoming) {
      if (term.within(left))
        toLeft.add(term);
      else if (inner && term.within(right))
        toRight.add(term);
      else if (inner)
        matching.add(term);
      else
        above.add(term);
    }
    for (final Conjunct term : on) {
      if (inner && term.mentionsAny() && term.within(left))
        toLeft.add(term);
      else if (term.mentionsAny() && term.within(right))
        toRight.add(term);
      else
        matching.add(term);
    }
    return new Placement(toLeft, toRight, matching, above);
  }

  /** The top level {@code AND} terms of a condition, left to right. */
  public static List<Expression> conjuncts(final Expression expression) {
    final List<Expression> out = new ArrayList<>();
    if (expression instanceof Expression.BinaryOp op && op.operator() == Expression.BinaryOperator.AND) {
      out.addAll(conjuncts(op.left()));
      out.addAll(conjuncts(op.right()));
    } else
      out.add(expression);
    return out;
  }

  /** The terms of a condition with the relations each one reads, resolved in {@code scope}. */
  static List<Conjunct> terms(final Expression condition, final RelationScope scope) {
    final List<Conjunct> out = new ArrayList<>();
    for (final Expression term : conjuncts(condition))
      out.add(new Conjunct(term, relations(term, scope)));
    return out;
  }

  /**
   * The relations whose columns an expression mentions. Every column reference is resolved in {@code scope}, so an unknown or
   * ambiguous name is the error PostgreSQL gives ({@code 42703}, {@code 42702}, {@code 42P01}).
   */
  static Set<String> relations(final Expression expression, final RelationScope scope) {
    final Set<String> out = new LinkedHashSet<>();
    collect(expression, scope, out);
    return out;
  }

  private static void collect(final Expression expression, final RelationScope scope, final Set<String> out) {
    if (expression instanceof Expression.ColumnRef reference) {
      if (scope.column(reference) instanceof Evaluable.Slot slot)
        out.add(scope.fields().get(slot.index()).relation());
      return;
    }
    for (final Expression child : ExpressionCompiler.children(expression))
      collect(child, scope, out);
  }
}
