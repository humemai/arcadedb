-- The PostgreSQL side of the write corpus (writes.tsv, fork issue #29), run once on top of small-schema.sql: what makes the tables of
-- the small fixture behave like the table view of ArcadeDB when they are written.
--  * id is the record id: it is never given a value of its own (SQLSTATE 428C9, as for a GENERATED ALWAYS column), and a new row gets
--    one from a sequence. A trigger does it, so that the plain text column of small-schema.sql keeps the type the view shows.
--  * a link is checked when it is written (SQLSTATE 23503). small-schema.sql declares its key NOT ENFORCED, because the engine
--    lets the record a link points at be deleted, so a trigger does this check.
-- A deleted vertex takes its edges with it, as in ArcadeDB: the edge keys of small-schema.sql are ON DELETE CASCADE.
-- The reads of the other corpus files are not affected: the file is applied after them.
CREATE SEQUENCE write_rid_seq;

CREATE FUNCTION write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.id IS NOT NULL THEN
      RAISE EXCEPTION 'cannot insert a non-DEFAULT value into column "id"' USING ERRCODE = '428C9';
    END IF;
    NEW.id := '#9:' || nextval('write_rid_seq');
  ELSIF NEW.id IS DISTINCT FROM OLD.id THEN
    RAISE EXCEPTION 'column "id" can only be updated to DEFAULT' USING ERRCODE = '428C9';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER company_write_guard BEFORE INSERT OR UPDATE ON company FOR EACH ROW EXECUTE FUNCTION write_guard();
CREATE TRIGGER person_write_guard BEFORE INSERT OR UPDATE ON person FOR EACH ROW EXECUTE FUNCTION write_guard();
CREATE TRIGGER orders_write_guard BEFORE INSERT OR UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION write_guard();
CREATE TRIGGER works_at_write_guard BEFORE INSERT OR UPDATE ON works_at FOR EACH ROW EXECUTE FUNCTION write_guard();

CREATE FUNCTION buyer_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.buyer IS NOT NULL AND NOT EXISTS (SELECT 1 FROM person WHERE id = NEW.buyer) THEN
    RAISE EXCEPTION 'orders.buyer names no person' USING ERRCODE = '23503';
  END IF;
  RETURN NULL;
END
$$;

CREATE TRIGGER orders_buyer_guard AFTER INSERT OR UPDATE OF buyer ON orders FOR EACH ROW EXECUTE FUNCTION buyer_guard();
