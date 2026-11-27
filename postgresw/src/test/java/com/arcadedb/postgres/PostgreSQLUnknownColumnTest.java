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

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error for an unknown column names the column that was probably meant, and never says to add one (fork issue #220): a
 * statement that follows such advice succeeds and leaves the column it meant to change as it was.
 */
class PostgreSQLUnknownColumnTest extends PostgreSQLSessionAgainstPostgresTestBase {
  private static final List<String> SETUP = List.of(
      "CREATE TABLE price (id bigint PRIMARY KEY, amount int)",
      "INSERT INTO price (id, amount) VALUES (1, 10)");

  /** Each statement writes a name close to a column; the second text is the column it meant. */
  private static final List<List<String>> TYPOS = List.of(
      List.of("UPDATE price SET amont = 99 WHERE id = 1", "price.amount"),
      List.of("UPDATE price SET amount = 99 WHERE ib = 1", "price.id"),
      List.of("INSERT INTO price (id, amont) VALUES (2, 1)", "price.amount"),
      List.of("DELETE FROM price WHERE amont = 1", "price.amount"),
      List.of("SELECT amont FROM price", "price.amount"),
      List.of("SELECT p.amont FROM price p", "p.amount"),
      List.of("SELECT id FROM price ORDER BY amout", "price.amount"));

  /** No column is close to the name of these. */
  private static final List<String> FAR = List.of(
      "SELECT colour FROM price",
      "UPDATE price SET colour = 'red' WHERE id = 1");

  private static final Pattern SUGGESTED = Pattern.compile("Perhaps you meant to reference the column \"([^\"]+)\"");

  @Test
  void anUnknownColumnSuggestsTheColumnThatWasMeant() throws Exception {
    compare((postgres, arcade) -> {
      try (final Connection pg = postgres.connect(false); final Connection own = arcade.connect(false)) {
        SETUP.forEach(statement -> {
          answer(pg, statement);
          assertThat(answer(own, statement)).as(statement).doesNotStartWith("error");
        });
        final List<String> failures = new ArrayList<>();
        for (final List<String> typo : TYPOS) {
          final String statement = typo.get(0);
          assertThat(answer(own, statement)).as(statement).isEqualTo(answer(pg, statement)).isEqualTo("error 42703");
          final String message = errorMessage(own, statement);
          final Matcher theirs = SUGGESTED.matcher(errorMessage(pg, statement));
          // PostgreSQL suggests nothing for the target of a write; where it does, the suggestion is the same
          if (theirs.find() && !theirs.group(1).equals(typo.get(1)))
            failures.add(statement + ": PostgreSQL suggests " + theirs.group(1) + ", the test expects " + typo.get(1));
          if (!message.contains("Perhaps you meant to reference the column \"" + typo.get(1) + "\"")
              || !message.contains("the columns of ") || message.contains("CREATE PROPERTY"))
            failures.add(statement + ": " + message);
        }
        for (final String statement : FAR) {
          assertThat(answer(own, statement)).as(statement).isEqualTo(answer(pg, statement)).isEqualTo("error 42703");
          final String message = errorMessage(own, statement);
          if (!message.contains("the columns of price are id, amount") || message.contains("Perhaps")
              || message.contains("CREATE PROPERTY"))
            failures.add(statement + ": " + message);
        }
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        // none of them changed anything
        final String check = "SELECT id, amount FROM price ORDER BY id";
        assertThat(answer(own, check)).isEqualTo(answer(pg, check)).isEqualTo("rows [1|10]");
      }
    });
  }
}
