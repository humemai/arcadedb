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
import com.arcadedb.query.postgresql.PostgreSQLCatalogException;
import com.arcadedb.query.postgresql.PostgreSQLReferenceException;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.SelectItem;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.Identifier;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.ExpressionCompiler;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.eval.SubqueryPlanner;
import com.arcadedb.query.postgresql.plan.infoschema.InformationSchema;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the column references of one {@code SELECT} against the columns its {@code FROM} clause produces: the binder. A
 * reference is {@code name}, {@code relation.name} or {@code schema.relation.name}, where the relation is the alias of a table
 * or, with no alias, the table name. The rules of question 7 of the prototype decisions hold for every part: a quoted name
 * matches exactly, an unquoted name (already folded to lower case by the parser) matches exactly first and then, only if nothing
 * matched, the one name that differs in case.
 *
 * <ul>
 * <li>No such column: {@code 42703}. A relation that is not in the {@code FROM} clause: {@code 42P01}.</li>
 * <li>A name that two columns carry ({@code name} when two tables have it, two columns that differ only in case): {@code 42702}. A
 * column that the catalog does not list, a hidden record id, is not counted beside one that it lists (fork issues #151 and
 * #163).</li>
 * <li>A schema that is not {@code public} or the name of the database: {@code 3F000}.</li>
 * </ul>
 *
 * The scope knows columns by their position in the rows of the source, so it serves a join as well as a table.
 */
public final class RelationScope implements ExpressionCompiler.Scope {
  private static final int MAX_LISTED_RELATIONS = 5;

  private final List<RelationSource.Field> fields;
  private final RelationalCatalog          catalog;
  private final SubqueryPlanner            subqueries;
  private final List<RelationSource.Output> outputs;
  private final ExpressionCompiler.Scope   outer;
  /** The positions of the row that the expressions compiled with this scope read (fork issue #85). */
  private final BitSet                     used = new BitSet();
  /** True once an expression of this scope asked for a subquery: what such an expression reads of the row is not tracked here. */
  private boolean                          subqueryAsked;

  public RelationScope(final List<RelationSource.Field> fields, final RelationalCatalog catalog) {
    this(fields, catalog, null);
  }

  /**
   * @param subqueries plans the subqueries of the expressions of this scope, {@code null} where the clause has none
   */
  public RelationScope(final List<RelationSource.Field> fields, final RelationalCatalog catalog, final SubqueryPlanner subqueries) {
    this(fields, catalog, subqueries, null);
  }

  /**
   * @param outputs the columns that an unqualified name or {@code *} reads, {@code null} for every field in order; a qualified
   *                name reads the fields whatever this says
   */
  public RelationScope(final List<RelationSource.Field> fields, final RelationalCatalog catalog, final SubqueryPlanner subqueries,
      final List<RelationSource.Output> outputs) {
    this(fields, catalog, subqueries, outputs, null);
  }

  /**
   * @param outer the scope of the query around a correlated subquery, asked for a name that this scope does not have; {@code null}
   *              for a query that is not inside another one
   */
  public RelationScope(final List<RelationSource.Field> fields, final RelationalCatalog catalog, final SubqueryPlanner subqueries,
      final List<RelationSource.Output> outputs, final ExpressionCompiler.Scope outer) {
    this.fields = List.copyOf(fields);
    this.catalog = catalog;
    this.subqueries = subqueries;
    this.outputs = outputs == null ? null : List.copyOf(outputs);
    this.outer = outer;
  }

  /** The output that an unqualified name stands for: the exact name first, then the one that differs in case (not for a quoted name). */
  public RelationSource.Output output(final Name column) {
    for (int pass = 0; pass < 2; pass++) {
      final List<RelationSource.Output> matches = new ArrayList<>();
      for (final RelationSource.Output output : outputs)
        if (same(column, output.name(), pass))
          matches.add(output);
      if (matches.size() > 1) {
        // a column that the catalog does not list gives way to a column that it lists
        final List<RelationSource.Output> listed = matches.stream().filter(match -> fields.get(match.index()).listed()).toList();
        if (listed.size() == 1)
          return listed.get(0);
      }
      if (matches.size() == 1)
        return matches.get(0);
      if (matches.size() > 1) {
        final List<String> names = new ArrayList<>();
        for (final RelationSource.Output match : matches)
          names.add(fields.get(match.index()).relation().isEmpty() ? match.name()
              : fields.get(match.index()).relation() + "." + match.name());
        throw PostgreSQLAmbiguousIdentifierException.column(column.value(), names);
      }
      if (column.quoted())
        break;
    }
    throw PostgreSQLReferenceException.undefinedColumn(column.value(), unknownColumnHint(null, column));
  }

  @Override
  public SubqueryPlanner subqueries() {
    subqueryAsked = true;
    return subqueries;
  }

  /**
   * The positions of the row that the expressions compiled with this scope so far read: every column reference goes through this
   * scope, and each one marks its position. A copy.
   */
  public BitSet usedColumns() {
    return (BitSet) used.clone();
  }

  /** Whether an expression of this scope has a subquery, which can read the row of the query around it by a path this scope does not mark. */
  public boolean askedForSubquery() {
    return subqueryAsked;
  }

  public List<RelationSource.Field> fields() {
    return fields;
  }

  /**
   * A scope of the same columns, in the same query, that has recorded nothing yet: {@link #usedColumns} then says which of these
   * columns the expressions compiled with it read, the subqueries inside them included.
   */
  public RelationScope unused() {
    return new RelationScope(fields, catalog, subqueries, outputs, outer);
  }

  /** What an unqualified name finds among the columns of this query alone. */
  private enum Found {
    NOTHING, UNLISTED, LISTED
  }

  private Found find(final Name column) {
    final int width = outputs == null ? fields.size() : outputs.size();
    for (int pass = 0; pass < 2; pass++) {
      boolean unlisted = false;
      for (int i = 0; i < width; i++) {
        if (!same(column, outputs == null ? fields.get(i).name() : outputs.get(i).name(), pass))
          continue;
        if (fields.get(outputs == null ? i : outputs.get(i).index()).listed())
          return Found.LISTED;
        unlisted = true;
      }
      if (unlisted)
        return Found.UNLISTED;
      if (column.quoted())
        break;
    }
    return Found.NOTHING;
  }

  @Override
  public boolean shows(final Name column) {
    return showsHere(column) || outer != null && outer.shows(column);
  }

  @Override
  public boolean showsHere(final Name column) {
    return find(column) == Found.LISTED;
  }

  /**
   * Whether the reference is an unqualified name that this query has only as columns that the catalog does not list, and that a
   * query around it shows: it then reads that query's column, as in PostgreSQL, whose table has no such column (fork issue #157).
   */
  boolean givesWay(final Expression.ColumnRef reference, final ExpressionCompiler around) {
    return reference.qualifier().isEmpty() && find(reference.column()) == Found.UNLISTED && around.shows(reference.column());
  }

  /**
   * The column of this query, or else of the query around it. The innermost scope wins, as in PostgreSQL: the outer one is asked only
   * when the relation of a qualified name is not in this {@code FROM} clause ({@code 42P01}) or an unqualified name is no column of
   * it ({@code 42703}); a name that is ambiguous here, or a column that a relation of this scope lacks, stays an error. One
   * exception (fork issue #157): a column that the catalog does not list gives way to a column that a query around this one shows.
   */
  @Override
  public Evaluable column(final Expression.ColumnRef reference) {
    if (outer == null)
      return own(reference);
    if (reference.qualifier().isEmpty() && find(reference.column()) == Found.UNLISTED && outer.shows(reference.column()))
      return outer.column(reference);
    try {
      return own(reference);
    } catch (final PostgreSQLReferenceException notHere) {
      if (!(reference.qualifier().isEmpty() ? "42703" : "42P01").equals(notHere.getSqlState()))
        throw notHere;
      try {
        return outer.column(reference);
      } catch (final PostgreSQLReferenceException notThereEither) {
        // the relation is the outer query's but has no such column (42703) says more than that this scope lacks the relation; an
        // unqualified name is reported with the columns of this query
        throw reference.qualifier().isEmpty() || "42P01".equals(notThereEither.getSqlState()) ? notHere : notThereEither;
      }
    }
  }

  /**
   * A join that compares the record ids of a column with the numeric key of a table names the record id column of that table,
   * which is what the column can be joined with.
   */
  @Override
  public String recordIdHint(final Expression ids, final Expression number) {
    final RelationSource.Field field = fieldOf(ids);
    if (field == null || field.recordIds() == RelationSource.RecordIds.NONE)
      return outer == null ? null : outer.recordIdHint(ids, number);
    final String column = qualified(field);
    final String holds = column + " holds record ids, which are text such as '#12:3'";
    final RelationSource.Field other = fieldOf(number);
    if (other != null && !other.relation().equals(field.relation()))
      for (final RelationSource.Field candidate : fields)
        if (candidate.relation().equals(other.relation()) && candidate.recordIds() == RelationSource.RecordIds.OWN)
          return holds + ", not the values of " + qualified(other) + ": join on " + qualified(candidate)
              + ". A cast will not match them";
    if (field.recordIds() == RelationSource.RecordIds.REFERRED)
      return holds + ": join the table it refers to on that table's rid and filter by its key. A cast will not match them";
    for (final RelationSource.Field candidate : fields)
      if (candidate.relation().equals(field.relation()) && candidate.recordIds() == RelationSource.RecordIds.NONE
          && Column.ID.equals(candidate.name()))
        return holds + ": compare " + qualified(candidate) + ", the key of the table, with the number";
    return holds + ", not numbers: compare it with such a text, or name the row by another column. A cast will not match them";
  }

  @Override
  public String recordIdColumn(final Expression expression) {
    final RelationSource.Field field = fieldOf(expression);
    // a name that this query does not have, or has only as a hidden column that gives way, is the one of the query around
    if (outer != null && (field == null || expression instanceof Expression.ColumnRef reference && reference.qualifier().isEmpty()
        && find(reference.column()) == Found.UNLISTED && outer.shows(reference.column())))
      return outer.recordIdColumn(expression);
    return field == null || field.recordIds() == RelationSource.RecordIds.NONE ? null : qualified(field);
  }

  /** The column of this query that a reference reads, {@code null} when it is not one or several could be meant. */
  private RelationSource.Field fieldOf(final Expression expression) {
    if (!(expression instanceof Expression.ColumnRef reference) || reference.qualifier().size() > 2)
      return null;
    final Name relation = reference.qualifier().isEmpty() ? null : reference.qualifier().get(reference.qualifier().size() - 1);
    final List<RelationSource.Field> found = new ArrayList<>(1);
    for (final RelationSource.Field field : fields)
      if ((same(reference.column(), field.name(), 0) || same(reference.column(), field.name(), 1))
          && (relation == null || sameRelation(relation, field.relation())))
        found.add(field);
    if (found.size() > 1)
      // a column that the catalog does not list gives way to a column that it lists
      found.removeIf(field -> !field.listed());
    return found.size() == 1 ? found.get(0) : null;
  }

  private static String qualified(final RelationSource.Field field) {
    return field.relation().isEmpty() ? field.name() : field.relation() + "." + field.name();
  }

  private Evaluable own(final Expression.ColumnRef reference) {
    final List<Name> qualifier = reference.qualifier();
    Name relation = null;
    if (qualifier.size() == 2) {
      requireSchema(qualifier.get(0));
      relation = qualifier.get(1);
    } else if (qualifier.size() == 1)
      relation = qualifier.get(0);
    if (relation == null && outputs != null) {
      final RelationSource.Output output = output(reference.column());
      final RelationSource.Field source = fields.get(output.index());
      used.set(output.index());
      if (output.type() == source.type())
        return new Evaluable.Slot(source.type(), source.typeName(), output.index());
      // a merged column of two types is read in the common type
      final int position = output.index();
      final PgType from = source.type();
      final PgType to = output.type();
      return new Evaluable.Node(to, row -> SetTypes.convert(row[position], from, to));
    }
    final int index = resolve(relation, reference.column());
    used.set(index);
    final RelationSource.Field field = fields.get(index);
    return new Evaluable.Slot(field.type(), field.typeName(), index);
  }

  /** The schema part of a qualified name: {@code information_schema} (its views are relations of the scope) or a schema of the catalog. */
  private void requireSchema(final Name schema) {
    if (!InformationSchema.isSchema(schema))
      catalog.requireSchema(new Identifier(schema.value(), schema.quoted()));
  }

  private int resolve(final Name relation, final Name column) {
    for (int pass = 0; pass < 2; pass++) {
      int first = -1;
      List<Integer> more = null;
      for (int i = 0; i < fields.size(); i++) {
        final RelationSource.Field field = fields.get(i);
        // the name first: it differs for most columns, and then the relation is not compared at all
        if (!same(column, field.name(), pass) || (relation != null && !sameRelation(relation, field.relation())))
          continue;
        if (first < 0)
          first = i;
        else {
          if (more == null)
            more = new ArrayList<>();
          more.add(i);
        }
      }
      if (more != null) {
        more.add(0, first);
        // a column that the catalog does not list gives way to a column that it lists
        final List<Integer> listed = more.stream().filter(match -> fields.get(match).listed()).toList();
        if (listed.size() == 1)
          return listed.get(0);
        final List<String> names = new ArrayList<>();
        for (final int match : more)
          names.add(fields.get(match).relation().isEmpty() ? fields.get(match).name()
              : fields.get(match).relation() + "." + fields.get(match).name());
        throw PostgreSQLAmbiguousIdentifierException.column(column.value(), names);
      }
      if (first >= 0)
        return first;
      if (column.quoted())
        break;
    }
    // no column matched: a relation that is not in the FROM clause is reported before the column
    if (relation != null && !hasRelation(relation))
      throw PostgreSQLReferenceException.missingFromEntry(relation.value());
    throw PostgreSQLReferenceException.undefinedColumn(relation == null ? column.value() : relation.value() + "." + column.value(),
        unknownColumnHint(relation, column));
  }

  /**
   * What a statement could have named instead of a column that does not exist, for the hint of the error: the closest column, and
   * the columns of every relation of this query, or of the relation the name is qualified with. {@code null} when no relation has
   * a name.
   */
  private String unknownColumnHint(final Name relation, final Name column) {
    // id where a table in reach has a record id and no column id: say where the record id is (fork issue #240)
    if (PostgreSQLCatalogException.isId(column.value(), column.quoted()))
      for (final RelationSource.Field field : fields)
        if (field.recordIds() == RelationSource.RecordIds.OWN && (relation == null || sameRelation(relation, field.relation())))
          return PostgreSQLCatalogException.NO_ID_HINT;
    final Map<String, List<String>> columns = new LinkedHashMap<>();
    for (final RelationSource.Field field : fields)
      if (!field.hidden() && !field.relation().isEmpty() && (relation == null || sameRelation(relation, field.relation())))
        columns.computeIfAbsent(field.relation(), name -> new ArrayList<>()).add(field.name());
    if (columns.isEmpty())
      return null;
    return PostgreSQLCatalogException.unknownColumnHint(column.value(), columns, columns.entrySet().stream()
        .limit(MAX_LISTED_RELATIONS).map(entry -> PostgreSQLCatalogException.columnList(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining("; ")));
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
    if (relation == null && outputs != null) {
      for (final RelationSource.Output output : outputs) {
        final RelationSource.Field field = fields.get(output.index());
        if (field.hidden())
          continue;
        if (output.type() != field.type())
          // a merged column of two types: its name stands for it, and is read in the common type
          out.add(new Expression.ColumnRef(star.position(), List.of(), new Name(star.position(), output.name(), true)));
        else
          out.add(new Expression.ColumnRef(star.position(), List.of(new Name(star.position(), field.relation(), true)),
              new Name(star.position(), field.name(), true)));
      }
      return out;
    }
    for (final RelationSource.Field field : fields) {
      if (field.hidden() || relation != null && !sameRelation(relation, field.relation()))
        continue;
      out.add(new Expression.ColumnRef(star.position(), List.of(new Name(star.position(), field.relation(), true)),
          new Name(star.position(), field.name(), true)));
    }
    return out;
  }
}
