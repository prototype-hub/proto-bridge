-- Read-only: run against a restored copy; select the actual migration stage explicitly.
\ir prototype-hub-table-names.sql
SELECT current_database(), version();
SELECT table_name, column_name, data_type, character_maximum_length, is_nullable
 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name IN (:'prototype_hub_project_table',:'prototype_hub_version_table',:'prototype_hub_share_table',:'prototype_hub_cleanup_table',:'prototype_hub_member_table',:'prototype_hub_preview_session_table')
 ORDER BY table_name,ordinal_position;
SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname=current_schema()
 AND tablename IN (:'prototype_hub_project_table',:'prototype_hub_version_table',:'prototype_hub_share_table',:'prototype_hub_cleanup_table',:'prototype_hub_member_table',:'prototype_hub_preview_session_table');
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
\endif
