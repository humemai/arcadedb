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
package com.arcadedb.query.postgresql.eval;

import com.arcadedb.exception.AlreadyExistsException;
import com.arcadedb.exception.CommandExecutionException;

/**
 * An error that PostgreSQL would raise while it types or evaluates an expression, with the SQLSTATE PostgreSQL gives it, so a
 * client sees the same code. The catalog errors (unknown column or table) and the refusal of a statement outside the subset
 * have their own classes in {@code com.arcadedb.query.postgresql}; they carry a SQLSTATE the same way, through
 * {@link #getSqlState()}.
 * <p>
 * An error of this class is the statement's, a client error on every protocol ({@link com.arcadedb.exception.ErrorCategory}),
 * except a refusal for resources, see {@link #isStatementError()}. One that stands for an exception of the engine (a duplicate
 * key, a concurrent update, a violated constraint, a taken name) keeps it as its cause, so it is classified as that exception
 * is.
 *
 * <table>
 * <caption>The SQLSTATEs of this class</caption>
 * <tr><td>{@code 22003}</td><td>numeric_value_out_of_range: integer overflow, {@code numeric} field overflow, float overflow</td></tr>
 * <tr><td>{@code 22012}</td><td>division_by_zero</td></tr>
 * <tr><td>{@code 22P02}</td><td>invalid_text_representation: {@code 'abc'::integer}, {@code age = 'x'}</td></tr>
 * <tr><td>{@code 22007}, {@code 22008}</td><td>invalid_datetime_format, datetime_field_overflow</td></tr>
 * <tr><td>{@code 22011}, {@code 22019}, {@code 22025}</td><td>substring error, invalid escape character, invalid escape sequence</td></tr>
 * <tr><td>{@code 2201F}</td><td>invalid_argument_for_power_function</td></tr>
 * <tr><td>{@code 2201W}, {@code 2201X}</td><td>invalid_row_count_in_limit_clause, invalid_row_count_in_result_offset_clause</td></tr>
 * <tr><td>{@code 42883}</td><td>undefined_function: no operator or function takes these types</td></tr>
 * <tr><td>{@code 42804}</td><td>datatype_mismatch: a {@code WHERE} that is not boolean, a {@code CASE} with unmatched branches</td></tr>
 * <tr><td>{@code 42846}</td><td>cannot_coerce: no cast between the two types</td></tr>
 * <tr><td>{@code 42725}</td><td>ambiguous_function: {@code unknown + unknown}</td></tr>
 * <tr><td>{@code 42803}</td><td>grouping_error</td></tr>
 * <tr><td>{@code 42P02}</td><td>undefined_parameter: {@code $2} with one value bound</td></tr>
 * <tr><td>{@code 42P07}, {@code 42710}</td><td>duplicate_table, duplicate_object: a relation or a constraint under a taken
 * name</td></tr>
 * <tr><td>{@code 0A000}</td><td>feature_not_supported: a type or function outside the subset</td></tr>
 * </table>
 */
public class PostgreSQLEvaluationException extends CommandExecutionException {
  private final String sqlState;

  public PostgreSQLEvaluationException(final String sqlState, final String message) {
    super(message);
    this.sqlState = sqlState;
  }

  /** An error that stands for an exception of the engine, kept as its cause. */
  public PostgreSQLEvaluationException(final String sqlState, final String message, final Throwable cause) {
    super(message, cause);
    this.sqlState = sqlState;
  }

  public String getSqlState() {
    return sqlState;
  }

  /**
   * Whether the statement, or the data it was given, is at fault. Every state is, but those of the classes {@code 53}
   * (insufficient resources: a step over its memory cap) and {@code XX} (internal error), which keep the verdict of a server
   * fault, as a query of another language that alone needs more heap than there is does.
   */
  public boolean isStatementError() {
    return !sqlState.startsWith("53") && !sqlState.startsWith("XX");
  }

  /** {@code 22003}: {@code integer out of range}, {@code bigint out of range}, {@code smallint out of range}. */
  public static PostgreSQLEvaluationException outOfRange(final PgType type) {
    return new PostgreSQLEvaluationException("22003", type.displayName() + " out of range");
  }

  public static PostgreSQLEvaluationException outOfRange(final String text, final PgType type) {
    return new PostgreSQLEvaluationException("22003",
        "value \"" + text + "\" is out of range for type " + type.displayName());
  }

  public static PostgreSQLEvaluationException divisionByZero() {
    return new PostgreSQLEvaluationException("22012", "division by zero");
  }

  /** {@code 22P02}: the text is not a value of the type. */
  public static PostgreSQLEvaluationException invalidText(final PgType type, final String text) {
    return new PostgreSQLEvaluationException("22P02", "invalid input syntax for type " + type.displayName() + ": \"" + text + "\"");
  }

  /** {@code 42883}: {@code operator does not exist: integer = text}. */
  public static PostgreSQLEvaluationException noOperator(final String operator, final PgType left, final PgType right) {
    return new PostgreSQLEvaluationException("42883", "operator does not exist: " + left.displayName() + " " + operator + " "
        + right.displayName() + ". Hint: No operator matches the given name and argument types. You might need to add explicit "
        + "type casts.");
  }

  public static PostgreSQLEvaluationException noOperator(final String operator, final PgType operand) {
    return new PostgreSQLEvaluationException("42883", "operator does not exist: " + operator + " " + operand.displayName()
        + ". Hint: No operator matches the given name and argument types. You might need to add explicit type casts.");
  }

  /** {@code 42883}: {@code function sum(text) does not exist}. */
  public static PostgreSQLEvaluationException noFunction(final String name, final PgType... arguments) {
    final StringBuilder out = new StringBuilder("function ").append(name).append('(');
    for (int i = 0; i < arguments.length; i++)
      out.append(i > 0 ? ", " : "").append(arguments[i].displayName());
    return new PostgreSQLEvaluationException("42883", out.append(") does not exist. Hint: No function matches the given name "
        + "and argument types. You might need to add explicit type casts.").toString());
  }

  /**
   * {@code 42P07}: {@code relation "x" already exists}.
   *
   * @param holder the kind of object that holds the name
   */
  public static PostgreSQLEvaluationException duplicateRelation(final AlreadyExistsException.Kind holder, final String name) {
    return taken("42P07", new AlreadyExistsException(holder, "relation \"" + name + "\" already exists"));
  }

  /** {@code 42710}: {@code constraint "x" for relation "y" already exists}. */
  public static PostgreSQLEvaluationException duplicateConstraint(final String name, final String table) {
    return taken("42710", new AlreadyExistsException(AlreadyExistsException.Kind.CONSTRAINT,
        "constraint \"" + name + "\" for relation \"" + table + "\" already exists"));
  }

  /** A taken name keeps the engine's exception for it as its cause: every protocol answers that one as a conflict. */
  private static PostgreSQLEvaluationException taken(final String sqlState, final AlreadyExistsException cause) {
    return new PostgreSQLEvaluationException(sqlState, cause.getMessage(), cause);
  }

  /** {@code 42804}. */
  public static PostgreSQLEvaluationException mismatch(final String message) {
    return new PostgreSQLEvaluationException("42804", message);
  }

  /** {@code 0A000}. */
  public static PostgreSQLEvaluationException unsupported(final String message) {
    return new PostgreSQLEvaluationException("0A000", message);
  }
}
