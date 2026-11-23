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
package com.arcadedb.query.postgresql.plan.join;

/**
 * The answer to one question about a join: does something in the database make it cheap, or does it compare every pair of rows?
 * It is the decision of question 22 of the prototype decisions, as a value with its reason, so that the warning of fork issue
 * #9 can print the reason without asking again. The join planner computes it for every join ({@code JoinPlanner#support}); it is
 * only shown in the plan today.
 *
 * <ul>
 * <li>{@link Kind#EDGE}: the equality joins an edge table through {@code source_id} or {@code target_id} to the {@code id} of a
 * table, which is the shape a traversal answers (fork issue #8). Today it runs as the plain equality join it is.</li>
 * <li>{@link Kind#LINK}: the equality joins a {@code LINK} property to the {@code id} of its table: a record read by id.</li>
 * <li>{@link Kind#INDEX}: one side of an equality is {@code id} or a property with an index usable on that key.</li>
 * <li>{@link Kind#NOTHING}: none of the above, or the {@code ON} condition has no equality between the two sides.</li>
 * </ul>
 *
 * @param kind   which support the join has
 * @param reason one sentence that names the columns involved
 */
public record JoinSupport(Kind kind, String reason) {

  public enum Kind {
    EDGE, LINK, INDEX, NOTHING
  }

  /** True when an edge pattern, a link or an index supports the join. */
  public boolean supported() {
    return kind != Kind.NOTHING;
  }

  @Override
  public String toString() {
    return kind.name().toLowerCase(java.util.Locale.ROOT) + ": " + reason;
  }
}
