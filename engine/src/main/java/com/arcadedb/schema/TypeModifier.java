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
package com.arcadedb.schema;

import com.arcadedb.database.MutableDocument;
import com.arcadedb.exception.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The length of a {@code varchar(n)} column, or the precision and scale of a {@code numeric(p,s)} column (fork issue #226).
 * {@code CREATE TABLE} of the {@code postgresql} language keeps it in the custom value {@value #CUSTOM_KEY} of the property,
 * {@code n} on a {@code STRING} property and {@code p,s} on a {@code DECIMAL} one, and the engine applies it to every record that
 * any language creates or updates, as PostgreSQL's assignment casts do (see {@link #assign}). Records that exist when the custom
 * value is set are not changed or checked until they are written again. {@code ALTER PROPERTY t.c CUSTOM `arcadedb.typeModifier`
 * = null} of the {@code sql} language removes the limit.
 *
 * @param size  the length of a string in characters, or the precision of a number
 * @param scale the scale of a number, {@code null} for the length of a string
 */
public record TypeModifier(int size, Integer scale) {
  /** The key of the custom value of a property, under the engine's prefix as {@link ForeignKey#CUSTOM_KEY}. */
  public static final String CUSTOM_KEY = "arcadedb.typeModifier";

  /** The longest {@code varchar(n)} of PostgreSQL. */
  private static final int MAX_LENGTH    = 10 * 1024 * 1024;
  /** The largest precision of a {@code numeric}, and the largest scale on either side of zero. */
  private static final int MAX_PRECISION = 1000;

  /** The length of a {@code varchar(n)}. */
  public static TypeModifier length(final int length) {
    return new TypeModifier(length, null);
  }

  /** The precision and scale of a {@code numeric(p,s)}. */
  public static TypeModifier numeric(final int precision, final int scale) {
    return new TypeModifier(precision, scale);
  }

  /** The modifier that the property declares, {@code null} when it declares none or one that cannot be applied. */
  public static TypeModifier of(final Property property) {
    final Object declared = property.getCustomValue(CUSTOM_KEY);
    if (declared == null)
      return null;
    final String[] parts = declared.toString().split(",", -1);
    try {
      if (property.getType() == Type.STRING && parts.length == 1) {
        final int length = Integer.parseInt(parts[0].trim());
        return length >= 1 && length <= MAX_LENGTH ? length(length) : null;
      }
      if (property.getType() == Type.DECIMAL && parts.length == 2) {
        final int precision = Integer.parseInt(parts[0].trim());
        final int scale = Integer.parseInt(parts[1].trim());
        return precision >= 1 && precision <= MAX_PRECISION && Math.abs((long) scale) <= MAX_PRECISION ?
            numeric(precision, scale) : null;
      }
      return null;
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  /** Whether the modifier is the precision and scale of a number. */
  public boolean isNumeric() {
    return scale != null;
  }

  /** The custom value that declares this modifier. */
  public String declaration() {
    return scale == null ? Integer.toString(size) : size + "," + scale;
  }

  /**
   * The value that a column with this modifier holds when {@code value} is assigned to it. A number is rounded to the scale, half
   * away from zero. A string that is too long is cut when all the characters over the limit are spaces. A value of another class,
   * and {@code null}, are returned as they are.
   *
   * @throws ValidationException {@link ValidationException.Kind#OUT_OF_RANGE} for a number with more than {@code p - s} digits
   *                             before the point once rounded, {@link ValidationException.Kind#MAX_LENGTH} for a string that is
   *                             too long; the messages are PostgreSQL's
   */
  public Object assign(final Object value) {
    if (scale == null)
      return value instanceof String text ? cut(text) : value;
    return value instanceof BigDecimal number ? round(number) : value;
  }

  private String cut(final String text) {
    if (text.length() <= size || text.codePointCount(0, text.length()) <= size)
      return text;
    final int end = text.offsetByCodePoints(0, size);
    for (int i = end; i < text.length(); i++)
      if (text.charAt(i) != ' ')
        throw new ValidationException(ValidationException.Kind.MAX_LENGTH,
            "value too long for type character varying(" + size + ")");
    return text.substring(0, end);
  }

  private BigDecimal round(final BigDecimal number) {
    // the digits before the point, zero or negative for a number under one; known without computing with the number
    final long digits = (long) number.precision() - number.scale();
    // rounding never takes a digit away from a number that is too large, so it is refused first
    if (number.signum() != 0 && digits > (long) size - scale)
      throw overflow();
    // a number under a tenth of the last place rounds to zero, however far under it is
    final BigDecimal rounded = digits < -(long) scale ? BigDecimal.ZERO.setScale(scale) :
        number.setScale(scale, RoundingMode.HALF_UP);
    if (rounded.signum() != 0 && (long) rounded.precision() - rounded.scale() > (long) size - scale)
      throw overflow();
    // a negative scale rounds left of the point, and the result has no digits after it
    return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
  }

  private ValidationException overflow() {
    return new ValidationException(ValidationException.Kind.OUT_OF_RANGE,
        "numeric field overflow. A field with precision " + size + ", scale " + scale + " must round to an absolute value less than "
            + (size == scale ? "1" : "10^" + (size - scale)));
  }

  /** Applies the modifier that {@code property} declares to the value that the record is about to be written with. */
  public static void apply(final MutableDocument document, final Property property) {
    final Object value = document.get(property.getName());
    if (value == null)
      return;
    final String column = "property '" + document.getTypeName() + "." + property.getName() + "'";
    final TypeModifier modifier = of(property);
    if (modifier == null)
      throw new ValidationException(ValidationException.Kind.TYPE,
          "The custom value " + CUSTOM_KEY + " of " + column + " cannot be applied: remove it with ALTER PROPERTY `"
              + document.getTypeName() + "`.`" + property.getName() + "` CUSTOM `" + CUSTOM_KEY + "` = null");
    final Object assigned;
    try {
      assigned = modifier.assign(value);
    } catch (final ValidationException e) {
      throw new ValidationException(e.getKind(), e.getMessage() + " (" + column + ")");
    }
    // equals of BigDecimal compares the scale too, which is what changes
    if (!assigned.equals(value))
      document.set(property.getName(), assigned);
  }
}
