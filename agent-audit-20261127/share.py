"""Count the 267 blind statements by outcome, with one outcome for each statement.

usage: share.py RESULTS_DIR OUT_JSON

Adapted run: memory.jsonl and web.jsonl. As-written run: memory-strict.jsonl and web-strict.jsonl.
"""

import json
import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import analyze  # noqa: E402

# Statements whose difference comes from a statement that had to be replaced or was refused earlier (judged by reading each).
KNOCK_ON_DIFFERENT = {"C01", "C02", "C03", "C04", "C05", "C10", "C13", "C15", "C23", "C24", "C27", "C32"}
KNOCK_ON_SUCCESS = {"M76", "E17", "E34"}
# Float answers that differ only after the sixth digit (vector distances).
ROUNDING = {"M34", "M35"}
# Marked "volatile" before the first run, but their values differ for another reason than the clock (version and schema name).
OWN_VALUE = {"C20", "C21", "W88"}


def rounding_only(item):
    pg, arc = item["pg"], item["arc"]
    if pg["columns"] != arc["columns"] or pg["count"] != arc["count"]:
        return False
    for left, right in zip(pg["rows"], arc["rows"]):
        for a, b in zip(left, right):
            if a == b:
                continue
            if not math.isclose(float(a), float(b), rel_tol=1e-5, abs_tol=1e-9):
                return False
    return True


def category(item, uuid_map):
    key = item["id"]
    name, _ = analyze.verdict(item, uuid_map)
    if key in OWN_VALUE:
        assert name == "same" and item["pg"]["rows"] != item["arc"]["rows"], key
        return "answers another value"
    if name == "same":
        return "same"
    arc = item["arc"]
    text = str(arc.get("text") or arc.get("message") or "")
    if name == "different answer":
        if key in ROUNDING:
            assert rounding_only(item), key
            return "same"
        assert key in KNOCK_ON_DIFFERENT, ("unjudged different answer", key)
        return "knock-on"
    if name == "succeeds where PostgreSQL refuses":
        assert key in KNOCK_ON_SUCCESS, ("unjudged success", key)
        return "knock-on"
    state = arc.get("state")
    if state == "23502" and 'column "id"' in text:
        return "knock-on"
    if state == "42P01" and "active_users" in text:
        return "knock-on"
    if state == "25P02":
        return "knock-on"
    if state == "42703" and "CREATE PROPERTY" in text:
        return "confusing"
    if state == "42803" or (state == "42P01" and "pg_attribute" in text) or "A prefix operator" in text:
        return "confusing"
    if state == "42601" and key == "E04":
        return "confusing"
    if "use the sql language for it" in text or "This form of a timestamp" in text or "transaction isolation level" in text:
        return "refused, names a way out"
    if state == "0A000":
        return "refused, no way out"
    raise AssertionError(("unjudged", key, state, text[:200]))


def area(item):
    key = item["id"]
    tags = item["tags"].split()[0]
    if key.startswith("E"):
        return "usual mistakes"
    if key.startswith("C"):
        return "schema discovery"
    if key.startswith("T"):
        return "transactions and session"
    if tags == "schema" or (item["sql"].split()[0].upper() in ("CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT")):
        return "schema statements"
    words = item["sql"].split()[0].upper()
    if words in ("INSERT", "UPDATE", "DELETE") or item["sql"].upper().startswith("WITH MOVED"):
        return "writes"
    return "queries"


def main():
    directory, out = sys.argv[1], sys.argv[2]
    result = {}
    for label, groups in (("adapted", ("memory", "web")), ("as written", ("memory-strict", "web-strict"))):
        counts, areas, ids = {}, {}, {}
        for group in groups:
            items = analyze.load(directory, group)
            uuid_map = {i["pg"]["rows"][0][0]: analyze.BEN for i in items if i["id"] == "W24" and i["pg"]["ok"]}
            for item in items:
                if label == "adapted":
                    kind = category(item, uuid_map)
                else:
                    name, _ = analyze.verdict(item, uuid_map)
                    kind = "same" if (name == "same" and item["id"] not in OWN_VALUE) or (item["id"] in ROUNDING and name != "refused 42P01" and item["arc"]["ok"]
                                                        and rounding_only(item)) else "not the same"
                counts[kind] = counts.get(kind, 0) + 1
                row = areas.setdefault(area(item), {"statements": 0, "same": 0})
                row["statements"] += 1
                row["same"] += kind == "same"
                if kind == "same" and "volatile" in item["tags"].split():
                    ids.setdefault("equal by shape only", []).append(item["id"])
                ids.setdefault(kind, []).append(item["id"])
        total = sum(counts.values())
        assert total == 267, total
        result[label] = {"total": total, "counts": counts, "areas": areas, "ids": ids}
    pathlib.Path(out).write_text(json.dumps(result, indent=1), encoding="utf-8")
    for label, data in result.items():
        print(label, data["total"], data["counts"], f"same {100 * data['counts']['same'] / data['total']:.1f}%")
        for name, row in data["areas"].items():
            print(f"   {name}: {row['same']} of {row['statements']}")


main()
