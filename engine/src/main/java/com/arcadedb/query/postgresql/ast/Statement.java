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
 * A statement: {@code stmt} of {@code gram.y}. The statements of the language are a {@code SELECT} and an {@code EXPLAIN} of
 * one, the writes {@code INSERT}, {@code UPDATE} and {@code DELETE} with their {@code EXPLAIN}, the transaction statements,
 * {@code CREATE TABLE}, {@code CREATE INDEX}, {@code CREATE EXTENSION IF NOT EXISTS} of what is built in, and the empty
 * statement. Every other statement is refused with SQLSTATE 0A000 before a tree exists. The parser takes exactly one
 * statement; a text with a second statement after a semicolon is a syntax error, as the extended protocol of PostgreSQL has it.
 */
public sealed interface Statement extends Node permits SelectStatement, SetOperationStatement, WithStatement, ExplainStatement, ExplainWriteStatement, EmptyStatement, WriteStatement,
    TransactionStatement, CreateTableStatement, CreateIndexStatement, CreateExtensionStatement {
}
