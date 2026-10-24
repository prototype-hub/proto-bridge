package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.http.ResponseCookie;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 真 WebFlux、PostgreSQL 和本地 SMTP 验证访客身份，不向外部地址发送邮件。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000",
            "prototype-hub.review.trusted-origin=https://review.example.invalid",
            "spring.mail.properties.mail.smtp.connectiontimeout=2000",
            "spring.mail.properties.mail.smtp.timeout=2000",
            "spring.mail.properties.mail.smtp.writetimeout=2000"
        })
@Testcontainers
class PrototypeHubReviewIdentityIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String ORIGIN = "https://review.example.invalid";
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final LocalReviewMailbox MAILBOX = new LocalReviewMailbox();
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
        properties.add("prototype-hub.storage.root", FILES::toString);
        properties.add("spring.mail.host", () -> "127.0.0.1");
        properties.add("spring.mail.port", MAILBOX::port);
        properties.add("spring.mail.username", () -> "identity@example.invalid");
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired PrototypeRetentionService retention;
    private String admin;

    @BeforeEach
    void isolate() throws IOException {
        PrototypeHubTestData.clear(database);
        MAILBOX.reset();
        SecureZipExtractor.deleteRecursively(FILES);
        Files.createDirectories(FILES);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin =
                management(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", "admin", "password", PASSWORD),
                                200)
                        .path("token")
                        .asText();
    }

    @AfterAll
    static void cleanup() throws IOException {
        MAILBOX.close();
        SecureZipExtractor.deleteRecursively(FILES);
    }

    @Test
    void disabledByDefaultPolicyUsesCasAndDoesNotGrantAnInternalAccount() throws Exception {
        Fixture fixture = fixture(false, false);
        JsonNode policy = management(HttpMethod.GET, manage(fixture), admin, null, 200);
        assertFalse(policy.path("enabled").asBoolean());
        assertTrue(policy.path("mailAvailable").asBoolean());
        Actor viewer = actor(List.of("query", "save"));
        Actor outsider = actor(List.of("query", "save"));
        Actor noSave = actor(List.of("query"));
        for (Actor actor : List.of(viewer, noSave))
            management(
                    HttpMethod.PUT,
                    "/prototype-hub/projects/" + fixture.project + "/members/" + actor.id,
                    admin,
                    Map.of("role", actor.equals(viewer) ? "viewer" : "editor"),
                    200);
        for (Actor actor : List.of(viewer, outsider)) {
            int status = actor.equals(viewer) ? 403 : 404;
            management(HttpMethod.GET, manage(fixture), actor.token, null, status);
            management(
                    HttpMethod.PUT,
                    manage(fixture),
                    actor.token,
                    Map.of("enabled", true, "expectedVersion", 1),
                    status);
        }
        management(HttpMethod.GET, manage(fixture), noSave.token, null, 200);
        management(
                HttpMethod.PUT,
                manage(fixture),
                noSave.token,
                Map.of("enabled", true, "expectedVersion", 1),
                403);
        exchange(fixture, "context", Map.of(), null, null, ORIGIN, 403);
        management(
                HttpMethod.PUT,
                manage(fixture),
                null,
                Map.of("enabled", true, "expectedVersion", 1),
                401);
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "expectedVersion", 0),
                409);
        JsonNode enabled =
                management(
                        HttpMethod.PUT,
                        manage(fixture),
                        admin,
                        Map.of("enabled", true, "expectedVersion", 1),
                        200);
        assertEquals(2, enabled.path("configVersion").asLong());
        exchange(
                fixture, "context", Map.of(), null, null, ORIGIN,
                403); // Previous share proof rotated.
        Fixture fresh = refresh(fixture);
        long users = count("s_user");
        Browser browser = context(fresh, null);
        assertTrue(browser.data.path("session").isNull());
        Browser verified = verify(fresh, browser, "first@example.invalid", " 张三 ", " 公司 ");
        assertEquals("张三", verified.data.path("session").path("displayName").asText());
        assertEquals("公司", verified.data.path("session").path("company").asText());
        assertEquals("email", verified.data.path("session").path("verificationLevel").asText());
        assertEquals(users, count("s_user"));
        assertFalse(verified.data.toString().contains("first@example.invalid"));
        assertNotEquals(browser.cookie, verified.cookie);
        assertNotEquals(browser.csrf(), verified.csrf());
    }

    @Test
    void otpRecoversSameProjectIdentityAcrossDevicesButNeverMergesNamesOrProjects()
            throws Exception {
        Fixture first = fixture(true, false);
        Browser a =
                verify(first, context(first, null), " SAME@EXAMPLE.INVALID ", "Same name", null);
        sql("update p_review_rate set next_allowed_at=0");
        Browser b =
                verify(
                        first,
                        context(first, null),
                        "same@example.invalid",
                        "Other self-declared name",
                        null);
        assertEquals(a.guest(), b.guest());
        Browser otherMailbox =
                verify(first, context(first, null), "other@example.invalid", "Same name", null);
        assertNotEquals(a.guest(), otherMailbox.guest());
        Fixture foreign = fixture(true, false);
        Browser c =
                verify(foreign, context(foreign, null), "same@example.invalid", "Same name", null);
        assertNotEquals(a.guest(), c.guest());
        assertEquals(3, count("p_review_guest"));
        assertTrue(context(first, a.cookie).data.path("session").isObject());
        assertTrue(context(foreign, a.cookie).data.path("session").isNull());
    }

    @Test
    void wrongAttemptsCommitAndCorrectCodeCannotBeReplayedOrConsumeExpiredChallenges()
            throws Exception {
        Fixture fixture = fixture(true, false);
        Browser browser = context(fixture, null);
        JsonNode challenge =
                challenge(fixture, browser, "attempts@example.invalid", "Reviewer", null, 200);
        var mail = MAILBOX.receive();
        String wrong = mail.code().equals("000000") ? "111111" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            Browser result = code(fixture, browser, challenge, wrong, 400);
            assertEquals("PROTOTYPE_HUB_REVIEW_CODE_INVALID", result.data.path("code").asText());
            assertEquals(
                    attempt,
                    number(
                            "select attempts as value from p_review_challenge where id=:id",
                            challenge.path("challengeId").asText()));
        }
        code(fixture, browser, challenge, mail.code(), 400);
        JsonNode valid =
                challenge(fixture, browser, "valid@example.invalid", "Reviewer", null, 200);
        var validMail = MAILBOX.receive();
        Browser verified = code(fixture, browser, valid, validMail.code(), 200);
        assertNotNull(verified.guest());
        code(fixture, browser, valid, validMail.code(), 400);
        JsonNode expired =
                challenge(fixture, browser, "expired@example.invalid", "Reviewer", null, 200);
        var expiredMail = MAILBOX.receive();
        sql(
                "update p_review_challenge set expires_at=0 where id=:id",
                expired.path("challengeId").asText());
        code(fixture, browser, expired, expiredMail.code(), 400);
        assertEquals(1, count("p_review_guest"));
    }

    @Test
    void originCsrfCookiePasswordAndRevisionAreIndependentRequiredProofs() throws Exception {
        Fixture fixture = fixture(true, true);
        Browser browser = context(fixture, null);
        for (String origin :
                Arrays.asList(
                        null,
                        "null",
                        "https://attacker.example.invalid",
                        "https://review.example.invalid:444"))
            exchange(fixture, "context", Map.of(), null, null, origin, 403);
        exchange(
                fixture,
                "challenges",
                input("boundary@example.invalid", "Reviewer", null),
                browser.cookie,
                null,
                ORIGIN,
                403);
        exchange(
                fixture,
                "challenges",
                input("boundary@example.invalid", "Reviewer", null),
                browser.cookie,
                "wrong",
                ORIGIN,
                403);
        Browser other = context(fixture, null);
        exchange(
                fixture,
                "challenges",
                input("boundary@example.invalid", "Reviewer", null),
                other.cookie,
                browser.csrf(),
                ORIGIN,
                403);
        Fixture wrongKey =
                new Fixture(
                        fixture.project, fixture.version, fixture.share, fixture.grant.deepCopy());
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongKey.grant).put("accessKey", "wrong");
        exchange(wrongKey, "context", Map.of(), browser.cookie, null, ORIGIN, 403);
        Fixture wrongRevision =
                new Fixture(
                        fixture.project, fixture.version, fixture.share, fixture.grant.deepCopy());
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongRevision.grant)
                .put("revisionId", UUID.randomUUID().toString());
        exchange(wrongRevision, "context", Map.of(), browser.cookie, null, ORIGIN, 403);
        assertEquals(0, count("p_review_challenge"));
        ResponseCookie cookie = browser.cookieAttributes;
        assertNotNull(cookie);
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals("Strict", cookie.getSameSite());
        assertEquals(
                "/api/prototype-hub/public/shares/"
                        + fixture.share.path("token").asText()
                        + "/review",
                cookie.getPath());
        assertTrue(cookie.getDomain() == null || cookie.getDomain().isEmpty());
        assertTrue(browser.data.path("session").isNull());
    }

    @Test
    void parallelCorrectVerificationConsumesTheChallengeExactlyOnce() throws Exception {
        Fixture fixture = fixture(true, false);
        Browser browser = context(fixture, null);
        JsonNode challenge =
                challenge(fixture, browser, "parallel@example.invalid", "Reviewer", null, 200);
        String code = MAILBOX.receive().code();
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return exchange(
                                                    fixture,
                                                    "verify",
                                                    Map.of(
                                                            "challengeId",
                                                            challenge.path("challengeId").asText(),
                                                            "code",
                                                            code),
                                                    browser.cookie,
                                                    browser.csrf(),
                                                    ORIGIN,
                                                    200,
                                                    400)
                                            .status;
                                }));
            assertEquals(
                    List.of(200, 400),
                    futures.stream()
                            .map(
                                    f -> {
                                        try {
                                            return f.get(30, TimeUnit.SECONDS);
                                        } catch (Exception e) {
                                            throw new AssertionError(
                                                    "Concurrent verification did not complete", e);
                                        }
                                    })
                            .sorted()
                            .toList());
        }
        assertEquals(1, count("p_review_guest"));
        assertEquals(1, count("p_review_session"));
    }

    @Test
    void smtpPendingOrFailureNeverVerifiesAndSendCooldownCannotBeBypassed() throws Exception {
        Fixture fixture = fixture(true, false);
        Browser browser = context(fixture, null);
        MAILBOX.reject(true);
        challenge(fixture, browser, "failed@example.invalid", "Reviewer", null, 503);
        challenge(fixture, browser, "failed@example.invalid", "Reviewer", null, 429);
        assertEquals(0, count("p_review_guest"));
        assertEquals(0, count("p_review_session"));
        MAILBOX.reject(false);
        MAILBOX.pauseDelivery();
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<JsonNode> pending =
                    pool.submit(
                            () ->
                                    challenge(
                                            fixture,
                                            browser,
                                            "pending@example.invalid",
                                            "Reviewer",
                                            null,
                                            200));
            var mail = MAILBOX.receive();
            String id =
                    database.sql("select id from p_review_challenge where email=:email")
                            .bind("email", "pending@example.invalid")
                            .map((r, m) -> r.get("id", String.class))
                            .one()
                            .block(TIMEOUT);
            assertNotNull(id);
            exchange(
                    fixture,
                    "verify",
                    Map.of("challengeId", id, "code", mail.code()),
                    browser.cookie,
                    browser.csrf(),
                    ORIGIN,
                    400);
            MAILBOX.deliver();
            JsonNode sent = pending.get(30, TimeUnit.SECONDS);
            Browser verified = code(fixture, browser, sent, mail.code(), 200);
            assertNotNull(verified.guest());
        } finally {
            MAILBOX.deliver();
        }
    }

    @Test
    void logoutExpiryPolicyRotationAndSoftDeletionNeverReviveOldSessions() throws Exception {
        Fixture fixture = fixture(true, false);
        Browser verified =
                verify(
                        fixture,
                        context(fixture, null),
                        "lifecycle@example.invalid",
                        "Reviewer",
                        null);
        Browser out =
                exchange(
                        fixture, "logout", Map.of(), verified.cookie, verified.csrf(), ORIGIN, 200);
        assertTrue(out.data.path("loggedOut").asBoolean());
        assertTrue(context(fixture, verified.cookie).data.path("session").isNull());
        sql("update p_review_rate set next_allowed_at=0");
        verified =
                verify(
                        fixture,
                        context(fixture, null),
                        "lifecycle@example.invalid",
                        "Reviewer",
                        null);
        sql("update p_review_session set expires_at=0");
        assertTrue(context(fixture, verified.cookie).data.path("session").isNull());
        sql("update p_review_rate set next_allowed_at=0");
        Browser fresh =
                verify(
                        fixture,
                        context(fixture, null),
                        "lifecycle@example.invalid",
                        "Reviewer",
                        null);
        long version =
                management(HttpMethod.GET, manage(fixture), admin, null, 200)
                        .path("configVersion")
                        .asLong();
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", false, "expectedVersion", version),
                200);
        exchange(fixture, "context", Map.of(), fresh.cookie, null, ORIGIN, 403);
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "expectedVersion", version + 1),
                200);
        Fixture renewed = refresh(fixture);
        assertTrue(context(renewed, fresh.cookie).data.path("session").isNull());
        management(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + fixture.version.path("id").asText(),
                admin,
                null,
                200);
        exchange(renewed, "context", Map.of(), fresh.cookie, null, ORIGIN, 404);
    }

    @Test
    void permanentPurgeRemovesScopedIdentityGraphAndRollsBackOnDatabaseFailure() throws Exception {
        Fixture fixture = fixture(true, false);
        verify(fixture, context(fixture, null), "purge@example.invalid", "Reviewer", null);
        assertEquals(1, count("p_review_guest"));
        assertEquals(1, count("p_review_session"));
        management(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + fixture.version.path("id").asText(),
                admin,
                null,
                200);
        database.sql("update p_version set deleted_time=:expired where id=:id")
                .bind("expired", System.currentTimeMillis() - Duration.ofDays(31).toMillis())
                .bind("id", fixture.version.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeVersion(fixture.version.path("id").asText()).block(TIMEOUT));
        assertEquals(0, count("p_review_session"));
        assertEquals(0, count("p_review_challenge"));
        assertEquals(1, count("p_review_guest"));
        assertEquals(2, count("p_review_rate"));
        management(
                HttpMethod.DELETE, "/prototype-hub/projects/" + fixture.project, admin, null, 200);
        database.sql("update p_project set deleted_time=:expired where id=:id")
                .bind("expired", System.currentTimeMillis() - Duration.ofDays(31).toMillis())
                .bind("id", fixture.project)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        sql(
                "create function reject_review_fixture_delete() returns trigger language plpgsql as $$ begin raise exception 'fixture rejection'; end $$");
        sql(
                "create trigger reject_review_fixture_delete before delete on p_review_guest for each row execute function reject_review_fixture_delete()");
        try {
            assertThrows(
                    Exception.class, () -> retention.purgeProject(fixture.project).block(TIMEOUT));
            assertEquals(1, count("p_review_guest"));
            assertEquals(2, count("p_review_rate"));
            assertEquals(
                    1,
                    number(
                            "select count(*) as value from p_project where id=:id",
                            fixture.project));
        } finally {
            sql("drop trigger reject_review_fixture_delete on p_review_guest");
            sql("drop function reject_review_fixture_delete()");
        }
        assertTrue(retention.purgeProject(fixture.project).block(TIMEOUT));
        for (String table :
                List.of(
                        "p_review_guest",
                        "p_review_challenge",
                        "p_review_session",
                        "p_review_rate")) assertEquals(0, count(table));
    }

    private record Fixture(String project, JsonNode version, JsonNode share, JsonNode grant) {}

    private record Actor(String id, String token) {}

    private record Browser(
            JsonNode data, String cookie, ResponseCookie cookieAttributes, int status) {
        String csrf() {
            return data.path("csrfToken").asText();
        }

        String guest() {
            return data.path("session").path("guestId").asText(null);
        }
    }

    private Fixture fixture(boolean enable, boolean password) throws IOException {
        String project =
                management(
                                HttpMethod.POST,
                                "/prototype-hub/projects",
                                admin,
                                Map.of("name", "Review fixture"),
                                200)
                        .path("id")
                        .asText();
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.html"));
            zip.write("<html><body>Review fixture</body></html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var parts = new MultipartBodyBuilder();
        parts.part("versionName", "R1");
        parts.part(
                        "file",
                        new ByteArrayResource(bytes.toByteArray()) {
                            @Override
                            public String getFilename() {
                                return "review.zip";
                            }
                        })
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        JsonNode version =
                client.post()
                        .uri("/prototype-hub/projects/" + project + "/versions")
                        .header("X-Access-Token", admin)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(parts.build()))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .path("result");
        JsonNode share =
                management(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version.path("id").asText() + "/shares",
                        admin,
                        password ? Map.of("password", PASSWORD) : Map.of(),
                        200);
        Fixture fixture = new Fixture(project, version, share, null);
        if (enable)
            management(
                    HttpMethod.PUT,
                    manage(fixture),
                    admin,
                    Map.of("enabled", true, "expectedVersion", 1),
                    200);
        return refresh(fixture);
    }

    private Fixture refresh(Fixture fixture) {
        JsonNode grant =
                management(
                        HttpMethod.POST,
                        "/prototype-hub/public/shares/"
                                + fixture.share.path("token").asText()
                                + "/verify",
                        null,
                        Map.of("password", PASSWORD),
                        200);
        return new Fixture(fixture.project, fixture.version, fixture.share, grant);
    }

    private String manage(Fixture fixture) {
        return "/prototype-hub/shares/" + fixture.share.path("id").asText() + "/review";
    }

    private Browser context(Fixture fixture, String cookie) {
        return exchange(fixture, "context", Map.of(), cookie, null, ORIGIN, 200);
    }

    private JsonNode challenge(
            Fixture fixture,
            Browser browser,
            String email,
            String name,
            String company,
            int status) {
        return exchange(
                        fixture,
                        "challenges",
                        input(email, name, company),
                        browser.cookie,
                        browser.csrf(),
                        ORIGIN,
                        status)
                .data;
    }

    private Map<String, Object> input(String email, String name, String company) {
        var body = new HashMap<String, Object>();
        body.put("email", email);
        body.put("displayName", name);
        if (company != null) body.put("company", company);
        return body;
    }

    private Browser verify(
            Fixture fixture, Browser browser, String email, String name, String company)
            throws InterruptedException {
        JsonNode challenge = challenge(fixture, browser, email, name, company, 200);
        var mail = MAILBOX.receive();
        assertEquals(email.trim().toLowerCase(Locale.ROOT), mail.recipient());
        Browser verified = code(fixture, browser, challenge, mail.code(), 200);
        assertFalse(verified.data.toString().contains(mail.code()));
        return verified;
    }

    private Browser code(
            Fixture fixture, Browser browser, JsonNode challenge, String code, int status) {
        return exchange(
                fixture,
                "verify",
                Map.of("challengeId", challenge.path("challengeId").asText(), "code", code),
                browser.cookie,
                browser.csrf(),
                ORIGIN,
                status);
    }

    private Browser exchange(
            Fixture fixture,
            String operation,
            Map<String, Object> fields,
            String cookie,
            String csrf,
            String origin,
            int... statuses) {
        var body = new HashMap<String, Object>(fields);
        body.put("revisionId", fixture.grant.path("revisionId").asText());
        body.put("accessKey", fixture.grant.path("accessKey").asText());
        var request =
                client.post()
                        .uri(
                                "/api/prototype-hub/public/shares/"
                                        + fixture.share.path("token").asText()
                                        + "/review/"
                                        + operation)
                        .contentType(MediaType.APPLICATION_JSON);
        if (cookie != null) request.cookie("prototype-review", cookie);
        if (csrf != null) request.header("X-Review-CSRF", csrf);
        if (origin != null) request.header("Origin", origin);
        var result = request.bodyValue(body).exchange().expectBody(JsonNode.class).returnResult();
        assertTrue(
                Arrays.stream(statuses).anyMatch(status -> status == result.getStatus().value()),
                "Unexpected review HTTP status: " + result.getStatus().value());
        JsonNode data = result.getResponseBody();
        assertNotNull(data);
        var received = result.getResponseCookies().getFirst("prototype-review");
        return new Browser(
                result.getStatus().is2xxSuccessful() ? data.path("result") : data,
                received == null ? cookie : received.getValue(),
                received,
                result.getStatus().value());
    }

    private JsonNode management(
            HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        var response =
                request.exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        return status == 200 ? response.path("result") : response;
    }

    private Actor actor(List<String> actions) {
        String role = UUID.randomUUID().toString().replace("-", "");
        management(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Review test"), 200);
        management(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        role,
                        "permission",
                        "prototype-hub-share",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        actions,
                        "state",
                        1),
                200);
        String username = "review-" + role;
        String id =
                management(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Review test",
                                        "username",
                                        username,
                                        "password",
                                        PASSWORD),
                                200)
                        .path("id")
                        .asText();
        management(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(id), 200);
        String token =
                management(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", username, "password", PASSWORD),
                                200)
                        .path("token")
                        .asText();
        return new Actor(id, token);
    }

    private long count(String table) {
        return database.sql(
                        switch (table) {
                            case "s_user" -> "select count(*) as value from s_user";
                            case "p_review_guest" -> "select count(*) as value from p_review_guest";
                            case "p_review_challenge" ->
                                    "select count(*) as value from p_review_challenge";
                            case "p_review_session" ->
                                    "select count(*) as value from p_review_session";
                            case "p_review_rate" -> "select count(*) as value from p_review_rate";
                            default -> throw new IllegalArgumentException("Unknown fixture table");
                        })
                .map((r, m) -> r.get("value", Long.class))
                .one()
                .block(TIMEOUT);
    }

    private long number(String statement, String id) {
        return database.sql(statement)
                .bind("id", id)
                .map((r, m) -> ((Number) r.get("value")).longValue())
                .one()
                .block(TIMEOUT);
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(TIMEOUT);
    }

    private void sql(String statement, String id) {
        database.sql(statement).bind("id", id).fetch().rowsUpdated().block(TIMEOUT);
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-review-http-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
