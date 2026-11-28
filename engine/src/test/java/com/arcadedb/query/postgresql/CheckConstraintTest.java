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
import com.arcadedb.exception.CheckConstraintException;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.CheckConstraint;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Check constraints (fork issue #227) are kept by the engine: for the Java API, {@code sql} and openCypher as for the
 * {@code postgresql} language, across a reopen of the database, for subtypes, and after schema changes. The comparison with
 * PostgreSQL 18 is {@code PostgreSQLCheckConstraintTest} of the {@code postgresw} module.
 */
class CheckConstraintTest extends TestHelper {
  private static final String PRODUCTS = "CREATE TABLE products (id int PRIMARY KEY, price numeric CHECK (price >= 0),"
      + " stock int, name text, CHECK (stock >= 0 OR name IS NOT NULL))";

  private void run(final String language, final String statement) {
    database.transaction(() -> {
      try (final ResultSet rows = database.command(language, statement)) {
        while (rows.hasNext())
          rows.next();
      }
    });
  }

  private void create() {
    // CREATE TABLE changes the schema, outside a transaction
    database.command("postgresql", PRODUCTS).close();
  }

  private static void refused(final ThrowingCallable write, final String state, final String constraint) {
    assertThatThrownBy(write).hasStackTraceContaining(constraint).satisfies(error -> {
      Throwable cause = error;
      while (cause != null && !(cause instanceof CheckConstraintException))
        cause = cause.getCause();
      assertThat(cause).isInstanceOf(CheckConstraintException.class);
      assertThat(((CheckConstraintException) cause).getSqlState()).isEqualTo(state);
    });
  }

  private long count() {
    try (final ResultSet rows = database.query("sql", "SELECT count(*) AS n FROM products")) {
      return rows.next().<Number>getProperty("n").longValue();
    }
  }

  @Test
  void everyLanguageIsChecked() {
    create();
    run("sql", "INSERT INTO products SET id = 1, price = 5, stock = 1");
    // unknown passes
    run("sql", "INSERT INTO products SET id = 2");
    run("opencypher", "CREATE (:products {id: 3, price: 0})");

    refused(() -> run("sql", "INSERT INTO products SET id = 4, price = -1"), "23514", "products_price_check");
    refused(() -> run("sql", "UPDATE products SET price = -1 WHERE id = 1"), "23514", "products_price_check");
    refused(() -> run("sql", "INSERT INTO products SET id = 4, stock = -1"), "23514", "products_check");
    refused(() -> run("opencypher", "CREATE (:products {id: 4, price: -1})"), "23514", "products_price_check");
    refused(() -> run("opencypher", "MATCH (p:products {id: 1}) SET p.price = -2"), "23514", "products_price_check");
    refused(() -> database.transaction(() -> database.newVertex("products").set("id", 4, "price", -1).save()), "23514",
        "products_price_check");
    assertThatThrownBy(() -> run("postgresql", "INSERT INTO products (id, price) VALUES (4, -1)"))
        .isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> assertThat(e.getSqlState()).isEqualTo("23514"));
    assertThat(count()).isEqualTo(3);
  }

  @Test
  void checksStayAfterAReopenAndHoldForSubtypes() {
    create();
    reopenDatabase();
    refused(() -> run("sql", "INSERT INTO products SET id = 1, price = -1"), "23514", "products_price_check");
    run("sql", "CREATE VERTEX TYPE special EXTENDS products");
    refused(() -> run("sql", "INSERT INTO special SET id = 2, price = -1"), "23514", "products_price_check");
    run("sql", "INSERT INTO special SET id = 2, price = 1");
  }

  @Test
  void aCheckThatCannotBeReadRefusesEveryWrite() {
    create();
    run("sql", "DROP PROPERTY products.price");
    refused(() -> run("sql", "INSERT INTO products SET id = 1"), "55000", "products_price_check");
    run("sql", "ALTER TYPE products CUSTOM " + CheckConstraint.CUSTOM_KEY + " = null");
    run("sql", "INSERT INTO products SET id = 1, price = -1");
    assertThat(count()).isEqualTo(1);
  }

  /** Fork issue #297: the view of the checks fails on none of them, whatever their expressions are. */
  @Test
  void aCheckThatIsNotWrittenAsPostgreSqlWritesItIsListedAsItWasWritten() {
    create();
    final String written = "CASE WHEN a > 0 THEN true ELSE false END";
    database.command("postgresql", "CREATE TABLE odd (a int, CHECK (" + written + "))").close();
    assertThat(checkClauses()).contains(written, "(price >= (0)::numeric)");

    // a check that cannot be read, since its column is gone
    run("sql", "DROP PROPERTY products.price");
    assertThat(checkClauses()).contains(written, "price >= 0");
  }

  private List<String> checkClauses() {
    final List<String> clauses = new ArrayList<>();
    try (final ResultSet rows = database.query("postgresql", "SELECT check_clause FROM information_schema.check_constraints")) {
      while (rows.hasNext())
        clauses.add(rows.next().getProperty("check_clause"));
    }
    return clauses;
  }

  @Test
  void aParameterIsNoPartOfACheck() {
    // over the PostgreSQL protocol the server refuses the statement before the language reads it (fork issue #204)
    assertThatThrownBy(() -> database.command("postgresql", "CREATE TABLE p (a int CHECK (a > $1))", 1))
        .isInstanceOfSatisfying(PostgreSQLEvaluationException.class, e -> assertThat(e.getSqlState()).isEqualTo("42P02"));
  }

  @Test
  void anErrorOfTheExpressionKeepsItsState() {
    database.command("postgresql", "CREATE TABLE g (a int CHECK (10 / a > 0))").close();
    refused(() -> run("sql", "INSERT INTO g SET a = 0"), "22012", "g_a_check");
  }
}
