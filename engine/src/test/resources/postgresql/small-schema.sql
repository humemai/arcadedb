-- TEMPORARY, written by S3 only so that the parity harness has a schema to run against until the file of S2 lands. It is dropped
-- from the pull request of S3 when S2's engine/src/test/resources/postgresql/small-schema.sql is on the prototype branch.
-- The record ids are the ones SmallFixture produces in a fresh database.
CREATE TABLE person (
  id   text PRIMARY KEY,
  pid  integer UNIQUE NOT NULL,
  name text,
  age  integer,
  city text
);
INSERT INTO person VALUES ('#1:0', 1, 'Ada', 36, 'Delft');
INSERT INTO person VALUES ('#1:1', 2, 'Bo', 41, 'Delft');
INSERT INTO person VALUES ('#1:2', 3, 'Cy', NULL, 'Leiden');
INSERT INTO person VALUES ('#1:3', 4, 'Di', 29, 'Leiden');
INSERT INTO person VALUES ('#1:4', 5, 'Ed', 52, 'Utrecht');

CREATE TABLE company (
  id   text PRIMARY KEY,
  cid  integer UNIQUE NOT NULL,
  name text
);
INSERT INTO company VALUES ('#2:0', 10, 'Acme');
INSERT INTO company VALUES ('#2:1', 20, 'Birch');
INSERT INTO company VALUES ('#2:2', 30, 'Cedar');

CREATE TABLE works_at (
  id        text PRIMARY KEY,
  source_id text NOT NULL REFERENCES person (id),
  target_id text NOT NULL REFERENCES company (id),
  since     integer
);
INSERT INTO works_at VALUES ('#3:0', '#1:0', '#2:0', 2019);
INSERT INTO works_at VALUES ('#3:1', '#1:0', '#2:0', 2022);
INSERT INTO works_at VALUES ('#3:2', '#1:1', '#2:0', 2020);
INSERT INTO works_at VALUES ('#3:3', '#1:2', '#2:1', 2021);
INSERT INTO works_at VALUES ('#3:4', '#1:3', '#2:1', 2023);

CREATE TABLE orders (
  id      text PRIMARY KEY,
  oid     integer UNIQUE NOT NULL,
  pid     integer,
  total   numeric,
  placed  date,
  address jsonb,
  tags    text[],
  buyer   text REFERENCES person (id)
);
INSERT INTO orders VALUES ('#4:0', 100, 1, 25.50, '2026-01-05', '{"city": "Delft", "zip": "1000AA"}', ARRAY['gift', 'express'], '#1:0');
INSERT INTO orders VALUES ('#4:1', 101, 1, 10.00, '2026-01-07', '{"city": "Delft", "zip": "1000AA"}', ARRAY['gift'], '#1:0');
INSERT INTO orders VALUES ('#4:2', 102, 2, 99.99, '2026-02-01', '{"city": "Delft", "zip": "1000AA"}', ARRAY['express'], '#1:1');
INSERT INTO orders VALUES ('#4:3', 103, 4, 5.00, '2026-02-03', '{"city": "Leiden", "zip": "1000AA"}', ARRAY[]::text[], '#1:3');
INSERT INTO orders VALUES ('#4:4', 104, 4, 7.25, '2026-03-09', '{"city": "Leiden", "zip": "1000AA"}', ARRAY['gift'], '#1:3');
INSERT INTO orders VALUES ('#4:5', 105, NULL, 1.00, '2026-03-10', '{"city": "Delft", "zip": "1000AA"}', ARRAY[]::text[], NULL);
