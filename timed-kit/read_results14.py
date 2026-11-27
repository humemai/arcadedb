"""Reader of a pg14 run:  read_results14.py <out-dir> [<ref-out-dir>]   (uv run python)

Prints the table of the nine tasks and the verdicts by the decision rule of the pre-registration, and writes <out-dir>/verdict.json. Everything
printed is computed from raw/*.jsonl; nothing is typed. Exit status: 0 read, 3 an A/A control left its band (that task is not readable), 6 a task
was not timed (an answer or a write check differed, a statement failed).

THE DECISION RULE (written before the first run; the rule of pg13, stated for P against S per task):
  for each task t, with M_a the median over the measured rounds of the mean call time of arm a in that round (one warm-up round before them, never read):
    A/A     Z/S within 1 -+ 0.03, else the task is NOT READABLE (no verdict, the number is still printed)
    ratio   P/S = M_P / M_S
    P/S <= 1.3          IN THE RANGE
    1.3 < P/S <= 2.0    REPORTED, NOT CLAIMED
    P/S > 2.0           OUTSIDE THE RANGE   (reported with its plan, never dropped)
  A task is read only when: arms P, S and Z have every measured round, the arms returned the same number of rows in every round, the answers were
  equal as multisets (reads) and the read-backs equal the oracle (writes) before the timing, and the write tables came back to their size. No pooled
  ratio over tasks. C/S, P/C and the reference arm R (PostgreSQL 18 over a socket) are reported with no threshold. Every comparison whose median ratio
  is below 0.90 or above 1.10 is listed with the control rows.

THE MEMORY READING (the amendment of 2026-11-24; a SECOND reading, printed after the first, that never changes a verdict, a gate or the exit status above):
  A_a = the median over the measured rounds of the mean bytes the CALLING thread allocated per call of arm a in that round (two reads of
  com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes around each call, outside the timed window)
    A/A     A_Z/A_S within 1 -+ 0.10 (0.90 to 1.10), else the task is NOT READABLE for memory
    ratio   A_P/A_S <= 1.3 IN THE RANGE; above 1.3 and up to 2.0 REPORTED, NOT CLAIMED; above 2.0 OUTSIDE THE RANGE
    off-thread  the other threads' share of (calling thread + other threads) is printed for every arm; above 10 percent in P, S or Z the task is
            marked off-thread and an IN THE RANGE becomes REPORTED, NOT CLAIMED (the all-threads ratio is printed beside)
  Same read conditions as the latency reading (P, S and Z with every measured round, equal row counts). C/S and P/C have no threshold. The reference arm R has no
  memory figure (another process). Beside it, per task block and round: the peak resident set (VmHWM since the reset at the block's start) and the heap in
  use after a full collection, whole-process figures (the arms of a block share the process), reported with no rule.

THE CPU AND WRITE READINGS (the second amendment of 2026-11-24; two more readings, printed after the memory one, that never change a verdict, a gate or the exit status above):
  X_a = the median over the measured rounds of the mean CPU time the CALLING thread used per call of arm a in that round (ThreadMXBean.getCurrentThreadCpuTime around each
  call, outside the timed window, the null pair of the same reads subtracted); W_a = the same for the bytes passed to write calls per call (wchar of /proc/self/io around
  each pass; t6 to t9 only). For each: A/A control Z/S within 1 -+ its band (CPU_AA_BAND, WRITE_AA_BAND in pg14lib), P/S at most 1.3 IN THE RANGE, up to 2.0 REPORTED, NOT
  CLAIMED, above OUTSIDE THE RANGE; C/S and P/C no threshold. CPU: if the share of the process's CPU that is not the calling thread's is above CPU_OFFTHREAD_MAX in P, S or Z
  the task is off-thread and an IN THE RANGE becomes REPORTED, NOT CLAIMED. Load and disk per arm and per task block (cores busy = CPU seconds over wall seconds, read_bytes,
  write_bytes, wchar, syscw, the size of the database folder across the write tasks) are whole-process figures, reported with no rule. R has no figure.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pg13lib as L
import pg14lib as P14


def us(ns):
    return f"{ns / 1e3:,.1f}"


def rd(out, name):
    return L.read_jsonl(Path(out) / "raw" / f"{name}.jsonl")


def med_of(by_round):
    return L.median(list(by_round.values()))


def mem_rows(rows):
    """{arm: {round: mean bytes per call of the calling thread}} and {arm: {round: mean bytes per call of the other threads}} of the measured turns of one task."""
    d, o = {}, {}
    for r in rows:
        if r["warm"] or "alloc_mean_bytes" not in r:
            continue
        d.setdefault(r["arm"], {})[r["round"]] = r["alloc_mean_bytes"]
        o.setdefault(r["arm"], {})[r["round"]] = r.get("others_mean_bytes", 0.0)
    return d, o


def memory_task(rows, rounds_expected, same_rows):
    """The memory reading of one task: a dict with the verdict and the figures."""
    d, o = mem_rows(rows)
    miss = [a for a in ("P", "S", "Z") if a not in d or (rounds_expected and len(d[a]) != rounds_expected)]
    if miss:
        return {"verdict": f"NOT READ (arms {miss} lack an allocation figure)"}
    A = {a: med_of(d[a]) for a in d}
    O = {a: med_of(o[a]) for a in o}
    if min(A[a] for a in ("P", "S", "Z")) <= 0:
        return {"verdict": "NOT MEASURED (the allocation counter read zero)", "A_bytes": A}
    share = {a: O[a] / (A[a] + O[a]) if A[a] + O[a] > 0 else 0.0 for a in A}
    off = max(share[a] for a in ("P", "S", "Z")) > P14.MEM_OFFTHREAD_MAX
    z_s, p_s = A["Z"] / A["S"], A["P"] / A["S"]
    all_t = {a: A[a] + O[a] for a in A}
    res = {"A_bytes": A, "other_threads_bytes": O, "other_share": share, "off_thread": off, "Z/S": z_s, "P/S": p_s,
           "C/S": A["C"] / A["S"] if "C" in A else None, "P/C": A["P"] / A["C"] if "C" in A else None, "P/S all threads": all_t["P"] / all_t["S"],
           "Z/S per round": ", ".join(f"r{r}: {d['Z'][r] / d['S'][r]:.3f}" for r in sorted(d["S"]) if r in d["Z"])}
    if not same_rows:
        res["verdict"] = "INVALID (the arms returned different row counts in a round)"
    else:
        res["verdict"] = P14.memory_verdict(z_s, p_s, off)
    return res


def process_task(blocks, t):
    """The process figures of task t: medians over the measured rounds of its block records."""
    b = [x for x in blocks if x["task"] == t and not x["warm"]]
    if not b:
        return None
    m = lambda k: L.median([x[k] for x in b if x.get(k) is not None]) if any(x.get(k) is not None for x in b) else None     # noqa: E731
    return {"rounds": len(b), "hwm_kb": m("hwm_kb"), "rss_kb_after_gc": m("rss_kb_after_gc"), "heap_used_after_gc": m("heap_used_after_gc"),
            "hwm_reset_all": all(x.get("hwm_reset") for x in b), "forced_gc_ran_all": all((x.get("gc_forced_count") or 0) > 0 for x in b)}


def cpu_task(rows, rounds_expected, same_rows, null_ns):
    """The CPU reading of one task: the CPU time the calling thread used per call (the null pair subtracted), the process's CPU per call, the share of the
    process's CPU that is not the calling thread's, and the cores busy (process CPU over the wall time of the arm's passes)."""
    d, share, pcall, busy = {}, {}, {}, {}
    for r in rows:
        if r["warm"] or "cpu_mean_ns" not in r:
            continue
        a, k = r["arm"], r["round"]
        d.setdefault(a, {})[k] = max(r["cpu_mean_ns"] - null_ns, 0.0)
        prc, thr, wall = r["proc_cpu_pass_ns"], r["thr_cpu_pass_ns"], r["pass_wall_ns"]
        share.setdefault(a, {})[k] = max(0.0, prc - thr) / prc if prc > 0 else 0.0
        pcall.setdefault(a, {})[k] = prc / r["calls"]
        busy.setdefault(a, {})[k] = prc / wall if wall > 0 else 0.0
    miss = [a for a in ("P", "S", "Z") if a not in d or (rounds_expected and len(d[a]) != rounds_expected)]
    if miss:
        return {"verdict": f"NOT READ (arms {miss} lack a CPU figure)"}
    X = {a: med_of(d[a]) for a in d}
    if min(X[a] for a in ("P", "S", "Z")) <= 0:
        return {"verdict": "NOT MEASURED (the CPU clock read zero)", "X_ns": X}
    sh = {a: med_of(share[a]) for a in share}
    off = max(sh[a] for a in ("P", "S", "Z")) > P14.CPU_OFFTHREAD_MAX
    z_s, p_s = X["Z"] / X["S"], X["P"] / X["S"]
    pc = {a: med_of(pcall[a]) for a in pcall}
    res = {"X_ns": X, "other_cpu_share": sh, "off_thread": off, "Z/S": z_s, "P/S": p_s, "C/S": X["C"] / X["S"] if "C" in X else None,
           "P/C": X["P"] / X["C"] if "C" in X else None, "proc_cpu_per_call_ns": pc, "P/S all threads": pc["P"] / pc["S"],
           "cores_busy": {a: med_of(busy[a]) for a in busy}, "Z/S per round": ", ".join(f"r{r}: {d['Z'][r] / d['S'][r]:.3f}" for r in sorted(d["S"]) if r in d["Z"])}
    res["verdict"] = "INVALID (the arms returned different row counts in a round)" if not same_rows else P14.reading_verdict(z_s, p_s, P14.CPU_AA_BAND, off)
    return res


def write_task(rows, rounds_expected, same_rows, rows_per_call, band=None):
    """The write reading of one write task: bytes passed to write calls per call (wchar of /proc/self/io), the bytes that reached the storage layer (write_bytes), the
    write calls (syscw), per row, and the write amplification against the logical bytes of a row (t6 and t9)."""
    d = {}
    for r in rows:
        if r["warm"] or "io_per_call" not in r:
            continue
        for key in ("wchar", "write_bytes", "syscw"):
            d.setdefault(key, {}).setdefault(r["arm"], {})[r["round"]] = r["io_per_call"].get(key, 0.0)
    w = d.get("wchar", {})
    miss = [a for a in ("P", "S", "Z") if a not in w or (rounds_expected and len(w[a]) != rounds_expected)]
    if miss:
        return {"verdict": f"NOT READ (arms {miss} lack a write figure)"}
    W = {a: med_of(w[a]) for a in w}
    if W["S"] <= 0 or W["P"] <= 0 or W["Z"] <= 0:
        return {"verdict": "NOT MEASURED (wchar read zero)", "W_bytes": W}
    z_s, p_s = W["Z"] / W["S"], W["P"] / W["S"]
    logical = P14.row_logical_bytes(P14.new_row(P14.NEW_ID_BASE + 1))
    res = {"W_bytes": W, "Z/S": z_s, "P/S": p_s, "C/S": W["C"] / W["S"] if "C" in W else None, "P/C": W["P"] / W["C"] if "C" in W else None,
           "storage_write_bytes_per_call": {a: med_of(v) for a, v in d["write_bytes"].items()}, "syscw_per_call": {a: med_of(v) for a, v in d["syscw"].items()},
           "rows_per_call": rows_per_call, "logical_bytes_per_row": logical, "bytes_per_row": {a: W[a] / rows_per_call for a in W},
           "amplification": {a: W[a] / rows_per_call / logical for a in W}, "Z/S per round": ", ".join(f"r{r}: {w['Z'][r] / w['S'][r]:.3f}" for r in sorted(w["S"]) if r in w["Z"])}
    res["verdict"] = "INVALID (the arms returned different row counts in a round)" if not same_rows else P14.reading_verdict(z_s, p_s, P14.WRITE_AA_BAND if band is None else band)
    return res


def cpu_lines(verdict, env):
    """The CPU reading and the load per arm."""
    n = lambda x: f"{x / 1e3:,.1f}" if x is not None else "-"       # noqa: E731
    f = lambda x: f"{x:.3f}" if x is not None else "-"              # noqa: E731
    probe = next((e["cpu_probe"] for e in env if "cpu_probe" in e), {})
    L_ = ["CPU TIME, a further reading (microseconds of CPU the calling thread used per call, the null pair subtracted, median over the measured rounds; it never changes a verdict above)",
          f"instrument: clock ok {probe.get('ok')}, agrees with the thread clock of Python over a spin {probe.get('agrees_with_python_thread_time')}, null pair {probe.get('null_pair_cpu_ns_median')} ns "
          f"(median, subtracted), a read costs {probe.get('cpu_read_wall_ns_median')} ns; the idle process (this thread asleep for 1 s) used {probe.get('idle_cores_busy_1s')} cores; band {1 - P14.CPU_AA_BAND:.2f} to {1 + P14.CPU_AA_BAND:.2f}; other-thread share limit {P14.CPU_OFFTHREAD_MAX:.0%}",
          f"{'task':<5}{'X_P us':>11}{'X_S us':>11}{'X_Z us':>11}{'X_C us':>11}{'Z/S':>7}{'P/S':>7}{'C/S':>7}{'P/C':>7}{'other cpu %':>13}{'P/S all thr':>13}  verdict"]
    for t, v in verdict["tasks"].items():
        m = v.get("cpu")
        if not m or "X_ns" not in m or "Z/S" not in m:
            L_.append(f"{t:<5}{(m or {}).get('verdict', 'NOT TIMED')}")
            continue
        X = m["X_ns"]
        L_.append(f"{t:<5}{n(X['P']):>11}{n(X['S']):>11}{n(X['Z']):>11}{n(X.get('C')):>11}{f(m['Z/S']):>7}{f(m['P/S']):>7}{f(m['C/S']):>7}{f(m['P/C']):>7}"
                  f"{max(m['other_cpu_share'].values()) * 100:>12.1f}%{f(m['P/S all threads']):>13}  {m['verdict']}")
    L_.append("")
    L_.append("LOAD per arm (process CPU over the wall time of the arm's passes = cores busy; microseconds of process CPU per call; whole process, no rule):")
    L_.append(f"{'task':<5}{'busy P':>9}{'busy S':>9}{'busy Z':>9}{'busy C':>9}{'proc us P':>12}{'proc us S':>12}{'proc us Z':>12}{'proc us C':>12}")
    for t, v in verdict["tasks"].items():
        m = v.get("cpu")
        if not m or "cores_busy" not in m:
            continue
        b, pc = m["cores_busy"], m["proc_cpu_per_call_ns"]
        L_.append(f"{t:<5}" + "".join(f"{b.get(a, 0):>9.2f}" for a in "PSZC") + "".join(f"{n(pc.get(a)):>12}" for a in "PSZC"))
    L_.append("")
    L_.append("Z/S of the CPU time in each round (diagnostic, no rule reads it):")
    for t, v in verdict["tasks"].items():
        if "Z/S per round" in v.get("cpu", {}):
            L_.append(f"  {t}: {v['cpu']['Z/S per round']}")
    L_.append("")
    L_.append("The reference arm R (PostgreSQL 18) runs in another process and has no CPU figure.")
    L_.append("")
    return L_


def disk_lines(verdict, blocks, env):
    """The write reading (t6 to t9), the storage layer, the load and disk of each task block and the size of the database folder across the write tasks."""
    n = lambda x: f"{x:,.0f}" if x is not None else "-"             # noqa: E731
    f = lambda x: f"{x:.3f}" if x is not None else "-"              # noqa: E731
    probe = next((e["disk_probe"] for e in env if "disk_probe" in e), {})
    L_ = ["WRITE, a further reading (bytes the process passed to write calls per call: wchar of /proc/self/io around each pass; t6 to t9; it never changes a verdict above)",
          f"instrument: probe ok {probe.get('ok')}, database folder {probe.get('db_path')} on {probe.get('mount')} ({probe.get('fstype')}, ram backed {probe.get('ram_backed')}); "
          f"a 4 MiB write moved wchar by {probe.get('probe_wchar')} and write_bytes by {probe.get('probe_write_bytes')}; bands {1 - P14.WRITE_AA_BAND:.2f} to {1 + P14.WRITE_AA_BAND:.2f} (t6 to t8) and {1 - P14.WRITE_AA_BAND_BATCH:.2f} to {1 + P14.WRITE_AA_BAND_BATCH:.2f} (t9)",
          f"{'task':<5}{'W_P B':>12}{'W_S B':>12}{'W_Z B':>12}{'W_C B':>12}{'Z/S':>7}{'P/S':>7}{'C/S':>7}{'P/C':>7}{'amp P':>8}{'amp S':>8}  verdict"]
    for t in P14.WRITE_TASKS:
        m = verdict["tasks"].get(t, {}).get("write")
        if not m or "W_bytes" not in m or "Z/S" not in m:
            L_.append(f"{t:<5}{(m or {}).get('verdict', 'NOT TIMED')}")
            continue
        W, amp = m["W_bytes"], m["amplification"]
        L_.append(f"{t:<5}{n(W['P']):>12}{n(W['S']):>12}{n(W['Z']):>12}{n(W.get('C')):>12}{f(m['Z/S']):>7}{f(m['P/S']):>7}{f(m['C/S']):>7}{f(m['P/C']):>7}"
                  f"{amp['P']:>8.1f}{amp['S']:>8.1f}  {m['verdict']}")
    L_.append("amp = bytes written per row over the logical bytes of a row (id, created, age, name, city = %d bytes), per row of the call (t7 and t8 touch one row, t9 10,000 rows)" %
              P14.row_logical_bytes(P14.new_row(P14.NEW_ID_BASE + 1)))
    L_.append("")
    L_.append("Storage layer and write calls per call (write_bytes reaches the storage layer once per dirtied page until the kernel cleans it, so it is lumpy per call; no rule):")
    L_.append(f"{'task':<5}" + "".join(f"{'wb ' + a:>12}" for a in "PSZC") + "".join(f"{'syscw ' + a:>10}" for a in "PSZC"))
    for t in P14.WRITE_TASKS:
        m = verdict["tasks"].get(t, {}).get("write")
        if not m or "storage_write_bytes_per_call" not in m:
            continue
        wb, sc = m["storage_write_bytes_per_call"], m["syscw_per_call"]
        L_.append(f"{t:<5}" + "".join(f"{n(wb.get(a)):>12}" for a in "PSZC") + "".join(f"{sc.get(a, 0):>10.1f}" for a in "PSZC"))
    L_.append("")
    L_.append("TASK BLOCKS (median over the measured rounds, whole process, all arms of the block; cores busy = CPU seconds over wall seconds; no rule reads them):")
    L_.append(f"{'task':<5}{'wall s':>9}{'cpu s':>9}{'busy':>7}{'user s':>9}{'sys s':>8}{'read MiB':>10}{'write_bytes MiB':>17}{'wchar MiB':>11}{'syscw':>10}{'db disk MiB (before > after)':>32}")
    verdict["blocks"] = {}
    mib = lambda x: x / (1 << 20)                                  # noqa: E731
    for t in verdict["tasks"]:
        b = [x for x in blocks if x["task"] == t and not x["warm"] and "io" in x]
        if not b:
            continue
        m = lambda k: L.median([x[k] for x in b])                  # noqa: E731
        mi = lambda k: L.median([x["io"].get(k, 0) for x in b])    # noqa: E731
        row = {"wall_s": m("wall_s"), "cpu_s": m("cpu_s"), "cores_busy": L.median([x["cpu_s"] / x["wall_s"] for x in b if x["wall_s"] > 0]), "cpu_user_s": m("cpu_user_s"),
               "cpu_sys_s": m("cpu_sys_s"), "read_bytes": mi("read_bytes"), "write_bytes": mi("write_bytes"), "wchar": mi("wchar"), "syscw": mi("syscw"),
               "db_disk_before": m("db_disk_before"), "db_disk_after": m("db_disk_after")}
        verdict["blocks"][t] = row
        L_.append(f"{t:<5}{row['wall_s']:>9.2f}{row['cpu_s']:>9.2f}{row['cores_busy']:>7.2f}{row['cpu_user_s']:>9.2f}{row['cpu_sys_s']:>8.2f}{mib(row['read_bytes']):>10.1f}"
                  f"{mib(row['write_bytes']):>17.1f}{mib(row['wchar']):>11.1f}{row['syscw']:>10,.0f}{mib(row['db_disk_before']):>16.1f} >{mib(row['db_disk_after']):>12.1f}")
    spans = []
    for r in sorted({x["round"] for x in blocks if not x["warm"]}):
        t6 = next((x for x in blocks if x["task"] == "t6" and x["round"] == r), None)
        t9 = next((x for x in blocks if x["task"] == "t9" and x["round"] == r), None)
        if t6 and t9 and "db_disk_before" in t6 and "db_disk_after" in t9:
            spans.append((r, t6["db_disk_before"], t9["db_disk_after"], t9["db_apparent_after"] - t6["db_apparent_before"]))
    L_.append("")
    L_.append("DATABASE FOLDER across the write tasks of each measured round (size on disk, MiB, from the start of the t6 block to the end of the t9 block; the rows t6 and t9 add are deleted again):")
    for r, a, b, ap in spans:
        L_.append(f"  round {r}: {mib(a):,.2f} > {mib(b):,.2f} MiB (change {mib(b - a):+,.2f} MiB on disk, {mib(ap):+,.2f} MiB apparent)")
    verdict["db_spans"] = spans
    L_.append("The database folder is on the data disk of the host (see the probe above); read_bytes is zero or small where the page cache serves the reads, and a tmpfs would say so in the probe.")
    L_.append("")
    return L_


def read_task(t, rows, rounds_expected):
    d, n = {}, {}
    for r in rows:
        if r["warm"]:
            continue
        d.setdefault(r["arm"], {})[r["round"]] = r["mean_ns"]
        n.setdefault(r["round"], set()).add((r["arm"], r["rows"]))
    return d, n


def memory_lines(verdict, blocks, env):
    """The second reading: allocation of the calling thread per call (bytes), the other threads, the process figures. Fills verdict['process']."""
    n = lambda x: f"{x:,.0f}" if x is not None else "-"             # noqa: E731
    f = lambda x: f"{x:.3f}" if x is not None else "-"              # noqa: E731
    probe = next((e["mem_probe"] for e in env if "mem_probe" in e), {})
    sample_errors = next((e["others_sample_errors"] for e in env if "others_sample_errors" in e), None)
    L_ = ["MEMORY, a second reading (bytes the calling thread allocated per call, median over the measured rounds of the turn's mean; it never changes a verdict above)",
          f"instrument: counter ok {probe.get('ok')}, a read costs {probe.get('read_ns_median')} ns outside the timed window, two reads with nothing between them see "
          f"{probe.get('null_pair_bytes_median')} bytes (median), the counter moved by {probe.get('moves_by_1mib_array')} bytes for a 1 MiB array; runtime {probe.get('java_version')}; "
          f"other threads sampled: {probe.get('threads_live')} live at the probe, {sample_errors} failed samples",
          f"{'task':<5}{'A_P B':>13}{'A_S B':>13}{'A_Z B':>13}{'A_C B':>13}{'Z/S':>7}{'P/S':>7}{'C/S':>7}{'P/C':>7}{'other thr %':>13}{'P/S all thr':>13}  verdict"]
    for t, v in verdict["tasks"].items():
        m = v.get("memory")
        if not m or "A_bytes" not in m or "Z/S" not in m:
            L_.append(f"{t:<5}{(m or {}).get('verdict', 'NOT TIMED'):<}")
            continue
        A = m["A_bytes"]
        share = max(m["other_share"].values()) * 100
        L_.append(f"{t:<5}{n(A['P']):>13}{n(A['S']):>13}{n(A['Z']):>13}{n(A.get('C')):>13}{f(m['Z/S']):>7}{f(m['P/S']):>7}{f(m['C/S']):>7}{f(m['P/C']):>7}"
                  f"{share:>12.1f}%{f(m['P/S all threads']):>13}  {m['verdict']}")
    L_.append("")
    L_.append("Z/S of the allocation in each round (diagnostic, no rule reads it):")
    for t, v in verdict["tasks"].items():
        if "Z/S per round" in v.get("memory", {}):
            L_.append(f"  {t}: {v['memory']['Z/S per round']}")
    L_.append("")
    L_.append("The reference arm R (PostgreSQL 18) runs in another process and has no memory figure.")
    L_.append("")
    L_.append("PROCESS figures per task block (median over the measured rounds; whole process, the arms of a block share it; no rule reads them):")
    L_.append(f"{'task':<5}{'peak RSS MiB':>14}{'RSS after GC MiB':>18}{'heap after GC MiB':>19}  notes")
    verdict["process"] = {}
    for t in verdict["tasks"]:
        pr = process_task(blocks, t)
        if not pr:
            continue
        verdict["process"][t] = pr
        notes = ("" if pr["hwm_reset_all"] else "peak is the running peak of the process (the reset did not work); ") + ("" if pr["forced_gc_ran_all"] else "a forced collection did not run; ")
        mib = lambda kb: f"{kb / 1024:,.0f}" if kb is not None else "-"          # noqa: E731
        L_.append(f"{t:<5}{mib(pr['hwm_kb']):>14}{mib(pr['rss_kb_after_gc']):>18}{(pr['heap_used_after_gc'] / (1 << 20) if pr['heap_used_after_gc'] is not None else 0):>19,.0f}  {notes}")
    L_.append("")
    return L_


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    out = Path(argv[1])
    ref_out = Path(argv[2]) if len(argv) > 2 else out
    turns = rd(out, "turns")
    blocks = rd(out, "blocks")
    gates = rd(out, "gates")
    not_timed = {r["task"]: r for r in rd(out, "not_timed")}
    plans = {r["task"]: r for r in rd(out, "plans")}
    env = rd(out, "env")
    rounds_expected = next((e["rounds_measured"] for e in env if "rounds_measured" in e), None)
    null_ns = next((e["cpu_probe"].get("null_pair_cpu_ns_median", 0.0) for e in env if "cpu_probe" in e), 0.0)
    batch_rows = int(next((e["caps"]["batch_rows"] for e in env if "caps" in e), P14.BATCH_ROWS))
    ref_t = {}
    for r in rd(ref_out, "ref_timing"):
        if not r["warm"]:
            ref_t.setdefault(r["task"], {})[r["round"]] = r["mean_ns"]
    lines, verdict, rc = [], {"tasks": {}, "gates_failed": [g["name"] for g in gates if not g["ok"]]}, 0
    lines.append(f"pg14 run {out.name}: engine {next((e.get('engine_commit') for e in env if e.get('engine_commit')), '?')}, "
                 f"{len(gates)} gates, {len(verdict['gates_failed'])} failed")
    for g in verdict["gates_failed"]:
        lines.append(f"  GATE FAIL {g}")
    lines.append("")
    lines.append(f"{'task':<5}{'what':<62}{'M_P us':>10}{'M_S us':>10}{'M_Z us':>10}{'M_C us':>10}{'M_R us':>10}{'Z/S':>7}{'P/S':>7}{'C/S':>7}{'P/C':>7}  verdict")
    listed = []
    for t in P14.TASK_IDS:
        info = P14.TASKS[t]
        rows = [r for r in turns if r["task"] == t]
        if not rows:
            why = not_timed.get(t, {}).get("why", "no turns recorded")
            lines.append(f"{t:<5}{info['title'][:60]:<62}{'':>50}  NOT TIMED ({why})")
            verdict["tasks"][t] = {"verdict": "NOT TIMED", "why": why}
            rc = max(rc, 6)
            continue
        d, nrows = read_task(t, rows, rounds_expected)
        miss = [a for a in ("P", "S", "Z") if a not in d or (rounds_expected and len(d[a]) != rounds_expected)]
        if miss:
            lines.append(f"{t:<5}{info['title'][:60]:<62}  NOT READ: arms {miss} lack measured rounds")
            verdict["tasks"][t] = {"verdict": "INCOMPLETE", "missing": miss}
            rc = max(rc, 6)
            continue
        rowsets = {r: {x[1] for x in v} for r, v in nrows.items()}
        same_rows = all(len(v) == 1 for v in rowsets.values())
        M = {a: med_of(d[a]) for a in d}
        z_s = M["Z"] / M["S"]
        p_s = M["P"] / M["S"]
        c_s = M["C"] / M["S"] if "C" in M else None
        p_c = M["P"] / M["C"] if "C" in M else None
        m_r = med_of(ref_t[t]) if t in ref_t and ref_t[t] else None
        if not same_rows:
            v = "INVALID (the arms returned different row counts in a round)"
            rc = max(rc, 6)
        elif abs(z_s - 1) > P14.AA_BAND:
            v = "NOT READABLE (A/A control out of band)"
            rc = max(rc, 3)
        elif p_s <= P14.RATIO_IN_RANGE:
            v = "IN THE RANGE"
        elif p_s <= P14.RATIO_REPORTED_MAX:
            v = "REPORTED, NOT CLAIMED"
        else:
            v = "OUTSIDE THE RANGE"
        f = lambda x: f"{x:.3f}" if x is not None else "-"          # noqa: E731
        g = lambda a: us(M[a]) if a in M else "-"                    # noqa: E731
        lines.append(f"{t:<5}{info['title'][:60]:<62}{g('P'):>10}{g('S'):>10}{g('Z'):>10}{g('C'):>10}{us(m_r) if m_r else '-':>10}{f(z_s):>7}{f(p_s):>7}{f(c_s):>7}{f(p_c):>7}  {v}")
        for name, x in (("Z/S", z_s), ("P/S", p_s), ("C/S", c_s), ("P/C", p_c)):
            if x is not None and (x < P14.CONTROL_LO or x > P14.CONTROL_HI):
                listed.append(f"{t} {name} = {x:.3f}")
        per_round = ", ".join(f"r{r}: {d['Z'][r] / d['S'][r]:.3f}" for r in sorted(d["S"]) if r in d["Z"])
        verdict["tasks"][t] = {"verdict": v, "M_ns": M, "Z/S": z_s, "P/S": p_s, "C/S": c_s, "P/C": p_c, "M_R_ns": m_r, "Z/S per round": per_round,
                               "calls_per_turn": rows[0]["calls"], "passes": rows[0]["passes"]}
        verdict["tasks"][t]["memory"] = memory_task(rows, rounds_expected, same_rows)
        verdict["tasks"][t]["cpu"] = cpu_task(rows, rounds_expected, same_rows, null_ns)
        if t in P14.WRITE_TASKS:
            verdict["tasks"][t]["write"] = write_task(rows, rounds_expected, same_rows, batch_rows if t == "t9" else 1, P14.WRITE_AA_BANDS[t])
    lines.append("")
    lines.append("Comparisons below 0.90 or above 1.10 (listed with the control rows): " + ("; ".join(listed) if listed else "none"))
    lines.append("")
    lines.append("Z/S of each round (diagnostic, no rule reads it), calls per turn, passes:")
    for t, v in verdict["tasks"].items():
        if "Z/S per round" in v:
            lines.append(f"  {t}: {v['Z/S per round']}   calls {v['calls_per_turn']}, passes {v['passes']}")
    lines.append("")
    lines.append("M_R is PostgreSQL 18 over a socket in another process (a fifth of the passes): a reference row, never a claim.")
    lines.append("")
    lines += memory_lines(verdict, blocks, env)
    lines += cpu_lines(verdict, env)
    lines += disk_lines(verdict, blocks, env)
    lines.append("Plans of arm P (the first statement of each read task, as the engine ran it):")
    for t, p in plans.items():
        lines.append(f"  {t}: {p['plan'][:700]}")
    print("\n".join(lines))
    (out / "verdict.json").write_text(json.dumps(verdict, indent=1, sort_keys=True, default=str), encoding="utf-8")
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
