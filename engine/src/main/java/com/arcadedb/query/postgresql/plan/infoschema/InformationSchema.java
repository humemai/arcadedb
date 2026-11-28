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
package com.arcadedb.query.postgresql.plan.infoschema;

import com.arcadedb.database.Database;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.query.postgresql.ast.Name;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.plan.CheckClause;
import com.arcadedb.query.postgresql.plan.RelationSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.arcadedb.query.postgresql.plan.infoschema.InfoType.CARDINAL_NUMBER;
import static com.arcadedb.query.postgresql.plan.infoschema.InfoType.CHARACTER_DATA;
import static com.arcadedb.query.postgresql.plan.infoschema.InfoType.SQL_IDENTIFIER;
import static com.arcadedb.query.postgresql.plan.infoschema.InfoType.YES_OR_NO;

/**
 * The schema {@value #SCHEMA} of the {@code postgresql} language: the views that a tool reads to find the tables, columns, keys
 * and joins of a database, generated from the {@link RelationalCatalog} snapshot of the statement. Their columns and types are
 * those of PostgreSQL 18 (chapter 35 of the manual, checked against a real server by the answer corpus), their rows describe the
 * relational view and nothing else.
 *
 * <p>The eight views are {@code schemata}, {@code tables}, {@code columns}, {@code table_constraints}, {@code key_column_usage},
 * {@code referential_constraints}, {@code constraint_column_usage} and {@code check_constraints}. They are read-only relations and
 * are themselves listed in {@code tables} and {@code columns}, as views of the schema {@value #SCHEMA}.
 *
 * <p><b>Resolution.</b> A relation written {@code information_schema.x} is resolved here, before the tables of the catalog, so
 * a type can never be reached under that qualifier: {@code information_schema.person} is {@code 42P01} even when a type
 * {@code person} exists, and a type that is itself named {@code information_schema} is still the table of the one-part name
 * {@code information_schema}. The schema is the name {@value #SCHEMA} exactly (an unquoted name is already folded to lower case
 * by the parser, a quoted one is as written). When the database itself is named {@value #SCHEMA}, the alias of the database name
 * for the schema {@code public} is shadowed by this schema.
 *
 * <p><b>What is NULL because the engine has no such information:</b> {@code schemata.schema_owner} and the default character set
 * columns, {@code columns.column_default} (a default of the engine is not part of the relational view, except that a table made by
 * {@code CREATE TABLE} shows the defaults it declared), the collation,
 * character set, domain, scope and interval columns of a column of a table, the start, increment, maximum and minimum of a record
 * id (a column that {@code CREATE TABLE} declared {@code GENERATED ... AS IDENTITY} has them), {@code columns.maximum_cardinality},
 * and
 * {@code tables.self_referencing_column_name}, {@code reference_generation}, the user-defined type columns and
 * {@code commit_action}. What is a constant: {@code is_deferrable} and {@code initially_deferred} are {@code NO} (the keys are
 * declared, nothing checks them and nothing defers them), {@code match_option} is {@code NONE} and the update rule is
 * {@code NO ACTION}, as a {@code CREATE TABLE} without these clauses gives. {@code delete_rule} and {@code enforced} say what the
 * engine does: the foreign keys of the ends of an edge are {@code CASCADE}, because deleting a vertex deletes its edges, and the
 * foreign key of a {@code LINK} property has {@code enforced = 'NO'} (delete rule {@code NO ACTION}), because the record a link
 * points at can be deleted.
 * Not-null columns are {@code is_nullable = 'NO'}; PostgreSQL also lists each of them as a {@code CHECK} constraint in
 * {@code table_constraints}, with a name made of object ids, and the engine does not. The check constraints that a table
 * declares are listed there and in {@code constraint_column_usage} (fork issue #227), and in {@code check_constraints} with its
 * expression as {@link CheckClause} writes it (fork issue #297): a database with a check that it cannot write refuses that view.
 */
public final class InformationSchema {
  /** The name of the schema. */
  public static final String SCHEMA = "information_schema";

  /** The schema of the base types in {@code udt_schema}. */
  private static final String PG_CATALOG = "pg_catalog";

  /** One column of a view: its name and the domain it is declared with. */
  record Col(String name, InfoType type) {
  }

  /** One view: its columns, in order, and its rows for a catalog snapshot. */
  record View(String name, List<Col> columns, Function<RelationalCatalog, List<Object[]>> rows) {
  }

  private static final List<View> VIEWS = List.of(
      new View("schemata", cols("catalog_name", SQL_IDENTIFIER, "schema_name", SQL_IDENTIFIER, "schema_owner", SQL_IDENTIFIER,
          "default_character_set_catalog", SQL_IDENTIFIER, "default_character_set_schema", SQL_IDENTIFIER,
          "default_character_set_name", SQL_IDENTIFIER, "sql_path", CHARACTER_DATA), InformationSchema::schemataRows),
      new View("tables", cols("table_catalog", SQL_IDENTIFIER, "table_schema", SQL_IDENTIFIER, "table_name", SQL_IDENTIFIER,
          "table_type", CHARACTER_DATA, "self_referencing_column_name", SQL_IDENTIFIER, "reference_generation", CHARACTER_DATA,
          "user_defined_type_catalog", SQL_IDENTIFIER, "user_defined_type_schema", SQL_IDENTIFIER, "user_defined_type_name",
          SQL_IDENTIFIER, "is_insertable_into", YES_OR_NO, "is_typed", YES_OR_NO, "commit_action", CHARACTER_DATA),
          InformationSchema::tablesRows),
      new View("columns", cols("table_catalog", SQL_IDENTIFIER, "table_schema", SQL_IDENTIFIER, "table_name", SQL_IDENTIFIER,
          "column_name", SQL_IDENTIFIER, "ordinal_position", CARDINAL_NUMBER, "column_default", CHARACTER_DATA, "is_nullable",
          YES_OR_NO, "data_type", CHARACTER_DATA, "character_maximum_length", CARDINAL_NUMBER, "character_octet_length",
          CARDINAL_NUMBER, "numeric_precision", CARDINAL_NUMBER, "numeric_precision_radix", CARDINAL_NUMBER, "numeric_scale",
          CARDINAL_NUMBER, "datetime_precision", CARDINAL_NUMBER, "interval_type", CHARACTER_DATA, "interval_precision",
          CARDINAL_NUMBER, "character_set_catalog", SQL_IDENTIFIER, "character_set_schema", SQL_IDENTIFIER,
          "character_set_name", SQL_IDENTIFIER, "collation_catalog", SQL_IDENTIFIER, "collation_schema", SQL_IDENTIFIER,
          "collation_name", SQL_IDENTIFIER, "domain_catalog", SQL_IDENTIFIER, "domain_schema", SQL_IDENTIFIER, "domain_name",
          SQL_IDENTIFIER, "udt_catalog", SQL_IDENTIFIER, "udt_schema", SQL_IDENTIFIER, "udt_name", SQL_IDENTIFIER,
          "scope_catalog", SQL_IDENTIFIER, "scope_schema", SQL_IDENTIFIER, "scope_name", SQL_IDENTIFIER, "maximum_cardinality",
          CARDINAL_NUMBER, "dtd_identifier", SQL_IDENTIFIER, "is_self_referencing", YES_OR_NO, "is_identity", YES_OR_NO,
          "identity_generation", CHARACTER_DATA, "identity_start", CHARACTER_DATA, "identity_increment", CHARACTER_DATA,
          "identity_maximum", CHARACTER_DATA, "identity_minimum", CHARACTER_DATA, "identity_cycle", YES_OR_NO, "is_generated",
          CHARACTER_DATA, "generation_expression", CHARACTER_DATA, "is_updatable", YES_OR_NO), InformationSchema::columnsRows),
      new View("table_constraints", cols("constraint_catalog", SQL_IDENTIFIER, "constraint_schema", SQL_IDENTIFIER,
          "constraint_name", SQL_IDENTIFIER, "table_catalog", SQL_IDENTIFIER, "table_schema", SQL_IDENTIFIER, "table_name",
          SQL_IDENTIFIER, "constraint_type", CHARACTER_DATA, "is_deferrable", YES_OR_NO, "initially_deferred", YES_OR_NO,
          "enforced", YES_OR_NO, "nulls_distinct", YES_OR_NO), InformationSchema::tableConstraintsRows),
      new View("key_column_usage", cols("constraint_catalog", SQL_IDENTIFIER, "constraint_schema", SQL_IDENTIFIER,
          "constraint_name", SQL_IDENTIFIER, "table_catalog", SQL_IDENTIFIER, "table_schema", SQL_IDENTIFIER, "table_name",
          SQL_IDENTIFIER, "column_name", SQL_IDENTIFIER, "ordinal_position", CARDINAL_NUMBER, "position_in_unique_constraint",
          CARDINAL_NUMBER), InformationSchema::keyColumnUsageRows),
      new View("referential_constraints", cols("constraint_catalog", SQL_IDENTIFIER, "constraint_schema", SQL_IDENTIFIER,
          "constraint_name", SQL_IDENTIFIER, "unique_constraint_catalog", SQL_IDENTIFIER, "unique_constraint_schema",
          SQL_IDENTIFIER, "unique_constraint_name", SQL_IDENTIFIER, "match_option", CHARACTER_DATA, "update_rule",
          CHARACTER_DATA, "delete_rule", CHARACTER_DATA), InformationSchema::referentialConstraintsRows),
      new View("constraint_column_usage", cols("table_catalog", SQL_IDENTIFIER, "table_schema", SQL_IDENTIFIER, "table_name",
          SQL_IDENTIFIER, "column_name", SQL_IDENTIFIER, "constraint_catalog", SQL_IDENTIFIER, "constraint_schema",
          SQL_IDENTIFIER, "constraint_name", SQL_IDENTIFIER), InformationSchema::constraintColumnUsageRows),
      new View("check_constraints", cols("constraint_catalog", SQL_IDENTIFIER, "constraint_schema", SQL_IDENTIFIER,
          "constraint_name", SQL_IDENTIFIER, "check_clause", CHARACTER_DATA), InformationSchema::checkConstraintsRows));

  private InformationSchema() {
  }

  /** The names of the views, in the order of {@code tables}. */
  public static List<String> viewNames() {
    final List<String> names = new ArrayList<>();
    for (final View view : VIEWS)
      names.add(view.name());
    return names;
  }

  /** Whether a name written as the schema of a relation is this schema (an unquoted name is folded to lower case already). */
  public static boolean isSchema(final Name name) {
    return SCHEMA.equals(name.value());
  }

  /**
   * The source of a {@code FROM} item that names a view of this schema, {@code null} when the item is not qualified with this
   * schema (then it is a table of the catalog).
   *
   * @throws PostgreSQLUndefinedTableException ({@code 42P01}) when the schema is this one and the name is not a view of it
   */
  public static RelationSource resolve(final TableReference.Table reference, final RelationalCatalog catalog,
      final Database database) {
    final List<Name> name = reference.name();
    if (name.size() != 2 || !isSchema(name.get(0)))
      return null;
    final String viewName = name.get(1).value();
    for (final View view : VIEWS)
      if (view.name().equals(viewName)) {
        final String relation = reference.alias() != null ? reference.alias().value() : view.name();
        return new InformationSchemaSource(view, relation, () -> view.rows().apply(catalog.readableBy(database)));
      }
    throw new PostgreSQLUndefinedTableException(SCHEMA + "." + viewName);
  }

  private static List<Col> cols(final Object... namesAndTypes) {
    final List<Col> columns = new ArrayList<>();
    for (int i = 0; i < namesAndTypes.length; i += 2)
      columns.add(new Col((String) namesAndTypes[i], (InfoType) namesAndTypes[i + 1]));
    return List.copyOf(columns);
  }

  private static View view(final String name) {
    for (final View view : VIEWS)
      if (view.name().equals(name))
        return view;
    throw new IllegalArgumentException(name);
  }

  private static Object[] row(final View view, final Object... values) {
    if (values.length != view.columns().size())
      throw new IllegalStateException(view.name() + " has " + view.columns().size() + " columns, a row has " + values.length);
    return values;
  }

  private static String yesNo(final boolean value) {
    return value ? "YES" : "NO";
  }

  // ---- the rows ----

  private static List<Object[]> schemataRows(final RelationalCatalog catalog) {
    final View view = view("schemata");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final String schema : List.of(RelationalCatalog.SCHEMA, SCHEMA))
      rows.add(row(view, db, schema, null, null, null, null, null));
    return rows;
  }

  private static List<Object[]> tablesRows(final RelationalCatalog catalog) {
    final View view = view("tables");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final Table table : catalog.tables())
      rows.add(row(view, db, RelationalCatalog.SCHEMA, table.name(), "BASE TABLE", null, null, null, null, null, "YES", "NO", null));
    for (final View own : VIEWS)
      rows.add(row(view, db, SCHEMA, own.name(), "VIEW", null, null, null, null, null, "NO", "NO", null));
    return rows;
  }

  private static List<Object[]> columnsRows(final RelationalCatalog catalog) {
    final View view = view("columns");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final Table table : catalog.tables()) {
      int position = 0;
      for (final Column column : table.listedColumns()) {
        ++position;
        final ColumnAttributes a = ColumnAttributes.of(column);
        // the record id and the id of an edge are given by the engine and cannot be chosen: GENERATED ALWAYS AS IDENTITY, as
        // PostgreSQL reports a column of that kind (the start, increment, maximum and minimum of a sequence do not apply to
        // them, and are those of PostgreSQL for a declared identity column, fork issue #221)
        final String generation = column.identityGeneration();
        final boolean counted = generation != null && column.kind() == Column.Kind.PROPERTY;
        rows.add(row(view, db, RelationalCatalog.SCHEMA, table.name(), column.name(), position, column.defaultText(),
            yesNo(column.nullable()), a.dataType(), ColumnAttributes.characterMaximumLength(column), a.characterOctetLength(),
            a.numericPrecision(), a.numericPrecisionRadix(), a.numericScale(),
            a.datetimePrecision(), null, null, null, null, null, null, null, null, null, null, null, db, PG_CATALOG, a.udtName(), null,
            null, null, null, Integer.toString(position), "NO", yesNo(generation != null), generation, counted ? "1" : null,
            counted ? "1" : null, counted ? largest(column.type()) : null, counted ? "1" : null, "NO", "NEVER", null, "YES"));
      }
    }
    for (final View own : VIEWS) {
      int position = 0;
      for (final Col column : own.columns()) {
        ++position;
        final ColumnAttributes a = ColumnAttributes.of(column.type());
        rows.add(row(view, db, SCHEMA, own.name(), column.name(), position, null, "YES", a.dataType(),
            ColumnAttributes.characterMaximumLength(column.type()), a.characterOctetLength(), a.numericPrecision(),
            a.numericPrecisionRadix(), a.numericScale(), a.datetimePrecision(), null, null, null, null, null, null, null, null,
            db, SCHEMA, column.type().domain(), db, PG_CATALOG, a.udtName(), null, null, null, null, Integer.toString(position), "NO",
            "NO", null, null, null, null, null, "NO", "NEVER", null, "NO"));
      }
    }
    return rows;
  }

  /** {@code identity_maximum} of an identity column of an integer type. */
  private static String largest(final ColumnType type) {
    return Long.toString(type == ColumnType.INT2 ? Short.MAX_VALUE : type == ColumnType.INT4 ? Integer.MAX_VALUE : Long.MAX_VALUE);
  }

  private static List<Object[]> tableConstraintsRows(final RelationalCatalog catalog) {
    final View view = view("table_constraints");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final Constraint constraint : Constraint.of(catalog))
      rows.add(row(view, db, RelationalCatalog.SCHEMA, constraint.name(), db, RelationalCatalog.SCHEMA, constraint.table(),
          constraint.type(), "NO", "NO", yesNo(constraint.enforced()), Constraint.UNIQUE.equals(constraint.type()) ? "YES" : null));
    return rows;
  }

  private static List<Object[]> keyColumnUsageRows(final RelationalCatalog catalog) {
    final View view = view("key_column_usage");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final Constraint constraint : Constraint.of(catalog))
      // a check constraint constrains no key
      for (int i = 0; !constraint.isCheck() && i < constraint.columns().size(); i++)
        rows.add(row(view, db, RelationalCatalog.SCHEMA, constraint.name(), db, RelationalCatalog.SCHEMA, constraint.table(),
            constraint.columns().get(i), i + 1, constraint.isForeignKey() ? (Object) (i + 1) : null));
    return rows;
  }

  private static List<Object[]> referentialConstraintsRows(final RelationalCatalog catalog) {
    final View view = view("referential_constraints");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final Constraint constraint : Constraint.of(catalog))
      if (constraint.isForeignKey())
        rows.add(row(view, db, RelationalCatalog.SCHEMA, constraint.name(), constraint.referencedKey() == null ? null : db,
            constraint.referencedKey() == null ? null : RelationalCatalog.SCHEMA, constraint.referencedKey(), "NONE",
            constraint.updateRule(), constraint.deleteRule()));
    return rows;
  }

  private static List<Object[]> checkConstraintsRows(final RelationalCatalog catalog) {
    final View view = view("check_constraints");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final Table table : catalog.tables())
      for (final CheckClause.Declared check : CheckClause.declaredOn(table.documentType()))
        rows.add(row(view, db, RelationalCatalog.SCHEMA, check.name(),
            CheckClause.of(check, table.documentType(), false)));
    return rows;
  }

  private static List<Object[]> constraintColumnUsageRows(final RelationalCatalog catalog) {
    final View view = view("constraint_column_usage");
    final String db = catalog.databaseName();
    final List<Object[]> rows = new ArrayList<>();
    for (final Constraint constraint : Constraint.of(catalog)) {
      // a foreign key lists the columns it refers to, any other key the columns it constrains
      final String table = constraint.isForeignKey() ? constraint.referencedTable() : constraint.table();
      final List<String> columns = constraint.isForeignKey() ? constraint.referencedColumns() : constraint.columns();
      for (final String column : columns)
        rows.add(row(view, db, RelationalCatalog.SCHEMA, table, column, db, RelationalCatalog.SCHEMA, constraint.name()));
    }
    return rows;
  }
}
