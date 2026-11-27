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
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ALTER TABLE} of columns on the {@code postgresql} language against PostgreSQL 18 (fork issue #230), over JDBC: what the
 * rows that exist hold afterwards, what later writes do, the errors, and the catalog; and what the {@code sql} language sees.
 */
class PostgreSQLAlterTableTest extends PostgreSQLSideBySideTestBase {

  @Test
  void alterTableAnswersAsOnPostgreSql() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        both(postgres, arcade, "CREATE TABLE facts (id int PRIMARY KEY, content text)");
        both(postgres, arcade, "CREATE TABLE empty_one (id int PRIMARY KEY)");
        both(postgres, arcade, "INSERT INTO facts VALUES (1, 'one'), (2, NULL), (3, 'three')");

        for (final String change : List.of("ALTER TABLE facts ADD COLUMN expires_at timestamptz",
            "ALTER TABLE facts ADD COLUMN IF NOT EXISTS expires_at timestamptz",
            "ALTER TABLE facts ADD COLUMN IF NOT EXISTS content text",
            "ALTER TABLE facts ADD COLUMN importance integer NOT NULL DEFAULT 0",
            "ALTER TABLE public.facts ADD note text DEFAULT 'none', ADD COLUMN price numeric(8,2) DEFAULT 1.005",
            "ALTER TABLE facts ADD COLUMN code varchar(4), ADD COLUMN meta jsonb NOT NULL DEFAULT '{}'",
            "ALTER TABLE facts ADD COLUMN tags text[] DEFAULT '{}', ADD COLUMN gone boolean DEFAULT NULL",
            "ALTER TABLE facts ADD COLUMN created timestamptz DEFAULT now()",
            "ALTER TABLE facts ADD COLUMN token uuid NOT NULL DEFAULT gen_random_uuid()",
            "ALTER TABLE empty_one ADD COLUMN must text NOT NULL",
            "ALTER TABLE IF EXISTS nowhere ADD COLUMN a int", "ALTER TABLE facts ALTER COLUMN importance SET DEFAULT 5",
            "ALTER TABLE facts ALTER note DROP DEFAULT", "ALTER TABLE facts ALTER COLUMN gone SET DEFAULT true",
            "ALTER TABLE facts ALTER COLUMN importance DROP NOT NULL", "ALTER TABLE facts ALTER COLUMN price SET NOT NULL",
            "ALTER TABLE facts ALTER COLUMN content DROP NOT NULL",
            "INSERT INTO facts (id, content) VALUES (4, 'four')",
            "INSERT INTO facts (id, content, importance, note, code) VALUES (5, 'five', NULL, 'n', 'ab  ')"))
          both(postgres, arcade, change);
        assertThat(failures).as(String.join("\n", failures)).isEmpty();

        for (final String[] error : new String[][] { { "ALTER TABLE nowhere ADD COLUMN a int", "42P01" },
            { "ALTER TABLE facts ADD COLUMN content text", "42701" }, { "ALTER TABLE facts ADD COLUMN a int, ADD a int", "42701" },
            { "ALTER TABLE facts ADD COLUMN xmin int", "42701" }, { "ALTER TABLE facts ADD COLUMN must text NOT NULL", "23502" },
            { "ALTER TABLE facts ADD COLUMN a int NULL NOT NULL", "42601" },
            { "ALTER TABLE facts ADD COLUMN a int DEFAULT 1 DEFAULT 2", "42601" },
            { "ALTER TABLE facts ADD COLUMN a int DEFAULT 'x'", "22P02" },
            { "ALTER TABLE facts ALTER COLUMN nosuch SET NOT NULL", "42703" },
            { "ALTER TABLE facts ALTER COLUMN nosuch DROP DEFAULT", "42703" },
            { "ALTER TABLE facts ALTER COLUMN content SET NOT NULL", "23502" },
            // the first action is taken back with the second
            { "ALTER TABLE facts ADD COLUMN a int DEFAULT 1, ALTER COLUMN content SET NOT NULL", "23502" },
            { "ALTER TABLE facts ALTER COLUMN id DROP NOT NULL", "42P16" },
            { "ALTER TABLE facts ALTER COLUMN importance SET DEFAULT 'x'", "22P02" },
            { "INSERT INTO facts (id, price) VALUES (9, NULL)", "23502" }, { "INSERT INTO facts (id, code) VALUES (9, 'abcde')", "22001" },
            { "INSERT INTO empty_one (id) VALUES (1)", "23502" }, { "ALTER TABLE facts ADD COLUMN a int,", "42601" } })
          sameError(postgres, arcade, error[0], error[1]);
        sameError(postgres, arcade, "SELECT a FROM facts", "42703");

        sameValues(postgres, arcade, "SELECT id, content, expires_at, importance, note, price::text, code, meta::text, tags::text,"
            + " gone FROM facts ORDER BY id");
        // one time of the clock for the rows that existed, and a new uuid for each
        sameValues(postgres, arcade, "SELECT count(DISTINCT created), count(created) FROM facts WHERE id <= 3");
        sameValues(postgres, arcade, "SELECT count(DISTINCT token), count(token) FROM facts");
        sameValues(postgres, arcade, "SELECT column_name, data_type, is_nullable, column_default, character_maximum_length,"
            + " numeric_scale FROM information_schema.columns WHERE table_name = 'facts' ORDER BY ordinal_position");

        // what PostgreSQL runs and the language does not yet says what to write
        refused(arcade, "ALTER TABLE facts ADD COLUMN u int UNIQUE", "CREATE UNIQUE INDEX ON facts (u)");
        refused(arcade, "ALTER TABLE facts ADD COLUMN c int CHECK (c > 0)", "in CREATE TABLE");
        refused(arcade, "ALTER TABLE facts ADD COLUMN s serial", "CREATE TABLE");
        arcade.setAutoCommit(false);
        refused(arcade, "ALTER TABLE facts ADD COLUMN b int", "autocommit");
        arcade.rollback();
        arcade.setAutoCommit(true);

        // sql sees the columns and their values, and its writes keep them
        compare("sql reads", "    0|1.01|\n", answer(arcade, "{sql} SELECT importance, price.asString() AS price FROM facts"
            + " WHERE id = 1", false).replaceFirst("^[^\n]*\n", ""));
        run(arcade, "{sql} INSERT INTO facts SET id = 6, content = 'six', price = 2");
        compare("defaults of a row of sql", "    6|5|true|\n",
            answer(arcade, "SELECT id, importance, gone::text FROM facts WHERE id = 6", false).replaceFirst("^[^\n]*\n", ""));

        // a property that the table does not declare is neither shown nor written over
        run(arcade, "{sql} UPDATE facts SET extra = 'kept' WHERE id = 1");
        refused(arcade, "ALTER TABLE facts ADD COLUMN extra text", "{sql}UPDATE `facts` REMOVE `extra`");
        run(arcade, "{sql} UPDATE facts REMOVE extra");
        run(arcade, "ALTER TABLE facts ADD COLUMN extra text");
        compare("the column after the removal", "    0|\n",
            answer(arcade, "SELECT count(extra) AS n FROM facts", false).replaceFirst("^[^\n]*\n", ""));

        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
    }
  }
}
