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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Writes of link tables whose ends refer to a unique column (fork issue #213), declared by {@code CREATE TABLE}: a link is
 * inserted by the keys, a key that links point at cannot change, a delete follows the rule of the reference, and what the
 * language cannot keep is refused when the table is made.
 */
class LinkKeyWriteTest extends TestHelper {

  @Override
  protected void beginTest() {
    for (final String statement : List.of(
        "CREATE TABLE person (id bigint PRIMARY KEY, name text NOT NULL)",
        "CREATE TABLE company (code text UNIQUE, name text)",
        "CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL REFERENCES person, since int)",
        "CREATE TABLE works_at (source_id bigint NOT NULL REFERENCES person (id) ON DELETE CASCADE, "
            + "target_id varchar NOT NULL REFERENCES company (code) ON DELETE CASCADE, since int)"))
      database.command("postgresql", statement);
    write("INSERT INTO person (id, name) VALUES (1, 'Ada'), (2, 'Bo'), (3, 'Cy'), (4, 'Di')");
    write("INSERT INTO company VALUES ('acme', 'Acme'), ('birch', 'Birch')");
    write("INSERT INTO knows (source_id, target_id, since) VALUES (1, 2, 2020), (1, 3, 2021), (2, 3, 2022)");
    write("INSERT INTO works_at VALUES (1, 'acme', 2019), (3, 'birch', 2021)");
  }

  private static List<Object> row(final Object... values) {
    return Arrays.asList(values);
  }

  private static List<List<Object>> read(final ResultSet resultSet) {
    final List<List<Object>> out = new ArrayList<>();
    while (resultSet.hasNext()) {
      final Result result = resultSet.next();
      final List<Object> values = new ArrayList<>();
      for (final String name : result.getPropertyNames())
        values.add(result.getProperty(name));
      out.add(values);
    }
    return out;
  }

  private List<List<Object>> rows(final String sql) {
    try (final ResultSet resultSet = database.query("postgresql", sql)) {
      return read(resultSet);
    }
  }

  private List<List<Object>> write(final String sql) {
    final List<List<Object>> out = new ArrayList<>();
    database.transaction(() -> {
      out.clear();
      try (final ResultSet resultSet = database.command("postgresql", sql)) {
        out.addAll(read(resultSet));
      }
    });
    return out;
  }

  private static String refusal(final ThrowingCallable call) {
    final Throwable thrown = catchThrowable(call);
    assertThat(thrown).as("a refusal").isNotNull();
    for (Throwable current = thrown; current != null; current = current.getCause())
      try {
        return current.getClass().getMethod("getSqlState").invoke(current) + " " + current.getMessage();
      } catch (final ReflectiveOperationException e) {
        // look at the cause
      }
    throw new AssertionError("not a refusal of the language: " + thrown, thrown);
  }

  private String refusedWrite(final String sql) {
    return refusal(() -> write(sql));
  }

  private String refusedTable(final String end) {
    return refusal(() -> database.command("postgresql",
        "CREATE TABLE bad (source_id " + end + ", target_id bigint NOT NULL REFERENCES person (id))"));
  }

  @Test
  void theTableIsShownAsItWasDeclared() {
    assertThat(rows("SELECT * FROM knows ORDER BY since")).containsExactly(row(1L, 2L, 2020), row(1L, 3L, 2021), row(2L, 3L, 2022));
    assertThat(rows("SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'works_at' "
        + "ORDER BY ordinal_position")).containsExactly(row("source_id", "bigint", "NO"),
        row("target_id", "character varying", "NO"), row("since", "integer", "YES"));
    assertThat(rows("SELECT constraint_name, unique_constraint_name, delete_rule FROM information_schema.referential_constraints "
        + "ORDER BY constraint_name")).containsExactly(row("knows_source_id_fkey", "person_pkey", "NO ACTION"),
        row("knows_target_id_fkey", "person_pkey", "NO ACTION"), row("works_at_source_id_fkey", "person_pkey", "CASCADE"),
        row("works_at_target_id_fkey", "company_code_key", "CASCADE"));
    assertThat(rows("SELECT q.name FROM person p JOIN knows k ON k.source_id = p.id JOIN person q ON q.id = k.target_id "
        + "WHERE p.id = 1 ORDER BY q.name")).containsExactly(row("Bo"), row("Cy"));
  }

  @Test
  void aLinkIsInsertedByItsKeys() {
    assertThat(write("INSERT INTO knows (source_id, target_id, since) VALUES (3, '1', 2023) RETURNING source_id, target_id, since"))
        .containsExactly(row(3L, 1L, 2023));
    write("INSERT INTO knows (source_id, target_id, since) SELECT 4, p.id, 2024 FROM person p WHERE p.name = 'Ada'");
    assertThat(rows("SELECT source_id FROM knows WHERE target_id = 1 ORDER BY source_id")).containsExactly(row(3L), row(4L));
    // a row of the same transaction can be linked
    database.transaction(() -> {
      database.command("postgresql", "INSERT INTO person (id, name) VALUES (5, 'Ed')");
      database.command("postgresql", "INSERT INTO knows (source_id, target_id) VALUES (1, 5)");
    });
    assertThat(rows("SELECT count(*) FROM knows k JOIN person p ON p.id = k.target_id WHERE p.name = 'Ed'")).containsExactly(row(1L));
  }

  @Test
  void aLinkWithoutItsRowsIsRefused() {
    assertThat(refusedWrite("INSERT INTO knows (source_id, target_id) VALUES (9, 1)")).startsWith("23503")
        .contains("knows_source_id_fkey").contains("Key (source_id)=(9) is not present in table \"person\"");
    assertThat(refusedWrite("INSERT INTO works_at VALUES (1, 'nobody', 1)")).startsWith("23503").contains("company");
    assertThat(refusedWrite("INSERT INTO knows (source_id, target_id) VALUES (NULL, 1)")).startsWith("23502");
    assertThat(refusedWrite("INSERT INTO knows (target_id) VALUES (1)")).startsWith("23502");
    assertThat(refusedWrite("INSERT INTO knows (source_id, target_id) VALUES ('#1:0', 1)")).startsWith("22P02");
    assertThat(refusedWrite("INSERT INTO knows (source_id, target_id) SELECT rid, rid FROM person")).startsWith("42804");
    assertThat(rows("SELECT count(*) FROM knows")).containsExactly(row(3L));
  }

  @Test
  void aKeyThatLinksPointAtCannotChange() {
    assertThat(refusedWrite("UPDATE person SET id = 10 WHERE id = 1")).startsWith("23503").contains("knows_source_id_fkey")
        .contains("Key (id)=(1) is still referenced from table \"knows\"");
    assertThat(refusedWrite("UPDATE person SET id = 30 WHERE id = 3")).startsWith("23503").contains("knows_target_id_fkey");
    assertThat(refusedWrite("UPDATE company SET code = 'x' WHERE code = 'acme'")).startsWith("23503");
    assertThat(refusedWrite("UPDATE company SET code = NULL WHERE code = 'acme'")).startsWith("23503");
    assertThat(refusedWrite("INSERT INTO person (id, name) VALUES (1, 'A') ON CONFLICT (id) DO UPDATE SET id = 11"))
        .startsWith("23503");
    assertThat(refusedWrite("UPDATE person SET id = 10 FROM company c WHERE person.id = 1 AND c.code = 'acme'"))
        .startsWith("23503");
    // the same value, another column, and a row that no link points at
    write("UPDATE person SET id = id, name = 'Ada L' WHERE id = 1");
    write("UPDATE person SET id = 40 WHERE id = 4");
    assertThat(rows("SELECT id, name FROM person ORDER BY id"))
        .containsExactly(row(1L, "Ada L"), row(2L, "Bo"), row(3L, "Cy"), row(40L, "Di"));
    assertThat(refusedWrite("UPDATE knows SET source_id = 2 WHERE source_id = 1")).startsWith("0A000");
  }

  @Test
  void aDeleteFollowsTheRuleOfTheReference() {
    assertThat(refusedWrite("DELETE FROM person WHERE id = 1")).startsWith("23503")
        .contains("Key (id)=(1) is still referenced from table \"knows\"");
    assertThat(refusedWrite("DELETE FROM person WHERE id = 3")).startsWith("23503").contains("knows_target_id_fkey");
    write("DELETE FROM person WHERE id = 4");
    write("DELETE FROM company WHERE code = 'birch'");
    assertThat(rows("SELECT source_id, target_id FROM works_at")).containsExactly(row(1L, "acme"));
    write("DELETE FROM knows WHERE target_id = 3");
    write("DELETE FROM person WHERE id = 3");
    assertThat(rows("SELECT count(*) FROM person")).containsExactly(row(2L));
    assertThat(rows("SELECT count(*) FROM knows")).containsExactly(row(1L));
  }

  @Test
  void aLinkThatACascadeDeletesDoesNotStopTheDelete() {
    database.command("postgresql", "CREATE TABLE follows (source_id bigint NOT NULL REFERENCES person (id) ON DELETE CASCADE, "
        + "target_id bigint NOT NULL REFERENCES person (id))");
    write("INSERT INTO person (id, name) VALUES (5, 'Ed'), (6, 'Fay')");
    write("INSERT INTO follows VALUES (5, 6)");
    assertThat(refusedWrite("DELETE FROM person WHERE id = 6")).startsWith("23503").contains("follows_target_id_fkey");
    write("DELETE FROM person WHERE id IN (5, 6)");
    assertThat(rows("SELECT count(*) FROM follows")).containsExactly(row(0L));
  }

  @Test
  void aOneWayLinkTypeIsCheckedFromBothEnds() {
    database.command("sql", "CREATE EDGE TYPE saw UNIDIRECTIONAL CUSTOM source_table = 'person', source_column = 'id', "
        + "target_table = 'person', target_column = 'id', target_on_delete = 'NO ACTION'");
    write("INSERT INTO person (id, name) VALUES (5, 'Ed'), (6, 'Fay')");
    write("INSERT INTO saw (source_id, target_id) VALUES (5, 6)");
    assertThat(refusedWrite("UPDATE person SET id = 60 WHERE id = 6")).startsWith("23503");
    assertThat(refusedWrite("DELETE FROM person WHERE id = 6")).startsWith("23503");
  }

  @Test
  void aKeyThatAnotherLanguageChangesShowsItsNewValue() {
    database.transaction(() -> database.command("sql", "UPDATE person SET id = 100 WHERE id = 1"));
    assertThat(rows("SELECT DISTINCT source_id FROM knows ORDER BY source_id")).containsExactly(row(2L), row(100L));
  }

  @Test
  void aUniqueIndexIsEnoughToReferTo() {
    database.command("postgresql", "CREATE UNIQUE INDEX ON company (name)");
    database.command("postgresql", "CREATE TABLE owns (source_id bigint NOT NULL REFERENCES person (id), "
        + "target_id text NOT NULL REFERENCES company (name))");
    write("INSERT INTO owns VALUES (1, 'Acme')");
    assertThat(rows("SELECT target_id FROM owns")).containsExactly(row("Acme"));
    assertThat(rows("SELECT unique_constraint_name FROM information_schema.referential_constraints "
        + "WHERE constraint_name = 'owns_target_id_fkey'")).containsExactly(row((Object) null));
  }

  @Test
  void whatCannotBeKeptIsRefusedWhenTheTableIsMade() {
    assertThat(refusedTable("text NOT NULL REFERENCES person (name)")).startsWith("42830");
    assertThat(refusedTable("bigint NOT NULL REFERENCES person (nosuch)")).startsWith("42703");
    assertThat(refusedTable("bigint NOT NULL REFERENCES nobody (id)")).startsWith("42P01");
    assertThat(refusedTable("bigint NOT NULL REFERENCES company")).startsWith("42704");
    assertThat(refusedTable("text NOT NULL REFERENCES person (id)")).startsWith("42804");
    assertThat(refusedTable("int NOT NULL REFERENCES person (id)")).startsWith("0A000").contains("source_id bigint");
    assertThat(refusedTable("bigint REFERENCES person (id)")).startsWith("0A000");
    assertThat(refusedTable("bigint NOT NULL REFERENCES person (id, name)")).startsWith("42830");
    assertThat(refusal(() -> database.command("postgresql", "CREATE TABLE bad (source_id bigint NOT NULL REFERENCES person (id), "
        + "other text, target_id bigint NOT NULL REFERENCES person (id), "
        + "FOREIGN KEY (source_id, other) REFERENCES person (id, name))"))).startsWith("0A000").contains("one column");
    assertThat(refusedTable("bigint NOT NULL REFERENCES person (id) ON DELETE SET NULL")).startsWith("0A000")
        .contains("ON DELETE CASCADE");
    assertThat(refusedTable("bigint NOT NULL REFERENCES person (id) ON UPDATE RESTRICT")).startsWith("0A000")
        .contains("ON UPDATE CASCADE");
    assertThat(refusedTable("bigint NOT NULL REFERENCES person (id) ON UPDATE SET NULL")).startsWith("0A000");
    assertThat(refusedTable("text NOT NULL REFERENCES person (rid)")).startsWith("0A000").contains("ON DELETE CASCADE");
    assertThat(refusedTable("text NOT NULL REFERENCES company (id) ON DELETE CASCADE")).startsWith("42703").contains("rid");
    assertThat(database.getSchema().existsType("bad")).isFalse();
  }

  @Test
  void theColumnThatARefusalSaysToWriteRuns() {
    final String refused = refusedTable("int REFERENCES person (id) ON DELETE RESTRICT ON UPDATE CASCADE");
    assertThat(refused).startsWith("0A000")
        .contains("Write source_id bigint NOT NULL REFERENCES person (id) ON DELETE RESTRICT ON UPDATE CASCADE");
    final String column = refused.substring(refused.indexOf("Write source_id ") + "Write ".length());
    database.command("postgresql", "CREATE TABLE good (" + column + ", target_id bigint NOT NULL REFERENCES person (id))");
    assertThat(database.getSchema().existsType("good")).isTrue();
  }

  @Test
  void restrictRefusesAsNoActionDoesAndACascadeOfUpdatesShowsTheNewKey() {
    database.command("postgresql", "CREATE TABLE likes (source_id bigint NOT NULL REFERENCES person (id) ON DELETE RESTRICT "
        + "ON UPDATE CASCADE, target_id bigint NOT NULL REFERENCES person (id) ON UPDATE CASCADE)");
    write("INSERT INTO person (id, name) VALUES (7, 'Gus'), (8, 'Hal')");
    write("INSERT INTO likes VALUES (7, 8)");
    assertThat(rows("SELECT constraint_name, update_rule, delete_rule FROM information_schema.referential_constraints "
        + "WHERE constraint_name LIKE 'likes%' ORDER BY constraint_name")).containsExactly(
        row("likes_source_id_fkey", "CASCADE", "RESTRICT"), row("likes_target_id_fkey", "CASCADE", "NO ACTION"));
    assertThat(refusedWrite("DELETE FROM person WHERE id = 7")).startsWith("23001").contains("likes_source_id_fkey");
    assertThat(refusedWrite("DELETE FROM person WHERE id = 8")).startsWith("23503").contains("likes_target_id_fkey");
    write("UPDATE person SET id = 70 WHERE id = 7");
    write("UPDATE person SET id = 80 WHERE id = 8");
    assertThat(rows("SELECT source_id, target_id FROM likes")).containsExactly(row(70L, 80L));
  }

  @Test
  void aStatementThatIsRefusedChangesNothing() {
    // the row that no link points at comes first: neither is deleted, and neither key changes
    assertThat(refusedWrite("DELETE FROM person WHERE id IN (4, 1)")).startsWith("23503");
    assertThat(refusedWrite("UPDATE person SET id = id + 100 WHERE id IN (4, 1)")).startsWith("23503");
    assertThat(refusedWrite("INSERT INTO knows (source_id, target_id) VALUES (4, 1), (4, 9)")).startsWith("23503");
    assertThat(rows("SELECT id FROM person ORDER BY id")).containsExactly(row(1L), row(2L), row(3L), row(4L));
    assertThat(rows("SELECT count(*) FROM knows")).containsExactly(row(3L));
  }

  @Test
  void aOneWayLinkThatADeleteCascadesGoesWithEitherEnd() {
    database.command("sql", "CREATE EDGE TYPE nod UNIDIRECTIONAL CUSTOM source_table = 'person', source_column = 'id', "
        + "target_table = 'person', target_column = 'id'");
    write("INSERT INTO person (id, name) VALUES (5, 'Ed'), (6, 'Fay'), (7, 'Gus')");
    write("INSERT INTO nod (source_id, target_id) VALUES (5, 6), (7, 5)");
    write("DELETE FROM person WHERE id = 6");
    assertThat(rows("SELECT source_id, target_id FROM nod")).containsExactly(row(7L, 5L));
    write("DELETE FROM person WHERE id = 7");
    assertThat(rows("SELECT count(*) FROM nod")).containsExactly(row(0L));
  }

  /** Runs the work in another thread, with a transaction of its own, and waits for it. */
  private static void inAnotherThread(final Runnable work) {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final Thread other = new Thread(() -> {
      try {
        work.run();
      } catch (final Throwable t) {
        failure.set(t);
      }
    });
    other.start();
    try {
      other.join(60_000);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted", e);
    }
    assertThat(other.isAlive()).as("the other transaction ends").isFalse();
    assertThat(failure.get()).as("the other transaction").isNull();
  }

  @Test
  void aDeleteThatRacesTheInsertOfALinkDoesNotLeaveTheLinkWithoutItsRow() {
    // the link is committed while the delete is open: the delete conflicts, and its second attempt finds the link
    final AtomicInteger deletes = new AtomicInteger();
    assertThat(refusal(() -> database.transaction(() -> {
      final int attempt = deletes.incrementAndGet();
      database.command("postgresql", "DELETE FROM person WHERE id = 4");
      if (attempt == 1)
        inAnotherThread(() -> write("INSERT INTO knows (source_id, target_id) VALUES (1, 4)"));
    }))).startsWith("23503").contains("knows_target_id_fkey");
    assertThat(deletes).hasValue(2);
    assertThat(rows("SELECT p.id FROM knows k JOIN person p ON p.id = k.target_id WHERE k.target_id = 4"))
        .containsExactly(row(4L));

    // the delete is committed while the insert of a link is open: the insert conflicts, and its second attempt finds no row
    write("INSERT INTO person (id, name) VALUES (5, 'Ed')");
    final AtomicInteger inserts = new AtomicInteger();
    assertThat(refusal(() -> database.transaction(() -> {
      final int attempt = inserts.incrementAndGet();
      database.command("postgresql", "INSERT INTO knows (source_id, target_id) VALUES (2, 5)");
      if (attempt == 1)
        inAnotherThread(() -> write("DELETE FROM person WHERE id = 5"));
    }))).startsWith("23503").contains("Key (target_id)=(5) is not present");
    assertThat(inserts).hasValue(2);
    assertThat(rows("SELECT count(*) FROM person WHERE id = 5")).containsExactly(row(0L));
    // every link reads both its ends
    assertThat(rows("SELECT source_id, target_id FROM knows")).hasSize(4);
  }
}
