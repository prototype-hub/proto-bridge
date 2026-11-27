-- Read-only: run against a restored copy; select the actual migration stage explicitly.
\ir prototype-hub-table-names.sql
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_code_mapping_table')) IS NOT NULL AS prototype_hub_has_code_mappings \gset
-- Page tracing is optional for historical schemas, but partial graphs fail closed.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_page_relation_table')) IS NOT NULL AS prototype_hub_has_page_relations,
 to_regclass(format('%I.%I',current_schema(),:'prototype_hub_page_trace_context_table')) IS NOT NULL AS prototype_hub_has_page_trace_contexts,
 to_regclass(format('%I.%I',current_schema(),:'prototype_hub_page_trace_decision_table')) IS NOT NULL AS prototype_hub_has_page_trace_decisions \gset
SELECT (:'prototype_hub_has_page_relations'::boolean AND :'prototype_hub_has_page_trace_contexts'::boolean
 AND :'prototype_hub_has_page_trace_decisions'::boolean) AS prototype_hub_has_page_traceability,
 (:'prototype_hub_has_page_relations'::boolean OR :'prototype_hub_has_page_trace_contexts'::boolean
 OR :'prototype_hub_has_page_trace_decisions'::boolean) AS prototype_hub_has_page_traceability_parts \gset
-- Organization data adds no storage files. Check its complete optional graph before claiming coverage.
SELECT count(*)=3 AS prototype_hub_has_organization_columns,
 count(*)>0 AS prototype_hub_has_any_organization_columns FROM information_schema.columns
 WHERE table_schema=current_schema() AND table_name=:'prototype_hub_project_table'
 AND column_name IN ('group_name','archived_time','organization_version') \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_project_tag_table')) IS NOT NULL AS prototype_hub_has_project_tags,
 to_regclass(format('%I.%I',current_schema(),:'prototype_hub_project_favorite_table')) IS NOT NULL AS prototype_hub_has_project_favorites,
 to_regclass(format('%I.%I',current_schema(),'s_user')) IS NOT NULL AS prototype_hub_has_organization_users \gset
SELECT (:'prototype_hub_has_organization_columns'::boolean AND :'prototype_hub_has_project_tags'::boolean
 AND :'prototype_hub_has_project_favorites'::boolean AND :'prototype_hub_has_organization_users'::boolean)
 AS prototype_hub_has_organization,
 (:'prototype_hub_has_any_organization_columns'::boolean OR :'prototype_hub_has_project_tags'::boolean
 OR :'prototype_hub_has_project_favorites'::boolean) AS prototype_hub_has_organization_parts \gset
SELECT current_database(), version();
SELECT table_name, column_name, data_type, character_maximum_length, is_nullable
 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name IN (:'prototype_hub_installation_table',:'prototype_hub_project_table',:'prototype_hub_project_tag_table',:'prototype_hub_project_favorite_table',:'prototype_hub_member_table',:'prototype_hub_preview_session_table',:'prototype_hub_version_table',:'prototype_hub_revision_table',:'prototype_hub_revision_approval_table',:'prototype_hub_work_item_table',:'prototype_hub_element_description_table',:'prototype_hub_viewport_check_table',:'prototype_hub_demo_script_table',:'prototype_hub_page_relation_table',:'prototype_hub_page_trace_context_table',:'prototype_hub_page_trace_decision_table',:'prototype_hub_code_mapping_table',:'prototype_hub_share_table',:'prototype_hub_review_guest_table',:'prototype_hub_review_unverified_guest_table',:'prototype_hub_review_challenge_table',:'prototype_hub_review_session_table',:'prototype_hub_review_rate_table',:'prototype_hub_review_invitation_table',:'prototype_hub_review_thread_table',:'prototype_hub_review_message_table',:'prototype_hub_review_event_table',:'prototype_hub_review_association_table',:'prototype_hub_copy_snapshot_table',:'prototype_hub_copy_entry_table',:'prototype_hub_share_visit_table',:'prototype_hub_receipt_table',:'prototype_hub_cleanup_table')
 ORDER BY table_name,ordinal_position;
SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname=current_schema()
 AND tablename IN (:'prototype_hub_installation_table',:'prototype_hub_project_table',:'prototype_hub_project_tag_table',:'prototype_hub_project_favorite_table',:'prototype_hub_member_table',:'prototype_hub_preview_session_table',:'prototype_hub_version_table',:'prototype_hub_revision_table',:'prototype_hub_revision_approval_table',:'prototype_hub_work_item_table',:'prototype_hub_element_description_table',:'prototype_hub_viewport_check_table',:'prototype_hub_demo_script_table',:'prototype_hub_page_relation_table',:'prototype_hub_page_trace_context_table',:'prototype_hub_page_trace_decision_table',:'prototype_hub_code_mapping_table',:'prototype_hub_share_table',:'prototype_hub_review_guest_table',:'prototype_hub_review_unverified_guest_table',:'prototype_hub_review_challenge_table',:'prototype_hub_review_session_table',:'prototype_hub_review_rate_table',:'prototype_hub_review_invitation_table',:'prototype_hub_review_thread_table',:'prototype_hub_review_message_table',:'prototype_hub_review_event_table',:'prototype_hub_review_association_table',:'prototype_hub_copy_snapshot_table',:'prototype_hub_copy_entry_table',:'prototype_hub_share_visit_table',:'prototype_hub_receipt_table',:'prototype_hub_cleanup_table');
SELECT :'prototype_hub_project_table' AS name,count(*) AS rows FROM :"prototype_hub_project_table" UNION ALL
SELECT :'prototype_hub_version_table',count(*) FROM :"prototype_hub_version_table" UNION ALL
SELECT :'prototype_hub_share_table',count(*) FROM :"prototype_hub_share_table";
SELECT v.id AS orphan_version FROM :"prototype_hub_version_table" v
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=v.project_id WHERE p.id IS NULL;
SELECT s.id AS orphan_share FROM :"prototype_hub_share_table" s
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=s.version_id
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=s.project_id
 WHERE v.id IS NULL OR p.id IS NULL OR v.project_id<>s.project_id;
SELECT md5(token) AS duplicate_share_token_digest,count(*) FROM :"prototype_hub_share_table" GROUP BY token HAVING count(*)>1;
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

-- Null deletion time is active, including legacy rows. Retained tombstones are
-- part of the same physical graph and must not be excluded from integrity checks.
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_project_table' AND column_name='deleted_time') AS prototype_hub_has_project_deletion,
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_version_table' AND column_name='deleted_time') AS prototype_hub_has_version_deletion \gset
\if :prototype_hub_has_project_deletion
SELECT count(*) FILTER (WHERE deleted_time IS NULL) AS active_projects,
 count(*) FILTER (WHERE deleted_time IS NOT NULL) AS soft_deleted_projects FROM :"prototype_hub_project_table";
\endif
\if :prototype_hub_has_version_deletion
SELECT count(*) FILTER (WHERE deleted_time IS NULL) AS active_versions,
 count(*) FILTER (WHERE deleted_time IS NOT NULL) AS soft_deleted_versions FROM :"prototype_hub_version_table";
\endif
SELECT count(*)=2 AS prototype_hub_has_receipt_revision FROM information_schema.columns
 WHERE table_schema=current_schema() AND table_name=:'prototype_hub_receipt_table'
 AND column_name IN ('resource_id','revision_id') \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_review_association_table')) IS NOT NULL AS prototype_hub_has_review_association \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_share_visit_table')) IS NOT NULL AS prototype_hub_has_share_visit \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_copy_snapshot_table')) IS NOT NULL AS prototype_hub_has_copy_snapshot \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_copy_entry_table')) IS NOT NULL AS prototype_hub_has_copy_entry \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_review_thread_table')) IS NOT NULL
 AND to_regclass(format('%I.%I',current_schema(),:'prototype_hub_review_message_table')) IS NOT NULL
 AS prototype_hub_has_review_threads \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_demo_script_table')) IS NOT NULL AS prototype_hub_has_demo_scripts \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_viewport_check_table')) IS NOT NULL AS prototype_hub_has_viewport_checks \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_element_description_table')) IS NOT NULL AS prototype_hub_has_element_descriptions \gset
\if :prototype_hub_has_revisions
WITH current_scope_errors AS (
 SELECT null::varchar AS id WHERE false
\if :prototype_hub_has_receipt_revision
 UNION ALL
 SELECT c.id FROM :"prototype_hub_receipt_table" c
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=c.revision_id
 WHERE nullif(c.revision_id,'') IS NOT NULL AND (r.id IS NULL OR c.resource_id IS NULL OR NOT (
  r.version_id=c.resource_id
\if :prototype_hub_has_review_threads
  OR EXISTS(SELECT 1 FROM :"prototype_hub_review_thread_table" t
   WHERE t.id=c.resource_id AND t.revision_id=r.id AND t.version_id=r.version_id AND t.project_id=r.project_id)
  OR EXISTS(SELECT 1 FROM :"prototype_hub_review_message_table" m
   JOIN :"prototype_hub_review_thread_table" t ON t.id=m.thread_id
   WHERE m.id=c.resource_id AND m.revision_id=r.id AND m.version_id=r.version_id AND m.project_id=r.project_id
    AND t.revision_id=m.revision_id AND t.version_id=m.version_id AND t.project_id=m.project_id)
\endif
\if :prototype_hub_has_review_association
  OR EXISTS(SELECT 1 FROM :"prototype_hub_review_association_table" a
   WHERE a.id=c.resource_id AND a.revision_id=r.id AND a.version_id=r.version_id AND a.project_id=r.project_id)
\endif
 ))
\endif
\if :prototype_hub_has_review_association
 UNION ALL
 SELECT a.id FROM :"prototype_hub_review_association_table" a
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=a.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=a.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=a.revision_id
 LEFT JOIN :"prototype_hub_share_table" s ON s.id=a.share_id
 LEFT JOIN :"prototype_hub_review_thread_table" t ON t.id=a.source_thread_id
 LEFT JOIN :"prototype_hub_review_guest_table" g ON g.id=a.target_guest_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL OR s.id IS NULL OR t.id IS NULL OR g.id IS NULL
  OR g.project_id IS DISTINCT FROM a.project_id
  OR v.project_id IS DISTINCT FROM a.project_id OR r.project_id IS DISTINCT FROM a.project_id
  OR r.version_id IS DISTINCT FROM a.version_id OR s.project_id IS DISTINCT FROM a.project_id
  OR s.version_id IS DISTINCT FROM a.version_id OR t.project_id IS DISTINCT FROM a.project_id
  OR t.version_id IS DISTINCT FROM a.version_id OR t.revision_id IS DISTINCT FROM a.revision_id
  OR t.share_id IS DISTINCT FROM a.share_id OR t.guest_id IS DISTINCT FROM a.source_guest_id
  OR a.source_verification_level IS DISTINCT FROM 'unverified'
  OR a.target_verification_level IS DISTINCT FROM 'email'
\endif
\if :prototype_hub_has_share_visit
 UNION ALL
 SELECT a.id FROM :"prototype_hub_share_visit_table" a
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=a.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=a.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=a.revision_id
 LEFT JOIN :"prototype_hub_share_table" s ON s.id=a.share_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL OR s.id IS NULL
  OR v.project_id IS DISTINCT FROM a.project_id OR r.project_id IS DISTINCT FROM a.project_id
  OR r.version_id IS DISTINCT FROM a.version_id OR s.project_id IS DISTINCT FROM a.project_id
  OR s.version_id IS DISTINCT FROM a.version_id
\endif
\if :prototype_hub_has_copy_snapshot
 UNION ALL
 SELECT a.id FROM :"prototype_hub_copy_snapshot_table" a
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=a.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=a.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=a.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM a.project_id OR r.project_id IS DISTINCT FROM a.project_id
  OR r.version_id IS DISTINCT FROM a.version_id OR a.manifest_sha256 IS DISTINCT FROM r.manifest_sha256
  OR a.page_id IS DISTINCT FROM encode(sha256(convert_to(a.page_path,'UTF8')),'hex')
  OR (SELECT count(*) FROM jsonb_array_elements(r.manifest_json::jsonb->'files') f
   WHERE f->>'path'=a.page_path AND f->>'sha256'=a.file_sha256)<>1
  OR NOT jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.path == $path)',
   jsonb_build_object('page',a.page_id,'path',a.page_path))
\endif
\if :prototype_hub_has_copy_entry
 UNION ALL
 SELECT a.id FROM :"prototype_hub_copy_entry_table" a
 LEFT JOIN :"prototype_hub_copy_snapshot_table" s ON s.id=a.snapshot_id
 WHERE s.id IS NULL OR s.project_id IS DISTINCT FROM a.project_id
  OR s.version_id IS DISTINCT FROM a.version_id OR s.revision_id IS DISTINCT FROM a.revision_id
  OR s.page_id IS DISTINCT FROM a.page_id
\endif
\if :prototype_hub_has_element_descriptions
 UNION ALL
 SELECT d.id FROM :"prototype_hub_element_description_table" d
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=d.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=d.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=d.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM d.project_id OR r.project_id IS DISTINCT FROM d.project_id
  OR r.version_id IS DISTINCT FROM d.version_id
  OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.folder == false)',
   jsonb_build_object('page',d.page_id)),false)
\endif
 UNION ALL
 SELECT p.id FROM :"prototype_hub_project_table" p
 WHERE :'prototype_hub_has_organization_parts'::boolean AND NOT :'prototype_hub_has_organization'::boolean
\if :prototype_hub_has_organization_columns
 UNION ALL
 SELECT p.id FROM :"prototype_hub_project_table" p
 WHERE (p.group_name IS NOT NULL AND (p.group_name='' OR length(p.group_name)>64
  OR p.group_name IS DISTINCT FROM btrim(p.group_name,E' \t\n\r\v\f' || U&'\001C\001D\001E\001F\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000\FEFF')))
  OR (p.archived_time IS NOT NULL AND p.archived_time<0)
  OR (p.organization_version IS NOT NULL AND p.organization_version NOT BETWEEN 0 AND 9007199254740991)
\endif
\if :prototype_hub_has_project_tags
 UNION ALL
 SELECT t.id FROM :"prototype_hub_project_tag_table" t
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=t.project_id
 WHERE p.id IS NULL OR t.tag_name IS NULL OR t.tag_name='' OR length(t.tag_name)>32
  OR t.tag_name IS DISTINCT FROM btrim(t.tag_name,E' \t\n\r\v\f' || U&'\001C\001D\001E\001F\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000\FEFF')
  OR (SELECT count(*) FROM :"prototype_hub_project_tag_table" other WHERE other.project_id=t.project_id)>10
  OR EXISTS(SELECT 1 FROM :"prototype_hub_project_tag_table" other
   WHERE other.id<>t.id AND other.project_id=t.project_id AND other.tag_name=t.tag_name)
\endif
\if :prototype_hub_has_project_favorites
 UNION ALL
 SELECT f.id FROM :"prototype_hub_project_favorite_table" f
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=f.project_id
\if :prototype_hub_has_organization_users
 LEFT JOIN s_user u ON u.id=f.user_id
\endif
 WHERE p.id IS NULL OR nullif(f.user_id,'') IS NULL OR f.favorite IS NULL
\if :prototype_hub_has_organization_users
  OR u.id IS NULL
\endif
  OR f.config_version IS NULL OR f.config_version NOT BETWEEN 1 AND 9007199254740991
  OR f.created_at IS NULL OR f.created_at<0 OR f.updated_at IS NULL OR f.updated_at<f.created_at
  OR EXISTS(SELECT 1 FROM :"prototype_hub_project_favorite_table" other
   WHERE other.id<>f.id AND other.project_id=f.project_id AND other.user_id=f.user_id)
\endif
 UNION ALL
 SELECT null::varchar WHERE :'prototype_hub_has_page_traceability_parts'::boolean
  AND NOT :'prototype_hub_has_page_traceability'::boolean
\if :prototype_hub_has_page_relations
 UNION ALL
 SELECT t.id FROM :"prototype_hub_page_relation_table" t
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=t.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=t.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=t.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM t.project_id OR r.project_id IS DISTINCT FROM t.project_id
  OR r.version_id IS DISTINCT FROM t.version_id
  OR t.config_version IS NULL OR t.config_version NOT BETWEEN 0 AND 9007199254740991
  OR t.created_at IS NULL OR t.created_at<0 OR t.updated_at IS NULL OR t.updated_at<t.created_at
  OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.folder == false)',jsonb_build_object('page',t.from_page_id)),false)
  OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.folder == false)',jsonb_build_object('page',t.to_page_id)),false)
  OR t.from_page_id=t.to_page_id OR t.label IS NULL OR btrim(t.label)='' OR length(t.label)>120
  OR t.note IS NULL OR length(t.note)>2000
\endif
\if :prototype_hub_has_page_trace_contexts
 UNION ALL
 SELECT t.id FROM :"prototype_hub_page_trace_context_table" t
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=t.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=t.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=t.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM t.project_id OR r.project_id IS DISTINCT FROM t.project_id
  OR r.version_id IS DISTINCT FROM t.version_id
  OR t.config_version IS NULL OR t.config_version NOT BETWEEN 0 AND 9007199254740991
  OR t.created_at IS NULL OR t.created_at<0 OR t.updated_at IS NULL OR t.updated_at<t.created_at
  OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.folder == false)',jsonb_build_object('page',t.page_id)),false)
  OR t.entry IS NULL OR jsonb_typeof(t.source_refs::jsonb) IS DISTINCT FROM 'array'
  OR CASE WHEN jsonb_typeof(t.source_refs::jsonb)='array'
   THEN jsonb_array_length(t.source_refs::jsonb)>40 ELSE true END
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(t.source_refs::jsonb)='array'
   THEN t.source_refs::jsonb ELSE '[]'::jsonb END) ref
   WHERE jsonb_typeof(ref) IS DISTINCT FROM 'object'
    OR jsonb_typeof(ref->'kind') IS DISTINCT FROM 'string'
    OR ref->>'kind' NOT IN ('work-item','material','description','check')
    OR jsonb_typeof(ref->'id') IS DISTINCT FROM 'string' OR length(ref->>'id') NOT BETWEEN 1 AND 128
    OR jsonb_typeof(ref->'expectedVersion') IS DISTINCT FROM 'number'
    OR NOT coalesce((ref->>'expectedVersion') ~ '^[0-9]+$',false)
    OR CASE WHEN (ref->>'expectedVersion') ~ '^[0-9]+$'
     THEN (ref->>'expectedVersion')::numeric>9007199254740991 ELSE true END
    OR (SELECT count(*) FROM jsonb_object_keys(CASE WHEN jsonb_typeof(ref)='object'
     THEN ref ELSE '{}'::jsonb END))<>3)
  OR (SELECT count(*)<>count(DISTINCT (ref->>'kind',ref->>'id'))
   FROM jsonb_array_elements(CASE WHEN jsonb_typeof(t.source_refs::jsonb)='array'
    THEN t.source_refs::jsonb ELSE '[]'::jsonb END) ref)
  OR EXISTS(SELECT 1 FROM :"prototype_hub_page_trace_context_table" other
   WHERE other.id<>t.id AND other.revision_id=t.revision_id AND other.page_id=t.page_id)
 -- Source deletion/updates are valid historical pointer states, not graph corruption.
\endif
\if :prototype_hub_has_page_trace_decisions
 UNION ALL
 SELECT t.id FROM :"prototype_hub_page_trace_decision_table" t
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=t.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=t.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=t.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM t.project_id OR r.project_id IS DISTINCT FROM t.project_id
  OR r.version_id IS DISTINCT FROM t.version_id
  OR t.config_version IS NULL OR t.config_version NOT BETWEEN 0 AND 9007199254740991
  OR t.created_at IS NULL OR t.created_at<0 OR t.updated_at IS NULL OR t.updated_at<t.created_at
  OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.folder == false)',jsonb_build_object('page',t.page_id)),false)
  OR t.title IS NULL OR btrim(t.title)='' OR length(t.title)>120
  OR t.decision IS NULL OR btrim(t.decision)='' OR length(t.decision)>2000
  OR t.rationale IS NULL OR btrim(t.rationale)='' OR length(t.rationale)>2000
  OR nullif(t.author_id,'') IS NULL OR nullif(t.updated_by,'') IS NULL
 -- Historical actor IDs survive account removal; they never authorize later access.
\endif
\if :prototype_hub_has_code_mappings
 UNION ALL
 SELECT t.id FROM :"prototype_hub_code_mapping_table" t
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=t.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=t.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=t.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM t.project_id OR r.project_id IS DISTINCT FROM t.project_id
  OR r.version_id IS DISTINCT FROM t.version_id
  OR t.config_version IS NULL OR t.config_version NOT BETWEEN 0 AND 9007199254740991
  OR t.created_at IS NULL OR t.created_at<0 OR t.updated_at IS NULL OR t.updated_at<t.created_at
  OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.folder == false)',jsonb_build_object('page',t.page_id)),false)
  OR t.alias IS NULL OR btrim(t.alias)='' OR length(t.alias)>120
  OR t.repository_path IS NULL OR btrim(t.repository_path)='' OR length(t.repository_path)>2048
  OR t.snippet IS NULL OR length(t.snippet)>16000
  OR t.framework IS NULL OR length(t.framework)>120
  OR t.framework_version IS NULL OR length(t.framework_version)>120
  OR t.repository_url IS NULL OR length(t.repository_url)>2048
  OR t.documentation_url IS NULL OR length(t.documentation_url)>2048
  OR (t.repository_url<>'' AND (t.repository_url !~* '^https?://[^/?#@[:space:]]+([/?#]|$)'
   OR t.repository_url ~ '[[:space:][:cntrl:]]' OR position(chr(92) in t.repository_url)>0))
  OR (t.documentation_url<>'' AND (t.documentation_url !~* '^https?://[^/?#@[:space:]]+([/?#]|$)'
   OR t.documentation_url ~ '[[:space:][:cntrl:]]' OR position(chr(92) in t.documentation_url)>0))
  OR (t.description_id IS NULL) IS DISTINCT FROM (t.description_version IS NULL)
  OR (t.description_id IS NOT NULL AND (btrim(t.description_id)='' OR length(t.description_id)>128
   OR t.description_version NOT BETWEEN 0 AND 9007199254740991))
 -- Changed/missing F27 pointers are valid historical references, never a reason to delete a card.
\endif
\if :prototype_hub_has_demo_scripts
 UNION ALL
 SELECT d.id FROM :"prototype_hub_demo_script_table" d
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=d.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=d.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=d.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM d.project_id OR r.project_id IS DISTINCT FROM d.project_id
  OR r.version_id IS DISTINCT FROM d.version_id
  OR d.share_enabled IS NULL OR d.config_version IS NULL
  OR d.config_version NOT BETWEEN 0 AND 9007199254740991
  OR jsonb_typeof(d.steps::jsonb) IS DISTINCT FROM 'array'
  OR CASE WHEN jsonb_typeof(d.steps::jsonb)='array'
   THEN jsonb_array_length(d.steps::jsonb) NOT BETWEEN 1 AND 50 ELSE true END
  OR EXISTS(SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.steps::jsonb)='array'
   THEN d.steps::jsonb ELSE '[]'::jsonb END) step
   WHERE jsonb_typeof(step) IS DISTINCT FROM 'object'
    OR jsonb_typeof(step->'id') IS DISTINCT FROM 'string'
    OR NOT coalesce(step->>'id' ~ '^[A-Za-z0-9_-]{1,64}$',false)
    OR jsonb_typeof(step->'pageId') IS DISTINCT FROM 'string'
    OR length(step->>'pageId') NOT BETWEEN 1 AND 128
    OR jsonb_typeof(step->'narration') IS DISTINCT FROM 'string'
    OR length(step->>'narration')>2000
    OR (SELECT count(*) FROM jsonb_object_keys(CASE WHEN jsonb_typeof(step)='object'
     THEN step ELSE '{}'::jsonb END))<>3
    OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
     '$.pages.** ? (@.pageId == $page && @.folder == false)',
     jsonb_build_object('page',step->>'pageId')),false))
  OR (SELECT count(*)<>count(DISTINCT step->>'id')
   FROM jsonb_array_elements(CASE WHEN jsonb_typeof(d.steps::jsonb)='array'
    THEN d.steps::jsonb ELSE '[]'::jsonb END) step)
 -- An openable parent is a valid page; narration and all other script text stay private.
\endif
\if :prototype_hub_has_viewport_checks
 UNION ALL
 SELECT w.id FROM :"prototype_hub_viewport_check_table" w
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=w.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=w.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=w.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM w.project_id OR r.project_id IS DISTINCT FROM w.project_id
  OR r.version_id IS DISTINCT FROM w.version_id
  OR NOT jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.folder == false)',
   jsonb_build_object('page',w.page_id))
\endif
)
SELECT 'current_scope_errors',count(*) FROM current_scope_errors;
\endif

-- Approval history shares the fixed revision lifecycle; removed actors remain valid historical snapshots.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_revision_approval_table')) IS NOT NULL AS prototype_hub_has_revision_approvals \gset
\if :prototype_hub_has_revision_approvals
SELECT a.id AS invalid_revision_approval FROM :"prototype_hub_revision_approval_table" a
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=a.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=a.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=a.revision_id
 LEFT JOIN :"prototype_hub_revision_approval_table" ap ON ap.id=a.approval_id
 LEFT JOIN :"prototype_hub_revision_approval_table" pub ON pub.id=a.publication_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
 OR v.project_id IS DISTINCT FROM a.project_id OR r.project_id IS DISTINCT FROM a.project_id
 OR r.version_id IS DISTINCT FROM a.version_id
 OR a.config_version IS NULL OR a.config_version NOT BETWEEN 1 AND 9007199254740991
 OR a.action IS NULL OR a.action NOT IN ('approve','revoke','publish','withdraw')
 OR a.approved IS NULL OR a.published IS NULL OR (a.published AND NOT a.approved)
 OR (a.approved IS DISTINCT FROM (a.approval_id IS NOT NULL))
 OR (a.published IS DISTINCT FROM (a.publication_id IS NOT NULL))
 OR (a.approval_id IS NOT NULL AND (ap.id IS NULL OR ap.action<>'approve'
  OR ap.project_id IS DISTINCT FROM a.project_id OR ap.version_id IS DISTINCT FROM a.version_id
  OR ap.revision_id IS DISTINCT FROM a.revision_id OR ap.config_version>a.config_version))
 OR (a.publication_id IS NOT NULL AND (pub.id IS NULL OR pub.action<>'publish'
  OR pub.project_id IS DISTINCT FROM a.project_id OR pub.version_id IS DISTINCT FROM a.version_id
  OR pub.revision_id IS DISTINCT FROM a.revision_id OR pub.config_version>a.config_version
  OR pub.approval_id IS DISTINCT FROM a.approval_id))
 OR (a.action='approve' AND (NOT a.approved OR a.published OR a.approval_id IS DISTINCT FROM a.id))
 OR (a.action='revoke' AND (a.approved OR a.published))
 OR (a.action='publish' AND (NOT a.approved OR NOT a.published OR a.publication_id IS DISTINCT FROM a.id))
 OR (a.action='withdraw' AND (NOT a.approved OR a.published));
\endif
