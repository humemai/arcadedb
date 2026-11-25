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

import com.arcadedb.TestHelper;
import com.arcadedb.database.DatabaseContext;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.security.SecurityDatabaseUser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code information_schema} of the {@code postgresql} language lists only what the user may read, although the catalog it is
 * read from is shared by every user of the database.
 */
class CatalogReadableTypesTest extends TestHelper {
  @Test
  void informationSchemaLeavesOutTheTypesTheUserMayNotRead() {
    database.command("sql", "CREATE VERTEX TYPE Visible");
    database.command("sql", "CREATE VERTEX TYPE Hidden");
    database.command("sql", "CREATE PROPERTY Visible.name STRING");
    database.command("sql", "CREATE PROPERTY Visible.other LINK OF Hidden");

    final String tables = "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name";
    final String foreignKeys = "SELECT table_name FROM information_schema.table_constraints WHERE constraint_type = 'FOREIGN KEY'";
    assertThat(names(tables)).containsExactly("Hidden", "Visible");
    assertThat(names(foreignKeys)).containsExactly("Visible");

    final DatabaseContext.DatabaseContextTL context = DatabaseContext.INSTANCE.getContext(database.getDatabasePath());
    context.setCurrentUser(userDenying("Hidden"));
    try {
      assertThat(names(tables)).containsExactly("Visible");
      assertThat(names(foreignKeys)).isEmpty();
    } finally {
      context.setCurrentUser(null);
    }
    assertThat(names(tables)).containsExactly("Hidden", "Visible");
  }

  private List<String> names(final String query) {
    final List<String> names = new ArrayList<>();
    try (final ResultSet resultSet = database.query("postgresql", query)) {
      while (resultSet.hasNext())
        names.add(resultSet.next().getProperty("table_name"));
    }
    return names;
  }

  private SecurityDatabaseUser userDenying(final String denied) {
    final Set<Integer> deniedBuckets = Set.copyOf(database.getSchema().getType(denied).getBucketIds(false));
    return new SecurityDatabaseUser() {
      @Override
      public String getName() {
        return "scoped";
      }

      @Override
      public boolean requestAccessOnDatabase(final DATABASE_ACCESS access) {
        return true;
      }

      @Override
      public boolean requestAccessOnFile(final int fileId, final ACCESS access) {
        return !deniedBuckets.contains(fileId);
      }

      @Override
      public boolean requestAccessOnType(final String typeName, final ACCESS access) {
        return !denied.equals(typeName);
      }

      @Override
      public long getResultSetLimit() {
        return -1;
      }

      @Override
      public long getReadTimeout() {
        return -1;
      }
    };
  }
}
