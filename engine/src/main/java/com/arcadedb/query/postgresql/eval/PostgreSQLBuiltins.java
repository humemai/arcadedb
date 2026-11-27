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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Whether PostgreSQL 18 has a function or an operator that takes arguments of given types. The language asks this only when it
 * has none itself: what PostgreSQL has is then not supported ({@code 0A000}), and what PostgreSQL does not have either does not
 * exist ({@code 42883}), fork issue #198.
 * <p>
 * The functions, operators, implicit casts and types are those of the system catalog of a PostgreSQL 18 server, in the resource
 * {@value #RESOURCE}, which {@code .github/scripts/pg_catalog_list.sh} writes. Extensions, such as pgvector, are not in it.
 * <p>
 * This is not PostgreSQL's choice among candidates: it only says whether there is a candidate. The element type of an array is
 * not known here, so an array fits every parameter that is an array.
 */
final class PostgreSQLBuiltins {
  private static final String RESOURCE = "postgresql-18-catalog.tsv";

  /** @param variadic the type of each argument that a {@code VARIADIC} last parameter takes, {@code null} for none */
  private record Signature(String[] parameters, int defaults, String variadic) {
  }

  /** @param left {@code null} for a prefix operator */
  private record Operator(String left, String right) {
  }

  private final Map<String, List<Signature>> functions     = new HashMap<>();
  private final Map<String, List<Operator>>  operators     = new HashMap<>();
  private final Set<String>                  implicitCasts = new HashSet<>();
  /** The category of every type that a call of one argument can name, as a cast. */
  private final Map<String, String>          types         = new HashMap<>();

  /** Read on the first question, which only a statement that fails asks. */
  private static final class Holder {
    private static final PostgreSQLBuiltins CATALOG = load();
  }

  private PostgreSQLBuiltins() {
  }

  /** Whether PostgreSQL has a function, an aggregate or a window function of this name for these argument types. */
  static boolean hasFunction(final String name, final PgType[] arguments) {
    final PostgreSQLBuiltins catalog = Holder.CATALOG;
    for (final Signature signature : catalog.functions.getOrDefault(name, List.of()))
      if (catalog.takes(signature, arguments))
        return true;
    // a call of one argument that is named after a type is a cast to PostgreSQL: of a literal, or through the text of a value
    final String category = arguments.length == 1 ? catalog.types.get(name) : null;
    return category != null && (arguments[0] == PgType.UNKNOWN || arguments[0].isString() || category.equals("S"));
  }

  /**
   * Whether PostgreSQL has this operator for these operand types.
   *
   * @param left {@code null} for a prefix operator
   */
  static boolean hasOperator(final String symbol, final PgType left, final PgType right) {
    final PostgreSQLBuiltins catalog = Holder.CATALOG;
    for (final Operator operator : catalog.operators.getOrDefault(symbol, List.of())) {
      if ((left == null) != (operator.left() == null))
        continue;
      if ((left == null || catalog.accepts(operator.left(), left)) && catalog.accepts(operator.right(), right))
        return true;
    }
    return false;
  }

  private boolean takes(final Signature signature, final PgType[] arguments) {
    final String[] parameters = signature.parameters();
    final int declared = parameters.length;
    final boolean variadic = signature.variadic() != null;
    if (variadic ? arguments.length < declared : arguments.length > declared || arguments.length < declared - signature.defaults())
      return false;
    for (int i = 0; i < arguments.length; i++)
      if (!accepts(variadic && i >= declared - 1 ? signature.variadic() : parameters[i], arguments[i]))
        return false;
    return true;
  }

  /** Whether an argument of this type can go to a parameter of the type {@code parameter}, a name of {@code pg_type}. */
  private boolean accepts(final String parameter, final PgType argument) {
    // a literal takes the type of the parameter; nothing of a statement is "internal"
    if (argument == PgType.UNKNOWN)
      return !parameter.equals("internal");
    final boolean array = argument == PgType.ARRAY;
    return switch (parameter) {
      case "any", "anyelement", "anycompatible" -> true;
      case "anynonarray", "anycompatiblenonarray" -> !array;
      case "anyarray", "anycompatiblearray" -> array;
      default -> array ? parameter.startsWith("_")
          : parameter.equals(argument.typeName()) || implicitCasts.contains(argument.typeName() + ' ' + parameter);
    };
  }

  private static PostgreSQLBuiltins load() {
    final PostgreSQLBuiltins catalog = new PostgreSQLBuiltins();
    try (final InputStream stream = PostgreSQLBuiltins.class.getResourceAsStream(RESOURCE)) {
      if (stream == null)
        throw new IllegalStateException("The resource " + RESOURCE + " is missing");
      final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
      for (String line = reader.readLine(); line != null; line = reader.readLine())
        if (!line.isEmpty() && line.charAt(0) != '#')
          catalog.add(line.split("\t"));
    } catch (final IOException e) {
      throw new UncheckedIOException("Cannot read the resource " + RESOURCE, e);
    }
    return catalog;
  }

  private void add(final String[] fields) {
    switch (fields[0]) {
    case "cast" -> implicitCasts.add(fields[1] + ' ' + fields[2]);
    case "function" -> functions.computeIfAbsent(fields[1], name -> new ArrayList<>()).add(
        new Signature(fields[2].equals("-") ? new String[0] : fields[2].split(","), Integer.parseInt(fields[3]), orNone(fields[4])));
    case "operator" -> operators.computeIfAbsent(fields[1], name -> new ArrayList<>())
        .add(new Operator(orNone(fields[2]), fields[3]));
    case "type" -> types.put(fields[1], fields[2]);
    default -> throw new IllegalStateException("Unknown line in " + RESOURCE + ": " + fields[0]);
    }
  }

  private static String orNone(final String field) {
    return field.equals("-") ? null : field;
  }
}
