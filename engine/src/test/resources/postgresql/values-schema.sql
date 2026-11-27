-- PostgreSQL-style SQL prototype: the table of the values corpus (fork issue #29) for the real server, the same as ValuesFixture
-- without the embedding, which needs pgvector.
-- Copyright © 2021-present Arcade Data Ltd (info@arcadedata.com)
-- SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
-- SPDX-License-Identifier: Apache-2.0
CREATE TABLE memory (mid int4, tags text[], labels text[], flags bool[], counts int4[], big int8[], scores float8[],
  ratios float4[], prices numeric[], meta jsonb, items jsonb, payload bytea);
INSERT INTO memory (mid, tags, meta, payload) VALUES (1, '{a,b}', '{"k": 1}', '\x0102');
-- the arrays of dates and timestamps (fork issue #146)
CREATE TABLE calendar (cid int4, days date[], moments timestamp[]);
INSERT INTO calendar (cid, days, moments) VALUES (1, '{2025-12-31,2026-01-01}', '{"2025-12-31 23:59:59.25"}');
