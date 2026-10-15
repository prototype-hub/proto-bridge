-- Correct only built-in default labels. Permission/action identifiers and custom labels remain intact.
WITH builtin_permissions(id, chinese_name, legacy_english_name) AS (
    VALUES
        ('protohub-project', '原型项目管理', NULL),
        ('protohub-version', '原型版本管理', NULL),
        ('protohub-share', '原型分享管理', NULL),
        ('menu', '菜单管理', NULL),
        ('user', '用户管理', NULL),
        ('dimension', '权限维度管理', NULL),
        ('dimension-type', '维度类型管理', NULL),
        ('dimension-user', '维度用户绑定', NULL),
        ('permission', '权限管理', NULL),
        ('autz-setting', '权限分配', NULL),
        ('user-token', '用户令牌管理', NULL),
        ('role', '角色管理', NULL),
        ('organization', '组织管理', NULL),
        ('position', '职位管理', NULL),
        ('dictionary', '数据字典', NULL),
        ('system-config', '基础配置', NULL),
        ('file', '文件上传', NULL),
        ('system-logger', '系统日志', 'System logs'),
        ('access-logger', '访问日志', 'Access logs')
), action_names(action, chinese_name) AS (
    VALUES
        ('query', '查询'),
        ('save', '保存'),
        ('delete', '删除'),
        ('add', '新增'),
        ('grant', '授权'),
        ('update-self-info', '修改当前用户信息'),
        ('update-self-pwd', '修改当前用户密码')
), translated AS (
    SELECT permission.id,
           CASE
               WHEN permission.name = builtin.id
                 OR permission.name = 'hswebframework.web.system.permission.' || builtin.id
                 OR permission.name = builtin.legacy_english_name
               THEN builtin.chinese_name
               ELSE permission.name
           END AS name,
           COALESCE((
               -- Preserve action order and every field except a recognized default name.
               SELECT jsonb_agg(
                   CASE
                       WHEN label.action IS NOT NULL
                        AND (item.value ->> 'name' = label.action
                             OR item.value ->> 'name' = 'hswebframework.web.system.action.' || label.action)
                       THEN jsonb_set(item.value, '{name}', to_jsonb(label.chinese_name))
                       ELSE item.value
                   END ORDER BY item.ordinality
               )::text
               FROM jsonb_array_elements(permission.actions::jsonb) WITH ORDINALITY AS item(value, ordinality)
               LEFT JOIN action_names label ON label.action = item.value ->> 'action'
           ), permission.actions) AS actions
    FROM s_permission permission
    JOIN builtin_permissions builtin ON builtin.id = permission.id
)
UPDATE s_permission permission
SET name = translated.name,
    actions = translated.actions
FROM translated
WHERE permission.id = translated.id
  AND (permission.name IS DISTINCT FROM translated.name
       OR permission.actions::jsonb IS DISTINCT FROM translated.actions::jsonb);
