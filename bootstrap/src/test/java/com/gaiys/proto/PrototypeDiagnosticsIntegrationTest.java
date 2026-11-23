package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.notification.SmtpWork;
import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectService;
import com.gaiys.proto.system.service.MenuService;
import com.gaiys.proto.system.service.MenuStartupInitializationService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实PostgreSQL/HTTP的诊断权限、软删占额、队列聚合、脱敏及只读副作用边界。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000"
        })
@Testcontainers
class PrototypeDiagnosticsIntegrationTest {
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    static final String PASSWORD = UUID.randomUUID().toString();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Path STORAGE = temporaryStorage();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        p.add("spring.r2dbc.username", DATABASE::getUsername);
        p.add("spring.r2dbc.password", DATABASE::getPassword);
        p.add("proto.admin.password", () -> PASSWORD);
        p.add("prototype-hub.storage.root", STORAGE::toString);
        p.add("spring.r2dbc.pool.max-size", () -> 6);
    }

    @Autowired WebTestClient client;
    @Autowired PrototypeProjectService projects;
    @Autowired FileCleanupService cleanup;
    @Autowired DatabaseClient database;
    @Autowired ObjectMapper mapper;
    @Autowired SmtpWork smtp;
    @Autowired MenuService menus;
    @Autowired MenuStartupInitializationService menuInitialization;

    @Test
    void snapshotIsReadOnlyAggregatedRedactedAndCurrentPermissionIsAnIntersection()
            throws Exception {
        client = client.mutate().responseTimeout(TIMEOUT).build();
        String admin = login("admin");
        call(HttpMethod.GET, "/prototype-hub/diagnostics", null, null, 401);
        String project = UUID.randomUUID().toString();
        PrototypeProjectEntity row = new PrototypeProjectEntity();
        row.setId(project);
        row.setName("private-project-name");
        row.setCreatorId("private-user");
        row.setModifierId("private-user");
        row.setCreateTime(1L);
        row.setModifyTime(1L);
        row.setDeletedTime(System.currentTimeMillis());
        projects.insert(row).block(TIMEOUT);
        long baseline = projects.countForCapacity().block(TIMEOUT);
        long now = System.currentTimeMillis();
        for (int index = 0; index < 3; index++) {
            var task = new FileCleanupEntity();
            task.setId("diagnostics-" + index);
            task.setProjectId(project);
            task.setResourceType(index == 2 ? "legacy" : "material");
            task.setStoragePath("private/storage/file-" + index);
            task.setCreateTime(now);
            task.setNextAttempt(index == 1 ? now + 3_600_000 : 0L);
            task.setAttempts(index == 0 ? 2 : 0);
            cleanup.insert(task).block(TIMEOUT);
        }
        var beforeCleanup =
                mapper.valueToTree(
                        cleanup.createQuery()
                                .orderBy(
                                        org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder
                                                .asc(FileCleanupEntity::getId))
                                .fetch()
                                .collectList()
                                .block(TIMEOUT));
        var beforeInstall =
                database.sql(
                                "select row_to_json(p_installation)::text as value from p_installation")
                        .fetch()
                        .one()
                        .block(TIMEOUT);
        var workers = smtp.snapshot();
        var response =
                client.get()
                        .uri("/prototype-hub/diagnostics")
                        .header("X-Access-Token", admin)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .valueEquals("Cache-Control", "no-store")
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        assertEquals(1, response.path("schemaVersion").asInt());
        assertTrue(response.path("complete").asBoolean(), response.toString());
        var checks = response.path("checks");
        assertEquals(
                baseline,
                checks.path("database").path("data").path("projects").path("used").asLong());
        assertEquals(3, checks.path("cleanup").path("data").path("pending").asLong());
        assertEquals(1, checks.path("cleanup").path("data").path("due").asLong());
        assertEquals(1, checks.path("cleanup").path("data").path("retried").asLong());
        assertEquals(1, checks.path("cleanup").path("data").path("unknown").asLong());
        assertFalse(checks.path("mail").path("data").path("configured").asBoolean());
        assertEquals("missing", checks.path("license").path("data").path("status").asText());
        for (String secret :
                List.of(
                        "private-",
                        "installationId",
                        "fingerprint",
                        "keyId",
                        "customer",
                        "storagePath",
                        STORAGE.toString()))
            assertFalse(response.toString().contains(secret), secret);
        assertEquals(
                beforeCleanup,
                mapper.valueToTree(
                        cleanup.createQuery()
                                .orderBy(
                                        org.hswebframework.ezorm.rdb.operator.dml.query.SortOrder
                                                .asc(FileCleanupEntity::getId))
                                .fetch()
                                .collectList()
                                .block(TIMEOUT)));
        assertEquals(
                beforeInstall,
                database.sql(
                                "select row_to_json(p_installation)::text as value from p_installation")
                        .fetch()
                        .one()
                        .block(TIMEOUT));
        assertEquals(workers, smtp.snapshot());

        String role = UUID.randomUUID().toString().replace("-", "");
        call(
                HttpMethod.POST,
                "/role",
                admin,
                Map.of("id", role, "name", "Diagnostics tester"),
                200);
        call(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        role,
                        "permission",
                        "prototype-hub-diagnostics",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        List.of("query"),
                        "state",
                        1),
                200);
        String username = "diagnostics-" + role;
        String user =
                call(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Diagnostics tester",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD),
                                200)
                        .path("result")
                        .path("id")
                        .asText();
        call(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(user), 200);
        String token = login(username);
        call(HttpMethod.GET, "/prototype-hub/diagnostics", token, null, 403);
        call(HttpMethod.POST, "/role/ADMIN/users/_bind", admin, List.of(user), 200);
        token = login(username);
        call(HttpMethod.GET, "/prototype-hub/diagnostics", token, null, 200);
        database.sql("delete from s_dimension_user where dimension_id='ADMIN' and user_id=:user")
                .bind("user", user)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.GET, "/prototype-hub/diagnostics", token, null, 403);
        call(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        role,
                        "permission",
                        "prototype-hub-diagnostics",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        List.of(),
                        "state",
                        1),
                200);
        token = login(username);
        call(HttpMethod.GET, "/prototype-hub/diagnostics", token, null, 403);
    }

    @Test
    void activeAdministratorWithoutDiagnosticQueryCannotRead() {
        client = client.mutate().responseTimeout(TIMEOUT).build();
        String admin = login("admin");
        String username = "diagnostics-admin-" + UUID.randomUUID().toString().replace("-", "");
        String user =
                call(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Diagnostic administrator",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD),
                                200)
                        .path("result")
                        .path("id")
                        .asText();
        call(HttpMethod.POST, "/role/ADMIN/users/_bind", admin, List.of(user), 200);
        call(HttpMethod.GET, "/prototype-hub/diagnostics", login(username), null, 200);
        String setting =
                database.sql(
                                "select id from s_autz_setting_info where dimension_type='role' and dimension_target='ADMIN' and permission='prototype-hub-diagnostics'")
                        .map((row, metadata) -> row.get("id", String.class))
                        .one()
                        .block(TIMEOUT);
        assertNotNull(setting);
        try {
            call(
                    HttpMethod.PATCH,
                    "/autz-setting",
                    admin,
                    Map.of(
                            "id",
                            setting,
                            "permission",
                            "prototype-hub-diagnostics",
                            "dimensionType",
                            "role",
                            "dimensionTarget",
                            "ADMIN",
                            "actions",
                            List.of(),
                            "state",
                            1),
                    200);
            // 内置恢复管理员派生全部动作；使用普通ADMIN账号才有真实的缺query反例。
            String withoutQuery = login(username);
            call(HttpMethod.GET, "/prototype-hub/license", withoutQuery, null, 200);
            call(HttpMethod.GET, "/prototype-hub/diagnostics", withoutQuery, null, 403);
        } finally {
            call(
                    HttpMethod.PATCH,
                    "/autz-setting",
                    admin,
                    Map.of(
                            "id",
                            setting,
                            "permission",
                            "prototype-hub-diagnostics",
                            "dimensionType",
                            "role",
                            "dimensionTarget",
                            "ADMIN",
                            "actions",
                            List.of("query"),
                            "state",
                            1),
                    200);
        }
    }

    @Test
    void diagnosticsMenuUpgradeIsIdempotentAndPreservesCustomizedDisabledEntries() {
        menus.deleteById("prototype-hub-diagnostics").block(TIMEOUT);
        assertTrue(menuInitialization.ensureDiagnosticsMenuIfMissing().block(TIMEOUT));
        var row = menus.findById("prototype-hub-diagnostics").block(TIMEOUT);
        assertEquals("prototype-hub/Diagnostics", row.getComponentCode());
        row.setName("Custom diagnostics");
        row.setEnabled(false);
        menus.save(row).block(TIMEOUT);
        assertFalse(menuInitialization.ensureDiagnosticsMenuIfMissing().block(TIMEOUT));
        var retained = menus.findById(row.getId()).block(TIMEOUT);
        assertEquals("Custom diagnostics", retained.getName());
        assertFalse(retained.getEnabled());
    }

    JsonNode call(HttpMethod method, String uri, String token, Object body, int status) {
        var request = client.method(method).uri(uri);
        if (token != null) request.header("X-Access-Token", token);
        var response = body == null ? request.exchange() : request.bodyValue(body).exchange();
        return response.expectStatus()
                .isEqualTo(status)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    String login(String username) {
        return call(
                        HttpMethod.POST,
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", PASSWORD),
                        200)
                .path("result")
                .path("token")
                .asText();
    }

    static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-diagnostics-test-");
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
