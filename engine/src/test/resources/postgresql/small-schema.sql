-- The PostgreSQL side of the small fixture of the PostgreSQL-style SQL prototype: the tables of the relational view
-- (RelationalCatalog) and the rows of the Java fixture (SmallFixture), written by SmallSchemaSql. Do not edit by hand:
-- SmallSchemaSqlTest fails when this file and the generator differ.
-- The record ids are renumbered #<table number>:<row number>. The real ones come from SmallSchemaSql.generate(db, false).

CREATE TABLE company (
  id text NOT NULL,
  cid int4,
  name varchar,
  CONSTRAINT company_pkey PRIMARY KEY (id),
  CONSTRAINT company_cid_key UNIQUE (cid)
);
INSERT INTO company (id, cid, name) VALUES ('#1:0', 10, 'Acme');
INSERT INTO company (id, cid, name) VALUES ('#1:1', 20, 'Birch');
INSERT INTO company (id, cid, name) VALUES ('#1:2', 30, 'Cedar');

CREATE TABLE person (
  id text NOT NULL,
  age int4,
  city varchar,
  name varchar,
  pid int4,
  CONSTRAINT person_pkey PRIMARY KEY (id),
  CONSTRAINT person_pid_key UNIQUE (pid)
);
INSERT INTO person (id, age, city, name, pid) VALUES ('#2:0', 36, 'Delft', 'Ada', 1);
INSERT INTO person (id, age, city, name, pid) VALUES ('#2:1', 41, 'Delft', 'Bo', 2);
INSERT INTO person (id, age, city, name, pid) VALUES ('#2:2', NULL, 'Leiden', 'Cy', 3);
INSERT INTO person (id, age, city, name, pid) VALUES ('#2:3', 29, 'Leiden', 'Di', 4);
INSERT INTO person (id, age, city, name, pid) VALUES ('#2:4', 52, 'Utrecht', 'Ed', 5);

CREATE TABLE orders (
  id text NOT NULL,
  address jsonb,
  buyer text,
  oid int4,
  pid int4,
  placed date,
  tags text[],
  total numeric,
  CONSTRAINT orders_pkey PRIMARY KEY (id),
  CONSTRAINT orders_oid_key UNIQUE (oid),
  CONSTRAINT orders_buyer_fkey FOREIGN KEY (buyer) REFERENCES person (id)
);
INSERT INTO orders (id, address, buyer, oid, pid, placed, tags, total) VALUES ('#3:0', '{"city":"Delft","zip":"1000AA"}'::jsonb, '#2:0', 100, 1, '2026-01-05', ARRAY['gift', 'express']::text[], 25.50);
INSERT INTO orders (id, address, buyer, oid, pid, placed, tags, total) VALUES ('#3:1', '{"city":"Delft","zip":"1000AA"}'::jsonb, '#2:0', 101, 1, '2026-01-07', ARRAY['gift']::text[], 10.00);
INSERT INTO orders (id, address, buyer, oid, pid, placed, tags, total) VALUES ('#3:2', '{"city":"Delft","zip":"1000AA"}'::jsonb, '#2:1', 102, 2, '2026-02-01', ARRAY['express']::text[], 99.99);
INSERT INTO orders (id, address, buyer, oid, pid, placed, tags, total) VALUES ('#3:3', '{"city":"Leiden","zip":"1000AA"}'::jsonb, '#2:3', 103, 4, '2026-02-03', ARRAY[]::text[], 5.00);
INSERT INTO orders (id, address, buyer, oid, pid, placed, tags, total) VALUES ('#3:4', '{"city":"Leiden","zip":"1000AA"}'::jsonb, '#2:3', 104, 4, '2026-03-09', ARRAY['gift']::text[], 7.25);
INSERT INTO orders (id, address, buyer, oid, pid, placed, tags, total) VALUES ('#3:5', '{"city":"Delft","zip":"1000AA"}'::jsonb, NULL, 105, NULL, '2026-03-10', ARRAY[]::text[], 1.00);

CREATE TABLE works_at (
  id text NOT NULL,
  source_id text NOT NULL,
  target_id text NOT NULL,
  since int4,
  CONSTRAINT works_at_pkey PRIMARY KEY (id),
  CONSTRAINT works_at_source_id_fkey FOREIGN KEY (source_id) REFERENCES person (id),
  CONSTRAINT works_at_target_id_fkey FOREIGN KEY (target_id) REFERENCES company (id)
);
INSERT INTO works_at (id, source_id, target_id, since) VALUES ('#4:0', '#2:0', '#1:0', 2019);
INSERT INTO works_at (id, source_id, target_id, since) VALUES ('#4:1', '#2:0', '#1:0', 2022);
INSERT INTO works_at (id, source_id, target_id, since) VALUES ('#4:2', '#2:1', '#1:0', 2020);
INSERT INTO works_at (id, source_id, target_id, since) VALUES ('#4:3', '#2:2', '#1:1', 2021);
INSERT INTO works_at (id, source_id, target_id, since) VALUES ('#4:4', '#2:3', '#1:1', 2023);
