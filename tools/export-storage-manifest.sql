-- Run on a quiescent database snapshot. Export every retained revision plus any
-- unmigrated legacy projection; never audit history from current-version rows only.
-- Select prototype_hub_table_prefix=protohub_ for a pre-V15 inspection.
\ir prototype-hub-table-names.sql
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
 -- Receipts with no revision include project and pre-migration requests. Only explicit
 -- revision bindings prove a retained version reference; never guess the resource type.
 SELECT c.id FROM :"prototype_hub_receipt_table" c
 LEFT JOIN :"prototype_hub_revision_table" r ON r.id=c.revision_id
 WHERE nullif(c.revision_id,'') IS NOT NULL AND (r.id IS NULL OR r.version_id<>c.resource_id)
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
SELECT json_build_object('format','prototype-hub-storage-references','formatVersion',2,
 'sourceSchema','immutable-revisions-v1','referencesComplete',:'prototype_hub_has_receipt_revision'::boolean,
 'receiptReferencesChecked',:'prototype_hub_has_receipt_revision'::boolean,
 'integrityErrors',(SELECT count(*) FROM integrity_errors),
 'entries',coalesce(json_agg(json_build_object('id',version_id,'projectId',project_id,
 'revisionId',revision_id,'storagePath',storage_path)
 ORDER BY project_id,version_id,storage_path,revision_id)
 FILTER (WHERE storage_path IS NOT NULL),'[]'::json)) FROM storage_references;
\else
-- A current-only export is useful for diagnosis, but can never authorize repair.
SELECT json_build_object('format','prototype-hub-storage-references','formatVersion',2,
 'sourceSchema','legacy-current-only','referencesComplete',false,
 'receiptReferencesChecked',false,'integrityErrors',null,
 'entries',coalesce(json_agg(json_build_object('id',id,'projectId',project_id,
 'revisionId',null,'storagePath',storage_path) ORDER BY project_id,id,storage_path)
 FILTER (WHERE storage_path IS NOT NULL),'[]'::json)) FROM :"prototype_hub_version_table";
\endif
