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
package com.arcadedb.postgres;

import com.arcadedb.GlobalConfiguration;
import com.arcadedb.query.postgresql.SmallFixture;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two business intelligence tools of the client matrix of fork issue #12, Grafana and Metabase, driven through their HTTP
 * APIs against the wire server on the {@code small} fixture: each one is set up with a PostgreSQL data source and runs the join
 * of the matrix. The issue asks for a report of them and does not require them to pass; the lines that start with
 * {@code bi-report} are that report, and the assertions pin what was seen.
 * <p>
 * Needs a Docker daemon: without one the tests are skipped.
 */
class PostgreSQLBiClientsTest extends PostgresWireProtocolTestBase {
  private static final String JOIN = "SELECT p.name, o.oid FROM orders o JOIN person p ON o.pid = p.pid ORDER BY o.oid";
  private static final List<String> JOIN_ROWS = List.of("Ada|100", "Ada|101", "Bo|102", "Di|103", "Di|104");

  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

  @Override
  public void setTestConfiguration() {
    super.setTestConfiguration();
    GlobalConfiguration.POSTGRES_LANGUAGES.setValue("sqlscript,cypher,opencypher,postgresql");
  }

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
    assumeTrue(available, "a Docker daemon is needed to start the tools");
  }

  private static void report(final String line) {
    System.out.println("bi-report " + line);
  }

  private HttpResponse<String> call(final String method, final String url, final String body, final String... headers)
      throws Exception {
    final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120))
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (headers.length > 0)
      request.headers(headers);
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String shortText(final String text) {
    final String line = text.replaceAll("\\s+", " ");
    return line.length() > 600 ? line.substring(0, 600) : line;
  }

  // ------------------------------------------------------------------------------------------------------------ Grafana

  /** The rows of one {@code /api/ds/query} answer, as {@code a|b}, or the error text with the prefix {@code error: }. */
  private static List<String> grafanaRows(final HttpResponse<String> response) {
    final JSONObject result = new JSONObject(response.body()).getJSONObject("results").getJSONObject("A");
    if (result.has("error"))
      return List.of("error: " + result.getString("error"));
    final JSONArray values = result.getJSONArray("frames").getJSONObject(0).getJSONObject("data").getJSONArray("values");
    final List<String> rows = new ArrayList<>();
    for (int row = 0; values.length() > 0 && row < values.getJSONArray(0).length(); row++) {
      final StringBuilder text = new StringBuilder();
      for (int column = 0; column < values.length(); column++)
        text.append(column > 0 ? "|" : "").append(values.getJSONArray(column).get(row));
      rows.add(text.toString());
    }
    return rows;
  }

  @Test
  void grafanaRunsTheJoinWithTheLanguageNamedInTheQuery() throws Exception {
    requireDocker();
    Testcontainers.exposeHostPorts(getServerPostgresPort());
    try (final GenericContainer<?> grafana = new GenericContainer<>("grafana/grafana:latest").withExposedPorts(3000)
        .withEnv("GF_SECURITY_ADMIN_PASSWORD", "grafana-test-password")
        .waitingFor(Wait.forHttp("/api/health").forPort(3000).withStartupTimeout(Duration.ofMinutes(5)))) {
      grafana.start();
      final String base = "http://" + grafana.getHost() + ":" + grafana.getMappedPort(3000);
      final String auth = "Basic " + Base64.getEncoder()
          .encodeToString("admin:grafana-test-password".getBytes(StandardCharsets.UTF_8));
      report("grafana health " + shortText(call("GET", base + "/api/health", null).body()));

      final JSONObject source = new JSONObject().put("name", "arcadedb").put("type", "grafana-postgresql-datasource")
          .put("access", "proxy").put("url", "host.testcontainers.internal:" + getServerPostgresPort()).put("user", "root")
          .put("database", getDatabaseName())
          .put("jsonData", new JSONObject().put("sslmode", "disable").put("database", getDatabaseName()))
          .put("secureJsonData", new JSONObject().put("password", DEFAULT_PASSWORD_FOR_TESTS));
      final HttpResponse<String> created = call("POST", base + "/api/datasources", source.toString(), "Authorization", auth);
      report("grafana datasource " + created.statusCode() + " " + shortText(created.body()));
      assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
      final String uid = new JSONObject(created.body()).getJSONObject("datasource").getString("uid");

      // its PostgreSQL data source has no field for startup options, so the language is named in the query text
      final List<List<String>> answers = new ArrayList<>();
      for (final String sql : List.of(JOIN, "{postgresql} " + JOIN, "SET arcadedb.language = 'postgresql'; " + JOIN)) {
        final JSONObject query = new JSONObject().put("refId", "A").put("format", "table").put("rawSql", sql)
            .put("datasource", new JSONObject().put("uid", uid).put("type", "grafana-postgresql-datasource"));
        final HttpResponse<String> response = call("POST", base + "/api/ds/query",
            new JSONObject().put("queries", new JSONArray().put(query)).put("from", "now-1h").put("to", "now").toString(),
            "Authorization", auth);
        final List<String> rows = grafanaRows(response);
        report("grafana query [" + sql + "] -> " + response.statusCode() + " " + shortText(String.join(", ", rows)));
        answers.add(rows);
      }
      assertThat(answers.get(0).get(0)).as("with no language named the statement runs in sql, which has no such join")
          .startsWith("error: ");
      assertThat(answers.get(1)).as("the marker").isEqualTo(JOIN_ROWS);
    }
  }

  // ----------------------------------------------------------------------------------------------------------- Metabase

  @Test
  void metabaseRunsTheJoinWithTheLanguageOfTheConnection() throws Exception {
    requireDocker();
    Testcontainers.exposeHostPorts(getServerPostgresPort());
    try (final GenericContainer<?> metabase = new GenericContainer<>("metabase/metabase:latest").withExposedPorts(3000)
        .waitingFor(Wait.forHttp("/api/health").forPort(3000).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(8)))) {
      metabase.start();
      final String base = "http://" + metabase.getHost() + ":" + metabase.getMappedPort(3000);
      final JSONObject properties = new JSONObject(call("GET", base + "/api/session/properties", null).body());
      report("metabase version " + shortText(String.valueOf(properties.opt("version"))));

      // the connection chooses the language with a JDBC option, which Metabase takes as additional connection string options
      final JSONObject details = new JSONObject().put("host", "host.testcontainers.internal").put("port", getServerPostgresPort())
          .put("dbname", getDatabaseName()).put("user", "root").put("password", DEFAULT_PASSWORD_FOR_TESTS).put("ssl", false)
          .put("additional-options", "options=-c%20arcadedb.language=postgresql");
      final JSONObject setup = new JSONObject().put("token", properties.getString("setup-token"))
          .put("user", new JSONObject().put("first_name", "Ada").put("last_name", "Test").put("email", "ada@example.com")
              .put("password", "Metabase-test-password-1"))
          .put("prefs", new JSONObject().put("site_name", "test").put("allow_tracking", false));
      final HttpResponse<String> session = call("POST", base + "/api/setup", setup.toString());
      report("metabase setup " + session.statusCode() + (session.statusCode() == 200 ? "" : " " + shortText(session.body())));
      assertThat(session.statusCode()).as(session.body()).isEqualTo(200);
      final String key = new JSONObject(session.body()).getString("id");

      final HttpResponse<String> added = call("POST", base + "/api/database",
          new JSONObject().put("engine", "postgres").put("name", "arcadedb").put("details", details).toString(),
          "X-Metabase-Session", key);
      report("metabase add database " + added.statusCode() + " " + shortText(added.body()));
      assertThat(added.statusCode()).as(added.body()).isEqualTo(200);
      final int id = new JSONObject(added.body()).getInt("id");

      final HttpResponse<String> answer = call("POST", base + "/api/dataset", new JSONObject().put("database", id)
          .put("type", "native").put("native", new JSONObject().put("query", JOIN)).toString(), "X-Metabase-Session", key);
      report("metabase join " + answer.statusCode() + " " + shortText(answer.body()));
      final JSONObject body = new JSONObject(answer.body());
      assertThat(body.getString("status")).as(shortText(answer.body())).isEqualTo("completed");
      final JSONArray data = body.getJSONObject("data").getJSONArray("rows");
      final List<String> rows = new ArrayList<>();
      for (int i = 0; i < data.length(); i++)
        rows.add(data.getJSONArray(i).get(0) + "|" + data.getJSONArray(i).get(1));
      assertThat(rows).isEqualTo(JOIN_ROWS);

      // what Metabase read of the tables by itself, for the report; the sync runs in the background
      Thread.sleep(30_000);
      final HttpResponse<String> metadata = call("GET", base + "/api/database/" + id + "/metadata", null, "X-Metabase-Session", key);
      final JSONArray tables = new JSONObject(metadata.body()).getJSONArray("tables");
      final List<String> names = new ArrayList<>();
      for (int i = 0; i < tables.length(); i++)
        names.add(tables.getJSONObject(i).getString("name") + "(" + tables.getJSONObject(i).getJSONArray("fields").length() + ")");
      report("metabase tables synced " + names);
    }
  }
}
