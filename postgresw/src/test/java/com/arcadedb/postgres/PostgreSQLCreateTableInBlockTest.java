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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CREATE TABLE} in a transaction block of the {@code postgresql} language against PostgreSQL 18 (fork issue #229): over
 * JDBC, and with psycopg and SQLAlchemy on their defaults, which open a transaction.
 */
class PostgreSQLCreateTableInBlockTest extends PostgreSQLSideBySideTestBase {
  private static final String SCRIPT = """
      import sys
      import time
      import psycopg
      import sqlalchemy as sa
      from sqlalchemy import orm

      CONNINFO = sys.argv[1]

      with psycopg.connect(CONNINFO) as conn:
          cursor = conn.cursor()
          cursor.execute("CREATE TABLE kept (id serial PRIMARY KEY, name text NOT NULL)")
          print(cursor.statusmessage)
          cursor.execute("INSERT INTO kept (name) VALUES (%s) RETURNING id", ("a",))
          print(cursor.fetchone()[0])
          conn.commit()
          cursor.execute("CREATE TABLE gone (id serial PRIMARY KEY)")
          cursor.execute("INSERT INTO gone DEFAULT VALUES RETURNING id")
          print(cursor.fetchone()[0])
          conn.rollback()
          try:
              cursor.execute("SELECT * FROM gone")
          except psycopg.Error as e:
              print(e.sqlstate)
          conn.rollback()
          cursor.execute("SELECT * FROM kept")
          print(cursor.fetchall())
          conn.commit()

      class Base(orm.DeclarativeBase):
          pass

      class Account(Base):
          __tablename__ = "orm_account"
          id: orm.Mapped[int] = orm.mapped_column(primary_key=True)
          name: orm.Mapped[str] = orm.mapped_column(sa.String)
          created_at = sa.Column(sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False)

      class Note(Base):
          __tablename__ = "orm_note"
          id: orm.Mapped[int] = orm.mapped_column(sa.BigInteger, primary_key=True)
          account_id: orm.Mapped[int] = orm.mapped_column(sa.Integer)
          body: orm.Mapped[str] = orm.mapped_column(sa.Text)

      engine = sa.create_engine("postgresql+psycopg://", creator=lambda: psycopg.connect(CONNINFO))
      Base.metadata.create_all(engine)
      Base.metadata.create_all(engine)
      with orm.Session(engine) as session:
          account = Account(name="Ada")
          session.add(account)
          session.flush()
          session.add(Note(account_id=account.id, body="first"))
          session.commit()
          print(account.id, session.execute(sa.select(Note.id, Note.account_id, Note.body)).all())

      try:
          with engine.begin() as connection:
              connection.execute(sa.text("CREATE TABLE undone (id serial PRIMARY KEY)"))
              raise RuntimeError("stop")
      except RuntimeError:
          print("rolled back")
      print(sa.inspect(engine).has_table("undone"), sa.inspect(engine).has_table("orm_account"))

      # a connection that ends inside its block takes the empty table with it, a moment after it closed
      other = psycopg.connect(CONNINFO)
      other.execute("CREATE TABLE left_open (a int)")
      other.close()
      for attempt in range(100):
          if not sa.inspect(engine).has_table("left_open"):
              break
          time.sleep(0.1)
      print(sa.inspect(engine).has_table("left_open"))
      """;

  @Test
  void aTransactionBlockCreatesTablesAndARollbackTakesTheEmptyOnesBack() throws Exception {
    try (final GenericContainer<?> container = startPostgres()) {
      try (final Connection postgres = postgres(container, ""); final Connection arcade = arcade("")) {
        for (final Connection connection : new Connection[] { postgres, arcade })
          connection.setAutoCommit(false);

        both(postgres, arcade, "CREATE TABLE t1 (id bigserial PRIMARY KEY, v text)");
        both(postgres, arcade, "INSERT INTO t1 (v) VALUES ('a')");
        sameValues(postgres, arcade, "SELECT * FROM t1");
        postgres.rollback();
        arcade.rollback();
        sameError(postgres, arcade, "SELECT * FROM t1", "42P01");
        postgres.rollback();
        arcade.rollback();

        both(postgres, arcade, "CREATE TABLE t2 (id serial PRIMARY KEY, v text)");
        both(postgres, arcade, "INSERT INTO t2 (v) VALUES ('a'), ('b')");
        postgres.commit();
        arcade.commit();
        sameValues(postgres, arcade, "SELECT * FROM t2 ORDER BY id");

        // a failed block is rolled back, whatever ends it
        both(postgres, arcade, "CREATE TABLE t3 (a int)");
        sameError(postgres, arcade, "SELECT nope FROM t3", "42703");
        postgres.commit();
        arcade.commit();
        sameError(postgres, arcade, "SELECT * FROM t3", "42P01");
        postgres.rollback();
        arcade.rollback();

        // statements sent together are one block of their own, which the error ends
        for (final Connection connection : new Connection[] { postgres, arcade })
          connection.setAutoCommit(true);
        sameError(postgres, arcade, "CREATE TABLE t4 (a int); SELECT nope FROM t4", "42703");
        sameError(postgres, arcade, "SELECT * FROM t4", "42P01");

        // another session sees the table before the commit, unlike on PostgreSQL, and what it writes keeps the table
        arcade.setAutoCommit(false);
        run(arcade, "CREATE TABLE t5 (a int)");
        try (final Connection other = arcade("")) {
          run(other, "INSERT INTO t5 VALUES (1)");
        }
        arcade.rollback();
        arcade.setAutoCommit(true);
        assertThat(answer(arcade, "SELECT a FROM t5", false)).isEqualTo("    a|\n    1|\n");

        // an index is still refused in a block
        arcade.setAutoCommit(false);
        refused(arcade, "CREATE INDEX ON t2 (v)", "CREATE INDEX inside a transaction block");
        arcade.rollback();
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
      python(container, SCRIPT);
    }
  }
}
