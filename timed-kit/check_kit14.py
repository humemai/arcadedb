"""check_kit14.py selftest | verbatim <pg13-v3-src dir> | data     (uv run --no-project --python 3.12 python check_kit14.py ...)

selftest   no engine, no data: the design of the turns, the statements, the oracle of the writes, the thresholds and the memory reading, on a made-up world
verbatim   the four files that pg14 reuses from pg13 (pg13lib.py, backend_arcade.py, backend_pg.py, pg_ref_server.sh) are byte for byte those of pg13 version 3
data       the creationDate column lines up with the persons of the generator of the benchmark (needs the SNB files; skipped when they are missing)
"""
from __future__ import annotations

import hashlib
import os
import sys
from pathlib import Path

import pg13lib as L
import pg14lib as P14
import read_results14 as R14

HERE = Path(__file__).resolve().parent
VERBATIM = ("pg13lib.py", "backend_arcade.py", "backend_pg.py", "pg_ref_server.sh")
bad = 0


def ok(cond, what):
    global bad
    bad += not cond
    print(("ok    " if cond else "FAIL  ") + what)


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def selftest():
    os.environ.update(PG13_TRAVERSAL_MODEL="bound-start", PG14_PARAMS_K="12", PG14_T3_CALLS="3", PG14_WRITE_K="7", PG14_BATCH_ROWS="25", PG13_ROUNDS="5")
    c = P14.cfg()
    P14.check_cfg(c)
    persons = [{"id": 100 + i, "name": f"p{i}", "age": 20 + i % 9, "city": f"city_{i % 5}", "created": 1_200_000_000_000 + 7 * i} for i in range(40)]
    w = P14.World(c, persons=persons, starts=[100, 101, 102])
    ok(P14.RATIO_IN_RANGE == 1.3 and P14.RATIO_REPORTED_MAX == 2.0 and P14.AA_BAND == 0.03, "thresholds are the pg13 values: 1.3, 2.0, 0.03")
    ok(P14.ROUNDS_MEASURED == 5 and P14.ROUNDS_WARMUP == 1 and (P14.CONTROL_LO, P14.CONTROL_HI) == (0.90, 1.10), "5 measured rounds after 1 warm-up round, control rows outside 0.90 to 1.10")
    ok(all(P14.TASKS[t]["passes"] % 2 == 0 for t in P14.TASK_IDS), "every task has an even number of passes (the S/Z block alternates in fours)")
    # the turns: S and Z are one block in every round, P and C are single turns, every arm once
    for arms in (("P", "S", "C", "Z"), ("P", "S", "Z")):
        for r in range(6):
            turns = P14.task_turns(r, arms)
            names = [x for t, _ in turns for x in (("S", "Z") if t == "SZ" else (t,))]
            ok(sorted(names) == sorted(arms) and sum(1 for t, _ in turns if t == "SZ") == 1, f"round {r}, arms {''.join(arms)}: turns {[t for t, _ in turns]} hold every arm once, S and Z in one block")
    seq = P14.abba_sz("S", 6)
    ok(seq.count("S") == 6 and seq.count("Z") == 6 and "".join(seq[:4]) == "SZZS", "abba_sz: 6 passes of each arm, S Z Z S first")
    # statements: every task has a text in every arm, the same parameter list in every arm of a task
    for t in P14.READ_TASKS:
        n = {a: len(P14.read_pass_texts(w, t, a)) for a in ("P", "S", "C", "R")}
        ok(len(set(n.values())) == 1 and n["P"] > 0, f"{t}: {n['P']} statements per pass in each of P, S, C, R")
        ok(P14.read_pass_texts(w, t, "P") == [("postgresql", x[1]) for x in P14.read_pass_texts(w, t, "R")], f"{t}: P and R use one PostgreSQL text")
    ok(all(P14.read_text("t1", a, 5)[0] == lg for a, lg in (("P", "postgresql"), ("S", "sql"), ("C", "opencypher"), ("Z", "sql"))), "languages: P postgresql, S sql, C opencypher, Z sql")
    ok(P14.read_text("t5", "P", 5)[1] == L.JOIN_P1.format(id=5) and P14.read_text("t5", "C", 5)[1] == L.CYPHER_P1.format(id=5), "t5: the P and C texts are the pg13 pair 1 texts, unchanged")
    for t in P14.WRITE_TASKS:
        for a in ("P", "S", "C", "Z", "R"):
            units, cleanup = P14.write_pass(w, t, a, P14.MEM[a], 2, 3)
            want = 1 if t == "t9" else 7
            ok(len(units) == want and (cleanup is not None) == (t == "t9"), f"{t} arm {a}: {len(units)} timed statements per pass{', one untimed cleanup' if cleanup else ''}")
    t6 = P14.write_pass(w, "t6", "P", "MemP", 2, 3)[0]
    t8 = P14.write_pass(w, "t8", "P", "MemP", 2, 3)[0]
    ok([x[1].split("VALUES (")[1].split(",")[0] for x in t6] == [x[1].rsplit("id = ", 1)[1] for x in t8], "t8 of a pass deletes exactly the ids that t6 of the same pass inserted")
    ids = {P14.new_id(r, j, i) for r in range(6) for j in range(10) for i in range(7)}
    ok(len(ids) == 6 * 10 * 7 and min(ids) >= P14.NEW_ID_BASE and max(ids) < P14.BATCH_ID_BASE, "ids of inserted rows are unique over the run and clear of the persons and of the batch rows")
    # the oracle: five states, the checksums differ where the state differs, the last state is the first plus the updates
    st = P14.apply_check_steps(w)
    sums = [P14.state_checksum(s) for _, s in st]
    ok([n for n, _ in st] == ["after t6", "after t7", "after t8", "after t9", "after the cleanup of t9"], "oracle: five checkpoints in the order of the check")
    ok(len(set(sums)) == 5 - (1 if sums[2] == sums[4] else 0) and len(st[0][1]) == 40 + 7 and len(st[3][1]) == 40 + 25, "oracle: sizes after t6 (seed + 7) and after t9 (seed + 25), checksums differ per state")
    ok(P14.state_checksum(P14.seed_state(w)) == P14.state_checksum({p["id"]: p for p in persons}), "oracle: the checksum of the seed is a function of the rows")
    fs = P14.final_state(w, c)
    ok(len(fs) == 40 and all(r["age"] >= 20 for r in fs.values()), "final state keeps the seed rows, with updated ages")
    d1, d2 = P14.statements_digest(w), P14.statements_digest(P14.World(c, persons=persons, starts=[100, 101, 102]))
    ok(d1 == d2 and len(d1) == 64, "the statements digest is a function of the world")
    names, rows = ["age", "city", "created", "id", "name"], [[1, "c", 2, 9, "n"], [1, "c", 2, 3, "m"]]
    ok(P14.table_checksum(names, rows) == P14.table_checksum(names, rows[::-1]), "the read-back checksum does not depend on the row order")
    ok(L.multiset_equal((["a"], [[13194139535446], [13194139538575]]), (["a"], [[13194139538575], [13194139535446]]))[0], "pg13's multiset comparison keeps the exact-integer tie-break")
    selftest_memory()
    selftest_cpu_write()


def selftest_memory():
    """The memory reading (amendment of 2026-11-24): the band, the ratios, the off-thread rule, the other-thread delta, the process figures, the reader."""
    ok(P14.MEM_AA_BAND == 0.10 and P14.MEM_OFFTHREAD_MAX == 0.10, "memory: A/A band 0.90 to 1.10, off-thread share 10 percent")
    v = P14.memory_verdict
    ok([v(1.0, 1.0), v(1.09, 1.3), v(0.91, 1.31), v(1.0, 2.0), v(1.0, 2.01)] == ["IN THE RANGE", "IN THE RANGE", "REPORTED, NOT CLAIMED", "REPORTED, NOT CLAIMED", "OUTSIDE THE RANGE"],
       "memory verdicts: at most 1.3 in the range, up to 2.0 reported, above 2.0 outside")
    ok(v(1.11, 1.0).startswith("NOT READABLE") and v(0.89, 1.0).startswith("NOT READABLE") and v(1.10, 1.0) == "IN THE RANGE" and v(0.90, 1.0) == "IN THE RANGE",
       "memory A/A control: 0.90 and 1.10 are in band, 0.89 and 1.11 are not")
    ok(v(1.0, 1.0, off_thread=True) == "REPORTED, NOT CLAIMED (off-thread work)" and v(1.0, 1.5, off_thread=True) == "REPORTED, NOT CLAIMED"
       and v(1.0, 2.5, off_thread=True) == "OUTSIDE THE RANGE", "memory off-thread rule: an in-range ratio is not claimed, the others keep their words")
    ok(P14.others_delta({1: 10, 2: 20, 3: 5}, {1: 15, 2: 30, 4: 7}) == (22, 1), "other threads: growth of the common ones plus the new one from zero, one ended thread counted as lost")
    hwm = P14.proc_status_kb("VmHWM")
    ok(hwm is not None and hwm > 0 and P14.proc_status_kb("VmRSS") > 0 and P14.proc_status_kb("NoSuchField") is None, "process figures: VmHWM and VmRSS are read from /proc/self/status")
    ok(isinstance(P14.reset_peak_rss(), bool), "process figures: the peak reset answers true or false (the kernel decides), never raises")
    # the reader on made-up turns: Z/S of the allocation 1.02, P/S 1.2 (in the range), P/S of another task 1.6 and 2.4, a rise of the first round (warm-up) that no figure reads
    def rows(task, a_p, a_s, a_z, other_p=0.0):
        out = []
        for r in range(6):
            for arm, a in (("P", a_p), ("S", a_s), ("Z", a_z), ("C", a_s * 0.9)):
                out.append({"task": task, "arm": arm, "round": r, "warm": r == 0, "rows": 5, "mean_ns": 1000.0, "alloc_mean_bytes": a * (3 if r == 0 else 1),
                            "others_mean_bytes": other_p if arm == "P" else 0.0})
        return out
    m = R14.memory_task(rows("t1", 1200.0, 1000.0, 1020.0), 5, True)
    ok(m["verdict"] == "IN THE RANGE" and abs(m["P/S"] - 1.2) < 1e-9 and abs(m["Z/S"] - 1.02) < 1e-9 and abs(m["C/S"] - 0.9) < 1e-9, "reader: memory of a made-up task is IN THE RANGE with P/S 1.2, Z/S 1.02, C/S 0.9 (round 0 is not read)")
    ok(R14.memory_task(rows("t2", 1600.0, 1000.0, 1000.0), 5, True)["verdict"] == "REPORTED, NOT CLAIMED"
       and R14.memory_task(rows("t3", 2400.0, 1000.0, 1000.0), 5, True)["verdict"] == "OUTSIDE THE RANGE"
       and R14.memory_task(rows("t4", 1000.0, 1000.0, 1200.0), 5, True)["verdict"].startswith("NOT READABLE"), "reader: reported, outside and not readable cases of the memory reading")
    ok(R14.memory_task(rows("t1", 1200.0, 1000.0, 1000.0, other_p=400.0), 5, True)["verdict"] == "REPORTED, NOT CLAIMED (off-thread work)"
       and R14.memory_task(rows("t1", 1200.0, 1000.0, 1000.0), 5, False)["verdict"].startswith("INVALID")
       and R14.memory_task(rows("t1", 0.0, 0.0, 0.0), 5, True)["verdict"].startswith("NOT MEASURED"), "reader: off-thread share above 10 percent, unequal row counts and a counter that reads zero")
    blocks = [{"task": "t1", "round": r, "warm": r == 0, "hwm_kb": 1000 + r, "rss_kb_after_gc": 900, "heap_used_after_gc": 5 << 20, "hwm_reset": True, "gc_forced_count": 1} for r in range(6)]
    pr = R14.process_task(blocks, "t1")
    ok(pr["rounds"] == 5 and pr["hwm_kb"] == 1003 and pr["hwm_reset_all"] and pr["forced_gc_ran_all"] and R14.process_task(blocks, "t2") is None, "reader: process figures are medians of the five measured rounds")


def selftest_cpu_write():
    """The CPU and write readings (second amendment of 2026-11-24): the verdicts, the /proc helpers, the logical row, the reader on made-up turns."""
    v = P14.reading_verdict
    ok([v(1.0, 1.0, 0.05), v(1.04, 1.3, 0.05), v(1.06, 1.0, 0.05), v(0.94, 1.0, 0.05), v(1.0, 1.31, 0.10), v(1.0, 2.01, 0.10)] ==
       ["IN THE RANGE", "IN THE RANGE", "NOT READABLE (A/A control out of band)", "NOT READABLE (A/A control out of band)", "REPORTED, NOT CLAIMED", "OUTSIDE THE RANGE"],
       "cpu and write verdicts: the band is the argument, the thresholds are 1.3 and 2.0")
    ok(v(1.0, 1.0, 0.1, off_thread=True) == "REPORTED, NOT CLAIMED (off-thread work)" and v(1.0, 2.5, 0.1, off_thread=True) == "OUTSIDE THE RANGE", "cpu off-thread rule: only an in-range ratio is held back")
    io = P14.proc_io()
    ok(all(k in io for k in P14.IO_KEYS) and io["wchar"] > 0, "proc_io reads rchar, wchar, syscw, read_bytes, write_bytes from /proc/self/io")
    u, s = P14.proc_cpu_split_s()
    ok(u >= 0 and s >= 0 and u + s > 0, "proc_cpu_split_s reads utime and stime from /proc/self/stat")
    mp, fs = P14.mount_of("/")
    ok(mp != "?" and fs != "?", f"mount_of finds the file system of / ({fs})")
    ok(P14.row_logical_bytes(P14.new_row(P14.NEW_ID_BASE + 1)) == 8 + 8 + 4 + len("memory 1000000001") + len("city_1858"), "the logical bytes of a row: id, created, age, name, city")

    def rows(task, x, null=1000.0, proc=1000.0, thr=900.0, w=None):
        out = []
        for r in range(6):
            for arm, a in (("P", x["P"]), ("S", x["S"]), ("Z", x["Z"]), ("C", x["S"] * 0.9)):
                out.append({"task": task, "arm": arm, "round": r, "warm": r == 0, "rows": 5, "calls": 100, "cpu_mean_ns": a * (2 if r == 0 else 1) + null, "thr_cpu_pass_ns": thr * 100,
                            "proc_cpu_pass_ns": (proc * 3 if arm == "P" and w == "off" else proc) * 100, "pass_wall_ns": 2000.0 * 100,
                            "io_per_call": {"wchar": a, "write_bytes": a / 2, "syscw": 3.0}})
        return out
    m = R14.cpu_task(rows("t1", {"P": 12500.0, "S": 10000.0, "Z": 10100.0}), 5, True, 1000.0)
    ok(m["verdict"] == "IN THE RANGE" and abs(m["P/S"] - 1.25) < 1e-9 and abs(m["Z/S"] - 1.01) < 1e-9 and abs(m["cores_busy"]["S"] - 0.5) < 1e-9, "reader: CPU of a made-up task is IN THE RANGE with P/S 1.25, Z/S 1.01, half a core busy (round 0 is not read, the null pair is subtracted)")
    ok(R14.cpu_task(rows("t1", {"P": 12500.0, "S": 10000.0, "Z": 10100.0}, w="off"), 5, True, 1000.0)["verdict"] == "REPORTED, NOT CLAIMED (off-thread work)"
       and R14.cpu_task(rows("t1", {"P": 25000.0, "S": 10000.0, "Z": 10000.0}), 5, True, 1000.0)["verdict"] == "OUTSIDE THE RANGE"
       and R14.cpu_task(rows("t1", {"P": 10000.0, "S": 10000.0, "Z": 12000.0}), 5, True, 1000.0)["verdict"].startswith("NOT READABLE"), "reader: off-thread CPU, outside and not readable cases")
    wt = R14.write_task(rows("t9", {"P": 400000.0, "S": 500000.0, "Z": 500100.0}), 5, True, 100)
    ok(wt["verdict"] == "IN THE RANGE" and abs(wt["bytes_per_row"]["S"] - 5000.0) < 1e-9 and abs(wt["amplification"]["S"] - 5000.0 / 46) < 1e-9, "reader: write reading, bytes per row and write amplification against the 46 logical bytes of a row")
    ok(R14.write_task(rows("t6", {"P": 0.0, "S": 0.0, "Z": 0.0}), 5, True, 1)["verdict"].startswith("NOT MEASURED"), "reader: a write counter that reads zero is NOT MEASURED")


def verbatim(src):
    src = Path(src)
    for f in VERBATIM:
        ok(src.joinpath(f).exists() and sha(src / f) == sha(HERE / f), f"{f} is byte for byte the file of pg13 version 3")


def data():
    scale = os.environ.get("PG13_SNB_SCALE", "sf1")
    try:
        a = list(L.gen_persons(scale, 500))
    except SystemExit as e:
        print(f"skip  no SNB data here: {e}")
        return
    b = list(P14.gen_created(scale, 500))
    ok([x[0] for x in a] == [x[0] for x in b] and len(a) == 500, "creationDate lines up with the persons of the generator (same ids, same order)")
    ok(all(1_000_000_000_000 < x[1] < 2_000_000_000_000 for x in b), "creationDate is epoch milliseconds")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "selftest":
        selftest()
    elif mode == "verbatim" and len(sys.argv) == 3:
        verbatim(sys.argv[2])
    elif mode == "data":
        data()
    else:
        print(__doc__)
        sys.exit(2)
    sys.exit(1 if bad else 0)
