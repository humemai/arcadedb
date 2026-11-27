"""Print the chosen statements of a result file in full.

usage: show.py RESULTS_FILE ID [ID ...]
"""

import json
import sys


def main():
    path, wanted = sys.argv[1], sys.argv[2:]
    for line in open(path, encoding="utf-8"):
        item = json.loads(line)
        if wanted != ["all"] and item["id"] not in wanted:
            continue
        print(f"== {item['id']} {' '.join(item['sql'].split())[:260]}")
        for side in ("pg", "arc"):
            out = item[side]
            if out["ok"]:
                if "columns" in out:
                    print(f"  {side}: {out['columns']} {out['types']} n={out['count']} {json.dumps(out['rows'][:8], ensure_ascii=False)[:600]}")
                else:
                    print(f"  {side}: ok count={out.get('count')} {out.get('warnings', '')}")
            else:
                print(f"  {side}: ERROR {out.get('state')} {str(out.get('text') or out.get('message'))[:420]}")
                for key in ("detail", "hint"):
                    if out.get(key):
                        print(f"      {key}: {out[key][:300]}")
        for fallback in item.get("fallbacks", []):
            out = fallback["out"]
            print(f"  fallback: {' '.join(fallback['sql'].split())[:200]}")
            if out["ok"]:
                print(f"    -> ok {out.get('columns', '')} n={out.get('count')} {json.dumps(out.get('rows', [])[:6], ensure_ascii=False)[:300]}")
            else:
                print(f"    -> ERROR {out.get('state')} {str(out.get('text') or out.get('message'))[:300]}")


main()
