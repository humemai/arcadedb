"""The pg14 PostgreSQL 18 side (arm R, a reference row only): backend_pg.PGBackend of pg13 (a byte-for-byte copy of the version 3 file) plus the
`created` column, the tables of the write tasks and the calls of the core tasks. Tables follow rule B as in pg13 (`person` has `rid` and `id`)."""
from __future__ import annotations

import backend_pg as BP
import pg13lib as L

PG_COLS = "id bigint PRIMARY KEY, name text, age int, city text, created bigint"


class PG14Backend(BP.PGBackend):
    name = "postgres14"

    def load_snb14(self, c, persons):
        cur = self.cx.cursor()
        cur.execute("CREATE TABLE person (rid text NOT NULL UNIQUE, id bigint PRIMARY KEY, name text, age int, city text, created bigint)")
        cur.execute("CREATE TABLE knows (id text PRIMARY KEY, source_id text NOT NULL, target_id text NOT NULL, since int)")
        with cur.copy("COPY person FROM STDIN") as cp:
            for p in persons:
                cp.write_row((str(p["id"]), p["id"], p["name"], p["age"], p["city"], p["created"]))
        n_k = 0
        with cur.copy("COPY knows FROM STDIN") as cp:
            for n, (s, d, y) in enumerate(L.gen_edges(c["snb_scale"], c["person_limit"])):
                cp.write_row((f"e{n}", str(s), str(d), y))
                n_k += 1
        cur.execute("CREATE INDEX knows_source ON knows (source_id)")
        cur.execute("CREATE INDEX knows_target ON knows (target_id)")
        cur.execute("ANALYZE")
        return {"persons": len(persons), "knows": n_k}

    def create_mem(self, tbl, persons):
        cur = self.cx.cursor()
        cur.execute(f"CREATE TABLE {tbl} ({PG_COLS})")
        with cur.copy(f"COPY {tbl} FROM STDIN") as cp:
            for p in persons:
                cp.write_row((p["id"], p["name"], p["age"], p["city"], p["created"]))
        cur.execute(f"ANALYZE {tbl}")
        return len(persons)

    def run_read(self, text):
        return self.run(text)

    def run_write(self, text):
        """One statement in autocommit: its own transaction (synchronous_commit is off, as in the lane's PostgreSQL arm)."""
        cur = self.cx.cursor()
        cur.execute(text)
        return 0

    def readback(self, tbl):
        return self.table(f"SELECT id, name, age, city, created FROM {tbl} ORDER BY id")
