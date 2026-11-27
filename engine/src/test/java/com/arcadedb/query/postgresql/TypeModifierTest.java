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
import com.arcadedb.database.Document;
import com.arcadedb.exception.CauseChain;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.TypeModifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The length of a {@code varchar(n)} column and the precision and scale of a {@code numeric(p,s)} column (fork issue #226) as the
 * engine holds them for the {@code sql} and openCypher languages, and after the database is opened again. What PostgreSQL answers
 * is compared in the wire module, {@code PostgreSQLTypeModifierTest}.
 */
class TypeModifierTest extends TestHelper {

  @Test
  void everyLanguageRoundsAndRefuses() {
    run("CREATE TABLE products (id int PRIMARY KEY, name varchar(3), price numeric(5, 2), round numeric(3, -2))");

    command("sql", "INSERT INTO products SET id = 1, name = 'ab    ', price = 1.125, round = 12345");
    assertRow(1, "ab ", "1.13", "12300");
    command("sql", "UPDATE products SET price = 2 WHERE id = 1");
    assertRow(1, "ab ", "2.00", "12300");
    command("opencypher", "CREATE (:products {id: 2, name: 'xyz', price: -0.004})");
    assertRow(2, "xyz", "0.00", null);

    assertRefused("sql", "INSERT INTO products SET id = 3, name = 'abcd'", ValidationException.Kind.MAX_LENGTH);
    assertRefused("sql", "UPDATE products SET price = 999.999 WHERE id = 1", ValidationException.Kind.OUT_OF_RANGE);
    assertRefused("opencypher", "MATCH (p:products {id: 2}) SET p.name = 'abcd'", ValidationException.Kind.MAX_LENGTH);
    assertRefused("opencypher", "CREATE (:products {id: 4, price: 1000})", ValidationException.Kind.OUT_OF_RANGE);
    assertRow(1, "ab ", "2.00", "12300");

    reopenDatabase();
    assertRefused("sql", "INSERT INTO products SET id = 5, name = 'abcd'", ValidationException.Kind.MAX_LENGTH);

    // sql removes the limit
    command("sql", "ALTER PROPERTY products.name CUSTOM " + TypeModifier.CUSTOM_KEY + " = null");
    command("sql", "INSERT INTO products SET id = 5, name = 'abcd'");
    assertRow(5, "abcd", null, null);
  }

  @Test
  void aLengthCountsCharacters() {
    final TypeModifier two = TypeModifier.length(2);
    // two characters outside the basic plane are four UTF-16 units
    final String pair = new String(Character.toChars(0x1F600)).repeat(2);
    assertThat(two.assign(pair)).isEqualTo(pair);
    assertThat(two.assign(pair + "  ")).isEqualTo(pair);
    assertThatThrownBy(() -> two.assign(pair + "a")).isInstanceOf(ValidationException.class)
        .hasMessage("value too long for type character varying(2)");
  }

  @Test
  void aNumberFarFromTheScaleIsNotComputed() {
    final TypeModifier money = TypeModifier.numeric(10, 2);
    assertThat(money.assign(new BigDecimal("1E-100000000"))).isEqualTo(new BigDecimal("0.00"));
    assertThatThrownBy(() -> money.assign(new BigDecimal("1E+100000000"))).isInstanceOf(ValidationException.class)
        .hasMessageStartingWith("numeric field overflow");
  }

  private void assertRow(final int id, final String name, final String price, final String round) {
    database.transaction(() -> {
      try (final ResultSet rows = database.query("sql", "SELECT FROM products WHERE id = ?", id)) {
        final Document row = rows.next().getRecord().orElseThrow().asDocument();
        assertThat(row.getString("name")).isEqualTo(name);
        assertThat(text(row.get("price"))).isEqualTo(price);
        assertThat(text(row.get("round"))).isEqualTo(round);
      }
    });
  }

  private static String text(final Object number) {
    return number == null ? null : ((BigDecimal) number).toPlainString();
  }

  private void assertRefused(final String language, final String statement, final ValidationException.Kind kind) {
    assertThatThrownBy(() -> command(language, statement)).satisfies(error -> assertThat(
        CauseChain.find(error, ValidationException.class)).isNotNull().extracting(ValidationException::getKind).isEqualTo(kind));
  }

  private void command(final String language, final String statement) {
    database.transaction(() -> database.command(language, statement).close());
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }
}
