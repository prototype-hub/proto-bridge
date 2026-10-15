-- Align system management tables with the current s_* entity mappings.
-- PostgreSQL preserves rows, table/index/constraint identities and foreign keys on rename.
-- A pre-existing target is an error; Flyway rolls back this entire migration without overwriting it.
ALTER TABLE proto_menu RENAME TO s_menu;
ALTER TABLE proto_organization RENAME TO s_organization;
ALTER TABLE proto_position RENAME TO s_position;
ALTER TABLE proto_role_group RENAME TO s_role_group;
ALTER TABLE proto_role_menu_grant RENAME TO s_role_menu_grant;
ALTER TABLE proto_system_config RENAME TO s_system_config;
ALTER TABLE proto_user_detail RENAME TO s_user_detail;
ALTER TABLE proto_user_setting RENAME TO s_user_setting;
