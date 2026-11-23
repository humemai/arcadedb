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
package com.arcadedb.query.postgresql.plan.infoschema;

import com.arcadedb.query.postgresql.plan.RelationSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * One view of {@code information_schema} read as a relation: the rows are built from the catalog snapshot of the statement each
 * time the source is opened, and the columns are those of the view with the types a PostgreSQL client sees.
 */
final class InformationSchemaSource implements RelationSource {
  private final InformationSchema.View view;
  private final String                 relation;
  private final List<Field>            columns;
  private final Supplier<List<Object[]>> rows;

  InformationSchemaSource(final InformationSchema.View view, final String relation, final Supplier<List<Object[]>> rows) {
    this.view = view;
    this.relation = relation;
    final List<Field> fields = new ArrayList<>();
    for (final InformationSchema.Col column : view.columns())
      fields.add(new Field(relation, column.name(), column.type().type(), column.type().typeName()));
    this.columns = List.copyOf(fields);
    this.rows = rows;
  }

  @Override
  public List<Field> columns() {
    return columns;
  }

  @Override
  public Iterator<Object[]> open() {
    return rows.get().iterator();
  }

  @Override
  public String describe() {
    return "SCAN VIEW " + InformationSchema.SCHEMA + "." + view.name() + (relation.equals(view.name()) ? "" : " AS " + relation);
  }
}
