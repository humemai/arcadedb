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

import com.arcadedb.database.Database;
import com.arcadedb.database.RID;
import com.arcadedb.exception.RecordNotFoundException;
import com.arcadedb.graph.Edge;
import com.arcadedb.graph.Vertex;
import com.arcadedb.query.postgresql.ast.TableReference;
import com.arcadedb.query.postgresql.catalog.Column;
import com.arcadedb.query.postgresql.eval.Evaluable;
import com.arcadedb.query.postgresql.eval.Numbers;
import com.arcadedb.query.postgresql.eval.PgType;
import com.arcadedb.query.postgresql.plan.RelationSource;
import com.arcadedb.query.postgresql.plan.TableSource;
import com.arcadedb.schema.DocumentType;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rows of a join of two sources: the columns of the left source followed by those of the right one, and for each pair of
 * rows that matches, the left row followed by the right row. {@code INNER} keeps the matching pairs; {@code LEFT} also keeps
 * every left row that has no partner, with {@code NULL} in the right columns.
 *
 * <p>Two rows match when every equality key is equal on both sides and the residual condition is {@code true}. A key that is
 * {@code NULL} on either side matches nothing, not even another {@code NULL} ({@code NULL = NULL} is not true), so such a row
 * has no partner, in a {@code LEFT} join it is padded. The values of a key are compared in the common type of its two sides,
 * the type the {@code =} operator resolves to ({@code int4} against {@code int8} is {@code int8}, {@code int4} against
 * {@code numeric} is {@code numeric}), so {@code 1} and {@code 1.0} are one key.
 *
 * <p>Four strategies, chosen by {@link JoinPlanner}:
 * <ul>
 * <li>{@link Strategy#TRAVERSE}: the right input is an edge table joined through {@code source_id} or {@code target_id} to the
 * {@code id} of a vertex on the left (fork issue #8). For each left row the vertex is read by its record id and its stored
 * edges of that edge type are walked ({@code Vertex.getEdges}, the call that openCypher's {@code ExpandAll} makes), so the
 * work follows the degree of the vertex and not the size of the edge type. Each edge gives the row a scan of the edge table
 * gives, so the rows are those of the equality join, parallel edges included.</li>
 * <li>{@link Strategy#HASH}: one input is read once into a hash table keyed on the join key (the build side), the other is read
 * once and probed (the probe side). The build side counts against the memory cap ({@link BuildMemory}); over the cap the join
 * stops with SQLSTATE {@code 53200}.</li>
 * <li>{@link Strategy#INDEX_NESTED_LOOP}: for each left row the right table is read through its index (or by record id) on the
 * join key, the {@code FETCH FROM INDEX} and {@code READ RECORD BY ID} paths of the single-table plan. Nothing is built.</li>
 * <li>{@link Strategy#NESTED_LOOP}: the right input is read into memory (under the same cap) and every left row is compared with
 * every right row. It is the strategy of a join with no equality between the two sides.</li>
 * </ul>
 * The order of the rows is the order of the probe side (the left input for every strategy but a hash join that builds on the
 * left), as the order of an unsorted result is not defined.
 */
public final class JoinSource implements RelationSource {

  public enum Strategy {
    HASH, INDEX_NESTED_LOOP, NESTED_LOOP, TRAVERSE
  }

  /**
   * One equality of the {@code ON} condition: {@code left} is an expression over a row of the left input, {@code right} one
   * over a row of the right input, both already cast to {@code type}, the common type of the comparison.
   */
  record Key(Evaluable left, Evaluable right, PgType type) {
  }

  /**
   * How an index nested loop reads the right table for one left row.
   *
   * @param table  the right table, read by a scan until this probe narrows it
   * @param column the column of the right table the lookup uses: a property with an index, or {@code id}
   * @param key    the position in the key list of the equality that supplies the value to look up
   * @param filter a condition on the right rows that was pushed to the right side, {@code null} for none
   */
  record IndexProbe(TableSource table, Column column, int key, Evaluable filter) {
  }

  /**
   * How a traversal reads the edges of one left vertex.
   *
   * @param database    the database, to read the vertex by its record id
   * @param edges       the edge table; whatever access path a pushed term gave it, the walk reads the edges of the vertex
   * @param direction   {@code OUT} when the join key is {@code source_id}, {@code IN} when it is {@code target_id}
   * @param key         the position in the key list of the equality that joins the edge endpoint to the id
   * @param filter      a condition on the edge rows that was pushed to the right side, {@code null} for none
   * @param sourceGuard a type the out vertex of every edge has to be an instance of, {@code null} for none: the scan of an edge
   *                    table that can hold lightweight edges walks the declared source table only, so an incoming walk must
   *                    drop an edge from anywhere else to give the rows of the scan
   * @param from        the left vertex table the walk starts at, for the plan line
   */
  record Traversal(Database database, TableSource edges, Vertex.DIRECTION direction, int key, Evaluable filter,
                   String sourceGuard, TableSource from) {
    String[] edgeTypes() {
      return edges.edgeTypeNames();
    }
  }

  private final Strategy                  strategy;
  private final TableReference.JoinType   type;
  private final RelationSource            left;
  private final RelationSource            right;
  private final List<Key>                 keys;
  private final Evaluable                 residual;
  private final boolean                   buildLeft;
  private final IndexProbe                probe;
  private final Traversal                 traversal;
  private final AtomicLong                edgesRead = new AtomicLong();
  private final long                      capBytes;
  private final String                    header;
  private final List<String>              rightLines;
  private final JoinSupport               support;
  private final String                    warning;
  private final long                      estimatedRows;
  private final List<Field>               columns;

  JoinSource(final Strategy strategy, final TableReference.JoinType type, final RelationSource left, final RelationSource right,
      final List<Key> keys, final Evaluable residual, final boolean buildLeft, final IndexProbe probe, final Traversal traversal,
      final long capBytes, final String header, final List<String> rightLines, final JoinSupport support, final String warning,
      final long estimatedRows) {
    this.strategy = strategy;
    this.type = type;
    this.left = left;
    this.right = right;
    this.keys = List.copyOf(keys);
    this.residual = residual;
    this.buildLeft = buildLeft;
    this.probe = probe;
    this.traversal = traversal;
    this.capBytes = capBytes;
    this.header = header;
    this.rightLines = List.copyOf(rightLines);
    this.support = support;
    this.warning = warning;
    this.estimatedRows = estimatedRows;
    final List<Field> all = new ArrayList<>(left.columns());
    all.addAll(right.columns());
    this.columns = List.copyOf(all);
  }

  public Strategy strategy() {
    return strategy;
  }

  /** The left input of the join. */
  RelationSource left() {
    return left;
  }

  /** The right input of the join. */
  RelationSource right() {
    return right;
  }

  /**
   * The sentence of the warning for a join that no edge, link or index supports (fork issue #9), {@code null} for a join that
   * something supports. The plan lists it as a {@code WARNING} line and the engine writes it to the log once per statement.
   */
  public String warning() {
    return warning;
  }

  /** How the edges are walked when the strategy is {@link Strategy#TRAVERSE}, {@code null} for any other strategy. */
  Traversal traversal() {
    return traversal;
  }

  /**
   * The edge entries a traversal took from the adjacency lists of vertices since this source was built, before any condition
   * dropped one: what a test reads to tell the work of a walk from the size of the edge type. Zero for any other strategy.
   */
  public long edgesRead() {
    return edgesRead.get();
  }

  /** What supports this join: an index, a link, an edge pattern, or nothing (fork issue #9 raises its warning from this). */
  public JoinSupport support() {
    return support;
  }

  long estimatedRows() {
    return estimatedRows;
  }

  @Override
  public List<Field> columns() {
    return columns;
  }

  @Override
  public String describe() {
    return header;
  }

  @Override
  public List<String> describeTree() {
    final List<String> lines = new ArrayList<>();
    lines.add(header);
    for (final String line : left.describeTree())
      lines.add("  " + line);
    for (final String line : rightLines)
      lines.add("  " + line);
    return lines;
  }

  @Override
  public Iterator<Object[]> open() {
    final BuildMemory memory = new BuildMemory(capBytes);
    switch (strategy) {
    case HASH:
      return buildLeft ? hashJoinBuildingLeft(memory) : hashJoinBuildingRight(memory);
    case INDEX_NESTED_LOOP:
      return indexNestedLoop();
    case TRAVERSE:
      return traverse();
    default:
      return nestedLoop(memory);
    }
  }

  // ---- the strategies ----

  private Iterator<Object[]> hashJoinBuildingRight(final BuildMemory memory) {
    final Map<Object, List<Object[]>> table = build(right, false, memory);
    final Iterator<Object[]> probing = left.open();
    return new Expanding() {
      @Override
      boolean produce(final ArrayDeque<Object[]> out) {
        if (!probing.hasNext())
          return false;
        final Object[] l = probing.next();
        boolean matched = false;
        final Object key = keyOf(l, true);
        final List<Object[]> bucket = key == null ? null : table.get(key);
        if (bucket != null)
          for (final Object[] r : bucket) {
            final Object[] row = concat(l, r);
            if (accepts(row)) {
              out.add(row);
              matched = true;
            }
          }
        if (!matched && type == TableReference.JoinType.LEFT)
          out.add(pad(l));
        return true;
      }
    };
  }

  /** An {@code INNER} join that builds on its left input: the right input is the probe side. */
  private Iterator<Object[]> hashJoinBuildingLeft(final BuildMemory memory) {
    final Map<Object, List<Object[]>> table = build(left, true, memory);
    final Iterator<Object[]> probing = right.open();
    return new Expanding() {
      @Override
      boolean produce(final ArrayDeque<Object[]> out) {
        if (!probing.hasNext())
          return false;
        final Object[] r = probing.next();
        final Object key = keyOf(r, false);
        final List<Object[]> bucket = key == null ? null : table.get(key);
        if (bucket != null)
          for (final Object[] l : bucket) {
            final Object[] row = concat(l, r);
            if (accepts(row))
              out.add(row);
          }
        return true;
      }
    };
  }

  private Iterator<Object[]> nestedLoop(final BuildMemory memory) {
    final List<Object[]> rows = new ArrayList<>();
    final Iterator<Object[]> input = right.open();
    while (input.hasNext()) {
      final Object[] row = input.next();
      memory.charge(row);
      rows.add(row);
    }
    final Iterator<Object[]> outer = left.open();
    return new Expanding() {
      @Override
      boolean produce(final ArrayDeque<Object[]> out) {
        if (!outer.hasNext())
          return false;
        final Object[] l = outer.next();
        boolean matched = false;
        for (final Object[] r : rows) {
          final Object[] row = concat(l, r);
          if (accepts(row)) {
            out.add(row);
            matched = true;
          }
        }
        if (!matched && type == TableReference.JoinType.LEFT)
          out.add(pad(l));
        return true;
      }
    };
  }

  private Iterator<Object[]> indexNestedLoop() {
    final Object[] holder = new Object[1];
    final Key lookupKey = keys.get(probe.key());
    final Evaluable value = new Evaluable.Node(lookupKey.type(), row -> holder[0]);
    final TableSource lookup = probe.column().kind() == Column.Kind.IDENTITY ? probe.table().withIdLookup(probe.column(), value)
        : probe.table().withIndexLookup(probe.column(), value);
    final Iterator<Object[]> outer = left.open();
    return new Expanding() {
      @Override
      boolean produce(final ArrayDeque<Object[]> out) {
        if (!outer.hasNext())
          return false;
        final Object[] l = outer.next();
        boolean matched = false;
        final Object key = keyOf(l, true);
        final Object lookupValue = key == null ? null : lookupKey.left().eval(l);
        if (lookupValue != null) {
          holder[0] = lookupValue;
          final Iterator<Object[]> candidates = lookup.open();
          while (candidates.hasNext()) {
            final Object[] r = candidates.next();
            if (probe.filter() != null && !Boolean.TRUE.equals(probe.filter().eval(r)))
              continue;
            if (!Objects.equals(key, keyOf(r, false)))
              continue;
            final Object[] row = concat(l, r);
            if (accepts(row)) {
              out.add(row);
              matched = true;
            }
          }
        }
        if (!matched && type == TableReference.JoinType.LEFT)
          out.add(pad(l));
        return true;
      }
    };
  }

  /**
   * For each left row, the edges of the vertex whose id the key holds, in the direction of the endpoint column. A key that is
   * {@code NULL} or not the id of a vertex has no edges; a {@code LEFT} join pads such a row once. Every edge entry is checked
   * against the whole key and the residual, so the rows are those of the equality join: one row per edge, so two parallel edges
   * give two rows.
   */
  private Iterator<Object[]> traverse() {
    final Traversal walk = traversal;
    final Key lookupKey = keys.get(walk.key());
    final Iterator<Object[]> outer = left.open();
    return new Expanding() {
      @Override
      boolean produce(final ArrayDeque<Object[]> out) {
        if (!outer.hasNext())
          return false;
        final Object[] l = outer.next();
        boolean matched = false;
        final Object key = keyOf(l, true);
        final Object id = key == null ? null : lookupKey.left().eval(l);
        final Vertex vertex = id instanceof String text ? vertexOf(walk, text) : null;
        if (vertex != null) {
          final Iterator<Edge> edges = vertex.getEdges(walk.direction(), walk.edgeTypes()).iterator();
          while (edges.hasNext()) {
            final Edge edge = edges.next();
            edgesRead.incrementAndGet();
            final Object[] r = edgeRow(walk, edge);
            if (r == null || !Objects.equals(key, keyOf(r, false)))
              continue;
            final Object[] row = concat(l, r);
            if (accepts(row)) {
              out.add(row);
              matched = true;
            }
          }
        }
        if (!matched && type == TableReference.JoinType.LEFT)
          out.add(pad(l));
        return true;
      }
    };
  }

  /** The vertex a record id names, {@code null} when it is not a record id, not a record, or not a vertex. */
  private static Vertex vertexOf(final Traversal walk, final String id) {
    try {
      final Object found = walk.database().lookupByRID(new RID(id), true);
      return found instanceof Vertex vertex ? vertex : null;
    } catch (final RuntimeException e) {
      // not a record id, or a record that does not exist: no edge starts or ends there
      return null;
    }
  }

  /** The row of the edge table for one edge, {@code null} when the edge is not a row of the table or fails the pushed condition. */
  private static Object[] edgeRow(final Traversal walk, final Edge edge) {
    try {
      if (!walk.edges().holds(edge))
        return null;
      if (walk.sourceGuard() != null) {
        final DocumentType from = walk.database().getSchema().getTypeByBucketId(edge.getOut().getBucketId());
        if (from == null || !from.instanceOf(walk.sourceGuard()))
          return null;
      }
      final Object[] row = walk.edges().rowOf(edge);
      return walk.filter() == null || Boolean.TRUE.equals(walk.filter().eval(row)) ? row : null;
    } catch (final RecordNotFoundException e) {
      // an entry of an adjacency list whose edge record is gone: a scan of the edge table does not see it either
      return null;
    }
  }

  // ---- helpers ----

  /** The rows of one input in a hash table by key; a row with a {@code NULL} key can never match, so it is not kept. */
  private Map<Object, List<Object[]>> build(final RelationSource input, final boolean leftSide, final BuildMemory memory) {
    final Map<Object, List<Object[]>> table = new HashMap<>();
    final Iterator<Object[]> rows = input.open();
    while (rows.hasNext()) {
      final Object[] row = rows.next();
      final Object key = keyOf(row, leftSide);
      if (key == null)
        continue;
      memory.charge(row);
      table.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }
    return table;
  }

  /** The join key of a row as one hash key, {@code null} when any part of it is {@code NULL}. */
  private Object keyOf(final Object[] row, final boolean leftSide) {
    if (keys.size() == 1) {
      final Key key = keys.get(0);
      final Object value = (leftSide ? key.left() : key.right()).eval(row);
      return value == null ? null : normalize(value, key.type());
    }
    final List<Object> parts = new ArrayList<>(keys.size());
    for (final Key key : keys) {
      final Object value = (leftSide ? key.left() : key.right()).eval(row);
      if (value == null)
        return null;
      parts.add(normalize(value, key.type()));
    }
    return parts;
  }

  /**
   * A value as the hash key of its common type: integers as {@code Long}, {@code numeric} without trailing zeros (so
   * {@code 1.0} and {@code 1.00} are one key), floats as {@code Double} with {@code -0.0} as {@code 0.0}. Other types are
   * already values whose {@code equals} is the equality of the type.
   */
  static Object normalize(final Object value, final PgType type) {
    if (type.isInteger())
      return ((Number) value).longValue();
    if (type == PgType.NUMERIC) {
      final BigDecimal decimal = Numbers.toBigDecimal(value);
      return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    }
    if (type.isFloat()) {
      final double number = ((Number) value).doubleValue();
      return number == 0.0 ? 0.0 : number;
    }
    return value;
  }

  private boolean accepts(final Object[] row) {
    return residual == null || Boolean.TRUE.equals(residual.eval(row));
  }

  private Object[] concat(final Object[] l, final Object[] r) {
    final Object[] row = Arrays.copyOf(l, l.length + r.length);
    System.arraycopy(r, 0, row, l.length, r.length);
    return row;
  }

  /** A left row with {@code NULL} in every right column. */
  private Object[] pad(final Object[] l) {
    return Arrays.copyOf(l, l.length + right.columns().size());
  }

  /** An iterator whose input step may produce no row, one row or several rows. */
  private abstract static class Expanding implements Iterator<Object[]> {
    private final ArrayDeque<Object[]> buffer = new ArrayDeque<>();

    /** Reads the next input and adds the rows it gives; false when there is no more input. */
    abstract boolean produce(ArrayDeque<Object[]> out);

    @Override
    public boolean hasNext() {
      while (buffer.isEmpty())
        if (!produce(buffer))
          return false;
      return true;
    }

    @Override
    public Object[] next() {
      if (!hasNext())
        throw new NoSuchElementException();
      return buffer.poll();
    }
  }
}
