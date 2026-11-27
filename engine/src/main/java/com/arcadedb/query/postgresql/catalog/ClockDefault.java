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
package com.arcadedb.query.postgresql.catalog;

/**
 * A default of a column that reads the clock (fork issue #222). The catalog shows it as PostgreSQL writes it, and the property
 * holds {@link #SCHEMA_DEFAULT}, an expression of the {@code sql} language, so a record that another language creates takes the
 * time too, in UTC. A write of the {@code postgresql} language takes the time of its transaction instead.
 */
public enum ClockDefault {
  NOW("now()"), TRANSACTION_TIMESTAMP("transaction_timestamp()"), CURRENT_TIMESTAMP("CURRENT_TIMESTAMP"),
  LOCALTIMESTAMP("LOCALTIMESTAMP"), CURRENT_DATE("CURRENT_DATE");

  /** The default of the property: the current time as a wall clock of UTC. */
  public static final String SCHEMA_DEFAULT = "sysdate()";

  private final String text;

  ClockDefault(final String text) {
    this.text = text;
  }

  /** The text that {@code information_schema.columns.column_default} shows. */
  public String text() {
    return text;
  }

  /** The default that the catalog shows as {@code text}, {@code null} for any other text. */
  public static ClockDefault of(final String text) {
    for (final ClockDefault value : values())
      if (value.text.equals(text))
        return value;
    return null;
  }
}
