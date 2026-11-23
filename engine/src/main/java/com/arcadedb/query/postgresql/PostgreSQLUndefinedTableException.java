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

/**
 * No type of the schema is the table the statement names: SQLSTATE {@code 42P01} (undefined_table).
 */
public class PostgreSQLUndefinedTableException extends PostgreSQLCatalogException {
  /** undefined_table */
  public static final String SQLSTATE = "42P01";

  public PostgreSQLUndefinedTableException(final String table) {
    super(SQLSTATE, "relation " + quote(table) + " does not exist", null);
  }
}
