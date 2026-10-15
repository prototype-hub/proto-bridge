package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class MenuCompatibilityIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String PASSWORD = UUID.randomUUID().toString();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
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

    @Autowired WebTestClient client;
    @Autowired org.springframework.r2dbc.core.DatabaseClient database;
    String admin;

    @BeforeEach
    void authenticate() {
        client = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        admin = login("admin");
    }

    String key() {
        return UUID.randomUUID().toString().replace("-", "");
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

    String user(String role) {
        String username = "menu-" + key();
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
                        PASSWORD,
                        "roleIdList",
                        role == null ? List.of() : List.of(role)),
                200);
        return login(username);
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

    Map<String, Object> menu(String id, String owner, String permission, List<?> buttons) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", "Menu " + id);
        result.put("code", "menu-" + id);
        result.put("owner", owner);
        if (permission != null) result.put("permission", permission);
        result.put("action", "query");
        result.put("buttons", buttons);
        return result;
    }

    void grant(String role, String menu, String other, List<String> buttons) {
        call(
                "PUT",
                "/menu/role/" + role + "/_grant",
                admin,
                Map.of(
                        "menus",
                        List.of(
                                Map.of(
                                        "id",
                                        menu,
                                        "buttons",
                                        buttons.stream().map(id -> Map.of("id", id)).toList()),
                                Map.of("id", other, "buttons", List.of()))),
                200);
    }

    void createAs(String token, int status) {
        call(
                "POST",
                "/user/detail/_create",
                token,
                Map.of(
                        "username",
                        "created-" + key(),
                        "name",
                        "Created user",
                        "password",
                        PASSWORD),
                status);
    }

    @Test
    void advancedMetadataPersistsAndGenericCrudEnforcesOwnerAndReferences() {
        String id = key(), owner = "owner-" + key(), indirect = key();
        Map<String, Object> input = menu(id, owner, null, List.of());
        input.put("describe", "Description");
        input.put("accessSupport", "support");
        input.put("assetType", "legacy-user");
        input.put("options", Map.of("show", false));
        call("POST", "/menu", admin, input, 200);
        JsonNode loaded = call("GET", "/menu/" + id, admin, null, 200);
        assertEquals("Description", loaded.path("describe").asText());
        assertEquals("support", loaded.path("accessSupport").asText());
        assertEquals("legacy-user", loaded.path("assetType").asText());
        assertFalse(loaded.path("options").path("show").asBoolean());
        Map<String, Object> linked = menu(indirect, owner, null, List.of());
        linked.put("accessSupport", "indirect");
        linked.put("indirectMenus", List.of(id));
        call("POST", "/menu", admin, linked, 200);
        assertEquals(
                id,
                call("GET", "/menu/" + indirect, admin, null, 200)
                        .path("indirectMenus")
                        .get(0)
                        .asText());
        call(
                "PATCH",
                "/menu",
                admin,
                Map.of("id", id, "owner", "another", "name", "Unchanged"),
                400);
        call(
                "PATCH",
                "/menu",
                admin,
                Map.of("id", indirect, "name", "Invalid", "indirectMenus", List.of(key())),
                400);
        call("DELETE", "/menu/" + id, admin, null, 400);
        assertEquals(owner, call("GET", "/menu/" + id, admin, null, 200).path("owner").asText());
        String ordinary = user(null);
        assertEquals(0, call("GET", "/asset/types", ordinary, null, 200).size());
        call("PATCH", "/menu/" + owner + "/_all", ordinary, List.of(input), 403);
        call("DELETE", "/menu/" + indirect, admin, null, 200);
        call("DELETE", "/menu/" + id, admin, null, 200);
    }

    @Test
    void frontendEnumEnvelopesNormalizeWithoutBypassingMetadataValidation() {
        String id = key(), owner = "enum-" + key();
        Map<String, Object> input = menu(id, owner, null, List.of());
        input.put("accessSupport", Map.of("value", "unsupported", "label", "Not supported"));
        call("POST", "/menu", admin, input, 200);
        assertEquals(
                "unsupported",
                call("GET", "/menu/" + id, admin, null, 200).path("accessSupport").asText());
        call(
                "PATCH",
                "/menu",
                admin,
                Map.of(
                        "id",
                        id,
                        "name",
                        "Direct access",
                        "accessSupport",
                        Map.of("value", "support", "label", "Direct"),
                        "assetType",
                        "user"),
                200);
        assertEquals(
                "support",
                call("GET", "/menu/" + id, admin, null, 200).path("accessSupport").asText());
        for (Object invalid :
                List.of(Map.of(), Map.of("value", 42), Map.of("value", "unknown"), 42, List.of())) {
            call(
                    "PATCH",
                    "/menu",
                    admin,
                    Map.of("id", id, "name", "Invalid", "accessSupport", invalid),
                    400);
        }
        call(
                "PATCH",
                "/menu",
                admin,
                Map.of(
                        "id",
                        id,
                        "name",
                        "Invalid direct",
                        "accessSupport",
                        Map.of("value", "support"),
                        "assetType",
                        ""),
                400);
        JsonNode unchanged = call("GET", "/menu/" + id, admin, null, 200);
        assertEquals("Direct access", unchanged.path("name").asText());
        assertEquals("user", unchanged.path("assetType").asText());
    }

    @Test
    void menuTreeQueriesPreserveFilteredRootsAndTheFrontendVisibleNullOptions() {
        String marker = key(), owner = "filter-" + marker, app = key();
        String root = key(),
                visible = key(),
                defaults = key(),
                hidden = key(),
                otherApp = key(),
                otherOwner = key();
        call("POST", "/menu", admin, menu(root, owner, null, List.of()), 200);
        List<String> ids = List.of(defaults, visible, hidden, otherApp, otherOwner);
        for (int index = 0; index < ids.size(); index++) {
            String id = ids.get(index);
            Map<String, Object> row =
                    menu(id, id.equals(otherOwner) ? "another-" + marker : owner, null, List.of());
            row.put("name", "needle-" + marker);
            row.put("appId", id.equals(otherApp) ? "another-app" : app);
            row.put("sortIndex", index + 1);
            if (!id.equals(otherOwner)) row.put("parentId", root);
            if (id.equals(visible)) row.put("options", Map.of("show", true));
            if (id.equals(hidden)) row.put("options", Map.of("show", false));
            call("POST", "/menu", admin, row, 200);
        }
        Map<String, Object> omittedOptions =
                database.sql(
                                "SELECT options IS NULL AS sql_null, options AS stored_options"
                                        + " FROM s_menu WHERE id=:id")
                        .bind("id", defaults)
                        .fetch()
                        .one()
                        .block();
        assertNotNull(omittedOptions);
        System.out.println("Omitted menu options storage: " + omittedOptions);
        // Exercise SQL NULL explicitly, independently of JSON/entity default values.
        assertEquals(
                1L,
                database.sql("UPDATE s_menu SET options=NULL WHERE id=:id")
                        .bind("id", defaults)
                        .fetch()
                        .rowsUpdated()
                        .block());
        assertEquals(
                Boolean.TRUE,
                database.sql("SELECT options IS NULL AS empty_options FROM s_menu WHERE id=:id")
                        .bind("id", defaults)
                        .map((row, metadata) -> row.get("empty_options", Boolean.class))
                        .one()
                        .block());
        Map<String, Object> hiddenFilter =
                Map.of("column", "options", "termType", "nlike", "value", "%show\":false%");
        List<Object> scope =
                new ArrayList<>(
                        List.of(
                                Map.of("column", "owner", "termType", "eq", "value", owner),
                                Map.of("column", "appId", "termType", "eq", "value", app),
                                Map.of(
                                        "terms",
                                        List.of(
                                                Map.of(
                                                        "column",
                                                        "name",
                                                        "termType",
                                                        "like",
                                                        "value",
                                                        "%needle-" + marker + "%"),
                                                Map.of(
                                                        "column",
                                                        "code",
                                                        "termType",
                                                        "eq",
                                                        "value",
                                                        "absent",
                                                        "type",
                                                        "or")))));
        scope.add(hiddenFilter);
        JsonNode filtered =
                call("POST", "/menu/_all/tree", admin, Map.of("pageSize", 1, "terms", scope), 200);
        assertEquals(2, filtered.size());
        assertEquals(defaults, filtered.get(0).path("id").asText());
        assertEquals(visible, filtered.get(1).path("id").asText());
        assertEquals(root, filtered.get(0).path("parentId").asText());
        assertTrue(findMenu(filtered, root).isMissingNode());
        assertFalse(
                findMenu(call("POST", "/menu/_all/tree", admin, null, 200), root).isMissingNode());
        JsonNode defaultVisible =
                call("POST", "/menu/_all/tree", admin, Map.of("terms", List.of(hiddenFilter)), 200);
        assertFalse(findMenu(defaultVisible, "system-user").isMissingNode());
        assertFalse(findMenu(defaultVisible, defaults).isMissingNode());
        assertFalse(findMenu(defaultVisible, visible).isMissingNode());
        assertTrue(findMenu(defaultVisible, hidden).isMissingNode());

        // Other negative LIKE expressions retain the native ORM's NULL inclusion.
        scope.set(
                scope.size() - 1,
                Map.of("column", "options", "termType", "nlike", "value", "%unrelated-marker%"));
        JsonNode ordinary = call("POST", "/menu/_all/tree", admin, Map.of("terms", scope), 200);
        assertEquals(3, ordinary.size());
        Set<String> ordinaryIds = new HashSet<>();
        ordinary.forEach(row -> ordinaryIds.add(row.path("id").asText()));
        assertEquals(Set.of(defaults, visible, hidden), ordinaryIds);
        assertFalse(findMenu(ordinary, defaults).isMissingNode());
        assertFalse(findMenu(ordinary, visible).isMissingNode());
        assertFalse(findMenu(ordinary, hidden).isMissingNode());

        String role = key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Filtered grant"), 200);
        call(
                "PUT",
                "/menu/role/" + role + "/_grant",
                admin,
                Map.of("menus", List.of(Map.of("id", visible))),
                200);
        JsonNode response =
                client.get()
                        .uri(
                                builder ->
                                        builder.path("/menu/role/{id}/_grant/tree")
                                                .queryParam("terms[0].column", "options")
                                                .queryParam("terms[0].termType", "nlike")
                                                .queryParam("terms[0].value", "%show\":false%")
                                                .build(role))
                        .header("X-Access-Token", admin)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(response);
        JsonNode granted = response.path("result");
        assertTrue(findMenu(granted, visible).path("granted").asBoolean());
        assertFalse(findMenu(granted, defaults).isMissingNode());
        assertFalse(findMenu(granted, "system-user").isMissingNode());
        assertTrue(findMenu(granted, hidden).isMissingNode());
    }

    @Test
    void malformedGrantMenuAndButtonShapesReturn400WithoutChangingSelections() {
        String role = key(), id = key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Validated grant"), 200);
        call(
                "POST",
                "/menu",
                admin,
                menu(id, "shape-" + key(), "user", List.of(Map.of("id", "add", "name", "Create"))),
                200);
        call(
                "PUT",
                "/menu/role/" + role + "/_grant",
                admin,
                Map.of(
                        "menus",
                        List.of(
                                Map.of(
                                        "id",
                                        id,
                                        "buttons",
                                        List.of(Map.of("id", "add", "granted", false))))),
                200);
        List<List<?>> invalidMenus =
                List.of(
                        Collections.singletonList(null),
                        List.of(Map.of()),
                        List.of(Map.of("id", 42)),
                        List.of(Map.of("id", id, "buttons", "add")),
                        List.of(Map.of("id", id, "buttons", Collections.singletonList(null))),
                        List.of(Map.of("id", id, "buttons", List.of(42))),
                        List.of(Map.of("id", id, "buttons", List.of(Map.of("id", 42)))),
                        List.of(
                                Map.of(
                                        "id",
                                        id,
                                        "buttons",
                                        List.of(Map.of("id", "add", "granted", "false")))));
        for (List<?> invalid : invalidMenus) {
            call("PUT", "/menu/role/" + role + "/_grant", admin, Map.of("menus", invalid), 400);
        }
        JsonNode selected =
                findMenu(call("GET", "/menu/role/" + role + "/_grant/tree", admin, null, 200), id);
        assertTrue(selected.path("granted").asBoolean());
        assertFalse(findMenu(selected.path("buttons"), "add").path("granted").asBoolean());
        call("PUT", "/menu/role/" + role + "/_grant", admin, Map.of(), 200);
        assertFalse(
                findMenu(call("GET", "/menu/role/" + role + "/_grant/tree", admin, null, 200), id)
                        .path("granted")
                        .asBoolean());
    }

    @Test
    void initializationAcceptsNestedAndFlatTreesAndPreservesOtherOwners() {
        String owner = "owner-" + key(), otherOwner = "owner-" + key();
        String root = key(), removed = key(), other = key(), child = key();
        call("POST", "/menu", admin, menu(root, owner, null, List.of()), 200);
        call("POST", "/menu", admin, menu(removed, owner, null, List.of()), 200);
        call("POST", "/menu", admin, menu(other, otherOwner, null, List.of()), 200);
        Map<String, Object> nested = menu(root, owner, null, List.of());
        nested.put("children", List.of(menu(child, owner, null, List.of())));
        call("PATCH", "/menu/" + owner + "/_all", admin, List.of(nested), 200);
        call("GET", "/menu/" + removed, admin, null, 404);
        assertEquals(
                root, call("GET", "/menu/" + child, admin, null, 200).path("parentId").asText());
        assertEquals(
                otherOwner, call("GET", "/menu/" + other, admin, null, 200).path("owner").asText());
        call("PATCH", "/menu/" + owner + "/_all", admin, List.of(), 400);
        call("PATCH", "/menu/" + owner + "/_all", admin, List.of(nested, nested), 400);
        call(
                "PATCH",
                "/menu/" + owner + "/_all",
                admin,
                List.of(menu(other, owner, null, List.of())),
                400);
        Map<String, Object> cyclicRoot = menu(root, owner, null, List.of());
        cyclicRoot.put("parentId", child);
        Map<String, Object> cyclicChild = menu(child, owner, null, List.of());
        cyclicChild.put("parentId", root);
        call("PATCH", "/menu/" + owner + "/_all", admin, List.of(cyclicRoot, cyclicChild), 400);
        assertEquals(
                root, call("GET", "/menu/" + child, admin, null, 200).path("parentId").asText());
        Map<String, Object> flatChild = menu(child, owner, null, List.of());
        flatChild.put("parentId", root);
        call(
                "PATCH",
                "/menu/" + owner + "/_all",
                admin,
                List.of(flatChild, menu(root, owner, null, List.of())),
                200);
        assertEquals(
                root, call("GET", "/menu/" + child, admin, null, 200).path("parentId").asText());
        assertEquals(
                otherOwner, call("GET", "/menu/" + other, admin, null, 200).path("owner").asText());
    }

    @Test
    void menuEditsDeletionsAndReplacementRevokeExistingTokensWithoutGrantingNewActions() {
        String owner = "grant-" + key(), otherOwner = "grant-" + key();
        String id = key(), other = key(), role = key(), manual = key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Menu grant"), 200);
        call(
                "POST",
                "/menu",
                admin,
                menu(id, owner, "user", List.of(Map.of("id", "add", "name", "Create"))),
                200);
        call("POST", "/menu", admin, menu(other, otherOwner, "permission", List.of()), 200);
        call(
                "PATCH",
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        manual,
                        "permission",
                        "system-config",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        List.of("query"),
                        "state",
                        1,
                        "priority",
                        50,
                        "merge",
                        false),
                200);
        grant(role, id, other, List.of("add"));
        String token = user(role);
        createAs(token, 200);
        call("POST", "/permission/_query/", token, Map.of(), 200);
        call("POST", "/system/config/scopes", token, List.of("front"), 200);
        database.sql(
                        "UPDATE s_autz_setting_info SET priority=70,"
                                + " data_accesses='[{\"action\":\"query\",\"type\":\"DENY_FIELDS\",\"config\":{\"fields\":[\"password\"]}}]'"
                                + " WHERE dimension_type='role' AND dimension_target=:role AND"
                                + " permission='user'")
                .bind("role", role)
                .fetch()
                .rowsUpdated()
                .block();
        call("PATCH", "/menu", admin, menu(id, owner, "user", List.of()), 200);
        createAs(token, 403);
        call("POST", "/user/detail/_query", token, Map.of(), 200);
        JsonNode settings =
                call(
                                "POST",
                                "/autz-setting/_query",
                                admin,
                                Map.of(
                                        "terms",
                                        List.of(
                                                Map.of(
                                                        "column",
                                                        "dimensionTarget",
                                                        "termType",
                                                        "eq",
                                                        "value",
                                                        role),
                                                Map.of(
                                                        "column",
                                                        "permission",
                                                        "termType",
                                                        "eq",
                                                        "value",
                                                        "user"))),
                                200)
                        .path("data")
                        .get(0);
        assertEquals(70, settings.path("priority").asInt());
        assertEquals("DENY_FIELDS", settings.path("dataAccesses").get(0).path("type").asText());
        call("PATCH", "/menu", admin, menu(id, owner, "role", List.of()), 200);
        call("POST", "/user/detail/_query", token, Map.of(), 403);
        call("POST", "/role/_query/", token, Map.of(), 403);
        call("POST", "/permission/_query/", token, Map.of(), 200);
        call("POST", "/system/config/scopes", token, List.of("front"), 200);
        grant(role, id, other, List.of());
        call("POST", "/role/_query/", token, Map.of(), 200);
        call("DELETE", "/menu/" + id, admin, null, 200);
        call("POST", "/role/_query/", token, Map.of(), 403);
        call(
                "POST",
                "/menu",
                admin,
                menu(id, owner, "user", List.of(Map.of("id", "add", "name", "Create"))),
                200);
        grant(role, id, other, List.of("add"));
        createAs(token, 200);
        call(
                "PATCH",
                "/menu/" + owner + "/_all",
                admin,
                List.of(menu(id, owner, "user", List.of())),
                200);
        createAs(token, 403);
        call("POST", "/user/detail/_query", token, Map.of(), 200);
        call(
                "PATCH",
                "/menu/" + owner + "/_all",
                admin,
                List.of(menu(key(), owner, "role", List.of())),
                200);
        call("POST", "/user/detail/_query", token, Map.of(), 403);
        call("POST", "/role/_query/", token, Map.of(), 403);
        call("POST", "/permission/_query/", token, Map.of(), 200);
        call("POST", "/system/config/scopes", token, List.of("front"), 200);
        assertEquals(
                otherOwner, call("GET", "/menu/" + other, admin, null, 200).path("owner").asText());
        assertEquals(
                1L,
                database.sql("SELECT count(*) FROM s_autz_setting_info WHERE id=:id")
                        .bind("id", manual)
                        .map((row, metadata) -> row.get(0, Long.class))
                        .one()
                        .block());
    }

    @Test
    void databaseFailureRollsBackOwnerReplacementAndGrantRevocation() {
        String owner = "rollback-" + key(), id = key(), other = key(), role = key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Rollback role"), 200);
        call("POST", "/menu", admin, menu(id, owner, "user", List.of()), 200);
        call("POST", "/menu", admin, menu(other, "other-" + key(), "permission", List.of()), 200);
        grant(role, id, other, List.of());
        String token = user(role);
        // A database write failure after DELETE proves the transaction, without a production test
        // hook.
        database.sql(
                        "CREATE FUNCTION reject_test_menu() RETURNS trigger LANGUAGE plpgsql AS $$"
                                + " BEGIN IF NEW.name = 'Reject replacement' THEN RAISE EXCEPTION"
                                + " 'test_write_rejected'; END IF; RETURN NEW; END; $$")
                .fetch()
                .rowsUpdated()
                .block();
        database.sql(
                        "CREATE TRIGGER reject_test_menu BEFORE INSERT ON s_menu FOR EACH ROW"
                                + " EXECUTE FUNCTION reject_test_menu()")
                .fetch()
                .rowsUpdated()
                .block();
        try {
            Map<String, Object> rejected = menu(key(), owner, "role", List.of());
            rejected.put("name", "Reject replacement");
            call("PATCH", "/menu/" + owner + "/_all", admin, List.of(rejected), 500);
            assertEquals(
                    owner, call("GET", "/menu/" + id, admin, null, 200).path("owner").asText());
            call("POST", "/user/detail/_query", token, Map.of(), 200);
            call("POST", "/role/_query/", token, Map.of(), 403);
        } finally {
            database.sql("DROP TRIGGER reject_test_menu ON s_menu").fetch().rowsUpdated().block();
            database.sql("DROP FUNCTION reject_test_menu()").fetch().rowsUpdated().block();
        }
    }

    @Test
    void initializationValidatesForwardIndirectReferencesAcrossOfficialSaveBatches() {
        String owner = "batch-" + key();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 205; index++) rows.add(menu(key(), owner, null, List.of()));
        String first = rows.get(0).get("id").toString();
        String last = rows.get(204).get("id").toString();
        rows.get(0).put("accessSupport", "indirect");
        rows.get(0).put("indirectMenus", List.of(last));
        call("PATCH", "/menu/" + owner + "/_all", admin, rows, 200);
        assertEquals(
                last,
                call("GET", "/menu/" + first, admin, null, 200)
                        .path("indirectMenus")
                        .get(0)
                        .asText());
        assertEquals(owner, call("GET", "/menu/" + last, admin, null, 200).path("owner").asText());
        assertEquals(
                205L,
                database.sql("SELECT count(*) FROM s_menu WHERE owner=:owner")
                        .bind("owner", owner)
                        .map((row, metadata) -> row.get(0, Long.class))
                        .one()
                        .block());
        rows.get(0).put("indirectMenus", List.of(key()));
        call("PATCH", "/menu/" + owner + "/_all", admin, rows, 400);
        assertEquals(
                last,
                call("GET", "/menu/" + first, admin, null, 200)
                        .path("indirectMenus")
                        .get(0)
                        .asText());
    }

    private JsonNode findMenu(JsonNode roots, String id) {
        for (JsonNode node : roots) {
            if (id.equals(node.path("id").asText())) return node;
            JsonNode child = findMenu(node.path("children"), id);
            if (!child.isMissingNode()) return child;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
}
