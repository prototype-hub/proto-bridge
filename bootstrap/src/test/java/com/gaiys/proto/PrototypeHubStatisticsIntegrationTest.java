package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.service.PrototypeInstallationService;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.service.PrototypeShareService;
import com.gaiys.proto.prototypehub.service.PrototypeStatisticsService;
import com.gaiys.proto.prototypehub.service.keys.PrototypeVisitTickets;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真实HTTP、EasyORM冷建表与PostgreSQL并发/授权/保留边界，不使用Mock统计结果。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml", "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
                    "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000",
                    "prototype-hub.review.trusted-origin=http://statistics.test"
        })
@Testcontainers
class PrototypeHubStatisticsIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();
    private static final Map<String, List<String>> ALL_ACTIONS =
            Map.of(
                    "prototype-hub-project", List.of("query", "save", "delete"),
                    "prototype-hub-version", List.of("query", "save", "delete"),
                    "prototype-hub-share", List.of("query", "save", "delete"));

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry p) {
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
        p.add("spring.r2dbc.pool.max-size", () -> 6);
        p.add("proto.admin.password", () -> PASSWORD);
        p.add("prototype-hub.storage.root", FILES::toString);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired PrototypeStatisticsService statistics;
    @Autowired PrototypeRetentionService retention;
    @Autowired PrototypeInstallationService installations;
    @Autowired PrototypeShareService shares;
    private String admin;

    @BeforeEach
    void isolate() throws IOException {
        PrototypeHubTestData.clear(database);
        SecureZipExtractor.deleteRecursively(FILES);
        Files.createDirectories(FILES);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin");
    }

    @AfterAll
    static void removeFiles() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-statistics-");
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    @Test
    void emptyHistoryAndWindowAreExplicitAndStartupDoesNotMoveCollectionStart() {
        var first = installations.current().block(TIMEOUT);
        assertNotNull(first.getStatisticsStartedAt());
        assertEquals(
                first.getStatisticsStartedAt(),
                installations.initialize().block(TIMEOUT).getStatisticsStartedAt());
        var result = summary(admin, 7);
        assertEquals("Asia/Shanghai", result.path("timeZone").asText());
        assertEquals(35, result.path("retentionDays").asInt());
        assertEquals(0, result.path("projectCount").asLong());
        assertEquals(7, result.path("trend").size());
        assertTrue(result.path("trend").get(0).path("visits").isNull());
        assertEquals(0, result.path("trend").get(6).path("visits").asLong());
        assertEquals(30, summary(admin, 30).path("trend").size());
        call(HttpMethod.GET, "/prototype-hub/statistics?days=8", admin, null, 400);
        call(HttpMethod.GET, "/prototype-hub/statistics", null, null, 401);
    }

    @Test
    void onlyConfirmedOuterOpenCountsAndConcurrentTicketReplaysAreOne() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of());
        var grant = grant(share);
        assertFalse(grant.path("visitTicket").asText().isBlank());
        result(HttpMethod.GET, backend(grant.path("pagesUrl").asText()), null, null);
        client.get()
                .uri(backend(grant.path("previewUrl").asText()))
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri(backend(grant.path("runtimeBaseUrl").asText()) + "/assets/style.css")
                .exchange()
                .expectStatus()
                .isOk();
        assertEquals(0, summary(admin, 7).path("visits").asLong());
        List<CompletableFuture<Void>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) calls.add(CompletableFuture.runAsync(() -> visit(grant, 200)));
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get();
        assertEquals(1, summary(admin, 7).path("visits").asLong());
        visit(grant(share), 200);
        var summary = summary(admin, 7);
        assertEquals(2, summary.path("visits").asLong());
        assertEquals(1, summary.path("projectCount").asLong());
        assertEquals(1, summary.path("versionCount").asLong());
        assertEquals(1, summary.path("shareCount").asLong());
        assertEquals(2, summary.path("ranking").get(0).path("visits").asLong());
        assertEquals(
                version.path("projectId").asText(),
                summary.path("ranking").get(0).path("projectId").asText());
        assertEquals(1, number("select count(distinct revision_id) as n from p_share_visit"));
    }

    @Test
    void passwordFailuresAndInvalidOriginTicketOrRevokedGrantsDoNotCount() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of("password", "a-protected-secret"));
        var info = grant(share);
        assertTrue(info.path("visitTicket").isNull());
        call(
                HttpMethod.POST,
                publicPath(share) + "/verify",
                null,
                Map.of("password", "incorrect"),
                403);
        var granted =
                result(
                        HttpMethod.POST,
                        publicPath(share) + "/verify",
                        null,
                        Map.of("password", "a-protected-secret"));
        client.post()
                .uri(visitPath(granted))
                .bodyValue(Map.of("ticket", granted.path("visitTicket").asText()))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri(visitPath(granted))
                .header("Origin", "https://other.test")
                .bodyValue(Map.of("ticket", granted.path("visitTicket").asText()))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri(visitPath(granted))
                .header("Origin", "http://statistics.test")
                .bodyValue(Map.of("ticket", "invalid"))
                .exchange()
                .expectStatus()
                .isForbidden();
        var stored = shares.findById(share.path("id").asText()).block(TIMEOUT);
        String expired =
                PrototypeVisitTickets.issue(
                        installations.current().block(TIMEOUT).getStatisticsSigningKey(),
                        stored,
                        granted.path("revisionId").asText(),
                        System.currentTimeMillis() - PrototypeVisitTickets.TTL);
        client.post()
                .uri(visitPath(granted))
                .header("Origin", "http://statistics.test")
                .bodyValue(Map.of("ticket", expired))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertEquals(0, summary(admin, 7).path("visits").asLong());
        visit(granted, 200);
        result(
                HttpMethod.POST,
                "/prototype-hub/shares/" + share.path("id").asText() + "/revoke",
                admin,
                Map.of("expectedVersion", 1));
        visit(granted, 404);
        assertEquals(0, summary(admin, 7).path("shareCount").asLong());
        assertEquals(1, summary(admin, 7).path("visits").asLong());
    }

    @Test
    void followingLatestKeepsTheActualRevisionOfEachOpen() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of("revisionMode", "latest"));
        var old = grant(share);
        var updated =
                upload(
                        version.path("projectId").asText(),
                        version.path("id").asText(),
                        "R2",
                        admin);
        visit(old, 200);
        visit(grant(share), 200);
        assertEquals(2, number("select count(distinct revision_id) as n from p_share_visit"));
        assertEquals(2, summary(admin, 7).path("visits").asLong());
        assertNotEquals(old.path("revisionId").asText(), updated.path("revisionId").asText());
    }

    @Test
    void permissionsAndLiveProjectMembershipApplyBeforeEveryAggregate() throws Exception {
        Actor owner = actor(ALL_ACTIONS), editor = actor(ALL_ACTIONS), viewer = actor(ALL_ACTIONS);
        Actor projectOnly = actor(Map.of("prototype-hub-project", List.of("query")));
        String project = project(owner.token());
        var version = upload(project, null, "R1", owner.token());
        var share =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version.path("id").asText() + "/shares",
                        owner.token(),
                        Map.of());
        visit(grant(share), 200);
        result(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + editor.id(),
                admin,
                Map.of("role", "editor"));
        result(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + viewer.id(),
                admin,
                Map.of("role", "viewer"));
        result(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + projectOnly.id(),
                admin,
                Map.of("role", "viewer"));
        assertEquals(1, summary(owner.token(), 7).path("visits").asLong());
        assertEquals(1, summary(editor.token(), 7).path("visits").asLong());
        var view = summary(viewer.token(), 7);
        assertEquals(1, view.path("projectCount").asLong());
        assertEquals(1, view.path("versionCount").asLong());
        assertEquals(0, view.path("visits").asLong());
        assertEquals(0, view.path("shareCount").asLong());
        var limited = summary(projectOnly.token(), 7);
        assertEquals(1, limited.path("projectCount").asLong());
        assertTrue(limited.path("versionCount").isNull());
        assertTrue(limited.path("shareCount").isNull());
        assertTrue(limited.path("visits").isNull());
        assertTrue(limited.path("trend").isNull());
        result(
                HttpMethod.DELETE,
                "/prototype-hub/projects/" + project + "/members/" + editor.id(),
                admin,
                null);
        assertEquals(0, summary(editor.token(), 7).path("projectCount").asLong());
        assertEquals(0, summary(editor.token(), 7).path("visits").asLong());
        Actor unrelated = actor(ALL_ACTIONS);
        assertEquals(0, summary(unrelated.token(), 7).path("projectCount").asLong());
        assertTrue(summary(unrelated.token(), 7).path("ranking").isEmpty());
    }

    @Test
    void softDeletedVersionHidesHistoricalVisitsAndPermanentPurgeRemovesThem() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of());
        visit(grant(share), 200);
        result(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + version.path("id").asText(),
                admin,
                null);
        assertEquals(0, summary(admin, 7).path("versionCount").asLong());
        assertEquals(0, summary(admin, 7).path("visits").asLong());
        assertEquals(1, number("select count(*) as n from p_share_visit"));
        database.sql("update p_version set deleted_time=:cutoff where id=:id")
                .bind(
                        "cutoff",
                        System.currentTimeMillis() - PrototypeRetentionService.RETENTION_MILLIS)
                .bind("id", version.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeVersion(version.path("id").asText()).block(TIMEOUT));
        assertEquals(0, number("select count(*) as n from p_share_visit"));
    }

    @Test
    void retentionIs35NaturalDaysAndLateRetriesKeepTheirFirstReceiptDay() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of());
        var granted = grant(share);
        visit(granted, 200);
        String today = summary(admin, 7).path("toDate").asText();
        String yesterday = LocalDate.parse(today).minusDays(1).toString();
        database.sql("update p_share_visit set visit_day=:day")
                .bind("day", yesterday)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        visit(granted, 200);
        assertEquals(
                yesterday,
                database.sql("select visit_day from p_share_visit")
                        .map((row, meta) -> row.get("visit_day", String.class))
                        .one()
                        .block(TIMEOUT));
        database.sql("update p_share_visit set visit_day=:day")
                .bind("day", LocalDate.parse(today).minusDays(34).toString())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        statistics.cleanup().block(TIMEOUT);
        assertEquals(1, number("select count(*) as n from p_share_visit"));
        database.sql("update p_share_visit set visit_day=:day")
                .bind("day", LocalDate.parse(today).minusDays(35).toString())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        statistics.cleanup().block(TIMEOUT);
        assertEquals(0, number("select count(*) as n from p_share_visit"));
    }

    @Test
    void countingFailureDoesNotChangeTheReadableShareOrCommitHalfAnEvent() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of());
        var granted = grant(share);
        database.sql(
                        "CREATE FUNCTION reject_stat_visit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced statistics failure'; END $$")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql(
                        "CREATE TRIGGER reject_stat_visit BEFORE INSERT ON p_share_visit FOR EACH ROW EXECUTE FUNCTION reject_stat_visit()")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        try {
            visit(granted, 500);
            assertEquals(0, summary(admin, 7).path("visits").asLong());
            client.get()
                    .uri(backend(granted.path("previewUrl").asText()))
                    .exchange()
                    .expectStatus()
                    .isOk();
        } finally {
            database.sql("DROP TRIGGER reject_stat_visit ON p_share_visit")
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
            database.sql("DROP FUNCTION reject_stat_visit()").fetch().rowsUpdated().block(TIMEOUT);
        }
        visit(granted, 200);
        assertEquals(1, summary(admin, 7).path("visits").asLong());
    }

    @Test
    void knownRuntimeKeysCannotForgeHttpEventsAndTicketsCannotCrossShareOrRevision()
            throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var first = create(version, Map.of());
        var second = create(version, Map.of());
        var granted = grant(first);
        var other = grant(second);
        var stored = shares.findById(first.path("id").asText()).block(TIMEOUT);
        for (String known : List.of(granted.path("accessKey").asText(), stored.getAccessKey())) {
            String forged =
                    PrototypeVisitTickets.issue(
                            known,
                            stored,
                            granted.path("revisionId").asText(),
                            System.currentTimeMillis());
            client.post()
                    .uri(visitPath(granted))
                    .header("Origin", "http://statistics.test")
                    .bodyValue(Map.of("ticket", forged))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
        client.post()
                .uri(visitPath(other))
                .header("Origin", "http://statistics.test")
                .bodyValue(Map.of("ticket", granted.path("visitTicket").asText()))
                .exchange()
                .expectStatus()
                .isForbidden();
        result(
                HttpMethod.POST,
                "/prototype-hub/shares/" + first.path("id").asText() + "/rotate-link",
                admin,
                Map.of("expectedVersion", 1));
        visit(granted, 404);
        assertEquals(0, summary(admin, 7).path("visits").asLong());
    }

    @Test
    void rotationAndConfirmationAreSerializedAndOldGrantCannotBeReplayed() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of());
        var granted = grant(share);
        var confirmation =
                CompletableFuture.supplyAsync(
                        () ->
                                client.post()
                                        .uri(visitPath(granted))
                                        .header("Origin", "http://statistics.test")
                                        .bodyValue(
                                                Map.of(
                                                        "ticket",
                                                        granted.path("visitTicket").asText()))
                                        .exchange()
                                        .returnResult(Void.class)
                                        .getStatus()
                                        .value());
        var rotated =
                CompletableFuture.supplyAsync(
                        () ->
                                result(
                                        HttpMethod.POST,
                                        "/prototype-hub/shares/"
                                                + share.path("id").asText()
                                                + "/rotate-link",
                                        admin,
                                        Map.of("expectedVersion", 1)));
        CompletableFuture.allOf(confirmation, rotated).get();
        int status = confirmation.get();
        assertTrue(status == 200 || status == 404);
        assertEquals(status == 200 ? 1 : 0, summary(admin, 7).path("visits").asLong());
        visit(granted, 404);
        visit(grant(rotated.get()), 200);
        assertEquals(status == 200 ? 2 : 1, summary(admin, 7).path("visits").asLong());
    }

    @Test
    void pausedExpiredRevokedAndLegacyUnboundSharesDoNotCountAsEffective() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var active = create(version, Map.of());
        var paused = create(version, Map.of());
        var expired = create(version, Map.of());
        var unbound = create(version, Map.of());
        var revoked = create(version, Map.of());
        database.sql("update p_share set enabled=false where id=:id")
                .bind("id", paused.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_share set expires_at=:now where id=:id")
                .bind("now", System.currentTimeMillis() - 1)
                .bind("id", expired.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_share set revision_id=null where id=:id")
                .bind("id", unbound.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_share set revoked=true where id=:id")
                .bind("id", revoked.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertEquals(1, summary(admin, 7).path("shareCount").asLong());
        visit(grant(active), 200);
        assertEquals(1, summary(admin, 7).path("visits").asLong());
    }

    @Test
    void representativeEventVolumeUsesDatabaseAggregatesAndStableBoundedRanking() throws Exception {
        var version = upload(project(admin), null, "R1", admin);
        var share = create(version, Map.of());
        var granted = grant(share);
        visit(granted, 200);
        database.sql(
                        "INSERT INTO p_share_visit(id,project_id,version_id,revision_id,share_id,visit_day,received_at) SELECT md5(cast(n as text)), project_id,version_id,revision_id,share_id,visit_day,received_at FROM p_share_visit CROSS JOIN generate_series(1,5000) n")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        var result = summary(admin, 30);
        assertEquals(5001, result.path("visits").asLong());
        assertEquals(5001, result.path("ranking").get(0).path("visits").asLong());
        assertEquals(30, result.path("trend").size());
        assertEquals(1, result.path("ranking").size());
    }

    private JsonNode summary(String token, int days) {
        return result(HttpMethod.GET, "/prototype-hub/statistics?days=" + days, token, null);
    }

    private long number(String sql) {
        return database.sql(sql)
                .map((row, meta) -> ((Number) row.get("n")).longValue())
                .one()
                .block(TIMEOUT);
    }

    private JsonNode create(JsonNode version, Object body) {
        return result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + version.path("id").asText() + "/shares",
                admin,
                body);
    }

    private String publicPath(JsonNode share) {
        return "/prototype-hub/public/shares/" + share.path("token").asText();
    }

    private JsonNode grant(JsonNode share) {
        return result(HttpMethod.GET, publicPath(share), null, null);
    }

    private static String backend(String path) {
        return path.startsWith("/api/") ? path.substring(4) : path;
    }

    private String visitPath(JsonNode grant) {
        return backend(grant.path("runtimeBaseUrl").asText()).replace("/runtime", "/visits");
    }

    private void visit(JsonNode grant, int status) {
        client.post()
                .uri(visitPath(grant))
                .header("Origin", "http://statistics.test")
                .bodyValue(Map.of("ticket", grant.path("visitTicket").asText()))
                .exchange()
                .expectStatus()
                .isEqualTo(status);
    }

    private JsonNode result(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, 200).path("result");
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        var response = body == null ? request.exchange() : request.bodyValue(body).exchange();
        var result =
                response.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(result);
        return result;
    }

    private String login(String username) {
        return result(
                        HttpMethod.POST,
                        "/authorize/login",
                        null,
                        Map.of("username", username, "password", PASSWORD))
                .path("token")
                .asText();
    }

    private String project(String token) {
        return result(
                        HttpMethod.POST,
                        "/prototype-hub/projects",
                        token,
                        Map.of("name", "Share lifecycle"))
                .path("id")
                .asText();
    }

    private JsonNode upload(String project, String version, String label, String token)
            throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var file :
                    Map.of(
                                    "index.html",
                                    "<html><body>" + label + "</body></html>",
                                    "nested/page.html",
                                    "<html><body>nested " + label + "</body></html>",
                                    "assets/style.css",
                                    "/* " + label + " */")
                            .entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var parts = new MultipartBodyBuilder();
        parts.part("versionName", label);
        parts.part(
                        "file",
                        new ByteArrayResource(bytes.toByteArray()) {
                            @Override
                            public String getFilename() {
                                return "share.zip";
                            }
                        })
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        return client.method(version == null ? HttpMethod.POST : HttpMethod.PUT)
                .uri(
                        version == null
                                ? "/prototype-hub/projects/" + project + "/versions"
                                : "/prototype-hub/versions/" + version)
                .header("X-Access-Token", token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(parts.build()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("result");
    }

    private Actor actor(Map<String, List<String>> actions) {
        String key = UUID.randomUUID().toString().replace("-", "");
        result(HttpMethod.POST, "/role", admin, Map.of("id", key, "name", "Share test"));
        for (var permission : actions.entrySet())
            result(
                    HttpMethod.PATCH,
                    "/autz-setting",
                    admin,
                    Map.of(
                            "id",
                            UUID.randomUUID().toString().replace("-", ""),
                            "permission",
                            permission.getKey(),
                            "dimensionType",
                            "role",
                            "dimensionTarget",
                            key,
                            "actions",
                            permission.getValue(),
                            "state",
                            1));
        String username = "share-" + key;
        String id =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Share test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + key + "/users/_bind", admin, List.of(id));
        return new Actor(id, login(username));
    }

    private record Actor(String id, String token) {}
}
