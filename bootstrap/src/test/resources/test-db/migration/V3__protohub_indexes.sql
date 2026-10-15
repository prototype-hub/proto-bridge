-- Check duplicates and orphans in preflight; no destructive schema changes or cascades.
CREATE INDEX IF NOT EXISTS idx_protohub_version_project ON protohub_version(project_id);
CREATE INDEX IF NOT EXISTS idx_protohub_share_project ON protohub_share(project_id);
CREATE INDEX IF NOT EXISTS idx_protohub_share_version ON protohub_share(version_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_protohub_share_token ON protohub_share(token);
