"""PostgreSQL 18 reference, over psycopg, against the server that pg_ref_server.sh started. The tables are the lane's own PostgreSQL
tables (experiments/l1_tpc.py, PostgresTPC.build) with the columns Q14 and Q19 read added to the load: the load is extended, not the
query. The SNB tables carry the relational view of the fork under rule B (fork issue #38, version 2 of the kit): `person` has `rid`, the
record id as text (a unique key), and `id`, the LDBC id as bigint (the primary key); `knows` has no property `id` of its own, so its record id
stays `id` (text, the primary key), and `source_id` and `target_id` hold the `rid` of a person. The data are the same as in version 1."""
from __future__ import annotations

import os
import time

import pg13lib as L

DSN = "host=127.0.0.1 port={port} dbname=bench user=postgres password=CHANGE_ME"


class PGBackend:
    name = "postgres"

    def __init__(self, c):
        import psycopg
        port = os.environ.get("PG13_PG_PORT", "55432")
        last = None
        for _ in range(90):
            try:
                self.cx = psycopg.connect(DSN.format(port=port), autocommit=True)
                break
            except Exception as e:      # noqa: BLE001
                last = e
                time.sleep(2)
        else:
            raise SystemExit(f"cannot reach the PostgreSQL reference on port {port}: {last!r}")
        self.c = c

    def facts(self):
        cur = self.cx.cursor()
        cur.execute("SELECT version()")
        v = cur.fetchone()[0]
        cur.execute("SHOW synchronous_commit")
        sc = cur.fetchone()[0]
        return {"backend": "postgres", "server_version": v.split(" (")[0], "server_full_version": v, "synchronous_commit": sc}

    def load_snb(self, c):
        cur = self.cx.cursor()
        cur.execute("CREATE TABLE person (rid text NOT NULL UNIQUE, id bigint PRIMARY KEY, name text, age int, city text)")
        cur.execute("CREATE TABLE knows (id text PRIMARY KEY, source_id text NOT NULL, target_id text NOT NULL, since int)")
        n_p = n_k = 0
        with cur.copy("COPY person FROM STDIN") as cp:
            for i, name, age, city in L.gen_persons(c["snb_scale"], c["person_limit"]):
                cp.write_row((str(i), i, name, age, city))
                n_p += 1
        with cur.copy("COPY knows FROM STDIN") as cp:
            for n, (s, d, y) in enumerate(L.gen_edges(c["snb_scale"], c["person_limit"])):
                cp.write_row((f"e{n}", str(s), str(d), y))
                n_k += 1
        cur.execute("CREATE INDEX knows_source ON knows (source_id)")
        cur.execute("CREATE INDEX knows_target ON knows (target_id)")
        cur.execute("ANALYZE")
        return {"persons": n_p, "knows": n_k}

    def load_tpch(self, c):
        cur = self.cx.cursor()
        cur.execute("CREATE TABLE lineitem (l_orderkey BIGINT, l_partkey BIGINT, l_quantity DOUBLE PRECISION, "
                    "l_extendedprice DOUBLE PRECISION, l_discount DOUBLE PRECISION, l_returnflag TEXT, l_linestatus TEXT, "
                    "l_shipdate DATE, l_shipmode TEXT, l_tax DOUBLE PRECISION, l_shipinstruct TEXT)")
        n = 0
        with cur.copy("COPY lineitem FROM STDIN") as cp:
            for cols in L.tpch_lineitem_batches(c):
                lists = [cols[k].tolist() for k in L.TPCH_LI_COLS]
                for t in zip(*lists):
                    cp.write_row(t)
                n += len(lists[0])
        cur.execute("CREATE TABLE part (p_partkey BIGINT PRIMARY KEY, p_retailprice DOUBLE PRECISION, p_brand TEXT, p_type TEXT, "
                    "p_size INT, p_container TEXT)")
        part = L.tpch_part_frame(c)
        with cur.copy("COPY part FROM STDIN") as cp:
            for t in zip(*[part[k].tolist() for k in L.TPCH_PART_COLS]):
                cp.write_row(t)
        cur.execute("CREATE INDEX li_shipdate ON lineitem (l_shipdate)")
        cur.execute("ANALYZE lineitem")
        cur.execute("ANALYZE part")
        return {"lineitem": n, "part": len(part)}

    def run(self, text):
        cur = self.cx.cursor()
        cur.execute(text)
        return len(cur.fetchall())

    def table(self, text):
        cur = self.cx.cursor()
        cur.execute(text)
        names = [d.name for d in cur.description]
        return L.to_table([dict(zip(names, r)) for r in cur.fetchall()])

    def close(self):
        self.cx.close()
