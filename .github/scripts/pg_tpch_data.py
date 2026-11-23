#!/usr/bin/env python3
#
# Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
# SPDX-License-Identifier: Apache-2.0
#

"""Writes the TPC-H tables `lineitem` and `part` as tab separated files for the PostgreSQL parity harness.

Fork only (PostgreSQL-style SQL prototype, fork issue #13). The data is generated, never committed: DuckDB's `dbgen` (the `tpch`
extension) is deterministic for one DuckDB version and one scale factor, so the workflow pins both and prints the SHA-256 of each file.
Both sides of the comparison (a real PostgreSQL 18 and the `postgresql` language of ArcadeDB) read these same two files.

The columns are the ones the benchmark lane loads (`experiments/l1_tpc.py` of `humemai/mmdbbench`: money and quantity columns as
double precision, `l_shipdate` as a date) plus the columns that TPC-H Q14 and Q19 read and the lane does not load:
`l_shipinstruct`, `p_type`, `p_brand`, `p_container` and `p_size`. The lane's own columns keep their order and the new ones come last,
so that a positional load of the lane stays valid. `part.stock` is the lane's counter column, always 100 here.

Usage: pg_tpch_data.py OUT_DIR [SCALE_FACTOR]   (default scale factor 0.01)
"""

import hashlib
import pathlib
import sys

import duckdb

LINEITEM = """
SELECT l_orderkey, l_partkey, CAST(l_quantity AS DOUBLE), CAST(l_extendedprice AS DOUBLE), CAST(l_discount AS DOUBLE),
       l_returnflag, l_linestatus, l_shipdate, l_shipmode, CAST(l_tax AS DOUBLE), l_shipinstruct
FROM lineitem ORDER BY l_orderkey, l_linenumber
"""

PART = """
SELECT p_partkey, CAST(p_retailprice AS DOUBLE), 100, p_type, p_brand, p_container, p_size
FROM part ORDER BY p_partkey
"""


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    out = pathlib.Path(sys.argv[1])
    scale = sys.argv[2] if len(sys.argv) > 2 else "0.01"
    float(scale)  # a number, nothing else reaches the SQL text below
    out.mkdir(parents=True, exist_ok=True)

    con = duckdb.connect()
    con.execute("INSTALL tpch")
    con.execute("LOAD tpch")
    con.execute(f"CALL dbgen(sf={scale})")
    print(f"duckdb {duckdb.__version__}, TPC-H scale factor {scale}")

    for name, query in (("lineitem", LINEITEM), ("part", PART)):
        path = out / f"{name}.tsv"
        con.execute(f"COPY ({query}) TO '{path}' (FORMAT csv, DELIMITER '\t', QUOTE '', HEADER false)")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        rows = con.execute(f"SELECT count(*) FROM {name}").fetchone()[0]
        print(f"{path} rows={rows} sha256={digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
