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
import com.arcadedb.database.Document;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.database.RID;
import com.arcadedb.query.sql.executor.Result;
import com.arcadedb.query.sql.executor.ResultSet;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Throwaway probe for fork issue #288, not to be merged: what the engine does with every kind of JSON value in each form a
 * {@code jsonb} column could be stored in. It only reports.
 */
class JsonbStorageProbeTest extends TestHelper {
  private record Value(String name, Object value) {
  }

  private enum Form {
    MAP, UNDECLARED, TEXT, ENVELOPE, LIST
  }

  private final StringBuilder out = new StringBuilder();

  @Test
  void report() throws IOException {
    final List<Value> values = new ArrayList<>();
    values.add(new Value("object", Map.of("k", 1L)));
    values.add(new Value("array of strings", List.of("a", "b")));
    values.add(new Value("empty array", List.of()));
    values.add(new Value("array of objects", List.of(Map.of("k", 1L), Map.of("k", 2L))));
    values.add(new Value("nested array", List.of(List.of(1L, 2L), List.of(3L))));
    values.add(new Value("mixed array with null", Arrays.asList(1L, "a", true, null)));
    values.add(new Value("string", "x"));
    values.add(new Value("integer", 1L));
    values.add(new Value("decimal 1.50", new BigDecimal("1.50")));
    values.add(new Value("large number", new BigDecimal("123456789012345678901234567890")));
    values.add(new Value("boolean", Boolean.TRUE));
    values.add(new Value("JSON null", null));

    out.append("## Round trip of each JSON value\n\n");
    out.append("| form | value | write and read back | has property | sql `SELECT v` | openCypher `n.v` |\n");
    out.append("| --- | --- | --- | --- | --- | --- |\n");
    for (final Form form : Form.values()) {
      final String type = "P_" + form;
      declare(type, form);
      for (final Value value : values) {
        clear(type);
        final RID[] rid = new RID[1];
        final String written = attempt(() -> {
          rid[0] = write(type, stored(form, value.value()));
          final Document loaded = (Document) database.lookupByRID(rid[0], true);
          return describe(loaded.get("v"));
        });
        if (rid[0] == null) {
          row(form, value.name(), written, "-", "-", "-");
          continue;
        }
        row(form, value.name(), written, attempt(() -> String.valueOf(((Document) database.lookupByRID(rid[0], true)).has("v"))),
            attempt(() -> first(database.query("sql", "SELECT v FROM " + type))),
            attempt(() -> first(database.query("opencypher", "MATCH (n:" + type + ") RETURN n.v AS v"))));
      }
      // SQL NULL: the property is not set
      clear(type);
      final RID[] rid = new RID[1];
      final String written = attempt(() -> {
        rid[0] = save(type, false, null);
        return describe(((Document) database.lookupByRID(rid[0], true)).get("v"));
      });
      row(form, "(SQL NULL, not set)", written,
          attempt(() -> String.valueOf(((Document) database.lookupByRID(rid[0], true)).has("v"))), "-", "-");
    }

    out.append("\n## Bytes of one record with only this value\n\n");
    final List<Object> history = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      final Map<String, Object> message = new LinkedHashMap<>();
      message.put("role", i % 2 == 0 ? "user" : "assistant");
      message.put("content", "message number " + i + " of a conversation that an agent keeps in one column");
      message.put("tokens", (long) (100 + i));
      history.add(message);
    }
    final Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("theme", "dark");
    settings.put("limit", 25L);
    final List<Value> sized = List.of(new Value("object of 2 keys", settings),
        new Value("5 tags", List.of("alpha", "beta", "gamma", "delta", "epsilon")), new Value("20 messages", history),
        new Value("1000 integers", integers(1000)), new Value("string of 5 characters", "hello"), new Value("integer", 42L));
    out.append("| value |");
    for (final Form form : Form.values())
      out.append(' ').append(form).append(" |");
    out.append("\n| --- |").append(" --- |".repeat(Form.values().length)).append('\n');
    for (final Value value : sized) {
      out.append("| ").append(value.name()).append(" |");
      for (final Form form : Form.values()) {
        final String type = "P_" + form;
        out.append(' ').append(attempt(() -> {
          clear(type);
          final RID rid = write(type, stored(form, value.value()));
          final Document loaded = (Document) database.lookupByRID(rid, true);
          loaded.get("v");
          return String.valueOf(loaded.size());
        })).append(" |");
      }
      out.append('\n');
    }

    out.append("\n## What the other languages and indexes can do with the array `[\"a\",\"b\"]`\n\n");
    out.append("| form | sql `v CONTAINS 'a'` rows | openCypher `'a' IN n.v` rows | sql `v.size()` | tree index on v | NOT NULL on v |\n");
    out.append("| --- | --- | --- | --- | --- | --- |\n");
    for (final Form form : Form.values()) {
      final String type = "Q_" + form;
      declare(type, form);
      out.append("| ").append(form).append(" | ").append(attempt(() -> {
        write(type, stored(form, List.of("a", "b")));
        return count(database.query("sql", "SELECT FROM " + type + " WHERE v CONTAINS 'a'"));
      })).append(" | ").append(attempt(
          () -> count(database.query("opencypher", "MATCH (n:" + type + ") WHERE 'a' IN n.v RETURN n"))))
          .append(" | ").append(attempt(() -> first(database.query("sql", "SELECT v.size() AS v FROM " + type))))
          .append(" | ").append(attempt(() -> {
            database.getSchema().getType(type).createTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "v");
            return "made";
          })).append(" | ").append(attempt(() -> {
            database.getSchema().getType(type).getProperty("v").setNotNull(true);
            return "set";
          })).append(" |\n");
    }

    final Path file = Path.of("target", "pg-agent-fit", "jsonb-storage-probe.md");
    Files.createDirectories(file.getParent());
    Files.writeString(file, out.toString());
    System.out.println(out);
  }

  private static List<Object> integers(final int count) {
    final List<Object> list = new ArrayList<>(count);
    for (int i = 0; i < count; i++)
      list.add((long) i);
    return list;
  }

  private void declare(final String name, final Form form) {
    final DocumentType type = database.getSchema().createVertexType(name);
    switch (form) {
    case MAP, ENVELOPE -> type.createProperty("v", Type.MAP);
    case TEXT -> type.createProperty("v", Type.STRING);
    case LIST -> type.createProperty("v", Type.LIST);
    case UNDECLARED -> {
    }
    }
  }

  /** The value as the form holds it: an object stays a map in the envelope, and every other value goes under one reserved key. */
  private static Object stored(final Form form, final Object value) {
    return switch (form) {
      case MAP, UNDECLARED, LIST -> value;
      case TEXT -> text(value);
      case ENVELOPE -> value instanceof Map<?, ?> ? value : Collections.singletonMap("$json", value);
    };
  }

  private static String text(final Object value) {
    if (value instanceof Map<?, ?> map) {
      final JSONObject object = new JSONObject();
      map.forEach((key, item) -> object.put((String) key, item));
      return object.toString();
    }
    final JSONArray wrapper = new JSONArray();
    wrapper.put(value);
    final String array = wrapper.toString();
    return array.substring(1, array.length() - 1);
  }

  private RID write(final String type, final Object value) {
    return save(type, true, value);
  }

  private RID save(final String type, final boolean set, final Object value) {
    final RID[] rid = new RID[1];
    database.transaction(() -> {
      final MutableDocument vertex = database.newVertex(type);
      if (set)
        vertex.set("v", value);
      vertex.save();
      rid[0] = vertex.getIdentity();
    });
    return rid[0];
  }

  private void clear(final String type) {
    database.transaction(() -> database.command("sql", "DELETE FROM " + type));
  }

  private void row(final Form form, final String... cells) {
    out.append("| ").append(form).append(" | ").append(String.join(" | ", cells)).append(" |\n");
  }

  private static String describe(final Object value) {
    return value == null ? "null" : value.getClass().getSimpleName() + " " + value;
  }

  private static String first(final ResultSet rows) {
    try (rows) {
      if (!rows.hasNext())
        return "no row";
      final Result row = rows.next();
      return describe(row.getProperty("v"));
    }
  }

  private static String count(final ResultSet rows) {
    try (rows) {
      return String.valueOf(rows.stream().count());
    }
  }

  private static String attempt(final Callable<String> action) {
    String result;
    try {
      result = action.call();
    } catch (final Exception e) {
      result = "refused: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).split("\n")[0];
    }
    result = result.replace("|", "\\|").replace('\n', ' ');
    return result.length() > 140 ? result.substring(0, 140) + "..." : result;
  }
}
