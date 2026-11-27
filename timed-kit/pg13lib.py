"""Shared pieces of the pg13 study (fork issue humemai/arcadedb#13): statements, the data generators of the benchmark's own lanes, the
comparison of results as multisets, the interleaving and the results files. Nothing here starts a JVM or a container.

Thresholds live HERE, as constants, written before the first run; the environment cannot move them (the environment moves only the
caps of a smoke run and the paths). A threshold changes only by the owner's word, and a change is a new study.
"""
from __future__ import annotations

import csv
import datetime
import decimal
import hashlib
import json
import math
import os
import random
import re
import statistics
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------------------------------------------------------------------------
# THE THRESHOLDS (fork issue #1 question 25, confirmed 2026-11-23; the rest is the benchmark's standing rule)
RATIO_IN_RANGE = 1.3          # median(join) / median(Cypher) at most this: in the range
RATIO_REPORTED_MAX = 2.0      # above RATIO_IN_RANGE and up to this: reported, not claimed; above: outside the range, reported with its plan
ROUNDS_MEASURED = 5           # n=5 interleaved rounds ...
ROUNDS_WARMUP = 1             # ... after one warm-up round (round 0, never read)
P1_PASSES = 200               # version 3, pair 1 only: a turn of arm J, C or Z is this many passes over the 200 starts (arm O keeps one pass); C and Z alternate pass by pass
AA_BAND = 0.03                # A/A control: the median ratio Z/C must lie in 1 -+ this (the benchmark's aa_band, 0.97 to 1.03)
CONTROL_LO, CONTROL_HI = 0.90, 1.10   # every comparison outside this band is listed, whatever its verdict (the control-rows rule)
FLOAT_REL_TOL = 1e-9          # two floating point cells match when they agree to this relative tolerance
ARMS = ("J", "C", "O", "Z")   # J join with the traversal rule on, C Cypher, O join with the rule off, Z Cypher again (the A/A control)
ARM_NAMES = {"J": "join, rule on", "C": "Cypher", "O": "join, rule off", "Z": "Cypher again (A/A)"}
PICK_SEED = 777               # the graph lane's seed for start ids

PAIRS = ("p1", "p2", "p3")
PAIR_TITLES = {"p1": "one hop from one vertex", "p2": "one hop from all vertices, aggregate", "p3": "one hop, LEFT JOIN"}
TPCH_QUERIES = ("q1", "q6", "q14", "q19")
TPCH_FILES = {"q1": "q01.sql", "q6": "q06.sql", "q14": "q14.sql", "q19": "q19.sql"}

# The statements. The relational names are those of fork issue #38, rule B (version 2 of the kit; fork PR #44 is in both engines under test):
# a vertex type with its own property `id` shows it as the column `id` (the LDBC id, bigint, the primary key) and its record id as text is the
# column `rid`; `source_id` and `target_id` of an edge table hold record ids, so they join to `rid`. An edge type without a property `id`
# (KNOWS has only `since`) keeps its record id as the column `id`: `count(k.id)` of pair 3 counts edge records and is unchanged. The start id
# is an integer literal in BOTH languages (the new language has no stated parameter support, so neither arm binds a parameter). Each pair
# follows the stored direction of KNOWS (the lane stores each friendship once, from the smaller id to the larger).
JOIN_P1 = ("SELECT q.id AS qid, q.name AS name, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid "
           "JOIN person q ON k.target_id = q.rid WHERE p.id = {id}")
CYPHER_P1 = ("MATCH (p:Person)-[k:KNOWS]->(q:Person) WHERE p.id = {id} "
             "RETURN q.id AS qid, q.name AS name, k.since AS since")
JOIN_P2 = ("SELECT count(*) AS n, sum(q.age) AS age_sum FROM person p JOIN knows k ON k.source_id = p.rid "
           "JOIN person q ON k.target_id = q.rid")
CYPHER_P2 = "MATCH (p:Person)-[k:KNOWS]->(q:Person) RETURN count(*) AS n, sum(q.age) AS age_sum"
JOIN_P3 = ("SELECT count(*) AS rows_n, count(k.id) AS edges_n, sum(q.age) AS age_sum FROM person p LEFT JOIN knows k ON k.source_id = p.rid "
           "LEFT JOIN person q ON k.target_id = q.rid")
CYPHER_P3 = ("MATCH (p:Person) OPTIONAL MATCH (p)-[k:KNOWS]->(q:Person) "
             "RETURN count(*) AS rows_n, count(k) AS edges_n, sum(q.age) AS age_sum")
TEXTS = {"p1": (JOIN_P1, CYPHER_P1), "p2": (JOIN_P2, CYPHER_P2), "p3": (JOIN_P3, CYPHER_P3)}


def cfg():
    e = os.environ.get
    home = Path.home()
    return dict(
        tpch_dir=Path(e("PG13_TPCH_DIR", str(home / "bench-data" / "tpch"))), tpch_sf=e("PG13_TPCH_SF", "1"),
        snb_dir=Path(e("PG13_SNB_DIR", str(home / "bench-data" / "ldbc"))), snb_scale=e("PG13_SNB_SCALE", "sf1"),
        lineitem_limit=int(e("PG13_LINEITEM_LIMIT", "0")), person_limit=int(e("PG13_PERSON_LIMIT", "0")),
        rounds=int(e("PG13_ROUNDS", str(ROUNDS_MEASURED))), p1_starts=int(e("PG13_P1_STARTS", "200")), p1_passes=int(e("PG13_P1_PASSES", str(P1_PASSES))),
        p23_calls=int(e("PG13_P23_CALLS", "3")), tpch_reps=int(e("PG13_TPCH_REPS", str(ROUNDS_MEASURED))),
        plan_token=e("PG13_PLAN_TOKEN", "TRAVERSE"), plan_model=e("PG13_TRAVERSAL_MODEL", ""), study_role=e("PG13_STUDY_ROLE", ""), heap=e("PG13_HEAP", "8g"), join_memory_mb=e("PG13_JOIN_MEMORY_MB", "256"),
        rule_setting=e("PG13_RULE_SETTING", "POSTGRESQL_TRAVERSAL_RULE"),
        tpch_case=e("PG13_TPCH_TYPE_CASE", "lane"),   # lane: types LineItem and Part as the lane names them; lower: lineitem and part
    )


def check_cfg(c):
    """The traversal model has no default: a study folder pins the rule of the engine it tests (config.env)."""
    if c["plan_model"] not in PLAN_MODELS:
        raise SystemExit(f"PG13_TRAVERSAL_MODEL is '{c['plan_model']}', expected one of {sorted(PLAN_MODELS)} (a pin of config.env)")
    if c["p1_passes"] < 2 or c["p1_passes"] % 2:
        raise SystemExit(f"PG13_P1_PASSES is {c['p1_passes']}: it has to be an even number of at least 2 (the passes of C and Z alternate in blocks of four)")


def caps_of(c):
    return {k: str(c[k]) for k in ("tpch_sf", "snb_scale", "lineitem_limit", "person_limit", "p1_starts")}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_queries(qdir):
    """The four TPC-H texts: the text of each file with leading and trailing whitespace stripped, nothing else touched."""
    out = {}
    for q, fn in TPCH_FILES.items():
        out[q] = (Path(qdir) / fn).read_bytes().decode("utf-8").strip()
    return out


def statements_digest(queries):
    h = hashlib.sha256()
    for p in PAIRS:
        for t in TEXTS[p]:
            h.update(t.encode())
            h.update(b"\0")
    for q in TPCH_QUERIES:
        h.update(queries[q].encode())
        h.update(b"\0")
    return h.hexdigest()


# ---------------------------------------------------------------------------------------------------------------------------------
# PLAN GATES (version 2). What the plan of each pair has to show with the traversal rule ON, per MODEL of the engine under test (a pin of
# config.env, written before the run):
#   all-starts   the rule as it was in runs 1 and 2 and in no later commit: with the setting on, every join an edge supports is a walk
#                (TRAVERSE), whatever its start, and no plan line carries a reason. The BASE study (fork 3ebb25e786) has this rule.
#   bound-start  fork PR #53 (issue #52): with the setting on, a join is a walk only when its start is bound. p1 (WHERE p.id = <id>, a unique
#                key) is TRAVERSE with the note [traversal: start bound by p.id ...] on each walk line, and has no HASH JOIN. p2 and p3 have
#                no bound start: no TRAVERSE, a HASH JOIN with the note [hash join: no bound start] (a join the planner makes an index nested
#                loop carries [index nested loop: no bound start]), and the plan is, once the notes are cut out, the plan of the rule off,
#                line for line (the test of the fork's TraversalBoundStartTest compares them the same way). The HEAD study has this rule.
# With the rule OFF the plan has no TRAVERSE, has a join, and has no note of either kind, in both models (nothing is added with the setting off).
# A gate that fails makes its pair NOT TIMED.
PLAN_MODELS = {"all-starts": {"p1": "walk", "p2": "walk", "p3": "walk"},
               "bound-start": {"p1": "walk", "p2": "hash", "p3": "hash"}}
NOTE_WALK = "[traversal: start bound by p.id"
NOTE_HASH = "[hash join: no bound start]"
NOTE_UNBOUND_RE = re.compile(r" \[(?:hash join|index nested loop): no bound start\]")


def plan_norm(text):
    """The plan as lines without trailing blanks and without blank ends: the form two plans are compared in."""
    return "\n".join(line.rstrip() for line in text.strip().splitlines())


def plan_asserts(model, pair):
    """One sentence: what the gate of this pair asserts (it is the name of the gate in gates.jsonl and the text of the README)."""
    kind = PLAN_MODELS[model][pair]
    if model == "all-starts":
        return f"plan {pair} ({model}): TRAVERSE with no note with the rule on; no TRAVERSE and no note with it off"
    if kind == "walk":
        return f"plan {pair} ({model}): TRAVERSE with [traversal: start bound by p.id ...] and no HASH JOIN with the rule on; no TRAVERSE and no note with it off"
    return (f"plan {pair} ({model}): HASH JOIN with [hash join: no bound start] and no TRAVERSE with the rule on, the same plan as with it off "
            f"once the notes are cut out; no TRAVERSE and no note with it off")


def plan_on_check(model, pair, on, token, off=None):
    """(ok, detail) for the plan of the statement with the rule ON. `off` is the plan of the same statement with the rule OFF (needed
    for the pairs that have no bound start under the bound-start model)."""
    if not on.strip():
        return False, "empty plan"
    has_token = token in on
    walk_note = "[traversal: start bound by" in on
    unbound_note = NOTE_UNBOUND_RE.search(on) is not None
    facts = f"token={has_token} walk-note={walk_note} unbound-note={unbound_note} hash-join={'HASH JOIN' in on}"
    if model == "all-starts":
        return has_token and not walk_note and not unbound_note, facts
    if PLAN_MODELS[model][pair] == "walk":
        return has_token and NOTE_WALK in on and not unbound_note and "HASH JOIN" not in on, facts
    same = off is not None and plan_norm(NOTE_UNBOUND_RE.sub("", on)) == plan_norm(off)
    return (not has_token) and "HASH JOIN" in on and NOTE_HASH in on and not walk_note and same, facts + f" same-as-off-without-notes={same}"


def plan_off_check(off, token):
    """(ok, detail) for the plan with the rule OFF: no walk, a join, and no note."""
    if not off.strip():
        return False, "empty plan"
    notes = "start bound by" in off or "no bound start" in off
    facts = f"token={token in off} join={'JOIN' in off.upper()} note={notes}"
    return token not in off and "JOIN" in off.upper() and not notes, facts


# ---------------------------------------------------------------------------------------------------------------------------------
# DATA GENERATORS OF THE BENCHMARK'S OWN LANES. The five functions below are copies of mmdbbench experiments/ldbc_snb.py; check_kit.py
# compares their source with the file at the pinned commit, so the graph is the graph lane's graph.
_REF_YEAR = 2026


def _birth_year(raw):
    try:
        ms = int(raw)
    except ValueError:
        return int(raw[:4]) if raw[:4].isdigit() else 1980
    return datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc).year


def _root(scale):
    base = Path(os.environ.get("PG13_SNB_DIR", str(Path.home() / "bench-data" / "ldbc"))) / scale
    hits = list(base.glob("social_network-*/dynamic"))
    if not hits:
        sys.exit(f"ldbc_snb: no dynamic/ dir under {base}")
    return hits[0]


def _csv_rows(path):
    with open(path, newline="", encoding="utf-8") as f:
        r = csv.reader(f, delimiter="|")
        header = next(r)
        yield header
        yield from r


def _loaded_persons(scale, limit):
    if not limit:
        return None
    s = set()
    rows = _csv_rows(_root(scale) / "person_0_0.csv")
    i_id = next(rows).index("id")
    for n, row in enumerate(rows):
        if n >= limit:
            break
        s.add(int(row[i_id]))
    return s


def gen_persons(scale, limit=0):
    """Yield (id, name, age, city) from person_0_0.csv (capped by limit), as the graph lane does."""
    rows = _csv_rows(_root(scale) / "person_0_0.csv")
    header = next(rows)
    col = {name: i for i, name in enumerate(header)}
    i_id, i_fn, i_ln = col["id"], col["firstName"], col["lastName"]
    i_bd = col["birthday"]
    i_place = col.get("place", len(header) - 1)
    for n, row in enumerate(rows):
        if limit and n >= limit:
            break
        birth_year = _birth_year(row[i_bd])
        yield (int(row[i_id]), f"{row[i_fn]} {row[i_ln]}", max(0, _REF_YEAR - birth_year), f"city_{row[i_place]}")


def gen_edges(scale, limit=0):
    """Yield (src_id, dst_id, since_year) from person_knows_person_0_0.csv; under a cap only edges with both ends loaded."""
    keep = _loaded_persons(scale, limit)
    rows = _csv_rows(_root(scale) / "person_knows_person_0_0.csv")
    header = next(rows)
    for row in rows:
        src, dst = int(row[0]), int(row[1])
        if keep is not None and (src not in keep or dst not in keep):
            continue
        try:
            since = 1970 + int(row[2]) // 31_557_600_000
        except (ValueError, IndexError):
            since = 2012
        yield src, dst, since


def pick_starts(scale, limit, n):
    """n start persons, seeded, among the persons that have at least one outgoing KNOWS in the loaded graph (a start with no
    edge would make the pair compare two empty answers)."""
    srcs = sorted({s for s, _, _ in gen_edges(scale, limit)})
    rng = random.Random(PICK_SEED)
    return sorted(rng.sample(srcs, min(n, len(srcs))))


TPCH_LI_COLS = ["l_orderkey", "l_partkey", "l_quantity", "l_extendedprice", "l_discount", "l_returnflag", "l_linestatus",
                "l_shipdate", "l_shipmode", "l_tax", "l_shipinstruct"]   # the lane's LI_COLS, then the one column Q19 needs
TPCH_PART_COLS = ["p_partkey", "p_retailprice", "p_brand", "p_type", "p_size", "p_container"]  # the lane's two, then the three Q14 and Q19 read
LI_BATCH_ROWS = 122_880


def tpch_lineitem_batches(c):
    """Yield dicts of numpy columns of TPCH_LI_COLS, LI_BATCH_ROWS rows each, prepared as the lane prepares them (DECIMAL to
    float64, the ship date as an ISO string so each engine casts it to its DATE), capped by lineitem_limit rows."""
    import numpy as np
    import pyarrow.parquet as pq
    pf = pq.ParquetFile(str(Path(c["tpch_dir"]) / f"sf{c['tpch_sf']}_lineitem.parquet"))
    left = c["lineitem_limit"] or None
    for b in pf.iter_batches(batch_size=LI_BATCH_ROWS, columns=TPCH_LI_COLS):
        df = b.to_pandas()
        if left is not None:
            df = df.iloc[:left]
            left -= len(df)
        df["l_shipdate"] = df["l_shipdate"].astype(str)
        for col in ("l_quantity", "l_extendedprice", "l_discount", "l_tax"):
            df[col] = df[col].astype("float64")
        yield {k: (df[k].to_numpy() if df[k].dtype != object else np.asarray(df[k], dtype=object)) for k in TPCH_LI_COLS}
        if left is not None and left <= 0:
            break


def tpch_part_frame(c):
    import pyarrow.parquet as pq
    part = pq.read_table(str(Path(c["tpch_dir"]) / f"sf{c['tpch_sf']}_part.parquet"), columns=TPCH_PART_COLS).to_pandas()
    part["p_retailprice"] = part["p_retailprice"].astype("float64")
    return part


# ---------------------------------------------------------------------------------------------------------------------------------
# RESULTS AS MULTISETS
def canon_cell(x):
    if x is None or isinstance(x, (bool, int, str)):
        return x
    if isinstance(x, (float, decimal.Decimal)):
        return float(x)
    if isinstance(x, (datetime.date, datetime.datetime)):
        return x.isoformat()
    if isinstance(x, (list, tuple)):
        return json.dumps([canon_cell(i) for i in x])
    if isinstance(x, dict):
        return json.dumps({str(k): canon_cell(v) for k, v in sorted(x.items(), key=lambda kv: str(kv[0]))})
    return str(x)


def to_table(dicts):
    """A list of result dicts to (column names sorted, rows as lists in that order, cells canonical)."""
    if not dicts:
        return [], []
    names = sorted(dicts[0].keys())
    return names, [[canon_cell(d.get(n)) for n in names] for d in dicts]


def _sig(v, n=4):
    return float(f"{v:.{n - 1}e}") if math.isfinite(v) else v


def _k(c):
    if c is None:
        return (0, 0.0, "", 0)
    if isinstance(c, bool):
        return (1, float(c), "", 0)
    if isinstance(c, (int, float)):
        # The rounded value is the primary key (floating point cells that differ by rounding noise must sort together), the exact integer
        # breaks the tie: without it two ids that share their first 4 digits (13194139535446 and 13194139538575) sort in the order each arm
        # returned them and equal answers are reported as different (pg13 first run, fork #51, 2026-11-24).
        return (2, _sig(float(c)), "", c if isinstance(c, int) else 0)
    return (3, 0.0, str(c), 0)


def _cell_equal(a, b):
    if a is None or b is None:
        return a is None and b is None
    if isinstance(a, bool) or isinstance(b, bool):
        return a == b
    if isinstance(a, int) and isinstance(b, int):
        return a == b
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return math.isclose(float(a), float(b), rel_tol=FLOAT_REL_TOL, abs_tol=1e-9)
    return a == b


def multiset_equal(a, b):
    """(ok, detail). a and b are (names, rows). Rows compare as complete multisets: the same number of rows, one duplicated row
    counts, integers, text and NULL identical, floating point cells to FLOAT_REL_TOL. Names compare when both sides have rows."""
    (na, ra), (nb, rb) = a, b
    if len(ra) != len(rb):
        return False, f"row count {len(ra)} against {len(rb)}"
    if ra and rb and na != nb:
        return False, f"column names {na} against {nb}"
    sa = sorted(ra, key=lambda r: tuple(_k(c) for c in r))
    sb = sorted(rb, key=lambda r: tuple(_k(c) for c in r))
    for i, (x, y) in enumerate(zip(sa, sb)):
        if len(x) != len(y) or not all(_cell_equal(p, q) for p, q in zip(x, y)):
            return False, f"sorted row {i}: {x} against {y}"
    return True, f"{len(ra)} rows"


# ---------------------------------------------------------------------------------------------------------------------------------
# INTERLEAVING AND TIMING
def arm_order(r, arms=ARMS):
    """The notes' order: the arm string rotated by half the round number, reversed on odd rounds (controlplane.ab.arm_order)."""
    k = (r // 2) % len(arms)
    rot = tuple(arms[k:]) + tuple(arms[:k])
    return rot[::-1] if r % 2 else rot


def p1_turns(r, arms=ARMS):
    """Version 3, pair 1: the turns of round r as (turn, first arm). The order of the four arms is the notes' rule (arm_order, unchanged). Arms
    C and Z are ONE turn, a block in which their passes alternate (abba), placed where the first of the two stands in that order; that arm
    opens the block. J and O keep their places."""
    turns, seen = [], False
    for a in arm_order(r, arms):
        if a in ("C", "Z"):
            if not seen:
                seen = True
                turns.append(("CZ", a))
        else:
            turns.append((a, a))
    return turns


def abba(first, k):
    """The arm of each of the 2k passes of the interleaved C/Z block: first, other, other, first, then again (k passes of each arm, k even)."""
    other = "Z" if first == "C" else "C"
    return [x for _ in range(k // 2) for x in (first, other, other, first)]


def timed_calls(run, texts):
    ns, rows = [], 0
    for t in texts:
        t0 = time.perf_counter_ns()
        rows += run(t)
        ns.append(time.perf_counter_ns() - t0)
    return ns, rows


def pair_texts(pair, arm, starts, calls):
    j, cy = TEXTS[pair]
    t = cy if arm in ("C", "Z") else j
    if pair == "p1":
        return [t.format(id=s) for s in starts]
    return [t] * calls


class Out:
    """The results folder of one run: append-only JSON lines, flushed and synced at every write."""

    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "raw").mkdir(exist_ok=True)

    def write(self, name, rec):
        p = self.root / "raw" / f"{name}.jsonl"
        with open(p, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, sort_keys=True, default=str) + "\n")
            f.flush()
            os.fsync(f.fileno())

    def gate(self, name, ok, detail=""):
        self.write("gates", {"name": name, "ok": bool(ok), "detail": str(detail)[:2000], "t": time.time()})
        print(("GATE ok   " if ok else "GATE FAIL ") + f"{name}: {str(detail)[:300]}", flush=True)
        return bool(ok)


def read_jsonl(path):
    p = Path(path)
    if not p.exists():
        return []
    return [json.loads(line) for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]


def median(xs):
    return statistics.median(xs)
