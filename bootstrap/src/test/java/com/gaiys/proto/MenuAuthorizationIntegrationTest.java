package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Exercises menu grants through the same HTTP and hsweb authorization boundaries as the UI. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class MenuAuthorizationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        r.add("spring.r2dbc.username", DATABASE::getUsername);
        r.add("spring.r2dbc.password", DATABASE::getPassword);
        r.add("proto.admin.password", () -> PASSWORD);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    String admin;

    @BeforeEach
    void authenticate() {
        client = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        admin = login("admin");
    }

    String key() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    JsonNode call(String method, String path, String token, Object body, int status) {
        var request = client.method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.bodyValue(body);
        JsonNode response =
                request.exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(response);
        return response.path("result");
    }

    String login(String username) {
        return call(
                        "POST",
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", PASSWORD),
                        200)
                .path("token")
                .asText();
    }

    String role() {
        String id = key();
        call("POST", "/role", admin, Map.of("id", id, "name", "Role " + id), 200);
        return id;
    }

    String user(String username, List<String> roles) {
        String id =
                call(
                                "POST",
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Menu user",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD),
                                200)
                        .path("id")
                        .asText();
        for (String role : roles)
            call("POST", "/role/" + role + "/users/_bind", admin, List.of(id), 200);
        return id;
    }

    Map<String, Object> selection(String menu, String... buttons) {
        return Map.of(
                "id",
                menu,
                "buttons",
                Arrays.stream(buttons).map(id -> Map.of("id", id, "granted", true)).toList());
    }

    void grant(String role, List<Map<String, Object>> menus) {
        call("PUT", "/menu/role/" + role + "/_grant", admin, Map.of("menus", menus), 200);
    }

    void state(String role, String state) {
        call(
                "PATCH",
                "/role",
                admin,
                Map.of("id", role, "name", "Role " + role, "state", state),
                200);
    }

    JsonNode own(String token) {
        return call("POST", "/menu/user-own/tree", token, Map.of(), 200);
    }

    JsonNode find(JsonNode rows, String id) {
        for (JsonNode row : rows) {
            if (id.equals(row.path("id").asText())) return row;
            JsonNode nested = find(row.path("children"), id);
            if (!nested.isMissingNode()) return nested;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    @Test
    void differentRolesCombineActionsAndRevokingOneRetainsTheOther() {
        String writer = role(), deleter = role();
        grant(writer, List.of(selection("system-dictionary", "add")));
        grant(deleter, List.of(selection("system-dictionary", "delete")));
        String username = "multi-" + key();
        user(username, List.of(writer, deleter));
        String token = login(username);
        String dictionary = key();
        call(
                "PATCH",
                "/dictionary",
                token,
                Map.of("id", dictionary, "name", "Joint grant", "status", 1),
                200);
        call("POST", "/dictionary/_query", token, Map.of(), 200);
        call("DELETE", "/dictionary/" + dictionary, token, null, 200);
        JsonNode menu = find(own(token), "system-dictionary");
        assertTrue(find(menu.path("buttons"), "add").path("granted").asBoolean());
        assertTrue(find(menu.path("buttons"), "delete").path("granted").asBoolean());

        grant(writer, List.of());
        call(
                "PATCH",
                "/dictionary",
                token,
                Map.of("id", key(), "name", "Denied", "status", 1),
                403);
        call("POST", "/dictionary/_query", token, Map.of(), 200);
        menu = find(own(token), "system-dictionary");
        assertFalse(find(menu.path("buttons"), "add").path("granted").asBoolean());
        assertTrue(find(menu.path("buttons"), "delete").path("granted").asBoolean());
    }

    @Test
    void disabledRolesDoNotContributeMenusButtonsOrLegacyFallback() {
        String creator = role(), updater = role(), legacy = role();
        String customMenu = key();
        call(
                "POST",
                "/menu",
                admin,
                Map.of(
                        "id",
                        customMenu,
                        "name",
                        "Creator menu",
                        "code",
                        customMenu,
                        "permission",
                        "dictionary",
                        "action",
                        "query",
                        "enabled",
                        true,
                        "buttons",
                        List.of(Map.of("id", "add", "name", "Add"))),
                200);
        grant(
                creator,
                List.of(selection("system-dictionary", "add"), selection(customMenu, "add")));
        grant(updater, List.of(selection("system-dictionary", "update")));
        state(legacy, "disabled");
        String username = "disabled-" + key();
        user(username, List.of(creator, updater, legacy));
        String token = login(username);
        assertFalse(find(own(token), customMenu).isMissingNode());

        state(creator, "disabled");
        JsonNode menus = own(token);
        assertTrue(find(menus, customMenu).isMissingNode());
        JsonNode dictionary = find(menus, "system-dictionary");
        assertFalse(dictionary.isMissingNode());
        assertFalse(find(dictionary.path("buttons"), "add").path("granted").asBoolean());
        assertTrue(find(dictionary.path("buttons"), "update").path("granted").asBoolean());
        call(
                "PATCH",
                "/dictionary",
                token,
                Map.of("id", key(), "name", "Updater retains save", "status", 1),
                200);
    }

    @Test
    void allDisabledAndUnboundUsersHaveNoPersonalMenusOrManagementActions() {
        String role = role();
        grant(role, List.of(selection("system-dictionary", "add")));
        String username = "all-disabled-" + key();
        user(username, List.of(role));
        String token = login(username);
        assertFalse(own(token).isEmpty());
        state(role, "disabled");
        assertTrue(own(token).isEmpty());
        call("POST", "/dictionary/_query", token, Map.of(), 403);
        String unbound = "no-role-" + key();
        user(unbound, List.of());
        String unboundToken = login(unbound);
        assertTrue(own(unboundToken).isEmpty());
        call("POST", "/dictionary/_query", unboundToken, Map.of(), 403);
    }

    @Test
    void editingMenuActionsRetainsExistingDataBoundaryIdAndExplicitPriority() throws Exception {
        String role = role(), authorization = key();
        String access =
                "[{\"action\":\"query\",\"type\":\"dimension\",\"describe\":\"Organization scope\",\"config\":{\"dimensionType\":\"org\"}}]";
        database.sql(
                        "INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,data_accesses,priority,merge) VALUES (:id,'dictionary','role',:role,1,'[\"query\"]',:access,50,false)")
                .bind("id", authorization)
                .bind("role", role)
                .bind("access", access)
                .fetch()
                .rowsUpdated()
                .block();
        grant(role, List.of(selection("system-dictionary", "add")));
        Map<String, Object> retained =
                database.sql(
                                "SELECT id,data_accesses,priority,merge FROM s_autz_setting_info WHERE dimension_type='role' AND dimension_target=:role AND permission='dictionary'")
                        .bind("role", role)
                        .fetch()
                        .one()
                        .block();
        assertNotNull(retained);
        assertEquals(authorization, retained.get("id"));
        assertEquals(
                mapper.readTree(access), mapper.readTree(retained.get("data_accesses").toString()));
        assertEquals(50, retained.get("priority"));
        assertEquals(true, retained.get("merge"));
    }

    @Test
    void upgradeChangesOnlyEnabledResourcesManagedByMenuGrants() throws Exception {
        String schema = "menu_upgrade_" + key();
        Flyway.configure()
                .locations("classpath:test-db/migration")
                .dataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target("10")
                .load()
                .migrate();
        try (var connection =
                        DriverManager.getConnection(
                                DATABASE.getJdbcUrl(),
                                DATABASE.getUsername(),
                                DATABASE.getPassword());
                var sql = connection.createStatement()) {
            connection.setSchema(schema);
            sql.execute(
                    "INSERT INTO s_dimension(id,type_id,name) VALUES ('managed-upgrade','role','Managed'),('manual-upgrade','role','Manual')");
            sql.execute(
                    "INSERT INTO proto_role_menu_grant(id,menus,managed_permissions) VALUES ('managed-upgrade','{}','[\"dictionary\",\"position\"]')");
            sql.execute(
                    "INSERT INTO s_autz_setting_info(id,permission,dimension_type,dimension_target,state,actions,data_accesses,priority,merge) VALUES ('managed-setting','dictionary','role','managed-upgrade',1,'[\"query\"]','[]',0,false),('inactive-setting','position','role','managed-upgrade',0,'[\"query\"]','[]',0,false),('unlisted-setting','user','role','managed-upgrade',1,'[\"query\"]','[]',50,false),('manual-setting','dictionary','role','manual-upgrade',1,'[\"query\"]','[]',50,false)");
            Flyway.configure()
                    .locations("classpath:test-db/migration")
                    .dataSource(
                            DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load()
                    .migrate();
            try (var rows =
                    sql.executeQuery(
                            "SELECT id,priority,merge,data_accesses FROM s_autz_setting_info WHERE id IN ('managed-setting','inactive-setting','unlisted-setting','manual-setting')")) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    boolean managed = "managed-setting".equals(rows.getString("id"));
                    assertEquals(managed, rows.getBoolean("merge"));
                    assertEquals(
                            managed
                                    ? 10
                                    : ("inactive-setting".equals(rows.getString("id")) ? 0 : 50),
                            rows.getInt("priority"));
                    assertEquals("[]", rows.getString("data_accesses"));
                }
                assertEquals(4, count);
            }
            try (var rows =
                    sql.executeQuery(
                            "SELECT priority,merge FROM s_autz_setting_info WHERE id='v7-dictionary-admin'")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt("priority"));
                assertFalse(rows.getBoolean("merge"));
            }
        }
    }
}
