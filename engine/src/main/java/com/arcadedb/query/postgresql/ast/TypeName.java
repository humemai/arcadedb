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

import java.util.List;
import java.util.Objects;

/**
 * A type name: {@code Typename}, {@code SimpleTypename}, {@code GenericType}, {@code Numeric}, {@code Character},
 * {@code ConstDatetime} and {@code ConstInterval} of {@code gram.y}.
 *
 * <p>The words that SQL spells in several ways are normalised to the catalog name, the way the grammar actions of PostgreSQL
 * do: {@code INT}, {@code INTEGER} and {@code int4} give {@code int4}; {@code SMALLINT} gives {@code int2}; {@code BIGINT}
 * gives {@code int8}; {@code REAL} gives {@code float4}; {@code DOUBLE PRECISION} gives {@code float8}; {@code FLOAT(p)} gives
 * {@code float4} for p up to 24 and {@code float8} above, and plain {@code FLOAT} gives {@code float8}; {@code DECIMAL},
 * {@code DEC} and {@code NUMERIC} give {@code numeric}; {@code BOOLEAN} gives {@code bool}; {@code CHAR} and
 * {@code CHARACTER} give {@code bpchar}; {@code VARCHAR} and {@code CHARACTER VARYING} give {@code varchar};
 * {@code TIMESTAMP [WITH TIME ZONE]} gives {@code timestamptz} and {@code TIMESTAMP WITHOUT TIME ZONE} gives {@code timestamp};
 * {@code TIME [WITH TIME ZONE]} gives {@code timetz} and {@code TIME WITHOUT TIME ZONE} gives {@code time}. Any other name
 * stays as written, possibly qualified ({@code pg_catalog.int4} is the two parts).
 *
 * @param position        where the type name starts
 * @param name            the parts of the name, one for a normalised or unqualified name, never empty; a quoted part keeps its
 *                        case, an unquoted part is folded to lower case
 * @param modifiers       the type modifiers in parentheses, such as the {@code 10} and {@code 2} of {@code numeric(10,2)},
 *                        possibly empty
 * @param intervalFields  for {@code interval}, the field restriction in lower case such as {@code month},
 *                        {@code year to month} or {@code second(3)}, or {@code null} when there is none; always {@code null}
 *                        for any other type
 * @param arrayDimensions the number of {@code []} pairs after the name ({@code int[][]} is 2), 0 for a type that is not an
 *                        array; PostgreSQL ignores the size inside the brackets, so it is not kept
 */
public record TypeName(SourcePosition position, List<String> name, List<Expression> modifiers, String intervalFields,
                       int arrayDimensions) implements Node {
  public TypeName {
    Objects.requireNonNull(position, "position");
    name = List.copyOf(name);
    modifiers = List.copyOf(modifiers);
    if (name.isEmpty())
      throw new IllegalArgumentException("A type name has at least one part");
    if (arrayDimensions < 0)
      throw new IllegalArgumentException("arrayDimensions is negative: " + arrayDimensions);
  }
}
