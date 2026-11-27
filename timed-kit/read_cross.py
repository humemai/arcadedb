"""Reader of the two timed runs of version 3 side by side:  read_cross.py <base pg13-main dir> <head pg13-main dir>   (uv run python)

Reads the verdict.json that read_results.py wrote in each run folder, and prints for every pair that both runs timed the medians of the four
arms in both studies, each study's J/C and verdict (the registered rule, unchanged), and the ratio head / base of each arm. The head / base
ratio of the Cypher arm C is the control row between the two studies: the Cypher code is the same in both engines, so a C ratio outside
0.90 to 1.10 (the control band of pg13lib) is listed as a difference of the host or the run, and the pair gets no statement of effect.
Everything printed is computed from the two files; nothing is typed. No threshold is added: the verdict of a pair stays the registered rule
applied to each study alone, and this table only puts the two side by side. Exit status 0.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pg13lib as L


def load(d):
    return json.loads((Path(d) / "verdict.json").read_text(encoding="utf-8"))


def main(argv):
    base, head = load(argv[1]), load(argv[2])
    lines = ["the two timed runs of pg13 version 3, side by side (medians of the mean call time over rounds 1 to 5, in ms; head / base is the ratio of the same arm in the two studies)",
             f"base {argv[1]}", f"head {argv[2]}"]
    for p in L.PAIRS:
        b, h = base["pairs"].get(p, {}), head["pairs"].get(p, {})
        lines.append("")
        lines.append(f"{p}  {L.PAIR_TITLES[p]}")
        if "median_ms" not in b or "median_ms" not in h:
            lines.append(f"    base: {b.get('verdict')}")
            lines.append(f"    head: {h.get('verdict')}")
            lines.append("    not compared: a pair has to be timed in both studies")
            continue
        lines.append("    arm                     base ms      head ms   head / base")
        for a in L.ARMS:
            x, y = b["median_ms"][a], h["median_ms"][a]
            lines.append(f"    {a} {L.ARM_NAMES[a]:<20} {x:>10,.3f}  {y:>10,.3f}   {y / x:>8.3f}")
        lines.append(f"    J/C   base {b['J/C']:.3f}  head {h['J/C']:.3f}      O/J   base {b['O/J']:.3f}  head {h['O/J']:.3f}")
        lines.append(f"    VERDICT base: {b['verdict']}")
        lines.append(f"    VERDICT head: {h['verdict']}")
        c = h["median_ms"]["C"] / b["median_ms"]["C"]
        if not (L.CONTROL_LO <= c <= L.CONTROL_HI):
            lines.append(f"    CONTROL ROW: C head / base = {c:.3f} is outside {L.CONTROL_LO:.2f} to {L.CONTROL_HI:.2f}: the two studies differ in something other than the engine's join planning; no statement of effect for {p}")
    text = "\n".join(lines)
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
