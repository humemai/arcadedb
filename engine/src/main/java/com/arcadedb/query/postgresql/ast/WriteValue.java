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
 * A value that is written: an expression, or {@code DEFAULT}. It is the item of a row of {@code VALUES} and the right side of
 * {@code SET column = ...}, the two places where {@code gram.y} accepts {@code DEFAULT} next to an {@code a_expr}
 * ({@code DEFAULT} is not an {@link Expression}: it has no value of its own, the column decides it).
 *
 * @param expression the value, or {@code null} for {@code DEFAULT}
 */
public record WriteValue(SourcePosition position, Expression expression) implements Node {
  public WriteValue {
    Objects.requireNonNull(position, "position");
  }

  public boolean isDefault() {
    return expression == null;
  }
}
