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
 * One identifier: {@code ColId}, {@code name}, {@code ColLabel}, {@code type_function_name} and the parts of
 * {@code qualified_name} and {@code columnref} of {@code gram.y}.
 *
 * <p>{@code value} is what PostgreSQL's scanner hands to the grammar: an unquoted identifier folded to lower case (ASCII
 * letters only, as PostgreSQL does for a UTF-8 database), a quoted identifier exactly as written with {@code ""} read as one
 * quote. {@code quoted} tells the binder which of the two it has: a quoted name matches a type or column exactly, an unquoted
 * one may fall back to a unique case-insensitive match (question 7 of the decisions in issue #1).
 *
 * @param position where the identifier starts (the opening quote for a quoted one)
 * @param value    the identifier after folding or unquoting, never empty
 * @param quoted   true when the identifier was written in double quotes
 */
public record Name(SourcePosition position, String value, boolean quoted) implements Node {
  public Name {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(value, "value");
  }
}
