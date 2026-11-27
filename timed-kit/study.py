"""The pg13 study, phases `ref`, `main` and `smoke`:  study.py <phase> <out-dir>.

ref    PostgreSQL 18 (the server pg_ref_server.sh started): loads the lane's tables, answers every statement, times the four TPC-H
       statements as a reference point. Writes <out>/ref/answers.json.
main   the prototype engine in the swapped-jar JVM: loads the SNB graph and TPC-H, checks every answer against ref and against the
       other language BEFORE any timing, then times the three pairs interleaved (arms J C O Z) and the four TPC-H statements.
smoke  ref then main at the caps of the environment (a tiny load and two rounds): a rehearsal, never a result.

Version 2 (2026-11-25): the statements use the names of rule B (fork #38) and the plan gates follow the traversal model pinned in config.env
(pg13lib.PLAN_MODELS: `all-starts` for the base engine, `bound-start` for fork PR #53). One study folder is one engine.
Version 3 (2026-11-26): pair 1 only. A turn of arm J, C or Z is p1_passes passes over the 200 starts (arm O one pass), and C and Z alternate pass by
pass in one block (pg13lib.p1_turns, pg13lib.abba), so that the A/A control is a paired comparison of long turns. Pairs 2 and 3 are as in version 2.

Exit status: 0 done, 4 a gate failed (engine, jar, setting, plan, load), 6 an answer differed or a statement failed (a finding, the
run is complete), 1 broke. The reader (read_results.py) adds 3 when the A/A control left its band."""
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

HERE = Path(__file__).resolve().parent


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
                      "python": sys.version.split()[0], "packages": pkg_versions(), "lane": lane_facts(),
                      "caps": L.caps_of(c), "rounds_measured": c["rounds"], "rounds_warmup": L.ROUNDS_WARMUP, "p1_passes": c["p1_passes"], "heap": c["heap"], "join_memory_mb": c["join_memory_mb"],
                      "engine_commit": os.environ.get("PG13_ENGINE_COMMIT"), "engine_jar_sha256": os.environ.get("PG13_ENGINE_JAR_SHA256"),
                      "traversal_model": c["plan_model"], "study_role": c["study_role"],
                      "mmdbbench_commit": os.environ.get("PG13_MMDBBENCH_COMMIT"), "pg_image": os.environ.get("PG13_PG_IMAGE")})


def world(c, dry):
    queries = L.load_queries(HERE / "queries")
    starts = list(range(1000, 1000 + c["p1_starts"])) if dry else L.pick_starts(c["snb_scale"], c["person_limit"], c["p1_starts"])
    return queries, starts


def statements(queries, starts):
    """Every statement with its key: (key, kind, join text, Cypher text or None)."""
    out = []
    for p in L.PAIRS:
        j, cy = L.TEXTS[p]
        if p == "p1":
            out += [(f"p1:{s}", "pair", j.format(id=s), cy.format(id=s)) for s in starts]
        else:
            out.append((p, "pair", j, cy))
    out += [(q, "tpch", queries[q], None) for q in L.TPCH_QUERIES]
    return out


# ---------------------------------------------------------------------------------------------------------------------------------
def run_ref(out, c, dry, ref_dir):
    queries, starts = world(c, dry)
    if dry:
        import backend_stub as S
        reg = S.Registry(starts, queries)
        be = S.StubPG(reg)
    else:
        import backend_pg
        be = backend_pg.PGBackend(c)
    out.write("env", {"ref_server": be.facts()})
    t0 = time.perf_counter()
    snb = be.load_snb(c)
    t1 = time.perf_counter()
    tpch = be.load_tpch(c)
    t2 = time.perf_counter()
    out.write("env", {"ref_load": {"snb": snb, "tpch": tpch, "snb_s": t1 - t0, "tpch_s": t2 - t1}})
    answers = {}
    for key, kind, jt, _ in statements(queries, starts):
        try:
            names, rows = be.table(jt)
            answers[key] = {"cols": names, "rows": rows}
        except Exception as e:          # noqa: BLE001
            answers[key] = {"error": f"{type(e).__name__}: {e}"}
            out.gate(f"reference answers {key}", False, answers[key]["error"])
    # reference timing: the lane's way (execute and fetch everything), one warm-up round then the measured rounds, statements interleaved
    ref_stmts = [("p1", [jt for k, _, jt, _ in statements(queries, starts) if k.startswith("p1:")])] + \
                [(p, [L.TEXTS[p][0]] * c["p23_calls"]) for p in ("p2", "p3")] + [(q, [queries[q]]) for q in L.TPCH_QUERIES]
    for r in range(L.ROUNDS_WARMUP + c["tpch_reps"]):
        for key, texts in ref_stmts:
            if "error" in answers.get(key if key != "p1" else f"p1:{starts[0]}", {}):
                continue
            ns, rows = L.timed_calls(be.run, texts)
            out.write("ref_timing", {"id": key, "round": r, "warm": r < L.ROUNDS_WARMUP, "calls": len(texts), "mean_ns": sum(ns) / len(ns), "rows": rows})
    server_facts = be.facts()       # read BEFORE the connection is closed (the first smoke on the measurement host died here: the connection is closed)
    be.close()
    ref_dir = Path(ref_dir)
    ref_dir.mkdir(parents=True, exist_ok=True)
    (ref_dir / "answers.json").write_text(json.dumps(answers, sort_keys=True), encoding="utf-8")
    failed = [k for k, v in answers.items() if "error" in v]
    (ref_dir / "done.json").write_text(json.dumps({"caps": L.caps_of(c), "digest": L.statements_digest(queries), "n_answers": len(answers),
                                                   "failed": failed, "server": server_facts, "load": {"snb": snb, "tpch": tpch}}, sort_keys=True), encoding="utf-8")
    return 4 if failed else 0


# ---------------------------------------------------------------------------------------------------------------------------------
def check_pair(out, be, ref, p, stmts):
    """Answers of one pair, every statement: join with the rule on, join with the rule off, Cypher, and the reference. Returns the
    list of failures (each says which comparison failed and how)."""
    fails = []
    for key, jt, ct in stmts:
        got = {}
        for arm, lang, text, rule in (("J", "postgresql", jt, True), ("O", "postgresql", jt, False), ("C", "opencypher", ct, True)):
            be.set_rule(rule)
            try:
                got[arm] = be.table(lang, text)
            except Exception as e:      # noqa: BLE001
                got[arm] = None
                fails.append({"key": key, "cmp": f"{arm} error", "detail": f"{type(e).__name__}: {str(e)[:400]}"})
        be.set_rule(True)
        r = ref.get(key, {})
        refv = None if "rows" not in r else (r["cols"], r["rows"])
        pairs = [("J=C", "J", "C"), ("O=C", "O", "C"), ("J=ref", "J", None), ("C=ref", "C", None)]
        for name, a, b in pairs:
            x = got[a]
            y = got[b] if b else refv
            if x is None or y is None:
                if b is None and refv is None:
                    fails.append({"key": key, "cmp": name, "detail": "no reference answer"})
                continue
            ok, detail = L.multiset_equal(x, y)
            if not ok:
                fails.append({"key": key, "cmp": name, "detail": detail[:600]})
    out.write("validation", {"pair": p, "statements": len(stmts), "failures": len(fails), "first": fails[:5]})
    return fails


def plan_gate(out, be, p, sample_text, token, model, refoff):
    """The plan gate of one pair, before its timing: the plans of the sample statement with the rule on and with it off, checked by the
    model of the engine under test (pg13lib.PLAN_MODELS, what each gate asserts is pg13lib.plan_asserts). The plan with the rule off is kept
    in refoff[p]: every batch of arm J and arm O is checked against it again."""
    be.set_rule(True)
    on = be.explain(sample_text)
    be.set_rule(False)
    off = be.explain(sample_text)
    be.set_rule(True)
    out.write("plans", {"pair": p, "model": model, "rule_on": on, "rule_off": off})
    ok_on, d_on = L.plan_on_check(model, p, on, token, off)
    ok_off, d_off = L.plan_off_check(off, token)
    refoff[p] = off
    return out.gate(L.plan_asserts(model, p), ok_on and ok_off, f"rule on: {d_on}; rule off: {d_off}; off-text-len={len(off)}")


def arm_plan_ok(out, be, p, arm, r, text, model, token, refoff):
    """The plan check before every turn of arm J and arm O, on the very statement that is timed: J has the plan of the model's rule on, O the
    plan of the rule off, and O is the plan the gate saw (a plan cached under the other setting, or a setting that did not hold, shows here).
    False after the gate is written (the timing stops)."""
    be.set_rule(arm == "J")
    plan = be.explain(text)
    if arm == "J":
        ok, detail = L.plan_on_check(model, p, plan, token, refoff[p])
    else:
        ok, detail = L.plan_off_check(plan, token)
        ok = ok and L.plan_norm(plan) == L.plan_norm(refoff[p])
    if not ok:
        out.gate(f"arm {arm} in force at round {r} of {p}", False, f"{detail}; {plan[:300]}")
        be.set_rule(True)
    return ok


def passes_of(ns, n):
    """The mean call time of each pass of n calls (what a reader of the raw file needs to look at a long turn pass by pass)."""
    return [sum(ns[i:i + n]) / n for i in range(0, len(ns), n)]


def time_p1(out, be, c, starts, token, model, refoff, r, warm):
    """Version 3, pair 1, round r: the turns of L.p1_turns(r). J is c['p1_passes'] passes over the starts, O is one pass, C and Z are one block
    of 2k passes that alternate (L.abba) and are recorded as two arms. The figure of an arm is still the mean wall time of one call of its turn
    (the same statements, the same starts, the same call). GC counts around each turn are recorded and read by no rule."""
    k, n = c["p1_passes"], len(starts)
    cy = L.pair_texts("p1", "C", starts, c["p23_calls"])
    for turn, first in L.p1_turns(r):
        if turn in ("J", "O"):
            passes = k if turn == "J" else 1
            texts = L.pair_texts("p1", turn, starts, c["p23_calls"]) * passes
            if not arm_plan_ok(out, be, "p1", turn, r, texts[0], model, token, refoff):
                return False
            g0, t0 = be.gc_stats(), time.time()
            ns, rows = L.timed_calls(lambda t: be.run("postgresql", t), texts)
            t1, g1 = time.time(), be.gc_stats()
            out.write("pairs", {"pair": "p1", "arm": turn, "round": r, "warm": warm, "calls": len(ns), "passes": passes, "mean_ns": sum(ns) / len(ns),
                                "median_call_ns": L.median(ns), "rows": rows, "plan_ok": True, "t0": t0, "t1": t1, "call_ns": ns,
                                "pass_mean_ns": passes_of(ns, n), "gc_before": g0, "gc_after": g1})
            continue
        seq = L.abba(first, k)
        got = {"C": [], "Z": []}
        rows = {"C": 0, "Z": 0}
        pass_t0 = []
        g0, t0 = be.gc_stats(), time.time()
        for arm in seq:
            pass_t0.append(time.time())
            ns, nrows = L.timed_calls(lambda t: be.run("opencypher", t), cy)
            got[arm] += ns
            rows[arm] += nrows
        t1, g1 = time.time(), be.gc_stats()
        for arm in ("C", "Z"):
            ns = got[arm]
            out.write("pairs", {"pair": "p1", "arm": arm, "round": r, "warm": warm, "calls": len(ns), "passes": k, "mean_ns": sum(ns) / len(ns),
                                "median_call_ns": L.median(ns), "rows": rows[arm], "plan_ok": None, "t0": t0, "t1": t1, "call_ns": ns,
                                "pass_mean_ns": passes_of(ns, n), "block": "CZ", "block_first": first, "pass_seq": "".join(seq),
                                "pass_t0": pass_t0, "gc_before": g0, "gc_after": g1})
    return True


def time_pairs(out, be, c, starts, pairs_ok, token, model, refoff):
    be.set_rule(True)
    for r in range(L.ROUNDS_WARMUP + c["rounds"]):
        warm = r < L.ROUNDS_WARMUP
        for p in pairs_ok:
            if p == "p1":
                if not time_p1(out, be, c, starts, token, model, refoff, r, warm):
                    return False
                continue
            for arm in L.arm_order(r):
                texts = L.pair_texts(p, arm, starts, c["p23_calls"])
                lang = "opencypher" if arm in ("C", "Z") else "postgresql"
                plan_ok = None
                if arm in ("J", "O"):
                    plan_ok = arm_plan_ok(out, be, p, arm, r, texts[0], model, token, refoff)
                    if not plan_ok:
                        return False
                t0 = time.time()
                ns, rows = L.timed_calls(lambda t, lg=lang: be.run(lg, t), texts)
                out.write("pairs", {"pair": p, "arm": arm, "round": r, "warm": warm, "calls": len(ns), "passes": 1, "mean_ns": sum(ns) / len(ns),
                                    "median_call_ns": L.median(ns), "rows": rows, "plan_ok": plan_ok, "t0": t0, "t1": time.time(),
                                    "call_ns": ns})
        be.set_rule(True)
    return True


def time_tpch(out, be, c, queries, ok_q):
    for r in range(L.ROUNDS_WARMUP + c["tpch_reps"]):
        for q in ok_q:
            t0 = time.time()
            ns, rows = L.timed_calls(lambda t: be.run("postgresql", t), [queries[q]])
            out.write("tpch", {"q": q, "round": r, "warm": r < L.ROUNDS_WARMUP, "ns": ns[0], "rows": rows, "t0": t0, "t1": time.time()})


def run_main(out, c, dry, ref_dir):
    queries, starts = world(c, dry)
    rc = 0
    ref_dir = Path(ref_dir)
    if not (ref_dir / "done.json").exists():
        out.gate("reference answers present", False, f"{ref_dir}/done.json missing: run the ref phase first")
        return 4
    meta = json.loads((ref_dir / "done.json").read_text())
    ref = json.loads((ref_dir / "answers.json").read_text())
    ok = out.gate("reference was made with these caps and these statements", meta["caps"] == L.caps_of(c) and meta["digest"] == L.statements_digest(queries),
                  f"ref caps {meta['caps']} now {L.caps_of(c)}; digest equal {meta['digest'] == L.statements_digest(queries)}")
    if not ok:
        return 4
    if dry:
        import backend_stub as S
        be = S.StubBackend(S.Registry(starts, queries))
    else:
        import backend_arcade
        be = backend_arcade.ArcadeBackend(c, out.root, os.environ["PG13_ENGINE_JAR_IN_ARM"])
    out.write("env", {"engine_side": be.facts()})
    token = c["plan_token"]
    model = c["plan_model"]
    refoff = {}

    # ---- the social network graph: load, gates, answers, timing
    be.create("snb")
    if not dry:
        import arcadedb
        out.gate("the arcadedb package is the arm's copy", os.path.realpath(arcadedb.__file__).startswith(str(out.root.resolve() / "arm")), arcadedb.__file__)
        eok, info = be.engine_gates(out)
        if not eok:
            return 4
        # the jar was built from a source tree without .git, with the build number given by hand: the first 12 hex of the commit
        # (build/s2_build.sh), so the running engine says which commit it was built from, and the run stops when it is not the pinned one
        want = os.environ.get("PG13_ENGINE_COMMIT", "")[:12]
        if not out.gate("the engine reports the pinned commit as its build number", len(want) == 12 and info["build"] == want, f"engine says {info}, pinned {want}"):
            return 4
    t0 = time.perf_counter()
    snb = be.load_snb(c)
    out.write("env", {"load_snb": snb, "load_snb_s": time.perf_counter() - t0})
    if not dry:
        if not out.gate("SNB load stored every streamed row", snb["persons"] == snb["persons_streamed"] and snb["knows"] == snb["knows_streamed"], snb):
            return 4
        out.gate("edge endpoints declared in the edge type", snb["endpoints"] == ("Person", "Person"), snb["endpoints"])
        out.gate("the traversal rule is on by default", be.get_rule(), be.get_rule())
        nrow = be.table("postgresql", "SELECT count(*) AS n FROM person")
        if not out.gate("language `postgresql` is registered and finds `person`", nrow[1] == [[snb["persons"]]], nrow):
            return 4
    pairs_ok = []
    for p in L.PAIRS:
        stmts = [(k, jt, ct) for k, kind, jt, ct in statements(queries, starts) if kind == "pair" and k.split(":")[0] == p]
        fails = check_pair(out, be, ref, p, stmts)
        if fails:
            rc = max(rc, 6)
            out.gate(f"answers of pair {p} equal as multisets (join on, join off, Cypher, PostgreSQL 18)", False, f"{len(fails)} failures, first {fails[:2]}")
            continue
        out.gate(f"answers of pair {p} equal as multisets (join on, join off, Cypher, PostgreSQL 18)", True, f"{len(stmts)} statements")
        if plan_gate(out, be, p, stmts[0][1], token, model, refoff):
            pairs_ok.append(p)
        else:
            rc = max(rc, 4)
    if pairs_ok and not time_pairs(out, be, c, starts, pairs_ok, token, model, refoff):
        rc = max(rc, 4)

    # ---- TPC-H
    be.create("tpch")
    t0 = time.perf_counter()
    tp = be.load_tpch(c)
    out.write("env", {"load_tpch": tp, "load_tpch_s": time.perf_counter() - t0})
    ok_q = []
    for q in L.TPCH_QUERIES:
        try:
            got = be.table("postgresql", queries[q])
        except Exception as e:          # noqa: BLE001
            out.gate(f"TPC-H {q} runs unchanged", False, f"{type(e).__name__}: {str(e)[:600]}")
            rc = max(rc, 6)
            continue
        r = ref.get(q, {})
        same, detail = L.multiset_equal(got, (r["cols"], r["rows"])) if "rows" in r else (False, "no reference answer")
        out.gate(f"TPC-H {q} answers equal PostgreSQL 18 as a multiset", same, detail)
        if same:
            ok_q.append(q)
        else:
            rc = max(rc, 6)
    time_tpch(out, be, c, queries, ok_q)
    be.close()
    out.write("env", {"finished": time.strftime("%Y-%m-%dT%H:%M:%S%z")})
    return rc


def main(argv):
    if len(argv) != 3 or argv[1] not in ("ref", "main", "smoke"):
        print(__doc__)
        return 2
    phase, root = argv[1], Path(argv[2])
    c = L.cfg()
    L.check_cfg(c)
    dry = os.environ.get("PG13_DRYRUN") == "1"
    out = L.Out(root)
    env_record(out, phase, c)
    ref_dir = Path(os.environ.get("PG13_REF_DIR", str(root / "ref")))
    try:
        if phase == "ref":
            return run_ref(out, c, dry, root / "ref")
        rc = 0
        if phase == "smoke":
            rc = run_ref(out, c, dry, root / "ref")
            if rc:
                return rc
            os.environ["PG13_REF_DIR"] = str(root / "ref")
            ref_dir = root / "ref"
        return max(rc, run_main(out, c, dry, ref_dir))
    except SystemExit as e:
        out.gate("stopped by a check", False, e.code)
        return 4
    except Exception:                   # noqa: BLE001
        out.write("env", {"traceback": traceback.format_exc()})
        traceback.print_exc()
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
