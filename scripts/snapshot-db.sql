-- Save before and after; counts alone cannot prove identity/relationship preservation.
-- Select prototype_hub_table_prefix=protohub_ before V15; the default reads current p_* tables.
\ir prototype-hub-table-names.sql
SELECT 'projects', count(*), md5(coalesce(string_agg(id, ',' ORDER BY id),'')) FROM :"prototype_hub_project_table";
SELECT 'versions', count(*), md5(coalesce(string_agg(id||':'||project_id, ',' ORDER BY id),'')) FROM :"prototype_hub_version_table";
SELECT 'shares', count(*), md5(coalesce(string_agg(id||':'||project_id||':'||version_id, ',' ORDER BY id),'')) FROM :"prototype_hub_share_table";
