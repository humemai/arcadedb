"""Phase 3 shared pieces (no engine, no model): the memory-store seed, the statement splitter and screen, the value canon.
Everything in a run that has to be the same for the prototype, PostgreSQL 18 and DuckDB comes from here.
"""
import decimal
import re

AGENTS = ["alice", "bob", "carol", "dave"]
KINDS = ["fact", "event", "preference", "note"]
N_MEMORY = 400
MEM_COLS = ["id", "agent", "kind", "content", "importance", "created", "subject"]
MEM_TYPES = {"id": "LONG", "agent": "STRING", "kind": "STRING", "content": "STRING", "importance": "INTEGER", "created": "LONG",
             "subject": "LONG"}
ROW_CAP_RB = 2000  # read-back rows kept per query; a larger result is a gold-check failure, never a silent cut


def memory_rows(person_ids):
    """400 memories, deterministic. Ids 1..250 have distinct contents, ids 251..400 repeat the content (and so the agent and kind)
    of ids 1..150: the duplicates of the de-duplication tasks. `created` has ties (211 distinct values for 400 rows). `subject`
    is the id of a person (four of five rows) or null."""
    pids = sorted(person_ids)
    rows = []
    for i in range(1, N_MEMORY + 1):
        c = (i - 1) % 250 + 1
        rows.append((i, AGENTS[c % 4], KINDS[(c // 4) % 4], f"note {c}", (i * 13) % 5 + 1, 1700000000 + ((i * 7919) % 211) * 60,
                     pids[(i * 31) % len(pids)] if i % 5 else None))
    return rows


def archive_rows(mem):
    """12 archived memories, ids 100001..100012: six repeat the content of a memory (so a join or IN on content finds them), six do not."""
    out = []
    for j in range(12):
        base = mem[j * 17]
        content = base[3] if j < 6 else "old " + base[3]
        out.append((100001 + j, base[1], base[2], content, (j * 7) % 5 + 1, base[5] - 86400, base[6]))
    return out


# ---------------------------------------------------------------- the statements of a generation
ALLOWED_FIRST = {"select", "with", "insert", "update", "delete", "merge", "begin", "start", "commit", "end", "rollback", "abort",
                 "savepoint", "release", "values", "table", "truncate", "create", "drop", "alter", "lock", "set", "do", "call",
                 "explain", "copy", "upsert", "replace"}
TX_FIRST = {"begin", "start", "commit", "end", "rollback", "abort"}


def split_statements(text):
    """Split at semicolons outside single quotes, double quotes, dollar quotes and comments; comments are dropped. Returns the
    non-empty statements. The same rule for every arm."""
    out, cur, i, n = [], [], 0, len(text)
    while i < n:
        ch = text[i]
        if ch == "-" and text.startswith("--", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            cur.append(" ")
        elif ch == "/" and text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            cur.append(" ")
        elif ch in "'\"":
            j = i + 1
            while j < n:
                if text[j] == ch:
                    if j + 1 < n and text[j + 1] == ch:
                        j += 2
                        continue
                    break
                j += 1
            cur.append(text[i:j + 1])
            i = j + 1
        elif ch == "$":
            m = re.match(r"\$[A-Za-z_]*\$", text[i:])
            if m:
                tag = m.group(0)
                j = text.find(tag, i + len(tag))
                j = n if j < 0 else j + len(tag)
                cur.append(text[i:j])
                i = j
            else:
                cur.append(ch)
                i += 1
        elif ch == ";":
            s = "".join(cur).strip()
            if s:
                out.append(s)
            cur = []
            i += 1
        else:
            cur.append(ch)
            i += 1
    s = "".join(cur).strip()
    if s:
        out.append(s)
    return out


def first_word(stmt):
    m = re.match(r"\s*([A-Za-z_]+)", stmt)
    return m.group(1).lower() if m else ""


def screen_script(text):
    """(statements, None) when the text is a script of SQL statements, else ([], reason). A statement whose first word is not an SQL
    verb makes the whole text prose (the not_a_query class of phases 1 and 2)."""
    stmts = split_statements(text or "")
    if not stmts:
        return [], "empty query"
    for s in stmts:
        if first_word(s) not in ALLOWED_FIRST:
            return [], f"not a statement (starts with {first_word(s)!r})"
    return stmts, None


def is_tx_control(stmt):
    return first_word(stmt) in TX_FIRST


def canon(v):
    """JSON-safe, engine-neutral value: Decimal to int when integral else float, bool to int, bytes never occur."""
    if isinstance(v, bool):
        return int(v)
    if isinstance(v, decimal.Decimal):
        return int(v) if v == v.to_integral_value() else float(v)
    return v
