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
import com.arcadedb.database.Database;
import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Two transactions at once on the keys of a link table (fork issue #292): a new link by a key, and a change of that key. They
 * never both commit unless the reference follows the change ({@code ON UPDATE CASCADE}) and the link is committed first; the
 * commit that comes second is a conflict, and run again the statement gets PostgreSQL's answer. A transaction is never in
 * conflict with its own writes.
 */
class LinkKeyRaceTest extends TestHelper {

  @Override
  protected void beginTest() {
    for (final String statement : List.of("CREATE TABLE person (id bigint PRIMARY KEY, name text)",
        "CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL REFERENCES person)",
        "CREATE TABLE follows (source_id bigint NOT NULL REFERENCES person (id) ON UPDATE CASCADE, "
            + "target_id bigint NOT NULL REFERENCES person ON UPDATE CASCADE)"))
      database.command("postgresql", statement);
    database.command("sql", "CREATE EDGE TYPE saw UNIDIRECTIONAL CUSTOM source_table = 'person', source_column = 'id', "
        + "target_table = 'person', target_column = 'id'");
  }

  private void run(final String statement) {
    database.command("postgresql", statement).close();
  }

  private void write(final String... statements) {
    database.transaction(() -> {
      for (final String statement : statements)
        run(statement);
    });
  }

  private void people(final int... ids) {
    for (final int id : ids)
      write("INSERT INTO person VALUES (" + id + ", 'p')");
  }

  private List<String> links(final String table) {
    final List<String> links = new ArrayList<>();
    try (final ResultSet rows = database.query("postgresql",
        "SELECT source_id, target_id FROM " + table + " ORDER BY source_id, target_id")) {
      while (rows.hasNext()) {
        final Result row = rows.next();
        links.add(row.getProperty("source_id") + ">" + row.getProperty("target_id"));
      }
    }
    return links;
  }

  /**
   * Runs both transactions up to their commits, the statements of {@code first} before those of {@code second}, then commits one
   * and the other. Each transaction has a thread of its own.
   *
   * @param secondCommitsFirst whether the second transaction commits before the first
   *
   * @return what the commit that came second threw, or {@code null}
   */
  private Throwable race(final List<String> first, final List<String> second, final boolean secondCommitsFirst) throws Exception {
    final ExecutorService one = Executors.newSingleThreadExecutor();
    final ExecutorService two = Executors.newSingleThreadExecutor();
    try {
      begin(one, first);
      begin(two, second);
      final ExecutorService winner = secondCommitsFirst ? two : one;
      final ExecutorService loser = secondCommitsFirst ? one : two;
      assertThat(commit(winner)).as("the first commit").isNull();
      return commit(loser);
    } finally {
      one.shutdownNow();
      two.shutdownNow();
    }
  }

  private void begin(final ExecutorService thread, final List<String> statements) throws Exception {
    thread.submit(() -> {
      database.begin();
      for (final String statement : statements)
        run(statement);
    }).get(60, TimeUnit.SECONDS);
  }

  private Throwable commit(final ExecutorService thread) throws Exception {
    final Callable<Throwable> commit = () -> catchThrowable(() -> database.commit());
    return thread.submit(commit).get(60, TimeUnit.SECONDS);
  }

  private void refusedAgain(final String statement) {
    assertThat(catchThrowable(() -> write(statement))).as("again: " + statement).isNotNull()
        .hasStackTraceContaining("foreign key constraint");
  }

  @Test
  void aKeyChangeAndANewLinkNeverBothCommit() throws Exception {
    int key = 100;
    for (final String table : List.of("knows", "saw"))
      for (final boolean sourceEnd : new boolean[] { true, false })
        for (final boolean insertRunsFirst : new boolean[] { true, false })
          for (final boolean insertCommitsFirst : new boolean[] { true, false }) {
            final int changed = key++;
            final int other = key++;
            people(changed, other);
            final String insert = "INSERT INTO " + table + " (source_id, target_id) VALUES (" + (sourceEnd ?
                changed + ", " + other :
                other + ", " + changed) + ")";
            final String update = "UPDATE person SET id = " + (changed + 10_000) + " WHERE id = " + changed;
            final String what = table + ", source end " + sourceEnd + ", insert runs first " + insertRunsFirst
                + ", insert commits first " + insertCommitsFirst;

            final Throwable lost = race(List.of(insertRunsFirst ? insert : update), List.of(insertRunsFirst ? update : insert),
                insertRunsFirst != insertCommitsFirst);
            assertThat(lost).as(what).isInstanceOf(ConcurrentModificationException.class);
            // run again, the transaction that lost gets the answer that PostgreSQL gives
            refusedAgain(insertCommitsFirst ? update : insert);
          }
  }

  @Test
  void aLinkThatTheKeyChangeDeletedDoesNotHideANewOne() throws Exception {
    int key = 500;
    for (final boolean insertCommitsFirst : new boolean[] { true, false }) {
      final int changed = key++;
      final int linked = key++;
      final int added = key++;
      people(changed, linked, added);
      write("INSERT INTO knows VALUES (" + changed + ", " + linked + ")");
      final List<String> rekey = List.of("DELETE FROM knows WHERE source_id = " + changed,
          "UPDATE person SET id = " + (changed + 10_000) + " WHERE id = " + changed);
      final List<String> insert = List.of("INSERT INTO knows VALUES (" + changed + ", " + added + ")");

      assertThat(race(insert, rekey, !insertCommitsFirst)).as("insert commits first " + insertCommitsFirst)
          .isInstanceOf(ConcurrentModificationException.class);
    }
    // the second round: the key change won, so its link is gone and no link refers to the old key
    assertThat(links("knows")).containsExactly("500>501", "500>502");
  }

  @Test
  void aTransactionIsNotInConflictWithItsOwnWrites() {
    people(1, 2, 3);
    write("INSERT INTO knows VALUES (1, 2)");
    // the links of a row are deleted, then its key changes, and a link by the new key follows
    write("DELETE FROM knows WHERE source_id = 1", "UPDATE person SET id = 10 WHERE id = 1", "INSERT INTO knows VALUES (10, 3)");
    // a row of the transaction itself is linked, and a key that changes twice
    write("INSERT INTO person VALUES (4, 'd')", "INSERT INTO knows VALUES (4, 2)", "INSERT INTO person VALUES (5, 'e')",
        "UPDATE person SET id = 50 WHERE id = 5", "UPDATE person SET id = 51 WHERE id = 50");
    // a link by a key, which then changes under ON UPDATE CASCADE
    people(7, 8, 9);
    write("INSERT INTO follows VALUES (7, 8)", "UPDATE person SET id = 70 WHERE id = 7",
        "INSERT INTO follows VALUES (70, 9)", "UPDATE person SET id = 71 WHERE id = 70");
    // a key that changes and changes back, and a link that is deleted again
    people(6);
    write("UPDATE person SET id = 60 WHERE id = 6", "UPDATE person SET id = 6 WHERE id = 60",
        "INSERT INTO knows VALUES (6, 3)", "DELETE FROM knows WHERE source_id = 6");

    assertThat(links("knows")).containsExactly("4>2", "10>3");
    assertThat(links("follows")).containsExactly("71>8", "71>9");
  }

  @Test
  void aCascadingKeyChangeFollowsALinkThatWasCommittedFirst() throws Exception {
    int key = 700;
    for (final boolean insertCommitsFirst : new boolean[] { true, false }) {
      final int changed = key++;
      final int linked = key++;
      final int added = key++;
      people(changed, linked, added);
      // the row has a link already, so a new one writes the list of its links and not the row
      write("INSERT INTO follows VALUES (" + changed + ", " + linked + ")");
      final Throwable lost = race(List.of("INSERT INTO follows VALUES (" + changed + ", " + added + ")"),
          List.of("UPDATE person SET id = " + (changed + 10_000) + " WHERE id = " + changed), !insertCommitsFirst);
      if (insertCommitsFirst)
        assertThat(lost).as("the key change after the link").isNull();
      else
        assertThat(lost).as("the link after the key change").isInstanceOf(ConcurrentModificationException.class);
    }
    assertThat(links("follows")).containsExactly("10700>701", "10700>702", "10703>704");
  }

  /**
   * The link is committed between the read of the row and the check of its links, as it is when two statements run at the same
   * moment. The row that was read lists no link, and the statement still finds it.
   */
  @Test
  void aLinkCommittedAfterTheRowWasReadStopsItsKeyChange() throws Exception {
    people(1, 2);
    final ExecutorService other = Executors.newSingleThreadExecutor();
    try {
      database.begin(Database.TRANSACTION_ISOLATION_LEVEL.REPEATABLE_READ);
      try (final ResultSet rows = database.query("postgresql", "SELECT * FROM person WHERE id = 1")) {
        assertThat(rows.stream().count()).isEqualTo(1);
      }
      other.submit(() -> write("INSERT INTO knows VALUES (1, 2)")).get(60, TimeUnit.SECONDS);
      assertThat(catchThrowable(() -> run("UPDATE person SET id = 10 WHERE id = 1"))).as("the key change")
          .hasStackTraceContaining("foreign key constraint");
      database.rollback();
    } finally {
      other.shutdownNow();
    }
    assertThat(links("knows")).containsExactly("1>2");
  }

  @Test
  void twoNewLinksOfOneRowBothCommit() throws Exception {
    people(1, 2, 3, 4);
    write("INSERT INTO knows VALUES (1, 2)");
    assertThat(race(List.of("INSERT INTO knows VALUES (1, 3)"), List.of("INSERT INTO knows VALUES (1, 4)"), false)).isNull();
    assertThat(links("knows")).containsExactly("1>2", "1>3", "1>4");
  }
}
