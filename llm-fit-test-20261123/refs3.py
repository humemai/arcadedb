"""Phase 3 reference and control backends, in process: PostgreSQL 18 over psycopg (the reference of every gold, and a diagnostic run of
every generation) and DuckDB 1.5.6 (the control arm). Same job shape and same result shape as pgengine3.py. No model call.

PostgreSQL 18 gets the data of the prototype's own table view (the dump of pgengine3.py), the DDL the prototype exposes (with two
additions a store needs to take inserts: a default for the record id column and the edge id, and the foreign keys added after the
load). One database per job, made from a template, so no job sees another job's writes.
"""
import os
import re
import shutil
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "vendor"))
import seed3  # noqa: E402

PG_DSN = "host=127.0.0.1 port={port} user=postgres password=CHANGE_ME"


def qi(name):
    return '"' + name.replace('"', '""') + '"'


IDENT_RX = re.compile(r"^(\s*)(\S+) (.+?) GENERATED ALWAYS AS IDENTITY( NOT NULL)?$")


def split_ddl(ddl, identity=False):
    """(create statements without foreign keys, foreign key alter statements, generated columns) of the DDL text the prototype exposes.
    identity=False (phase 3): a default on the record id column and the edge id, so the reference takes inserts. identity=True (phase
    3b): the text carries GENERATED ALWAYS AS IDENTITY on those columns; PostgreSQL allows an identity only on an integer type, so the
    reference keeps the text column and a BEFORE INSERT trigger that raises 428C9 for a supplied value and generates a uuid otherwise."""
    creates, fks, gens = [], [], []
    for m in re.finditer(r'CREATE TABLE ("?[\w]+"?) \((.*?)\n\);', ddl, re.S):
        table, body = m.group(1), m.group(2)
        lines = [ln.rstrip(",") for ln in body.strip("\n").split("\n")]
        keep = []
        for ln in lines:
            if re.match(r"\s*CONSTRAINT .* FOREIGN KEY", ln):
                fks.append(f"ALTER TABLE {table} ADD {ln.strip()}")
                continue
            m = IDENT_RX.match(ln) if identity else None
            if m:
                gens.append((table, m.group(2)))
                ln = f"{m.group(1)}{m.group(2)} {m.group(3)} NOT NULL"
            elif not identity and re.match(r"\s*(rid|id) text NOT NULL$", ln):
                ln += " DEFAULT gen_random_uuid()::text"
            keep.append(ln)
        creates.append(f"CREATE TABLE {table} (\n" + ",\n".join(keep) + "\n)")
    return creates, fks, gens


GEN_FUNCTION = """CREATE FUNCTION gen_always() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF to_jsonb(NEW) ->> TG_ARGV[0] IS NOT NULL THEN
    RAISE EXCEPTION 'cannot insert a non-DEFAULT value into column "%"', TG_ARGV[0]
      USING ERRCODE = '428C9', DETAIL = 'Column "' || TG_ARGV[0] || '" is an identity column defined as GENERATED ALWAYS.',
            HINT = 'Use OVERRIDING SYSTEM VALUE to override.';
  END IF;
  NEW := jsonb_populate_record(NEW, jsonb_build_object(TG_ARGV[0], gen_random_uuid()::text));
  RETURN NEW;
END $$"""


class PgRef:
    def __init__(self, port):
        import psycopg
        self.psycopg, self.port, self.n = psycopg, port, 0
        self.admin = psycopg.connect(PG_DSN.format(port=port) + " dbname=postgres", autocommit=True)

    def version(self):
        return self.admin.execute("SELECT version()").fetchone()[0].split(" on ")[0]

    def ensure(self, ddl, dump, identity=False):
        """Load the template database when this server has none (a fresh container); a loaded one is kept as it is."""
        if not self.admin.execute("SELECT 1 FROM pg_database WHERE datname = 'base'").fetchone():
            self.load(ddl, dump, identity)

    def load(self, ddl, dump, identity=False):
        a = self.admin
        a.execute("DROP DATABASE IF EXISTS base WITH (FORCE)")
        a.execute("CREATE DATABASE base")
        cx = self.psycopg.connect(PG_DSN.format(port=self.port) + " dbname=base", autocommit=True)
        creates, fks, gens = split_ddl(ddl, identity)
        for c in creates:
            cx.execute(c)
        counts = {}
        for t, d in dump.items():
            cols = ", ".join(qi(c) for c in d["columns"])
            with cx.cursor() as cur:
                with cur.copy(f"COPY {qi(t)} ({cols}) FROM STDIN") as cp:
                    for r in d["rows"]:
                        cp.write_row(r)
            counts[t] = len(d["rows"])
        for f in fks:
            cx.execute(f)
        if gens:
            cx.execute(GEN_FUNCTION)
            for t, c in gens:  # after the load: the trigger would refuse the supplied record ids of the dump
                cx.execute(f"CREATE TRIGGER gen_{re.sub(r'[^a-z0-9]', '', t.lower())}_{c} BEFORE INSERT ON {t} FOR EACH ROW EXECUTE FUNCTION gen_always('{c}')")
        cx.execute("ANALYZE")
        cx.close()
        return counts

    def run(self, job, timeout=60):
        t0 = time.time()
        self.n += 1
        name = f"j{os.getpid()}_{self.n}"
        self.admin.execute(f"CREATE DATABASE {name} TEMPLATE base")
        res = {"key": job["key"], "status": "ok", "failed_index": None, "sqlstate": None, "exc_class": None, "error": None,
               "n_statements": len(job["statements"]), "readbacks": None, "ms": 0}
        cx = self.psycopg.connect(PG_DSN.format(port=self.port) + f" dbname={name}", autocommit=True)
        try:
            cx.execute(f"SET statement_timeout = '{int(timeout)}s'")
            for i, s in enumerate(job["statements"]):
                try:
                    cx.execute(s)
                except self.psycopg.Error as e:
                    st = e.sqlstate
                    status = "syntax" if st == "42601" else "timeout" if st == "57014" else "outside_subset" if st == "0A000" else "runtime"
                    res.update(status=status, failed_index=i, sqlstate=st, exc_class=type(e).__name__, error=str(e).strip()[:600])
                    break
            try:
                cx.execute("ROLLBACK")
            except self.psycopg.Error:
                pass
            if res["status"] == "ok" and job.get("readbacks"):
                res["readbacks"] = []
                for q in job["readbacks"]:
                    rows = [[seed3.canon(v) for v in r] for r in cx.execute(q).fetchall()]
                    if len(rows) > seed3.ROW_CAP_RB:
                        raise RuntimeError("read-back larger than the cap")
                    res["readbacks"].append(rows)
        finally:
            cx.close()
            self.admin.execute(f"DROP DATABASE {name} WITH (FORCE)")
            res["ms"] = round((time.time() - t0) * 1000, 1)
        return res


class DuckRef:
    def __init__(self, data, base_path, work):
        import duckdb
        import engine_runner as er  # vendored: DUCK_DDL, DUCK_SRC (the control arm's schema of the original run)
        self.duckdb, self.base, self.work = duckdb, base_path, work
        os.makedirs(work, exist_ok=True)
        if os.path.exists(base_path):
            os.remove(base_path)
        cx = duckdb.connect(base_path)
        import csv
        for table, cols in er.DUCK_DDL.items():
            cx.execute(f"CREATE TABLE {table}({cols})")
            path = os.path.join(work, table + ".csv")
            with open(path, "w", newline="", encoding="utf-8") as f:
                csv.writer(f).writerows(data[er.DUCK_SRC[table]])
            cx.execute(f"COPY {table} FROM '{path}' (FORMAT csv, HEADER false)")
            os.remove(path)
        mem = seed3.memory_rows([p[0] for p in data["person"]])
        for t, rows in (("Memory", mem), ("MemoryArchive", seed3.archive_rows(mem))):
            cx.execute(f"CREATE TABLE {t}(id BIGINT PRIMARY KEY, agent VARCHAR, kind VARCHAR, content VARCHAR, importance INTEGER, "
                       "created BIGINT, subject BIGINT)")
            cx.executemany(f"INSERT INTO {t} VALUES (?, ?, ?, ?, ?, ?, ?)", rows)
        cx.execute("CHECKPOINT")
        cx.close()
        self.version = f"duckdb {duckdb.__version__}"

    def run(self, job, timeout=60):
        t0 = time.time()
        path = os.path.join(self.work, "job.duckdb")
        for ext in ("", ".wal"):
            if os.path.exists(path + ext):
                os.remove(path + ext)
        shutil.copy(self.base, path)
        cx = self.duckdb.connect(path)
        cx.execute("PRAGMA threads=2")
        res = {"key": job["key"], "status": "ok", "failed_index": None, "sqlstate": None, "exc_class": None, "error": None,
               "n_statements": len(job["statements"]), "readbacks": None, "ms": 0}
        timer = threading.Timer(timeout, cx.interrupt)
        timer.start()
        try:
            for i, s in enumerate(job["statements"]):
                try:
                    cx.execute(s)
                except Exception as e:  # noqa: BLE001
                    msg = f"{type(e).__name__}: {e}"
                    status = "timeout" if "interrupt" in msg.lower() else "syntax" if "ParserException" in msg else "runtime"
                    res.update(status=status, failed_index=i, exc_class=type(e).__name__, error=msg[:600])
                    break
            try:
                cx.execute("ROLLBACK")
            except Exception:  # noqa: BLE001
                pass
            if res["status"] == "ok" and job.get("readbacks"):
                res["readbacks"] = []
                for q in job["readbacks"]:
                    rows = [[seed3.canon(v) for v in r] for r in cx.execute(q).fetchall()]
                    if len(rows) > seed3.ROW_CAP_RB:
                        raise RuntimeError("read-back larger than the cap")
                    res["readbacks"].append(rows)
        finally:
            timer.cancel()
            cx.close()
            res["ms"] = round((time.time() - t0) * 1000, 1)
        return res
