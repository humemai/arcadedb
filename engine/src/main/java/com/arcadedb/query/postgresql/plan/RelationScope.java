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

import com.arcadedb.query.postgresql.PostgreSQLAmbiguousIdentifierException;
import com.arcadedb.query.postgresql.PostgreSQLReferenceException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.eval.SubqueryPlanner;
import com.arcadedb.query.postgresql.plan.infoschema.InformationSchema;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the column references of one {@code SELECT} against the columns its {@code FROM} clause produces: the binder. A
 * reference is {@code name}, {@code relation.name} or {@code schema.relation.name}, where the relation is the alias of a table
 * or, with no alias, the table name. The rules of question 7 of the prototype decisions hold for every part: a quoted name
 * matches exactly, an unquoted name (already folded to lower case by the parser) matches exactly first and then, only if nothing
 * matched, the one name that differs in case.
 *
 * <ul>
 * <li>No such column: {@code 42703}. A relation that is not in the {@code FROM} clause: {@code 42P01}.</li>
 * <li>A name that two columns carry ({@code name} when two tables have it, two columns that differ only in case): {@code 42702}.</li>
 * <li>A schema that is not {@code public} or the name of the database: {@code 3F000}.</li>
 * </ul>
 *
 * The scope knows columns by their position in the rows of the source, so it serves a join as well as a table.
 */
public final class RelationScope implements ExpressionCompiler.Scope {
  private final List<RelationSource.Field> fields;
  private final RelationalCatalog          catalog;
  private final SubqueryPlanner            subqueries;

  public RelationScope(final List<RelationSource.Field> fields, final RelationalCatalog catalog) {
    this(fields, catalog, null);
  }

  /**
   * @param subqueries plans the subqueries of the expressions of this scope, {@code null} where the clause has none
   */
  public RelationScope(final List<RelationSource.Field> fields, final RelationalCatalog catalog, final SubqueryPlanner subqueries) {
    this.fields = List.copyOf(fields);
    this.catalog = catalog;
    this.subqueries = subqueries;
  }

  @Override
  public SubqueryPlanner subqueries() {
    return subqueries;
  }

  public List<RelationSource.Field> fields() {
    return fields;
  }

  @Override
  public Evaluable column(final Expression.ColumnRef reference) {
    final List<Name> qualifier = reference.qualifier();
    Name relation = null;
    if (qualifier.size() == 2) {
      requireSchema(qualifier.get(0));
      relation = qualifier.get(1);
    } else if (qualifier.size() == 1)
      relation = qualifier.get(0);
    final int index = resolve(relation, reference.column());
    final RelationSource.Field field = fields.get(index);
    return new Evaluable.Slot(field.type(), field.typeName(), index);
  }

  /** The schema part of a qualified name: {@code information_schema} (its views are relations of the scope) or a schema of the catalog. */
  private void requireSchema(final Name schema) {
    if (!InformationSchema.isSchema(schema))
      catalog.requireSchema(new Identifier(schema.value(), schema.quoted()));
  }

  private int resolve(final Name relation, final Name column) {
    if (relation != null && !hasRelation(relation))
      throw PostgreSQLReferenceException.missingFromEntry(relation.value());
    for (int pass = 0; pass < 2; pass++) {
      final List<Integer> matches = new ArrayList<>();
      for (int i = 0; i < fields.size(); i++) {
        final RelationSource.Field field = fields.get(i);
        if (relation != null && !sameRelation(relation, field.relation()))
          continue;
        if (same(column, field.name(), pass))
          matches.add(i);
      }
      if (matches.size() == 1)
        return matches.get(0);
      if (matches.size() > 1) {
        final List<String> names = new ArrayList<>();
        for (final int match : matches)
          names.add(fields.get(match).relation().isEmpty() ? fields.get(match).name()
              : fields.get(match).relation() + "." + fields.get(match).name());
        throw PostgreSQLAmbiguousIdentifierException.column(column.value(), names);
      }
      if (column.quoted())
        break;
    }
    throw PostgreSQLReferenceException.undefinedColumn(relation == null ? column.value() : relation.value() + "." + column.value());
  }

  private boolean hasRelation(final Name relation) {
    for (final RelationSource.Field field : fields)
      if (sameRelation(relation, field.relation()))
        return true;
    return false;
  }

  private static boolean sameRelation(final Name relation, final String actual) {
    return same(relation, actual, 0) || same(relation, actual, 1);
  }

  /** Pass 0 is the exact match, pass 1 the match that ignores case, which a quoted name never has. */
  private static boolean same(final Name name, final String actual, final int pass) {
    return pass == 0 ? actual.equals(name.value()) : !name.quoted() && actual.equalsIgnoreCase(name.value());
  }

  /**
   * The column references that {@code *} or {@code relation.*} stands for, in the order of the columns. They are quoted
   * references, so they resolve to exactly the column they were made from.
   */
  public List<Expression.ColumnRef> expand(final SelectItem.Star star) {
    final List<Name> qualifier = star.qualifier();
    Name relation = null;
    if (!qualifier.isEmpty()) {
      if (qualifier.size() == 2)
        requireSchema(qualifier.get(0));
      relation = qualifier.get(qualifier.size() - 1);
      if (!hasRelation(relation))
        throw PostgreSQLReferenceException.missingFromEntry(relation.value());
    } else if (fields.isEmpty())
      throw new PostgreSQLEvaluationException("42601", "SELECT * with no tables specified is not valid");
    final List<Expression.ColumnRef> out = new ArrayList<>();
    for (final RelationSource.Field field : fields) {
      if (relation != null && !sameRelation(relation, field.relation()))
        continue;
      out.add(new Expression.ColumnRef(star.position(), List.of(new Name(star.position(), field.relation(), true)),
          new Name(star.position(), field.name(), true)));
    }
    return out;
  }
}
