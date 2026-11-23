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
 * Three-valued logic on {@link Boolean} values where {@code null} is SQL {@code NULL} (unknown): the tables of section 9.2 of
 * the manual, "Logical Operators".
 *
 * <pre>
 *   a      b      a AND b   a OR b
 *   TRUE   TRUE   TRUE      TRUE
 *   TRUE   FALSE  FALSE     TRUE
 *   TRUE   NULL   NULL      TRUE
 *   FALSE  FALSE  FALSE     FALSE
 *   FALSE  NULL   FALSE     NULL
 *   NULL   NULL   NULL      NULL
 *
 *   a      NOT a
 *   TRUE   FALSE
 *   FALSE  TRUE
 *   NULL   NULL
 * </pre>
 */
public final class Logic {
  private Logic() {
  }

  public static Boolean and(final Boolean a, final Boolean b) {
    if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b))
      return Boolean.FALSE;
    if (a == null || b == null)
      return null;
    return Boolean.TRUE;
  }

  public static Boolean or(final Boolean a, final Boolean b) {
    if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b))
      return Boolean.TRUE;
    if (a == null || b == null)
      return null;
    return Boolean.FALSE;
  }

  public static Boolean not(final Boolean a) {
    return a == null ? null : !a;
  }

  /** {@code a IS NOT DISTINCT FROM b}, given the result of {@code a = b}: two nulls are the same, one null is different. */
  public static boolean isNotDistinctFrom(final Object a, final Object b, final Boolean equal) {
    if (a == null || b == null)
      return a == null && b == null;
    return Boolean.TRUE.equals(equal);
  }
}
