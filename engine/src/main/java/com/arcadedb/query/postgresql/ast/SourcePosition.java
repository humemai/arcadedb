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
 * Where a node starts in the statement text: the line and the column, both counted from 1, and the character offset counted
 * from 0. It stands for the {@code location} field that PostgreSQL keeps on its parse nodes (there only an offset), so a later
 * error can point at the same token PostgreSQL points at.
 *
 * <p>Equality ignores the position: any two positions are equal. This makes the nodes that hold a position compare by their
 * content alone, so a tree parsed from the printed form of another tree equals it. Read {@link #line()}, {@link #column()} and
 * {@link #offset()} to test a position.
 *
 * @param line   line, counted from 1
 * @param column column in the line, counted from 1
 * @param offset character offset from the start of the statement text, counted from 0, or -1 when unknown
 */
public record SourcePosition(int line, int column, int offset) {
  /** The position of a node that was built by code and not read from text. */
  public static final SourcePosition UNKNOWN = new SourcePosition(0, 0, -1);

  @Override
  public boolean equals(final Object other) {
    return other instanceof SourcePosition;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  /** The words every message that points at a position uses. */
  @Override
  public String toString() {
    return "line " + line + ", column " + column;
  }
}
