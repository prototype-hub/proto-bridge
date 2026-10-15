-- Self-service actions do not grant user administration/query permissions.
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge)
VALUES ('member-self-profile','user','role','MEMBER',1,'["update-self-info","update-self-pwd"]',0,false);
