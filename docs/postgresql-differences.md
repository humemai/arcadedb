# How `postgresql` differs from PostgreSQL

The `postgresql` language (a prototype in the fork `humemai/arcadedb`) aims to give PostgreSQL 18's answer or to refuse with an error. It is for tables: rows, keys, and joins. Questions about connections, such as paths of any length or graph patterns, belong in openCypher. It cannot match PostgreSQL in everything, because ArcadeDB stores records rather than tables, so the aim is that most PostgreSQL use works as it does there, and that every known difference is listed here. The page has four groups: what comes from the ArcadeDB engine, what is a choice and open to change, what is not built yet, and the answers that are still wrong. **Which of the choices would you change?**

How many statements of PostgreSQL's own regression files pass, are refused, or are answered wrong, and which ones, is on the [regression page](postgresql-regression.md).

## Example: a join through an edge type

The tested [examples page](postgresql-examples.md) runs the same query text on both systems, over these PostgreSQL tables:

```sql
CREATE TABLE person (rid bigint GENERATED ALWAYS AS IDENTITY UNIQUE, id bigint PRIMARY KEY, name text, age int, city text, created bigint);
CREATE TABLE knows (rid bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, source_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, target_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int);
```

```sql
SELECT q.id AS qid, q.name AS name, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON k.target_id = q.rid WHERE p.id = 1 ORDER BY since;
```

Here the two ends of the edge hold record ids, so the join is on `rid`, the record id of every table. A PostgreSQL schema would usually point `knows.source_id` at `person (id)` and join on `p.id`, which an end that refers to a key allows, as the next section shows.

## Ends that refer to a key

An end of a link table can refer to a column that is alone a unique key of the table at that end, so the link table is declared and joined as in PostgreSQL:

```sql
CREATE TABLE person (id bigint PRIMARY KEY, name text);
CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (id), target_id bigint NOT NULL REFERENCES person, since int);
INSERT INTO person VALUES (1, 'Ada'), (2, 'Bo');
INSERT INTO knows VALUES (1, 2, 2022);
SELECT q.name FROM person p JOIN knows k ON k.source_id = p.id JOIN person q ON q.id = k.target_id WHERE p.id = 1;
```

`source_id` and `target_id` are the `id` of the two rows, with its type. The key is a primary key, a unique constraint, or the index of `CREATE UNIQUE INDEX`; `REFERENCES person` is the primary key. Each end is chosen on its own, so one can refer to a key and the other to `rid`. The engine still stores a link between the two records, and from a start that a key binds the two joins walk it, as the joins on record ids do.

An edge type made in ArcadeDB `sql` declares the same in its custom metadata:

```sql
CREATE EDGE TYPE knows CUSTOM source_table = 'person', source_column = 'id', target_table = 'person', target_column = 'id';
```

| custom entry, for `source_` and for `target_` | values | when it is absent |
| --- | --- | --- |
| `source_column`, `target_column` | a column that is alone a unique key | the end holds record ids |
| `source_on_delete`, `target_on_delete` | `CASCADE`, `NO ACTION`, `RESTRICT` | `CASCADE`, which is what the engine does |
| `source_on_update`, `target_on_update` | `NO ACTION`, `CASCADE` | `NO ACTION` |

`CREATE TABLE` writes the same entries, with PostgreSQL's default of `NO ACTION` for both rules.

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| Who holds `ON DELETE` and `ON UPDATE` | every writer | the writes of this language only. `sql` and openCypher do not read the declaration: they delete a linked record together with its links, and a key that they change shows its new value on every link, as `ON UPDATE CASCADE` would |
| `ON DELETE RESTRICT` | cannot wait for the end of the statement | checked as `NO ACTION`, after what the statement cascades, with PostgreSQL's `23001` |
| `CREATE TABLE` | | refuses with `0A000` a nullable end, a `DEFAULT` on an end, a reference over several columns, `SET NULL`, `SET DEFAULT`, and `ON UPDATE RESTRICT` |
| The type of an end | any type that compares with the key's | the type of the key (`text` and `varchar` count as one). Another type that PostgreSQL accepts, such as `int` for a `bigint` key, is `0A000`, and the hint writes the column |
| A delete or a change of a key that races the insert of a link to the same row | one of the two waits | one of the two commits fails as a conflict (`40001`). Run again, which the server does for a write in autocommit ([write conflicts](#write-conflicts)), it answers from what the other committed: `23503` for the delete, the key change, and the insert alike. Under `ON UPDATE CASCADE` both commit when the link is committed first, and the link follows the key; a key change committed first refuses the insert (`PostgreSQLLinkKeyRaceTest`, `LinkKeyRaceTest`). One case is still open: for a lightweight link type (declared in `sql`), a key change in a block that has already written something can miss a link committed while the statement reads the row, and both commit (from reading the code, not tested) |
| On a replica of a high availability cluster | | a transaction block, or a run of several statements, that inserts a link by its keys, or changes a key that links refer to, is refused at `COMMIT` or at the `Sync`: run it on the leader (from reading the code, not tested) |
| A one-way (`UNIDIRECTIONAL`) edge type | | the records at its target end do not know their links, so a delete or a key change of such a record reads every link of the type, once for each statement |
| A link to a record without the key, or of another table | cannot exist | `sql` and openCypher can write one. A statement that reads that end fails with `23503` and names the link; it is never shown as `NULL` or with another table's value. The hint gives the statement of `sql` or openCypher that repairs it |
| A declared column that is no unique key anymore | the unique index cannot be dropped | every statement on the link table fails with `55000`, whose hint says how to repair it. Record ids are not shown in place of the key |
| Reading a key end | reads the column | reads the record at that end, so it needs read permission on the referenced table (from reading the code, not tested) |
| Cost of reading an end | the column is in the row | the record at that end is read, where a record id comes from the link alone. A walk from a bound start reads each far record once; a scan or a hash join over all links reads one record for each link and end that it reads |
| Join through a supertype of the declared table | | answers on the value, as written, without the walk |

## From the engine

Changing these means changing the ArcadeDB engine.

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| Record ids | none | every record has one, given by the engine and read as the column `rid`; a statement cannot set it (`428C9`). It says nothing about the order in which records were made, so `max()` and `min()` of a column of record ids are `0A000`. A text that a statement writes or binds and compares with such a column has to be a record id, such as `#12:3` (`22P02`) |
| Deleting a vertex | follows the `ON DELETE` rule of each foreign key | the engine always deletes its edges, so a foreign key on record ids is listed as `ON DELETE CASCADE` and no other rule can be chosen for it. An end that [refers to a key](#ends-that-refer-to-a-key) can say `NO ACTION` or `RESTRICT`, which the writes of this language check |
| Two writers on one row | by default the second waits, then writes | neither waits; the commit that comes last fails with `40001` and has to be retried. Over the PostgreSQL protocol a single autocommit write is retried by the server, see [write conflicts](#write-conflicts) |
| `SERIALIZABLE` | supported | not available. Over the wire, `BEGIN ISOLATION LEVEL SERIALIZABLE` is `0A000` and leaves a failed block, so every statement is `25P02` until `ROLLBACK` and none is committed on its own; write `BEGIN ISOLATION LEVEL REPEATABLE READ`. Through the engine API a refused `BEGIN` opens no block (fork issue [#236](https://github.com/humemai/arcadedb/issues/236)) |
| `READ ONLY` | a transaction mode, a session default, and a setting | `BEGIN READ ONLY` and `START TRANSACTION READ ONLY` work over the wire, where a statement that writes is `25006`. The other spellings (`SET TRANSACTION READ ONLY`, `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`, `default_transaction_read_only`) and `READ ONLY` through the engine API are `0A000` |
| An unknown column (`42703`) | the hint names a close column, except for the column that an `INSERT` or an `UPDATE` writes to | the hint names a close column there too, and lists the columns the statement can name. Only declared properties are columns (choice 7), so a property that records carry but the type does not declare is unknown (fork issue [#220](https://github.com/humemai/arcadedb/issues/220)) |
| Savepoints | supported | the language refuses `SAVEPOINT`; over the wire it is accepted and does nothing, and `ROLLBACK TO SAVEPOINT` is refused |
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
| one write in autocommit on a follower of a replicated database: the only statement of a simple query, or the only `Execute` before its `Sync` | the leader runs it, and the follower does not run it again |
| a transaction block, or a run of several statements (a simple query with several statements, a JDBC batch, a pipeline) on a follower | the follower runs the writes in one transaction, which commits at `COMMIT` or at the `Sync`: `40001`, and none of them is applied (`PostgresFollowerForwardingIT`, fork issue [#338](https://github.com/humemai/arcadedb/issues/338)). A commit that has a [foreign key](#foreign-keys) or a link key to check is refused there, and none of them is applied: send each write on its own, or connect to the leader |

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
| Foreign keys | any columns | checked, see [Foreign keys](#foreign-keys). A table with `REFERENCES` on both `source_id` and `target_id` is a link table: each end is written as above, or [refers to a key](#ends-that-refer-to-a-key); any other form of the two is refused |
| A table that an edge table refers to by its record id | declares a column `rid` itself | declares nothing when `CREATE TABLE` made it: its record id answers to `rid`, a `text`. A table that declares its own column `rid` cannot be referred to by record id. A type that ArcadeDB `sql` made can be referred to in the same way |
| The record id | none | readable by name, as `rid`, and never as `id`: in a table that declares no column `id`, `id` is `42703` as in PostgreSQL, with the hint `no column id: read rid, or declare id bigserial PRIMARY KEY` (fork issue [#240](https://github.com/humemai/arcadedb/issues/240)). A table that declares a column `rid` has no record id column. Left out of `*` and of an `INSERT` without a column list. The catalog lists it only once an end of an edge table or a `LINK` property refers to the record id of the table: an ordinary foreign key, and an end that refers to a key of the table, do not. An unqualified name in one `FROM` reads a column that the catalog lists before a record id that it does not list, so a listed record id and a declared column of the same name are `42702`, as in PostgreSQL (fork issue [#163](https://github.com/humemai/arcadedb/issues/163)) |
| An end of an edge | any row of the referenced table | checked by writes of this language only: `sql` and openCypher do not read the declaration |
| Keys over an end of an edge | any | only `(source_id, target_id)`, together |
| Transactions | nobody else sees a new table before the commit, and a rollback takes it back | the engine's schema is not transactional: `CREATE TABLE` runs at once, in a transaction block too, and **other sessions see the table before the commit** and can write to it. When the block rolls back, the table is dropped if it is still empty; a table that another session has written stays. A transaction that ends without the language or the PostgreSQL protocol, a `rollback()` of the Java API for example, leaves the drop to the next statement of the language. A server that stops inside a block leaves the table |
| Names | `person` and `"Person"` are two tables | an unquoted name that finds a table in another case counts as taken (`42P07`) |
| A taken name | `42P07`, or `42710` for a constraint | the same SQLSTATE, also from `CREATE INDEX`. As for a taken name in `sql`, every protocol of ArcadeDB answers it as a conflict: HTTP with 409 and `AlreadyExistsException` |
| Column types | all, with modifiers | `bool`, `int2`, `int4`, `int8`, `float4`, `float8`, `numeric` and [`numeric(p,s)`](#lengths-and-precision), `text`, `varchar` and [`varchar(n)`](#lengths-and-precision), `bytea`, `date`, `timestamp`, `timestamptz`, `uuid`, `jsonb`, `vector` and `vector(n)`, and arrays of `bool`, `int4`, `int8`, `float4`, `float8`, `numeric`, `bytea`, `text`, `date`, and `timestamp`. No `char(n)`, no modifier on an array, and no other type modifier |
| A `jsonb` column | any JSON | a JSON object; an array or a scalar is refused when it is written. In an expression every JSON value is a `jsonb` value; outside the PostgreSQL protocol a string, number, boolean, or `null` comes back as its JSON text |
| Constraints | all | `NULL`, `NOT NULL`, `UNIQUE`, `PRIMARY KEY`, `DEFAULT`, and [`serial` and identity columns](#serial-and-identity-columns). [`CHECK`](#check-constraints). No `EXCLUDE`, generated columns, deferrable constraints, `NULLS NOT DISTINCT`, or table options (`TEMP`, `INHERITS`, `PARTITION BY`, `LIKE`, `AS`, `WITH`) |
| Defaults | any expression | a constant, for `bool`, the number types, `text`, and `varchar`; a constant JSON object for `jsonb` (`'{}'`); for an array, `'{}'`, `ARRAY[]::type[]`, or `'{...}'` with elements of `bool`, `int4`, `int8`, `float8`, or `text` and no `NULL` (fork issue [#224](https://github.com/humemai/arcadedb/issues/224)); a constant for `uuid`; `gen_random_uuid()` for `uuid`, `text`, and `varchar`; and `now()`, `CURRENT_TIMESTAMP`, `transaction_timestamp()`, `LOCALTIMESTAMP`, or `CURRENT_DATE` without a precision, for `date`, `timestamp`, and `timestamptz` |
| Keys | any type with an index | `bool`, the number types, `text`, `varchar`, `uuid`, `date`, `timestamp`, and `timestamptz` |
| A `uuid` from another language | not applicable | the property is a `STRING` with a pattern: `sql` and openCypher write the 36 characters in lower case, and any other text is refused |
| `23505` | names the constraint | names the index of the engine |
| `NOT NULL` in `information_schema.table_constraints` | listed as a `CHECK` constraint | not listed; `is_nullable` says it |
| A name in a subquery that its tables do not declare | read from a query around it | the same, also where a table of the subquery reads its record id under that name: a record id that the catalog does not list gives way to a column of that name in a query around the subquery. In a join condition of an `EXISTS` subquery, in a subquery inside it, and beside another `EXISTS` in its `WHERE`, such a name is refused |

`NOT NULL`, unique keys, foreign keys, check constraints, defaults, the length of a `varchar(n)`, the precision and scale of a `numeric(p,s)`, and the dimensions of a `vector(n)` are kept by the schema of the engine, so they hold for writes of every language.

### Lengths and precision

The engine applies the length of a `varchar(n)` and the precision and scale of a `numeric(p,s)` to every record that any language creates or updates (fork issue [#226](https://github.com/humemai/arcadedb/issues/226), `PostgreSQLTypeModifierTest`).

| topic | PostgreSQL 18 | `postgresql` |
| --- | --- | --- |
| Writes of `sql`, openCypher, and the Java API | not applicable | the same rule as in this language: a number is rounded half away from zero to the scale, spaces over a length are cut off, and a value that does not fit fails the write (SQLSTATE `22001` or `22003` over the PostgreSQL protocol, a validation error elsewhere) |
| A `DEFAULT` that does not fit its column | the table is made, and each row that takes the default fails | `CREATE TABLE` is `0A000` |
| The type modifier in a result description (`RowDescription`) | the one of the column | `-1`: a driver reads the length and the precision from the catalog, not from the description of a result |
| `char(n)` | a type | `0A000`; write `varchar(n)`, which does not pad |
| An end of a link table | any compatible type | the length or precision of its key, or `text` |
| Removing a limit | `ALTER TABLE ... ALTER COLUMN ... TYPE` | in `sql`: `ALTER PROPERTY t.c CUSTOM arcadedb_typeModifier = null`. A limit set by editing the custom value does not change or check the rows that exist until they are written again |

### ALTER TABLE

`ADD [COLUMN] [IF NOT EXISTS]` with `NULL`, `NOT NULL`, and `DEFAULT`, `ALTER [COLUMN] c SET` and `DROP DEFAULT`, and `SET` and `DROP NOT NULL`, one or several in a statement (fork issue [#230](https://github.com/humemai/arcadedb/issues/230), `PostgreSQLAlterTableTest`). The rows that exist end up as PostgreSQL 18 leaves them, and the change is in the schema of the engine, so `sql` and openCypher see it.

| topic | PostgreSQL 18 | `postgresql` |
| --- | --- | --- |
| A transaction block | allowed; other sessions wait for the lock on the table, and see the change at `COMMIT` | allowed, and a rollback takes the change back. The schema of the engine is not transactional, so **other sessions see the change at once**, and nothing is locked: a write of another session to a row that the block wrote makes the `COMMIT` fail with `40001` and takes the change back (`PostgreSQLAlterTableInBlockTest`). A `rollback()` of the Java API leaves the schema to the next statement of the language, and a server that stops inside a block leaves the change. On a replica of a high availability cluster every schema change of the language inside a transaction block (`CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE`, and `DROP`, a message with several statements among them) is `0A000`, because it would run on the leader at once, outside the block: run it outside a block, or on the leader (`PostgresFollowerForwardingIT`, fork issue [#316](https://github.com/humemai/arcadedb/issues/316)). The same holds for a schema change that more statements follow before the same `Sync`: send it on its own (fork issue [#335](https://github.com/humemai/arcadedb/issues/335)), and for one that follows writes of its run (fork issue [#338](https://github.com/humemai/arcadedb/issues/338)) |
| A table | any table | one that `CREATE TABLE` of this language made; for another type the hint names the `sql` statement |
| An added column with a default | the rows are usually not rewritten | each row is written, in one transaction, so a very large table needs the memory for it |
| A record that already has an undeclared property of the new name, written by `sql` or openCypher | not applicable | `0A000`: remove the property first |
| An added serial or identity column | allowed | `0A000` |
| `SET DEFAULT` on a column that the same statement adds | allowed | `0A000` |
| `ADD CONSTRAINT` | the table is locked while the rows are read | the constraint is registered first and the rows are then read, with nothing locked, so other sessions meet it before the statement has finished. A key over columns that have an index that is not unique replaces that index with a unique one. `NOT VALID`, `DEFERRABLE`, `USING INDEX`, `EXCLUDE`, and a key or check over an end of a link table are `0A000`. The detail of an error names no row |
| `DROP COLUMN` | the values stay on disk and are hidden; the statement takes the same time for any table | **the value is removed from every record**, in one transaction, so a very large table needs the memory for it. As in PostgreSQL, the indexes, keys, and checks that involve the column go with it, and a foreign key that refers to it needs `CASCADE` (`2BP01`). A column that a link table refers to, an end of a link table, and the only column of a table are not dropped (`2BP01` or `0A000`) |
| `RENAME COLUMN` | changes the catalog only | **every record is rewritten**, because the engine renames no property, and the indexes over the column are built again. Indexes, keys, checks, and link tables follow the new name under their own names. Queries of `sql` and openCypher have to use the new name |
| `DROP CONSTRAINT`, a rename of a table or constraint, `ALTER COLUMN ... TYPE`, and the other actions | allowed | `0A000`; the hint names the `sql` statement where there is one |

### DROP TABLE and DROP INDEX

Fork issue [#230](https://github.com/humemai/arcadedb/issues/230), `PostgreSQLDropTest`. A statement is checked whole before anything changes.

| topic | PostgreSQL 18 | `postgresql` |
| --- | --- | --- |
| A transaction block | allowed, and a rollback brings the table or index back | `0A000`: run it in autocommit |
| Statements that follow before the same `Sync`: a JDBC statement string with several statements, a JDBC batch, a psycopg pipeline | one transaction, and the failure of a later statement brings the table or index back | `0A000` before anything is dropped, also when the later statements would succeed: send the `DROP` on its own, in autocommit. A `DROP` that is the last statement before the `Sync` runs, unless an earlier one wrote a row, and so does a `DROP ... IF EXISTS` that finds nothing. A simple query message with several statements is refused as a transaction block is (`PostgreSQLDropInRunTest`, fork issue [#335](https://github.com/humemai/arcadedb/issues/335)) |
| A table | any table | one that `CREATE TABLE` of this language made; for another type the hint names the `sql` statement |
| The rows | go with the table | every edge, and every row that an edge holds, is deleted first, in one transaction of its own, so a large table needs the memory for it; then the type is dropped. A server that stops between the two leaves the table without some of its rows |
| An edge that `sql` or openCypher made at a row | not applicable | deleted with the row |
| `CASCADE` of a table that a link table refers to | drops the foreign key, and the rows of the link table stay | `0A000`: an edge cannot stay without its ends, so name both tables in one statement |
| The index behind a foreign key, which `CREATE TABLE` made over the referring columns | not there | `DROP INDEX` refuses its name (`0A000`). A name that `CREATE INDEX` gave to it goes, and the index stays |
| The index of a primary key or unique constraint | `2BP01`; `ALTER TABLE ... DROP CONSTRAINT` removes it | `2BP01`; `DROP CONSTRAINT` is not supported yet |
| The notice of `IF EXISTS` | `NOTICE: table "t" does not exist, skipping` | no notice |

### Check constraints

The engine evaluates each `CHECK` for every record that any language creates or updates, always as the `postgresql` language reads the expression (fork issue [#227](https://github.com/humemai/arcadedb/issues/227), `PostgreSQLCheckConstraintTest`).

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| What a check may read | any immutable or volatile function, the clock, the session | the columns of the row: no clock, no `random()`, no session function, and a `timestamptz` value only in a comparison of two `timestamptz` columns or `IS NULL`. The record id and the ends of a link table are refused too (`0A000`) |
| `sql`, openCypher, Java API | not applicable | the same check at each record write, a validation error that names the constraint. openCypher saves a node built in two steps twice, and each save is checked |
| A column that a check reads is dropped | the constraint goes with it | writes to the type are refused until the column is back, or `ALTER TYPE t CUSTOM arcadedb_checkConstraints = null` in `sql` removes the checks |
| An edge type with a check | not applicable | cannot be `LIGHTWEIGHT`: a lightweight edge has no record |
| The text of a check in `pg_get_constraintdef`, `pg_get_expr`, and `information_schema.check_constraints` | any expression, written back as PostgreSQL analysed it | the same text for columns, number, text, boolean, date, timestamp, and uuid constants, comparisons, `+ - * / %`, `\|\|`, `AND`, `OR`, `NOT`, `IS [NOT] NULL`, `BETWEEN`, `IN`, `LIKE`, `ILIKE`, `->`, `->>`, `COALESCE`, `GREATEST`, `LEAST`, and `length`, `char_length`, `character_length`, `octet_length`, `lower`, `upper`, `btrim`, `ltrim`, `rtrim`, `abs`, `jsonb_typeof`, `jsonb_array_length`. Any other shape (a cast, `CASE`, another function), and a check that reads a dropped column, is shown as it was written when it was declared, without the casts and parentheses that PostgreSQL adds |
| `NOT NULL` in `pg_constraint` and `check_constraints` | each is listed as a constraint | not listed |
| The detail of a `23514` | the failing row | no detail |

## Foreign keys

A foreign key between ordinary tables is written and checked as in PostgreSQL: `REFERENCES t [(columns)]` as a column or table constraint, over one or several columns, also to the table itself, with `ON DELETE` and `ON UPDATE` `NO ACTION`, `RESTRICT`, `CASCADE`, or `SET NULL`. The language checks it at the end of each statement. A row with `NULL` in a referring column is not checked.

```sql
CREATE TABLE users (id bigint PRIMARY KEY);
CREATE TABLE orders (id bigint PRIMARY KEY, user_id bigint NOT NULL REFERENCES users ON DELETE CASCADE);
INSERT INTO orders VALUES (1, 99);  -- 23503
```

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| A parent delete or key change against a child insert, in two transactions | the second waits for the first, then `23503` | both statements run, and the commit that comes second fails, normally with `40001` (`23503` when its check had not run before the first commit). Run again, it gets PostgreSQL's answer. Two inserts that refer to one row do not conflict |
| `DEFERRABLE`, `INITIALLY DEFERRED`, `MATCH`, `NOT ENFORCED` | supported | `0A000` |
| `SET DEFAULT`, a column list after `SET NULL` | supported | `0A000` |
| Referenced columns | a primary key, a unique constraint, or a unique index | the same (`42830`). A record id (`rid`) can be referred to only by the two ends of a link table (`0A000`). The ends of a link table have rules of their own, [above](#ends-that-refer-to-a-key) |
| Column types | any pair that compares | the same type, or both of `smallint`, `integer`, and `bigint`, or both of `text` and `varchar`. Other pairs that PostgreSQL accepts are `0A000`, the rest `42804` |
| Indexes | none over the referring columns | `CREATE TABLE` adds one unless a key covers exactly those columns. `CREATE UNIQUE INDEX` over those columns is then `0A000`: declare `UNIQUE` in `CREATE TABLE` |
| `ALTER TABLE ... ADD CONSTRAINT` | the key is checked while the table is locked | supported, the rows are read after the key is registered, see [`ALTER TABLE`](#alter-table) |
| Writes of `sql`, openCypher, and the Java API | | the engine checks the same keys at commit and runs the same actions then, so rows that a cascade will remove are visible until the commit. A violation refuses the commit |
| `sql` schema changes | | `ALTER TYPE orders CUSTOM arcadedb_foreignKeys = null` removes the keys of a table, and a key declared through that custom value does not check the rows that exist. Dropping or renaming a referenced type is refused. When an index that a key needs is dropped, writes to both tables are `55000` until it is created again |
| `LOCK TYPE` in `sql` | | the lock list must include the referenced type, or the commit is refused |
| High availability | | a commit that starts on a replica and has a key to check is refused: run it on the leader. A transaction block and a run of several statements (a batch, a pipeline) that reach a replica commit there; one write in autocommit runs on the leader |

For every distinct key value that a statement writes, a foreign key costs up to two index lookups at the end of the statement and two at commit, and the referring table has one more index to maintain. Commits that refer to rows in one bucket wait for each other while they commit. These costs have not been measured.

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
| Transactions | a rollback takes the index back, and nobody else sees it before the commit | runs in a transaction block on a table that the same block made, so `create_all` of SQLAlchemy works on its defaults for a model with an index. A rollback drops the table together with its indexes, under the rules of [`CREATE TABLE`](#create-table): a table that another session has written stays, with its index. In a block, on a table that the block did not make, `CREATE INDEX` is refused: send it on its own, in autocommit. A simple query with several statements is such a block, so it is refused there too. In a run of messages before one `Sync` (a JDBC batch, a JDBC statement string with several statements, a pipeline) it runs, and an error later in the run drops the index again; other sessions see it until then (`PostgreSQLCreateTableInBlockTest`, fork issue [#293](https://github.com/humemai/arcadedb/issues/293)) |

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
| `ALTER TABLE acc RENAME TO account` | `0A000` | Rename the table in the sql language, outside a transaction block: ``{sql}ALTER TYPE `acc` NAME `account` `` |
| `SELECT 1 FOR UPDATE` | `0A000` | Leave the clause out: no row is locked. Of two transactions that change one row the later commit fails with SQLSTATE 40001, run it again |
| `JOIN knows k ON k.source_id = p.id` | `42883` | k.source_id holds record ids, which are text such as '#12:3', not the values of p.id: join on p.rid. A cast will not match them |
| `SELECT v FROM acc LIMIT 1, 2` | `42601`, as PostgreSQL | Use separate LIMIT and OFFSET clauses: LIMIT 1, 2 is LIMIT 2 OFFSET 1 |

## Missing functions and operators

A call of a function or operator that PostgreSQL 18 has and `postgresql` lacks is `0A000` (not supported). A call that PostgreSQL would not find either keeps PostgreSQL's code, `42883`. The language knows what PostgreSQL has from a [list of its catalog](../engine/src/main/resources/com/arcadedb/query/postgresql/eval/postgresql-18-catalog.tsv) (names and argument types), which [`pg_catalog_list.sh`](../.github/scripts/pg_catalog_list.sh) writes from a PostgreSQL 18.7 server and CI compares with a server again.

| statement | `postgresql` | why |
| --- | --- | --- |
| `SELECT make_date(2013, 2, 30)` | `0A000` | PostgreSQL has the function |
| `SELECT substr('a'::bytea, 1, 1)` | `0A000` | the language has `substr` for text only |
| `SELECT 'a'::bytea \|\| 'b'::bytea` | `0A000` | PostgreSQL has the operator |
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
| 1 | The record id is the column `rid` of every table, and `id` is only a column that the type declares (the primary key when a unique index covers it). The record id of a type without a declared `id` is its primary key, and a type that `sql` or openCypher made lists it in `*` and in the catalog | the record id as `id` in a table that declares no `id` |
| 2 | A record id is `text`, such as `#12:3` | a number |
| 3 | `source_id` and `target_id` hold record ids, unless the end declares a unique column of the table at that end, whose value it then shows ([above](#ends-that-refer-to-a-key)) | the key always, which reads the record at the end for each edge row |
| 4 | Edge ends are declared on the edge type: `CREATE EDGE TYPE knows CUSTOM source_table = 'person', target_table = 'person'`. Foreign keys come only from this and from link properties | a schema feature of the engine, or ends inferred from the data |
| 5 | Updating an end of an edge is refused; delete the edge and insert it again | replace the edge record behind the statement |
| 6 | An unquoted name is folded to lower case, and then one name that differs only in case is accepted (`person` finds `Person`) | lower case only, as PostgreSQL does, with quotes for `Person` |
| 7 | Only declared properties are columns | one more `jsonb` column for the rest |
| 8 | Maps are `jsonb`, lists are arrays | keep ArcadeDB's own type names |
| 9 | A `jsonb` object is stored in the order its keys were written, and a decimal as a double when a double holds it, so `sql` and openCypher read what was written. In an answer over the PostgreSQL protocol (`COPY` aside) and in a text made from it (`->>`, `::text`), the keys are in PostgreSQL's order. A value that holds a part that is no JSON value, such as a date in a map that `sql` wrote, is sent in the stored order and without spaces. A stored `1.50` reads as `1.5` | sort keys and store decimals, as PostgreSQL does |
| 10 | A record id column is reported as an identity column in `information_schema` and `pg_attribute`, so clients leave it out of an `INSERT`. Unlike an identity column in PostgreSQL, it also has a default in `pg_attrdef`, `arcadedb_record_id()` (`atthasdef` true, a label and not a function a statement can call), so that the SQLAlchemy ORM reads a new key back with `RETURNING`; `column_default` stays `NULL` (fork issue [#137](https://github.com/humemai/arcadedb/issues/137)) | report a plain column, and declare the key in each ORM class |
| 11 | A separate language with its own grammar and evaluator; `sql` is unchanged | extend `sql` |

## Not built yet

| area | state |
| --- | --- |
| `ALTER` other than [`ALTER TABLE` of columns](#alter-table), `DROP` other than [`DROP TABLE` and `DROP INDEX`](#drop-table-and-drop-index), and every `CREATE` but `CREATE TABLE`, `CREATE INDEX`, and `CREATE EXTENSION IF NOT EXISTS` of [what is built in](#create-extension) | refused; use ArcadeDB `sql`. `COPY`, `TRUNCATE`, `GRANT`, and `MERGE` are refused too |
| Types | no `json` columns (a `json` value goes into `jsonb`), `time`, `timetz`, `uuid[]` or `timestamptz[]` columns, or `char(n)` |
| Syntax | a whole-row value such as `(t.*)`, `WITHOUT OVERLAPS`, `COALESCE`, `GREATEST`, `LEAST`, and `NULLIF` as functions in `FROM`, and an index parameter with a namespace are read and refused (`0A000`; fork issue [#202](https://github.com/humemai/arcadedb/issues/202)) |
| Queries | refused: `array_agg` of arrays and of `jsonb`, the operators of `json`, most window frames, `ntile`, named windows, `WITH RECURSIVE`, `ANY` and `ALL` with an operator that is not a comparison, the order of arrays (`<`, `>`) and of `jsonb` values, set-returning functions such as `unnest` in a select list (write them in `FROM`), the `?` operators of `jsonb` (write `jsonb_exists`), `ROWS FROM`, `NATURAL` joins, `SIMILAR TO` |
| `numeric ^ numeric` | an integer exponent only. The bases `1`, `-1`, and `0` take every integer; another base takes `0` to `2147483647`, or a larger one when `numeric` cannot hold the result (`22003`, or zero). Every other exponent is refused. The result, its scale, and these errors are those of PostgreSQL 18 (`PostgreSQLNumericPowerTest`, fork issue [#161](https://github.com/humemai/arcadedb/issues/161)) |
| A large `numeric` parameter in binary format | results hold all that `numeric` holds in the text and in the binary format (`PostgreSQLLargeNumericTest`, fork issue [#171](https://github.com/humemai/arcadedb/issues/171)), but a parameter that a client sends in binary is refused above 4,000 groups of four digits, a weight beyond 4,000, or 16,000 decimals (from reading the code, not tested) |
| Large operations | joins, `GROUP BY`, derived tables, `WITH`, and set operations fail with `53200` over a memory limit instead of spilling to disk |
| Date and timestamp text | read as year-month-day with an optional time (`2026-03-09 10:11:12`), and after the time of a timestamp `Z`, `UTC`, or an offset. Another form that PostgreSQL may read is refused (`0A000`): `1/8/1999`, `08-Jan-99`, `19990108`, a zone name or abbreviation, a time zone after a date, `infinity`, `today`, a date BC, a year above 9999. A text that PostgreSQL reads in no way is `22007`, and a date that it does not have is `22008`, with the month before the day as in its default `DateStyle`, `ISO, MDY` (fork issue [#200](https://github.com/humemai/arcadedb/issues/200)) |
| Interval text | pairs of a number and a unit up to `millennium`, and a time. `infinity`, `1-2`, the ISO 8601 form, and a fraction of a day or of a larger unit are refused (`0A000`). A unit given twice is `22007`, a field that does not fit is `22015`, and years and months that do not fit together are `22008`. A sum with a date or a timestamp outside PostgreSQL's range is `22008`, and one before the year 1 or after 9999 is refused |
| Time zone | any spelling of a zone, used by every time value | the zone of the session, from the startup parameter or `SET TIME ZONE`, is read and applied as in PostgreSQL: a name, a number of hours east of UTC, an interval, or a POSIX offset (west of UTC), with `22023` for a value that names no zone (fork issue [#270](https://github.com/humemai/arcadedb/issues/270)). Refused (`0A000`): a POSIX rule with daylight saving time (`EST5EDT4,M3.2.0,M11.1.0`) and an offset of more than 18 hours. The rules of the zones are those of the time zone database of Java, so a recent change of a rule can differ until Java and PostgreSQL carry the same release. A constant of a prepared statement, such as `'2026-01-01'::timestamptz`, is read in the zone of the execution, where PostgreSQL reads it in the zone of the preparation. Only a connection whose language is `postgresql` refuses such a value at `SET` and at connect; a connection of another language takes any value, as before. A connection that changes to the `postgresql` language works at once when the value that it holds names a supported zone. When the value does not, every statement of the language is refused until `SET TIME ZONE` names one. Through the engine API, which has no session, a `timestamptz` is read and written in UTC and the clock is that of the Java virtual machine (fork issue [#237](https://github.com/humemai/arcadedb/issues/237)). Still refused: a zone name or abbreviation in a timestamp text, `AT TIME ZONE`, and a time before the year 1 or after 9999 in UTC (fork issue [#223](https://github.com/humemai/arcadedb/issues/223)) |
| `SET search_path` | names resolve in the listed schemas | there is one schema, `public`. Over the wire, a path that names neither it nor `current_schema()` is accepted, and every statement of the language is then `0A000` until the path names it again, where PostgreSQL finds no table (`42P01`) |

## Kept differences

These answer differently from PostgreSQL and stay so, by decision. The [regression page](postgresql-regression.md) counts their statements as wrong, in a row of their own.

| case | difference |
| --- | --- |
| Privileges and roles | `CREATE ROLE`, `GRANT`, `SET ROLE`, and `SET SESSION AUTHORIZATION` are refused (`0A000`). The statements after them are not checked against that role or that grant, so one that PostgreSQL refuses with `42501` can succeed (fork issue [#176](https://github.com/humemai/arcadedb/issues/176)) |
| Server version | PostgreSQL 18.7 answers `18.7` to `SHOW server_version` and `180007` to `SHOW server_version_num`. A `postgresql` connection answers `12.0`, `120000`, and `PostgreSQL 12.0 (ArcadeDB ...)` to `SELECT version()`, as a `sql` connection does. Tools choose their statements by this number, and at 18 they send some that the language cannot answer: SQLAlchemy 2.0.58 and 2.1.9 write every read of a `jsonb` key as a subscript (`data['k']`, `0A000`), and `psql` 18 fails on `\d table`, `\d+ table`, and `\l`. At 12, Django, which needs 15 or later, does not connect (fork issue [#289](https://github.com/humemai/arcadedb/issues/289)) |

## Known wrong answers

These should match PostgreSQL and do not yet.

| case | difference |
| --- | --- |
| `ILIKE` on non-ASCII letters | folds them, while PostgreSQL with the `C` collation does not |

More detail: [supported syntax](postgresql-supported-syntax.md), the [decisions](postgresql-decisions.md), and the design issues [#1](https://github.com/humemai/arcadedb/issues/1), [#240](https://github.com/humemai/arcadedb/issues/240), [#29](https://github.com/humemai/arcadedb/issues/29), and [#10](https://github.com/humemai/arcadedb/issues/10).
