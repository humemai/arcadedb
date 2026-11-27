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
public class ForeignKeyException extends SqlStateValidationException {
  public ForeignKeyException(final String sqlState, final String message) {
    super(Kind.FOREIGN_KEY, sqlState, message);
  }
}
