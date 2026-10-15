CREATE TABLE protohub_file_cleanup (id varchar(64) PRIMARY KEY, resource_type varchar(16) NOT NULL,
 project_id varchar(64) NOT NULL, version_id varchar(128), storage_path varchar(1024), create_time bigint NOT NULL,
 next_attempt bigint NOT NULL, attempts integer NOT NULL);
CREATE INDEX idx_protohub_cleanup_due ON protohub_file_cleanup(next_attempt);
