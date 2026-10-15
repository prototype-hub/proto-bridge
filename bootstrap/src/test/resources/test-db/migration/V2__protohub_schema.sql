-- New-install baseline from frozen entity contracts; NOT a reverse export of production DDL.
-- Existing databases must pass scripts/preflight-db.sql before explicit Flyway baseline adoption.
CREATE TABLE protohub_project (
 id varchar(64) PRIMARY KEY, name varchar(255) NOT NULL, description varchar(2000), cover varchar(1024),
 creator_id varchar(64) NOT NULL, create_time bigint NOT NULL, modifier_id varchar(64) NOT NULL, modify_time bigint NOT NULL);
CREATE TABLE protohub_version (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, version_name varchar(128) NOT NULL,
 description varchar(2000), storage_path varchar(1024) NOT NULL, entry_file varchar(1024) NOT NULL,
 preview_key varchar(64) NOT NULL, status varchar(255) NOT NULL, creator_id varchar(64) NOT NULL, create_time bigint NOT NULL);
CREATE TABLE protohub_share (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, version_id varchar(64) NOT NULL,
 token varchar(128) NOT NULL, access_key varchar(128) NOT NULL, password_hash varchar(255), expires_at bigint,
 enabled boolean NOT NULL, creator_id varchar(64) NOT NULL, create_time bigint NOT NULL);
