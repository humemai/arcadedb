"""The real backend: the arcadedb-jvm package of the arm folder (PYTHONPATH) with the engine jar swapped for the fork's build.
One JVM for the whole run, one database open at a time. Everything the engine says about itself is read back, never assumed."""
from __future__ import annotations

import os
import shutil
from pathlib import Path

import pg13lib as L

INGEST_BATCH = 10_000
LI_TYPES = {"l_orderkey": "LONG", "l_partkey": "LONG", "l_quantity": "DOUBLE", "l_extendedprice": "DOUBLE", "l_discount": "DOUBLE",
            "l_returnflag": "STRING", "l_linestatus": "STRING", "l_shipdate": "DATE", "l_shipmode": "STRING", "l_tax": "DOUBLE",
            "l_shipinstruct": "STRING"}
JOIN_CAP_PROPERTY = "arcadedb.postgresql.joinMemoryMb"
PART_TYPES = {"p_partkey": "LONG", "p_retailprice": "DOUBLE", "p_brand": "STRING", "p_type": "STRING", "p_size": "INTEGER",
              "p_container": "STRING"}


class ArcadeBackend:
    name = "arcade"

    def __init__(self, c, root, engine_jar):
        self.c = c
        self.root = Path(root)
        self.engine_jar = Path(engine_jar).resolve()
        self.db = None
        import arcadedb
        import jpype
        self.adb = arcadedb
        self.jpype = jpype

    # -- the engine in force ---------------------------------------------------------------------------------------------------
    def facts(self):
        a = self.adb
        out = {"backend": "arcadedb-jvm", "package_file": os.path.realpath(a.__file__), "package_version": getattr(a, "__version__", "?")}
        try:
            out["jar_fingerprint"] = a.jar_fingerprint()
        except Exception as e:          # noqa: BLE001
            out["jar_fingerprint_error"] = repr(e)
        return out

    def engine_gates(self, out):
        """Code source of an engine class and of the postgresql language class: both must be the swapped jar."""
        j = self.jpype
        ok_all = True
        for cls in ("com.arcadedb.Constants", "com.arcadedb.query.postgresql.PostgreSQLQueryEngine"):
            try:
                k = j.JClass(cls)
                loc = str(k.class_.getProtectionDomain().getCodeSource().getLocation().getPath())
                ok = os.path.realpath(loc) == str(self.engine_jar)
            except Exception as e:      # noqa: BLE001
                loc, ok = repr(e), False
            ok_all &= out.gate(f"engine class in the swapped jar: {cls}", ok, f"code source {loc}, expected {self.engine_jar}")
        C = j.JClass("com.arcadedb.Constants")
        info = {"version": str(C.getVersion()), "branch": str(C.getBranch()), "build": str(C.getBuildNumber())}
        S, R = j.JClass("java.lang.System"), j.JClass("java.lang.Runtime").getRuntime()
        jvm = {"java_version": str(S.getProperty("java.version")), "vm": str(S.getProperty("java.vm.name")), "vendor": str(S.getProperty("java.vendor")),
               "processors": int(R.availableProcessors()), "max_heap_mib": int(R.maxMemory()) // (1 << 20)}
        # the join memory cap in force, read from the running JVM: the property AND what the engine's own reader (the one every statement
        # calls) makes of it, in bytes
        want_mb = int(self.c["join_memory_mb"])
        prop = S.getProperty(JOIN_CAP_PROPERTY)
        try:
            cap = int(j.JClass("com.arcadedb.query.postgresql.plan.join.BuildMemory").configuredCapBytes())
        except Exception as e:          # noqa: BLE001
            cap = repr(e)
        jvm["join_memory_property"] = None if prop is None else str(prop)
        jvm["join_memory_cap_bytes"] = cap
        out.write("env", {"engine_constants": info, "jvm": jvm})
        ok_all &= out.gate("the join memory cap is the pinned one", cap == want_mb * 1024 * 1024, f"property {prop}, engine reads {cap} bytes, pinned {want_mb} MiB")
        ok_all &= out.gate("the JVM sees the lane's two processors", jvm["processors"] == 2, jvm)
        want = int(self.c["heap"].rstrip("gG")) * 1024
        ok_all &= out.gate("the JVM heap is the pinned heap", abs(jvm["max_heap_mib"] - want) <= want * 0.1, f"{jvm['max_heap_mib']} MiB against {want} MiB")
        return ok_all, info

    # -- databases -------------------------------------------------------------------------------------------------------------
    def create(self, name):
        if self.db is not None:
            self.db.close()
        path = self.root / "db" / name
        if path.exists():
            shutil.rmtree(path)          # our own folder under this run's output
        path.parent.mkdir(parents=True, exist_ok=True)
        # the JVM starts once, with the first database: its properties are the run's. joinMemoryMb is the cap of one join of the postgresql
        # language (fork PR #30, BuildMemory.CAP_PROPERTY, read at every statement); the 64 MiB default stops Q14 and Q19 at scale factor 1.
        jvm_args = f"-Darcadedb.txWalFlush=0 -D{JOIN_CAP_PROPERTY}={self.c['join_memory_mb']}"
        self.db = self.adb.create_database(str(path), jvm_kwargs={"heap_size": self.c["heap"], "jvm_args": jvm_args})

    def sql(self, text):
        return self.db.command("sql", text)

    def load_snb(self, c):
        for ddl in ["CREATE VERTEX TYPE Person", "CREATE PROPERTY Person.id LONG", "CREATE PROPERTY Person.name STRING",
                    "CREATE PROPERTY Person.age INTEGER", "CREATE PROPERTY Person.city STRING", "CREATE INDEX ON Person (id) UNIQUE"]:
            self.sql(ddl)
        try:   # the endpoint tables are declared, never inferred (fork issue #1 question 11, confirmed 2026-11-23)
            self.sql("CREATE EDGE TYPE KNOWS CUSTOM source_table = 'Person', target_table = 'Person'")
        except Exception:               # noqa: BLE001
            self.sql("CREATE EDGE TYPE KNOWS")
            t = self.db.get_java_database().getSchema().getType("KNOWS")
            t.setCustomValue("source_table", "Person")
            t.setCustomValue("target_table", "Person")
        self.sql("CREATE PROPERTY KNOWS.since INTEGER")
        persons = [{"id": i, "name": n, "age": a, "city": ct} for i, n, a, ct in L.gen_persons(c["snb_scale"], c["person_limit"])]
        edges = list(L.gen_edges(c["snb_scale"], c["person_limit"]))
        rid = {}
        with self.db.graph_batch(use_wal=True, expected_edge_count=len(edges)) as gb:
            for s in range(0, len(persons), INGEST_BATCH):
                chunk = persons[s:s + INGEST_BATCH]
                for p, r in zip(chunk, gb.create_vertices("Person", chunk)):
                    rid[p["id"]] = r
            for s in range(0, len(edges), INGEST_BATCH):
                chunk = edges[s:s + INGEST_BATCH]
                gb.new_edges([rid[a] for a, _, _ in chunk], "KNOWS", [rid[b] for _, b, _ in chunk], [{"since": y} for _, _, y in chunk])
        t = self.db.get_java_database().getSchema().getType("KNOWS")
        declared = (str(t.getCustomValue("source_table")), str(t.getCustomValue("target_table")))
        n_p = int(self.db.query("sql", "SELECT count(*) AS n FROM Person").to_list()[0]["n"])
        n_k = int(self.db.query("sql", "SELECT count(*) AS n FROM KNOWS").to_list()[0]["n"])
        return {"persons_streamed": len(persons), "knows_streamed": len(edges), "persons": n_p, "knows": n_k, "endpoints": declared}

    def load_tpch(self, c):
        li_t, part_t = ("LineItem", "Part") if c["tpch_case"] == "lane" else ("lineitem", "part")
        ex = self.db.async_executor()
        writers = ex.get_parallel_level()
        ex.set_transaction_sync("no")
        self.sql(f"CREATE DOCUMENT TYPE {li_t} BUCKETS {writers}")
        for col, t in LI_TYPES.items():
            self.sql(f"CREATE PROPERTY {li_t}.{col} {t}")
        self.sql(f"CREATE DOCUMENT TYPE {part_t}")
        for col, t in PART_TYPES.items():
            self.sql(f"CREATE PROPERTY {part_t}.{col} {t}")
        self.sql(f"CREATE INDEX ON {part_t} (p_partkey) UNIQUE_HASH")
        streamed, first = 0, True
        for cols in L.tpch_lineitem_batches(c):
            self.db.insert_columns(li_t, cols, parallel=True)
            streamed += len(cols["l_orderkey"])
            if first:     # the date column must have arrived as a DATE before the other batches are loaded
                first = False
                row = self.db.query("sql", f"SELECT l_shipdate FROM {li_t} LIMIT 1").to_list()[0]
                if isinstance(row["l_shipdate"], str):
                    raise SystemExit(f"load check: l_shipdate came back as text {row['l_shipdate']!r}, not a DATE: stop before loading the rest")
        stored = int(self.db.query("sql", f"SELECT count(*) AS n FROM {li_t}").to_list()[0]["n"])
        if stored != streamed:
            raise SystemExit(f"{li_t} stored {stored:,} of {streamed:,} streamed rows: the load lost records")
        part = L.tpch_part_frame(c)
        self.db.insert_columns(part_t, {k: part[k].to_numpy() for k in L.TPCH_PART_COLS}, parallel=False)
        n_part = int(self.db.query("sql", f"SELECT count(*) AS n FROM {part_t}").to_list()[0]["n"])
        if n_part != len(part):
            raise SystemExit(f"{part_t} stored {n_part:,} of {len(part):,} rows")
        self.sql(f"CREATE INDEX ON {li_t} (l_shipdate) NOTUNIQUE")
        return {"lineitem": stored, "part": n_part, "types": [li_t, part_t], "writers": writers}

    # -- the rule and the statements -------------------------------------------------------------------------------------------
    def _setting(self):
        return getattr(self.jpype.JClass("com.arcadedb.GlobalConfiguration"), self.c["rule_setting"])

    def set_rule(self, on):
        cfg = self.db.get_java_database().getConfiguration()
        cfg.setValue(self._setting(), self.jpype.JClass("java.lang.Boolean").valueOf(bool(on)))

    def get_rule(self):
        return bool(self.db.get_java_database().getConfiguration().getValueAsBoolean(self._setting()))

    def run(self, lang, text):
        return self.db.query(lang, text).count()

    def table(self, lang, text):
        return L.to_table(self.db.query(lang, text).to_list())

    def explain(self, text):
        """The plan of THIS statement text as the engine ran it: the execution plan of the result set itself (the form the engine's
        own tests read), so a plan cached under the other setting would show. EXPLAIN is the fallback when the result has none."""
        rs = self.db.query("postgresql", text)
        try:
            plan = rs._java_result_set.getExecutionPlan()
            if plan.isPresent():
                return str(plan.get().prettyPrint(0, 2))
        except Exception:               # noqa: BLE001  (no plan on the result: use EXPLAIN below)
            pass
        finally:
            rs.close()
        rows = self.db.query("postgresql", "EXPLAIN " + text).to_list()
        return "\n".join(str(v) for d in rows for v in d.values())

    def gc_stats(self):
        """Count and total milliseconds of the JVM's collections so far (java.lang.management), recorded around every turn of pair 1 for a reader of
        the raw files. No rule reads it. None when the runtime image has no management module (nothing is lost: the run goes on)."""
        try:
            beans = self.jpype.JClass("java.lang.management.ManagementFactory").getGarbageCollectorMXBeans()
            return {"count": sum(int(b.getCollectionCount()) for b in beans), "ms": sum(int(b.getCollectionTime()) for b in beans), "n_beans": int(beans.size())}
        except Exception:               # noqa: BLE001
            return None

    def close(self):
        if self.db is not None:
            self.db.close()
            self.db = None
