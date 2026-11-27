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
package com.arcadedb.remote;

import com.arcadedb.exception.ForeignKeyException;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fork issue #228: the HTTP driver rebuilds a {@link ForeignKeyException} with the SQLSTATE of the body, buffered or streamed.
 */
class ForeignKeyRemoteExceptionTest {
  private RemoteHttpComponentTest.TestableRemoteHttpComponent component;

  @BeforeEach
  void setUp() {
    // Nothing is sent: manageException only reads the body it is given
    component = new RemoteHttpComponentTest.TestableRemoteHttpComponent("localhost", 1, "root", "test");
  }

  @AfterEach
  void tearDown() {
    component.close();
  }

  @Test
  void theStateOfTheBodyIsKept() {
    final Exception result = component.manageException(400, body().put("sqlState", "23001").toString(), "command");

    assertRebuilt(result, "23001");
  }

  @Test
  void aStreamedErrorLineKeepsTheStateToo() {
    final RuntimeException result = component.manageStreamedError(
        new JSONObject().put("status", 400).put("exception", ForeignKeyException.class.getName()).put("message", "refused")
            .put("sqlState", "2BP01"), "query");

    assertRebuilt(result, "2BP01");
  }

  @Test
  void anAnswerWithoutAStateIsAViolatedKey() {
    assertRebuilt(component.manageException(400, body().toString(), "command"), "23503");
  }

  private static JSONObject body() {
    return new JSONObject().put("error", "Constraint violated (FOREIGN_KEY)").put("detail", "refused")
        .put("exception", ForeignKeyException.class.getName()).put("exceptionArgs", ValidationException.Kind.FOREIGN_KEY.name());
  }

  private static void assertRebuilt(final Exception result, final String sqlState) {
    assertThat(result).isExactlyInstanceOf(ForeignKeyException.class).hasMessage("refused");
    assertThat(((ForeignKeyException) result).getSqlState()).isEqualTo(sqlState);
    assertThat(((ForeignKeyException) result).getKind()).isEqualTo(ValidationException.Kind.FOREIGN_KEY);
  }
}
