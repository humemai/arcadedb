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
package com.arcadedb.query.postgresql;

import com.arcadedb.TestHelper;
import com.arcadedb.query.postgresql.catalog.TableDefinition;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arcadedb.query.postgresql.catalog.Table.Key;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relational view of a type that has a {@link TableDefinition} (fork issue #118): the declared column order, the record id
 * out of {@code *} and listed only when a foreign key refers to it, and the declared keys under their names.
 */
class CreatedTableCatalogTest extends TestHelper {
  @Override
  protected void beginTest() {
    database.command("sqlscript", """
        CREATE VERTEX TYPE person;
        CREATE PROPERTY person.id LONG (mandatory true, notnull true);
        CREATE PROPERTY person.name STRING;
        CREATE PROPERTY person.city STRING;
        CREATE INDEX ON person (id) UNIQUE;
        CREATE VERTEX TYPE tag;
        CREATE PROPERTY tag.label STRING;
        CREATE PROPERTY tag.code STRING;
        CREATE EDGE TYPE knows CUSTOM source_table = 'person', target_table = 'person';
        CREATE PROPERTY knows.since INTEGER;""");
    define("person", new TableDefinition(List.of("name", "id", "city"), Set.of("name"), new Key("person_pk", List.of("id")),
        List.of(), Map.of(), Map.of()));
    define("tag", new TableDefinition(List.of("label", "code"), new LinkedHashSet<>(), null, List.of(), Map.of(), Map.of()));
    define("knows", new TableDefinition(List.of("since", "source_id", "target_id"), Set.of(), null, List.of(), Map.of(),
        Map.of("source_id", "knows_from")));
  }

  private void define(final String type, final TableDefinition definition) {
    database.getSchema().getType(type).setCustomValue(TableDefinition.CUSTOM_KEY, definition.toJson());
  }

  @Test
  void starAndAnInsertWithoutColumnsFollowTheDeclaredOrder() {
    run("INSERT INTO person VALUES ('Ada', 1, 'Delft')");
    run("INSERT INTO tag VALUES ('red')");

    assertThat(columnsOf("SELECT * FROM person")).containsExactly("name", "id", "city");
    assertThat(columnsOf("SELECT * FROM tag")).containsExactly("label", "code");
    assertThat(columnsOf("SELECT * FROM knows")).containsExactly("since", "source_id", "target_id");
    // the record id stays readable by its name rid, with or without a declared id
    assertThat(rows("SELECT rid FROM person").get(0).<String>getProperty("rid")).startsWith("#");
    assertThat(rows("SELECT rid FROM tag").get(0).<String>getProperty("rid")).startsWith("#");
    // id is the column of the table that declares it
    assertThat(rows("SELECT id FROM person, tag").get(0).<Object>getProperty("id")).isEqualTo(1L);

    final String person = rows("SELECT rid FROM person").get(0).getProperty("rid");
    run("INSERT INTO knows VALUES (2020, '" + person + "', '" + person + "')");
    assertThat(rows("SELECT since FROM knows k JOIN person p ON k.source_id = p.rid")).hasSize(1);
  }

  @Test
  void theCatalogListsWhatWasDeclared() {
    assertThat(lines("SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns"
        + " WHERE table_schema = 'public' ORDER BY table_name, ordinal_position")).containsExactly(
        "knows|since|integer|YES", "knows|source_id|text|NO", "knows|target_id|text|NO",
        "person|name|text|YES", "person|id|bigint|NO", "person|city|character varying|YES",
        // an edge table refers to person, so its record id is listed, after the declared columns
        "person|rid|text|NO",
        "tag|label|character varying|YES", "tag|code|character varying|YES");

    assertThat(lines("SELECT table_name, constraint_name, constraint_type FROM information_schema.table_constraints"
        + " ORDER BY table_name, constraint_name")).containsExactly(
        "knows|knows_from|FOREIGN KEY", "knows|knows_target_id_fkey|FOREIGN KEY",
        "person|person_pk|PRIMARY KEY", "person|person_rid_key|UNIQUE");
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }

  private List<Result> rows(final String statement) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    return rows;
  }

  private List<String> columnsOf(final String statement) {
    return ((PostgreSQLQueryEngine) ((com.arcadedb.database.DatabaseInternal) database).getQueryEngine("postgresql"))
        .describe(statement).stream().map(column -> column.name()).toList();
  }

  private List<String> lines(final String statement) {
    final List<String> columns = columnsOf(statement);
    return rows(statement).stream()
        .map(row -> String.join("|", columns.stream().map(column -> String.valueOf(row.<Object>getProperty(column))).toList()))
        .toList();
  }
}
