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

import com.arcadedb.database.RID;
import com.arcadedb.query.opencypher.temporal.CypherDuration;
import com.arcadedb.serializer.json.JSONArray;
import com.arcadedb.serializer.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fork issue #341: each parameter that a replica sends to the leader arrives with its class and its value, or is refused. */
class ForwardedParametersTest {
  @Test
  void everyValueArrivesWithItsClass() {
    final LocalDateTime time = LocalDateTime.of(2026, 11, 29, 10, 11, 12, 123_456_789);
    final Object[] values = { null, new UnknownText("changed"), new JsonbText("{\"k\": 1}"), "text", "", 'c', true, (byte) -1,
        (short) 5, 7, 5L, new BigInteger("123456789012345678901234567890"), new BigDecimal("10.50"), new BigDecimal("-1E-30"), 0.1f,
        Float.NaN, Float.NEGATIVE_INFINITY, -0.0f, 0.1d, Double.NaN, Double.POSITIVE_INFINITY, -0.0d, UUID.randomUUID(),
        LocalDate.of(2026, 11, 29), LocalDate.MAX, time, OffsetDateTime.of(time, ZoneOffset.ofHoursMinutes(5, 30)),
        OffsetDateTime.MIN, ZonedDateTime.of(time, ZoneOffset.UTC), ZonedDateTime.of(time, ZoneId.of("Europe/Paris")),
        Instant.parse("2026-11-29T10:11:12.123456Z"), new Date(1_000L), new java.sql.Date(86_400_000L), new byte[] { 0, 1, -1 },
        new float[] { 0.1f, Float.NaN }, new double[] { 0.1d }, new int[] { 1 }, new long[] { 1L }, new Object[] { null, "a" },
        new String[] { null }, new Boolean[0], new Short[] { null }, new Integer[0], new Long[0], new BigDecimal[0],
        new Float[0], new Double[0], new byte[][] { null }, new LocalDate[0], new LocalDateTime[0], new OffsetDateTime[0],
        List.of(), Arrays.asList((short) 1, null, new BigDecimal("1.50"), List.of(LocalDate.of(2026, 1, 1))),
        Map.of("$bytes", "AA==") };

    final Map<String, Object> decoded = ForwardedParameters.decode(ForwardedParameters.encode(null, values));

    assertThat(decoded).hasSize(values.length);
    for (int i = 0; i < values.length; i++) {
      final Object arrived = decoded.get(Integer.toString(i));
      if (values[i] == null)
        assertThat(arrived).as("parameter %d", i).isNull();
      else if (values[i] instanceof List<?> || values[i] instanceof Map<?, ?>)
        assertThat(arrived).as("parameter %d", i).isEqualTo(values[i]);
      else {
        assertThat(arrived).as("parameter %d", i).hasSameClassAs(values[i]);
        // deepEquals compares the items of an array, and the bits of a float or a double
        assertThat(Arrays.deepEquals(new Object[] { arrived }, new Object[] { values[i] })).as("parameter %d: %s", i, arrived)
            .isTrue();
      }
    }
    assertThat(((List<?>) decoded.get(Integer.toString(values.length - 2))).get(0)).isInstanceOf(Short.class);
  }

  @Test
  void jsonValuesAndNamedParametersArrive() {
    final Map<String, Object> named = new LinkedHashMap<>();
    named.put("object", new JSONObject("{\"k\": [1, null]}"));
    named.put("array", new JSONArray("[1, \"a\"]"));
    named.put("null", JSONObject.NULL);

    final Map<String, Object> decoded = ForwardedParameters.decode(ForwardedParameters.encode(named, null));

    assertThat(decoded).containsOnlyKeys("object", "array", "null");
    assertThat(decoded.get("object")).isInstanceOf(JSONObject.class).hasToString(named.get("object").toString());
    assertThat(decoded.get("array")).isInstanceOf(JSONArray.class).hasToString(named.get("array").toString());
    assertThat(decoded.get("null")).isNull();
  }

  @Test
  void aValueThatTheLanguageHasNoTypeForIsRefusedAsWhereItRuns() {
    for (final Object value : List.of(LocalTime.NOON, OffsetTime.of(LocalTime.NOON, ZoneOffset.UTC),
        new CypherDuration(0, 1, 2, 0), List.of(LocalTime.NOON), new RID("#1:0")))
      assertThatThrownBy(() -> ForwardedParameters.encode(null, new Object[] { "a", value })).as(value.toString())
          .isInstanceOfSatisfying(PostgreSQLEvaluationException.class,
              refused -> assertThat(refused.getMessage()).contains("no type for the value of parameter $2"));
  }

  @Test
  void aTextThatDoesNotSayWhatItHoldsIsRefused() {
    assertThatThrownBy(() -> ForwardedParameters.decode("{\"0\": [\"class\", \"java.lang.Runtime\"]}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ForwardedParameters.decode("{\"0\": [\"array\", \"Runtime\", []]}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ForwardedParameters.decode("{\"0\": [\"bigDecimal\", \"1E+999999999\"]}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
