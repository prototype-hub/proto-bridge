-- Upgrade the three reserved resource identifiers and their structured references together.
-- Flyway runs this migration in one PostgreSQL transaction. Never merge an existing new resource
-- with an old one: that would discard independently configured actions or data-access boundaries.
LOCK TABLE s_permission, s_autz_setting_info, s_menu, s_role_menu_grant IN SHARE ROW EXCLUSIVE MODE;

CREATE FUNCTION pg_temp.prototype_hub_reference(value text, context text)
RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$
BEGIN
    -- Names, actions, menu IDs and translations are data, even when equal to an old namespace.
    IF context IN ('id', 'parentId', 'name', 'label', 'description', 'describe', 'hint', 'action')
       OR context ~ '^[a-z]{2}([_-][A-Za-z]{2})?$' THEN
        RETURN value;
    END IF;
    IF context IN ('permission', 'permissionId', 'resource', 'resourceId', 'permissions',
                   'permissionIds', 'resources', 'parents', 'managedPermissions', 'metadata-key')
       AND value IN ('protohub-project', 'protohub-version', 'protohub-share') THEN
        RETURN 'prototype-hub-' || substr(value, 10);
    END IF;
    IF context IN ('code', 'componentCode', 'parentCode', 'menuCode', 'code-key')
       AND (value = 'protohub' OR value LIKE 'protohub/%') THEN
        RETURN 'prototype-hub' || substr(value, 9);
    END IF;
    IF context IN ('url', 'route', 'redirect', 'redirectTo', 'path', 'route-key')
       AND value ~ '^/protohub($|[/?#])' THEN
        RETURN '/prototype-hub' || substr(value, 10);
    END IF;
    IF context IN ('metadata-key', 'i18n', 'i18nKey', 'messageKey', 'nameKey', 'titleKey', 'localeKey') THEN
        IF value ~ '^(protohub|protoHub)[.]' THEN
            RETURN 'prototypeHub' || substr(value, 9);
        END IF;
        IF value LIKE 'error.protohub.%' THEN
            RETURN 'error.prototype-hub.' || substr(value, 16);
        END IF;
        IF value IN ('hswebframework.web.system.permission.protohub-project',
                     'hswebframework.web.system.permission.protohub-version',
                     'hswebframework.web.system.permission.protohub-share') THEN
            RETURN replace(value, '.protohub-', '.prototype-hub-');
        END IF;
    END IF;
    RETURN value;
END
$$;

CREATE FUNCTION pg_temp.prototype_hub_json(value jsonb, context text DEFAULT '')
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    result jsonb;
    item record;
    renamed_key text;
    key_context text;
BEGIN
    IF value IS NULL THEN RETURN NULL; END IF;
    CASE jsonb_typeof(value)
        WHEN 'object' THEN
            result := '{}'::jsonb;
            FOR item IN SELECT key, val FROM jsonb_each(value) AS entries(key, val) LOOP
                key_context := CASE WHEN context IN ('codes', 'codeMap') THEN 'code-key'
                                    WHEN context IN ('routes', 'routeMap') THEN 'route-key'
                                    ELSE 'metadata-key' END;
                renamed_key := pg_temp.prototype_hub_reference(item.key, key_context);
                IF result ? renamed_key THEN
                    RAISE EXCEPTION 'PrototypeHub migration JSON key conflict: %', renamed_key
                        USING ERRCODE = '23505';
                END IF;
                -- IDs and visible text are opaque. Recurse through every other nested descriptor.
                result := result || jsonb_build_object(renamed_key,
                    CASE WHEN item.key = 'id' AND context IN ('permissions', 'resources', 'permissionIds')
                              AND jsonb_typeof(item.val) = 'string'
                         THEN to_jsonb(pg_temp.prototype_hub_reference(item.val #>> '{}', 'permission'))
                         WHEN item.key IN ('id', 'parentId', 'name', 'label', 'description',
                                          'describe', 'hint', 'action', 'actions', 'indirectMenus')
                         THEN item.val
                         ELSE pg_temp.prototype_hub_json(item.val, item.key) END);
            END LOOP;
            RETURN result;
        WHEN 'array' THEN
            SELECT COALESCE(jsonb_agg(pg_temp.prototype_hub_json(entry, context) ORDER BY ordinal),
                            '[]'::jsonb) INTO result
            FROM jsonb_array_elements(value) WITH ORDINALITY AS entries(entry, ordinal);
            RETURN result;
        WHEN 'string' THEN
            RETURN to_jsonb(pg_temp.prototype_hub_reference(value #>> '{}', context));
        ELSE
            RETURN value;
    END CASE;
END
$$;

CREATE FUNCTION pg_temp.prototype_hub_json_text(value text, context text DEFAULT '')
RETURNS text LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    parsed jsonb;
    renamed jsonb;
BEGIN
    IF value IS NULL OR value = '' OR (value NOT LIKE '%protohub%' AND value NOT LIKE '%protoHub%') THEN
        RETURN value;
    END IF;
    parsed := value::jsonb;
    renamed := pg_temp.prototype_hub_json(parsed, context);
    -- Keep untouched metadata byte-for-byte instead of rewriting its whitespace/key order.
    RETURN CASE WHEN renamed = parsed THEN value ELSE renamed::text END;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM s_permission old_permission
        JOIN s_permission new_permission
          ON new_permission.id = pg_temp.prototype_hub_reference(old_permission.id, 'permission')
        WHERE old_permission.id IN ('protohub-project', 'protohub-version', 'protohub-share')
    ) THEN
        RAISE EXCEPTION 'PrototypeHub migration permission identifier conflict' USING ERRCODE = '23505';
    END IF;
    IF EXISTS (
        SELECT 1 FROM s_autz_setting_info old_setting
        JOIN s_autz_setting_info new_setting
          ON new_setting.id <> old_setting.id
         AND new_setting.permission = pg_temp.prototype_hub_reference(old_setting.permission, 'permission')
         AND new_setting.dimension_type = old_setting.dimension_type
         AND new_setting.dimension_target IS NOT DISTINCT FROM old_setting.dimension_target
        WHERE old_setting.permission IN ('protohub-project', 'protohub-version', 'protohub-share')
    ) THEN
        RAISE EXCEPTION 'PrototypeHub migration authorization tuple conflict' USING ERRCODE = '23505';
    END IF;
    IF EXISTS (
        SELECT 1 FROM s_menu old_menu JOIN s_menu new_menu
          ON new_menu.id <> old_menu.id
         AND new_menu.code = pg_temp.prototype_hub_reference(old_menu.code, 'code')
        WHERE old_menu.code = 'protohub' OR old_menu.code LIKE 'protohub/%'
    ) THEN
        RAISE EXCEPTION 'PrototypeHub migration menu code conflict' USING ERRCODE = '23505';
    END IF;
END
$$;

-- A permission resource ID is the namespace itself. Every other record/menu/role/user ID stays.
UPDATE s_permission
SET id = pg_temp.prototype_hub_reference(id, 'permission'),
    parents = pg_temp.prototype_hub_json_text(parents, 'parents'),
    properties = pg_temp.prototype_hub_json_text(properties, 'properties'),
    i18n_messages = pg_temp.prototype_hub_json_text(i18n_messages, 'i18nMessages');

-- Actions, data_accesses, state, priority, merge and authorization record IDs remain untouched.
UPDATE s_autz_setting_info
SET permission = pg_temp.prototype_hub_reference(permission, 'permission')
WHERE permission IN ('protohub-project', 'protohub-version', 'protohub-share');

-- parent_id/path/indirect_menus and role grant menu-map keys refer to stable menu IDs, not codes.
UPDATE s_menu
SET code = pg_temp.prototype_hub_reference(code, 'code'),
    component_code = pg_temp.prototype_hub_reference(component_code, 'componentCode'),
    url = pg_temp.prototype_hub_reference(url, 'url'),
    permission = pg_temp.prototype_hub_reference(permission, 'permission'),
    options = pg_temp.prototype_hub_json_text(options, 'options'),
    permissions = pg_temp.prototype_hub_json_text(permissions, 'permissions'),
    buttons = pg_temp.prototype_hub_json_text(buttons, 'buttons'),
    i18n_messages = pg_temp.prototype_hub_json_text(i18n_messages, 'i18nMessages');

UPDATE s_role_menu_grant
SET managed_permissions = pg_temp.prototype_hub_json_text(managed_permissions, 'managedPermissions');
