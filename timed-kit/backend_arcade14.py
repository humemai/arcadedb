"""The pg14 engine side: the backend of pg13 (backend_arcade.ArcadeBackend, a byte-for-byte copy of the version 3 file: engine gates, the swapped
jar, the rule, plans, GC counts) plus the loader with the `created` column, the write types of the arms and the calls of the core tasks,
and (the memory amendment) the allocation counters of the JVM: the calling thread's per call, the other threads' per pass, the process figures per block;
and (the CPU and disk amendment) the CPU clock of the calling thread, the size of the database folder and the probe of /proc/self/io."""
from __future__ import annotations

import os
import time

import backend_arcade as B
import pg13lib as L
import pg14lib as P14

PROPS = {"id": "LONG", "name": "STRING", "age": "INTEGER", "city": "STRING", "created": "LONG"}


class Arcade14(B.ArcadeBackend):
    name = "arcade14"

    def load_snb14(self, c, persons):
        """The pg13 graph (the same persons and edges, the same loader) with one more property on Person: `created`, the creationDate of the CSV row."""
        ddl = ["CREATE VERTEX TYPE Person"] + [f"CREATE PROPERTY Person.{k} {v}" for k, v in PROPS.items()] + ["CREATE INDEX ON Person (id) UNIQUE"]
        for d in ddl:
            self.sql(d)
        try:   # the endpoint tables are declared, never inferred (fork issue #1 question 11)
            self.sql("CREATE EDGE TYPE KNOWS CUSTOM source_table = 'Person', target_table = 'Person'")
        except Exception:               # noqa: BLE001
            self.sql("CREATE EDGE TYPE KNOWS")
            t = self.db.get_java_database().getSchema().getType("KNOWS")
            t.setCustomValue("source_table", "Person")
            t.setCustomValue("target_table", "Person")
        self.sql("CREATE PROPERTY KNOWS.since INTEGER")
        edges = list(L.gen_edges(c["snb_scale"], c["person_limit"]))
        rid = {}
        with self.db.graph_batch(use_wal=True, expected_edge_count=len(edges)) as gb:
            for s in range(0, len(persons), B.INGEST_BATCH):
                chunk = persons[s:s + B.INGEST_BATCH]
                for p, r in zip(chunk, gb.create_vertices("Person", chunk)):
                    rid[p["id"]] = r
            for s in range(0, len(edges), B.INGEST_BATCH):
                chunk = edges[s:s + B.INGEST_BATCH]
                gb.new_edges([rid[a] for a, _, _ in chunk], "KNOWS", [rid[b] for _, b, _ in chunk], [{"since": y} for _, _, y in chunk])
        t = self.db.get_java_database().getSchema().getType("KNOWS")
        declared = (str(t.getCustomValue("source_table")), str(t.getCustomValue("target_table")))
        n_p = int(self.db.query("sql", "SELECT count(*) AS n FROM Person").to_list()[0]["n"])
        n_k = int(self.db.query("sql", "SELECT count(*) AS n FROM KNOWS").to_list()[0]["n"])
        return {"persons_streamed": len(persons), "knows_streamed": len(edges), "persons": n_p, "knows": n_k, "endpoints": declared}

    def create_mem(self, tbl, persons):
        """A vertex type with the five properties and a unique index on id, seeded with the persons (the table a write arm works on)."""
        for d in [f"CREATE VERTEX TYPE {tbl}"] + [f"CREATE PROPERTY {tbl}.{k} {v}" for k, v in PROPS.items()] + [f"CREATE INDEX ON {tbl} (id) UNIQUE"]:
            self.sql(d)
        with self.db.graph_batch(use_wal=True, expected_edge_count=1) as gb:
            for s in range(0, len(persons), B.INGEST_BATCH):
                gb.create_vertices(tbl, persons[s:s + B.INGEST_BATCH])
        n = int(self.db.query("sql", f"SELECT count(*) AS n FROM {tbl}").to_list()[0]["n"])
        if n != len(persons):
            raise SystemExit(f"{tbl} stored {n} of {len(persons)} seed rows")
        return n

    def run_read(self, lang, text):
        """The call of a read task: the statement, then the result drained (the pg13 call)."""
        return self.db.query(lang, text).count()

    def run_write(self, lang, text):
        """The call of a write task: begin, the statement, its result drained, commit: one transaction. Returns 0 (rows are not the figure of a write)."""
        db = self.db
        db.begin()
        try:
            rs = db.command(lang, text)
            if rs is not None:
                rs.count()
            db.commit()
        except BaseException:
            db.rollback()
            raise
        return 0

    def run_plain(self, lang, text):
        """An untimed statement in its own transaction (the cleanup of t9, the read-back of the check)."""
        return self.run_write(lang, text)

    def readback(self, lang, tbl):
        """The check table: every row of the type, as the engine's reader gives it (columns sorted by name, canonical cells)."""
        return L.to_table(self.db.query(lang, f"SELECT id, name, age, city, created FROM {tbl} ORDER BY id").to_list())

    # -- memory (the amendment of 2026-11-24): the allocation of the calling thread, of the other threads, and the process --------------------------
    alloc = staticmethod(lambda: 0)         # replaced by mem_setup() with the counter of the calling thread (a probe that fails leaves this one: every call reads 0)
    mem_ok = False
    others_errors = 0

    def mem_setup(self):
        """Find the counter, prove it moves, measure what reading it costs. Never raises: a figure that cannot be had is recorded as such (mem_ok False) and the
        run goes on, because the memory reading is a second reading and never stops the first. Returns the facts for env.jsonl."""
        j = self.jpype
        f = {"ok": False}
        try:
            mf = j.JClass("java.lang.management.ManagementFactory")
            mx = j.JObject(mf.getThreadMXBean(), j.JClass("com.sun.management.ThreadMXBean"))
            f["supported"] = bool(mx.isThreadAllocatedMemorySupported())
            if f["supported"] and not bool(mx.isThreadAllocatedMemoryEnabled()):
                mx.setThreadAllocatedMemoryEnabled(True)
            f["enabled"] = bool(mx.isThreadAllocatedMemoryEnabled())
            me = j.JClass("java.lang.Thread").currentThread()
            f["thread"] = str(me.getName())
            self._mx, self._tid = mx, int(me.threadId())
            f["java_version"] = str(j.JClass("java.lang.System").getProperty("java.version"))
            f["gc"] = [str(b.getName()) for b in mf.getGarbageCollectorMXBeans()]
            read = mx.getCurrentThreadAllocatedBytes
            # the counter moves by the size of a 1 MiB array that this thread allocates
            a0 = int(read())
            keep = j.JArray(j.JByte)(1 << 20)
            a1 = int(read())
            f["moves_by_1mib_array"] = a1 - a0
            f["moves_ok"] = P14.MEM_PROBE_MOVES[0] <= a1 - a0 <= P14.MEM_PROBE_MOVES[1] and len(keep) == 1 << 20
            # what a read costs, and what two reads with nothing between them see (the offset that every call's figure contains)
            deltas, t_read = [], []
            for _ in range(2000):
                a0 = int(read())
                x0 = time.perf_counter_ns()
                a1 = int(read())
                t_read.append(time.perf_counter_ns() - x0)
                deltas.append(a1 - a0)
            f["null_pair_bytes_median"] = L.median(deltas)
            f["null_pair_bytes_max"] = max(deltas)
            f["read_ns_median"] = L.median(t_read)
            # the other threads: a sample of every live thread but this one
            f["threads_live"] = len(self.others_sample())
            f["others_ok"] = f["threads_live"] > 0 and self.others_errors == 0
            samples = []
            for _ in range(200):
                x0 = time.perf_counter_ns()
                self.others_sample()
                samples.append(time.perf_counter_ns() - x0)
            f["others_sample_ns_median"] = L.median(samples)
            self.alloc = read
            self.mem_ok = bool(f["supported"] and f["enabled"] and f["moves_ok"])
            f["ok"] = self.mem_ok
        except Exception as e:          # noqa: BLE001
            f["error"] = f"{type(e).__name__}: {str(e)[:300]}"
        return f

    def others_sample(self):
        """{thread id: bytes allocated so far} of every live thread but the calling one (the other threads' work: scan producers, flush threads, workers)."""
        try:
            ids = self._mx.getAllThreadIds()
            vals = self._mx.getThreadAllocatedBytes(ids)
            return {int(i): int(v) for i, v in zip(ids, vals) if int(i) != self._tid and int(v) >= 0}
        except Exception:               # noqa: BLE001  (counted, and the probe's threads_live and others_errors say so: the calling thread's figure stands)
            self.others_errors += 1
            return {}

    def block_figures(self):
        """The process figures at the end of a task block: peak resident set since the reset at the block's start and the resident set now, then a full
        collection (System.gc, outside every timed window) and the heap in use after it. kB for the resident set, bytes for the heap."""
        j = self.jpype
        hwm, rss = P14.proc_status_kb("VmHWM"), P14.proc_status_kb("VmRSS")
        g0 = self.gc_stats()
        t0 = time.perf_counter()
        j.JClass("java.lang.System").gc()
        gc_s = time.perf_counter() - t0
        g1 = self.gc_stats()
        rt = j.JClass("java.lang.Runtime").getRuntime()
        return {"hwm_kb": hwm, "rss_kb": rss, "heap_used_after_gc": int(rt.totalMemory()) - int(rt.freeMemory()), "heap_committed": int(rt.totalMemory()),
                "rss_kb_after_gc": P14.proc_status_kb("VmRSS"), "gc_forced_s": gc_s,
                "gc_forced_count": None if not g0 or not g1 else g1["count"] - g0["count"]}

    # -- CPU and disk (the second amendment of 2026-11-24) ---------------------------------------------------------------------------------------
    cpu = staticmethod(lambda: 0)           # replaced by cpu_setup() with the CPU clock of the calling thread (a probe that fails leaves this one: every call reads 0)
    cpu_ok = False

    def cpu_setup(self):
        """Find the thread CPU clock, prove it agrees with Python's own clock of the same thread over a spin, measure what a read costs and what the null pair sees
        (two reads with the two clock reads of the timer between them: the constant that every call's figure contains and the reader subtracts). Never raises."""
        f = {"ok": False}
        try:
            mx = self._mx
            f["supported"] = bool(mx.isCurrentThreadCpuTimeSupported())
            if f["supported"] and not bool(mx.isThreadCpuTimeEnabled()):
                mx.setThreadCpuTimeEnabled(True)
            f["enabled"] = bool(mx.isThreadCpuTimeEnabled())
            read = mx.getCurrentThreadCpuTime
            p0, c0 = time.thread_time_ns(), int(read())
            while time.thread_time_ns() - p0 < 20_000_000:
                pass
            p1, c1 = time.thread_time_ns(), int(read())
            f["spin_python_ns"], f["spin_jvm_ns"] = p1 - p0, c1 - c0
            f["agrees_with_python_thread_time"] = abs((c1 - c0) - (p1 - p0)) <= P14.CPU_PROBE_AGREE[0] * (p1 - p0) + P14.CPU_PROBE_AGREE[1]
            deltas, t_read = [], []
            for _ in range(2000):
                c0 = int(read())
                x0 = time.perf_counter_ns()
                x1 = time.perf_counter_ns()
                c1 = int(read())
                t_read.append(x1 - x0)
                deltas.append(c1 - c0)
            f["null_pair_cpu_ns_median"] = L.median(deltas)
            f["null_pair_cpu_ns_max"] = max(deltas)
            f["null_pair_cpu_ns_p90"] = sorted(deltas)[int(0.9 * len(deltas))]
            rd = []
            for _ in range(2000):
                x0 = time.perf_counter_ns()
                read()
                rd.append(time.perf_counter_ns() - x0)
            f["cpu_read_wall_ns_median"] = L.median(rd)
            p0, w0 = time.process_time_ns(), time.perf_counter_ns()
            time.sleep(1.0)
            f["idle_cores_busy_1s"] = round((time.process_time_ns() - p0) / (time.perf_counter_ns() - w0), 3)      # the process while this thread sleeps: JIT, GC, the engine's own threads
            self.cpu = read
            self.cpu_ok = bool(f["supported"] and f["enabled"] and f["agrees_with_python_thread_time"])
            f["ok"] = self.cpu_ok
        except Exception as e:          # noqa: BLE001
            f["error"] = f"{type(e).__name__}: {str(e)[:300]}"
        return f

    def db_size(self):
        """Size of the databases folder of this run: bytes on disk (allocated blocks) and apparent bytes (file sizes), and the number of files."""
        disk = apparent = n = 0
        for dirpath, _, names in os.walk(self.root / "db"):
            for name in names:
                try:
                    st = os.lstat(os.path.join(dirpath, name))
                except OSError:
                    continue
                disk += st.st_blocks * 512
                apparent += st.st_size
                n += 1
        return {"disk": disk, "apparent": apparent, "files": n}

    def drain_flush(self):
        """Wait until the engine's page flush thread has written what a write turn queued (PageManager.waitAllPagesOfDatabaseAreFlushed), and return what /proc/self/io
        counted meanwhile, so that those write calls belong to the turn that caused them. Called at the end of a write turn, outside every timed window. {} on failure."""
        try:
            jdb = self.db.get_java_database()
            io0 = P14.proc_io()
            jdb.getPageManager().waitAllPagesOfDatabaseAreFlushed(jdb)
            io1 = P14.proc_io()
            return {k: io1[k] - io0[k] for k in io1 if k in io0}
        except Exception:               # noqa: BLE001
            self.drain_errors += 1
            return {}

    drain_errors = 0

    def disk_setup(self):
        """Which directory and file system hold the database, whether /proc/self/io counts a write of 4 MiB to it (wchar always, write_bytes when the data reaches the
        storage layer: a tmpfs or a page-cache-served directory shows read_bytes zero), and the size of the folder now. Never raises."""
        f = {"ok": False}
        try:
            d = (self.root / "db").resolve()
            f["db_path"] = str(d)
            f["mount"], f["fstype"] = P14.mount_of(str(d))
            f["ram_backed"] = f["fstype"] in ("tmpfs", "ramfs")
            io0 = P14.proc_io()
            probe = d / "io-probe.bin"
            with open(probe, "wb", buffering=0) as fh:
                fh.write(b"\0" * P14.IO_PROBE_BYTES)
                os.fsync(fh.fileno())
            io1 = P14.proc_io()
            probe.unlink()
            f["probe_bytes"] = P14.IO_PROBE_BYTES
            f["probe_wchar"] = io1.get("wchar", 0) - io0.get("wchar", 0)
            f["probe_write_bytes"] = io1.get("write_bytes", 0) - io0.get("write_bytes", 0)
            f["probe_syscw"] = io1.get("syscw", 0) - io0.get("syscw", 0)
            f["io_keys_present"] = all(k in io0 for k in P14.IO_KEYS)
            f["wchar_ok"] = P14.IO_PROBE_BYTES <= f["probe_wchar"] <= P14.IO_PROBE_BYTES + (1 << 16)
            f["size_now"] = self.db_size()
            f["ok"] = bool(f["io_keys_present"] and f["wchar_ok"])
        except Exception as e:          # noqa: BLE001
            f["error"] = f"{type(e).__name__}: {str(e)[:300]}"
        return f
