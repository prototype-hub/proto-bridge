-- Menu grants combine role actions. Leave manual hsweb settings and seed grants unchanged.
UPDATE s_autz_setting_info AS setting
SET merge = true,
    priority = GREATEST(COALESCE(setting.priority, 0), 10)
FROM proto_role_menu_grant AS menu_grant
WHERE setting.dimension_type = 'role'
  AND setting.dimension_target = menu_grant.id
  AND setting.state = 1
  AND COALESCE(menu_grant.managed_permissions, '[]')::jsonb ? setting.permission;
