package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class UserSettingsIntegrationTest {
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

    record Actor(String id, String token) {}

    Actor user() {
        String username = "history-" + UUID.randomUUID().toString().replace("-", "");
        String id =
                call(
                                "POST",
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "username",
                                        username,
                                        "name",
                                        "Saved query user",
                                        "password",
                                        PASSWORD,
                                        "roleIdList",
                                        List.of()),
                                200)
                        .path("id")
                        .asText();
        return new Actor(id, login(username));
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

    @Test
    void savedQueriesPersistForAnOrdinaryUserAndUseTheReturnedIdForDeletion() {
        Actor actor = user();
        String content =
                "{\"terms\":[{\"column\":\"name\",\"termType\":\"like\",\"value\":\"%engineer%\"}]}";
        JsonNode saved =
                call(
                        "POST",
                        "/user/settings/system-user",
                        actor.token(),
                        Map.of("name", "Engineers", "content", content, "userId", "admin"),
                        200);
        String key = saved.path("id").asText();
        assertFalse(key.isBlank());
        assertEquals(key, saved.path("key").asText());
        assertFalse(saved.has("userId"));
        JsonNode rows = call("GET", "/user/settings/system-user", actor.token(), null, 200);
        assertEquals(1, rows.size());
        assertEquals(content, rows.get(0).path("content").asText());
        assertEquals(
                "Engineers",
                call("GET", "/user/settings/system-user/" + key, actor.token(), null, 200)
                        .path("name")
                        .asText());
        call(
                "PATCH",
                "/user/settings/system-user/" + key,
                actor.token(),
                Map.of("name", "All users", "content", Map.of("terms", List.of())),
                200);
        JsonNode updated =
                call("GET", "/user/settings/system-user/" + key, actor.token(), null, 200);
        assertEquals("All users", updated.path("name").asText());
        assertTrue(updated.path("content").path("terms").isArray());
        String plainContent = "plain-text saved query";
        call(
                "PATCH",
                "/user/settings/system-user/" + key,
                actor.token(),
                Map.of("name", "Plain content", "content", plainContent),
                200);
        assertEquals(
                plainContent,
                call("GET", "/user/settings/system-user/" + key, actor.token(), null, 200)
                        .path("content")
                        .asText());
        assertEquals(
                1L,
                database.sql("SELECT count(*) FROM s_user_setting WHERE user_id=:owner")
                        .bind("owner", actor.id())
                        .map((row, metadata) -> row.get(0, Long.class))
                        .one()
                        .block());
        call("DELETE", "/user/settings/system-user/" + key, actor.token(), null, 200);
        assertEquals(0, call("GET", "/user/settings/system-user", actor.token(), null, 200).size());
    }

    @Test
    void settingsAreIsolatedAcrossUsersAndScopesEvenWhenKeysAreShared() {
        Actor first = user(), second = user();
        JsonNode saved =
                call(
                        "POST",
                        "/user/settings/system-user",
                        first.token(),
                        Map.of("name", "First user", "content", "{\"terms\":[]}"),
                        200);
        String key = saved.path("id").asText();
        assertEquals(
                0, call("GET", "/user/settings/system-user", second.token(), null, 200).size());
        assertEquals(
                0, call("GET", "/user/settings/search-system", first.token(), null, 200).size());
        call("GET", "/user/settings/system-user/" + key, second.token(), null, 404);
        call("DELETE", "/user/settings/system-user/" + key, second.token(), null, 200);
        assertEquals(
                "First user",
                call("GET", "/user/settings/system-user/" + key, first.token(), null, 200)
                        .path("name")
                        .asText());
        call(
                "PATCH",
                "/user/settings/system-user/" + key,
                second.token(),
                Map.of("name", "Second user", "content", Map.of("terms", List.of())),
                200);
        call(
                "PATCH",
                "/user/settings/search-system/" + key,
                first.token(),
                Map.of("name", "Different scope", "content", Map.of("terms", List.of())),
                200);
        assertEquals(
                "First user",
                call("GET", "/user/settings/system-user/" + key, first.token(), null, 200)
                        .path("name")
                        .asText());
        assertEquals(
                "Second user",
                call("GET", "/user/settings/system-user/" + key, second.token(), null, 200)
                        .path("name")
                        .asText());
        call("DELETE", "/user/settings/system-user/" + key, second.token(), null, 200);
        assertEquals(
                "Different scope",
                call("GET", "/user/settings/search-system/" + key, first.token(), null, 200)
                        .path("name")
                        .asText());
    }

    @Test
    void initializationMarkerPersistsAndAnonymousRequestsAreRejected() {
        Actor actor = user();
        call("GET", "/user/settings/system-user", null, null, 401);
        call(
                "POST",
                "/user/settings/system-user",
                null,
                Map.of("name", "Denied", "content", Map.of("terms", List.of())),
                401);
        call("POST", "/user/settings/init", actor.token(), Map.of("init", true), 200);
        JsonNode marker = call("GET", "/user/settings/init/init", actor.token(), null, 200);
        assertTrue(marker.path("content").path("init").asBoolean());
        assertEquals(
                1L,
                database.sql(
                                "SELECT count(*) FROM s_user_setting WHERE user_id=:owner AND type='init'")
                        .bind("owner", actor.id())
                        .map((row, metadata) -> row.get(0, Long.class))
                        .one()
                        .block());
        // Backend readiness retains its established initialization response.
        assertTrue(call("GET", "/user/settings/init", actor.token(), null, 200).isArray());
        call(
                "POST",
                "/user/settings/system-user",
                actor.token(),
                Map.of("name", "Too large", "content", "x".repeat(16385)),
                400);
        assertEquals(0, call("GET", "/user/settings/system-user", actor.token(), null, 200).size());
    }
}
