# The same operations in four query languages

Fork only (`humemai/arcadedb`, branch `feat/postgresql-prototype`). This page shows each operation in ArcadeDB `sql`, ArcadeDB `postgresql` (the prototype), PostgreSQL 18, and ArcadeDB openCypher, over the same data. The test [`PostgreSQLExamplesPageTest`](../postgresw/src/test/java/com/arcadedb/postgres/PostgreSQLExamplesPageTest.java) reads this file, runs every code block on it, and compares the answers with the tables below, so the page cannot drift from what runs. What the language supports in general is on the [supported syntax page](postgresql-supported-syntax.md).

I used the nine tasks of the timed study on fork issue #13 as the starting point, with sample values: point lookup, latest rows, grouped count and sum, filtered read, neighbour list, single insert, update by key, delete by key, and batch insert.

## How to read this page

- The line above a block names the systems that run that text. Where ArcadeDB `sql` and ArcadeDB `postgresql` are on one line, their text is the same.
- A "Result" or "After" table that names no system is the answer of PostgreSQL 18, and every other system with a query for that operation gives the same one. A table that names a system is a real difference.
- For a write, "After" is the answer of the "Check" statement, run once the writes are done: by the `postgresql` language on the same ArcadeDB database, and by PostgreSQL 18 on its own tables.
- Row order is compared where the PostgreSQL 18 text has `ORDER BY`. Elsewhere the rows are compared without order, duplicates included.
- "No query" means that I show none for that system, and why. A block marked "refuses this text" is checked to fail in that language.
- `SQLSTATE` with `00000` means that the statement succeeded.
- Every operation starts from the data below. No write carries over to the next operation.
- The sections "Transactions" and "Errors a client sees" run over the PostgreSQL wire protocol, for all four. Everything else, including every "refuses this text" block, runs on ArcadeDB embedded and on PostgreSQL 18 through JDBC.
- PostgreSQL 18 is the `pgvector/pgvector:pg18` image (for the vector search) with the `C` collation.

## Overview

<!-- overview: generated from the sections below, and checked by the test -->

| Operation | `sql` and `postgresql` text | Answers differently | No query in |
| --- | --- | --- | --- |
| Point lookup by the indexed key | same | none | none |
| Filtered read on a property without an index | same | none | none |
| Latest rows with a filter, ORDER BY and LIMIT | same | none | none |
| ORDER BY on a column with a missing value | same | `sql` | none |
| Count and sum with GROUP BY | same | none | none |
| GROUP BY and HAVING | different | none | none |
| IS DISTINCT FROM | different | none | none |
| Case-insensitive match with ILIKE | same | none | none |
| Joining strings with string_agg | different | none | none |
| Collecting values into an array | different | `postgresql` | none |
| A table of constants with VALUES | different | none | none |
| A generated uuid | different | none | none |
| A cast with a type modifier | no `sql` query | none | `sql`, openCypher |
| Neighbour list from one vertex | different | none | none |
| Inner join | different | none | none |
| Parallel edges | different | none | none |
| LEFT JOIN with an unmatched row | different | none | none |
| Join with aggregation | different | none | none |
| Two-hop join | different | none | none |
| Everyone reachable from one vertex | different | `postgresql` | none |
| Correlated EXISTS | different | none | none |
| Correlated scalar subquery | different | none | none |
| WITH, a common table expression | different | none | none |
| CROSS JOIN | different | none | none |
| RIGHT JOIN on a value | no `sql` query | none | `sql` |
| FULL JOIN on a value | no `sql` query | none | `sql` |
| Window functions over a partition | no `sql` query | none | `sql` |
| Running totals with the default frame and a ROWS frame | no `sql` query | none | `sql` |
| A moving window frame | no `sql` query | `postgresql` | `sql` |
| Reading an array and a jsonb value | same | none | none |
| Filtering on an element of an array | different | `postgresql` | none |
| Filtering on a field of a jsonb value | different | `postgresql` | none |
| Vector search for the two nearest rows | different | none | none |
| Inserting one record and returning it | same | none | none |
| Batch insert in one statement | same | none | none |
| Inserting the rows of a query | different | none | none |
| Inserting an edge | different | none | none |
| Upsert that updates | different | none | none |
| Upsert that leaves the existing row | different | none | none |
| Update by key | same | none | none |
| Update by a join | different | none | none |
| Update with a subquery | different | none | none |
| Delete by key | same | none | none |
| Delete with a subquery | same | none | none |
| Delete an edge | same | none | none |
| Delete a vertex that has edges | same | none | none |
| Writing an array | different | none | none |
| Writing a jsonb value | different | none | openCypher |
| Writing binary data | different | none | openCypher |
| Writing a vector | different | none | none |
| Rolling a transaction back | same | none | openCypher |
| Committing a transaction | same | none | openCypher |
| Syntax error | same | none | none |
| A WHERE with no condition | same | `sql` | none |
| A DELETE with no condition after WHERE | same | `sql` | none |
| Unknown column | same | `sql`, openCypher | none |
| Unknown table | same | openCypher | none |
| Duplicate key | same | none | none |
| Giving a value to the record id column | same | `sql`, openCypher | none |
| A jsonb array where the property holds a map | different | `sql`, `postgresql`, openCypher | none |
| A statement after an error inside a transaction | same | none | openCypher |
| BEGIN on a JDBC connection | same | openCypher | none |

## The data

The same records serve all three ArcadeDB languages. A vertex type or document type is a table, and an edge type is a table with `source_id` and `target_id`, which hold record ids. A type with a property `id` shows its record id as `rid`. In PostgreSQL 18 I declare `rid` as an identity column, and foreign keys with `ON DELETE CASCADE` so that a deleted person takes its edges along.

ArcadeDB `sql` setup, schema:

```sql
CREATE VERTEX TYPE person;
CREATE PROPERTY person.id LONG;
CREATE PROPERTY person.name STRING;
CREATE PROPERTY person.age INTEGER;
CREATE PROPERTY person.city STRING;
CREATE PROPERTY person.created LONG;
CREATE INDEX ON person (id) UNIQUE;
CREATE EDGE TYPE knows CUSTOM source_table = 'person', target_table = 'person';
CREATE PROPERTY knows.since INTEGER;
CREATE VERTEX TYPE memory;
CREATE PROPERTY memory.id LONG;
CREATE PROPERTY memory.name STRING;
CREATE PROPERTY memory.age INTEGER;
CREATE PROPERTY memory.city STRING;
CREATE PROPERTY memory.created LONG;
CREATE PROPERTY memory.tags LIST OF STRING;
CREATE PROPERTY memory.meta MAP;
CREATE PROPERTY memory.payload BINARY;
CREATE PROPERTY memory.embedding ARRAY_OF_FLOATS;
CREATE INDEX ON memory (id) UNIQUE;
CREATE INDEX ON memory (embedding) LSM_VECTOR METADATA {"dimensions": 3, "similarity": "EUCLIDEAN"};
```

ArcadeDB `sql` setup, data:

```sql
INSERT INTO person (id, name, age, city, created) VALUES (1, 'Ada', 36, 'Delft', 100), (2, 'Bo', 41, 'Delft', 200), (3, 'Cy', 29, 'Leiden', 300), (4, 'Di', 36, 'Leiden', 400), (5, 'Ed', NULL, 'Utrecht', 500);
CREATE EDGE knows FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 2) SET since = 2019;
CREATE EDGE knows FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 2) SET since = 2022;
CREATE EDGE knows FROM (SELECT FROM person WHERE id = 1) TO (SELECT FROM person WHERE id = 3) SET since = 2020;
CREATE EDGE knows FROM (SELECT FROM person WHERE id = 2) TO (SELECT FROM person WHERE id = 3) SET since = 2021;
CREATE EDGE knows FROM (SELECT FROM person WHERE id = 3) TO (SELECT FROM person WHERE id = 4) SET since = 2023;
CREATE EDGE knows FROM (SELECT FROM person WHERE id = 4) TO (SELECT FROM person WHERE id = 1) SET since = 2024;
INSERT INTO memory (id, name, age, city, created, tags, meta, embedding) VALUES (1, 'Ada', 36, 'Delft', 100, ['graph', 'sql'], {"source": "chat", "score": 3}, [1.0, 0.0, 0.0]), (2, 'Bo', 29, 'Delft', 200, ['sql'], {"source": "mail", "score": 5}, [0.0, 1.0, 0.0]), (3, 'Cy', 52, 'Leiden', 300, [], {"source": "chat", "score": 1}, [0.9, 0.1, 0.0]);
```

PostgreSQL 18 setup, schema:

```sql
CREATE EXTENSION vector;
CREATE TABLE person (rid bigint GENERATED ALWAYS AS IDENTITY UNIQUE, id bigint PRIMARY KEY, name text, age int, city text, created bigint);
CREATE TABLE knows (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, source_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, target_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int);
CREATE INDEX knows_source ON knows (source_id);
CREATE INDEX knows_target ON knows (target_id);
CREATE TABLE memory (rid bigint GENERATED ALWAYS AS IDENTITY UNIQUE, id bigint PRIMARY KEY, name text, age int, city text, created bigint, tags text[], meta jsonb, payload bytea, embedding vector(3));
```

PostgreSQL 18 setup, data:

```sql
INSERT INTO person (id, name, age, city, created) VALUES (1, 'Ada', 36, 'Delft', 100), (2, 'Bo', 41, 'Delft', 200), (3, 'Cy', 29, 'Leiden', 300), (4, 'Di', 36, 'Leiden', 400), (5, 'Ed', NULL, 'Utrecht', 500);
INSERT INTO knows (source_id, target_id, since) SELECT p.rid, q.rid, e.since FROM (VALUES (1, 2, 2019), (1, 2, 2022), (1, 3, 2020), (2, 3, 2021), (3, 4, 2023), (4, 1, 2024)) AS e(a, b, since) JOIN person p ON p.id = e.a JOIN person q ON q.id = e.b;
INSERT INTO memory (id, name, age, city, created, tags, meta, embedding) VALUES (1, 'Ada', 36, 'Delft', 100, '{graph,sql}', '{"source": "chat", "score": 3}', '[1,0,0]'), (2, 'Bo', 29, 'Delft', 200, '{sql}', '{"source": "mail", "score": 5}', '[0,1,0]'), (3, 'Cy', 52, 'Leiden', 300, '{}', '{"source": "chat", "score": 1}', '[0.9,0.1,0]');
```

## Reads

### Point lookup by the indexed key

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id, name, age, city, created FROM person WHERE id = 3;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.id = 3 RETURN p.id AS id, p.name AS name, p.age AS age, p.city AS city, p.created AS created;
```

Result:

| id | name | age | city | created |
| --- | --- | --- | --- | --- |
| 3 | Cy | 29 | Leiden | 300 |

### Filtered read on a property without an index

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id, name, age FROM person WHERE city = 'Leiden';
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.city = 'Leiden' RETURN p.id AS id, p.name AS name, p.age AS age;
```

Result:

| id | name | age |
| --- | --- | --- |
| 3 | Cy | 29 |
| 4 | Di | 36 |

### Latest rows with a filter, ORDER BY and LIMIT

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id, name, created FROM person WHERE age >= 30 ORDER BY created DESC, id DESC LIMIT 2;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.age >= 30 RETURN p.id AS id, p.name AS name, p.created AS created ORDER BY created DESC, id DESC LIMIT 2;
```

Result:

| id | name | created |
| --- | --- | --- |
| 4 | Di | 400 |
| 2 | Bo | 200 |

### ORDER BY on a column with a missing value

ArcadeDB `sql` puts the missing value first in ascending order. The other three put it last.

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name, age FROM person ORDER BY age, name;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) RETURN p.name AS name, p.age AS age ORDER BY age, name;
```

Result:

| name | age |
| --- | --- |
| Cy | 29 |
| Ada | 36 |
| Di | 36 |
| Bo | 41 |
| Ed | NULL |

Result, ArcadeDB `sql` (differs):

| name | age |
| --- | --- |
| Ed | NULL |
| Cy | 29 |
| Ada | 36 |
| Di | 36 |
| Bo | 41 |

### Count and sum with GROUP BY

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT age, count(*) AS n, sum(created) AS total FROM person GROUP BY age;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) RETURN p.age AS age, count(*) AS n, sum(p.created) AS total;
```

Result:

| age | n | total |
| --- | --- | --- |
| 29 | 1 | 300 |
| 36 | 2 | 500 |
| 41 | 1 | 200 |
| NULL | 1 | 500 |

### GROUP BY and HAVING

ArcadeDB `sql` has no `HAVING`, so the group is filtered by an outer query.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT city, count(*) AS n, sum(created) AS total FROM person GROUP BY city HAVING count(*) > 1 ORDER BY city;
```

ArcadeDB `sql`:

```sql
SELECT FROM (SELECT city, count(*) AS n, sum(created) AS total FROM person GROUP BY city) WHERE n > 1 ORDER BY city;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WITH p.city AS city, count(*) AS n, sum(p.created) AS total WHERE n > 1 RETURN city, n, total ORDER BY city;
```

ArcadeDB `sql` refuses this text:

```sql
SELECT city, count(*) AS n, sum(created) AS total FROM person GROUP BY city HAVING count(*) > 1 ORDER BY city;
```

Result:

| city | n | total |
| --- | --- | --- |
| Delft | 2 | 300 |
| Leiden | 2 | 700 |

### IS DISTINCT FROM

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name FROM person WHERE age IS DISTINCT FROM 36 ORDER BY name;
```

ArcadeDB `sql`:

```sql
SELECT name FROM person WHERE age IS NULL OR age <> 36 ORDER BY name;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.age IS NULL OR p.age <> 36 RETURN p.name AS name ORDER BY name;
```

Result:

| name |
| --- |
| Bo |
| Cy |
| Ed |

### Case-insensitive match with ILIKE

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name FROM person WHERE name ILIKE '%D%' ORDER BY name;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE toLower(p.name) CONTAINS toLower('D') RETURN p.name AS name ORDER BY name;
```

Result:

| name |
| --- |
| Ada |
| Di |
| Ed |

### Joining strings with string_agg

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT string_agg(DISTINCT city, ', ') AS cities FROM person;
```

ArcadeDB `sql`:

```sql
SELECT set(city).sort().join(', ') AS cities FROM person;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WITH DISTINCT p.city AS city ORDER BY city WITH collect(city) AS cities RETURN reduce(s = head(cities), c IN tail(cities) | s + ', ' + c) AS cities;
```

Result:

| cities |
| --- |
| Delft, Leiden, Utrecht |

### Collecting values into an array

The prototype refuses `array_agg`.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT array_agg(name) AS names FROM person WHERE id = 1;
```

ArcadeDB `sql`:

```sql
SELECT list(name) AS names FROM person WHERE id = 1;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.id = 1 RETURN collect(p.name) AS names;
```

Result:

| names |
| --- |
| [Ada] |

Result, ArcadeDB `postgresql` (differs):

| SQLSTATE |
| --- |
| 0A000 |

### A table of constants with VALUES

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT v.n AS n, v.word AS word FROM (VALUES (1, 'one'), (2, 'two')) AS v(n, word) ORDER BY n;
```

ArcadeDB `sql`:

```sql
SELECT n, word FROM (SELECT expand([{"n": 1, "word": "one"}, {"n": 2, "word": "two"}])) ORDER BY n;
```

ArcadeDB openCypher:

```cypher
UNWIND [{n: 1, word: 'one'}, {n: 2, word: 'two'}] AS v RETURN v.n AS n, v.word AS word ORDER BY n;
```

Result:

| n | word |
| --- | --- |
| 1 | one |
| 2 | two |

### A generated uuid

The length is shown because the value is random.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT length(gen_random_uuid()::text) AS n;
```

ArcadeDB `sql`:

```sql
SELECT uuid().length() AS n;
```

ArcadeDB openCypher:

```cypher
RETURN size(randomUUID()) AS n;
```

Result:

| n |
| --- |
| 36 |

### A cast with a type modifier

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT 2.345::numeric(4,2) AS price, 'abcdef'::varchar(3) AS code;
```

ArcadeDB `sql`: no query. A cast with a precision or a length is not part of the language.

ArcadeDB `sql` refuses this text:

```sql
SELECT 2.345::numeric(4,2) AS price, 'abcdef'::varchar(3) AS code;
```

ArcadeDB openCypher: no query. A cast with a precision or a length is not part of the language.

ArcadeDB openCypher refuses this text:

```cypher
RETURN 2.345::numeric(4,2) AS price, 'abcdef'::varchar(3) AS code;
```

Result:

| price | code |
| --- | --- |
| 2.35 | abc |

## Joins and graph reads

### Neighbour list from one vertex

`sql` walks the edges of a record, `postgresql` joins through the edge table.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT q.id AS qid, q.name AS name, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON k.target_id = q.rid WHERE p.id = 1 ORDER BY since;
```

ArcadeDB `sql`:

```sql
SELECT in.id AS qid, in.name AS name, since FROM (SELECT expand(outE('knows')) FROM person WHERE id = 1) ORDER BY since;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person)-[k:knows]->(q:person) WHERE p.id = 1 RETURN q.id AS qid, q.name AS name, k.since AS since ORDER BY since;
```

Result:

| qid | name | since |
| --- | --- | --- |
| 2 | Bo | 2019 |
| 3 | Cy | 2020 |
| 2 | Bo | 2022 |

### Inner join

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT p.name AS name, q.name AS friend, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON q.rid = k.target_id WHERE k.since >= 2022 ORDER BY since;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, as: p}.outE('knows'){as: k, where: (since >= 2022)}.inV(){as: q} RETURN p.name AS name, q.name AS friend, k.since AS since ORDER BY since;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person)-[k:knows]->(q:person) WHERE k.since >= 2022 RETURN p.name AS name, q.name AS friend, k.since AS since ORDER BY since;
```

Result:

| name | friend | since |
| --- | --- | --- |
| Ada | Bo | 2022 |
| Cy | Di | 2023 |
| Di | Ada | 2024 |

### Parallel edges

Two edges between the same two people are two rows.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT q.name AS friend, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON q.rid = k.target_id WHERE p.id = 1 AND q.id = 2 ORDER BY since;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, where: (id = 1)}.outE('knows'){as: k}.inV(){as: q, where: (id = 2)} RETURN q.name AS friend, k.since AS since ORDER BY since;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person)-[k:knows]->(q:person) WHERE p.id = 1 AND q.id = 2 RETURN q.name AS friend, k.since AS since ORDER BY since;
```

Result:

| friend | since |
| --- | --- |
| Bo | 2019 |
| Bo | 2022 |

### LEFT JOIN with an unmatched row

Ed knows nobody and stays in the answer. Ada appears twice with Bo, once for each edge.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT p.name AS name, q.name AS friend FROM person p LEFT JOIN knows k ON k.source_id = p.rid LEFT JOIN person q ON q.rid = k.target_id ORDER BY name, friend;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, as: p}.out('knows'){as: q, optional: true} RETURN p.name AS name, q.name AS friend ORDER BY name, friend;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) OPTIONAL MATCH (p)-[:knows]->(q:person) RETURN p.name AS name, q.name AS friend ORDER BY name, friend;
```

Result:

| name | friend |
| --- | --- |
| Ada | Bo |
| Ada | Bo |
| Ada | Cy |
| Bo | Cy |
| Cy | Di |
| Di | Ada |
| Ed | NULL |

### Join with aggregation

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT p.name AS name, count(*) AS n, min(k.since) AS first_since FROM person p JOIN knows k ON k.source_id = p.rid GROUP BY p.name ORDER BY n DESC, name;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, as: p}.outE('knows'){as: k} RETURN p.name AS name, count(*) AS n, min(k.since) AS first_since GROUP BY name ORDER BY n DESC, name;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person)-[k:knows]->(:person) RETURN p.name AS name, count(*) AS n, min(k.since) AS first_since ORDER BY n DESC, name;
```

Result:

| name | n | first_since |
| --- | --- | --- |
| Ada | 3 | 2019 |
| Bo | 1 | 2021 |
| Cy | 1 | 2023 |
| Di | 1 | 2024 |

### Two-hop join

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT b.name AS via, c.name AS name FROM person a JOIN knows k1 ON k1.source_id = a.rid JOIN person b ON b.rid = k1.target_id JOIN knows k2 ON k2.source_id = b.rid JOIN person c ON c.rid = k2.target_id WHERE a.id = 1 ORDER BY via, name;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, where: (id = 1)}.out('knows'){as: b}.out('knows'){as: c} RETURN b.name AS via, c.name AS name ORDER BY via, name;
```

ArcadeDB openCypher:

```cypher
MATCH (a:person)-[:knows]->(b:person)-[:knows]->(c:person) WHERE a.id = 1 RETURN b.name AS via, c.name AS name ORDER BY via, name;
```

Result:

| via | name |
| --- | --- |
| Bo | Cy |
| Bo | Cy |
| Cy | Di |

### Everyone reachable from one vertex

A path of any length needs `WITH RECURSIVE` in SQL, which the prototype refuses. The other two ArcadeDB languages walk the graph.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
WITH RECURSIVE reach(rid) AS (SELECT k.target_id FROM person p JOIN knows k ON k.source_id = p.rid WHERE p.id = 2 UNION SELECT k.target_id FROM reach r JOIN knows k ON k.source_id = r.rid) SELECT p.id AS id, p.name AS name FROM reach r JOIN person p ON p.rid = r.rid ORDER BY id;
```

ArcadeDB `sql`:

```sql
SELECT id, name FROM (TRAVERSE out('knows') FROM (SELECT expand(out('knows')) FROM person WHERE id = 2)) ORDER BY id;
```

ArcadeDB openCypher:

```cypher
MATCH (a:person)-[:knows*1..]->(b:person) WHERE a.id = 2 RETURN DISTINCT b.id AS id, b.name AS name ORDER BY id;
```

Result:

| id | name |
| --- | --- |
| 1 | Ada |
| 2 | Bo |
| 3 | Cy |
| 4 | Di |

Result, ArcadeDB `postgresql` (differs):

| SQLSTATE |
| --- |
| 0A000 |

### Correlated EXISTS

People who know someone older than themselves.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id, name FROM person p WHERE EXISTS (SELECT 1 FROM knows k JOIN person q ON q.rid = k.target_id WHERE k.source_id = p.rid AND q.age > p.age) ORDER BY id;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, as: p}.out('knows'){where: (age > $matched.p.age)} RETURN DISTINCT p.id AS id, p.name AS name ORDER BY id;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE EXISTS { MATCH (p)-[:knows]->(q:person) WHERE q.age > p.age } RETURN p.id AS id, p.name AS name ORDER BY id;
```

Result:

| id | name |
| --- | --- |
| 1 | Ada |
| 3 | Cy |

### Correlated scalar subquery

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT p.name AS name, (SELECT count(*) FROM knows k WHERE k.source_id = p.rid) AS friends FROM person p ORDER BY name;
```

ArcadeDB `sql`:

```sql
SELECT name, outE('knows').size() AS friends FROM person ORDER BY name;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) RETURN p.name AS name, COUNT { (p)-[:knows]->() } AS friends ORDER BY name;
```

Result:

| name | friends |
| --- | --- |
| Ada | 3 |
| Bo | 1 |
| Cy | 1 |
| Di | 1 |
| Ed | 0 |

### WITH, a common table expression

ArcadeDB `sql` has no `WITH`; one `MATCH` pattern answers the same question.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
WITH delft AS (SELECT rid, name FROM person WHERE city = 'Delft') SELECT d.name AS name, count(*) AS n FROM delft d JOIN knows k ON k.source_id = d.rid GROUP BY d.name ORDER BY name;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, as: d, where: (city = 'Delft')}.outE('knows'){as: k} RETURN d.name AS name, count(*) AS n GROUP BY name ORDER BY name;
```

ArcadeDB openCypher:

```cypher
MATCH (d:person) WHERE d.city = 'Delft' WITH d MATCH (d)-[k:knows]->(:person) RETURN d.name AS name, count(k) AS n ORDER BY name;
```

ArcadeDB `sql` refuses this text:

```sql
WITH delft AS (SELECT rid, name FROM person WHERE city = 'Delft') SELECT d.name AS name, count(*) AS n FROM delft d JOIN knows k ON k.source_id = d.rid GROUP BY d.name ORDER BY name;
```

Result:

| name | n |
| --- | --- |
| Ada | 3 |
| Bo | 1 |

### CROSS JOIN

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT p.name AS name, m.id AS memory_id FROM person p CROSS JOIN memory m WHERE p.city = 'Delft' AND m.id <= 2 ORDER BY name, memory_id;
```

ArcadeDB `sql`:

```sql
MATCH {type: person, as: p, where: (city = 'Delft')}, {type: memory, as: m, where: (id <= 2)} RETURN p.name AS name, m.id AS memory_id ORDER BY name, memory_id;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person), (m:memory) WHERE p.city = 'Delft' AND m.id <= 2 RETURN p.name AS name, m.id AS memory_id ORDER BY name, memory_id;
```

Result:

| name | memory_id |
| --- | --- |
| Ada | 1 |
| Ada | 2 |
| Bo | 1 |
| Bo | 2 |

### RIGHT JOIN on a value

Every memory, with the people of the same age.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT p.name AS person, m.name AS memory FROM person p RIGHT JOIN memory m ON m.age = p.age ORDER BY memory, person;
```

ArcadeDB `sql`: no query. It has no outer join between records on a value (`MATCH` follows edges), and I did not write an emulation.

ArcadeDB `sql` refuses this text:

```sql
SELECT p.name AS person, m.name AS memory FROM person p RIGHT JOIN memory m ON m.age = p.age ORDER BY memory, person;
```

ArcadeDB openCypher:

```cypher
MATCH (m:memory) OPTIONAL MATCH (p:person) WHERE p.age = m.age RETURN p.name AS person, m.name AS memory ORDER BY memory, person;
```

Result:

| person | memory |
| --- | --- |
| Ada | Ada |
| Di | Ada |
| Cy | Bo |
| NULL | Cy |

### FULL JOIN on a value

openCypher has no full outer join; the union of an optional match and of the memories that nobody matches gives the same rows.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT p.name AS person, m.name AS memory FROM person p FULL JOIN memory m ON m.age = p.age;
```

ArcadeDB `sql`: no query. It has no outer join between records on a value, and I did not write an emulation.

ArcadeDB `sql` refuses this text:

```sql
SELECT p.name AS person, m.name AS memory FROM person p FULL JOIN memory m ON m.age = p.age ORDER BY person, memory;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) OPTIONAL MATCH (m:memory) WHERE m.age = p.age RETURN p.name AS person, m.name AS memory
UNION ALL
MATCH (m:memory) WHERE NOT EXISTS { MATCH (p:person) WHERE p.age = m.age } RETURN null AS person, m.name AS memory;
```

Result:

| person | memory |
| --- | --- |
| Ada | Ada |
| Bo | NULL |
| Cy | Bo |
| Di | Ada |
| Ed | NULL |
| NULL | Cy |

## Window functions

### Window functions over a partition

openCypher has no window functions. For `row_number`, `count` and `lag`, collecting each ordered group and numbering it gives the same answer.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name, city, age, row_number() OVER (PARTITION BY city ORDER BY age DESC, name) AS rn, count(*) OVER (PARTITION BY city) AS in_city, lag(name) OVER (PARTITION BY city ORDER BY age DESC, name) AS older FROM person WHERE age IS NOT NULL ORDER BY city, rn;
```

ArcadeDB `sql`: no query. It has no window functions, and I did not write an emulation.

ArcadeDB `sql` refuses this text:

```sql
SELECT name, row_number() OVER (PARTITION BY city ORDER BY age DESC, name) AS rn FROM person;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.age IS NOT NULL
WITH p ORDER BY p.age DESC, p.name
WITH p.city AS city, collect(p) AS people
UNWIND range(0, size(people) - 1) AS i
RETURN people[i].name AS name, city, people[i].age AS age, i + 1 AS rn, size(people) AS in_city, CASE WHEN i = 0 THEN null ELSE people[i - 1].name END AS older
ORDER BY city, rn;
```

ArcadeDB openCypher refuses this text:

```cypher
MATCH (p:person) RETURN p.name AS name, row_number() OVER (ORDER BY p.id) AS rn;
```

Result:

| name | city | age | rn | in_city | older |
| --- | --- | --- | --- | --- | --- |
| Bo | Delft | 41 | 1 | 2 | NULL |
| Ada | Delft | 36 | 2 | 2 | Bo |
| Di | Leiden | 36 | 1 | 2 | NULL |
| Cy | Leiden | 29 | 2 | 2 | Di |

### Running totals with the default frame and a ROWS frame

The default frame ends at the last row with the same `ORDER BY` value, the `ROWS` frame at the current row. openCypher sums over the rows that each frame holds.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name, age, sum(created) OVER (ORDER BY age) AS by_peer, sum(created) OVER (ORDER BY age, name ROWS UNBOUNDED PRECEDING) AS by_row FROM person WHERE age IS NOT NULL ORDER BY age, name;
```

ArcadeDB `sql`: no query. It has no window functions, and I did not write an emulation.

ArcadeDB `sql` refuses this text:

```sql
SELECT name, sum(created) OVER (ORDER BY age) AS by_peer FROM person;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.age IS NOT NULL
MATCH (o:person) WHERE o.age <= p.age
RETURN p.name AS name, p.age AS age, sum(o.created) AS by_peer, sum(CASE WHEN o.age < p.age OR o.name <= p.name THEN o.created ELSE 0 END) AS by_row
ORDER BY age, name;
```

Result:

| name | age | by_peer | by_row |
| --- | --- | --- | --- |
| Cy | 29 | 300 | 300 |
| Ada | 36 | 800 | 400 |
| Di | 36 | 800 | 800 |
| Bo | 41 | 1000 | 1000 |

### A moving window frame

The prototype refuses a frame with an offset. openCypher adds the row before by its position in the ordered list.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name, sum(created) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS last_two FROM person ORDER BY name;
```

ArcadeDB `sql`: no query. It has no window functions, and I did not write an emulation.

ArcadeDB `sql` refuses this text:

```sql
SELECT name, sum(created) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS last_two FROM person;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person)
WITH p ORDER BY p.id
WITH collect(p) AS people
UNWIND range(0, size(people) - 1) AS i
RETURN people[i].name AS name, people[i].created + CASE WHEN i = 0 THEN 0 ELSE people[i - 1].created END AS last_two
ORDER BY name;
```

Result:

| name | last_two |
| --- | --- |
| Ada | 100 |
| Bo | 300 |
| Cy | 500 |
| Di | 700 |
| Ed | 900 |

Result, ArcadeDB `postgresql` (differs):

| SQLSTATE |
| --- |
| 0A000 |

## Documents, arrays and vectors

### Reading an array and a jsonb value

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id, tags, meta FROM memory WHERE id = 1;
```

ArcadeDB openCypher:

```cypher
MATCH (m:memory) WHERE m.id = 1 RETURN m.id AS id, m.tags AS tags, m.meta AS meta;
```

Result:

| id | tags | meta |
| --- | --- | --- |
| 1 | [graph, sql] | {score: 3, source: chat} |

### Filtering on an element of an array

The prototype writes and reads arrays but refuses `= ANY`.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id FROM memory WHERE 'graph' = ANY (tags) ORDER BY id;
```

ArcadeDB `sql`:

```sql
SELECT id FROM memory WHERE tags CONTAINS 'graph' ORDER BY id;
```

ArcadeDB openCypher:

```cypher
MATCH (m:memory) WHERE 'graph' IN m.tags RETURN m.id AS id ORDER BY id;
```

Result:

| id |
| --- |
| 1 |

Result, ArcadeDB `postgresql` (differs):

| SQLSTATE |
| --- |
| 0A000 |

### Filtering on a field of a jsonb value

The prototype writes and reads `jsonb` but refuses `->>`.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id FROM memory WHERE meta->>'source' = 'chat' ORDER BY id;
```

ArcadeDB `sql`:

```sql
SELECT id FROM memory WHERE meta.source = 'chat' ORDER BY id;
```

ArcadeDB openCypher:

```cypher
MATCH (m:memory) WHERE m.meta.source = 'chat' RETURN m.id AS id ORDER BY id;
```

Result:

| id |
| --- |
| 1 |
| 3 |

Result, ArcadeDB `postgresql` (differs):

| SQLSTATE |
| --- |
| 0A000 |

### Vector search for the two nearest rows

`postgresql` and PostgreSQL 18 use the distance operator of pgvector. `sql` and openCypher call the vector index of ArcadeDB.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT id, name FROM memory ORDER BY embedding <-> '[1,0,0]' LIMIT 2;
```

ArcadeDB `sql`:

```sql
SELECT id, name FROM (SELECT expand(`vector.neighbors`('memory[embedding]', [1.0, 0.0, 0.0], 2)));
```

ArcadeDB openCypher:

```cypher
CALL db.index.vector.queryNodes('memory[embedding]', 2, [1.0, 0.0, 0.0]) YIELD node RETURN node.id AS id, node.name AS name;
```

Result:

| id | name |
| --- | --- |
| 1 | Ada |
| 3 | Cy |

## Writes

### Inserting one record and returning it

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, name, age, city, created) VALUES (4, 'Di', 41, 'Leiden', 400) RETURNING id, name;
```

ArcadeDB openCypher:

```cypher
CREATE (m:memory {id: 4, name: 'Di', age: 41, city: 'Leiden', created: 400}) RETURN m.id AS id, m.name AS name;
```

Check:

```sql
SELECT id, name, age, city, created FROM memory WHERE id >= 3 ORDER BY id;
```

Result:

| id | name |
| --- | --- |
| 4 | Di |

After:

| id | name | age | city | created |
| --- | --- | --- | --- | --- |
| 3 | Cy | 52 | Leiden | 300 |
| 4 | Di | 41 | Leiden | 400 |

### Batch insert in one statement

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, name, age) VALUES (4, 'Di', 41), (5, 'Ed', 23);
```

ArcadeDB openCypher:

```cypher
UNWIND [{id: 4, name: 'Di', age: 41}, {id: 5, name: 'Ed', age: 23}] AS r CREATE (:memory {id: r.id, name: r.name, age: r.age});
```

Check:

```sql
SELECT id, name, age FROM memory WHERE id >= 4 ORDER BY id;
```

After:

| id | name | age |
| --- | --- | --- |
| 4 | Di | 41 |
| 5 | Ed | 23 |

### Inserting the rows of a query

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, name, age) SELECT id + 100, name, NULL FROM person WHERE city = 'Leiden';
```

ArcadeDB `sql`:

```sql
INSERT INTO memory FROM SELECT id + 100 AS id, name, NULL AS age FROM person WHERE city = 'Leiden';
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.city = 'Leiden' CREATE (:memory {id: p.id + 100, name: p.name});
```

Check:

```sql
SELECT id, name, age FROM memory WHERE id > 100 ORDER BY id;
```

After:

| id | name | age |
| --- | --- | --- |
| 103 | Cy | NULL |
| 104 | Di | NULL |

### Inserting an edge

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO knows (source_id, target_id, since) SELECT p.rid, q.rid, 2025 FROM person p, person q WHERE p.id = 5 AND q.id = 1;
```

ArcadeDB `sql`:

```sql
CREATE EDGE knows FROM (SELECT FROM person WHERE id = 5) TO (SELECT FROM person WHERE id = 1) SET since = 2025;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person), (q:person) WHERE p.id = 5 AND q.id = 1 CREATE (p)-[:knows {since: 2025}]->(q);
```

Check:

```sql
SELECT p.name AS name, q.name AS friend, k.since AS since FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON q.rid = k.target_id WHERE k.since >= 2024 ORDER BY since;
```

After:

| name | friend | since |
| --- | --- | --- |
| Di | Ada | 2024 |
| Ed | Ada | 2025 |

### Upsert that updates

One key is there and one is new. In `sql` I write one `UPSERT` statement for each key.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, name, age) VALUES (1, 'Ada', 37), (4, 'Di', 41) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, age = EXCLUDED.age;
```

ArcadeDB `sql`:

```sql
UPDATE memory SET id = 1, name = 'Ada', age = 37 UPSERT WHERE id = 1;
UPDATE memory SET id = 4, name = 'Di', age = 41 UPSERT WHERE id = 4;
```

ArcadeDB openCypher:

```cypher
UNWIND [{id: 1, name: 'Ada', age: 37}, {id: 4, name: 'Di', age: 41}] AS r MERGE (m:memory {id: r.id}) SET m.name = r.name, m.age = r.age;
```

Check:

```sql
SELECT id, name, age, city FROM memory WHERE id IN (1, 4) ORDER BY id;
```

After:

| id | name | age | city |
| --- | --- | --- | --- |
| 1 | Ada | 37 | Delft |
| 4 | Di | 41 | NULL |

### Upsert that leaves the existing row

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, name) VALUES (1, 'Other'), (4, 'Di') ON CONFLICT (id) DO NOTHING;
```

ArcadeDB `sql`:

```sql
INSERT INTO memory (id, name) VALUES (1, 'Other'), (4, 'Di') ON DUPLICATE KEY SKIP;
```

ArcadeDB openCypher:

```cypher
UNWIND [{id: 1, name: 'Other'}, {id: 4, name: 'Di'}] AS r MERGE (m:memory {id: r.id}) ON CREATE SET m.name = r.name;
```

Check:

```sql
SELECT id, name FROM memory WHERE id IN (1, 4) ORDER BY id;
```

After:

| id | name |
| --- | --- |
| 1 | Ada |
| 4 | Di |

### Update by key

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
UPDATE memory SET age = 37 WHERE id = 1;
```

ArcadeDB openCypher:

```cypher
MATCH (m:memory) WHERE m.id = 1 SET m.age = 37;
```

Check:

```sql
SELECT id, age FROM memory ORDER BY id;
```

After:

| id | age |
| --- | --- |
| 1 | 37 |
| 2 | 29 |
| 3 | 52 |

### Update by a join

Everyone Ada knows moves to Ada's city.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
UPDATE person AS q SET city = p.city FROM knows k, person p WHERE k.target_id = q.rid AND p.rid = k.source_id AND p.id = 1;
```

ArcadeDB `sql`:

```sql
UPDATE person SET city = (SELECT city FROM person WHERE id = 1)[0].city WHERE in('knows').id CONTAINS 1;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person)-[:knows]->(q:person) WHERE p.id = 1 SET q.city = p.city;
```

Check:

```sql
SELECT name, city FROM person ORDER BY name;
```

After:

| name | city |
| --- | --- |
| Ada | Delft |
| Bo | Delft |
| Cy | Delft |
| Di | Leiden |
| Ed | Utrecht |

### Update with a subquery

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
UPDATE memory SET age = (SELECT max(age) FROM person) WHERE id = 1;
```

ArcadeDB `sql`:

```sql
UPDATE memory SET age = (SELECT max(age) AS oldest FROM person)[0].oldest WHERE id = 1;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WITH max(p.age) AS oldest MATCH (m:memory) WHERE m.id = 1 SET m.age = oldest;
```

Check:

```sql
SELECT id, age FROM memory WHERE id = 1;
```

After:

| id | age |
| --- | --- |
| 1 | 41 |

### Delete by key

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
DELETE FROM memory WHERE id = 3;
```

ArcadeDB openCypher:

```cypher
MATCH (m:memory) WHERE m.id = 3 DELETE m;
```

Check:

```sql
SELECT id FROM memory ORDER BY id;
```

After:

| id |
| --- |
| 1 |
| 2 |

### Delete with a subquery

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
DELETE FROM memory WHERE id IN (SELECT id FROM person WHERE city = 'Delft');
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.city = 'Delft' WITH collect(p.id) AS ids MATCH (m:memory) WHERE m.id IN ids DELETE m;
```

Check:

```sql
SELECT id FROM memory ORDER BY id;
```

After:

| id |
| --- |
| 3 |

### Delete an edge

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
DELETE FROM knows WHERE since = 2022;
```

ArcadeDB openCypher:

```cypher
MATCH (:person)-[k:knows]->(:person) WHERE k.since = 2022 DELETE k;
```

Check:

```sql
SELECT since FROM knows ORDER BY since;
```

After:

| since |
| --- |
| 2019 |
| 2020 |
| 2021 |
| 2023 |
| 2024 |

### Delete a vertex that has edges

The edges go with the vertex: in ArcadeDB by itself, in PostgreSQL 18 because of `ON DELETE CASCADE`. openCypher asks for `DETACH`.

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
DELETE FROM person WHERE id = 3;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.id = 3 DETACH DELETE p;
```

ArcadeDB openCypher refuses this text:

```cypher
MATCH (p:person) WHERE p.id = 3 DELETE p;
```

Check:

```sql
SELECT p.name AS name, k.since AS since FROM person p LEFT JOIN knows k ON k.source_id = p.rid ORDER BY name, since;
```

After:

| name | since |
| --- | --- |
| Ada | 2019 |
| Ada | 2022 |
| Bo | NULL |
| Di | 2024 |
| Ed | NULL |

### Writing an array

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, tags) VALUES (4, ARRAY['x', 'y z']);
```

ArcadeDB `sql`:

```sql
INSERT INTO memory (id, tags) VALUES (4, ['x', 'y z']);
```

ArcadeDB openCypher:

```cypher
CREATE (:memory {id: 4, tags: ['x', 'y z']});
```

Check:

```sql
SELECT id, tags FROM memory WHERE id = 4;
```

After:

| id | tags |
| --- | --- |
| 4 | [x, y z] |

### Writing a jsonb value

openCypher reads a map property that another language wrote, but it cannot write one: its property values are primitives and arrays of them.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, meta) VALUES (4, '{"source": "chat", "score": 2}');
```

ArcadeDB `sql`:

```sql
INSERT INTO memory (id, meta) VALUES (4, {"source": "chat", "score": 2});
```

ArcadeDB openCypher: no query. A map cannot be the value of a property.

ArcadeDB openCypher refuses this text:

```cypher
CREATE (:memory {id: 4, meta: {source: 'chat', score: 2}});
```

Check:

```sql
SELECT id, meta FROM memory WHERE id = 4;
```

After:

| id | meta |
| --- | --- |
| 4 | {score: 2, source: chat} |

### Writing binary data

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, payload) VALUES (4, '\x0aff');
```

ArcadeDB `sql`:

```sql
INSERT INTO memory (id, payload) VALUES (4, decode('Cv8=', 'base64'));
```

ArcadeDB openCypher: no query. It has no `decode` function, and I did not find another way to write bytes in the text of a statement.

ArcadeDB openCypher refuses this text:

```cypher
CREATE (:memory {id: 4, payload: decode('Cv8=', 'base64')});
```

Check:

```sql
SELECT id, payload FROM memory WHERE id = 4;
```

After:

| id | payload |
| --- | --- |
| 4 | \x0aff |

### Writing a vector

The check finds the new row by its distance.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, embedding) VALUES (4, '[0,0,1]');
```

ArcadeDB `sql`:

```sql
INSERT INTO memory (id, embedding) VALUES (4, [0.0, 0.0, 1.0]);
```

ArcadeDB openCypher:

```cypher
CREATE (:memory {id: 4, embedding: [0.0, 0.0, 1.0]});
```

Check:

```sql
SELECT id, embedding FROM memory ORDER BY embedding <-> '[0,0,1]' LIMIT 1;
```

After:

| id | embedding |
| --- | --- |
| 4 | [0, 0, 1] |

## Transactions

openCypher has no transaction statements: the transaction belongs to the API of the client. Over the PostgreSQL protocol the connection handles `BEGIN`, `COMMIT` and `ROLLBACK` for `sql` and `postgresql`. A JDBC connection that chose openCypher refuses `BEGIN` (see the errors below), so I show no openCypher query here.

### Rolling a transaction back

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
BEGIN;
UPDATE memory SET age = 99 WHERE id = 1;
DELETE FROM memory WHERE id = 2;
ROLLBACK;
```

ArcadeDB openCypher: no query. It has no transaction statements.

ArcadeDB openCypher refuses this text:

```cypher
BEGIN;
```

Check:

```sql
SELECT id, age FROM memory ORDER BY id;
```

After:

| id | age |
| --- | --- |
| 1 | 36 |
| 2 | 29 |
| 3 | 52 |

### Committing a transaction

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
BEGIN;
UPDATE memory SET age = 99 WHERE id = 1;
INSERT INTO memory (id, age) VALUES (4, 18);
COMMIT;
```

ArcadeDB openCypher: no query. It has no transaction statements.

ArcadeDB openCypher refuses this text:

```cypher
BEGIN;
```

Check:

```sql
SELECT id, age FROM memory ORDER BY id;
```

After:

| id | age |
| --- | --- |
| 1 | 99 |
| 2 | 29 |
| 3 | 52 |
| 4 | 18 |

## Errors a client sees

### Syntax error

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT (name FROM person;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person RETURN p.name AS name;
```

Result:

| SQLSTATE |
| --- |
| 42601 |

### A WHERE with no condition

ArcadeDB `sql` allows `WHERE` as a name, so it reads the last word as an alias of `person` and returns every row. A statement that was cut off after `WHERE` is not an error there.

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name FROM person WHERE;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE RETURN p.name AS name;
```

Result:

| SQLSTATE |
| --- |
| 42601 |

Result, ArcadeDB `sql` (differs):

| name |
| --- |
| Ada |
| Bo |
| Cy |
| Di |
| Ed |

### A DELETE with no condition after WHERE

The same alias rule applies to a write: in `sql` this statement deletes every person.

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
DELETE FROM person WHERE;
SELECT count(*) AS n FROM person;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE DELETE p;
MATCH (p:person) RETURN count(*) AS n;
```

Result:

| n |
| --- |
| 5 |

Result, ArcadeDB `sql` (differs):

| n |
| --- |
| 0 |

### Unknown column

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT nickname FROM person WHERE id = 1;
```

ArcadeDB openCypher:

```cypher
MATCH (p:person) WHERE p.id = 1 RETURN p.nickname AS nickname;
```

Result:

| SQLSTATE |
| --- |
| 42703 |

Result, ArcadeDB `sql`, ArcadeDB openCypher (differs):

| nickname |
| --- |
| NULL |

### Unknown table

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
SELECT name FROM nosuch;
```

ArcadeDB openCypher:

```cypher
MATCH (p:nosuch) RETURN p.name AS name;
```

Result:

| SQLSTATE |
| --- |
| 42P01 |

Result, ArcadeDB openCypher (differs):

| name |
| --- |

### Duplicate key

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO person (id, name) VALUES (1, 'Again');
```

ArcadeDB openCypher:

```cypher
CREATE (:person {id: 1, name: 'Again'});
```

Result:

| SQLSTATE |
| --- |
| 23505 |

### Giving a value to the record id column

`sql` and openCypher have no column `rid`, so the same write stores an ordinary property of that name.

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO person (rid, id) VALUES ('999', 9);
```

ArcadeDB openCypher:

```cypher
CREATE (:person {rid: '999', id: 9});
```

Result:

| SQLSTATE |
| --- |
| 428C9 |

Result, ArcadeDB `sql`, ArcadeDB openCypher (differs):

| SQLSTATE |
| --- |
| 00000 |

### A jsonb array where the property holds a map

PostgreSQL 18 stores any JSON value. A `MAP` property holds an object only, and the three ArcadeDB languages refuse the array, with two different codes.

ArcadeDB `postgresql`, PostgreSQL 18:

```sql
INSERT INTO memory (id, meta) VALUES (9, '[1, 2]');
```

ArcadeDB `sql`:

```sql
INSERT INTO memory (id, meta) VALUES (9, [1, 2]);
```

ArcadeDB openCypher:

```cypher
CREATE (:memory {id: 9, meta: [1, 2]});
```

Result:

| SQLSTATE |
| --- |
| 00000 |

Result, ArcadeDB `postgresql` (differs):

| SQLSTATE |
| --- |
| 0A000 |

Result, ArcadeDB `sql`, ArcadeDB openCypher (differs):

| SQLSTATE |
| --- |
| 42804 |

### A statement after an error inside a transaction

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18:

```sql
BEGIN;
SELECT (name FROM person;
SELECT count(*) AS n FROM memory;
```

ArcadeDB openCypher: no query. It has no transaction statements, and a JDBC connection that chose it refuses `BEGIN` (the next operation).

ArcadeDB openCypher refuses this text:

```cypher
BEGIN;
```

Result:

| SQLSTATE |
| --- |
| 25P02 |

### BEGIN on a JDBC connection

The connection chooses its language when it starts. This is `BEGIN` sent by the PostgreSQL JDBC driver in its default mode.

ArcadeDB `sql`, ArcadeDB `postgresql`, PostgreSQL 18, ArcadeDB openCypher:

```sql
BEGIN;
```

Result:

| SQLSTATE |
| --- |
| 00000 |

Result, ArcadeDB openCypher (differs):

| SQLSTATE |
| --- |
| 42601 |

## Not shown

The memory cap of an operator (`53200`) is a limit that depends on the heap, not a statement, so it has no example here.
