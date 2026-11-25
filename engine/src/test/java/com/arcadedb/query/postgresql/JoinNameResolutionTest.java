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
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The names of a statement with joins (fork issue #13): the join planner builds the scope of each node once and reads a column by
 * its name first, and what a name resolves to, or how it is refused, stays as it was.
 */
class JoinNameResolutionTest extends TestHelper {
  private static final String HOPS =
      " FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON k.target_id = q.rid WHERE p.id = 1";

  private void load() {
    database.command("sql", "CREATE VERTEX TYPE Person");
    database.command("sql", "CREATE PROPERTY Person.id LONG");
    database.command("sql", "CREATE PROPERTY Person.name STRING");
    database.command("sql", "CREATE INDEX ON Person (id) UNIQUE");
    database.command("sql", "CREATE EDGE TYPE Knows");
    database.command("sql", "CREATE PROPERTY Knows.since INTEGER");
    database.getSchema().getType("Knows").setCustomValue("source_table", "Person");
    database.getSchema().getType("Knows").setCustomValue("target_table", "Person");
    database.transaction(() -> {
      database.command("sql", "CREATE VERTEX Person SET id = 1, name = 'ann'");
      database.command("sql", "CREATE VERTEX Person SET id = 2, name = 'bob'");
      database.command("sql",
          "CREATE EDGE Knows FROM (SELECT FROM Person WHERE id = 1) TO (SELECT FROM Person WHERE id = 2) SET since = 2020");
    });
  }

  private List<Result> rows(final String sql) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet result = database.query("postgresql", sql)) {
      while (result.hasNext())
        rows.add(result.next());
    }
    return rows;
  }

  @Test
  void aOneHopJoinReadsTheColumnsOfEachRelation() {
    load();

    final List<Result> rows = rows("SELECT q.id AS qid, q.name AS name, k.since AS since" + HOPS);
    assertThat(rows).hasSize(1);
    assertThat(((Number) rows.get(0).getProperty("qid")).longValue()).isEqualTo(2L);
    assertThat(rows.get(0).<String>getProperty("name")).isEqualTo("bob");
    assertThat(((Number) rows.get(0).getProperty("since")).intValue()).isEqualTo(2020);

    // unquoted names match whatever their case, and a name that one relation alone has needs no qualifier
    assertThat(rows("SELECT Q.NAME, SINCE" + HOPS).get(0).<String>getProperty("name")).isEqualTo("bob");
    // the merged column of USING is read without a qualifier
    assertThat(rows("SELECT id FROM person p JOIN person q USING (id) WHERE p.id = 1")).hasSize(1);
  }

  @Test
  void aNameThatCannotBeResolvedIsRefused() {
    load();

    assertThatThrownBy(() -> rows("SELECT name" + HOPS)).isInstanceOf(PostgreSQLAmbiguousIdentifierException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> rows("SELECT zz.name" + HOPS)).hasMessageContaining("zz");
    // the relation is reported first, even when the column is unknown too
    assertThatThrownBy(() -> rows("SELECT zz.nope" + HOPS)).hasMessageContaining("zz");
    assertThatThrownBy(() -> rows("SELECT q.nope" + HOPS)).isInstanceOf(PostgreSQLReferenceException.class)
        .hasMessageContaining("nope");
    assertThatThrownBy(() -> rows("SELECT q.\"NAME\"" + HOPS)).hasMessageContaining("NAME");
  }
}
