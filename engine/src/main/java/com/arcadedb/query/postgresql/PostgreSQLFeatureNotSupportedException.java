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

import java.util.List;
import java.util.Objects;

/**
 * A statement the {@code postgresql} language does not accept: anything that is not a statement of the supported subset.
 * It carries the SQLSTATE {@code 0A000} (feature_not_supported) that a PostgreSQL client understands, so the wire module can
 * answer with it instead of the {@code 42601} it uses for a {@link CommandParsingException}. The message names the unsupported
 * word with its line and column; a real syntax error is a different case ({@code 42601}). Every refusal has a hint (fork issue
 * #241): what to write instead, or that there is no way yet. A refusal that gives none takes the one of
 * {@link PostgreSQLHints#of}.
 */
public class PostgreSQLFeatureNotSupportedException extends CommandParsingException implements PostgreSQLError {
  /** feature_not_supported */
  public static final String SQLSTATE = "0A000";

  private final String       hint;
  /** the words of a refused statement that the language does not run, empty for any other refusal */
  private final List<String> statement;

  public PostgreSQLFeatureNotSupportedException(final String message) {
    this(PostgreSQLError.Text.of(message));
  }

  private PostgreSQLFeatureNotSupportedException(final PostgreSQLError.Text text) {
    this(text.message(), text.hint() != null ? text.hint() : PostgreSQLHints.of(text.message()), List.of());
  }

  public PostgreSQLFeatureNotSupportedException(final String message, final String hint) {
    this(message, hint, List.of());
  }

  public PostgreSQLFeatureNotSupportedException(final String message, final String hint, final List<String> statement) {
    super(message);
    this.hint = Objects.requireNonNull(hint);
    this.statement = statement;
  }

  public String getSqlState() {
    return SQLSTATE;
  }

  @Override
  public String getHint() {
    return hint;
  }

  public List<String> getStatement() {
    return statement;
  }

  @Override
  public String getMessage() {
    return super.getMessage() + HINT + hint;
  }
}
