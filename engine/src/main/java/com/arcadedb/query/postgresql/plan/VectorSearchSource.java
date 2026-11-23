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

import com.arcadedb.database.Database;
import com.arcadedb.database.Document;
import com.arcadedb.database.RID;
import com.arcadedb.database.Record;
import com.arcadedb.index.IndexInternal;
import com.arcadedb.index.TypeIndex;
import com.arcadedb.index.vector.LSMVectorIndex;
import com.arcadedb.query.postgresql.ast.Expression;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.catalog.ColumnType;
import com.arcadedb.query.postgresql.catalog.Table;
import com.arcadedb.query.postgresql.eval.Casts;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.eval.VectorDistance;
import com.arcadedb.schema.Schema;
import com.arcadedb.utility.Pair;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The ordered nearest-neighbour access path: {@code SELECT ... FROM t ORDER BY embedding <-> $1 LIMIT k} reads the {@code k}
 * candidates from the {@code LSM_VECTOR} index of {@code t[embedding]} ({@code LSMVectorIndex.findNeighborsFromVector}) instead
 * of scanning the table. The sort and the limit stay in the plan above this source, so the projected distances and the final
 * order are the exact ones of {@link VectorDistance}: the index only decides which rows are candidates.
 *
 * <p><b>When it is chosen</b> ({@link #choose}): the statement orders by exactly one distance of a vector column and a constant,
 * ascending, has a constant {@code LIMIT}, no {@code WHERE}, grouping or {@code DISTINCT}, reads one table without subtypes with
 * a plain scan, the column has an {@code LSM_VECTOR} index of its own, and the metric of the index is the one of the operator
 * ({@code EUCLIDEAN} for {@code <->}, {@code COSINE} for {@code <=>}, {@code DOT_PRODUCT} for {@code <#>}). In every other case the
 * table is scanned and sorted, and the plan says why ({@code SCAN + SORT exact distance (<reason>)}).
 *
 * <p><b>Exactness.</b> An {@code LSM_VECTOR} index is an approximate nearest-neighbour graph: its top {@code k} can miss a true
 * neighbour (recall below 1), so an index answer can differ from the exact one, which is also what pgvector says of its
 * approximate indexes. Two rules keep the rest exact: an index that returns fewer than {@code k} rows (fewer indexed vectors
 * than {@code k}, {@code NULL} vectors and, for {@code COSINE}, zero vectors are not indexed) is not trusted and the table is
 * scanned, and a {@code WHERE} clause is never combined with the index (the statement is an exact scan). The plan names the
 * index search from the statement alone; the fallback to the scan is decided when the rows are read.
 */
public final class VectorSearchSource implements RelationSource {

  /** The outcome of {@link #choose}: a source, or the reason why the table is scanned. */
  public record Choice(RelationSource source, String reason) {
  }

  private final Database                 database;
  private final TableSource              table;
  private final Column                   column;
  private final List<LSMVectorIndex>     indexes;
  private final float[]                  query;
  private final int                      k;
  private final String                   metric;
  private final PgType[]                 types;

  private VectorSearchSource(final Database database, final TableSource table, final Column column,
      final List<LSMVectorIndex> indexes, final float[] query, final int k, final String metric) {
    this.database = database;
    this.table = table;
    this.column = column;
    this.indexes = indexes;
    this.query = query;
    this.k = k;
    this.metric = metric;
    this.types = new PgType[table.table().columns().size()];
    for (int i = 0; i < types.length; i++)
      types[i] = PgType.of(table.table().columns().get(i).type());
  }

  /**
   * @param table    the source of the statement, read by a scan
   * @param distance the one key of the {@code ORDER BY}
   * @param limit    the {@code LIMIT} of the statement, {@code null} when there is none
   * @param offset   the {@code OFFSET}, {@code null} when there is none
   */
  public static Choice choose(final Database database, final TableSource table, final VectorDistance distance, final Evaluable limit,
      final Evaluable offset) {
    if (table.access() != TableSource.Access.SCAN)
      return scan("the table is read through another access path");
    final Evaluable columnSide = distance.left() instanceof Evaluable.Slot ? distance.left() : distance.right();
    final Evaluable constantSide = columnSide == distance.left() ? distance.right() : distance.left();
    if (!(columnSide instanceof Evaluable.Slot slot) || !constantSide.isConstant())
      return scan("the distance is not between one vector column and a constant");
    final Table relation = table.table();
    final Column column = relation.columns().get(slot.index());
    if (column.kind() != Column.Kind.PROPERTY || column.type() != ColumnType.VECTOR)
      return scan("the column is not a vector property");
    if (limit == null || !limit.isConstant())
      return scan("no constant LIMIT");
    final Object limitValue = limit.eval(new Object[0]);
    final Object offsetValue = offset == null ? Long.valueOf(0) : offset.eval(new Object[0]);
    if (!(limitValue instanceof Long count) || !(offsetValue instanceof Long skip) || count <= 0 || skip < 0
        || count + skip > Integer.MAX_VALUE)
      return scan("the LIMIT is NULL or not positive");
    final float[] query = (float[]) constantSide.eval(new Object[0]);
    if (query == null)
      return scan("the query vector is NULL");
    if (!relation.subTables().isEmpty() && !table.only())
      return scan("the table has subtypes");

    final String name = relation.name() + "[" + column.property() + "]";
    final TypeIndex typeIndex = relation.documentType().getIndexByProperties(column.property());
    if (typeIndex == null)
      return scan("no index on " + name);
    if (typeIndex.getType() != Schema.INDEX_TYPE.LSM_VECTOR)
      return scan("the index on " + name + " is not an LSM_VECTOR index");
    final List<LSMVectorIndex> indexes = new ArrayList<>();
    for (final IndexInternal sub : typeIndex.getIndexesOnBuckets())
      if (sub instanceof LSMVectorIndex vector)
        indexes.add(vector);
    if (indexes.isEmpty())
      return scan("the index on " + name + " has no vector index per bucket");

    final String wanted = metricOf(distance.operator());
    final String actual = indexes.get(0).getSimilarityFunction().name();
    for (final LSMVectorIndex index : indexes)
      if (!index.getSimilarityFunction().name().equals(wanted))
        return scan("the index on " + name + " uses " + actual + ", the operator " + distance.operator().symbol() + " needs "
            + wanted);
    if (indexes.get(0).getDimensions() != query.length)
      return scan("the index on " + name + " has " + indexes.get(0).getDimensions() + " dimensions, the query vector "
          + query.length);
    return new Choice(new VectorSearchSource(database, table, column, indexes, query, (int) (count + skip), wanted), null);
  }

  private static Choice scan(final String reason) {
    return new Choice(null, reason);
  }

  /** The metric name of the index that answers the operator exactly in order. */
  static String metricOf(final Expression.BinaryOperator operator) {
    return switch (operator) {
      case L2_DISTANCE -> "EUCLIDEAN";
      case COSINE_DISTANCE -> "COSINE";
      case NEGATIVE_INNER_PRODUCT -> "DOT_PRODUCT";
      default -> throw new IllegalArgumentException(operator.symbol());
    };
  }

  @Override
  public List<Field> columns() {
    return table.columns();
  }

  @Override
  public String describe() {
    return "VECTOR INDEX SEARCH " + table.table().name() + "[" + column.property() + "] k=" + k + " (" + metric + ")";
  }

  @Override
  public Iterator<Object[]> open() {
    final List<Pair<RID, Float>> candidates = new ArrayList<>();
    try {
      for (final LSMVectorIndex index : indexes)
        candidates.addAll(index.findNeighborsFromVector(query, k));
    } catch (final IllegalArgumentException e) {
      // a query vector the metric cannot score (the zero vector under COSINE): the exact distance of the operator is NaN for it
      return table.open();
    }
    candidates.sort((a, b) -> Float.compare(a.getSecond(), b.getSecond()));

    final List<Object[]> rows = new ArrayList<>();
    for (final Pair<RID, Float> candidate : candidates) {
      if (rows.size() == k)
        break;
      final Record record;
      try {
        record = database.lookupByRID(candidate.getFirst(), true);
      } catch (final RuntimeException e) {
        // a record deleted after the index answered
        continue;
      }
      if (record instanceof Document document)
        rows.add(row(document));
    }
    // fewer candidates than asked for: the index does not hold every row (NULL and zero vectors are not indexed), so the answer
    // is the exact scan, which the sort and the limit above this source turn into the same statement
    if (rows.size() < k)
      return table.open();
    return rows.iterator();
  }

  private Object[] row(final Document record) {
    final List<Column> columns = table.table().columns();
    final Object[] values = new Object[types.length];
    for (int i = 0; i < values.length; i++)
      values[i] = Casts.fromEngine(columns.get(i).read(record), types[i]);
    return values;
  }
}
