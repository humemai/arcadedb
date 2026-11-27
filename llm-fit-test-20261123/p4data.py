"""Phase 4 data side (no engine, no model): the questions, the prompt texts, the cheat sheet and the worked examples, the screens and the
feedback texts of the repair loop. Rules: PREREG-phase4.md of this directory. Importable by a later phase: screen(), feedback_text(),
render_prompt(), split/first-word helpers from seed3.
"""
import os
import re
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor")
PROMPTS = os.path.join(HERE, "prompts", "p4")
sys.path.insert(0, HERE)
sys.path.insert(0, VENDOR)
import seed3  # noqa: E402
import pgarm3 as A3  # noqa: E402  (phase 3, unchanged: read-backs, state comparison)
import json  # noqa: E402

CELLS = ["pg0", "pg1", "pg2", "sqln", "cyph", "duck"]
LANG = {"pg0": "postgresql", "pg1": "postgresql", "pg2": "postgresql", "sqln": "sql", "cyph": "opencypher", "duck": None}
CELL_NAME = {"pg0": "PostgreSQL language, schema text only (baseline)", "pg1": "PostgreSQL language + cheat sheet",
             "pg2": "PostgreSQL language + cheat sheet + three worked examples", "sqln": "ArcadeDB native SQL", "cyph": "openCypher on ArcadeDB",
             "duck": "ANSI SQL on DuckDB (control)"}
PG_CELLS = ("pg0", "pg1", "pg2")
FIRST_READ = {"pg": {"select", "with", "values", "table"}, "sqln": {"select", "match", "traverse"},
              "cyph": {"match", "optional", "with", "unwind", "return"}, "duck": {"select", "with", "values", "table", "from"}}
FIRST_WRITE = {"pg": seed3.ALLOWED_FIRST, "duck": seed3.ALLOWED_FIRST,
               "sqln": {"select", "insert", "update", "delete", "create", "begin", "commit", "rollback", "traverse", "match", "truncate",
                        "alter", "drop", "let", "if", "return", "upsert"},
               "cyph": {"match", "merge", "create", "unwind", "with", "optional", "return", "call", "delete", "detach", "set", "remove",
                        "foreach"}}
READ_TOL = {"float_abs": 0.01, "float_rel": 1.0e-6}


def family(cell):
    return "pg" if cell in PG_CELLS else cell


# ---------------------------------------------------------------- questions
def _one(text):
    """A one-statement gold list, or None when the original experiment has no gold for that language (null gold)."""
    return [text.strip()] if text and text.strip() else None


def read_questions():
    """The 39 read questions of phases 1 and 2 whose reference exists in every original arm (the scorable set of those phases)."""
    gold = json.load(open(os.path.join(VENDOR, "gold_rows.json")))["questions"]
    qs = yaml.safe_load(open(os.path.join(VENDOR, "questions.yaml")))["questions"]
    pgg = yaml.safe_load(open(os.path.join(HERE, "pg_golds_p2.yaml")))["golds"]
    out = []
    for q in qs:
        g = gold[q["id"]]
        if not all(g["arm_verified"].values()):
            continue
        out.append({"id": q["id"], "kind": "read", "group": q.get("category", g["category"]), "text": q["text"], "ordered": g["ordered"],
                    "ref_rows": g["rows"],
                    "golds": {"pg": [" ".join(pgg[q["id"]].split())], "sqln": _one(q["gold"]["arcadedb_sql"]),
                              "cyph": _one(q["gold"]["cypher"]), "duck": _one(q["gold"]["sql"])}})
    return out


def write_questions():
    qs = yaml.safe_load(open(os.path.join(HERE, "p3_questions.yaml")))["questions"]
    nat = yaml.safe_load(open(os.path.join(HERE, "p4_golds_native.yaml")))
    out = []
    for q in qs:
        n = nat[q["id"]]
        out.append({"id": q["id"], "kind": "write", "group": q["construct"], "text": q["text"], "unchanged": bool(q.get("unchanged")),
                    "raw": q, "golds": {"pg": list(q["pg"]), "duck": list(q["sql"]), "sqln": n.get("sql"), "cyph": n.get("cypher")}})
    return out


def readbacks(q, cell):
    """The fixed read-backs of a write task: the PostgreSQL ones for everything that runs on the prototype, the ANSI ones for DuckDB."""
    return A3.readbacks_of(q["raw"], "sql" if cell == "duck" else "pg")


# ---------------------------------------------------------------- prompts
def graph_ddl(ddl):
    """The DDL of the read questions: the full DDL without the two memory tables (phase 2 showed the graph tables only)."""
    blocks = re.split(r"\n(?=CREATE TABLE )", ddl.strip("\n"))
    keep = [b for b in blocks if not re.match(r'CREATE TABLE "(Memory|MemoryArchive)" ', b)]
    return "\n".join(keep) + "\n"


def edge_tables(ddl):
    return re.findall(r'CREATE TABLE "(\w+)" \(\n  id text(?: GENERATED ALWAYS AS IDENTITY)? NOT NULL,\n  source_id text NOT NULL,\n  target_id text NOT NULL,', ddl)


def cheatsheet(doc, ddl, limit=5500):
    """The supported-syntax table of the fork's docs/postgresql-supported-syntax.md trimmed to one page (construct, state, the first
    sentence of the note) plus the schema facts. Stops (SystemExit) when the result passes `limit` characters (4500 in the pre-registration;
    5500 from the addendum, because the document grew to 15 rows)."""
    rows = []
    for ln in doc.splitlines():
        if not ln.startswith("|") or re.match(r"^\|[\s:-]+(\|[\s:-]+)+\|?$", ln):
            continue
        body = re.sub(r"^\|\s*", "", ln.strip())
        while body.endswith("|"):
            body = body[:-1].rstrip()
        cells = [c.strip() for c in re.split(r"\s+\|\s+", body)]
        if len(cells) < 2 or cells[0].lower() == "construct":
            continue
        note = cells[2] if len(cells) > 2 else ""
        note = re.split(r"(?<=[.])\s", note, maxsplit=1)[0][:300]
        rows.append(f"- {cells[0]} [{cells[1]}]" + (f": {note}" if note else ""))
    if not rows:
        sys.exit("no table rows found in the syntax document")
    edges = edge_tables(ddl)
    facts = [
        "Schema facts:",
        "- Every vertex table has the column rid (text), the record id. The engine generates it: leave rid out of INSERT column lists. "
        "The column id of a vertex table is the application id (a number).",
        "- Every edge table (" + ", ".join(edges) + ") is a link table with one row per edge: id (text, generated by the engine: leave it out "
        "of INSERT lists), source_id and target_id (text: the rid of the first and of the second end vertex) and the properties of the edge "
        '(KNOWS has "sinceYear").',
        '- To add an edge, select the rids of its two end vertices: INSERT INTO "<edge table>" (source_id, target_id) SELECT a.rid, b.rid '
        'FROM "<source table>" a, "<target table>" b WHERE a.id = <number> AND b.id = <number>',
        "- A statement outside BEGIN ... COMMIT runs in its own transaction.",
    ]
    text = ("Reference for this PostgreSQL dialect. A construct that is not listed as supported may be refused with SQLSTATE 0A000.\n"
            + "\n".join(rows) + "\n\n" + "\n".join(facts) + "\n")
    if len(text) > limit:
        sys.exit(f"the cheat sheet is {len(text)} characters, over the one-page limit of {limit}: not building")
    return text


EXAMPLE_SETUP = [  # run in order on a copy of the base: the statements of examples 1 and 2
    "INSERT INTO \"Forum\" (id, title) VALUES (990001, 'Reading list') ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title",
    "INSERT INTO \"HAS_MODERATOR\" (source_id, target_id) SELECT f.rid, p.rid FROM \"Forum\" f, \"Person\" p WHERE f.id = 990001 AND p.id = 94",
]
EXAMPLE_READ = ('SELECT title FROM (SELECT f.title, row_number() OVER (PARTITION BY h.target_id ORDER BY f.id DESC) AS rn FROM "Forum" f '
                'JOIN "HAS_MODERATOR" h ON h.source_id = f.rid JOIN "Person" p ON p.rid = h.target_id WHERE p.id IN (94, 96, 102)) x WHERE rn = 1')
EXAMPLE_CHECK = ['SELECT count(*) FROM "HAS_MODERATOR" h JOIN "Forum" f ON f.rid = h.source_id WHERE f.id = 990001',
                 "SELECT id, title FROM \"Forum\" WHERE id = 990001"]


def examples_block():
    return ("Worked examples (each one runs on this dialect):\n"
            "1. Insert a row, or update it when its id exists:\n" + EXAMPLE_SETUP[0] + "\n"
            "2. Add an edge between two existing vertices:\n" + EXAMPLE_SETUP[1] + "\n"
            "3. Keep the newest row of each group with a window function:\n" + EXAMPLE_READ + "\n")


def template(cell, kind):
    return open(os.path.join(PROMPTS, f"{kind}_{family(cell)}.txt")).read()


def render_prompt(cell, q, ctx):
    """ctx: {"ddl_graph", "ddl_full", "cheat", "examples"}. pg cells take the engine's own DDL; pg1 and pg2 insert the reference block
    (and for pg2 the examples) between the data sentences and the question."""
    t = template(cell, q["kind"])
    if cell in PG_CELLS:
        t = t.replace("{schema}", ctx["ddl_graph"] if q["kind"] == "read" else ctx["ddl_full"]).replace("{ldbc_id}", "id")
        extra = {"pg0": "", "pg1": ctx["cheat"] + "\n", "pg2": ctx["cheat"] + "\n" + ctx["examples"] + "\n"}[cell]
        if extra:
            assert t.count("\nQuestion: {question}") == 1
            t = t.replace("\nQuestion: {question}", "\n" + extra + "\nQuestion: {question}")
    assert t.count("{question}") == 1
    return t.replace("{question}", q["text"])


def closing_line(cell, kind):
    return template(cell, kind).rstrip().split("\n")[-1]


# ---------------------------------------------------------------- screens and feedback
def screen(cell, kind, text):
    """(statements, None) for a text that is one statement (read) or a script (write) of the arm's language, else ([], reason): a statement
    whose first word is not in the arm's verb set makes the text prose (class not_a_query, as in phases 1 to 3)."""
    stmts = seed3.split_statements(text or "")
    if not stmts:
        return [], "empty query"
    if kind == "read" and len(stmts) != 1:
        return [], "not a single statement"
    allowed = (FIRST_READ if kind == "read" else FIRST_WRITE)[family(cell)]
    for s in stmts:
        if seed3.first_word(s) not in allowed:
            return [], f"not a statement of this language (starts with {seed3.first_word(s)!r})"
    return stmts, None


def feedback_text(cell, kind, cls, res):
    """What the agent sees after a failed attempt: the database's own error (or the harness's one line when nothing could be run), and the
    closing line of the task prompt. Nothing about right or wrong answers: an attempt that ran ends the episode."""
    if cls == "not_a_query":
        body = f"Nothing could be run: {res['screen']}."
    elif cls == "timeout":
        body = "ERROR: the statement was cancelled after 60 seconds."
    elif cls == "engine_killed":
        body = "ERROR: the connection to the database was lost."
    else:
        st = f" [{res['sqlstate']}]" if res.get("sqlstate") and family(cell) == "pg" else ""
        where = f" (statement {res['failed_index'] + 1} of {res['n_statements']})" if res.get("n_statements", 1) > 1 else ""
        body = f"ERROR{st}: {res['error']}{where}"
    tail = "\n\nThe attempt was rolled back: the data is as it was before it." if kind == "write" else ""
    return f"{body}{tail}\n\nTry again. {closing_line(cell, kind)}"
