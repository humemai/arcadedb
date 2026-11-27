--@ M02
--! fallback
CREATE TABLE IF NOT EXISTS entities (id bigint PRIMARY KEY, name text NOT NULL UNIQUE, type text NOT NULL, metadata jsonb, created_at timestamp, updated_at timestamp)
--@ M03
--! fallback
CREATE TABLE IF NOT EXISTS facts (id bigint PRIMARY KEY, entity_id bigint NOT NULL, content text NOT NULL, confidence real DEFAULT 1.0, source text, embedding vector(3), tags text[], created_at timestamp)
--@ M04
--! fallback
CREATE TABLE IF NOT EXISTS relations (id bigint PRIMARY KEY, source_id bigint NOT NULL, target_id bigint NOT NULL, relation_type text NOT NULL, weight double precision DEFAULT 1, created_at timestamp, UNIQUE (source_id, target_id, relation_type))
--@ M09
--! fallback
INSERT INTO entities (id, name, type, metadata, created_at, updated_at) VALUES (1, 'Alice', 'person', '{"role": "engineer", "city": "Berlin", "address": {"city": "Berlin", "zip": "10115"}, "aliases": ["Al", "Ally"]}', '2026-11-27 00:00:00', '2026-11-27 00:00:00') RETURNING id
--@ M10
--! fallback
INSERT INTO entities (id, name, type, metadata, created_at, updated_at) VALUES (2, 'Bob', 'person', '{"role": "designer"}', '2026-11-27 00:00:00', '2026-11-27 00:00:00'), (3, 'Acme', 'organization', '{"industry": "software", "size": 120}', '2026-11-27 00:00:00', '2026-11-27 00:00:00') RETURNING id, name
--@ M11
--! fallback
INSERT INTO facts (id, entity_id, content, confidence, source, embedding, tags, created_at) VALUES (1, 1, 'Alice prefers dark mode', 0.9, 'chat', '[0.1,0.2,0.3]', ARRAY['preference', 'ui'], '2026-11-27 00:00:00')
--@ M12
--! fallback
INSERT INTO facts (id, entity_id, content, confidence, source, embedding, tags, created_at) VALUES (2, 1, 'Alice lives in Berlin', 0.8, 'profile', '[0.9,0.1,0.0]', '{location}', '2026-11-27 00:00:00'), (3, 2, 'Bob likes coffee', 0.6, 'chat', '[0.2,0.8,0.1]', '{preference,food}', '2026-11-27 00:00:00'), (4, 3, 'Acme was founded in 2012', 0.2, NULL, NULL, '{}', '2026-11-27 00:00:00') RETURNING id, entity_id
--@ M13
--! fallback
INSERT INTO facts (id, entity_id, content, tags, created_at) VALUES (5, 2, 'Bob dislikes meetings', '{}', '2026-11-27 00:00:00') RETURNING *
--@ M14
--! fallback
INSERT INTO relations (id, source_id, target_id, relation_type, created_at) VALUES (1, 1, 3, 'works_at', '2026-11-27 00:00:00'), (2, 2, 3, 'works_at', '2026-11-27 00:00:00'), (3, 1, 2, 'knows', '2026-11-27 00:00:00') RETURNING id, source_id, target_id
--@ M15
--! fallback
INSERT INTO entities (id, name, type, metadata) VALUES (4, 'Alice', 'person', '{"role": "engineer", "city": "Berlin", "address": {"city": "Berlin", "zip": "10115"}, "aliases": ["Al", "Ally"], "team": "platform"}') ON CONFLICT (name) DO UPDATE SET metadata = EXCLUDED.metadata RETURNING id, name, metadata
--@ M16
--! fallback
INSERT INTO relations (id, source_id, target_id, relation_type) VALUES (4, 1, 2, 'knows') ON CONFLICT DO NOTHING
--@ M17
--! fallback
INSERT INTO relations (id, source_id, target_id, relation_type, weight) VALUES (5, 1, 2, 'knows', 0.5) ON CONFLICT (source_id, target_id, relation_type) DO UPDATE SET weight = relations.weight + EXCLUDED.weight RETURNING weight
--@ M24
--! fallback
UPDATE entities SET metadata = '{"role": "engineer", "city": "Munich", "address": {"city": "Berlin", "zip": "10115"}, "aliases": ["Al", "Ally"], "team": "platform"}' WHERE name = 'Alice'
--@ M25
--! fallback
UPDATE entities SET metadata = '{"role": "designer", "verified": true}' WHERE name = 'Bob' RETURNING metadata
--@ M26
--! fallback
UPDATE entities SET metadata = '{"role": "designer"}' WHERE name = 'Bob'
--@ M71
--! fallback
UPDATE facts SET confidence = confidence + 0.05 WHERE entity_id = 2 AND confidence < 0.9 RETURNING id
--@ M72
--! fallback
UPDATE facts f SET source = e.type FROM entities e WHERE e.id = f.entity_id AND f.source IS NULL
--@ M75
--! fallback
DELETE FROM facts WHERE source = 'profile'
--@ M76
--! rollback
--@ M78
--! after
DELETE FROM facts WHERE entity_id = 2
--@ M80
--! fallback-sql
CREATE PROPERTY facts.expires_at IF NOT EXISTS DATETIME_MICROS
--@ M81
--! fallback-sql
CREATE PROPERTY entities.importance IF NOT EXISTS INTEGER
--! after-sql
UPDATE entities SET importance = 0 WHERE importance IS NULL
--@ M84
--! fallback
INSERT INTO facts (id, entity_id, content, embedding) VALUES (6, (SELECT id FROM entities WHERE name = 'Acme'), 'Acme ships a CLI', '[0.3,0.3,0.4]') RETURNING entity_id, content
--@ W01
--! fallback
CREATE TABLE users (id text PRIMARY KEY, email text NOT NULL UNIQUE, full_name varchar, is_active boolean NOT NULL DEFAULT true, created_at timestamp)
--@ W02
--! fallback
CREATE TABLE products (id integer PRIMARY KEY, sku varchar NOT NULL UNIQUE, name text NOT NULL, price numeric NOT NULL, stock integer NOT NULL DEFAULT 0)
--@ W04
--! fallback
CREATE TABLE orders (id bigint PRIMARY KEY, user_id text NOT NULL, status text NOT NULL DEFAULT 'pending', total numeric NOT NULL DEFAULT 0, created_at timestamp)
--@ W05
--! fallback
CREATE TABLE order_items (order_id bigint NOT NULL, product_id integer NOT NULL, quantity integer NOT NULL, unit_price numeric NOT NULL, PRIMARY KEY (order_id, product_id))
--@ W09
--! fallback-sql
CREATE PROPERTY users.last_login IF NOT EXISTS DATETIME_MICROS
--@ W10
--! fallback-sql
CREATE PROPERTY products.description IF NOT EXISTS STRING
--@ W16
--! fallback
CREATE TABLE audit_log (id bigint PRIMARY KEY, user_id text, action text NOT NULL, payload jsonb, at timestamp)
--@ W24
--! fallback
INSERT INTO users (id, email, full_name) VALUES ('22222222-2222-2222-2222-222222222222', 'ben@example.com', 'Ben Ode') RETURNING id
--! after
UPDATE users SET created_at = '2026-11-27 00:00:00' WHERE created_at IS NULL
--@ W25
--! after
UPDATE users SET created_at = '2026-11-27 00:00:00' WHERE created_at IS NULL
--@ W26
--! fallback
INSERT INTO products (id, sku, name, price, stock) VALUES (1, 'A-1', 'Keyboard', 49.90, 10), (2, 'B-2', 'Mouse', 19.50, 25), (3, 'C-3', 'Monitor', 229.00, 5) RETURNING id, sku
--@ W27
--! fallback
INSERT INTO orders (id, user_id, created_at) VALUES (1, '11111111-1111-1111-1111-111111111111', '2026-11-27 00:00:00') RETURNING id, status, total
--@ W30
--! fallback
INSERT INTO orders (id, user_id, status, created_at) VALUES (2, (SELECT id FROM users WHERE email = 'ben@example.com'), 'paid', '2026-11-27 00:00:00') RETURNING id
--@ W32
--! fallback
UPDATE orders SET total = 119.30 WHERE id = 1
--@ W33
--! fallback
UPDATE orders SET total = s.total FROM (SELECT order_id, sum(quantity * unit_price) AS total FROM order_items GROUP BY order_id) s WHERE orders.id = s.order_id
--@ W36
--! fallback
UPDATE users SET last_login = LOCALTIMESTAMP WHERE email = 'ann@example.com'
--@ W69
--! fallback
INSERT INTO users (id, email, full_name) VALUES ('99999999-9999-9999-9999-999999999999', 'ann@example.com', 'Ann B. Lee') ON CONFLICT (email) DO UPDATE SET full_name = EXCLUDED.full_name RETURNING email, full_name
--@ W70
--! fallback
INSERT INTO products (id, sku, name, price) VALUES (11, 'A-1', 'Keyboard', 59.90) ON CONFLICT (sku) DO UPDATE SET price = EXCLUDED.price RETURNING sku, price
--@ W71
--! fallback
INSERT INTO products AS p (id, sku, name, price, stock) VALUES (12, 'B-2', 'Mouse', 19.50, 5) ON CONFLICT (sku) DO UPDATE SET stock = p.stock + EXCLUDED.stock RETURNING sku, stock
--@ W73
--! fallback
DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE status = 'cancelled')
--@ W85
--! fallback
INSERT INTO products (id, sku, name, price) SELECT 4, 'D-4', 'Cable', 5.00 WHERE NOT EXISTS (SELECT 1 FROM products WHERE sku = 'D-4') RETURNING id, sku, stock
--@ W86
--! fallback
INSERT INTO audit_log (id, user_id, action, payload, at) VALUES (1, '11111111-1111-1111-1111-111111111111', 'login', '{"ip": "10.0.0.1", "ok": true}', '2026-11-27 00:00:00')
--@ T02
--! fallback
ROLLBACK; BEGIN; INSERT INTO orders (id, user_id, total, created_at) VALUES (3, '11111111-1111-1111-1111-111111111111', 19.50, '2026-11-27 00:00:00') RETURNING id
--@ T10
--! after
DELETE FROM order_items WHERE order_id = 3
--@ T24
--! fallback
DELETE FROM audit_log
--@ E16
--! rollback
--! fallback
INSERT INTO products (id, sku, name, price) VALUES (99, 'A-1', 'Duplicate', 1)
--@ E17
--! rollback
--@ E18
--! rollback
--@ E19
--! rollback
--! fallback
INSERT INTO products (id, sku, name, price) VALUES (99, 'Z-9', 'Negative', -1)
--@ E20
--! rollback
--@ E21
--! rollback
--@ E26
--! rollback
--@ E29
--! rollback
--@ E32
--! rollback
--! fallback
INSERT INTO orders (id, user_id, status) VALUES (99, '11111111-1111-1111-1111-111111111111', 'refunded')
--@ E34
--! rollback
