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
package com.arcadedb.query.postgresql;

import com.arcadedb.database.Database;
import com.arcadedb.database.MutableDocument;
import com.arcadedb.schema.DocumentType;
import com.arcadedb.schema.Schema;
import com.arcadedb.schema.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * The two TPC-H tables that Q1, Q6, Q14 and Q19 read (fork issue #13), as document types with declared properties: the schema
 * of the benchmark lane's load ({@code experiments/l1_tpc.py} of {@code humemai/mmdbbench}: keys {@code bigint}, quantity,
 * price, discount and tax {@code double precision}, {@code l_shipdate} a date) plus the columns that Q14 and Q19 read and the lane
 * does not load ({@code l_shipinstruct}, {@code p_type}, {@code p_brand}, {@code p_container}, {@code p_size}). The PostgreSQL
 * side of the parity harness has the same two tables in {@code e2e/src/test/resources/pgparity/tpch-schema.sql}.
 * <p>
 * {@link #load(Database, Path)} reads the two tab separated files that {@code .github/scripts/pg_tpch_data.py} writes; the
 * columns of a line are the columns of the type, in the order of the declarations below.
 */
public final class TpchFixture {
  public static final String LINEITEM = "lineitem";
  public static final String PART     = "part";

  private TpchFixture() {
  }

  public static void createSchema(final Database database) {
    final Schema schema = database.getSchema();

    final DocumentType lineitem = schema.createDocumentType(LINEITEM);
    lineitem.createProperty("l_orderkey", Type.LONG);
    lineitem.createProperty("l_partkey", Type.LONG);
    lineitem.createProperty("l_quantity", Type.DOUBLE);
    lineitem.createProperty("l_extendedprice", Type.DOUBLE);
    lineitem.createProperty("l_discount", Type.DOUBLE);
    lineitem.createProperty("l_returnflag", Type.STRING);
    lineitem.createProperty("l_linestatus", Type.STRING);
    lineitem.createProperty("l_shipdate", Type.DATE);
    lineitem.createProperty("l_shipmode", Type.STRING);
    lineitem.createProperty("l_tax", Type.DOUBLE);
    lineitem.createProperty("l_shipinstruct", Type.STRING);

    final DocumentType part = schema.createDocumentType(PART);
    part.createProperty("p_partkey", Type.LONG);
    part.createProperty("p_retailprice", Type.DOUBLE);
    part.createProperty("stock", Type.INTEGER);
    part.createProperty("p_type", Type.STRING);
    part.createProperty("p_brand", Type.STRING);
    part.createProperty("p_container", Type.STRING);
    part.createProperty("p_size", Type.INTEGER);
  }

  /** One row of {@code lineitem}, the columns in the order of the declarations. */
  public static void lineitem(final Database database, final long orderkey, final long partkey, final double quantity,
      final double extendedprice, final double discount, final String returnflag, final String linestatus, final LocalDate shipdate,
      final String shipmode, final double tax, final String shipinstruct) {
    final MutableDocument row = database.newDocument(LINEITEM);
    row.set("l_orderkey", orderkey);
    row.set("l_partkey", partkey);
    row.set("l_quantity", quantity);
    row.set("l_extendedprice", extendedprice);
    row.set("l_discount", discount);
    row.set("l_returnflag", returnflag);
    row.set("l_linestatus", linestatus);
    row.set("l_shipdate", shipdate);
    row.set("l_shipmode", shipmode);
    row.set("l_tax", tax);
    row.set("l_shipinstruct", shipinstruct);
    row.save();
  }

  /** One row of {@code part}, the columns in the order of the declarations. */
  public static void part(final Database database, final long partkey, final double retailprice, final int stock, final String type,
      final String brand, final String container, final int size) {
    final MutableDocument row = database.newDocument(PART);
    row.set("p_partkey", partkey);
    row.set("p_retailprice", retailprice);
    row.set("stock", stock);
    row.set("p_type", type);
    row.set("p_brand", brand);
    row.set("p_container", container);
    row.set("p_size", size);
    row.save();
  }

  /**
   * Loads {@code lineitem.tsv} and {@code part.tsv} of a directory, in transactions of 10000 rows.
   *
   * @return the number of rows loaded into {@code lineitem} and into {@code part}
   */
  public static long[] load(final Database database, final Path directory) throws IOException {
    final long lineitems = read(database, directory.resolve("lineitem.tsv"), fields -> lineitem(database, Long.parseLong(fields[0]),
        Long.parseLong(fields[1]), Double.parseDouble(fields[2]), Double.parseDouble(fields[3]), Double.parseDouble(fields[4]),
        fields[5], fields[6], LocalDate.parse(fields[7]), fields[8], Double.parseDouble(fields[9]), fields[10]), 11);
    final long parts = read(database, directory.resolve("part.tsv"), fields -> part(database, Long.parseLong(fields[0]),
        Double.parseDouble(fields[1]), Integer.parseInt(fields[2]), fields[3], fields[4], fields[5], Integer.parseInt(fields[6])), 7);
    return new long[] { lineitems, parts };
  }

  private interface RowLoader {
    void load(String[] fields);
  }

  private static long read(final Database database, final Path file, final RowLoader loader, final int columns) throws IOException {
    long rows = 0;
    database.begin();
    try (final BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        final String[] fields = line.split("\t", -1);
        if (fields.length != columns)
          throw new IllegalArgumentException(file + " line " + (rows + 1) + " has " + fields.length + " fields, not " + columns);
        loader.load(fields);
        if (++rows % 10000 == 0) {
          database.commit();
          database.begin();
        }
      }
      database.commit();
    } catch (final IOException | RuntimeException e) {
      if (database.isTransactionActive())
        database.rollback();
      throw e;
    }
    return rows;
  }
}
