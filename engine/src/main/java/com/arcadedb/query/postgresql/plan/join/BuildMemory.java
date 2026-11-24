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
package com.arcadedb.query.postgresql.plan.join;

import com.arcadedb.query.postgresql.eval.PostgreSQLEvaluationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The memory cap of the rows a join keeps while it runs: the build side of a hash join and the right side of a nested loop. Each
 * row that is kept is charged an estimate of its size in bytes; when the total goes over the cap the join stops with SQLSTATE
 * {@code 53200} ({@code out_of_memory}) and a hint, and never answers from part of its input. There is no spill to disk in this
 * step: a join that does not fit is refused, the way PostgreSQL refuses when it has no memory left.
 *
 * <p>The cap of a planner made without one is a share of the heap of the JVM, {@link #defaultCapBytes}: a sixteenth of the maximum
 * heap, never under {@link #FLOOR_CAP_BYTES} and never over {@link #CEILING_CAP_BYTES}, or the number of MiB in the JVM property
 * {@link #CAP_PROPERTY}. It is the cap of each operator that keeps rows (each hash join build, derived table, common table
 * expression, set operation and subquery key set), not of the statement, and the estimate it counts is about 1.5 times the real
 * heap of the rows (fork issue #50 measured 205 estimated against 137 real bytes for the two-column rows of a friendship list).
 * The engine's {@code GlobalConfiguration} has no setting for the memory of a query operator (the two memory settings it has
 * belong to the {@code FACETS} counters and to the openCypher algorithm procedures), so the planner takes the cap as a
 * constructor argument.
 *
 * <p>The estimate is the size of the Java objects of one row: the array, the reference of each value, and the object behind it
 * (a string by its length, a number by its class), plus a fixed share for the hash table entry. It is an estimate, kept on the
 * safe side, not a measurement.
 */
public final class BuildMemory {
  /** The least that the default cap is, whatever the heap: 64 MiB of estimated row bytes. */
  public static final long FLOOR_CAP_BYTES = 64L * 1024 * 1024;

  /** The most that the default cap is, whatever the heap: 1 GiB of estimated row bytes. */
  public static final long CEILING_CAP_BYTES = 1024L * 1024 * 1024;

  /** The default cap is one part in this many of the maximum heap of the JVM. */
  public static final int HEAP_SHARE = 16;

  /** The JVM property that raises or lowers the cap of a planner made without an explicit one, in MiB. */
  public static final String CAP_PROPERTY = "arcadedb.postgresql.joinMemoryMb";

  private static final long ROW_OVERHEAD = 16 + 64;

  private final long   cap;
  private final String holder;
  private final String hint;
  private long         used;

  /**
   * The cap of a planner made without one for a JVM with this maximum heap: {@code maxHeapBytes / }{@link #HEAP_SHARE}, between
   * {@link #FLOOR_CAP_BYTES} and {@link #CEILING_CAP_BYTES}. A server with a small heap keeps the floor, a large one is not
   * allowed more than the ceiling per operator. Fork issue #50: the symmetric friendship list of LDBC SF1 (361,246 rows of two
   * record ids) is 74 million estimated bytes, over the 64 MiB that was the constant default, so every statement that read it as a
   * {@code WITH} stopped with {@code 53200}; a heap of 2 GiB gives it 128 MiB.
   */
  public static long defaultCapBytes(final long maxHeapBytes) {
    return Math.max(FLOOR_CAP_BYTES, Math.min(CEILING_CAP_BYTES, maxHeapBytes / HEAP_SHARE));
  }

  /**
   * The cap of a planner made without one: {@link #defaultCapBytes} of the maximum heap of this JVM, or the number of MiB in the
   * JVM property {@link #CAP_PROPERTY}. TPC-H at scale factor 1 builds the 200000 rows of {@code part}, about 95 MiB by the
   * estimate below, so the timed run of fork issue #13 sets it; a value that is not a positive number is an error, never a silent
   * default.
   */
  public static long configuredCapBytes() {
    final String value = System.getProperty(CAP_PROPERTY);
    if (value == null || value.isBlank())
      return defaultCapBytes(Runtime.getRuntime().maxMemory());
    try {
      final long mebibytes = Long.parseLong(value.strip());
      if (mebibytes > 0 && mebibytes <= Long.MAX_VALUE / (1024L * 1024L))
        return mebibytes * 1024L * 1024L;
    } catch (final NumberFormatException e) {
      // reported below
    }
    throw new IllegalArgumentException("The JVM property " + CAP_PROPERTY + " is " + value + ": a positive number of MiB is expected");
  }

  /** The cap of one join. */
  public BuildMemory(final long capBytes) {
    this(capBytes, "a join", "the join does not spill to disk; narrow the smaller side with a WHERE condition on its own columns, "
        + "join on a column that has an index, or raise the cap with the JVM property " + CAP_PROPERTY + " (MiB)");
  }

  /**
   * The cap of any other step that keeps rows: a derived table, a common table expression or a set operation.
   *
   * @param holder what holds the rows, for the message: {@code a derived table}
   * @param hint   what to do about it, for the message
   */
  public BuildMemory(final long capBytes, final String holder, final String hint) {
    this.cap = capBytes;
    this.holder = holder;
    this.hint = hint;
  }

  public long cap() {
    return cap;
  }

  public long used() {
    return used;
  }

  /**
   * Charges one kept row.
   *
   * @throws PostgreSQLEvaluationException with SQLSTATE {@code 53200} when the rows kept so far are over the cap
   */
  public void charge(final Object[] row) {
    chargeBytes(sizeOf(row));
  }

  /**
   * Charges bytes that a step keeps besides its rows (the matched flag of a build row of a {@code FULL} join).
   *
   * @throws PostgreSQLEvaluationException with SQLSTATE {@code 53200} when the bytes kept so far are over the cap
   */
  public void chargeBytes(final long bytes) {
    used += bytes;
    if (used > cap)
      throw new PostgreSQLEvaluationException("53200",
          "out of memory: " + holder + " holds more than " + cap + " bytes of rows in memory (estimated). Hint: " + hint);
  }

  /** The estimated size in bytes of one row, with the share of the hash table entry that holds it. */
  static long sizeOf(final Object[] row) {
    long bytes = ROW_OVERHEAD + 8L * row.length;
    for (final Object value : row)
      bytes += sizeOf(value);
    return bytes;
  }

  private static long sizeOf(final Object value) {
    if (value == null)
      return 0;
    if (value instanceof String text)
      return 40 + 2L * text.length();
    if (value instanceof Integer || value instanceof Short || value instanceof Boolean || value instanceof Float)
      return 16;
    if (value instanceof Long || value instanceof Double || value instanceof LocalDate)
      return 24;
    if (value instanceof BigDecimal)
      return 72;
    if (value instanceof LocalDateTime)
      return 48;
    return 256;
  }
}
