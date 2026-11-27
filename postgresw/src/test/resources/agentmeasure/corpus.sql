-- SPDX-FileCopyrightText: 2021-present Arcade Data Ltd (info@arcadedata.com)
-- SPDX-License-Identifier: Apache-2.0
--
-- The statements of the agent measure (PostgreSQLAgentMeasureTest, .github/scripts/pg_agent_measure.py, the page
-- docs/postgresql-agent-measure.md, fork issue #265). An LLM wrote them for the audit of fork issue #248.
--
-- The agent scripts are the groups memory, web, web-transactions, web-discovery, and web-mistakes: what an agent writes when
-- it is told "PostgreSQL 18". The other groups are probes of single features and of known cases.
--
-- Directives, each on a line of its own:
--   --# group NAME      the statements that follow belong to NAME
--   --@ ID TAGS         a statement starts. The tag volatile: its values depend on the clock, a random id, the server, or
--                       an order that PostgreSQL does not fix, so only its column names and row count are compared, and it
--                       does not count in M1. The tag own-value with it: the values are the server's own (its version, its
--                       search path) and are compared all the same
--   --! pg              the text that follows is what PostgreSQL runs instead
--   --! via-sql         the language side runs the statement in ArcadeDB sql: a setup step, not counted
--   --! rollback        the statement runs between BEGIN and ROLLBACK
--   --! param TYPE V    a bound parameter of the statement
--# group memory
--@ M01 schema
CREATE EXTENSION IF NOT EXISTS vector
--@ M02 schema
CREATE TABLE IF NOT EXISTS entities (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    type TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
)
--@ M03 schema
CREATE TABLE IF NOT EXISTS facts (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entity_id BIGINT NOT NULL REFERENCES entities(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    confidence REAL DEFAULT 1.0 CHECK (confidence BETWEEN 0 AND 1),
    source TEXT,
    embedding vector(3),
    tags TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now()
)
--@ M04 schema
CREATE TABLE IF NOT EXISTS relations (
    id SERIAL PRIMARY KEY,
    source_id BIGINT NOT NULL REFERENCES entities(id),
    target_id BIGINT NOT NULL REFERENCES entities(id),
    relation_type TEXT NOT NULL,
    weight DOUBLE PRECISION DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE (source_id, target_id, relation_type)
)
--@ M05 schema
CREATE INDEX IF NOT EXISTS idx_facts_entity ON facts (entity_id)
--@ M06 schema
CREATE INDEX IF NOT EXISTS idx_facts_embedding ON facts USING hnsw (embedding vector_cosine_ops)
--@ M07 schema
CREATE INDEX IF NOT EXISTS idx_entities_metadata ON entities USING gin (metadata)
--@ M08 schema
CREATE TABLE IF NOT EXISTS entities (id bigserial PRIMARY KEY, name text)
--@ M09 write
INSERT INTO entities (name, type, metadata) VALUES ('Alice', 'person', '{"role": "engineer", "city": "Berlin", "address": {"city": "Berlin", "zip": "10115"}, "aliases": ["Al", "Ally"]}') RETURNING id
--@ M10 write
INSERT INTO entities (name, type, metadata) VALUES ('Bob', 'person', '{"role": "designer"}'), ('Acme', 'organization', '{"industry": "software", "size": 120}') RETURNING id, name
--@ M11 write
INSERT INTO facts (entity_id, content, confidence, source, embedding, tags) VALUES (1, 'Alice prefers dark mode', 0.9, 'chat', '[0.1,0.2,0.3]', ARRAY['preference', 'ui'])
--@ M12 write
INSERT INTO facts (entity_id, content, confidence, source, embedding, tags) VALUES (1, 'Alice lives in Berlin', 0.8, 'profile', '[0.9,0.1,0.0]', '{location}'), (2, 'Bob likes coffee', 0.6, 'chat', '[0.2,0.8,0.1]', '{preference,food}'), (3, 'Acme was founded in 2012', 0.2, NULL, NULL, '{}') RETURNING id, entity_id
--@ M13 write volatile
INSERT INTO facts (entity_id, content) VALUES (2, 'Bob dislikes meetings') RETURNING *
--@ M14 write
INSERT INTO relations (source_id, target_id, relation_type) VALUES (1, 3, 'works_at'), (2, 3, 'works_at'), (1, 2, 'knows') RETURNING id, source_id, target_id
--@ M15 upsert
INSERT INTO entities (name, type, metadata) VALUES ('Alice', 'person', '{"team": "platform"}') ON CONFLICT (name) DO UPDATE SET metadata = entities.metadata || EXCLUDED.metadata, updated_at = now() RETURNING id, name, metadata
--@ M16 upsert
INSERT INTO relations (source_id, target_id, relation_type) VALUES (1, 2, 'knows') ON CONFLICT DO NOTHING
--@ M17 upsert
INSERT INTO relations (source_id, target_id, relation_type, weight) VALUES (1, 2, 'knows', 0.5) ON CONFLICT (source_id, target_id, relation_type) DO UPDATE SET weight = relations.weight + EXCLUDED.weight RETURNING weight
--@ M18 json
SELECT id, name, metadata->>'role' AS role FROM entities WHERE type = 'person' ORDER BY id
--@ M19 json
SELECT name FROM entities WHERE metadata @> '{"role": "engineer"}'
--@ M20 json
SELECT name FROM entities WHERE metadata ? 'city' ORDER BY name
--@ M21 json
SELECT name, metadata->'city' AS city FROM entities ORDER BY name
--@ M22 json
SELECT name FROM entities WHERE (metadata->>'size')::int > 100
--@ M23 json
SELECT name, metadata->'address'->>'zip' AS zip, metadata#>>'{address,city}' AS address_city FROM entities WHERE name = 'Alice'
--@ M24 json
UPDATE entities SET metadata = jsonb_set(metadata, '{city}', '"Munich"') WHERE name = 'Alice' RETURNING metadata->>'city' AS city
--@ M25 json
UPDATE entities SET metadata = metadata || '{"verified": true}'::jsonb WHERE name = 'Bob' RETURNING metadata
--@ M26 json
UPDATE entities SET metadata = metadata - 'verified' WHERE name = 'Bob'
--@ M27 array
SELECT content FROM facts WHERE 'preference' = ANY(tags) ORDER BY id
--@ M28 array
SELECT content FROM facts WHERE tags @> ARRAY['ui'] ORDER BY id
--@ M29 array
SELECT content FROM facts WHERE tags && ARRAY['ui', 'food'] ORDER BY id
--@ M30 array
SELECT name FROM entities WHERE id = ANY(ARRAY[1, 2]) ORDER BY id
--@ M31 array
SELECT name FROM entities WHERE id = ANY('{1,3}'::bigint[]) ORDER BY id
--@ M32 filter
SELECT name FROM entities WHERE name IN ('Alice', 'Bob') ORDER BY name
--@ M33 filter
SELECT content FROM facts WHERE content ILIKE '%DARK%'
--@ M34 vector
SELECT content, embedding <=> '[0.1,0.2,0.25]' AS distance FROM facts WHERE embedding IS NOT NULL ORDER BY embedding <=> '[0.1,0.2,0.25]' LIMIT 3
--@ M35 vector
SELECT content, 1 - (embedding <=> '[0.1,0.2,0.3]'::vector) AS similarity FROM facts WHERE embedding IS NOT NULL ORDER BY similarity DESC LIMIT 2
--@ M36 vector
SELECT e.name, f.content FROM facts f JOIN entities e ON e.id = f.entity_id WHERE f.embedding IS NOT NULL AND e.type = 'person' ORDER BY f.embedding <-> '[0.2,0.8,0.1]' LIMIT 2
--@ M37 join
SELECT e.name, f.content FROM facts f JOIN entities e ON e.id = f.entity_id ORDER BY f.id
--@ M38 join
SELECT t.name, r.relation_type FROM relations r JOIN entities s ON s.id = r.source_id JOIN entities t ON t.id = r.target_id WHERE s.name = 'Alice' ORDER BY t.name
--@ M39 join
SELECT DISTINCT t.name FROM entities s JOIN relations r1 ON r1.source_id = s.id JOIN relations r2 ON r2.source_id = r1.target_id JOIN entities t ON t.id = r2.target_id WHERE s.name = 'Alice'
--@ M40 join
SELECT CASE WHEN r.source_id = e.id THEN r.target_id ELSE r.source_id END AS neighbor_id, r.relation_type FROM relations r JOIN entities e ON e.id IN (r.source_id, r.target_id) WHERE e.name = 'Acme' ORDER BY neighbor_id
--@ M41 subquery
SELECT id, name FROM entities WHERE id IN (SELECT target_id FROM relations WHERE source_id = 1) ORDER BY id
--@ M42 cte
WITH RECURSIVE reachable AS (SELECT target_id, 1 AS depth FROM relations WHERE source_id = 1 UNION SELECT r.target_id, reachable.depth + 1 FROM relations r JOIN reachable ON r.source_id = reachable.target_id WHERE reachable.depth < 3) SELECT DISTINCT e.name FROM reachable JOIN entities e ON e.id = reachable.target_id ORDER BY e.name
--@ M43 aggregate
SELECT e.name, count(f.id) AS fact_count FROM entities e LEFT JOIN facts f ON f.entity_id = e.id GROUP BY e.id ORDER BY fact_count DESC, e.name
--@ M44 aggregate
SELECT e.name, array_agg(f.content ORDER BY f.id) AS facts FROM entities e JOIN facts f ON f.entity_id = e.id GROUP BY e.name ORDER BY e.name
--@ M45 aggregate
SELECT e.name, json_agg(json_build_object('content', f.content, 'confidence', f.confidence) ORDER BY f.id) AS facts FROM entities e JOIN facts f ON f.entity_id = e.id GROUP BY e.name ORDER BY e.name
--@ M46 json
SELECT jsonb_build_object('name', name, 'meta', metadata) AS doc FROM entities WHERE name = 'Bob'
--@ M47 json
SELECT row_to_json(t) FROM (SELECT id, name, type FROM entities WHERE name = 'Bob') t
--@ M48 aggregate
SELECT json_agg(t) FROM (SELECT name, type FROM entities ORDER BY name) t
--@ M49 aggregate
SELECT type, count(*) FILTER (WHERE metadata ? 'role') AS with_role, count(*) AS total FROM entities GROUP BY type ORDER BY type
--@ M50 array
SELECT unnest(tags) AS tag, count(*) FROM facts GROUP BY tag ORDER BY count(*) DESC, tag
--@ M51 time
SELECT content FROM facts WHERE created_at > now() - interval '7 days' ORDER BY id
--@ M52 time volatile
SELECT date_trunc('day', created_at) AS day, count(*) FROM facts GROUP BY 1 ORDER BY 1
--@ M53 window
SELECT DISTINCT ON (entity_id) entity_id, content FROM facts ORDER BY entity_id, confidence DESC, id
--@ M54 window
SELECT entity_id, content, row_number() OVER (PARTITION BY entity_id ORDER BY confidence DESC, id) AS rn FROM facts ORDER BY entity_id, rn
--@ M55 cte
WITH ranked AS (SELECT entity_id, content, row_number() OVER (PARTITION BY entity_id ORDER BY confidence DESC, id) AS rn FROM facts) SELECT e.name, r.content FROM ranked r JOIN entities e ON e.id = r.entity_id WHERE r.rn = 1 ORDER BY e.name
--@ M56 page
SELECT id, content, count(*) OVER () AS total FROM facts ORDER BY id LIMIT 2 OFFSET 2
--@ M57 page
SELECT id, content FROM facts WHERE (confidence, id) < (0.85, 100) ORDER BY confidence DESC, id DESC LIMIT 2
--@ M58 page
SELECT id, content FROM facts ORDER BY id FETCH FIRST 2 ROWS ONLY
--@ M59 aggregate
SELECT coalesce(source, 'unknown') AS source, count(*) FROM facts GROUP BY 1 ORDER BY 1
--@ M60 subquery
SELECT EXISTS (SELECT 1 FROM entities WHERE name = 'Alice') AS found
--@ M61 read volatile
SELECT * FROM entities WHERE name = 'Bob'
--@ M62 read volatile
SELECT * FROM relations ORDER BY id
--@ M63 search
SELECT content FROM facts WHERE to_tsvector('english', content) @@ plainto_tsquery('english', 'dark') ORDER BY id
--@ M64 join
SELECT e.name, f.content FROM entities e CROSS JOIN LATERAL (SELECT content FROM facts WHERE entity_id = e.id ORDER BY id DESC LIMIT 1) f ORDER BY e.name
--@ M65 aggregate
SELECT e.name, string_agg(f.content, '; ' ORDER BY f.id) AS summary FROM entities e JOIN facts f ON f.entity_id = e.id GROUP BY e.name ORDER BY e.name
--@ M66 aggregate
SELECT jsonb_agg(name ORDER BY name) AS names FROM entities
--@ M67 aggregate
SELECT jsonb_object_agg(name, type) AS types FROM entities
--@ M68 json
SELECT key, value FROM entities, jsonb_each_text(metadata) WHERE name = 'Bob' ORDER BY key
--@ M69 json
SELECT DISTINCT jsonb_object_keys(metadata) AS key FROM entities ORDER BY key
--@ M70 json
SELECT jsonb_array_elements_text(metadata->'aliases') AS alias FROM entities WHERE name = 'Alice'
--@ M71 write
UPDATE facts SET confidence = confidence + 0.05 WHERE entity_id = (SELECT id FROM entities WHERE name = 'Bob') AND confidence < 0.9 RETURNING id
--@ M72 write
UPDATE facts f SET source = e.type FROM entities e WHERE e.id = f.entity_id AND f.source IS NULL RETURNING f.id, f.source
--@ M73 write
DELETE FROM facts WHERE confidence < 0.3 RETURNING id, content
--@ M74 schema
CREATE TABLE IF NOT EXISTS facts_archive (LIKE facts INCLUDING ALL)
--@ M75 cte
WITH moved AS (DELETE FROM facts WHERE source = 'profile' RETURNING id, content) SELECT count(*) FROM moved
--@ M76 error
DELETE FROM entities WHERE name = 'Alice'
--! rollback
--@ M77 write
DELETE FROM relations WHERE source_id = 2 OR target_id = 2
--@ M78 write
DELETE FROM entities WHERE name = 'Bob' RETURNING id, name
--@ M79 read
SELECT count(*) AS facts_left FROM facts
--@ M80 schema
ALTER TABLE facts ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ
--@ M81 schema
ALTER TABLE entities ADD COLUMN importance INTEGER NOT NULL DEFAULT 0
--@ M82 write
UPDATE entities SET importance = 5 WHERE name = 'Alice' RETURNING name, importance
--@ M83 read
SELECT name, importance FROM entities ORDER BY importance DESC, name
--@ M84 write
INSERT INTO facts (entity_id, content, embedding) VALUES ((SELECT id FROM entities WHERE name = 'Acme'), 'Acme ships a CLI', '[0.3,0.3,0.4]') RETURNING entity_id, content
--# group web
--@ W01 schema
CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL UNIQUE,
    full_name varchar(100),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
)
--@ W02 schema
CREATE TABLE products (
    id serial PRIMARY KEY,
    sku varchar(32) NOT NULL UNIQUE,
    name text NOT NULL,
    price numeric(10,2) NOT NULL CHECK (price >= 0),
    stock integer NOT NULL DEFAULT 0
)
--@ W03 schema
CREATE TYPE order_status AS ENUM ('pending', 'paid', 'shipped', 'cancelled')
--@ W04 schema
CREATE TABLE orders (
    id bigserial PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    status order_status NOT NULL DEFAULT 'pending',
    total numeric(12,2) NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
)
--@ W05 schema
CREATE TABLE order_items (
    order_id bigint NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id integer NOT NULL REFERENCES products(id),
    quantity integer NOT NULL CHECK (quantity > 0),
    unit_price numeric(10,2) NOT NULL,
    PRIMARY KEY (order_id, product_id)
)
--@ W06 schema
CREATE INDEX idx_orders_user_id ON orders(user_id)
--@ W07 schema
CREATE UNIQUE INDEX users_email_lower_idx ON users (lower(email))
--@ W08 schema
CREATE VIEW active_users AS SELECT id, email, full_name FROM users WHERE is_active
--@ W09 schema
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login timestamptz
--@ W10 schema
ALTER TABLE products ADD COLUMN description text
--@ W11 schema
ALTER TABLE products ALTER COLUMN price TYPE numeric(12,2)
--@ W12 schema
ALTER TABLE products ALTER COLUMN stock SET DEFAULT 0
--@ W13 schema
ALTER TABLE users ADD COLUMN nickname text
--@ W14 schema
ALTER TABLE users RENAME COLUMN nickname TO handle
--@ W15 schema
ALTER TABLE orders ADD CONSTRAINT orders_total_positive CHECK (total >= 0)
--@ W16 schema
CREATE TABLE audit_log (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id uuid,
    action text NOT NULL,
    payload jsonb,
    at timestamp NOT NULL DEFAULT now()
)
--@ W17 schema
ALTER TABLE audit_log ADD CONSTRAINT audit_log_user_fk FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
--@ W18 schema
CREATE TABLE IF NOT EXISTS audit_log (id bigint)
--@ W19 schema
COMMENT ON TABLE users IS 'Application users'
--@ W20 schema
CREATE TEMP TABLE tmp_import (sku text, qty int)
--@ W21 schema
DROP TABLE IF EXISTS tmp_import
--@ W22 schema
CREATE OR REPLACE FUNCTION set_last_login() RETURNS trigger AS $$ BEGIN NEW.last_login = now(); RETURN NEW; END; $$ LANGUAGE plpgsql
--@ W23 write
INSERT INTO users (id, email, full_name) VALUES ('11111111-1111-1111-1111-111111111111', 'ann@example.com', 'Ann Lee') RETURNING id, email, is_active
--@ W24 write volatile
INSERT INTO users (email, full_name) VALUES ('ben@example.com', 'Ben Ode') RETURNING id
--@ W25 write
INSERT INTO users (id, email, full_name, is_active) VALUES ('33333333-3333-3333-3333-333333333333', 'cy@example.org', NULL, false)
--@ W26 write
INSERT INTO products (sku, name, price, stock) VALUES ('A-1', 'Keyboard', 49.90, 10), ('B-2', 'Mouse', 19.50, 25), ('C-3', 'Monitor', 229.00, 5) RETURNING id, sku
--@ W27 write
INSERT INTO orders (user_id) VALUES ('11111111-1111-1111-1111-111111111111') RETURNING id, status, total
--@ W28 write
INSERT INTO order_items (order_id, product_id, quantity, unit_price) SELECT 1, id, 2, price FROM products WHERE sku = 'A-1'
--@ W29 write
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (1, 2, 1, 19.50) RETURNING *
--@ W30 write
INSERT INTO orders (user_id, status) VALUES ((SELECT id FROM users WHERE email = 'ben@example.com'), 'paid') RETURNING id
--@ W31 write
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (2, 3, 1, 229.00), (2, 2, 3, 18.00)
--@ W32 write
UPDATE orders o SET total = (SELECT coalesce(sum(i.quantity * i.unit_price), 0) FROM order_items i WHERE i.order_id = o.id) WHERE o.id = 1 RETURNING total
--@ W33 write
UPDATE orders SET total = s.total FROM (SELECT order_id, sum(quantity * unit_price) AS total FROM order_items GROUP BY order_id) s WHERE orders.id = s.order_id RETURNING orders.id, orders.total
--@ W34 write
UPDATE products p SET stock = p.stock - i.quantity FROM order_items i WHERE i.product_id = p.id AND i.order_id = 1
--@ W35 write
UPDATE orders SET status = 'shipped' WHERE id = 2 AND status = 'paid' RETURNING id, status
--@ W36 write
UPDATE users SET last_login = now() WHERE email = 'ann@example.com'
--@ W37 join
SELECT u.email, count(o.id) AS orders, coalesce(sum(o.total), 0) AS revenue FROM users u LEFT JOIN orders o ON o.user_id = u.id GROUP BY u.email ORDER BY revenue DESC, u.email
--@ W38 join
SELECT p.name, sum(i.quantity) AS sold FROM order_items i JOIN products p ON p.id = i.product_id GROUP BY p.id, p.name ORDER BY sold DESC, p.name LIMIT 5
--@ W39 join
SELECT o.id AS order_id, u.email, p.name, i.quantity, i.quantity * i.unit_price AS line_total FROM orders o JOIN users u ON u.id = o.user_id JOIN order_items i ON i.order_id = o.id JOIN products p ON p.id = i.product_id ORDER BY o.id, p.name
--@ W40 time volatile
SELECT to_char(created_at, 'YYYY-MM') AS month, sum(total) FROM orders GROUP BY month ORDER BY month
--@ W41 time volatile
SELECT date_trunc('month', created_at)::date AS month, count(*) FROM orders GROUP BY 1 ORDER BY 1
--@ W42 time volatile
SELECT EXTRACT(YEAR FROM created_at) AS y, count(*) FROM orders GROUP BY y
--@ W43 subquery
SELECT name FROM products p WHERE NOT EXISTS (SELECT 1 FROM order_items i WHERE i.product_id = p.id) ORDER BY name
--@ W44 subquery
SELECT email FROM users WHERE id NOT IN (SELECT user_id FROM orders) ORDER BY email
--@ W45 expr
SELECT name, price, price * 1.19 AS gross, round(price * 1.19, 2) AS gross_rounded FROM products ORDER BY price DESC
--@ W46 expr
SELECT name, CASE WHEN stock = 0 THEN 'out' WHEN stock < 10 THEN 'low' ELSE 'ok' END AS level FROM products ORDER BY name
--@ W47 aggregate
SELECT avg(price)::numeric(10,2) AS avg_price, min(price), max(price), count(*) FROM products
--@ W48 filter
SELECT sku, price FROM products WHERE price BETWEEN 10 AND 100 ORDER BY price
--@ W49 filter
SELECT email FROM users WHERE email ILIKE '%@EXAMPLE.COM' ORDER BY email
--@ W50 expr
SELECT lower(email), upper(full_name), length(full_name), split_part(email, '@', 2) AS domain FROM users ORDER BY 1
--@ W51 expr
SELECT concat(coalesce(full_name, 'unknown'), ' <', email, '>') AS label FROM users ORDER BY 1
--@ W52 expr
SELECT full_name || ' (' || id || ')' AS label FROM users WHERE email = 'ann@example.com'
--@ W53 time volatile
SELECT d::date AS day, count(o.id) FROM generate_series(current_date - 6, current_date, interval '1 day') AS d LEFT JOIN orders o ON o.created_at::date = d::date GROUP BY 1 ORDER BY 1
--@ W54 expr
SELECT * FROM generate_series(1, 5) AS n
--@ W55 aggregate
SELECT count(*) AS total, count(*) FILTER (WHERE status = 'paid') AS paid, count(*) FILTER (WHERE status = 'shipped') AS shipped FROM orders
--@ W56 aggregate
SELECT status, count(*) FROM orders GROUP BY status ORDER BY status
--@ W57 window
SELECT rank() OVER (ORDER BY price DESC) AS rank, name FROM products ORDER BY rank
--@ W58 window
SELECT name, price, sum(price) OVER (ORDER BY price) AS running_total FROM products ORDER BY price
--@ W59 window
SELECT id, total, lag(total) OVER (PARTITION BY user_id ORDER BY id) AS previous_total FROM orders ORDER BY id
--@ W60 aggregate
SELECT user_id, json_agg(json_build_object('id', id, 'total', total) ORDER BY id) AS orders FROM orders GROUP BY user_id ORDER BY user_id
--@ W61 aggregate
SELECT u.email, coalesce(json_agg(o.id ORDER BY o.id) FILTER (WHERE o.id IS NOT NULL), '[]') AS order_ids FROM users u LEFT JOIN orders o ON o.user_id = u.id GROUP BY u.email ORDER BY u.email
--@ W62 aggregate
SELECT u.email, array_agg(DISTINCT p.sku) AS skus FROM users u JOIN orders o ON o.user_id = u.id JOIN order_items i ON i.order_id = o.id JOIN products p ON p.id = i.product_id GROUP BY u.email ORDER BY u.email
--@ W63 cte
WITH spend AS (SELECT user_id, sum(total) AS spent FROM orders GROUP BY user_id) SELECT u.email, s.spent FROM spend s JOIN users u ON u.id = s.user_id ORDER BY s.spent DESC
--@ W64 page volatile
SELECT * FROM orders ORDER BY created_at DESC LIMIT 10 OFFSET 0
--@ W65 page
SELECT count(*) OVER () AS full_count, id, sku FROM products ORDER BY id LIMIT 2 OFFSET 2
--@ W66 uuid
SELECT email FROM users WHERE id = '11111111-1111-1111-1111-111111111111'::uuid
--@ W67 uuid
SELECT email FROM users WHERE id::text LIKE '3333%'
--@ W68 uuid
SELECT email FROM users WHERE id = ANY(ARRAY['11111111-1111-1111-1111-111111111111', '33333333-3333-3333-3333-333333333333']::uuid[]) ORDER BY email
--@ W69 upsert
INSERT INTO users (email, full_name) VALUES ('ann@example.com', 'Ann B. Lee') ON CONFLICT (email) DO UPDATE SET full_name = EXCLUDED.full_name RETURNING email, full_name, (xmax = 0) AS inserted
--@ W70 upsert
INSERT INTO products (sku, name, price) VALUES ('A-1', 'Keyboard', 59.90) ON CONFLICT ON CONSTRAINT products_sku_key DO UPDATE SET price = EXCLUDED.price RETURNING sku, price
--@ W71 upsert
INSERT INTO products AS p (sku, name, price, stock) VALUES ('B-2', 'Mouse', 19.50, 5) ON CONFLICT (sku) DO UPDATE SET stock = p.stock + EXCLUDED.stock RETURNING sku, stock
--@ W72 write
DELETE FROM orders WHERE id = 999 RETURNING *
--@ W73 write
DELETE FROM order_items USING orders o WHERE order_items.order_id = o.id AND o.status = 'cancelled'
--@ W74 read
SELECT email FROM active_users ORDER BY email
--@ W75 join
SELECT u.email, latest.id AS latest_order FROM users u LEFT JOIN LATERAL (SELECT id FROM orders o WHERE o.user_id = u.id ORDER BY id DESC LIMIT 1) latest ON true ORDER BY u.email
--@ W76 aggregate
SELECT user_id, count(*) AS n FROM orders GROUP BY user_id HAVING count(*) >= 1 ORDER BY n DESC, user_id
--@ W77 read
SELECT DISTINCT status FROM orders ORDER BY status
--@ W78 aggregate
SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY price) AS median_price FROM products
--@ W79 expr
SELECT name, greatest(stock, 10) AS restock_to, least(price, 100) AS capped FROM products ORDER BY name
--@ W80 time
SELECT count(*) FROM orders WHERE created_at + interval '30 days' > now()
--@ W81 time volatile
SELECT email, age(now(), created_at) AS account_age FROM users ORDER BY email
--@ W82 expr
SELECT email, coalesce(nullif(trim(full_name), ''), split_part(email, '@', 1)) AS display FROM users ORDER BY email
--@ W83 set
SELECT 'user' AS kind, email AS label FROM users UNION ALL SELECT 'product', sku FROM products ORDER BY kind, label
--@ W84 subquery
SELECT t.sku, t.sold FROM (SELECT p.sku, coalesce(sum(i.quantity), 0) AS sold FROM products p LEFT JOIN order_items i ON i.product_id = p.id GROUP BY p.sku) t WHERE t.sold > 0 ORDER BY t.sold DESC, t.sku
--@ W85 write
INSERT INTO products (sku, name, price) SELECT 'D-4', 'Cable', 5.00 WHERE NOT EXISTS (SELECT 1 FROM products WHERE sku = 'D-4') RETURNING id, sku, stock
--@ W86 write
INSERT INTO audit_log (user_id, action, payload) VALUES ('11111111-1111-1111-1111-111111111111', 'login', jsonb_build_object('ip', '10.0.0.1', 'ok', true))
--@ W87 json
SELECT action, payload->>'ip' AS ip, (payload->>'ok')::boolean AS ok FROM audit_log ORDER BY id
--@ W88 read volatile own-value
SELECT version()
--@ W89 read volatile
SELECT current_user, current_database(), current_schema()
--@ W90 expr
SELECT now()::date = current_date AS same_day
--@ W91 subquery
SELECT p.sku, (SELECT count(*) FROM order_items i WHERE i.product_id = p.id) AS times_ordered FROM products p ORDER BY p.sku
--@ W92 join
SELECT a.sku AS a, b.sku AS b FROM order_items x JOIN order_items y ON x.order_id = y.order_id AND x.product_id < y.product_id JOIN products a ON a.id = x.product_id JOIN products b ON b.id = y.product_id ORDER BY 1, 2
--# group web-transactions
--@ T01 txn
BEGIN
--@ T02 txn
INSERT INTO orders (user_id, total) VALUES ('11111111-1111-1111-1111-111111111111', 19.50) RETURNING id
--@ T03 txn
UPDATE products SET stock = stock - 1 WHERE sku = 'B-2' AND stock > 0 RETURNING stock
--@ T04 txn
COMMIT
--@ T05 txn
BEGIN
--@ T06 txn
SAVEPOINT before_item
--@ T07 txn
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (3, 1, 1, 49.90)
--@ T08 txn
ROLLBACK TO SAVEPOINT before_item
--@ T09 txn
RELEASE SAVEPOINT before_item
--@ T10 txn
COMMIT
--@ T11 txn
BEGIN ISOLATION LEVEL SERIALIZABLE
--@ T12 txn
SELECT stock FROM products WHERE sku = 'A-1' FOR UPDATE
--@ T13 txn
ROLLBACK
--@ T14 txn
START TRANSACTION
--@ T15 txn
SELECT id FROM orders WHERE status = 'pending' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
--@ T16 txn
ROLLBACK
--@ T17 txn
BEGIN
--@ T18 txn
SELECT 1/0
--@ T19 txn
SELECT 1
--@ T20 txn
ROLLBACK
--@ T21 session
SET statement_timeout = '5s'
--@ T22 session
SET search_path TO public
--@ T23 session
SET TIME ZONE 'UTC'
--@ T24 schema
TRUNCATE TABLE audit_log RESTART IDENTITY
--@ T25 read
SELECT count(*) FROM audit_log
--# group web-discovery
--@ C01 discovery
SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name
--@ C02 discovery
SELECT table_schema, table_name, table_type FROM information_schema.tables WHERE table_schema NOT IN ('pg_catalog', 'information_schema') ORDER BY 1, 2
--@ C03 discovery
SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'products' ORDER BY ordinal_position
--@ C04 discovery
SELECT table_name, column_name, data_type, udt_name FROM information_schema.columns WHERE table_schema = 'public' ORDER BY table_name, ordinal_position
--@ C05 discovery
SELECT tc.table_name, kcu.column_name, ccu.table_name AS foreign_table_name, ccu.column_name AS foreign_column_name FROM information_schema.table_constraints AS tc JOIN information_schema.key_column_usage AS kcu ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema JOIN information_schema.constraint_column_usage AS ccu ON ccu.constraint_name = tc.constraint_name AND ccu.table_schema = tc.table_schema WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' ORDER BY 1, 2
--@ C06 discovery
SELECT kcu.column_name FROM information_schema.table_constraints tc JOIN information_schema.key_column_usage kcu USING (constraint_name, table_schema) WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = 'order_items' ORDER BY kcu.ordinal_position
--@ C07 discovery
SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename
--@ C08 discovery
SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'products' ORDER BY indexname
--@ C09 discovery
SELECT c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY 1
--@ C10 discovery
SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull FROM pg_attribute a WHERE a.attrelid = 'products'::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum
--@ C11 discovery
SELECT to_regclass('public.users') IS NOT NULL AS users_exists, to_regclass('public.nope') IS NOT NULL AS nope_exists
--@ C12 discovery
SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'users')
--@ C13 discovery
SELECT conname, contype, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'order_items'::regclass ORDER BY conname
--@ C14 discovery
SELECT schema_name FROM information_schema.schemata WHERE schema_name NOT LIKE 'pg_%' ORDER BY 1
--@ C15 discovery
SELECT t.typname, e.enumlabel FROM pg_type t JOIN pg_enum e ON t.oid = e.enumtypid ORDER BY t.typname, e.enumsortorder
--@ C16 discovery volatile
SELECT extname FROM pg_extension ORDER BY 1
--@ C17 discovery
SELECT obj_description('public.users'::regclass) AS comment
--@ C18 discovery volatile
SELECT pg_size_pretty(pg_total_relation_size('products'))
--@ C19 discovery volatile
SELECT relname, reltuples::bigint AS estimate FROM pg_class WHERE relname = 'products'
--@ C20 discovery volatile own-value
SHOW server_version
--@ C21 discovery volatile own-value
SHOW search_path
--@ C22 orm
SELECT pg_catalog.pg_class.relname FROM pg_catalog.pg_class JOIN pg_catalog.pg_namespace ON pg_catalog.pg_namespace.oid = pg_catalog.pg_class.relnamespace WHERE pg_catalog.pg_class.relkind = ANY (ARRAY['r', 'p']) AND pg_catalog.pg_class.relpersistence != 't' AND pg_catalog.pg_table_is_visible(pg_catalog.pg_class.oid) AND pg_catalog.pg_namespace.nspname != 'pg_catalog' ORDER BY 1
--@ C23 orm
SELECT n.nspname AS table_schema, c.relname AS table_name, CASE WHEN c.relkind = 'r' THEN 'table' WHEN c.relkind = 'v' THEN 'view' WHEN c.relkind = 'm' THEN 'materialized_view' END AS type, c.relrowsecurity AS rls_enabled FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r', 'v', 'm') AND n.nspname = 'public' ORDER BY 2
--@ C24 orm
SELECT tbl.relname AS table_name, namespace.nspname AS namespace, (tbl.relhassubclass AND tbl.relkind = 'p') AS is_partition, tbl.relrowsecurity AS has_row_level_security, obj_description(tbl.oid, 'pg_class') AS description FROM pg_class AS tbl INNER JOIN pg_namespace AS namespace ON namespace.oid = tbl.relnamespace WHERE tbl.relkind IN ('r', 'p') AND namespace.nspname = ANY (ARRAY['public']) ORDER BY namespace, table_name
--@ C25 orm
SELECT i.relname AS index_name, ix.indisunique, a.attname AS column_name FROM pg_class t, pg_class i, pg_index ix, pg_attribute a WHERE t.oid = ix.indrelid AND i.oid = ix.indexrelid AND a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) AND t.relkind = 'r' AND t.relname = 'products' ORDER BY i.relname, a.attname
--@ C26 orm
SELECT info.table_name, info.column_name, format_type(att.atttypid, att.atttypmod) AS formatted_type, info.data_type, info.udt_name, pg_get_expr(attdef.adbin, attdef.adrelid) AS column_default, info.is_nullable, info.is_identity, info.character_maximum_length FROM information_schema.columns info JOIN pg_attribute att ON att.attname = info.column_name AND att.attrelid = (quote_ident(info.table_schema) || '.' || quote_ident(info.table_name))::regclass LEFT OUTER JOIN pg_attrdef attdef ON attdef.adrelid = att.attrelid AND attdef.adnum = att.attnum WHERE info.table_schema = 'public' AND info.table_name = 'users' ORDER BY info.ordinal_position
--@ C27 discovery
SELECT rc.constraint_name, rc.delete_rule, rc.update_rule FROM information_schema.referential_constraints rc ORDER BY 1
--@ C28 discovery
SELECT constraint_name, constraint_type FROM information_schema.table_constraints WHERE table_name = 'users' AND constraint_type <> 'CHECK' ORDER BY 1
--@ C29 discovery
SELECT c.oid IS NOT NULL AS found, n.nspname, c.relname FROM pg_catalog.pg_class c LEFT JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE c.relname OPERATOR(pg_catalog.~) '^(users)$' COLLATE pg_catalog.default AND pg_catalog.pg_table_is_visible(c.oid) ORDER BY 2, 3
--@ C30 discovery
SELECT column_name FROM information_schema.columns WHERE table_name = 'no_such_table'
--@ C31 discovery volatile
SELECT current_setting('server_version_num')::int >= 120000 AS modern
--@ C32 discovery
SELECT table_name, count(*) AS columns FROM information_schema.columns WHERE table_schema = 'public' GROUP BY table_name ORDER BY table_name
--# group web-mistakes
--@ E01 mistake
SELECT nme FROM products
--@ E02 mistake
SELECT p.nam FROM products p
--@ E03 mistake
SELECT * FROM usr
--@ E04 mistake
SELECT * FROM user
--@ E05 mistake
SELECT email FROM users WHERE id = 1
--@ E06 mistake
SELECT sku FROM products WHERE price = 'cheap'
--@ E07 mistake
SELECT sku FROM products WHERE name = "Mouse"
--@ E08 mistake
SELECT `name` FROM products
--@ E09 mistake
SELECT name, count(*) FROM products
--@ E10 mistake
SELECT id FROM users, orders
--@ E11 mistake
SELECT IFNULL(full_name, '') FROM users
--@ E12 mistake
SELECT GETDATE()
--@ E13 mistake
SELECT id FROM orders WHERE created_at > NOW() - INTERVAL 7 DAY
--@ E14 mistake
SELECT id FROM orders WHERE created_at > DATE_SUB(NOW(), INTERVAL '7 days')
--@ E15 mistake
SELECT sku FROM products ORDER BY id LIMIT 1, 2
--@ E16 mistake
INSERT INTO products (sku, name, price) VALUES ('A-1', 'Duplicate', 1)
--! rollback
--@ E17 mistake
INSERT INTO order_items (order_id, product_id, quantity, unit_price) VALUES (9999, 1, 1, 1)
--! rollback
--@ E18 mistake
INSERT INTO products (name, price) VALUES ('No sku', 1)
--! rollback
--@ E19 mistake
INSERT INTO products (sku, name, price) VALUES ('Z-9', 'Negative', -1)
--! rollback
--@ E20 mistake
INSERT INTO products (sku, name, price, color) VALUES ('Y-8', 'Colored', 1, 'red')
--! rollback
--@ E21 mistake
INSERT INTO products (sku, name, price) VALUES ('X-7', 'Short')
--! rollback
--@ E22 mistake
SELECT 'abc'::int
--@ E23 mistake
SELECT sku FROM products WHERE stock > '5' ORDER BY sku
--@ E24 mistake
SELECT sum(name) FROM products
--@ E25 mistake
SELECT sku FROM products ORDER BY nme
--@ E26 mistake
UPDATE products SET prise = 1 WHERE id = 1
--! rollback
--@ E27 mistake
SELECT "Name" FROM products
--@ E28 mistake
SELECT Name FROM Products ORDER BY SKU LIMIT 1
--@ E29 mistake
INSERT INTO users (email) VALUES (NULL)
--! rollback
--@ E30 mistake
SELECT * FROM public.products WHERE sku = 'A-1' AND
--@ E31 mistake
SELECT sku FROM products WHERE id = 'abc'
--@ E32 mistake
INSERT INTO orders (user_id, status) VALUES ('11111111-1111-1111-1111-111111111111', 'refunded')
--! rollback
--@ E33 mistake
SELECT u.email FROM users u JOIN orders o ON o.userid = u.id
--@ E34 mistake
DELETE FROM users WHERE email = 'ann@example.com'
--! rollback
--# group ddl-probes
--@ P01 probe
CREATE TABLE p01 (id serial PRIMARY KEY, v text)
--@ P02 probe
CREATE TABLE p02 (id bigserial PRIMARY KEY, v text)
--@ P03 probe
CREATE TABLE p03 (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, v text)
--@ P04 probe
CREATE TABLE p04 (id int GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, v text)
--@ P05 probe
CREATE TABLE p05 (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), v text)
--@ P06 probe
CREATE TABLE p06 (id int PRIMARY KEY, at timestamptz DEFAULT now())
--@ P07 probe
CREATE TABLE p07 (id int PRIMARY KEY, at timestamp DEFAULT CURRENT_TIMESTAMP)
--@ P08 probe
CREATE TABLE p08 (id int PRIMARY KEY, meta jsonb DEFAULT '{}')
--@ P09 probe
CREATE TABLE p09 (id int PRIMARY KEY, tags text[] DEFAULT '{}')
--@ P10 probe
CREATE TABLE p10 (id int PRIMARY KEY, active boolean NOT NULL DEFAULT true, n int NOT NULL DEFAULT 0, s text DEFAULT 'x', price numeric DEFAULT 0)
--@ P11 probe
CREATE TABLE p11 (id int PRIMARY KEY, name varchar(255))
--@ P12 probe
CREATE TABLE p12 (id int PRIMARY KEY, price numeric(10,2))
--@ P13 probe
CREATE TABLE p13 (id int PRIMARY KEY, n int CHECK (n > 0))
--@ P14 probe
CREATE TABLE p14 (id int PRIMARY KEY, a int, b int, CHECK (a < b))
--@ P15 probe
CREATE TABLE p15 (id int PRIMARY KEY, a int, b int, UNIQUE (a, b))
--@ P16 probe
CREATE TABLE p16 (a int, b int, PRIMARY KEY (a, b))
--@ P17 setup
CREATE TABLE parent (id bigint PRIMARY KEY, name text)
--@ P18 probe
CREATE TABLE p18 (id int PRIMARY KEY, parent_id bigint REFERENCES parent(id))
--@ P19 probe
CREATE TABLE p19 (id int PRIMARY KEY, parent_id bigint, FOREIGN KEY (parent_id) REFERENCES parent(id) ON DELETE CASCADE)
--@ P20 probe
CREATE TABLE p20 (source_id bigint NOT NULL REFERENCES parent(id), target_id bigint NOT NULL REFERENCES parent(id))
--@ P21 probe
CREATE TABLE p21 (source_id bigint NOT NULL REFERENCES parent(rid) ON DELETE CASCADE, target_id bigint NOT NULL REFERENCES parent(rid) ON DELETE CASCADE)
--@ P22 probe
CREATE TABLE p22 (source_id text NOT NULL REFERENCES parent(rid), target_id text NOT NULL REFERENCES parent(rid))
--@ P23 probe
CREATE TABLE p23 (follower_id text NOT NULL REFERENCES parent(rid) ON DELETE CASCADE, followee_id text NOT NULL REFERENCES parent(rid) ON DELETE CASCADE)
--@ P24 probe
CREATE TABLE p24 (source_id text NOT NULL REFERENCES parent(rid) ON DELETE CASCADE, target_id text NOT NULL REFERENCES parent(rid) ON DELETE CASCADE, PRIMARY KEY (source_id, target_id))
--@ P25 probe
CREATE TABLE p25 (name text)
--@ P26 probe
CREATE TABLE p26 (user_id bigint PRIMARY KEY, name text)
--@ P27 probe
CREATE TABLE p27 (id int PRIMARY KEY, doc json)
--@ P28 probe
CREATE TABLE p28 (id int PRIMARY KEY, u uuid)
--@ P29 probe
CREATE TABLE p29 (id int PRIMARY KEY, at timestamptz)
--@ P30 probe
CREATE TABLE p30 (id int PRIMARY KEY, at timestamp with time zone)
--@ P31 probe
CREATE TABLE p31 (id int PRIMARY KEY, t time)
--@ P32 probe
CREATE TABLE p32 (id int PRIMARY KEY, d date, ts timestamp without time zone)
--@ P33 probe
CREATE TABLE p33 (id int PRIMARY KEY, i interval)
--@ P34 probe
CREATE TABLE p34 (id int PRIMARY KEY, c char(2))
--@ P35 probe
CREATE TABLE p35 (id int PRIMARY KEY, a double precision, b real, c smallint, d integer, e bigint, f decimal, g boolean, h bytea)
--@ P36 probe
CREATE TABLE p36 (id int PRIMARY KEY, a int[], b bigint[], c numeric[])
--@ P37 probe
CREATE TABLE p37 (id int PRIMARY KEY, a int, b int GENERATED ALWAYS AS (a * 2) STORED)
--@ P38 probe
CREATE TABLE p38 AS SELECT * FROM parent
--@ P39 probe
CREATE SCHEMA app
--@ P40 probe
CREATE SEQUENCE order_seq
--@ P41 probe
ALTER TABLE parent ADD COLUMN extra text
--@ P42 probe
ALTER TABLE parent DROP COLUMN IF EXISTS extra
--@ P43 probe
DROP TABLE p25
--@ P44 probe
DROP TABLE IF EXISTS no_such_table
--@ P45 probe
CREATE EXTENSION IF NOT EXISTS pgcrypto
--@ P46 probe
CREATE EXTENSION IF NOT EXISTS "uuid-ossp"
--@ P47 probe
INSERT INTO p26 DEFAULT VALUES
--@ P48 probe
CREATE TABLE IF NOT EXISTS public.p48 (id int PRIMARY KEY)
--@ P49 probe
CREATE TABLE "User" (id int PRIMARY KEY, "createdAt" timestamp)
--@ P50 probe
CREATE TABLE p50 (id int PRIMARY KEY, name text NOT NULL, CONSTRAINT p50_name_key UNIQUE (name))
--@ P51 probe
ALTER TABLE parent ADD CONSTRAINT parent_name_key UNIQUE (name)
--@ P52 probe
DROP INDEX IF EXISTS some_index
--@ P53 probe
TRUNCATE parent
--@ P54 probe
MERGE INTO parent p USING (VALUES (1, 'a')) AS v(id, name) ON p.id = v.id WHEN NOT MATCHED THEN INSERT (id, name) VALUES (v.id, v.name)
--@ P55 probe
DO $$ BEGIN RAISE NOTICE 'hi'; END $$

--# group features
--@ F00 setup
CREATE TABLE f (id bigint PRIMARY KEY, name text, n int, price numeric, at timestamp, meta jsonb, tags text[], emb vector(3))
--! pg
CREATE EXTENSION IF NOT EXISTS vector; CREATE TABLE f (id bigint PRIMARY KEY, name text, n int, price numeric, at timestamp, meta jsonb, tags text[], emb vector(3))
--@ F01 setup
INSERT INTO f (id, name, n, price, at, meta, tags, emb) VALUES (1, 'ann', 10, 1.50, '2026-01-05 10:00:00', '{"a": 1, "b": {"c": "x"}, "l": ["p", "q"]}', '{red,blue}', '[1,0,0]'), (2, 'bob', 20, 2.25, '2026-02-10 11:30:00', '{"a": 2}', '{blue}', '[0,1,0]'), (3, 'cy', NULL, NULL, NULL, NULL, NULL, NULL)
--@ F02 feature
SELECT array_agg(name ORDER BY id) FROM f
--@ F03 feature
SELECT json_agg(name ORDER BY id) FROM f
--@ F04 feature
SELECT jsonb_agg(n) FROM f
--@ F05 feature
SELECT json_build_object('id', id, 'name', name) FROM f ORDER BY id
--@ F06 feature
SELECT jsonb_build_object('id', id) FROM f ORDER BY id
--@ F07 feature
SELECT row_to_json(t) FROM (SELECT id, name FROM f ORDER BY id) t
--@ F08 feature
SELECT to_jsonb(name) FROM f ORDER BY id
--@ F09 feature
SELECT meta->>'a' FROM f ORDER BY id
--@ F10 feature
SELECT meta->'b' FROM f ORDER BY id
--@ F11 feature
SELECT meta#>>'{b,c}' FROM f ORDER BY id
--@ F12 feature
SELECT id FROM f WHERE meta @> '{"a": 1}'
--@ F13 feature
SELECT id FROM f WHERE meta ? 'b'
--@ F14 feature
SELECT id FROM f WHERE meta['a'] = '1'
--@ F15 feature
SELECT jsonb_set(meta, '{a}', '5') FROM f WHERE id = 1
--@ F16 feature
SELECT meta || '{"z": 1}'::jsonb FROM f WHERE id = 1
--@ F17 feature
SELECT meta - 'a' FROM f WHERE id = 1
--@ F18 feature
SELECT jsonb_typeof(meta) FROM f ORDER BY id
--@ F19 feature
SELECT id, k.key, k.value FROM f, jsonb_each(meta) k WHERE id = 2
--@ F20 feature
SELECT jsonb_object_keys(meta) FROM f WHERE id = 1 ORDER BY 1
--@ F21 feature
SELECT jsonb_array_elements_text(meta->'l') FROM f WHERE id = 1
--@ F22 feature
SELECT id FROM f WHERE id = ANY(ARRAY[1, 2]) ORDER BY id
--@ F23 feature
SELECT id FROM f WHERE 'red' = ANY(tags) ORDER BY id
--@ F24 feature
SELECT id FROM f WHERE id <> ALL(ARRAY[1, 2]) ORDER BY id
--@ F25 feature
SELECT id FROM f WHERE tags @> ARRAY['red'] ORDER BY id
--@ F26 feature
SELECT id FROM f WHERE tags && ARRAY['red', 'green'] ORDER BY id
--@ F27 feature
SELECT unnest(tags) FROM f WHERE id = 1
--@ F28 feature
SELECT t.x FROM unnest(ARRAY[1, 2, 3]) AS t(x)
--@ F29 feature
SELECT array_length(tags, 1), cardinality(tags) FROM f ORDER BY id
--@ F30 feature
SELECT tags[1] FROM f ORDER BY id
--@ F31 feature
SELECT array_to_string(tags, ',') FROM f ORDER BY id
--@ F32 feature
SELECT string_to_array('a,b', ',')
--@ F33 feature
SELECT string_agg(name, ',' ORDER BY name) FROM f
--@ F34 feature volatile
SELECT string_agg(name, ',') FROM f
--@ F35 feature
SELECT now() - interval '7 days' < now()
--@ F36 feature volatile
SELECT now()
--@ F37 feature volatile
SELECT CURRENT_TIMESTAMP
--@ F38 feature volatile
SELECT LOCALTIMESTAMP, current_date
--@ F39 feature
SELECT id FROM f WHERE at > now() - interval '3650 days' ORDER BY id
--@ F40 feature
SELECT id FROM f WHERE at > LOCALTIMESTAMP - interval '3650 days' ORDER BY id
--@ F41 feature
SELECT id FROM f WHERE at > current_date - interval '3650 days' ORDER BY id
--@ F42 feature
SELECT date_trunc('month', at) FROM f ORDER BY id
--@ F43 feature
SELECT EXTRACT(YEAR FROM at), date_part('month', at) FROM f ORDER BY id
--@ F44 feature
SELECT to_char(at, 'YYYY-MM-DD HH24:MI') FROM f ORDER BY id
--@ F45 feature
SELECT at::date, at::time FROM f ORDER BY id
--@ F46 feature
SELECT current_date - at::date FROM f WHERE id = 99
--@ F47 feature
SELECT make_interval(days => 3)
--@ F48 feature
SELECT age(timestamp '2026-03-01', at) FROM f WHERE id = 1
--@ F49 feature
SELECT '2026-03-01T10:00:00Z'::timestamptz
--@ F50 feature
SELECT id FROM f WHERE at >= '2026-01-01T00:00:00Z' ORDER BY id
--@ F51 feature
SELECT id FROM f WHERE at BETWEEN '2026-01-01' AND '2026-01-31' ORDER BY id
--@ F52 feature
SELECT DISTINCT ON (n > 0) n FROM f ORDER BY n > 0, n
--@ F53 feature
SELECT id FROM f WHERE (n, id) < (20, 5) ORDER BY id
--@ F54 feature
SELECT id FROM f ORDER BY id FETCH FIRST 2 ROWS ONLY
--@ F55 feature
SELECT count(*) FILTER (WHERE n > 10) FROM f
--@ F56 feature
SELECT sum(n) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM f
--@ F57 feature
SELECT ntile(2) OVER (ORDER BY id) FROM f
--@ F58 feature
SELECT id, count(*) OVER (), row_number() OVER w FROM f WINDOW w AS (ORDER BY id)
--@ F59 feature
SELECT name, rank() OVER (ORDER BY sum(n)) FROM f GROUP BY name ORDER BY name
--@ F60 feature
WITH RECURSIVE t(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM t WHERE x < 3) SELECT x FROM t
--@ F61 feature
SELECT * FROM generate_series(1, 3) g(x)
--@ F62 feature
SELECT d::date FROM generate_series('2026-01-01'::timestamp, '2026-01-03'::timestamp, interval '1 day') d
--@ F63 feature
SELECT f.id, x.m FROM f, LATERAL (SELECT f.n * 2 AS m) x ORDER BY f.id
--@ F64 feature
SELECT bool_or(n > 10), bool_and(n > 0) FROM f
--@ F65 feature
SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY n), stddev(n) FROM f
--@ F66 feature
SELECT round(avg(price), 2), round(2.5), ceil(1.2), floor(1.8), abs(-3), mod(7, 3), power(2, 10), sqrt(16) FROM f
--@ F67 feature
SELECT lower(name), upper(name), length(name), substr(name, 1, 2), substring(name FROM 1 FOR 2), position('o' IN name), strpos(name, 'o'), replace(name, 'o', '0'), trim('  x '), left(name, 1), right(name, 1), lpad(name, 5, '*'), initcap(name), reverse(name) FROM f ORDER BY id
--@ F68 feature
SELECT concat(name, '-', n), concat_ws(', ', name, n), format('%s=%s', name, n), name || '-' || n FROM f ORDER BY id
--@ F69 feature
SELECT split_part('a@b.com', '@', 2), regexp_replace('a1b2', '[0-9]', '', 'g'), starts_with(name, 'a'), left(md5(name), 6) FROM f WHERE id = 1
--@ F70 feature
SELECT id FROM f WHERE name ~* '^A'
--@ F71 feature
SELECT id FROM f WHERE name SIMILAR TO 'a%'
--@ F72 feature
SELECT coalesce(n, 0), nullif(n, 10), greatest(n, 15), least(n, 15) FROM f ORDER BY id
--@ F73 feature
SELECT n::text, '42'::int, price::int, CAST(id AS varchar), 1.5::numeric(10,1) FROM f ORDER BY id
--@ F74 feature
SELECT gen_random_uuid() IS NOT NULL
--@ F75 feature
SELECT 'a3bb189e-8bf9-3888-9912-ace4e6543002'::uuid
--@ F76 feature
SELECT to_tsvector('english', 'dark modes') @@ plainto_tsquery('english', 'mode')
--@ F77 feature
SELECT similarity('abc', 'abd')
--@ F78 feature
SELECT id, emb <=> '[1,0,0]' FROM f WHERE emb IS NOT NULL ORDER BY 2, 1
--@ F79 feature
SELECT id FROM f ORDER BY emb <=> '[1,0,0]'::vector LIMIT 2
--@ F80 feature
SELECT l2_distance(emb, '[1,0,0]'), cosine_distance(emb, '[1,0,0]') FROM f WHERE id = 1
--@ F81 feature
SELECT avg(emb) FROM f
--@ F82 feature
SELECT id FROM f ORDER BY n NULLS FIRST, id
--@ F83 feature
SELECT n FROM f ORDER BY n DESC NULLS LAST
--@ F84 feature
SELECT count(DISTINCT n), count(n), count(*) FROM f
--@ F85 feature
SELECT version() LIKE 'PostgreSQL%'
--@ F86 feature volatile
SELECT current_user, current_database(), current_schema(), current_schema, session_user
--@ F87 feature volatile own-value
SHOW server_version_num
--@ F88 feature
SHOW TIME ZONE
--@ F89 feature
SELECT set_config('app.user', 'x', false)
--@ F90 feature
SELECT pg_advisory_lock(1)
--@ F91 feature
SELECT nextval('f_id_seq')
--@ F92 feature
SELECT currval(pg_get_serial_sequence('f', 'id'))
--@ F93 feature
SELECT * FROM f WHERE id = 1 FOR UPDATE
--@ F94 feature
SELECT * FROM f WHERE id = 1 FOR SHARE
--@ F95 feature
EXPLAIN SELECT * FROM f WHERE id = 1
--@ F96 feature volatile
EXPLAIN ANALYZE SELECT * FROM f WHERE id = 1
--@ F97 feature
SELECT * FROM f NATURAL JOIN f g
--@ F98 feature
SELECT t.id FROM f AS t WHERE EXISTS (SELECT 1 FROM f u WHERE u.id = t.id AND u.n > 10) ORDER BY t.id
--@ F99 feature
SELECT (SELECT array_agg(g.name) FROM f g) AS names
--@ F100 feature
SELECT ARRAY(SELECT name FROM f ORDER BY id)
--@ F101 feature
SELECT f FROM f WHERE id = 1
--@ F102 feature
SELECT 1 AS one WHERE NOT EXISTS (SELECT 1 FROM f WHERE id = 100)
--@ F103 feature
INSERT INTO f (id, name) VALUES (10, 'x') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name || '!' RETURNING id, name
--@ F104 feature
INSERT INTO f (id, name) VALUES (10, 'x') ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name || '!' RETURNING id, name
--@ F105 feature
INSERT INTO f (id, name) VALUES (11, 'y') RETURNING *
--@ F106 feature
UPDATE f SET n = COALESCE(n, 0) + 1 WHERE id = 3 RETURNING id, n
--@ F107 feature
UPDATE f SET meta = '{"k": 1}' WHERE id = 3 RETURNING meta
--@ F108 feature
UPDATE f SET tags = array_append(tags, 'new') WHERE id = 2 RETURNING tags
--@ F109 feature
UPDATE f SET tags = tags || ARRAY['more'] WHERE id = 2 RETURNING tags
--@ F110 feature
UPDATE f SET at = now() WHERE id = 3
--@ F111 feature
INSERT INTO f (id, name, at) VALUES (12, 'z', '2026-03-01T10:00:00Z')
--@ F112 feature
INSERT INTO f (id, name, at) VALUES (13, 'z', '2026-03-01 10:00:00+09')
--@ F113 feature
INSERT INTO f (id, name, meta) VALUES (14, 'z', '[1, 2]')
--@ F114 feature
DELETE FROM f WHERE id = ANY(ARRAY[10, 11]) RETURNING id
--@ F115 feature
DELETE FROM f WHERE id IN (10, 11, 12, 13, 14) RETURNING id
--@ F116 param
SELECT name FROM f WHERE id = ?
--! param long 2
--@ F117 param
SELECT name FROM f WHERE id = ANY(?) ORDER BY id
--! param bigint-array 1,2
--@ F118 param
SELECT id FROM f WHERE name IN (?, ?) ORDER BY id
--! param string ann
--! param string bob
--@ F119 param
SELECT id FROM f ORDER BY id LIMIT ? OFFSET ?
--! param int 2
--! param int 1
--@ F120 param
SELECT id FROM f ORDER BY emb <=> ?::vector LIMIT 1
--! param string [0,1,0]
--@ F121 param
INSERT INTO f (id, name, meta) VALUES (?, ?, ?::jsonb) RETURNING id, name, meta
--! param long 20
--! param string param
--! param string {"a": 3}
--@ F122 param
INSERT INTO f (id, name, meta) VALUES (?, ?, ?) RETURNING id, meta
--! param long 21
--! param string param2
--! param jsonb {"a": 4}
--@ F123 param
SELECT id FROM f WHERE at > ? ORDER BY id
--! param timestamp 2026-01-31 00:00:00
--@ F124 param
SELECT id FROM f WHERE meta @> ?::jsonb
--! param string {"a": 1}
--@ F125 param
SELECT id FROM f WHERE name ILIKE '%' || ? || '%' ORDER BY id
--! param string AN
--@ F126 param
SELECT id FROM f WHERE ? = ANY(tags) ORDER BY id
--! param string red

--# group design
--@ D01 setup
CREATE TABLE person (id bigint PRIMARY KEY, name text)
--! pg
CREATE TABLE person (rid bigint GENERATED ALWAYS AS IDENTITY UNIQUE, id bigint PRIMARY KEY, name text)
--@ D02 setup
CREATE TABLE knows (source_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE, target_id text NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int)
--! pg
CREATE TABLE knows (source_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, target_id bigint NOT NULL REFERENCES person (rid) ON DELETE CASCADE, since int)
--@ D03 setup
INSERT INTO person (id, name) VALUES (1, 'Ada'), (2, 'Bo'), (3, 'Cy')
--@ D04 design
INSERT INTO knows (source_id, target_id, since) VALUES (1, 2, 2022)
--@ D05 design
INSERT INTO knows (source_id, target_id, since) VALUES ('1', '2', 2022)
--@ D06 design
INSERT INTO knows (source_id, target_id, since) SELECT a.rid, b.rid, 2022 FROM person a, person b WHERE a.id = 1 AND b.id = 2
--@ D07 design
INSERT INTO knows (source_id, target_id, since) VALUES ((SELECT rid FROM person WHERE id = 2), (SELECT rid FROM person WHERE id = 3), 2023) RETURNING source_id, target_id
--@ D08 design
SELECT q.name FROM person p JOIN knows k ON k.source_id = p.id JOIN person q ON q.id = k.target_id
--@ D09 design
SELECT q.name FROM person p JOIN knows k ON k.source_id = p.rid JOIN person q ON q.rid = k.target_id ORDER BY q.name
--@ D10 design
SELECT * FROM knows ORDER BY since
--@ D11 design
SELECT rid, id, name FROM person ORDER BY id
--@ D12 design
SELECT * FROM person ORDER BY id
--@ D13 design
SELECT name FROM person WHERE rid = 1
--@ D14 design
SELECT name FROM person WHERE rid = '1'
--@ D15 design
SELECT source_id, count(*) FROM knows WHERE source_id IN (SELECT id FROM person) GROUP BY source_id
--@ D16 design
SELECT p.name FROM person p WHERE p.id IN (SELECT source_id FROM knows)
--@ D17 setup
CREATE TABLE tag (label text)
--! pg
CREATE TABLE tag (id bigint GENERATED ALWAYS AS IDENTITY, label text)
--@ D18 setup
INSERT INTO tag (label) VALUES ('t01'), ('t02'), ('t03'), ('t04'), ('t05'), ('t06'), ('t07'), ('t08'), ('t09'), ('t10'), ('t11'), ('t12'), ('t13'), ('t14'), ('t15'), ('t16'), ('t17'), ('t18'), ('t19'), ('t20'), ('t21'), ('t22'), ('t23'), ('t24'), ('t25'), ('t26'), ('t27'), ('t28'), ('t29'), ('t30')
--@ D19 design
SELECT id, label FROM tag ORDER BY id LIMIT 8
--@ D20 design
SELECT id, label FROM tag ORDER BY id DESC LIMIT 3
--@ D21 design
SELECT max(id), min(id) FROM tag
--@ D22 design
SELECT * FROM tag ORDER BY label LIMIT 2
--@ D23 design
INSERT INTO tag (id, label) VALUES (100, 'x')
--@ D24 design
SELECT label FROM tag WHERE id = 5
--@ D25 design
INSERT INTO tag (label) VALUES ('late') RETURNING id
--@ D26 setup
CREATE TABLE noted (id bigint, label text)
--@ D27 design
INSERT INTO noted (label) VALUES ('no id given') RETURNING id, label
--@ D30 design
CREATE VERTEX TYPE City
--! via-sql
--! pg
CREATE TABLE city (zip text, name text)
--@ D31 design
CREATE PROPERTY City.zip STRING
--! via-sql
--! pg
SELECT 1
--@ D32 design
INSERT INTO City SET name = 'Delft', zip = '2611'
--! via-sql
--! pg
INSERT INTO city (zip, name) VALUES ('2611', 'Delft')
--@ D33 design
SELECT name FROM City
--@ D34 design
SELECT * FROM City
--@ D35 design
INSERT INTO City (zip, name) VALUES ('1012', 'Amsterdam')
--@ D36 design
SELECT zip FROM "city" ORDER BY zip
--@ D40 design
CREATE DOCUMENT TYPE memo
--! via-sql
--! pg
CREATE TABLE person_ref (marker int)
--@ D41 design
CREATE PROPERTY memo.title STRING
--! via-sql
--! pg
CREATE TABLE memo (title text, author bigint REFERENCES person (rid))
--@ D42 design
CREATE PROPERTY memo.author LINK OF person
--! via-sql
--! pg
SELECT 1
--@ D43 design
INSERT INTO memo (title, author) VALUES ('hello', (SELECT rid FROM person WHERE id = 3))
--@ D44 design
DELETE FROM person WHERE id = 3
--@ D45 design
INSERT INTO person (id, name) VALUES (4, 'Dee') RETURNING rid, id, name
--@ D46 design
SELECT m.title, p.name FROM memo m LEFT JOIN person p ON p.rid = m.author
--@ D47 design
SELECT count(*) FROM knows
--@ D48 design
SELECT constraint_name, table_name, enforced FROM information_schema.table_constraints WHERE constraint_type = 'FOREIGN KEY' ORDER BY 1
--@ D50 design
UPDATE knows SET target_id = (SELECT rid FROM person WHERE id = 1)
--@ D51 design
SELECT column_name, data_type, is_nullable, column_default, is_identity FROM information_schema.columns WHERE table_name = 'person' ORDER BY ordinal_position
--@ D60 setup
CREATE TABLE acct (id bigint PRIMARY KEY, v text)
--@ D61 setup
INSERT INTO acct (id, v) VALUES (1, 'a')
--@ D62 savepoint
BEGIN
--@ D63 savepoint
INSERT INTO acct (id, v) VALUES (2, 'b')
--@ D64 savepoint
SAVEPOINT sp1
--@ D65 savepoint
INSERT INTO acct (id, v) VALUES (1, 'dup')
--@ D66 savepoint
ROLLBACK TO SAVEPOINT sp1
--@ D67 savepoint
INSERT INTO acct (id, v) VALUES (3, 'c')
--@ D68 savepoint
COMMIT
--@ D69 savepoint
SELECT id, v FROM acct ORDER BY id
--@ D70 savepoint
BEGIN
--@ D71 savepoint
SAVEPOINT sp2
--@ D72 savepoint
INSERT INTO acct (id, v) VALUES (4, 'd')
--@ D73 savepoint
ROLLBACK TO SAVEPOINT sp2
--@ D74 savepoint
COMMIT
--@ D75 savepoint
SELECT id, v FROM acct ORDER BY id

--# group followups
--@ G00 setup
CREATE TABLE t (id bigint PRIMARY KEY, name text, n int, at timestamp, meta jsonb, tags text[])
--@ G01 setup
INSERT INTO t (id, name, n, at, meta, tags) VALUES (1, 'ann', 10, '2026-01-05 10:00:00', '{"a": 1}', '{red,blue}'), (2, 'bob', 20, '2026-02-10 11:30:00', '{"a": 2}', '{blue}')
--@ G02 time volatile
SELECT now()
--@ G03 time volatile
SELECT CURRENT_TIMESTAMP
--@ G04 time volatile
SELECT LOCALTIMESTAMP
--@ G05 time volatile
SELECT current_date
--@ G06 time volatile
SELECT now() AS server_time
--@ G07 time volatile
SELECT clock_timestamp()
--@ G08 session volatile
SELECT current_database()
--@ G09 session volatile
SELECT current_schema()
--@ G10 session volatile
SELECT current_user
--@ G11 session volatile own-value
SELECT version()
--@ G12 session volatile
SELECT current_database(), count(*) FROM t
--@ G13 iso
INSERT INTO t (id, at) VALUES (10, '2026-03-01T10:00:00')
--@ G14 iso
INSERT INTO t (id, at) VALUES (11, '2026-03-01T10:00:00.000Z')
--@ G15 iso
INSERT INTO t (id, at) VALUES (12, '2026-03-01T10:00:00+00:00')
--@ G16 iso
INSERT INTO t (id, at) VALUES (13, '2026-03-01 10:00:00.123456')
--@ G17 iso
SELECT id FROM t WHERE at > '2026-01-31T00:00:00Z' ORDER BY id
--@ G20 json
SELECT jsonb_extract_path_text(meta, 'a') FROM t ORDER BY id
--@ G21 json
SELECT id, meta::text FROM t WHERE meta IS NOT NULL ORDER BY id
--@ G22 json
SELECT id FROM t WHERE meta::text LIKE '%"a"%' ORDER BY id
--@ G23 json
SELECT json_extract_path_text(meta::json, 'a') FROM t ORDER BY id
--@ G24 json
SELECT id, meta FROM t ORDER BY id
--@ G25 array
SELECT id FROM t WHERE array_position(tags, 'red') IS NOT NULL
--@ G26 array
SELECT id FROM t WHERE tags::text LIKE '%red%' ORDER BY id
--@ G27 array
SELECT id FROM t WHERE 'red' IN (SELECT unnest(tags))
--@ G28 array
SELECT id FROM t WHERE tags = '{blue}'
--@ G30 group
SELECT n > 10 AS big, count(*) FROM t GROUP BY big ORDER BY big
--@ G31 group
SELECT n > 10 AS big, count(*) FROM t GROUP BY 1 ORDER BY 1
--@ G32 group
SELECT n > 10 AS big, count(*) FROM t GROUP BY n > 10 ORDER BY big
--@ G33 group
SELECT id, name, count(*) FROM t GROUP BY id ORDER BY id
--@ G34 group
SELECT name, count(*) AS n FROM t GROUP BY name ORDER BY n DESC, name
--@ G40 exists
SELECT EXISTS (SELECT FROM t WHERE id = 1)
--@ G41 exists
SELECT EXISTS (SELECT 1 FROM t WHERE id = 1)
--@ G50 txn-ddl
BEGIN
--@ G51 txn-ddl
CREATE TABLE mig1 (id bigint PRIMARY KEY)
--@ G52 txn-ddl
ROLLBACK
--@ G53 txn-ddl
BEGIN
--@ G54 txn-ddl
CREATE INDEX t_name_idx ON t (name)
--@ G55 txn-ddl
ROLLBACK
--@ G56 txn-ddl
CREATE TABLE mig2 (id bigint PRIMARY KEY); CREATE TABLE mig3 (id bigint PRIMARY KEY)
--@ G57 txn-ddl
SELECT table_name FROM information_schema.tables WHERE table_name LIKE 'mig%' ORDER BY 1
--@ G60 set
SET statement_timeout = '5s'
--@ G61 set volatile
SHOW statement_timeout
--@ G62 set
SET TIME ZONE 'Asia/Seoul'
--@ G63 set
SHOW TIME ZONE
--@ G64 set
SET search_path TO no_such_schema
--@ G65 set
SELECT count(*) FROM t WHERE id < 3
--@ G66 set
SET search_path TO public
--@ G67 set
SET no_such_setting = 'x'
--@ G68 set
BEGIN READ ONLY
--@ G69 set
INSERT INTO t (id, name) VALUES (20, 'written in a read only transaction')
--@ G70 set
COMMIT
--@ G71 set
SELECT count(*) FROM t WHERE id = 20
--@ G72 set
SET default_transaction_read_only = on
--@ G73 set
INSERT INTO t (id, name) VALUES (21, 'written with default read only')
--@ G74 set
SET default_transaction_read_only = off
--@ G75 set
SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL SERIALIZABLE
--@ G76 set
BEGIN ISOLATION LEVEL REPEATABLE READ
--@ G77 set
ROLLBACK
--@ G78 set
SET CONSTRAINTS ALL DEFERRED
--@ G80 misc
LOCK TABLE t IN EXCLUSIVE MODE
--@ G81 misc
ANALYZE t
--@ G82 misc
VACUUM t
--@ G83 misc
PREPARE q AS SELECT name FROM t WHERE id = $1
--@ G84 misc
EXECUTE q(1)
--@ G85 misc
DEALLOCATE q
--@ G86 misc
GRANT SELECT ON t TO PUBLIC
--@ G87 misc
COPY t (id, name) TO STDOUT
--@ G88 misc
LISTEN events
--@ G89 misc
DISCARD ALL
--@ G90 rid setup
CREATE TABLE doc (title text)
--@ G91 rid setup
INSERT INTO doc (title) VALUES ('x'), ('y')
--@ G92 rid
SELECT title FROM doc WHERE id = 'abc'
--@ G93 rid
SELECT title FROM doc WHERE id = '#999:999'
--@ G94 rid
UPDATE doc SET title = 'z' WHERE id = '1'
--@ G95 rid
DELETE FROM doc WHERE id = '12'
--@ G96 rid
SELECT title FROM doc WHERE id = ANY(ARRAY['#1:0'])
--@ G97 rid
SELECT title FROM doc WHERE id::bigint = 1
--@ G100 hint setup
CREATE TABLE price (id bigint PRIMARY KEY, amount int)
--@ G101 hint setup
INSERT INTO price (id, amount) VALUES (1, 10)
--@ G102 hint
UPDATE price SET amont = 99 WHERE id = 1
--@ G103 hint setup
CREATE PROPERTY price.amont INTEGER
--! via-sql
--! pg
ALTER TABLE price ADD COLUMN amont integer
--@ G104 hint
UPDATE price SET amont = 99 WHERE id = 1
--@ G105 hint
SELECT * FROM price
--@ G110 error
SELECT * FROM t WHERE name = 'ann' AND n = 'ten'
--@ G111 error
SELECT nam, n FROM t
--@ G112 error
INSERT INTO t (id, n) VALUES (30, 'many')
--@ G113 error
INSERT INTO t (id, name) VALUES (1, 'dup')
--@ G114 fk setup
CREATE TABLE author (id bigint PRIMARY KEY, name text)
--@ G115 fk
CREATE TABLE book (id bigint PRIMARY KEY, author_id bigint NOT NULL REFERENCES author (id), title text)
--@ G116 fk
CREATE TABLE wrote (source_id bigint REFERENCES author (id), target_id bigint REFERENCES author (id))
--@ G117 fk
CREATE TABLE wrote2 (source_id text REFERENCES author (rid), target_id text REFERENCES author (rid))
--@ G118 identity
CREATE TABLE auto1 (name text)
--@ G119 identity
INSERT INTO auto1 (name) VALUES ('first') RETURNING id
--@ G120 identity
CREATE TABLE auto2 (id bigint PRIMARY KEY, name text)
--@ G121 identity
INSERT INTO auto2 (id, name) VALUES ((SELECT coalesce(max(id), 0) + 1 FROM auto2), 'first') RETURNING id
--@ G122 uuid
CREATE TABLE auto3 (id text PRIMARY KEY DEFAULT gen_random_uuid(), name text)
--@ G123 uuid
CREATE TABLE auto4 (id text PRIMARY KEY, name text)
--@ G124 uuid volatile
INSERT INTO auto4 (id, name) VALUES (gen_random_uuid()::text, 'first') RETURNING id

--# group followups2
--@ H00 setup
CREATE TABLE acc (id bigint PRIMARY KEY, amount int)
--@ H01 setup
INSERT INTO acc (id, amount) VALUES (1, 100)
--@ H02 refused-begin
BEGIN ISOLATION LEVEL SERIALIZABLE
--@ H03 refused-begin
UPDATE acc SET amount = 0 WHERE id = 1
--@ H04 refused-begin
ROLLBACK
--@ H05 refused-begin
SELECT amount FROM acc WHERE id = 1
--@ H10 timezone
SET TIME ZONE 'Asia/Seoul'
--@ H11 timezone volatile
SELECT date_part('hour', LOCALTIMESTAMP) AS local_hour
--@ H12 timezone
SET TIME ZONE 'UTC'
--@ H13 timezone volatile
SELECT date_part('hour', LOCALTIMESTAMP) AS local_hour
--@ H20 marker
{sql} CREATE PROPERTY acc.note STRING
--! pg
ALTER TABLE acc ADD COLUMN note text
--@ H21 marker
SELECT id, amount, note FROM acc
--@ H22 marker
{sql} ALTER TABLE acc ADD COLUMN extra text
--! pg
ALTER TABLE acc ADD COLUMN extra text
--@ H23 marker
{sql} DROP TYPE acc UNSAFE
--! pg
DROP TABLE acc
--@ H24 marker
SELECT count(*) FROM acc
--@ H30 setup
CREATE TABLE js (id bigint PRIMARY KEY, meta jsonb)
--@ H31 setup
INSERT INTO js (id, meta) VALUES (1, '{"a": 1}')
--@ H32 json
SELECT id FROM js WHERE meta = '{"a": 1}'
--@ H33 json
SELECT id, meta FROM js
