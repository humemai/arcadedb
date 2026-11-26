# How `postgresql` differs from PostgreSQL

The `postgresql` language (a prototype in the fork `humemai/arcadedb`) aims to give PostgreSQL 18's answer or to refuse with an error. It is for tables: rows, keys, and joins. Questions about connections, such as paths of any length or graph patterns, belong in openCypher. It cannot match PostgreSQL in everything, because ArcadeDB stores records rather than tables, so the aim is that most PostgreSQL use works as it does there, and that every known difference is listed here. The page has four groups: what comes from the ArcadeDB engine, what is a choice and open to change, what is not built yet, and the answers that are still wrong. **Which of the choices would you change?**

## Example: a join through an edge type

The tested [examples page](postgresql-examples.md) runs the same query text on both systems, over these PostgreSQL tables:

```sql
CREATE TABLE person (rid bigint GENERATED ALWAYS AS IDENTITY UNIQUE, id bigint PRIMARY KEY, name text, age int, city text, created bigint);
CREATE TABLE knows (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, source_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, target_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int);
```

```sql
SELECT q.id AS qid, q.name AS name, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON k.target_id = q.rid WHERE p.id = 1 ORDER BY since;
```

A PostgreSQL schema would usually point `knows.source_id` at `person (id)` and join on `p.id`. In `postgresql` the two ends of an edge hold record ids, so the join is on `rid`, or on `id` when the type has no `id` property of its own.

## From the engine

Changing these means changing the ArcadeDB engine.

| area | PostgreSQL | `postgresql` |
| --- | --- | --- |
| Record ids | none | every record has one, given by the engine; a statement cannot set it (`428C9`) |
| Deleting a vertex | follows the `ON DELETE` rule of each foreign key | its edges are always deleted, so the foreign keys of an edge table are listed as `ON DELETE CASCADE`, and no other rule can be chosen |
| Two writers on one row | by default the second waits, then writes | neither waits; the commit that comes last fails with `40001` and has to be retried |
| Savepoints | supported | the language refuses `SAVEPOINT`; over the wire it is accepted and does nothing, and `ROLLBACK TO SAVEPOINT` is refused |
| Ends of an edge type | a foreign key is declared | the schema does not record which types an edge type connects, so the prototype needs a declaration (choice 4) |
| `date[]` and `timestamp[]` | any array | the columns of a `LIST OF DATE` and of a `LIST OF DATETIME` (any precision), which ArcadeDB `sql` declares and `CREATE TABLE` does not yet. A null element is refused (`0A000`), as in every typed list. A list of links is `text[]` and is not written |
| A foreign key on a `LINK` column | enforced: the referenced row cannot be deleted, or the declared `ON DELETE` rule runs | listed as not enforced (`enforced` `NO`, `NOT ENFORCED`), because the delete of the referenced row succeeds, and a later insert can take the freed record id, so the link then points at another record, in `sql` and openCypher too (seen on `dc591c4d1c`). Unlike a `NOT ENFORCED` key in PostgreSQL, an `INSERT` or `UPDATE` through this language still refuses a link to a missing record (`23503`) |

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
| A table that an edge table refers to | declares a column `rid` itself | needs a column `id` of its own, so that its record id is `rid`, a `text` |
| The record id | none | readable by name (`rid`, or `id` in a table with no column `id`, where PostgreSQL raises `42703`), left out of `*` and of an `INSERT` without a column list, listed in the catalog once a foreign key refers to the table |
| An end of an edge | any row of the referenced table | checked by writes of this language only: `sql` and openCypher do not read the declaration |
| Keys over an end of an edge | any | only `(source_id, target_id)`, together |
| Transactions | a rollback takes a new table back | the engine's schema is not transactional, so `CREATE TABLE` is refused in a transaction block, also one that a message of several statements opens on the wire |
| Names | `person` and `"Person"` are two tables | an unquoted name that finds a table in another case counts as taken (`42P07`) |
| Column types | all, with modifiers | `bool`, `int2`, `int4`, `int8`, `float4`, `float8`, `numeric`, `text`, `varchar`, `bytea`, `date`, `timestamp`, `jsonb`, `vector` and `vector(n)`, and arrays of `bool`, `int4`, `int8`, `float4`, `float8`, `numeric`, `bytea`, and `text`. No other type modifier |
| A `jsonb` column | any JSON | a JSON object; an array or a scalar is refused when it is written |
| Constraints | all | `NULL`, `NOT NULL`, `UNIQUE`, `PRIMARY KEY`, `DEFAULT`. No `CHECK`, `EXCLUDE`, `serial`, identity or generated columns, deferrable constraints, `NULLS NOT DISTINCT`, or table options (`TEMP`, `INHERITS`, `PARTITION BY`, `LIKE`, `AS`, `WITH`) |
| Defaults | any expression | a constant, for `bool`, the number types, `text`, and `varchar` |
| Keys | any type with an index | `bool`, the number types, `text`, `varchar`, `date`, and `timestamp` |
| `23505` | names the constraint | names the index of the engine |
| `NOT NULL` in `information_schema.table_constraints` | listed as a `CHECK` constraint | not listed; `is_nullable` says it |

`NOT NULL`, unique keys, defaults, and the dimensions of a `vector(n)` are kept by the schema of the engine, so they hold for writes of every language.

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

## Choices, open to change

| # | choice | the main alternative |
| ---: | --- | --- |
| 1 | A type's own `id` property is the column `id` (the primary key when a unique index covers it), and the record id is `rid`. Without one, `id` is the record id | the record id always under one name |
| 2 | A record id is `text`, such as `#12:3` | a number |
| 3 | `source_id` and `target_id` hold record ids | the key of the vertex they point at, which reads the vertex for each edge row |
| 4 | Edge ends are declared on the edge type: `CREATE EDGE TYPE knows CUSTOM source_table = 'person', target_table = 'person'`. Foreign keys come only from this and from link properties | a schema feature of the engine, or ends inferred from the data |
| 5 | Updating an end of an edge is refused; delete the edge and insert it again | replace the edge record behind the statement |
| 6 | An unquoted name is folded to lower case, and then one name that differs only in case is accepted (`person` finds `Person`) | lower case only, as PostgreSQL does, with quotes for `Person` |
| 7 | Only declared properties are columns | one more `jsonb` column for the rest |
| 8 | Maps are `jsonb`, lists are arrays | keep ArcadeDB's own type names |
| 9 | A `jsonb` object keeps its key order, and a decimal is stored as a double when a double holds it, so `sql` and openCypher read what was written | sort keys and store decimals, as PostgreSQL does |
| 10 | A record id column is reported as an identity column in `information_schema` and `pg_attribute`, so clients leave it out of an `INSERT`. Unlike an identity column in PostgreSQL, it also has a default in `pg_attrdef`, `arcadedb_record_id()` (`atthasdef` true, a label and not a function a statement can call), so that the SQLAlchemy ORM reads a new key back with `RETURNING`; `column_default` stays `NULL` (fork issue [#137](https://github.com/humemai/arcadedb/issues/137)) | report a plain column, and declare the key in each ORM class |
| 11 | A separate language with its own grammar and evaluator; `sql` is unchanged | extend `sql` |

## Not built yet

| area | state |
| --- | --- |
| `ALTER`, `DROP`, and every `CREATE` but `CREATE TABLE` | refused; use ArcadeDB `sql`. `COPY`, `TRUNCATE`, `GRANT`, and `MERGE` are refused too |
| Types | no `uuid`, `timestamptz`, `time`, or `char(n)` |
| Queries | refused: `array_agg`, the JSON aggregates, most window frames, `ntile`, named windows, `WITH RECURSIVE`, `= ANY`, `->>`, `LATERAL`, `NATURAL` joins, `SIMILAR TO` |
| Large operations | joins, `GROUP BY`, derived tables, `WITH`, and set operations fail with `53200` over a memory limit instead of spilling to disk |

## Known wrong answers

These should match PostgreSQL and do not yet.

| case | difference |
| --- | --- |
| `ILIKE` on non-ASCII letters | folds them, while PostgreSQL with the `C` collation does not |
| `CURRENT_DATE`, `LOCALTIMESTAMP` | read the clock for each row, not once per transaction (from reading the code, not tested) |

More detail: [supported syntax](postgresql-supported-syntax.md), the [decisions](postgresql-decisions.md), and the design issues [#1](https://github.com/humemai/arcadedb/issues/1), [#38](https://github.com/humemai/arcadedb/issues/38), [#29](https://github.com/humemai/arcadedb/issues/29), and [#10](https://github.com/humemai/arcadedb/issues/10).
