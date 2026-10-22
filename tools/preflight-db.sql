-- Read-only: run against a restored copy; select the actual migration stage explicitly.
\ir prototype-hub-table-names.sql
SELECT current_database(), version();
SELECT table_name, column_name, data_type, character_maximum_length, is_nullable
 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name IN (:'prototype_hub_project_table',:'prototype_hub_version_table',:'prototype_hub_revision_table',:'prototype_hub_share_table',:'prototype_hub_cleanup_table',:'prototype_hub_member_table',:'prototype_hub_preview_session_table')
 ORDER BY table_name,ordinal_position;
SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname=current_schema()
 AND tablename IN (:'prototype_hub_project_table',:'prototype_hub_version_table',:'prototype_hub_revision_table',:'prototype_hub_share_table',:'prototype_hub_cleanup_table',:'prototype_hub_member_table',:'prototype_hub_preview_session_table');
SELECT :'prototype_hub_project_table' AS name,count(*) AS rows FROM :"prototype_hub_project_table" UNION ALL
SELECT :'prototype_hub_version_table',count(*) FROM :"prototype_hub_version_table" UNION ALL
SELECT :'prototype_hub_share_table',count(*) FROM :"prototype_hub_share_table";
SELECT v.id AS orphan_version FROM :"prototype_hub_version_table" v
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=v.project_id WHERE p.id IS NULL;
SELECT s.id AS orphan_share FROM :"prototype_hub_share_table" s
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=s.version_id
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=s.project_id
 WHERE v.id IS NULL OR p.id IS NULL OR v.project_id<>s.project_id;
SELECT token,count(*) FROM :"prototype_hub_share_table" GROUP BY token HAVING count(*)>1;
SELECT status,count(*) FROM :"prototype_hub_version_table" GROUP BY status;

-- Optional current authorization structure: old pre-member schemas remain inspectable.
SELECT count(*)=2 AS prototype_hub_has_ownership FROM information_schema.columns
 WHERE table_schema=current_schema() AND table_name=:'prototype_hub_project_table'
 AND column_name IN ('owner_id','access_epoch') \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_member_table')) IS NOT NULL AS prototype_hub_has_members,
       to_regclass(format('%I.%I',current_schema(),:'prototype_hub_preview_session_table')) IS NOT NULL AS prototype_hub_has_preview_sessions,
       to_regclass(format('%I.%I',current_schema(),'s_user')) IS NOT NULL AS prototype_hub_has_users \gset
\if :prototype_hub_has_ownership
SELECT count(*) AS unassigned_projects FROM :"prototype_hub_project_table" WHERE owner_id IS NULL OR owner_id='';
SELECT count(*) AS missing_authorization_epoch FROM :"prototype_hub_project_table" WHERE access_epoch IS NULL OR access_epoch='';
\if :prototype_hub_has_users
SELECT p.id AS pending_project FROM :"prototype_hub_project_table" p
 LEFT JOIN s_user u ON u.id=p.owner_id AND u.status=1 WHERE u.id IS NULL ORDER BY p.id;
\endif
\endif
-- Revision migration is explicit. Missing pointers/bindings are pending migration,
-- while a non-null pointer to another logical version is an integrity failure.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_revision_table')) IS NOT NULL AS prototype_hub_has_revisions \gset
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_version_table' AND column_name='current_revision_id') AS prototype_hub_has_current_revision \gset
\if :prototype_hub_has_revisions
SELECT 'revisions',count(*) FROM :"prototype_hub_revision_table";
SELECT r.id AS orphan_revision FROM :"prototype_hub_revision_table" r
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=r.version_id
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=r.project_id
 WHERE v.id IS NULL OR p.id IS NULL OR v.project_id<>r.project_id;
\if :prototype_hub_has_current_revision
SELECT count(*) AS versions_pending_revision_migration FROM :"prototype_hub_version_table"
 WHERE current_revision_id IS NULL OR current_revision_id='';
SELECT v.id AS invalid_current_revision FROM :"prototype_hub_version_table" v
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=v.current_revision_id
 WHERE nullif(v.current_revision_id,'') IS NOT NULL
 AND (r.id IS NULL OR r.version_id<>v.id OR r.project_id<>v.project_id
 OR r.storage_path IS DISTINCT FROM v.storage_path OR r.entry_file IS DISTINCT FROM v.entry_file);
\endif
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_share_table' AND column_name='revision_id') AS prototype_hub_has_share_revision \gset
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_share_table' AND column_name='revision_mode') AS prototype_hub_has_share_mode \gset
\if :prototype_hub_has_share_revision
\if :prototype_hub_has_share_mode
-- A latest share intentionally has no persisted snapshot; only fixed shares need binding.
SELECT count(*) AS shares_pending_revision_binding FROM :"prototype_hub_share_table"
 WHERE coalesce(revision_mode,'fixed')='fixed' AND nullif(revision_id,'') IS NULL;
SELECT count(*) AS shares_following_latest FROM :"prototype_hub_share_table" WHERE revision_mode='latest';
SELECT id AS invalid_share_mode FROM :"prototype_hub_share_table"
 WHERE coalesce(revision_mode,'fixed') NOT IN ('fixed','latest')
 OR (revision_mode='latest' AND nullif(revision_id,'') IS NOT NULL);
\else
SELECT count(*) AS shares_pending_revision_binding FROM :"prototype_hub_share_table" WHERE nullif(revision_id,'') IS NULL;
\endif
SELECT s.id AS invalid_share_revision FROM :"prototype_hub_share_table" s
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=s.revision_id
 WHERE nullif(s.revision_id,'') IS NOT NULL
 AND (r.id IS NULL OR r.version_id<>s.version_id OR r.project_id<>s.project_id);
\endif
\endif
\if :prototype_hub_has_members
SELECT count(*) AS project_members FROM :"prototype_hub_member_table";
SELECT m.id AS orphan_member FROM :"prototype_hub_member_table" m
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=m.project_id WHERE p.id IS NULL;
SELECT project_id,user_id,count(*) FROM :"prototype_hub_member_table" GROUP BY project_id,user_id HAVING count(*)>1;
SELECT id AS invalid_member_role FROM :"prototype_hub_member_table" WHERE role NOT IN ('editor','viewer');
\endif
\if :prototype_hub_has_preview_sessions
SELECT count(*) AS short_preview_sessions FROM :"prototype_hub_preview_session_table";
SELECT s.id AS orphan_preview_session FROM :"prototype_hub_preview_session_table" s
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=s.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=s.version_id
 WHERE p.id IS NULL OR v.id IS NULL OR v.project_id<>s.project_id;
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_preview_session_table' AND column_name='revision_id') AS prototype_hub_has_session_revision \gset
\if :prototype_hub_has_revisions
\if :prototype_hub_has_session_revision
SELECT count(*) AS denied_legacy_preview_sessions FROM :"prototype_hub_preview_session_table" WHERE nullif(revision_id,'') IS NULL;
SELECT s.id AS invalid_session_revision FROM :"prototype_hub_preview_session_table" s
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=s.revision_id
 WHERE nullif(s.revision_id,'') IS NOT NULL
 AND (r.id IS NULL OR r.version_id<>s.version_id OR r.project_id<>s.project_id
 OR r.storage_path IS DISTINCT FROM s.storage_path);
\endif
\endif
\endif
