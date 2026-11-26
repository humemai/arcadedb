-- The shape of the small fixture (fork issue #2) as PostgreSQL 18 spells it: what the relational view of the language shows for it.
-- The record id is the text column id, the primary key of every table (nothing declares an id of its own); an edge type is a link
-- table with source_id and target_id that refer to the record ids of its endpoint tables; a LINK property is a foreign key to the
-- record id of the type it links to; a unique index is a unique constraint. Columns are in the order of the view: the record id and
-- the ends of an edge first, then the declared properties by name. Record ids are made up here: no case prints one.
CREATE TABLE person (id text PRIMARY KEY, age integer, city varchar, name varchar, pid integer UNIQUE);
CREATE TABLE company (id text PRIMARY KEY, cid integer UNIQUE, name varchar);
CREATE TABLE works_at (id text PRIMARY KEY, source_id text NOT NULL REFERENCES person (id) ON DELETE CASCADE, target_id text NOT NULL REFERENCES company (id) ON DELETE CASCADE, since integer);
CREATE TABLE orders (id text PRIMARY KEY, address jsonb, buyer text REFERENCES person (id) NOT ENFORCED, oid integer UNIQUE, pid integer, placed date, tags text[], total numeric);

INSERT INTO person VALUES ('p1', 36, 'Delft', 'Ada', 1), ('p2', 41, 'Delft', 'Bo', 2), ('p3', NULL, 'Leiden', 'Cy', 3), ('p4', 29, 'Leiden', 'Di', 4), ('p5', 52, 'Utrecht', 'Ed', 5);
INSERT INTO company VALUES ('c10', 10, 'Acme'), ('c20', 20, 'Birch'), ('c30', 30, 'Cedar');
INSERT INTO works_at VALUES ('w1', 'p1', 'c10', 2019), ('w2', 'p1', 'c10', 2022), ('w3', 'p2', 'c10', 2020), ('w4', 'p3', 'c20', 2021), ('w5', 'p4', 'c20', 2023);
INSERT INTO orders (id, address, buyer, oid, pid, total, placed, tags) VALUES
  ('o100', '{"city": "Delft", "zip": "1000AA"}', 'p1', 100, 1, 25.50, '2026-01-05', '{gift,express}'),
  ('o101', '{"city": "Delft", "zip": "1000AA"}', 'p1', 101, 1, 10.00, '2026-01-07', '{gift}'),
  ('o102', '{"city": "Delft", "zip": "1000AA"}', 'p2', 102, 2, 99.99, '2026-02-01', '{express}'),
  ('o103', '{"city": "Leiden", "zip": "1000AA"}', 'p4', 103, 4, 5.00, '2026-02-03', '{}'),
  ('o104', '{"city": "Leiden", "zip": "1000AA"}', 'p4', 104, 4, 7.25, '2026-03-09', '{gift}'),
  ('o105', '{"city": "Delft", "zip": "1000AA"}', NULL, 105, NULL, 1.00, '2026-03-10', '{}');
