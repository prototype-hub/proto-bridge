-- The role details screen queries and saves the shared menu authorization tree.
UPDATE proto_menu SET permissions='[{"permission":"menu","actions":["query"]}]',buttons='[{"id":"add","name":"add"},{"id":"update","name":"update","permissions":[{"permission":"menu","actions":["query","grant"]}]},{"id":"delete","name":"delete"},{"id":"groupUpdate","name":"groupUpdate"},{"id":"groupDelete","name":"groupDelete"}]' WHERE id='system-role';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "基础配置"}}' WHERE id='system-basis';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "用户管理"}}' WHERE id='system-user';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "组织管理"}}' WHERE id='system-org';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "角色管理"}}' WHERE id='system-role';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "菜单管理"}}' WHERE id='system-menu';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "权限管理"}}' WHERE id='system-permission';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "数据字典"}}' WHERE id='system-dictionary';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "职位管理"}}' WHERE id='system-position';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "访问日志"}}' WHERE id='system-access-log';
UPDATE proto_menu SET i18n_messages='{"name": {"zh_CN": "系统日志"}}' WHERE id='system-logs';
