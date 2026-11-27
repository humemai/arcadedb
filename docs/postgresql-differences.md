# How `postgresql` differs from PostgreSQL

The `postgresql` language (a prototype in the fork `humemai/arcadedb`) aims to give PostgreSQL 18's answer or to refuse with an error. It is for tables: rows, keys, and joins. Questions about connections, such as paths of any length or graph patterns, belong in openCypher. It cannot match PostgreSQL in everything, because ArcadeDB stores records rather than tables, so the aim is that most PostgreSQL use works as it does there, and that every known difference is listed here. The page has four groups: what comes from the ArcadeDB engine, what is a choice and open to change, what is not built yet, and the answers that are still wrong. **Which of the choices would you change?**

How many statements of PostgreSQL's own regression files pass, are refused, or are answered wrong, and which ones, is on the [regression page](postgresql-regression.md).

## Example: a join through an edge type

The tested [examples page](postgresql-examples.md) runs the same query text on both systems, over these PostgreSQL tables:

```sql
CREATE TABLE person (rid bigint GENERATED ALWAYS AS IDENTITY UNIQUE, id bigint PRIMARY KEY, name text, age int, city text, created bigint);
CREATE TABLE knows (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, source_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, target_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int);
```

```sql
SELECT q.id AS qid, q.name AS name, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON k.target_id = q.rid WHERE p.id = 1 ORDER BY since;
```

A PostgreSQL schema would usually point `knows.source_id` at `person (id)` and join on `p.id`. In `postgresql` the two ends of an edge hold record ids, so the join is on `rid`, or on `id` when the type has no `id` property of its own (a table that `CREATE TABLE` made answers to both names).

## From the engine

Changing these means changing the ArcadeDB engine.

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| Record ids | none | every record has one, given by the engine; a statement cannot set it (`428C9`) |
| Deleting a vertex | follows the `ON DELETE` rule of each foreign key | its edges are always deleted, so the foreign keys of an edge table are listed as `ON DELETE CASCADE`, and no other rule can be chosen |
| Two writers on one row | by default the second waits, then writes | neither waits; the commit that comes last fails with `40001` and has to be retried. Over the PostgreSQL protocol a single autocommit write is retried by the server, see [write conflicts](#write-conflicts) |
| `SERIALIZABLE` | supported | not available. Over the wire, `BEGIN ISOLATION LEVEL SERIALIZABLE` is `0A000` and leaves a failed block, so every statement is `25P02` until `ROLLBACK` and none is committed on its own; write `BEGIN ISOLATION LEVEL REPEATABLE READ`. Through the engine API a refused `BEGIN` opens no block (fork issue [#236](https://github.com/humemai/arcadedb/issues/236)) |
| `READ ONLY` | a transaction mode, a session default, and a setting | `BEGIN READ ONLY` and `START TRANSACTION READ ONLY` work over the wire, where a statement that writes is `25006`. The other spellings (`SET TRANSACTION READ ONLY`, `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`, `default_transaction_read_only`) and `READ ONLY` through the engine API are `0A000` |
| An unknown column (`42703`) | the hint names a close column, except for the column that an `INSERT` or an `UPDATE` writes to | the hint names a close column there too, and lists the columns the statement can name. Only declared properties are columns (choice 7), so a property that records carry but the type does not declare is unknown (fork issue [#220](https://github.com/humemai/arcadedb/issues/220)) || Savepoints | supported | the language refuses `SAVEPOINT`; over the wire it is accepted and does nothing, and `ROLLBACK TO SAVEPOINT` is refused |
| Ends of an edge type | a foreign key is declared | the schema does not record which types an edge type connects, so the prototype needs a declaration (choice 4) |
| `date[]` and `timestamp[]` | any array | the columns of a `LIST OF DATE` and of a `LIST OF DATETIME` (any precision), declared in ArcadeDB `sql` or by `CREATE TABLE`, where `timestamp[]` is a `LIST OF DATETIME_MICROS`. A null element is refused (`0A000`), as in every typed list. A list of links is `text[]` and is not written |
| A foreign key on a `LINK` column | enforced: the referenced row cannot be deleted, or the declared `ON DELETE` rule runs | listed as not enforced (`enforced` `NO`, `NOT ENFORCED`), because the delete of the referenced row succeeds, and a later insert can take the freed record id, so the link then points at another record, in `sql` and openCypher too (seen on `dc591c4d1c`). Unlike a `NOT ENFORCED` key in PostgreSQL, an `INSERT` or `UPDATE` through this language still refuses a link to a missing record (`23503`) |

## Write conflicts

PostgreSQL makes the second writer of a row wait. The engine locks no row: of two transactions that change the same data, the one that commits last fails. Over the PostgreSQL protocol, the server runs a statement again when it is a transaction of its own, so a single `INSERT`, `UPDATE`, or `DELETE` in autocommit needs no retry loop (fork issue [#239](https://github.com/humemai/arcadedb/issues/239)). Nothing of a run that failed is sent to the client, so a row of `RETURNING` arrives once.

| what the client sends | on a write conflict |
| --- | --- |
| one `INSERT`, `UPDATE`, or `DELETE` in autocommit: the only statement of a simple query, or the only `Execute` before its `Sync` | run again in a new transaction, at most 50 more times (`arcadedb.postgresql.autocommitRetries`, `0` turns it off), after a random wait that grows from 2 ms to 100 ms (`arcadedb.txRetryDelayBase`, `arcadedb.txRetryDelay`). `40001` when every run fails, `57014` when `statement_timeout` passes first |
| statements in a transaction block | `40001`: send `ROLLBACK` if the block is still open, then run the whole transaction again |
| a simple query with several statements, several `Execute` messages before one `Sync` (a JDBC batch, a pipeline), or an `Execute` followed by `Flush` | `40001`, and none of them is applied: send them again. To have one retried, send it on its own |
| any write on a database that has a trigger (made in ArcadeDB `sql`) | `40001`: run the statement again. A trigger can do work that a rollback does not take back |
| a write on a follower of a replicated database | the leader runs it, and the follower does not run it again |

What stays different from PostgreSQL:

- A statement that waits in PostgreSQL can still fail here with `40001`, when many sessions write the same rows and every run meets a conflict.
- Each run is a new transaction, so `now()` and the clock defaults give the time of the run that commits, not the time the statement arrived.
- Each run takes new values from the counter of a `serial` or identity column, so the gaps are larger. PostgreSQL has gaps too.
- A record listener of a plugin, or of an application that embeds the server, runs once for each run.
- `INSERT ... ON CONFLICT` is also run again when another transaction inserted its key in the meantime, and then does what its clause says, as PostgreSQL does after it waited. In a transaction block, or in a message of several statements, it fails with `23505` or `40001` instead. Every other duplicate key is `23505` at once, as in PostgreSQL.
- Through the engine API (`db.command`) a statement runs once.

## `CREATE TABLE`

A table is a vertex type, so openCypher matches its rows. A table that declares both ends of an edge is an edge type. Every refusal below is `0A000`.

```sql
CREATE TABLE person (id bigint PRIMARY KEY, name text);
CREATE TABLE knows (
  source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,
  target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE,
  since int
);
```

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| Foreign keys | any columns | only the two ends of an edge, written as above (as column or table constraints). Every other `REFERENCES` is refused, a link column included |
| A table that an edge table refers to | declares a column `rid` itself | declares nothing when `CREATE TABLE` made it: its record id answers to `rid`, a `text`. A table that declares its own column `rid` cannot be referred to (`0A000`). A type that ArcadeDB `sql` made needs a property `id` of its own (`42703` without one) |
| The record id | none | readable by name, as `rid`, and also as `id` in a table with no column `id` (PostgreSQL raises `42703` for a name that the table does not declare). In a table that declares a column `rid` and no column `id`, it is `id` only. Left out of `*` and of an `INSERT` without a column list. Once a foreign key refers to the table, the catalog lists it, under `rid` when that name reads it. An unqualified name in one `FROM` reads a column that the catalog lists before a record id that it does not list, so a listed record id and a declared column of the same name are `42702`, as in PostgreSQL (fork issue [#163](https://github.com/humemai/arcadedb/issues/163)) |
| An end of an edge | any row of the referenced table | checked by writes of this language only: `sql` and openCypher do not read the declaration |
| Keys over an end of an edge | any | only `(source_id, target_id)`, together |
| Transactions | nobody else sees a new table before the commit, and a rollback takes it back | the engine's schema is not transactional: `CREATE TABLE` runs at once, in a transaction block too, and **other sessions see the table before the commit** and can write to it. When the block rolls back, the table is dropped if it is still empty; a table that another session has written stays. A transaction that ends without the language or the PostgreSQL protocol, a `rollback()` of the Java API for example, leaves the drop to the next statement of the language. A server that stops inside a block leaves the table |
| Names | `person` and `"Person"` are two tables | an unquoted name that finds a table in another case counts as taken (`42P07`) |
| A taken name | `42P07`, or `42710` for a constraint | the same SQLSTATE, also from `CREATE INDEX`. As for a taken name in `sql`, every protocol of ArcadeDB answers it as a conflict: HTTP with 409 and `AlreadyExistsException` |
| Column types | all, with modifiers | `bool`, `int2`, `int4`, `int8`, `float4`, `float8`, `numeric`, `text`, `varchar`, `bytea`, `date`, `timestamp`, `timestamptz`, `jsonb`, `vector` and `vector(n)`, and arrays of `bool`, `int4`, `int8`, `float4`, `float8`, `numeric`, `bytea`, `text`, `date`, and `timestamp`. No other type modifier |
| A `jsonb` column | any JSON | a JSON object; an array or a scalar is refused when it is written. In an expression every JSON value is a `jsonb` value; outside the PostgreSQL protocol a string, number, boolean, or `null` comes back as its JSON text |
| Constraints | all | `NULL`, `NOT NULL`, `UNIQUE`, `PRIMARY KEY`, `DEFAULT`, and [`serial` and identity columns](#serial-and-identity-columns). No `CHECK`, `EXCLUDE`, generated columns, deferrable constraints, `NULLS NOT DISTINCT`, or table options (`TEMP`, `INHERITS`, `PARTITION BY`, `LIKE`, `AS`, `WITH`) |
| Defaults | any expression | a constant, for `bool`, the number types, `text`, and `varchar`; and `now()`, `CURRENT_TIMESTAMP`, `transaction_timestamp()`, `LOCALTIMESTAMP`, or `CURRENT_DATE` without a precision, for `date`, `timestamp`, and `timestamptz` |
| Keys | any type with an index | `bool`, the number types, `text`, `varchar`, `date`, `timestamp`, and `timestamptz` |
| `23505` | names the constraint | names the index of the engine |
| `NOT NULL` in `information_schema.table_constraints` | listed as a `CHECK` constraint | not listed; `is_nullable` says it |
| A name in a subquery that its tables do not declare | read from a query around it | the same, also where a table of the subquery reads its record id under that name: a record id that the catalog does not list gives way to a column of that name in a query around the subquery. In a join condition of an `EXISTS` subquery, in a subquery inside it, and beside another `EXISTS` in its `WHERE`, such a name is refused |

`NOT NULL`, unique keys, defaults, and the dimensions of a `vector(n)` are kept by the schema of the engine, so they hold for writes of every language.

### `serial` and identity columns

```sql
CREATE TABLE users (id serial PRIMARY KEY, name text NOT NULL);
CREATE TABLE events (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, user_id int NOT NULL);
INSERT INTO users (name) VALUES ('Ada'), ('Bo') RETURNING id;
```

`serial`, `bigserial`, `smallserial`, and `GENERATED { ALWAYS | BY DEFAULT } AS IDENTITY` give 1, 2, and so on, and gaps are possible, as in PostgreSQL. The engine has no sequence: each such column has a counter, one record of the document type `_postgresql_sequences`, and the default of its property (`postgresql_nextval('...')`) takes the next value.

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| Writes of another language | a sequence fills a `serial` column of any `INSERT` | an `INSERT` of `sql` or openCypher, or a record of the Java API, that leaves the column out takes the next value. `sql` and openCypher can also write any value, to a `GENERATED ALWAYS` column too |
| A value that a statement gives | the sequence does not move | the same: a later generated value can meet it (`23505` on a key) |
| Rollback | the value is not given again | the same: the counter is committed in a transaction of its own |
| The cost of a value | a sequence call | one commit of the counter for each value. In one server process the commits of all counters of a database follow one another |
| Sequences | objects of their own, with `nextval`, `setval`, `ALTER SEQUENCE` | none: those are refused, and no sequence is listed anywhere. `column_default` of a `serial` column shows `nextval('users_id_seq'::regclass)`, a name that no statement can use |
| Identity options | start, increment, bounds, cycle | only those that restate the defaults (`START WITH 1`, `INCREMENT BY 1`, `NO CYCLE`); `OVERRIDING` is refused |
| Permissions | `USAGE` on the sequence of a `serial` column | an ArcadeDB user whose permissions are set for each type needs to read and update `_postgresql_sequences` to insert |
| The type of a result column in a driver | pgjdbc says `serial` or `bigserial` for a generated key that it reads from a table | `int4` or `int8`, because an answer does not name its table; the values are the same |
| A dropped table | drops its sequences | `DROP TYPE` in `sql` leaves the counter record; a table made again counts from 1 |

## Embedded documents

A property declared `EMBEDDED OF address`, `LIST OF address`, or `MAP OF address`, for a document type `address`, is a `jsonb` column. `CREATE TABLE` does not declare one; use ArcadeDB `sql`.

```sql
INSERT INTO person (name, home) VALUES ('Fay', '{"city": "Delft", "zip": "1000AA"}');
SELECT home FROM person WHERE name = 'Fay';
```

| area | `jsonb` in PostgreSQL | `postgresql` |
| --- | --- | --- |
| What is stored | the JSON | an embedded document of the type for the object, for each element of the array (`LIST`), or for each value of the object (`MAP`), which `sql` and openCypher read as such |
| Reading | the JSON | the object, without `@type` and `@cat`. A document of a subtype, which only another language writes, keeps both keys |
| What can be written | any JSON | an object that reads back as written. Refused with `0A000`: a value that the type converts to another one (a number for a text property, `"2026-1-5"` for a date), a `null` in place of a document in an array or a map, and the keys `@type` and `@cat` |
| Constraints | `CHECK` | those of the document type hold, so a missing `MANDATORY` property is `23502` |
| Not written | | an `EMBEDDED` property with no declared type, and a collection of links (`0A000`) |

## `CREATE INDEX`

```sql
CREATE UNIQUE INDEX ON person (id);
CREATE INDEX ON memory USING hnsw (embedding vector_l2_ops);
```

Every refusal below is `0A000`.

| area | PostgreSQL and pgvector | `postgresql` |
| --- | --- | --- |
| Methods | `btree`, `hash`, `gin`, `gist`, `brin`, `hnsw`, `ivfflat` and more | the default and `btree` make the tree index of the engine, `hnsw` an `LSM_VECTOR` index; every other method is refused |
| Operator classes of `hnsw` | one per type and distance | `vector_l2_ops`, `vector_cosine_ops`, `vector_ip_ops`; others are refused |
| Dimensions of an `hnsw` index | from the column | from a column declared `vector(n)`; a column without is refused |
| `WITH (m = ..., ef_construction = ...)` | `m` neighbours per node on an upper layer, `2 * m` on the base layer | `ef_construction` is the beam width of the engine; its graph degree is one number for every layer, set to `2 * m`. Other parameters are refused |
| An index on `source_id`, `target_id`, or a record id | an index | only the name is recorded, because the engine reaches the edges of a vertex without one; a unique one is refused unless the columns are unique already |
| Several indexes over the same columns | each is an index | one index of the engine serves them; a unique one over columns with an index that is not unique, and a second vector index over a column, are refused |
| The name of an index | in `pg_indexes` | recorded for `IF NOT EXISTS` and `42P07`; the wire catalog (`\d`) shows the name of the engine's index |
| What it takes | expressions, `WHERE`, `INCLUDE`, `DESC`, `NULLS`, `COLLATE`, `CONCURRENTLY`, `TABLESPACE` | plain columns, ascending; the rest is refused |
| Keys | any type with an operator class | tree indexes over `bool`, the number types, `text`, `varchar`, `date`, and `timestamp` |
| Transactions | a rollback takes the index back | refused in a transaction block, also for a table that the block made, so `create_all` of SQLAlchemy needs autocommit for a model with an index |

## `CREATE EXTENSION`

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

What these two bring is built in (the `vector` type, its distance operators and indexes, and `gen_random_uuid()`), so the statement runs and changes nothing. It runs in a transaction block too.

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| Extensions | any installed one | `vector` and `pgcrypto`; every other name is `0A000`. No other function of `pgcrypto` runs |
| Without `IF NOT EXISTS` | installs the extension, `42710` when it is there | `0A000`: write `IF NOT EXISTS` |
| Options | `SCHEMA`, `VERSION`, `CASCADE` | `SCHEMA public` only; the rest is `0A000` |
| The notice for an extension that is there | `NOTICE: extension "vector" already exists, skipping` | no notice |
| `pg_extension` | lists the installed extensions | not changed by the statement |

## Errors

Read the hint. It is sent in the protocol's hint field, which `psql` and drivers show on its own line, and over other protocols it ends the message after `. Hint: `. Every `0A000` has one: what to write instead, or a text that starts with "There is no way to write this in the postgresql language yet". A hint that names a statement of another language ends with it, from `{sql}` or `{opencypher}` on, so that the statement can be sent as it is.

| statement | SQLSTATE | hint |
| --- | --- | --- |
| `ALTER TABLE acc ADD COLUMN extra text` | `0A000` | Add the column in the sql language, outside a transaction block: ``{sql}CREATE PROPERTY `acc`.`extra` STRING`` |
| `SELECT 1 FOR UPDATE` | `0A000` | Leave the clause out: no row is locked. Of two transactions that change one row the later commit fails with SQLSTATE 40001, run it again |
| `JOIN knows k ON k.source_id = p.id` | `42883` | k.source_id holds record ids, which are text such as '#12:3', not the values of p.id: join on p.rid. A cast will not match them |
| `SELECT v FROM acc LIMIT 1, 2` | `42601`, as PostgreSQL | Use separate LIMIT and OFFSET clauses: LIMIT 1, 2 is LIMIT 2 OFFSET 1 |

## Missing functions and operators

A call of a function or operator that PostgreSQL 18 has and `postgresql` lacks is `0A000` (not supported). A call that PostgreSQL would not find either keeps PostgreSQL's code, `42883`. The language knows what PostgreSQL has from a [list of its catalog](../engine/src/main/resources/com/arcadedb/query/postgresql/eval/postgresql-18-catalog.tsv) (names and argument types), which [`pg_catalog_list.sh`](../.github/scripts/pg_catalog_list.sh) writes from a PostgreSQL 18.7 server and CI compares with a server again.

| statement | `postgresql` | why |
| --- | --- | --- |
| `SELECT make_date(2013, 2, 30)` | `0A000` | PostgreSQL has the function |
| `SELECT substr('a'::bytea, 1, 1)` | `0A000` | the language has `substr` for text only |
| `SELECT '{}'::jsonb \|\| '{}'::jsonb` | `0A000` | PostgreSQL has the operator |
| `SELECT lower(1)` | `42883` | PostgreSQL has no `lower(integer)` either |
| `SELECT no_such_function(1)` | `42883` | PostgreSQL has no such name |

Limits:

- An extension is not in the list: `l2_distance` of pgvector, which the language lacks, is `42883`.
- The match is simpler than PostgreSQL's. The element type of an array is not compared, and the polymorphic arguments of one call need not agree, so `width_bucket('5'::text, ARRAY[3, 4])` is `0A000` where PostgreSQL answers `42883`.
- The list says only whether PostgreSQL has a candidate. A call that PostgreSQL refuses as ambiguous (`42725`) or for another reason is `0A000` too (from reading the code, not tested).
- The language answers for the first call that it cannot make, whatever PostgreSQL finds later. In `SELECT f1(point(3, 4))` that is `point` (`0A000`), while PostgreSQL answers `42883` for `f1`. `regproc('know')` is `0A000` too, while PostgreSQL has the cast and answers `42883` for the name `know`.
- A function that a script makes with `CREATE FUNCTION`, which is refused, is not in the list, so a later call of it is `42883`.

## Choices, open to change

| # | choice | the main alternative |
| ---: | --- | --- |
| 1 | A type's own `id` property is the column `id` (the primary key when a unique index covers it), and the record id is `rid`. Without one, `id` is the record id, and a table that `CREATE TABLE` made reads it as `rid` too | the record id always under one name |
| 2 | A record id is `text`, such as `#12:3` | a number |
| 3 | `source_id` and `target_id` hold record ids | the key of the vertex they point at, which reads the vertex for each edge row |
| 4 | Edge ends are declared on the edge type: `CREATE EDGE TYPE knows CUSTOM source_table = 'person', target_table = 'person'`. Foreign keys come only from this and from link properties | a schema feature of the engine, or ends inferred from the data |
| 5 | Updating an end of an edge is refused; delete the edge and insert it again | replace the edge record behind the statement |
| 6 | An unquoted name is folded to lower case, and then one name that differs only in case is accepted (`person` finds `Person`) | lower case only, as PostgreSQL does, with quotes for `Person` |
| 7 | Only declared properties are columns | one more `jsonb` column for the rest |
| 8 | Maps are `jsonb`, lists are arrays | keep ArcadeDB's own type names |
| 9 | A `jsonb` object is stored in the order its keys were written, and a decimal as a double when a double holds it, so `sql` and openCypher read what was written. A whole value is sent in the stored order; a text made from it (`->>`, `::text`) orders the keys as PostgreSQL does. A stored `1.50` reads as `1.5` | sort keys and store decimals, as PostgreSQL does |
| 10 | A record id column is reported as an identity column in `information_schema` and `pg_attribute`, so clients leave it out of an `INSERT`. Unlike an identity column in PostgreSQL, it also has a default in `pg_attrdef`, `arcadedb_record_id()` (`atthasdef` true, a label and not a function a statement can call), so that the SQLAlchemy ORM reads a new key back with `RETURNING`; `column_default` stays `NULL` (fork issue [#137](https://github.com/humemai/arcadedb/issues/137)) | report a plain column, and declare the key in each ORM class |
| 11 | A separate language with its own grammar and evaluator; `sql` is unchanged | extend `sql` |

## Not built yet

| area | state |
| --- | --- |
| `ALTER`, `DROP`, and every `CREATE` but `CREATE TABLE`, `CREATE INDEX`, and `CREATE EXTENSION IF NOT EXISTS` of [what is built in](#create-extension) | refused; use ArcadeDB `sql`. `COPY`, `TRUNCATE`, `GRANT`, and `MERGE` are refused too |
| Types | no `json` (there is `jsonb`), `uuid`, `time`, `timetz`, `timestamptz[]` columns, or `char(n)` |
| Syntax | a whole-row value such as `(t.*)`, `WITHOUT OVERLAPS`, `COALESCE`, `GREATEST`, `LEAST`, and `NULLIF` as functions in `FROM`, and an index parameter with a namespace are read and refused (`0A000`; fork issue [#202](https://github.com/humemai/arcadedb/issues/202)) |
| Queries | refused: `array_agg`, the JSON aggregates, most window frames, `ntile`, named windows, `WITH RECURSIVE`, `ANY` and `ALL` with an operator that is not a comparison, the order of arrays (`<`, `>`) and of `jsonb` values, `unnest`, the `?` operators of `jsonb` (write `jsonb_exists`), set-returning `jsonb` functions, `LATERAL`, `NATURAL` joins, `SIMILAR TO` |
| `numeric ^ numeric` | an integer exponent only. The bases `1`, `-1`, and `0` take every integer; another base takes `0` to `2147483647`, or a larger one when `numeric` cannot hold the result (`22003`, or zero). Every other exponent is refused. The result, its scale, and these errors are those of PostgreSQL 18 (`PostgreSQLNumericPowerTest`, fork issue [#161](https://github.com/humemai/arcadedb/issues/161)) |
| A large `numeric` parameter in binary format | results hold all that `numeric` holds in the text and in the binary format (`PostgreSQLLargeNumericTest`, fork issue [#171](https://github.com/humemai/arcadedb/issues/171)), but a parameter that a client sends in binary is refused above 4,000 groups of four digits, a weight beyond 4,000, or 16,000 decimals (from reading the code, not tested) |
| Large operations | joins, `GROUP BY`, derived tables, `WITH`, and set operations fail with `53200` over a memory limit instead of spilling to disk |
| Date and timestamp text | read as year-month-day with an optional time (`2026-03-09 10:11:12`), and after the time of a timestamp `Z`, `UTC`, or an offset. Another form that PostgreSQL may read is refused (`0A000`): `1/8/1999`, `08-Jan-99`, `19990108`, a zone name or abbreviation, a time zone after a date, `infinity`, `today`, a date BC, a year above 9999. A text that PostgreSQL reads in no way is `22007`, and a date that it does not have is `22008`, with the month before the day as in its default `DateStyle`, `ISO, MDY` (fork issue [#200](https://github.com/humemai/arcadedb/issues/200)) |
| Interval text | pairs of a number and a unit up to `millennium`, and a time. `infinity`, `1-2`, the ISO 8601 form, and a fraction of a day or of a larger unit are refused (`0A000`). A unit given twice is `22007`, a field that does not fit is `22015`, and years and months that do not fit together are `22008`. A sum with a date or a timestamp outside PostgreSQL's range is `22008`, and one before the year 1 or after 9999 is refused |
| Time zone | any spelling of a zone, used by every time value | over the wire, `CURRENT_DATE`, `LOCALTIMESTAMP`, and their defaults read the clock in the session's zone when it is a name of the time zone database or a zero offset. With another spelling (`9`, `'+09:00'`, a POSIX rule, an interval, or an unknown name, which PostgreSQL refuses with `22023`), the `SET` runs and those two are `0A000`. Through the engine API the clock is that of the Java virtual machine (fork issue [#237](https://github.com/humemai/arcadedb/issues/237)). `timestamptz` is computed in UTC only: in a session whose time zone is not UTC, which pgjdbc sets from the zone of its Java process, a statement that reads or makes one is `0A000` and says to send `SET TIME ZONE 'UTC'`. A zone name or abbreviation in a text, `AT TIME ZONE`, and a time before the year 1 or after 9999 in UTC are refused too (fork issue [#223](https://github.com/humemai/arcadedb/issues/223)) |
| `SET search_path` | names resolve in the listed schemas | there is one schema, `public`. Over the wire, a path that names neither it nor `current_schema()` is accepted, and every statement of the language is then `0A000` until the path names it again, where PostgreSQL finds no table (`42P01`) |

## Kept differences

These answer differently from PostgreSQL and stay so, by decision. The [regression page](postgresql-regression.md) counts their statements as wrong, in a row of their own.

| case | difference |
| --- | --- |
| Privileges and roles | `CREATE ROLE`, `GRANT`, `SET ROLE`, and `SET SESSION AUTHORIZATION` are refused (`0A000`). The statements after them are not checked against that role or that grant, so one that PostgreSQL refuses with `42501` can succeed (fork issue [#176](https://github.com/humemai/arcadedb/issues/176)) |

## Known wrong answers

These should match PostgreSQL and do not yet.

| case | difference |
| --- | --- |
| `ILIKE` on non-ASCII letters | folds them, while PostgreSQL with the `C` collation does not |

More detail: [supported syntax](postgresql-supported-syntax.md), the [decisions](postgresql-decisions.md), and the design issues [#1](https://github.com/humemai/arcadedb/issues/1), [#38](https://github.com/humemai/arcadedb/issues/38), [#29](https://github.com/humemai/arcadedb/issues/29), and [#10](https://github.com/humemai/arcadedb/issues/10).
