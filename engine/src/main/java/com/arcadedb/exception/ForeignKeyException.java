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
package com.arcadedb.exception;

/**
 * A foreign key refuses a write or a schema change (fork issue #228). It carries the SQLSTATE PostgreSQL answers with:
 * {@code 23503} for a violated key, {@code 23001} for {@code RESTRICT}, {@code 55000} for a key that cannot be checked, and
 * {@code 2BP01} for a schema change that a key depends on.
 */
public class ForeignKeyException extends ValidationException {
  private final String sqlState;

  public ForeignKeyException(final String sqlState, final String message) {
    super(Kind.FOREIGN_KEY, message);
    this.sqlState = sqlState;
  }

  /**
   * The refusal a server answered, rebuilt by a client or by a follower that forwarded the write: with the SQLSTATE the server
   * sent, or the one of a violated key when the answer carries none.
   */
  public static ForeignKeyException fromRemote(final String sqlState, final String message) {
    return new ForeignKeyException(sqlState != null && !sqlState.isEmpty() ? sqlState : "23503", message);
  }

  public String getSqlState() {
    return sqlState;
  }
}
