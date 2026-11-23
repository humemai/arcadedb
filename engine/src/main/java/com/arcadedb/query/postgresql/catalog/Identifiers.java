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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The lookup rule of {@link Identifier} over a set of names.
 */
final class Identifiers {
  private Identifiers() {
  }

  /**
   * @param identifier  the name as written
   * @param byName      the candidates by their exact name
   * @param onAmbiguous builds the exception for a reference that matches several names: it gets the identifier as written and the
   *                    matching names in order
   *
   * @return the candidate, {@code null} when none matches
   */
  static <T> T resolve(final Identifier identifier, final Map<String, T> byName,
      final BiFunction<String, List<String>, ? extends RuntimeException> onAmbiguous) {
    final String folded = identifier.folded();
    final T exact = byName.get(folded);
    if (exact != null || identifier.quoted())
      return exact;

    final List<String> matches = new ArrayList<>(2);
    for (final String name : byName.keySet())
      if (name.toLowerCase(Locale.ROOT).equals(folded))
        matches.add(name);
    if (matches.size() > 1) {
      matches.sort(String::compareTo);
      throw onAmbiguous.apply(identifier.text(), matches);
    }
    return matches.isEmpty() ? null : byName.get(matches.get(0));
  }
}
