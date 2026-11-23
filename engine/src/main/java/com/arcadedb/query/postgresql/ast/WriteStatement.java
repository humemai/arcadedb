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
package com.arcadedb.query.postgresql.ast;

/**
 * A statement that changes records: {@code INSERT}, {@code UPDATE} or {@code DELETE} (fork issue #29). Each names one table, may
 * return rows ({@code RETURNING}), and is a write whatever it does with the rows: the analysis of the language reports it as one.
 */
public sealed interface WriteStatement extends Statement permits InsertStatement, UpdateStatement, DeleteStatement {
  /** The table that the statement writes. */
  TableReference.Table table();

  /** The {@code RETURNING} list, empty when the statement has none. */
  java.util.List<SelectItem> returning();
}
