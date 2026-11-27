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
 * A constraint that the engine holds for every language refuses a write or a schema change, with the SQLSTATE PostgreSQL answers
 * with: a {@link ForeignKeyException} or a {@link CheckConstraintException}. The state crosses the wire next to the class name, so
 * a remote caller reads what an embedded one reads: a new subclass gets a line in {@link #fromRemote}.
 */
public abstract class SqlStateValidationException extends ValidationException {
  private final String sqlState;

  protected SqlStateValidationException(final Kind kind, final String sqlState, final String message) {
    super(kind, message);
    this.sqlState = sqlState;
  }

  public String getSqlState() {
    return sqlState;
  }

  /**
   * The refusal a server answered, rebuilt by a client or by a follower that forwarded the write: with the SQLSTATE the server
   * sent, or the one of a violated constraint when the answer carries none.
   *
   * @return {@code null} when {@code className} names no such refusal
   */
  public static SqlStateValidationException fromRemote(final String className, final String sqlState, final String message) {
    final boolean sent = sqlState != null && !sqlState.isEmpty();
    if (ForeignKeyException.class.getName().equals(className))
      return new ForeignKeyException(sent ? sqlState : "23503", message);
    if (CheckConstraintException.class.getName().equals(className))
      return new CheckConstraintException(sent ? sqlState : "23514", message);
    return null;
  }
}
