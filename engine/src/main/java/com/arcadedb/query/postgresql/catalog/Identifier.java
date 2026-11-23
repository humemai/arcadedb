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
package com.arcadedb.query.postgresql.catalog;

import java.util.Locale;
import java.util.Objects;

/**
 * A name as the statement wrote it, with the one thing the rules of the relational view need to know about it: whether it was
 * quoted. The rules are those of question 7 of the prototype decisions, a documented relaxation of PostgreSQL's:
 * <ul>
 * <li>A quoted identifier matches exactly, nothing else.</li>
 * <li>An unquoted identifier is folded to lower case, as PostgreSQL does. The folded name is matched exactly first; when nothing
 * matches, a name that differs from it only in case matches, if there is exactly one. Two such names make the reference
 * ambiguous, which is an error that names both.</li>
 * </ul>
 * ArcadeDB type and property names are case sensitive and {@code Person} is a normal type name, so strict folding would hide every
 * such type behind quotes. Types {@code Person} and {@code person} can coexist: each is reachable by quoting, and the unquoted
 * {@code person} is the second one.
 */
public record Identifier(String text, boolean quoted) {
  public Identifier {
    Objects.requireNonNull(text, "text");
  }

  /** An identifier written without quotes. */
  public static Identifier unquoted(final String text) {
    return new Identifier(text, false);
  }

  /** An identifier written in double quotes, {@code text} being what is between them. */
  public static Identifier quoted(final String text) {
    return new Identifier(text, true);
  }

  /** The name PostgreSQL looks up for this identifier: the text itself when quoted, the text folded to lower case otherwise. */
  public String folded() {
    return quoted ? text : text.toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return quoted ? '"' + text.replace("\"", "\"\"") + '"' : text;
  }
}
