package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.system.web.handler.ApiExceptionHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.server.MethodNotAllowedException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 以实际 HTTP、认证和 PostgreSQL 验证前端用户契约及框架继承路由，不模拟服务实现。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class IdentityApiContractIntegrationTest {
    static final String PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

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
    @Autowired ObjectMapper mapper;
    @Autowired DatabaseClient database;
    @Autowired ApplicationContext context;
    String admin;

    @BeforeEach
    void authenticate() {
        client = client.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        admin = login("admin", PASSWORD);
    }

    JsonNode call(String method, String path, String token, Object body, int status) {
        var request = client.method(HttpMethod.valueOf(method)).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) {
            request.contentType(
                    body instanceof String ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON);
            request.bodyValue(body);
        }
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

    String login(String username, String password) {
        String token =
                call(
                                "POST",
                                "/authorize/login",
                                null,
                                Map.of("username", username, "password", password),
                                200)
                        .path("token")
                        .asText();
        assertFalse(token.isBlank());
        return token;
    }

    record Actor(String id, String username, String token) {}

    Actor actor(String name, Map<String, Object> contacts) {
        String username = "audit-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> account = new LinkedHashMap<>(contacts);
        account.put("username", username);
        account.put("name", name);
        account.put("password", PASSWORD);
        String id =
                call(
                                "POST",
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "user",
                                        account,
                                        "roleIdList",
                                        List.of("MEMBER"),
                                        "orgIdList",
                                        List.of(),
                                        "positions",
                                        List.of()),
                                200)
                        .path("id")
                        .asText();
        return new Actor(id, username, login(username, PASSWORD));
    }

    Map<String, Object> term(String column, String operator, Object value) {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("column", column);
        // 真实前端包装没有外层 termType；Term.setColumn 把 $user-detail 解析成操作符。
        if (!"id$user-detail".equals(column)) term.put("termType", operator);
        term.put("value", value);
        return term;
    }

    Map<String, Object> relation(Map<String, Object> term, String type) {
        Map<String, Object> related = new LinkedHashMap<>(term);
        related.put("type", type);
        return related;
    }

    Set<String> queryUsers(List<Actor> scope, List<Map<String, Object>> terms) {
        JsonNode rows =
                call(
                                "POST",
                                "/user/detail/_query",
                                admin,
                                Map.of(
                                        "pageSize",
                                        200,
                                        "terms",
                                        List.of(
                                                Map.of("terms", terms),
                                                term(
                                                        "id",
                                                        "in",
                                                        scope.stream().map(Actor::id).toList()))),
                                200)
                        .path("data");
        Set<String> result = new TreeSet<>();
        rows.forEach(row -> result.add(row.path("id").asText()));
        return result;
    }

    @Test
    void nestedAndFlatManagementRequestsKeepPathIdentityAndWritableFieldBoundaries() {
        Actor actor =
                actor(
                        "Nested user",
                        Map.of("telephone", "13300000001", "email", "nested@example.test"));
        JsonNode saved = call("GET", "/user/detail/" + actor.id(), admin, null, 200);
        assertEquals("nested@example.test", saved.path("email").asText());
        assertFalse(saved.has("password"));
        assertFalse(saved.has("salt"));
        call(
                "PUT",
                "/user/detail/" + actor.id() + "/_update",
                admin,
                Map.of(
                        "id",
                        "admin",
                        "user",
                        Map.of(
                                "id",
                                "admin",
                                "name",
                                "Nested updated",
                                "username",
                                "admin",
                                "type",
                                "admin",
                                "status",
                                0,
                                "salt",
                                "injected",
                                "telephone",
                                "13300000002")),
                200);
        JsonNode updated = call("GET", "/user/detail/" + actor.id(), admin, null, 200);
        assertEquals(actor.id(), updated.path("id").asText());
        assertEquals(actor.username(), updated.path("username").asText());
        assertEquals("normal", updated.path("type").path("id").asText());
        assertEquals(1, updated.path("status").asInt());
        assertEquals("13300000002", updated.path("telephone").asText());
        assertEquals("nested@example.test", updated.path("email").asText());
        assertFalse(login(actor.username(), PASSWORD).isBlank());
        call(
                "PUT",
                "/user/detail/" + actor.id() + "/_update",
                admin,
                Map.of("name", "Flat compatibility", "email", "flat@example.test"),
                200);
        assertEquals(
                "Flat compatibility",
                call("GET", "/user/detail/" + actor.id(), admin, null, 200).path("name").asText());
        call(
                "PUT",
                "/user/detail/" + actor.id() + "/_update",
                admin,
                Map.of("user", Map.of("name", "Must roll back", "email", "invalid")),
                400);
        assertEquals(
                "Flat compatibility",
                call("GET", "/user/detail/" + actor.id(), admin, null, 200).path("name").asText());
        for (Object invalid :
                List.of("wrong envelope", List.of(), Map.of("name", List.of("invalid")))) {
            call("POST", "/user/detail/_create", admin, Map.of("user", invalid), 400);
        }
        call(
                "POST",
                "/user/detail/_create",
                admin,
                Map.of("user", Map.of("name", "Nested"), "name", "Ambiguous"),
                400);
        JsonNode flat =
                call(
                        "POST",
                        "/user/detail/_create",
                        admin,
                        Map.of(
                                "name",
                                "Flat create",
                                "username",
                                "flat-" + UUID.randomUUID(),
                                "password",
                                PASSWORD,
                                "id",
                                "admin",
                                "status",
                                0,
                                "type",
                                "admin",
                                "roleIdList",
                                List.of()),
                        200);
        assertNotEquals("admin", flat.path("id").asText());
        assertEquals(1, flat.path("status").asInt());
        assertEquals("normal", flat.path("type").path("id").asText());
    }

    @Test
    void anOrdinaryUserPersistsSelfProfileWithoutManagementPermissionAndCannotChangeIdentity() {
        Actor actor =
                actor(
                        "Before profile",
                        Map.of("email", "old@example.test", "telephone", "13300000003"));
        call("POST", "/user/detail/_query", actor.token(), Map.of(), 403);
        call(
                "PUT",
                "/api/user/detail",
                actor.token(),
                Map.of(
                        "name",
                        "After profile",
                        "avatar",
                        "/avatar.svg",
                        "telephone",
                        "13300000004",
                        "email",
                        "new@example.test"),
                200);
        JsonNode profile = call("GET", "/user/detail", actor.token(), null, 200);
        assertEquals(actor.id(), profile.path("id").asText());
        assertEquals("After profile", profile.path("name").asText());
        assertEquals("/avatar.svg", profile.path("avatar").asText());
        assertEquals("13300000004", profile.path("telephone").asText());
        assertEquals("new@example.test", profile.path("email").asText());
        assertFalse(profile.has("password"));
        assertFalse(profile.has("salt"));
        call("PUT", "/user/detail", actor.token(), Map.of("name", "Only name"), 200);
        assertEquals(
                "new@example.test",
                call("GET", "/user/detail", actor.token(), null, 200).path("email").asText());
        Map<String, Object> clear = new LinkedHashMap<>();
        clear.put("name", "Cleared contact");
        clear.put("email", null);
        call("PUT", "/user/detail", actor.token(), clear, 200);
        assertEquals(
                "", call("GET", "/user/detail", actor.token(), null, 200).path("email").asText());
        for (String forbidden :
                List.of("id", "username", "password", "salt", "status", "type", "roleIdList")) {
            call(
                    "PUT",
                    "/user/detail",
                    actor.token(),
                    Map.of("name", "Forbidden", forbidden, "admin"),
                    400);
        }
        call(
                "PUT",
                "/user/detail",
                actor.token(),
                Map.of("name", "Invalid update", "email", "bad-email"),
                400);
        assertEquals(
                "Cleared contact",
                call("GET", "/user/detail", actor.token(), null, 200).path("name").asText());
        // MEMBER 的原生自服务动作不授予 user:query，/me 仍只修改姓名。
        call("PUT", "/user/me", actor.token(), Map.of("name", "Native name"), 200);
        assertEquals(
                "Native name",
                call("GET", "/user/detail", actor.token(), null, 200).path("name").asText());
        call("PUT", "/user/me", actor.token(), Map.of("name", "Unsafe", "id", "admin"), 400);
        call("PUT", "/user/detail", null, Map.of("name", "Anonymous"), 401);
    }

    @Test
    void plaintextPasswordValidationAndChangeUseTheAuthenticatedAccountAndServerPolicy() {
        Actor actor = actor("Password owner", Map.of());
        assertTrue(
                call("POST", "/user/me/password/_validate", actor.token(), PASSWORD, 200)
                        .path("passed")
                        .asBoolean());
        assertFalse(
                call("POST", "/user/me/password/_validate", actor.token(), "WrongPassword123!", 200)
                        .path("passed")
                        .asBoolean());
        for (int length : List.of(11, 12, 128, 129)) {
            assertEquals(
                    length >= 12 && length <= 128,
                    call("POST", "/user/password/_validate", actor.token(), "x".repeat(length), 200)
                            .path("passed")
                            .asBoolean());
        }
        call("POST", "/user/username/_validate", actor.token(), actor.username(), 403);
        call("POST", "/user/me/password/_validate", null, PASSWORD, 401);
        call("POST", "/user/password/_validate", null, PASSWORD, 401);
        call(
                "PUT",
                "/user/passwd",
                actor.token(),
                Map.of("oldPassword", "WrongPassword123!", "newPassword", PASSWORD),
                400);
        assertTrue(
                call("POST", "/user/me/password/_validate", actor.token(), PASSWORD, 200)
                        .path("passed")
                        .asBoolean());
        String next = UUID.randomUUID().toString();
        call(
                "PUT",
                "/user/passwd",
                actor.token(),
                Map.of("oldPassword", PASSWORD, "newPassword", next),
                200);
        String nextToken = login(actor.username(), next);
        assertFalse(
                call("POST", "/user/me/password/_validate", nextToken, PASSWORD, 200)
                        .path("passed")
                        .asBoolean());
        assertTrue(
                call("POST", "/user/me/password/_validate", nextToken, next, 200)
                        .path("passed")
                        .asBoolean());
    }

    @Test
    void legacyContactDslPreservesNestedRelationsNullNegationAndEmptyMatches() {
        Actor first =
                actor(
                        "Contact first",
                        Map.of("telephone", "13333333333", "email", "first@example.test"));
        Actor second = actor("Contact second", Map.of("telephone", "14444444444"));
        Actor nullable = actor("Contact null", Map.of());
        Actor absent = actor("Contact absent", Map.of());
        database.sql("DELETE FROM s_user_detail WHERE id=:id")
                .bind("id", absent.id())
                .fetch()
                .rowsUpdated()
                .block(Duration.ofSeconds(10));
        List<Actor> scope = List.of(first, second, nullable, absent);
        assertEquals(
                Set.of(first.id()),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(term("email", "like", "%first%"))))));
        assertEquals(
                Set.of(second.id()),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(term("telephone", "not", "13333333333"))))));
        assertEquals(
                Set.of(second.id()),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(term("telephone", "nlike", "%333%"))))));
        assertEquals(
                Set.of(second.id()),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(
                                                term(
                                                        "telephone",
                                                        "nin",
                                                        List.of("13333333333")))))));
        assertEquals(
                Set.of(nullable.id(), absent.id()),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(term("telephone", "isnull", null))))));
        assertEquals(
                Set.of(first.id(), second.id()),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(term("telephone", "notnull", null))))));
        assertEquals(
                Set.of(),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(term("telephone", "eq", "unmatched"))))));
        assertEquals(
                Set.of(first.id(), second.id()),
                queryUsers(
                        scope,
                        List.of(
                                term(
                                        "id$user-detail",
                                        "eq",
                                        List.of(
                                                term("email", "eq", "first@example.test"),
                                                relation(
                                                        term("telephone", "eq", "14444444444"),
                                                        "or"))))));
        assertEquals(
                Set.of(first.id(), second.id()),
                queryUsers(
                        scope,
                        List.of(
                                term("name", "eq", "Contact first"),
                                relation(
                                        term(
                                                "id$user-detail",
                                                "eq",
                                                List.of(term("telephone", "eq", "14444444444"))),
                                        "or"))));
        assertEquals(
                Set.of(first.id()),
                queryUsers(scope, List.of(term("telephone", "eq", "13333333333"))));
        for (Object value :
                List.of(List.of(), List.of(term("username", "eq", "admin")), "invalid")) {
            call(
                    "POST",
                    "/user/detail/_query",
                    admin,
                    Map.of("terms", List.of(term("id$user-detail", "eq", value))),
                    400);
        }
        for (String invalid :
                List.of(
                        "{\"terms\":[null]}",
                        "{\"terms\":[{\"terms\":[null]}]}",
                        "{\"sorts\":[null]}")) {
            try {
                call("POST", "/user/detail/_query", admin, mapper.readTree(invalid), 400);
                call("POST", "/role/_query", admin, mapper.readTree(invalid), 400);
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        }
    }

    @Test
    void defaultGroupAliasAndDescriptionWorkForPagerAllAndMixedNestedQueries() {
        String prefix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String first = prefix + "a";
        String second = prefix + "b";
        String excluded = prefix + "c";
        for (String id : List.of(first, second, excluded)) {
            JsonNode role =
                    call(
                            "POST",
                            "/role",
                            admin,
                            Map.of(
                                    "id",
                                    id,
                                    "name",
                                    id,
                                    "groupId",
                                    "default_group",
                                    "description",
                                    id.equals(excluded)
                                            ? "Other"
                                            : id.equals(first) ? "Alpha" : "Beta"),
                            200);
            assertEquals("default", role.path("groupId").asText());
        }
        assertEquals(
                "default",
                call("GET", "/role/" + first, admin, null, 200).path("groupId").asText());
        List<Map<String, Object>> conditions =
                List.of(
                        Map.of(
                                "terms",
                                List.of(
                                        term("description", "eq", "Alpha"),
                                        relation(term("description", "eq", "Beta"), "or"))),
                        term("groupId", "eq", "default_group"),
                        term("id", "in", List.of(first, second, excluded)));
        Map<String, Object> query =
                Map.of(
                        "terms",
                        conditions,
                        "sorts",
                        List.of(Map.of("name", "description", "order", "asc")));
        JsonNode page = call("POST", "/role/_query", admin, query, 200);
        assertEquals(2, page.path("total").asInt());
        assertEquals(first, page.path("data").get(0).path("id").asText());
        assertEquals(second, page.path("data").get(1).path("id").asText());
        JsonNode all = call("POST", "/role/_query/no-paging", admin, query, 200);
        assertEquals(2, all.size());
        JsonNode in =
                call(
                        "POST",
                        "/role/_query",
                        admin,
                        Map.of(
                                "terms",
                                List.of(
                                        term(
                                                "groupId",
                                                "in",
                                                List.of("default_group", "unknown-group")),
                                        term("id", "eq", first))),
                        200);
        assertEquals(1, in.path("total").asInt());
        assertEquals(
                0,
                call(
                                "POST",
                                "/role/_query",
                                admin,
                                Map.of(
                                        "terms",
                                        List.of(
                                                term("groupId", "nin", List.of("default_group")),
                                                term("id", "eq", first))),
                                200)
                        .path("total")
                        .asInt());
        assertEquals(
                0,
                call(
                                "POST",
                                "/role/_query",
                                admin,
                                Map.of("terms", List.of(term("groupId", "eq", "unknown-group"))),
                                200)
                        .path("total")
                        .asInt());
        call(
                "POST",
                "/role",
                admin,
                Map.of("name", "Unknown group", "groupId", "unknown-group"),
                404);
    }

    @Test
    void preferredPostAndCompatibleGetResetOnlyTheCurrentToken() {
        for (String method : List.of("POST", "GET")) {
            Actor actor = actor("Token owner", Map.of());
            String otherSession = login(actor.username(), PASSWORD);
            assertTrue(call(method, "/user-token/reset", actor.token(), null, 200).asBoolean());
            call("GET", "/user/detail", actor.token(), null, 401);
            call("GET", "/user/detail", otherSession, null, 200);
            call(method, "/user-token/reset", null, null, 401);
        }
    }

    @Test
    void methodNotAllowedPreservesAllowAndJsonThroughBothErrorBoundaries() {
        client.delete()
                .uri("/system/version")
                .header("X-Access-Token", admin)
                .exchange()
                .expectStatus()
                .isEqualTo(405)
                .expectHeader()
                .value("Allow", value -> assertTrue(value.contains("GET")))
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(405);
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/test"));
        var response =
                new ApiExceptionHandler()
                        .handle(
                                new MethodNotAllowedException("POST", List.of(HttpMethod.GET)),
                                Locale.ENGLISH,
                                exchange);
        assertEquals(Set.of(HttpMethod.GET), response.getHeaders().getAllow());
        assertEquals(405, response.getStatusCode().value());
    }

    @Test
    void exportActualInheritedMappingsAndOpenApiForContractCrossChecking() throws Exception {
        List<Map<String, Object>> routes = new ArrayList<>();
        Set<String> pairs = new TreeSet<>();
        for (var mappingBean :
                context.getBeansOfType(RequestMappingInfoHandlerMapping.class).entrySet()) {
            for (var entry : mappingBean.getValue().getHandlerMethods().entrySet()) {
                RequestMappingInfo mapping = entry.getKey();
                HandlerMethod handler = entry.getValue();
                Set<String> paths =
                        new TreeSet<>(
                                mapping.getPatternsCondition().getPatterns().stream()
                                        .map(Object::toString)
                                        .toList());
                Set<RequestMethod> declared = mapping.getMethodsCondition().getMethods();
                List<String> methods =
                        declared.isEmpty()
                                ? List.of("ANY")
                                : declared.stream().map(Enum::name).sorted().toList();
                for (String path : paths) {
                    for (String method : methods) {
                        pairs.add(method + " " + path);
                        Map<String, Object> route = new LinkedHashMap<>();
                        route.put("method", method);
                        route.put("path", path);
                        route.put("mappingBean", mappingBean.getKey());
                        route.put("controller", handler.getBeanType().getName());
                        route.put(
                                "declaringController",
                                handler.getMethod().getDeclaringClass().getName());
                        route.put("handler", handler.getMethod().toGenericString());
                        route.put("group", routeGroup(handler));
                        route.put(
                                "inherited",
                                !handler.getMethod()
                                        .getDeclaringClass()
                                        .equals(handler.getBeanType()));
                        route.put("methodsCondition", methods);
                        route.put(
                                "consumes",
                                mapping.getConsumesCondition().getConsumableMediaTypes().stream()
                                        .map(Object::toString)
                                        .sorted()
                                        .toList());
                        route.put(
                                "produces",
                                mapping.getProducesCondition().getProducibleMediaTypes().stream()
                                        .map(Object::toString)
                                        .sorted()
                                        .toList());
                        route.put("paramsCondition", mapping.getParamsCondition().toString());
                        route.put("headersCondition", mapping.getHeadersCondition().toString());
                        route.put("customCondition", String.valueOf(mapping.getCustomCondition()));
                        route.put(
                                "parameters",
                                Arrays.stream(handler.getMethodParameters())
                                        .map(this::parameter)
                                        .toList());
                        routes.add(route);
                    }
                }
            }
        }
        routes.sort(
                Comparator.comparing(
                        route ->
                                route.get("path")
                                        + " "
                                        + route.get("method")
                                        + " "
                                        + route.get("handler")));
        Path output = Path.of("target", "api-contract-inventory.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(
                        output.toFile(),
                        Map.of(
                                "schemaVersion",
                                1,
                                "apiPrefixAlias",
                                "/api",
                                "uniqueMethodPathCount",
                                pairs.size(),
                                "uniqueMethodPaths",
                                pairs,
                                "routes",
                                routes));
        for (String fixed :
                List.of(
                        "PUT /user/detail",
                        "PUT /user/me",
                        "POST /user/me/avatar",
                        "POST /user/me/password/_validate",
                        "POST /user/password/_validate",
                        "POST /user-token/reset",
                        "GET /user-token/reset",
                        "POST /user/detail/_create",
                        "GET /user/_query",
                        "POST /permission/_query")) {
            assertTrue(pairs.contains(fixed), "Missing actual registered route: " + fixed);
        }
        JsonNode openApi =
                client.get()
                        .uri("/v3/api-docs")
                        .header("X-Access-Token", admin)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(openApi);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target", "api-contract-openapi.json").toFile(), openApi);
        assertTrue(openApi.path("paths").path("/user/detail").has("put"));
        assertTrue(openApi.path("paths").path("/user/me/avatar").has("post"));
        JsonNode fields =
                openApi.path("components")
                        .path("schemas")
                        .path("UserProfileRequest")
                        .path("properties");
        assertTrue(fields.has("name"));
        assertTrue(fields.has("avatar"));
        assertTrue(fields.has("telephone"));
        assertTrue(fields.has("email"));
        assertFalse(fields.has("id"));
        assertFalse(fields.has("password"));
        assertFalse(fields.has("salt"));
    }

    String routeGroup(HandlerMethod handler) {
        String controller = handler.getBeanType().getName();
        if (controller.startsWith("com.gaiys.proto.")) return "application";
        if (controller.startsWith("org.hswebframework.")) return "framework";
        if (controller.startsWith("org.springdoc.")
                || controller.startsWith("org.springframework.boot.actuate."))
            return "infrastructure";
        return "framework-other";
    }

    Map<String, Object> parameter(MethodParameter parameter) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", parameter.getParameterIndex());
        result.put("javaName", parameter.getParameter().getName());
        result.put("javaType", parameter.getGenericParameterType().getTypeName());
        result.put(
                "annotations",
                Arrays.stream(parameter.getParameterAnnotations())
                        .map(Annotation::toString)
                        .toList());
        result.put("location", "model-or-framework");
        RequestBody body = parameter.getParameterAnnotation(RequestBody.class);
        RequestPart part = parameter.getParameterAnnotation(RequestPart.class);
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (body != null) {
            result.put("location", "body");
            result.put("required", body.required());
        }
        if (part != null) {
            result.put("location", "part");
            result.put("name", part.name().isEmpty() ? part.value() : part.name());
            result.put("required", part.required());
        }
        if (query != null) {
            result.put("location", "query");
            result.put("name", query.name().isEmpty() ? query.value() : query.name());
            result.put("required", query.required());
            result.put("defaultValue", query.defaultValue());
        }
        if (path != null) {
            result.put("location", "path");
            result.put("name", path.name().isEmpty() ? path.value() : path.name());
            result.put("required", path.required());
        }
        if (header != null) {
            result.put("location", "header");
            result.put("name", header.name().isEmpty() ? header.value() : header.name());
            result.put("required", header.required());
            result.put("defaultValue", header.defaultValue());
        }
        Schema schema = parameter.getParameterAnnotation(Schema.class);
        if (schema != null) {
            result.put("schemaDescription", schema.description());
            result.put("schemaRequiredMode", schema.requiredMode().name());
        }
        return result;
    }
}
