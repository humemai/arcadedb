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
import com.arcadedb.query.postgresql.SmallFixture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PostgreSQL side of the {@code small} fixture, {@code postgresql/small-schema.sql} of the test resources, is what
 * {@link SmallSchemaSql} generates from the catalog and the Java fixture, and holds as many rows per table as the Java fixture does.
 * The parity harness (fork issue #2) loads that file into PostgreSQL 18, so a fixture or a catalog rule that changes without the file
 * is a red test and not a silent difference between the two sides.
 */
class SmallSchemaSqlTest extends TestHelper {
  private static final String RESOURCE = "/postgresql/small-schema.sql";

  private String checkedIn() throws IOException {
    try (final InputStream stream = SmallSchemaSqlTest.class.getResourceAsStream(RESOURCE)) {
      assertThat(stream).as("test resource " + RESOURCE).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private void loadFixture() {
    SmallFixture.createSchema(database);
    SmallFixture.load(database);
  }

  @Test
  void theCheckedInFileIsWhatTheCatalogAndTheFixtureGenerate() throws IOException {
    loadFixture();
    final String generated = SmallSchemaSql.generate(database, true);
    final String checkedIn = checkedIn();

    if (!generated.equals(checkedIn)) {
      // the way to refresh the file: copy what the generator wrote
      final Path file = Path.of("target", "small-schema.generated.sql");
      Files.createDirectories(file.getParent());
      Files.writeString(file, generated);
      System.out.println("SMALL-SCHEMA-GENERATED-BEGIN\n" + generated + "SMALL-SCHEMA-GENERATED-END");
    }
    assertThat(generated).isEqualTo(checkedIn);
  }

  @Test
  void theFileHoldsAsManyRowsPerTableAsTheJavaFixture() throws IOException {
    loadFixture();
    final String checkedIn = checkedIn();

    // the numbers of the fixture of issue #2: three companies, five people, six orders, five edges
    final Map<String, Integer> expected = Map.of("company", 3, "person", 5, "orders", 6, "works_at", 5);
    assertThat(RelationalCatalog.of(database).tables()).extracting(Table::name).containsExactlyInAnyOrderElementsOf(expected.keySet());
    for (final Map.Entry<String, Integer> entry : expected.entrySet()) {
      final String table = entry.getKey();
      assertThat(database.countType(table, false)).as("rows of " + table + " in the Java fixture").isEqualTo(entry.getValue().longValue());
      assertThat(checkedIn.lines().filter(line -> line.startsWith("INSERT INTO " + table + " ")).count())
          .as("INSERTs of " + table + " in the file").isEqualTo(entry.getValue().longValue());
      assertThat(checkedIn.lines().filter(line -> line.startsWith("CREATE TABLE " + table + " ")).count())
          .as("CREATE TABLE of " + table).isEqualTo(1);
    }
  }

  @Test
  void theGeneratorWithoutNormalizationWritesTheRealRecordIds() {
    loadFixture();
    final String real = SmallSchemaSql.generate(database, false);

    final Iterator<Document> people = database.iterateType("person", false);
    while (people.hasNext()) {
      final String rid = people.next().getIdentity().toString();
      assertThat(real).contains("'" + rid + "'");
    }
    final List<String> inserts = real.lines().filter(line -> line.startsWith("INSERT INTO person ")).toList();
    assertThat(inserts).hasSize(5);
    assertThat(real).doesNotContain("record ids are renumbered");
  }
}
