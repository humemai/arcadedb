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
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CHECK} constraints of the {@code postgresql} language against PostgreSQL 18 (fork issue #227), over JDBC: which rows
 * are refused, with which SQLSTATE and which constraint, the errors of {@code CREATE TABLE}, and the catalog.
 */
class PostgreSQLCheckConstraintTest extends PostgreSQLSideBySideTestBase {
  private static final Pattern VIOLATED = Pattern.compile("violates check constraint \"([^\"]*)\"");

  private static final List<String> TABLES = List.of("CREATE EXTENSION IF NOT EXISTS vector",
      "CREATE TABLE p13 (id int PRIMARY KEY, n int CHECK (n > 0))",
      "CREATE TABLE p14 (id int PRIMARY KEY, a int, b int, CHECK (a < b))",
      "CREATE TABLE products (id serial PRIMARY KEY, sku text NOT NULL UNIQUE, name text NOT NULL,"
          + " price numeric NOT NULL CHECK (price >= 0), stock integer NOT NULL DEFAULT 0)",
      // the facts table of the memory store of the agent measure, without its reference
      "CREATE TABLE facts (id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, content TEXT NOT NULL,"
          + " confidence REAL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1), source TEXT, embedding vector(3),"
          + " tags TEXT[] DEFAULT '{}', created_at TIMESTAMPTZ DEFAULT now())",
      "CREATE TABLE c (id int PRIMARY KEY, a int CHECK (a > 0), b int, CHECK (a < b), c text CHECK (c IN ('x', 'y'))"
          + " CHECK (length(c) > 0), d int CONSTRAINT named CHECK (d > 0), CHECK (1 = 1), e int CHECK (b > 0),"
          + " f int CHECK (f > 0 OR b IS NULL))",
      "CREATE TABLE times (id int, s timestamptz, e timestamptz, CHECK (s <= e AND e IS NOT NULL),"
          + " d date CHECK (d > '2000-01-01'), j jsonb CHECK (j ->> 'k' <> 'bad'),"
          + " u uuid CHECK (u <> '00000000-0000-0000-0000-000000000000'),"
          + " w text CHECK (coalesce(w, 'ok') LIKE 'o%' AND times.id > 0))",
      "CREATE TABLE g (a int CHECK (10 / a > 0))", "CREATE TABLE person (id bigint PRIMARY KEY)",
      "CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL"
          + " REFERENCES person (id), weight int CHECK (weight > 0))");

  /** Every shape of an expression whose text is written back (fork issue #297), each under a name of its own. */
  private static final List<String> SHAPES = List.of("s > 0 AND i > 0 AND b > 0", "s < i OR i < b",
      "(i + 1) * 2 - b % 3 > -1", "i - (b - 1) < 100 AND (i - b) - 1 < 100", "n * 2 >= 1.50 AND i * 1.5 < n",
      "r + 1 > 0.5 AND f > n", "r < f AND r <= 1.5 AND f / 2 > -1.5", "v = 'x' OR v <> t", "'a' <= t AND v >= w",
      "v IN ('a', 'b''c')", "t NOT IN ('a', 'b')", "b IN (1, 2)", "s IN (1, 2)", "n NOT IN (1, 2.5, -3)",
      "i IN (1, 2.5)", "i IN (7)", "NOT flag OR t IS NOT NULL", "NOT (i > 0 AND b > 0)",
      "(i > 0 AND b > 0) AND (s > 0 OR flag)", "flag = true OR flag", "i NOT BETWEEN 1 AND 5",
      "i BETWEEN 1 AND 5 AND b > 0", "v NOT LIKE 'a%' AND t ILIKE 'b%'",
      "lower(v) = 'x' AND length(btrim(t, 'x')) > 0 AND abs(i) < 10", "upper(t) <> ltrim(v) AND char_length(w) < 9",
      "coalesce(n, 0) > 0 AND greatest(b, 1) < 5 AND least(i, 2) > 0", "coalesce(w, 'x') = v", "t || v <> 'a' || t",
      "- i < 0", "i + b > s AND i % s = 0", "d >= '2000-01-01' AND ts < '2100-01-01 00:00:00'", "\"Mixed Case\" > \"order\"",
      "(i > 0) IS NOT NULL", "(i + b) IS NULL OR (i > 0 OR b > 0) AND s > 0", "jsonb_typeof(j) = 'object'",
      "j -> 'a' IS NULL OR j ->> 0 <> 'x'", "(i > 0) = (b > 0)");

  /** What SQLAlchemy reflects: the name and the text of each check. */
  private static final String REFLECTION = """
      import sys
      import psycopg
      from sqlalchemy import create_engine, inspect

      engine = create_engine("postgresql+psycopg://", creator=lambda: psycopg.connect(sys.argv[1]))
      inspector = inspect(engine)
      for table in ("p13", "p14", "products", "facts", "c", "times", "g", "knows", "shapes"):
          checks = inspector.get_check_constraints(table)
          print(table, sorted((check["name"], check["sqltext"]) for check in checks))
      """;

  @Test
  void checkConstraintsAreListedWithTheirExpressions() throws Exception {
    // before the container starts, so that its psql reaches this server
    Testcontainers.exposeHostPorts(getServerPostgresPort());
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        final StringBuilder shapes = new StringBuilder("CREATE TABLE shapes (id int PRIMARY KEY, s smallint, i int, b bigint,"
            + " n numeric(10, 2), r real, f double precision, t text, v varchar(10), w varchar, flag boolean, d date,"
            + " ts timestamp, j jsonb, \"Mixed Case\" int, \"order\" int");
        for (int k = 0; k < SHAPES.size(); k++)
          shapes.append(", CONSTRAINT k").append(100 + k).append(" CHECK (").append(SHAPES.get(k)).append(')');
        for (final String table : TABLES)
          both(postgres, arcade, table);
        both(postgres, arcade, shapes.append(')').toString());
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        // the query of psql \d, the one without the pretty form, and conbin as pg_get_expr prints it
        for (final String table : List.of("p13", "p14", "products", "facts", "c", "times", "g", "knows", "shapes")) {
          sameValues(postgres, arcade, definitions(table));
          compare("\\d " + table, checksOfPsql(container, true, table), checksOfPsql(container, false, table));
        }
        assertThat(checksOfPsql(container, false, "products")).contains("\"products_price_check\" CHECK (price >= 0::numeric)");
        sameValues(postgres, arcade, "SELECT relname, relchecks FROM pg_catalog.pg_class WHERE relname IN " + CHECKED
            + " ORDER BY 1");
        sameValues(postgres, arcade, "SELECT constraint_name, check_clause FROM information_schema.check_constraints"
            + " WHERE constraint_schema = 'public' AND constraint_name NOT LIKE '%not_null' ORDER BY 1");
        sameValues(postgres, arcade, "SELECT check_clause FROM information_schema.check_constraints"
            + " WHERE constraint_name = 'products_price_check'");
        python(container, REFLECTION);

        // a shape that is not written as PostgreSQL writes it is refused, and the check is still listed
        run(arcade, "CREATE TABLE odd (a int, CONSTRAINT odd_case CHECK (CASE WHEN a > 0 THEN true ELSE false END))");
        refused(arcade, definitions("odd"), "odd_case");
        refused(arcade, "SELECT check_clause FROM information_schema.check_constraints", "odd_case");
        compare("the names of the checks of odd", "    conname|\n    odd_case|\n", answer(arcade,
            "SELECT conname FROM pg_catalog.pg_constraint WHERE conrelid = 'odd'::regclass AND contype = 'c'", false));

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  private static String definitions(final String table) {
    return "SELECT r.conname, pg_catalog.pg_get_constraintdef(r.oid, true), pg_catalog.pg_get_constraintdef(r.oid),"
        + " pg_catalog.pg_get_expr(r.conbin, r.conrelid) FROM pg_catalog.pg_constraint r WHERE r.conrelid = '" + table
        + "'::regclass AND r.contype = 'c' ORDER BY 1";
  }

  /** The "Check constraints:" part of what psql prints for {@code \d table}, run in the container of PostgreSQL. */
  private String checksOfPsql(final GenericContainer<?> container, final boolean real, final String table) throws Exception {
    final String server = real ?
        "PGPASSWORD=sidebyside psql -h localhost -p 5432 -U postgres -d sidebyside" :
        "PGPASSWORD=" + DEFAULT_PASSWORD_FOR_TESTS + " PGOPTIONS='-c arcadedb.language=postgresql' psql -h"
            + " host.testcontainers.internal -p " + getServerPostgresPort() + " -U root -d " + getDatabaseName();
    final String printed = container.execInContainer("sh", "-c", "PGSSLMODE=disable " + server + " -X -q -c '\\d " + table
        + "' 2>&1").getStdout();
    final int start = printed.indexOf("Check constraints:\n");
    if (start < 0)
      return printed;
    return printed.substring(start).lines().skip(1).takeWhile(line -> line.startsWith("    "))
        .collect(Collectors.joining("\n", "Check constraints:\n", "\n"));
  }

  private static final String CHECKED = "('p13', 'p14', 'products', 'facts', 'c', 'times', 'g', 'knows')";

  @Test
  void checkConstraintsAnswerAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final String table : TABLES)
          both(postgres, arcade, table);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
        // PostgreSQL 18 lists each NOT NULL too, which the language does not
        sameValues(postgres, arcade, "SELECT table_name, constraint_name, constraint_type, is_deferrable, enforced FROM"
            + " information_schema.table_constraints WHERE table_name IN " + CHECKED
            + " AND constraint_type = 'CHECK' AND constraint_name NOT LIKE '%not_null' ORDER BY 1, 2");
        sameValues(postgres, arcade, "SELECT table_name, column_name, constraint_name FROM"
            + " information_schema.constraint_column_usage WHERE table_name IN " + CHECKED
            + " AND constraint_name LIKE '%check%' ORDER BY 1, 2, 3");
        sameValues(postgres, arcade, "SELECT count(*) FROM information_schema.key_column_usage WHERE constraint_name"
            + " LIKE '%check%'");

        for (final String write : List.of("INSERT INTO p13 VALUES (1, 1), (2, NULL)",
            "INSERT INTO p14 VALUES (1, 1, 2), (2, NULL, 1), (3, 5, NULL)",
            "INSERT INTO products (sku, name, price) VALUES ('s1', 'one', 0)",
            "INSERT INTO facts (content, confidence) VALUES ('a', 0), ('b', 1), ('c', NULL)",
            "INSERT INTO facts (content) VALUES ('default')", "INSERT INTO c (id, a, b) VALUES (1, 1, 2)",
            "INSERT INTO c (id, a, b) VALUES (1, 3, 4) ON CONFLICT DO NOTHING",
            "INSERT INTO times (id, s, e, d, j, u, w) VALUES (1, '2026-01-01 00:00:00Z', '2026-01-02 00:00:00Z', '2026-01-01', '{}',"
                + " '11111111-1111-1111-1111-111111111111', NULL)",
            "INSERT INTO g VALUES (1), (NULL)", "INSERT INTO person VALUES (1), (2)", "INSERT INTO knows VALUES (1, 2, 3)"))
          both(postgres, arcade, write);

        for (final String violation : List.of("INSERT INTO p13 VALUES (3, 0)", "UPDATE p13 SET n = -n WHERE id = 1",
            "INSERT INTO p13 VALUES (10, 1), (11, 0)", "INSERT INTO p14 VALUES (4, 5, 1)",
            "UPDATE p14 SET b = 0 WHERE id = 1", "INSERT INTO products (sku, name, price) VALUES ('s2', 'two', -1)",
            "INSERT INTO facts (content, confidence) VALUES ('d', 1.5)", "UPDATE facts SET confidence = -0.1",
            "INSERT INTO c (id, a, b) VALUES (2, -1, -2)", "INSERT INTO c (id, d) VALUES (2, -1)",
            "INSERT INTO c (id, c) VALUES (2, '')", "INSERT INTO c (id, b) VALUES (2, -1)",
            "INSERT INTO c (id, b, f) VALUES (2, 1, -1)",
            // a check comes before the conflict, and before a unique key
            "INSERT INTO c (id, a) VALUES (1, -1) ON CONFLICT DO NOTHING", "INSERT INTO c (id, a) VALUES (1, -1)",
            "INSERT INTO c (id, a) VALUES (1, -1) ON CONFLICT (id) DO UPDATE SET a = 5",
            "INSERT INTO c (id, a, b) VALUES (1, 5, 9) ON CONFLICT (id) DO UPDATE SET a = -5",
            "INSERT INTO times (id, s, e) VALUES (2, '2026-01-03 00:00:00Z', '2026-01-02 00:00:00Z')", "INSERT INTO times (id) VALUES (2)",
            "INSERT INTO times (id, e, d) VALUES (2, '2026-01-02 00:00:00Z', '1999-01-01')",
            "INSERT INTO times (id, e, j) VALUES (2, '2026-01-02 00:00:00Z', '{\"k\": \"bad\"}')",
            "INSERT INTO times (id, e, u) VALUES (2, '2026-01-02 00:00:00Z', '00000000000000000000000000000000')",
            "INSERT INTO times (id, e, w) VALUES (0, '2026-01-02 00:00:00Z', 'ok')", "INSERT INTO knows VALUES (2, 1, 0)",
            "UPDATE knows SET weight = -1")) {
          sameError(postgres, arcade, violation, "23514");
          compare(violation + ": the constraint", violated(postgres, violation), violated(arcade, violation));
        }
        sameError(postgres, arcade, "INSERT INTO c (id, a) VALUES (NULL, -1)", "23502");
        sameError(postgres, arcade, "INSERT INTO g VALUES (0)", "22012");
        for (final String table : List.of("p13", "p14", "products", "facts", "c", "times", "g", "knows"))
          sameValues(postgres, arcade, "SELECT count(*) FROM " + table);
        sameValues(postgres, arcade, "SELECT * FROM c ORDER BY id");

        for (final String[] error : new String[][] { { "CREATE TABLE e (a int CHECK (sum(a) > 0))", "42803" },
            { "CREATE TABLE e (a int CHECK (a IN (SELECT 1)))", "0A000" }, { "CREATE TABLE e (a int CHECK (a + 1))", "42804" },
            { "CREATE TABLE e (a int CHECK (nosuch > 0))", "42703" },
            { "CREATE TABLE e (a int CHECK (row_number() OVER () > 0))", "42P20" },
            { "CREATE TABLE e (a int CHECK (xmin::text <> ''))", "42P10" },
            { "CREATE TABLE e (a int CHECK (other.a > 0))", "42P01" },
            { "CREATE TABLE e (a int CHECK (a > 'x'))", "22P02" }, { "CREATE TABLE e (a int CHECK (a || a = 'x'))", "42883" },
            { "CREATE TABLE e (a int CONSTRAINT k CHECK (a > 0), b int CONSTRAINT k CHECK (b > 0))", "42710" } })
          sameError(postgres, arcade, error[0], error[1]);

        // what PostgreSQL accepts and every language cannot keep says what to do
        refused(arcade, "CREATE TABLE e (a timestamptz CHECK (a < now()))", "The function now() in a CHECK");
        refused(arcade, "CREATE TABLE e (a date CHECK (a < CURRENT_DATE))", "CURRENT_DATE in a CHECK");
        refused(arcade, "CREATE TABLE e (a timestamptz CHECK (a > '2000-01-01 00:00:00Z'))", "A timestamptz value in a CHECK");
        refused(arcade, "CREATE TABLE e (a timestamptz, b date, CHECK (a > b))", "A timestamptz value in a CHECK");
        refused(arcade, "CREATE TABLE e (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL"
            + " REFERENCES person (id), CHECK (source_id <> target_id))", "A CHECK that reads an end of a link table");
        refused(arcade, "CREATE TABLE e (a int CHECK (rid IS NOT NULL))", "A CHECK that reads the record id");
        refused(arcade, "CREATE TABLE e (a int CHECK (a > 0) NO INHERIT)", "not supported");
        sameValues(postgres, arcade, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'e'");

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }

  /** The constraint that the error of a statement names. */
  private static String violated(final Connection connection, final String statement) {
    try (final Statement run = connection.createStatement()) {
      run.execute(statement);
      return "no error";
    } catch (final SQLException e) {
      final Matcher matcher = VIOLATED.matcher(e.getMessage());
      return matcher.find() ? matcher.group(1) : e.getMessage();
    }
  }
}
