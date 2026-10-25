package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.entity.MenuEntity;
import com.gaiys.proto.system.service.MenuService;
import com.gaiys.proto.system.service.MenuStartupInitializationService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the V17-to-V18 naming boundary without rewriting historical migrations or grants. */
@SpringBootTest(
        classes = ProtoBridgeApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class PrototypeHubNamingMigrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final List<String> SNAPSHOT_TABLES =
            List.of(
                    "s_permission",
                    "s_autz_setting_info",
                    "s_menu",
                    "s_role_menu_grant",
                    "s_dimension",
                    "s_dimension_user",
                    "s_user",
                    "p_project",
                    "p_version",
                    "p_share",
                    "p_file_cleanup");
    private static final Set<String> PERMISSIONS =
            Set.of("prototype-hub-project", "prototype-hub-version", "prototype-hub-share");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        registry.add("spring.r2dbc.username", DATABASE::getUsername);
        registry.add("spring.r2dbc.password", DATABASE::getPassword);
        registry.add("proto.admin.password", () -> PASSWORD);
    }

    @Autowired ObjectMapper mapper;
    @Autowired MenuService menus;
    @Autowired MenuStartupInitializationService initialization;
    @Autowired DatabaseClient database;

    @Test
    void upgradeRetainsRecordIdsChineseNamesActionsDataAccessesAndPrototypeRows() throws Exception {
        String schema = schema();
        migrations(schema, "17").migrate();
        try (var connection = connection(schema)) {
            legacyFixture(connection);
            var before = snapshot(connection);
            migrations(schema, "18").migrate();
            var after = snapshot(connection);
            assertEquals(before.keySet(), after.keySet());
            for (String table : SNAPSHOT_TABLES) {
                Set<String> mutable =
                        switch (table) {
                            case "s_permission" ->
                                    Set.of("id", "parents", "properties", "i18n_messages");
                            case "s_autz_setting_info" -> Set.of("permission");
                            case "s_menu" ->
                                    Set.of(
                                            "code",
                                            "component_code",
                                            "url",
                                            "permission",
                                            "permissions",
                                            "buttons",
                                            "options",
                                            "i18n_messages");
                            case "s_role_menu_grant" -> Set.of("managed_permissions");
                            default -> Set.of();
                        };
                assertEquals(
                        protectedRows(before.get(table), mutable),
                        protectedRows(after.get(table), mutable),
                        table);
            }
            var permissions = after.get("s_permission");
            for (String kind : List.of("project", "version", "share")) {
                assertFalse(permissions.containsKey("protohub-" + kind));
                assertTrue(permissions.containsKey("prototype-hub-" + kind));
            }
            assertEquals("原型项目管理", permissions.get("prototype-hub-project").path("name").asText());
            assertEquals("手工版本管理", permissions.get("prototype-hub-version").path("name").asText());
            var parent = permissions.get("vendor-extra");
            assertEquals(
                    mapper.readTree("[\"prototype-hub-project\",\"vendor-extra\"]"),
                    json(parent, "parents"));
            assertEquals(
                    "prototype-hub-share",
                    json(parent, "properties").path("resources").get(0).path("id").asText());
            assertEquals("protohub-project", json(parent, "properties").path("note").asText());
            assertEquals("边界不变", parent.path("name").asText());
            assertEquals(
                    "protohub-share",
                    json(parent, "i18n_messages").path("name").path("en").asText());
            assertTrue(
                    json(parent, "i18n_messages")
                            .has("hswebframework.web.system.permission.prototype-hub-share"));
            var settings = after.get("s_autz_setting_info");
            assertEquals(
                    "prototype-hub-project",
                    settings.get("manual-authorization").path("permission").asText());
            assertEquals(
                    before.get("s_autz_setting_info")
                            .get("manual-authorization")
                            .path("data_accesses"),
                    settings.get("manual-authorization").path("data_accesses"));
            assertEquals(
                    "vendor-extra",
                    settings.get("extra-authorization").path("permission").asText());
            var menu = after.get("s_menu").get("protohub");
            assertEquals("prototype-hub/Project", menu.path("code").asText());
            assertEquals("prototype-hub/Project", menu.path("component_code").asText());
            assertEquals("/prototype-hub", menu.path("url").asText());
            assertEquals("prototype-hub-project", menu.path("permission").asText());
            assertEquals(
                    "prototype-hub-version", json(menu, "permissions").get(0).path("id").asText());
            var button = json(menu, "buttons").get(0);
            assertEquals(
                    "prototype-hub-share",
                    button.path("permissions").get(0).path("permission").asText());
            assertEquals("保存定制名称", button.path("name").asText());
            assertEquals("protohub-share", button.path("i18nMessages").path("en").asText());
            assertEquals(
                    "prototype-hub/Project", button.path("options").path("componentCode").asText());
            assertEquals("prototypeHub.share", button.path("options").path("i18nKey").asText());
            var options = json(menu, "options");
            assertEquals("prototype-hub", options.path("meta").path("parentCode").asText());
            assertEquals(
                    "/prototype-hub/project?mode=readonly#top",
                    options.path("nested").get(0).path("url").asText());
            assertEquals(
                    "https://outside.example/protohub/project",
                    options.path("prototypeurl").asText());
            assertEquals("protohub-project", options.path("note").asText());
            assertTrue(json(menu, "i18n_messages").has("prototypeHub.project"));
            assertEquals("自定义中文名称", menu.path("name").asText());
            assertEquals(
                    "prototype-hub/Custom",
                    after.get("s_menu").get("child-menu").path("code").asText());
            assertEquals(
                    "/prototype-hub/custom",
                    after.get("s_menu").get("child-menu").path("url").asText());
            assertEquals(
                    "protohub/Project-child",
                    after.get("s_menu").get("child-menu").path("path").asText());
            assertEquals(
                    "protohub", after.get("s_menu").get("child-menu").path("parent_id").asText());
            var grants = after.get("s_role_menu_grant").get("CUSTOM");
            assertEquals(
                    mapper.readTree("{\"protohub\":[\"save\"],\"child-menu\":[\"vendor\"]}"),
                    json(grants, "menus"));
            assertEquals(
                    mapper.readTree(
                            "[\"prototype-hub-project\",\"prototype-hub-share\",\"vendor-extra\"]"),
                    json(grants, "managed_permissions"));
            assertEquals(
                    before.get("s_menu").get("unrelated-menu"),
                    after.get("s_menu").get("unrelated-menu"));
            assertEquals(0, migrations(schema, "18").migrate().migrationsExecuted);
            assertEquals(after, snapshot(connection));
            assertEquals(
                    1,
                    count(
                            connection,
                            "SELECT count(*) FROM flyway_schema_history WHERE version='18' AND success=true"));
        }
    }

    @Test
    void freshDatabaseHasOneNewPermissionModelAndPreservesTheHistoricalMenuId() throws Exception {
        String schema = schema();
        migrations(schema, "18").migrate();
        try (var connection = connection(schema)) {
            assertEquals(
                    3,
                    count(
                            connection,
                            "SELECT count(*) FROM s_permission WHERE id IN ('prototype-hub-project','prototype-hub-version','prototype-hub-share')"));
            assertEquals(
                    0,
                    count(
                            connection,
                            "SELECT count(*) FROM s_permission WHERE id IN ('protohub-project','protohub-version','protohub-share')"));
            assertEquals(
                    0,
                    count(
                            connection,
                            "SELECT count(*) FROM s_autz_setting_info WHERE permission IN ('protohub-project','protohub-version','protohub-share')"));
            assertEquals(
                    6,
                    count(
                            connection,
                            "SELECT count(*) FROM s_autz_setting_info WHERE permission IN ('prototype-hub-project','prototype-hub-version','prototype-hub-share')"));
            assertEquals(
                    1,
                    count(
                            connection,
                            "SELECT count(*) FROM s_menu WHERE id='protohub' AND code='prototype-hub/Project' AND url='/prototype-hub' AND permission='prototype-hub-project'"));
            var before = snapshot(connection);
            assertEquals(0, migrations(schema, "18").migrate().migrationsExecuted);
            assertEquals(before, snapshot(connection));
        }
    }

    @Test
    void existingNewPermissionFailsWithoutOverwritingEitherSide() throws Exception {
        assertConflict(
                "INSERT INTO s_permission(id,name,status,actions) VALUES ('prototype-hub-share','独立新权限',0,'[{\"action\":\"query\",\"name\":\"自定义\"}]')",
                "permission identifier conflict");
    }

    @Test
    void existingNewAuthorizationTupleFailsWithoutMergingDataBoundaries() throws Exception {
        assertConflict(
                "INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,data_accesses,priority,merge) VALUES ('new-authorization','prototype-hub-project','role','ADMIN',0,'[\"save\"]','[{\"type\":\"CUSTOM\",\"config\":{\"scope\":\"new-only\"}}]',30,false)",
                "authorization tuple conflict");
    }

    @Test
    void existingNewMenuCodeFailsWithoutDiscardingEitherMenu() throws Exception {
        assertConflict(
                "INSERT INTO s_menu(id,name,code,enabled,owner,access_support,path,sort_index,_level) VALUES ('new-menu','独立新菜单','prototype-hub/Project',false,'custom','unsupported','new-tree',8,1)",
                "menu code conflict");
    }

    @Test
    void nestedMetadataKeyConflictRollsBackEarlierPermissionAndMenuUpdates() throws Exception {
        assertConflict(
                "UPDATE s_menu SET options='{\"nested\":{\"protohub.project\":\"old metadata\",\"prototypeHub.project\":\"new metadata\"}}' WHERE id='protohub'",
                "JSON key conflict");
    }

    @Test
    void emptyMenusInitializeTheNewSeedWithStableIdsAndCompleteButtonGrants() {
        database.sql("DELETE FROM s_menu").fetch().rowsUpdated().block(TIMEOUT);
        assertTrue(Boolean.TRUE.equals(initialization.initializeIfEmpty().block(TIMEOUT)));
        Map<String, MenuEntity> actual =
                menus.createQuery().fetch().collectMap(MenuEntity::getCode).block(TIMEOUT);
        assertNotNull(actual);
        assertEquals(15, actual.size());
        assertTrue(actual.containsKey("prototype-hub"));
        MenuEntity project = actual.get("prototype-hub/Project");
        assertNotNull(project);
        assertEquals("protohub", project.getId());
        assertEquals("/prototype-hub", project.getUrl());
        assertEquals(actual.get("prototype-hub").getId(), project.getParentId());
        assertEquals("原型项目", project.getName());
        assertEquals("Prototype Projects", project.getI18nMessages().get("name").get("en_US"));
        assertEquals(39, actual.values().stream().mapToInt(menu -> menu.getButtons().size()).sum());
        assertEquals("/prototype-hub/recycle-bin", actual.get("prototype-hub/Recycle").getUrl());
        assertEquals(3, project.getButtons().size());
        for (Map<String, Object> button : project.getButtons()) {
            JsonNode value = mapper.valueToTree(button);
            Set<String> resources = new java.util.LinkedHashSet<>();
            for (JsonNode permission : value.path("permissions")) {
                resources.add(permission.path("permission").asText());
                assertEquals(
                        button.get("id").equals("delete") ? "delete" : "save",
                        permission.path("actions").get(0).asText());
            }
            assertEquals(PERMISSIONS, resources);
        }
        assertFalse(Boolean.TRUE.equals(initialization.initializeIfEmpty().block(TIMEOUT)));
        assertEquals(15, menus.createQuery().count().block(TIMEOUT));
    }

    private void assertConflict(String conflictSql, String expectedMessage) throws Exception {
        String schema = schema();
        migrations(schema, "17").migrate();
        try (var connection = connection(schema);
                var sql = connection.createStatement()) {
            legacyFixture(connection);
            sql.execute(conflictSql);
            var before = snapshot(connection);
            FlywayException error =
                    assertThrows(FlywayException.class, () -> migrations(schema, "18").migrate());
            assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
            assertEquals(before, snapshot(connection));
            assertEquals(
                    0,
                    count(
                            connection,
                            "SELECT count(*) FROM flyway_schema_history WHERE version='18' AND success=true"));
        }
    }

    private void legacyFixture(Connection connection) throws Exception {
        try (var sql = connection.createStatement()) {
            sql.execute(
                    "INSERT INTO s_dimension(id,type_id,name,path,sort_index,_level,properties) VALUES ('CUSTOM','role','定制角色','custom-role',7,1,'{\"retain\":true}')");
            sql.execute(
                    "INSERT INTO s_user(id,name,username,password,salt,status,create_time) VALUES ('custom-user','定制用户','custom-user','opaque-hash','opaque-salt',1,1710000000000)");
            sql.execute(
                    "INSERT INTO s_dimension_user(id,dimension_type_id,dimension_id,dimension_name,user_id,user_name,relation,relation_name,relation_time) VALUES ('custom-member','role','CUSTOM','定制角色','custom-user','定制用户','custom','定制关系',1710000000001)");
            sql.execute(
                    "UPDATE s_permission SET name='手工版本管理',status=0,actions='[{\"action\":\"query\",\"name\":\"自定义查看\",\"vendor\":true}]' WHERE id='protohub-version'");
            sql.execute(
                    "INSERT INTO s_permission(id,name,status,actions,parents,properties,i18n_messages) VALUES ('vendor-extra','边界不变',0,'[{\"action\":\"vendor\",\"name\":\"手工操作\"}]','[\"protohub-project\",\"vendor-extra\"]','{\"resources\":[{\"id\":\"protohub-share\"}],\"note\":\"protohub-project\"}','{\"name\":{\"zh_CN\":\"边界不变\",\"en\":\"protohub-share\"},\"hswebframework.web.system.permission.protohub-share\":{\"en\":\"Share label\"}}')");
            sql.execute(
                    "INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,data_accesses,priority,merge) VALUES ('manual-authorization','protohub-project','role','CUSTOM',0,'[\"query\",\"vendor\"]','[{\"type\":\"CUSTOM\",\"config\":{\"scope\":\"protohub-project\",\"url\":\"/protohub/raw\"}}]',17,false)");
            sql.execute(
                    "INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,data_accesses,priority,merge) VALUES ('extra-authorization','vendor-extra','role','CUSTOM',1,'[\"vendor\"]','[{\"type\":\"OWN_CREATED\",\"config\":{\"creatorId\":\"custom-user\"}}]',21,true)");
            sql.execute(
                    "UPDATE s_menu SET name='自定义中文名称',permissions='[{\"id\":\"protohub-version\",\"actions\":[\"query\"]},{\"permission\":\"vendor-extra\",\"actions\":[\"vendor\"]}]',buttons='[{\"id\":\"save\",\"name\":\"保存定制名称\",\"permissions\":[{\"permission\":\"protohub-share\",\"actions\":[\"save\"]}],\"options\":{\"componentCode\":\"protohub/Project\",\"i18nKey\":\"protohub.share\"},\"i18nMessages\":{\"zh_CN\":\"保存定制名称\",\"en\":\"protohub-share\"}}]',options='{\"meta\":{\"parentCode\":\"protohub\",\"componentCode\":\"protohub/Project\"},\"nested\":[{\"url\":\"/protohub/project?mode=readonly#top\"}],\"prototypeurl\":\"https://outside.example/protohub/project\",\"note\":\"protohub-project\"}',i18n_messages='{\"name\":{\"zh_CN\":\"自定义中文名称\",\"en\":\"Existing Projects\"},\"protohub.project\":{\"zh_CN\":\"定制翻译\",\"en\":\"Custom Translation\"}}' WHERE id='protohub'");
            sql.execute(
                    "INSERT INTO s_menu(id,parent_id,name,code,url,enabled,owner,access_support,path,sort_index,_level,buttons) VALUES ('child-menu','protohub','定制子菜单','protohub/Custom','/protohub/custom',false,'custom','unsupported','protohub/Project-child',9,2,'[]')");
            sql.execute(
                    "INSERT INTO s_menu(id,name,code,url,enabled,owner,access_support,path,sort_index,_level,options) VALUES ('unrelated-menu','protohub-project','vendor/protohub','https://outside.example/protohub',true,'custom','unsupported','vendor-tree',12,1,'{\"note\":\"protohub-project\"}')");
            sql.execute(
                    "INSERT INTO s_role_menu_grant(id,menus,managed_permissions) VALUES ('CUSTOM','{\"protohub\":[\"save\"],\"child-menu\":[\"vendor\"]}','[\"protohub-project\",\"protohub-share\",\"vendor-extra\"]')");
            sql.execute(
                    "INSERT INTO p_project(id,name,creator_id,create_time,modifier_id,modify_time) VALUES ('preserved-project','原型数据','custom-user',1710000000000,'custom-user',1710000000001)");
            sql.execute(
                    "INSERT INTO p_version(id,project_id,version_name,storage_path,entry_file,preview_key,status,creator_id,create_time) VALUES ('preserved-version','preserved-project','版本一','projects/preserved-project/versions/preserved-version/site','nested/index.html','preserved-preview','ready','custom-user',1710000000002)");
            sql.execute(
                    "INSERT INTO p_share(id,project_id,version_id,token,access_key,password_hash,enabled,creator_id,create_time) VALUES ('preserved-share','preserved-project','preserved-version','retained-token','retained-key','opaque-bcrypt',true,'custom-user',1710000000003)");
            sql.execute(
                    "INSERT INTO p_file_cleanup(id,resource_type,project_id,version_id,storage_path,create_time,next_attempt,attempts) VALUES ('preserved-cleanup','version','preserved-project','preserved-version','projects/preserved-project/versions/preserved-version/site',1710000000004,1710000000005,2)");
        }
    }

    private Flyway migrations(String schema, String version) {
        return Flyway.configure()
                .locations("classpath:test-db/migration")
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(version)
                .load();
    }

    private Connection connection(String schema) throws Exception {
        var connection =
                DriverManager.getConnection(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
        connection.setSchema(schema);
        return connection;
    }

    private String schema() {
        return "prototype_hub_names_" + UUID.randomUUID().toString().replace("-", "");
    }

    private JsonNode json(JsonNode row, String field) throws Exception {
        return mapper.readTree(row.path(field).asText());
    }

    private Map<String, Map<String, JsonNode>> snapshot(Connection connection) throws Exception {
        Map<String, Map<String, JsonNode>> tables = new LinkedHashMap<>();
        for (String table : SNAPSHOT_TABLES) {
            Map<String, JsonNode> rows = new LinkedHashMap<>();
            try (var sql = connection.createStatement();
                    var result =
                            sql.executeQuery(
                                    "SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY id")) {
                while (result.next()) {
                    JsonNode value = mapper.readTree(result.getString(1));
                    rows.put(value.path("id").asText(), value);
                }
            }
            tables.put(table, rows);
        }
        return tables;
    }

    private Map<String, JsonNode> protectedRows(Map<String, JsonNode> rows, Set<String> mutable) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        rows.forEach(
                (id, row) -> {
                    var copy = row.deepCopy();
                    mutable.forEach(
                            field ->
                                    ((com.fasterxml.jackson.databind.node.ObjectNode) copy)
                                            .remove(field));
                    result.put(
                            mutable.contains("id") && id.startsWith("protohub-")
                                    ? "prototype-hub-" + id.substring(9)
                                    : id,
                            copy);
                });
        return result;
    }

    private int count(Connection connection, String query) throws Exception {
        try (var sql = connection.createStatement();
                var rows = sql.executeQuery(query)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
