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

import java.util.Objects;

/**
 * {@code CREATE EXTENSION IF NOT EXISTS name} of an extension whose features are built in (fork issue #231). It runs and changes
 * nothing. Every other form of the statement is refused with SQLSTATE 0A000 before a tree exists.
 *
 * @param position where the statement starts
 * @param name     the name of the extension
 */
public record CreateExtensionStatement(SourcePosition position, Name name) implements Statement {
  public CreateExtensionStatement {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(name, "name");
  }
}
