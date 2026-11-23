-- The PostgreSQL side of the TPC-H data of fork issue #13 (PgTpchParityIT): the tables lineitem and part, with the columns and the
-- types that the benchmark lane loads (experiments/l1_tpc.py of humemai/mmdbbench: bigint keys, double precision money, quantity,
-- discount and tax, a date for l_shipdate, stock integer default 100) and the columns that TPC-H Q14 and Q19 read and the lane does
-- not load: l_shipinstruct, p_type, p_brand, p_container and p_size. No rows here: the harness copies the two tab separated files
-- of .github/scripts/pg_tpch_data.py into them (then the harness creates the index on l_shipdate and runs ANALYZE, in the order of the lane), and loads the same files into ArcadeDB (TpchFixture). The columns of the files are
-- the columns below, in this order.
CREATE TABLE lineitem (
  l_orderkey      bigint,
  l_partkey       bigint,
  l_quantity      double precision,
  l_extendedprice double precision,
  l_discount      double precision,
  l_returnflag    text,
  l_linestatus    text,
  l_shipdate      date,
  l_shipmode      text,
  l_tax           double precision,
  l_shipinstruct  text
);

CREATE TABLE part (
  p_partkey     bigint PRIMARY KEY,
  p_retailprice double precision,
  stock         integer DEFAULT 100,
  p_type        text,
  p_brand       text,
  p_container   text,
  p_size        integer
);
