"""Compare what PostgreSQL 18 and the postgresql language answered, statement by statement.

usage: analyze.py RESULTS_DIR GROUP [GROUP ...] [--all] [--json OUT]

Prints one block for each statement whose answer differs (every statement with --all).
"""

import json
import pathlib
import re
import sys
from decimal import Decimal, InvalidOperation

NUMBER_TYPES = {"int2", "int4", "int8", "serial", "bigserial", "float4", "float8", "numeric"}
BEN = "22222222-2222-2222-2222-222222222222"


def load(directory, group):
    path = pathlib.Path(directory) / f"{group}.jsonl"
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def value_key(value, left_type, right_type):
    if value is None:
        return ("null",)
    if left_type in ("json", "jsonb") or right_type in ("json", "jsonb"):
        try:
            return ("json", json.dumps(json.loads(value, parse_float=Decimal, parse_int=Decimal), sort_keys=True, default=lambda d: str(d.normalize())))
        except (ValueError, TypeError):
            return ("text", value)
    if left_type in NUMBER_TYPES or right_type in NUMBER_TYPES:
        try:
            number = Decimal(value)
            if number.is_nan():
                return ("number", "nan")
            return ("number", str(number.normalize()))
        except InvalidOperation:
            return ("text", value)
    return ("text", value)


def compare(item, pg, arc, uuid_map):
    """Return (same, [notes]) for two successful outcomes."""
    notes = []
    volatile = "volatile" in item["tags"].split()
    has_pg, has_arc = "columns" in pg, "columns" in arc
    if has_pg != has_arc:
        return False, ["one side returns rows and the other a count"]
    if not has_pg:
        if pg.get("count") != arc.get("count"):
            return False, [f"count {pg.get('count')} against {arc.get('count')}"]
        return True, notes
    if pg["columns"] != arc["columns"]:
        notes.append(f"column names {pg['columns']} against {arc['columns']}")
    if pg["types"] != arc["types"]:
        notes.append(f"types {pg['types']} against {arc['types']}")
    if volatile:
        same = pg["columns"] == arc["columns"] and pg["count"] == arc["count"]
        if pg["count"] != arc["count"]:
            notes.append(f"row count {pg['count']} against {arc['count']}")
        return same, notes
    if pg["count"] != arc["count"]:
        notes.append(f"row count {pg['count']} against {arc['count']}")
        return False, notes
    width = len(pg["columns"])
    if width != len(arc["columns"]):
        return False, notes

    def keys(rows, mapping):
        result = []
        for row in rows:
            result.append(tuple(value_key(mapping.get(v, v), pg["types"][i], arc["types"][i]) for i, v in enumerate(row)))
        return result

    left, right = keys(pg["rows"], uuid_map), keys(arc["rows"], {})
    if "order by" not in item["sql"].lower():
        left, right = sorted(left, key=repr), sorted(right, key=repr)
    if left != right:
        notes.append("values differ")
        return False, notes
    return pg["columns"] == arc["columns"], notes


def verdict(item, uuid_map):
    pg, arc = item["pg"], item["arc"]
    if pg["ok"] and arc["ok"]:
        same, notes = compare(item, pg, arc, uuid_map)
        return ("same" if same else "different answer"), notes
    if pg["ok"] and not arc["ok"]:
        return "refused " + str(arc.get("state")), []
    if not pg["ok"] and arc["ok"]:
        return "succeeds where PostgreSQL refuses", []
    if pg.get("state") == arc.get("state"):
        return "same", [f"both {pg.get('state')}"]
    return "other error code", [f"PostgreSQL {pg.get('state')} against {arc.get('state')}"]


def show(outcome, limit=6):
    if outcome["ok"]:
        if "columns" in outcome:
            rows = outcome["rows"][:limit]
            return f"ok {outcome['columns']} {outcome['types']} n={outcome['count']} {json.dumps(rows, ensure_ascii=False)[:420]}" + (
                f" warnings={outcome['warnings']}" if outcome.get("warnings") else "")
        return f"ok count={outcome.get('count')}" + (f" warnings={outcome['warnings']}" if outcome.get("warnings") else "")
    text = outcome.get("text") or outcome.get("message")
    extra = ""
    for key in ("detail", "hint"):
        if outcome.get(key):
            extra += f" | {key}: {outcome[key]}"
    return f"ERROR {outcome.get('state')} {str(text)[:330]}{extra[:300]}" + (f" | position {outcome['position']}" if outcome.get("position") else "")


def main():
    args = sys.argv[1:]
    every = "--all" in args
    if every:
        args.remove("--all")
    out = None
    if "--json" in args:
        index = args.index("--json")
        out = args[index + 1]
        del args[index:index + 2]
    directory, groups = args[0], args[1:]
    summary = []
    for group in groups:
        items = load(directory, group)
        uuid_map = {}
        for item in items:
            if item["id"] == "W24" and item["pg"]["ok"]:
                uuid_map[item["pg"]["rows"][0][0]] = BEN
        counts = {}
        print(f"########## {group}")
        for item in items:
            name, notes = verdict(item, uuid_map)
            counts[name] = counts.get(name, 0) + 1
            summary.append({"group": group, "id": item["id"], "tags": item["tags"], "verdict": name, "notes": notes})
            if name == "same" and not every:
                continue
            sql = re.sub(r"\s+", " ", item["sql"])
            print(f"== {item['id']} [{name}] {sql[:230]}")
            for note in notes:
                print(f"   note: {note[:300]}")
            print(f"   pg : {show(item['pg'])}")
            print(f"   arc: {show(item['arc'])}")
            for fallback in item.get("fallbacks", []):
                print(f"   fallback ({fallback['language']}): {show(fallback['out'], 3)}")
            for after in item.get("after", []):
                if not after["out"]["ok"]:
                    print(f"   after failed: {show(after['out'])}")
        print(f"---------- {group}: {sum(counts.values())} statements, {counts}")
    if out:
        pathlib.Path(out).write_text(json.dumps(summary, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
