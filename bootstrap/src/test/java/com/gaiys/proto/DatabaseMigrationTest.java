package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DatabaseMigrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void nonDestructiveIndexUpgradePreservesStringIdsRelationshipsAndTimes() throws Exception {
        Flyway.configure()
                .locations("classpath:test-db/migration")
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .target("2")
                .load()
                .migrate();
        try (var connection =
                        DriverManager.getConnection(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword());
                var sql = connection.createStatement()) {
            sql.execute(
                    "INSERT INTO protohub_project(id,name,creator_id,create_time,modifier_id,modify_time) VALUES ('legacy-project','旧原型','legacy-user',1720000000123,'legacy-user',1720000000456)");
            sql.execute(
                    "INSERT INTO protohub_version(id,project_id,version_name,storage_path,entry_file,preview_key,status,creator_id,create_time) VALUES ('legacy-version','legacy-project','版本一','projects/legacy-project/versions/legacy-version/site','nested/index.html','retained-preview','ready','legacy-user',1720000000123)");
            sql.execute(
                    "INSERT INTO protohub_share(id,project_id,version_id,token,access_key,enabled,creator_id,create_time) VALUES ('legacy-share','legacy-project','legacy-version','retained-token','retained-key',true,'legacy-user',1720000000123)");
            Flyway.configure()
                    .locations("classpath:test-db/migration")
                    .dataSource(
                            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                    .load()
                    .migrate();
            try (var result =
                    sql.executeQuery(
                            "SELECT p.id,v.id,s.id,v.status,p.create_time,p.modify_time,s.token,s.access_key,s.expires_at,v.storage_path FROM p_project p JOIN p_version v ON v.project_id=p.id JOIN p_share s ON s.project_id=p.id AND s.version_id=v.id")) {
                assertTrue(result.next());
                assertEquals("legacy-project", result.getString(1));
                assertEquals("legacy-version", result.getString(2));
                assertEquals("legacy-share", result.getString(3));
                assertEquals("ready", result.getString(4));
                assertEquals(1720000000123L, result.getLong(5));
                assertEquals(1720000000456L, result.getLong(6));
                assertEquals("retained-token", result.getString(7));
                assertEquals("retained-key", result.getString(8));
                assertNull(result.getObject(9));
                assertEquals(
                        "projects/legacy-project/versions/legacy-version/site",
                        result.getString(10));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void shareDescriptionUpgradeKeepsExistingRowsNullAndAddsAnOptionalBoundedColumn()
            throws Exception {
        String schema = schema();
        migrations(schema, "18").migrate();
        try (var connection = connection(schema);
                var sql = connection.createStatement()) {
            sql.execute(
                    "INSERT INTO p_project(id,name,creator_id,create_time,modifier_id,modify_time) VALUES ('description-project','Project','actor',1720000000000,'actor',1720000000000)");
            sql.execute(
                    "INSERT INTO p_version(id,project_id,version_name,storage_path,entry_file,preview_key,status,creator_id,create_time) VALUES ('description-version','description-project','Version','projects/description-project/versions/description-version/site','index.html','preview','ready','actor',1720000000000)");
            sql.execute(
                    "INSERT INTO p_share(id,project_id,version_id,token,access_key,enabled,creator_id,create_time) VALUES ('description-share','description-project','description-version','description-token','description-key',true,'actor',1720000000000)");

            migrations(schema, null).migrate();

            try (var result =
                    sql.executeQuery(
                            "SELECT is_nullable,character_maximum_length FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='p_share' AND column_name='description'")) {
                assertTrue(result.next());
                assertEquals("YES", result.getString(1));
                assertEquals(2000, result.getInt(2));
                assertFalse(result.next());
            }
            try (var result =
                    sql.executeQuery(
                            "SELECT description FROM p_share WHERE id='description-share'")) {
                assertTrue(result.next());
                assertNull(result.getString(1));
                assertFalse(result.next());
            }
            assertEquals(
                    1,
                    sql.executeUpdate(
                            "UPDATE p_share SET description='Migration description' WHERE id='description-share'"));
            try (var result =
                    sql.executeQuery(
                            "SELECT description FROM p_share WHERE id='description-share'")) {
                assertTrue(result.next());
                assertEquals("Migration description", result.getString(1));
            }
            try (var result =
                    sql.executeQuery(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='19' AND success=true")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    private static final List<String> SYSTEM_TABLES =
            List.of(
                    "menu",
                    "organization",
                    "position",
                    "role_group",
                    "role_menu_grant",
                    "system_config",
                    "user_detail",
                    "user_setting");

    private static final List<String> PROTOTYPE_HUB_TABLES =
            List.of("project", "version", "share", "file_cleanup");

    private Flyway migrations(String schema, String target) {
        var configuration =
                Flyway.configure()
                        .locations("classpath:test-db/migration")
                        .dataSource(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword())
                        .schemas(schema)
                        .defaultSchema(schema);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private Connection connection(String schema) throws SQLException {
        Connection connection =
                DriverManager.getConnection(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        connection.setSchema(schema);
        return connection;
    }

    private String schema() {
        return "migration_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void legacyManagementRecords(Connection connection) throws SQLException {
        // Synthetic user data verifies the detail/settings foreign keys without using real
        // credentials.
        try (var user =
                connection.prepareStatement(
                        "INSERT INTO s_user(id,name,username,password,salt,status) VALUES ('migration-user','Migration User','migration-user',?,?,1)")) {
            user.setString(1, UUID.randomUUID().toString());
            user.setString(2, UUID.randomUUID().toString());
            user.executeUpdate();
        }
        try (var sql = connection.createStatement()) {
            sql.execute(
                    "INSERT INTO proto_organization(id,name,code,parent_id,path,sort_index,_level,properties) VALUES ('migration-org-root','Root Org','root',NULL,'morg',4,1,'{\"region\":\"test\"}'),('migration-org-child','Child Org','child','migration-org-root','morg-child',5,2,'{}')");
            sql.execute(
                    "INSERT INTO proto_position(id,name,org_id,parent_id,path,sort_index,_level,roles,description) VALUES ('migration-position-root','Root Position','migration-org-root',NULL,'mpos',6,1,'[{\"id\":\"MEMBER\"}]','root description'),('migration-position-child','Child Position','migration-org-child','migration-position-root','mpos-child',7,2,'[{\"id\":\"ADMIN\"}]','child description')");
            sql.execute(
                    "INSERT INTO proto_role_group(id,name,parent_id,path,sort_index,_level) VALUES ('migration-group-root','Root Group',NULL,'mgroup',8,1),('migration-group-child','Child Group','migration-group-root','mgroup-child',9,2)");
            sql.execute(
                    "INSERT INTO proto_menu(id,name,url,permission,action,enabled,parent_id,path,sort_index,_level,code,component_code,owner,describe,asset_type,access_support,indirect_menus,options,permissions,buttons,i18n_messages) VALUES ('migration-menu-root','Root Menu','/migration/root','user','query',false,NULL,'mmenu',10,1,'migration/Root','system/User','other-owner','preserved metadata','user','support',NULL,'{\"show\":false}','[{\"permission\":\"user\",\"actions\":[\"query\"]}]','[{\"id\":\"update\",\"permissions\":[{\"permission\":\"user\",\"actions\":[\"save\"]}]}]','{\"name\":{\"zh_CN\":\"保留菜单\"}}'),('migration-menu-child','Child Menu','/migration/child','user','query',true,'migration-menu-root','mmenu-child',11,2,'migration/Child','system/User','other-owner','child metadata',NULL,'indirect','[\"migration-menu-root\"]','{}','[]','[]','{}')");
            sql.execute(
                    "INSERT INTO proto_role_menu_grant(id,menus,managed_permissions) VALUES ('ADMIN','{\"migration-menu-root\":[\"update\"]}','[\"user\"]')");
            sql.execute(
                    "INSERT INTO proto_system_config(id,properties) VALUES ('migration-front','{\"title\":\"Preserved title\",\"enabled\":true}')");
            sql.execute(
                    "INSERT INTO proto_user_detail(id,email,telephone,avatar) VALUES ('migration-user','migration@example.test','10000000','/test-avatar.png')");
            sql.execute(
                    "INSERT INTO proto_user_setting(id,user_id,type,setting_key,name,content,create_time) VALUES ('migration-setting','migration-user','system-user','query-1','Saved search','{\"terms\":[{\"column\":\"name\",\"value\":\"Migration\"}]}',1720000000789)");
        }
    }

    private record TableSnapshot(
            List<String> rows, long tableId, Set<Long> indexes, Set<Long> constraints) {}

    private Map<String, TableSnapshot> snapshot(Connection connection, String prefix)
            throws SQLException {
        return snapshot(connection, prefix, SYSTEM_TABLES);
    }

    private Map<String, TableSnapshot> snapshot(
            Connection connection, String prefix, List<String> tables) throws SQLException {
        Map<String, TableSnapshot> result = new LinkedHashMap<>();
        try (var sql = connection.createStatement()) {
            for (String suffix : tables) {
                String table = prefix + suffix;
                List<String> rows = new ArrayList<>();
                try (var values =
                        sql.executeQuery(
                                "SELECT to_jsonb(entry)::text FROM "
                                        + table
                                        + " AS entry ORDER BY id")) {
                    while (values.next()) rows.add(values.getString(1));
                }
                long tableId;
                try (var values =
                        sql.executeQuery("SELECT '" + table + "'::regclass::oid::bigint")) {
                    assertTrue(values.next());
                    tableId = values.getLong(1);
                }
                Set<Long> indexes = new LinkedHashSet<>(), constraints = new LinkedHashSet<>();
                try (var values =
                        sql.executeQuery(
                                "SELECT indexrelid::bigint FROM pg_index WHERE indrelid='"
                                        + table
                                        + "'::regclass ORDER BY indexrelid")) {
                    while (values.next()) indexes.add(values.getLong(1));
                }
                try (var values =
                        sql.executeQuery(
                                "SELECT oid::bigint FROM pg_constraint WHERE conrelid='"
                                        + table
                                        + "'::regclass ORDER BY oid")) {
                    while (values.next()) constraints.add(values.getLong(1));
                }
                result.put(suffix, new TableSnapshot(rows, tableId, indexes, constraints));
            }
        }
        return result;
    }

    private void assertTableNames(Connection connection, String present, String absent)
            throws SQLException {
        assertTableNames(connection, present, absent, SYSTEM_TABLES);
    }

    private void assertTableNames(
            Connection connection, String present, String absent, List<String> tables)
            throws SQLException {
        try (var sql = connection.prepareStatement("SELECT to_regclass(?),to_regclass(?)")) {
            for (String suffix : tables) {
                sql.setString(1, present + suffix);
                sql.setString(2, absent + suffix);
                try (var result = sql.executeQuery()) {
                    assertTrue(result.next());
                    assertNotNull(result.getObject(1), present + suffix);
                    assertNull(result.getObject(2), absent + suffix);
                }
            }
        }
    }

    @Test
    void systemTableRenamePreservesAllEightTablesRowsIndexesAndForeignKeys() throws Exception {
        String schema = schema();
        migrations(schema, "13").migrate();
        try (var connection = connection(schema)) {
            legacyManagementRecords(connection);
            Map<String, TableSnapshot> before = snapshot(connection, "proto_");
            migrations(schema, "14").migrate();
            assertTableNames(connection, "s_", "proto_");
            assertEquals(before, snapshot(connection, "s_"));
            try (var sql = connection.createStatement()) {
                try (var result =
                        sql.executeQuery(
                                "SELECT p.parent_id,p.org_id,o.parent_id FROM s_position p JOIN s_organization o ON o.id=p.org_id WHERE p.id='migration-position-child'")) {
                    assertTrue(result.next());
                    assertEquals("migration-position-root", result.getString(1));
                    assertEquals("migration-org-child", result.getString(2));
                    assertEquals("migration-org-root", result.getString(3));
                }
                SQLException blocked =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        sql.execute(
                                                "DELETE FROM s_organization WHERE id='migration-org-child'"));
                assertEquals("23503", blocked.getSQLState());
                SQLException duplicate =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        sql.execute(
                                                "INSERT INTO s_user_setting(id,user_id,type,setting_key,name,content,create_time) SELECT 'duplicate-setting',user_id,type,setting_key,name,content,create_time FROM s_user_setting WHERE id='migration-setting'"));
                assertEquals("23505", duplicate.getSQLState());
                sql.execute("DELETE FROM s_user WHERE id='migration-user'");
                try (var result =
                        sql.executeQuery(
                                "SELECT (SELECT count(*) FROM s_user_detail WHERE id='migration-user'),(SELECT count(*) FROM s_user_setting WHERE user_id='migration-user')")) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                    assertEquals(0, result.getInt(2));
                }
                sql.execute("DELETE FROM s_dimension WHERE id='ADMIN'");
                try (var result =
                        sql.executeQuery(
                                "SELECT count(*) FROM s_role_menu_grant WHERE id='ADMIN'")) {
                    assertTrue(result.next());
                    assertEquals(0, result.getInt(1));
                }
            }
        }
    }

    @Test
    void freshMigrationUsesSystemNamesAndRetainsTheElevenHistoricalMenuSeeds() throws Exception {
        String schema = schema();
        migrations(schema, null).migrate();
        try (var connection = connection(schema);
                var sql = connection.createStatement()) {
            assertTableNames(connection, "s_", "proto_");
            try (var result =
                    sql.executeQuery(
                            "SELECT count(*),bool_and(owner='iot'),bool_and(access_support='unsupported') FROM s_menu")) {
                assertTrue(result.next());
                assertEquals(11, result.getInt(1));
                assertTrue(result.getBoolean(2));
                assertTrue(result.getBoolean(3));
            }
            try (var result =
                    sql.executeQuery(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='14' AND success=true")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            assertTableNames(connection, "p_", "protohub_", PROTOTYPE_HUB_TABLES);
            try (var result =
                    sql.executeQuery(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='15' AND success=true")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void conflictingTargetFailsAtomicallyAndPreservesBothSourceAndTargetData() throws Exception {
        String schema = schema();
        migrations(schema, "13").migrate();
        try (var connection = connection(schema);
                var sql = connection.createStatement()) {
            legacyManagementRecords(connection);
            Map<String, TableSnapshot> before = snapshot(connection, "proto_");
            // The final rename conflicts, proving all earlier renames in V14 also roll back.
            sql.execute("CREATE TABLE s_user_setting(id varchar(64) PRIMARY KEY,note text)");
            sql.execute("INSERT INTO s_user_setting VALUES ('existing-target','Do not overwrite')");
            assertThrows(FlywayException.class, () -> migrations(schema, "14").migrate());
            assertEquals(before, snapshot(connection, "proto_"));
            for (String suffix : SYSTEM_TABLES) {
                if (suffix.equals("user_setting")) continue;
                try (var result = sql.executeQuery("SELECT to_regclass('s_" + suffix + "')")) {
                    assertTrue(result.next());
                    assertNull(result.getObject(1));
                }
            }
            try (var result = sql.executeQuery("SELECT id,note FROM s_user_setting")) {
                assertTrue(result.next());
                assertEquals("existing-target", result.getString(1));
                assertEquals("Do not overwrite", result.getString(2));
                assertFalse(result.next());
            }
            try (var result =
                    sql.executeQuery(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='14' AND success=true")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    private void legacyPrototypeRecords(Connection connection) throws SQLException {
        try (var sql = connection.createStatement()) {
            sql.execute(
                    "INSERT INTO protohub_project(id,name,description,cover,creator_id,create_time,modifier_id,modify_time) VALUES ('rename-project','Preserved project','Existing description','/existing-cover.png','existing-actor',1720000000123,'existing-modifier',1720000000456)");
            sql.execute(
                    "INSERT INTO protohub_version(id,project_id,version_name,description,storage_path,entry_file,preview_key,status,creator_id,create_time) VALUES ('rename-version','rename-project','Existing version','Existing version description','projects/rename-project/versions/rename-version/site','nested/index.html','preserved-preview','ready','existing-actor',1720000000789)");
            sql.execute(
                    "INSERT INTO protohub_share(id,project_id,version_id,token,access_key,password_hash,expires_at,enabled,creator_id,create_time) VALUES ('rename-share','rename-project','rename-version','preserved-token','preserved-access','opaque-existing-hash',1920000000123,false,'existing-actor',1720000000999)");
            sql.execute(
                    "INSERT INTO protohub_file_cleanup(id,resource_type,project_id,version_id,storage_path,create_time,next_attempt,attempts) VALUES ('rename-cleanup','version','rename-project','rename-version','projects/rename-project/versions/rename-version/site',1720000001000,1720000002000,3)");
            // V2 defines no foreign keys. Representative keys on the restored fixture verify that
            // V15 preserves installations which already enforce these relationships in PostgreSQL.
            sql.execute(
                    "ALTER TABLE protohub_version ADD CONSTRAINT rename_version_project_fk FOREIGN KEY (project_id) REFERENCES protohub_project(id)");
            sql.execute(
                    "ALTER TABLE protohub_share ADD CONSTRAINT rename_share_project_fk FOREIGN KEY (project_id) REFERENCES protohub_project(id)");
            sql.execute(
                    "ALTER TABLE protohub_share ADD CONSTRAINT rename_share_version_fk FOREIGN KEY (version_id) REFERENCES protohub_version(id)");
        }
    }

    @Test
    void prototypeTableRenamePreservesAllFourTablesDataIndexesAndExistingForeignKeys()
            throws Exception {
        String schema = schema();
        migrations(schema, "14").migrate();
        try (var connection = connection(schema)) {
            legacyPrototypeRecords(connection);
            Map<String, TableSnapshot> before =
                    snapshot(connection, "protohub_", PROTOTYPE_HUB_TABLES);
            // 本用例只验证 V15 更名；V19 分享描述字段由独立升级用例验证。
            migrations(schema, "15").migrate();
            assertTableNames(connection, "p_", "protohub_", PROTOTYPE_HUB_TABLES);
            assertEquals(before, snapshot(connection, "p_", PROTOTYPE_HUB_TABLES));
            try (var sql = connection.createStatement()) {
                try (var result =
                        sql.executeQuery(
                                "SELECT p.id,v.id,s.id,v.storage_path,v.entry_file,s.token,s.access_key,c.attempts,c.next_attempt FROM p_project p JOIN p_version v ON v.project_id=p.id JOIN p_share s ON s.project_id=p.id AND s.version_id=v.id JOIN p_file_cleanup c ON c.project_id=p.id AND c.version_id=v.id")) {
                    assertTrue(result.next());
                    assertEquals("rename-project", result.getString(1));
                    assertEquals("rename-version", result.getString(2));
                    assertEquals("rename-share", result.getString(3));
                    assertEquals(
                            "projects/rename-project/versions/rename-version/site",
                            result.getString(4));
                    assertEquals("nested/index.html", result.getString(5));
                    assertEquals("preserved-token", result.getString(6));
                    assertEquals("preserved-access", result.getString(7));
                    assertEquals(3, result.getInt(8));
                    assertEquals(1720000002000L, result.getLong(9));
                    assertFalse(result.next());
                }
                SQLException projectKey =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        sql.execute(
                                                "DELETE FROM p_project WHERE id='rename-project'"));
                assertEquals("23503", projectKey.getSQLState());
                SQLException versionKey =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        sql.execute(
                                                "DELETE FROM p_version WHERE id='rename-version'"));
                assertEquals("23503", versionKey.getSQLState());
                SQLException tokenKey =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        sql.execute(
                                                "INSERT INTO p_share(id,project_id,version_id,token,access_key,password_hash,expires_at,enabled,creator_id,create_time) SELECT 'duplicate-share',project_id,version_id,token,access_key,password_hash,expires_at,enabled,creator_id,create_time FROM p_share WHERE id='rename-share'"));
                assertEquals("23505", tokenKey.getSQLState());
                SQLException cleanupKey =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        sql.execute(
                                                "INSERT INTO p_file_cleanup SELECT * FROM p_file_cleanup WHERE id='rename-cleanup'"));
                assertEquals("23505", cleanupKey.getSQLState());
            }
        }
    }

    @Test
    void conflictingPrototypeTargetRollsBackAllFourRenamesAndPreservesBothSides() throws Exception {
        String schema = schema();
        migrations(schema, "14").migrate();
        try (var connection = connection(schema);
                var sql = connection.createStatement()) {
            legacyPrototypeRecords(connection);
            Map<String, TableSnapshot> before =
                    snapshot(connection, "protohub_", PROTOTYPE_HUB_TABLES);
            // Conflict on the last rename proves the earlier three also roll back.
            sql.execute("CREATE TABLE p_file_cleanup(id varchar(64) PRIMARY KEY,note text)");
            sql.execute("INSERT INTO p_file_cleanup VALUES ('existing-target','Do not overwrite')");
            long targetId;
            try (var result = sql.executeQuery("SELECT 'p_file_cleanup'::regclass::oid::bigint")) {
                assertTrue(result.next());
                targetId = result.getLong(1);
            }
            assertThrows(FlywayException.class, () -> migrations(schema, null).migrate());
            assertEquals(before, snapshot(connection, "protohub_", PROTOTYPE_HUB_TABLES));
            for (String suffix : PROTOTYPE_HUB_TABLES) {
                if (suffix.equals("file_cleanup")) continue;
                try (var result = sql.executeQuery("SELECT to_regclass('p_" + suffix + "')")) {
                    assertTrue(result.next());
                    assertNull(result.getObject(1));
                }
            }
            try (var result =
                    sql.executeQuery(
                            "SELECT id,note,'p_file_cleanup'::regclass::oid::bigint FROM p_file_cleanup")) {
                assertTrue(result.next());
                assertEquals("existing-target", result.getString(1));
                assertEquals("Do not overwrite", result.getString(2));
                assertEquals(targetId, result.getLong(3));
                assertFalse(result.next());
            }
            try (var result =
                    sql.executeQuery(
                            "SELECT count(*) FROM flyway_schema_history WHERE version='15' AND success=true")) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
            assertTableNames(connection, "s_", "proto_");
        }
    }
}
