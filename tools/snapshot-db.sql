-- Save before and after; counts alone cannot prove identity/relationship preservation.
-- Select prototype_hub_table_prefix=protohub_ before V15; the default reads current p_* tables.
\ir prototype-hub-table-names.sql
-- Identity, accepted credential/sequence and durable sweep cursors must survive a quiescent backup/restore.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_installation_table')) IS NOT NULL AS prototype_hub_has_installation \gset
\if :prototype_hub_has_installation
SELECT 'installation',count(*),md5(coalesce(jsonb_agg(to_jsonb(i) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_installation_table" i;
\endif
SELECT 'projects', count(*), md5(coalesce(string_agg(id, ',' ORDER BY id),'')) FROM :"prototype_hub_project_table";
SELECT 'versions', count(*), md5(coalesce(string_agg(id||':'||project_id, ',' ORDER BY id),'')) FROM :"prototype_hub_version_table";
SELECT 'shares', count(*), md5(coalesce(string_agg(id||':'||project_id||':'||version_id, ',' ORDER BY id),'')) FROM :"prototype_hub_share_table";

-- Compare stable ownership separately from expected short-session issuance/pruning.
-- Only hashes/counts are emitted; never print capability tokens or the full private rows.
SELECT count(*)=2 AS prototype_hub_has_ownership FROM information_schema.columns
 WHERE table_schema=current_schema() AND table_name=:'prototype_hub_project_table'
 AND column_name IN ('owner_id','access_epoch') \gset
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_member_table')) IS NOT NULL AS prototype_hub_has_members,
       to_regclass(format('%I.%I',current_schema(),:'prototype_hub_preview_session_table')) IS NOT NULL AS prototype_hub_has_preview_sessions \gset
\if :prototype_hub_has_ownership
SELECT 'project_authorization',count(*),md5(coalesce(jsonb_agg(jsonb_build_array(id,owner_id,access_epoch) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_project_table";
\endif
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_receipt_table' AND column_name='revision_id') AS prototype_hub_has_receipt_revision \gset
\if :prototype_hub_has_receipt_revision
SELECT 'creation_receipt_revision_bindings',count(*),md5(coalesce(jsonb_agg(
 jsonb_build_array(id,resource_id,revision_id) ORDER BY id)::text,'[]')) FROM :"prototype_hub_receipt_table";
\endif
-- Immutable content and current/share bindings must survive migration and restore.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_revision_table')) IS NOT NULL AS prototype_hub_has_revisions \gset
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_version_table' AND column_name='current_revision_id') AS prototype_hub_has_current_revision \gset
\if :prototype_hub_has_revisions
SELECT 'revisions',count(*),md5(coalesce(jsonb_agg(to_jsonb(r) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_revision_table" r;
\if :prototype_hub_has_current_revision
SELECT 'current_revision_bindings',count(*),md5(coalesce(jsonb_agg(
 jsonb_build_array(id,project_id,current_revision_id,storage_path,entry_file) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_version_table";
\endif
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_share_table' AND column_name='revision_id') AS prototype_hub_has_share_revision \gset
\if :prototype_hub_has_share_revision
SELECT 'share_revision_bindings',count(*),md5(coalesce(jsonb_agg(
 jsonb_build_array(id,project_id,version_id,revision_id) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_share_table";
SELECT count(*)=3 AS prototype_hub_has_share_lifecycle FROM information_schema.columns
 WHERE table_schema=current_schema() AND table_name=:'prototype_hub_share_table'
 AND column_name IN ('revision_mode','revoked','config_version') \gset
\if :prototype_hub_has_share_lifecycle
-- Hash the complete private record: policy, revocation, version and rotated secrets must all survive restore.
SELECT 'share_lifecycle',count(*),md5(coalesce(jsonb_agg(to_jsonb(s) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_share_table" s;
\endif
\endif
\endif
\if :prototype_hub_has_members
SELECT 'project_members',count(*),md5(coalesce(jsonb_agg(to_jsonb(m) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_member_table" m;
\endif
\if :prototype_hub_has_preview_sessions
SELECT 'short_preview_sessions',count(*),md5(coalesce(jsonb_agg(to_jsonb(s) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_preview_session_table" s;
\endif

-- Committed purge tasks bridge the database transaction and retryable file deletion.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_cleanup_table')) IS NOT NULL AS prototype_hub_has_cleanup \gset
\if :prototype_hub_has_cleanup
SELECT 'file_cleanup',count(*),md5(coalesce(jsonb_agg(to_jsonb(c) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_cleanup_table" c;
\endif

-- Deletion status is stable business data: restoring only IDs must not revive access.
-- Old schemas remain diagnosable before EasyORM adds the nullable columns.
SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_project_table' AND column_name='deleted_time') AS prototype_hub_has_project_deletion,
 EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=current_schema()
 AND table_name=:'prototype_hub_version_table' AND column_name='deleted_time') AS prototype_hub_has_version_deletion \gset
\if :prototype_hub_has_project_deletion
SELECT 'project_deletion_state',count(*),md5(coalesce(jsonb_agg(
 jsonb_build_array(id,deleted_time) ORDER BY id)::text,'[]')) FROM :"prototype_hub_project_table";
\endif
\if :prototype_hub_has_version_deletion
SELECT 'version_deletion_state',count(*),md5(coalesce(jsonb_agg(
 jsonb_build_array(id,project_id,deleted_time) ORDER BY id)::text,'[]')) FROM :"prototype_hub_version_table";
\endif

-- Work item references are private durable page metadata; emit hashes, never URLs or notes.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_work_item_table')) IS NOT NULL AS prototype_hub_has_work_items \gset
\if :prototype_hub_has_work_items
SELECT 'work_items',count(*),md5(coalesce(jsonb_agg(to_jsonb(w) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_work_item_table" w;
\endif

-- Access aggregates must be reproducible after restoring the matching whole database.
SELECT to_regclass(format('%I.%I',current_schema(),:'prototype_hub_share_visit_table')) IS NOT NULL AS prototype_hub_has_share_visits \gset
\if :prototype_hub_has_share_visits
SELECT 'share_visits',count(*),md5(coalesce(jsonb_agg(to_jsonb(v) ORDER BY id)::text,'[]'))
 FROM :"prototype_hub_share_visit_table" v;
\endif
