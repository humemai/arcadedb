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
 * The schema name is neither {@code public} nor the name of the database: SQLSTATE {@code 3F000} (invalid_schema_name).
 */
public class PostgreSQLUndefinedSchemaException extends PostgreSQLCatalogException {
  /** invalid_schema_name */
  public static final String SQLSTATE = "3F000";

  public PostgreSQLUndefinedSchemaException(final String schema, final String database) {
    super(SQLSTATE, "schema " + quote(schema) + " does not exist",
        "the tables are in the schema public, which the name of the database (" + database + ") also names");
  }
}
