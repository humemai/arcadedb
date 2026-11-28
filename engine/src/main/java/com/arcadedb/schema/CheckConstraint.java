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
package com.arcadedb.schema;

import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A check constraint of a type (fork issue #227): every record of the type and of its subtypes has to make {@code expression}
 * true or unknown. The expression is one of the {@code postgresql} language, over the properties of the record, and the engine
 * evaluates it for every record that any language creates or updates, see {@link CheckConstraints}.
 * <p>
 * The declaration is the custom value {@link #CUSTOM_KEY} of the type: the text of a JSON array of the constraints.
 *
 * @param name       the name an error gives
 * @param expression the boolean expression, as the statement that declared it wrote it
 */
public record CheckConstraint(String name, String expression) {

  /** The custom value of a type that holds its check constraints, under the engine's prefix as {@link ForeignKey#CUSTOM_KEY}. */
  public static final String CUSTOM_KEY = "arcadedb_checkConstraints";

  public CheckConstraint {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(expression, "expression");
  }

  /** Whether the type or one of its super types declares a check constraint. */
  public static boolean declaredOn(final DocumentType type) {
    return type.getCustomValue(CUSTOM_KEY) != null || type.getSuperTypes().stream().anyMatch(CheckConstraint::declaredOn);
  }

  /** The text of the custom value that holds {@code constraints}. */
  public static String toJson(final List<CheckConstraint> constraints) {
    final JSONArray array = new JSONArray();
    for (final CheckConstraint constraint : constraints) {
      final JSONObject json = new JSONObject();
      json.put("name", constraint.name);
      json.put("expression", constraint.expression);
      array.put(json);
    }
    return array.toString();
  }

  /** The constraints that a custom value holds. Fails with a runtime exception for a value that is no such text. */
  public static List<CheckConstraint> fromJson(final String text) {
    final JSONArray array = new JSONArray(text);
    final List<CheckConstraint> constraints = new ArrayList<>(array.length());
    for (int i = 0; i < array.length(); i++) {
      final JSONObject json = array.getJSONObject(i);
      constraints.add(new CheckConstraint(json.getString("name"), json.getString("expression")));
    }
    return constraints;
  }
}
