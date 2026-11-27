"""Merge the blind corpus, the Arcade-side directives, and the extra groups into one resource file.

usage: merge_corpus.py BLIND FALLBACKS EXTRA OUT
"""

import re
import sys


def blocks(path):
    """Return a list of (group, header line, body text) for each statement of a corpus file."""
    out = []
    group = None
    current = None
    for line in open(path, encoding="utf-8").read().split("\n"):
        if line.startswith("--# "):
            current = None
            group = line
        elif line.startswith("--@ "):
            current = [group, line, []]
            out.append(current)
        elif current is not None:
            current[2].append(line)
    return [(g, h, "\n".join(b).strip()) for g, h, b in out]


def main():
    blind, fallbacks, extra, target = sys.argv[1:5]
    directives = {}
    for _, header, body in blocks(fallbacks):
        key = header.split()[1]
        assert key not in directives, key
        directives[key] = body
    original = blocks(blind)
    ids = [h.split()[1] for _, h, _ in original]
    assert len(ids) == len(set(ids)), "duplicate id"
    assert set(directives) <= set(ids), set(directives) - set(ids)
    lines = []
    seen = None
    for group, header, body in original:
        assert "--!" not in body
        if group != seen:
            lines.append(group.replace("--# scenario ", "--# group "))
            seen = group
        lines.append(header)
        lines.append(body)
        extra_lines = directives.get(header.split()[1])
        if extra_lines:
            lines.append(extra_lines)
    lines.append(open(extra, encoding="utf-8").read().strip())
    text = "\n".join(lines) + "\n"
    open(target, "w", encoding="utf-8").write(text)

    merged = {h.split()[1]: b.split("\n--!")[0].strip() for _, h, b in blocks(target)}
    for _, header, body in original:
        assert merged[header.split()[1]] == body, header
    all_ids = [h.split()[1] for _, h, _ in blocks(target)]
    assert len(all_ids) == len(set(all_ids)), "duplicate id after merge"
    counts = {}
    for key in ids:
        counts[re.match(r"[A-Z]+", key).group()] = counts.get(re.match(r"[A-Z]+", key).group(), 0) + 1
    print("blind statements:", len(ids), counts)
    print("all statements:", len(all_ids))


main()
