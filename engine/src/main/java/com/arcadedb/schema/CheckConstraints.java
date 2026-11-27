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

import com.arcadedb.database.Document;
import com.arcadedb.exception.CheckConstraintException;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;
import com.arcadedb.query.postgresql.plan.CheckExpression;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The check constraints of a schema (fork issue #227), each type's compiled when a record of the type is first written after a
 * change of the types. A constraint that cannot be compiled, because a property it reads is gone or has a type that its
 * expression does not take, or because the custom value was changed into something else, cannot be checked: every write of the
 * type is refused until it can, so that no record breaks it unseen.
 */
public final class CheckConstraints {
  /** A constraint of a type, compiled, or the reason why it cannot be. */
  private record Compiled(String name, CheckExpression expression, String broken) {
  }

  private final long                        serial;
  private final Schema                      schema;
  private final boolean                     empty;
  private final Map<String, List<Compiled>> byType = new ConcurrentHashMap<>();

  private CheckConstraints(final long serial, final Schema schema, final boolean empty) {
    this.serial = serial;
    this.schema = schema;
    this.empty = empty;
  }

  static CheckConstraints of(final long serial, final Schema schema) {
    return new CheckConstraints(serial, schema,
        schema.getTypes().stream().noneMatch(type -> type.getCustomValue(CheckConstraint.CUSTOM_KEY) != null));
  }

  long serial() {
    return serial;
  }

  /** Whether the schema declares no check constraint at all, which is all a write of such a database asks. */
  public boolean isEmpty() {
    return empty;
  }

  /**
   * Checks the record against the constraints of its type, in the order of their names.
   *
   * @throws CheckConstraintException when one is false for the record, fails for it, or cannot be checked
   */
  public void check(final Document record) {
    final String typeName = record.getTypeName();
    if (empty || typeName == null || !schema.existsType(typeName))
      return;
    for (final Compiled each : byType.computeIfAbsent(typeName, name -> compile(schema.getType(name)))) {
      if (each.broken() != null)
        throw new CheckConstraintException("55000", each.broken());
      final boolean violated;
      try {
        violated = each.expression().isViolatedBy(record);
      } catch (final PostgreSQLEvaluationException e) {
        throw new CheckConstraintException(e.getSqlState(),
            "check constraint \"" + each.name() + "\" of relation \"" + typeName + "\" failed: " + e.getMessage());
      }
      if (violated)
        throw new CheckConstraintException("23514",
            "new row for relation \"" + typeName + "\" violates check constraint \"" + each.name() + "\"");
    }
  }

  private static List<Compiled> compile(final DocumentType type) {
    final Map<String, DocumentType> declaring = new LinkedHashMap<>();
    collect(type, declaring);
    final List<Compiled> compiled = new ArrayList<>();
    for (final DocumentType each : declaring.values()) {
      final Object value = each.getCustomValue(CheckConstraint.CUSTOM_KEY);
      if (value == null)
        continue;
      final List<CheckConstraint> constraints;
      try {
        constraints = CheckConstraint.fromJson(value.toString());
      } catch (final RuntimeException e) {
        compiled.add(new Compiled("", null, "The custom value " + CheckConstraint.CUSTOM_KEY + " of type '" + each.getName()
            + "' is not a list of check constraints: remove it with ALTER TYPE `" + each.getName() + "` CUSTOM "
            + CheckConstraint.CUSTOM_KEY + " = null"));
        continue;
      }
      for (final CheckConstraint constraint : constraints)
        try {
          compiled.add(new Compiled(constraint.name(), CheckExpression.of(each, type, constraint.expression()), null));
        } catch (final RuntimeException e) {
          compiled.add(new Compiled(constraint.name(), null, "The check constraint '" + constraint.name() + "' of type '"
              + each.getName() + "' cannot be checked: " + e.getMessage() + ". Restore what it reads, or remove the constraints"
              + " of the type with ALTER TYPE `" + each.getName() + "` CUSTOM " + CheckConstraint.CUSTOM_KEY + " = null"));
        }
    }
    compiled.sort(Comparator.comparing(Compiled::name));
    return List.copyOf(compiled);
  }

  private static void collect(final DocumentType type, final Map<String, DocumentType> found) {
    if (found.putIfAbsent(type.getName(), type) != null)
      return;
    for (final DocumentType superType : type.getSuperTypes())
      collect(superType, found);
  }
}
