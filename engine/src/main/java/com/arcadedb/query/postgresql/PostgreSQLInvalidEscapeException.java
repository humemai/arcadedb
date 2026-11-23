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
 * A string constant with a backslash escape that PostgreSQL's scanner refuses as an invalid escape sequence, SQLSTATE
 * {@code 22025} (invalid_escape_sequence): a backslash and a lower case u in an E string that is not followed by four hex digits.
 * PostgreSQL does not
 * call it a syntax error, and neither does the {@code postgresql} language.
 */
public class PostgreSQLInvalidEscapeException extends CommandParsingException {
  /** invalid_escape_sequence */
  public static final String SQLSTATE = "22025";

  public PostgreSQLInvalidEscapeException(final String message) {
    super(message);
  }

  public String getSqlState() {
    return SQLSTATE;
  }
}
