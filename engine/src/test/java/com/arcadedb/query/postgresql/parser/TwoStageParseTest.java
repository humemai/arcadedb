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
package com.arcadedb.query.postgresql.parser;

import com.arcadedb.query.postgresql.ast.Statement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first stage of the parser (SLL prediction, fork issue #92) against the full parse: for every text of the corpus files, in
 * every column of every line, either the first stage gives the very tree of the full parse, or it gives nothing and the full parse
 * answers (a tree or an error); and whatever {@link StatementParser#parse} answers is what the full parse answers, tree or error.
 */
class TwoStageParseTest {
  private static final String[] FILES = { "syntax.tsv", "answers.tsv", "writes.tsv", "idrule-writes.tsv", "tpch.tsv", "idrule.tsv", "generated.tsv",
      "agent-sql.tsv" };

  private static Object outcome(final String text, final boolean full) {
    try {
      return full ? StatementParser.parseFull(text) : StatementParser.parse(text);
    } catch (final RuntimeException e) {
      return e.getClass().getName() + ": " + e.getMessage();
    }
  }

  private static List<String> texts() throws IOException {
    final List<String> out = new ArrayList<>();
    for (final String file : FILES)
      try (final InputStream in = TwoStageParseTest.class.getResourceAsStream("/postgresql/corpus/" + file)) {
        if (in == null)
          continue;
        for (final String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
          if (!line.startsWith("#"))
            for (final String field : line.split("\t"))
              if (field.length() > 6)
                out.add(field.replace("\\n", "\n").replace("\\t", "\t"));
      }
    out.addAll(List.of("SELECT 1", "select 1;", "SELECT a - -1 FROM t", "SELECT $1::int + 2", "SELECT CASE WHEN a THEN 1 END FROM t", "SELECT", "SELECT FROM",
        "SELECT 1 FROM", "CREATE TABLE t (a int)", "SELECT 'a' 'b'", "SELECT * FROM t WHERE a IN (1, 2) AND b BETWEEN 1 AND 2 OR c LIKE 'x%'",
        "SELECT x FROM (VALUES (1), (2)) AS t(x)", "INSERT INTO t (a) VALUES (1), (2)", "UPDATE t SET a = 1 WHERE b = 2", "DELETE FROM t WHERE a = 1",
        "SELECT row_number() OVER (PARTITION BY a ORDER BY b) FROM t", "SELECT a FROM t1 JOIN t2 USING (a) LEFT JOIN t3 ON t3.x = t1.a",
        "WITH x AS (SELECT 1) SELECT * FROM x", "SELECT 1 UNION ALL SELECT 2 ORDER BY 1 LIMIT 1", "SELECT a FROM t WHERE (a, b) = (1, 2)", ""));
    return out;
  }

  @Test
  void theFirstStageGivesTheTreeOfTheFullParseOrNothing() throws IOException {
    final List<String> texts = texts();
    assertThat(texts.size()).isGreaterThan(500);
    int fast = 0;
    for (final String text : texts) {
      final Statement quick = StatementParser.parseFast(text);
      if (quick != null) {
        ++fast;
        assertThat(outcome(text, true)).as(text).isEqualTo(quick);
      }
      assertThat(outcome(text, false)).as(text).isEqualTo(outcome(text, true));
    }
    // the corpus lines hold statements and other cells: the statements are what the first stage answers for
    assertThat(fast).isGreaterThan(500);
  }
}
