"""Dump the pg14 kit's own data and parameter picks (persons with `created`, KNOWS edges, the t1/t2/t4/t5 parameter lists) to tab files,
so that the Java harness loads exactly what the kit loads. Run from anywhere: python dump_data.py <kit dir> <out dir>."""
import json
import os
import sys

kit, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, kit)
os.makedirs(out, exist_ok=True)
import pg13lib as L  # noqa: E402
import pg14lib as P  # noqa: E402

c = P.cfg()
w = P.World(c)
with open(os.path.join(out, "persons.tsv"), "w", encoding="utf-8") as f:
    for p in w.persons:
        f.write(f"{p['id']}\t{p['name']}\t{p['age']}\t{p['city']}\t{p['created']}\n")
with open(os.path.join(out, "edges.tsv"), "w", encoding="utf-8") as f:
    for s, d, y in L.gen_edges(c["snb_scale"], c["person_limit"]):
        f.write(f"{s}\t{d}\t{y}\n")
params = {"t1": [str(x) for x in P.read_params(w, "t1")], "t2": P.read_params(w, "t2"), "t4": P.read_params(w, "t4"),
          "t5": [str(x) for x in P.read_params(w, "t5")]}
with open(os.path.join(out, "params.json"), "w", encoding="utf-8") as f:
    json.dump(params, f)
# the statements of the kit, so the harness runs the kit's own text: language, text of every arm for the first parameter of each task
stm = {}
for t in P.READ_TASKS:
    for a in ("P", "S"):
        lg, tx = P.read_text(t, a, P.read_params(w, t)[0])
        stm[f"{t}.{a}"] = [lg, tx, P.read_params(w, t)[0] if P.read_params(w, t)[0] is not None else ""]
with open(os.path.join(out, "statements.json"), "w", encoding="utf-8") as f:
    json.dump(stm, f, indent=1)
print(len(w.persons), "persons", {k: len(v) for k, v in params.items()}, "scale", c["snb_scale"], "limit", c["person_limit"])
