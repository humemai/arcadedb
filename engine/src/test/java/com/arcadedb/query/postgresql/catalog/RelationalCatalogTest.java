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
package com.arcadedb.query.postgresql.catalog;

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedSchemaException;
import com.arcadedb.query.postgresql.PostgreSQLUndefinedTableException;
import com.arcadedb.query.postgresql.SmallFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relational view of the {@code small} schema of fork issue #2: the tables, their columns and types, the keys, and the values
 * that the columns read from the rows. Rules that need a schema of their own are in {@link CatalogRulesTest}.
 */
class RelationalCatalogTest extends TestHelper {

  private RelationalCatalog small() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
    return RelationalCatalog.of(database);
  }

  static List<String> names(final Table table) {
    final List<String> names = new ArrayList<>();
    for (final Column column : table.columns())
      names.add(column.name());
    return names;
  }

  static Map<String, String> typesOf(final Table table) {
    final Map<String, String> types = new HashMap<>();
    for (final Column column : table.columns())
      types.put(column.name(), column.type().typeName());
    return types;
  }

  @Test
  void theTablesAreExactlyCompanyOrdersPersonAndWorksAt() {
    final RelationalCatalog catalog = small();

    assertThat(catalog.tables()).extracting(Table::name).containsExactly("company", "orders", "person", "works_at");
    assertThat(catalog.table("person").kind()).isEqualTo(Table.Kind.VERTEX);
    assertThat(catalog.table("company").kind()).isEqualTo(Table.Kind.VERTEX);
    assertThat(catalog.table("orders").kind()).isEqualTo(Table.Kind.DOCUMENT);
    assertThat(catalog.table("works_at").kind()).isEqualTo(Table.Kind.EDGE);
  }

  @Test
  void theColumnsAreTheSyntheticOnesThenTheDeclaredPropertiesByName() {
    final RelationalCatalog catalog = small();

    assertThat(names(catalog.table("person"))).containsExactly("rid", "age", "city", "name", "pid");
    assertThat(names(catalog.table("company"))).containsExactly("rid", "cid", "name");
    assertThat(names(catalog.table("orders"))).containsExactly("rid", "address", "buyer", "oid", "pid", "placed", "tags", "total");
    assertThat(names(catalog.table("works_at"))).containsExactly("rid", "source_id", "target_id", "since");
  }

  @Test
  void anEdgeTableHasNoColumnOfTheRecordModel() {
    final Table worksAt = small().table("works_at");

    assertThat(names(worksAt)).doesNotContain("@out", "@in", "out", "in");
    assertThat(worksAt.edgeStorage()).isEqualTo(Table.EdgeStorage.REGULAR);
    assertThat(worksAt.column("rid").kind()).isEqualTo(Column.Kind.IDENTITY);
    assertThat(worksAt.column("source_id").kind()).isEqualTo(Column.Kind.SOURCE);
    assertThat(worksAt.column("target_id").kind()).isEqualTo(Column.Kind.TARGET);
    assertThat(worksAt.column("since").kind()).isEqualTo(Column.Kind.PROPERTY);
  }

  @Test
  void theColumnTypesAreTheOnesTheDecisionsName() {
    final RelationalCatalog catalog = small();

    final Map<String, String> orders = typesOf(catalog.table("orders"));
    assertThat(orders).containsEntry("rid", "text").containsEntry("oid", "int4").containsEntry("pid", "int4")
        .containsEntry("total", "numeric").containsEntry("placed", "date").containsEntry("address", "jsonb")
        .containsEntry("tags", "text[]").containsEntry("buyer", "text");

    final Map<String, String> person = typesOf(catalog.table("person"));
    assertThat(person).containsEntry("age", "int4").containsEntry("pid", "int4").containsEntry("name", "varchar")
        .containsEntry("city", "varchar");

    final Map<String, String> worksAt = typesOf(catalog.table("works_at"));
    assertThat(worksAt).containsEntry("source_id", "text").containsEntry("target_id", "text").containsEntry("since", "int4");
  }

  @Test
  void everyTableHasIdAsItsPrimaryKeyAndTheUniqueIndexesAreUniqueKeys() {
    final RelationalCatalog catalog = small();

    for (final Table table : catalog.tables()) {
      assertThat(table.primaryKey().columns()).as(table.name()).containsExactly("rid");
      assertThat(table.primaryKey().name()).isEqualTo(table.name() + "_pkey");
    }

    assertThat(catalog.table("person").uniqueKeys()).extracting(Table.Key::name).containsExactly("person_pid_key");
    assertThat(catalog.table("person").uniqueKeys().get(0).columns()).containsExactly("pid");
    assertThat(catalog.table("company").uniqueKeys()).extracting(Table.Key::columns).containsExactly(List.of("cid"));
    assertThat(catalog.table("orders").uniqueKeys()).extracting(Table.Key::columns).containsExactly(List.of("oid"));
    assertThat(catalog.table("works_at").uniqueKeys()).isEmpty();
  }

  @Test
  void theForeignKeysAreTheEdgeEndpointsAndTheLink() {
    final RelationalCatalog catalog = small();

    final List<String> foreignKeys = new ArrayList<>();
    for (final Table table : catalog.tables())
      for (final Table.ForeignKey foreignKey : table.foreignKeys())
        foreignKeys.add(table.name() + "." + String.join(",", foreignKey.columns()) + " -> " + foreignKey.referencedTable() + "."
            + String.join(",", foreignKey.referencedColumns()));
    assertThat(foreignKeys).containsExactlyInAnyOrder("works_at.source_id -> person.rid", "works_at.target_id -> company.rid",
        "orders.buyer -> person.rid");

    final List<Table.ForeignKey> worksAt = catalog.table("works_at").foreignKeys();
    assertThat(worksAt).extracting(Table.ForeignKey::name).containsExactly("works_at_source_id_fkey", "works_at_target_id_fkey");
    assertThat(worksAt).extracting(Table.ForeignKey::origin)
        .containsExactly(Table.ForeignKey.Origin.EDGE_SOURCE, Table.ForeignKey.Origin.EDGE_TARGET);
    assertThat(catalog.table("orders").foreignKeys()).extracting(Table.ForeignKey::origin)
        .containsExactly(Table.ForeignKey.Origin.LINK_PROPERTY);
    assertThat(catalog.table("works_at").sourceTable()).isEqualTo("person");
    assertThat(catalog.table("works_at").targetTable()).isEqualTo("company");
  }

  @Test
  void idIsTheRecordIdAsTextAndUniqueOverTheRowsOfEveryTable() {
    final RelationalCatalog catalog = small();

    for (final Table table : catalog.tables()) {
      final Column id = table.column("rid");
      final Set<String> seen = new HashSet<>();
      final Iterator<Document> rows = database.iterateType(table.name(), false);
      while (rows.hasNext()) {
        final Document row = rows.next();
        final Object value = id.read(row);
        assertThat(value).as(table.name()).isEqualTo(row.getIdentity().toString());
        assertThat((String) value).matches("#\\d+:\\d+");
        assertThat(seen.add((String) value)).as("id " + value + " of " + table.name() + " is unique").isTrue();
      }
      assertThat(seen).as(table.name()).hasSize((int) database.countType(table.name(), false));
    }
  }

  @Test
  void sourceAndTargetOfEachEdgeAreTheIdsOfItsPersonAndItsCompany() {
    final RelationalCatalog catalog = small();
    final Map<Integer, String> personIds = idsBy(catalog.table("person"), "pid");
    final Map<Integer, String> companyIds = idsBy(catalog.table("company"), "cid");
    assertThat(personIds).hasSize(5);
    assertThat(companyIds).hasSize(3);

    // the five edges of the fixture by their since: from pid, to cid
    final Map<Integer, int[]> expected = Map.of(2019, new int[] { 1, 10 }, 2022, new int[] { 1, 10 }, 2020, new int[] { 2, 10 },
        2021, new int[] { 3, 20 }, 2023, new int[] { 4, 20 });

    final Table worksAt = catalog.table("works_at");
    final Column source = worksAt.column("source_id");
    final Column target = worksAt.column("target_id");
    int edges = 0;
    final Iterator<Document> rows = database.iterateType("works_at", false);
    while (rows.hasNext()) {
      final Document edge = rows.next();
      final int[] ends = expected.get(edge.getInteger("since"));
      assertThat(source.read(edge)).isEqualTo(personIds.get(ends[0]));
      assertThat(target.read(edge)).isEqualTo(companyIds.get(ends[1]));
      ++edges;
    }
    assertThat(edges).isEqualTo(5);
  }

  @Test
  void aLinkColumnReadsTheRecordIdOfItsTargetAndAnAbsentLinkIsNull() {
    final RelationalCatalog catalog = small();
    final String ada = idsBy(catalog.table("person"), "pid").get(1);
    final Column buyer = catalog.table("orders").column("buyer");

    final Map<Integer, String> buyers = new HashMap<>();
    final Iterator<Document> rows = database.iterateType("orders", false);
    while (rows.hasNext()) {
      final Document order = rows.next();
      buyers.put(order.getInteger("oid"), (String) buyer.read(order));
    }
    assertThat(buyers).hasSize(6);
    assertThat(buyers.get(100)).isEqualTo(ada);
    assertThat(buyers.get(101)).isEqualTo(ada);
    assertThat(buyers.get(105)).isNull();
  }

  @Test
  void aTypeCreatedAfterTheFirstQueryIsInTheNextCatalogAndNotInTheSnapshot() {
    final RelationalCatalog before = small();
    assertThat(before.findTable(Identifier.unquoted("city"))).isNull();

    database.command("sql", "CREATE VERTEX TYPE city");
    final RelationalCatalog after = RelationalCatalog.of(database);

    assertThat(after.table("city").kind()).isEqualTo(Table.Kind.VERTEX);
    assertThat(names(after.table("city"))).containsExactly("rid");
    assertThat(after.tables()).hasSize(before.tables().size() + 1);
    assertThat(before.findTable(Identifier.unquoted("city"))).as("a snapshot does not change").isNull();

    database.command("sql", "CREATE PROPERTY city.name STRING");
    assertThat(names(RelationalCatalog.of(database).table("city"))).containsExactly("rid", "name");
    assertThat(names(after.table("city"))).containsExactly("rid");
  }

  @Test
  void theSchemaIsPublicAndTheNameOfTheDatabaseIsAnAlias() {
    final RelationalCatalog catalog = small();

    assertThat(RelationalCatalog.SCHEMA).isEqualTo("public");
    assertThat(catalog.isSchema(Identifier.unquoted("public"))).isTrue();
    assertThat(catalog.isSchema(Identifier.unquoted("PUBLIC"))).isTrue();
    assertThat(catalog.isSchema(Identifier.quoted("public"))).isTrue();
    assertThat(catalog.isSchema(Identifier.quoted("PUBLIC"))).isFalse();
    assertThat(catalog.isSchema(Identifier.quoted(database.getName()))).isTrue();
    assertThat(catalog.isSchema(Identifier.unquoted(database.getName()))).isTrue();
    assertThat(catalog.isSchema(Identifier.unquoted("information_schema"))).isFalse();

    assertThat(catalog.table(Identifier.unquoted("public"), Identifier.unquoted("person")).name()).isEqualTo("person");
    assertThat(catalog.table(Identifier.quoted(database.getName()), Identifier.unquoted("person")).name()).isEqualTo("person");

    assertThatThrownBy(() -> catalog.table(Identifier.unquoted("information_schema"), Identifier.unquoted("person")))
        .isInstanceOfSatisfying(PostgreSQLUndefinedSchemaException.class, e -> assertThat(e.getSqlState()).isEqualTo("3F000"))
        .hasMessageContaining("schema \"information_schema\" does not exist");
  }

  @Test
  void anUnknownTableIs42P01() {
    final RelationalCatalog catalog = small();

    assertThat(catalog.findTable(Identifier.unquoted("nothing"))).isNull();
    assertThatThrownBy(() -> catalog.table("nothing"))
        .isInstanceOfSatisfying(PostgreSQLUndefinedTableException.class, e -> assertThat(e.getSqlState()).isEqualTo("42P01"))
        .hasMessage("relation \"nothing\" does not exist");
  }

  private Map<Integer, String> idsBy(final Table table, final String key) {
    final Column id = table.column("rid");
    final Map<Integer, String> ids = new HashMap<>();
    final Iterator<Document> rows = database.iterateType(table.name(), false);
    while (rows.hasNext()) {
      final Document row = rows.next();
      ids.put(row.getInteger(key), (String) id.read(row));
    }
    return ids;
  }
}
