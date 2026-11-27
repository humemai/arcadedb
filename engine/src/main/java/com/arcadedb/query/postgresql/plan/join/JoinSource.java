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
import com.arcadedb.query.postgresql.eval.Jsonb;
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
import java.util.function.Supplier;

/**
 * The rows of a join of two sources: the columns of the left source followed by those of the right one, and for each pair of
 * rows that matches, the left row followed by the right row. {@code INNER} keeps the matching pairs; {@code LEFT} also keeps
 * every left row that has no partner, with {@code NULL} in the right columns; {@code RIGHT} keeps every right row that has no
 * partner, with {@code NULL} in the left columns; {@code FULL} keeps both. A {@code RIGHT} join is the mirror of {@code LEFT}: it
 * builds the left input and probes with the right one. A {@code FULL} join marks each row of its build side that a probe row
 * matched, and after the probe side ends it gives the rows that were not marked (a hash join), or does the same over the right
 * rows it holds (a nested loop). Neither is an index nested loop or a walk, which read the right input for each left row.
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
   * One merged column of {@code FULL JOIN ... USING}: the value is {@code left} of the left row, or {@code right} of the right
   * row when the left one is {@code NULL}, both in the common type (PostgreSQL's {@code coalesce(a.x, b.x)}).
   */
  record Merge(Evaluable left, Evaluable right) {
  }

  /**
   * How an index nested loop reads the right table for one left row.
   *
   * @param table  the right table, read by a scan until this probe narrows it
   * @param column the column of the right table the lookup uses: a property with an index, or {@code id}
   * @param key    the position in the key list of the equality that supplies the value to look up
   * @param filter a condition on the right rows that was pushed to the right side, {@code null} for none
   * @param recordSlot the position in a left row of the record at the end of a link that a walk kept, for a join on the key that
   *                   end refers to (fork issue #213): the right row is read from that record, and {@code -1} for a lookup by the
   *                   value
   */
  record IndexProbe(TableSource table, Column column, int key, Evaluable filter, int recordSlot) {
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
   * @param startSlot   the position in a left row of the record id of the start vertex, for an end that refers to a key (fork
   *                    issue #213); {@code -1} for an end that holds record ids, where the key value is the record id
   */
  record Traversal(Database database, TableSource edges, Vertex.DIRECTION direction, int key, Evaluable filter,
                   String sourceGuard, TableSource from, int startSlot) {
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
  private final Supplier<String>          header;
  private final Supplier<List<String>>    rightLines;
  private final JoinSupport               support;
  private final String                    warning;
  private final long                      estimatedRows;
  private final List<Field>               columns;
  private final List<Merge>               merges;

  JoinSource(final Strategy strategy, final TableReference.JoinType type, final RelationSource left, final RelationSource right,
      final List<Key> keys, final Evaluable residual, final boolean buildLeft, final IndexProbe probe, final Traversal traversal,
      final long capBytes, final Supplier<String> header, final Supplier<List<String>> rightLines, final JoinSupport support,
      final String warning, final long estimatedRows, final List<Field> hidden, final List<Merge> merges) {
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
    this.rightLines = rightLines;
    this.support = support;
    this.warning = warning;
    this.estimatedRows = estimatedRows;
    final List<Field> all = new ArrayList<>(left.columns());
    all.addAll(right.columns());
    all.addAll(hidden);
    this.columns = List.copyOf(all);
    this.merges = List.copyOf(merges);
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

  private List<Output> outputs;

  /** The columns an unqualified name reads: the merged columns of a {@code USING} first. */
  JoinSource withOutputs(final List<Output> outputs) {
    this.outputs = outputs;
    return this;
  }

  @Override
  public List<Output> outputs() {
    return outputs;
  }

  @Override
  public String describe() {
    return header.get();
  }

  @Override
  public List<String> describeTree() {
    return planLines().get();
  }

  @Override
  public Supplier<List<String>> planLines() {
    final Supplier<String> first = header;
    final Supplier<List<String>> above = left.planLines();
    final Supplier<List<String>> below = rightLines;
    return () -> {
      final List<String> lines = new ArrayList<>();
      lines.add(first.get());
      for (final String line : above.get())
        lines.add("  " + line);
      for (final String line : below.get())
        lines.add("  " + line);
      return lines;
    };
  }

  @Override
  public Iterator<Object[]> open() {
    final Iterator<Object[]> rows = openRows();
    return merges.isEmpty() ? rows : withMergedColumns(rows);
  }

  private Iterator<Object[]> openRows() {
    final BuildMemory memory = new BuildMemory(capBytes);
    switch (strategy) {
    case HASH:
      if (type == TableReference.JoinType.FULL)
        return fullHashJoin(memory);
      return buildLeft ? hashJoinBuildingLeft(memory) : hashJoinBuildingRight(memory);
    case INDEX_NESTED_LOOP:
      return indexNestedLoop();
    case TRAVERSE:
      return traverse();
    default:
      return nestedLoop(memory);
    }
  }

  /** The rows with the merged columns of {@code FULL ... USING} appended: {@code coalesce(left, right)} of each pair. */
  private Iterator<Object[]> withMergedColumns(final Iterator<Object[]> rows) {
    final int leftWidth = left.columns().size();
    final int rightWidth = right.columns().size();
    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return rows.hasNext();
      }

      @Override
      public Object[] next() {
        final Object[] row = rows.next();
        final Object[] l = Arrays.copyOfRange(row, 0, leftWidth);
        final Object[] r = Arrays.copyOfRange(row, leftWidth, leftWidth + rightWidth);
        final Object[] out = Arrays.copyOf(row, leftWidth + rightWidth + merges.size());
        for (int i = 0; i < merges.size(); i++) {
          final Object value = merges.get(i).left().eval(l);
          out[leftWidth + rightWidth + i] = value != null ? value : merges.get(i).right().eval(r);
        }
        return out;
      }
    };
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
        boolean matched = false;
        final Object key = keyOf(r, false);
        final List<Object[]> bucket = key == null ? null : table.get(key);
        if (bucket != null)
          for (final Object[] l : bucket) {
            final Object[] row = concat(l, r);
            if (accepts(row)) {
              out.add(row);
              matched = true;
            }
          }
        if (!matched && type == TableReference.JoinType.RIGHT)
          out.add(padLeft(r));
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
    // RIGHT and FULL give the right rows that no left row matched, after the last left row
    final boolean keepsRight = type == TableReference.JoinType.RIGHT || type == TableReference.JoinType.FULL;
    final boolean keepsLeft = type == TableReference.JoinType.LEFT || type == TableReference.JoinType.FULL;
    final boolean[] matchedRight = new boolean[keepsRight ? rows.size() : 0];
    return new Expanding() {
      private boolean flushed;

      @Override
      boolean produce(final ArrayDeque<Object[]> out) {
        if (!outer.hasNext()) {
          if (keepsRight && !flushed) {
            flushed = true;
            for (int i = 0; i < matchedRight.length; i++)
              if (!matchedRight[i])
                out.add(padLeft(rows.get(i)));
            return !out.isEmpty();
          }
          return false;
        }
        final Object[] l = outer.next();
        boolean matched = false;
        for (int i = 0; i < rows.size(); i++) {
          final Object[] row = concat(l, rows.get(i));
          if (accepts(row)) {
            out.add(row);
            matched = true;
            if (keepsRight)
              matchedRight[i] = true;
          }
        }
        if (!matched && keepsLeft)
          out.add(pad(l));
        return true;
      }
    };
  }

  /** One row of the build side of a {@code FULL} join and whether a probe row matched it. */
  private static final class Tracked {
    final Object[] row;
    boolean        matched;

    Tracked(final Object[] row) {
      this.row = row;
    }
  }

  /** The bytes that the matched flag of a build row adds: the object, its reference in the table and in the list of all rows. */
  private static final long TRACKED_BYTES = 24 + 8 + 8;

  /**
   * A {@code FULL} join: one input is kept in a hash table by key (rows with a {@code NULL} key too, as they can never match but
   * have to come out), the other is probed. A probe row without a partner comes out padded; after the probe side ends, every kept
   * row that no probe row matched comes out padded on the other side.
   */
  private Iterator<Object[]> fullHashJoin(final BuildMemory memory) {
    final boolean buildsLeft = buildLeft;
    final Map<Object, List<Tracked>> table = new HashMap<>();
    final List<Tracked> all = new ArrayList<>();
    final Iterator<Object[]> input = (buildsLeft ? left : right).open();
    while (input.hasNext()) {
      final Object[] row = input.next();
      memory.charge(row);
      memory.chargeBytes(TRACKED_BYTES);
      final Tracked tracked = new Tracked(row);
      all.add(tracked);
      final Object key = keyOf(row, buildsLeft);
      if (key != null)
        table.computeIfAbsent(key, k -> new ArrayList<>()).add(tracked);
    }
    final Iterator<Object[]> probing = (buildsLeft ? right : left).open();
    return new Expanding() {
      private boolean flushed;

      @Override
      boolean produce(final ArrayDeque<Object[]> out) {
        if (!probing.hasNext()) {
          if (flushed)
            return false;
          flushed = true;
          for (final Tracked tracked : all)
            if (!tracked.matched)
              out.add(buildsLeft ? pad(tracked.row) : padLeft(tracked.row));
          return !out.isEmpty();
        }
        final Object[] p = probing.next();
        boolean matched = false;
        final Object key = keyOf(p, !buildsLeft);
        final List<Tracked> bucket = key == null ? null : table.get(key);
        if (bucket != null)
          for (final Tracked t : bucket) {
            final Object[] row = buildsLeft ? concat(t.row, p) : concat(p, t.row);
            if (accepts(row)) {
              out.add(row);
              t.matched = true;
              matched = true;
            }
          }
        if (!matched)
          out.add(buildsLeft ? padLeft(p) : pad(p));
        return true;
      }
    };
  }

  private Iterator<Object[]> indexNestedLoop() {
    final Object[] holder = new Object[1];
    final Key lookupKey = keys.get(probe.key());
    final Evaluable value = new Evaluable.Node(lookupKey.type(), row -> holder[0]);
    final int recordSlot = probe.recordSlot();
    final TableSource lookup = recordSlot >= 0 ? probe.table()
        : probe.column().kind() == Column.Kind.IDENTITY ? probe.table().withIdLookup(probe.column(), value)
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
          final Iterator<Object[]> candidates = recordSlot >= 0 ? lookup.openRecord(l[recordSlot]) : lookup.open();
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
        // the start vertex is named by the key itself when it is a record id, else by the record id its row keeps
        final Object id = key == null ? null : walk.startSlot() < 0 ? lookupKey.left().eval(l) : l[walk.startSlot()];
        final Vertex vertex = id == null ? null : vertexOf(walk, id);
        if (vertex != null) {
          final Iterator<Edge> edges = vertex.getEdges(walk.direction(), walk.edgeTypes()).iterator();
          while (edges.hasNext()) {
            final Edge edge = edges.next();
            edgesRead.incrementAndGet();
            final Object[] r = edgeRow(walk, edge, vertex);
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
  private static Vertex vertexOf(final Traversal walk, final Object id) {
    if (!(id instanceof String || id instanceof RID))
      return null;
    try {
      final Object found = walk.database().lookupByRID(id instanceof RID rid ? rid : new RID((String) id), true);
      return found instanceof Vertex vertex ? vertex : null;
    } catch (final RuntimeException e) {
      // not a record id, or a record that does not exist: no edge starts or ends there
      return null;
    }
  }

  /** The row of the edge table for one edge, {@code null} when the edge is not a row of the table or fails the pushed condition. */
  private static Object[] edgeRow(final Traversal walk, final Edge edge, final Vertex near) {
    try {
      if (!walk.edges().holds(edge))
        return null;
      if (walk.sourceGuard() != null) {
        final DocumentType from = walk.database().getSchema().getTypeByBucketId(edge.getOut().getBucketId());
        if (from == null || !from.instanceOf(walk.sourceGuard()))
          return null;
      }
      final Object[] row = walk.edges().rowOfWalk(edge, near);
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
    if (type == PgType.JSONB)
      return Jsonb.key(value);
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

  /** A right row with {@code NULL} in every left column. */
  private Object[] padLeft(final Object[] r) {
    final int leftWidth = left.columns().size();
    final Object[] row = new Object[leftWidth + r.length];
    System.arraycopy(r, 0, row, leftWidth, r.length);
    return row;
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
