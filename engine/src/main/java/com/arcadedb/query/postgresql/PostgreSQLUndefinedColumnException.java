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

import java.util.List;
import java.util.Map;

/**
 * The table has no such column: SQLSTATE {@code 42703} (undefined_column). The columns of a table are the properties its type
 * declares; the hint suggests the column that was probably meant and lists them ({@link #unknownColumnHint}).
 */
public class PostgreSQLUndefinedColumnException extends PostgreSQLCatalogException {
  /** undefined_column */
  public static final String SQLSTATE = "42703";

  public PostgreSQLUndefinedColumnException(final String column, final String table, final List<String> columns) {
    super(SQLSTATE, "column " + quote(column) + " of relation " + quote(table) + " does not exist",
        unknownColumnHint(column, Map.of(table, columns), columnList(table, columns)));
  }
}
