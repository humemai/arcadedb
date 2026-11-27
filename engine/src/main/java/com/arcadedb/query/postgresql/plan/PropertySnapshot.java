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

import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Property;
import com.arcadedb.schema.Type;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a property declares, so that it can be made again, under its name or another one: {@code DROP COLUMN} that is taken back
 * and {@code RENAME COLUMN} (fork issue #230).
 */
record PropertySnapshot(Type type, String ofType, boolean mandatory, boolean notNull, boolean readOnly, Object defaultDefinition,
    String min, String max, String regexp, Map<String, Object> custom) {

  static PropertySnapshot of(final Property property) {
    final Map<String, Object> custom = new LinkedHashMap<>();
    for (final String key : property.getCustomKeys())
      custom.put(key, property.getCustomValue(key));
    return new PropertySnapshot(property.getType(), property.getOfType(), property.isMandatory(), property.isNotNull(),
        property.isReadonly(), property.getDefaultValueDefinition(), property.getMin(), property.getMax(), property.getRegexp(),
        custom);
  }

  /** The same property that a record may lack, which is how it is while the records get their values. */
  PropertySnapshot withoutNotNull() {
    return new PropertySnapshot(type, ofType, false, false, readOnly, defaultDefinition, min, max, regexp, custom);
  }

  /** Makes the property on the type. */
  void create(final DocumentType declaring, final String name) {
    final Property property = ofType == null ? declaring.createProperty(name, type) : declaring.createProperty(name, type, ofType);
    custom.forEach(property::setCustomValue);
    if (min != null)
      property.setMin(min);
    if (max != null)
      property.setMax(max);
    if (regexp != null)
      property.setRegexp(regexp);
    if (defaultDefinition != null)
      property.setDefaultValue(defaultDefinition);
    if (mandatory)
      property.setMandatory(true);
    if (notNull)
      property.setNotNull(true);
    if (readOnly)
      property.setReadonly(true);
  }
}
