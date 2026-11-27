"""The pg14 study (DRAFT), phases `ref`, `main` and `smoke`:  study14.py <phase> <out-dir>.

ref    PostgreSQL 18 (the server pg_ref_server.sh started): loads the graph and the write tables, answers every read statement, runs the write
       check against the oracle, times every task (reference points, arm R). Writes <out>/ref/answers.json and done.json.
main   the engine in the swapped-jar JVM: loads the graph and the write types of the four arms, checks every answer against ref and across the
       arms BEFORE any timing (reads as multisets, writes by the checksum of the read-back table after each step), then times the tasks.
smoke  ref then main at the caps of the environment (a tiny load, one measured round): a rehearsal, never a result.

The memory reading (amendment of 2026-11-24): every timed call is bracketed by two reads of the calling thread's allocation counter, outside the timed window; every
pass by a sample of the other threads; every task block of a round ends with the process figures (peak resident set, heap after a full collection). A second
reading, it stops nothing and changes no latency figure.

The CPU and disk amendment (the same day): every call is also bracketed by two reads of the calling thread's CPU clock (innermost), every pass by the process's CPU time and
/proc/self/io, every task block by the same and by the size of the database folder. Two more readings, they stop nothing and change no latency figure.

Exit status: 0 done, 4 a gate failed (engine, jar, setting, plan, load, reference, a table that did not come back to its size), 6 an answer differed or
a statement failed (a finding, the run is complete), 1 broke. The reader (read_results14.py) adds 3 when an A/A control left its band."""
from __future__ import annotations

import importlib.metadata as md
import json
import os
import platform
import sys
import time
import traceback
from pathlib import Path

import pg13lib as L
import pg14lib as P14

CHECK_STEPS = (("after t6", "t6"), ("after t7", "t7"), ("after t8", "t8"), ("after t9", "t9"))


def pkg_versions():
    out = {}
    for n in ("arcadedb-jvm", "arcadedb", "pyarrow", "numpy", "pandas", "psycopg", "JPype1"):
        try:
            out[n] = md.version(n)
        except md.PackageNotFoundError:
            pass
    return out


def lane_facts():
    allowed = ""
    for line in Path("/proc/self/status").read_text().splitlines():
        if line.startswith("Cpus_allowed_list"):
            allowed = line.split(":", 1)[1].strip()
    keys = ("HUMEM_HOST_LANE", "HUMEM_HOST_CPUS", "HUMEM_HOST_LANE_CLASS", "HUMEM_HOST_CELL_CLASS")
    return {"cpus_allowed_list": allowed, "nproc": os.cpu_count(), **{k.lower(): os.environ.get(k) for k in keys}}


def env_record(out, phase, c):
    out.write("env", {"phase": phase, "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "host": platform.node(), "kernel": platform.release(),
                      "python": sys.version.split()[0], "packages": pkg_versions(), "lane": lane_facts(), "caps": P14.caps_of(c),
                      "rounds_measured": c["rounds"], "rounds_warmup": L.ROUNDS_WARMUP, "smoke": c["smoke"], "heap": c["heap"], "join_memory_mb": c["join_memory_mb"],
                      "engine_commit": os.environ.get("PG13_ENGINE_COMMIT"), "engine_jar_sha256": os.environ.get("PG13_ENGINE_JAR_SHA256"),
                      "traversal_model": c["plan_model"], "study_role": c["study_role"], "local_rehearsal": os.environ.get("PG14_LOCAL") == "1",
                      "mmdbbench_commit": os.environ.get("PG13_MMDBBENCH_COMMIT"), "pg_image": os.environ.get("PG13_PG_IMAGE"),
                      "passes": {t: P14.passes_of_task(c, t) for t in P14.TASK_IDS}})


def answer_key(task, x):
    return "t3" if task == "t3" else f"{task}:{x}"


def distinct_params(w, task):
    return list(dict.fromkeys(P14.read_params(w, task)))


def checkpoint_sums(w):
    """The checksum of each state of the oracle: [(label, sha256)] after t6, t7, t8, t9 and the cleanup of t9."""
    return [(label, P14.state_checksum(state)) for label, state in P14.apply_check_steps(w)]


def run_check_writes(out, w, runner, readers, tbl, arm):
    """The write check of one arm on its check table: the four write tasks of pass 0 of round 0, a read-back after each step (and after the cleanup of t9),
    each compared with the oracle. runner(lang, text) runs one statement in its own transaction, readers() gives a list of (reader name, (names, rows)).
    Returns (ok, detail, [(label, {reader: checksum})])."""
    want = checkpoint_sums(w)
    got, bad = [], []
    for (label, task), (_, wsum) in zip(CHECK_STEPS, want[:4]):
        units, cleanup = P14.write_pass(w, task, arm, tbl, 0, 0)
        for lg, tx in units:
            runner(lg, tx)
        sums = {name: P14.table_checksum(*tb) for name, tb in readers()}
        got.append((label, sums))
        bad += [f"{label}: {name} reads {s[:12]}, oracle {wsum[:12]}" for name, s in sums.items() if s != wsum]
        if cleanup:
            runner(*cleanup)
            sums = {name: P14.table_checksum(*tb) for name, tb in readers()}
            got.append(("after the cleanup of t9", sums))
            bad += [f"after the cleanup of t9: {name} reads {s[:12]}, oracle {want[4][1][:12]}" for name, s in sums.items() if s != want[4][1]]
    return not bad, "; ".join(bad[:4]) or f"{len(got)} read-backs equal the oracle", got


# ---------------------------------------------------------------------------------------------------------------------------------
def run_ref(out, c, ref_dir):
    import backend_pg14
    w = P14.World(c)
    be = backend_pg14.PG14Backend(c)
    out.write("env", {"ref_server": be.facts()})
    t0 = time.perf_counter()
    snb = be.load_snb14(c, w.persons)
    memr = [be.create_mem(P14.MEM["R"], w.persons), be.create_mem(P14.CHK["R"], w.persons)]
    out.write("env", {"ref_load": {"snb": snb, "mem": memr, "s": time.perf_counter() - t0}})
    answers = {}
    for t in P14.READ_TASKS:
        for x in distinct_params(w, t):
            lg, tx = P14.read_text(t, "R", x)
            try:
                names, rows = be.table(tx)
                answers[answer_key(t, x)] = {"cols": names, "rows": rows}
            except Exception as e:      # noqa: BLE001
                answers[answer_key(t, x)] = {"error": f"{type(e).__name__}: {e}"}
                out.gate(f"reference answers {answer_key(t, x)}", False, answers[answer_key(t, x)]["error"])
    ok, detail, cps = run_check_writes(out, w, lambda lg, tx: be.run_write(tx), lambda: [("postgresql 18", be.readback(P14.CHK["R"]))], P14.CHK["R"], "R")
    out.gate("reference: the write check equals the oracle after every step", ok, detail)
    ref_ok = ok and not [k for k, v in answers.items() if "error" in v]
    # reference timing: every task, one turn per round, a fifth of the passes of the engine arms (a reference point, never a ratio's denominator)
    for r in range(L.ROUNDS_WARMUP + c["rounds"]):
        for t in P14.TASK_IDS:
            kp = max(1, P14.passes_of_task(c, t) // 5)
            ns_all, rows = [], 0
            for j in range(kp):
                if P14.TASKS[t]["kind"] == "read":
                    units = P14.read_pass_texts(w, t, "R")
                    cleanup = None
                else:
                    units, cleanup = P14.write_pass(w, t, "R", P14.MEM["R"], r, j)
                for lg, tx in units:
                    t1 = time.perf_counter_ns()
                    rows += be.run_read(tx) if P14.TASKS[t]["kind"] == "read" else be.run_write(tx)
                    ns_all.append(time.perf_counter_ns() - t1)
                if cleanup:
                    be.run_write(cleanup[1])
            out.write("ref_timing", {"task": t, "round": r, "warm": r < L.ROUNDS_WARMUP, "calls": len(ns_all), "passes": kp,
                                     "mean_ns": sum(ns_all) / len(ns_all), "rows": rows})
    server_facts = be.facts()
    be.close()
    ref_dir = Path(ref_dir)
    ref_dir.mkdir(parents=True, exist_ok=True)
    (ref_dir / "answers.json").write_text(json.dumps(answers, sort_keys=True), encoding="utf-8")
    (ref_dir / "done.json").write_text(json.dumps({"caps": P14.caps_of(c), "digest": P14.statements_digest(w), "n_answers": len(answers),
                                                   "checkpoints": cps, "server": server_facts, "load": {"snb": snb}}, sort_keys=True), encoding="utf-8")
    return 0 if ref_ok else 4


# ---------------------------------------------------------------------------------------------------------------------------------
def check_reads(out, be, ref, w):
    """Every read statement, in P, S and C, against the reference answer and P against S; returns {(task, arm): first failure}."""
    bad = {}
    for t in P14.READ_TASKS:
        n = 0
        for x in distinct_params(w, t):
            n += 1
            r = ref.get(answer_key(t, x), {})
            refv = None if "rows" not in r else (r["cols"], r["rows"])
            got = {}
            for arm in ("P", "S", "C"):
                lg, tx = P14.read_text(t, arm, x)
                try:
                    got[arm] = be.table(lg, tx)
                except Exception as e:  # noqa: BLE001
                    bad.setdefault((t, arm), f"{answer_key(t, x)}: {type(e).__name__}: {str(e)[:300]}")
            for arm, tb in got.items():
                if refv is None:
                    bad.setdefault((t, arm), f"{answer_key(t, x)}: no reference answer")
                    continue
                ok, detail = L.multiset_equal(tb, refv)
                if not ok:
                    bad.setdefault((t, arm), f"{answer_key(t, x)}: {arm}=R {detail[:300]}")
            if "P" in got and "S" in got:
                ok, detail = L.multiset_equal(got["P"], got["S"])
                if not ok:
                    bad.setdefault((t, "P"), f"{answer_key(t, x)}: P=S {detail[:300]}")
        arms_bad = sorted(a for (tt, a) in bad if tt == t)
        out.gate(f"answers of {t} equal as multisets (P, S, C, PostgreSQL 18), {n} statements", not arms_bad,
                 "all equal" if not arms_bad else "; ".join(f"{a}: {bad[(t, a)]}" for a in arms_bad))
    return bad


def check_writes(out, be, w, ref_cps):
    """The write check of the four arms. Returns {arm: detail} of the arms that failed it."""
    bad = {}
    for arm in ("P", "S", "C", "Z"):
        tbl = P14.CHK[arm]
        be.create_mem(tbl, w.persons)
        low = P14.tbl_text("P", tbl)
        try:
            ok, detail, _ = run_check_writes(out, w, be.run_plain, lambda tbl=tbl, low=low: [("native SQL", be.readback("sql", tbl)), ("PostgreSQL language", be.readback("postgresql", low))],
                                              tbl, arm)
        except Exception as e:          # noqa: BLE001
            ok, detail = False, f"{type(e).__name__}: {str(e)[:400]}"
        out.gate(f"write check of arm {arm} ({P14.ARM_NAMES[arm]}): the read-back equals the oracle after every step, in both readers", ok, detail)
        if not ok:
            bad[arm] = detail
    ref_sums = {label: sums for label, sums in ref_cps}
    want = dict(checkpoint_sums(w))
    out.gate("the reference's read-backs are the oracle's", all(s.get("postgresql 18") == want[label] for label, s in ref_sums.items()), f"{len(ref_sums)} checkpoints")
    return bad


def plan_records(out, be, w, model, token):
    """The plan of the PostgreSQL language for the first statement of each read task, as the engine ran it (kept for the post); t5 has a gate: the
    walk of the traversal rule, as the model of the engine says (a bound start)."""
    ok_all = True
    for t in P14.READ_TASKS:
        lg, tx = P14.read_text(t, "P", P14.read_params(w, t)[0])
        plan = be.explain(tx)
        out.write("plans", {"task": t, "text": tx, "plan": plan})
        if t == "t5":
            ok, detail = L.plan_on_check(model, "p1", plan, token)
            ok_all &= out.gate(f"plan of t5 ({model}): the join walks from the bound start (the rule at its default)", ok, detail)
    return ok_all


def final_check(out, be, w, c, arms_w):
    """After the last round: every write arm's table equals the oracle's final state (the seed with the ages of the last round)."""
    want = P14.state_checksum(P14.final_state(w, c))
    for arm in arms_w:
        tbl = P14.MEM[arm]
        got = P14.table_checksum(*be.readback("sql", tbl))
        out.gate(f"final table of arm {arm} equals the oracle", got == want, f"read {got[:12]}, oracle {want[:12]}")


def counts_check(out, be, w, arms_w, r):
    ok = True
    for arm in arms_w:
        n = int(be.db.query("sql", f"SELECT count(*) AS n FROM {P14.MEM[arm]}").to_list()[0]["n"])
        ok &= n == len(w.persons)
    return out.gate(f"every write table is back to its seed size after round {r}", ok, f"{len(w.persons)} rows expected")


def do_pass(be, w, t, arm, r, j, units_cache):
    """One pass of task t by arm: the timed calls, then (t9) the untimed cleanup. Returns (call times in ns, rows, the calling thread's allocation of each call in
    bytes, the other threads' allocation during the pass in bytes, the threads that ended during the pass, the calling thread's CPU time of each call in ns, and a dict
    of the pass: wall time, the calling thread's CPU time and the process's CPU time over the calls, and the deltas of /proc/self/io)."""
    kind = P14.TASKS[t]["kind"]
    if kind == "read":
        units = units_cache[(t, arm)]
        run, cleanup = be.run_read, None
    else:
        units, cleanup = P14.write_pass(w, t, arm, P14.MEM[arm], r, j)
        run = be.run_write
    ns, ab, cb, rows = [], [], [], 0
    alloc, cpu = be.alloc, be.cpu
    o0 = be.others_sample()
    p0, io0 = time.process_time_ns(), P14.proc_io()
    tc0, w0 = int(cpu()), time.perf_counter_ns()
    for lg, tx in units:
        a0 = alloc()                              # the counters are read outside the timed window, before it and after it; the CPU clock of the thread is the innermost
        c0 = cpu()
        t0 = time.perf_counter_ns()
        rows += run(lg, tx)
        t1 = time.perf_counter_ns()
        c1 = cpu()
        a1 = alloc()
        ns.append(t1 - t0)
        ab.append(int(a1) - int(a0))
        cb.append(int(c1) - int(c0))
    w1, tc1 = time.perf_counter_ns(), int(cpu())
    p1, io1 = time.process_time_ns(), P14.proc_io()
    other, lost = P14.others_delta(o0, be.others_sample())
    if cleanup:
        be.run_plain(*cleanup)
    io = {k: io1[k] - io0[k] for k in io1 if k in io0} if io0 and io1 else {}
    return ns, rows, ab, other, lost, cb, {"wall_ns": w1 - w0, "thr_cpu_ns": tc1 - tc0, "proc_cpu_ns": p1 - p0, "io": io}


def record(out, t, arm, r, warm, ns_passes, rows, t0, t1, g0, g1, extra=None, mem=None):
    ns = [x for p in ns_passes for x in p]
    rec = {"task": t, "arm": arm, "round": r, "warm": warm, "calls": len(ns), "passes": len(ns_passes), "mean_ns": sum(ns) / len(ns), "median_call_ns": L.median(ns),
           "max_call_ns": max(ns), "rows": rows, "t0": t0, "t1": t1, "pass_mean_ns": [sum(p) / len(p) for p in ns_passes], "gc_before": g0, "gc_after": g1}
    rec.update(extra or {})
    if mem is not None:                           # mem = [(call bytes, other bytes, lost threads, call CPU ns, the pass dict) of each pass]
        ab = [x for m in mem for x in m[0]]
        rec.update(alloc_mean_bytes=sum(ab) / len(ab), alloc_median_call_bytes=L.median(ab), alloc_max_call_bytes=max(ab), alloc_min_call_bytes=min(ab),
                   others_mean_bytes=sum(m[1] for m in mem) / len(ab), others_lost_threads=sum(m[2] for m in mem),
                   alloc_pass_mean_bytes=[sum(m[0]) / len(m[0]) for m in mem])
        cb = [x for m in mem for x in m[3]]
        io = {k: sum(m[4]["io"].get(k, 0) for m in mem) for k in P14.IO_KEYS}
        rec.update(cpu_mean_ns=sum(cb) / len(cb), cpu_median_call_ns=L.median(cb), cpu_min_call_ns=min(cb), cpu_max_call_ns=max(cb),
                   thr_cpu_pass_ns=sum(m[4]["thr_cpu_ns"] for m in mem), proc_cpu_pass_ns=sum(m[4]["proc_cpu_ns"] for m in mem), pass_wall_ns=sum(m[4]["wall_ns"] for m in mem),
                   io_per_call={k: v / len(cb) for k, v in io.items()}, io_total=io)
    out.write("turns", rec)


def add_io(passinfo, drained):
    """Add the /proc/self/io counted while a write turn's flush was drained to the last pass of that turn."""
    for k, v in drained.items():
        passinfo["io"][k] = passinfo["io"].get(k, 0) + v


def block_mark(be):
    """The process's counters at the start or the end of a task block: wall and CPU time, user and system seconds, /proc/self/io, the size of the database folder."""
    return {"wall": time.perf_counter_ns(), "cpu": time.process_time_ns(), "split": P14.proc_cpu_split_s(), "io": P14.proc_io(), "db": be.db_size()}


def block_delta(b0, b1):
    io = {k: b1["io"][k] - b0["io"][k] for k in b1["io"] if k in b0["io"]} if b0["io"] and b1["io"] else {}
    return {"wall_s": (b1["wall"] - b0["wall"]) / 1e9, "cpu_s": (b1["cpu"] - b0["cpu"]) / 1e9, "cpu_user_s": b1["split"][0] - b0["split"][0],
            "cpu_sys_s": b1["split"][1] - b0["split"][1], "io": io, "db_disk_before": b0["db"]["disk"], "db_disk_after": b1["db"]["disk"],
            "db_apparent_before": b0["db"]["apparent"], "db_apparent_after": b1["db"]["apparent"], "db_files_after": b1["db"]["files"]}


def time_all(out, be, c, w, arms_by_task, arms_w):
    cache = {(t, a): P14.read_pass_texts(w, t, a) for t in P14.READ_TASKS for a in arms_by_task.get(t, ())}
    for r in range(L.ROUNDS_WARMUP + c["rounds"]):
        warm = r < L.ROUNDS_WARMUP
        for t in P14.TASK_IDS:
            arms = arms_by_task.get(t)
            if not arms:
                continue
            k = P14.passes_of_task(c, t)
            hwm_reset = P14.reset_peak_rss()          # the process figures of this task block start here (outside every timed window)
            b0 = block_mark(be)
            for turn, first in P14.task_turns(r, arms):
                if turn != "SZ":
                    g0, t0 = be.gc_stats(), time.time()
                    res = [do_pass(be, w, t, turn, r, j, cache) for j in range(k)]
                    t1, g1 = time.time(), be.gc_stats()
                    if P14.TASKS[t]["kind"] != "read":    # the write calls of the last pass belong to this turn, not to the next one
                        add_io(res[-1][6], be.drain_flush())
                    record(out, t, turn, r, warm, [x[0] for x in res], sum(x[1] for x in res), t0, t1, g0, g1, mem=[x[2:] for x in res])
                    continue
                seq = P14.abba_sz(first, k)
                done = {"S": [], "Z": []}
                mem = {"S": [], "Z": []}
                rows = {"S": 0, "Z": 0}
                pass_t0 = []
                g0, t0 = be.gc_stats(), time.time()
                for arm in seq:
                    pass_t0.append(time.time())
                    ns, nrows, *info = do_pass(be, w, t, arm, r, len(done[arm]), cache)
                    done[arm].append(ns)
                    mem[arm].append(tuple(info))
                    rows[arm] += nrows
                t1, g1 = time.time(), be.gc_stats()
                if P14.TASKS[t]["kind"] != "read":
                    add_io(mem[seq[-1]][-1][4], be.drain_flush())
                for arm in ("S", "Z"):
                    record(out, t, arm, r, warm, done[arm], rows[arm], t0, t1, g0, g1, {"block": "SZ", "block_first": first, "pass_seq": "".join(seq), "pass_t0": pass_t0}, mem=mem[arm])
            b1 = block_mark(be)                       # before the forced collection of block_figures: its time and CPU are not the block's
            out.write("blocks", {"task": t, "round": r, "warm": warm, "hwm_reset": hwm_reset, **block_delta(b0, b1), **be.block_figures()})
        if arms_w and not counts_check(out, be, w, arms_w, r):
            return False
    return True


def run_main(out, c, ref_dir):
    import backend_arcade14
    w = P14.World(c)
    ref_dir = Path(ref_dir)
    if not (ref_dir / "done.json").exists():
        out.gate("reference answers present", False, f"{ref_dir}/done.json missing: run the ref phase first")
        return 4
    meta = json.loads((ref_dir / "done.json").read_text())
    ref = json.loads((ref_dir / "answers.json").read_text())
    if not out.gate("reference was made with these caps and these statements", meta["caps"] == P14.caps_of(c) and meta["digest"] == P14.statements_digest(w),
                    f"ref caps {meta['caps']} now {P14.caps_of(c)}; digest equal {meta['digest'] == P14.statements_digest(w)}"):
        return 4
    be = backend_arcade14.Arcade14(c, out.root, os.environ["PG13_ENGINE_JAR_IN_ARM"])
    out.write("env", {"engine_side": be.facts()})
    token, model = c["plan_token"], c["plan_model"]
    rc = 0
    be.create("snb")
    import arcadedb
    out.gate("the arcadedb package is the arm's copy", os.path.realpath(arcadedb.__file__).startswith(str(out.root.resolve() / "arm")), arcadedb.__file__)
    eok, info = be.engine_gates(out)
    if not eok:
        return 4
    want = os.environ.get("PG13_ENGINE_COMMIT", "")[:12]
    if not out.gate("the engine reports the pinned commit as its build number", len(want) == 12 and info["build"] == want, f"engine says {info}, pinned {want}"):
        return 4
    t0 = time.perf_counter()
    snb = be.load_snb14(c, w.persons)
    out.write("env", {"load_snb": snb, "load_snb_s": time.perf_counter() - t0})
    if not out.gate("SNB load stored every streamed row", snb["persons"] == snb["persons_streamed"] and snb["knows"] == snb["knows_streamed"], snb):
        return 4
    out.gate("edge endpoints declared in the edge type", snb["endpoints"] == ("Person", "Person"), snb["endpoints"])
    out.gate("the traversal rule is on by default and stays on", be.get_rule(), be.get_rule())
    nrow = be.table("postgresql", "SELECT count(*) AS n FROM person")
    if not out.gate("language `postgresql` is registered and finds `person`", nrow[1] == [[snb["persons"]]], nrow):
        return 4
    # ---- answers before any timing
    bad_r = check_reads(out, be, ref, w)
    bad_w = check_writes(out, be, w, meta["checkpoints"])
    if bad_r or bad_w:
        rc = max(rc, 6)
    plan_ok = plan_records(out, be, w, model, token)
    if not plan_ok:
        rc = max(rc, 4)
    arms_w = [a for a in ("P", "S", "C", "Z") if a not in bad_w and (a != "Z" or "S" not in bad_w)]
    arms_by_task = {}
    for t in P14.TASK_IDS:
        arms = [a for a in P14.arms_of(t)]
        if P14.TASKS[t]["kind"] == "read":
            arms = [a for a in arms if (t, a) not in bad_r and (a != "Z" or (t, "S") not in bad_r)]
        else:
            arms = [a for a in arms if a in arms_w]
        if "P" in arms and "S" in arms and "Z" in arms and not (t == "t5" and not plan_ok):
            arms_by_task[t] = tuple(arms)
        else:
            out.write("not_timed", {"task": t, "arms": arms, "why": "P, S or Z has no equal answer or the plan gate failed"})
    out.write("env", {"arms_by_task": {t: list(a) for t, a in arms_by_task.items()}})
    # the memory reading's instrument: not a gate (the second reading never stops the first), a record that the reader and the lead read
    out.write("env", {"mem_probe": be.mem_setup(), "hwm_reset_works": P14.reset_peak_rss(), "vm_hwm_kb": P14.proc_status_kb("VmHWM"), "vm_rss_kb": P14.proc_status_kb("VmRSS")})
    out.write("env", {"cpu_probe": be.cpu_setup(), "disk_probe": be.disk_setup()})   # the CPU and disk instruments: records, not gates (the second readings never stop the first)
    # the timed write types are created only now, after the checks: nothing of the check is in them
    arms_timed_w = sorted({a for t in P14.WRITE_TASKS for a in arms_by_task.get(t, ())})
    for arm in arms_timed_w:
        be.create_mem(P14.MEM[arm], w.persons)
    if arms_by_task and not time_all(out, be, c, w, arms_by_task, arms_timed_w):
        rc = max(rc, 4)
    elif arms_timed_w:
        final_check(out, be, w, c, arms_timed_w)
    out.write("env", {"finished": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "others_sample_errors": be.others_errors, "drain_errors": be.drain_errors})
    be.close()
    return rc


def main(argv):
    if len(argv) != 3 or argv[1] not in ("ref", "main", "smoke"):
        print(__doc__)
        return 2
    phase, root = argv[1], Path(argv[2])
    c = P14.cfg()
    P14.check_cfg(c)
    out = L.Out(root)
    env_record(out, phase, c)
    ref_dir = Path(os.environ.get("PG13_REF_DIR", str(root / "ref")))
    try:
        if phase == "ref":
            return run_ref(out, c, root / "ref")
        rc = 0
        if phase == "smoke":
            rc = run_ref(out, c, root / "ref")
            if rc:
                return rc
            ref_dir = root / "ref"
        return max(rc, run_main(out, c, ref_dir))
    except SystemExit as e:
        out.gate("stopped by a check", False, e.code)
        return 4
    except Exception:                   # noqa: BLE001
        out.write("env", {"traceback": traceback.format_exc()})
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
