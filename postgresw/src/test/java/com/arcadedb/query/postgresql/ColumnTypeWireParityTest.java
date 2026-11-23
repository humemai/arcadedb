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

import com.arcadedb.postgres.PostgresType;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.schema.Type;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The column types of the {@code postgresql} language ({@link ColumnType}, in the engine) are the types the Postgres wire module
 * announces ({@link PostgresType#getTypeFromArcade(Type, String)}), except where the language decides differently. The wire module
 * depends on the engine, so the engine cannot call the wire's mapping: this test, which sees both, is what keeps them one mapping.
 * It fails when the wire changes a type, when the language does, and when a new schema {@link Type} appears.
 * <p>
 * The differences, each decided for the prototype (fork issue #1, question 15, and fork issue #11 for the vectors) and each written
 * down here and nowhere else:
 * <ul>
 * <li>{@code json} is {@code jsonb}: for {@code MAP}, {@code EMBEDDED}, and a {@code LIST} the wire announces as {@code json}
 * (a list of embedded documents or of lists). {@code jsonb} is the JSON type with an equality operator.</li>
 * <li>{@code LINK} is {@code text}, the wire says {@code varchar}: it is the same kind of value as {@code id}, a record id as text.</li>
 * <li>{@code ARRAY_OF_FLOATS} is {@code vector}, the wire says {@code float4[]}: the pgvector operators take the type, and the wire
 * has no {@code vector} type yet (the wire step decides how it announces one).</li>
 * </ul>
 */
class ColumnTypeWireParityTest {

  /** The name a column of this wire type has in {@code pg_type}, with {@code []} for an array of the element type's name. */
  private static String wireName(final PostgresType type) {
    return type.isArrayType() ? PostgresType.byCode(type.elementCode).typeName + "[]" : type.typeName;
  }

  /** Every element type a declaration can name: each schema type by both of its names, nothing, blank, and an embedded type. */
  private static List<String> declaredElements() {
    final List<String> elements = new ArrayList<>();
    elements.add(null);
    elements.add("");
    elements.add("  ");
    elements.add("address_t");
    for (final Type type : Type.values()) {
      elements.add(type.getName());
      elements.add(type.name());
      elements.add(type.name().toLowerCase());
    }
    return elements;
  }

  @Test
  void theLanguageTypesAreTheWireTypesExceptTheTwoDecidedDifferences() {
    final Set<String> differing = new TreeSet<>();
    int compared = 0;
    for (final Type type : Type.values())
      for (final String element : declaredElements()) {
        final PostgresType wire = PostgresType.getTypeFromArcade(type, element);
        final ColumnType language = ColumnType.of(type, element);
        ++compared;
        final String label = type + (element == null ? "" : " OF " + element);
        if (language.typeName().equals(wireName(wire)))
          continue;

        final boolean json = wire == PostgresType.JSON && language == ColumnType.JSONB;
        final boolean link = type == Type.LINK && wire == PostgresType.VARCHAR && language == ColumnType.TEXT;
        final boolean vector = type == Type.ARRAY_OF_FLOATS && wireName(wire).equals("float4[]") && language == ColumnType.VECTOR;
        assertThat(json || link || vector).as("%s: the wire says %s, the language says %s", label, wireName(wire), language).isTrue();
        differing.add(type.name());
      }

    assertThat(compared).isGreaterThan(Type.values().length * 20);
    // a list differs only for the elements the wire announces as json
    assertThat(differing).containsExactly("ARRAY_OF_FLOATS", "EMBEDDED", "LINK", "LIST", "MAP");
  }

  @Test
  void everyTypeWithNoElementDeclarationMatchesToo() {
    for (final Type type : Type.values()) {
      final PostgresType wire = PostgresType.getTypeFromArcade(type);
      final ColumnType language = ColumnType.of(type);
      final String name = wireName(wire);
      if (type == Type.LINK)
        assertThat(language.typeName()).as(type.name()).isEqualTo("text");
      else if (type == Type.MAP || type == Type.EMBEDDED)
        assertThat(language.typeName()).as(type.name()).isEqualTo("jsonb");
      else if (type == Type.ARRAY_OF_FLOATS)
        assertThat(language.typeName()).as(type.name()).isEqualTo("vector");
      else
        assertThat(language.typeName()).as(type.name()).isEqualTo(name);
    }
    assertThat(PostgresType.getTypeFromArcade((Type) null)).isEqualTo(PostgresType.VARCHAR);
    assertThat(ColumnType.of(null)).isEqualTo(ColumnType.VARCHAR);
  }

  @Test
  void everyColumnTypeNamesAWireType() {
    for (final ColumnType type : ColumnType.values()) {
      // the wire module has no vector type yet
      if (type == ColumnType.VECTOR)
        continue;
      if (type.isArray()) {
        final PostgresType element = PostgresType.byName(type.elementType().typeName());
        assertThat(element).as(type.typeName()).isNotNull();
        final PostgresType array = PostgresType.byCode(element.arrayCode);
        assertThat(array).as(type.typeName()).isNotNull();
        assertThat(array.isArrayType()).isTrue();
        assertThat(wireName(array)).isEqualTo(type.typeName());
      } else {
        final PostgresType wire = PostgresType.byName(type.typeName());
        assertThat(wire).as(type.typeName()).isNotNull();
        assertThat(wire.typeName).isEqualTo(type.typeName());
      }
    }
  }
}
