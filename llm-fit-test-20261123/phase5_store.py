"""Phase 5 shared pieces (no engine, no model): the agent-memory store seed, the reference store that records gold operations, the
renderers of those operations in four dialects, the canonical state, and the answer matcher. Everything a run has to have the same
in every arm comes from here. Rules: PREREG-phase5.md.

The store: Person, Topic, Memory, Fact (vertex tables, each with a declared numeric `id`, unique in its table) and four edge tables:
MENTIONS (Memory to Person), ABOUT (Memory to Topic), DESCRIBES (Fact to Person), SUPERSEDES (Fact to the older Fact).
"""
import copy
import datetime
import random
import re

SEED = 20261124
COLS = {"Person": ["id", "name", "role"], "Topic": ["id", "name"], "Memory": ["id", "content", "kind", "importance", "created"],
        "Fact": ["id", "attribute", "detail", "valid_from", "valid_to"]}
TYPES = {"id": "LONG", "name": "STRING", "role": "STRING", "content": "STRING", "kind": "STRING", "importance": "INTEGER",
         "created": "STRING", "attribute": "STRING", "detail": "STRING", "valid_from": "STRING", "valid_to": "STRING"}
EDGES = {"MENTIONS": ("Memory", "Person"), "ABOUT": ("Memory", "Topic"), "DESCRIBES": ("Fact", "Person"), "SUPERSEDES": ("Fact", "Fact")}
VERTS = list(COLS)
NATURAL = {"Person": ["name"], "Topic": ["name"], "Memory": ["content", "created"], "Fact": ["attribute", "detail", "valid_from"]}

PERSONS = [("Alice", "colleague"), ("Bob", "friend"), ("Carol", "family"), ("Dana", "colleague"), ("Eve", "friend"), ("Frank", "colleague"),
           ("Gina", "family"), ("Henry", "friend"), ("Ivan", "colleague"), ("Julia", "friend"), ("Kofi", "colleague"), ("Lena", "family")]
TOPICS = ["travel", "health", "work", "food", "music", "home", "books", "job"]
OBJECTS = {
    "travel": ["the Lisbon trip", "the Osaka flights", "the passport renewal", "the Alps train pass", "the Oslo hotel", "the Porto weekend"],
    "health": ["the dentist visit", "the flu shot", "the knee physio", "the sleep tracker", "the blood test", "the yoga class"],
    "work": ["the Q3 budget meeting", "the roadmap review", "the hiring plan", "the demo script", "the onboarding doc", "the vendor contract"],
    "food": ["the pasta recipe", "the sushi place", "the vegan menu", "the coffee beans", "the birthday cake", "the farmers market"],
    "music": ["the jazz concert", "the piano lessons", "the vinyl collection", "the festival tickets", "the new playlist", "the choir rehearsal"],
    "home": ["the new sofa", "the boiler repair", "the garden shed", "the rent increase", "the paint colour", "the internet contract"],
    "books": ["the novel Dune", "the library card", "the book club", "the poetry volume", "the audiobook", "the chess manual"],
    "job": ["the job offer", "the interview prep", "the CV update", "the salary talk", "the notice period", "the recruiter call"],
}
PATTERNS = {"note": "{P} mentioned {o}", "event": "{P} and I discussed {o}", "plan": "Ask {P} about {o}", "preference": "{P} likes {o}"}
CITY_CHAINS = {"Alice": ["Hamburg", "Berlin"], "Bob": ["Lisbon"], "Carol": ["Rotterdam"], "Dana": ["Utrecht", "Rotterdam", "Amsterdam"],
               "Eve": ["Oslo"], "Frank": ["Utrecht"], "Gina": ["Lisbon", "Porto"], "Henry": ["Vienna"], "Ivan": ["Brno", "Prague"],
               "Julia": ["Madrid"], "Kofi": ["Accra"], "Lena": ["Basel", "Zurich"]}
EMPLOYER_CHAINS = {"Alice": ["Acme"], "Bob": ["Globex"], "Dana": ["Initech", "Hooli"], "Eve": ["Hooli"], "Frank": ["Initech"], "Ivan": ["Acme"],
                   "Kofi": ["Globex"]}
DIET_CHAINS = {"Carol": ["vegetarian"], "Gina": ["omnivore", "vegan"], "Henry": ["vegetarian"], "Julia": ["pescatarian"], "Lena": ["vegan"]}
CHAIN_STARTS = ["2022-03-01", "2024-09-01", "2025-11-01"]
N_DUP = 8


def day(n):
    return (datetime.date(2026, 1, 2) + datetime.timedelta(days=n)).isoformat()


class Ref:
    """The reference store: tables as {id: row}, edges as lists of (source id, target id). Every mutator records the operation in the
    current session, so a task written against it IS its gold: the final state, the answers and the per-session operation lists."""

    def __init__(self):
        self.T = {t: {} for t in VERTS}
        self.E = {e: [] for e in EDGES}
        self.sessions = []

    # ---- recording mutators
    def session(self, text, answer=None):
        self.sessions.append({"text": text, "answer": answer, "ops": []})

    def answer(self, spec):
        self.sessions[-1]["answer"] = spec

    def _rec(self, op):
        if self.sessions:
            self.sessions[-1]["ops"].append(op)

    def next_id(self, t):
        return max(self.T[t], default=0) + 1

    def add(self, t, **row):
        row.setdefault("id", self.next_id(t))
        full = {c: row.get(c) for c in COLS[t]}
        assert full["id"] not in self.T[t], (t, full["id"])
        self.T[t][full["id"]] = full
        self._rec(("add", t, dict(full)))
        return full["id"]

    def set(self, t, i, **cols):
        assert i in self.T[t]
        self.T[t][i].update(cols)
        self._rec(("set", t, i, dict(cols)))

    def delete(self, t, i):
        assert i in self.T[t]
        del self.T[t][i]
        for e, (s, d) in EDGES.items():
            self.E[e] = [(a, b) for a, b in self.E[e] if not ((s == t and a == i) or (d == t and b == i))]
        self._rec(("del", t, i))

    def link(self, e, a, b):
        s, d = EDGES[e]
        assert a in self.T[s] and b in self.T[d], (e, a, b)
        self.E[e].append((a, b))
        self._rec(("link", e, a, b))

    def unlink(self, e, a, b):
        assert (a, b) in self.E[e], (e, a, b)
        self.E[e].remove((a, b))
        self._rec(("unlink", e, a, b))

    def apply(self, op):
        k = op[0]
        {"add": lambda: self.add(op[1], **op[2]), "set": lambda: self.set(op[1], op[2], **op[3]), "del": lambda: self.delete(op[1], op[2]),
         "link": lambda: self.link(op[1], op[2], op[3]), "unlink": lambda: self.unlink(op[1], op[2], op[3])}[k]()

    def clone_data(self):
        r = Ref()
        r.T, r.E = copy.deepcopy(self.T), copy.deepcopy(self.E)
        return r

    # ---- lookups
    def pid(self, name):
        return next(i for i, r in self.T["Person"].items() if r["name"] == name)

    def tid(self, name):
        return next(i for i, r in self.T["Topic"].items() if r["name"] == name)

    def mid(self, content):
        m = [i for i, r in self.T["Memory"].items() if r["content"] == content]
        assert len(m) == 1, (content, m)
        return m[0]

    def mems(self, topic=None, person=None):
        out = sorted(self.T["Memory"])
        if topic:
            t = self.tid(topic)
            out = [m for m in out if (m, t) in self.E["ABOUT"]]
        if person:
            p = self.pid(person)
            out = [m for m in out if (m, p) in self.E["MENTIONS"]]
        return out

    def mem(self, i):
        return self.T["Memory"][i]

    def mentions_of(self, m):
        return sorted(b for a, b in self.E["MENTIONS"] if a == m)

    def topics_of(self, m):
        return sorted(b for a, b in self.E["ABOUT"] if a == m)

    def facts_of(self, person, attribute):
        p = self.pid(person)
        return sorted((f for f, q in self.E["DESCRIBES"] if q == p and self.T["Fact"][f]["attribute"] == attribute),
                      key=lambda f: self.T["Fact"][f]["valid_from"])

    # ---- state
    def state(self):
        out = {t: sorted((tuple(r[c] for c in COLS[t]) for r in self.T[t].values()), key=skey) for t in VERTS}
        for e in EDGES:
            out[e] = sorted((tuple(x) for x in self.E[e]), key=skey)
        return out

    def lenient(self):
        return lenient_of(self.state())


def skey(x):
    """A sort key that tolerates None next to text and numbers."""
    if isinstance(x, (tuple, list)):
        return tuple(skey(y) for y in x)
    return (x is None, str(x))


def lenient_of(state):
    """The id-agnostic view: vertex rows without their declared id, edges as the natural keys of their two ends."""
    def key(t, rows):
        idx = {c: COLS[t].index(c) for c in NATURAL[t]}
        return {r[0]: tuple(r[i] for i in idx.values()) for r in rows}
    keys = {t: key(t, state[t]) for t in VERTS}
    out = {t: sorted((tuple(x for j, x in enumerate(r) if j > 0) for r in state[t]), key=skey) for t in VERTS}
    for e, (s, d) in EDGES.items():
        out[e] = sorted(((keys[s].get(a), keys[d].get(b)) for a, b in state[e]), key=skey)
    return out


def build_seed():
    """The seed store, deterministic from SEED. 120 memories, 12 persons, 8 topics, facts with validity periods."""
    rng = random.Random(SEED)
    R = Ref()
    for i, (n, role) in enumerate(PERSONS, 1):
        R.T["Person"][i] = {"id": i, "name": n, "role": role}
    for i, n in enumerate(TOPICS, 1):
        R.T["Topic"][i] = {"id": i, "name": n}
    base = []
    for t in TOPICS:
        combos = [(o, k) for o in OBJECTS[t] for k in PATTERNS]
        for o, k in rng.sample(combos, 14):
            two = rng.random() < 0.2
            ps = rng.sample([p for p, _ in PERSONS], 2 if two else 1)
            base.append((t, o, k, ps))
    rng.shuffle(base)
    days = rng.sample(range(180), 120)
    mems = []
    for t, o, k, ps in base:
        mems.append({"content": PATTERNS[k].format(P=" and ".join(ps), o=o), "kind": k, "importance": rng.randint(1, 5), "topic": t, "ps": ps})
    dups = rng.sample(range(len(mems)), N_DUP)
    for i, m in enumerate(mems):
        m["created"] = day(days[i])
    nb = len(mems)
    for j, i in enumerate(dups):  # later copies of eight memories: same content and kind, a later day
        d = dict(mems[i])
        d["created"] = day(days[nb + j])
        if d["created"] <= mems[i]["created"]:
            d["created"], mems[i]["created"] = mems[i]["created"], d["created"]
        d["importance"] = rng.randint(1, 5)
        mems.append(d)
    second = rng.sample(range(len(mems)), 12)
    for n, m in enumerate(mems, 1):
        R.T["Memory"][n] = {"id": n, "content": m["content"], "kind": m["kind"], "importance": m["importance"], "created": m["created"]}
        R.E["ABOUT"].append((n, R.tid(m["topic"])))
        for p in m["ps"]:
            R.E["MENTIONS"].append((n, R.pid(p)))
        if n - 1 in second:
            other = rng.choice([t for t in TOPICS if t != m["topic"]])
            R.E["ABOUT"].append((n, R.tid(other)))
    fid = 0
    for attr, chains in (("city", CITY_CHAINS), ("employer", EMPLOYER_CHAINS), ("diet", DIET_CHAINS)):
        for person, vals in chains.items():
            prev = None
            for k, v in enumerate(vals):
                fid += 1
                last = k == len(vals) - 1
                if len(vals) == 1:
                    start = "2023-06-01"
                else:
                    start = CHAIN_STARTS[3 - len(vals) + k]
                vto = None if last else (datetime.date.fromisoformat(CHAIN_STARTS[3 - len(vals) + k + 1]) - datetime.timedelta(days=1)).isoformat()
                R.T["Fact"][fid] = {"id": fid, "attribute": attr, "detail": v, "valid_from": start, "valid_to": vto}
                R.E["DESCRIBES"].append((fid, R.pid(person)))
                if prev:
                    R.E["SUPERSEDES"].append((fid, prev))
                prev = fid
    return R


# ---------------------------------------------------------------- rendering of the gold operations
def lit(v, dialect):
    if v is None:
        return "NULL" if dialect != "cypher" else "null"
    if isinstance(v, int):
        return str(v)
    s = str(v)
    return "'" + (s.replace("'", "\\'") if dialect == "cypher" else s.replace("'", "''")) + "'"


def incident(t):
    """(edge type, side) pairs whose edge rows hang on a vertex of table t."""
    return [(e, "source_id") for e, (s, d) in EDGES.items() if s == t] + [(e, "target_id") for e, (s, d) in EDGES.items() if d == t]


def render(op, dialect):
    """One statement for an operation. dialect: pg (the prototype's PostgreSQL language and the DuckDB control: the same text),
    sql (ArcadeDB native SQL), cypher (openCypher). Placeholders {rid:Table:id} are resolved by the gold runner from the live store."""
    k = op[0]
    if dialect == "pg":
        q = lambda x: '"' + x + '"'  # noqa: E731
        if k == "add":
            t, row = op[1], op[2]
            return f"INSERT INTO {q(t)} ({', '.join(COLS[t])}) VALUES ({', '.join(lit(row[c], dialect) for c in COLS[t])})"
        if k == "set":
            return f"UPDATE {q(op[1])} SET " + ", ".join(f"{c} = {lit(v, dialect)}" for c, v in op[3].items()) + f" WHERE id = {op[2]}"
        if k == "del":
            t, i = op[1], op[2]
            return [f"DELETE FROM {q(e)} WHERE {side} = '{{rid:{t}:{i}}}'" for e, side in incident(t)] + [f"DELETE FROM {q(t)} WHERE id = {i}"]
        if k == "link":
            s, d = EDGES[op[1]]
            return (f"INSERT INTO {q(op[1])} (source_id, target_id) SELECT a.rid, b.rid FROM {q(s)} a, {q(d)} b "
                    f"WHERE a.id = {op[2]} AND b.id = {op[3]}")
        if k == "unlink":
            s, d = EDGES[op[1]]
            return f"DELETE FROM {q(op[1])} WHERE source_id = '{{rid:{s}:{op[2]}}}' AND target_id = '{{rid:{d}:{op[3]}}}'"
    if dialect == "sql":
        if k == "add":
            t, row = op[1], op[2]
            return f"CREATE VERTEX {t} SET " + ", ".join(f"{c} = {lit(row[c], dialect)}" for c in COLS[t] if row[c] is not None)
        if k == "set":
            return f"UPDATE {op[1]} SET " + ", ".join(f"{c} = {lit(v, dialect)}" for c, v in op[3].items()) + f" WHERE id = {op[2]}"
        if k == "del":
            return f"DELETE FROM {op[1]} WHERE id = {op[2]}"
        if k == "link":
            s, d = EDGES[op[1]]
            return f"CREATE EDGE {op[1]} FROM (SELECT FROM {s} WHERE id = {op[2]}) TO (SELECT FROM {d} WHERE id = {op[3]})"
        if k == "unlink":
            return f"DELETE FROM {op[1]} WHERE out.id = {op[2]} AND in.id = {op[3]}"
    if dialect == "cypher":
        if k == "add":
            t, row = op[1], op[2]
            return f"CREATE (:{t} {{" + ", ".join(f"{c}: {lit(row[c], dialect)}" for c in COLS[t] if row[c] is not None) + "})"
        if k == "set":
            return f"MATCH (n:{op[1]} {{id: {op[2]}}}) SET " + ", ".join(f"n.{c} = {lit(v, dialect)}" for c, v in op[3].items())
        if k == "del":
            return f"MATCH (n:{op[1]} {{id: {op[2]}}}) DETACH DELETE n"
        if k == "link":
            s, d = EDGES[op[1]]
            return f"MATCH (a:{s} {{id: {op[2]}}}), (b:{d} {{id: {op[3]}}}) CREATE (a)-[:{op[1]}]->(b)"
        if k == "unlink":
            s, d = EDGES[op[1]]
            return f"MATCH (a:{s} {{id: {op[2]}}})-[e:{op[1]}]->(b:{d} {{id: {op[3]}}}) DELETE e"
    raise ValueError((op, dialect))


def render_session(ops, dialect):
    out = []
    for op in ops:
        r = render(op, dialect)
        out.extend(r if isinstance(r, list) else [r])
    return out


# ---------------------------------------------------------------- answers
def parse_answer(text):
    """The value after the last line that starts with ANSWER:, or None."""
    m = re.findall(r"(?im)^[\s*_`>]*answer\s*:\s*(.*?)\s*$", text or "")
    return m[-1].strip(" *_`") if m else None


def _items(s):
    parts = [x.strip(" '\"`[]().") for x in re.split(r"[,;]|\band\b", s)]
    return [x for x in parts if x]


PROSE = {"Memory": "content", "Fact": "detail"}  # the columns that hold prose: compared after norm_text, every other column exactly
_QUOTES = {'"': '"', "'": "'", "`": "`", "\u201c": "\u201d", "\u2018": "\u2019"}


def norm_text(x):
    """Trim, collapse whitespace, strip trailing . ! ? and surrounding quotes (repeatedly); case is kept. Applied to the gold state and the
    final state alike, so a full stop that the task text put next to a quoted value, or a closing quote, cannot decide a task."""
    if not isinstance(x, str):
        return x
    s = " ".join(x.split())
    while True:
        t = s
        s = s.rstrip(".!? ").strip()
        if len(s) >= 2 and s[0] in _QUOTES and s[-1] == _QUOTES[s[0]]:
            s = s[1:-1].strip()
        if s == t:
            return s


def norm_state(state):
    out = dict(state)
    for t, c in PROSE.items():
        i = COLS[t].index(c)
        out[t] = sorted((tuple(norm_text(v) if j == i else v for j, v in enumerate(r)) for r in state[t]), key=skey)
    return out


def answer_ok(spec, got):
    """spec: ("int", n) | ("set", [..]) | ("list", [..]) | ("text", s) | ("float", x). Compared after the same normalisation for every arm."""
    if spec is None:
        return True
    if got is None:
        return False
    kind, val = spec
    if kind == "int":
        m = re.search(r"-?\d+", got.replace(",", ""))
        return bool(m) and int(m.group(0)) == val
    if kind == "float":
        m = re.search(r"-?\d+(\.\d+)?", got)
        return bool(m) and abs(float(m.group(0)) - val) <= 0.005
    if kind == "set":
        return sorted(x.casefold() for x in _items(got)) == sorted(str(x).casefold() for x in val)
    if kind == "list":
        return [x.casefold() for x in _items(got)] == [str(x).casefold() for x in val]
    return norm_text(got).casefold() == norm_text(str(val)).casefold()


def answer_format(spec):
    return {"int": "a single integer", "float": "a single number with two decimals", "set": "names or numbers separated by commas, in any order",
            "list": "values separated by commas, in the order asked", "text": "a short text"}[spec[0]]
