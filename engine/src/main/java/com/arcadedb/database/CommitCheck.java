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
package com.arcadedb.database;

import com.arcadedb.utility.IntHashSet;

/**
 * A rule that a query language holds on its own and that the page versions of a commit cannot see, because one transaction reads
 * what another one writes (fork issue #292). It is checked as the foreign keys are ({@code ForeignKeyChecks}): the commit locks
 * the files the check names beside the ones it writes, and reads the committed state again under those locks. A commit that
 * writes one of these files runs before or after this one, never beside it. A check is registered with
 * {@link TransactionContext#commitCheck}; a transaction that registers none pays one null test at its commit.
 */
public interface CommitCheck {
  /** Adds the files the commit has to lock beside the ones it writes. Called while the transaction still reads its own view. */
  void filesToLock(IntHashSet files);

  /**
   * Called under the file locks of the commit, inside a read-committed transaction of its own, which reads the pages as they are
   * committed.
   *
   * @throws com.arcadedb.exception.ConcurrentModificationException when a concurrent transaction broke the rule
   */
  void checkCommitted();

  /** The message of the refusal of a commit that starts on a high-availability replica, where the check is not safe. */
  String replicaRefusal();

  /**
   * What the check holds now, as a statement of the transaction begins. A statement that is taken back (see
   * {@link StatementSavepoint}) restores the mark, so the commit checks nothing for it. A check the statement itself registered
   * is dropped whole and is asked for no mark.
   */
  Mark mark();

  /** What a check held when {@link #mark()} was called. A check only takes things in, so the mark says how far to cut it back. */
  interface Mark {
    /** Whether the check holds more than it did. */
    boolean hasGrown();

    /** Drops what the check took in since the mark. */
    void restore();
  }
}
