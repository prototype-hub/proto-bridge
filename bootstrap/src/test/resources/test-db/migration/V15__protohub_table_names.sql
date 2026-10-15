-- Align ProtoHub tables with the current p_* entity mappings.
-- Renaming preserves data, table/index/constraint identities and existing foreign keys.
-- A pre-existing target is an error; PostgreSQL/Flyway rolls back all four renames.
ALTER TABLE protohub_project RENAME TO p_project;
ALTER TABLE protohub_version RENAME TO p_version;
ALTER TABLE protohub_share RENAME TO p_share;
ALTER TABLE protohub_file_cleanup RENAME TO p_file_cleanup;
