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

import com.arcadedb.database.Database;
import com.arcadedb.database.DatabaseFactory;
import com.arcadedb.query.postgresql.SmallFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire catalog of a statement of the {@code postgresql} language (fork issue #78): the record id and the id of an edge are
 * given by the engine, so {@code pg_attribute} says it the way PostgreSQL does for a {@code GENERATED ALWAYS AS IDENTITY} column
 * ({@code attidentity = 'a'}), and the other columns are what they were. {@code information_schema.columns} of such a statement is
 * the language's own, which the engine tests of that view cover. No server: the catalog is asked directly.
 */
class PostgresCatalogGeneratedColumnTest {
  private static final String DATABASE_PATH = "./target/databases/PostgresCatalogGeneratedColumnTest";

  private Database database;

  @BeforeEach
  void createDatabase() {
    final DatabaseFactory factory = new DatabaseFactory(DATABASE_PATH);
    if (factory.exists())
      factory.open().drop();
    database = factory.create();
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  @AfterEach
  void dropDatabase() {
    if (database != null && database.isOpen())
      database.drop();
  }

  private List<String> rows(final String sql, final boolean relational, final String... columns) {
    final PostgresCatalog.Answer answer = PostgresCatalog.resolveForStatement(sql, database, "root", null, relational);
    assertThat(answer).as(sql).isNotNull();
    final List<String> rows = new ArrayList<>();
    for (final Map<String, Object> row : answer.rows) {
      final StringBuilder text = new StringBuilder();
      for (int i = 0; i < columns.length; i++)
        text.append(i > 0 ? "|" : "").append(row.get(columns[i]));
      rows.add(text.toString());
    }
    return rows;
  }

  @Test
  void theRecordIdAndTheIdOfAnEdgeAreIdentityColumnsInPgAttribute() {
    final String person = "select a.attname, a.attidentity, a.atthasdef, a.attnotnull from pg_attribute a"
        + " join pg_class c on a.attrelid = c.oid where c.relname = 'person' and a.attnum > 0 order by a.attnum";
    assertThat(rows(person, true, "attname", "attidentity", "atthasdef", "attnotnull")).containsExactly("id|a|false|true",
        "age||false|false", "city||false|false", "name||false|false", "pid||false|false");

    final String edge = "select a.attname, a.attidentity from pg_attribute a join pg_class c on a.attrelid = c.oid"
        + " where c.relname = 'works_at' and a.attnum > 0 order by a.attnum";
    assertThat(rows(edge, true, "attname", "attidentity")).containsExactly("id|a", "source_id|", "target_id|", "since|");
  }

  @Test
  void aStatementOfAnotherLanguageKeepsItsColumnsWithoutAnIdentity() {
    final String all = "select a.attname, a.attidentity from pg_attribute a join pg_class c on a.attrelid = c.oid"
        + " where c.relname = 'person' and a.attnum > 0 order by a.attnum";
    assertThat(rows(all, false, "attname", "attidentity")).isNotEmpty().allMatch(row -> row.endsWith("|"));
  }
}
