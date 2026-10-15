-- Keep default menu names Chinese while retaining the existing per-field locale metadata.
-- Only known built-in codes (or their seed IDs when code is absent) and untouched default names
-- are eligible. User names, owners, hierarchy, permissions and non-name JSON fields are preserved.
WITH menu_defaults(code, seed_id, zh, en, default_names) AS (
    VALUES
    ('protohub', NULL, '原型管理', 'Prototype Management', ARRAY['原型管理','Prototype Management']),
    ('protohub/Project', 'protohub', '原型项目', 'Prototype Projects', ARRAY['原型项目','Prototype Projects','ProtoHub']),
    ('system', NULL, '系统管理', 'System Management', ARRAY['系统管理','System Management']),
    ('system/Basis', 'system-basis', '基础配置', 'Basic Configuration', ARRAY['基础配置','基本设置','Basic settings','Basic Settings','Basic Configuration']),
    ('system/User', 'system-user', '用户管理', 'User Management', ARRAY['用户管理','Users','User Management']),
    ('system/Department', 'system-org', '组织管理', 'Organization Management', ARRAY['组织管理','Organizations','Organization Management']),
    ('system/Role', 'system-role', '角色管理', 'Role Management', ARRAY['角色管理','Roles','Role Management']),
    ('system/Menu', 'system-menu', '菜单管理', 'Menu Management', ARRAY['菜单管理','Menus','Menu Management']),
    ('system/Permission', 'system-permission', '权限管理', 'Permission Management', ARRAY['权限管理','Permissions','Permission Management']),
    ('system/Dictionary', 'system-dictionary', '数据字典', 'Data Dictionary', ARRAY['数据字典','字典管理','Dictionary','Data Dictionary']),
    ('system/Positions', 'system-position', '职位管理', 'Position Management', ARRAY['职位管理','Positions','Positions Management','Position Management']),
    ('system/Log', NULL, '日志管理', 'Log Management', ARRAY['日志管理','Log Management']),
    ('system/Log/Access', 'system-access-log', '访问日志', 'Access Logs', ARRAY['访问日志','Access logs','Access Logs']),
    ('system/Log/System', 'system-logs', '系统日志', 'System Logs', ARRAY['系统日志','System logs','System Logs'])
), matched AS (
    SELECT m.id, d.code, m.buttons, d.zh, d.en,
           COALESCE(NULLIF(m.i18n_messages, ''), '{}')::jsonb AS messages
    FROM s_menu m JOIN menu_defaults d
      ON m.code=d.code OR ((m.code IS NULL OR m.code='') AND m.id=d.seed_id)
    WHERE m.owner='iot' AND m.name=ANY(d.default_names)
), localized AS (
    SELECT m.*, CASE WHEN jsonb_typeof(messages->'name')='object'
                    THEN messages->'name' ELSE '{}'::jsonb END AS names
    FROM matched m
), button_defaults(id, zh, en) AS (
    VALUES
    ('add','新增','Add'), ('update','编辑','Edit'), ('delete','删除','Delete'),
    ('action','操作','Actions'), ('bind','绑定/解绑成员','Bind/Unbind Members'),
    ('bind-user','绑定/解绑成员','Bind/Unbind Members'),
    ('groupUpdate','保存角色分组','Save Role Group'), ('groupDelete','删除角色分组','Delete Role Group'),
    ('grant','授权','Grant'), ('import','导入','Import'), ('export','导出','Export'),
    ('down','下载','Download'), ('user-center-passwd-update','修改密码','Change Password')
), repaired AS (
    SELECT m.id,
           CASE WHEN NULLIF(btrim(names->>'zh_CN'),'') IS NOT NULL
                THEN names->>'zh_CN' ELSE m.zh END AS name,
           messages || jsonb_build_object('name', names || jsonb_build_object(
               'zh_CN', CASE WHEN NULLIF(btrim(names->>'zh_CN'),'') IS NOT NULL THEN names->>'zh_CN' ELSE m.zh END,
               'en_US', CASE WHEN NULLIF(btrim(names->>'en_US'),'') IS NOT NULL THEN names->>'en_US' ELSE m.en END,
               'en', CASE WHEN NULLIF(btrim(names->>'en'),'') IS NOT NULL THEN names->>'en' ELSE m.en END)) AS messages,
           CASE WHEN m.buttons IS NULL THEN NULL ELSE fixed.buttons::text END AS buttons
    FROM localized m
    LEFT JOIN LATERAL (
        -- Aggregate in original order; only name/locale metadata of default-labelled buttons changes.
        SELECT COALESCE(jsonb_agg(
            CASE WHEN d.id IS NOT NULL AND b->>'name'=ANY(ARRAY[d.id,d.zh,d.en,label.zh,label.en])
                 THEN b || jsonb_build_object(
                     'name', CASE WHEN NULLIF(btrim(i18n.messages->>'zh_CN'),'') IS NOT NULL THEN i18n.messages->>'zh_CN' ELSE label.zh END,
                     'i18nMessages', i18n.messages || jsonb_build_object(
                         'zh_CN', CASE WHEN NULLIF(btrim(i18n.messages->>'zh_CN'),'') IS NOT NULL THEN i18n.messages->>'zh_CN' ELSE label.zh END,
                         'en_US', CASE WHEN NULLIF(btrim(i18n.messages->>'en_US'),'') IS NOT NULL THEN i18n.messages->>'en_US' ELSE label.en END,
                         'en', CASE WHEN NULLIF(btrim(i18n.messages->>'en'),'') IS NOT NULL THEN i18n.messages->>'en' ELSE label.en END))
                 ELSE b END ORDER BY ordinal), '[]'::jsonb) AS buttons
        FROM jsonb_array_elements(COALESCE(NULLIF(m.buttons,''),'[]')::jsonb) WITH ORDINALITY AS items(b, ordinal)
        LEFT JOIN button_defaults d ON d.id=b->>'id'
        CROSS JOIN LATERAL (
            SELECT CASE WHEN m.code='system/Log/System' AND d.id='update' THEN '调整日志级别' ELSE d.zh END AS zh,
                   CASE WHEN m.code='system/Log/System' AND d.id='update' THEN 'Update Log Level' ELSE d.en END AS en
        ) label
        CROSS JOIN LATERAL (
            SELECT CASE WHEN jsonb_typeof(b->'i18nMessages')='object'
                        THEN b->'i18nMessages' ELSE '{}'::jsonb END AS messages
        ) i18n
    ) fixed ON true
)
UPDATE s_menu m
SET name=r.name, i18n_messages=r.messages::text, buttons=r.buttons
FROM repaired r WHERE m.id=r.id;
