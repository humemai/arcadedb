# Research records of the `postgresql` language prototype

These are the research records behind the `postgresql` query language that I prototype in this fork, on the branch [`feat/postgresql-prototype`](https://github.com/humemai/arcadedb/tree/feat/postgresql-prototype). They are not supported code and not the documentation of the language. This branch shares no history with the engine branches and holds no engine code. For what the language does today, read the prototype branch and its issues.

The records are as of 2026-11-28, and the work they describe keeps moving. The scripts are kept for reading: many do not run from this branch, because run scripts, configuration files, jars, data, raw logs, and some imported modules are left out. Names such as `lane`, `compare1`, and `HUMEM_HOST_*` belong to the scheduler on my benchmark host.

## Folders

| folder | what it holds |
| --- | --- |
| [`design/`](design/) | my agent-first design recommendation of 2026-11-27 (sections 5 and 6 left out) |
| [`feasibility-20261106/`](feasibility-20261106/) | the study before the prototype, from reading code and web pages only: whether a separate `postgresql` language can be added, with evidence notes and one small script |
| [`agent-audit-20261127/`](agent-audit-20261127/) | the audit of what agent-written statements run into: my hand replacements (`fallbacks.sql`), analysis scripts, and the answers of PostgreSQL 18 and of the language, statement by statement |
| [`llm-fit-test-20261123/`](llm-fit-test-20261123/) | questions, gold statements, memory tasks with their system prompt and tool text, engine runners, a check of one phase, and three result tables of the model runs on [issue 35](https://github.com/humemai/arcadedb/issues/35) |
| [`timed-kit/`](timed-kit/) | the Python part of the timed measurements on [issue 13](https://github.com/humemai/arcadedb/issues/13), last version: join patterns (`study.py`) and core tasks (`study14.py`) |
| [`timed-runs/`](timed-runs/) | summaries of timed runs: `verdict.json`, and `analysis.txt` for the join pattern runs |
| [`traversal-ab/`](traversal-ab/) | runs before and after two changes to the one-hop join ([PR 116](https://github.com/humemai/arcadedb/pull/116), [PR 117](https://github.com/humemai/arcadedb/pull/117)) |
| [`profiling-20261125/`](profiling-20261125/) | harnesses and scripts for profiling the language against ArcadeDB `sql` |

## Design recommendations that were replaced

Do not read these parts of [`design/PG-DESIGN-20261127.md`](design/PG-DESIGN-20261127.md) as current.

| the design note says | what was decided later | where |
| --- | --- | --- |
| Principle, measure M2, and several refusals (for example R9 to R12): PostgreSQL's answer, or an error that says what to write instead | No workarounds: what agents commonly write should work. An error with a hint is a stopgap | [issue 14](https://github.com/humemai/arcadedb/issues/14#issuecomment-7221856397), [issue 122](https://github.com/humemai/arcadedb/issues/122#issuecomment-7221855612), [issue 248](https://github.com/humemai/arcadedb/issues/248#issuecomment-7221857303) |
| Choice 1: a table made with `CREATE TABLE` stops answering `id`, and types made with `sql` keep it | On every table, however it was made, `id` is only a declared column, and `rid` is the record id | [issue 38](https://github.com/humemai/arcadedb/issues/38#issuecomment-7221856439), [issue 118](https://github.com/humemai/arcadedb/issues/118#issuecomment-7221855656) |
| R6: accept a foreign key on an ordinary column only when it is written `NOT ENFORCED` | These keys will be checked for real, with a design first | [decision](https://github.com/humemai/arcadedb/issues/228#issuecomment-7221857143), [design](https://github.com/humemai/arcadedb/issues/228#issuecomment-7222548299) |
| R9: counts of concurrent increments that failed with `40001` | Autocommit writes that hit a conflict were changed after the note | [issue 239](https://github.com/humemai/arcadedb/issues/239#issuecomment-7223272438) |
| Section 1: counts from the audit run | The note itself says that the audit issue is authoritative | [issue 248](https://github.com/humemai/arcadedb/issues/248) |
| Section 4: order of work | The state of the issues is what counts | [issue 122](https://github.com/humemai/arcadedb/issues/122) |

Several other recommendations were confirmed or built after the note, for example `serial` columns ([issue 221](https://github.com/humemai/arcadedb/issues/221)), `CREATE TABLE` in a transaction block ([issue 229](https://github.com/humemai/arcadedb/issues/229)), and the link table keys ([issue 213](https://github.com/humemai/arcadedb/issues/213#issuecomment-7221855683)). Check each issue for its state.

## Older records that were replaced

- The feasibility study of 2026-11-06 recommends a spike first and a read-only first version, and one section dates the language work from December 2026. The prototype started in November and went on to writes and `CREATE TABLE` ([issue 14](https://github.com/humemai/arcadedb/issues/14), [issue 122](https://github.com/humemai/arcadedb/issues/122)).
- Its last section says that `id` is the primary key of every table. The rule on [issue 38](https://github.com/humemai/arcadedb/issues/38#issuecomment-7221856439) replaces that.
- `EVIDENCE.md` was written as evidence for a draft design note that is not part of this branch. The design note of 2026-11-27 also builds on a comparison note of 2026-11-26 that is not part of this branch.

## Timed runs

Every registration, amendment, and result text is a comment on [issue 13](https://github.com/humemai/arcadedb/issues/13). Read a run through the comment that reports it, because it says which figures are readable.

| folder in `timed-runs/` | engine commit | status | report |
| --- | --- | --- | --- |
| `pg13-v1-run2` | `132525868a` | kit version 1, replaced by later versions | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7125893465) |
| `pg13-v2-base` | `3ebb25e786` | kit version 2 | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7130973704) |
| `pg13-v2-head1`, `-head2`, `-head3` | `8bac961392` | kit version 2 | same comment |
| `pg13-v3-base2` | `3ebb25e786` | kit version 3 | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7142938487) |
| `pg13-v3-head2` | `8bac961392` | kit version 3 | same comment |
| `pg13-head4` | `be7a50af9f` | questions 1 and 2 met, question 3 not met on this engine | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7152317110) |
| `pg13-head8` | `64c15afea0` | question 3 met, the last run | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7183262670), [closing note](https://github.com/humemai/arcadedb/issues/13#issuecomment-7193753716) |
| `pg14-head3` | `8bac961392` | core tasks on an older engine, partly void | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7142941154) |
| `pg14-head5` | `be7a50af9f` | latency verdicts of record for t2, t3, t4, t5, and t8 | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7148923124), [summary](https://github.com/humemai/arcadedb/issues/13#issuecomment-7153821406) |
| `pg14-head7` | `be7a50af9f` | latency verdicts of record for t1, t6, t7, and t9 | [comment](https://github.com/humemai/arcadedb/issues/13#issuecomment-7156273678) |

Two runs are left out. The first run of kit version 1 compared the answers of pattern 1 wrongly ([correction](https://github.com/humemai/arcadedb/issues/13#issuecomment-7125631917)). The `head6` run of the core tasks is [void by its registered rule](https://github.com/humemai/arcadedb/issues/13#issuecomment-7152317417).

The runs in `traversal-ab/` are relative figures from my development machine and are not pooled with the runs above. Rules and results: PR 116 ([rules](https://github.com/humemai/arcadedb/issues/13#issuecomment-7167122638), [result](https://github.com/humemai/arcadedb/issues/13#issuecomment-7167293332)) and PR 117 ([rules](https://github.com/humemai/arcadedb/issues/13#issuecomment-7167729431), [result](https://github.com/humemai/arcadedb/issues/13#issuecomment-7167889923)).

## Model runs

The registrations and results of all phases are comments on [issue 35](https://github.com/humemai/arcadedb/issues/35). In [`llm-fit-test-20261123/results/`](llm-fit-test-20261123/results/), `phase5-table-runs.md` belongs to the [registered result](https://github.com/humemai/arcadedb/issues/35#issuecomment-7137656733). The [`sql_script` arm](https://github.com/humemai/arcadedb/issues/35#issuecomment-7138825618) and the [re-run on a newer engine](https://github.com/humemai/arcadedb/issues/35#issuecomment-7139427191) were added after the fact and do not replace it. The code that calls the model API and the raw model outputs are not in this branch.

## Already public, so not repeated here

- The audit statements and probes are in [`corpus.sql`](https://github.com/humemai/arcadedb/blob/5cc12c50024d112e0809694ef89ba13b23d4b470/postgresw/src/test/resources/agentmeasure/corpus.sql) of the agent measure. `agent-audit-20261127/merge_corpus.py` reads two input files that are not here, `corpus-blind.sql` and `extra.sql`, whose statements are in that corpus. The [measure page](https://github.com/humemai/arcadedb/blob/feat/postgresql-prototype/docs/postgresql-agent-measure.md) has newer numbers, from another engine and without replacements, so do not compare them directly with the results here.
- The audit harness, in Java with one Python script for the ORM check, ran on engine `d88fa5c59b` plus test files only. Its last version is commit [`47e8d0a682`](https://github.com/humemai/arcadedb/commit/47e8d0a682), made on a temporary branch that is gone. The results in `agent-audit-20261127/results/` are the ones that [issue 248](https://github.com/humemai/arcadedb/issues/248) counts. Section 1 of the design note counted an earlier run of the harness.
- The prompts of phases 1 to 4 of the model runs are quoted in their registrations on [issue 35](https://github.com/humemai/arcadedb/issues/35).
- The profile table of the engine before the fixes, the other profiling findings, and the fixes are in issues [76](https://github.com/humemai/arcadedb/issues/76), [85](https://github.com/humemai/arcadedb/issues/85), [88](https://github.com/humemai/arcadedb/issues/88), [90](https://github.com/humemai/arcadedb/issues/90), and [92](https://github.com/humemai/arcadedb/issues/92) and their pull requests.

## What I changed in the copies

Most files are unchanged copies. In these I removed local paths, host names, names of other people, test passwords, one cost figure, drafts, or one paragraph about presenting a plan to the upstream project:

- `design/PG-DESIGN-20261127.md`
- `feasibility-20261106/FEASIBILITY.md` and `EVIDENCE.md`
- `llm-fit-test-20261123/CHECK-phase5-timeouts.md`, `p4_golds_native.yaml`, and `refs3.py`
- `timed-kit/backend_pg.py`, `pg14lib.py`, `pg_ref_server.sh`, and `study.py`
- `traversal-ab/pr116/ab.py` and `traversal-ab/pr117/ab.py`

`CHANGE_ME` stands where a password of a throwaway test database was. A checksum recorded for an original run does not match an edited file.
