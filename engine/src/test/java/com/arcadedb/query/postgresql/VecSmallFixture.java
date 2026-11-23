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

import com.arcadedb.database.Database;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Type;

/**
 * The {@code vec-small} fixture of fork issue #11: a document type {@code item} with a string {@code iid} and an
 * {@code ARRAY_OF_FLOATS} {@code embedding}, and four rows: a {@code [1,0,0]}, b {@code [0,1,0]}, c {@code [2,2,0]} and
 * d {@code [-1,0,0]}. {@link #createIndex} adds an {@code LSM_VECTOR} index of one metric on {@code embedding}.
 * <p>
 * Public, so that the tests of the sub-packages use the same data.
 */
public final class VecSmallFixture {
  public static final String ITEM = "item";

  private VecSmallFixture() {
  }

  public static void createSchema(final Database database) {
    final DocumentType item = database.getSchema().createDocumentType(ITEM);
    item.createProperty("iid", Type.STRING);
    item.createProperty("embedding", Type.ARRAY_OF_FLOATS);
  }

  /** An {@code LSM_VECTOR} index on {@code item[embedding]} of three dimensions; {@code similarity} is {@code EUCLIDEAN}, {@code COSINE} or {@code DOT_PRODUCT}. */
  public static void createIndex(final Database database, final String similarity) {
    database.transaction(() -> database.getSchema().buildTypeIndex(ITEM, new String[] { "embedding" }).withLSMVectorType()
        .withDimensions(3).withSimilarity(similarity).create());
  }

  /** The four rows of the issue, or {@code rows} given as {@code iid} then three floats. */
  public static void load(final Database database) {
    load(database, new Object[][] { { "a", 1f, 0f, 0f }, { "b", 0f, 1f, 0f }, { "c", 2f, 2f, 0f }, { "d", -1f, 0f, 0f } });
  }

  public static void load(final Database database, final Object[][] rows) {
    database.transaction(() -> {
      for (final Object[] row : rows)
        database.newDocument(ITEM).set("iid", row[0]).set("embedding", new float[] { (Float) row[1], (Float) row[2], (Float) row[3] })
            .save();
    });
  }
}
