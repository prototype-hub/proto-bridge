-- Select prototype_hub_table_prefix=protohub_ for a pre-V15 export; the default reads p_version.
\ir prototype-hub-table-names.sql
SELECT coalesce(json_agg(json_build_object('id',id,'projectId',project_id,'storagePath',storage_path)),'[]'::json) FROM :"prototype_hub_version_table";
