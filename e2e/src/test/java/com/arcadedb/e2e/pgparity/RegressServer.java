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
package com.arcadedb.e2e.pgparity;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A PostgreSQL server of the regression harness (fork issue #119): version 18.7 with the C collation, in a container of its own.
 * The harness uses one server for each of the two runs of PostgreSQL, so that what a server keeps outside of a database (roles,
 * tablespaces, the counter of object ids) is as fresh in the second run as in the first. Before each file the database
 * {@code regression} is made again and what earlier files left behind is removed.
 */
final class RegressServer implements AutoCloseable {
  static final String IMAGE = System.getProperty("pgregress.image", "postgres:18.7");

  /** Where the data files of the regression tests are in the container, {@code PG_ABS_SRCDIR} of {@code pg_regress}. */
  static final String SOURCE_DIRECTORY = "/regress";
  static final String BUILD_DIRECTORY  = "/tmp/regress_out";

  private static final String CUSTOM_ROLES       = "SELECT rolname::text FROM pg_catalog.pg_roles WHERE rolname !~ '^pg_'";
  private static final String CUSTOM_TABLESPACES = "SELECT spcname::text FROM pg_catalog.pg_tablespace WHERE spcname !~ '^pg_'";
  private static final String CUSTOM_DATABASES   =
      "SELECT datname::text FROM pg_catalog.pg_database WHERE datname NOT IN ('postgres', 'template0', 'template1')";

  private final GenericContainer<?> container;
  private       RegressWire         admin;
  private       Set<String>         roles;

  private RegressServer(final GenericContainer<?> container) {
    this.container = container;
  }

  /**
   * @param regress          the directory {@code src/test/regress} of the PostgreSQL sources, {@code null} for a server that
   *                         needs no data files
   * @param burnedObjectIds  object ids that the server takes before the first file, so that two servers do not give two objects
   *                         the same id and a statement that shows one answers differently on them
   */
  static RegressServer start(final Path regress, final int burnedObjectIds) throws Exception {
    final GenericContainer<?> container = PostgresServer.container(IMAGE, Map.of("POSTGRES_HOST_AUTH_METHOD", "trust"),
        "postgres", "-c", "fsync=off", "-c", "autovacuum=off");
    if (regress != null)
      container.withCopyFileToContainer(MountableFile.forHostPath(regress.resolve("data")), SOURCE_DIRECTORY + "/data");
    System.out.println("pgregress: starting " + IMAGE);
    container.start();
    final RegressServer server = new RegressServer(container);
    container.execInContainer("sh", "-c",
        "mkdir -p " + BUILD_DIRECTORY + "/results && chmod -R 777 " + BUILD_DIRECTORY);
    server.connect();
    server.roles = new LinkedHashSet<>(server.column(CUSTOM_ROLES));
    if (burnedObjectIds > 0)
      server.column("SELECT pg_catalog.lo_unlink(pg_catalog.lo_create(0))::text FROM pg_catalog.generate_series(1, "
          + burnedObjectIds + ")");
    return server;
  }

  private void connect() throws InterruptedException {
    IOException last = null;
    for (int attempt = 0; attempt < 60; attempt++) {
      try {
        admin = new RegressWire(container.getHost(), container.getMappedPort(5432), "postgres", "postgres");
        return;
      } catch (final IOException e) {
        last = e;
        Thread.sleep(1000);
      }
    }
    throw new UncheckedIOException("PostgreSQL did not accept a connection", last);
  }

  private RegressWire.Reply send(final String sql) {
    try {
      return admin.query(sql, (Supplier<String>) null);
    } catch (final IOException e) {
      throw new UncheckedIOException("The connection to the server failed on: " + sql, e);
    }
  }

  private List<String> column(final String sql) {
    final RegressWire.Reply reply = send(sql);
    if (reply.failed())
      throw new IllegalStateException(reply.sqlState + " " + reply.message + " for: " + sql);
    final List<String> values = new ArrayList<>(reply.rows.size());
    for (final String[] row : reply.rows)
      values.add(row[0]);
    return values;
  }

  /** The database {@code regression}, new and empty, on a server without what an earlier file left outside of its database. */
  RegressDatabase freshDatabase() {
    clean();
    // fails with the reason of the server when the database of the file before cannot be removed
    column("DROP DATABASE IF EXISTS " + RegressDatabase.NAME + " WITH (FORCE)");
    column("CREATE DATABASE " + RegressDatabase.NAME);
    return new RegressDatabase(container.getHost(), container.getMappedPort(5432));
  }

  /** A statement that fails here leaves something behind, which the next file then finds on both sides alike. */
  private void clean() {
    for (final String database : column(CUSTOM_DATABASES))
      send("DROP DATABASE " + RegressDatabase.identifier(database) + " WITH (FORCE)");
    for (final String tablespace : column(CUSTOM_TABLESPACES))
      send("DROP TABLESPACE " + RegressDatabase.identifier(tablespace));
    for (final String role : column(CUSTOM_ROLES))
      if (!roles.contains(role))
        send("DROP ROLE " + RegressDatabase.identifier(role));
    send("ALTER SYSTEM RESET ALL");
    send("SELECT pg_catalog.pg_reload_conf()");
  }

  String version() {
    return column("SELECT pg_catalog.version()").get(0);
  }

  /** The names of the tables and views of {@code pg_catalog}. */
  Set<String> catalogRelations() {
    return new LinkedHashSet<>(column("SELECT relname::text FROM pg_catalog.pg_class "
        + "WHERE relnamespace = 'pg_catalog'::pg_catalog.regnamespace AND relkind IN ('r', 'v', 'm')"));
  }

  @Override
  public void close() {
    if (admin != null)
      admin.close();
    container.stop();
  }
}
