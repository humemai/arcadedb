"""Phase 4 engine side: the prototype jar with the base database of phase 3 (sf1 graph + memory store), jobs in THREE languages:
the PostgreSQL language ("postgresql"), ArcadeDB native SQL ("sql") and openCypher ("opencypher"). No model call.

  uv run --no-project --python 3.13 --with jpype1==1.6.0 --with pyyaml python pgengine4.py --jars DIR --base-dir BASEDB \
      --ddl-out ddl.sql --jobs jobs.json --out out.json --partial part.jsonl [--timeout 60] [--cpus 4,5]

jobs.json: {"jobs": [{"key", "kind": "read"|"write", "lang": "postgresql"|"sql"|"opencypher", "statements": [str], "readbacks": [str]}]}
  read : statements has ONE query; run with db.query(lang, q) on one shared read-only copy of the base; result rows (<= 200, else truncated).
  write: a fresh copy of the base per job; the statements run one by one with db.command(lang, s); outside a BEGIN a statement runs in its
         own transaction (what a wire session does; the native languages are driven the same way); the first failing statement ends the
         script; an open transaction at the end is rolled back; then the read-backs (always in the PostgreSQL language, so one fixed set of
         read-backs reads the state whichever language wrote it) run on the copy.
Result: {"key", "status": ok|syntax|outside_subset|runtime|timeout, "failed_index", "sqlstate", "exc_class", "error", "n_statements",
"rows" (read), "truncated" (read), "readbacks" (write), "ms"}. The engine of pgengine.py (phases 1 and 2) is imported unchanged.
Reusable by a later phase: Runner4 (one job in any of the three languages), run_jobs (restart loop in pgarm4.py).
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "vendor"))
import pgengine as P  # noqa: E402  (phases 1 and 2, unchanged)
import seed3  # noqa: E402

READ_ROW_CAP = 200  # as in phases 1 and 2: a longer result is truncated and never matches


def status_of(lang, e):
    state, cls, msg = P.jerr(e)
    msg = (msg or "")[:600]
    if lang == "postgresql":
        status = "syntax" if state == "42601" else "outside_subset" if state == "0A000" else "runtime"
    else:  # the native languages carry no SQLSTATE: a parser exception is a syntax error, anything else a runtime error
        low = (cls or "").lower() + " " + msg.lower()[:200]
        status = "syntax" if ("pars" in (cls or "").lower() or "syntax" in low or "encountered" in low) else "runtime"
    return status, state, cls, msg


class Runner4:
    def __init__(self, jpype, base_dir, work, timeout):
        self.jpype, self.base, self.work, self.timeout = jpype, base_dir, work, timeout
        self.rdb = self.rfactory = None

    def _drain(self, rs):
        try:
            while rs.hasNext():
                rs.next()
        finally:
            rs.close()

    def _stmt(self, db, lang, text):
        auto = (not seed3.is_tx_control(text)) and not db.isTransactionActive()
        if auto:
            db.begin()
        try:
            self._drain(db.command(lang, text))
            if auto and db.isTransactionActive():
                db.commit()
        except BaseException:
            if auto and db.isTransactionActive():
                try:
                    db.rollback()
                except Exception:  # noqa: BLE001
                    pass
            raise

    def _rows(self, db, lang, sql, cap):
        rs = db.query(lang, sql)
        rows = []
        try:
            while rs.hasNext():
                r = rs.next()
                names = [str(n) for n in r.getPropertyNames() if not str(n).startswith("@")]
                rows.append([seed3.canon(P.pyval(r.getProperty(n))) for n in names])
                if len(rows) > cap:
                    break
        finally:
            rs.close()
        return rows

    def _open_read(self):
        if self.rdb is None:
            jdb = os.path.join(self.work, "readdb")
            shutil.rmtree(jdb, ignore_errors=True)
            shutil.copytree(self.base, jdb)
            self.rfactory = self.jpype.JClass("com.arcadedb.database.DatabaseFactory")(jdb)
            self.rdb = self.rfactory.open()
        return self.rdb

    def run(self, job):
        """(result, dead): dead is true when a statement ran past the timeout and the process must end."""
        t0 = time.time()
        lang = job.get("lang", "postgresql")
        res = {"key": job["key"], "status": "ok", "failed_index": None, "sqlstate": None, "exc_class": None, "error": None,
               "n_statements": len(job["statements"]), "ms": 0}
        ex = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        if job.get("kind") == "read":
            db = self._open_read()
            try:
                rows = ex.submit(self._rows, db, lang, job["statements"][0], READ_ROW_CAP).result(timeout=self.timeout)
                res.update(rows=rows[:READ_ROW_CAP], truncated=len(rows) > READ_ROW_CAP)
            except concurrent.futures.TimeoutError:
                res.update(status="timeout", failed_index=0, error=f"timeout after {self.timeout}s", rows=[], truncated=False)
                return res, True
            except Exception as e:  # noqa: BLE001
                st, state, cls, msg = status_of(lang, e)
                res.update(status=st, failed_index=0, sqlstate=state, exc_class=cls, error=msg, rows=[], truncated=False)
            finally:
                res["ms"] = round((time.time() - t0) * 1000, 1)
                ex.shutdown(wait=False)
            return res, False
        jdb = os.path.join(self.work, "jobdb")
        shutil.rmtree(jdb, ignore_errors=True)
        shutil.copytree(self.base, jdb)
        factory = self.jpype.JClass("com.arcadedb.database.DatabaseFactory")(jdb)
        db = factory.open()
        res["readbacks"] = None
        try:
            for i, s in enumerate(job["statements"]):
                fut = ex.submit(self._stmt, db, lang, s)
                try:
                    fut.result(timeout=self.timeout)
                except concurrent.futures.TimeoutError:
                    res.update(status="timeout", failed_index=i, error=f"timeout after {self.timeout}s")
                    return res, True
                except Exception as e:  # noqa: BLE001
                    st, state, cls, msg = status_of(lang, e)
                    res.update(status=st, failed_index=i, sqlstate=state, exc_class=cls, error=msg)
                    break
            try:  # whatever is open at the end is rolled back (the client went away)
                ex.submit(self._stmt, db, lang, "ROLLBACK").result(timeout=self.timeout)
            except Exception:  # noqa: BLE001
                try:
                    if db.isTransactionActive():
                        db.rollback()
                except Exception:  # noqa: BLE001
                    pass
            if res["status"] == "ok" and job.get("readbacks"):
                res["readbacks"] = [ex.submit(self._rows, db, "postgresql", q, seed3.ROW_CAP_RB).result(timeout=self.timeout)
                                    for q in job["readbacks"]]
            return res, False
        finally:
            res["ms"] = round((time.time() - t0) * 1000, 1)
            ex.shutdown(wait=False)
            try:
                if res["status"] != "timeout":
                    db.close()
                    factory.close()
            except Exception:  # noqa: BLE001
                pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jars", required=True)
    ap.add_argument("--base-dir", required=True)
    ap.add_argument("--ddl-out", required=True, help="ddl.sql of the base (its sha256 goes into the header)")
    ap.add_argument("--jobs")
    ap.add_argument("--out")
    ap.add_argument("--partial")
    ap.add_argument("--timeout", type=float, default=60.0)
    ap.add_argument("--heap", default="4g")
    ap.add_argument("--cpus", default="4,5")
    a = ap.parse_args()
    if a.cpus:
        os.sched_setaffinity(0, {int(c) for c in a.cpus.split(",")})
    import jpype
    P.start_jvm(a.jars, a.heap)
    header = {"engine": P.engine_stamp(jpype), "affinity": sorted(os.sched_getaffinity(0)),
              "ddl_sha256": hashlib.sha256(open(a.ddl_out, "rb").read()).hexdigest()}
    print(json.dumps(header), flush=True)
    if not a.jobs:
        os._exit(0)
    part = open(a.partial, "w") if a.partial else None

    def emit(obj):
        if part:
            part.write(json.dumps(obj) + "\n")
            part.flush()
            os.fsync(part.fileno())

    emit({"header": header})
    work = os.path.join(os.path.dirname(os.path.abspath(a.base_dir)), "work4-" + os.path.basename(a.base_dir) + f"-{os.getpid()}")
    os.makedirs(work, exist_ok=True)
    runner = Runner4(jpype, a.base_dir, work, a.timeout)
    out = dict(header, results=[])
    for j in json.load(open(a.jobs))["jobs"]:
        r, dead = runner.run(j)
        out["results"].append(r)
        emit(r)
        print(f"{j['key']} {r['status']} {r['sqlstate'] or ''} i={r['failed_index']} ms={r['ms']}", flush=True)
        if dead:  # a timed-out statement still holds the copy: the process ends and the caller restarts it for the rest
            sys.stdout.flush()
            shutil.rmtree(work, ignore_errors=True)
            os._exit(3)
    json.dump(out, open(a.out, "w"))
    sys.stdout.flush()
    shutil.rmtree(work, ignore_errors=True)
    os._exit(0)


if __name__ == "__main__":
    main()
