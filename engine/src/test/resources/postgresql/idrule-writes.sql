-- The PostgreSQL side of the write corpus of the id rule (idrule-writes.tsv, fork issues #38 and #240), run once on top of the
-- tables that SmallSchemaSql writes for the idrule fixture: what makes them behave like the table view of ArcadeDB when they are
-- written.
--  * the record id column rid (every table but badge and twin, whose type has a property rid) is never given a value of its own
--    (SQLSTATE 428C9, as for a GENERATED ALWAYS column), and a new row gets one from a sequence. A trigger does it, so that the
--    plain text column keeps the type the view shows. The columns id of account, post, memo and twin and the columns rid of badge
--    and twin are the user's data and have no guard; badge and twin have no record id column at all.
CREATE SEQUENCE write_rid_seq;

CREATE FUNCTION write_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  col text := TG_ARGV[0];
  new_value text := to_jsonb(NEW) ->> TG_ARGV[0];
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF new_value IS NOT NULL THEN
      RAISE EXCEPTION 'cannot insert a non-DEFAULT value into column "%"', col USING ERRCODE = '428C9';
    END IF;
    NEW := jsonb_populate_record(NEW, jsonb_build_object(col, '#9:' || nextval('write_rid_seq')));
  ELSIF new_value IS DISTINCT FROM (to_jsonb(OLD) ->> col) THEN
    RAISE EXCEPTION 'column "%" can only be updated to DEFAULT', col USING ERRCODE = '428C9';
  END IF;
  RETURN NEW;
END
$$;

CREATE TRIGGER account_write_guard BEFORE INSERT OR UPDATE ON account FOR EACH ROW EXECUTE FUNCTION write_guard('rid');
CREATE TRIGGER post_write_guard BEFORE INSERT OR UPDATE ON post FOR EACH ROW EXECUTE FUNCTION write_guard('rid');
CREATE TRIGGER memo_write_guard BEFORE INSERT OR UPDATE ON memo FOR EACH ROW EXECUTE FUNCTION write_guard('rid');
CREATE TRIGGER topic_write_guard BEFORE INSERT OR UPDATE ON topic FOR EACH ROW EXECUTE FUNCTION write_guard('rid');
CREATE TRIGGER follows_write_guard BEFORE INSERT OR UPDATE ON follows FOR EACH ROW EXECUTE FUNCTION write_guard('rid');
CREATE TRIGGER interested_write_guard BEFORE INSERT OR UPDATE ON interested FOR EACH ROW EXECUTE FUNCTION write_guard('rid');
