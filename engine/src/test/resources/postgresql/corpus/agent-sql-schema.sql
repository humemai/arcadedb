-- Throwaway schema of the agent SQL corpus (agent-sql.tsv): the eight tables of the LDBC SNB slice that the experiment sql-evidence
-- (humemai/arcadedb-embedded-python at commit ce4ed5e9327f) described to the models in prompts/sql.txt. The statements are the DDL of
-- engine_runner.DUCK_DDL of the experiment, unchanged: they are plain PostgreSQL. The harness (PgParityIT) loads this into an empty
-- database of the real server so that the parse step can bind table and column names (it never runs a generated statement and
-- loads no rows). The table names are unquoted, so the server folds them to lower case, as it does for a generated statement.
CREATE TABLE Person(id BIGINT PRIMARY KEY, first_name VARCHAR, last_name VARCHAR, gender VARCHAR, birth_year INTEGER, creation_year INTEGER, city_id BIGINT);
CREATE TABLE City(id BIGINT PRIMARY KEY, name VARCHAR, country_id BIGINT);
CREATE TABLE Country(id BIGINT PRIMARY KEY, name VARCHAR);
CREATE TABLE Forum(id BIGINT PRIMARY KEY, title VARCHAR, moderator_id BIGINT);
CREATE TABLE Tag(id BIGINT PRIMARY KEY, name VARCHAR, tagclass_id BIGINT);
CREATE TABLE TagClass(id BIGINT PRIMARY KEY, name VARCHAR);
CREATE TABLE Knows(person1_id BIGINT, person2_id BIGINT, since_year INTEGER);
CREATE TABLE HasInterest(person_id BIGINT, tag_id BIGINT);
