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
 * A check constraint refuses a write (fork issue #227). It carries the SQLSTATE PostgreSQL answers with: {@code 23514} for a
 * record that does not meet the constraint, the code of the error when the expression fails for the record ({@code 22012} for a
 * division by zero), and {@code 55000} for a constraint that cannot be checked.
 */
public class CheckConstraintException extends ValidationException {
  private final String sqlState;

  public CheckConstraintException(final String sqlState, final String message) {
    super(Kind.CHECK, message);
    this.sqlState = sqlState;
  }

  public String getSqlState() {
    return sqlState;
  }
}
