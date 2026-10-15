package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hswebframework.web.system.authorization.api.PasswordEncoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 三步骤密码修改的真实认证与持久化边界；仅使用独立容器数据库和临时文件目录。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false"
        })
@Testcontainers
class PasswordStepsIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path STORAGE = storage();

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
        registry.add("SYSTEM_FILE_STORAGE_ROOT", STORAGE::toString);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired PasswordEncoder encoder;
    String admin;

    @BeforeEach
    void authenticate() {
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin", PASSWORD);
    }

    private static Path storage() {
        try {
            return Files.createTempDirectory("proto-password-steps-");
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        try (var files = Files.walk(STORAGE)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
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

    Actor actor() {
        String username = "password-" + UUID.randomUUID().toString().replace("-", "");
        String id =
                call(
                                "POST",
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "user",
                                        Map.of(
                                                "username",
                                                username,
                                                "name",
                                                "Password owner",
                                                "password",
                                                PASSWORD),
                                        "roleIdList",
                                        List.of("MEMBER"),
                                        "orgIdList",
                                        List.of(),
                                        "positions",
                                        List.of()),
                                200)
                        .path("id")
                        .asText();
        assertFalse(id.isBlank());
        return new Actor(id, username, login(username, PASSWORD));
    }

    @Test
    void oldPasswordValidationUsesOnlyCurrentIdentityAndDoesNotApplyTheNewPasswordPolicy() {
        Actor owner = actor();
        Actor other = actor();
        // 已持久化短旧密码是兼容数据；只在测试数据库写入真实编码，不能用新策略锁住旧账号。
        String legacy = " old ";
        String salt =
                database.sql("SELECT salt FROM s_user WHERE id=:id")
                        .bind("id", owner.id())
                        .map((row, metadata) -> row.get("salt", String.class))
                        .one()
                        .block(TIMEOUT);
        assertNotNull(salt);
        assertEquals(
                1L,
                database.sql("UPDATE s_user SET password=:password WHERE id=:id")
                        .bind("password", encoder.encode(legacy, salt))
                        .bind("id", owner.id())
                        .fetch()
                        .rowsUpdated()
                        .block(TIMEOUT));
        String token = login(owner.username(), legacy);
        assertTrue(
                call("POST", "/user/me/password/_validate", token, legacy, 200)
                        .path("passed")
                        .asBoolean());
        assertFalse(
                call("POST", "/user/me/password/_validate", token, legacy.trim(), 200)
                        .path("passed")
                        .asBoolean());
        assertFalse(
                call("POST", "/user/me/password/_validate", other.token(), legacy, 200)
                        .path("passed")
                        .asBoolean());
        assertFalse(
                call("POST", "/user/password/_validate", token, legacy, 200)
                        .path("passed")
                        .asBoolean());
        call("POST", "/user/me/password/_validate", null, legacy, 401);
        call(
                "PUT",
                "/user/passwd",
                null,
                Map.of("oldPassword", legacy, "newPassword", PASSWORD),
                401);
        assertEquals(owner.id(), call("GET", "/user/detail", token, null, 200).path("id").asText());
    }

    @Test
    void rejectedFinalChangesLeaveTheAccountAndSessionUnchanged() {
        Actor owner = actor();
        for (int length : List.of(11, 12, 128, 129)) {
            assertEquals(
                    length >= 12 && length <= 128,
                    call("POST", "/user/password/_validate", owner.token(), "x".repeat(length), 200)
                            .path("passed")
                            .asBoolean());
        }
        call(
                "PUT",
                "/user/passwd",
                owner.token(),
                Map.of("oldPassword", "wrong", "newPassword", PASSWORD),
                400);
        for (int length : List.of(11, 129)) {
            call(
                    "PUT",
                    "/user/passwd",
                    owner.token(),
                    Map.of("oldPassword", PASSWORD, "newPassword", "x".repeat(length)),
                    400);
        }
        assertTrue(
                call("POST", "/user/me/password/_validate", owner.token(), PASSWORD, 200)
                        .path("passed")
                        .asBoolean());
        assertEquals(
                owner.id(),
                call("GET", "/user/detail", owner.token(), null, 200).path("id").asText());
    }

    @Test
    void finalChangePreservesRawPasswordsAndRevokesThePreviousSession() {
        Actor owner = actor();
        String next = " " + "x".repeat(126) + " ";
        assertTrue(
                call("POST", "/user/password/_validate", owner.token(), next, 200)
                        .path("passed")
                        .asBoolean());
        assertTrue(
                call(
                                "PUT",
                                "/user/passwd",
                                owner.token(),
                                Map.of("oldPassword", PASSWORD, "newPassword", next),
                                200)
                        .asBoolean());
        call("GET", "/user/detail", owner.token(), null, 401);
        String token = login(owner.username(), next);
        assertTrue(
                call("POST", "/user/me/password/_validate", token, next, 200)
                        .path("passed")
                        .asBoolean());
        assertFalse(
                call("POST", "/user/me/password/_validate", token, next.trim(), 200)
                        .path("passed")
                        .asBoolean());
    }

    @Test
    void earlierValidationNeverBypassesTheFinalOldPasswordCheck() {
        Actor owner = actor();
        assertTrue(
                call("POST", "/user/me/password/_validate", owner.token(), PASSWORD, 200)
                        .path("passed")
                        .asBoolean());
        String changed = UUID.randomUUID().toString();
        assertTrue(
                call(
                                "PUT",
                                "/user/passwd",
                                owner.token(),
                                Map.of("oldPassword", PASSWORD, "newPassword", changed),
                                200)
                        .asBoolean());
        String token = login(owner.username(), changed);
        call(
                "PUT",
                "/user/passwd",
                token,
                Map.of("oldPassword", PASSWORD, "newPassword", UUID.randomUUID().toString()),
                400);
        assertTrue(
                call("POST", "/user/me/password/_validate", token, changed, 200)
                        .path("passed")
                        .asBoolean());
    }
}
