"""Shared pieces of the pg14 study (fork issue humemai/arcadedb#13, DRAFT): the core tasks an agent does on memory records, written in
four languages of one engine jar (P the PostgreSQL language of the prototype, S ArcadeDB's native SQL, C Cypher, Z native SQL again as the
A/A control) and against real PostgreSQL 18 (R, a reference row only). Nothing here starts a JVM or a container.

pg13lib.py (a byte-for-byte copy of the pg13 version 3 file) supplies the data generators of the benchmark's own lanes, the multiset
comparison with the exact-integer tie-break (`_k`) and the thresholds. Everything that is new in pg14 lives HERE. Thresholds are constants,
written before the first run; the environment cannot move them (it moves only the caps of a smoke run and the paths).
"""
from __future__ import annotations

import hashlib
import json
import os
import random
from collections import Counter

import pg13lib as L

# ---------------------------------------------------------------------------------------------------------------------------------
# THE THRESHOLDS: the values of pg13 (fork issue #1 question 25), stated for P against S per task
RATIO_IN_RANGE = L.RATIO_IN_RANGE            # P/S at most this: in the range
RATIO_REPORTED_MAX = L.RATIO_REPORTED_MAX    # above RATIO_IN_RANGE and up to this: reported, not claimed; above: outside the range, reported with its plan
AA_BAND = L.AA_BAND                          # Z/S within 1 -+ this, else the task is not readable
CONTROL_LO, CONTROL_HI = L.CONTROL_LO, L.CONTROL_HI
ROUNDS_MEASURED, ROUNDS_WARMUP = L.ROUNDS_MEASURED, L.ROUNDS_WARMUP
PICK_SEED = L.PICK_SEED
PARAMS_K = 200                 # parameter values of a pass of a read task (a pass is one statement per value)
T3_CALLS = 50                  # the aggregate has no parameter: a pass is this many identical calls
WRITE_K = 200                  # statements of one pass of a single-row write task, each in its own transaction
BATCH_ROWS = 10_000            # rows of the one batch INSERT of task t9
NEW_ID_BASE = 1_000_000_000    # ids of the rows t6 inserts (the persons have ids below this)
BATCH_ID_BASE = 2_000_000_000  # ids of the rows t9 inserts
ARMS_ALL = ("P", "S", "C", "Z", "R")
ARM_NAMES = {"P": "PostgreSQL language (prototype)", "S": "ArcadeDB native SQL", "C": "Cypher", "Z": "native SQL again (A/A control)",
             "R": "PostgreSQL 18 (reference only)"}
LANG = {"P": "postgresql", "S": "sql", "C": "opencypher", "Z": "sql"}

# the 80/20 of what an agent does on a memory store; the reason of each choice is in the pre-registration (generated from the corpus counts)
# passes: full-run passes of a turn (even, so that the S/Z block alternates in blocks of four); smoke_passes: the smoke's
TASKS = {
    "t1": dict(title="point lookup by the indexed key", kind="read", passes=1000, smoke_passes=2, unit="one lookup"),
    "t2": dict(title="the latest 10 rows of one kind by timestamp (filter, ORDER BY, LIMIT)", kind="read", passes=20, smoke_passes=2, unit="one query"),
    "t3": dict(title="count and sum with GROUP BY over one table", kind="read", passes=20, smoke_passes=2, unit="one aggregate"),
    "t4": dict(title="filtered scan on a property without an index", kind="read", passes=20, smoke_passes=2, unit="one scan"),
    "t5": dict(title="one-hop neighbour list (join through the edge table)", kind="read", passes=200, smoke_passes=2, unit="one neighbour list"),
    "t6": dict(title="single-row INSERT, one transaction each", kind="write", passes=400, smoke_passes=2, unit="one insert and its commit"),
    "t7": dict(title="UPDATE by key, one transaction each", kind="write", passes=200, smoke_passes=2, unit="one update and its commit"),
    "t8": dict(title="DELETE by key, one transaction each", kind="write", passes=400, smoke_passes=2, unit="one delete and its commit"),
    "t9": dict(title="one batch INSERT of 10,000 rows in one transaction", kind="batch", passes=8, smoke_passes=2, unit="one batch and its commit"),
}
TASK_IDS = tuple(TASKS)
READ_TASKS = tuple(t for t in TASKS if TASKS[t]["kind"] == "read")
WRITE_TASKS = ("t6", "t7", "t8", "t9")
# NOT EXPRESSIBLE, decided in the kit before any run (an arm that the engine rejects at the answer check is added at run time, with the
# engine's message, and is not timed). Empty: every task has a statement in every arm; the answer check is what can add to it.
NOT_EXPRESSIBLE = {}

MEM = {"P": "MemP", "S": "MemS", "C": "MemC", "Z": "MemZ", "R": "memr"}     # the type (table) the timed write tasks of an arm use
CHK = {"P": "ChkP", "S": "ChkS", "C": "ChkC", "Z": "ChkZ", "R": "chkr"}     # the same, for the answer check (never timed)


def arms_of(task):
    return tuple(a for a in ("P", "S", "C", "Z") if (task, a) not in NOT_EXPRESSIBLE)


def cfg():
    e = os.environ.get
    c = L.cfg()
    c.update(params_k=int(e("PG14_PARAMS_K", str(PARAMS_K))), t3_calls=int(e("PG14_T3_CALLS", str(T3_CALLS))), write_k=int(e("PG14_WRITE_K", str(WRITE_K))),
             batch_rows=int(e("PG14_BATCH_ROWS", str(BATCH_ROWS))), smoke=e("PG14_SMOKE", "0") == "1")
    return c


def passes_of_task(c, t):
    return TASKS[t]["smoke_passes"] if c["smoke"] else TASKS[t]["passes"]


def check_cfg(c):
    L.check_cfg(c)
    for t in TASK_IDS:
        k = passes_of_task(c, t)
        if k < 2 or k % 2:
            raise SystemExit(f"passes of {t} is {k}: it has to be an even number of at least 2 (the S and Z passes alternate in blocks of four)")


def caps_of(c):
    return {"snb_scale": str(c["snb_scale"]), "person_limit": str(c["person_limit"]), "params_k": str(c["params_k"]), "t3_calls": str(c["t3_calls"]),
            "write_k": str(c["write_k"]), "batch_rows": str(c["batch_rows"])}


# ---------------------------------------------------------------------------------------------------------------------------------
# DATA: the pg13 graph (persons and KNOWS of the SNB scale) plus ONE column, `created`, the creationDate (epoch ms) of the same CSV row
def gen_created(scale, limit=0):
    """Yield (id, created) in the order of L.gen_persons: the creationDate column of person_0_0.csv (epoch milliseconds, a timestamp)."""
    rows = L._csv_rows(L._root(scale) / "person_0_0.csv")
    header = next(rows)
    i_id, i_cd = header.index("id"), header.index("creationDate")
    for n, row in enumerate(rows):
        if limit and n >= limit:
            break
        yield int(row[i_id]), int(row[i_cd])


def person_rows(c):
    """The persons as dicts (id, name, age, city, created), in the file order."""
    created = dict(gen_created(c["snb_scale"], c["person_limit"]))
    return [{"id": i, "name": n, "age": a, "city": ct, "created": created[i]} for i, n, a, ct in L.gen_persons(c["snb_scale"], c["person_limit"])]


class World:
    """Everything the statements take their values from, picked with a seed from the loaded data (the same on every host and every arm)."""

    def __init__(self, c, persons=None, starts=None):
        self.c = c
        self.persons = persons if persons is not None else person_rows(c)
        k = c["params_k"]
        rng = random.Random(PICK_SEED)
        ids = sorted(p["id"] for p in self.persons)
        cnt = Counter(p["city"] for p in self.persons)
        by_count = sorted(cnt, key=lambda x: (-cnt[x], x))
        self.t1_ids = rng.sample(ids, min(k, len(ids)))
        self.t2_cities = by_count[:k]                                   # the most populated cities: LIMIT 10 has something to cut
        self.t4_cities = rng.sample(sorted(cnt), min(k, len(cnt)))       # a seeded sample of all cities
        self.t5_starts = starts if starts is not None else L.pick_starts(c["snb_scale"], c["person_limit"], k)
        self.t7_ids = rng.sample(ids, min(c["write_k"], len(ids)))
        self.seed_ids = ids


def new_row(i):
    return {"id": i, "name": f"memory {i}", "age": 20 + i % 50, "city": f"city_{1000 + i % 1343}", "created": 1_300_000_000_000 + i}


def new_id(r, j, i):
    """Id of the i-th row of pass j of the turns of round r (unique over the whole run)."""
    return NEW_ID_BASE + (r * 1000 + j) * 1000 + i


def batch_id_range(r, j, n):
    lo = BATCH_ID_BASE + (r * 1000 + j) * 100_000
    return lo, lo + n


def upd_age(r, j, i):
    return 20 + (r * 31 + j * 7 + i) % 60


# ---------------------------------------------------------------------------------------------------------------------------------
# STATEMENTS. One function per task and arm family; the name of the table or type is a parameter. P and R share the PostgreSQL text.
def sql_lit(s):
    return "'" + str(s).replace("'", "''") + "'"


def read_text(task, arm, x):
    """The statement of read task `task` in `arm` for the parameter x (None for t3). Returns (language, text)."""
    pg = arm in ("P", "R")
    person = "person" if pg else "Person"
    if task == "t1":
        if arm == "C":
            return "opencypher", f"MATCH (p:Person) WHERE p.id = {x} RETURN p.id AS id, p.name AS name, p.age AS age, p.city AS city, p.created AS created"
        return LANG.get(arm, "postgresql"), f"SELECT id, name, age, city, created FROM {person} WHERE id = {x}"
    if task == "t2":
        if arm == "C":
            return "opencypher", (f"MATCH (p:Person) WHERE p.city = {sql_lit(x)} RETURN p.id AS id, p.name AS name, p.created AS created "
                                  "ORDER BY created DESC, id DESC LIMIT 10")
        return LANG.get(arm, "postgresql"), f"SELECT id, name, created FROM {person} WHERE city = {sql_lit(x)} ORDER BY created DESC, id DESC LIMIT 10"
    if task == "t3":
        if arm == "C":
            return "opencypher", "MATCH (p:Person) RETURN p.age AS age, count(*) AS n, sum(p.created) AS created_sum"
        return LANG.get(arm, "postgresql"), f"SELECT age, count(*) AS n, sum(created) AS created_sum FROM {person} GROUP BY age"
    if task == "t4":
        if arm == "C":
            return "opencypher", f"MATCH (p:Person) WHERE p.city = {sql_lit(x)} RETURN p.id AS id, p.name AS name, p.age AS age"
        return LANG.get(arm, "postgresql"), f"SELECT id, name, age FROM {person} WHERE city = {sql_lit(x)}"
    if task == "t5":
        if arm == "C":
            return "opencypher", L.CYPHER_P1.format(id=x)
        if arm in ("S", "Z"):
            return "sql", NATIVE_T5.format(id=x)
        return "postgresql", L.JOIN_P1.format(id=x)
    raise KeyError(task)


# the native SQL of t5: the edge records of the person's outgoing KNOWS edges, with the target person read through the edge's `in` link
NATIVE_T5 = "SELECT in.id AS qid, in.name AS name, since FROM (SELECT expand(outE('KNOWS')) FROM Person WHERE id = {id})"
READ_COLS = {"t1": "id, name, age, city, created", "t2": "id, name, created", "t3": "age, count(*), sum(created)", "t4": "id, name, age", "t5": "qid, name, since"}


def write_text(task, arm, tbl, rows=None, ident=None, age=None):
    """Statements of the write tasks. t6: rows=[row]; t7: ident, age; t8: ident; t9: rows=[many]. tbl is the type or table of the arm."""
    if task in ("t6", "t9"):
        if arm == "C":
            if task == "t6":
                r = rows[0]
                return "opencypher", (f"CREATE (m:{tbl} {{id: {r['id']}, name: {sql_lit(r['name'])}, age: {r['age']}, city: {sql_lit(r['city'])}, created: {r['created']}}})")
            maps = ", ".join(f"{{id: {r['id']}, name: {sql_lit(r['name'])}, age: {r['age']}, city: {sql_lit(r['city'])}, created: {r['created']}}}" for r in rows)
            return "opencypher", (f"UNWIND [{maps}] AS r CREATE (m:{tbl} {{id: r.id, name: r.name, age: r.age, city: r.city, created: r.created}})")
        vals = ", ".join(f"({r['id']}, {sql_lit(r['name'])}, {r['age']}, {sql_lit(r['city'])}, {r['created']})" for r in rows)
        return LANG.get(arm, "postgresql"), f"INSERT INTO {tbl} (id, name, age, city, created) VALUES {vals}"
    if task == "t7":
        if arm == "C":
            return "opencypher", f"MATCH (m:{tbl}) WHERE m.id = {ident} SET m.age = {age}"
        return LANG.get(arm, "postgresql"), f"UPDATE {tbl} SET age = {age} WHERE id = {ident}"
    if task == "t8":
        if arm == "C":
            return "opencypher", f"MATCH (m:{tbl}) WHERE m.id = {ident} DELETE m"
        return LANG.get(arm, "postgresql"), f"DELETE FROM {tbl} WHERE id = {ident}"
    raise KeyError(task)


def tbl_text(arm, tbl):
    """The name as the arm's statements write it: the PostgreSQL language and PostgreSQL fold unquoted names to lower case."""
    return tbl.lower() if arm in ("P", "R") else tbl


def cleanup_text(tbl, lo, hi):
    """Untimed removal of a range of ids, in native SQL for every arm (so that no arm's own language is used to undo its own work)."""
    return f"DELETE FROM {tbl} WHERE id >= {lo} AND id < {hi}"


def read_params(w, task):
    c = w.c
    if task == "t1":
        return list(w.t1_ids)
    if task == "t2":
        return list(w.t2_cities)
    if task == "t3":
        return [None] * c["t3_calls"]
    if task == "t4":
        return list(w.t4_cities)
    if task == "t5":
        return list(w.t5_starts)
    raise KeyError(task)


def read_pass_texts(w, task, arm):
    return [read_text(task, arm, x) for x in read_params(w, task)]


def write_pass(w, task, arm, tbl, r, j):
    """(timed units, untimed cleanup or None) of one pass j of the write task in round r, as lists of (language, text)."""
    k = w.c["write_k"]
    t = tbl_text(arm, tbl)
    if task == "t6":
        return [write_text("t6", arm, t, rows=[new_row(new_id(r, j, i))]) for i in range(k)], None
    if task == "t7":
        return [write_text("t7", arm, t, ident=x, age=upd_age(r, j, i)) for i, x in enumerate(w.t7_ids)], None
    if task == "t8":
        return [write_text("t8", arm, t, ident=new_id(r, j, i)) for i in range(k)], None
    if task == "t9":
        lo, hi = batch_id_range(r, j, w.c["batch_rows"])
        rows = [new_row(i) for i in range(lo, hi)]
        return [write_text("t9", arm, t, rows=rows)], ("sql", cleanup_text(tbl, lo, hi))
    raise KeyError(task)


# ---------------------------------------------------------------------------------------------------------------------------------
# THE ORACLE OF THE WRITES: the table after each step of the answer check, computed in Python from the seed rows alone
def seed_state(w):
    return {p["id"]: p for p in w.persons}


def apply_check_steps(w, r=0, j=0):
    """The states (a dict id -> row) after t6, t7, t8, t9 and the cleanup of t9 of one pass, in this order: what every arm's check table has to hold."""
    k = w.c["write_k"]
    st = seed_state(w)
    out = []
    for i in range(k):
        row = new_row(new_id(r, j, i))
        st[row["id"]] = row
    out.append(("after t6", dict(st)))
    for i, x in enumerate(w.t7_ids):
        if x in st:
            st[x] = dict(st[x], age=upd_age(r, j, i))
    out.append(("after t7", dict(st)))
    for i in range(k):
        st.pop(new_id(r, j, i), None)
    out.append(("after t8", dict(st)))
    lo, hi = batch_id_range(r, j, w.c["batch_rows"])
    for i in range(lo, hi):
        st[i] = new_row(i)
    out.append(("after t9", dict(st)))
    for i in range(lo, hi):
        st.pop(i, None)
    out.append(("after the cleanup of t9", dict(st)))
    return out


def final_state(w, c):
    """The table of every write arm after the whole timed run: the seed with the ages of the last update of each key (t6 and t9 rows are gone)."""
    st = seed_state(w)
    for r in range(ROUNDS_WARMUP + c["rounds"]):
        for j in range(passes_of_task(c, "t7")):
            for i, x in enumerate(w.t7_ids):
                st[x] = dict(st[x], age=upd_age(r, j, i))
    return st


def table_checksum(names, rows):
    """sha256 of the read-back table (columns sorted by name, rows sorted by id), the form a state of the oracle is put in below."""
    ix = names.index("id")
    body = json.dumps([names, sorted(rows, key=lambda r: r[ix])], sort_keys=True, default=str)
    return hashlib.sha256(body.encode()).hexdigest()


def state_checksum(state):
    names = ["age", "city", "created", "id", "name"]
    rows = [[row[n] for n in names] for row in state.values()]
    return table_checksum(names, rows)


# ---------------------------------------------------------------------------------------------------------------------------------
# INTERLEAVING: the turns of a round. The arms of a task rotate and reverse by L.arm_order; S and Z are ONE turn, a block whose passes alternate
# (S Z Z S), placed where the first of the two stands, opened by that arm (the version 3 design of pg13, pair 1, with S in the place of J).
def task_turns(r, arms):
    turns, seen = [], False
    for a in L.arm_order(r, tuple(arms)):
        if a in ("S", "Z"):
            if not seen:
                seen = True
                turns.append(("SZ", a))
        else:
            turns.append((a, a))
    return turns


def abba_sz(first, k):
    """The arm of each of the 2k passes of the S/Z block: first, other, other, first, then again (k passes of each arm, k even)."""
    other = "Z" if first == "S" else "S"
    return [x for _ in range(k // 2) for x in (first, other, other, first)]


def statements_digest(w):
    """sha256 over every statement text of every arm of the read tasks and the first pass of each write task (a reference made for other statements is refused)."""
    h = hashlib.sha256()
    for t in READ_TASKS:
        for a in ("P", "S", "C", "R"):
            for lg, tx in read_pass_texts(w, t, a):
                h.update(f"{lg}\0{tx}\0".encode())
    for t in WRITE_TASKS:
        for a in ("P", "S", "C", "R"):
            units, cleanup = write_pass(w, t, a, CHK[a], 0, 0)
            for lg, tx in units + ([cleanup] if cleanup else []):
                h.update(f"{lg}\0{tx}\0".encode())
    return h.hexdigest()


# ---------------------------------------------------------------------------------------------------------------------------------
# THE MEMORY READING (amendment of 2026-11-24, before any timed run): a SECOND reading beside the latency one. It never changes a latency verdict,
# a gate, an exit status or the rule that voids a run. The figure of an arm is the JVM allocation of the CALLING thread per call (the bytes the
# thread allocated between the two reads of com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes that bracket the timed call, outside it),
# as the mean of one turn; M_a is the median over the measured rounds of that mean, exactly as the latency figure is. Other threads of the JVM are
# sampled around every pass (all live threads but the calling one) and reported beside it; the arm R (a server in another process) has no figure.
MEM_AA_BAND = 0.10             # allocation A/A control: Z/S within 1 -+ this (0.90 to 1.10); the calibration on the development machine, in the amendment, says why
MEM_OFFTHREAD_MAX = 0.10       # a task whose other threads allocate more than this share of (calling thread + other threads) in P, S or Z is OFF-THREAD
MEM_PROBE_MOVES = (1 << 20, int(1.1 * (1 << 20)))   # the counter has to move by a 1 MiB array allocated by the thread: within these bounds


def memory_verdict(z_s, p_s, off_thread=False):
    """The registered memory reading of one task from Z/S and P/S of the allocation per call (never touches the latency verdict)."""
    if abs(z_s - 1) > MEM_AA_BAND + 1e-9:
        return "NOT READABLE (A/A control out of band)"
    if p_s <= RATIO_IN_RANGE:
        v = "IN THE RANGE"
    elif p_s <= RATIO_REPORTED_MAX:
        v = "REPORTED, NOT CLAIMED"
    else:
        v = "OUTSIDE THE RANGE"
    if off_thread and v == "IN THE RANGE":
        v = "REPORTED, NOT CLAIMED (off-thread work)"
    return v


def others_delta(before, after):
    """(bytes the other threads allocated between two samples, threads of the first sample that were gone in the second). A sample is {thread id: bytes}
    of every live thread but the calling one; a thread that is new in the second sample counts from zero; a thread that ended in between is lost (counted)."""
    return sum(v - before.get(i, 0) for i, v in after.items()), len(set(before) - set(after))


def proc_status_kb(field):
    """A kB figure of /proc/self/status (VmHWM, VmRSS); None when the file or the field is missing."""
    try:
        for line in open("/proc/self/status", encoding="utf-8"):
            if line.startswith(field + ":"):
                return int(line.split()[1])
    except OSError:
        pass
    return None


def reset_peak_rss():
    """Reset VmHWM of this process to its current RSS (writing 5 to /proc/self/clear_refs, Linux 4.0 and later). True when the kernel took it and VmHWM fell to RSS."""
    try:
        with open("/proc/self/clear_refs", "w", encoding="utf-8") as f:
            f.write("5")
    except OSError:
        return False
    hwm, rss = proc_status_kb("VmHWM"), proc_status_kb("VmRSS")
    return hwm is not None and rss is not None and hwm <= rss * 1.02 + 64


# ---------------------------------------------------------------------------------------------------------------------------------
# THE CPU AND WRITE READINGS (the second amendment of 2026-11-24, before any timed run, after the memory amendment): two more readings beside the latency one,
# never touching it. CPU: the CPU time the CALLING thread used per call (two reads of ThreadMXBean.getCurrentThreadCpuTime around each call, innermost of the
# counters, outside the timed window; the null pair of the same reads is measured in the same JVM and subtracted). Write: the bytes the process passed to write
# calls per call (`wchar` of /proc/self/io around each pass; t6 to t9 only). Load and disk of the whole process are reported per arm and per task block with no rule.
CPU_AA_BAND = 0.10             # A/A control of the CPU time per call: Z/S within 1 -+ this; set from the development machine run by the rule in the amendment
CPU_OFFTHREAD_MAX = 0.50       # the share of the process's CPU that is not the calling thread's, above this in P, S or Z: off-thread, an in-range ratio is not claimed (rule and numbers in the amendment)
WRITE_AA_BAND = 0.03           # A/A control of the bytes written per call (wchar) of the single-row writes t6 to t8: Z/S within 1 -+ this
WRITE_AA_BAND_BATCH = 0.30     # the same for t9, one batch a call and eight calls a turn, whose flush runs on into the next pass (the amendment gives the distances seen)
WRITE_AA_BANDS = {"t6": WRITE_AA_BAND, "t7": WRITE_AA_BAND, "t8": WRITE_AA_BAND, "t9": WRITE_AA_BAND_BATCH}
CPU_PROBE_AGREE = (0.05, 200_000)   # the thread CPU clock of the JVM must agree with Python's time.thread_time_ns over a 20 ms spin within 5 percent + 200 microseconds
IO_PROBE_BYTES = 4 << 20       # the disk probe writes this many bytes to a file of the database folder and wchar must grow by at least that
IO_KEYS = ("write_bytes", "wchar", "syscw", "read_bytes", "rchar", "syscr")


def reading_verdict(z_s, p_s, band, off_thread=False):
    """The registered verdict of one task for a CPU or write reading from Z/S and P/S (the latency thresholds, a band of its own, never touches the latency verdict)."""
    if abs(z_s - 1) > band + 1e-9:
        return "NOT READABLE (A/A control out of band)"
    if p_s <= RATIO_IN_RANGE:
        v = "IN THE RANGE"
    elif p_s <= RATIO_REPORTED_MAX:
        v = "REPORTED, NOT CLAIMED"
    else:
        v = "OUTSIDE THE RANGE"
    if off_thread and v == "IN THE RANGE":
        v = "REPORTED, NOT CLAIMED (off-thread work)"
    return v


def proc_io():
    """The counters of /proc/self/io as ints ({} when the file is unreadable): rchar, wchar, syscr, syscw, read_bytes, write_bytes, cancelled_write_bytes."""
    try:
        with open("/proc/self/io", "rb") as f:
            return {k.decode(): int(v) for k, v in (line.split(b":") for line in f.read().splitlines() if b":" in line)}
    except (OSError, ValueError):
        return {}


def proc_cpu_split_s():
    """(user, system) CPU seconds of this process from /proc/self/stat (utime and stime, in clock ticks); (0.0, 0.0) when unreadable."""
    try:
        with open("/proc/self/stat", encoding="utf-8") as f:
            rest = f.read().rsplit(")", 1)[1].split()
        hz = os.sysconf("SC_CLK_TCK")
        return int(rest[11]) / hz, int(rest[12]) / hz
    except (OSError, ValueError, IndexError):
        return 0.0, 0.0


def mount_of(path):
    """(mount point, file system type) of the mount that holds `path`, from /proc/self/mountinfo (the longest mount point that is a prefix); ('?', '?') when unreadable."""
    best = ("?", "?")
    try:
        real = os.path.realpath(path)
        for line in open("/proc/self/mountinfo", encoding="utf-8"):
            left, right = line.split(" - ", 1)
            mp = left.split()[4].replace("\\040", " ")
            if (real == mp or real.startswith(mp.rstrip("/") + "/")) and (best[0] == "?" or len(mp) >= len(best[0])):
                best = (mp, right.split()[0])
    except (OSError, ValueError, IndexError):
        pass
    return best


def row_logical_bytes(row):
    """The bytes of one row's values as the statements carry them: id and created 8 each, age 4, name and city as UTF-8 (the logical size a write amplification is read against)."""
    return 8 + 8 + 4 + len(str(row["name"]).encode()) + len(str(row["city"]).encode())
