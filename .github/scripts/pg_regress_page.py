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

"""Writes the page docs/postgresql-regression.md from the result of a run of the regression harness.

Fork only (humemai/arcadedb, PostgreSQL-style SQL prototype, fork issue #120).

    pg_regress_page.py collect RUN_DIRECTORY   read the files of a run, keep what the page needs in the result directory, and
                                               write the page
    pg_regress_page.py write                   write the page from the result directory
    pg_regress_page.py check                   fail unless the page is the one that the result directory gives, every row adds
                                               up, and every wrong statement has a cause with an issue

RUN_DIRECTORY holds the two sides of one run: e2e/target/pgregress, or the artifact `pgregress-results` of a CI run. Its
folder `arcadedb` gives the counts. Its folder `postgresql`, the run with PostgreSQL on both sides, has to hold only passed and
out of scope, or `collect` fails. The only statements that the result directory and the page copy from PostgreSQL's files are
the wrong ones; ATTRIBUTIONS.md names both, with the licence.

A wrong statement gets the cause of the first rule of causes.tsv that matches it, so a new run is grouped by the same rules,
and a statement that no rule matches makes `check` fail.
"""

import collections
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "e2e/src/test/resources/pgregress"
RESULT = RESOURCES / "result"
CAUSES = RESOURCES / "causes.tsv"
PAGE = ROOT / "docs/postgresql-regression.md"

FORK = "https://github.com/humemai/arcadedb"
SOURCES = "https://github.com/postgres/postgres/blob/REL_18_7/src/test/regress/sql"

VERDICTS = ("passed", "refused_0a000", "refused_other", "wrong", "blocked", "out_of_scope")
TITLES = {
    "passed": "passed",
    "refused_0a000": "refused, `0A000`",
    "refused_other": "refused, other code",
    "wrong": "wrong",
    "blocked": "blocked",
    "out_of_scope": "out of scope",
}
FILE_COLUMNS = ("file", "statements", *VERDICTS, "on_copied_table", "psql_commands", "not_sent", "failure")
ABOUT = ("side under test", "PostgreSQL", "regression files", "engine", "CI run", "date")
# how many reasons the page lists; the rest is one row, so that each table adds up
TOP = {"refused_0a000": 25, "refused_other": 20, "missing_function": 25, "blocked": 20}
# the three reasons of the rules for an entry of the out-of-scope list
SCOPE_REASONS = {"1": "PostgreSQL does not fix the answer", "2": "PostgreSQL internals", "3": "server administration"}
# the entries of the list for the C code of the suite, whose library the harness does not build
C_ENTRIES = ("c-function", "c-function-call")
C_COLUMNS = ("file", "statements", "defines", "calls", "wrong", "postgresql_errors", "expected_errors")
# a statement on the page is cut after this many characters, so that GitHub still renders the page
SHOWN = 120
# REUSE-IgnoreStart
STATEMENT_NOTICE = """# The statements in this file are from PostgreSQL's regression tests, tag REL_18_7; ATTRIBUTIONS.md has the notice.
#
# SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
# SPDX-FileCopyrightText: 1996-2026 PostgreSQL Global Development Group
# SPDX-FileCopyrightText: 1994 The Regents of the University of California
# SPDX-License-Identifier: Apache-2.0 AND PostgreSQL
"""
# REUSE-IgnoreEnd


def fail(message):
    sys.exit("pg_regress_page: " + message)


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


def write_table(path, header, rows, notice=""):
    text = notice + "\t".join(header) + "\n"
    for row in rows:
        text += "\t".join(str(row[name]) for name in header) + "\n"
    path.write_text(text, encoding="utf-8")


# ------------------------------------------------------------------------------------------------------------------ collect


def summary_tables(text):
    """The tables of summary.md, each as a list of rows of cells."""
    tables, current = [], []
    for line in text.split("\n"):
        if line.startswith("|"):
            current.append([cell.strip() for cell in line.strip().strip("|").split("|")])
        elif current:
            tables.append(current)
            current = []
    if current:
        tables.append(current)
    return [[table[0], *table[2:]] for table in tables]


def reason_of(kind, reason):
    """A reason with what differs from statement to statement taken out, so that equal causes group together."""
    text = reason.replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\")
    if kind == "blocked":
        text = re.sub(r"^(uses|writes) [^:]+: ", "", text)
        text = re.sub(r"^its information_schema differs:.*", "the copy differs from the table in information_schema", text)
        text = re.sub(r"^column \S+ of type (.*) cannot be held", r"a column of type \1 cannot be held", text)
        text = re.sub(r"^the value .* of type (.*?) cannot be held.*", r"a value of type \1 cannot be held", text)
        # the first thing the copy lacks, without the name of its column
        text = re.sub(r"^the copy does not carry (a default, identity or generated column|[^,]+).*",
                      r"for a write: the copy does not carry \1", text)
        text = re.sub(r"^a write is not judged: the table has (a default, identity or generated column|[^,]+).*",
                      r"for a write: a table of the language has \1", text)
        text = re.sub(r"^(a query of information_schema about an object that differs between the sides): .*", r"\1", text)
    else:
        text = re.split(r" \(line \d+, column \d+\)", text)[0]
        text = text.split(". Hint:")[0]
        text = re.sub(r"^function ([^\s(]+)\(.*\) does not exist", r"function does not exist", text)
        text = re.sub(r"^operator does not exist: .*", "operator does not exist", text)
    text = re.sub(r'"[^"]*"', '"..."', text)
    text = re.sub(r"'[^']*'", "'...'", text)
    text = re.sub(r"\$\d+", "$n", text)
    text = re.sub(r"(?<![A-Za-z_0-9])\d+(?![A-Za-z_0-9])", "n", text)
    text = text.replace("`", "'").strip()
    return text if len(text) <= 160 else text[:157] + "..."


def rules():
    compiled = []
    for row in read_table(CAUSES):
        tests = {}
        for column, key in (("postgresql_sqlstate_re", "postgresql_sqlstate"), ("tested_sqlstate_re", "tested_sqlstate"),
                            ("file_re", "file"), ("reason_re", "reason"), ("statement_re", "statement")):
            if row[column] != "-":
                tests[key] = re.compile(row[column], re.IGNORECASE)
        compiled.append((row["cause"], tests))
    return compiled


def cause_of(line, compiled):
    for cause, tests in compiled:
        if all((pattern.fullmatch(line[key]) if key.endswith("sqlstate") or key == "file" else pattern.search(line[key]))
               for key, pattern in tests.items()):
            return cause
    return "unclassified"


def printable(text):
    """A statement as the result keeps it: no raw control character."""
    return re.sub(r"[\x00-\x1f\x7f]", lambda match: f"\\x{ord(match.group()):02x}", text)


def both_sides(run):
    """The counts of the run with PostgreSQL on both sides, which may hold only passed and out of scope."""
    tables = summary_tables((run / "summary.md").read_text(encoding="utf-8"))
    counts, commands = tables[0], tables[2]
    total = dict(zip(("statements", *VERDICTS), (int(cell) for cell in counts[-1][1:8])))
    other = sum(total[key] for key in ("refused_0a000", "refused_other", "wrong", "blocked"))
    failed = [row[0] for row in commands[1:] if row[5]]
    if other or failed:
        fail(f"PostgreSQL on both sides: {other} statements neither passed nor out of scope, files that failed: {failed}")
    return {"files": len(commands) - 1, **total}


def collect(root):
    run = root / "arcadedb"
    reference = both_sides(root / "postgresql")
    summary = (run / "summary.md").read_text(encoding="utf-8")
    about = {}
    for line in summary.split("\n"):
        if line.startswith("- ") and ": " in line:
            key, value = line[2:].split(": ", 1)
            about[key] = value
    missing = [key for key in ABOUT if key not in about]
    if missing:
        fail(f"summary.md does not say: {', '.join(missing)}")
    if about["side under test"] != "arcadedb":
        fail("the page is about the run on ArcadeDB, not " + about["side under test"])
    counts_table, _, commands_table = summary_tables(summary)
    counted = {row[0].strip("`"): [int(cell) for cell in row[1:]] for row in counts_table[1:]}
    commands = {row[0].strip("`"): row for row in commands_table[1:]}

    compiled = rules()
    files, wrong, reasons, entries, library = [], [], collections.Counter(), collections.Counter(), []
    for name, row in commands.items():
        entry = {"file": name, "psql_commands": int(row[1]), "not_sent": int(row[2]), "failure": row[5]}
        if entry["failure"]:
            files.append({**entry, "statements": 0, **dict.fromkeys(VERDICTS, 0), "on_copied_table": 0})
            continue
        lines = read_run_file(run / (name + ".tsv"))
        totals = collections.Counter()
        for line in lines:
            verdict = line["verdict"].replace(" ", "_")
            if verdict == "refused":
                verdict = "refused_0a000" if line["tested_sqlstate"] == "0A000" else "refused_other"
            if verdict not in VERDICTS:
                fail(f"{name}, line {line['line']}: unknown verdict {line['verdict']}")
            totals[verdict] += 1
            totals["on_copied_table"] += line["on_copied_table"] == "yes"
            if verdict == "wrong":
                wrong.append({"file": name, "line": int(line["line"]), "postgresql_sqlstate": line["postgresql_sqlstate"],
                              "tested_sqlstate": line["tested_sqlstate"], "cause": cause_of(line, compiled),
                              "statement": printable(line["statement"])})
            elif verdict in ("refused_0a000", "refused_other", "blocked"):
                reasons[(verdict, reason_of(verdict, line["reason"]))] += 1
            elif verdict == "out_of_scope":
                entries[line["out_of_scope_entry"]] += 1
            function = re.match(r"function ([^\s(]+)\(.*\) does not exist", line["reason"])
            if verdict == "refused_other" and function:
                reasons[("missing_function", function.group(1))] += 1
        record = {**entry, "statements": len(lines), **{key: totals[key] for key in VERDICTS},
                  "on_copied_table": totals["on_copied_table"]}
        harness = counted.get(name)
        own = [record["statements"], *(record[key] for key in VERDICTS), record["on_copied_table"]]
        if harness != own:
            fail(f"{name}: summary.md says {harness}, the lines of the file give {own}")
        files.append(record)
        scoped = collections.Counter(line["out_of_scope_entry"] for line in lines)
        defines, calls = (scoped[entry] for entry in C_ENTRIES)
        if defines or calls or any("regress.so" in line["statement"] for line in lines):
            library.append({"file": name, "statements": len(lines), "defines": defines, "calls": calls, "wrong": totals["wrong"],
                            "postgresql_errors": int(row[3]), "expected_errors": int(row[4])})

    total = [sum(record[key] for record in files) for key in ("statements", *VERDICTS, "on_copied_table")]
    if total != counted.get("all"):
        fail(f"all: summary.md says {counted.get('all')}, the files give {total}")

    reasons_of_scope = {row[0].strip("`"): row[1] for row in summary_tables(summary)[1][1:]}
    RESULT.mkdir(parents=True, exist_ok=True)
    write_table(RESULT / "run.tsv", ("key", "value"), [{"key": key, "value": about[key]} for key in ABOUT]
                + [{"key": "both sides, " + key, "value": value} for key, value in reference.items()])
    write_table(RESULT / "c_library.tsv", C_COLUMNS, library)
    write_table(RESULT / "files.tsv", FILE_COLUMNS, files)
    order = {record["file"]: position for position, record in enumerate(files)}
    write_table(RESULT / "wrong.tsv", ("file", "line", "postgresql_sqlstate", "tested_sqlstate", "cause", "statement"),
                sorted(wrong, key=lambda row: (order[row["file"]], row["line"], row["postgresql_sqlstate"])), STATEMENT_NOTICE)
    write_table(RESULT / "reasons.tsv", ("kind", "statements", "reason"),
                [{"kind": kind, "statements": count, "reason": reason}
                 for (kind, reason), count in sorted(reasons.items(), key=lambda item: (item[0][0], -item[1], item[0][1]))])
    write_table(RESULT / "out_of_scope.tsv", ("entry", "statements", "reason"),
                [{"entry": entry, "statements": count, "reason": reasons_of_scope[entry]}
                 for entry, count in sorted(entries.items(), key=lambda item: (-item[1], item[0]))])
    unclassified = sum(1 for row in wrong if row["cause"] == "unclassified")
    print(f"collected {len(files)} files, {total[0]} statements, {len(wrong)} wrong, {unclassified} of them unclassified")


def read_run_file(path):
    lines = path.read_text(encoding="utf-8").split("\n")
    header = lines[0].split("\t")
    rows = []
    for line in lines[1:]:
        if not line:
            continue
        cells = line.split("\t")
        if len(cells) != len(header):
            fail(f"{path.name}: a line with {len(cells)} cells for {len(header)} columns")
        rows.append(dict(zip(header, cells)))
    return rows


# ------------------------------------------------------------------------------------------------------------------- render


def number(value):
    return f"{value:,}"


def code(text):
    return "`" + text.replace("|", "\\|") + "`"


def share(part, whole):
    return f"{100 * part / whole:.1f}%"


def load():
    about = {row["key"]: row["value"] for row in read_table(RESULT / "run.tsv")}
    files = read_table(RESULT / "files.tsv")
    for record in files:
        for key in FILE_COLUMNS[1:-1]:
            record[key] = int(record[key])
    wrong = read_table(RESULT / "wrong.tsv")
    reasons = read_table(RESULT / "reasons.tsv")
    entries = read_table(RESULT / "out_of_scope.tsv")
    library = read_table(RESULT / "c_library.tsv")
    causes = {}
    for row in read_table(CAUSES):
        known = causes.setdefault(row["cause"], (row["issue"], row["title"]))
        if row["issue"] != "-" and known == ("-", "-"):
            causes[row["cause"]] = (row["issue"], row["title"])
    return about, files, wrong, reasons, entries, causes, library


def shown(statement):
    """A statement as one cell of a table: on one line, cut where it is long, as a code span."""
    text = re.sub(r"\\(\\|t|r|n)", lambda match: "\\" if match.group(1) == "\\" else " ", statement)
    text = " ".join(text.split())
    if len(text) > SHOWN:
        text = text[:SHOWN].rstrip() + " ..."
    ticks = "`"
    while ticks in text:
        ticks += "`"
    pad = " " if text.startswith("`") or text.endswith("`") else ""
    return ticks + pad + text.replace("|", "\\|") + pad + ticks


def problems(files, wrong, reasons, entries, causes):
    """Where the result does not add up."""
    found = []
    for record in files:
        verdicts = sum(record[key] for key in VERDICTS)
        if verdicts != record["statements"]:
            found.append(f"{record['file']}: {verdicts} verdicts for {record['statements']} statements")
    listed = collections.Counter(row["file"] for row in wrong)
    for record in files:
        if listed.pop(record["file"], 0) != record["wrong"]:
            found.append(f"{record['file']}: the list of wrong statements does not have {record['wrong']} lines")
    if listed:
        found.append("wrong statements of files that have no row: " + ", ".join(sorted(listed)))
    for kind in ("refused_0a000", "refused_other", "blocked"):
        grouped = sum(int(row["statements"]) for row in reasons if row["kind"] == kind)
        total = sum(record[kind] for record in files)
        if grouped != total:
            found.append(f"{kind}: the reasons cover {grouped} statements of {total}")
    scoped = sum(int(row["statements"]) for row in entries)
    total = sum(record["out_of_scope"] for record in files)
    if scoped != total:
        found.append(f"out of scope: the entries cover {scoped} statements of {total}")
    for cause, count in collections.Counter(row["cause"] for row in wrong).items():
        issue, _ = causes.get(cause, ("-", "-"))
        if not issue.isdigit():
            found.append(f"{count} wrong statements of the cause {cause!r} have no issue in causes.tsv")
    return found


def reason_table(out, reasons, kind, total, column):
    rows = [row for row in reasons if row["kind"] == kind]
    top = rows[:TOP[kind]]
    out.append(f"| {column} | statements |\n| --- | ---: |\n")
    for row in top:
        out.append(f"| {code(row['reason'])} | {number(int(row['statements']))} |\n")
    rest = total - sum(int(row["statements"]) for row in top)
    if rest:
        out.append(f"| every other one | {number(rest)} |\n")
    out.append(f"| all | {number(total)} |\n\n")


def render():
    about, files, wrong, reasons, entries, causes, library = load()
    total = {key: sum(record[key] for record in files) for key in FILE_COLUMNS[1:-1]}
    finished = [record for record in files if not record["failure"]]
    unfinished = [record for record in files if record["failure"]]
    statements = total["statements"]
    tag, commit = about["regression files"].split(", commit ")
    engine = about["engine"]
    run = about["CI run"]

    # REUSE-IgnoreStart
    out = ["<!--\nWritten by .github/scripts/pg_regress_page.py from e2e/src/test/resources/pgregress/result. Do not edit by hand.\n\n"
           "The quoted statements are from PostgreSQL's regression tests, under the PostgreSQL License.\n\n"
           "SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)\n"
           "SPDX-FileCopyrightText: 1996-2026 PostgreSQL Global Development Group\n"
           "SPDX-FileCopyrightText: 1994 The Regents of the University of California\n"
           "SPDX-License-Identifier: Apache-2.0 AND PostgreSQL\n-->\n\n",
           # REUSE-IgnoreEnd
           "# PostgreSQL's regression files on the `postgresql` language\n\n",
           "Each statement of PostgreSQL's regression files ran on PostgreSQL and on the `postgresql` language of this fork, and got "
           "one verdict by the [rules](postgresql-regression-rules.md). This page holds the counts of one run. Fork only "
           "(`humemai/arcadedb`, a prototype).\n\n",
           "| | |\n| --- | --- |\n",
           f"| PostgreSQL | {about['PostgreSQL'].split(' on ')[0]}, `C` collation |\n",
           f"| regression files | tag `{tag}`, commit `{commit[:10]}`, {number(len(files))} files |\n",
           f"| engine | [`{engine[:10]}`]({FORK}/commit/{engine}) |\n",
           f"| CI run | [{run.rsplit('/', 1)[-1]}]({run}) |\n",
           f"| date | {about['date'][:10]} (UTC) |\n\n",
           "## Counts\n\n",
           "| verdict | statements | share of all statements |\n| --- | ---: | ---: |\n"]
    for key in VERDICTS:
        out.append(f"| {TITLES[key]} | {number(total[key])} | {share(total[key], statements)} |\n")
    out.append(f"| all | {number(statements)} | 100% |\n\n")
    out.append("| verdict | rule, in short |\n| --- | --- |\n"
               "| passed | the same result on both, or the same SQLSTATE on both |\n"
               "| refused | an error where PostgreSQL succeeds, or `0A000` where PostgreSQL raises another error |\n"
               "| wrong | another result or other table contents, success where PostgreSQL raises an error, another code than "
               "PostgreSQL (`0A000` aside), a crash, or a timeout |\n"
               "| blocked | not judged: the two sides cannot be made to agree on what the statement uses or depends on |\n"
               "| out of scope | one of the entries of a closed list |\n\n")
    judged = sum(total[key] for key in ("passed", "refused_0a000", "refused_other", "wrong"))
    out.append(f"{number(judged)} statements were judged, and {number(total['on_copied_table'])} of them on a table that the "
               "harness had copied from PostgreSQL into ArcadeDB through the engine's own API. "
               f"{number(total['psql_commands'])} `psql` commands never reach a server and are not statements, and "
               f"{number(total['not_sent'])} statements that `psql` does not send are not counted either.\n\n")
    out.append("A check of the harness: in the same CI run, with a second PostgreSQL server as the side under test, all "
               f"{number(int(about['both sides, files']))} files gave only passed "
               f"({number(int(about['both sides, passed']))} statements) and out of scope "
               f"({number(int(about['both sides, out_of_scope']))}).\n\n")
    if unfinished:
        out.append(f"The run of {len(unfinished)} files did not finish, and no count of theirs is on this page: "
                   + ", ".join(f"`{record['file']}`" for record in unfinished) + ".\n\n")

    grouped = collections.defaultdict(list)
    for row in wrong:
        grouped[row["cause"]].append(row)
    order = sorted(grouped, key=lambda cause: (-len(grouped[cause]), cause))
    out.append("## Wrong statements, by cause\n\n")
    out.append(f"{number(len(wrong))} statements are wrong, in {len(order)} groups of one cause each. Each group has the issue that "
               "tracks it, with an example. A statement is named by its file and the line of the file where it starts, and is "
               f"quoted on one line, cut after {SHOWN} characters. The two codes are the SQLSTATE of PostgreSQL and the one of "
               "`postgresql`; `00000` is success.\n\n")
    out.append("| cause | issue | wrong statements |\n| --- | --- | ---: |\n")
    for cause in order:
        issue, title = causes.get(cause, ("-", cause))
        out.append(f"| {title} | [#{issue}]({FORK}/issues/{issue}) | {number(len(grouped[cause]))} |\n")
    out.append(f"| all | | {number(len(wrong))} |\n\n")
    for cause in order:
        issue, title = causes.get(cause, ("-", cause))
        out.append(f"<details>\n<summary>#{issue}: {len(grouped[cause])} statements</summary>\n\n{title}\n\n")
        out.append("| file and line | codes | statement |\n| --- | --- | --- |\n")
        for row in grouped[cause]:
            place = f"[{row['file']}:{row['line']}]({SOURCES}/{row['file']}.sql#L{row['line']})"
            out.append(f"| {place} | {row['postgresql_sqlstate']} / {row['tested_sqlstate']} | {shown(row['statement'])} |\n")
        out.append("\n</details>\n\n")

    out.append("## Why statements are refused\n\nThe messages are the language's own, with names, values, and numbers taken "
               "out.\n\n### With `0A000`\n\n")
    reason_table(out, reasons, "refused_0a000", total["refused_0a000"], "message")
    out.append("### With another code\n\n")
    reason_table(out, reasons, "refused_other", total["refused_other"], "message")
    functions = [row for row in reasons if row["kind"] == "missing_function"]
    out.append("The functions that are missing most often, among the statements refused with another code:\n\n")
    reason_table(out, reasons, "missing_function", sum(int(row["statements"]) for row in functions), "function")
    typed = {name: sum(int(row["statements"]) for row in reasons
                       if row["kind"] == "blocked" and row["reason"] == f"a column of type {name} cannot be held")
             for name in ("name", "character(n)")}
    out.append("## Why statements are blocked\n\nA statement with several reasons is counted under the first "
               f"one. {number(typed['name'])} statements are blocked first by a column of type `name`, and "
               f"{number(typed['character(n)'])} by a column of type `char(n)`; whether the language gets the two types is "
               f"[open]({FORK}/issues/119).\n\n")
    reason_table(out, reasons, "blocked", total["blocked"], "reason")
    out.append("## Out of scope\n\nThe [list](../e2e/src/test/resources/pgregress/out-of-scope.tsv) is closed, and a change "
               "to it goes through a pull request.\n\n| reason | statements |\n| --- | ---: |\n")
    for key, reason in SCOPE_REASONS.items():
        out.append(f"| {reason} | {number(sum(int(row['statements']) for row in entries if row['reason'] == key))} |\n")
    out.append(f"| all | {number(total['out_of_scope'])} |\n\n| entry of the list | reason | statements |\n| --- | --- | ---: |\n")
    for row in entries:
        out.append(f"| `{row['entry']}` | {SCOPE_REASONS[row['reason']]} | {number(int(row['statements']))} |\n")
    out.append(f"| all | | {number(total['out_of_scope'])} |\n\n")

    out.append("## What the missing C library changes\n\n`pg_regress` builds a library of C functions, `regress.so`, for the "
               "suite. The harness does not. A statement that defines a function of that code, or calls one, is out of scope. "
               "What else changes is that PostgreSQL itself fails where the suite expects success, when a type or another object "
               "needs such a function; both sides are then compared with that error. The table has every file that defines or "
               "calls such a function. Compare the two last columns: they are not the same measure, because the suite runs all "
               "files in one database with its own settings.\n\n| file | statements | defines C code | calls C code | wrong | "
               "errors of PostgreSQL | `ERROR` lines the suite expects |\n| --- |" + " ---: |" * 6 + "\n")
    for row in library:
        out.append(f"| `{row['file']}` | " + " | ".join(number(int(row[key])) for key in C_COLUMNS[1:]) + " |\n")
    sums = {key: sum(int(row[key]) for row in library) for key in C_COLUMNS[1:]}
    out.append("| these files | " + " | ".join(number(sums[key]) for key in C_COLUMNS[1:]) + " |\n\n")
    out.append("What differs in four of them, read from the statements on which PostgreSQL fails:\n\n"
               "- `create_type`: the input and output functions of its new types are C code, so the types are not completed, and "
               "statements about them fail on PostgreSQL, many with `42704` (undefined object).\n"
               "- `conversion`: `test_enc_setup` and `test_enc_conversion` are C code, and the calls of `test_conv`, which the "
               "file tests the conversions with, fail on PostgreSQL with `42883` (undefined function).\n"
               "- `misc_functions`: the functions of the library are not made, so calls of them fail with `42883`.\n"
               "- `psql`: it defines one such function. Part of its difference has another cause: it reads back files that `psql` "
               "would have written, and the harness writes none (`58P01`).\n\n")

    out.append("## By file\n\n| file | statements | " + " | ".join(TITLES[key] for key in VERDICTS)
               + " |\n| --- |" + " ---: |" * (len(VERDICTS) + 1) + "\n")
    for record in finished:
        out.append(f"| `{record['file']}` | " + " | ".join(number(record[key]) for key in ("statements", *VERDICTS)) + " |\n")
    out.append("| all | " + " | ".join(number(total[key]) for key in ("statements", *VERDICTS)) + " |\n\n")
    out.append("## Write this page again\n\n```sh\n"
               ".github/scripts/pg_regress.sh --subject postgresql all\n"
               ".github/scripts/pg_regress.sh --skip-build all\n"
               "uv run --no-project python .github/scripts/pg_regress_page.py collect e2e/target/pgregress\n"
               "```\n\n"
               "CI fails when this page and the [result](../e2e/src/test/resources/pgregress/result) disagree.\n\n"
               "## Licence of the quoted statements\n\nThe statements quoted above are from `src/test/regress/sql` of PostgreSQL "
               "at tag `REL_18_7`, under the PostgreSQL License. The notice is in [`ATTRIBUTIONS.md`](../ATTRIBUTIONS.md) and in "
               "[`LICENSES/PostgreSQL.txt`](../LICENSES/PostgreSQL.txt).\n")
    return "".join(out)


def check():
    _, files, wrong, reasons, entries, causes, _ = load()
    found = problems(files, wrong, reasons, entries, causes)
    if not found and PAGE.read_text(encoding="utf-8") != render():
        found.append(f"{PAGE.relative_to(ROOT)} is not the page that the result gives: write it again with `write`")
    if found:
        fail("\n".join(found))
    print(f"the page and the result agree: {len(files)} files, {len(wrong)} wrong statements")


def main(arguments):
    if len(arguments) == 2 and arguments[0] == "collect":
        collect(pathlib.Path(arguments[1]))
        PAGE.write_text(render(), encoding="utf-8")
        check()
    elif arguments == ["write"]:
        PAGE.write_text(render(), encoding="utf-8")
    elif arguments == ["check"]:
        check()
    else:
        fail("usage: pg_regress_page.py collect RUN_DIRECTORY | write | check")


if __name__ == "__main__":
    main(sys.argv[1:])
