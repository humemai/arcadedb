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
package com.arcadedb.query.postgresql.plan.infoschema;

import com.arcadedb.query.postgresql.eval.PgType;

/**
 * The four domains that the columns of {@code information_schema} are declared with. PostgreSQL sends the base type of a domain
 * to the client, so each domain has the type the evaluator computes with and the type name that a client sees:
 * {@code sql_identifier} is {@code name} (compared and sorted like {@code text}), {@code character_data} and {@code yes_or_no}
 * are {@code varchar}, {@code cardinal_number} is {@code int4}.
 */
enum InfoType {
  SQL_IDENTIFIER("sql_identifier", PgType.TEXT, "name"),
  CHARACTER_DATA("character_data", PgType.VARCHAR, "varchar"),
  YES_OR_NO("yes_or_no", PgType.VARCHAR, "varchar"),
  CARDINAL_NUMBER("cardinal_number", PgType.INT4, "int4");

  private final String domain;
  private final PgType type;
  private final String typeName;

  InfoType(final String domain, final PgType type, final String typeName) {
    this.domain = domain;
    this.type = type;
    this.typeName = typeName;
  }

  /** The name of the domain, which {@code columns.domain_name} reports for a column of a view. */
  String domain() {
    return domain;
  }

  /** The type the evaluator computes with. */
  PgType type() {
    return type;
  }

  /** The type name the client sees: the base type of the domain. */
  String typeName() {
    return typeName;
  }
}
