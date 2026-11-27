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
package com.arcadedb.remote.grpc;

import com.arcadedb.exception.ForeignKeyException;
import com.arcadedb.server.grpc.GrpcErrorMapper;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fork issue #228: a {@link ForeignKeyException} crosses gRPC with its type and its SQLSTATE, from the server's mapper to the
 * client's.
 */
class ForeignKeyGrpcClientErrorTest {

  @Test
  void theStateCrossesTheWire() {
    final RuntimeException rebuilt = GrpcClientErrorMapper.toException(
        GrpcErrorMapper.toStatusRuntimeException(new ForeignKeyException("23001", "restricted"), null));

    assertThat(rebuilt).isExactlyInstanceOf(ForeignKeyException.class).hasMessageContaining("restricted");
    assertThat(((ForeignKeyException) rebuilt).getSqlState()).isEqualTo("23001");
  }

  @Test
  void theStateOfACheckConstraintCrossesTheWire() {
    final RuntimeException rebuilt = GrpcClientErrorMapper.toException(GrpcErrorMapper.toStatusRuntimeException(
        new com.arcadedb.exception.CheckConstraintException("22012", "division by zero"), null));

    assertThat(rebuilt).isExactlyInstanceOf(com.arcadedb.exception.CheckConstraintException.class)
        .hasMessageContaining("division by zero");
    assertThat(((com.arcadedb.exception.CheckConstraintException) rebuilt).getSqlState()).isEqualTo("22012");
  }

  @Test
  void aServerThatSendsNoStateRebuildsAViolatedKey() {
    final io.grpc.Metadata trailers = new io.grpc.Metadata();
    trailers.put(GrpcClientErrorMapper.EXCEPTION_CLASS_KEY, ForeignKeyException.class.getName());

    final RuntimeException rebuilt = GrpcClientErrorMapper.toException(
        Status.INVALID_ARGUMENT.withDescription("refused").asRuntimeException(trailers));

    assertThat(rebuilt).isExactlyInstanceOf(ForeignKeyException.class);
    assertThat(((ForeignKeyException) rebuilt).getSqlState()).isEqualTo("23503");
  }
}
