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
package com.arcadedb.query.postgresql.eval;

/**
 * The text of a parameter whose type the client did not declare, which PostgreSQL types as {@code unknown} and resolves from the
 * expression it is in: {@code pid = $1} with {@code '2'} reads {@code '2'} as an {@code integer}, as the string literal
 * {@code pid = '2'} does. A client that declares no parameter types sends every value as text this way: libpq's {@code PQexecParams}
 * with no types (psql's {@code \bind}), node-postgres, and a Python {@code str} bound by psycopg. A plain {@link String} is a
 * {@code text} parameter, which PostgreSQL does not coerce, and is what a client that declared {@code text} or {@code varchar} gets.
 *
 * @param text the value as the client sent it
 */
public record UnknownText(String text) {
}
