-- Legacy frontend tags local menus as iot; this is an owner label, not an IoT dependency.
ALTER TABLE proto_menu
    ADD COLUMN owner varchar(64) NOT NULL DEFAULT 'iot',
    ADD COLUMN describe varchar(1024),
    ADD COLUMN asset_type varchar(64),
    ADD COLUMN access_support varchar(32) NOT NULL DEFAULT 'unsupported',
    ADD COLUMN indirect_menus text;
CREATE INDEX proto_menu_owner_idx ON proto_menu(owner);
