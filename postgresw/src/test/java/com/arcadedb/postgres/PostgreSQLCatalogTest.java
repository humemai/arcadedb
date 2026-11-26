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
package com.arcadedb.postgres;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.query.postgresql.SmallFixture;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relational catalog of the {@code postgresql} language on the wire (fork issue #12, step W2, with the decisions of fork issue
 * #10 of 2026-11-24): a session that speaks the language sees the tables, columns, primary keys and foreign keys of the relational
 * view through the JDBC metadata calls and through {@code pg_class}, {@code pg_attribute}, {@code pg_index} and
 * {@code pg_constraint}, on the {@code small} fixture. A session that does not keep the catalog it always had.
 * <p>
 * Each check is a test of its own, so that one run of the suite says all that is wrong.
 */
class PostgreSQLCatalogTest extends PostgresWireProtocolTestBase {
  private static final String LANGUAGE_OPTION = "-c arcadedb.language=postgresql";

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database of every test is the small fixture and nothing else, so a catalog lists its four tables only. */
  @Override
  protected void populateDatabase() {
    SmallFixture.createSchema(getDatabases()[0]);
    SmallFixture.load(getDatabases()[0]);
  }

  private Connection connect(final String options) throws SQLException {
    final Properties props = new Properties();
    props.setProperty("user", "root");
    props.setProperty("password", DEFAULT_PASSWORD_FOR_TESTS);
    props.setProperty("ssl", "false");
    // A hang detector: a reply the server never sends fails the test instead of stalling the build
    props.setProperty("socketTimeout", "60");
    if (options != null)
      props.setProperty("options", options);
    return DriverManager.getConnection(getServerPostgresJdbcUrl(), props);
  }

  /** The named columns of every row, joined by a bar; the result set is closed. */
  private static List<String> rows(final ResultSet resultSet, final String... columns) throws SQLException {
    try (resultSet) {
      final List<String> rows = new ArrayList<>();
      while (resultSet.next()) {
        final StringBuilder row = new StringBuilder();
        for (int i = 0; i < columns.length; i++)
          row.append(i > 0 ? "|" : "").append(resultSet.getString(columns[i]));
        rows.add(row.toString());
      }
      return rows;
    }
  }

  private static List<String> query(final Connection connection, final String sql) throws SQLException {
    try (final Statement statement = connection.createStatement(); final ResultSet resultSet = statement.executeQuery(sql)) {
      final List<String> rows = new ArrayList<>();
      final int columns = resultSet.getMetaData().getColumnCount();
      while (resultSet.next()) {
        final StringBuilder row = new StringBuilder();
        for (int i = 1; i <= columns; i++)
          row.append(i > 1 ? "|" : "").append(resultSet.getString(i));
        rows.add(row.toString());
      }
      return rows;
    }
  }

  private static final List<String> TABLES = List.of("company", "orders", "person", "works_at");

  // ------------------------------------------------------------------------------------------------------- tables and columns

  @Test
  void getTablesListsTheTablesOfTheView() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(rows(connection.getMetaData().getTables(null, null, "%", new String[] { "TABLE" }), "TABLE_SCHEM", "TABLE_NAME",
          "TABLE_TYPE")).containsExactly("public|company|TABLE", "public|orders|TABLE", "public|person|TABLE",
          "public|works_at|TABLE");
    }
  }

  @Test
  void getColumnsOfAnEdgeTableAreTheLinkTableColumns() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(rows(connection.getMetaData().getColumns(null, null, "works_at", "%"), "COLUMN_NAME", "TYPE_NAME", "IS_NULLABLE",
          "ORDINAL_POSITION")).containsExactly("id|text|NO|1", "source_id|text|NO|2", "target_id|text|NO|3", "since|int4|YES|4");
    }
  }

  @Test
  void getColumnsOfAVertexTableHaveTheRecordIdFirst() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(rows(connection.getMetaData().getColumns(null, null, "person", "%"), "COLUMN_NAME", "TYPE_NAME", "IS_NULLABLE"))
          .containsExactly("id|text|NO", "age|int4|YES", "city|varchar|YES", "name|varchar|YES", "pid|int4|YES");
    }
  }

  /** The record id has a default, which an ORM needs to read a new key back, and no other column has one (fork issue #137). */
  @Test
  void getColumnsGivesTheRecordIdADefault() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(rows(connection.getMetaData().getColumns(null, null, "person", "%"), "COLUMN_NAME", "COLUMN_DEF"))
          .containsExactly("id|arcadedb_record_id()", "age|null", "city|null", "name|null", "pid|null");
    }
  }

  /** The columns the catalog reports are the columns of {@code SELECT *}, with the same type names (fork issue #10 acceptance). */
  @Test
  void theColumnsOfTheCatalogAreTheColumnsOfSelectStar() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      for (final String table : TABLES) {
        final List<String> catalog = rows(connection.getMetaData().getColumns(null, null, table, "%"), "COLUMN_NAME", "TYPE_NAME");
        final List<String> select = new ArrayList<>();
        try (final Statement statement = connection.createStatement();
            final ResultSet resultSet = statement.executeQuery("SELECT * FROM " + table + " LIMIT 1")) {
          final ResultSetMetaData metaData = resultSet.getMetaData();
          for (int i = 1; i <= metaData.getColumnCount(); i++)
            select.add(metaData.getColumnLabel(i) + "|" + metaData.getColumnTypeName(i));
        }
        assertThat(catalog).as("columns of " + table).isEqualTo(select);
      }
    }
  }

  @Test
  void aSessionThatDoesNotSpeakTheLanguageKeepsTheCatalogItHad() throws Exception {
    try (final Connection connection = connect(null)) {
      final List<String> columns = rows(connection.getMetaData().getColumns(null, null, "works_at", "%"), "COLUMN_NAME");
      assertThat(columns).contains("since").doesNotContain("source_id", "target_id");
    }
  }

  // --------------------------------------------------------------------------------------------------------------- keys

  @Test
  void getPrimaryKeysAnswersForEveryTable() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      for (final String table : TABLES)
        assertThat(rows(connection.getMetaData().getPrimaryKeys(null, null, table), "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME"))
            .as("primary key of " + table).containsExactly(table + "|id|1|" + table + "_pkey");
    }
  }

  @Test
  void getImportedKeysOfAnEdgeTableAreItsTwoEnds() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(rows(connection.getMetaData().getImportedKeys(null, null, "works_at"), "PKTABLE_NAME", "PKCOLUMN_NAME",
          "FKTABLE_NAME", "FKCOLUMN_NAME", "KEY_SEQ", "FK_NAME", "PK_NAME", "DELETE_RULE")).containsExactlyInAnyOrder(
          // 0 is importedKeyCascade: deleting a vertex deletes its edges
          "person|id|works_at|source_id|1|works_at_source_id_fkey|person_pkey|0",
          "company|id|works_at|target_id|1|works_at_target_id_fkey|company_pkey|0");
    }
  }

  @Test
  void getImportedKeysOfADocumentTableIsItsLink() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      // 3 is importedKeyNoAction
      assertThat(rows(connection.getMetaData().getImportedKeys(null, null, "orders"), "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_NAME",
          "FKCOLUMN_NAME", "FK_NAME", "DELETE_RULE")).containsExactly("person|id|orders|buyer|orders_buyer_fkey|3");
    }
  }

  @Test
  void getExportedKeysOfAVertexTableAreTheTablesThatPointAtIt() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(rows(connection.getMetaData().getExportedKeys(null, null, "person"), "PKTABLE_NAME", "PKCOLUMN_NAME", "FKTABLE_NAME",
          "FKCOLUMN_NAME", "FK_NAME")).containsExactlyInAnyOrder("person|id|orders|buyer|orders_buyer_fkey",
          "person|id|works_at|source_id|works_at_source_id_fkey");
    }
  }

  @Test
  void getIndexInfoListsThePrimaryKeyAndTheUniqueIndex() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(rows(connection.getMetaData().getIndexInfo(null, null, "person", false, false), "INDEX_NAME", "COLUMN_NAME",
          "NON_UNIQUE")).containsExactlyInAnyOrder("person_pid_key|pid|f", "person_pkey|id|f");
    }
  }

  // ------------------------------------------------------------------------------------------------------ the catalog rows

  @Test
  void pgConstraintHasTheKeysOfTheView() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT conname, contype FROM pg_catalog.pg_constraint ORDER BY conname")).containsExactly(
          "company_cid_key|u", "company_pkey|p", "orders_buyer_fkey|f", "orders_oid_key|u", "orders_pkey|p", "person_pid_key|u",
          "person_pkey|p", "works_at_pkey|p", "works_at_source_id_fkey|f", "works_at_target_id_fkey|f");
    }
  }

  @Test
  void pgConstraintDefinitionsArePostgreSQLs() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT conname, pg_catalog.pg_get_constraintdef(oid, true) FROM pg_catalog.pg_constraint "
          + "WHERE conrelid = 'works_at'::regclass ORDER BY conname")).containsExactly("works_at_pkey|PRIMARY KEY (id)",
          "works_at_source_id_fkey|FOREIGN KEY (source_id) REFERENCES person(id) ON DELETE CASCADE",
          "works_at_target_id_fkey|FOREIGN KEY (target_id) REFERENCES company(id) ON DELETE CASCADE");
      assertThat(query(connection, "SELECT conname, pg_catalog.pg_get_constraintdef(oid, true) FROM pg_catalog.pg_constraint "
          + "WHERE conname = 'orders_buyer_fkey'")).containsExactly(
          "orders_buyer_fkey|FOREIGN KEY (buyer) REFERENCES person(id) NOT ENFORCED");
    }
  }

  @Test
  void aForeignKeySaysWhatTheEngineDoesOnDelete() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT conname, confdeltype, conenforced, convalidated FROM pg_catalog.pg_constraint "
          + "WHERE contype = 'f' ORDER BY conname")).containsExactly("orders_buyer_fkey|a|f|f", "works_at_source_id_fkey|c|t|t",
          "works_at_target_id_fkey|c|t|t");
    }
  }

  @Test
  void aForeignKeyNamesTheIndexOfTheKeyItReferencesAndItsColumns() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT c.conname, i.relname, t.relname, c.conkey, c.confkey FROM pg_catalog.pg_constraint c "
          + "JOIN pg_catalog.pg_class i ON i.oid = c.conindid JOIN pg_catalog.pg_class t ON t.oid = c.confrelid "
          + "WHERE c.contype = 'f' ORDER BY c.conname")).containsExactly("orders_buyer_fkey|person_pkey|person|{3}|{1}",
          "works_at_source_id_fkey|person_pkey|person|{2}|{1}", "works_at_target_id_fkey|company_pkey|company|{3}|{1}");
    }
  }

  @Test
  void pgIndexHasThePrimaryKeysAndTheUniqueIndexes() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT c.relname, i.indisprimary, i.indisunique FROM pg_catalog.pg_index i "
          + "JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid ORDER BY c.relname")).containsExactly("company_cid_key|f|t",
          "company_pkey|t|t", "orders_oid_key|f|t", "orders_pkey|t|t", "person_pid_key|f|t", "person_pkey|t|t", "works_at_pkey|t|t");
    }
  }

  @Test
  void pgAttributeNumbersTheColumnsOfTheView() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT a.attname, a.attnum, t.typname, a.attnotnull FROM pg_catalog.pg_attribute a "
          + "JOIN pg_catalog.pg_class c ON c.oid = a.attrelid JOIN pg_catalog.pg_type t ON t.oid = a.atttypid "
          + "WHERE c.relname = 'works_at' AND a.attnum > 0 ORDER BY a.attnum")).containsExactly("id|1|text|t", "source_id|2|text|t",
          "target_id|3|text|t", "since|4|int4|f");
    }
  }

  /** One source: the constraints that {@code information_schema} of the language lists are the ones {@code pg_constraint} does. */
  @Test
  void informationSchemaOfTheLanguageAndPgConstraintListTheSameKeys() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      final List<String> fromLanguage = query(connection, "SELECT constraint_name, constraint_type FROM "
          + "information_schema.table_constraints WHERE constraint_type IN ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY') "
          + "ORDER BY constraint_name");
      final List<String> fromWire = new ArrayList<>();
      for (final String row : query(connection, "SELECT conname, contype FROM pg_catalog.pg_constraint ORDER BY conname")) {
        final String[] parts = row.split("\\|");
        fromWire.add(parts[0] + "|" + switch (parts[1]) {
          case "p" -> "PRIMARY KEY";
          case "u" -> "UNIQUE";
          default -> "FOREIGN KEY";
        });
      }
      assertThat(fromLanguage).isNotEmpty().isEqualTo(fromWire);
    }
  }

  @Test
  void informationSchemaOfALanguageStatementIsTheLanguages() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
          + "ORDER BY table_name")).isEqualTo(TABLES);
    }
  }

  @Test
  void theMarkerGivesAStatementTheRelationalCatalogInASessionOfAnotherLanguage() throws Exception {
    try (final Connection connection = connect(null)) {
      assertThat(query(connection, "{postgresql} SELECT attname FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c "
          + "ON c.oid = a.attrelid WHERE c.relname = 'works_at' AND a.attnum > 0 ORDER BY a.attnum")).containsExactly("id",
          "source_id", "target_id", "since");
    }
  }

  @Test
  void aSchemaIsPublic() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname = 'public'")).containsExactly("public");
      final DatabaseMetaData metaData = connection.getMetaData();
      assertThat(rows(metaData.getSchemas(), "TABLE_SCHEM")).contains("public");
    }
  }

  // ------------------------------------------------------------------------------- the shapes of SQLAlchemy reflection (#64)

  @Test
  void anArrayConstructorFiltersTheKindOfARelation() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT c.relname FROM pg_catalog.pg_class c WHERE c.relkind = ANY (ARRAY['r'::VARCHAR, "
          + "'p'::VARCHAR]) ORDER BY c.relname")).containsExactlyElementsOf(TABLES);
      assertThat(query(connection, "SELECT c.relname FROM pg_catalog.pg_class c WHERE c.relkind = ANY (ARRAY['v'])")).isEmpty();
    }
  }

  @Test
  void theColumnsOfAKeyAreUnnestedInTheSelectListAndAggregatedInOrder() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT conname, unnest(conkey) AS attnum, generate_subscripts(conkey, 1) AS ord "
          + "FROM pg_catalog.pg_constraint WHERE conname = 'works_at_pkey'")).containsExactly("works_at_pkey|1|1");
      // the subscripts of an int2vector start at 0
      assertThat(query(connection, "SELECT unnest(i.indkey) AS attnum, generate_subscripts(i.indkey, 1) AS ord "
          + "FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'person_pid_key'"))
          .containsExactly("5|0");
      assertThat(query(connection, "SELECT a.relname, array_agg(CAST(a.attname AS TEXT) ORDER BY a.attnum DESC) AS cols FROM "
          + "(SELECT c.relname, t.attname, t.attnum FROM pg_catalog.pg_class c JOIN pg_catalog.pg_attribute t ON t.attrelid = c.oid "
          + "WHERE c.relname = 'company') AS a GROUP BY a.relname")).containsExactly("company|{\"name\",\"cid\",\"id\"}");
    }
  }

  @Test
  void aRelationJoinedTwiceIsReadThroughTheEntryTheNameNames() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      // the shape of SQLAlchemy's foreign keys: pg_namespace once under an alias, and once by its name
      assertThat(query(connection, "SELECT c.conname, pg_namespace.nspname FROM pg_catalog.pg_class "
          + "JOIN pg_catalog.pg_constraint c ON c.conrelid = pg_class.oid AND c.contype = 'f' "
          + "LEFT OUTER JOIN pg_catalog.pg_class AS cls_ref ON cls_ref.oid = c.confrelid "
          + "LEFT OUTER JOIN pg_catalog.pg_namespace AS nsp_ref ON cls_ref.relnamespace = nsp_ref.oid "
          + "JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace "
          + "WHERE pg_class.relname = 'works_at' ORDER BY c.conname"))
          .containsExactly("works_at_source_id_fkey|public", "works_at_target_id_fkey|public");
    }
  }

  @Test
  void theOperatorClassesAreNoneForTheLanguageAndRefusedForAnotherOne() throws Exception {
    try (final Connection connection = connect(LANGUAGE_OPTION)) {
      assertThat(query(connection, "SELECT oid, opcname FROM pg_catalog.pg_opclass WHERE NOT opcdefault")).isEmpty();
    }
    try (final Connection connection = connect(null)) {
      assertThatThrownBy(() -> query(connection, "SELECT oid, opcname FROM pg_catalog.pg_opclass WHERE NOT opcdefault"))
          .isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("0A000");
    }
  }
}
