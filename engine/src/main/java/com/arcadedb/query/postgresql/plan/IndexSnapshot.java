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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.lsm.LSMTreeIndexAbstract;
import com.arcadedb.schema.IndexMetadata;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.TypeIndexBuilder;

import java.util.List;

/**
 * What an index of a type is, so that it can be built again, over the same properties or renamed ones (fork issue #230): what
 * {@code TRUNCATE TYPE} of {@code sql} carries over when it makes an index again, the settings of a vector or full-text index among
 * them.
 */
record IndexSnapshot(String name, List<String> properties, Schema.INDEX_TYPE type, boolean unique, int pageSize,
    LSMTreeIndexAbstract.NULL_STRATEGY nullStrategy, IndexMetadata metadata) {

  static IndexSnapshot of(final TypeIndex index) {
    return new IndexSnapshot(index.getName(), List.copyOf(index.getPropertyNames()), index.getType(), index.isUnique(),
        index.getPageSizeForNewFile(), index.getNullStrategy(), index.getMetadataForNewFile());
  }

  IndexSnapshot renamed(final String from, final String to) {
    final List<String> renamed = properties.stream().map(each -> each.equals(from) ? to : each).toList();
    return new IndexSnapshot(name, renamed, type, unique, pageSize, nullStrategy, metadata);
  }

  /** Builds the index on the type. */
  void create(final Schema schema, final String typeName) {
    final String[] columns = properties.toArray(new String[0]);
    // the builder of the type of the index is the one that knows its settings
    final TypeIndexBuilder builder = schema.buildTypeIndex(typeName, columns).withType(type);
    builder.withUnique(unique);
    builder.withPageSize(pageSize);
    builder.withNullStrategy(nullStrategy);
    if (metadata != null)
      builder.withMetadata(metadata.copy(typeName, columns, -1));
    builder.create();
  }

  /** Drops the index of the type over these properties, if there is one. */
  void dropFrom(final Schema schema, final String typeName) {
    if (!schema.existsType(typeName))
      return;
    final TypeIndex made = schema.getType(typeName).getPolymorphicIndexByProperties(properties.toArray(new String[0]));
    if (made != null)
      schema.dropIndex(made.getName());
  }
}
