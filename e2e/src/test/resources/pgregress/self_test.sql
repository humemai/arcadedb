--
-- The script of the self-test of the regression harness (fork issue #119, class PgRegressSelfIT). It is no file of PostgreSQL.
-- 27 statements reach the server, and psql does not send one more.
--
\set who 'bob'
CREATE TABLE person (pid int PRIMARY KEY, name text NOT NULL, age int);
INSERT INTO person VALUES (1, 'ada', 36), (2, :'who', 41), (3, 'cy', 36);
SELECT name AS planted_row FROM person WHERE pid = 1;
SELECT name AS planted_refusal FROM person WHERE pid = 2;
SELECT name, age FROM person ORDER BY age;
SELECT name FROM person ORDER BY age DESC, pid;
SELECT 1/0;
SELECT now();
SELECT random();
EXPLAIN SELECT * FROM person;
SELECT ctid, name FROM person;
SELECT relname FROM pg_class WHERE relname = 'person';
CREATE TABLESPACE selftest LOCATION '';
BEGIN;
UPDATE person SET age = age + 1 WHERE pid = 3;
ROLLBACK;
CREATE TABLE log (note text);
INSERT INTO log VALUES ('planted_write');
SELECT count(*) FROM log;
BEGIN;
SELECT * FROM missing_table;
SELECT 1;
ROLLBACK;
COPY person FROM stdin;
4	dee	50
\.
SELECT sum(age) FROM person;
SELECT count(*) AS n FROM person \gset
SELECT :n + 1 AS next;
\if false
SELECT 'not sent';
\endif
