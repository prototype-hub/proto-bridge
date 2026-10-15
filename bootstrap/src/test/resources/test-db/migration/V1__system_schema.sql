-- Stable hsweb 5.0.1 entity mappings; new database schema, not an export of a production database.
CREATE TABLE s_user (id varchar(64) PRIMARY KEY, name varchar(128) NOT NULL, username varchar(128) NOT NULL,
 password varchar(255) NOT NULL, salt varchar(255) NOT NULL, type varchar(255), status smallint DEFAULT 1,
 creator_id varchar(255), create_time bigint);
CREATE UNIQUE INDEX user_username_idx ON s_user(username);
CREATE TABLE s_dimension_type (id varchar(64) PRIMARY KEY, name varchar(32) NOT NULL, describe varchar(256));
CREATE TABLE s_dimension (id varchar(64) PRIMARY KEY, type_id varchar(32), name varchar(32), describe varchar(256),
 properties text, parent_id varchar(64), path varchar(128), sort_index bigint, _level integer);
CREATE INDEX idx_dims_path ON s_dimension(path);
CREATE TABLE s_dimension_user (id varchar(64) PRIMARY KEY, dimension_type_id varchar(32) NOT NULL,
 dimension_id varchar(64) NOT NULL, dimension_name varchar(255) NOT NULL, user_id varchar(64) NOT NULL,
 user_name varchar(255) NOT NULL, relation varchar(32), relation_name varchar(255), features numeric, relation_time bigint);
CREATE INDEX idx_dimsu_dimension_id ON s_dimension_user(dimension_id);
CREATE INDEX idx_dimsu_dimension_type_id ON s_dimension_user(dimension_type_id);
CREATE INDEX idx_dimsu_user_id ON s_dimension_user(user_id);
CREATE TABLE s_permission (id varchar(64) PRIMARY KEY, name varchar(255), describe varchar(255), status smallint NOT NULL DEFAULT 1,
 actions text, optional_fields text, parents text, properties text, create_time bigint, creator_id varchar(64),
 modify_time bigint, modifier_id varchar(64), i18n_messages text);
CREATE TABLE s_autz_setting_info (id varchar(32) PRIMARY KEY, permission varchar(32) NOT NULL, dimension_type varchar(32) NOT NULL,
 dimension_type_name varchar(64), dimension_target varchar(32), dimension_target_name varchar(64), state smallint NOT NULL DEFAULT 1,
 actions text, data_accesses text, priority integer, merge boolean);
CREATE INDEX idx_sasi_dss ON s_autz_setting_info(dimension_type,dimension_target,state DESC);
CREATE UNIQUE INDEX idx_sasi_pdd ON s_autz_setting_info(permission,dimension_type,dimension_target);
CREATE TABLE proto_menu (id varchar(64) PRIMARY KEY, name varchar(128) NOT NULL, url varchar(255), permission varchar(64),
 action varchar(32), enabled boolean NOT NULL, parent_id varchar(64), path varchar(128), sort_index bigint, _level integer);
INSERT INTO s_dimension_type(id,name) VALUES ('role','Role');
INSERT INTO s_dimension(id,type_id,name,path,sort_index,_level) VALUES
 ('ADMIN','role','Administrator','0001',0,1),('MEMBER','role','Member','0002',1,1);
INSERT INTO proto_menu(id,name,url,permission,action,enabled,path,sort_index,_level) VALUES
 ('protohub','ProtoHub','/protohub','protohub-project','query',true,'0001',0,1),
 ('system-user','Users','/system/user','user','query',true,'0002',1,1),
 ('system-role','Roles','/system/role','dimension','query',true,'0003',2,1),
 ('system-menu','Menus','/system/menu','menu','query',true,'0004',3,1);
INSERT INTO s_permission(id,name,status,actions) VALUES ('protohub-project','protohub-project',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('37e9c8d6c7cc96a04b44d4fcec1760ab','protohub-project','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('4a184f503b7f039a5aac27ccf9a15fd8','protohub-project','role','MEMBER',1,'["query"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('protohub-version','protohub-version',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('0f9c1baac67e0d56fbdefda6f976d585','protohub-version','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('81b05264c55e0c86cdba1edb862a9fd1','protohub-version','role','MEMBER',1,'["query"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('protohub-share','protohub-share',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('831ec4febbbbf0cbacf8f779f2c1e2ae','protohub-share','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('8ed1147e8d99a958d3cde78dec53ca34','protohub-share','role','MEMBER',1,'["query"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('menu','menu',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('8470656cf6caca3a0964bfb0f91e84d8','menu','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('user','user',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('a46a90ddb8bd605f757e3a5940cf9c69','user','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('dimension','dimension',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('07faf421644f3877d213791da7d823cb','dimension','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('dimension-type','dimension-type',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('4feea7cab12ca517c91650458f12943a','dimension-type','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('dimension-user','dimension-user',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('19d44b2ebe946244e87de3f592fb5973','dimension-user','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('permission','permission',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('4247e8a82f874dff73c874392f2991c7','permission','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('autz-setting','autz-setting',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('0ba7b5f5ee9a48fc19cfcf28614dd6f7','autz-setting','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
INSERT INTO s_permission(id,name,status,actions) VALUES ('user-token','user-token',1,'[{"action": "query", "name": "query"}, {"action": "save", "name": "save"}, {"action": "delete", "name": "delete"}]');
INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,priority,merge) VALUES ('5dc8c3cd24e3949612269bd558ca67e3','user-token','role','ADMIN',1,'["query", "save", "delete", "grant", "update-self-info", "update-self-pwd"]',0,false);
