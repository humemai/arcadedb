"""Reader of a pg13 run:  read_results.py <out-dir> [<ref-out-dir>]   (uv run python)

Prints the three result tables and the verdicts by the decision rule of the pre-registration, and writes <out-dir>/verdict.json.
Everything printed is computed from raw/*.jsonl; nothing is typed. Exit status: 0 read, 3 an A/A control left its band (that pair is
not readable), 4 a gate failed (an arm or the engine was not what it should be), 6 an answer differed or a statement failed.

THE DECISION RULE (written before the first run; fork issue #1 question 25 and issue #13):
  for each pair p, with M_a the median over the measured rounds of the mean call time of arm a:
    A/A     Z/C within 1 -+ 0.03, else the pair is NOT READABLE (no verdict, the number is still printed)
    ratio   R = M_J / M_C
    R <= 1.3          IN THE RANGE            (the claim the issue wants)
    1.3 < R <= 2.0    REPORTED, NOT CLAIMED
    R > 2.0           OUTSIDE THE RANGE       (reported with its plan, never dropped)
  A pair is read only when: its four arms have every measured round, every plan gate held, the arms returned the same number of rows
  in every round, the A/A control is clean, and its answers were equal as multisets before the timing. No pooled ratio of the three pairs.
  The control arm O (rule off) and the A/A arm Z are reported, with no threshold but the A/A band.

Version 3 (pair 1 only): an arm of p1 has more calls in a turn (the passes of the arms are in the raw file); the figure of an arm is still the mean call time
of its turn, the rows of an arm are compared per pass, and the table prints the calls of one turn and the Z/C of each round (a diagnostic).

Version 2 prints, besides, the traversal model and engine pins of the study (what the plan gates asserted) and the plans of each pair
with the rule on and off, as the engine printed them, so that the post can show them.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pg13lib as L


def ms(ns):
    return f"{ns / 1e6:,.3f}"


def per_arm(rows):
    """{arm: {round: mean_ns}} of the measured rounds, and the row counts per (arm, round)."""
    d, n, plan_bad = {}, {}, False
    for r in rows:
        if r["warm"]:
            continue
        d.setdefault(r["arm"], {})[r["round"]] = r["mean_ns"]
        n.setdefault(r["round"], set()).add((r["arm"], r["rows"] / r.get("passes", 1)))     # rows of one pass: version 3 times J, C and Z of p1 over many passes
        if r.get("plan_ok") is False:
            plan_bad = True
    return d, n, plan_bad


def read_pairs(out, env_phase, rounds_expected, gates_ok_by_pair):
    pairs = L.read_jsonl(Path(out) / "raw" / "pairs.jsonl")
    res, lines, aa_bad = {}, [], False
    for p in L.PAIRS:
        rows = [r for r in pairs if r["pair"] == p]
        d, rowsets, plan_bad = per_arm(rows)
        if not rows:
            res[p] = {"verdict": "NOT TIMED (its answers or its plan did not pass before the timing)"}
            lines.append(f"{p}  {L.PAIR_TITLES[p]}: {res[p]['verdict']}")
            continue
        med = {a: L.median(list(d[a].values())) for a in d if d[a]}
        full = all(a in d and len(d[a]) == rounds_expected for a in L.ARMS)
        rows_same = all(len({cnt for _, cnt in s}) == 1 for s in rowsets.values())
        r = {"median_ms": {a: med[a] / 1e6 for a in med}, "rounds": {a: len(d.get(a, {})) for a in L.ARMS}, "rows_same_in_every_round": rows_same}
        if full:
            r["J/C"], r["Z/C"], r["O/J"], r["O/C"] = med["J"] / med["C"], med["Z"] / med["C"], med["O"] / med["J"], med["O"] / med["C"]
            r["J/C per round"] = [d["J"][k] / d["C"][k] for k in sorted(d["J"])]
            r["Z/C per round"] = [d["Z"][k] / d["C"][k] for k in sorted(d["Z"])]       # a diagnostic, no rule reads it
            r["calls per turn"] = {a: next(x["calls"] for x in rows if x["arm"] == a and not x["warm"]) for a in L.ARMS}
        why = []
        if not full:
            why.append(f"missing rounds {r['rounds']}")
        if plan_bad:
            why.append("a plan gate failed")
        if not rows_same:
            why.append("the arms returned different row counts in a round")
        if not gates_ok_by_pair.get(p, True):
            why.append("its answers were not equal before the timing")
        aa_ok = full and abs(r["Z/C"] - 1) <= L.AA_BAND
        if full and not aa_ok:
            aa_bad = True
            why.append(f"A/A control Z/C = {r['Z/C']:.3f} is outside 1 -+ {L.AA_BAND}")
        if env_phase != "main" or rounds_expected != L.ROUNDS_MEASURED:
            r["verdict"] = "REHEARSAL (not n=5 on the main label): no verdict"
        elif why:
            r["verdict"] = "NOT READABLE: " + "; ".join(why)
        else:
            R = r["J/C"]
            r["verdict"] = ("IN THE RANGE" if R <= L.RATIO_IN_RANGE else
                            "REPORTED, NOT CLAIMED" if R <= L.RATIO_REPORTED_MAX else "OUTSIDE THE RANGE (report with its plan)")
        res[p] = r
        lines.append(f"{p}  {L.PAIR_TITLES[p]}")
        lines.append("    arm                    median ms    rounds  " + "  ".join(f"r{k}" for k in range(1, rounds_expected + 1)))
        for a in L.ARMS:
            if a in d:
                lines.append(f"    {a} {L.ARM_NAMES[a]:<20} {ms(med[a]):>10}   {len(d[a]):>5}  " + "  ".join(ms(d[a][k]) for k in sorted(d[a])))
        if full:
            lines.append(f"    J/C {r['J/C']:.3f}   Z/C (A/A) {r['Z/C']:.3f}   O/J (what the rule is worth) {r['O/J']:.3f}   O/C {r['O/C']:.3f}")
            lines.append("    J/C per round: " + "  ".join(f"{x:.3f}" for x in r["J/C per round"]))
            lines.append("    Z/C per round: " + "  ".join(f"{x:.3f}" for x in r["Z/C per round"]) + "   (a diagnostic; the rule reads the medians)")
            lines.append("    calls in one turn of each arm: " + "  ".join(f"{a} {r['calls per turn'][a]}" for a in L.ARMS))
        lines.append(f"    VERDICT {p}: {r['verdict']}")
    return res, lines, aa_bad


def read_tpch(out, ref_out, rounds_expected, gates):
    rows = L.read_jsonl(Path(out) / "raw" / "tpch.jsonl")
    ref = L.read_jsonl(Path(ref_out) / "raw" / "ref_timing.jsonl") if ref_out else []
    lines = ["query  answer vs PostgreSQL 18   prototype median ms (min .. max)   PostgreSQL 18 median ms   prototype / PostgreSQL 18 (a reference point)"]
    res = {}
    for q in L.TPCH_QUERIES:
        g = [x for x in gates if x["name"].startswith(f"TPC-H {q} ")]
        status = "equal" if g and all(x["ok"] for x in g) else ("DIFFERENT or failed: " + "; ".join(x["detail"][:120] for x in g if not x["ok"]) if g else "not checked")
        t = [r["ns"] for r in rows if r["q"] == q and not r["warm"]]
        rt = [r["mean_ns"] for r in ref if r["id"] == q and not r["warm"]]
        a = L.median(t) if t else None
        b = L.median(rt) if rt else None
        res[q] = {"answer": status, "median_ms": a and a / 1e6, "reference_ms": b and b / 1e6, "n": len(t)}
        lines.append(f"{q:<5}  {status:<24}  " + (f"{ms(a)} ({ms(min(t))} .. {ms(max(t))}) n={len(t)}" if t else "not timed") +
                     f"   {ms(b) if b else '-'}   " + (f"{a / b:.2f}" if a and b else "-"))
    return res, lines


def main(argv):
    out = Path(argv[1])
    ref_out = argv[2] if len(argv) > 2 else None
    env = L.read_jsonl(out / "raw" / "env.jsonl")
    gates = L.read_jsonl(out / "raw" / "gates.jsonl")
    phase = next((e["phase"] for e in env if "phase" in e), "?")
    caps = next((e["caps"] for e in env if "caps" in e), {})
    rounds = next((e["rounds_measured"] for e in env if "rounds_measured" in e), 0)
    lane = next((e["lane"] for e in env if "lane" in e), {})
    pins = next((e for e in env if "traversal_model" in e), {})
    lines = [f"pg13 run {out.name}  phase {phase}  rounds measured {rounds}  caps {caps}", f"lane {lane}",
             f"study role {pins.get('study_role')}  traversal model {pins.get('traversal_model')}  engine commit {pins.get('engine_commit')}  jar sha256 {pins.get('engine_jar_sha256')}"]
    lines += [f"engine {e['engine_constants']}" for e in env if "engine_constants" in e]
    lines += [f"{k} {e[k]}" for e in env for k in ("load_snb", "load_tpch", "load_snb_s", "load_tpch_s") if k in e]
    bad = [g for g in gates if not g["ok"]]
    answer_bad = [g for g in bad if "equal" in g["name"] or "runs unchanged" in g["name"] or "reference answers" in g["name"]]
    other_bad = [g for g in bad if g not in answer_bad]
    lines.append(f"GATES {len(gates)}: {len(gates) - len(bad)} ok, {len(answer_bad)} answer failures, {len(other_bad)} other failures")
    lines += [f"  FAIL {g['name']}: {g['detail'][:300]}" for g in bad]
    pair_ok = {p: not any(f"pair {p} " in g["name"] and not g["ok"] for g in gates) for p in L.PAIRS}
    lines.append("")
    lines.append("RESULT 3: a join against the traversal (ratio of medians of the mean call time over the measured rounds; arms interleaved)")
    pres, plines, aa_bad = read_pairs(out, phase, rounds, pair_ok)
    lines += plines
    lines.append("")
    lines.append("PLANS of the first statement of each pair, as the engine printed them (prettyPrint of the execution plan)")
    for pl in L.read_jsonl(Path(out) / "raw" / "plans.jsonl"):
        lines.append(f"  {pl['pair']} rule ON  (model {pl.get('model')}):")
        lines += ["    " + ln for ln in pl["rule_on"].splitlines()]
        lines.append(f"  {pl['pair']} rule OFF:")
        lines += ["    " + ln for ln in pl["rule_off"].splitlines()]
    lines.append("")
    lines.append("RESULTS 1 and 2: the four TPC-H statements unchanged, and the parity of their answers")
    tres, tlines = read_tpch(out, ref_out or (out if (out / "raw" / "ref_timing.jsonl").exists() else None), rounds, gates)
    lines += tlines
    lines.append("")
    lines.append("every comparison outside 0.90 .. 1.10, whatever its verdict (the control-rows rule); O/J and O/C show the rule's own effect")
    for p, r in pres.items():
        for k in ("J/C", "Z/C", "O/J", "O/C"):
            if k in r and not (L.CONTROL_LO <= r[k] <= L.CONTROL_HI):
                lines.append(f"  {p} {k} = {r[k]:.3f}")
    rc = 4 if other_bad else 6 if answer_bad else 3 if aa_bad else 0
    lines.append(f"READER STATUS {rc} (0 read, 3 an A/A control out of band, 4 a gate failed, 6 an answer differed)")
    text = "\n".join(lines)
    print(text)
    (out / "analysis.txt").write_text(text + "\n", encoding="utf-8")
    (out / "verdict.json").write_text(json.dumps({"pairs": pres, "tpch": tres, "status": rc, "phase": phase}, sort_keys=True, indent=1), encoding="utf-8")
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
