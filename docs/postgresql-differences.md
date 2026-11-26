# How `postgresql` differs from PostgreSQL

The `postgresql` language (a prototype in the fork `humemai/arcadedb`) aims to give PostgreSQL 18's answer or to refuse with an error. This page lists where it behaves differently, in three groups: what comes from the ArcadeDB engine, what is a choice and open to change, and what is not built yet. **Which of the choices would you change?**

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
| Deleting a vertex | follows the `ON DELETE` rule of each foreign key | its edges are always deleted |
| Two writers on one row | by default the second waits | the second commit fails with `40001` and has to be retried (not yet compared with a server) |
| Savepoints | supported | the language refuses `SAVEPOINT`; over the wire it is accepted and does nothing, and `ROLLBACK TO SAVEPOINT` is refused |
| Ends of an edge type | a foreign key is declared | the schema does not record which types an edge type connects, so the prototype needs a declaration (choice 4) |

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
| 10 | A record id column is reported as an identity column in `information_schema`, so clients leave it out of an `INSERT` | report a plain column |
| 11 | A separate language with its own grammar and evaluator; `sql` is unchanged | extend `sql` |

## Not built yet

| area | state |
| --- | --- |
| `CREATE`, `ALTER`, `DROP` | refused; types are made with ArcadeDB `sql`. `COPY`, `TRUNCATE`, `GRANT`, and `MERGE` are refused too |
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
