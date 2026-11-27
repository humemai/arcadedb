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
package com.arcadedb.query.postgresql;

/**
 * An error of the {@code postgresql} language with a hint: what to do about it (fork issue #241). The PostgreSQL wire protocol
 * sends the hint in a field of its own, apart from the message ({@link PostgreSQLErrors#hint}). Every other protocol has one
 * text, so {@link Throwable#getMessage()} ends with the hint, after {@link #HINT}.
 */
public interface PostgreSQLError {
  /** What stands between the message and the hint in {@link Throwable#getMessage()}. */
  String HINT = ". Hint: ";

  /** @return what to do about the error, {@code null} when there is nothing to add to the message */
  String getHint();

  /** A message and its hint. */
  record Text(String message, String hint) {
    /** The two as one text, as {@link Throwable#getMessage()} gives them. */
    public String joined() {
      return hint == null ? message : message + HINT + hint;
    }

    /**
     * Takes apart a message that is written with its hint. A {@code Hint:} inside a quoted value of the user, which leaves an
     * odd number of double quotes before it, is part of the message.
     */
    public static Text of(final String text) {
      final int at = text == null ? -1 : text.indexOf(HINT);
      if (at < 0 || text.substring(0, at).chars().filter(c -> c == '"').count() % 2 != 0)
        return new Text(text, null);
      return new Text(text.substring(0, at), text.substring(at + HINT.length()));
    }
  }
}
