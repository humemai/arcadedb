"""Gate of phase 3b, no model and no engine jar: (1) render_ddl prints the identity flag and split_ddl reads it back; (2) a PostgreSQL 18
reference loaded from the phase 3 dump with the identity text (a trigger instead of a default) gives, for all 40 golds, the same
read-back state as the phase 3 reference (goldcheck/ref.json of phase 3), a supplied record id is 428C9 and DEFAULT is accepted.

  uv run --no-project --python 3.13 --with pyyaml --with 'psycopg[binary]' python check_identity_ref.py PHASE3_RUN_DIR [PGPORT]
Needs the container of pg_ref3.sh (start it first). Exit 0 only when everything holds.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
os.environ.setdefault("PGARM3_PHASE", "3b")
import pgarm3  # noqa: E402
import pgengine3  # noqa: E402
import refs3  # noqa: E402

run = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 55443
bad = []

# (1) the renderer and the reader, on a tiny schema
tables = [{"table_name": "T", "table_type": "BASE TABLE"}, {"table_name": "V", "table_type": "VIEW"}]
cols = [{"table_name": "T", "column_name": "rid", "data_type": "text", "is_nullable": "NO", "is_identity": "YES", "identity_generation": "ALWAYS"},
        {"table_name": "T", "column_name": "id", "data_type": "bigint", "is_nullable": "NO", "is_identity": "NO", "identity_generation": None},
        {"table_name": "T", "column_name": "name", "data_type": "character varying", "is_nullable": "YES", "is_identity": "NO", "identity_generation": None}]
text = pgengine3.render_ddl(tables, cols, [{"constraint_name": "T_pkey", "table_name": "T", "constraint_type": "PRIMARY KEY"}],
                            [{"constraint_name": "T_pkey", "table_name": "T", "column_name": "id", "ordinal_position": 1}], [])
want = 'CREATE TABLE "T" (\n  rid text GENERATED ALWAYS AS IDENTITY NOT NULL,\n  id bigint NOT NULL,\n  name character varying,\n  CONSTRAINT "T_pkey" PRIMARY KEY (id)\n);\n'
if text != want:
    bad.append(f"render_ddl: {text!r}")
creates, fks, gens = refs3.split_ddl(text, True)
if gens != [('"T"', "rid")] or "GENERATED" in creates[0]:
    bad.append(f"split_ddl: {gens} {creates}")

# (2) the reference
ddl = open(os.path.join(run, "ddl.sql")).read()
dump = json.load(open(os.path.join(run, "dump.json")))
ident = re.sub(r'^(  (?:rid|id)) text NOT NULL,$', r'\1 text GENERATED ALWAYS AS IDENTITY NOT NULL,', ddl, flags=re.M)
n_flag = ident.count("GENERATED ALWAYS AS IDENTITY")
pg = refs3.PgRef(port)
pg.load(ident, dump, True)
old_ref = json.load(open(os.path.join(run, "goldcheck", "ref.json")))
for q in pgarm3.load_questions():
    r = pg.run({"key": q["id"], "statements": pgarm3.stmts_of(q, "pg"), "readbacks": pgarm3.readbacks_of(q, "pg")})
    if r["status"] != "ok" or not pgarm3.state_equal(old_ref[q["id"]]["readbacks"], r["readbacks"]):
        bad.append(f"{q['id']}: {r['status']} {r['error']}")
probes = {"supplied rid": ("INSERT INTO \"Memory\" (rid, id, agent) VALUES ('x', 990001, 'probe')", ("runtime", "428C9")),
          "DEFAULT rid": ("INSERT INTO \"Memory\" (rid, id, agent) VALUES (DEFAULT, 990002, 'probe')", ("ok", None)),
          "supplied edge id": ("INSERT INTO \"KNOWS\" (id, source_id, target_id) SELECT 'x', a.rid, b.rid FROM \"Person\" a, \"Person\" b WHERE a.id = 65 AND b.id = 933", ("runtime", "428C9")),
          "edge without id": ("INSERT INTO \"KNOWS\" (source_id, target_id) SELECT a.rid, b.rid FROM \"Person\" a, \"Person\" b WHERE a.id = 65 AND b.id = 933", ("ok", None))}
for name, (stmt, want_st) in probes.items():
    r = pg.run({"key": name, "statements": [stmt], "readbacks": []})
    if (r["status"], r["sqlstate"]) != want_st:
        bad.append(f"probe {name}: {r['status']} {r['sqlstate']} {r['error']}")
print(f"identity flags in the synthetic text: {n_flag}; PostgreSQL {pg.version()}; 40 golds compared with the phase 3 reference; {len(probes)} probes")
if bad:
    print("FAILED:\n  " + "\n  ".join(bad))
    sys.exit(1)
print("identity reference gate: ok")
