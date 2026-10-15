INSERT INTO s_permission(id,name,status,actions) VALUES
('system-logger','System logs',1,'[{"action":"query","name":"query"},{"action":"save","name":"save"}]'),
('access-logger','Access logs',1,'[{"action":"query","name":"query"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES
('proto-system-logs-admin','system-logger','role','ADMIN',1,'["query","save"]',0,false),
('proto-access-logs-admin','access-logger','role','ADMIN',1,'["query"]',0,false);
INSERT INTO proto_menu(id,name,url,permission,action,enabled,path,sort_index,_level) VALUES
('system-logs','System logs','/system/log','system-logger','query',true,'0005',4,1);
