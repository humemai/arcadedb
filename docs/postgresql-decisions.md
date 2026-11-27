# Decisions of the `postgresql` language

The 25 questions of [#1](https://github.com/humemai/arcadedb/issues/1) about the prototype in this fork, with the answers as the code follows them. Differences from PostgreSQL that follow from them are on the [differences page](postgresql-differences.md).

## Language and packaging

| # | question | answer |
| ---: | --- | --- |
| 1 | Language name | `postgresql`, no aliases. |
| 2 | Where the code lives | The package `com.arcadedb.query.postgresql` inside `engine`, registered by one line like `sql`. |
| 3 | Off by default | No setting for registration. The wire exposure is gated by `arcadedb.postgres.languages`, whose default includes `postgresql` since #54. |
| 4 | Branch | The long-lived `feat/postgresql-prototype` of the fork, with its own workflows. |

## Grammar and parser

| # | question | answer |
| ---: | --- | --- |
| 5 | Grammar source | An own small ANTLR4 grammar written from the structure of `gram.y`. Nothing is copied from the community grammar. |
| 6 | Parser generator | ANTLR 4.13.2, as the rest of the engine. |
| 7 | Identifier case | An unquoted name is folded to lower case and matched exactly first, then to a unique match that differs only in case. Two such matches are an error. A quoted name matches exactly. |
| 8 | Error outside the subset | `0A000` with the line and column in the message. A syntax error is `42601`. |

## Names

| # | question | answer |
| ---: | --- | --- |
| 9 | What `id` and `rid` are | Changed by #240, which replaces the choice of #38. The record id is the column `rid` of every table, however its type was made. `id` is only a column that the type declares (the primary key when a unique index covers it), and a table without one answers `42703` for it. A record id is `text`, such as `#12:3`. |
| 10 | `source_id` and `target_id` | The record ids of the out and in vertices as text, read from the edge record without loading a vertex. Changed by #213 for an end that declares a unique column of the table at that end (`source_column`, `target_column`): it is the value of that column in the record at the end, with its type. |
| 11 | Which table the edge foreign keys point to | Only what the edge type declares in its custom metadata (`source_table`, `target_table`). Never inferred from the data; no foreign key without a declaration. A type that connects several vertex types names a common supertype. |
| 12 | Inheritance | A supertype table includes the rows of its subtypes. `FROM ONLY t` leaves them out. |
| 13 | Undeclared properties | Hidden: the table is the declared schema, and an unknown column is `42703` with a hint. |
| 14 | A `LINK` column | Named as the property (`buyer`), holding the record id of the target as text, like `rid`. |
| 15 | Column types | One mapping shared with the wire module: scalar lists are arrays, maps and embedded values are `jsonb`, a `LINK` is `text`. |
| 16 | Schema name | `public`; the database name is accepted as an alias. |
| 17 | Lightweight edges | No `rid` column. The primary key is `(source_id, target_id)` when the type is unique, none otherwise. |
| 18 | A bidirectional edge | One row for each edge record, `source_id` the out vertex and `target_id` the in vertex. |

## Semantics

| # | question | answer |
| ---: | --- | --- |
| 19 | Expression evaluation | An own evaluator with PostgreSQL's rules. Decided before a parity report existed; the first report, [posted in #1](https://github.com/humemai/arcadedb/issues/1#issuecomment-7163071237), found that `sql` gives different cells from PostgreSQL 18 for 21 of the 86 single-table statements that both run. |
| 20 | `SELECT *` over a join, duplicate names | Columns in table order. Duplicate labels stay in the described columns; the keys of a `Result` are made unique with `_1`, `_2`. |
| 21 | An absent property and `NULL` | No difference: both are `NULL`. |

## The warning

| # | question | answer |
| ---: | --- | --- |
| 22 | Support, place, and sameness | A join is supported by an edge pattern, a `LINK` to the record id of its target, or an index on a join key. The warning goes to the server log only. The same statement is the parsed text with its literals replaced. |

## Wire and catalog

| # | question | answer |
| ---: | --- | --- |
| 23 | Catalog visibility | The relational names are shown only to a session that speaks the language. |
| 24 | Session language | The custom setting `arcadedb.language`, set by the startup `options` or by `SET`. Session commands stay with the wire. A write is never handed to `sql`: it was refused with `0A000` until writes were added to the language (#29). |

## Evidence

| # | question | answer |
| ---: | --- | --- |
| 25 | Threshold | The ratio of the median times, join over openCypher, at most 1.3, with 5 interleaved runs per arm after one warm-up. From 1.3 to 2.0 is reported and not claimed. Measured in #13. |

## One statement for each answer about names and semantics

The statements run on the `small` fixture (`engine/src/test/resources/postgresql/small-schema.sql`). Each case id is a line of `engine/src/test/resources/postgresql/corpus/answers.tsv`, whose rows are what PostgreSQL 18 answered and what the engine tests compare with. A record id such as `#2:0` is the number that the fixture file gives the row; the real ids depend on the database. The `small` fixture has no subtype and no lightweight edge type, so questions 12 and 17 point at a unit test with its own types.

### Question 9

Case `st-person-id`.

```sql
SELECT rid, pid FROM person ORDER BY pid
```

Columns `rid, pid`: ("#2:0", 1)<br>("#2:1", 2)<br>("#2:2", 3)<br>("#2:3", 4)<br>("#2:4", 5)

Case `err-240-select-undeclared-id`.

```sql
SELECT id FROM person
```

Refused, `42703`.

### Question 10

Case `st-works-at`.

```sql
SELECT source_id, target_id, since FROM works_at ORDER BY since
```

Columns `source_id, target_id, since`: ("#2:0", "#1:0", 2019)<br>("#2:1", "#1:0", 2020)<br>("#2:2", "#1:1", 2021)<br>("#2:0", "#1:0", 2022)<br>("#2:3", "#1:1", 2023)

### Question 11

Case `is-fk-referential-join`. `works_at` declares `person` and `company`.

```sql
SELECT rc.constraint_name, kcu.table_name, kcu.column_name, rc.unique_constraint_name, pk.table_name AS referred_table, pk.column_name AS referred_column FROM information_schema.referential_constraints rc JOIN information_schema.key_column_usage kcu ON kcu.constraint_schema = rc.constraint_schema AND kcu.constraint_name = rc.constraint_name JOIN information_schema.key_column_usage pk ON pk.constraint_schema = rc.unique_constraint_schema AND pk.constraint_name = rc.unique_constraint_name AND pk.ordinal_position = kcu.position_in_unique_constraint ORDER BY rc.constraint_name
```

Columns `constraint_name, table_name, column_name, unique_constraint_name, referred_table, referred_column`: ("orders_buyer_fkey", "orders", "buyer", "person_pkey", "person", "rid")<br>("works_at_source_id_fkey", "works_at", "source_id", "person_pkey", "person", "rid")<br>("works_at_target_id_fkey", "works_at", "target_id", "company_pkey", "company", "rid")

### Question 12

Unit test `SelectPlanTest.onlyExcludesTheSubtypes`.

```sql
SELECT name FROM ONLY animal ORDER BY name
```

One row, `a`: the subtype row `d` is left out.

### Question 13

Case `err-unknown-column`.

```sql
SELECT nosuch FROM person
```

Refused, `42703`.

### Question 14

Case `st-orders-link`.

```sql
SELECT oid, buyer FROM orders ORDER BY oid
```

Columns `oid, buyer`: (100, "#2:0")<br>(101, "#2:0")<br>(102, "#2:1")<br>(103, "#2:3")<br>(104, "#2:3")<br>(105, NULL)

### Question 15

Case `st-orders-array`.

```sql
SELECT oid, tags FROM orders ORDER BY oid
```

Columns `oid, tags`: (100, ["gift", "express"])<br>(101, ["gift"])<br>(102, ["express"])<br>(103, [])<br>(104, ["gift"])<br>(105, [])

### Question 16

Case `is-schemata`.

```sql
SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('public', 'information_schema') ORDER BY schema_name
```

Columns `schema_name`: ("information_schema")<br>("public")

### Question 17

Unit test `SelectPlanTest.aLightweightEdgeTableIsReadByWalkingTheVertices`.

```sql
SELECT rid FROM lknows
```

Refused, `42703`.

### Question 18

Case `corr-edge-degree`. Five edge records, five rows: Ada has two, Ed none.

```sql
SELECT p.name, (SELECT count(*) FROM works_at w WHERE w.source_id = p.rid) AS jobs FROM person p ORDER BY p.pid
```

Columns `name, jobs`: ("Ada", 2)<br>("Bo", 1)<br>("Cy", 1)<br>("Di", 1)<br>("Ed", 0)

### Question 19

Case `st-integer-division`.

```sql
SELECT pid, age / 5 AS q, age % 5 AS r FROM person ORDER BY pid
```

Columns `pid, q, r`: (1, 7, 1)<br>(2, 8, 1)<br>(3, NULL, NULL)<br>(4, 5, 4)<br>(5, 10, 2)

### Question 20

Case `join-edge`.

```sql
SELECT p.name, c.name, w.since FROM person p JOIN works_at w ON w.source_id = p.rid JOIN company c ON w.target_id = c.rid ORDER BY p.name, w.since
```

Columns `name, name, since`: ("Ada", "Acme", 2019)<br>("Ada", "Acme", 2022)<br>("Bo", "Acme", 2020)<br>("Cy", "Birch", 2021)<br>("Di", "Birch", 2023)

### Question 21

Case `st-where-is-null`. Cy's record has no `age` property.

```sql
SELECT name FROM person WHERE age IS NULL ORDER BY pid
```

Columns `name`: ("Cy")
