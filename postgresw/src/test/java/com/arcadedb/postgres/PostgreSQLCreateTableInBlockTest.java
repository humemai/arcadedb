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
 * {@code CREATE TABLE} in a transaction block of the {@code postgresql} language against PostgreSQL 18 (fork issue #229), and
 * {@code CREATE INDEX} on a table that the block made (fork issue #277): over JDBC, and with psycopg and SQLAlchemy on their
 * defaults, which open a transaction.
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

      # create_all for models with indexes, on the defaults: one block holds the tables and their indexes
      class Tagged(orm.DeclarativeBase):
          pass

      class Tag(Tagged):
          __tablename__ = "orm_tag"
          id: orm.Mapped[int] = orm.mapped_column(primary_key=True)
          label: orm.Mapped[str] = orm.mapped_column(sa.String, index=True)
          code: orm.Mapped[int] = orm.mapped_column(sa.Integer)
          __table_args__ = (sa.Index("ux_orm_tag_code", "code", unique=True),)

      Tagged.metadata.create_all(engine)
      Tagged.metadata.create_all(engine)
      with orm.Session(engine) as session:
          session.add_all([Tag(label="a", code=1), Tag(label="b", code=2)])
          session.commit()
          print(session.execute(sa.select(Tag.code).where(Tag.label == "b")).all())
          session.add(Tag(label="c", code=1))
          try:
              session.commit()
          except sa.exc.IntegrityError as e:
              print(e.orig.sqlstate)

      indexed = sa.MetaData()
      sa.Table("undone_indexed", indexed, sa.Column("id", sa.Integer, primary_key=True),
               sa.Column("label", sa.String, index=True))
      try:
          with engine.begin() as connection:
              indexed.create_all(connection)
              connection.execute(sa.text("INSERT INTO undone_indexed (label) VALUES ('a')"))
              raise RuntimeError("stop")
      except RuntimeError:
          print("rolled back with its index")
      print(sa.inspect(engine).has_table("undone_indexed"))
      # the names of the table and of its index are free again
      indexed.create_all(engine)
      with engine.connect() as connection:
          print(connection.execute(sa.text("SELECT count(*) FROM undone_indexed")).scalar())

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

        // an index of a table that the block made runs, and a rollback takes both back (fork issue #277)
        for (final Connection connection : new Connection[] { postgres, arcade })
          connection.setAutoCommit(false);
        both(postgres, arcade, "CREATE TABLE t6 (id serial PRIMARY KEY, a int, b text)");
        both(postgres, arcade, "CREATE INDEX t6_a ON t6 (a)");
        both(postgres, arcade, "INSERT INTO t6 (a, b) VALUES (1, 'x'), (2, 'y')");
        both(postgres, arcade, "CREATE UNIQUE INDEX t6_b ON t6 (b)");
        sameValues(postgres, arcade, "SELECT a, b FROM t6 WHERE a = 2");
        sameError(postgres, arcade, "INSERT INTO t6 (a, b) VALUES (3, 'x')", "23505");
        postgres.rollback();
        arcade.rollback();
        sameError(postgres, arcade, "SELECT * FROM t6", "42P01");
        postgres.rollback();
        arcade.rollback();

        // the names are free again, and the indexes of a committed block answer and hold their keys
        both(postgres, arcade, "CREATE TABLE t6 (a int, b text)");
        both(postgres, arcade, "INSERT INTO t6 VALUES (1, 'x'), (2, 'y')");
        both(postgres, arcade, "CREATE INDEX t6_a ON t6 (a)");
        both(postgres, arcade, "CREATE UNIQUE INDEX t6_b ON t6 (b)");
        both(postgres, arcade, "INSERT INTO t6 VALUES (1, 'z')");
        postgres.commit();
        arcade.commit();
        sameValues(postgres, arcade, "SELECT b FROM t6 WHERE a = 1 ORDER BY b");
        sameError(postgres, arcade, "INSERT INTO t6 VALUES (3, 'y')", "23505");
        postgres.rollback();
        arcade.rollback();

        // a unique index over rows that repeat a key fails the block
        both(postgres, arcade, "CREATE TABLE t7 (a int)");
        both(postgres, arcade, "INSERT INTO t7 VALUES (1), (1)");
        sameError(postgres, arcade, "CREATE UNIQUE INDEX t7_a ON t7 (a)", "23505");
        sameError(postgres, arcade, "SELECT 1", "25P02");
        postgres.rollback();
        arcade.rollback();
        sameError(postgres, arcade, "SELECT * FROM t7", "42P01");
        postgres.rollback();
        arcade.rollback();

        // statements sent together are one block of their own, and the error takes the table and its index
        for (final Connection connection : new Connection[] { postgres, arcade })
          connection.setAutoCommit(true);
        sameError(postgres, arcade, "CREATE TABLE t8 (a int); CREATE INDEX t8_a ON t8 (a); SELECT nope FROM t8", "42703");
        sameError(postgres, arcade, "SELECT * FROM t8", "42P01");

        // the index of a table that was there before the block is refused
        arcade.setAutoCommit(false);
        refused(arcade, "CREATE INDEX ON t2 (v)", "CREATE INDEX inside a transaction block");
        arcade.rollback();
        assertThat(failures).as(String.join("\n", failures)).isEmpty();
      }
      python(container, SCRIPT);
    }
  }
}
