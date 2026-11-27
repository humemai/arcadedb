"""Read the before/after run by the rules posted on fork issue humemai/arcadedb#13."""
import json
import statistics
import sys
from pathlib import Path

run = Path(sys.argv[1])
per = []
for d in sorted(run.glob("jvm*-*")):
    engine = d.name.split("-", 1)[1]
    recs = [json.loads(x) for x in (d / "raw" / "timing.jsonl").read_text().splitlines()]
    us = {a: statistics.median(r["mean_ns"] for r in recs if r["arm"] == a and not r["warm"]) / 1000 for a in "JCZD"}
    row = {"jvm": d.name, "engine": engine, **us, "J/C": us["J"] / us["C"], "Z/C": us["Z"] / us["C"]}
    row["valid"] = 0.97 <= row["Z/C"] <= 1.03
    per.append(row)
print("jvm            J us     C us     Z us     D us    J/C    Z/C  valid")
for p in per:
    print(f"{p['jvm']:12} {p['J']:7.2f}  {p['C']:7.2f}  {p['Z']:7.2f}  {p['D']:7.2f}  {p['J/C']:.3f}  {p['Z/C']:.3f}  {p['valid']}")
v = {e: [p for p in per if p["engine"] == e and p["valid"]] for e in ("before", "after")}
out = {"per_jvm": per, "verdict": "not readable"}
if all(len(x) >= 3 for x in v.values()):
    m = {e: {k: statistics.median(p[k] for p in x) for k in ("J", "C", "D", "J/C")} for e, x in v.items()}
    ratio = m["after"]["J/C"] / m["before"]["J/C"]
    apart = max(p["J/C"] for p in v["after"]) < min(p["J/C"] for p in v["before"])
    out.update({"medians": m, "after_over_before_J/C": ratio, "no_overlap": apart, "verdict": "helps" if ratio <= 0.95 and apart else "no measured effect"})
    for e in ("before", "after"):
        print(f"{e:7} median of {len(v[e])} JVMs: J {m[e]['J']:.2f} us, C {m[e]['C']:.2f} us, D {m[e]['D']:.2f} us, J/C {m[e]['J/C']:.3f}")
    print(f"after/before of J/C {ratio:.3f}, every after below every before: {apart}")
print("verdict:", out["verdict"])
(run / "verdict.json").write_text(json.dumps(out, indent=1))
