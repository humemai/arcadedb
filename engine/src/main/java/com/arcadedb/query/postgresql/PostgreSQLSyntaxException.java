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

import com.arcadedb.exception.CommandParsingException;

/**
 * A statement that is not PostgreSQL syntax: the {@code postgresql} language refuses it with SQLSTATE {@code 42601}
 * (syntax_error), as PostgreSQL does. The message names the offending token with its line and column, in the form PostgreSQL
 * uses ({@code syntax error at or near "X"}) followed by the position, and a form that another database reads has a hint with
 * the form of PostgreSQL. A statement that is PostgreSQL syntax but outside the supported subset is a different case,
 * {@link PostgreSQLFeatureNotSupportedException} ({@code 0A000}).
 */
public class PostgreSQLSyntaxException extends CommandParsingException implements PostgreSQLError {
  /** syntax_error */
  public static final String SQLSTATE = "42601";

  private final String hint;

  public PostgreSQLSyntaxException(final String message) {
    this(message, null);
  }

  public PostgreSQLSyntaxException(final String message, final String hint) {
    super(message);
    this.hint = hint;
  }

  public String getSqlState() {
    return SQLSTATE;
  }

  @Override
  public String getHint() {
    return hint;
  }

  @Override
  public String getMessage() {
    return hint == null ? super.getMessage() : super.getMessage() + HINT + hint;
  }
}
