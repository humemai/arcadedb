# The postgresql language: supported syntax (fork prototype)

Fork only (`humemai/arcadedb`, branch `feat/postgresql-prototype`). One row per construct that agents write. A statement that is
not in the table is refused with SQLSTATE `0A000` (never a wrong answer). The answers are compared with PostgreSQL 18.7 by the
parity corpus (`engine/src/test/resources/postgresql/corpus`).

| Construct | State | Notes |
|---|---|---|
| `INSERT ... ON CONFLICT DO NOTHING`, with or without `(column, ...)` | supported | A row that breaks a unique index is skipped and not counted. With a target only the index on exactly those columns is an arbiter; a break of another index is `23505`. A target that no unique index matches is `42P10`. |
| `INSERT ... ON CONFLICT (column, ...) DO UPDATE SET ... [WHERE ...]` | supported | The row that is there is read by the table name or the alias, the proposed row as `EXCLUDED`; a bare column that both have is `42702`. A row that would change what the same statement wrote is `21000`. `DO UPDATE` without a target is `42601`. |
| `ON CONFLICT ON CONSTRAINT`, an expression or a predicate in the target, `ON CONFLICT` on an edge table | refused, `0A000` | Name the columns of the unique index instead. |
