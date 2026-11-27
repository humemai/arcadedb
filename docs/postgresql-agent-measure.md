<!--
Written by .github/scripts/pg_agent_measure.py from postgresw/src/test/resources/agentmeasure. Do not edit by hand.
-->

# Agent-written statements on the `postgresql` language

Statements that an LLM wrote ran on PostgreSQL 18 and on the `postgresql` language of this fork, both over the PostgreSQL protocol, as written: no replacement statement and no repair. The agent scripts are an agent memory store and a small web application, with schema creation, writes, queries, transactions, schema discovery, and usual mistakes. The probes try single features and known cases. This page holds the numbers of one run. Fork only (`humemai/arcadedb`, a prototype).

| | |
| --- | --- |
| PostgreSQL | PostgreSQL 18.7 (Debian 18.7-1.pgdg12+2), `C` collation, time zone UTC |
| engine | [`88a246fa7a`](https://github.com/humemai/arcadedb/commit/88a246fa7a8e3e94f7ae5502b8621b0d374ba848) |
| CI run | [46081172657](https://github.com/humemai/arcadedb/actions/runs/46081172657) |
| date | 2026-11-27 (UTC) |

## Numbers

| number | what it counts | agent scripts | probes | target |
| --- | --- | ---: | ---: | ---: |
| M1 | non-volatile statements that match PostgreSQL 18 as written | 129 of 248 (52.0%) | 204 of 331 (61.6%) | not set |
| M2 | errors of the language's own that name a next step | 46 of 53 (86.8%) | 97 of 111 (87.4%) | 100% |
| M3 | silent differences | 0 | 0 | 0 |

## M1 by class

One refused `CREATE TABLE` hides every later statement on its table, so the classes are counted apart.

| class | agent scripts | probes |
| --- | ---: | ---: |
| schema | 19 of 33 (57.6%) | 60 of 81 (74.1%) |
| write | 25 of 50 (50.0%) | 41 of 58 (70.7%) |
| read | 63 of 114 (55.3%) | 77 of 157 (49.0%) |
| transaction | 15 of 25 (60.0%) | 25 of 32 (78.1%) |
| discovery | 7 of 26 (26.9%) | 1 of 3 (33.3%) |
| all | 129 of 248 (52.0%) | 204 of 331 (61.6%) |

## Every statement, by verdict

Volatile statements are included here.

| verdict | meaning | agent scripts | probes |
| --- | --- | ---: | ---: |
| same | the same columns, rows, and count as PostgreSQL, or the same SQLSTATE | 134 | 222 |
| refused | the language fails where PostgreSQL succeeds | 53 | 103 |
| other code | both fail, with different codes | 0 | 8 |
| after an earlier failure | differs from PostgreSQL, and equals what PostgreSQL answers after it has run only what the language ran | 78 | 7 |
| listed difference | succeeds with another answer that the list of kept differences names | 2 | 15 |
| silent difference | succeeds where PostgreSQL answers otherwise or refuses | 0 | 0 |
| all | | 267 | 355 |

## M3: silent differences

0 statements succeed where PostgreSQL answers otherwise or refuses, and are not in the list of kept differences. The rows of the agent scripts come first.

## Kept differences

17 statements succeed with another answer on purpose and are not in M3. Each has a line in [listed.tsv](../postgresw/src/test/resources/agentmeasure/listed.tsv) that names its place on the [differences page](postgresql-differences.md). A difference that is planned to change is not in the list.

| id | statement | listed as |
| --- | --- | --- |
| W88 | `SELECT version()` | Server version: 12.0, so that tools send what the language answers |
| C20 | `SHOW server_version` | Server version: 12.0, so that tools send what the language answers |
| P24 | `CREATE TABLE p24 (source_id text NOT NULL REFERENCES parent(rid) ON DELETE CASCADE, target_id text NOT NULL RE ...` | `CREATE TABLE`: a table that an edge table refers to declares no column `rid`, its record id answers to it |
| F87 | `SHOW server_version_num` | Server version: 12.0, so that tools send what the language answers |
| D07 | `INSERT INTO knows (source_id, target_id, since) VALUES ((SELECT rid FROM person WHERE id = 2), (SELECT rid FRO ...` | Choices, 2: a record id is text |
| D10 | `SELECT * FROM knows ORDER BY since` | Choices, 2 and 3: the ends of an edge hold record ids, which are text |
| D11 | `SELECT rid, id, name FROM person ORDER BY id` | Choices, 2: a record id is text |
| D12 | `SELECT * FROM person ORDER BY id` | `CREATE TABLE`: the record id is left out of `*` |
| D22 | `SELECT * FROM tag ORDER BY label LIMIT 2` | `CREATE TABLE`: the record id is left out of `*` |
| D34 | `SELECT * FROM City` | Choices, 7: only declared properties are columns |
| D44 | `DELETE FROM person WHERE id = 3` | From the engine: a foreign key on a `LINK` column is not enforced |
| D45 | `INSERT INTO person (id, name) VALUES (4, 'Dee') RETURNING rid, id, name` | Choices, 2: a record id is text |
| D46 | `SELECT m.title, p.name FROM memo m LEFT JOIN person p ON p.rid = m.author` | After D44: a foreign key on a `LINK` column is not enforced |
| D47 | `SELECT count(*) FROM knows` | After D44: deleting a vertex deletes its edges |
| D48 | `SELECT constraint_name, table_name, enforced FROM information_schema.table_constraints WHERE constraint_type = ...` | From the engine: a foreign key on a `LINK` column is listed as not enforced |
| D51 | `SELECT column_name, data_type, is_nullable, column_default, is_identity FROM information_schema.columns WHERE ...` | Choices, 2 and 10: a record id is text and is reported as an identity column |
| G11 | `SELECT version()` | Server version: 12.0, so that tools send what the language answers |

## M2: errors that name no next step

21 errors of the language name no next step. Of all 164 errors of its own, 148 use the hint field of the protocol, and 49 of those hints say that there is no way yet. Names and values are taken out of the messages.

| message | statements |
| --- | ---: |
| `CURRENT_USER is not supported by the postgresql language` | 2 |
| `WITHIN GROUP is not supported by the postgresql language` | 2 |
| `column "..." must appear in the GROUP BY clause or be used in an aggregate function` | 2 |
| `insert or update on table "..." violates foreign key constraint on column "...": the value "..." is not a vertex of the table person` | 2 |
| `relation "..." does not exist` | 2 |
| `ARRAY is not supported by the postgresql language` | 1 |
| `COMMENT is not supported by the postgresql language` | 1 |
| `Error on executing query: prepared statement "..." does not exist` | 1 |
| `Error on executing query: read-only transactions are not supported by this server: a transaction declared READ ONLY would still accept writes` | 1 |
| `Error on executing query: transaction isolation level "..." is not supported here: transactions run at "..."` | 1 |
| `Error on parsing query: The catalog relation "..." is not supported by this server` | 1 |
| `GRANT is not supported by the postgresql language` | 1 |
| `LISTEN is not supported by the postgresql language` | 1 |
| `Syntax error on parsing query: SQL syntax error at line 1, column 6: no viable alternative at input '...' ALTER TABLE acc ADD COLUMN extra text ^` | 1 |
| `cannot cast type anyarray to text` | 1 |
| `syntax error at or near "..."` | 1 |
| all | 21 |

## Rules

- **Match.** The same column names, rows (in order where the statement has `ORDER BY`), and count of a write, or the same SQLSTATE. Type names are not compared. JSON and numbers are compared by value, and vector distances within a relative difference of 0.00001. For `EXPLAIN` only the column names are compared.
- **Volatile.** A statement whose values depend on the clock, a random id, the server, or an order that PostgreSQL does not fix is marked in the [corpus](../postgresw/src/test/resources/agentmeasure/corpus.sql). It counts in M2 and M3, not in M1, and only its column names and row count are compared. Where the values are the server's own, such as its version or its search path, they are compared all the same.
- **Class.** `discovery` reads `information_schema` or `pg_catalog`, `transaction` is a statement of the transaction script or a session statement, and the others go by their first word: `schema`, `write`, or `read`.
- **After an earlier failure.** A second PostgreSQL database runs only what the language ran, and follows its failed transaction blocks. An answer that differs from PostgreSQL's and equals the answer of that database, or an error with its code, comes from an earlier statement. It does not match in M1, and counts in neither M2 nor M3.
- **M2.** The error has a hint field, or its text has `Hint:`, or a clause that starts with use, write, send, cast, declare, retry, run, quote, add, remove, leave, or repeat, or it asks to retry. A hint that starts with "There is no way to write this in the postgresql language yet" counts only when it goes on with something to do, such as to compute the result in the calling program. The check reads the text only: it does not say that the step works.
- **Not counted.** 7 setup steps of the probes run in ArcadeDB `sql`.

## Write this page again

Download the artifact `pg-agent-measure` of a run of the workflow [pg-agent-measure](../.github/workflows/pg-agent-measure.yml) and run:

```sh
uv run --no-project python .github/scripts/pg_agent_measure.py collect DOWNLOADED_DIRECTORY
```

CI fails when this page and the [result](../postgresw/src/test/resources/agentmeasure/result) disagree. It does not fail because an answer changed: each run prints its own numbers and the statements whose verdict differs from this page in its job summary.
