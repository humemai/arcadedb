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
 * A node of the syntax tree of the {@code postgresql} language. The tree is immutable: every node is a record, lists are
 * unmodifiable copies, and a node never changes after the parser built it. The sealed hierarchies ({@link Statement},
 * {@link Expression}, {@link SelectItem}, {@link TableReference}) are meant to be read with a pattern matching {@code switch},
 * which the compiler checks for completeness, so there is no visitor to implement.
 *
 * <p>The tree follows the structure of the PostgreSQL grammar ({@code src/backend/parser/gram.y}); the javadoc of a node names
 * the rule it stands for. The tree is not a copy of PostgreSQL's parse tree: a construct that PostgreSQL rewrites in the
 * grammar action is rewritten the same way here ({@code x::t} and {@code CAST(x AS t)} are one {@link Expression.Cast}, a
 * negative literal is one literal), and a construct outside the supported subset has no node: the parser refuses it before a
 * tree exists.
 */
public interface Node {
  /** Where the node starts, except where the javadoc of the node says otherwise (an operator node starts at its operator). */
  SourcePosition position();
}
