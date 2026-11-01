-- Run on a quiescent database snapshot. Export every retained revision plus any
-- unmigrated legacy projection; never audit history from current-version rows only.
-- Select prototype_hub_table_prefix=protohub_ for a pre-V15 inspection.
\ir prototype-hub-table-names.sql
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_work_item_table')) IS NOT NULL
 AS prototype_hub_has_work_items \gset
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
SELECT json_build_object('format','prototype-hub-storage-references','formatVersion',3,
 'sourceSchema','immutable-revisions-review-images-v1',
 'referencesComplete',(:'prototype_hub_has_receipt_revision'::boolean
  AND :'prototype_hub_has_review_threads'::boolean AND :'prototype_hub_has_review_images'::boolean),
 'receiptReferencesChecked',:'prototype_hub_has_receipt_revision'::boolean,
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
 ) FROM storage_references;
\else
-- A current-only export is useful for diagnosis, but can never authorize repair.
SELECT json_build_object('format','prototype-hub-storage-references','formatVersion',2,
 'sourceSchema','legacy-current-only','referencesComplete',false,
 'receiptReferencesChecked',false,'integrityErrors',null,
 'entries',coalesce(json_agg(json_build_object('id',id,'projectId',project_id,
 'revisionId',null,'storagePath',storage_path) ORDER BY project_id,id,storage_path)
 FILTER (WHERE storage_path IS NOT NULL),'[]'::json)) FROM :"prototype_hub_version_table";
\endif
