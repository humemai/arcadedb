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
package com.arcadedb.query.postgresql.plan;

import com.arcadedb.exception.ConcurrentModificationException;
import com.arcadedb.exception.DuplicatedKeyException;
import com.arcadedb.exception.ValidationException;
import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

/**
 * The errors of the engine that a write can meet, as the SQLSTATE a PostgreSQL client expects. An exception of this language
 * passes unchanged; the exceptions of the engine become, each with the engine's exception as its cause (so that every protocol
 * classifies it as it does for the other languages: a conflict, a retry, an invalid value):
 *
 * <table>
 * <caption>Engine exceptions and SQLSTATEs</caption>
 * <tr><td>{@code DuplicatedKeyException}</td><td>{@code 23505} unique_violation</td></tr>
 * <tr><td>{@code ConcurrentModificationException}</td><td>{@code 40001} serialization_failure: a client retries</td></tr>
 * <tr><td>{@code ValidationException} MANDATORY, NOT_NULL</td><td>{@code 23502} not_null_violation</td></tr>
 * <tr><td>{@code ValidationException} MIN, MAX, REGEXP, READONLY</td><td>{@code 23514} check_violation</td></tr>
 * <tr><td>{@code ValidationException} MAX_LENGTH</td><td>{@code 22001} string_data_right_truncation</td></tr>
 * <tr><td>{@code ValidationException} TYPE</td><td>{@code 42804} datatype_mismatch</td></tr>
 * <tr><td>{@code ValidationException} ENCODING</td><td>{@code 22021} character_not_in_repertoire</td></tr>
 * </table>
 */
public final class WriteErrors {
  private WriteErrors() {
  }

  /** @return the exception a client sees for an exception of the engine, see the class comment */
  public static RuntimeException translate(final RuntimeException error) {
    if (error.getClass().getName().startsWith("com.arcadedb.query.postgresql."))
      return error;
    if (error instanceof DuplicatedKeyException duplicate)
      return new PostgreSQLEvaluationException("23505",
          "duplicate key value violates unique constraint \"" + duplicate.getIndexName() + "\": key " + duplicate.getKeys()
              + " already exists", duplicate);
    if (error instanceof ConcurrentModificationException)
      return new PostgreSQLEvaluationException("40001", "could not serialize access due to concurrent update: " + error.getMessage(),
          error);
    if (error instanceof ValidationException validation)
      return new PostgreSQLEvaluationException(validationState(validation.getKind()), validation.getMessage(), validation);
    return error;
  }

  static String validationState(final ValidationException.Kind kind) {
    if (kind == null)
      return "23000";
    return switch (kind) {
      case MANDATORY, NOT_NULL -> "23502";
      case MIN, MAX, REGEXP, READONLY -> "23514";
      case MAX_LENGTH -> "22001";
      case TYPE -> "42804";
      case ENCODING -> "22021";
    };
  }
}
