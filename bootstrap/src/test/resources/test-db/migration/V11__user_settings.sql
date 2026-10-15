-- Personal settings remain isolated by their authenticated owner and scope.
CREATE TABLE proto_user_setting (
    id varchar(64) PRIMARY KEY,
    user_id varchar(64) NOT NULL REFERENCES s_user(id) ON DELETE CASCADE,
    type varchar(128) NOT NULL,
    setting_key varchar(64) NOT NULL,
    name varchar(128) NOT NULL,
    content text NOT NULL,
    create_time bigint NOT NULL,
    UNIQUE(user_id, type, setting_key)
);
CREATE INDEX proto_user_setting_owner_idx ON proto_user_setting(user_id, type, create_time DESC, id);
