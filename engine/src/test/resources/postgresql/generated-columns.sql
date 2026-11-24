-- The PostgreSQL side of the generated-columns corpus (generated.tsv, fork issue #78): the tables of GeneratedColumnsFixture with the
-- columns that the engine generates (the record id of a type: rid, or id when the type has no property id of its own, and the id of an
-- edge) declared as PostgreSQL declares a column that its system fills in, GENERATED ALWAYS AS IDENTITY. PostgreSQL allows that for
-- smallint, integer and bigint only, so the type of those columns differs from the engine's (text); the corpus statements read the
-- flags (is_nullable, column_default, is_identity, identity_generation, is_generated) and never the type. Column order as the
-- relational view of the engine has it: the record id first, then the properties by name, an edge with source_id and target_id after
-- its id.
CREATE TABLE gen_note (
  rid bigint GENERATED ALWAYS AS IDENTITY,
  body character varying,
  id bigint NOT NULL,
  CONSTRAINT gen_note_pkey PRIMARY KEY (id)
);

CREATE TABLE gen_tag (
  id bigint GENERATED ALWAYS AS IDENTITY,
  label character varying,
  CONSTRAINT gen_tag_pkey PRIMARY KEY (id)
);

CREATE TABLE gen_link (
  id bigint GENERATED ALWAYS AS IDENTITY,
  source_id bigint NOT NULL,
  target_id bigint NOT NULL,
  since integer,
  CONSTRAINT gen_link_pkey PRIMARY KEY (id)
);
