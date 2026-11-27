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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The record id of a table that {@code CREATE TABLE} made is a hidden column, which PostgreSQL does not have: inside a subquery it
 * gives way to a column of the same name that a query around shows (fork issue #157). The comparison with PostgreSQL 18 is in
 * {@code PostgreSQLCreateTableTest} of the {@code postgresw} module.
 */
class HiddenRecordIdScopeTest extends TestHelper {
  @Override
  protected void beginTest() {
    // person shows id and hides rid, tag hides rid and has no id, badge shows id and hides rid, ticket shows rid
    for (final String statement : List.of("CREATE TABLE person (id bigint PRIMARY KEY, name text)",
        "CREATE TABLE tag (label text)", "CREATE TABLE badge (id int)", "CREATE TABLE ticket (rid int)",
        "CREATE TABLE other (x int)", "INSERT INTO person VALUES (1, 'Ada'), (2, 'Bob')", "INSERT INTO tag VALUES ('1')",
        "INSERT INTO badge VALUES (5)", "INSERT INTO ticket VALUES (7)", "INSERT INTO other VALUES (0)"))
      run(statement);
  }

  @Test
  void aSubqueryReadsTheColumnOfTheQueryAroundIt() {
    assertThat(lines("SELECT name, (SELECT id FROM tag) AS pid FROM person ORDER BY name")).containsExactly("Ada|1", "Bob|2");
    assertThat(lines("SELECT name FROM person WHERE (SELECT count(*) FROM tag WHERE id > 1) > 0")).containsExactly("Bob");
    assertThat(lines("SELECT name FROM person WHERE EXISTS (SELECT 1 FROM tag WHERE label = id::text)")).containsExactly("Ada");
    assertThat(lines("SELECT name FROM person WHERE NOT EXISTS (SELECT 1 FROM tag WHERE id = 1)")).containsExactly("Bob");
    assertThat(lines("SELECT name FROM person WHERE id::text IN (SELECT id::text FROM tag) ORDER BY name"))
        .containsExactly("Ada", "Bob");
    // two hidden columns of one name are not ambiguous when a query around shows the name
    assertThat(lines("SELECT name, (SELECT count(*) FROM tag a, tag b WHERE id = 1) AS n FROM person ORDER BY name"))
        .containsExactly("Ada|1", "Bob|0");
    assertThat(lines("SELECT (SELECT rid FROM badge) AS r FROM ticket")).containsExactly("7");
    // through a query between them, and through a subquery in FROM
    assertThat(lines("SELECT name, (SELECT (SELECT id FROM tag) FROM tag) AS pid FROM person ORDER BY name"))
        .containsExactly("Ada|1", "Bob|2");
    assertThat(lines("SELECT name, (SELECT count(*) FROM (SELECT label FROM tag WHERE id = 2) s) AS n FROM person ORDER BY name"))
        .containsExactly("Ada|0", "Bob|1");
    // the query around is grouped, and the column is not one of its keys
    assertState("SELECT name, count(*), (SELECT id FROM tag) FROM person GROUP BY name", "42803");
  }

  @Test
  void theRecordIdStaysReadableWhereNoQueryAroundShowsTheName() {
    final String id = lines("SELECT rid FROM tag").get(0);
    assertThat(id).startsWith("#");

    assertThat(lines("SELECT (SELECT rid FROM tag) AS r FROM other")).containsExactly(id);
    assertThat(lines("SELECT (SELECT tag.rid FROM tag) AS r FROM person WHERE id = 1")).containsExactly(id);
    assertThat(lines("SELECT x FROM other WHERE EXISTS (SELECT 1 FROM tag WHERE rid = '" + id + "')")).containsExactly("0");
    assertThat(lines("SELECT name FROM person WHERE EXISTS (SELECT 1 FROM tag WHERE tag.rid = '" + id + "') ORDER BY name"))
        .containsExactly("Ada", "Bob");
    // the record id has no other name (fork issue #240)
    assertState("SELECT id FROM tag", "42703");
    assertState("SELECT tag.id FROM tag", "42703");
  }

  /** A record id that the catalog lists is a column of its table, as the table has it in PostgreSQL: it takes part as one. */
  @Test
  void aRecordIdThatTheCatalogListsIsAColumnOfItsTable() {
    run("CREATE TABLE likes (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,"
        + " target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE)");

    // of a table of the subquery: it wins over the column of the query around
    assertThat(lines("SELECT (SELECT rid <> '7' FROM person WHERE id = 1) AS other FROM ticket")).containsExactly("true");
    // of the query around: the hidden record id of the subquery gives way to it
    assertThat(lines("SELECT name FROM person WHERE rid = (SELECT rid FROM badge) ORDER BY name")).containsExactly("Ada", "Bob");
  }

  /**
   * Within one {@code FROM} too, a record id that the catalog lists is a column of its table (fork issue #163): a name that two
   * tables have in PostgreSQL is ambiguous, and a record id that the catalog does not list gives way to the listed one.
   */
  @Test
  void aListedRecordIdTakesPartInTheNamesOfOneFrom() {
    // no foreign key refers to person yet: PostgreSQL has no column rid there, so the name is the one of ticket
    assertThat(lines("SELECT rid FROM person, ticket")).containsExactly("7", "7");

    run("CREATE TABLE likes (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,"
        + " target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE)");

    for (final String statement : List.of("SELECT rid FROM person, ticket", "SELECT rid FROM ticket, person",
        "SELECT 1 FROM person JOIN ticket ON rid = 7", "SELECT name FROM person, ticket WHERE rid = 7",
        "SELECT rid FROM person a, person b"))
      assertState(statement, "42702");
    // badge hides a record id that the catalog does not list: PostgreSQL has no such column there
    assertThat(lines("SELECT name FROM person, badge WHERE rid = person.rid ORDER BY name")).containsExactly("Ada", "Bob");
    assertThat(lines("SELECT name FROM badge JOIN person ON rid = person.rid ORDER BY name")).containsExactly("Ada", "Bob");
    // two record ids that the catalog does not list stay ambiguous
    assertState("SELECT rid FROM tag a, tag b", "42702");
  }

  /** Places of an {@code EXISTS} subquery that cannot read the query around it: refused, and not answered with a record id. */
  @ParameterizedTest
  @ValueSource(strings = {
      "SELECT name FROM person WHERE EXISTS (SELECT 1 FROM tag t WHERE EXISTS (SELECT 1 FROM tag u WHERE u.label = id::text))",
      "SELECT name FROM person WHERE EXISTS (SELECT 1 FROM tag a JOIN tag b ON a.label = id::text)",
      "SELECT name FROM person WHERE EXISTS (SELECT 1 FROM tag WHERE label = id::text OR EXISTS (SELECT 1 FROM other WHERE x = 1))" })
  void aPlaceThatCannotReadTheQueryAroundIsRefused(final String statement) {
    assertState(statement, "0A000");
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }

  private List<String> lines(final String statement) {
    final List<String> columns = ((PostgreSQLQueryEngine) ((com.arcadedb.database.DatabaseInternal) database)
        .getQueryEngine("postgresql")).describe(statement).stream().map(column -> column.name()).toList();
    final List<String> lines = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", statement)) {
      while (resultSet.hasNext()) {
        final Result row = resultSet.next();
        lines.add(String.join("|", columns.stream().map(column -> String.valueOf(row.<Object>getProperty(column))).toList()));
      }
    }
    return lines;
  }

  private void assertState(final String statement, final String sqlState) {
    assertThatThrownBy(() -> lines(statement)).as(statement)
        .satisfies(e -> assertThat(PostgreSQLErrors.sqlState(e)).as(e.getMessage()).isEqualTo(sqlState));
  }
}
