"""Aggregate `jfr print --events jdk.ExecutionSample --stack-depth 64` (or jdk.ObjectAllocationSample) text: top frames by self and inclusive.
usage: python analyze.py <file.txt> [--alloc] [--top N] [--keys m1,m2,...]   (keys: inclusive share of named method substrings)"""
import re
import sys
from collections import Counter

path = sys.argv[1]
alloc = "--alloc" in sys.argv
top = int(sys.argv[sys.argv.index("--top") + 1]) if "--top" in sys.argv else 25
keys = sys.argv[sys.argv.index("--keys") + 1].split(",") if "--keys" in sys.argv else []

text = open(path, encoding="utf-8").read()
events = re.split(r"\n(?=jdk\.\w+ \{)", text)
frame_re = re.compile(r"^\s+([\w$.<>\[\]/]+)\(.*?\)\s+line:\s*(\d+)", re.M)
weight_re = re.compile(r"weight = ([\d.]+) (\w+)")
cls_re = re.compile(r"objectClass = ([\w$.\[\]/]+)")
unit = {"bytes": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}
n = 0
total_w = 0.0
self_c, incl_c, cls_c, key_c = Counter(), Counter(), Counter(), Counter()
for ev in events:
    if "stackTrace" not in ev:
        continue
    if "sampledThread = \"main\"" not in ev and not alloc and "eventThread = \"main\"" not in ev:
        if "main" not in ev.split("sampledThread =")[-1][:30]:
            continue
    frames = [m.group(1) for m in frame_re.finditer(ev.split("stackTrace = [", 1)[1])]
    if not frames:
        continue
    w = 1.0
    if alloc:
        m = weight_re.search(ev)
        w = float(m.group(1)) * unit.get(m.group(2), 1) if m else 0.0
        c = cls_re.search(ev)
        if c:
            cls_c[c.group(1)] += w
    n += 1
    total_w += w
    self_c[frames[0]] += w
    for f in set(frames):
        incl_c[f] += w
    for k in keys:
        if any(k in f for f in frames):
            key_c[k] += w
what = "bytes" if alloc else "samples"
print(f"{path}: {n} events, total {total_w:,.0f} {what}")
sh = lambda v: f"{100 * v / total_w:5.1f}%"
print("-- top self")
for f, v in self_c.most_common(top):
    print(f"{sh(v)} {v:>12,.0f} {f}")
print("-- top inclusive (com.arcadedb, org.antlr, java.util.stream/regex only)")
shown = 0
for f, v in incl_c.most_common(400):
    if f.startswith(("com.arcadedb", "org.antlr")) and shown < top + 15:
        print(f"{sh(v)} {v:>12,.0f} {f}")
        shown += 1
if keys:
    print("-- key methods, inclusive")
    for k in keys:
        print(f"{sh(key_c[k])} {key_c[k]:>12,.0f} {k}")
if alloc:
    print("-- top allocated classes")
    for f, v in cls_c.most_common(12):
        print(f"{sh(v)} {v:>12,.0f} {f}")
