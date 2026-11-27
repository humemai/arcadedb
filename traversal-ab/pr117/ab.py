"""One JVM of the before/after run of pair 1 (fork issue humemai/arcadedb#13, PR #117) on the development machine: J the join, C openCypher,
Z openCypher again (A/A), D the join planned and not run. Relative figures only."""
from __future__ import annotations

import os
import sys
import traceback
from pathlib import Path

import backend_arcade
import pg13lib as L

ROUNDS, PASSES, WARM_BLOCKS = 6, 200, 3


def main(out_dir):
    O = Path(out_dir).resolve()
    out = L.Out(O)
    c = L.cfg()
    be = backend_arcade.ArcadeBackend(c, O, os.environ["KIT_ENGINE_JAR_IN_ARM"])
    be.create("snb")
    ok, info = be.engine_gates(out)
    out.write("env", {"engine": info})
    if not ok:
        return 4
    snb = be.load_snb(c)
    if not out.gate("SNB load stored every streamed row", snb["persons"] == snb["persons_streamed"] and snb["knows"] == snb["knows_streamed"], snb):
        return 4
    starts = L.pick_starts(c["snb_scale"], c["person_limit"], c["p1_starts"])
    be.set_rule(True)
    join = [L.JOIN_P1.format(id=s) for s in starts]
    cypher = [L.CYPHER_P1.format(id=s) for s in starts]

    def rows(lang, text):
        return sorted((int(d["qid"]), str(d["name"]), int(d["since"])) for d in be.db.query(lang, text).to_list())

    diff = [s for s, a, b in zip(starts, join, cypher) if rows("postgresql", a) != rows("opencypher", b)]
    if not out.gate("J and C give the same rows from every start", not diff, f"{len(diff)} starts differ"):
        return 4
    plan = be.explain(join[0])
    if not out.gate("the plan of J walks the edges", "TRAVERSE" in plan, plan):
        return 4
    j = be.jpype
    engine = j.JObject(be.db.get_java_database().getQueryEngine("postgresql"), j.JClass("com.arcadedb.query.postgresql.PostgreSQLQueryEngine"))
    arms = {"J": (lambda t: be.run("postgresql", t), join), "C": (lambda t: be.run("opencypher", t), cypher),
            "Z": (lambda t: be.run("opencypher", t), cypher), "D": (lambda t: engine.describe(t).size(), join)}
    for _ in range(WARM_BLOCKS):
        for fn, texts in arms.values():
            L.timed_calls(fn, texts * 50)
    names = list(arms)
    for r in range(ROUNDS):
        order = names[r % len(names):] + names[:r % len(names)]
        if r % 2:
            order = order[::-1]
        for name in order:
            fn, texts = arms[name]
            ns, _ = L.timed_calls(fn, texts * PASSES)
            out.write("timing", {"round": r, "warm": r == 0, "arm": name, "calls": len(ns), "mean_ns": sum(ns) / len(ns)})

    # untimed, after the last round: the plan lines of both read paths, which read.py compares across the engines
    def explained(text):
        return [str(v) for d in be.db.query("postgresql", "EXPLAIN " + text).to_list() for v in d.values()]

    for s, text in list(zip(starts, join))[:3]:
        out.write("plans", {"statement": f"p1 start {s}", "result_plan": be.explain(text), "explain": explained(text)})
    for label, text in (("p2", L.JOIN_P2), ("nested loop", "SELECT p.id FROM person p JOIN person q ON p.age < q.age")):
        out.write("plans", {"statement": label, "explain": explained(text)})
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1]))
    except SystemExit:
        raise
    except BaseException:
        traceback.print_exc()
        sys.exit(1)
