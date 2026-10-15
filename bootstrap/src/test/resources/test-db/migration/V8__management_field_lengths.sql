-- Expand management text/key fields without changing earlier Flyway checksums.
ALTER TABLE s_dimension ALTER COLUMN describe TYPE varchar(1024);
ALTER TABLE s_autz_setting_info ALTER COLUMN dimension_target TYPE varchar(64),ALTER COLUMN permission TYPE varchar(64);
