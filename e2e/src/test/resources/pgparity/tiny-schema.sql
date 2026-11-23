-- A tiny schema for the self-test of the PostgreSQL side of the parity harness (PostgresServerIT): one column of each kind of
-- value that the corpus reads (integer, text, numeric, float8, date, array, jsonb) and a row of NULLs. It is not the small
-- fixture: that is engine/src/test/resources/postgresql/small-schema.sql.
CREATE TABLE tiny (
  n     integer PRIMARY KEY,
  label text,
  price numeric(6, 2),
  ratio float8,
  day   date,
  tags  text[],
  extra jsonb
);

INSERT INTO tiny VALUES (1, 'one', 38.50, 0.5, '2026-01-05', ARRAY['a', 'b'], '{"k": 1, "j": "x"}');
INSERT INTO tiny VALUES (2, NULL, NULL, NULL, NULL, NULL, NULL);
