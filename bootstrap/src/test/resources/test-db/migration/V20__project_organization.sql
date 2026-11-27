ALTER TABLE p_project ADD COLUMN group_name varchar(64), ADD COLUMN archived_time bigint, ADD COLUMN organization_version bigint;
CREATE TABLE p_project_tag (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, tag_name varchar(32) NOT NULL
);
CREATE UNIQUE INDEX idx_project_tag_binding ON p_project_tag(project_id,tag_name);
CREATE INDEX idx_project_tag_name ON p_project_tag(tag_name,project_id);
CREATE TABLE p_project_favorite (
 id varchar(64) PRIMARY KEY, project_id varchar(64) NOT NULL, user_id varchar(64) NOT NULL,
 favorite boolean NOT NULL, config_version bigint NOT NULL, created_at bigint NOT NULL, updated_at bigint NOT NULL
);
CREATE UNIQUE INDEX idx_project_favorite_binding ON p_project_favorite(user_id,project_id);
CREATE INDEX idx_project_favorite_filter ON p_project_favorite(user_id,favorite,project_id);
CREATE INDEX idx_project_favorite_project ON p_project_favorite(project_id);
CREATE INDEX idx_project_group ON p_project(group_name,id);
CREATE INDEX idx_project_archive ON p_project(archived_time,id);
