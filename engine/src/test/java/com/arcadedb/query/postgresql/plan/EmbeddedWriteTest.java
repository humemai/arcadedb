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

import com.arcadedb.TestHelper;
import com.arcadedb.database.Document;
import com.arcadedb.database.EmbeddedDocument;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.schema.VertexType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * {@code EMBEDDED OF T}, {@code LIST OF T} and {@code MAP OF T} for a document type {@code T} in the {@code postgresql} language
 * (fork issue #145): a JSON object is stored as an embedded document of {@code T}, which {@code sql} and openCypher read as such,
 * and the column reads back as the object that was written, without {@code @type} and {@code @cat}.
 */
class EmbeddedWriteTest extends TestHelper {
  @Override
  protected void beginTest() {
    database.transaction(() -> {
      final Schema schema = database.getSchema();
      schema.createDocumentType("place").createProperty("lat", Type.DOUBLE);
      final DocumentType address = schema.createDocumentType("address");
      address.createProperty("city", Type.STRING).setMandatory(true);
      address.createProperty("zip", Type.STRING);
      address.createProperty("since", Type.DATE);
      address.createProperty("floor", Type.INTEGER);
      address.createProperty("at", Type.EMBEDDED, "place");
      schema.createDocumentType("business").addSuperType("address");
      final VertexType person = schema.createVertexType("person");
      person.createProperty("name", Type.STRING);
      person.createProperty("home", Type.EMBEDDED, "address");
      person.createProperty("past", Type.LIST, "address");
      person.createProperty("places", Type.MAP, "address");
      person.createProperty("free", Type.EMBEDDED);
      person.createProperty("friends", Type.LIST, "person");
    });
  }

  private void command(final String sql, final Object... parameters) {
    database.transaction(() -> {
      try (final ResultSet resultSet = database.command("postgresql", sql, parameters)) {
        while (resultSet.hasNext())
          resultSet.next();
      }
    });
  }

  private List<Result> rows(final String language, final String query) {
    final List<Result> rows = new ArrayList<>();
    try (final ResultSet resultSet = database.query(language, query)) {
      while (resultSet.hasNext())
        rows.add(resultSet.next());
    }
    return rows;
  }

  private Object value(final String column, final String name) {
    final List<Result> rows = rows("postgresql", "SELECT " + column + " FROM person WHERE name = '" + name + "'");
    assertThat(rows).hasSize(1);
    return rows.get(0).getProperty(column);
  }

  private Object stored(final String property, final String name) {
    final List<Result> rows = rows("sql", "SELECT FROM person WHERE name = '" + name + "'");
    assertThat(rows).hasSize(1);
    return rows.get(0).getProperty(property);
  }

  private static void assertDocument(final Object value, final String type) {
    assertThat(value).isInstanceOf(EmbeddedDocument.class);
    assertThat(((Document) value).getTypeName()).isEqualTo(type);
  }

  private void assertState(final String sql, final String sqlState) {
    try {
      command(sql);
      fail("no error for " + sql);
    } catch (final RuntimeException e) {
      Throwable cause = e;
      while (cause != null && !(cause instanceof PostgreSQLEvaluationException))
        cause = cause.getCause();
      assertThat(cause).as("%s: %s", sql, e).isNotNull();
      assertThat(((PostgreSQLEvaluationException) cause).getSqlState()).as(sql + ": " + cause.getMessage()).isEqualTo(sqlState);
    }
  }

  @Test
  void anObjectIsStoredAsAnEmbeddedDocumentAndReadsBackAsWritten() {
    command("INSERT INTO person (name, home) VALUES ('Fay', '{\"city\": \"Delft\", \"zip\": \"1000AA\"}')");

    final Map<?, ?> home = (Map<?, ?>) value("home", "Fay");
    assertThat(home.keySet().stream().map(String::valueOf).toList()).containsExactly("city", "zip");
    assertThat(home.get("city")).isEqualTo("Delft");
    assertThat(home.get("zip")).isEqualTo("1000AA");
    assertDocument(stored("home", "Fay"), "address");
    assertThat((Object) rows("opencypher", "MATCH (p:person) WHERE p.name = 'Fay' RETURN p.home.city AS city").get(0)
        .getProperty("city")).isEqualTo("Delft");
  }

  @Test
  void anArrayAndAnObjectOfObjectsAreCollectionsOfEmbeddedDocuments() {
    command("INSERT INTO person (name, past, places) VALUES ('Bo', "
        + "'[{\"city\": \"Gouda\"}, {\"city\": \"Leiden\", \"floor\": 2}]', '{\"work\": {\"city\": \"Delft\"}}')");

    assertThat(value("past", "Bo")).isEqualTo(List.of(Map.of("city", "Gouda"), Map.of("city", "Leiden", "floor", 2)));
    assertThat(value("places", "Bo")).isEqualTo(Map.of("work", Map.of("city", "Delft")));
    final List<?> past = (List<?>) stored("past", "Bo");
    assertThat(past).hasSize(2);
    past.forEach(item -> assertDocument(item, "address"));
    assertDocument(((Map<?, ?>) stored("places", "Bo")).get("work"), "address");

    command("INSERT INTO person (name, past, places) VALUES ('Cy', '[]', '{}')");
    assertThat((List<?>) value("past", "Cy")).isEmpty();
    assertThat((Map<?, ?>) value("places", "Cy")).isEmpty();
  }

  @Test
  void aDateAndAnEmbeddedDocumentInsideItReadBackAsWritten() {
    command("INSERT INTO person (name, home) VALUES ('Di', "
        + "'{\"city\": \"Delft\", \"since\": \"2026-01-05\", \"at\": {\"lat\": 52.01}, \"note\": null}')");

    final Map<?, ?> home = (Map<?, ?>) value("home", "Di");
    assertThat(home.get("since")).isEqualTo("2026-01-05");
    assertThat(home.containsKey("note")).isTrue();
    assertThat(home.get("note")).isNull();
    final Map<?, ?> at = (Map<?, ?>) home.get("at");
    assertThat(at.keySet().stream().map(String::valueOf).toList()).containsExactly("lat");
    assertThat(((Number) at.get("lat")).doubleValue()).isEqualTo(52.01);
    assertDocument(((Document) stored("home", "Di")).get("at"), "place");
  }

  @Test
  void whatIsReadCanBeWrittenAgain() {
    command("INSERT INTO person (name, home, past) VALUES ('Ed', '{\"city\": \"Delft\", \"since\": \"2026-01-05\"}', "
        + "'[{\"city\": \"Gouda\"}]')");
    command("UPDATE person SET home = home, past = past WHERE name = 'Ed'");
    command("INSERT INTO person (name, home, past) SELECT 'Flo', home, past FROM person WHERE name = 'Ed'");
    command("UPDATE person SET places = $1::jsonb WHERE name = 'Flo'", Map.of("old", Map.of("city", "Breda")));

    assertThat(value("home", "Flo")).isEqualTo(Map.of("city", "Delft", "since", "2026-01-05"));
    assertThat(value("past", "Flo")).isEqualTo(List.of(Map.of("city", "Gouda")));
    assertThat(value("places", "Flo")).isEqualTo(Map.of("old", Map.of("city", "Breda")));
    assertDocument(stored("home", "Flo"), "address");
  }

  @Test
  void aDocumentThatSqlWroteReadsTheSameWayAndASubtypeKeepsItsType() {
    database.transaction(() -> {
      database.command("sql", "INSERT INTO person SET name = 'Gil', home = {\"@type\": \"address\", \"city\": \"Delft\"}");
      database.command("sql", "INSERT INTO person SET name = 'Hal', home = {\"@type\": \"business\", \"city\": \"Delft\"}");
    });

    assertThat(value("home", "Gil")).isEqualTo(Map.of("city", "Delft"));
    // without @type this one would read, and be written back, as a document of the declared type
    assertThat(value("home", "Hal")).isEqualTo(Map.of("@type", "business", "@cat", "d", "city", "Delft"));
    assertState("UPDATE person SET home = home WHERE name = 'Hal'", "0A000");
    assertDocument(stored("home", "Hal"), "business");
  }

  @Test
  void whatTheDocumentTypeCannotHoldAsWrittenIsRefused() {
    // no declared type
    assertState("INSERT INTO person (name, free) VALUES ('x', '{\"city\": \"Delft\"}')", "0A000");
    // the shapes
    assertState("INSERT INTO person (name, home) VALUES ('x', '[{\"city\": \"Delft\"}]')", "0A000");
    assertState("INSERT INTO person (name, past) VALUES ('x', '{\"city\": \"Delft\"}')", "0A000");
    assertState("INSERT INTO person (name, past) VALUES ('x', '[{\"city\": \"Delft\"}, null]')", "0A000");
    assertState("INSERT INTO person (name, past) VALUES ('x', '[1]')", "0A000");
    assertState("INSERT INTO person (name, places) VALUES ('x', '{\"work\": 1}')", "0A000");
    assertState("INSERT INTO person (name, places) VALUES ('x', '{\"@type\": {\"city\": \"Delft\"}}')", "0A000");
    // keys the engine takes as its own
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"@type\": \"address\", \"city\": \"Delft\"}')", "0A000");
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"@cat\": \"d\", \"city\": \"Delft\"}')", "0A000");
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"city\": \"Delft\", \"at\": {\"@type\": \"business\"}}')",
        "0A000");
    // values that the type would turn into other ones
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"city\": 5}')", "0A000");
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"city\": \"Delft\", \"floor\": \"3\"}')", "0A000");
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"city\": \"Delft\", \"floor\": 2.5}')", "0A000");
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"city\": \"Delft\", \"since\": \"2026-1-5\"}')", "0A000");
    // a list of links
    assertState("INSERT INTO person (name, friends) VALUES ('x', '[{\"name\": \"y\"}]')", "0A000");
    // a constraint of the document type
    assertState("INSERT INTO person (name, home) VALUES ('x', '{\"zip\": \"1000AA\"}')", "23502");

    assertThat(rows("sql", "SELECT FROM person")).isEmpty();
  }
}
