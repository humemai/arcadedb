-- The PostgreSQL side of the write corpus (writes.tsv, fork issue #29), run once on top of small-schema.sql: what makes the tables of
-- the small fixture behave like the table view of ArcadeDB when they are written.
--  * id is the record id: it is never given a value of its own (SQLSTATE 428C9, as for a GENERATED ALWAYS column), and a new row gets
--    one from a sequence. A trigger does it, so that the plain text column of small-schema.sql keeps the type the view shows.
--  * a deleted vertex takes its edges with it (ON DELETE CASCADE), as in ArcadeDB.
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

ALTER TABLE works_at DROP CONSTRAINT works_at_source_id_fkey;
ALTER TABLE works_at DROP CONSTRAINT works_at_target_id_fkey;
ALTER TABLE works_at ADD CONSTRAINT works_at_source_id_fkey FOREIGN KEY (source_id) REFERENCES person (id) ON DELETE CASCADE;
ALTER TABLE works_at ADD CONSTRAINT works_at_target_id_fkey FOREIGN KEY (target_id) REFERENCES company (id) ON DELETE CASCADE;
