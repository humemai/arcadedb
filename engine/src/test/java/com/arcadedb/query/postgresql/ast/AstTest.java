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

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The syntax tree of the {@code postgresql} language: the nodes compare by content and not by position, hold unmodifiable
 * copies of their lists, refuse the shapes the grammar cannot produce, and can be read with an exhaustive {@code switch}.
 */
class AstTest {
  private static final SourcePosition P1 = new SourcePosition(1, 1, 0);
  private static final SourcePosition P2 = new SourcePosition(2, 7, 20);

  private static Name name(final String value) {
    return new Name(P1, value, false);
  }

  private static Expression.IntegerLiteral integer(final long value) {
    return new Expression.IntegerLiteral(P1, BigInteger.valueOf(value));
  }

  private static SelectStatement select(final List<SelectItem> items, final List<TableReference> from) {
    return new SelectStatement(P1, false, items, from, null, List.of(), null, List.of(), null, null);
  }

  @Test
  void positionsAreReadableButNeverTakePartInEquality() {
    assertThat(P2.line()).isEqualTo(2);
    assertThat(P2.column()).isEqualTo(7);
    assertThat(P2.offset()).isEqualTo(20);
    assertThat(P2).hasToString("line 2, column 7");

    // the same content at two places is the same tree: this is what the round trip of the printer relies on
    assertThat(new Name(P1, "person", false)).isEqualTo(new Name(P2, "person", false));
    assertThat(new Name(P1, "person", false).hashCode()).isEqualTo(new Name(P2, "person", false).hashCode());
    // but the content still counts
    assertThat(new Name(P1, "person", false)).isNotEqualTo(new Name(P1, "person", true));
    assertThat(new Name(P1, "person", false)).isNotEqualTo(new Name(P1, "orders", false));
  }

  @Test
  void aSelectOfTheDemoShapeIsBuiltFromTheNodes() {
    // SELECT p.name, o.oid FROM person p LEFT JOIN orders o ON o.pid = p.pid ORDER BY p.name, o.oid
    final Expression pName = new Expression.ColumnRef(P1, List.of(name("p")), name("name"));
    final Expression oOid = new Expression.ColumnRef(P1, List.of(name("o")), name("oid"));
    final Expression condition = new Expression.BinaryOp(P1, Expression.BinaryOperator.EQUAL,
        new Expression.ColumnRef(P1, List.of(name("o")), name("pid")),
        new Expression.ColumnRef(P1, List.of(name("p")), name("pid")));
    final TableReference join = new TableReference.Join(P1, new TableReference.Table(P1, List.of(name("person")), false, name("p")),
        TableReference.JoinType.LEFT, new TableReference.Table(P1, List.of(name("orders")), false, name("o")), condition);

    final SelectStatement statement = new SelectStatement(P1, false,
        List.of(new SelectItem.Projection(P1, pName, null), new SelectItem.Projection(P1, oOid, null)), List.of(join), null,
        List.of(), null, List.of(new SortItem(P1, pName, SortItem.Direction.DEFAULT, SortItem.Nulls.DEFAULT),
        new SortItem(P1, oOid, SortItem.Direction.DEFAULT, SortItem.Nulls.DEFAULT)), null, null);

    assertThat(statement.items()).hasSize(2);
    assertThat(statement.from()).hasSize(1);
    assertThat(statement.orderBy()).hasSize(2);
    assertThat(((TableReference.Join) statement.from().get(0)).type()).isEqualTo(TableReference.JoinType.LEFT);
  }

  @Test
  void listsAreUnmodifiableCopies() {
    final List<SelectItem> items = new ArrayList<>(List.of(new SelectItem.Star(P1, List.of())));
    final SelectStatement statement = select(items, List.of());

    items.add(new SelectItem.Star(P1, List.of()));

    assertThat(statement.items()).hasSize(1);
    assertThatThrownBy(() -> statement.items().add(new SelectItem.Star(P1, List.of())))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theShapesTheGrammarCannotProduceAreRefused() {
    assertThatThrownBy(() -> select(List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Expression.InList(P1, integer(1), List.of(), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Expression.Case(P1, null, List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Expression.FunctionCall(P1, List.of(), List.of(), false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Expression.FunctionCall(P1, List.of(name("count")), List.of(integer(1)), false, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TableReference.Table(P1, List.of(), false, null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TypeName(P1, List.of(), List.of(), null, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Name(P1, null, false)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Expression.BinaryOp(P1, Expression.BinaryOperator.ADD, integer(1), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void theVectorOperatorsHaveTheirOwnValues() {
    assertThat(Expression.BinaryOperator.L2_DISTANCE.symbol()).isEqualTo("<->");
    assertThat(Expression.BinaryOperator.COSINE_DISTANCE.symbol()).isEqualTo("<=>");
    assertThat(Expression.BinaryOperator.NEGATIVE_INNER_PRODUCT.symbol()).isEqualTo("<#>");
    assertThat(Expression.UnaryOperator.NOT.symbol()).isEqualTo("NOT");
  }

  @Test
  void everyExpressionAndStatementCanBeSwitchedOnExhaustively() {
    // this compiles only while the switches below name every permitted type: a node added without updating its consumers
    // breaks the build here first
    final Expression expression = integer(1);
    final String kind = switch (expression) {
      case Expression.IntegerLiteral e -> "integer";
      case Expression.DecimalLiteral e -> "decimal";
      case Expression.StringLiteral e -> "string";
      case Expression.BooleanLiteral e -> "boolean";
      case Expression.NullLiteral e -> "null";
      case Expression.ParameterRef e -> "parameter";
      case Expression.ColumnRef e -> "column";
      case Expression.UnaryOp e -> "unary";
      case Expression.BinaryOp e -> "binary";
      case Expression.IsNull e -> "isnull";
      case Expression.InList e -> "in";
      case Expression.InSubquery e -> "in-subquery";
      case Expression.ScalarSubquery e -> "scalar-subquery";
      case Expression.Exists e -> "exists";
      case Expression.Between e -> "between";
      case Expression.Like e -> "like";
      case Expression.Case e -> "case";
      case Expression.Cast e -> "cast";
      case Expression.FunctionCall e -> "function";
      case Expression.ArrayConstructor e -> "array";
      case Expression.SpecialFunction e -> "special";
      case Expression.Quantified e -> "quantified";
    };
    assertThat(kind).isEqualTo("integer");

    final Statement statement = new EmptyStatement(P1);
    final String statementKind = switch (statement) {
      case SelectStatement s -> "select";
      case SetOperationStatement s -> "set operation";
      case WithStatement s -> "with";
      case ExplainStatement s -> "explain";
      case EmptyStatement s -> "empty";
      case ExplainWriteStatement s -> "explain-write";
      case WriteStatement s -> "write";
      case TransactionStatement s -> "transaction";
      case CreateTableStatement s -> "create table";
      case CreateIndexStatement s -> "create index";
      case CreateExtensionStatement s -> "create extension";
      case AlterTableStatement s -> "alter table";
      case DropStatement s -> "drop";
    };
    assertThat(statementKind).isEqualTo("empty");

    final TableReference table = new TableReference.Table(P1, List.of(name("person")), true, null);
    final String tableKind = switch (table) {
      case TableReference.Table t -> "table";
      case TableReference.Join j -> "join";
      case TableReference.Subquery q -> "subquery";
    };
    assertThat(tableKind).isEqualTo("table");

    final SelectItem item = new SelectItem.Star(P1, List.of());
    final String itemKind = switch (item) {
      case SelectItem.Projection i -> "projection";
      case SelectItem.Star i -> "star";
    };
    assertThat(itemKind).isEqualTo("star");
  }
}
