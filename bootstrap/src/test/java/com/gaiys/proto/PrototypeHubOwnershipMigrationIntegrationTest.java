package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 单连接池验证超过两批的历史迁移，防止批次预取占连接与项目短事务相互等待。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml", "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
                    "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000", "spring.r2dbc.pool.max-size=1",
            "spring.r2dbc.pool.initial-size=1"
        })
@Testcontainers
class PrototypeHubOwnershipMigrationIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        properties.add("spring.r2dbc.username", DATABASE::getUsername);
        properties.add("spring.r2dbc.password", DATABASE::getPassword);
        properties.add("proto.admin.password", () -> PASSWORD);
        properties.add("prototype-hub.storage.root", () -> FILES.toString());
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-hub-single-pool-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @AfterAll
    static void removeFiles() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;

    private JsonNode migrate(String token, boolean apply) {
        return client.post()
                .uri("/prototype-hub/projects/_ownership-migration")
                .header("X-Access-Token", token)
                .bodyValue(Map.of("apply", apply))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("result");
    }

    private long countMissingEpoch() {
        Long result =
                database.sql("select count(*) as count from p_project where access_epoch is null")
                        .map((row, metadata) -> row.get("count", Long.class))
                        .one()
                        .block(TIMEOUT);
        assertNotNull(result);
        return result;
    }

    private String authorizationSnapshot() {
        return database.sql(
                        "select md5(coalesce(jsonb_agg(jsonb_build_array(id,owner_id,access_epoch) order by id)::text,'[]')) as hash from p_project")
                .map((row, metadata) -> row.get("hash", String.class))
                .one()
                .block(TIMEOUT);
    }

    @Test
    void legacyMigrationPagesReleaseTheOnlyConnectionAndClassifyPendingProjectsOnce() {
        client = client.mutate().responseTimeout(TIMEOUT).build();
        PrototypeHubTestData.clear(database);
        String token =
                client.post()
                        .uri("/authorize/login")
                        .bodyValue(Map.of("username", "admin", "password", PASSWORD))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result")
                        .path("token")
                        .asText();
        database.sql(
                        "insert into p_project(id,name,creator_id,create_time,modifier_id,modify_time) select 'batch-'||n,'Legacy batch','missing-creator',0,'missing-creator',0 from generate_series(1,205) n")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        String legacy = authorizationSnapshot();
        JsonNode dry = migrate(token, false);
        assertEquals(205, dry.path("scanned").asInt());
        assertEquals(205, dry.path("pending").asInt());
        assertEquals(205L, countMissingEpoch());
        assertEquals(legacy, authorizationSnapshot());
        JsonNode applied = migrate(token, true);
        assertEquals(205, applied.path("scanned").asInt());
        assertEquals(205, applied.path("pending").asInt());
        assertEquals(0L, countMissingEpoch());
        String initialized = authorizationSnapshot();
        JsonNode rerun = migrate(token, true);
        assertEquals(0, rerun.path("assigned").asInt());
        assertEquals(205, rerun.path("pending").asInt());
        assertEquals(initialized, authorizationSnapshot());
    }
}
