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
package com.arcadedb.query.postgresql.corpus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tpch.tsv}, the four TPC-H statements of fork issue #13, is well formed, and the four texts are the texts they are
 * meant to be: the SHA-256 of each is written here, so that a statement cannot be edited to make it run. A statement that needs
 * an edit counts as failed (the issue says so), so a change of one of these texts is a change of the instrument and has to
 * change the hash in the same commit, with the reason. Q1 and Q6 are the text of the PostgreSQL arm of the benchmark lane
 * ({@code experiments/l1_tpc.py} of {@code humemai/mmdbbench}, commit 48a1c2e), Q14 and Q19 the text of clauses 2.4.14.2 and
 * 2.4.19.2 of the TPC-H Standard Specification Revision 3.0.1 with the default values of its validation clauses.
 */
class TpchCorpusFileTest {
  private static final Map<String, String> SHA256 = Map.of(
      "tpch-q1", "c3c4809b5ddfdabaaf5ca616a9de1eddb800ba11bc9b87d2bbf82e0011d8f67f",
      "tpch-q6", "76c66232337bdaa2665b5fb45c98d381b347692eb591c466d45a71767020beb4",
      "tpch-q14", "16b3f1c3c228940e553f3c6a1ad80807ad5ab90eab4f49e34e844c6ae8d66845",
      "tpch-q19", "6b7fba1576d32ab92988b9c06662f2b9f0fd071976f6185a67de2b60d60ba84e");

  private static String sha256(final String text) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void theFileIsWellFormed() throws IOException {
    final CorpusFile file = Corpus.readTpch();
    assertThat(file.columns).containsExactly("id", "order", "tol", "status", "statement", "expected");
    assertThat(file.rows).hasSize(4);
    for (final Corpus.AnswerCase c : Corpus.answerCases(file)) {
      assertThat(c.id()).startsWith("tpch-q");
      assertThat(c.row[Corpus.AnswerCase.ORDER]).as("order of %s", c.id()).isIn("ordered", "multiset");
      assertThat(c.row[Corpus.AnswerCase.STATUS]).as("status of %s", c.id()).isIn("ok", "pending");
      assertThat(c.tolerance()).as("tolerance of %s", c.id()).isPositive();
      if (c.ordered())
        assertThat(c.statement().toUpperCase()).as("ordered %s has an ORDER BY", c.id()).contains("ORDER BY");
      if (c.hasOutcome()) {
        final Outcome outcome = Outcome.fromJson(c.expected());
        assertThat(outcome.isRefusal()).as("%s is answered by the server", c.id()).isFalse();
        assertThat(outcome.table.rows).as("rows of %s", c.id()).isNotEmpty();
      }
    }
  }

  @Test
  void theTextsAreTheOnesTheyAreMeantToBe() throws Exception {
    final Map<String, String> actual = new HashMap<>();
    for (final Corpus.AnswerCase c : Corpus.answerCases(Corpus.readTpch()))
      actual.put(c.id(), sha256(c.statement()));
    assertThat(actual).isEqualTo(SHA256);
  }
}
