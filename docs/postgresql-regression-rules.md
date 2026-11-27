# Rules of the PostgreSQL regression harness

The harness runs the files of PostgreSQL's `src/test/regress/sql` at tag `REL_18_7` statement by statement, on a PostgreSQL 18.7 server and on the `postgresql` language of this fork, and gives each statement one verdict. These rules are written before the first published run, so that a count cannot be shaped afterwards (fork issue #119). A change to them goes through a pull request.

## Run it

```sh
.github/scripts/pg_regress.sh                       # the eight files of the syntax corpus
.github/scripts/pg_regress.sh case,join             # these files
.github/scripts/pg_regress.sh all                   # every file
.github/scripts/pg_regress.sh --subject postgresql  # PostgreSQL on both sides
```

It needs git, a JDK 21, and a container runtime. In CI it is the workflow "PostgreSQL prototype - regression files". The result is in `e2e/target/pgregress/<subject>/`: one `<file>.tsv` for each file, with one line for each statement (file, line, verdict, the SQLSTATE of PostgreSQL, the SQLSTATE of the other side, the reason, and the statement), and `summary.md` with the counts.

PostgreSQL's files are fetched by the script at the pinned tag and are not committed.

## The published page

[`postgresql-regression.md`](postgresql-regression.md) holds the counts of one run over every file. A script writes it, never a hand:

```sh
uv run --no-project python .github/scripts/pg_regress_page.py collect e2e/target/pgregress  # or the downloaded artifact
uv run --no-project python .github/scripts/pg_regress_page.py check
```

`collect` reads both sides of the run and fails unless the run with PostgreSQL on both sides holds only passed and out of scope, and unless its counts equal those of the run's `summary.md`. It keeps what the page needs in [`result`](../e2e/src/test/resources/pgregress/result); the only statement text there is that of the wrong statements, under the licence that [`ATTRIBUTIONS.md`](../ATTRIBUTIONS.md) names. CI runs `check`, which fails when the page and the result disagree, when a row does not add up, or when a wrong statement has no cause with an issue. [`causes.tsv`](../e2e/src/test/resources/pgregress/causes.tsv) gives each wrong statement the cause of the first rule that matches it, and each cause its issue. A new cause is a pull request with its issue.

## Verdicts

| verdict | rule |
| --- | --- |
| passed | the same result on both, or the same SQLSTATE on both |
| refused | `postgresql` raises an error where PostgreSQL succeeds, or raises `0A000` where PostgreSQL raises another error. `0A000` and other codes are counted apart |
| wrong | `postgresql` succeeds with another result or other table contents, succeeds where PostgreSQL raises an error, raises another code than PostgreSQL (`0A000` aside), crashes, or times out |
| blocked | not judged: a table it uses differs between the two sides and cannot be copied |
| out of scope | one of the three reasons below |

"The same result" means:

- the same column names and types, row count, and values, with numbers compared by value;
- the same row order where the statement has `ORDER BY`, with rows that tie on the sort keys in any order;
- after a write, the same row count and the same contents of every table it touched;
- after `CREATE TABLE`, the same columns, types, nullability, and keys in `information_schema`.

In every run, passed + refused + wrong + blocked + out of scope equals the number of statements, and the harness fails otherwise.

## Out of scope is a closed list

1. PostgreSQL itself answers differently in two runs on two fresh databases (`now()`, `random()`, object ids).
2. The statement shows PostgreSQL internals that the language does not model: `EXPLAIN` plan text, `pg_catalog`, and system columns such as `ctid`.
3. The statement administers the PostgreSQL server: tablespaces, replication, and server-side C code.

The entries are in [`e2e/src/test/resources/pgregress/out-of-scope.tsv`](../e2e/src/test/resources/pgregress/out-of-scope.tsv), each with its reason, and each has its own count. Everything else that the language cannot do is refused. A function of `pg_catalog` that is called without the schema name is not in the list. The library of the C code of the suite is not built: a statement that defines such a function, and a later call of it, are out of scope.

## How the rules are applied

### Statements

- A statement is what `psql` sends to the server. A semicolon ends it outside of quotes, comments, parentheses, and the `BEGIN ... END` body of a function.
- A line that starts with a backslash is a `psql` command and is counted apart. The commands that decide which statements are sent are carried out: variables, `\if`, `\gset`, `\gexec`, `\g`, `\bind`, `\connect`, `\copy`, and `\quit`.
- `\;` separates two statements, and the harness sends them one after the other. `\gdesc` and `\parse` execute nothing, so their text is not a statement.
- A statement in a branch of `\if` that is not taken, or after `\quit`, is not sent. It is counted apart. A statement that `\gexec` makes is a statement.

### Two runs of PostgreSQL

- Each file runs twice on PostgreSQL, on two servers and on a new database each time. The second run is the reference that the other side is compared with, statement by statement.
- A statement whose outcome or changed table contents differ between the two runs is out of scope, entry `two-runs`. So is a statement whose text differs, because `psql` put an answer of the server into it, such as an object id.
- The entry `tied-order` is for a statement that numbers rows which tie on a distance: PostgreSQL gave it another order only in a third run.
- A table that PostgreSQL itself cannot read, such as one with a virtual column that overflows, is not compared.
- The server has the `C` collation, the `UTF8` encoding, no automatic vacuum, and otherwise the defaults of the image. `pg_regress` sets another date style and time zone; the harness does not.

### The shared setup

Each file starts on a fresh database on both sides. The files listed in [`setup.tsv`](../e2e/src/test/resources/pgregress/setup.tsv) run first, without being judged: `test_setup`, and the dependencies that the comments of `parallel_schedule` name. The summary shows the errors of PostgreSQL beside the `ERROR` lines of the suite's expected output, so that a missing dependency is visible. The expected output decides no verdict.

### One refusal must not hide the rest

- When the tables of the two sides differ after a statement, the harness copies the table from PostgreSQL into ArcadeDB through the engine's own API, never through the language under test.
- A copy is checked: its `information_schema` and its rows must equal PostgreSQL's. Otherwise the table is blocked, with the reason.
- These tables are not copied, so they are blocked: a column type that ArcadeDB cannot hold, a foreign key of its own (the copy would differ in its keys), inheritance or partitions, a schema other than `public` (temporary tables excepted), and more than 200,000 rows.
- A table that has something the copy does not carry, and that decides what a write does, is blocked for writes only: a check constraint, a foreign key that refers to it, an exclusion constraint, a deferrable key, a unique index that is no constraint, a default, an identity or generated column, a trigger, a rule, or row security. A statement that only reads it is judged.
- The tables a statement uses are those of the plan that PostgreSQL makes for it, and those its text names after `FROM`, `JOIN`, `INTO`, `UPDATE`, `ONLY`, `TABLE`, or a comma of a `FROM` list, because a plan leaves out a table that it does not have to read. A statement without a plan (DDL, utility statements, statements that PostgreSQL refuses before planning) uses the tables whose names are among its words, except a `CREATE TABLE`, which uses those it names after `FROM`, `JOIN`, `REFERENCES`, `LIKE`, `PARTITION OF`, and in `INHERITS`.
- The transaction block of ArcadeDB follows the one of PostgreSQL, so that a refused statement inside a block does not turn every later statement of the block into an error.
- The summary counts the judged statements that used a copied table.

### Comparing

- Both SQLSTATEs equal is passed. If ArcadeDB then holds other table contents, the reason says so.
- A crash is an exception without a SQLSTATE of the language, or `XX000`. A statement has 30 seconds on ArcadeDB.
- With `ORDER BY`, a sort key that is no column of the answer is read from PostgreSQL by adding it to the select list. Where that is not possible (set operations, `DISTINCT`), the order must be the same.
- Rows are compared as written. A `LIMIT` that cuts through rows which tie, or that has no `ORDER BY`, is not given any freedom: other rows than PostgreSQL's are wrong.
- Floating point values are equal within a relative difference of 1e-9.
