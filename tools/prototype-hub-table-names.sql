-- Explicit migration stage: current V15+ defaults to p_; V2-V14 uses
-- psql -v prototype_hub_table_prefix=protohub_ ... . Names are quoted as identifiers by callers.
\if :{?prototype_hub_table_prefix}
\else
\set prototype_hub_table_prefix p_
\endif
\set prototype_hub_project_table :prototype_hub_table_prefix 'project'
\set prototype_hub_version_table :prototype_hub_table_prefix 'version'
\set prototype_hub_revision_table :prototype_hub_table_prefix 'revision'
\set prototype_hub_share_table :prototype_hub_table_prefix 'share'
\set prototype_hub_cleanup_table :prototype_hub_table_prefix 'file_cleanup'
\set prototype_hub_member_table :prototype_hub_table_prefix 'project_member'
\set prototype_hub_preview_session_table :prototype_hub_table_prefix 'preview_session'
\set prototype_hub_receipt_table :prototype_hub_table_prefix 'creation_receipt'
\set prototype_hub_installation_table :prototype_hub_table_prefix 'installation'
\set prototype_hub_work_item_table :prototype_hub_table_prefix 'work_item'
\set prototype_hub_share_visit_table :prototype_hub_table_prefix 'share_visit'
