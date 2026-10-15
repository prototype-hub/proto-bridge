package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MenuChineseNamesMigrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Map<String, String> CHINESE =
            Map.ofEntries(
                    Map.entry("protohub", "原型项目"),
                    Map.entry("system-basis", "基础配置"),
                    Map.entry("system-user", "用户管理"),
                    Map.entry("system-org", "组织管理"),
                    Map.entry("system-role", "角色管理"),
                    Map.entry("system-menu", "菜单管理"),
                    Map.entry("system-permission", "权限管理"),
                    Map.entry("system-dictionary", "数据字典"),
                    Map.entry("system-position", "职位管理"),
                    Map.entry("system-access-log", "访问日志"),
                    Map.entry("system-logs", "系统日志"));

    @Test
    void historicalEnglishSeedsGainChineseNamesAndBothEnglishAliasesWithoutReplacingRows()
            throws Exception {
        String schema = schema();
        migrations(schema, "16").migrate();
        try (var connection = connection(schema)) {
            var before = protectedMenuFields(connection);
            var grants = authorizationSnapshot(connection);
            migrations(schema, "17").migrate();
            var actual = menuRows(connection);
            assertEquals(CHINESE.keySet(), actual.keySet());
            assertEquals(before, protectedMenuFields(connection));
            assertEquals(grants, authorizationSnapshot(connection));
            for (var entry : actual.entrySet()) {
                JsonNode row = entry.getValue();
                assertEquals(CHINESE.get(entry.getKey()), row.path("name").asText());
                var translations = mapper.readTree(row.path("i18n_messages").asText()).path("name");
                assertEquals(row.path("name").asText(), translations.path("zh_CN").asText());
                assertFalse(translations.path("en_US").asText().isBlank());
                assertFalse(translations.path("en").asText().isBlank());
                assertEquals(translations.path("en_US"), translations.path("en"));
                if (!row.path("buttons").isNull()) {
                    for (var button : mapper.readTree(row.path("buttons").asText())) {
                        assertTrue(
                                button.path("name")
                                        .asText()
                                        .codePoints()
                                        .anyMatch(code -> code >= 0x4e00 && code <= 0x9fff));
                        assertEquals(
                                button.path("name"), button.path("i18nMessages").path("zh_CN"));
                        assertFalse(button.path("i18nMessages").path("en_US").asText().isBlank());
                        assertFalse(button.path("i18nMessages").path("en").asText().isBlank());
                    }
                }
            }
            assertEquals(
                    "Prototype Projects",
                    mapper.readTree(actual.get("protohub").path("i18n_messages").asText())
                            .path("name")
                            .path("en_US")
                            .asText());
            assertEquals(
                    "调整日志级别",
                    mapper.readTree(actual.get("system-logs").path("buttons").asText())
                            .get(0)
                            .path("name")
                            .asText());
        }
    }

    @Test
    void existingCustomNamesTranslationsButtonFieldsHierarchyAndAuthorizationRemainIntact()
            throws Exception {
        String schema = schema();
        migrations(schema, "16").migrate();
        try (var connection = connection(schema);
                var sql = connection.createStatement()) {
            sql.execute(
                    """
                    UPDATE s_menu SET i18n_messages='{"name":{"zh_CN":"成员定制","en_US":"Existing Members","en":"Members","fr":"Membres"},"describe":{"en":"Existing description"}}',
                    buttons='[{"id":"add","name":"add","i18nMessages":{"zh_CN":"新增成员","en_US":"Create Member","en":"Create","fr":"Créer"},"permissions":[{"permission":"user","actions":["save"]}],"options":{"confirm":true}},{"id":"update","name":"修改实名信息","hint":"custom action"},{"id":"vendor","name":"Vendor action","options":{"vendor":true}}]'
                    WHERE id='system-user'
                    """);
            sql.execute(
                    "UPDATE s_menu SET name='Team Roles',i18n_messages='{\"name\":{\"en\":\"Custom Roles\"},\"describe\":{\"en\":\"Unchanged\"}}' WHERE id='system-role'");
            sql.execute(
                    "UPDATE s_menu SET name='菜单管理',i18n_messages='{\"name\":{\"zh_CN\":\"菜单管理\",\"en_US\":\" \",\"fr\":\"Menus\"},\"describe\":{\"en\":\"Retained\"}}' WHERE id='system-menu'");
            sql.execute("UPDATE s_menu SET code=NULL WHERE id='system-basis'");
            sql.execute("UPDATE s_menu SET i18n_messages=NULL WHERE id='system-access-log'");
            sql.execute(
                    "UPDATE s_menu SET owner='other-owner',enabled=false,parent_id='system-role',path='0104-child',_level=2 WHERE id='system-position'");
            sql.execute(
                    "INSERT INTO s_menu(id,name,code,enabled,owner,access_support,path,sort_index,_level,i18n_messages) VALUES ('custom-menu','Menus','custom/menu',true,'iot','unsupported','custom-root',30,1,'{\"name\":{\"en\":\"Custom Menu\"}}')");
            sql.execute(
                    "INSERT INTO s_role_menu_grant(id,menus,managed_permissions) VALUES ('ADMIN','{\"system-user\":[\"add\"]}','[\"user\"]')");
            var before = menuRows(connection);
            var protectedFields = protectedMenuFields(connection);
            var authorization = authorizationSnapshot(connection);
            var roleMenus = roleMenuSnapshot(connection);
            migrations(schema, "17").migrate();
            var after = menuRows(connection);
            assertEquals(before.keySet(), after.keySet());
            assertEquals(protectedFields, protectedMenuFields(connection));
            assertEquals(authorization, authorizationSnapshot(connection));
            assertEquals(roleMenus, roleMenuSnapshot(connection));
            for (String id : new String[] {"system-role", "system-position", "custom-menu"})
                assertEquals(before.get(id), after.get(id), id);
            assertEquals("成员定制", after.get("system-user").path("name").asText());
            assertEquals(
                    mapper.readTree(before.get("system-user").path("i18n_messages").asText()),
                    mapper.readTree(after.get("system-user").path("i18n_messages").asText()));
            var oldButtons = mapper.readTree(before.get("system-user").path("buttons").asText());
            var newButtons = mapper.readTree(after.get("system-user").path("buttons").asText());
            assertEquals(oldButtons.size(), newButtons.size());
            assertEquals("新增成员", newButtons.get(0).path("name").asText());
            assertEquals(
                    oldButtons.get(0).path("i18nMessages"), newButtons.get(0).path("i18nMessages"));
            assertEquals(
                    oldButtons.get(0).path("permissions"), newButtons.get(0).path("permissions"));
            assertEquals(oldButtons.get(0).path("options"), newButtons.get(0).path("options"));
            assertEquals(oldButtons.get(1), newButtons.get(1));
            assertEquals(oldButtons.get(2), newButtons.get(2));
            var menuMessages =
                    mapper.readTree(after.get("system-menu").path("i18n_messages").asText());
            assertEquals("Menu Management", menuMessages.path("name").path("en_US").asText());
            assertEquals("Menu Management", menuMessages.path("name").path("en").asText());
            assertEquals("Menus", menuMessages.path("name").path("fr").asText());
            assertEquals("Retained", menuMessages.path("describe").path("en").asText());
            assertEquals("基础配置", after.get("system-basis").path("name").asText());
            assertTrue(after.get("system-basis").path("code").isNull());
            assertEquals(
                    "Access Logs",
                    mapper.readTree(after.get("system-access-log").path("i18n_messages").asText())
                            .path("name")
                            .path("en")
                            .asText());
        }
    }

    private Flyway migrations(String schema, String target) {
        return Flyway.configure()
                .locations("classpath:test-db/migration")
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(target)
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
        return "menu_names_" + UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, JsonNode> menuRows(Connection connection) throws Exception {
        return rows(connection, "SELECT to_jsonb(m)::text FROM s_menu m ORDER BY id");
    }

    private Map<String, JsonNode> protectedMenuFields(Connection connection) throws Exception {
        return rows(
                connection,
                "SELECT (to_jsonb(m)-'name'-'i18n_messages'-'buttons')::text FROM s_menu m ORDER BY id");
    }

    private Map<String, JsonNode> authorizationSnapshot(Connection connection) throws Exception {
        return rows(connection, "SELECT to_jsonb(s)::text FROM s_autz_setting_info s ORDER BY id");
    }

    private Map<String, JsonNode> roleMenuSnapshot(Connection connection) throws Exception {
        return rows(connection, "SELECT to_jsonb(s)::text FROM s_role_menu_grant s ORDER BY id");
    }

    private Map<String, JsonNode> rows(Connection connection, String query) throws Exception {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        try (var sql = connection.createStatement();
                var result = sql.executeQuery(query)) {
            while (result.next()) {
                var row = mapper.readTree(result.getString(1));
                assertNotNull(row);
                rows.put(row.path("id").asText(), row);
            }
        }
        return rows;
    }
}
