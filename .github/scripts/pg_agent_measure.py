#!/usr/bin/env python3
#
# Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
# SPDX-License-Identifier: Apache-2.0
#

"""Writes the page docs/postgresql-agent-measure.md from the result of a run of the agent measure.

Fork only (humemai/arcadedb, PostgreSQL-style SQL prototype, fork issue #265).

    pg_agent_measure.py collect RUN_DIRECTORY   judge the answers of a run, keep what the page needs in the result directory,
                                               and write the page
    pg_agent_measure.py summary RUN_DIRECTORY   print the numbers of a run and what differs from the committed result
    pg_agent_measure.py write                   write the page from the result directory
    pg_agent_measure.py check                   fail unless the page is the one that the result directory gives and the result
                                               is one of the committed corpus

RUN_DIRECTORY holds one file per script, written by PostgreSQLAgentMeasureTest: postgresw/target/pg-agent-measure, or the
artifact `pg-agent-measure` of a CI run. Nothing here fails because of an answer of the language: only a run that is not
complete, or a page that is not the one of its result, fails.
"""

import collections
import json
import math
import pathlib
import re
import sys
from decimal import Decimal, InvalidOperation

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "postgresw/src/test/resources/agentmeasure"
CORPUS = RESOURCES / "corpus.sql"
LISTED = RESOURCES / "listed.tsv"
RESULT = RESOURCES / "result"
PAGE = ROOT / "docs/postgresql-agent-measure.md"

FORK = "https://github.com/humemai/arcadedb"

# each script of the test, the set it counts in, and its groups of the corpus
SCRIPTS = {
    "memory": ("agent", ("memory",)),
    "web": ("agent", ("web", "web-transactions", "web-discovery", "web-mistakes")),
    "ddl-probes": ("probes", ("ddl-probes",)),
    "features": ("probes", ("features",)),
    "design": ("probes", ("design",)),
    "followups": ("probes", ("followups",)),
    "followups2": ("probes", ("followups2",)),
}
SETS = {"agent": "agent scripts", "probes": "probes"}
CLASSES = ("schema", "write", "read", "transaction", "discovery")
FIRST_WORDS = {
    "schema": ("CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "ANALYZE", "VACUUM", "GRANT", "DO"),
    "write": ("INSERT", "UPDATE", "DELETE", "MERGE", "COPY"),
    "read": ("SELECT", "SHOW", "EXPLAIN", "WITH", "VALUES", "PREPARE", "EXECUTE", "DEALLOCATE"),
    "transaction": ("BEGIN", "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "SET", "LOCK", "LISTEN", "DISCARD"),
}
CATALOG = re.compile(r"\binformation_schema\.|\bpg_catalog\.|\bpg_(tables|indexes|class|attribute|constraint|type|extension)\b",
                     re.IGNORECASE)
VERDICTS = {
    "same": ("same", "the same columns, rows, and count as PostgreSQL, or the same SQLSTATE"),
    "refused": ("refused", "the language fails where PostgreSQL succeeds"),
    "other-code": ("other code", "both fail, with different codes"),
    "follows": ("after an earlier failure", "differs from PostgreSQL, and equals what PostgreSQL answers after it has run only "
                                            "what the language ran"),
    "listed": ("listed difference", "succeeds with another answer that the list of kept differences names"),
    "silent": ("silent difference", "succeeds where PostgreSQL answers otherwise or refuses"),
}
OWN_ERRORS = ("refused", "other-code")
COLUMNS = ("script", "id", "set", "class", "volatile", "verdict", "postgresql_18", "language", "next_step", "hint_field",
           "statement", "postgresql_18_answer", "language_answer", "message")
ABOUT = ("PostgreSQL", "engine", "CI run", "date")

NUMBER_TYPES = {"int2", "int4", "int8", "serial", "bigserial", "float4", "float8", "numeric"}
FLOAT_TYPES = {"float4", "float8"}
DISTANCE = re.compile(r"<=>|<->|<#>")
# an error names a next step: a clause of its message starts with one of these verbs, it has a hint, or it asks for a retry
STEP = re.compile(r"hint:\s*\S|(?:^|[.:;,]\s+)(?:please |then )?(?:use|write|send|cast|declare|retry|run|quote|add|remove|leave"
                  r"|repeat)\b|please retry", re.IGNORECASE)
# how a hint starts that says that nothing can be written instead: it is not a next step
NO_WAY = "There is no way to write this in the postgresql language yet"
# how many silent answers and messages the page shows in full
TOP_MESSAGES = 20
SHOWN = 110


def fail(message):
    sys.exit("pg_agent_measure: " + message)


def printable(text):
    """A text as one cell of the result: one line, no tab, no raw control character."""
    return re.sub(r"[\x00-\x1f\x7f]+", " ", str(text)).strip()


def read_table(path):
    """The rows of a tab separated file with a header line; lines that start with # are comments."""
    lines = [line for line in path.read_text(encoding="utf-8").split("\n") if line and not line.startswith("#")]
    header = lines[0].split("\t")
    rows = []
    for number, line in enumerate(lines[1:], start=2):
        cells = line.split("\t")
        if len(cells) != len(header):
            fail(f"{path.name}, row {number}: {len(cells)} cells for {len(header)} columns")
        rows.append(dict(zip(header, cells)))
    return rows


def write_table(path, header, rows):
    text = "\t".join(header) + "\n"
    for row in rows:
        text += "\t".join(str(row[name]) for name in header) + "\n"
    path.write_text(text, encoding="utf-8")


# ------------------------------------------------------------------------------------------------------------------- corpus


def corpus():
    statements, group, current, block, body = [], "", None, None, []
    for line in CORPUS.read_text(encoding="utf-8").split("\n") + ["--# end"]:
        if not line.startswith(("--# ", "--@ ", "--! ")):
            body.append(line)
            continue
        if current is not None and block:
            current[block] = "\n".join(body).strip()
        body, block = [], None
        words = line[4:].split()
        if line.startswith("--# "):
            current = None
            if words[0] == "group":
                group = words[1]
        elif line.startswith("--@ "):
            current = {"id": words[0], "group": group, "tags": words[1:], "sql": "", "via_sql": False}
            statements.append(current)
            block = "sql"
        elif words[0] == "pg":
            block = "pg"
        elif words[0] == "via-sql":
            current["via_sql"] = True
        elif words[0] not in ("rollback", "param"):
            fail(f"corpus: unknown directive {line}")
    repeated = [key for key, count in collections.Counter(s["id"] for s in statements).items() if count > 1]
    unknown = sorted({s["group"] for s in statements} - {g for _, groups in SCRIPTS.values() for g in groups})
    if repeated or unknown:
        fail(f"corpus: ids used twice {repeated}, groups that no script runs {unknown}")
    return statements


def class_of(statement):
    sql = re.sub(r"^\{sql\}\s*", "", statement["sql"])
    if statement["group"] == "web-discovery" or CATALOG.search(sql):
        return "discovery"
    if statement["group"] == "web-transactions":
        return "transaction"
    first = re.match(r"\s*([A-Za-z]+)", sql).group(1).upper()
    if first == "WITH" and re.search(r"\(\s*(INSERT|UPDATE|DELETE)\b", sql, re.IGNORECASE):
        return "write"
    for name, words in FIRST_WORDS.items():
        if first in words:
            return name
    return fail(f"{statement['id']}: no class for a statement that starts with {first}")


def listed():
    entries = {row["id"]: row for row in read_table(LISTED)}
    known = {s["id"] for s in corpus() if not s["via_sql"]}
    wrong = [key for key, row in entries.items() if key not in known or not row["listed_as"].strip()]
    if wrong:
        fail(f"listed.tsv: no statement of the corpus, or no reference: {wrong}")
    return entries


# ------------------------------------------------------------------------------------------------------------------- judging


def value_key(value, left_type, right_type):
    """A value as it is compared: JSON and numbers by value, everything else as its text."""
    if value is None:
        return ("null",)
    if left_type in ("json", "jsonb") or right_type in ("json", "jsonb"):
        try:
            parsed = json.loads(value, parse_float=Decimal, parse_int=Decimal)
            return ("json", json.dumps(parsed, sort_keys=True, default=lambda number: str(number.normalize())))
        except (ValueError, TypeError):
            return ("text", value)
    if left_type in NUMBER_TYPES or right_type in NUMBER_TYPES:
        try:
            number = Decimal(value)
            return ("number", "nan" if number.is_nan() else str(number.normalize()))
        except InvalidOperation:
            return ("text", value)
    return ("text", value)


def equal(statement, left, right):
    """Whether two outcomes of one statement count as the same answer."""
    if left["ok"] != right["ok"]:
        return False
    if not left["ok"]:
        return left.get("state") == right.get("state")
    if ("columns" in left) != ("columns" in right):
        return False
    if "columns" not in left:
        return left.get("count") == right.get("count")
    if left["columns"] != right["columns"]:
        return False
    sql = statement["sql"]
    # a plan is not an answer
    if sql.lstrip().upper().startswith("EXPLAIN"):
        return True
    if left["count"] != right["count"]:
        return False
    if "volatile" in statement["tags"] and "own-value" not in statement["tags"]:
        return True
    types = list(zip(left["types"], right["types"]))
    sides = [[tuple(value_key(value, *types[i]) for i, value in enumerate(row)) for row in side["rows"]] for side in (left, right)]
    if "order by" not in sql.lower():
        sides = [sorted(side, key=repr) for side in sides]
    if len(sides[0]) != len(sides[1]):
        return False

    def close(first, second, column):
        if first == second:
            return True
        if not (DISTANCE.search(sql) and types[column][0] in FLOAT_TYPES and first[0] == second[0] == "number"):
            return False
        return math.isclose(float(first[1]), float(second[1]), rel_tol=1e-5, abs_tol=1e-9)

    return all(close(a, b, i) for one, two in zip(*sides) for i, (a, b) in enumerate(zip(one, two)))


def error_text(outcome):
    text = outcome.get("text")
    if text is None:
        return re.sub(r"^ERROR: ", "", str(outcome.get("message")))
    return ". ".join(str(part) for part in (text, outcome.get("detail")) if part)


def names_next_step(outcome):
    """Static: the hint field says something, or the text says what to do. What the statement quotes does not count, and
    neither do the words that say that there is no way yet."""
    hint = " ".join(str(outcome.get("hint") or "").split())
    if hint and not hint.startswith(NO_WAY):
        return True
    text = " ".join(f"{error_text(outcome)}. {hint}".split())
    text = re.sub(r"(?:Hint: )?" + re.escape(NO_WAY), "", re.sub(r'"[^"]*"', '"..."', text))
    return bool(STEP.search(text))


def hint_field(outcome):
    hint = str(outcome.get("hint") or "")
    return "no way" if hint.startswith(NO_WAY) else "yes" if hint else "no"


def answer(outcome, limit=150):
    if not outcome["ok"]:
        text = f"{outcome.get('state')} {error_text(outcome)}"
    elif "columns" not in outcome:
        text = f"success, count {outcome.get('count')}"
    elif outcome["count"] == 0:
        text = "no rows (" + ", ".join(outcome["columns"]) + ")"
    else:
        rows = ["(" + ", ".join("NULL" if value is None else value for value in row) + ")" for row in outcome["rows"][:3]]
        more = " ..." if outcome["count"] > 3 else ""
        text = f"{outcome['count']} row{'' if outcome['count'] == 1 else 's'}: " + " ".join(rows) + more
    text = printable(text)
    return text if len(text) <= limit else text[:limit].rstrip() + " ..."


def judge(directory):
    """One row for each statement of the corpus, from the files of a run."""
    statements = corpus()
    rows = []
    for script, (kind, groups) in SCRIPTS.items():
        path = directory / f"{script}.jsonl"
        if not path.is_file():
            fail(f"the run has no {script}.jsonl")
        lines = [json.loads(line) for line in path.read_text(encoding="utf-8").split("\n") if line]
        expected = [s for s in statements if s["group"] in groups]
        if [(line["id"], line["sql"]) for line in lines] != [(s["id"], s["sql"]) for s in expected]:
            fail(f"{script}.jsonl does not hold every statement of the corpus, in its order: it is not a complete run of it")
        for statement, line in zip(expected, lines):
            postgres, language, shadow = line["pg"], line["arc"], line["shadow"]
            if statement["via_sql"]:
                verdict = "setup"
            elif equal(statement, postgres, language):
                verdict = "same"
            elif language["ok"]:
                verdict = "follows" if equal(statement, shadow, language) else "silent"
            elif not shadow["ok"] and shadow.get("state") == language.get("state"):
                verdict = "follows"
            else:
                verdict = "refused" if postgres["ok"] else "other-code"
            failed = not language["ok"] and verdict != "setup"
            silent = verdict == "silent"
            rows.append({
                **identity(script, kind, statement),
                "verdict": verdict,
                "postgresql_18": "00000" if postgres["ok"] else printable(postgres.get("state")),
                "language": "00000" if language["ok"] else printable(language.get("state")),
                "next_step": ("yes" if names_next_step(language) else "no") if failed else "-",
                "hint_field": hint_field(language) if failed else "-",
                "postgresql_18_answer": answer(postgres) if silent else "-",
                "language_answer": answer(language) if silent else "-",
                "message": printable(error_text(language)) if failed else "-",
            })
    return rows


def identity(script, kind, statement):
    """The cells of a row that the corpus alone gives."""
    return {"script": script, "id": statement["id"], "set": "setup" if statement["via_sql"] else kind,
            "class": class_of(statement), "volatile": "yes" if "volatile" in statement["tags"] else "no",
            "statement": printable(statement["sql"])}


def verdict_of(row, entries):
    return "listed" if row["verdict"] == "silent" and row["id"] in entries else row["verdict"]


# ------------------------------------------------------------------------------------------------------------------- render


def share(part, whole):
    return f"{part:,} of {whole:,} ({100 * part / whole:.1f}%)" if whole else "0 of 0"


def numbers(rows, entries):
    """For each set: M1 by class, M2, and M3."""
    result = {}
    for kind in SETS:
        part = [row for row in rows if row["set"] == kind]
        steady = [row for row in part if row["volatile"] == "no"]
        by_class = {name: (sum(row["verdict"] == "same" for row in steady if row["class"] == name),
                           sum(row["class"] == name for row in steady)) for name in CLASSES}
        errors = [row for row in part if row["verdict"] in OWN_ERRORS]
        result[kind] = {
            "rows": part,
            "classes": by_class,
            "m1": (sum(match for match, _ in by_class.values()), len(steady)),
            "m2": (sum(row["next_step"] == "yes" for row in errors), len(errors)),
            "hint_field": sum(row["hint_field"] != "no" for row in errors),
            "no_way": sum(row["hint_field"] == "no way" for row in errors),
            "m3": sum(verdict_of(row, entries) == "silent" for row in part),
        }
    return result


def numbers_table(measured):
    out = ["| number | what it counts | agent scripts | probes | target |\n| --- | --- | ---: | ---: | ---: |\n",
           "| M1 | non-volatile statements that match PostgreSQL 18 as written | "
           + " | ".join(share(*measured[kind]["m1"]) for kind in SETS) + " | not set |\n",
           "| M2 | errors of the language's own that name a next step | "
           + " | ".join(share(*measured[kind]["m2"]) for kind in SETS) + " | 100% |\n",
           "| M3 | silent differences | " + " | ".join(f"{measured[kind]['m3']:,}" for kind in SETS) + " | 0 |\n\n"]
    return "".join(out)


def shown(text, limit=SHOWN):
    """A text as one cell of a table: cut where it is long, as a code span."""
    text = " ".join(text.split())
    if len(text) > limit:
        text = text[:limit].rstrip() + " ..."
    ticks = "`"
    while ticks in text:
        ticks += "`"
    pad = " " if text.startswith("`") or text.endswith("`") else ""
    return ticks + pad + text.replace("|", "\\|") + pad + ticks


def message_group(message):
    """A message with what differs from statement to statement taken out, so that equal causes group together."""
    text = re.sub(r"\s*\(line \d+, column \d+\)", "", " ".join(message.split()))
    text = re.sub(r'"[^"]*"', '"..."', text)
    text = re.sub(r"'[^']*'", "'...'", text)
    return text if len(text) <= 150 else text[:147] + "..."


def load():
    about = {row["key"]: row["value"] for row in read_table(RESULT / "run.tsv")}
    return about, read_table(RESULT / "statements.tsv"), listed()


def render():
    about, rows, entries = load()
    measured = numbers(rows, entries)
    engine, run = about["engine"], about["CI run"]
    engine_cell = f"[`{engine[:10]}`]({FORK}/commit/{engine})" if re.fullmatch(r"[0-9a-f]{40}", engine) else engine
    run_cell = f"[{run.rsplit('/', 1)[-1]}]({run})" if run.startswith("https://") else run
    counted = [row for row in rows if row["set"] in SETS]

    out = ["<!--\nWritten by .github/scripts/pg_agent_measure.py from postgresw/src/test/resources/agentmeasure. Do not edit by "
           "hand.\n-->\n\n",
           "# Agent-written statements on the `postgresql` language\n\n",
           "Statements that an LLM wrote ran on PostgreSQL 18 and on the `postgresql` language of this fork, both over the "
           "PostgreSQL protocol, as written: no replacement statement and no repair. The agent scripts are an agent memory "
           "store and a small web application, with schema creation, writes, queries, transactions, schema discovery, and "
           "usual mistakes. The probes try single features and known cases. This page holds the numbers of one run. Fork only "
           "(`humemai/arcadedb`, a prototype).\n\n",
           "| | |\n| --- | --- |\n",
           f"| PostgreSQL | {about['PostgreSQL'].split(' on ')[0]}, `C` collation, time zone UTC |\n",
           f"| engine | {engine_cell} |\n",
           f"| CI run | {run_cell} |\n",
           f"| date | {about['date'][:10]} (UTC) |\n\n",
           "## Numbers\n\n", numbers_table(measured),
           "## M1 by class\n\nOne refused `CREATE TABLE` hides every later statement on its table, so the classes are counted "
           "apart.\n\n| class | agent scripts | probes |\n| --- | ---: | ---: |\n"]
    for name in CLASSES:
        out.append(f"| {name} | " + " | ".join(share(*measured[kind]["classes"][name]) for kind in SETS) + " |\n")
    out.append("| all | " + " | ".join(share(*measured[kind]["m1"]) for kind in SETS) + " |\n\n")

    out.append("## Every statement, by verdict\n\nVolatile statements are included here.\n\n| verdict | meaning | agent scripts "
               "| probes |\n| --- | --- | ---: | ---: |\n")
    for key, (title, meaning) in VERDICTS.items():
        count = [sum(verdict_of(row, entries) == key for row in measured[kind]["rows"]) for kind in SETS]
        out.append(f"| {title} | {meaning} | {count[0]:,} | {count[1]:,} |\n")
    out.append("| all | | " + " | ".join(f"{len(measured[kind]['rows']):,}" for kind in SETS) + " |\n\n")

    silent = [row for row in counted if verdict_of(row, entries) == "silent"]
    out.append(f"## M3: silent differences\n\n{len(silent)} statements succeed where PostgreSQL answers otherwise or refuses, "
               "and are not in the list of kept differences. The rows of the agent scripts come first.\n\n")
    if silent:
        out.append("| id | statement | PostgreSQL 18 | `postgresql` |\n| --- | --- | --- | --- |\n")
        for row in silent:
            out.append(f"| {row['id']} | {shown(row['statement'])} | {shown(row['postgresql_18_answer'], 160)} | "
                       f"{shown(row['language_answer'], 160)} |\n")
        out.append("\n")

    kept = [row for row in counted if verdict_of(row, entries) == "listed"]
    out.append(f"## Kept differences\n\n{len(kept)} statements succeed with another answer on purpose and are not in M3. Each "
               "has a line in [listed.tsv](../postgresw/src/test/resources/agentmeasure/listed.tsv) that names its place on the "
               "[differences page](postgresql-differences.md). A difference that is planned to change is not in the list.\n\n")
    if kept:
        out.append("| id | statement | listed as |\n| --- | --- | --- |\n")
        for row in kept:
            out.append(f"| {row['id']} | {shown(row['statement'])} | {entries[row['id']]['listed_as']} |\n")
        out.append("\n")

    quiet = collections.Counter(message_group(row["message"]) for row in counted
                                if row["verdict"] in OWN_ERRORS and row["next_step"] == "no")
    total = sum(quiet.values())
    hinted, no_way = (sum(measured[kind][key] for kind in SETS) for key in ("hint_field", "no_way"))
    errors = sum(measured[kind]["m2"][1] for kind in SETS)
    out.append(f"## M2: errors that name no next step\n\n{total:,} errors of the language name no next step. Of all {errors:,} "
               f"errors of its own, {hinted:,} use the hint field of the protocol, and {no_way:,} of those hints say that there "
               "is no way yet. Names and values are taken out of the messages.\n\n| message | statements |\n| --- | ---: |\n")
    top = sorted(quiet.items(), key=lambda item: (-item[1], item[0]))[:TOP_MESSAGES]
    for message, count in top:
        out.append(f"| {shown(message, 150)} | {count:,} |\n")
    rest = total - sum(count for _, count in top)
    if rest:
        out.append(f"| every other one | {rest:,} |\n")
    out.append(f"| all | {total:,} |\n\n")

    setups = sum(row["set"] == "setup" for row in rows)
    out.append(
        "## Rules\n\n"
        "- **Match.** The same column names, rows (in order where the statement has `ORDER BY`), and count of a write, or the "
        "same SQLSTATE. Type names are not compared. JSON and numbers are compared by value, and vector distances within a "
        "relative difference of 0.00001. For `EXPLAIN` only the column names are compared.\n"
        "- **Volatile.** A statement whose values depend on the clock, a random id, the server, or an order that PostgreSQL "
        "does not fix is marked in the [corpus](../postgresw/src/test/resources/agentmeasure/corpus.sql). It counts in M2 and "
        "M3, not in M1, and only its "
        "column names and row count are compared. Where the values are the server's own, such as its version or its search "
        "path, they are compared all the same.\n"
        "- **Class.** `discovery` reads `information_schema` or `pg_catalog`, `transaction` is a statement of the transaction "
        "script or a session statement, and the others go by their first word: `schema`, `write`, or `read`.\n"
        "- **After an earlier failure.** A second PostgreSQL database runs only what the language ran, and follows its failed "
        "transaction blocks. An answer that differs from PostgreSQL's and equals the answer of that database, or an error with "
        "its code, comes from an earlier statement. It does not match in M1, and counts in neither M2 nor M3.\n"
        "- **M2.** The error has a hint field, or its text has `Hint:`, or a clause that starts with use, write, send, cast, "
        f"declare, retry, run, quote, add, remove, leave, or repeat, or it asks to retry. The words \"{NO_WAY}\" are not a "
        "next step. The check reads the text only: it does not say that the step works.\n"
        f"- **Not counted.** {setups} setup steps of the probes run in ArcadeDB `sql`.\n\n"
        "## Write this page again\n\nDownload the artifact `pg-agent-measure` of a run of the workflow "
        "[pg-agent-measure](../.github/workflows/pg-agent-measure.yml) and run:\n\n"
        "```sh\nuv run --no-project python .github/scripts/pg_agent_measure.py collect DOWNLOADED_DIRECTORY\n```\n\n"
        "CI fails when this page and the [result](../postgresw/src/test/resources/agentmeasure/result) disagree. It does not "
        "fail because an answer changed: each run prints its own numbers and the statements whose verdict differs from this "
        "page in its job summary.\n")
    return "".join(out)


# ------------------------------------------------------------------------------------------------------------------ commands


def collect(directory):
    rows = judge(directory)
    about = {row["key"]: row["value"] for row in read_table(directory / "about.tsv")}
    missing = [key for key in ABOUT if key not in about]
    if missing:
        fail(f"about.tsv does not say: {', '.join(missing)}")
    RESULT.mkdir(parents=True, exist_ok=True)
    write_table(RESULT / "run.tsv", ("key", "value"), [{"key": key, "value": about[key]} for key in ABOUT])
    write_table(RESULT / "statements.tsv", COLUMNS, rows)
    PAGE.write_text(render(), encoding="utf-8")
    check()


def summary(directory):
    rows = judge(directory)
    entries = listed()
    print("## Agent measure of this run\n")
    print(numbers_table(numbers(rows, entries)), end="")
    if not (RESULT / "statements.tsv").is_file():
        print("There is no committed result to compare with.")
        return
    committed = {row["id"]: row for row in read_table(RESULT / "statements.tsv")}
    changed = [(row, committed.get(row["id"])) for row in rows
               if committed.get(row["id"]) is None or verdict_of(row, entries) != verdict_of(committed[row["id"]], entries)]
    print(f"{len(changed)} statements have another verdict than in the committed result "
          "(docs/postgresql-agent-measure.md).\n")
    if changed:
        print("| id | committed | this run | statement | message or answer |\n| --- | --- | --- | --- | --- |")
        for row, before in changed:
            detail = row["language_answer"] if row["language_answer"] != "-" else row["message"]
            old = VERDICTS.get(verdict_of(before, entries), ("setup",))[0] if before else "not there"
            new = VERDICTS.get(verdict_of(row, entries), ("setup",))[0]
            print(f"| {row['id']} | {old} | {new} | {shown(row['statement'])} | {shown(detail)} |")


def check():
    _, rows, _ = load()
    expected = [identity(script, kind, statement) for script, (kind, groups) in SCRIPTS.items()
                for statement in corpus() if statement["group"] in groups]
    if [{key: row[key] for key in expected[0]} for row in rows] != expected:
        fail("the result is not one of the committed corpus: collect a new run")
    unknown = sorted({row["verdict"] for row in rows} - set(VERDICTS) - {"setup"})
    if unknown:
        fail(f"the result has unknown verdicts: {unknown}")
    if not PAGE.is_file() or PAGE.read_text(encoding="utf-8") != render():
        fail(f"{PAGE.relative_to(ROOT)} is not the page that the result gives: write it again with `write`")
    print(f"the page and the result agree: {len(rows)} statements")


def main(arguments):
    if len(arguments) == 2 and arguments[0] == "collect":
        collect(pathlib.Path(arguments[1]))
    elif len(arguments) == 2 and arguments[0] == "summary":
        summary(pathlib.Path(arguments[1]))
    elif arguments == ["write"]:
        PAGE.write_text(render(), encoding="utf-8")
    elif arguments == ["check"]:
        check()
    else:
        fail("usage: pg_agent_measure.py collect RUN_DIRECTORY | summary RUN_DIRECTORY | write | check")


if __name__ == "__main__":
    main(sys.argv[1:])
