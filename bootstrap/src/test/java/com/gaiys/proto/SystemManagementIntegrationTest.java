package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class SystemManagementIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static final String PASSWORD = UUID.randomUUID().toString();
    static final Path FILES = files();

    static Path files() {
        try {
            return Files.createTempDirectory("proto-management-files-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
        r.add("hsweb.file.upload.static-file-path", FILES::toString);
    }

    @Autowired WebTestClient client;
    @Autowired org.springframework.r2dbc.core.DatabaseClient database;
    @Autowired com.gaiys.proto.system.service.SystemConfigService configs;
    String admin;

    @BeforeEach
    void authenticate() {
        client = client.mutate().responseTimeout(java.time.Duration.ofSeconds(30)).build();
        admin = login("admin", PASSWORD);
    }

    String login(String username, String password) {
        return call(
                        "POST",
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", password),
                        200)
                .path("token")
                .asText();
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

    JsonNode uploadImage(String path, String token, String filename, byte[] bytes, int status) {
        var body = new org.springframework.http.client.MultipartBodyBuilder();
        body.part("file", new org.springframework.core.io.ByteArrayResource(bytes))
                .filename(filename);
        var request = client.post().uri(path);
        if (token != null) request.header("X-Access-Token", token);
        JsonNode response =
                request.body(
                                org.springframework.web.reactive.function.BodyInserters
                                        .fromMultipartData(body.build()))
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(response);
        return response.path("result");
    }

    byte[] pngImage() {
        return Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jY1sAAAAASUVORK5CYII=");
    }

    String key() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    JsonNode createUser(String username) {
        return call(
                "POST",
                "/user/detail/_create",
                admin,
                Map.of(
                        "name",
                        "Test user",
                        "username",
                        username,
                        "password",
                        PASSWORD,
                        "telephone",
                        "12345",
                        "email",
                        "test@example.com"),
                200);
    }

    Object terms(String column, Object value) {
        return Map.of("terms", List.of(Map.of("column", column, "termType", "eq", "value", value)));
    }

    @Test
    void pagedManagementQueriesRejectNonPositivePageSizes() {
        for (String path :
                List.of("/user/detail/_query", "/role/_query", "/position/_query/detail")) {
            for (int pageSize : List.of(-1, 0)) {
                JsonNode response =
                        client.post()
                                .uri(path)
                                .header("X-Access-Token", admin)
                                .bodyValue(Map.of("pageSize", pageSize))
                                .exchange()
                                .expectStatus()
                                .isBadRequest()
                                .expectBody(JsonNode.class)
                                .returnResult()
                                .getResponseBody();
                assertNotNull(response);
                assertEquals("BAD_REQUEST", response.path("code").asText());
                assertFalse(response.path("success").asBoolean());
            }
        }
    }

    @Autowired org.springframework.context.ApplicationContext context;

    @Test
    void inheritedCrudPaginationRejectsInvalidSizesWithPersistedRowsAndPreservesNoPaging() {
        String dictionary = key();
        call(
                "PATCH",
                "/dictionary",
                admin,
                Map.of("id", dictionary, "name", "Pagination boundary", "status", 1),
                200);
        call(
                "PATCH",
                "/dictionary-item",
                admin,
                List.of(
                        Map.of(
                                "dictId",
                                dictionary,
                                "name",
                                "Ready",
                                "value",
                                "ready",
                                "text",
                                "Ready",
                                "status",
                                1)),
                200);
        call(
                "POST",
                "/organization",
                admin,
                Map.of("id", key(), "name", "Pagination organization"),
                200);
        call(
                "POST",
                "/role/group",
                admin,
                Map.of("id", key(), "name", "Pagination role group"),
                200);
        List<Map<String, Integer>> invalid =
                List.of(
                        Map.of("pageSize", -1),
                        Map.of("pageSize", 0),
                        Map.of("pageSize", 201),
                        Map.of("pageSize", -1, "total", 1));
        for (String path :
                List.of(
                        "/permission/_query",
                        "/menu/_query",
                        "/organization/_query",
                        "/role/group/_query",
                        "/dictionary/_query",
                        "/dictionary-item/_query")) {
            for (String method : List.of("GET", "POST")) {
                for (Map<String, Integer> input : invalid) {
                    var request =
                            client.method(HttpMethod.valueOf(method))
                                    .uri(
                                            "GET".equals(method)
                                                    ? path
                                                            + "?pageSize="
                                                            + input.get("pageSize")
                                                            + (input.containsKey("total")
                                                                    ? "&total=1"
                                                                    : "")
                                                    : path)
                                    .header("X-Access-Token", admin);
                    if ("POST".equals(method)) request.bodyValue(input);
                    JsonNode response =
                            request.exchange()
                                    .expectStatus()
                                    .isBadRequest()
                                    .expectBody(JsonNode.class)
                                    .returnResult()
                                    .getResponseBody();
                    assertNotNull(response, method + " " + path);
                    assertEquals(
                            "BAD_REQUEST", response.path("code").asText(), method + " " + path);
                    assertFalse(response.path("success").asBoolean());
                }
                for (int size : List.of(1, 200)) {
                    JsonNode page =
                            call(
                                    method,
                                    "GET".equals(method) ? path + "?pageSize=" + size : path,
                                    admin,
                                    "POST".equals(method) ? Map.of("pageSize", size) : null,
                                    200);
                    assertTrue(page.path("total").asInt() > 0, method + " " + path);
                    assertTrue(page.path("data").size() > 0, method + " " + path);
                    assertTrue(page.path("data").size() <= size, method + " " + path);
                }
                JsonNode rows =
                        call(
                                method,
                                path
                                        + "/no-paging"
                                        + ("GET".equals(method) ? "?paging=false&pageSize=1" : ""),
                                admin,
                                "POST".equals(method)
                                        ? Map.of("paging", false, "pageSize", 1)
                                        : null,
                                200);
                assertTrue(rows.isArray(), method + " " + path);
                assertTrue(rows.size() > 0, method + " " + path);
            }
        }
        for (int size : List.of(-1, 0, 201))
            call("POST", "/permission/_query/", admin, Map.of("pageSize", size), 400);
        assertTrue(
                call("POST", "/permission/_query/", admin, Map.of("pageSize", 1), 200)
                                .path("data")
                                .size()
                        > 0);
    }

    @Test
    void officialControllerFactoriesRemainUniqueAndSelfServiceWorks() {
        for (Class<?> type :
                List.of(
                        org.hswebframework.web.system.authorization.defaults.webflux
                                .WebFluxPermissionController.class,
                        org.hswebframework.web.system.authorization.defaults.webflux
                                .WebFluxUserController.class,
                        org.hswebframework.web.system.authorization.defaults.webflux
                                .WebFluxDimensionController.class,
                        org.hswebframework.web.system.authorization.defaults.webflux
                                .WebFluxDimensionTypeController.class,
                        org.hswebframework.web.system.authorization.defaults.webflux
                                .WebFluxDimensionUserController.class,
                        org.hswebframework.web.system.authorization.defaults.webflux
                                .WebFluxAuthorizationSettingController.class,
                        org.hswebframework.web.dictionary.webflux.WebfluxDictionaryController.class,
                        org.hswebframework.web.dictionary.webflux.WebfluxDictionaryItemController
                                .class))
            assertEquals(1, context.getBeansOfType(type).size(), type.getSimpleName());
        assertEquals(
                "admin",
                call("GET", "/authorize/me", admin, null, 200)
                        .path("user")
                        .path("username")
                        .asText());
        String username = "self-" + key();
        String id = createUser(username).path("id").asText();
        call("POST", "/role/MEMBER/users/_bind", admin, List.of(id), 200);
        String member = login(username, PASSWORD);
        call("PUT", "/user/me", member, Map.of("name", "Self updated"), 200);
        assertEquals(
                "Self updated",
                call("GET", "/user/detail", member, null, 200).path("name").asText());
        assertEquals(
                username,
                call("GET", "/authorize/me", member, null, 200)
                        .path("user")
                        .path("username")
                        .asText());
        String next = UUID.randomUUID().toString();
        call(
                "PUT",
                "/user/passwd",
                member,
                Map.of("oldPassword", PASSWORD, "newPassword", next),
                200);
        assertFalse(login(username, next).isBlank());
    }

    @Test
    void usersPersistContactAndBindingsAndResetPasswords() {
        String role = key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Reader"), 200);
        String username = "u-" + key();
        String id = createUser(username).path("id").asText();
        assertFalse(id.isBlank());
        call(
                "PUT",
                "/user/detail/" + id + "/_update",
                admin,
                Map.of(
                        "name",
                        "Updated",
                        "telephone",
                        "888",
                        "email",
                        "updated@example.com",
                        "roleIdList",
                        List.of(role)),
                200);
        call("PATCH", "/role", admin, Map.of("id", role, "name", "Reader renamed"), 200);
        JsonNode saved = call("GET", "/user/detail/" + id, admin, null, 200);
        assertEquals("888", saved.path("telephone").asText());
        assertEquals(role, saved.path("roleIdList").get(0).asText());
        assertEquals("Reader renamed", saved.path("roleList").get(0).path("name").asText());
        assertFalse(saved.has("password"));
        assertFalse(saved.has("salt"));
        JsonNode page = call("POST", "/user/detail/_query", admin, terms("id", id), 200);
        assertEquals(1, page.path("total").asInt());
        String next = "Changed-" + key();
        call("POST", "/user/" + id + "/password/_reset", admin, next, 200);
        assertFalse(login(username, next).isBlank());
        call(
                "PUT",
                "/user/detail/" + id + "/_update",
                admin,
                Map.of("name", "Changed", "email", "invalid"),
                400);
        assertEquals(
                "Updated",
                call("GET", "/user/detail/" + id, admin, null, 200).path("name").asText());
        database.sql(
                        "INSERT INTO s_user_setting(id,user_id,type,setting_key,name,content,create_time) VALUES (:id,:user,'cleanup','selection','清理测试','{}',1)")
                .bind("id", key())
                .bind("user", id)
                .fetch()
                .rowsUpdated()
                .block();
        assertEquals(1, countRecords("s_user_detail", "id", id));
        assertEquals(1, countRecords("s_user_setting", "user_id", id));
        call("DELETE", "/user/" + id, admin, null, 200);
        call("GET", "/user/detail/" + id, admin, null, 404);
        assertEquals(0, countRecords("s_user_detail", "id", id));
        assertEquals(0, countRecords("s_user_setting", "user_id", id));
    }

    Map<String, Object> condition(String column, String operator, Object value) {
        return Map.of("column", column, "termType", operator, "value", value);
    }

    JsonNode queryUsers(Object... conditions) {
        return call(
                "POST", "/user/detail/_query", admin, Map.of("terms", List.of(conditions)), 200);
    }

    Set<String> pageUserIds(JsonNode page) {
        Set<String> ids = new HashSet<>();
        page.path("data").forEach(row -> ids.add(row.path("id").asText()));
        assertEquals(ids.size(), page.path("total").asInt());
        return ids;
    }

    String createContactUser(String username, String telephone, String email) {
        return call(
                        "POST",
                        "/user/detail/_create",
                        admin,
                        Map.of(
                                "name", "Contact filter user",
                                "username", username,
                                "password", PASSWORD,
                                "telephone", telephone,
                                "email", email),
                        200)
                .path("id")
                .asText();
    }

    @Test
    void userContactFiltersPreserveNestedLogicAndDimensionFilters() {
        String marker = key();
        String firstName = "contact-" + marker + "-first";
        String secondName = "contact-" + marker + "-second";
        String phone = "phone-" + marker + "-100";
        String first = createContactUser(firstName, phone, "first" + marker + "@example.com");
        String second =
                createContactUser(
                        secondName, "phone-" + marker + "-200", "second" + marker + "@example.com");
        String role = key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Contact reader"), 200);
        call("POST", "/role/" + role + "/users/_bind", admin, List.of(first), 200);

        assertEquals(
                Set.of(first),
                pageUserIds(queryUsers(condition("telephone", "like", "%" + phone + "%"))));
        assertEquals(
                Set.of(first, second),
                pageUserIds(queryUsers(condition("email", "like", "%" + marker + "%"))));
        assertEquals(
                Set.of(),
                pageUserIds(queryUsers(condition("telephone", "like", "%missing-" + key() + "%"))));

        Map<String, Object> eitherContact =
                Map.of(
                        "terms",
                        List.of(
                                condition("telephone", "like", "%" + phone + "%"),
                                Map.of(
                                        "column",
                                        "email",
                                        "termType",
                                        "eq",
                                        "value",
                                        "second" + marker + "@example.com",
                                        "type",
                                        "or")));
        assertEquals(
                Set.of(first, second),
                pageUserIds(
                        queryUsers(
                                condition("username", "in", List.of(firstName, secondName)),
                                eitherContact)));
        assertEquals(
                Set.of(),
                pageUserIds(
                        queryUsers(
                                condition("username", "eq", secondName),
                                condition("telephone", "like", "%" + phone + "%"))));
        assertEquals(
                Set.of(first),
                pageUserIds(
                        queryUsers(
                                condition("id$in-org-user$role", "eq", role),
                                condition("email", "like", "%" + marker + "%"))));

        Map<String, Object> emptyOrUsername =
                Map.of(
                        "terms",
                        List.of(
                                condition("telephone", "like", "%missing-" + key() + "%"),
                                Map.of(
                                        "column",
                                        "username",
                                        "termType",
                                        "eq",
                                        "value",
                                        firstName,
                                        "type",
                                        "or")));
        assertEquals(Set.of(first), pageUserIds(queryUsers(emptyOrUsername)));
        assertEquals(
                Set.of(first),
                pageUserIds(
                        call(
                                "POST",
                                "/user/_query/",
                                admin,
                                Map.of("terms", List.of(condition("telephone", "eq", phone))),
                                200)));
    }

    @Test
    void userContactNullAndNegationUseSqlNullSemantics() {
        String marker = key();
        String firstName = "null-contact-" + marker + "-first";
        String secondName = "null-contact-" + marker + "-second";
        String nullName = "null-contact-" + marker + "-null";
        String missingName = "null-contact-" + marker + "-missing";
        String firstPhone = "phone-" + marker + "-100";
        String first = createContactUser(firstName, firstPhone, "first" + marker + "@example.com");
        String second =
                createContactUser(
                        secondName, "phone-" + marker + "-200", "second" + marker + "@example.com");
        String nullContact = createUser(nullName).path("id").asText();
        String missingContact = createUser(missingName).path("id").asText();
        database.sql("UPDATE s_user_detail SET telephone = NULL, email = NULL WHERE id = :id")
                .bind("id", nullContact)
                .fetch()
                .rowsUpdated()
                .block();
        database.sql("DELETE FROM s_user_detail WHERE id = :id")
                .bind("id", missingContact)
                .fetch()
                .rowsUpdated()
                .block();
        Map<String, Object> users =
                condition("username", "in", List.of(firstName, secondName, nullName, missingName));

        assertEquals(
                Set.of(second),
                pageUserIds(queryUsers(users, condition("telephone", "nlike", "%100%"))));
        assertEquals(
                Set.of(second),
                pageUserIds(queryUsers(users, condition("telephone", "not", firstPhone))));
        assertEquals(
                Set.of(second),
                pageUserIds(queryUsers(users, condition("telephone", "nin", List.of(firstPhone)))));
        assertEquals(
                Set.of(nullContact, missingContact),
                pageUserIds(queryUsers(users, condition("telephone", "isnull", ""))));
        assertEquals(
                Set.of(first, second),
                pageUserIds(queryUsers(users, condition("telephone", "notnull", ""))));
        assertEquals(
                Set.of(nullContact, missingContact),
                pageUserIds(queryUsers(users, condition("email", "isnull", ""))));
        assertEquals(
                Set.of(first, nullContact, missingContact),
                pageUserIds(
                        queryUsers(
                                users,
                                Map.of(
                                        "terms",
                                        List.of(
                                                condition("telephone", "isnull", ""),
                                                Map.of(
                                                        "column",
                                                        "telephone",
                                                        "termType",
                                                        "eq",
                                                        "value",
                                                        firstPhone,
                                                        "type",
                                                        "or"))))));
    }

    JsonNode createPosition(String orgId, String name, String parentId, List<?> roles) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", key());
        row.put("name", name);
        row.put("roles", roles);
        if (parentId != null) row.put("parentId", parentId);
        return call("POST", "/position/" + orgId + "/_create", admin, row, 200);
    }

    JsonNode queryPositions(Object... conditions) {
        return call(
                "POST",
                "/position/_query/detail",
                admin,
                Map.of("terms", List.of(conditions)),
                200);
    }

    @Test
    void positionRelatedFiltersUseCurrentNamesAndKeepQueryGrouping() {
        String marker = key(), org = key(), otherOrg = key(), firstRole = key(), secondRole = key();
        String orgName = "Engineering-" + marker, otherOrgName = "Operations-" + marker;
        String parentName = "Lead-" + marker, latestRole = "Latest reader-" + marker;
        call("POST", "/organization", admin, Map.of("id", org, "name", orgName), 200);
        call("POST", "/organization", admin, Map.of("id", otherOrg, "name", otherOrgName), 200);
        call("POST", "/role", admin, Map.of("id", firstRole, "name", "Old reader-" + marker), 200);
        call("POST", "/role", admin, Map.of("id", secondRole, "name", "Writer-" + marker), 200);
        String parent = createPosition(org, parentName, null, List.of()).path("id").asText();
        String first =
                createPosition(
                                org,
                                "Filtered-" + marker + "-1",
                                parent,
                                List.of(Map.of("id", firstRole)))
                        .path("id")
                        .asText();
        String second =
                createPosition(org, "Filtered-" + marker + "-2", parent, List.of(secondRole))
                        .path("id")
                        .asText();
        String root =
                createPosition(otherOrg, "Filtered-" + marker + "-3", null, List.of())
                        .path("id")
                        .asText();
        Map<String, Object> scope = condition("id", "in", List.of(first, second, root));
        call("PATCH", "/role", admin, Map.of("id", firstRole, "name", latestRole), 200);

        assertEquals(
                Set.of(first, second),
                pageUserIds(
                        queryPositions(scope, condition("orgName", "like", "%" + orgName + "%"))));
        assertEquals(
                Set.of(first, second),
                pageUserIds(queryPositions(scope, condition("parentName", "eq", parentName))));
        assertEquals(
                Set.of(first),
                pageUserIds(
                        queryPositions(scope, condition("roles", "like", "%" + latestRole + "%"))));
        assertEquals(
                Set.of(first),
                pageUserIds(
                        queryPositions(
                                scope, condition("id$position-role$position", "eq", firstRole))));
        assertEquals(
                latestRole,
                call("GET", "/position/" + first, admin, null, 200)
                        .path("roles")
                        .get(0)
                        .path("name")
                        .asText());
        assertEquals(
                Set.of(second, root),
                pageUserIds(
                        queryPositions(
                                scope, condition("roles", "nlike", "%" + latestRole + "%"))));
        assertEquals(
                Set.of(second, root),
                pageUserIds(queryPositions(scope, condition("roles", "eq$not", latestRole))));
        assertEquals(
                Set.of(second, root),
                pageUserIds(
                        queryPositions(
                                scope,
                                condition("id$position-role$position$not", "eq", firstRole))));
        assertEquals(
                Set.of(root),
                pageUserIds(queryPositions(scope, condition("parentName", "isnull", ""))));
        assertEquals(
                Set.of(first, second),
                pageUserIds(queryPositions(scope, condition("parentName", "notnull", ""))));
        assertEquals(
                Set.of(root), pageUserIds(queryPositions(scope, condition("roles", "isnull", ""))));
        assertEquals(
                Set.of(first, second),
                pageUserIds(queryPositions(scope, condition("roles", "notnull", ""))));
        assertEquals(
                Set.of(root),
                pageUserIds(queryPositions(scope, condition("orgName", "not", orgName))));
        assertEquals(
                Set.of(),
                pageUserIds(
                        queryPositions(
                                scope, condition("roles", "like", "%missing-" + key() + "%"))));

        Map<String, Object> either =
                Map.of(
                        "terms",
                        List.of(
                                condition("orgName", "eq", otherOrgName),
                                Map.of(
                                        "column",
                                        "roles",
                                        "termType",
                                        "eq",
                                        "value",
                                        latestRole,
                                        "type",
                                        "or")));
        assertEquals(Set.of(first, root), pageUserIds(queryPositions(scope, either)));
        JsonNode page =
                call(
                        "POST",
                        "/position/_query/detail",
                        admin,
                        Map.of(
                                "terms",
                                List.of(scope, condition("parentName", "eq", parentName)),
                                "pageSize",
                                1,
                                "pageIndex",
                                1,
                                "sorts",
                                List.of(Map.of("name", "name", "order", "asc"))),
                        200);
        assertEquals(2, page.path("total").asInt());
        assertEquals(second, page.path("data").get(0).path("id").asText());
        JsonNode all =
                call(
                        "POST",
                        "/position/_query/all",
                        admin,
                        Map.of("terms", List.of(scope, condition("roles", "eq", latestRole))),
                        200);
        assertEquals(1, all.size());
        assertEquals(first, all.get(0).path("id").asText());
        assertEquals(latestRole, all.get(0).path("roles").get(0).path("name").asText());
        JsonNode empty =
                call(
                        "POST",
                        "/position/_query/detail/no-paging",
                        admin,
                        Map.of(
                                "terms",
                                List.of(condition("orgName", "like", "%missing-" + key() + "%"))),
                        200);
        assertTrue(empty.isArray());
        assertEquals(0, empty.size());
    }

    @Test
    void positionJsonImportCreatesUpdatesAndRejectsCrossOrganizationOverwrite() {
        String org = key(), otherOrg = key(), role = key(), id = key();
        String latest = "Import role-" + key();
        call("POST", "/organization", admin, Map.of("id", org, "name", "Import organization"), 200);
        call(
                "POST",
                "/organization",
                admin,
                Map.of("id", otherOrg, "name", "Other import organization"),
                200);
        call("POST", "/role", admin, Map.of("id", role, "name", latest), 200);
        // 原 JSON 导出对象被前端再包成 {id: {id, name}}，应解析有限的一层历史格式。
        call(
                "PATCH",
                "/position/" + org + "/_save",
                admin,
                List.of(
                        Map.of(
                                "id",
                                id,
                                "orgId",
                                org,
                                "name",
                                "Imported position",
                                "roles",
                                List.of(
                                        Map.of(
                                                "id",
                                                Map.of("id", role, "name", "Exported old name"))))),
                200);
        JsonNode imported = call("GET", "/position/" + id, admin, null, 200);
        assertEquals(org, imported.path("orgId").asText());
        assertEquals(latest, imported.path("roles").get(0).path("name").asText());

        String generatedName = "Generated import-" + key();
        call(
                "PATCH",
                "/position/" + org + "/_save",
                admin,
                List.of(Map.of("name", generatedName, "roles", List.of(role))),
                200);
        JsonNode generated = queryPositions(condition("name", "eq", generatedName));
        assertEquals(1, generated.path("total").asInt());
        assertFalse(generated.path("data").get(0).path("id").asText().isBlank());
        call(
                "PATCH",
                "/position/" + org + "/_save",
                admin,
                List.of(
                        Map.of(
                                "id",
                                id,
                                "name",
                                "Updated imported position",
                                "roles",
                                List.of(Map.of("id", role)))),
                200);
        assertEquals(
                "Updated imported position",
                call("GET", "/position/" + id, admin, null, 200).path("name").asText());

        call(
                "PATCH",
                "/position/" + otherOrg + "/_save",
                admin,
                List.of(Map.of("id", id, "name", "Forbidden overwrite", "roles", List.of())),
                409);
        assertEquals(org, call("GET", "/position/" + id, admin, null, 200).path("orgId").asText());
        call(
                "PATCH",
                "/position/" + org + "/_save",
                admin,
                List.of(Map.of("id", key(), "orgId", otherOrg, "name", "Conflicting import")),
                409);
        call(
                "PATCH",
                "/position/" + org + "/_save",
                admin,
                List.of(
                        Map.of(
                                "id",
                                key(),
                                "name",
                                "Too deeply nested role",
                                "roles",
                                List.of(Map.of("id", Map.of("id", Map.of("id", role)))))),
                400);
        String rolledBack = key();
        call(
                "PATCH",
                "/position/" + otherOrg + "/_save",
                admin,
                List.of(
                        Map.of("id", rolledBack, "name", "Batch should roll back"),
                        Map.of("id", id, "name", "Cross organization in batch")),
                409);
        call("GET", "/position/" + rolledBack, admin, null, 404);
    }

    @Test
    void positionInheritedCrudPreservesOrganizationAndMembership() {
        String org = key(), otherOrg = key();
        call(
                "POST",
                "/organization",
                admin,
                Map.of("id", org, "name", "Immutable position organization"),
                200);
        call(
                "POST",
                "/organization",
                admin,
                Map.of("id", otherOrg, "name", "Different position organization"),
                200);
        String position =
                createPosition(org, "Member position", null, List.of()).path("id").asText();
        String user = createUser("position-org-" + key()).path("id").asText();
        call("POST", "/organization/" + org + "/users/_bind", admin, List.of(user), 200);
        call(
                "POST",
                "/organization/" + org + "/position/" + position + "/users/_bind",
                admin,
                List.of(user),
                200);
        call(
                "PATCH",
                "/position",
                admin,
                List.of(
                        Map.of(
                                "id",
                                position,
                                "orgId",
                                otherOrg,
                                "name",
                                "Moved by generic PATCH")),
                409);
        call(
                "PUT",
                "/position/" + position,
                admin,
                Map.of("orgId", otherOrg, "name", "Moved by generic PUT"),
                409);
        assertEquals(
                org, call("GET", "/position/" + position, admin, null, 200).path("orgId").asText());
        JsonNode detail = call("GET", "/user/detail/" + user, admin, null, 200);
        assertEquals(org, detail.path("orgIdList").get(0).asText());
        assertEquals(position, detail.path("positions").get(0).path("id").asText());
        call(
                "PUT",
                "/position/" + position,
                admin,
                Map.of("orgId", org, "name", "Same organization edit"),
                200);
        assertEquals(
                "Same organization edit",
                call("GET", "/position/" + position, admin, null, 200).path("name").asText());
    }

    @Test
    void roleGroupsFilterAndProtectReservedRoles() {
        String group = key(), role = key();
        call("PATCH", "/role/group", admin, Map.of("id", group, "name", "Engineering"), 200);
        call("POST", "/role", admin, Map.of("id", role, "name", "Engineer", "groupId", group), 200);
        JsonNode page = call("POST", "/role/_query/", admin, terms("groupId", group), 200);
        assertEquals(1, page.path("total").asInt());
        assertEquals(role, page.path("data").get(0).path("id").asText());
        call("POST", "/role", admin, Map.of("id", key(), "name", "Missing", "groupId", key()), 404);
        call("DELETE", "/role/ADMIN", admin, null, 409);
        call("PATCH", "/role", admin, Map.of("id", role, "name", "Renamed", "groupId", group), 200);
        assertEquals(
                "Renamed", call("GET", "/role/" + role, admin, null, 200).path("name").asText());
        database.sql(
                        "INSERT INTO s_role_menu_grant(id,menus,managed_permissions) VALUES (:role,'{}','[]')")
                .bind("role", role)
                .fetch()
                .rowsUpdated()
                .block();
        assertEquals(1, countRecords("s_role_menu_grant", "id", role));
        call("DELETE", "/role/" + role, admin, null, 200);
        assertEquals(0, countRecords("s_role_menu_grant", "id", role));
        call("DELETE", "/role/group/" + group, admin, null, 200);
    }

    @Test
    void roleJsonFiltersPreserveNullEmptyCollectionDefaultGroupAndBoundTextSemantics() {
        String enabled = key(),
                disabled = key(),
                missing = key(),
                explicitNull = key(),
                quoted = key();
        String group = key();
        List<String> scope = List.of(enabled, disabled, missing, explicitNull, quoted);
        for (String id : scope)
            call("POST", "/role", admin, Map.of("id", id, "name", "JSON property fixture"), 200);
        // Existing installations can contain absent or explicit-null JSON properties. Queries must
        // retain SQL null semantics and bind text rather than interpreting it as an expression.
        Map<String, String> properties =
                Map.of(
                        enabled, "{\"state\":\"enabled\",\"groupId\":\"default\"}",
                        disabled, "{\"state\":\"disabled\",\"groupId\":\"" + group + "\"}",
                        missing, "{}",
                        explicitNull, "{\"state\":null,\"groupId\":null}",
                        quoted, "{\"state\":\"x' OR true --\",\"groupId\":\"quoted\"}");
        properties.forEach(
                (id, json) ->
                        database.sql("UPDATE s_dimension SET properties=:properties WHERE id=:id")
                                .bind("properties", json)
                                .bind("id", id)
                                .fetch()
                                .rowsUpdated()
                                .block());
        assertEquals(
                Set.of(enabled),
                pageUserIds(
                        queryRoleProperties(scope, condition("groupId", "eq", "default_group"))));
        assertEquals(
                Set.of(enabled),
                pageUserIds(
                        queryRoleProperties(
                                scope, condition("groupId", "in", List.of("default_group")))));
        assertEquals(
                Set.of(),
                pageUserIds(queryRoleProperties(scope, condition("state", "in", List.of()))));
        assertEquals(
                Set.of(enabled, disabled, quoted),
                pageUserIds(queryRoleProperties(scope, condition("state", "nin", List.of()))));
        assertEquals(
                Set.of(missing, explicitNull),
                pageUserIds(queryRoleProperties(scope, condition("state", "isnull", ""))));
        assertEquals(
                Set.of(enabled, disabled, quoted),
                pageUserIds(queryRoleProperties(scope, condition("state", "notnull", ""))));
        assertEquals(
                Set.of(disabled, quoted),
                pageUserIds(queryRoleProperties(scope, condition("state", "not", "enabled"))));
        assertEquals(
                Set.of(enabled, disabled),
                pageUserIds(queryRoleProperties(scope, condition("state", "like", "%abled"))));
        assertEquals(
                Set.of(quoted),
                pageUserIds(queryRoleProperties(scope, condition("state", "nlike", "%abled"))));
        assertEquals(
                Set.of(quoted),
                pageUserIds(queryRoleProperties(scope, condition("state", "eq", "x' OR true --"))));
        assertEquals(
                Set.of(quoted),
                pageUserIds(
                        queryRoleProperties(scope, condition("state", "like", "%OR true --%"))));
        assertEquals(
                Set.of(enabled, disabled),
                pageUserIds(
                        queryRoleProperties(
                                scope,
                                Map.of(
                                        "terms",
                                        List.of(
                                                condition("state", "eq", "enabled"),
                                                Map.of(
                                                        "column", "groupId",
                                                        "termType", "eq",
                                                        "value", group,
                                                        "type", "or"))))));
    }

    private JsonNode queryRoleProperties(List<String> scope, Object... conditions) {
        List<Object> filters = new ArrayList<>();
        filters.add(condition("id", "in", scope));
        filters.addAll(Arrays.asList(conditions));
        return call("POST", "/role/_query", admin, Map.of("terms", filters), 200);
    }

    @Test
    void roleOptionsKeepNestedGroupsAndExposeOnlyTheirDirectRoles() {
        String parent = key(), child = key(), parentRole = key(), childRole = key();
        call("PATCH", "/role/group", admin, Map.of("id", parent, "name", "Parent group"), 200);
        call(
                "PATCH",
                "/role/group",
                admin,
                Map.of("id", child, "name", "Child group", "parentId", parent),
                200);
        call(
                "POST",
                "/role",
                admin,
                Map.of("id", parentRole, "name", "Parent role", "groupId", parent),
                200);
        call(
                "POST",
                "/role",
                admin,
                Map.of("id", childRole, "name", "Child role", "groupId", child),
                200);
        JsonNode tree = call("POST", "/role/group/detail/_query/tree", admin, Map.of(), 200);
        JsonNode parentGroup = findMenu(tree, parent);
        JsonNode childGroup = findMenu(parentGroup.path("children"), child);
        assertEquals(parent, parentGroup.path("id").asText());
        assertEquals(parent, parentGroup.path("groupId").asText());
        assertEquals("Parent group", parentGroup.path("name").asText());
        assertEquals("Parent group", parentGroup.path("groupName").asText());
        assertEquals(1, parentGroup.path("roles").size());
        assertEquals(parentRole, parentGroup.path("roles").get(0).path("id").asText());
        assertFalse(findMenu(parentGroup.path("children"), parentRole).isMissingNode());
        assertEquals(parent, childGroup.path("parentId").asText());
        assertEquals(child, childGroup.path("groupId").asText());
        assertEquals("Child group", childGroup.path("groupName").asText());
        assertEquals(1, childGroup.path("roles").size());
        assertEquals(childRole, childGroup.path("roles").get(0).path("id").asText());
        assertTrue(findMenu(parentGroup.path("roles"), childRole).isMissingNode());
    }

    @Test
    void positionMemberCountsUseExactDirectMembershipAcrossDetailAndPagedQueries() {
        String org = key();
        call(
                "POST",
                "/organization",
                admin,
                Map.of("id", org, "name", "Counted organization"),
                200);
        String parent = createPosition(org, "Lead", null, List.of()).path("id").asText();
        String child = createPosition(org, "Engineer", parent, List.of()).path("id").asText();
        String first = createUser("count-" + key()).path("id").asText();
        String second = createUser("count-" + key()).path("id").asText();
        String third = createUser("count-" + key()).path("id").asText();
        call(
                "POST",
                "/organization/" + org + "/users/_bind",
                admin,
                List.of(first, second, third),
                200);
        String parentMembers = "/organization/" + org + "/position/" + parent + "/users/";
        String childMembers = "/organization/" + org + "/position/" + child + "/users/";
        call("POST", parentMembers + "_bind", admin, List.of(first, second), 200);
        call("POST", parentMembers + "_bind", admin, List.of(first), 200);
        call("POST", childMembers + "_bind", admin, List.of(third), 200);
        JsonNode page = call("POST", "/position/_query/detail", admin, terms("orgId", org), 200);
        assertEquals(2, page.path("total").asInt());
        assertEquals(2, findMenu(page.path("data"), parent).path("memberCount").asLong());
        assertEquals(1, findMenu(page.path("data"), child).path("memberCount").asLong());
        assertEquals(
                2,
                call("GET", "/position/" + parent, admin, null, 200).path("memberCount").asLong());
        JsonNode all =
                call("POST", "/position/_query/detail/no-paging", admin, terms("orgId", org), 200);
        assertTrue(all.isArray());
        assertEquals(2, all.size());
        assertEquals(2, findMenu(all, parent).path("memberCount").asLong());
        JsonNode limited =
                call(
                        "POST",
                        "/position/_query/detail",
                        admin,
                        Map.of(
                                "pageSize",
                                1,
                                "terms",
                                List.of(condition("orgId", "eq", org)),
                                "sorts",
                                List.of(Map.of("name", "id", "order", "asc"))),
                        200);
        assertEquals(2, limited.path("total").asInt());
        assertEquals(1, limited.path("data").size());
        JsonNode only = limited.path("data").get(0);
        assertEquals(
                parent.equals(only.path("id").asText()) ? 2 : 1, only.path("memberCount").asLong());
        call("POST", parentMembers + "_unbind", admin, List.of(first, second), 200);
        assertEquals(
                0,
                call("GET", "/position/" + parent, admin, null, 200).path("memberCount").asLong());
        assertEquals(
                1,
                call("GET", "/position/" + child, admin, null, 200).path("memberCount").asLong());
    }

    @Test
    void organizationPositionMembershipAndInheritedRoleAreReal() {
        String org = key(), position = key(), role = key(), parentPosition = key();
        call("POST", "/organization", admin, Map.of("id", org, "name", "R&D"), 200);
        call("POST", "/role", admin, Map.of("id", role, "name", "Position role"), 200);
        call(
                "PUT",
                "/menu/role/" + role + "/_grant",
                admin,
                Map.of("menus", List.of(Map.of("id", "system-user", "buttons", List.of()))),
                200);
        call(
                "POST",
                "/position/" + org + "/_create",
                admin,
                Map.of("id", parentPosition, "name", "Lead", "roles", List.of(Map.of("id", role))),
                200);
        call(
                "POST",
                "/position/" + org + "/_create",
                admin,
                Map.of(
                        "id",
                        position,
                        "name",
                        "Engineer",
                        "parentId",
                        parentPosition,
                        "roles",
                        List.of()),
                200);
        String username = "pos-" + key();
        String user = createUser(username).path("id").asText();
        call("POST", "/organization/" + org + "/users/_bind", admin, List.of(user), 200);
        call(
                "POST",
                "/organization/" + org + "/position/" + position + "/users/_bind",
                admin,
                List.of(user),
                200);
        JsonNode detail = call("GET", "/user/detail/" + user, admin, null, 200);
        assertEquals(org, detail.path("orgIdList").get(0).asText());
        assertEquals(position, detail.path("positions").get(0).path("id").asText());
        JsonNode members =
                call("POST", "/user/_query/", admin, terms("id$in-org-user$org", org), 200);
        assertEquals(1, members.path("total").asInt());
        JsonNode positions =
                call("POST", "/position/_query/detail", admin, terms("orgId", org), 200);
        assertEquals("R&D", positions.path("data").get(0).path("orgName").asText());
        String member = login(username, PASSWORD);
        call("POST", "/user/detail/_query", member, Map.of(), 200);
        call("POST", "/user/detail/_create", member, Map.of("name", "Denied"), 403);
        call("DELETE", "/organization/" + org, admin, null, 409);
        call(
                "POST",
                "/organization/" + org + "/position/" + position + "/users/_unbind",
                admin,
                List.of(user),
                200);
        call("POST", "/user/detail/_query", member, Map.of(), 403);
        call("DELETE", "/position/" + position, admin, null, 200);
        call("DELETE", "/position/" + parentPosition, admin, null, 200);
        call("DELETE", "/organization/" + org, admin, null, 200);
        assertEquals(
                0, call("GET", "/user/detail/" + user, admin, null, 200).path("orgIdList").size());
    }

    @Test
    void treeValidationRollsBackCyclesAndDimensionCollisions() {
        String parent = key(), child = key();
        call("POST", "/organization", admin, Map.of("id", parent, "name", "Parent"), 200);
        call(
                "POST",
                "/organization",
                admin,
                Map.of("id", child, "name", "Child", "parentId", parent),
                200);
        call(
                "PATCH",
                "/organization",
                admin,
                Map.of("id", parent, "name", "Parent", "parentId", child),
                400);
        call("DELETE", "/organization/" + parent, admin, null, 409);
        call("POST", "/organization", admin, Map.of("id", "ADMIN", "name", "Collision"), 409);
        call("GET", "/organization/ADMIN", admin, null, 404);
        call(
                "POST",
                "/position",
                admin,
                Map.of("id", key(), "name", "Invalid", "orgId", key()),
                404);
        call("DELETE", "/organization/" + child, admin, null, 200);
        call("DELETE", "/organization/" + parent, admin, null, 200);
    }

    @Test
    void menuGrantsDriveBackendActionsAndCanBeRevoked() {
        String role = key(), username = "grant-" + key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Granted"), 200);
        String user = createUser(username).path("id").asText();
        call("POST", "/role/" + role + "/users/_bind", admin, List.of(user), 200);
        call(
                "PUT",
                "/menu/role/" + role + "/_grant",
                admin,
                Map.of(
                        "menus",
                        List.of(
                                Map.of(
                                        "id",
                                        "system-dictionary",
                                        "buttons",
                                        List.of(Map.of("id", "add", "granted", true))))),
                200);
        String member = login(username, PASSWORD);
        String dic = key();
        call(
                "PATCH",
                "/dictionary",
                member,
                Map.of("id", dic, "name", "Allowed", "status", 1),
                200);
        call("POST", "/dictionary/_query", member, terms("id", dic), 200);
        call("DELETE", "/dictionary/" + dic, member, null, 403);
        JsonNode tree = call("GET", "/menu/role/" + role + "/_grant/tree", admin, null, 200);
        assertTrue(tree.toString().contains("system-dictionary"));
        JsonNode own = call("POST", "/menu/user-own/tree", member, Map.of(), 200);
        assertTrue(own.toString().contains("system/Dictionary"));
        assertFalse(own.toString().contains("system/User"));
        call(
                "PUT",
                "/menu/role/" + role + "/_grant",
                admin,
                Map.of(
                        "menus",
                        List.of(
                                Map.of(
                                        "id",
                                        "system-dictionary",
                                        "buttons",
                                        List.of(Map.of("id", "unknown"))))),
                400);
        call(
                "PATCH",
                "/role",
                admin,
                Map.of("id", role, "name", "Granted", "state", "disabled"),
                200);
        call("POST", "/dictionary/_query", member, Map.of(), 403);
        call(
                "PATCH",
                "/role",
                admin,
                Map.of("id", role, "name", "Granted", "state", "enabled"),
                200);
        call("POST", "/dictionary/_query", member, Map.of(), 200);
        call("PUT", "/menu/role/" + role + "/_grant", admin, Map.of("menus", List.of()), 200);
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> call("POST", "/dictionary/_query", member, Map.of(), 403));
    }

    @Test
    void officialDictionaryModuleStoresExportsAndDeletesItems() {
        String dic = key();
        call("PATCH", "/dictionary", admin, Map.of("id", dic, "name", "States", "status", 1), 200);
        call(
                "PATCH",
                "/dictionary-item",
                admin,
                List.of(
                        Map.of(
                                "dictId", dic, "name", "Open", "value", "open", "text", "Open",
                                "status", 1),
                        Map.of(
                                "dictId", dic, "name", "Closed", "value", "closed", "text",
                                "Closed", "status", 1)),
                200);
        JsonNode items = call("POST", "/dictionary-item/_query", admin, terms("dictId", dic), 200);
        assertEquals(2, items.path("total").asInt());
        JsonNode export = call("POST", "/dictionary/detail/_query", admin, terms("id", dic), 200);
        assertEquals(2, export.get(0).path("items").size());
        assertEquals(2, countRecords("s_dictionary_item", "dict_id", dic));
        call("DELETE", "/dictionary/" + dic, admin, null, 200);
        assertEquals(0, countRecords("s_dictionary_item", "dict_id", dic));
        assertEquals(
                0,
                call("POST", "/dictionary-item/_query", admin, terms("dictId", dic), 200)
                        .path("total")
                        .asInt());
    }

    @Test
    void brandingConfigPersistsAndAnonymousFieldsStayLimited() {
        call(
                "POST",
                "/system/config/scope/_save",
                admin,
                List.of(
                        Map.of(
                                "scope",
                                "front",
                                "properties",
                                Map.of(
                                        "title",
                                        "Persisted",
                                        "headerTheme",
                                        "dark",
                                        "showRecordNumber",
                                        true)),
                        Map.of("scope", "amap", "properties", Map.of("key", "private-map-key"))),
                200);
        assertEquals(
                "Persisted",
                call("GET", "/api/system/config/front", null, null, 200).path("title").asText());
        assertEquals("Persisted", configs.get("front").block().get("title"));
        call("GET", "/system/config/amap", null, null, 401);
        call("POST", "/system/config/front", admin, Map.of("password", "secret"), 400);
        call("POST", "/system/config/front", admin, Map.of("showRecordNumber", "true"), 400);
        long stored =
                database.sql(
                                "SELECT count(*) AS n FROM s_system_config WHERE id='front' AND"
                                        + " properties::jsonb->>'title'='Persisted'")
                        .map((r, m) -> r.get("n", Long.class))
                        .one()
                        .block();
        assertEquals(1, stored);
    }

    @Test
    void memberCanUploadAndSaveOwnAvatarWithoutManagementPermissions() {
        String username = "avatar-owner-" + key();
        String owner =
                call(
                                "POST",
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "user",
                                        Map.of(
                                                "name",
                                                "Avatar owner",
                                                "username",
                                                username,
                                                "password",
                                                PASSWORD),
                                        "roleIdList",
                                        List.of("MEMBER")),
                                200)
                        .path("id")
                        .asText();
        String other = createUser("avatar-other-" + key()).path("id").asText();
        JsonNode otherBefore = call("GET", "/user/detail/" + other, admin, null, 200);
        String member = login(username, PASSWORD);
        call("POST", "/user/detail/_query", member, Map.of(), 403);
        byte[] png = pngImage();
        uploadImage("/file/upload", member, "avatar.png", png, 403);
        uploadImage("/file/static", member, "avatar.png", png, 403);

        JsonNode before = call("GET", "/user/detail", member, null, 200);
        JsonNode uploaded = uploadImage("/api/user/me/avatar", member, "avatar.png", png, 200);
        String url = uploaded.path("url").asText();
        assertTrue(url.matches("/api/file/static/[0-9]{8}/[a-f0-9-]{36}\\.png"));
        assertFalse(uploaded.path("accessUrl").asText().isBlank());
        assertEquals(before, call("GET", "/user/detail", member, null, 200));
        client.get()
                .uri(url)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.IMAGE_PNG)
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectBody()
                .consumeWith(response -> assertArrayEquals(png, response.getResponseBody()));

        call("PUT", "/user/detail", member, Map.of("name", "Avatar owner", "avatar", url), 200);
        assertEquals(url, call("GET", "/user/detail", member, null, 200).path("avatar").asText());
        assertEquals(
                url,
                call("GET", "/user/detail/" + owner, admin, null, 200).path("avatar").asText());
        assertEquals(
                url,
                database.sql("SELECT avatar FROM s_user_detail WHERE id=:id")
                        .bind("id", owner)
                        .map((row, metadata) -> row.get("avatar", String.class))
                        .one()
                        .block());
        call(
                "PUT",
                "/user/detail",
                member,
                Map.of("id", other, "name", "Forbidden", "avatar", url),
                400);
        call(
                "PUT",
                "/user/detail/" + other + "/_update",
                member,
                Map.of("name", "Forbidden", "avatar", url),
                403);
        assertEquals(otherBefore, call("GET", "/user/detail/" + other, admin, null, 200));
        call("POST", "/user/detail/_query", member, Map.of(), 403);
        uploadImage("/file/upload", member, "avatar.png", png, 403);
    }

    @Test
    void selfAvatarUploadRequiresLoginAndKeepsStorageValidation() throws Exception {
        String username = "avatar-validation-" + key();
        createUser(username);
        String token = login(username, PASSWORD);
        byte[] png = pngImage();
        JsonNode before = call("GET", "/user/detail", token, null, 200);
        long stored;
        try (var paths = Files.walk(FILES)) {
            stored = paths.filter(Files::isRegularFile).count();
        }
        uploadImage("/user/me/avatar", null, "avatar.png", png, 401);
        uploadImage(
                "/user/me/avatar",
                token,
                "avatar.png",
                "<script>alert(1)</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                400);
        uploadImage("/user/me/avatar", token, "avatar.svg", png, 400);
        uploadImage(
                "/user/me/avatar",
                token,
                "oversized.png",
                Arrays.copyOf(png, 5 * 1024 * 1024 + 1),
                400);
        try (var paths = Files.walk(FILES)) {
            assertEquals(stored, paths.filter(Files::isRegularFile).count());
        }
        assertEquals(before, call("GET", "/user/detail", token, null, 200));
        // 无角色的已登录用户同样可上传；上传不依赖 MEMBER 动作，也不自动保存头像。
        uploadImage("/user/me/avatar", token, "avatar.png", png, 200);
        assertEquals(before, call("GET", "/user/detail", token, null, 200));
        call("POST", "/user/detail/_query", token, Map.of(), 403);
    }

    @Test
    void fileEndpointsUseOnlyTheApplicationStorageAndController() {
        // 保留 hsweb SPI，但其缺省存储和控制器不得绕过本平台的图片及权限边界。
        var storage =
                context.getBeansOfType(
                        org.hswebframework.web.file.service.FileStorageService.class);
        assertEquals(1, storage.size());
        assertInstanceOf(
                com.gaiys.proto.system.service.BrandingFileStorageService.class,
                storage.values().iterator().next());
        assertTrue(
                context.getBeansOfType(
                                org.hswebframework.web.file.service.LocalFileStorageService.class)
                        .isEmpty());
        assertInstanceOf(
                com.gaiys.proto.system.web.BrandingFileController.class,
                context.getBean("reactiveFileController"));
        assertTrue(
                context.getBeansOfType(org.hswebframework.web.file.web.ReactiveFileController.class)
                        .isEmpty());
    }

    @Test
    void hswebFilenameResolutionKeepsUntrustedSuffixPathsInsideTheConfiguredStorage() {
        var properties = new org.hswebframework.web.file.FileUploadProperties();
        properties.setStaticFilePath(FILES.toString());
        // 上游旧实现会解析原始路径或将目录段当作扩展名；两种官方命名策略均须限制写入根。
        for (boolean originalName : List.of(false, true)) {
            properties.setUseOriginalFileName(originalName);
            for (String filename :
                    List.of(
                            "avatar.png/../../../../outside-no-extension",
                            "avatar.png\\..\\..\\..\\..\\outside-no-extension",
                            "avatar.png／..／..／..／..／outside-no-extension")) {
                var info = properties.createStaticSavePath(filename);
                Path resolved = Path.of(info.getSavePath()).toAbsolutePath().normalize();
                assertTrue(resolved.startsWith(FILES));
                Path relative = FILES.relativize(resolved);
                assertTrue(relative.getName(0).toString().matches("[0-9]{8}"));
                assertEquals(originalName ? 3 : 2, relative.getNameCount());
            }
        }
    }

    @Test
    void uploadedOriginalFilenamesCannotSelectStoragePathsOrOverwriteExistingFiles()
            throws Exception {
        byte[] png = pngImage();
        Path sentinel = Files.createTempFile(FILES, "filename-boundary-", ".png");
        Path traversalSentinel = Files.createTempFile(FILES, "suffix-traversal-", "");
        byte[] original =
                "existing-file-must-survive".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(sentinel, original);
        Files.write(traversalSentinel, original);
        try {
            // 只攻击测试目录内的哨兵；外部输入只允许决定受校验的扩展名，不能决定存储路径。
            List<String> filenames =
                    List.of(
                            "../" + sentinel.getFileName(),
                            sentinel.toAbsolutePath().toString(),
                            "C:\\uploads\\..\\" + sentinel.getFileName(),
                            "folder／..／avatar.png");
            for (String endpoint : List.of("/file/upload", "/file/static", "/user/me/avatar")) {
                for (String filename : filenames) {
                    Set<Path> before;
                    try (var paths = Files.walk(FILES)) {
                        before =
                                paths.filter(Files::isRegularFile)
                                        .collect(java.util.stream.Collectors.toSet());
                    }
                    JsonNode uploaded = uploadImage(endpoint, admin, filename, png, 200);
                    String url =
                            uploaded.isTextual()
                                    ? uploaded.asText()
                                    : uploaded.path("url").asText();
                    assertTrue(url.matches("/api/file/static/[0-9]{8}/[a-f0-9-]{36}\\.png"));
                    Path saved = FILES.resolve(url.substring("/api/file/static/".length()));
                    Set<Path> created;
                    try (var paths = Files.walk(FILES)) {
                        created =
                                paths.filter(Files::isRegularFile)
                                        .collect(java.util.stream.Collectors.toSet());
                    }
                    created.removeAll(before);
                    assertEquals(Set.of(saved), created);
                    assertArrayEquals(png, Files.readAllBytes(saved));
                    assertArrayEquals(original, Files.readAllBytes(sentinel));
                    client.get()
                            .uri(url)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectHeader()
                            .valueEquals("X-Content-Type-Options", "nosniff")
                            .expectBody()
                            .consumeWith(
                                    response -> assertArrayEquals(png, response.getResponseBody()));
                }
                long stored;
                try (var paths = Files.walk(FILES)) {
                    stored = paths.filter(Files::isRegularFile).count();
                }
                for (String filename :
                        List.of(
                                "avatar.png/",
                                "avatar.png\\",
                                "avatar．png",
                                "avatar.png/../../" + traversalSentinel.getFileName(),
                                "avatar.png\\..\\..\\" + traversalSentinel.getFileName())) {
                    uploadImage(endpoint, admin, filename, png, 400);
                }
                try (var paths = Files.walk(FILES)) {
                    assertEquals(stored, paths.filter(Files::isRegularFile).count());
                }
                assertArrayEquals(original, Files.readAllBytes(sentinel));
                assertArrayEquals(original, Files.readAllBytes(traversalSentinel));
            }
        } finally {
            Files.deleteIfExists(sentinel);
            Files.deleteIfExists(traversalSentinel);
        }
    }

    @Test
    void brandingUploadIsAuthorizedReadableAndRejectsActiveContent() {
        byte[] png =
                Base64.getDecoder()
                        .decode(
                                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jY1sAAAAASUVORK5CYII=");
        var body = new org.springframework.http.client.MultipartBodyBuilder();
        body.part("file", new org.springframework.core.io.ByteArrayResource(png))
                .filename("logo.png");
        JsonNode uploaded =
                client.post()
                        .uri("/file/upload")
                        .header("X-Access-Token", admin)
                        .body(
                                org.springframework.web.reactive.function.BodyInserters
                                        .fromMultipartData(body.build()))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        client.get()
                .uri(uploaded.path("accessUrl").asText())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.IMAGE_PNG)
                .expectBody()
                .consumeWith(response -> assertArrayEquals(png, response.getResponseBody()));
        call(
                "POST",
                "/system/config/paths",
                admin,
                Map.of("base-path", "https://branding.example/api/"),
                200);
        JsonNode external =
                client.post()
                        .uri("/file/upload")
                        .header("X-Access-Token", admin)
                        .body(
                                org.springframework.web.reactive.function.BodyInserters
                                        .fromMultipartData(body.build()))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        assertEquals(
                "https://branding.example" + external.path("url").asText(),
                external.path("accessUrl").asText());
        client.get().uri(external.path("url").asText()).exchange().expectStatus().isOk();
        call(
                "POST",
                "/system/config/paths",
                admin,
                Map.of("base-path", "javascript:alert(1)"),
                400);
        call("POST", "/system/config/paths", admin, Map.of("base-path", "/api"), 200);
        client.post()
                .uri("/file/upload")
                .body(
                        org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                                body.build()))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        var malicious = new org.springframework.http.client.MultipartBodyBuilder();
        malicious
                .part(
                        "file",
                        new org.springframework.core.io.ByteArrayResource(
                                "<script>alert(1)</script>".getBytes()))
                .filename("logo.png");
        client.post()
                .uri("/file/upload")
                .header("X-Access-Token", admin)
                .body(
                        org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                                malicious.build()))
                .exchange()
                .expectStatus()
                .isBadRequest();
        var oversized = new org.springframework.http.client.MultipartBodyBuilder();
        oversized
                .part(
                        "file",
                        new org.springframework.core.io.ByteArrayResource(
                                new byte[6 * 1024 * 1024]))
                .filename("oversized.png");
        client.post()
                .uri("/file/upload")
                .header("X-Access-Token", admin)
                .body(
                        org.springframework.web.reactive.function.BodyInserters.fromMultipartData(
                                oversized.build()))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void unboundUserNeverInheritsUnrelatedPositionOrAdministratorRoles() {
        String role = key(), org = key(), pos = key();
        call("POST", "/organization", admin, Map.of("id", org, "name", "Isolated"), 200);
        call("POST", "/role", admin, Map.of("id", role, "name", "Restricted"), 200);
        call(
                "POST",
                "/position/" + org + "/_create",
                admin,
                Map.of("id", pos, "name", "Assigned", "roles", List.of(Map.of("id", role))),
                200);
        String username = "unbound-" + key();
        createUser(username);
        String token = login(username, PASSWORD);
        call("POST", "/user/detail/_query", token, Map.of(), 403);
        call("PATCH", "/dictionary", token, Map.of("id", key(), "name", "Denied"), 403);
        JsonNode auth = call("GET", "/authorize/me", token, null, 200);
        for (JsonNode dimension : auth.path("dimensions"))
            assertFalse(
                    java.util.Set.of("role", "org", "position")
                            .contains(dimension.path("type").path("id").asText()));
    }

    @Test
    void permissionAndMenuMetadataAreStoredAndAdministratorCannotBeAccidentallyShadowed() {
        String permission = key(), menu = key();
        call(
                "PATCH",
                "/permission",
                admin,
                Map.of(
                        "id",
                        permission,
                        "name",
                        "Custom",
                        "actions",
                        List.of(Map.of("action", "query", "name", "Read"))),
                200);
        assertFalse(
                call("GET", "/permission/id/_validate?id=" + permission, admin, null, 200)
                        .path("passed")
                        .asBoolean());
        call(
                "POST",
                "/menu",
                admin,
                Map.of(
                        "id",
                        menu,
                        "name",
                        "Custom menu",
                        "code",
                        "custom/" + menu,
                        "componentCode",
                        "system/User",
                        "url",
                        "/custom/" + menu,
                        "permission",
                        "user",
                        "buttons",
                        List.of(Map.of("id", "query", "name", "Read"))),
                200);
        JsonNode saved = call("GET", "/menu/" + menu, admin, null, 200);
        assertEquals("system/User", saved.path("componentCode").asText());
        assertEquals("query", saved.path("buttons").get(0).path("id").asText());
        assertFalse(
                call("GET", "/menu/code/_validate?code=custom/" + menu, admin, null, 200)
                        .path("passed")
                        .asBoolean());
        call("POST", "/user/detail/_query", admin, Map.of(), 200);
        call("DELETE", "/menu/" + menu, admin, null, 200);
        call("DELETE", "/permission/" + permission, admin, null, 200);
    }

    @Test
    void menuGrantUpdatesExistingOfficialAuthorizationSetting() {
        String role = key(), authorization = key();
        call("POST", "/role", admin, Map.of("id", role, "name", "Existing grant"), 200);
        call(
                "PATCH",
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        authorization,
                        "permission",
                        "dictionary",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        List.of("query"),
                        "state",
                        1),
                200);
        JsonNode existingTree =
                call("GET", "/menu/role/" + role + "/_grant/tree", admin, null, 200);
        JsonNode dictionary = findMenu(existingTree, "system-dictionary");
        assertTrue(dictionary.path("granted").asBoolean());
        assertFalse(findMenu(dictionary.path("buttons"), "add").path("granted").asBoolean());
        assertTrue(findMenu(dictionary.path("buttons"), "down").path("granted").asBoolean());
        call(
                "PUT",
                "/menu/role/" + role + "/_grant",
                admin,
                Map.of(
                        "menus",
                        List.of(
                                Map.of(
                                        "id",
                                        "system-dictionary",
                                        "buttons",
                                        List.of(Map.of("id", "add"))))),
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
                                        "system-dictionary",
                                        "buttons",
                                        List.of(Map.of("id", "add"))))),
                200);
        assertEquals(
                1,
                call("POST", "/autz-setting/_query", admin, terms("id", authorization), 200)
                        .path("total")
                        .asInt());
        String username = "existing-" + key();
        String user = createUser(username).path("id").asText();
        call("POST", "/role/" + role + "/users/_bind", admin, List.of(user), 200);
        String token = login(username, PASSWORD);
        call("PATCH", "/dictionary", token, Map.of("id", key(), "name", "Created"), 200);
    }

    private JsonNode findMenu(JsonNode roots, String id) {
        for (JsonNode node : roots) {
            if (id.equals(node.path("id").asText())) return node;
            JsonNode child = findMenu(node.path("children"), id);
            if (!child.isMissingNode()) return child;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private long countRecords(String table, String column, String value) {
        return database.sql(
                        "SELECT count(*) AS total FROM " + table + " WHERE " + column + "=:value")
                .bind("value", value)
                .map((row, metadata) -> row.get("total", Long.class))
                .one()
                .block();
    }
}
