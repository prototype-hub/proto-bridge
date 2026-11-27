-- Run on a quiescent database snapshot. Export every retained revision plus any
-- unmigrated legacy projection; never audit history from current-version rows only.
-- Select prototype_hub_table_prefix=protohub_ for a pre-V15 inspection.
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
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_revision_approval_table')) IS NOT NULL AS prototype_hub_has_revision_approvals \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_demo_script_table')) IS NOT NULL AS prototype_hub_has_demo_scripts \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_revision_material_table')) IS NOT NULL AS prototype_hub_has_revision_materials \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_viewport_check_table')) IS NOT NULL AS prototype_hub_has_viewport_checks \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_work_item_table')) IS NOT NULL
 AS prototype_hub_has_work_items \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_element_description_table')) IS NOT NULL
 AS prototype_hub_has_element_descriptions \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_page_delivery_table')) IS NOT NULL
 AS prototype_hub_has_page_deliveries \gset
\set prototype_hub_review_thread_table :prototype_hub_table_prefix 'review_thread'
\set prototype_hub_review_message_table :prototype_hub_table_prefix 'review_message'
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_review_thread_table')) IS NOT NULL
 AND to_regclass(format('%I.%I',current_schema(),:'prototype_hub_review_message_table')) IS NOT NULL
 AS prototype_hub_has_review_threads \gset
SELECT count(*)=6 AS prototype_hub_has_review_images FROM information_schema.columns
 WHERE table_schema=current_schema() AND table_name=:'prototype_hub_review_message_table'
 AND column_name IN ('image_path','image_content_type','image_size','image_sha256','image_width','image_height') \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_revision_table')) IS NOT NULL
 AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
  AND table_name=:'prototype_hub_version_table' AND column_name='current_revision_id')
 AND EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
  AND table_name=:'prototype_hub_share_table' AND column_name='revision_id')
 AS prototype_hub_has_revision_structure \gset
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_share_table' AND column_name='revision_mode')
 AS prototype_hub_has_share_mode \gset
SELECT count(*)=2 AS prototype_hub_has_receipt_revision FROM information_schema.columns
 WHERE table_schema=current_schema() AND table_name=:'prototype_hub_receipt_table'
 AND column_name IN ('resource_id','revision_id') \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_member_table')) IS NOT NULL AS prototype_hub_has_members,
 to_regclass(format('%I.%I',current_schema(),:'prototype_hub_preview_session_table')) IS NOT NULL AS prototype_hub_has_preview_sessions \gset
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_preview_session_table' AND column_name='revision_id')
 AS prototype_hub_has_session_revision \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_review_association_table')) IS NOT NULL AS prototype_hub_has_review_association \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_share_visit_table')) IS NOT NULL AS prototype_hub_has_share_visit \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_copy_snapshot_table')) IS NOT NULL AS prototype_hub_has_copy_snapshot \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_copy_entry_table')) IS NOT NULL AS prototype_hub_has_copy_entry \gset
\if :prototype_hub_has_revision_structure
WITH storage_references AS (
 SELECT id AS version_id,project_id,current_revision_id AS revision_id,storage_path
 FROM :"prototype_hub_version_table"
 UNION
 SELECT version_id,project_id,id,storage_path FROM :"prototype_hub_revision_table"
), integrity_errors AS (
 SELECT v.id FROM :"prototype_hub_version_table" v
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=v.project_id
 WHERE p.id IS NULL OR v.storage_path IS NULL
 UNION ALL
 SELECT r.id FROM :"prototype_hub_revision_table" r
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=r.version_id
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=r.project_id
 WHERE v.id IS NULL OR p.id IS NULL OR v.project_id<>r.project_id OR r.storage_path IS NULL
 UNION ALL
 SELECT v.id FROM :"prototype_hub_version_table" v
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=v.current_revision_id
 WHERE nullif(v.current_revision_id,'') IS NOT NULL
 AND (r.id IS NULL OR r.version_id<>v.id OR r.project_id<>v.project_id
 OR r.storage_path IS DISTINCT FROM v.storage_path OR r.entry_file IS DISTINCT FROM v.entry_file)
 UNION ALL
 SELECT s.id FROM :"prototype_hub_share_table" s
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=s.version_id
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=s.project_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=s.revision_id
 WHERE v.id IS NULL OR p.id IS NULL OR v.project_id<>s.project_id
\if :prototype_hub_has_share_mode
 OR coalesce(s.revision_mode,'fixed') NOT IN ('fixed','latest')
 OR (s.revision_mode='latest' AND nullif(s.revision_id,'') IS NOT NULL)
\endif
 OR (nullif(s.revision_id,'') IS NOT NULL
 AND (r.id IS NULL OR r.version_id<>s.version_id OR r.project_id<>s.project_id))
\if :prototype_hub_has_receipt_revision
 UNION ALL
 -- A revision-bound receipt may name a version, thread or reply. Validate the actual
 -- resource graph; the hashed receipt key does not reveal its operation type.
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
\if :prototype_hub_has_review_threads
 UNION ALL
 SELECT t.id FROM :"prototype_hub_review_thread_table" t
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=t.version_id
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=t.project_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=t.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL OR v.project_id IS DISTINCT FROM t.project_id
  OR r.version_id IS DISTINCT FROM t.version_id OR r.project_id IS DISTINCT FROM t.project_id
 UNION ALL
 SELECT m.id FROM :"prototype_hub_review_message_table" m
 LEFT JOIN :"prototype_hub_review_thread_table" t ON t.id=m.thread_id
 WHERE t.id IS NULL OR m.project_id IS DISTINCT FROM t.project_id
  OR m.version_id IS DISTINCT FROM t.version_id OR m.revision_id IS DISTINCT FROM t.revision_id
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
\if :prototype_hub_has_work_items
 UNION ALL
 SELECT w.id FROM :"prototype_hub_work_item_table" w
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=w.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=w.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=w.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM w.project_id OR r.project_id IS DISTINCT FROM w.project_id
  OR r.version_id IS DISTINCT FROM w.version_id
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
 -- Plain-text fields and static geometry stay in the private backup, never in this export.
\endif
\if :prototype_hub_has_revision_approvals
 UNION ALL
 SELECT a.id FROM :"prototype_hub_revision_approval_table" a
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
 OR (a.action='withdraw' AND (NOT a.approved OR a.published))
\endif
\if :prototype_hub_has_page_deliveries
 UNION ALL
 SELECT d.id FROM :"prototype_hub_page_delivery_table" d
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=d.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=d.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=d.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM d.project_id OR r.project_id IS DISTINCT FROM d.project_id
  OR r.version_id IS DISTINCT FROM d.version_id
  OR NOT coalesce(jsonb_path_exists(r.manifest_json::jsonb,
   '$.pages.** ? (@.pageId == $page && @.path == $path && @.folder == false)',
   jsonb_build_object('page',d.page_id,'path',d.page_path)),false)
 -- A removed/disabled assignee remains legitimate historical metadata.
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
\if :prototype_hub_has_revision_materials
 UNION ALL
 SELECT m.id FROM :"prototype_hub_revision_material_table" m
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=m.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=m.version_id
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=m.revision_id
 WHERE p.id IS NULL OR v.id IS NULL OR r.id IS NULL
  OR v.project_id IS DISTINCT FROM m.project_id OR r.project_id IS DISTINCT FROM m.project_id
  OR r.version_id IS DISTINCT FROM m.version_id OR m.material_type IS NULL
  OR m.material_type NOT IN ('prototype','image','document','link')
  -- Links have no payload: check the whole association but never invent a file reference.
  OR (m.material_type='link' AND (m.file_name IS DISTINCT FROM '' OR m.media_type IS DISTINCT FROM ''
   OR m.file_size IS DISTINCT FROM 0 OR m.sha256 IS DISTINCT FROM '' OR m.storage_path IS DISTINCT FROM ''
   OR m.source_name IS NULL OR btrim(m.source_name)='' OR length(m.source_name)>200
   OR m.source_url IS NULL OR btrim(m.source_url)='' OR length(m.source_url)>2048))
  OR (m.material_type IN ('prototype','image','document') AND (m.file_size IS NULL
   OR m.sha256 IS NULL OR m.storage_path IS NULL OR m.file_size<1 OR m.sha256 !~ '^[a-f0-9]{64}$'
   OR (m.material_type='prototype' AND m.storage_path IS DISTINCT FROM regexp_replace(r.storage_path,'/site$','/original.zip'))
   OR (m.material_type<>'prototype' AND (m.storage_path !~ ('^projects/' || m.project_id || '/materials/[A-Za-z0-9_-]{1,128}/content$') OR m.file_size>20971520))
   OR EXISTS(SELECT 1 FROM :"prototype_hub_revision_material_table" other WHERE other.id<>m.id
    AND (other.storage_path=m.storage_path OR (m.material_type='prototype' AND other.material_type='prototype' AND other.revision_id=m.revision_id)))))
\endif
\if :prototype_hub_has_review_images
 UNION ALL
 SELECT m.id FROM :"prototype_hub_review_message_table" m
 WHERE (m.image_path IS NOT NULL OR m.image_content_type IS NOT NULL OR m.image_size IS NOT NULL
  OR m.image_sha256 IS NOT NULL OR m.image_width IS NOT NULL OR m.image_height IS NOT NULL)
 AND (m.image_path IS NULL OR m.image_content_type IS NULL OR m.image_size IS NULL OR m.image_sha256 IS NULL
  OR m.image_width IS NULL OR m.image_height IS NULL
  OR m.image_path !~ ('^projects/' || m.project_id || '/review-images/[A-Za-z0-9_-]{43}/image$')
  OR m.image_content_type NOT IN ('image/png','image/jpeg') OR m.image_size NOT BETWEEN 1 AND 5242880
  OR m.image_sha256 !~ '^[0-9a-f]{64}$' OR m.image_width NOT BETWEEN 1 AND 16384
  OR m.image_height NOT BETWEEN 1 AND 16384 OR m.image_width::bigint*m.image_height::bigint>16000000
  OR EXISTS(SELECT 1 FROM :"prototype_hub_review_message_table" other
   WHERE other.id<>m.id AND other.image_path=m.image_path))
\endif
\if :prototype_hub_has_members
 UNION ALL
 SELECT m.id FROM :"prototype_hub_member_table" m
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=m.project_id
 WHERE p.id IS NULL
\endif
\if :prototype_hub_has_preview_sessions
 UNION ALL
 SELECT s.id FROM :"prototype_hub_preview_session_table" s
 LEFT JOIN :"prototype_hub_project_table" p ON p.id=s.project_id
 LEFT JOIN :"prototype_hub_version_table" v ON v.id=s.version_id
\if :prototype_hub_has_session_revision
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=s.revision_id
\endif
 WHERE p.id IS NULL OR v.id IS NULL OR v.project_id<>s.project_id
\if :prototype_hub_has_session_revision
 OR (nullif(s.revision_id,'') IS NOT NULL AND (r.id IS NULL
  OR r.version_id<>s.version_id OR r.project_id<>s.project_id
  OR r.storage_path IS DISTINCT FROM s.storage_path))
\endif
\endif
)
SELECT json_build_object('format','prototype-hub-storage-references','formatVersion',4,
 'sourceSchema','immutable-revisions-materials-v1',
 'referencesComplete',(:'prototype_hub_has_receipt_revision'::boolean
  AND :'prototype_hub_has_review_threads'::boolean AND :'prototype_hub_has_review_images'::boolean
  AND :'prototype_hub_has_revision_materials'::boolean
  AND (NOT :'prototype_hub_has_organization_parts'::boolean OR :'prototype_hub_has_organization'::boolean)
  AND (NOT :'prototype_hub_has_page_traceability_parts'::boolean OR :'prototype_hub_has_page_traceability'::boolean)),
 'materialReferencesChecked',:'prototype_hub_has_revision_materials'::boolean,
 'receiptReferencesChecked',:'prototype_hub_has_receipt_revision'::boolean,
 'pageDeliveryReferencesChecked',:'prototype_hub_has_page_deliveries'::boolean,
 'approvalReferencesChecked',:'prototype_hub_has_revision_approvals'::boolean,
 'elementDescriptionReferencesChecked',:'prototype_hub_has_element_descriptions'::boolean,
 'demoScriptReferencesChecked',:'prototype_hub_has_demo_scripts'::boolean,
 'organizationReferencesChecked',:'prototype_hub_has_organization'::boolean,
 'pageTraceabilityReferencesChecked',:'prototype_hub_has_page_traceability'::boolean,
 'codeMappingReferencesChecked',:'prototype_hub_has_code_mappings'::boolean,
 'imageReferencesChecked',(:'prototype_hub_has_review_threads'::boolean AND :'prototype_hub_has_review_images'::boolean),
 'integrityErrors',(SELECT count(*) FROM integrity_errors),
 'entries',coalesce(json_agg(json_build_object('id',version_id,'projectId',project_id,
 'revisionId',revision_id,'storagePath',storage_path)
 ORDER BY project_id,version_id,storage_path,revision_id)
 FILTER (WHERE storage_path IS NOT NULL),'[]'::json),
\if :prototype_hub_has_review_images
 'imageEntries',(SELECT coalesce(json_agg(json_build_object('projectId',project_id,'versionId',version_id,
  'messageId',id,'storagePath',image_path,'size',image_size,'sha256',image_sha256)
  ORDER BY project_id,version_id,id),'[]'::json) FROM :"prototype_hub_review_message_table"
  WHERE image_path IS NOT NULL OR image_content_type IS NOT NULL OR image_size IS NOT NULL
   OR image_sha256 IS NOT NULL OR image_width IS NOT NULL OR image_height IS NOT NULL)
\else
 'imageEntries','[]'::json
\endif
 ,
\if :prototype_hub_has_revision_materials
 'materialEntries',(SELECT coalesce(json_agg(json_build_object('projectId',project_id,'versionId',version_id,
 'revisionId',revision_id,'materialId',id,'materialType',material_type,'storagePath',storage_path,
 'size',file_size,'sha256',sha256) ORDER BY project_id,version_id,revision_id,id),'[]'::json)
 FROM :"prototype_hub_revision_material_table" WHERE material_type IN ('prototype','image','document'))
\else
 'materialEntries','[]'::json
\endif
 ) FROM storage_references;
\else
-- A current-only export is useful for diagnosis, but can never authorize repair.
SELECT json_build_object('format','prototype-hub-storage-references','formatVersion',2,
 'sourceSchema','legacy-current-only','referencesComplete',false,
 'receiptReferencesChecked',false,'pageDeliveryReferencesChecked',false,'approvalReferencesChecked',false,
 'elementDescriptionReferencesChecked',false,'demoScriptReferencesChecked',false,
 'organizationReferencesChecked',false,'pageTraceabilityReferencesChecked',false,'integrityErrors',null,
 'entries',coalesce(json_agg(json_build_object('id',id,'projectId',project_id,
 'revisionId',null,'storagePath',storage_path) ORDER BY project_id,id,storage_path)
 FILTER (WHERE storage_path IS NOT NULL),'[]'::json)) FROM :"prototype_hub_version_table";
\endif
