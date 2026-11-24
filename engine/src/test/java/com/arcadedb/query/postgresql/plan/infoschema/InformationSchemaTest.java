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

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.PostgreSQLCatalogException;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.query.postgresql.catalog.EdgeEndpoints;
import com.arcadedb.query.postgresql.catalog.RelationalCatalog;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.plan.PostgreSQLResultSet;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.EdgeType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seven views of {@code information_schema} on the {@code small} fixture of fork issue #2, with the expected rows written by
 * hand from the catalog (the tables {@code company}, {@code orders}, {@code person} and {@code works_at}, their declared
 * properties and keys). The same views are compared with a real PostgreSQL 18 by the statements {@code is-*} of the answer
 * corpus; this class pins the rows that the corpus cannot compare, the catalog columns (the name of the database) and the NULL
 * ones included, and the way the views resolve.
 */
class InformationSchemaTest extends TestHelper {

  @Override
  protected void beginTest() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  // ---- helpers ----

  private static List<Object> row(final Object... values) {
    return Arrays.asList(values);
  }

  private List<List<Object>> rows(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      final List<List<Object>> out = new ArrayList<>();
      while (resultSet.hasNext()) {
        final Result result = resultSet.next();
        final List<Object> values = new ArrayList<>();
        for (final String name : result.getPropertyNames())
          values.add(result.getProperty(name));
        out.add(values);
      }
      return out;
    }
  }

  private PostgreSQLResultSet run(final String sql) {
    return (PostgreSQLResultSet) database.query("postgresql", sql);
  }

  private String state(final String sql) {
    try {
      rows(sql);
      return null;
    } catch (final PostgreSQLCatalogException e) {
      return e.getSqlState();
    }
  }

  // ---- schemata and tables ----

  @Test
  void schemataListsPublicAndTheViewsSchema() {
    final String db = database.getName();
    assertThat(rows("SELECT * FROM information_schema.schemata ORDER BY schema_name"))
        .containsExactly(row(db, "information_schema", null, null, null, null, null), row(db, "public", null, null, null, null, null));
  }

  @Test
  void tablesListsTheFourBaseTablesAndTheSevenViews() {
    final String db = database.getName();
    assertThat(rows("SELECT * FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name")).containsExactly(
        row(db, "public", "company", "BASE TABLE", null, null, null, null, null, "YES", "NO", null),
        row(db, "public", "orders", "BASE TABLE", null, null, null, null, null, "YES", "NO", null),
        row(db, "public", "person", "BASE TABLE", null, null, null, null, null, "YES", "NO", null),
        row(db, "public", "works_at", "BASE TABLE", null, null, null, null, null, "YES", "NO", null));

    assertThat(rows("SELECT table_name, table_type, is_insertable_into FROM information_schema.tables"
        + " WHERE table_schema = 'information_schema' ORDER BY table_name")).containsExactly(
        row("columns", "VIEW", "NO"), row("constraint_column_usage", "VIEW", "NO"), row("key_column_usage", "VIEW", "NO"),
        row("referential_constraints", "VIEW", "NO"), row("schemata", "VIEW", "NO"), row("table_constraints", "VIEW", "NO"),
        row("tables", "VIEW", "NO"));
  }

  @Test
  void theIssueQueryForTheTablesAnswersTheFourTables() {
    assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_schema NOT IN ('information_schema', 'pg_catalog')"
        + " ORDER BY table_name")).containsExactly(row("company"), row("orders"), row("person"), row("works_at"));
  }

  @Test
  void theRecordIdAndTheIdOfAnEdgeAreGeneratedAlwaysIdentityColumns() {
    // what PostgreSQL says of a GENERATED ALWAYS AS IDENTITY column: a client that reads it leaves the column out of an INSERT
    assertThat(rows("SELECT table_name, column_name, is_nullable, column_default, is_identity, identity_generation, identity_start,"
        + " is_generated FROM information_schema.columns WHERE table_schema = 'public' AND is_identity = 'YES'"
        + " ORDER BY table_name")).containsExactly(
        row("company", "id", "NO", null, "YES", "ALWAYS", null, "NEVER"), row("orders", "id", "NO", null, "YES", "ALWAYS", null, "NEVER"),
        row("person", "id", "NO", null, "YES", "ALWAYS", null, "NEVER"),
        row("works_at", "id", "NO", null, "YES", "ALWAYS", null, "NEVER"));
    // the ends of an edge and the properties are ordinary columns, and so are the columns of the views themselves
    assertThat(rows("SELECT count(*) FROM information_schema.columns WHERE is_identity = 'NO' AND identity_generation IS NULL"
        + " AND table_name = 'works_at' AND column_name IN ('source_id', 'target_id', 'since')")).containsExactly(row(3L));
    assertThat(rows("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'information_schema'"
        + " AND is_identity <> 'NO'")).containsExactly(row(0L));
  }

  @Test
  void aTypeCreatedLaterIsInTheNextStatement() {
    database.getSchema().createDocumentType("fresh");
    assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_name = 'fresh'")).containsExactly(row("fresh"));
    assertThat(rows("SELECT column_name FROM information_schema.columns WHERE table_name = 'fresh'")).containsExactly(row("id"));
  }

  // ---- columns ----

  @Test
  void columnsOfTheFourTablesInTheOrderOfTheCatalog() {
    assertThat(rows("SELECT table_name, column_name, ordinal_position, is_nullable, data_type, udt_name FROM information_schema.columns"
        + " WHERE table_schema = 'public' ORDER BY table_name, ordinal_position")).containsExactly(
        row("company", "id", 1, "NO", "text", "text"), row("company", "cid", 2, "YES", "integer", "int4"),
        row("company", "name", 3, "YES", "character varying", "varchar"),
        row("orders", "id", 1, "NO", "text", "text"), row("orders", "address", 2, "YES", "jsonb", "jsonb"),
        row("orders", "buyer", 3, "YES", "text", "text"), row("orders", "oid", 4, "YES", "integer", "int4"),
        row("orders", "pid", 5, "YES", "integer", "int4"), row("orders", "placed", 6, "YES", "date", "date"),
        row("orders", "tags", 7, "YES", "ARRAY", "_text"), row("orders", "total", 8, "YES", "numeric", "numeric"),
        row("person", "id", 1, "NO", "text", "text"), row("person", "age", 2, "YES", "integer", "int4"),
        row("person", "city", 3, "YES", "character varying", "varchar"), row("person", "name", 4, "YES", "character varying", "varchar"),
        row("person", "pid", 5, "YES", "integer", "int4"),
        row("works_at", "id", 1, "NO", "text", "text"), row("works_at", "source_id", 2, "NO", "text", "text"),
        row("works_at", "target_id", 3, "NO", "text", "text"), row("works_at", "since", 4, "YES", "integer", "int4"));
  }

  @Test
  void theIssueQueryForTheColumnsOfAnEdgeTable() {
    assertThat(rows("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'works_at'"
        + " ORDER BY ordinal_position")).containsExactly(row("id", "text"), row("source_id", "text"), row("target_id", "text"),
        row("since", "integer"));
  }

  @Test
  void aColumnRowHasEveryAttributeThePostgreSQLViewHas() {
    final String db = database.getName();
    // person.age: a nullable integer with no default
    assertThat(rows("SELECT * FROM information_schema.columns WHERE table_name = 'person' AND column_name = 'age'")).containsExactly(
        row(db, "public", "person", "age", 2, null, "YES", "integer", null, null, 32, 2, 0, null, null, null, null, null, null, null,
            null, null, null, null, null, db, "pg_catalog", "int4", null, null, null, null, "2", "NO", "NO", null, null, null, null,
            null, "NO", "NEVER", null, "YES"));
  }

  @Test
  void numericAndTextAttributesFollowTheTypeOfTheColumn() {
    assertThat(rows("SELECT column_name, character_maximum_length, character_octet_length, numeric_precision, numeric_precision_radix,"
        + " numeric_scale, datetime_precision FROM information_schema.columns WHERE table_name = 'orders'"
        + " AND column_name IN ('buyer', 'oid', 'placed', 'tags', 'total') ORDER BY column_name")).containsExactly(
        row("buyer", null, 1073741824, null, null, null, null), row("oid", null, null, 32, 2, 0, null),
        row("placed", null, null, null, null, null, 0), row("tags", null, null, null, null, null, null),
        row("total", null, null, null, 10, null, null));
  }

  @Test
  void theViewsListTheirOwnColumnsWithTheDomainsOfTheServer() {
    final String db = database.getName();
    assertThat(rows("SELECT * FROM information_schema.columns WHERE table_name = 'referential_constraints'"
        + " AND column_name IN ('unique_constraint_name', 'match_option') ORDER BY ordinal_position")).containsExactly(
        row(db, "information_schema", "referential_constraints", "unique_constraint_name", 6, null, "YES", "name", null, null, null,
            null, null, null, null, null, null, null, null, null, null, null, db, "information_schema", "sql_identifier", db,
            "pg_catalog", "name", null, null, null, null, "6", "NO", "NO", null, null, null, null, null, "NO", "NEVER", null, "NO"),
        row(db, "information_schema", "referential_constraints", "match_option", 7, null, "YES", "character varying", null,
            1073741824, null, null, null, null, null, null, null, null, null, null, null, null, db, "information_schema",
            "character_data", db, "pg_catalog", "varchar", null, null, null, null, "7", "NO", "NO", null, null, null, null, null,
            "NO", "NEVER", null, "NO"));
    assertThat(rows("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'information_schema'"))
        .containsExactly(row(99L));
  }

  @Test
  void everyTableHasInColumnsWhatSelectStarReturns() {
    for (final String table : List.of("company", "orders", "person", "works_at")) {
      final List<String> expected = new ArrayList<>();
      try (final PostgreSQLResultSet resultSet = run("SELECT * FROM " + table)) {
        for (final PostgreSQLResultSet.Column column : resultSet.columns())
          expected.add(column.name());
      }
      final List<String> actual = new ArrayList<>();
      for (final List<Object> values : rows(
          "SELECT column_name FROM information_schema.columns WHERE table_name = '" + table + "' ORDER BY ordinal_position"))
        actual.add((String) values.get(0));
      assertThat(actual).as(table).isEqualTo(expected);
    }
  }

  // ---- constraints ----

  @Test
  void tableConstraintsListTheTenKeysOfTheFixtureByTheNamingRule() {
    final String db = database.getName();
    assertThat(rows("SELECT * FROM information_schema.table_constraints ORDER BY constraint_name")).containsExactly(
        row(db, "public", "company_cid_key", db, "public", "company", "UNIQUE", "NO", "NO", "YES", "YES"),
        row(db, "public", "company_pkey", db, "public", "company", "PRIMARY KEY", "NO", "NO", "YES", null),
        row(db, "public", "orders_buyer_fkey", db, "public", "orders", "FOREIGN KEY", "NO", "NO", "YES", null),
        row(db, "public", "orders_oid_key", db, "public", "orders", "UNIQUE", "NO", "NO", "YES", "YES"),
        row(db, "public", "orders_pkey", db, "public", "orders", "PRIMARY KEY", "NO", "NO", "YES", null),
        row(db, "public", "person_pid_key", db, "public", "person", "UNIQUE", "NO", "NO", "YES", "YES"),
        row(db, "public", "person_pkey", db, "public", "person", "PRIMARY KEY", "NO", "NO", "YES", null),
        row(db, "public", "works_at_pkey", db, "public", "works_at", "PRIMARY KEY", "NO", "NO", "YES", null),
        row(db, "public", "works_at_source_id_fkey", db, "public", "works_at", "FOREIGN KEY", "NO", "NO", "YES", null),
        row(db, "public", "works_at_target_id_fkey", db, "public", "works_at", "FOREIGN KEY", "NO", "NO", "YES", null));
  }

  @Test
  void keyColumnUsageListsTheColumnsOfEveryKeyAndForeignKeysPointToTheReferencedPosition() {
    final String db = database.getName();
    assertThat(rows("SELECT * FROM information_schema.key_column_usage ORDER BY constraint_name, ordinal_position")).containsExactly(
        row(db, "public", "company_cid_key", db, "public", "company", "cid", 1, null),
        row(db, "public", "company_pkey", db, "public", "company", "id", 1, null),
        row(db, "public", "orders_buyer_fkey", db, "public", "orders", "buyer", 1, 1),
        row(db, "public", "orders_oid_key", db, "public", "orders", "oid", 1, null),
        row(db, "public", "orders_pkey", db, "public", "orders", "id", 1, null),
        row(db, "public", "person_pid_key", db, "public", "person", "pid", 1, null),
        row(db, "public", "person_pkey", db, "public", "person", "id", 1, null),
        row(db, "public", "works_at_pkey", db, "public", "works_at", "id", 1, null),
        row(db, "public", "works_at_source_id_fkey", db, "public", "works_at", "source_id", 1, 1),
        row(db, "public", "works_at_target_id_fkey", db, "public", "works_at", "target_id", 1, 1));
  }

  @Test
  void referentialConstraintsNameTheReferencedPrimaryKey() {
    final String db = database.getName();
    assertThat(rows("SELECT * FROM information_schema.referential_constraints ORDER BY constraint_name")).containsExactly(
        row(db, "public", "orders_buyer_fkey", db, "public", "person_pkey", "NONE", "NO ACTION", "NO ACTION"),
        row(db, "public", "works_at_source_id_fkey", db, "public", "person_pkey", "NONE", "NO ACTION", "NO ACTION"),
        row(db, "public", "works_at_target_id_fkey", db, "public", "company_pkey", "NONE", "NO ACTION", "NO ACTION"));
  }

  @Test
  void constraintColumnUsageListsReferencedColumnsForForeignKeysAndOwnColumnsForOtherKeys() {
    final String db = database.getName();
    assertThat(rows("SELECT * FROM information_schema.constraint_column_usage ORDER BY constraint_name, table_name, column_name"))
        .containsExactly(row(db, "public", "company", "cid", db, "public", "company_cid_key"),
            row(db, "public", "company", "id", db, "public", "company_pkey"),
            row(db, "public", "person", "id", db, "public", "orders_buyer_fkey"),
            row(db, "public", "orders", "oid", db, "public", "orders_oid_key"),
            row(db, "public", "orders", "id", db, "public", "orders_pkey"),
            row(db, "public", "person", "pid", db, "public", "person_pid_key"),
            row(db, "public", "person", "id", db, "public", "person_pkey"),
            row(db, "public", "works_at", "id", db, "public", "works_at_pkey"),
            row(db, "public", "person", "id", db, "public", "works_at_source_id_fkey"),
            row(db, "public", "company", "id", db, "public", "works_at_target_id_fkey"));
  }

  @Test
  void theForeignKeyRowsOfTheThreeViewsAgreeWithTheCatalog() {
    // the same three foreign keys through each of the views that name them
    final List<List<Object>> fromTableConstraints = rows(
        "SELECT constraint_name FROM information_schema.table_constraints WHERE constraint_type = 'FOREIGN KEY' ORDER BY constraint_name");
    assertThat(fromTableConstraints).containsExactly(row("orders_buyer_fkey"), row("works_at_source_id_fkey"),
        row("works_at_target_id_fkey"));
    assertThat(rows("SELECT constraint_name FROM information_schema.referential_constraints ORDER BY constraint_name"))
        .isEqualTo(fromTableConstraints);
    assertThat(rows("SELECT constraint_name FROM information_schema.key_column_usage WHERE position_in_unique_constraint IS NOT NULL"
        + " ORDER BY constraint_name")).isEqualTo(fromTableConstraints);
    assertThat(RelationalCatalog.of(database).table("works_at").foreignKeys()).extracting(Table.ForeignKey::name)
        .containsExactly("works_at_source_id_fkey", "works_at_target_id_fkey");
  }

  // ---- result types, names and plan ----

  @Test
  void theColumnsOfTheAnswerHaveTheTypesTheServerSends() {
    try (final PostgreSQLResultSet resultSet = run(
        "SELECT table_name, table_type, is_insertable_into FROM information_schema.tables")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName).containsExactly("name", "varchar", "varchar");
    }
    try (final PostgreSQLResultSet resultSet = run("SELECT column_name, ordinal_position FROM information_schema.columns")) {
      assertThat(resultSet.columns()).extracting(PostgreSQLResultSet.Column::typeName).containsExactly("name", "int4");
    }
  }

  @Test
  void aViewHasAnAliasAndIsQualifiedByItsSchemaAndName() {
    assertThat(rows("SELECT t.table_name, t.table_type FROM information_schema.tables t"
        + " WHERE t.table_schema = 'public' AND t.table_name LIKE 'w%'")).containsExactly(row("works_at", "BASE TABLE"));
    assertThat(rows("SELECT information_schema.tables.table_name FROM information_schema.tables"
        + " WHERE table_schema = 'public' AND table_name LIKE 'w%'")).containsExactly(row("works_at"));
    // an alias hides the name of the view, as in PostgreSQL
    assertThat(state("SELECT information_schema.tables.table_name FROM information_schema.tables t")).isEqualTo("42P01");
    assertThat(rows("SELECT tables.table_name FROM information_schema.tables WHERE table_name = 'person'"))
        .containsExactly(row("person"));
    assertThat(rows("SELECT table_name, count(*) FROM information_schema.columns WHERE table_schema = 'public'"
        + " GROUP BY table_name ORDER BY table_name")).containsExactly(row("company", 3L), row("orders", 8L), row("person", 5L),
        row("works_at", 4L));
  }

  @Test
  void explainNamesTheViewScan() {
    try (final PostgreSQLResultSet resultSet = run("EXPLAIN SELECT table_name FROM information_schema.tables t WHERE table_schema = 'public'")) {
      final List<String> lines = new ArrayList<>();
      while (resultSet.hasNext())
        lines.add(resultSet.next().getProperty("QUERY PLAN"));
      assertThat(lines).containsExactly("SCAN VIEW information_schema.tables AS t", "FILTER WHERE", "PROJECT 1 column(s)");
    }
  }

  // ---- the id rule of fork issue #38 ----

  /**
   * {@code account} declares its own {@code id} with a unique index, {@code topic} does not, and {@code follows} and {@code post}
   * refer to both. The views must show {@code rid} where a type has its own {@code id}, the foreign keys pointing at the record
   * id column, and {@code id} where it has none.
   */
  private void declareTypesWithTheirOwnId() {
    final Schema schema = database.getSchema();
    final DocumentType account = schema.createVertexType("account");
    account.createProperty("id", Type.LONG);
    account.createProperty("name", Type.STRING);
    schema.createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "account", "id");
    schema.createVertexType("topic").createProperty("label", Type.STRING);
    final EdgeType follows = schema.createEdgeType("follows");
    follows.setCustomValue(EdgeEndpoints.SOURCE_KEY, "account");
    follows.setCustomValue(EdgeEndpoints.TARGET_KEY, "topic");
    final DocumentType post = schema.createDocumentType("post");
    post.createProperty("author", Type.LINK, "account");
    post.createProperty("about", Type.LINK, "topic");
  }

  @Test
  void aTypeWithItsOwnIdShowsTheRecordIdAsRidAndATypeWithoutOneKeepsId() {
    declareTypesWithTheirOwnId();
    assertThat(rows("SELECT table_name, column_name, ordinal_position, is_nullable, data_type FROM information_schema.columns"
        + " WHERE table_name IN ('account', 'topic', 'follows', 'post') ORDER BY table_name, ordinal_position")).containsExactly(
        row("account", "rid", 1, "NO", "text"), row("account", "id", 2, "NO", "bigint"),
        row("account", "name", 3, "YES", "character varying"),
        row("follows", "id", 1, "NO", "text"), row("follows", "source_id", 2, "NO", "text"), row("follows", "target_id", 3, "NO", "text"),
        row("post", "id", 1, "NO", "text"), row("post", "about", 2, "YES", "text"), row("post", "author", 3, "YES", "text"),
        row("topic", "id", 1, "NO", "text"), row("topic", "label", 2, "YES", "character varying"));
  }

  @Test
  void theKeysOfATypeWithItsOwnIdArePrimaryOnIdAndUniqueOnRid() {
    declareTypesWithTheirOwnId();
    assertThat(rows("SELECT constraint_name, table_name, constraint_type FROM information_schema.table_constraints"
        + " WHERE table_name IN ('account', 'topic', 'follows', 'post') ORDER BY constraint_name")).containsExactly(
        row("account_pkey", "account", "PRIMARY KEY"), row("account_rid_key", "account", "UNIQUE"),
        row("follows_pkey", "follows", "PRIMARY KEY"), row("follows_source_id_fkey", "follows", "FOREIGN KEY"),
        row("follows_target_id_fkey", "follows", "FOREIGN KEY"), row("post_about_fkey", "post", "FOREIGN KEY"),
        row("post_author_fkey", "post", "FOREIGN KEY"), row("post_pkey", "post", "PRIMARY KEY"),
        row("topic_pkey", "topic", "PRIMARY KEY"));
    assertThat(rows("SELECT constraint_name, column_name FROM information_schema.key_column_usage"
        + " WHERE table_name IN ('account', 'topic') ORDER BY constraint_name")).containsExactly(
        row("account_pkey", "id"), row("account_rid_key", "rid"), row("topic_pkey", "id"));

    // the foreign keys refer to the record id column of the other table, and name the key that holds it
    assertThat(rows("SELECT constraint_name, unique_constraint_name FROM information_schema.referential_constraints"
        + " WHERE constraint_name IN ('follows_source_id_fkey', 'follows_target_id_fkey', 'post_about_fkey', 'post_author_fkey')"
        + " ORDER BY constraint_name")).containsExactly(row("follows_source_id_fkey", "account_rid_key"),
        row("follows_target_id_fkey", "topic_pkey"), row("post_about_fkey", "topic_pkey"), row("post_author_fkey", "account_rid_key"));
    assertThat(rows("SELECT constraint_name, table_name, column_name FROM information_schema.constraint_column_usage"
        + " WHERE constraint_name IN ('follows_source_id_fkey', 'follows_target_id_fkey', 'post_about_fkey', 'post_author_fkey')"
        + " ORDER BY constraint_name")).containsExactly(
        row("follows_source_id_fkey", "account", "rid"), row("follows_target_id_fkey", "topic", "id"),
        row("post_about_fkey", "topic", "id"), row("post_author_fkey", "account", "rid"));
  }

  // ---- resolution ----

  @Test
  void aNameUnderTheSchemaIsAViewOrAnError() {
    // 42P01: relation does not exist
    assertThat(state("SELECT * FROM information_schema.nosuch")).isEqualTo("42P01");
    // a type is never reached under the qualifier
    assertThat(state("SELECT * FROM information_schema.person")).isEqualTo("42P01");
    assertThat(state("SELECT * FROM information_schema.\"Tables\"")).isEqualTo("42P01");
    // 42703: undefined column
    assertThat(state("SELECT nosuch FROM information_schema.tables")).isEqualTo("42703");
    // 3F000: no such schema
    assertThat(state("SELECT * FROM nosuch.tables")).isEqualTo("3F000");
  }

  @Test
  void aTypeNamedLikeTheSchemaOrAViewKeepsItsOwnTable() {
    database.getSchema().createDocumentType("information_schema").createProperty("x", Type.INTEGER);
    database.getSchema().createDocumentType("tables").createProperty("y", Type.INTEGER);
    database.transaction(() -> {
      database.newDocument("information_schema").set("x", 1).save();
      database.newDocument("tables").set("y", 2).save();
    });
    // one name: the type; two names with the schema: the view, never the type
    assertThat(rows("SELECT x FROM information_schema")).containsExactly(row(1));
    assertThat(rows("SELECT y FROM tables")).containsExactly(row(2));
    assertThat(rows("SELECT y FROM public.tables")).containsExactly(row(2));
    assertThat(state("SELECT * FROM information_schema.information_schema")).isEqualTo("42P01");
    assertThat(rows("SELECT table_name FROM information_schema.tables WHERE table_name = 'tables' ORDER BY table_schema"))
        .containsExactly(row("tables"), row("tables"));
  }
}
