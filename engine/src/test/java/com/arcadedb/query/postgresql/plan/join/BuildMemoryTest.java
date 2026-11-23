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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The cap of a join that a planner takes when it is made without one: a constant, or the JVM property in MiB. */
class BuildMemoryTest {

  @Test
  void theCapIsTheConstantUnlessThePropertyIsSet() {
    final String before = System.getProperty(BuildMemory.CAP_PROPERTY);
    try {
      System.clearProperty(BuildMemory.CAP_PROPERTY);
      assertThat(BuildMemory.configuredCapBytes()).isEqualTo(BuildMemory.DEFAULT_CAP_BYTES);
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
}
