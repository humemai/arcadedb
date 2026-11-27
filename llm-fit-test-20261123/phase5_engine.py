"""Phase 5 engines: the four tool backends of the arms and the one place that builds, copies, executes against and reads back the store.

  pg      ArcadeDB, the postgresql language of the prototype jar      (db.command("postgresql", ...))
  sql     ArcadeDB, native SQL, same jar, same data                   (db.command("sql", ...))
  cypher  ArcadeDB, openCypher, same jar, same data                   (db.command("opencypher", ...))
  duck    DuckDB, PostgreSQL-flavoured SQL, the control               (conn.execute)

A run gets its own copy of the base store; every engine call of a run goes through one worker thread (an ArcadeDB transaction belongs
to the thread that began it). The tool returns the rows as JSON or the engine's error text; the state read-back (never shown to a model)
is the same function of the stored records for the three ArcadeDB arms. No model call here.
"""
import concurrent.futures
import json
import os
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "vendor"))
import pgengine as P  # noqa: E402  (phases 1 and 2, unchanged: jar handling, errors, the information_schema DDL writer)
import phase5_store as S  # noqa: E402
import seed3  # noqa: E402  (phase 3: the statement splitter)

ARMS = ("pg", "sql", "cypher", "duck")
LANG = {"pg": "postgresql", "sql": "sql", "cypher": "opencypher"}
ROWS_CAP, CHARS_CAP, CALL_TIMEOUT = 100, 6000, 30.0
TX_FIRST = {"begin", "start", "commit", "end", "rollback", "abort"}


class EngineTimeout(Exception):
    pass


def fmt_rows(cols, rows, n_stmts=1):
    shown, trunc = rows[:ROWS_CAP], len(rows) > ROWS_CAP
    while True:
        body = {"columns": cols, "rows": shown, "row_count": len(rows)}
        if trunc:
            body["truncated"] = True
        if n_stmts > 1:
            body["statements_run"] = n_stmts
        txt = json.dumps(body, default=str, separators=(",", ":"))
        if len(txt) <= CHARS_CAP or not shown:
            return txt
        shown, trunc = shown[:max(1, len(shown) // 2)], True


def ok_result(cols, rows, n_stmts=1, ms=0):
    if not cols and not rows:
        txt = "OK. The statement returned no rows." + (f" ({n_stmts} statements run)" if n_stmts > 1 else "")
    else:
        txt = fmt_rows(cols, rows, n_stmts)
    return {"ok": True, "text": txt, "n_rows": len(rows), "sqlstate": None, "errcls": None, "error_class": None, "ms": ms}


def err_result(state, cls, msg, arm, ms=0):
    msg = (msg or "")[:1500]
    short = (cls or "").split(".")[-1]
    txt = f"ERROR (SQLSTATE {state}): {msg}" if state else f"ERROR ({short}): {msg}"
    if state:
        klass = {"0A000": "refused", "42601": "syntax"}.get(state, "runtime")
    else:
        klass = "syntax" if any(k in short for k in ("Parsing", "Parser", "Syntax")) else "runtime"
    return {"ok": False, "text": txt, "n_rows": 0, "sqlstate": state, "errcls": short, "error_class": klass, "ms": ms}


# ---------------------------------------------------------------- ArcadeDB
def start(jars, heap="2g"):
    import jpype
    if not jpype.isJVMStarted():
        P.start_jvm(jars, heap)
    return P.engine_stamp(jpype)


def build_base(jars, base_dir, seed_ref, heap="2g"):
    """The base ArcadeDB store: types, properties, a unique index on id, edge types with the endpoint declaration the table view reads."""
    import jpype
    stamp = start(jars, heap)
    shutil.rmtree(base_dir, ignore_errors=True)
    factory = jpype.JClass("com.arcadedb.database.DatabaseFactory")(base_dir)
    db = factory.create()
    for t in S.VERTS:
        db.command("sql", f"CREATE VERTEX TYPE {t}")
        for c in S.COLS[t]:
            db.command("sql", f"CREATE PROPERTY {t}.{c} {S.TYPES[c]}")
        db.command("sql", f"CREATE INDEX ON {t} (id) UNIQUE")
    for e in S.EDGES:
        db.command("sql", f"CREATE EDGE TYPE {e}")
    schema = db.getSchema()
    for e, (s, d) in S.EDGES.items():
        typ = schema.getType(e)
        typ.setCustomValue("source_table", s)
        typ.setCustomValue("target_table", d)
    vmap = {t: {} for t in S.VERTS}
    db.begin()
    for t in S.VERTS:
        for i in sorted(seed_ref.T[t]):
            v = db.newVertex(t)
            for c in S.COLS[t]:
                x = seed_ref.T[t][i][c]
                if x is not None:
                    v.set(c, x)
            v.save()
            vmap[t][i] = v
    for e, (s, d) in S.EDGES.items():
        for a, b in seed_ref.E[e]:
            vmap[s][a].newEdge(e, vmap[d][b])
    db.commit()
    counts = {}
    for t in list(S.VERTS) + list(S.EDGES):
        rs = db.query("sql", f"SELECT count(*) AS n FROM {t}")
        counts[t] = int(rs.next().getProperty("n"))
        rs.close()
    want = {t: len(seed_ref.T[t]) for t in S.VERTS}
    want.update({e: len(seed_ref.E[e]) for e in S.EDGES})
    if counts != want:
        raise SystemExit(f"loaded counts {counts} differ from the seed {want}")
    db.close()
    factory.close()
    return stamp, counts


class _Cat:
    def __init__(self, db):
        self.db = db
    _rows = P.PgEngine._rows


EDGE_TYPES = set(S.EDGES)


def render_ddl(tables, cols, cons, kcu, ccu):
    """CREATE TABLE text from information_schema rows, as phases 1 to 3 wrote it (pgengine.PgEngine.ddl) plus what PostgreSQL's own DDL
    says about a generated column: GENERATED ALWAYS (or BY DEFAULT) AS IDENTITY when is_identity is YES, DEFAULT <expr> when the engine
    reports a column default. A column the engine reports as plain NOT NULL is written as before."""
    kc, cc = {}, {}
    for r in kcu:
        kc.setdefault((r["table_name"], r["constraint_name"]), []).append(r["column_name"])
    for r in ccu:
        cc.setdefault(r["constraint_name"], []).append((r["table_name"], r["column_name"]))
    q = P.quote_ident
    out = []
    for t in tables:
        if t["table_type"] != "BASE TABLE":
            continue
        name = t["table_name"]
        lines = []
        for c in [c for c in cols if c["table_name"] == name]:
            line = f"  {q(c['column_name'])} {c['data_type']}" + ("" if c["is_nullable"] == "YES" else " NOT NULL")
            if c.get("is_identity") == "YES":
                line += " GENERATED " + ("BY DEFAULT" if c.get("identity_generation") == "BY DEFAULT" else "ALWAYS") + " AS IDENTITY"
            elif c.get("column_default") is not None:
                line += f" DEFAULT {c['column_default']}"
            lines.append(line)
        for k in [k for k in cons if k["table_name"] == name]:
            cname, ctype = k["constraint_name"], k["constraint_type"]
            cols_k = ", ".join(q(x) for x in kc.get((name, cname), []))
            if ctype == "PRIMARY KEY":
                lines.append(f"  CONSTRAINT {q(cname)} PRIMARY KEY ({cols_k})")
            elif ctype == "UNIQUE":
                lines.append(f"  CONSTRAINT {q(cname)} UNIQUE ({cols_k})")
            elif ctype == "FOREIGN KEY":
                refs = cc.get(cname, [])
                rtab = refs[0][0] if refs else "?"
                rcols = ", ".join(q(x[1]) for x in refs)
                lines.append(f"  CONSTRAINT {q(cname)} FOREIGN KEY ({cols_k}) REFERENCES {q(rtab)} ({rcols})")
        out.append(f"CREATE TABLE {q(name)} (\n" + ",\n".join(lines) + "\n);")
    return "\n\n".join(out) + "\n"


def read_catalog_rows(db):
    cat = _Cat(db)
    sc = "table_schema = 'public'"
    tables = cat._rows(f"SELECT table_name, table_type FROM information_schema.tables WHERE {sc} ORDER BY table_name")
    base_q = f"SELECT table_name, column_name, ordinal_position, is_nullable, data_type%s FROM information_schema.columns WHERE {sc} ORDER BY table_name, ordinal_position"
    try:  # a head that reports identity columns answers the extended query; an older one may not know the columns
        cols = cat._rows(base_q % ", column_default, is_identity, identity_generation")
        extended = True
    except Exception:  # noqa: BLE001
        cols = cat._rows(base_q % "")
        extended = False
    cons = cat._rows("SELECT constraint_name, table_name, constraint_type FROM information_schema.table_constraints "
                     f"WHERE {sc} ORDER BY table_name, constraint_name")
    kcu = cat._rows("SELECT constraint_name, table_name, column_name, ordinal_position FROM information_schema.key_column_usage "
                    f"WHERE {sc} ORDER BY constraint_name, ordinal_position")
    ccu = cat._rows("SELECT table_name, column_name, constraint_name FROM information_schema.constraint_column_usage "
                    f"WHERE {sc} ORDER BY constraint_name, column_name")
    return tables, cols, cons, kcu, ccu, extended


def project_identity(cols):
    """What a head with identity columns in its catalog (fork PR 71) reports for the same store: the record id rid of a vertex table and
    the id of an edge table GENERATED ALWAYS AS IDENTITY, no column default. A PROJECTION for the dry run and the estimate only: a real run
    shows the text the engine of the named head reports (phase5.py refuses to start on a projection)."""
    out = []
    for c in cols:
        c = dict(c)
        if (c["table_name"] in EDGE_TYPES and c["column_name"] == "id") or (c["table_name"] not in EDGE_TYPES and c["column_name"] == "rid"):
            c.update(is_identity="YES", identity_generation="ALWAYS", column_default=None)
        out.append(c)
    return out


SQL_ID_FACT = "-- The database generates the record id (@rid) of every vertex and every edge; a task never supplies one.\n"
CYPHER_ID_FACT = "Every node and every relationship has an internal record id that the database generates; a task never supplies one.\n"


def catalogs(jars, base_dir, heap="2g", project=False):
    """The catalog text each ArcadeDB arm shows: the table view (information_schema, as phases 1 to 3, with the identity flags the engine
    reports) for pg, the type view for sql and cypher, each with the one fact about generated record ids in its own syntax. Read from the
    engine of the base store, nothing written by hand except those two fact lines. Returns (texts, source) where source describes pg."""
    import jpype
    start(jars, heap)
    factory = jpype.JClass("com.arcadedb.database.DatabaseFactory")(base_dir)
    db = factory.open()
    tables, cols, cons, kcu, ccu, extended = read_catalog_rows(db)
    reported = render_ddl(tables, cols, cons, kcu, ccu)
    identity_reported = any(c.get("is_identity") == "YES" for c in cols)
    out = {"pg_engine_reported": reported, "pg": reported}
    if project:
        out["pg"] = render_ddl(tables, project_identity(cols), cons, kcu, ccu)
    sch = db.getSchema()
    props = {}
    for t in S.VERTS:
        typ = sch.getType(t)
        props[t] = sorted((str(n), str(typ.getProperty(n).getType())) for n in typ.getPropertyNames())
    ends = {}
    for e in S.EDGES:
        typ = sch.getType(e)
        ends[e] = (str(typ.getCustomValue("source_table")), str(typ.getCustomValue("target_table")))
    out["sql"] = ("\n".join(f"VERTEX TYPE {t} ({', '.join(f'{n} {ty}' for n, ty in props[t])})" for t in S.VERTS) + "\n"
                  + "\n".join(f"EDGE TYPE {e} (from {a} to {b}, no properties)" for e, (a, b) in ends.items()) + "\n" + SQL_ID_FACT)
    out["cypher"] = ("Node labels and their properties:\n" + "\n".join(f"(:{t} {{{', '.join(f'{n}: {ty}' for n, ty in props[t])}}})" for t in S.VERTS)
                     + "\nRelationship types (no properties):\n" + "\n".join(f"(:{a})-[:{e}]->(:{b})" for e, (a, b) in ends.items()) + "\n" + CYPHER_ID_FACT)
    db.close()
    factory.close()
    return out, {"pg": "projection" if project else "engine", "identity_reported_by_engine": identity_reported, "extended_columns_known": extended}


class ArcadeRun:
    def __init__(self, arm, base_dir, work, timeout=CALL_TIMEOUT):
        import jpype
        self.arm, self.lang, self.timeout, self.jpype = arm, LANG[arm], timeout, jpype
        self.dir = work
        shutil.rmtree(work, ignore_errors=True)
        shutil.copytree(base_dir, work)
        self.ex = concurrent.futures.ThreadPoolExecutor(max_workers=1, initializer=jpype.attachThreadToJVM)
        self.dead = False
        self.factory = jpype.JClass("com.arcadedb.database.DatabaseFactory")(work)
        self.db = self.call(self.factory.open)

    def call(self, fn, *a):
        fut = self.ex.submit(fn, *a)
        try:
            return fut.result(timeout=self.timeout)
        except concurrent.futures.TimeoutError:
            self.dead = True
            raise EngineTimeout(f"no answer within {self.timeout}s")

    def _stmt(self, text):
        db = self.db
        auto = seed3.first_word(text) not in TX_FIRST and not db.isTransactionActive()
        if auto:
            db.begin()
        try:
            rs = db.command(self.lang, text)
            cols, rows = [], []
            try:
                while rs.hasNext():
                    r = rs.next()
                    names = [str(n) for n in r.getPropertyNames()]
                    if not cols:
                        cols = names
                    rows.append([P.pyval(r.getProperty(n)) for n in names])
            finally:
                rs.close()
            if auto and db.isTransactionActive():
                db.commit()
            return cols, rows
        except BaseException:
            if auto and db.isTransactionActive():
                try:
                    db.rollback()
                except Exception:  # noqa: BLE001
                    pass
            raise

    def tool(self, statement):
        t0 = time.time()
        stmts = seed3.split_statements(statement or "")
        if not stmts:
            return err_result(None, "EmptyStatement", "the statement is empty", self.arm)
        cols, rows, n = [], [], 0
        for s in stmts:
            try:
                cols, rows = self.call(self._stmt, s)
            except EngineTimeout:
                raise
            except Exception as e:  # noqa: BLE001
                state, cls, msg = P.jerr(e)
                return err_result(state, cls, msg, self.arm, round((time.time() - t0) * 1000, 1))
            n += 1
        return ok_result(cols, rows, n, round((time.time() - t0) * 1000, 1))

    def end_session(self):
        def rb():
            if self.db.isTransactionActive():
                self.db.rollback()
        self.call(rb)

    def _q(self, sql):
        rs = self.db.query("sql", sql)
        rows = []
        try:
            while rs.hasNext():
                r = rs.next()
                rows.append([P.pyval(r.getProperty(str(n))) for n in r.getPropertyNames()])
        finally:
            rs.close()
        return rows

    def state(self):
        def dump():
            out = {}
            for t in S.VERTS:
                names = S.COLS[t]
                rs = self.db.query("sql", f"SELECT {', '.join(names)} FROM {t} ORDER BY id")
                rows2 = []
                while rs.hasNext():
                    r = rs.next()
                    rows2.append(tuple(_norm(P.pyval(r.getProperty(c))) for c in names))
                rs.close()
                out[t] = sorted(rows2, key=S.skey)
            for e in S.EDGES:
                rs = self.db.query("sql", f"SELECT out.id AS s, in.id AS d FROM {e}")
                pairs = []
                while rs.hasNext():
                    r = rs.next()
                    pairs.append((_norm(P.pyval(r.getProperty("s"))), _norm(P.pyval(r.getProperty("d")))))
                rs.close()
                out[e] = sorted(pairs, key=S.skey)
            return out
        return self.call(dump)

    def lookup_rid(self, table, i):
        res = self.tool(f'SELECT rid FROM "{table}" WHERE id = {i}') if self.arm == "pg" else None
        if res is None or not res["ok"]:
            raise RuntimeError(f"rid of {table} {i}: {res and res['text']}")
        return json.loads(res["text"])["rows"][0][0]

    def close(self):
        try:
            if not self.dead:
                self.call(self.db.close)
                self.factory.close()
        except Exception:  # noqa: BLE001
            pass
        self.ex.shutdown(wait=False)
        shutil.rmtree(self.dir, ignore_errors=True)


def _norm(v):
    return int(v) if isinstance(v, bool) is False and isinstance(v, (int, float)) and float(v) == int(v) else v


# ---------------------------------------------------------------- DuckDB control
DUCK_DDL = ["CREATE SEQUENCE rid_seq"]
for _t in S.VERTS:
    DUCK_DDL.append(f'CREATE TABLE "{_t}" (rid VARCHAR NOT NULL UNIQUE DEFAULT (\'r\' || nextval(\'rid_seq\')), id BIGINT PRIMARY KEY, '
                    + ", ".join(f"{c} {'BIGINT' if S.TYPES[c] == 'LONG' else 'INTEGER' if S.TYPES[c] == 'INTEGER' else 'VARCHAR'}"
                                for c in S.COLS[_t] if c != "id") + ")")
for _e, (_s, _d) in S.EDGES.items():
    DUCK_DDL.append(f'CREATE TABLE "{_e}" (id VARCHAR PRIMARY KEY DEFAULT (\'e\' || nextval(\'rid_seq\')), '
                    f'source_id VARCHAR NOT NULL REFERENCES "{_s}" (rid), target_id VARCHAR NOT NULL REFERENCES "{_d}" (rid))')


def duck_catalog():
    import duckdb
    c = duckdb.connect(":memory:")
    for s in DUCK_DDL:
        c.execute(s)
    txt = "\n".join(r[0].rstrip(";") + ";" for r in c.execute("SELECT sql FROM duckdb_tables() ORDER BY table_name").fetchall()) + "\n"
    c.close()
    return txt


class DuckRun:
    def __init__(self, seed_ref, timeout=CALL_TIMEOUT):
        import duckdb
        self.arm, self.timeout, self.dead = "duck", timeout, False
        self.conn = duckdb.connect(":memory:")
        for s in DUCK_DDL:
            self.conn.execute(s)
        for t in S.VERTS:
            for i in sorted(seed_ref.T[t]):
                cols = S.COLS[t]
                self.conn.execute(f'INSERT INTO "{t}" ({", ".join(cols)}) VALUES ({", ".join("?" for _ in cols)})', [seed_ref.T[t][i][c] for c in cols])
        for e, (s, d) in S.EDGES.items():
            for a, b in seed_ref.E[e]:
                self.conn.execute(f'INSERT INTO "{e}" (source_id, target_id) SELECT x.rid, y.rid FROM "{s}" x, "{d}" y WHERE x.id = ? AND y.id = ?', [a, b])
        self.ex = concurrent.futures.ThreadPoolExecutor(max_workers=1)

    def call(self, fn, *a):
        fut = self.ex.submit(fn, *a)
        try:
            return fut.result(timeout=self.timeout)
        except concurrent.futures.TimeoutError:
            self.dead = True
            try:
                self.conn.interrupt()
            except Exception:  # noqa: BLE001
                pass
            raise EngineTimeout(f"no answer within {self.timeout}s")

    def _stmt(self, text):
        cur = self.conn.execute(text)
        cols = [d[0] for d in cur.description] if cur.description else []
        rows = [[S_canon(x) for x in r] for r in cur.fetchall()] if cur.description else []
        return cols, rows

    def tool(self, statement):
        t0 = time.time()
        stmts = seed3.split_statements(statement or "")
        if not stmts:
            return err_result(None, "EmptyStatement", "the statement is empty", "duck")
        cols, rows, n = [], [], 0
        for s in stmts:
            try:
                cols, rows = self.call(self._stmt, s)
            except EngineTimeout:
                raise
            except Exception as e:  # noqa: BLE001
                return err_result(None, type(e).__name__, str(e), "duck", round((time.time() - t0) * 1000, 1))
            n += 1
        return ok_result(cols, rows, n, round((time.time() - t0) * 1000, 1))

    def end_session(self):
        try:
            self.call(lambda: self.conn.execute("ROLLBACK"))
        except EngineTimeout:
            raise
        except Exception:  # noqa: BLE001  (no open transaction)
            pass

    def state(self):
        def dump():
            out = {}
            for t in S.VERTS:
                cols = S.COLS[t]
                out[t] = sorted((tuple(_norm(x) for x in r) for r in self.conn.execute(f'SELECT {", ".join(cols)} FROM "{t}"').fetchall()), key=S.skey)
            for e, (s, d) in S.EDGES.items():
                q = (f'SELECT coalesce(a.id, -1), coalesce(b.id, -1) FROM "{e}" x LEFT JOIN "{s}" a ON a.rid = x.source_id '
                     f'LEFT JOIN "{d}" b ON b.rid = x.target_id')
                out[e] = sorted((int(a), int(b)) for a, b in self.conn.execute(q).fetchall())
            return out
        return self.call(dump)

    def lookup_rid(self, table, i):
        return self.conn.execute(f'SELECT rid FROM "{table}" WHERE id = ?', [i]).fetchone()[0]

    def close(self):
        try:
            self.conn.close()
        except Exception:  # noqa: BLE001
            pass
        self.ex.shutdown(wait=False)


def S_canon(x):
    return seed3.canon(x)


def open_run(arm, ctx, work):
    """ctx: {"base_dir": ..., "seed": Ref}. One engine instance for one task run."""
    if arm == "duck":
        return DuckRun(ctx["seed"])
    return ArcadeRun(arm, ctx["base_dir"], work)
