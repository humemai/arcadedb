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
package com.arcadedb.postgres;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.query.postgresql.SmallFixture;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The wire corpus (fork issue #12, step W3): psql 18 scripts, mostly the {@code \d} family, whose expected output is what REAL
 * PostgreSQL 18 prints for the same shape of schema ({@code schema-pg.sql}: the {@code small} fixture as the relational view shows
 * it), compared with what the same psql 18 prints when it runs the script against this server in a session of the
 * {@code postgresql} language.
 * <p>
 * The expected files are in {@code src/test/resources/pgwire/expected}, one per case of {@code corpus.txt}. Every run also starts a
 * real PostgreSQL 18 (the {@code postgres:18} image, the one of {@code -Dpgparity.image}) and checks that it still prints the
 * expected files, so an expected file cannot drift away from PostgreSQL, and writes what both servers printed to
 * {@code target/pgwire/real} and {@code target/pgwire/arcadedb}: a new case is added to {@code corpus.txt}, and the file the job
 * uploads has its expected output, to be reviewed and committed.
 * <p>
 * {@code known-differences.txt} lists the cases whose output differs from PostgreSQL, each with its reason. A case that is not
 * listed must be equal, and a listed case that becomes equal fails the run until it is taken off the list (the list may only
 * shrink), as the expected failures of the parity harness do (fork issue #2).
 * <p>
 * Needs a Docker daemon: without one the test is skipped. It runs in the wire module job of {@code pg-prototype-engine-tests.yml}.
 */
class PostgreSQLPsqlCorpusTest extends PostgresWireProtocolTestBase {
  private static final String PG_IMAGE    = System.getProperty("pgparity.image", "postgres:18");
  private static final String PG_PASSWORD = "corpus";

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

  /** The database of every test is the small fixture and nothing else, so a catalog lists its four tables only. */
  @Override
  protected void populateDatabase() {
    SmallFixture.createSchema(getDatabases()[0]);
    SmallFixture.load(getDatabases()[0]);
  }

  private static void requireDocker() {
    boolean available;
    try {
      available = DockerClientFactory.instance().isDockerAvailable();
    } catch (final RuntimeException e) {
      available = false;
    }
    assumeTrue(available, "a Docker daemon is needed to start PostgreSQL 18");
  }

  private static String resource(final String path) throws IOException {
    try (final InputStream in = PostgreSQLPsqlCorpusTest.class.getResourceAsStream(path)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** The cases of {@code corpus.txt}, by name: a line {@code == name} starts a case, and the lines after it are the psql script. */
  private static Map<String, String> corpus() throws IOException {
    final Map<String, String> cases = new LinkedHashMap<>();
    String name = null;
    StringBuilder script = new StringBuilder();
    for (final String line : resource("/pgwire/corpus.txt").split("\\R")) {
      if (line.startsWith("== ")) {
        if (name != null)
          cases.put(name, script.toString());
        name = line.substring(3).strip();
        script = new StringBuilder();
      } else if (name != null && !line.isBlank())
        script.append(line).append('\n');
    }
    if (name != null)
      cases.put(name, script.toString());
    return cases;
  }

  /** The cases that differ from PostgreSQL, by name, with the reason: {@code name | reason}. */
  private static Map<String, String> knownDifferences() throws IOException {
    final Map<String, String> known = new LinkedHashMap<>();
    final String text = resource("/pgwire/known-differences.txt");
    if (text != null)
      for (final String line : text.split("\\R"))
        if (!line.isBlank() && !line.startsWith("#")) {
          final int bar = line.indexOf('|');
          known.put(line.substring(0, bar).strip(), line.substring(bar + 1).strip());
        }
    return known;
  }

  /** Runs a script with psql inside the PostgreSQL container, against {@code host}: the real server, or this one. */
  private static String psql(final GenericContainer<?> container, final String host, final int port, final String options,
      final String name) throws Exception {
    final String command = "PGPASSWORD=" + (options == null ? PG_PASSWORD : DEFAULT_PASSWORD_FOR_TESTS)
        + " PGSSLMODE=disable PGCONNECT_TIMEOUT=30" + (options == null ? "" : " PGOPTIONS='" + options + "'") + " psql -X -q -h "
        + host + " -p " + port + " -U root -d postgresdb -f /work/" + name + ".psql 2>&1";
    final Container.ExecResult result = container.execInContainer("sh", "-c", command);
    return result.getStdout();
  }

  @Test
  void psqlOutputEqualsWhatRealPostgreSQL18Prints() throws Exception {
    requireDocker();
    final Map<String, String> cases = corpus();
    final Map<String, String> known = knownDifferences();
    final Path real = Path.of("target", "pgwire", "real");
    final Path arcadedb = Path.of("target", "pgwire", "arcadedb");
    Files.createDirectories(real);
    Files.createDirectories(arcadedb);

    Testcontainers.exposeHostPorts(getServerPostgresPort());
    final List<String> problems = new ArrayList<>();
    int equal = 0;
    try (final GenericContainer<?> pg = new GenericContainer<>(PG_IMAGE).withEnv("POSTGRES_USER", "root")
        .withEnv("POSTGRES_PASSWORD", PG_PASSWORD).withEnv("POSTGRES_DB", getDatabaseName())
        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        .withStartupTimeout(Duration.ofMinutes(3))) {
      pg.start();
      pg.execInContainer("mkdir", "-p", "/work");
      pg.copyFileToContainer(Transferable.of(resource("/pgwire/schema-pg.sql")), "/work/schema.sql");
      final Container.ExecResult load = pg.execInContainer("sh", "-c", "PGPASSWORD=" + PG_PASSWORD
          + " psql -X -q -v ON_ERROR_STOP=1 -h localhost -U root -d " + getDatabaseName() + " -f /work/schema.sql 2>&1");
      assertThat(load.getExitCode()).as(load.getStdout()).isZero();

      for (final Map.Entry<String, String> entry : cases.entrySet()) {
        final String name = entry.getKey();
        pg.copyFileToContainer(Transferable.of(entry.getValue()), "/work/" + name + ".psql");

        final String printedByPostgreSQL = psql(pg, "localhost", 5432, null, name);
        final String printedByUs = psql(pg, "host.testcontainers.internal", getServerPostgresPort(), "-c arcadedb.language=postgresql",
            name);
        Files.writeString(real.resolve(name + ".out"), printedByPostgreSQL);
        Files.writeString(arcadedb.resolve(name + ".out"), printedByUs);

        final String expected = resource("/pgwire/expected/" + name + ".out");
        if (expected == null) {
          problems.add(name + ": no expected file yet; PostgreSQL 18 printed target/pgwire/real/" + name + ".out");
          continue;
        }
        if (!printedByPostgreSQL.equals(expected))
          problems.add(name + ": the expected file is not what PostgreSQL 18 prints now:\n" + printedByPostgreSQL);

        final boolean same = printedByUs.equals(expected);
        if (same && known.containsKey(name))
          problems.add(name + ": equal to PostgreSQL 18 now, take it off known-differences.txt (" + known.get(name) + ")");
        else if (!same && !known.containsKey(name))
          problems.add(name + ": differs from PostgreSQL 18.\n--- PostgreSQL 18\n" + expected + "--- here\n" + printedByUs);
        if (same)
          ++equal;
      }
    }
    System.out.println("PSQL CORPUS: " + equal + " of " + cases.size() + " cases equal to PostgreSQL 18, " + known.size()
        + " known differences");
    assertThat(problems).as("the corpus: " + equal + " of " + cases.size() + " equal").isEmpty();
  }
}
