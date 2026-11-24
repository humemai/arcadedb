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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cap of a join that a planner takes when it is made without one: a share of the heap, or the JVM property in MiB. */
class BuildMemoryTest {

  @Test
  void theCapIsAShareOfTheHeapUnlessThePropertyIsSet() {
    final String before = System.getProperty(BuildMemory.CAP_PROPERTY);
    try {
      System.clearProperty(BuildMemory.CAP_PROPERTY);
      assertThat(BuildMemory.configuredCapBytes()).isEqualTo(BuildMemory.defaultCapBytes(Runtime.getRuntime().maxMemory()));
      System.setProperty(BuildMemory.CAP_PROPERTY, "512");
      assertThat(BuildMemory.configuredCapBytes()).isEqualTo(512L * 1024 * 1024);
      System.setProperty(BuildMemory.CAP_PROPERTY, "x");
      assertThatThrownBy(BuildMemory::configuredCapBytes).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("x");
      System.setProperty(BuildMemory.CAP_PROPERTY, "0");
      assertThatThrownBy(BuildMemory::configuredCapBytes).isInstanceOf(IllegalArgumentException.class);
    } finally {
      if (before == null)
        System.clearProperty(BuildMemory.CAP_PROPERTY);
      else
        System.setProperty(BuildMemory.CAP_PROPERTY, before);
    }
  }
  private static final long MIB = 1024L * 1024;

  @Test
  void theDefaultIsASixteenthOfTheHeapBetweenTheFloorAndTheCeiling() {
    assertThat(BuildMemory.defaultCapBytes(256 * MIB)).as("a small heap keeps the floor").isEqualTo(64 * MIB);
    assertThat(BuildMemory.defaultCapBytes(1024 * MIB)).isEqualTo(64 * MIB);
    assertThat(BuildMemory.defaultCapBytes(2048 * MIB)).isEqualTo(128 * MIB);
    assertThat(BuildMemory.defaultCapBytes(4096 * MIB)).isEqualTo(256 * MIB);
    assertThat(BuildMemory.defaultCapBytes(64 * 1024 * MIB)).as("a large heap does not raise it past the ceiling").isEqualTo(1024 * MIB);
    assertThat(BuildMemory.defaultCapBytes(Long.MAX_VALUE)).as("a JVM without a limit").isEqualTo(BuildMemory.CEILING_CAP_BYTES);
    assertThat(BuildMemory.defaultCapBytes(0)).isEqualTo(BuildMemory.FLOOR_CAP_BYTES);
  }

  @Test
  void aRunawayStatementStillStopsAtTheDefaultCap() {
    final String before = System.getProperty(BuildMemory.CAP_PROPERTY);
    try {
      System.clearProperty(BuildMemory.CAP_PROPERTY);
      final long cap = BuildMemory.configuredCapBytes();
      assertThat(cap).isBetween(BuildMemory.FLOOR_CAP_BYTES, BuildMemory.CEILING_CAP_BYTES);
      final BuildMemory memory = new BuildMemory(cap);
      final Object[] row = { "#1:0", "#1:1" };
      final long perRow = BuildMemory.sizeOf(row);
      long kept = 0;
      try {
        while (kept <= cap / perRow + 1) {
          memory.charge(row);
          kept++;
        }
        throw new AssertionError("the cap did not stop " + kept + " rows");
      } catch (final PostgreSQLEvaluationException e) {
        assertThat(e.getSqlState()).isEqualTo("53200");
        assertThat(kept * perRow).as("it stops at the cap, not before").isGreaterThan(cap - perRow).isLessThanOrEqualTo(cap);
      }
    } finally {
      if (before != null)
        System.setProperty(BuildMemory.CAP_PROPERTY, before);
    }
  }
}
