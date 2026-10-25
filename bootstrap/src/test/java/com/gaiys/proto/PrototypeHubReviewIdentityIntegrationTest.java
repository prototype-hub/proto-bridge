package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.service.PrototypeReviewService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
            "spring.mail.properties.mail.smtp.auth=false",
            "spring.mail.properties.mail.smtp.starttls.enable=false",
            "spring.mail.properties.mail.smtp.starttls.required=false",
            "spring.mail.properties.mail.smtp.ssl.enable=false",
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
        // 本地私密配置可能使用SMTPS和真实凭据；夹具必须固定为loopback明文SMTP且不继承它们。
        properties.add("spring.mail.protocol", () -> "smtp");
        properties.add("spring.mail.password", () -> "");
        properties.add("spring.mail.properties.mail.smtp.from", () -> "identity@example.invalid");
        properties.add("spring.mail.properties.mail.from", () -> "identity@example.invalid");
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired PrototypeRetentionService retention;
    @Autowired PrototypeReviewService review;
    @Autowired com.gaiys.proto.prototypehub.service.PrototypeReviewThreadService reviewThreads;
    @Autowired com.gaiys.proto.prototypehub.service.FileCleanupService fileCleanup;
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
            assertEquals(
                    "PROTOTYPE_HUB_REVIEW_ORIGIN_DENIED",
                    exchange(fixture, "context", Map.of(), null, null, origin, 403)
                            .data
                            .path("code")
                            .asText());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_COOKIE_REQUIRED",
                exchange(
                                fixture,
                                "challenges",
                                input("boundary@example.invalid", "Reviewer", null),
                                null,
                                browser.csrf(),
                                ORIGIN,
                                403)
                        .data
                        .path("code")
                        .asText());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_CSRF_INVALID",
                exchange(
                                fixture,
                                "challenges",
                                input("boundary@example.invalid", "Reviewer", null),
                                browser.cookie,
                                null,
                                ORIGIN,
                                403)
                        .data
                        .path("code")
                        .asText());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_CSRF_INVALID",
                exchange(
                                fixture,
                                "challenges",
                                input("boundary@example.invalid", "Reviewer", null),
                                browser.cookie,
                                "wrong",
                                ORIGIN,
                                403)
                        .data
                        .path("code")
                        .asText());
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
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_DENIED",
                exchange(wrongKey, "context", Map.of(), browser.cookie, null, ORIGIN, 403)
                        .data
                        .path("code")
                        .asText());
        Fixture wrongRevision =
                new Fixture(
                        fixture.project, fixture.version, fixture.share, fixture.grant.deepCopy());
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongRevision.grant)
                .put("revisionId", UUID.randomUUID().toString());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_DENIED",
                exchange(wrongRevision, "context", Map.of(), browser.cookie, null, ORIGIN, 403)
                        .data
                        .path("code")
                        .asText());
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

    @Test
    void guestManagementUsesProjectShareAndMenuPermissionsWithBoundedMaskedPagination()
            throws Exception {
        Fixture fixture = fixture(true, false);
        Browser verified =
                verify(fixture, context(fixture, null), "listed@example.invalid", "访客", "自述公司");
        // F22a旧行没有状态/版本；升级不能无意停用已有邮箱身份。
        sql(
                "update p_review_guest set enabled=null,config_version=null where id=:id",
                verified.guest());
        long now = System.currentTimeMillis();
        for (int i = 0; i < 4; i++)
            database.sql(
                            "insert into p_review_guest(id,project_id,email,display_name,company,create_time,modify_time) select :id,project_id,:email,:name,company,create_time,:modified from p_review_guest where id=:source")
                    .bind("id", "page-" + i)
                    .bind("email", "page-" + i + "@example.invalid")
                    .bind("name", "访客" + i)
                    .bind("modified", now + i)
                    .bind("source", verified.guest())
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        Fixture foreign = fixture(true, false);
        Browser otherProject =
                verify(foreign, context(foreign, null), "listed@example.invalid", "其他项目", null);
        JsonNode first = guestPage(fixture, admin, 0, 2, 200);
        JsonNode second = guestPage(fixture, admin, 1, 2, 200);
        assertEquals(5, first.path("total").asInt());
        assertEquals("page-3", first.path("data").get(0).path("id").asText());
        assertEquals("page-2", first.path("data").get(1).path("id").asText());
        assertEquals("page-1", second.path("data").get(0).path("id").asText());
        JsonNode last = guestPage(fixture, admin, 2, 2, 200).path("data").get(0);
        assertEquals(verified.guest(), last.path("id").asText());
        assertTrue(last.path("enabled").asBoolean());
        assertEquals(0, last.path("configVersion").asLong());
        assertEquals("email", last.path("verificationLevel").asText());
        var fields = new java.util.HashSet<String>();
        last.fieldNames().forEachRemaining(fields::add);
        assertEquals(
                java.util.Set.of(
                        "id",
                        "displayName",
                        "company",
                        "maskedEmail",
                        "verificationLevel",
                        "enabled",
                        "configVersion",
                        "createTime",
                        "modifyTime",
                        "expiresAt"),
                fields);
        for (String rawEmail :
                List.of(
                        "page-3@example.invalid",
                        "page-2@example.invalid",
                        "listed@example.invalid")) assertFalse(first.toString().contains(rawEmail));
        assertNotEquals("listed@example.invalid", last.path("maskedEmail").asText());
        JsonNode beyond = guestPage(fixture, admin, 10000, 2, 200);
        assertEquals(5, beyond.path("total").asInt());
        assertEquals(10000, beyond.path("pageIndex").asInt());
        assertTrue(beyond.path("data").isEmpty());
        for (Map<String, Integer> invalid :
                List.of(
                        Map.of("pageIndex", -1),
                        Map.of("pageSize", 101),
                        Map.of("pageSize", 0),
                        Map.of("pageIndex", 10001)))
            management(HttpMethod.POST, guestPath(fixture) + "/_query", admin, invalid, 400);
        guestPage(fixture, null, 0, 20, 401);
        Actor editor = actor(List.of("query", "save"));
        Actor viewer = actor(List.of("query", "save"));
        Actor outsider = actor(List.of("query", "save"));
        Actor noSave = actor(List.of("query"));
        Actor noQuery = actor(List.of("save"));
        for (Actor actor : List.of(editor, viewer, noSave, noQuery))
            management(
                    HttpMethod.PUT,
                    "/prototype-hub/projects/" + fixture.project + "/members/" + actor.id,
                    admin,
                    Map.of("role", actor.equals(viewer) ? "viewer" : "editor"),
                    200);
        guestPage(fixture, editor.token, 0, 20, 200);
        guestPage(fixture, viewer.token, 0, 20, 403);
        guestPage(fixture, outsider.token, 0, 20, 404);
        guestPage(fixture, noQuery.token, 0, 20, 403);
        guestEnabled(fixture, verified.guest(), noSave.token, false, 0, 403);
        guestEnabled(fixture, verified.guest(), noQuery.token, true, 0, 200);
        management(
                HttpMethod.PUT,
                guestPath(fixture) + "/" + verified.guest() + "/enabled",
                admin,
                Map.of("enabled", false),
                400);
        management(
                HttpMethod.PUT,
                guestPath(fixture) + "/" + verified.guest() + "/enabled",
                admin,
                Map.of("enabled", false, "expectedVersion", -1),
                400);
        guestEnabled(fixture, verified.guest(), viewer.token, false, 0, 403);
        guestEnabled(fixture, verified.guest(), outsider.token, false, 0, 404);
        guestEnabled(fixture, otherProject.guest(), admin, false, 0, 404);
        JsonNode noOp = guestEnabled(fixture, verified.guest(), editor.token, true, 0, 200);
        assertEquals(0, noOp.path("configVersion").asLong());
        assertNotNull(context(fixture, verified.cookie).guest());
        // 所有者与编辑者沿现有SHARE能力，不另建客户账号/角色。
        database.sql("update p_project set owner_id=:owner where id=:id")
                .bind("owner", editor.id)
                .bind("id", fixture.project)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        guestPage(fixture, editor.token, 0, 20, 200);
        JsonNode disabled = guestEnabled(fixture, verified.guest(), editor.token, false, 0, 200);
        assertFalse(disabled.path("enabled").asBoolean());
        assertEquals(1, disabled.path("configVersion").asLong());
        guestEnabled(fixture, verified.guest(), editor.token, true, 0, 409);
        management(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + fixture.version.path("id").asText(),
                admin,
                null,
                200);
        management(
                HttpMethod.DELETE, "/prototype-hub/projects/" + fixture.project, admin, null, 200);
        guestPage(fixture, admin, 0, 20, 404);
        guestEnabled(fixture, verified.guest(), admin, true, 1, 404);
    }

    @Test
    void disablingGuestRevokesAllProjectDevicesAndCodesWithoutAffectingPreviewOrOtherGuests()
            throws Exception {
        Fixture fixture = fixture(true, false);
        Browser first =
                verify(fixture, context(fixture, null), "revoke@example.invalid", "访客", null);
        sql("update p_review_rate set next_allowed_at=0");
        Browser second =
                verify(fixture, context(fixture, null), "revoke@example.invalid", "访客", null);
        Browser unrelated =
                verify(fixture, context(fixture, null), "unrelated@example.invalid", "其他访客", null);
        Fixture foreign = fixture(true, false);
        Browser foreignSession =
                verify(foreign, context(foreign, null), "revoke@example.invalid", "跨项目", null);
        sql("update p_review_rate set next_allowed_at=0");
        Browser pendingBrowser = context(fixture, null);
        JsonNode pending =
                challenge(fixture, pendingBrowser, "revoke@example.invalid", "访客", null, 200);
        String oldCode = MAILBOX.receive().code();
        long ratesBefore = count("p_review_rate");
        guestEnabled(fixture, first.guest(), admin, false, 0, 200);
        assertNull(context(fixture, first.cookie).guest());
        assertNull(context(fixture, second.cookie).guest());
        assertNotNull(context(fixture, unrelated.cookie).guest());
        assertNotNull(context(foreign, foreignSession.cookie).guest());
        challenge(fixture, pendingBrowser, "REVOKE@EXAMPLE.INVALID", "访客", null, 403);
        assertEquals(0, MAILBOX.pendingMessages());
        code(fixture, pendingBrowser, pending, oldCode, 400);
        assertEquals(ratesBefore, count("p_review_rate"));
        client.get()
                .uri(fixture.grant.path("previewUrl").asText())
                .exchange()
                .expectStatus()
                .isOk();
        JsonNode restored = guestEnabled(fixture, first.guest(), admin, true, 1, 200);
        assertEquals(2, restored.path("configVersion").asLong());
        assertNull(context(fixture, first.cookie).guest());
        assertNull(context(fixture, second.cookie).guest());
        code(fixture, pendingBrowser, pending, oldCode, 400);
        sql("update p_review_rate set next_allowed_at=0");
        Browser fresh =
                verify(fixture, context(fixture, null), "revoke@example.invalid", "访客", null);
        assertEquals(first.guest(), fresh.guest());
        JsonNode row = guestPage(fixture, admin, 0, 20, 200).path("data").get(0);
        assertEquals(2, row.path("configVersion").asLong());
    }

    @Test
    void mailAcceptedDuringDisableAndRestoreCannotResurrectTheDeletedChallenge() throws Exception {
        Fixture fixture = fixture(true, false);
        Browser verified =
                verify(fixture, context(fixture, null), "delayed@example.invalid", "访客", null);
        sql("update p_review_rate set next_allowed_at=0");
        Browser browser = context(fixture, null);
        MAILBOX.pauseDelivery();
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<JsonNode> pending =
                    pool.submit(
                            () ->
                                    challenge(
                                            fixture,
                                            browser,
                                            "delayed@example.invalid",
                                            "访客",
                                            null,
                                            400));
            String oldCode = MAILBOX.receive().code();
            String challengeId =
                    database.sql("select id from p_review_challenge where email=:email")
                            .bind("email", "delayed@example.invalid")
                            .map((r, m) -> r.get("id", String.class))
                            .one()
                            .block(TIMEOUT);
            assertNotNull(challengeId);
            guestEnabled(fixture, verified.guest(), admin, false, 0, 200);
            guestEnabled(fixture, verified.guest(), admin, true, 1, 200);
            MAILBOX.deliver();
            pending.get(30, TimeUnit.SECONDS);
            var old =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                            .objectNode()
                            .put("challengeId", challengeId);
            code(fixture, browser, old, oldCode, 400);
            assertNull(context(fixture, verified.cookie).guest());
            assertEquals(0, count("p_review_session"));
            assertEquals(0, count("p_review_challenge"));
        } finally {
            MAILBOX.deliver();
        }
        sql("update p_review_rate set next_allowed_at=0");
        Browser fresh =
                verify(fixture, context(fixture, null), "delayed@example.invalid", "访客", null);
        assertEquals(verified.guest(), fresh.guest());
    }

    @Test
    void guestDisableIsAtomicAndStaleConcurrentWritesCannotRestoreAccess() throws Exception {
        Fixture fixture = fixture(true, false);
        Browser verified =
                verify(fixture, context(fixture, null), "rollback@example.invalid", "访客", null);
        sql("update p_review_rate set next_allowed_at=0");
        Browser browser = context(fixture, null);
        JsonNode pending = challenge(fixture, browser, "rollback@example.invalid", "访客", null, 200);
        String code = MAILBOX.receive().code();
        long challengesBefore = count("p_review_challenge");
        sql(
                "create function reject_review_session_delete() returns trigger language plpgsql as $$ begin raise exception 'fixture rejection'; end $$");
        sql(
                "create trigger reject_review_session_delete before delete on p_review_session for each row execute function reject_review_session_delete()");
        try {
            guestEnabled(fixture, verified.guest(), admin, false, 0, 500);
            JsonNode row = guestPage(fixture, admin, 0, 20, 200).path("data").get(0);
            assertTrue(row.path("enabled").asBoolean());
            assertEquals(0, row.path("configVersion").asLong());
            assertEquals(challengesBefore, count("p_review_challenge"));
            assertEquals(verified.guest(), context(fixture, verified.cookie).guest());
        } finally {
            sql("drop trigger reject_review_session_delete on p_review_session");
            sql("drop function reject_review_session_delete()");
        }
        Browser accepted = code(fixture, browser, pending, code, 200);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++)
                results.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return client.put()
                                            .uri(
                                                    guestPath(fixture)
                                                            + "/"
                                                            + verified.guest()
                                                            + "/enabled")
                                            .header("X-Access-Token", admin)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .bodyValue(
                                                    Map.of("enabled", false, "expectedVersion", 0))
                                            .exchange()
                                            .expectBody(JsonNode.class)
                                            .returnResult()
                                            .getStatus()
                                            .value();
                                }));
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 409), statuses);
        }
        assertNull(context(fixture, accepted.cookie).guest());
        assertEquals(
                1,
                guestPage(fixture, admin, 0, 20, 200)
                        .path("data")
                        .get(0)
                        .path("configVersion")
                        .asLong());
    }

    @Test
    void concurrentCorrectOtpCannotLeaveALiveSessionAfterGuestDisableCommits() throws Exception {
        Fixture fixture = fixture(true, false);
        Browser original =
                verify(fixture, context(fixture, null), "race@example.invalid", "访客", null);
        sql("update p_review_rate set next_allowed_at=0");
        Browser browser = context(fixture, null);
        JsonNode pending = challenge(fixture, browser, "race@example.invalid", "访客", null, 200);
        String value = MAILBOX.receive().code();
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Browser> verification =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return exchange(
                                        fixture,
                                        "verify",
                                        Map.of(
                                                "challengeId",
                                                pending.path("challengeId").asText(),
                                                "code",
                                                value),
                                        browser.cookie,
                                        browser.csrf(),
                                        ORIGIN,
                                        200,
                                        400);
                            });
            Future<JsonNode> disabling =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return guestEnabled(
                                        fixture, original.guest(), admin, false, 0, 200);
                            });
            Browser outcome = verification.get(30, TimeUnit.SECONDS);
            assertFalse(disabling.get(30, TimeUnit.SECONDS).path("enabled").asBoolean());
            assertNull(context(fixture, original.cookie).guest());
            assertNull(context(fixture, outcome.cookie).guest());
            assertEquals(0, count("p_review_session"));
            assertEquals(0, count("p_review_challenge"));
        }
    }

    private String guestPath(Fixture fixture) {
        return "/prototype-hub/projects/" + fixture.project + "/review-guests";
    }

    private JsonNode guestPage(Fixture fixture, String token, int page, int size, int status) {
        return management(
                HttpMethod.POST,
                guestPath(fixture) + "/_query",
                token,
                Map.of("pageIndex", page, "pageSize", size),
                status);
    }

    @Test
    void explicitUnverifiedPolicyIsOffByDefaultAndLegacyPolicyUpdatesPreserveIt() throws Exception {
        Fixture fixture = fixture(true, false);
        assertFalse(
                management(HttpMethod.GET, manage(fixture), admin, null, 200)
                        .path("allowUnverified")
                        .asBoolean());
        assertFalse(fixture.grant.path("reviewUnverifiedAllowed").asBoolean());
        Browser browser = context(fixture, null);
        unverified(fixture, browser, "Visitor", null, 403);
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "allowUnverified", true, "expectedVersion", 2),
                200);
        fixture = refresh(fixture);
        assertTrue(fixture.grant.path("reviewUnverifiedAllowed").asBoolean());
        assertTrue(context(fixture, null).data.path("allowUnverified").asBoolean());
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "expectedVersion", 3),
                200);
        assertTrue(
                management(HttpMethod.GET, manage(fixture), admin, null, 200)
                        .path("allowUnverified")
                        .asBoolean());
        fixture = refresh(fixture);
        browser = context(fixture, null);
        for (Map<String, Object> invalid :
                List.<Map<String, Object>>of(
                        Map.of("displayName", " "), Map.of("displayName", "\u2003"),
                        Map.of("displayName", "\u3000"), Map.of("displayName", "x".repeat(101)),
                        Map.of("displayName", "Visitor\n"),
                                Map.of("displayName", "Visitor", "company", "x\u0085")))
            exchange(fixture, "unverified", invalid, browser.cookie, browser.csrf(), ORIGIN, 400);
        assertEquals(0, count("p_review_unverified_guest"));
        for (String blank : List.of("\u2003", "\u3000"))
            exchange(
                    fixture,
                    "challenges",
                    Map.of("email", "invalid@example.invalid", "displayName", blank),
                    browser.cookie,
                    browser.csrf(),
                    ORIGIN,
                    400);
        assertEquals(0, MAILBOX.pendingMessages());
        unverified(fixture, browser, "张三", null, 200);
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "allowUnverified", false, "expectedVersion", 4),
                200);
        fixture = refresh(fixture);
        unverified(fixture, context(fixture, null), "Visitor", null, 403);
    }

    @Test
    void unverifiedIdentityIsRandomPrivateFixedEightHoursAndReuseDoesNotChangeProfileOrQuota()
            throws Exception {
        Fixture fixture = unverifiedFixture();
        Browser anonymous = context(fixture, null);
        Browser first = unverified(fixture, anonymous, " Visitor ", " Company ", 200);
        assertNotEquals(anonymous.cookie, first.cookie);
        assertEquals(43, first.guest().length());
        assertEquals("unverified", first.data.path("session").path("verificationLevel").asText());
        assertTrue(first.data.path("session").path("maskedEmail").isNull());
        assertEquals("Visitor", first.data.path("session").path("displayName").asText());
        long expiry = first.data.path("session").path("expiresAt").asLong();
        assertEquals(
                PrototypeReviewService.SESSION_TTL,
                number(
                        "select expires_at-create_time as value from p_review_unverified_guest where id=:id",
                        first.guest()));
        Browser reused = unverified(fixture, first, "Changed", null, 200);
        assertEquals(first.cookie, reused.cookie);
        assertEquals(first.guest(), reused.guest());
        assertEquals(expiry, reused.data.path("session").path("expiresAt").asLong());
        assertEquals("Visitor", reused.data.path("session").path("displayName").asText());
        assertEquals("Company", reused.data.path("session").path("company").asText());
        assertEquals(1, count("p_review_unverified_guest"));
        assertEquals(2, count("p_review_rate"));
        Browser different = unverified(fixture, context(fixture, null), "Visitor", null, 200);
        assertNotEquals(first.guest(), different.guest());
        assertEquals(0, count("p_review_guest"));
        assertEquals(0, count("p_review_challenge"));
        assertEquals(0, MAILBOX.pendingMessages());
        assertFalse(first.data.toString().contains(first.cookie));
        assertNotNull(context(fixture, first.cookie).guest());
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "expectedVersion", 3),
                200);
        exchange(fixture, "context", Map.of(), first.cookie, null, ORIGIN, 403);
        fixture = refresh(fixture);
        assertNull(context(fixture, first.cookie).guest());
        Browser fresh = unverified(fixture, first, "Explicit fresh identity", null, 200);
        assertNotEquals(first.guest(), fresh.guest());
        assertNotEquals(first.cookie, fresh.cookie);
        assertNotEquals(expiry, fresh.data.path("session").path("expiresAt").asLong());
        assertNull(context(fixture, first.cookie).guest());
        assertEquals(
                expiry,
                number(
                        "select expires_at as value from p_review_unverified_guest where id=:id",
                        first.guest()));
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "allowUnverified", false, "expectedVersion", 4),
                200);
        exchange(fixture, "context", Map.of(), first.cookie, null, ORIGIN, 403);
        fixture = refresh(fixture);
        assertNull(context(fixture, first.cookie).guest());
        unverified(fixture, first, "Visitor", null, 403);
    }

    @Test
    void unverifiedStartKeepsOriginCookieCsrfGrantAndVerifiedIdentityBoundaries() throws Exception {
        Fixture fixture = unverifiedFixture();
        Browser anonymous = context(fixture, null);
        var fields = Map.<String, Object>of("displayName", "Visitor");
        exchange(
                fixture,
                "unverified",
                fields,
                anonymous.cookie,
                anonymous.csrf(),
                "https://foreign.example.invalid",
                403);
        exchange(fixture, "unverified", fields, null, anonymous.csrf(), ORIGIN, 403);
        exchange(fixture, "unverified", fields, anonymous.cookie, "invalid", ORIGIN, 403);
        Browser verified =
                verify(fixture, anonymous, "ownership@example.invalid", "Verified", null);
        Browser conflict = unverified(fixture, verified, "Visitor", null, 409);
        assertEquals("PROTOTYPE_HUB_REVIEW_IDENTITY_EXISTS", conflict.data.path("code").asText());
        assertEquals(0, count("p_review_unverified_guest"));
        // 持久化历史会话没有新增等级，仍按邮箱身份；未知值不得当作新低保障入口恢复。
        sql(
                "update p_review_session set verification_level=null where guest_id=:id",
                verified.guest());
        assertEquals(
                "email",
                context(fixture, verified.cookie)
                        .data
                        .path("session")
                        .path("verificationLevel")
                        .asText());
        sql(
                "update p_review_session set verification_level='unknown' where guest_id=:id",
                verified.guest());
        unverified(fixture, verified, "Visitor", null, 403);
        assertEquals(0, count("p_review_unverified_guest"));
        sql(
                "update p_review_session set verification_level=null where guest_id=:id",
                verified.guest());
        Fixture foreign = unverifiedFixture();
        Browser separate = unverified(foreign, verified, "Visitor", null, 200);
        assertNotEquals(verified.guest(), separate.guest());
        assertNotEquals(verified.cookie, separate.cookie);
        assertEquals(1, count("p_review_unverified_guest"));
    }

    @Test
    void unverifiedManagementUsesSeparateSelectorPermissionsAndNeverTouchesEmailOtp()
            throws Exception {
        Fixture fixture = unverifiedFixture();
        Browser email =
                verify(
                        fixture,
                        context(fixture, null),
                        "independent@example.invalid",
                        "Verified",
                        null);
        Browser first = unverified(fixture, context(fixture, null), "Low", null, 200);
        JsonNode page = unverifiedPage(fixture, admin, 0, 1, 200);
        assertEquals(1, page.path("total").asInt());
        assertEquals(first.guest(), page.path("data").get(0).path("id").asText());
        assertTrue(page.path("data").get(0).path("maskedEmail").isNull());
        assertEquals(1, guestPage(fixture, admin, 0, 20, 200).path("total").asInt());
        assertTrue(unverifiedPage(fixture, admin, 10000, 1, 200).path("data").isEmpty());
        for (Object invalidLevel : List.of("unknown", "", 0, 1, "0", "1"))
            management(
                    HttpMethod.POST,
                    guestPath(fixture) + "/_query",
                    admin,
                    Map.of("verificationLevel", invalidLevel),
                    400);
        Map<String, Object> nullLevel = new HashMap<>();
        nullLevel.put("verificationLevel", null);
        management(HttpMethod.POST, guestPath(fixture) + "/_query", admin, nullLevel, 400);
        for (String selector :
                List.of(
                        "unknown",
                        "",
                        "email&verificationLevel=unverified",
                        "unverified&verificationLevel=unverified"))
            management(
                    HttpMethod.PUT,
                    guestPath(fixture)
                            + "/"
                            + first.guest()
                            + "/enabled?verificationLevel="
                            + selector,
                    admin,
                    Map.of("enabled", false, "expectedVersion", 0),
                    400);
        guestEnabled(fixture, first.guest(), admin, false, 0, 404);
        unverifiedEnabled(fixture, email.guest(), admin, false, 0, 404);
        Actor viewer = actor(List.of("query", "save"));
        management(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + fixture.project + "/members/" + viewer.id,
                admin,
                Map.of("role", "viewer"),
                200);
        unverifiedPage(fixture, viewer.token, 0, 20, 403);
        unverifiedEnabled(fixture, first.guest(), viewer.token, false, 0, 403);
        unverifiedPage(fixture, null, 0, 20, 401);
        long quota = count("p_review_rate");
        unverifiedEnabled(fixture, first.guest(), admin, false, 0, 200);
        assertNull(context(fixture, first.cookie).guest());
        assertNotNull(context(fixture, email.cookie).guest());
        unverifiedEnabled(fixture, first.guest(), admin, true, 0, 409);
        unverifiedEnabled(fixture, first.guest(), admin, true, 1, 200);
        assertNull(context(fixture, first.cookie).guest());
        assertEquals(quota, count("p_review_rate"));
        sql("update p_review_unverified_guest set expires_at=0 where id=:id", first.guest());
        unverifiedEnabled(fixture, first.guest(), admin, false, 2, 404);
    }

    @Test
    void unverifiedCookieQuotaCooldownAndProjectQuotaAreDistinctFromEmailAndAtomic()
            throws Exception {
        Fixture fixture = unverifiedFixture();
        Browser anonymous = context(fixture, null);
        unverified(fixture, anonymous, "Visitor", null, 200);
        unverified(fixture, anonymous, "Visitor", null, 429);
        for (int i = 1; i < 5; i++) {
            sql("update p_review_rate set next_allowed_at=0");
            unverified(fixture, anonymous, "Visitor", null, 200);
        }
        sql("update p_review_rate set next_allowed_at=0");
        unverified(fixture, anonymous, "Visitor", null, 429);
        assertEquals(5, count("p_review_unverified_guest"));
        // 找出项目账本（跨Cookie唯一共享的行），保留Cookie账本的独立五次限制。
        String projectRate =
                com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys.digest(
                        "review-rate-v1:" + fixture.project + ":unverified-project");
        sql("update p_review_rate set send_count=59 where id=:id", projectRate);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Browser one = context(fixture, null), two = context(fixture, null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Browser> a =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return unverified(fixture, one, "A", null, 200, 429);
                            });
            Future<Browser> b =
                    pool.submit(
                            () -> {
                                barrier.await(10, TimeUnit.SECONDS);
                                return unverified(fixture, two, "B", null, 200, 429);
                            });
            var statuses =
                    new ArrayList<>(
                            List.of(
                                    a.get(30, TimeUnit.SECONDS).status,
                                    b.get(30, TimeUnit.SECONDS).status));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 429), statuses);
        }
        assertEquals(
                60,
                number("select send_count as value from p_review_rate where id=:id", projectRate));
        assertEquals(6, count("p_review_unverified_guest"));
        challenge(
                fixture,
                context(fixture, null),
                "otp-independent@example.invalid",
                "Verified",
                null,
                200);
        assertEquals(1, MAILBOX.pendingMessages());
    }

    @Test
    void simultaneousSameCookieStartsCreateOnlyOneIdentityAndTransactionFailureLeavesNoRows()
            throws Exception {
        Fixture fixture = unverifiedFixture();
        Browser browser = context(fixture, null);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Browser>>();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return unverified(fixture, browser, "Visitor", null, 200, 429);
                                }));
            var statuses = new ArrayList<Integer>();
            for (var task : tasks) statuses.add(task.get(30, TimeUnit.SECONDS).status);
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 429), statuses);
        }
        assertEquals(1, count("p_review_unverified_guest"));
        assertEquals(1, count("p_review_session"));
        long rateRows = count("p_review_rate");
        sql(
                "create function reject_unverified_session_fixture() returns trigger language plpgsql as $$ begin if NEW.verification_level='unverified' then raise exception 'fixture failure'; end if; return NEW; end $$");
        sql(
                "create trigger reject_unverified_session_fixture before insert on p_review_session for each row execute function reject_unverified_session_fixture()");
        try {
            unverified(fixture, context(fixture, null), "Rollback", null, 500);
            assertEquals(1, count("p_review_unverified_guest"));
            assertEquals(1, count("p_review_session"));
            assertEquals(rateRows, count("p_review_rate"));
        } finally {
            sql("drop trigger reject_unverified_session_fixture on p_review_session");
            sql("drop function reject_unverified_session_fixture()");
        }
    }

    @Test
    void expiredUnverifiedIdentityCannotRecoverAndCleanupIsBoundedWhileEmailGuestRemains()
            throws Exception {
        Fixture fixture = unverifiedFixture();
        Browser email =
                verify(
                        fixture,
                        context(fixture, null),
                        "retained@example.invalid",
                        "Verified",
                        null);
        Browser first = unverified(fixture, context(fixture, null), "Expired", null, 200);
        sql("update p_review_unverified_guest set expires_at=1 where id=:id", first.guest());
        sql("update p_review_session set expires_at=1 where guest_id=:id", first.guest());
        assertNull(context(fixture, first.cookie).guest());
        Browser replacement = unverified(fixture, first, "Fresh", null, 200);
        assertNotEquals(first.guest(), replacement.guest());
        for (int i = 0; i < 55; i++)
            database.sql(
                            "insert into p_review_unverified_guest(id,project_id,display_name,enabled,config_version,create_time,modify_time,expires_at) select :id,project_id,display_name,enabled,config_version,create_time,modify_time,1 from p_review_unverified_guest where id=:source")
                    .bind("id", "expired-" + i)
                    .bind("source", first.guest())
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        review.cleanupExpired().block(TIMEOUT);
        assertEquals(7, count("p_review_unverified_guest"));
        review.cleanupExpired().block(TIMEOUT);
        assertEquals(1, count("p_review_unverified_guest"));
        assertNotNull(context(fixture, replacement.cookie).guest());
        assertNotNull(context(fixture, email.cookie).guest());
        assertEquals(1, count("p_review_guest"));
        review.purgeProject(fixture.project).block(TIMEOUT);
        assertEquals(0, count("p_review_unverified_guest"));
        assertEquals(0, count("p_review_session"));
        assertEquals(0, count("p_review_guest"));
    }

    @Test
    void invitationCreationIsFixedPrivateBoundedAndManagementRequiresBothPermissions()
            throws Exception {
        Fixture f = fixture(true, false);
        JsonNode created = invite(f, " Recipient@Example.Invalid ", 200);
        JsonNode row = created.path("invitation");
        String token = created.path("invitationToken").asText();
        assertEquals(43, token.length());
        assertNotEquals(row.path("id").asText(), token);
        assertEquals("pending", row.path("status").asText());
        assertEquals(
                24 * 60 * 60 * 1000L,
                row.path("expiresAt").asLong() - row.path("createdAt").asLong());
        assertFalse(created.toString().contains("recipient@example.invalid"));
        assertEquals(0, MAILBOX.pendingMessages());
        JsonNode page = invitePage(f, admin, 0, 20, 200);
        assertEquals(1, page.path("total").asInt());
        assertFalse(page.toString().contains(token));
        assertFalse(page.toString().contains("recipient@example.invalid"));
        assertEquals(
                0,
                number(
                        "select count(*) as value from p_review_invitation where token_hash=:id",
                        token));
        assertTrue(invitePage(f, admin, 10000, 1, 200).path("data").isEmpty());
        management(HttpMethod.POST, invitePath(f) + "/_query", admin, Map.of("pageSize", 101), 400);
        invitePage(f, null, 0, 20, 401);
        Actor noSave = actor(List.of("query")),
                noQuery = actor(List.of("save")),
                viewer = actor(List.of("query", "save"));
        for (Actor actor : List.of(noSave, noQuery, viewer))
            management(
                    HttpMethod.PUT,
                    "/prototype-hub/projects/" + f.project + "/members/" + actor.id,
                    admin,
                    Map.of("role", actor.equals(viewer) ? "viewer" : "editor"),
                    200);
        invitePage(f, noSave.token, 0, 20, 200);
        invitePage(f, noQuery.token, 0, 20, 403);
        invitePage(f, viewer.token, 0, 20, 403);
        management(
                HttpMethod.POST,
                invitePath(f),
                noSave.token,
                Map.of("email", "no-save@example.invalid", "expectedShareVersion", 2),
                403);
        management(
                HttpMethod.POST,
                invitePath(f),
                viewer.token,
                Map.of("email", "viewer@example.invalid", "expectedShareVersion", 2),
                403);
        Actor outsider = actor(List.of("query", "save"));
        invitePage(f, outsider.token, 0, 20, 404);
        management(
                HttpMethod.POST,
                invitePath(f),
                admin,
                Map.of("email", "stale-admin@example.invalid", "expectedShareVersion", 1),
                409);
        sql(
                "update p_share set expires_at=create_time+60000 where id=:id",
                f.share.path("id").asText());
        JsonNode shortened = invite(f, "short@example.invalid", 200);
        assertEquals(
                number(
                        "select expires_at as value from p_share where id=:id",
                        f.share.path("id").asText()),
                shortened.path("invitation").path("expiresAt").asLong());
        sql(
                "update p_share set revision_mode='latest',revision_id=null where id=:id",
                f.share.path("id").asText());
        invite(f, "latest@example.invalid", 409);
        sql(
                "update p_share set revision_mode='fixed',revision_id=:id",
                f.grant.path("revisionId").asText());
        sql("update p_share set review_enabled=false where id=:id", f.share.path("id").asText());
        invite(f, "disabled-review@example.invalid", 409);
    }

    @Test
    void invitationRequiresRecipientFreshOtpAndConsumesAtomicallyOnce() throws Exception {
        Fixture f = fixture(true, false);
        Browser b = context(f, null);
        JsonNode created = invite(f, "invited@example.invalid", 200);
        String raw = created.path("invitationToken").asText(),
                id = created.path("invitation").path("id").asText();
        var wrong = inviteChallenge(f, b, "other@example.invalid", raw, 403);
        assertEquals("PROTOTYPE_HUB_REVIEW_INVITATION_INVALID", wrong.path("code").asText());
        inviteChallenge(f, b, "invited@example.invalid", "x".repeat(43), 403);
        assertEquals(0, count("p_review_challenge"));
        assertEquals(0, MAILBOX.pendingMessages());
        JsonNode challenge = inviteChallenge(f, b, "INVITED@example.invalid", raw, 200);
        var mail = MAILBOX.receive();
        String bad = mail.code().equals("000000") ? "111111" : "000000";
        code(f, b, challenge, bad, 400);
        assertEquals(
                0,
                number(
                        "select count(*) as value from p_review_invitation where id=:id and consumed_at is not null",
                        id));
        Browser verified = code(f, b, challenge, mail.code(), 200);
        assertEquals("email", verified.data.path("session").path("verificationLevel").asText());
        assertEquals(1, count("p_review_guest"));
        assertEquals(1, count("p_review_session"));
        assertEquals(
                "consumed",
                invitePage(f, admin, 0, 20, 200).path("data").get(0).path("status").asText());
        assertEquals(
                1,
                number("select config_version as value from p_review_invitation where id=:id", id));
        code(f, b, challenge, mail.code(), 400);
        inviteChallenge(f, verified, "invited@example.invalid", raw, 403);
        revokeInvitation(f, id, 1, 409);
        assertNotNull(context(f, verified.cookie).guest());
        // An existing verified session still needs a new delivered OTP for another invitation.
        JsonNode other = invite(f, "fresh@example.invalid", 200);
        JsonNode pending =
                inviteChallenge(
                        f,
                        verified,
                        "fresh@example.invalid",
                        other.path("invitationToken").asText(),
                        200);
        var secondMail = MAILBOX.receive();
        assertEquals(1, count("p_review_guest"));
        code(f, verified, pending, secondMail.code(), 200);
        assertEquals(2, count("p_review_guest"));
    }

    @Test
    void invitationRevocationGuestGenerationAndShareChangesNeverReviveOldInvitation()
            throws Exception {
        Fixture f = fixture(true, false);
        JsonNode created = invite(f, "revoke-invite@example.invalid", 200);
        String id = created.path("invitation").path("id").asText();
        revokeInvitation(f, id, 1, 409);
        assertEquals("revoked", revokeInvitation(f, id, 0, 200).path("status").asText());
        revokeInvitation(f, id, 0, 409);
        revokeInvitation(f, id, 1, 200);
        inviteChallenge(
                f,
                context(f, null),
                "revoke-invite@example.invalid",
                created.path("invitationToken").asText(),
                403);
        Browser guest = verify(f, context(f, null), "generation@example.invalid", "Guest", null);
        JsonNode bound = invite(f, "generation@example.invalid", 200);
        guestEnabled(f, guest.guest(), admin, false, 0, 200);
        guestEnabled(f, guest.guest(), admin, true, 1, 200);
        inviteChallenge(
                f,
                context(f, null),
                "generation@example.invalid",
                bound.path("invitationToken").asText(),
                403);
        assertEquals(
                "invalidated",
                invitePage(f, admin, 0, 20, 200).path("data").get(0).path("status").asText());
        revokeInvitation(f, bound.path("invitation").path("id").asText(), 0, 409);
        JsonNode stale = invite(f, "stale@example.invalid", 200);
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of("enabled", true, "expectedVersion", 2),
                200);
        f = refresh(f);
        inviteChallenge(
                f,
                context(f, null),
                "stale@example.invalid",
                stale.path("invitationToken").asText(),
                403);
        assertEquals(
                "invalidated",
                invitePage(f, admin, 0, 20, 200).path("data").get(0).path("status").asText());
    }

    @Test
    void revokedInvitationDuringSmtpCannotBecomeDeliveredAndDoesNotRefundOtpRate()
            throws Exception {
        Fixture f = fixture(true, false);
        Browser b = context(f, null);
        JsonNode created = invite(f, "mail-race@example.invalid", 200);
        MAILBOX.pauseDelivery();
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<JsonNode> result =
                    pool.submit(
                            () ->
                                    inviteChallenge(
                                            f,
                                            b,
                                            "mail-race@example.invalid",
                                            created.path("invitationToken").asText(),
                                            403));
            var mail = MAILBOX.receive();
            String challengeId =
                    database.sql(
                                    "select id from p_review_challenge where email='mail-race@example.invalid'")
                            .map((r, m) -> r.get("id", String.class))
                            .one()
                            .block(TIMEOUT);
            revokeInvitation(f, created.path("invitation").path("id").asText(), 0, 200);
            MAILBOX.deliver();
            result.get(30, TimeUnit.SECONDS);
            var challenge =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                            .objectNode()
                            .put("challengeId", challengeId);
            code(f, b, challenge, mail.code(), 400);
            assertEquals(
                    0,
                    number(
                            "select count(*) as value from p_review_challenge where id=:id and delivered=true",
                            challengeId));
            challenge(f, b, "mail-race@example.invalid", "Guest", null, 429);
            assertEquals(0, count("p_review_guest"));
            assertEquals(0, count("p_review_session"));
        } finally {
            MAILBOX.deliver();
        }
    }

    @Test
    void concurrentInvitationConsumptionAndRollbackCannotPartiallyConsumeIdentity()
            throws Exception {
        Fixture f = fixture(true, false);
        Browser b = context(f, null);
        JsonNode created = invite(f, "atomic@example.invalid", 200);
        JsonNode pending =
                inviteChallenge(
                        f,
                        b,
                        "atomic@example.invalid",
                        created.path("invitationToken").asText(),
                        200);
        String correct = MAILBOX.receive().code();
        sql(
                "create function reject_invitation_session_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture failure'; end $$");
        sql(
                "create trigger reject_invitation_session_fixture before insert on p_review_session for each row execute function reject_invitation_session_fixture()");
        try {
            code(f, b, pending, correct, 500);
            assertEquals(0, count("p_review_guest"));
            assertEquals(0, count("p_review_session"));
            assertEquals(
                    0,
                    number(
                            "select count(*) as value from p_review_invitation where id=:id and consumed_at is not null",
                            created.path("invitation").path("id").asText()));
            assertEquals(
                    0,
                    number(
                            "select count(*) as value from p_review_challenge where id=:id and consumed=true",
                            pending.path("challengeId").asText()));
        } finally {
            sql("drop trigger reject_invitation_session_fixture on p_review_session");
            sql("drop function reject_invitation_session_fixture()");
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Browser>>();
            for (int i = 0; i < 2; i++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return exchange(
                                            f,
                                            "verify",
                                            Map.of(
                                                    "challengeId",
                                                    pending.path("challengeId").asText(),
                                                    "code",
                                                    correct),
                                            b.cookie,
                                            b.csrf(),
                                            ORIGIN,
                                            200,
                                            400,
                                            403);
                                }));
            var statuses = new ArrayList<Integer>();
            for (var task : tasks) statuses.add(task.get(30, TimeUnit.SECONDS).status);
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 400), statuses);
        }
        assertEquals(1, count("p_review_guest"));
        assertEquals(1, count("p_review_session"));
        assertEquals(
                1,
                number(
                        "select config_version as value from p_review_invitation where id=:id",
                        created.path("invitation").path("id").asText()));
    }

    @Test
    void invitationCreationLimitsAreIndependentAndConcurrentProjectCapIsAtomic() throws Exception {
        Fixture f = fixture(true, false);
        invite(f, "quota@example.invalid", 200);
        invite(f, "quota@example.invalid", 429);
        for (int i = 1; i < 5; i++) {
            sql("update p_review_rate set next_allowed_at=0");
            invite(f, "quota@example.invalid", 200);
        }
        sql("update p_review_rate set next_allowed_at=0");
        invite(f, "quota@example.invalid", 429);
        assertEquals(5, count("p_review_invitation"));
        String projectRate =
                com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys.digest(
                        "review-rate-v1:" + f.project + ":invitation-project");
        sql("update p_review_rate set send_count=59 where id=:id", projectRate);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                String email = "parallel-invite-" + i + "@example.invalid";
                tasks.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return client.post()
                                            .uri(invitePath(f))
                                            .header("X-Access-Token", admin)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .bodyValue(
                                                    Map.of(
                                                            "email",
                                                            email,
                                                            "expectedShareVersion",
                                                            2))
                                            .exchange()
                                            .returnResult(JsonNode.class)
                                            .getStatus()
                                            .value();
                                }));
            }
            var statuses = new ArrayList<Integer>();
            for (var task : tasks) statuses.add(task.get(30, TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(List.of(200, 429), statuses);
        }
        assertEquals(
                60,
                number("select send_count as value from p_review_rate where id=:id", projectRate));
        assertEquals(6, count("p_review_invitation"));
        challenge(f, context(f, null), "quota@example.invalid", "Ordinary OTP", null, 200);
        assertEquals(1, MAILBOX.pendingMessages());
    }

    @Test
    void invitationCleanupIsBoundedAndBothVersionAndProjectPurgesIncludeInvitations()
            throws Exception {
        Fixture f = fixture(true, false);
        JsonNode created = invite(f, "cleanup@example.invalid", 200);
        for (int i = 0; i < 55; i++) {
            database.sql(
                            "insert into p_review_invitation(id,project_id,version_id,share_id,revision_id,share_config_version,share_generation,token_hash,email,guest_config_version,config_version,revoked,create_time,expires_at) select :id,project_id,version_id,share_id,revision_id,share_config_version,share_generation,:id,email,guest_config_version,config_version,revoked,create_time,1 from p_review_invitation where id=:source")
                    .bind("id", "expired-invite-" + i)
                    .bind("source", created.path("invitation").path("id").asText())
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        }
        review.cleanupExpired().block(TIMEOUT);
        assertEquals(6, count("p_review_invitation"));
        review.cleanupExpired().block(TIMEOUT);
        assertEquals(1, count("p_review_invitation"));
        review.purgeVersion(f.version.path("id").asText()).block(TIMEOUT);
        assertEquals(0, count("p_review_invitation"));
        invite(f, "purge-project@example.invalid", 200);
        review.purgeProject(f.project).block(TIMEOUT);
        assertEquals(0, count("p_review_invitation"));
    }

    private String invitePath(Fixture f) {
        return "/prototype-hub/shares/" + f.share.path("id").asText() + "/review-invitations";
    }

    @Test
    void pageFeedbackPolicyIsExplicitPreservedAndRestrictedToFixedShares() throws Exception {
        Fixture f = fixture(true, false);
        sql(
                "update p_share set review_allow_comment=null where id=:id",
                f.share.path("id").asText());
        assertFalse(context(f, null).data.path("allowComment").asBoolean());
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of("enabled", false, "allowComment", true, "expectedVersion", 2),
                400);
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of(
                        "enabled",
                        true,
                        "allowComment",
                        true,
                        "allowUnverified",
                        true,
                        "expectedVersion",
                        2),
                200);
        f = refresh(f);
        Browser guest = unverified(f, context(f, null), "客户", "公司", 200);
        assertTrue(guest.data.path("allowComment").asBoolean());
        var input = feedbackFields("首次反馈");
        feedback(f, guest, HttpMethod.POST, "threads", input, 200);
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of("enabled", false, "expectedVersion", 3),
                200);
        JsonNode disabled = management(HttpMethod.GET, manage(f), admin, null, 200);
        assertFalse(disabled.path("allowComment").asBoolean());
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of("enabled", true, "expectedVersion", 4),
                200);
        Fixture renewed = refresh(f);
        assertTrue(
                management(HttpMethod.GET, manage(f), admin, null, 200)
                        .path("allowComment")
                        .asBoolean());
        feedback(renewed, guest, HttpMethod.POST, "threads/_query", Map.of(), 403);
        Browser fresh = unverified(renewed, context(renewed, null), "新客户", null, 200);
        sql(
                "update p_share set revision_mode='latest',revision_id=null where id=:id",
                f.share.path("id").asText());
        feedback(renewed, fresh, HttpMethod.POST, "threads/_query", Map.of(), 403);
    }

    @Test
    void pageFeedbackHappyPathNotesNeverAffectPublicMetadataAndOnlyPmResolves() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = verify(f, context(f, null), "feedback@example.invalid", "客户", "公司");
        JsonNode thread =
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads",
                        feedbackFields("<script>plain</script>\n客户意见"),
                        200);
        String id = thread.path("id").asText();
        assertEquals("email", thread.path("verificationLevel").asText());
        assertEquals("index.html", thread.path("pagePath").asText());
        assertEquals(0, thread.path("configVersion").asLong());
        JsonNode note =
                pmFeedback(
                        f,
                        id + "/messages",
                        HttpMethod.POST,
                        admin,
                        Map.of(
                                "text",
                                "内部机密",
                                "visibility",
                                "internal",
                                "clientRequestId",
                                UUID.randomUUID().toString()),
                        200);
        assertEquals("internal", note.path("visibility").asText());
        assertTrue(note.path("verificationLevel").isNull());
        JsonNode publicThreads =
                feedback(f, guest, HttpMethod.POST, "threads/_query", Map.of(), 200);
        assertEquals(thread, publicThreads.path("data").get(0));
        JsonNode visible =
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + id + "/messages/_query",
                        Map.of(),
                        200);
        assertEquals(1, visible.path("total").asInt());
        assertFalse(visible.toString().contains("内部机密"));
        assertFalse(visible.toString().contains(note.path("id").asText()));
        assertEquals(
                2,
                pmFeedback(f, id + "/messages/_query", HttpMethod.POST, admin, Map.of(), 200)
                        .path("total")
                        .asInt());
        var resolve =
                Map.<String, Object>of(
                        "state",
                        "resolved",
                        "expectedVersion",
                        0,
                        "clientRequestId",
                        UUID.randomUUID().toString());
        feedback(f, guest, HttpMethod.PUT, "threads/" + id + "/status", resolve, 400);
        JsonNode resolved = pmFeedback(f, id + "/status", HttpMethod.PUT, admin, resolve, 200);
        assertEquals(1, resolved.path("configVersion").asInt());
        pmFeedback(
                f,
                id + "/messages",
                HttpMethod.POST,
                admin,
                Map.of(
                        "text",
                        "对客答复",
                        "visibility",
                        "public",
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        assertEquals(
                "resolved",
                feedback(f, guest, HttpMethod.POST, "threads/_query", Map.of(), 200)
                        .path("data")
                        .get(0)
                        .path("state")
                        .asText());
        feedback(
                f,
                guest,
                HttpMethod.POST,
                "threads/" + id + "/messages",
                feedbackFields("继续反馈"),
                200);
        JsonNode reopened =
                feedback(f, guest, HttpMethod.POST, "threads/_query", Map.of(), 200)
                        .path("data")
                        .get(0);
        assertEquals("open", reopened.path("state").asText());
        assertEquals(2, reopened.path("configVersion").asInt());
        assertEquals(thread.path("summary"), reopened.path("summary"));
        pmFeedback(
                f,
                id + "/status",
                HttpMethod.PUT,
                admin,
                Map.of(
                        "state",
                        "resolved",
                        "expectedVersion",
                        1,
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                409);
        pmFeedback(
                f,
                id + "/status",
                HttpMethod.PUT,
                admin,
                Map.of(
                        "state",
                        "resolved",
                        "expectedVersion",
                        2,
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        var reopen =
                Map.<String, Object>of(
                        "state",
                        "open",
                        "expectedVersion",
                        3,
                        "clientRequestId",
                        UUID.randomUUID().toString());
        JsonNode open =
                feedback(f, guest, HttpMethod.PUT, "threads/" + id + "/status", reopen, 200);
        assertEquals(4, open.path("configVersion").asInt());
        long events = count("p_review_event");
        feedback(f, guest, HttpMethod.PUT, "threads/" + id + "/status", reopen, 200);
        feedback(
                f,
                guest,
                HttpMethod.PUT,
                "threads/" + id + "/status",
                Map.of(
                        "state",
                        "open",
                        "expectedVersion",
                        4,
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        assertEquals(events, count("p_review_event"));
        assertFalse(publicThreads.toString().contains("feedback@example.invalid"));
    }

    @Test
    void pageFeedbackDetailsLocateBothIdentityLevelsOutsideListPagesWithoutMutation()
            throws Exception {
        Fixture f = feedbackFixture();
        Browser email = verify(f, context(f, null), "detail@example.invalid", "邮箱作者", "客户公司");
        Browser unverified = unverified(f, context(f, null), "未验证作者", null, 200);
        JsonNode emailThread =
                feedback(f, email, HttpMethod.POST, "threads", feedbackFields("定位旧线程"), 200);
        String id = emailThread.path("id").asText();
        JsonNode newest =
                feedback(f, email, HttpMethod.POST, "threads", feedbackFields("列表新线程"), 200);
        JsonNode lowThread =
                feedback(f, unverified, HttpMethod.POST, "threads", feedbackFields("未验证线程"), 200);
        String note = "仅供内部使用的定位备注";
        pmFeedback(
                f,
                id + "/messages",
                HttpMethod.POST,
                admin,
                Map.of(
                        "text",
                        note,
                        "visibility",
                        "internal",
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        Actor editor = actor(List.of("query"), "prototype-hub-version");
        member(f, editor, "editor");
        Actor owner = actor(List.of("query"), "prototype-hub-version");
        database.sql("update p_project set owner_id=:owner where id=:id")
                .bind("owner", owner.id)
                .bind("id", f.project)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        long events = count("p_review_event");
        long receipts =
                number(
                        "select count(*) as value from p_creation_receipt where revision_id=:id",
                        f.grant.path("revisionId").asText());
        JsonNode firstPage =
                feedback(f, email, HttpMethod.POST, "threads/_query", Map.of("pageSize", 1), 200);
        assertEquals(2, firstPage.path("total").asInt());
        assertEquals(newest.path("id"), firstPage.path("data").get(0).path("id"));
        assertFalse(firstPage.path("data").toString().contains(id));
        assertEquals(
                0,
                feedback(
                                f,
                                email,
                                HttpMethod.POST,
                                "threads/_query",
                                Map.of("state", "resolved"),
                                200)
                        .path("total")
                        .asInt());
        for (var entry : Map.of(email, emailThread, unverified, lowThread).entrySet()) {
            JsonNode expected = entry.getValue();
            String detail = expected.path("id").asText() + "/_detail";
            JsonNode actual =
                    feedback(
                            f, entry.getKey(), HttpMethod.POST, "threads/" + detail, Map.of(), 200);
            assertEquals(expected, actual);
            assertFalse(actual.has("guestId"));
            assertFalse(actual.has("email"));
            assertFalse(actual.has("messages"));
            assertFalse(actual.toString().contains(note));
            for (String token : List.of(admin, owner.token, editor.token))
                assertEquals(
                        expected, pmFeedback(f, detail, HttpMethod.POST, token, Map.of(), 200));
        }
        assertEquals(3, count("p_review_thread"));
        assertEquals(4, count("p_review_message"));
        assertEquals(events, count("p_review_event"));
        assertEquals(
                receipts,
                number(
                        "select count(*) as value from p_creation_receipt where revision_id=:id",
                        f.grant.path("revisionId").asText()));
    }

    @Test
    void pageFeedbackDetailsRetainScopeSecurityAndCurrentLifecycleChecks() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "作者", null, 200);
        Browser other = unverified(f, context(f, null), "作者", null, 200);
        Browser email = verify(f, context(f, null), "foreign-detail@example.invalid", "作者", null);
        String id =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("不能越界定位"), 200)
                        .path("id")
                        .asText();
        String path = "threads/" + id + "/_detail";
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_SESSION_REQUIRED",
                feedback(f, context(f, null), HttpMethod.POST, path, Map.of(), 403)
                        .path("code")
                        .asText());
        for (Browser visitor : List.of(other, email))
            assertEquals(
                    "PROTOTYPE_HUB_REVIEW_THREAD_UNAVAILABLE",
                    feedback(f, visitor, HttpMethod.POST, path, Map.of(), 404)
                            .path("code")
                            .asText());
        feedback(
                f, guest, HttpMethod.POST, "threads/" + "x".repeat(43) + "/_detail", Map.of(), 404);
        feedback(f, guest, HttpMethod.POST, path, Map.of("pageId", "f".repeat(64)), 404);
        feedback(f, guest, HttpMethod.POST, path, Map.of("revisionId", "wrong-revision"), 403);
        feedback(f, guest, HttpMethod.POST, path, Map.of("pageId", ""), 400);
        for (var entry :
                Map.of(
                                "PROTOTYPE_HUB_REVIEW_ORIGIN_DENIED", "origin",
                                "PROTOTYPE_HUB_REVIEW_CSRF_INVALID", "csrf",
                                "PROTOTYPE_HUB_REVIEW_COOKIE_REQUIRED", "cookie")
                        .entrySet()) {
            String boundary = entry.getValue();
            JsonNode denied =
                    exchange(
                                    f,
                                    path,
                                    Map.of("pageId", feedbackPage()),
                                    boundary.equals("cookie") ? null : guest.cookie,
                                    boundary.equals("csrf") ? "wrong" : guest.csrf(),
                                    boundary.equals("origin") ? null : ORIGIN,
                                    403)
                            .data;
            assertEquals(entry.getKey(), denied.path("code").asText());
        }
        JsonNode share =
                management(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + f.version.path("id").asText() + "/shares",
                        admin,
                        Map.of(),
                        200);
        Fixture second = new Fixture(f.project, f.version, share, null);
        management(
                HttpMethod.PUT,
                manage(second),
                admin,
                Map.of(
                        "enabled", true,
                        "allowComment", true,
                        "allowUnverified", true,
                        "expectedVersion", 1),
                200);
        second = refresh(second);
        Browser secondGuest = unverified(second, context(second, null), "作者", null, 200);
        feedback(second, secondGuest, HttpMethod.POST, path, Map.of(), 404);
        Fixture foreign = feedbackFixture();
        Browser foreignGuest = unverified(foreign, context(foreign, null), "作者", null, 200);
        feedback(foreign, foreignGuest, HttpMethod.POST, path, Map.of(), 404);
        pmFeedback(foreign, id + "/_detail", HttpMethod.POST, admin, Map.of(), 404);
        Actor viewer = actor(List.of("query"), "prototype-hub-version");
        Actor noQuery = actor(List.of("save"), "prototype-hub-version");
        Actor outsider = actor(List.of("query"), "prototype-hub-version");
        member(f, viewer, "viewer");
        member(f, noQuery, "editor");
        pmFeedback(f, id + "/_detail", HttpMethod.POST, viewer.token, Map.of(), 403);
        pmFeedback(f, id + "/_detail", HttpMethod.POST, noQuery.token, Map.of(), 403);
        pmFeedback(f, id + "/_detail", HttpMethod.POST, outsider.token, Map.of(), 404);
        management(
                HttpMethod.PUT,
                "/prototype-hub/projects/"
                        + f.project
                        + "/review-guests/"
                        + guest.guest()
                        + "/enabled?verificationLevel=unverified",
                admin,
                Map.of("enabled", false, "expectedVersion", 0),
                200);
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_SESSION_REQUIRED",
                feedback(f, guest, HttpMethod.POST, path, Map.of(), 403).path("code").asText());
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of("enabled", true, "allowComment", false, "expectedVersion", 3),
                200);
        feedback(f, other, HttpMethod.POST, path, Map.of(), 403);
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_COMMENTS_DISABLED",
                feedback(refresh(f), other, HttpMethod.POST, path, Map.of(), 403)
                        .path("code")
                        .asText());
    }

    @Test
    void pageFeedbackScopeSessionAndPermissionIntersectionsAreAuthoritative() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "同名", null, 200);
        String id =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("范围"), 200)
                        .path("id")
                        .asText();
        Browser other = unverified(f, context(f, null), "同名", null, 200);
        feedback(f, other, HttpMethod.POST, "threads/" + id + "/messages/_query", Map.of(), 404);
        assertEquals(
                0,
                feedback(f, other, HttpMethod.POST, "threads/_query", Map.of(), 200)
                        .path("total")
                        .asInt());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_ORIGIN_DENIED",
                exchange(
                                f,
                                "threads/_query",
                                Map.of("pageId", feedbackPage()),
                                guest.cookie,
                                guest.csrf(),
                                null,
                                403)
                        .data
                        .path("code")
                        .asText());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_CSRF_INVALID",
                exchange(
                                f,
                                "threads/_query",
                                Map.of("pageId", feedbackPage()),
                                guest.cookie,
                                "wrong",
                                ORIGIN,
                                403)
                        .data
                        .path("code")
                        .asText());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_COOKIE_REQUIRED",
                exchange(
                                f,
                                "threads/_query",
                                Map.of("pageId", feedbackPage()),
                                null,
                                guest.csrf(),
                                ORIGIN,
                                403)
                        .data
                        .path("code")
                        .asText());
        feedback(
                f,
                guest,
                HttpMethod.POST,
                "threads/_query",
                Map.of("revisionId", "wrong-revision"),
                403);
        Browser email = verify(f, context(f, null), "same-name@example.invalid", "同名", null);
        feedback(f, email, HttpMethod.POST, "threads/" + id + "/messages/_query", Map.of(), 404);
        feedback(
                f, guest, HttpMethod.POST, "threads/_query", Map.of("pageId", "f".repeat(64)), 404);
        feedback(f, guest, HttpMethod.POST, "threads/_query", Map.of("pageSize", 101), 400);
        for (String whitespace : List.of("\u2003\u3000", "\u00a0", "\u2007", "\u202f", "\u0085"))
            feedback(f, guest, HttpMethod.POST, "threads", feedbackFields(whitespace), 400);
        feedback(f, guest, HttpMethod.POST, "threads", Map.of("text", "缺请求键"), 400);
        feedback(f, guest, HttpMethod.POST, "threads/_query", Map.of("state", 1), 400);
        JsonNode secondShare =
                management(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + f.version.path("id").asText() + "/shares",
                        admin,
                        Map.of(),
                        200);
        Fixture second = new Fixture(f.project, f.version, secondShare, null);
        management(
                HttpMethod.PUT,
                manage(second),
                admin,
                Map.of(
                        "enabled",
                        true,
                        "allowComment",
                        true,
                        "allowUnverified",
                        true,
                        "expectedVersion",
                        1),
                200);
        second = refresh(second);
        String emailThread =
                feedback(f, email, HttpMethod.POST, "threads", feedbackFields("邮箱范围"), 200)
                        .path("id")
                        .asText();
        // 跨分享恢复仍遵守OTP的冷却；只推进夹具的时间边界，不放宽生产限频。
        sql("update p_review_rate set next_allowed_at=0");
        Browser secondEmail =
                verify(second, context(second, null), "same-name@example.invalid", "同名", null);
        assertEquals(email.guest(), secondEmail.guest());
        feedback(
                second,
                secondEmail,
                HttpMethod.POST,
                "threads/" + emailThread + "/messages/_query",
                Map.of(),
                404);
        feedback(
                second,
                secondEmail,
                HttpMethod.POST,
                "threads/" + id + "/messages/_query",
                Map.of(),
                404);
        Fixture foreign = feedbackFixture();
        Browser foreignGuest = unverified(foreign, context(foreign, null), "同名", null, 200);
        feedback(
                foreign,
                foreignGuest,
                HttpMethod.POST,
                "threads/" + id + "/messages/_query",
                Map.of(),
                404);
        Actor editor = actor(List.of("query", "save"), "prototype-hub-version");
        Actor viewer = actor(List.of("query", "save"), "prototype-hub-version");
        Actor noSave = actor(List.of("query"), "prototype-hub-version");
        Actor noQuery = actor(List.of("save"), "prototype-hub-version");
        for (Actor actor : List.of(editor, noSave, noQuery)) member(f, actor, "editor");
        member(f, viewer, "viewer");
        pmFeedback(f, "_query", HttpMethod.POST, editor.token, Map.of(), 200);
        Actor owner = actor(List.of("query", "save"), "prototype-hub-version");
        database.sql("update p_project set owner_id=:owner where id=:id")
                .bind("owner", owner.id)
                .bind("id", f.project)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        pmFeedback(f, "_query", HttpMethod.POST, owner.token, Map.of(), 200);
        pmFeedback(f, "_query", HttpMethod.POST, admin, Map.of(), 200);

        pmFeedback(f, "_query", HttpMethod.POST, viewer.token, Map.of(), 403);
        pmFeedback(f, "_query", HttpMethod.POST, noQuery.token, Map.of(), 403);
        pmFeedback(
                f,
                id + "/messages",
                HttpMethod.POST,
                noSave.token,
                Map.of(
                        "text",
                        "禁止",
                        "visibility",
                        "internal",
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                403);
        Actor outsider = actor(List.of("query", "save"), "prototype-hub-version");
        pmFeedback(f, "_query", HttpMethod.POST, outsider.token, Map.of(), 404);
        pmFeedback(
                f,
                id + "/messages",
                HttpMethod.POST,
                editor.token,
                Map.of(
                        "text",
                        "错误类型",
                        "visibility",
                        0,
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                400);
        // 公开DTO不包含visibility，无法把公开消息变成内部备注。
        JsonNode forced =
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + id + "/messages",
                        Map.of(
                                "text",
                                "始终公开",
                                "visibility",
                                "internal",
                                "clientRequestId",
                                UUID.randomUUID().toString()),
                        200);
        assertEquals("public", forced.path("visibility").asText());
        sql(
                "update p_review_session set verification_level='unknown' where guest_id=:id",
                guest.guest());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_SESSION_REQUIRED",
                feedback(f, guest, HttpMethod.POST, "threads/_query", Map.of(), 403)
                        .path("code")
                        .asText());
    }

    @Test
    void pageFeedbackIdempotencyConcurrencyAndRollbackAreAtomic() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        var fields = feedbackFields("只创建一次");
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<JsonNode>> responses = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                responses.add(
                        pool.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    return feedback(
                                            f, guest, HttpMethod.POST, "threads", fields, 200);
                                }));
            assertEquals(
                    responses.get(0).get(20, TimeUnit.SECONDS).path("id"),
                    responses.get(1).get(20, TimeUnit.SECONDS).path("id"));
        }
        assertEquals(1, count("p_review_thread"));
        assertEquals(1, count("p_review_message"));
        assertEquals(1, count("p_review_event"));
        var changed = new HashMap<String, Object>(fields);
        changed.put("text", "不同内容");
        feedback(f, guest, HttpMethod.POST, "threads", changed, 409);
        String id = feedback(f, guest, HttpMethod.POST, "threads", fields, 200).path("id").asText();
        var reply = feedbackFields("一次回复");
        JsonNode one =
                feedback(f, guest, HttpMethod.POST, "threads/" + id + "/messages", reply, 200);
        assertEquals(
                one,
                feedback(f, guest, HttpMethod.POST, "threads/" + id + "/messages", reply, 200));
        assertEquals(2, count("p_review_message"));
        assertEquals(2, count("p_review_event"));
        long receipts =
                number(
                        "select count(*) as value from p_creation_receipt where revision_id=:id",
                        f.grant.path("revisionId").asText());
        sql(
                "create function reject_feedback_event_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture event failure'; end $$");
        sql(
                "create trigger reject_feedback_event_fixture before insert on p_review_event for each row execute function reject_feedback_event_fixture()");
        try {
            feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("必须回滚"), 500);
        } finally {
            sql("drop trigger reject_feedback_event_fixture on p_review_event");
            sql("drop function reject_feedback_event_fixture()");
        }
        assertEquals(1, count("p_review_thread"));
        assertEquals(2, count("p_review_message"));
        assertEquals(2, count("p_review_event"));
        assertEquals(
                receipts,
                number(
                        "select count(*) as value from p_creation_receipt where revision_id=:id",
                        f.grant.path("revisionId").asText()));
    }

    @Test
    void pageFeedbackRatesAreIndependentReplayPrecedesLimitAndPmHasNoQuota() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        var fields = feedbackFields("限频前创建");
        JsonNode thread = feedback(f, guest, HttpMethod.POST, "threads", fields, 200);
        String id = thread.path("id").asText();
        String guestRate =
                com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys.digest(
                        "review-rate-v1:"
                                + f.project
                                + ":comment-guest:unverified:"
                                + guest.guest());
        sql("update p_review_rate set send_count=60 where id=:id", guestRate);
        assertEquals(thread, feedback(f, guest, HttpMethod.POST, "threads", fields, 200));
        feedback(
                f,
                guest,
                HttpMethod.POST,
                "threads/" + id + "/messages",
                feedbackFields("超额"),
                429);
        String projectRate =
                com.gaiys.proto.prototypehub.service.keys.PrototypeReviewKeys.digest(
                        "review-rate-v1:" + f.project + ":comment-project");
        sql("update p_review_rate set send_count=300 where id=:id", projectRate);
        Browser other = unverified(f, context(f, null), "其他", null, 200);
        feedback(f, other, HttpMethod.POST, "threads", feedbackFields("项目超额"), 429);
        pmFeedback(
                f,
                id + "/messages",
                HttpMethod.POST,
                admin,
                Map.of(
                        "text",
                        "PM不占公开额度",
                        "visibility",
                        "public",
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        assertEquals(1, count("p_review_thread"));
    }

    @Test
    void pageFeedbackExpiredGuestDisabledPolicyAndArchiveDoNotLeakOrEraseHistory()
            throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String id =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("保留历史"), 200)
                        .path("id")
                        .asText();
        Browser anonymous = context(f, null);
        JsonNode denied = feedback(f, anonymous, HttpMethod.POST, "threads/_query", Map.of(), 403);
        assertEquals("PROTOTYPE_HUB_REVIEW_SESSION_REQUIRED", denied.path("code").asText());
        sql("update p_review_session set expires_at=1 where guest_id=:id", guest.guest());
        feedback(f, guest, HttpMethod.POST, "threads/" + id + "/messages/_query", Map.of(), 403);
        sql("update p_review_unverified_guest set expires_at=1 where id=:id", guest.guest());
        review.cleanupExpired().block(TIMEOUT);
        Browser replacement = unverified(f, context(f, null), "客户", null, 200);
        feedback(
                f,
                replacement,
                HttpMethod.POST,
                "threads/" + id + "/messages/_query",
                Map.of(),
                404);
        assertEquals(1, count("p_review_thread"));
        assertEquals(1, count("p_review_message"));
        Browser email = verify(f, context(f, null), "history@example.invalid", "客户", null);
        String emailThread =
                feedback(f, email, HttpMethod.POST, "threads", feedbackFields("邮箱历史"), 200)
                        .path("id")
                        .asText();
        guestEnabled(f, email.guest(), admin, false, 0, 200);
        feedback(
                f,
                email,
                HttpMethod.POST,
                "threads/" + emailThread + "/messages/_query",
                Map.of(),
                403);
        sql("update p_share set enabled=false where id=:id", f.share.path("id").asText());
        assertEquals(
                2,
                pmFeedback(f, "_query", HttpMethod.POST, admin, Map.of(), 200)
                        .path("total")
                        .asInt());
        sql("update p_version set deleted_time=1 where id=:id", f.version.path("id").asText());
        pmFeedback(f, "_query", HttpMethod.POST, admin, Map.of(), 404);
    }

    @Test
    void pageFeedbackEventRetentionIsBoundedAndHardPurgeIncludesReceipts() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String id =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("保留正文"), 200)
                        .path("id")
                        .asText();
        pmFeedback(
                f,
                id + "/messages",
                HttpMethod.POST,
                admin,
                Map.of(
                        "text",
                        "内部记录",
                        "visibility",
                        "internal",
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        sql("update p_review_event set create_time=0");
        for (int i = 0; i < 53; i++)
            database.sql(
                            "insert into p_review_event(id,project_id,version_id,revision_id,thread_id,message_id,event_type,actor_type,create_time) select :id,project_id,version_id,revision_id,thread_id,message_id,event_type,actor_type,0 from p_review_event limit 1")
                    .bind("id", "old-feedback-event-" + i)
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        reviewThreads.cleanupEvents().block(TIMEOUT);
        assertEquals(5, count("p_review_event"));
        reviewThreads.cleanupEvents().block(TIMEOUT);
        assertEquals(0, count("p_review_event"));
        assertEquals(2, count("p_review_message"));
        management(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version.path("id").asText(),
                admin,
                null,
                200);
        database.sql("update p_version set deleted_time=:expired where id=:id")
                .bind("expired", System.currentTimeMillis() - Duration.ofDays(31).toMillis())
                .bind("id", f.version.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeVersion(f.version.path("id").asText()).block(TIMEOUT));
        assertEquals(0, count("p_review_thread"));
        assertEquals(0, count("p_review_message"));
        assertEquals(
                0,
                number(
                        "select count(*) as value from p_creation_receipt where revision_id=:id",
                        f.grant.path("revisionId").asText()));
        Fixture orphan = feedbackFixture();
        Browser orphanGuest = unverified(orphan, context(orphan, null), "客户", null, 200);
        feedback(orphan, orphanGuest, HttpMethod.POST, "threads", feedbackFields("残留资源项目清理"), 200);
        // 项目清理也必须覆盖已缺少版本父行的历史评审图。
        sql("delete from p_version where id=:id", orphan.version.path("id").asText());
        management(
                HttpMethod.DELETE, "/prototype-hub/projects/" + orphan.project, admin, null, 200);
        database.sql("update p_project set deleted_time=:expired where id=:id")
                .bind("expired", System.currentTimeMillis() - Duration.ofDays(31).toMillis())
                .bind("id", orphan.project)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeProject(orphan.project).block(TIMEOUT));
        assertEquals(0, count("p_review_thread"));
        assertEquals(0, count("p_review_message"));
        assertEquals(0, count("p_review_event"));
        assertEquals(
                0,
                number(
                        "select count(*) as value from p_creation_receipt where revision_id=:id",
                        orphan.grant.path("revisionId").asText()));
    }

    @Test
    void reviewOverviewPagesFixedRevisionAcrossPagesAndIgnoresPrivateActivity() throws Exception {
        List<String> pages = List.of("index.html", "details.html", "中文/结果.html");
        Fixture f = feedbackFixture(pages);
        Browser guest = unverified(f, context(f, null), "跨页客户", null, 200);
        var created = new ArrayList<JsonNode>();
        for (int i = 0; i < 22; i++) {
            var fields = new HashMap<>(feedbackFields("反馈" + i));
            fields.put("pageId", reviewPage(pages.get(i % pages.size())));
            created.add(feedback(f, guest, HttpMethod.POST, "threads", fields, 200));
        }
        var first = created.getFirst();
        pmFeedback(
                f,
                first.path("id").asText() + "/status",
                HttpMethod.PUT,
                admin,
                Map.of(
                        "pageId",
                        first.path("pageId").asText(),
                        "state",
                        "resolved",
                        "expectedVersion",
                        0,
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        // 相同更新时间用真实ID排序断言分页稳定性，不依赖处理器或数据库时钟精度。
        sql("update p_review_thread set public_updated_at=1000");
        var expected = created.stream().map(row -> row.path("id").asText()).sorted().toList();
        JsonNode one = overview(f, guest, Map.of(), 200);
        assertEquals(22, one.path("total").asInt());
        assertEquals(0, one.path("pageIndex").asInt());
        assertEquals(20, one.path("pageSize").asInt());
        assertEquals(20, one.path("data").size());
        JsonNode two = overview(f, guest, Map.of("pageIndex", 1), 200);
        assertEquals(22, two.path("total").asInt());
        assertEquals(2, two.path("data").size());
        var actual = new ArrayList<String>();
        one.path("data").forEach(row -> actual.add(row.path("id").asText()));
        two.path("data").forEach(row -> actual.add(row.path("id").asText()));
        assertEquals(expected, actual);
        JsonNode beyond = overview(f, guest, Map.of("pageIndex", 99), 200);
        assertEquals(99, beyond.path("pageIndex").asInt());
        assertEquals(22, beyond.path("total").asInt());
        assertTrue(beyond.path("data").isEmpty());
        assertEquals(1, overview(f, guest, Map.of("state", "resolved"), 200).path("total").asInt());
        assertEquals(21, overview(f, guest, Map.of("state", "open"), 200).path("total").asInt());
        assertEquals(one, pmFeedback(f, "_overview", HttpMethod.POST, admin, Map.of(), 200));
        Set<String> returnedPages = new HashSet<>();
        for (JsonNode row : one.path("data")) {
            returnedPages.add(row.path("pagePath").asText());
            assertEquals(reviewPage(row.path("pagePath").asText()), row.path("pageId").asText());
            assertFalse(row.path("pageName").asText().isBlank());
            for (String field :
                    List.of(
                            "guestId",
                            "email",
                            "shareId",
                            "messages",
                            "assigneeId",
                            "assigneeName",
                            "assignmentVersion",
                            "assignmentUpdatedAt")) assertFalse(row.has(field));
        }
        assertEquals(Set.copyOf(pages), returnedPages);
        Actor editor = actor(List.of("query", "save"), "prototype-hub-version");
        member(f, editor, "editor");
        pmFeedback(
                f,
                first.path("id").asText() + "/messages",
                HttpMethod.POST,
                editor.token,
                Map.of(
                        "pageId",
                        first.path("pageId").asText(),
                        "text",
                        "仅内部可见的总览备注",
                        "visibility",
                        "internal",
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        pmFeedback(
                f,
                first.path("id").asText() + "/assignment",
                HttpMethod.PUT,
                editor.token,
                Map.of(
                        "pageId",
                        first.path("pageId").asText(),
                        "assigneeId",
                        editor.id,
                        "expectedVersion",
                        0,
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        long events = count("p_review_event");
        long receipts = count("p_creation_receipt");
        assertEquals(one, overview(f, guest, Map.of(), 200));
        assertEquals(two, overview(f, guest, Map.of("pageIndex", 1), 200));
        assertEquals(one, pmFeedback(f, "_overview", HttpMethod.POST, editor.token, Map.of(), 200));
        assertFalse(one.toString().contains("仅内部可见的总览备注"));
        assertEquals(events, count("p_review_event"));
        assertEquals(receipts, count("p_creation_receipt"));
        for (JsonNode thread : created.subList(0, 3)) {
            Map<String, Object> page = Map.of("pageId", thread.path("pageId").asText());
            JsonNode detail =
                    feedback(
                            f,
                            guest,
                            HttpMethod.POST,
                            "threads/" + thread.path("id").asText() + "/_detail",
                            page,
                            200);
            assertEquals(1000, detail.path("updatedAt").asLong());
            assertEquals(
                    thread.equals(created.getFirst()) ? 8 : 7,
                    feedback(f, guest, HttpMethod.POST, "threads/_query", page, 200)
                            .path("total")
                            .asInt());
        }
        // 公开回复使对应线程上浮，内部操作仍不影响其他行的稳定次序。
        JsonNode target = created.getLast();
        feedback(
                f,
                guest,
                HttpMethod.POST,
                "threads/" + target.path("id").asText() + "/messages",
                Map.of(
                        "pageId",
                        target.path("pageId").asText(),
                        "text",
                        "公开更新",
                        "clientRequestId",
                        UUID.randomUUID().toString()),
                200);
        assertEquals(
                target.path("id"),
                overview(f, guest, Map.of("pageSize", 1), 200).path("data").get(0).path("id"));
    }

    @Test
    void reviewOverviewKeepsGuestLevelShareRevisionAndMemberPermissionScopes() throws Exception {
        Fixture f = feedbackFixture();
        Browser email = verify(f, context(f, null), "overview@example.invalid", "同名", null);
        Browser low = unverified(f, context(f, null), "同名", null, 200);
        Browser other = unverified(f, context(f, null), "同名", null, 200);
        JsonNode emailThread =
                feedback(f, email, HttpMethod.POST, "threads", feedbackFields("邮箱专属"), 200);
        JsonNode lowThread =
                feedback(f, low, HttpMethod.POST, "threads", feedbackFields("未验证专属"), 200);
        feedback(f, other, HttpMethod.POST, "threads", feedbackFields("另一访客"), 200);
        assertEquals(emailThread, overview(f, email, Map.of(), 200).path("data").get(0));
        assertEquals(lowThread, overview(f, low, Map.of(), 200).path("data").get(0));
        assertEquals(1, overview(f, other, Map.of(), 200).path("total").asInt());
        JsonNode share =
                management(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + f.version.path("id").asText() + "/shares",
                        admin,
                        Map.of(),
                        200);
        Fixture second = new Fixture(f.project, f.version, share, null);
        management(
                HttpMethod.PUT,
                manage(second),
                admin,
                Map.of("enabled", true, "allowComment", true, "expectedVersion", 1),
                200);
        second = refresh(second);
        sql("update p_review_rate set next_allowed_at=0");
        Browser secondEmail =
                verify(second, context(second, null), "overview@example.invalid", "同名", null);
        assertEquals(email.guest(), secondEmail.guest());
        assertEquals(0, overview(second, secondEmail, Map.of(), 200).path("total").asInt());
        JsonNode secondThread =
                feedback(
                        second,
                        secondEmail,
                        HttpMethod.POST,
                        "threads",
                        feedbackFields("同邮箱另一分享"),
                        200);
        assertEquals(
                secondThread, overview(second, secondEmail, Map.of(), 200).path("data").get(0));
        assertEquals(1, overview(f, email, Map.of(), 200).path("total").asInt());
        assertEquals(
                4,
                pmFeedback(f, "_overview", HttpMethod.POST, admin, Map.of(), 200)
                        .path("total")
                        .asInt());
        Fixture foreign = feedbackFixture();
        Browser foreignGuest = unverified(foreign, context(foreign, null), "同名", null, 200);
        feedback(foreign, foreignGuest, HttpMethod.POST, "threads", feedbackFields("跨项目"), 200);
        assertEquals(1, overview(foreign, foreignGuest, Map.of(), 200).path("total").asInt());
        pmFeedback(
                f,
                "_overview",
                HttpMethod.POST,
                admin,
                Map.of("revisionId", foreign.grant.path("revisionId").asText()),
                404);
        pmFeedback(
                foreign,
                "_overview",
                HttpMethod.POST,
                admin,
                Map.of("revisionId", f.grant.path("revisionId").asText()),
                404);
        feedback(
                f,
                email,
                HttpMethod.POST,
                "threads/_overview",
                Map.of("revisionId", foreign.grant.path("revisionId").asText()),
                403);
        Actor editor = actor(List.of("query"), "prototype-hub-version");
        Actor viewer = actor(List.of("query"), "prototype-hub-version");
        Actor noQuery = actor(List.of("save"), "prototype-hub-version");
        Actor outsider = actor(List.of("query"), "prototype-hub-version");
        member(f, editor, "editor");
        member(f, viewer, "viewer");
        member(f, noQuery, "editor");
        assertEquals(
                4,
                pmFeedback(f, "_overview", HttpMethod.POST, editor.token, Map.of(), 200)
                        .path("total")
                        .asInt());
        pmFeedback(f, "_overview", HttpMethod.POST, viewer.token, Map.of(), 403);
        pmFeedback(f, "_overview", HttpMethod.POST, noQuery.token, Map.of(), 403);
        pmFeedback(f, "_overview", HttpMethod.POST, outsider.token, Map.of(), 404);
        management(
                HttpMethod.DELETE,
                "/prototype-hub/projects/" + f.project + "/members/" + editor.id,
                admin,
                null,
                200);
        pmFeedback(f, "_overview", HttpMethod.POST, editor.token, Map.of(), 404);
    }

    @Test
    void reviewOverviewValidatesRequestsAndCurrentLifecycleWithoutWeakeningCsrf() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("保留反馈"), 200);
        for (Map<String, Object> invalid :
                List.of(
                        Map.<String, Object>of("pageIndex", -1),
                        Map.<String, Object>of("pageIndex", 10001),
                        Map.<String, Object>of("pageSize", 0),
                        Map.<String, Object>of("pageSize", 101),
                        Map.<String, Object>of("state", ""),
                        Map.<String, Object>of("state", "other"),
                        Map.<String, Object>of("revisionId", ""),
                        Map.<String, Object>of("revisionId", "x".repeat(129)),
                        Map.<String, Object>of("accessKey", "")))
            feedback(f, guest, HttpMethod.POST, "threads/_overview", invalid, 400);
        pmFeedback(f, "_overview", HttpMethod.POST, admin, Map.of("revisionId", "absent"), 404);
        assertEquals(
                1,
                management(
                                HttpMethod.POST,
                                "/prototype-hub/versions/"
                                        + f.version.path("id").asText()
                                        + "/review-threads/_overview",
                                admin,
                                Map.of("revisionId", f.grant.path("revisionId").asText()),
                                200)
                        .path("total")
                        .asInt());
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_SESSION_REQUIRED",
                overview(f, context(f, null), Map.of(), 403).path("code").asText());
        for (var entry :
                Map.of(
                                "PROTOTYPE_HUB_REVIEW_ORIGIN_DENIED",
                                "origin",
                                "PROTOTYPE_HUB_REVIEW_CSRF_INVALID",
                                "csrf",
                                "PROTOTYPE_HUB_REVIEW_COOKIE_REQUIRED",
                                "cookie")
                        .entrySet()) {
            String boundary = entry.getValue();
            JsonNode denied =
                    exchange(
                                    f,
                                    "threads/_overview",
                                    Map.of(),
                                    boundary.equals("cookie") ? null : guest.cookie,
                                    boundary.equals("csrf") ? "wrong" : guest.csrf(),
                                    boundary.equals("origin") ? null : ORIGIN,
                                    403)
                            .data;
            assertEquals(entry.getKey(), denied.path("code").asText());
        }
        long expiry =
                number(
                        "select expires_at as value from p_review_session where guest_id=:id",
                        guest.guest());
        sql("update p_review_session set expires_at=1 where guest_id=:id", guest.guest());
        overview(f, guest, Map.of(), 403);
        database.sql("update p_review_session set expires_at=:expiry where guest_id=:id")
                .bind("expiry", expiry)
                .bind("id", guest.guest())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        sql("update p_review_unverified_guest set enabled=false where id=:id", guest.guest());
        overview(f, guest, Map.of(), 403);
        sql("update p_review_unverified_guest set enabled=true where id=:id", guest.guest());
        String shareId = f.share.path("id").asText();
        sql("update p_share set enabled=false where id=:id", shareId);
        overview(f, guest, Map.of(), 404);
        sql("update p_share set enabled=true where id=:id", shareId);
        sql("update p_share set expires_at=1 where id=:id", shareId);
        overview(f, guest, Map.of(), 404);
        sql("update p_share set expires_at=null where id=:id", shareId);
        sql("update p_project set deleted_time=1 where id=:id", f.project);
        overview(f, guest, Map.of(), 404);
        pmFeedback(f, "_overview", HttpMethod.POST, admin, Map.of(), 404);
        sql("update p_project set deleted_time=null where id=:id", f.project);
        sql("update p_version set deleted_time=1 where id=:id", f.version.path("id").asText());
        overview(f, guest, Map.of(), 404);
        pmFeedback(f, "_overview", HttpMethod.POST, admin, Map.of(), 404);
        sql("update p_version set deleted_time=null where id=:id", f.version.path("id").asText());
        long version =
                management(HttpMethod.GET, manage(f), admin, null, 200)
                        .path("configVersion")
                        .asLong();
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of("enabled", true, "allowComment", false, "expectedVersion", version),
                200);
        overview(f, guest, Map.of(), 403);
        Fixture renewed = refresh(f);
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_COMMENTS_DISABLED",
                overview(renewed, guest, Map.of(), 403).path("code").asText());
        JsonNode policy = management(HttpMethod.GET, manage(f), admin, null, 200);
        management(
                HttpMethod.POST,
                "/prototype-hub/shares/" + shareId + "/revoke",
                admin,
                Map.of("expectedVersion", policy.path("configVersion").asLong()),
                200);
        overview(renewed, guest, Map.of(), 404);
        assertEquals(1, count("p_review_thread"));
        assertEquals(
                1,
                pmFeedback(f, "_overview", HttpMethod.POST, admin, Map.of(), 200)
                        .path("total")
                        .asInt());
    }

    private JsonNode overview(Fixture f, Browser guest, Map<String, Object> fields, int status) {
        return exchange(f, "threads/_overview", fields, guest.cookie, guest.csrf(), ORIGIN, status)
                .data;
    }

    private String reviewPage(String path) {
        return com.gaiys.proto.prototypehub.web.info.PrototypePageInfo.page(path, path).pageId();
    }

    @Test
    void rootAnchorsRoundTripTypedJsonForEveryShapeAndRemainCreateOnly() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "锚点客户", null, 200);
        for (String shape : List.of("point", "rect", "ellipse")) {
            var fields = new HashMap<>(feedbackFields("位置快照"));
            fields.put("anchor", rootAnchor(shape));
            JsonNode created = feedback(f, guest, HttpMethod.POST, "threads", fields, 200);
            String id = created.path("id").asText();
            JsonNode loaded =
                    feedback(
                            f, guest, HttpMethod.POST, "threads/" + id + "/_detail", Map.of(), 200);
            assertEquals(created, loaded);
            assertEquals(shape, loaded.path("anchor").path("shape").asText());
            assertEquals(10, loaded.path("anchor").size());
            assertEquals(
                    created.path("anchor"),
                    pmFeedback(f, id + "/_detail", HttpMethod.POST, admin, Map.of(), 200)
                            .path("anchor"));
            assertEquals(created, feedback(f, guest, HttpMethod.POST, "threads", fields, 200));
            var reply = new HashMap<>(feedbackFields("普通回复不能改变位置"));
            reply.put("anchor", rootAnchor("point"));
            feedback(f, guest, HttpMethod.POST, "threads/" + id + "/messages", reply, 200);
            assertEquals(
                    created.path("anchor"),
                    feedback(f, guest, HttpMethod.POST, "threads/" + id + "/_detail", Map.of(), 200)
                            .path("anchor"));
            Browser stranger = unverified(f, context(f, null), "其他人", null, 200);
            feedback(f, stranger, HttpMethod.POST, "threads/" + id + "/_detail", Map.of(), 404);
        }
        JsonNode query = feedback(f, guest, HttpMethod.POST, "threads/_query", Map.of(), 200);
        assertEquals(3, query.path("total").asInt());
        assertTrue(query.path("data").get(0).path("anchor").isObject());
        var old = new HashMap<>(feedbackFields("无锚旧语义"));
        JsonNode plain = feedback(f, guest, HttpMethod.POST, "threads", old, 200);
        assertTrue(plain.path("anchor").isNull());
        old.put("anchor", null);
        assertEquals(plain, feedback(f, guest, HttpMethod.POST, "threads", old, 200));
        old.put("anchor", rootAnchor("point"));
        feedback(f, guest, HttpMethod.POST, "threads", old, 409);
    }

    @Test
    void rootAnchorHttpRejectsCoercionShapeAndBoundsBeforeCreatingAnyGraph() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        for (String field :
                List.of(
                        "x",
                        "y",
                        "width",
                        "height",
                        "documentWidth",
                        "documentHeight",
                        "viewportWidth",
                        "viewportHeight")) {
            for (Object invalid : List.of("1", 1.0, -1)) {
                var anchor = rootAnchor("rect");
                anchor.put(field, invalid);
                var values = new HashMap<>(feedbackFields("无效几何"));
                values.put("anchor", anchor);
                feedback(f, guest, HttpMethod.POST, "threads", values, 400);
            }
        }
        for (Map<String, Object> invalid :
                List.of(
                        Map.<String, Object>of("shape", "triangle"),
                        Map.<String, Object>of("space", "viewport"),
                        Map.<String, Object>of("shape", "point"),
                        Map.<String, Object>of("width", 0),
                        Map.<String, Object>of("height", 0),
                        Map.<String, Object>of("x", 1000),
                        Map.<String, Object>of("documentWidth", 1_000_001),
                        Map.<String, Object>of("viewportWidth", 16_385),
                        Map.<String, Object>of("selector", "not-persisted"))) {
            var anchor = rootAnchor("rect");
            anchor.putAll(invalid);
            var values = new HashMap<>(feedbackFields("无效几何"));
            values.put("anchor", anchor);
            imageCreate(f, guest, values, replyImage("png", 3), 400);
        }
        assertEquals(0, count("p_review_thread"));
        assertEquals(0, count("p_review_message"));
        assertEquals(0, count("p_creation_receipt"));
        assertEquals(0, count("p_review_event"));
        assertEquals(0, imageFileCount());
    }

    @Test
    void rootAnchorImageConcurrencyFingerprintAndEventRollbackStayAtomic() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        var values = new HashMap<>(feedbackFields("带区域图片"));
        values.put("anchor", rootAnchor("ellipse"));
        byte[] png = replyImage("png", 3);
        JsonNode first;
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return imageCreate(f, guest, values, png, 200);
                            });
            var two =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return imageCreate(f, guest, values, png, 200);
                            });
            first = one.get(20, TimeUnit.SECONDS);
            assertEquals(first, two.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, count("p_review_thread"));
        assertEquals(1, count("p_review_message"));
        assertEquals(1, count("p_review_event"));
        assertEquals(1, count("p_creation_receipt"));
        assertEquals(1, imageFileCount());
        long rates =
                number(
                        "select sum(send_count) as value from p_review_rate where project_id=:id",
                        f.project);
        var reordered = new HashMap<>(values);
        var reversed = new java.util.LinkedHashMap<String, Object>();
        var entries = new ArrayList<>(rootAnchor("ellipse").entrySet());
        java.util.Collections.reverse(entries);
        entries.forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        reordered.put("anchor", reversed);
        assertEquals(first, imageCreate(f, guest, reordered, png, 200));
        assertEquals(
                rates,
                number(
                        "select sum(send_count) as value from p_review_rate where project_id=:id",
                        f.project));
        for (Object changed : java.util.Arrays.asList(null, rootAnchor("rect"))) {
            var different = new HashMap<>(values);
            different.put("anchor", changed);
            imageCreate(f, guest, different, png, 409);
        }
        imageCreate(f, guest, values, replyImage("png", 4), 409);
        var changedText = new HashMap<>(values);
        changedText.put("text", "不同正文");
        imageCreate(f, guest, changedText, png, 409);
        assertEquals(1, imageFileCount());
        var rollback = new HashMap<>(values);
        rollback.put("clientRequestId", UUID.randomUUID().toString());
        sql(
                "create function reject_anchor_event_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture event failure'; end $$");
        sql(
                "create trigger reject_anchor_event_fixture before insert on p_review_event for each row execute function reject_anchor_event_fixture()");
        try {
            imageCreate(f, guest, rollback, png, 500);
        } finally {
            sql("drop trigger reject_anchor_event_fixture on p_review_event");
            sql("drop function reject_anchor_event_fixture()");
        }
        assertEquals(1, count("p_review_thread"));
        assertEquals(1, count("p_review_message"));
        assertEquals(1, count("p_review_event"));
        assertEquals(1, count("p_creation_receipt"));
        assertEquals(1, imageFileCount());
        assertEquals(
                rates,
                number(
                        "select sum(send_count) as value from p_review_rate where project_id=:id",
                        f.project));
        JsonNode retry = imageCreate(f, guest, rollback, png, 200);
        assertEquals(
                first.path("anchor"),
                feedback(
                                f,
                                guest,
                                HttpMethod.POST,
                                "threads/" + retry.path("id").asText() + "/_detail",
                                Map.of(),
                                200)
                        .path("anchor"));
        JsonNode message =
                feedback(
                                f,
                                guest,
                                HttpMethod.POST,
                                "threads/" + first.path("id").asText() + "/messages/_query",
                                Map.of(),
                                200)
                        .path("data")
                        .get(0);
        assertArrayEquals(
                png,
                imageRead(
                        f,
                        guest,
                        null,
                        first.path("id").asText(),
                        message.path("id").asText(),
                        200));
    }

    private HashMap<String, Object> rootAnchor(String shape) {
        return new HashMap<>(
                Map.of(
                        "space",
                        "root-document-css-px-v1",
                        "shape",
                        shape,
                        "x",
                        10,
                        "y",
                        20,
                        "width",
                        shape.equals("point") ? 0 : 30,
                        "height",
                        shape.equals("point") ? 0 : 40,
                        "documentWidth",
                        1000,
                        "documentHeight",
                        2000,
                        "viewportWidth",
                        800,
                        "viewportHeight",
                        600));
    }

    @Test
    void initialImageThreadCreatesOneGraphAndKeepsOriginalJsonAndControlledReads()
            throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "初图客户", "示例公司", 200);
        byte[] png = replyImage("png", 3);
        JsonNode thread = imageCreate(f, guest, feedbackFields("初次提交附图"), png, 200);
        String id = thread.path("id").asText();
        assertEquals("open", thread.path("state").asText());
        assertEquals(0, thread.path("configVersion").asLong());
        assertEquals("初图客户", thread.path("authorName").asText());
        assertEquals("unverified", thread.path("verificationLevel").asText());
        assertFalse(thread.has("image"));
        assertEquals(1, count("p_review_thread"));
        assertEquals(1, count("p_review_message"));
        assertEquals(1, count("p_review_event"));
        assertEquals(1, count("p_creation_receipt"));
        assertEquals(1, imageFileCount());
        JsonNode messages =
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + id + "/messages/_query",
                        Map.of(),
                        200);
        assertEquals(1, messages.path("total").asInt());
        JsonNode first = messages.path("data").get(0);
        assertEquals("public", first.path("visibility").asText());
        assertEquals("guest", first.path("authorType").asText());
        assertEquals("初次提交附图", first.path("text").asText());
        assertEquals(4, first.path("image").size());
        assertEquals("image/png", first.path("image").path("contentType").asText());
        assertEquals(png.length, first.path("image").path("size").asInt());
        assertEquals(3, first.path("image").path("width").asInt());
        assertArrayEquals(png, imageRead(f, guest, null, id, first.path("id").asText(), 200));
        assertArrayEquals(png, imageRead(f, null, admin, id, first.path("id").asText(), 200));
        for (String field : List.of("imagePath", "imageSha256", "url", "filename"))
            assertFalse(first.has(field));
        Browser stranger = unverified(f, context(f, null), "另一客户", null, 200);
        imageRead(f, stranger, null, id, first.path("id").asText(), 404);
        JsonNode plain =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("原JSON仍可用"), 200);
        JsonNode plainMessages =
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + plain.path("id").asText() + "/messages/_query",
                        Map.of(),
                        200);
        assertTrue(plainMessages.path("data").get(0).path("image").isNull());
        assertEquals(1, imageFileCount());
    }

    @Test
    void initialImageThreadReplayConcurrencyConflictAndRollbackKeepOneOwnedFile() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        var fields = feedbackFields("初图幂等");
        byte[] png = replyImage("png", 3);
        var barrier = new CyclicBarrier(2);
        JsonNode first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return imageCreate(f, guest, fields, png, 200);
                            });
            var two =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return imageCreate(f, guest, fields, png, 200);
                            });
            first = one.get(20, TimeUnit.SECONDS);
            assertEquals(first, two.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, imageFileCount());
        assertEquals(1, count("p_review_thread"));
        assertEquals(1, count("p_review_message"));
        assertEquals(1, count("p_review_event"));
        assertEquals(1, count("p_creation_receipt"));
        long rates =
                number(
                        "select sum(send_count) as value from p_review_rate where project_id=:id",
                        f.project);
        assertEquals(first, imageCreate(f, guest, fields, png, 200));
        assertEquals(
                rates,
                number(
                        "select sum(send_count) as value from p_review_rate where project_id=:id",
                        f.project));
        imageCreate(f, guest, fields, replyImage("png", 4), 409);
        var differentText = new HashMap<>(fields);
        differentText.put("text", "更改正文");
        imageCreate(f, guest, differentText, png, 409);
        feedback(f, guest, HttpMethod.POST, "threads", fields, 409);
        var plainFields = feedbackFields("旧文字不能换图");
        feedback(f, guest, HttpMethod.POST, "threads", plainFields, 200);
        imageCreate(f, guest, plainFields, png, 409);
        long receipts = count("p_creation_receipt");
        rates =
                number(
                        "select sum(send_count) as value from p_review_rate where project_id=:id",
                        f.project);
        var retry = feedbackFields("初图回滚后重试");
        sql(
                "create function reject_initial_image_event_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture event failure'; end $$");
        sql(
                "create trigger reject_initial_image_event_fixture before insert on p_review_event for each row execute function reject_initial_image_event_fixture()");
        try {
            imageCreate(f, guest, retry, png, 500);
        } finally {
            sql("drop trigger reject_initial_image_event_fixture on p_review_event");
            sql("drop function reject_initial_image_event_fixture()");
        }
        assertEquals(2, count("p_review_thread"));
        assertEquals(2, count("p_review_message"));
        assertEquals(2, count("p_review_event"));
        assertEquals(receipts, count("p_creation_receipt"));
        assertEquals(
                rates,
                number(
                        "select sum(send_count) as value from p_review_rate where project_id=:id",
                        f.project));
        assertEquals(1, imageFileCount());
        imageCreate(f, guest, retry, png, 200);
        assertEquals(3, count("p_review_thread"));
        assertEquals(2, imageFileCount());
        String id = first.path("id").asText();
        String message =
                feedback(
                                f,
                                guest,
                                HttpMethod.POST,
                                "threads/" + id + "/messages/_query",
                                Map.of(),
                                200)
                        .path("data")
                        .get(0)
                        .path("id")
                        .asText();
        assertArrayEquals(png, imageRead(f, guest, null, id, message, 200));
    }

    @Test
    void initialImageThreadRejectsInvalidTransportScopeAndPolicyAndUsesExistingPurge()
            throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        byte[] png = replyImage("png", 3);
        for (byte[] invalid :
                List.of(
                        "<svg/>".getBytes(StandardCharsets.UTF_8),
                        new byte[] {1, 2},
                        new byte[5 * 1024 * 1024 + 1]))
            imageCreate(f, guest, feedbackFields("无效图片"), invalid, 400);
        imageCreate(f, guest, feedbackFields("   "), png, 400);
        imageCreate(f, guest, Map.of("text", "缺少键"), png, 400);
        var scope = new HashMap<>(feedbackFields("错页面"));
        scope.put("pageId", "f".repeat(64));
        imageCreate(f, guest, scope, png, 404);
        scope = new HashMap<>(feedbackFields("错修订"));
        scope.put("revisionId", "foreign");
        assertEquals(
                "PROTOTYPE_HUB_REVIEW_DENIED",
                imageCreate(f, guest, scope, png, 403).path("code").asText());
        scope = new HashMap<>(feedbackFields("无效访问证明"));
        scope.put("accessKey", "wrong");
        imageCreate(f, guest, scope, png, 403);
        for (String violation : List.of("origin", "csrf", "cookie")) {
            var multipart = new MultipartBodyBuilder();
            multipart.part(
                    "request", imageFields(f, feedbackFields("禁止写入")), MediaType.APPLICATION_JSON);
            multipart.part(
                    "image",
                    new ByteArrayResource(png) {
                        @Override
                        public String getFilename() {
                            return "ignored";
                        }
                    });
            var request =
                    client.post()
                            .uri(imageBase(f, guest) + "_with-image")
                            .header(
                                    "Origin",
                                    violation.equals("origin")
                                            ? "https://foreign.example.invalid"
                                            : ORIGIN)
                            .header(
                                    "X-Review-CSRF",
                                    violation.equals("csrf") ? "wrong" : guest.csrf());
            if (!violation.equals("cookie")) request.cookie("prototype-review", guest.cookie);
            request.body(BodyInserters.fromMultipartData(multipart.build()))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
        for (String kind : List.of("duplicate", "bad-json", "missing", "oversized-json")) {
            var multipart = new MultipartBodyBuilder();
            if (!kind.equals("missing"))
                multipart.part(
                        "request",
                        kind.equals("bad-json")
                                ? "{"
                                : kind.equals("oversized-json")
                                        ? "x".repeat(65537)
                                        : imageFields(f, feedbackFields("无效part")),
                        MediaType.APPLICATION_JSON);
            multipart.part(
                    "image",
                    new ByteArrayResource(png) {
                        @Override
                        public String getFilename() {
                            return "ignored";
                        }
                    });
            if (kind.equals("duplicate"))
                multipart.part(
                        "image",
                        new ByteArrayResource(png) {
                            @Override
                            public String getFilename() {
                                return "ignored2";
                            }
                        });
            client.post()
                    .uri(imageBase(f, guest) + "_with-image")
                    .header("Origin", ORIGIN)
                    .header("X-Review-CSRF", guest.csrf())
                    .cookie("prototype-review", guest.cookie)
                    .body(BodyInserters.fromMultipartData(multipart.build()))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
        assertEquals(0, count("p_review_thread"));
        assertEquals(0, count("p_review_message"));
        assertEquals(0, imageFileCount());
        sql(
                "update p_share set review_allow_comment=false where id=:id",
                f.share.path("id").asText());
        imageCreate(f, guest, feedbackFields("禁评论"), png, 403);
        sql(
                "update p_share set review_allow_comment=true where id=:id",
                f.share.path("id").asText());
        JsonNode thread = imageCreate(f, guest, feedbackFields("初图保留"), png, 200);
        String id = thread.path("id").asText();
        String message =
                feedback(
                                f,
                                guest,
                                HttpMethod.POST,
                                "threads/" + id + "/messages/_query",
                                Map.of(),
                                200)
                        .path("data")
                        .get(0)
                        .path("id")
                        .asText();
        sql("update p_review_session set expires_at=1 where guest_id=:id", guest.guest());
        imageCreate(f, guest, feedbackFields("过期身份"), png, 403);
        imageRead(f, guest, null, id, message, 403);
        management(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version.path("id").asText(),
                admin,
                null,
                200);
        assertEquals(1, imageFileCount());
        database.sql("update p_version set deleted_time=:expired where id=:id")
                .bind("expired", System.currentTimeMillis() - Duration.ofDays(31).toMillis())
                .bind("id", f.version.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeVersion(f.version.path("id").asText()).block(TIMEOUT));
        assertEquals(0, count("p_review_thread"));
        assertEquals(0, count("p_review_message"));
        assertEquals(0, count("p_creation_receipt"));
        sql("update p_file_cleanup set next_attempt=0");
        fileCleanup.retryPending().block(TIMEOUT);
        assertEquals(0, imageFileCount());
    }

    @Test
    void imageRepliesPreserveExactBytesAndPrivateVisibilityWithoutChangingOldJson()
            throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("文字线程"), 200)
                        .path("id")
                        .asText();
        byte[] png = replyImage("png", 3);
        JsonNode image = imageReply(f, guest, null, thread, feedbackFields("公开图片"), png, 200);
        assertEquals(4, image.path("image").size());
        assertEquals("image/png", image.path("image").path("contentType").asText());
        assertEquals(png.length, image.path("image").path("size").asInt());
        assertArrayEquals(png, imageRead(f, guest, null, thread, image.path("id").asText(), 200));
        JsonNode before =
                feedback(
                        f, guest, HttpMethod.POST, "threads/" + thread + "/_detail", Map.of(), 200);
        byte[] jpeg = replyImage("jpeg", 4);
        JsonNode note =
                imageReply(
                        f,
                        null,
                        admin,
                        thread,
                        Map.of(
                                "text",
                                "内部图片",
                                "visibility",
                                "internal",
                                "clientRequestId",
                                "private-image"),
                        jpeg,
                        200);
        assertEquals(
                before,
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + thread + "/_detail",
                        Map.of(),
                        200));
        imageRead(f, guest, null, thread, note.path("id").asText(), 404);
        assertArrayEquals(jpeg, imageRead(f, null, admin, thread, note.path("id").asText(), 200));
        JsonNode visible =
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + thread + "/messages/_query",
                        Map.of(),
                        200);
        assertEquals(2, visible.path("total").asInt());
        assertFalse(visible.toString().contains("内部图片"));
        for (String field :
                List.of("imagePath", "imageSha256", "storagePath", "sha256", "url", "filename"))
            assertFalse(image.toString().contains("\"" + field + "\""));
        JsonNode plain =
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + thread + "/messages",
                        feedbackFields("兼容旧JSON"),
                        200);
        assertTrue(plain.path("image").isNull());
        imageRead(f, guest, null, thread, plain.path("id").asText(), 404);
        String path =
                database.sql("select image_path as value from p_review_message where id=:id")
                        .bind("id", image.path("id").asText())
                        .map(row -> row.get("value", String.class))
                        .one()
                        .block(TIMEOUT);
        assertTrue(path.startsWith("projects/" + f.project + "/review-images/"));
        Files.write(FILES.resolve(path), new byte[] {1, 2, 3});
        imageRead(f, guest, null, thread, image.path("id").asText(), 404);
    }

    @Test
    void imageReplayConcurrencyAndRollbackKeepOneGraphAndCandidateOwnership() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("线程"), 200)
                        .path("id")
                        .asText();
        var fields = feedbackFields("相同图片重放");
        byte[] png = replyImage("png", 3);
        var barrier = new CyclicBarrier(2);
        JsonNode first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return imageReply(f, guest, null, thread, fields, png, 200);
                            });
            var b =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return imageReply(f, guest, null, thread, fields, png, 200);
                            });
            first = a.get(20, TimeUnit.SECONDS);
            assertEquals(first, b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, imageFileCount());
        assertEquals(2, count("p_review_message"));
        assertEquals(2, count("p_review_event"));
        imageReply(f, guest, null, thread, fields, replyImage("png", 4), 409);
        feedback(f, guest, HttpMethod.POST, "threads/" + thread + "/messages", fields, 409);
        var oldFields = feedbackFields("旧文字回执");
        feedback(f, guest, HttpMethod.POST, "threads/" + thread + "/messages", oldFields, 200);
        imageReply(f, guest, null, thread, oldFields, png, 409);
        long receipts = count("p_creation_receipt");
        sql(
                "create function reject_image_event_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture event failure'; end $$");
        sql(
                "create trigger reject_image_event_fixture before insert on p_review_event for each row execute function reject_image_event_fixture()");
        try {
            imageReply(f, guest, null, thread, feedbackFields("回滚图片"), png, 500);
        } finally {
            sql("drop trigger reject_image_event_fixture on p_review_event");
            sql("drop function reject_image_event_fixture()");
        }
        assertEquals(3, count("p_review_message"));
        assertEquals(3, count("p_review_event"));
        assertEquals(receipts, count("p_creation_receipt"));
        assertEquals(1, imageFileCount());
        assertArrayEquals(png, imageRead(f, guest, null, thread, first.path("id").asText(), 200));
    }

    @Test
    void imageInputsAndCurrentAuthorityRejectWithoutFilesAndHardPurgeQueuesImages()
            throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("线程"), 200)
                        .path("id")
                        .asText();
        for (byte[] invalid :
                List.of(
                        "<svg/>".getBytes(StandardCharsets.UTF_8),
                        new byte[] {1, 2},
                        new byte[5 * 1024 * 1024 + 1]))
            imageReply(f, guest, null, thread, feedbackFields("无效"), invalid, 400);
        byte[] png = replyImage("png", 3);
        imageReply(f, guest, null, thread, feedbackFields("   "), png, 400);
        assertEquals(0, imageFileCount());
        Browser stranger = unverified(f, context(f, null), "另一客户", null, 200);
        imageReply(f, stranger, null, thread, feedbackFields("越权"), png, 404);
        Actor viewer = actor(List.of("query", "save"));
        member(f, viewer, "viewer");
        imageReply(
                f,
                null,
                viewer.token,
                thread,
                Map.of("text", "无EDIT", "visibility", "public", "clientRequestId", "no-edit"),
                png,
                403);
        JsonNode saved = imageReply(f, guest, null, thread, feedbackFields("保留图片"), png, 200);
        String message = saved.path("id").asText();
        imageRead(f, stranger, null, thread, message, 404);
        imageRead(f, null, viewer.token, thread, message, 403);
        fileCleanup
                .enqueueReviewImage(
                        f.project,
                        f.version.path("id").asText(),
                        database.sql(
                                        "select image_path as value from p_review_message where id=:id")
                                .bind("id", message)
                                .map(row -> row.get("value", String.class))
                                .one()
                                .block(TIMEOUT))
                .block(TIMEOUT);
        fileCleanup.retryPending().block(TIMEOUT);
        assertArrayEquals(png, imageRead(f, guest, null, thread, message, 200));
        sql("update p_review_session set expires_at=1 where guest_id=:id", guest.guest());
        imageRead(f, guest, null, thread, message, 403);
        imageReply(f, guest, null, thread, feedbackFields("过期"), png, 403);
        management(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version.path("id").asText(),
                admin,
                null,
                200);
        assertEquals(1, imageFileCount());
        imageRead(f, null, admin, thread, message, 404);
        database.sql("update p_version set deleted_time=:expired where id=:id")
                .bind("expired", System.currentTimeMillis() - Duration.ofDays(31).toMillis())
                .bind("id", f.version.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeVersion(f.version.path("id").asText()).block(TIMEOUT));
        assertEquals(0, count("p_review_message"));
        sql("update p_file_cleanup set next_attempt=0");
        fileCleanup.retryPending().block(TIMEOUT);
        assertEquals(0, imageFileCount());
        assertEquals(
                0,
                number(
                        "select count(*) as value from p_file_cleanup where resource_type='review-image' and project_id=:id",
                        f.project));
    }

    @Test
    void imageMultipartAndReadEnforceTransportPartsAndCurrentShareScope() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("线程"), 200)
                        .path("id")
                        .asText();
        byte[] png = replyImage("png", 3);
        JsonNode saved = imageReply(f, guest, null, thread, feedbackFields("图片"), png, 200);
        String path =
                imageBase(f, guest) + thread + "/messages/" + saved.path("id").asText() + "/image";
        for (String violation : List.of("origin", "csrf", "cookie")) {
            var request =
                    client.post()
                            .uri(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(
                                    "Origin",
                                    violation.equals("origin")
                                            ? "https://foreign.example.invalid"
                                            : ORIGIN)
                            .header(
                                    "X-Review-CSRF",
                                    violation.equals("csrf") ? "wrong" : guest.csrf());
            if (!violation.equals("cookie")) request.cookie("prototype-review", guest.cookie);
            request.bodyValue(imageFields(f, Map.of())).exchange().expectStatus().isForbidden();
        }
        for (String kind : List.of("duplicate", "unexpected", "bad-json", "missing")) {
            var multipart = new MultipartBodyBuilder();
            if (!kind.equals("missing"))
                multipart.part(
                        "request",
                        kind.equals("bad-json") ? "{" : imageFields(f, feedbackFields("必须拒绝")),
                        MediaType.APPLICATION_JSON);
            multipart.part(
                    "image",
                    new ByteArrayResource(png) {
                        @Override
                        public String getFilename() {
                            return "ignored";
                        }
                    });
            if (kind.equals("duplicate"))
                multipart.part(
                        "image",
                        new ByteArrayResource(png) {
                            @Override
                            public String getFilename() {
                                return "ignored2";
                            }
                        });
            if (kind.equals("unexpected")) multipart.part("extra", "ignored");
            client.post()
                    .uri(imageBase(f, guest) + thread + "/messages-with-image")
                    .header("Origin", ORIGIN)
                    .header("X-Review-CSRF", guest.csrf())
                    .cookie("prototype-review", guest.cookie)
                    .body(BodyInserters.fromMultipartData(multipart.build()))
                    .exchange()
                    .expectStatus()
                    .isBadRequest();
        }
        assertEquals(1, imageFileCount());
        Actor noSave = actor(List.of("query"));
        member(f, noSave, "editor");
        Actor noQuery = actor(List.of("save"));
        member(f, noQuery, "editor");
        imageReply(
                f,
                null,
                noSave.token,
                thread,
                Map.of("text", "无按钮权限", "visibility", "public", "clientRequestId", "no-save"),
                png,
                403);
        imageRead(f, null, noQuery.token, thread, saved.path("id").asText(), 403);
        String other =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("其他线程"), 200)
                        .path("id")
                        .asText();
        imageRead(f, guest, null, other, saved.path("id").asText(), 404);
        feedback(
                f,
                guest,
                HttpMethod.POST,
                "threads/" + thread + "/messages/" + saved.path("id").asText() + "/image",
                Map.of("pageId", "f".repeat(64)),
                404);
        sql("update p_share set enabled=false where id=:id", f.share.path("id").asText());
        imageRead(f, guest, null, thread, saved.path("id").asText(), 404);
        imageReply(f, guest, null, thread, feedbackFields("撤销后不可上传"), png, 404);
        assertEquals(1, imageFileCount());
        assertArrayEquals(png, imageRead(f, null, admin, thread, saved.path("id").asText(), 200));
    }

    private byte[] replyImage(String format, int width) throws IOException {
        var output = new ByteArrayOutputStream();
        assertTrue(
                javax.imageio.ImageIO.write(
                        new java.awt.image.BufferedImage(
                                width, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
                        format,
                        output));
        return output.toByteArray();
    }

    private long imageFileCount() throws IOException {
        try (var files = Files.walk(FILES)) {
            return files.filter(path -> path.getFileName().toString().equals("image")).count();
        }
    }

    private Map<String, Object> imageFields(Fixture f, Map<String, Object> values) {
        var body = new HashMap<String, Object>();
        body.put("revisionId", f.grant.path("revisionId").asText());
        body.put("pageId", feedbackPage());
        body.put("accessKey", f.grant.path("accessKey").asText());
        body.putAll(values);
        return body;
    }

    private JsonNode imageReply(
            Fixture f,
            Browser b,
            String actor,
            String thread,
            Map<String, Object> values,
            byte[] bytes,
            int status) {
        return imagePost(f, b, actor, thread + "/messages-with-image", values, bytes, status);
    }

    private JsonNode imageCreate(
            Fixture f, Browser b, Map<String, Object> values, byte[] bytes, int status) {
        return imagePost(f, b, null, "_with-image", values, bytes, status);
    }

    private JsonNode imagePost(
            Fixture f,
            Browser b,
            String actor,
            String path,
            Map<String, Object> values,
            byte[] bytes,
            int status) {
        var multipart = new MultipartBodyBuilder();
        multipart.part("request", imageFields(f, values), MediaType.APPLICATION_JSON);
        multipart.part(
                "image",
                new ByteArrayResource(bytes) {
                    @Override
                    public String getFilename() {
                        return "../../ignored.svg";
                    }
                },
                MediaType.APPLICATION_OCTET_STREAM);
        var request =
                client.post()
                        .uri(imageBase(f, b) + path)
                        .contentType(MediaType.MULTIPART_FORM_DATA);
        if (b != null)
            request.header("Origin", ORIGIN)
                    .header("X-Review-CSRF", b.csrf())
                    .cookie("prototype-review", b.cookie);
        else request.header("X-Access-Token", actor);
        JsonNode body =
                request.body(BodyInserters.fromMultipartData(multipart.build()))
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        return status == 200 ? body.path("result") : body;
    }

    private byte[] imageRead(
            Fixture f, Browser b, String actor, String thread, String message, int status) {
        var request =
                client.post()
                        .uri(imageBase(f, b) + thread + "/messages/" + message + "/image")
                        .contentType(MediaType.APPLICATION_JSON);
        if (b != null)
            request.header("Origin", ORIGIN)
                    .header("X-Review-CSRF", b.csrf())
                    .cookie("prototype-review", b.cookie);
        else request.header("X-Access-Token", actor);
        var response =
                request.bodyValue(imageFields(f, Map.of()))
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status);
        if (status == 200)
            response.expectHeader()
                    .valueEquals("X-Content-Type-Options", "nosniff")
                    .expectHeader()
                    .valueEquals("Cache-Control", "private, no-store");
        return response.expectBody().returnResult().getResponseBody();
    }

    private String imageBase(Fixture f, Browser b) {
        return b != null
                ? "/api/prototype-hub/public/shares/"
                        + f.share.path("token").asText()
                        + "/review/threads/"
                : "/api/prototype-hub/versions/"
                        + f.version.path("id").asText()
                        + "/review-threads/";
    }

    private Fixture feedbackFixture() throws IOException {
        return feedbackFixture(List.of("index.html"));
    }

    private Fixture feedbackFixture(List<String> pages) throws IOException {
        Fixture f = fixture(true, false, pages);
        management(
                HttpMethod.PUT,
                manage(f),
                admin,
                Map.of(
                        "enabled",
                        true,
                        "allowComment",
                        true,
                        "allowUnverified",
                        true,
                        "expectedVersion",
                        2),
                200);
        return refresh(f);
    }

    private Map<String, Object> feedbackFields(String text) {
        return Map.of("text", text, "clientRequestId", UUID.randomUUID().toString());
    }

    private String feedbackPage() {
        return com.gaiys.proto.prototypehub.web.info.PrototypePageInfo.page("index", "index.html")
                .pageId();
    }

    private JsonNode feedback(
            Fixture f,
            Browser b,
            HttpMethod method,
            String path,
            Map<String, Object> values,
            int status) {
        var body = new HashMap<String, Object>();
        body.put("revisionId", f.grant.path("revisionId").asText());
        body.put("accessKey", f.grant.path("accessKey").asText());
        body.put("pageId", feedbackPage());
        body.putAll(values);
        var response =
                client.method(method)
                        .uri(
                                "/api/prototype-hub/public/shares/"
                                        + f.share.path("token").asText()
                                        + "/review/"
                                        + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Origin", ORIGIN)
                        .header("X-Review-CSRF", b.csrf())
                        .cookie("prototype-review", b.cookie)
                        .bodyValue(body)
                        .exchange();
        response.expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
        JsonNode data = response.expectBody(JsonNode.class).returnResult().getResponseBody();
        return status == 200 ? data.path("result") : data;
    }

    private JsonNode pmFeedback(
            Fixture f,
            String path,
            HttpMethod method,
            String actor,
            Map<String, Object> values,
            int status) {
        var body = new HashMap<String, Object>();
        body.put("revisionId", f.grant.path("revisionId").asText());
        body.put("pageId", feedbackPage());
        body.putAll(values);
        return management(
                method,
                "/prototype-hub/versions/"
                        + f.version.path("id").asText()
                        + "/review-threads/"
                        + path,
                actor,
                body,
                status);
    }

    private void member(Fixture f, Actor actor, String role) {
        management(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + f.project + "/members/" + actor.id,
                admin,
                Map.of("role", role),
                200);
    }

    private Map<String, Object> assignmentFields(String target, long expected, String key) {
        var fields = new HashMap<String, Object>();
        fields.put("assigneeId", target);
        fields.put("expectedVersion", expected);
        fields.put("clientRequestId", key);
        return fields;
    }

    private String assignmentThread(Fixture f, Browser guest) {
        return feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("待分派反馈"), 200)
                .path("id")
                .asText();
    }

    private JsonNode assignment(Fixture f, String thread, String actor) {
        return pmFeedback(f, thread + "/assignment/_detail", HttpMethod.POST, actor, Map.of(), 200);
    }

    @Test
    void assignmentCandidatesArePagedEligibleAndPrivateWithoutPublicMetadataChanges()
            throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread = assignmentThread(f, guest);
        Actor editor = actor(List.of("query", "save"), "prototype-hub-version", "候选%_编辑者");
        Actor second = actor(List.of("query"), "prototype-hub-version", "候选另一人");
        Actor viewer = actor(List.of("query", "save"), "prototype-hub-version", "候选查看者");
        Actor disabled = actor(List.of("query", "save"), "prototype-hub-version", "候选停用");
        Actor outsider = actor(List.of("query", "save"), "prototype-hub-version", "候选外部");
        member(f, editor, "editor");
        member(f, second, "editor");
        member(f, viewer, "viewer");
        member(f, disabled, "editor");
        database.sql("update s_user set status=0 where id=:id")
                .bind("id", disabled.id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        JsonNode original =
                feedback(
                        f, guest, HttpMethod.POST, "threads/" + thread + "/_detail", Map.of(), 200);
        JsonNode initial = assignment(f, thread, editor.token);
        assertEquals(0, initial.path("assignmentVersion").asLong());
        assertTrue(initial.path("assignee").isNull());
        assertTrue(initial.path("updatedAt").isNull());
        JsonNode page =
                pmFeedback(
                        f,
                        thread + "/assignment/_candidates",
                        HttpMethod.POST,
                        editor.token,
                        Map.of("keyword", "候选", "pageSize", 1),
                        200);
        assertEquals(2, page.path("total").asInt());
        assertEquals(editor.id, page.path("data").get(0).path("userId").asText());
        assertEquals(2, page.path("data").get(0).size());
        JsonNode next =
                pmFeedback(
                        f,
                        thread + "/assignment/_candidates",
                        HttpMethod.POST,
                        editor.token,
                        Map.of("keyword", "候选", "pageSize", 1, "pageIndex", 1),
                        200);
        assertEquals(second.id, next.path("data").get(0).path("userId").asText());
        JsonNode beyond =
                pmFeedback(
                        f,
                        thread + "/assignment/_candidates",
                        HttpMethod.POST,
                        editor.token,
                        Map.of("keyword", "候选", "pageSize", 1, "pageIndex", 99),
                        200);
        assertEquals(2, beyond.path("total").asInt());
        assertEquals(99, beyond.path("pageIndex").asInt());
        assertEquals(0, beyond.path("data").size());
        assertEquals(
                1,
                pmFeedback(
                                f,
                                thread + "/assignment/_candidates",
                                HttpMethod.POST,
                                editor.token,
                                Map.of("keyword", "%_"),
                                200)
                        .path("total")
                        .asInt());
        JsonNode all =
                pmFeedback(
                        f,
                        thread + "/assignment/_candidates",
                        HttpMethod.POST,
                        editor.token,
                        Map.of(),
                        200);
        assertEquals(3, all.path("total").asInt());
        String owner = text("select owner_id as value from p_project where id=:id", f.project);
        assertEquals(
                Set.of(owner, editor.id, second.id),
                new HashSet<>(all.path("data").findValuesAsText("userId")));
        assertEquals(
                1,
                all.path("data").findValuesAsText("userId").stream().filter(owner::equals).count());
        JsonNode assigned =
                pmFeedback(
                        f,
                        thread + "/assignment",
                        HttpMethod.PUT,
                        editor.token,
                        assignmentFields(editor.id, 0, "initial-assignment"),
                        200);
        assertEquals(1, assigned.path("assignmentVersion").asLong());
        assertTrue(assigned.path("updatedAt").asLong() > 0);
        assertEquals("候选%_编辑者", assigned.path("assignee").path("name").asText());
        assertTrue(assigned.path("assignee").path("available").asBoolean());
        database.sql("update s_user set name='改名' where id=:id")
                .bind("id", editor.id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertEquals(assigned, assignment(f, thread, admin));
        assertEquals(
                original,
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + thread + "/_detail",
                        Map.of(),
                        200));
        assertEquals(
                original,
                pmFeedback(f, thread + "/_detail", HttpMethod.POST, admin, Map.of(), 200));
        assertEquals(1, count("p_review_message"));
        assertEquals(2, count("p_review_event"));
        assertFalse(assigned.toString().contains("username"));
        assertFalse(assigned.toString().contains("email"));
        member(f, editor, "viewer");
        assertFalse(assignment(f, thread, admin).path("assignee").path("available").asBoolean());
        member(f, editor, "editor");
        assertTrue(assignment(f, thread, admin).path("assignee").path("available").asBoolean());
        management(
                HttpMethod.DELETE,
                "/prototype-hub/projects/" + f.project + "/members/" + editor.id,
                admin,
                null,
                200);
        assertFalse(assignment(f, thread, admin).path("assignee").path("available").asBoolean());
        member(f, editor, "editor");
        database.sql("update s_user set status=0 where id=:id")
                .bind("id", editor.id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertFalse(assignment(f, thread, admin).path("assignee").path("available").asBoolean());
        for (String target : List.of(viewer.id, disabled.id, outsider.id))
            pmFeedback(
                    f,
                    thread + "/assignment",
                    HttpMethod.PUT,
                    admin,
                    assignmentFields(target, 1, "bad-" + target),
                    400);
        assertEquals(
                original,
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + thread + "/_detail",
                        Map.of(),
                        200));
        database.sql("update p_project set owner_id=null where id=:id")
                .bind("id", f.project)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        JsonNode ownerless =
                pmFeedback(
                        f,
                        thread + "/assignment/_candidates",
                        HttpMethod.POST,
                        admin,
                        Map.of(),
                        200);
        assertEquals(1, ownerless.path("total").asInt());
        assertEquals(List.of(second.id), ownerless.path("data").findValuesAsText("userId"));
    }

    @Test
    void assignmentCasReplayNoopConcurrencyAndEventRollbackAreAtomic() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread = assignmentThread(f, guest);
        Actor a = actor(List.of("query", "save"), "prototype-hub-version", "A");
        Actor b = actor(List.of("query", "save"), "prototype-hub-version", "B");
        member(f, a, "editor");
        member(f, b, "editor");
        var set = assignmentFields(a.id, 0, "assignment-once");
        var barrier = new CyclicBarrier(2);
        JsonNode first;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return pmFeedback(
                                        f, thread + "/assignment", HttpMethod.PUT, admin, set, 200);
                            });
            var two =
                    pool.submit(
                            () -> {
                                barrier.await(5, TimeUnit.SECONDS);
                                return pmFeedback(
                                        f, thread + "/assignment", HttpMethod.PUT, admin, set, 200);
                            });
            first = one.get(20, TimeUnit.SECONDS);
            assertEquals(first, two.get(20, TimeUnit.SECONDS));
        }
        assertEquals(2, count("p_review_event"));
        assertEquals(2, count("p_creation_receipt"));
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                admin,
                assignmentFields(b.id, 0, "assignment-once"),
                409);
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                admin,
                assignmentFields(a.id, 0, "assignment-stale"),
                409);
        assertEquals(
                first,
                pmFeedback(
                        f,
                        thread + "/assignment",
                        HttpMethod.PUT,
                        admin,
                        assignmentFields(a.id, 1, "assignment-noop"),
                        200));
        assertEquals(2, count("p_review_event"));
        assertEquals(3, count("p_creation_receipt"));
        member(f, a, "viewer");
        assertFalse(
                pmFeedback(f, thread + "/assignment", HttpMethod.PUT, admin, set, 200)
                        .path("assignee")
                        .path("available")
                        .asBoolean());
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                admin,
                assignmentFields(a.id, 1, "new-key-unavailable"),
                400);
        JsonNode changed =
                pmFeedback(
                        f,
                        thread + "/assignment",
                        HttpMethod.PUT,
                        admin,
                        assignmentFields(b.id, 1, "assignment-next"),
                        200);
        assertEquals(2, changed.path("assignmentVersion").asLong());
        assertEquals(
                changed, pmFeedback(f, thread + "/assignment", HttpMethod.PUT, admin, set, 200));
        var clear = assignmentFields(null, 2, "assignment-clear");
        long receipts = count("p_creation_receipt"), events = count("p_review_event");
        sql(
                "create function reject_assignment_event_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture assignment failure'; end $$");
        sql(
                "create trigger reject_assignment_event_fixture before insert on p_review_event for each row execute function reject_assignment_event_fixture()");
        try {
            pmFeedback(f, thread + "/assignment", HttpMethod.PUT, admin, clear, 500);
        } finally {
            sql("drop trigger reject_assignment_event_fixture on p_review_event");
            sql("drop function reject_assignment_event_fixture()");
        }
        assertEquals(changed, assignment(f, thread, admin));
        assertEquals(receipts, count("p_creation_receipt"));
        assertEquals(events, count("p_review_event"));
        JsonNode cleared = pmFeedback(f, thread + "/assignment", HttpMethod.PUT, admin, clear, 200);
        assertTrue(cleared.path("assignee").isNull());
        assertEquals(3, cleared.path("assignmentVersion").asLong());
        assertEquals(
                cleared,
                pmFeedback(
                        f,
                        thread + "/assignment",
                        HttpMethod.PUT,
                        admin,
                        assignmentFields(null, 3, "clear-noop"),
                        200));
        assertEquals(events + 1, count("p_review_event"));
        member(f, a, "editor");
        var race = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> results = new ArrayList<>();
            for (String target : List.of(a.id, b.id))
                results.add(
                        pool.submit(
                                () -> {
                                    race.await(5, TimeUnit.SECONDS);
                                    var body =
                                            new HashMap<String, Object>(
                                                    assignmentFields(target, 3, "race-" + target));
                                    body.put("revisionId", f.grant.path("revisionId").asText());
                                    body.put("pageId", feedbackPage());
                                    return client.put()
                                            .uri(
                                                    "/api/prototype-hub/versions/"
                                                            + f.version.path("id").asText()
                                                            + "/review-threads/"
                                                            + thread
                                                            + "/assignment")
                                            .header("X-Access-Token", admin)
                                            .bodyValue(body)
                                            .exchange()
                                            .returnResult(JsonNode.class)
                                            .getStatus()
                                            .value();
                                }));
            var statuses =
                    List.of(
                            results.get(0).get(20, TimeUnit.SECONDS),
                            results.get(1).get(20, TimeUnit.SECONDS));
            assertTrue(statuses.contains(200));
            assertTrue(statuses.contains(409));
        }
        assertEquals(4, assignment(f, thread, admin).path("assignmentVersion").asLong());
        assertEquals(events + 2, count("p_review_event"));
        assertEquals(1, count("p_review_message"));
    }

    @Test
    void assignmentRequiresCurrentScopeAndActionAndRejectsInvalidTargetTypes() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread = assignmentThread(f, guest);
        Actor editor = actor(List.of("query", "save"), "prototype-hub-version"),
                viewer = actor(List.of("query", "save"), "prototype-hub-version"),
                outsider = actor(List.of("query", "save"), "prototype-hub-version"),
                query = actor(List.of("query"), "prototype-hub-version"),
                save = actor(List.of("save"), "prototype-hub-version");
        for (Actor a : List.of(editor, query, save)) member(f, a, "editor");
        member(f, viewer, "viewer");
        for (String suffix : List.of("/_detail", "/_candidates")) {
            pmFeedback(
                    f,
                    thread + "/assignment" + suffix,
                    HttpMethod.POST,
                    viewer.token,
                    Map.of(),
                    403);
            pmFeedback(
                    f,
                    thread + "/assignment" + suffix,
                    HttpMethod.POST,
                    outsider.token,
                    Map.of(),
                    404);
            pmFeedback(
                    f, thread + "/assignment" + suffix, HttpMethod.POST, save.token, Map.of(), 403);
            pmFeedback(f, thread + "/assignment" + suffix, HttpMethod.POST, null, Map.of(), 401);
            pmFeedback(
                    f,
                    thread + "/assignment" + suffix,
                    HttpMethod.POST,
                    query.token,
                    Map.of(),
                    200);
        }
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                query.token,
                assignmentFields(editor.id, 0, "no-save"),
                403);
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                viewer.token,
                assignmentFields(editor.id, 0, "viewer"),
                403);
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                outsider.token,
                assignmentFields(editor.id, 0, "outsider"),
                404);
        for (Object invalid :
                List.of("", "  ", "\u2003", 100, true, List.of("id"), Map.of("id", editor.id))) {
            var input = assignmentFields(null, 0, "invalid");
            input.put("assigneeId", invalid);
            pmFeedback(f, thread + "/assignment", HttpMethod.PUT, admin, input, 400);
        }
        for (var input :
                List.of(
                        Map.<String, Object>of(
                                "expectedVersion", -1, "clientRequestId", "negative"),
                        Map.<String, Object>of("expectedVersion", 0),
                        Map.<String, Object>of("expectedVersion", 0, "clientRequestId", "bad key")))
            pmFeedback(f, thread + "/assignment", HttpMethod.PUT, admin, input, 400);
        for (var input :
                List.of(
                        Map.<String, Object>of("pageSize", 51),
                        Map.<String, Object>of("pageIndex", -1),
                        Map.<String, Object>of("keyword", "x".repeat(101))))
            pmFeedback(f, thread + "/assignment/_candidates", HttpMethod.POST, admin, input, 400);
        pmFeedback(
                f,
                thread + "/assignment/_detail",
                HttpMethod.POST,
                admin,
                Map.of("pageId", "0".repeat(64)),
                404);
        pmFeedback(
                f,
                thread + "/assignment/_detail",
                HttpMethod.POST,
                admin,
                Map.of("revisionId", "absent"),
                404);
        pmFeedback(f, "absent/assignment/_detail", HttpMethod.POST, admin, Map.of(), 404);
        feedback(
                f,
                guest,
                HttpMethod.POST,
                "threads/" + thread + "/assignment/_detail",
                Map.of(),
                404);
        JsonNode assigned =
                pmFeedback(
                        f,
                        thread + "/assignment",
                        HttpMethod.PUT,
                        save.token,
                        assignmentFields(editor.id, 0, "save-only"),
                        200);
        assertEquals(1, assigned.path("assignmentVersion").asLong());
        management(
                HttpMethod.DELETE,
                "/prototype-hub/versions/" + f.version.path("id").asText(),
                admin,
                null,
                200);
        pmFeedback(f, thread + "/assignment/_detail", HttpMethod.POST, admin, Map.of(), 404);
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                admin,
                assignmentFields(editor.id, 1, "after-delete"),
                404);
    }

    @Test
    void assignmentDeletedUserCleanupIsBoundedAtomicAndSameIdCannotInherit() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String thread = assignmentThread(f, guest);
        Actor target = actor(List.of("query", "save"), "prototype-hub-version", "责任姓名");
        member(f, target, "editor");
        pmFeedback(
                f,
                thread + "/assignment",
                HttpMethod.PUT,
                admin,
                assignmentFields(target.id, 0, "assign-before-delete"),
                200);
        String username = text("select username as value from s_user where id=:id", target.id);
        management(
                HttpMethod.DELETE,
                "/prototype-hub/projects/" + f.project + "/members/" + target.id,
                admin,
                null,
                200);
        JsonNode publicBefore =
                feedback(
                        f, guest, HttpMethod.POST, "threads/" + thread + "/_detail", Map.of(), 200);
        // 足量旧责任行跨越100项发现游标和50项清理批次；来源为同一有效固定页面线程快照。
        database.sql(
                        "insert into p_review_thread(id,project_id,version_id,revision_id,share_id,page_id,page_name,page_path,guest_id,verification_level,author_name,author_company,summary,state,config_version,create_time,public_updated_at,assignee_id,assignee_name,assignment_version,assignment_updated_at) select 'assignment-batch-'||n,t.project_id,t.version_id,t.revision_id,t.share_id,t.page_id,t.page_name,t.page_path,t.guest_id,t.verification_level,t.author_name,t.author_company,t.summary,t.state,t.config_version,t.create_time,t.public_updated_at,t.assignee_id,t.assignee_name,t.assignment_version,t.assignment_updated_at from p_review_thread t cross join generate_series(1,101) n where t.id=:id")
                .bind("id", thread)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        sql(
                "create function reject_assignment_cleanup_fixture() returns trigger language plpgsql as $$ begin if OLD.assignee_id is not null and NEW.assignee_id is null then raise exception 'fixture cleanup failure'; end if; return NEW; end $$");
        sql(
                "create trigger reject_assignment_cleanup_fixture before update on p_review_thread for each row execute function reject_assignment_cleanup_fixture()");
        try {
            management(HttpMethod.DELETE, "/user/" + target.id, admin, null, 500);
        } finally {
            sql("drop trigger reject_assignment_cleanup_fixture on p_review_thread");
            sql("drop function reject_assignment_cleanup_fixture()");
        }
        assertEquals(1, number("select count(*) as value from s_user where id=:id", target.id));
        assertEquals(
                102,
                number(
                        "select count(*) as value from p_review_thread where assignee_id=:id",
                        target.id));
        assertEquals(2, count("p_review_event"));
        management(HttpMethod.DELETE, "/user/" + target.id, admin, null, 200);
        assertEquals(
                0,
                number(
                        "select count(*) as value from p_review_thread where assignee_id=:id",
                        target.id));
        assertEquals(
                102,
                number(
                        "select count(*) as value from p_review_thread where project_id=:id and assignee_id is null and assignee_name is null and assignment_version=2 and assignment_updated_at is not null",
                        f.project));
        assertEquals(104, count("p_review_event"));
        assertEquals(
                publicBefore,
                feedback(
                        f,
                        guest,
                        HttpMethod.POST,
                        "threads/" + thread + "/_detail",
                        Map.of(),
                        200));
        JsonNode recreated =
                management(
                        HttpMethod.POST,
                        "/user/detail/_create",
                        admin,
                        Map.of("name", "同ID新账号", "username", username, "password", PASSWORD),
                        200);
        assertEquals(target.id, recreated.path("id").asText());
        member(f, new Actor(target.id, "unused"), "editor");
        JsonNode current = assignment(f, thread, admin);
        assertTrue(current.path("assignee").isNull());
        assertEquals(2, current.path("assignmentVersion").asLong());
        assertEquals(
                current,
                pmFeedback(
                        f,
                        thread + "/assignment",
                        HttpMethod.PUT,
                        admin,
                        assignmentFields(target.id, 0, "assign-before-delete"),
                        200));
        database.sql("update p_version set deleted_time=1 where id=:id")
                .bind("id", f.version.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(retention.purgeVersion(f.version.path("id").asText()).block(TIMEOUT));
        assertEquals(0, count("p_review_thread"));
        assertEquals(0, count("p_review_event"));
        assertEquals(0, count("p_creation_receipt"));
    }

    @Test
    void pageFeedbackMemberNamesUseExistingUserModelLimitWithoutTruncation() throws Exception {
        Fixture f = feedbackFixture();
        Browser guest = unverified(f, context(f, null), "客户", null, 200);
        String id =
                feedback(f, guest, HttpMethod.POST, "threads", feedbackFields("姓名快照"), 200)
                        .path("id")
                        .asText();
        for (int length : List.of(101, 128)) {
            String name = "名".repeat(length);
            Actor member = actor(List.of("query", "save"), "prototype-hub-version", name);
            member(f, member, "editor");
            for (String visibility : List.of("public", "internal")) {
                JsonNode response =
                        pmFeedback(
                                f,
                                id + "/messages",
                                HttpMethod.POST,
                                member.token,
                                Map.of(
                                        "text",
                                        "完整姓名",
                                        "visibility",
                                        visibility,
                                        "clientRequestId",
                                        UUID.randomUUID().toString()),
                                200);
                assertEquals(name, response.path("authorName").asText());
            }
        }
        assertEquals(
                5,
                pmFeedback(f, id + "/messages/_query", HttpMethod.POST, admin, Map.of(), 200)
                        .path("total")
                        .asInt());
        assertEquals(
                3,
                feedback(
                                f,
                                guest,
                                HttpMethod.POST,
                                "threads/" + id + "/messages/_query",
                                Map.of(),
                                200)
                        .path("total")
                        .asInt());
    }

    private JsonNode invite(Fixture f, String email, int status) {
        return management(
                HttpMethod.POST,
                invitePath(f),
                admin,
                Map.of("email", email, "expectedShareVersion", 2),
                status);
    }

    private JsonNode invitePage(Fixture f, String actor, int page, int size, int status) {
        return management(
                HttpMethod.POST,
                invitePath(f) + "/_query",
                actor,
                Map.of("pageIndex", page, "pageSize", size),
                status);
    }

    private JsonNode revokeInvitation(Fixture f, String id, long version, int status) {
        return management(
                HttpMethod.PUT,
                invitePath(f) + "/" + id + "/revoke",
                admin,
                Map.of("expectedVersion", version),
                status);
    }

    private JsonNode inviteChallenge(Fixture f, Browser b, String email, String token, int status) {
        return exchange(
                        f,
                        "challenges",
                        Map.of("email", email, "displayName", "Invited", "invitationToken", token),
                        b.cookie,
                        b.csrf(),
                        ORIGIN,
                        status)
                .data;
    }

    private Fixture unverifiedFixture() throws IOException {
        Fixture fixture = fixture(true, false);
        management(
                HttpMethod.PUT,
                manage(fixture),
                admin,
                Map.of("enabled", true, "allowUnverified", true, "expectedVersion", 2),
                200);
        return refresh(fixture);
    }

    private Browser unverified(
            Fixture fixture, Browser browser, String name, String company, int... status) {
        var fields = new HashMap<String, Object>();
        fields.put("displayName", name);
        if (company != null) fields.put("company", company);
        return exchange(
                fixture, "unverified", fields, browser.cookie, browser.csrf(), ORIGIN, status);
    }

    private JsonNode unverifiedPage(Fixture fixture, String token, int page, int size, int status) {
        return management(
                HttpMethod.POST,
                guestPath(fixture) + "/_query",
                token,
                Map.of("pageIndex", page, "pageSize", size, "verificationLevel", "unverified"),
                status);
    }

    private JsonNode unverifiedEnabled(
            Fixture fixture,
            String guest,
            String token,
            boolean enabled,
            long version,
            int status) {
        return management(
                HttpMethod.PUT,
                guestPath(fixture) + "/" + guest + "/enabled?verificationLevel=unverified",
                token,
                Map.of("enabled", enabled, "expectedVersion", version),
                status);
    }

    private JsonNode guestEnabled(
            Fixture fixture,
            String guest,
            String token,
            boolean enabled,
            long version,
            int status) {
        return management(
                HttpMethod.PUT,
                guestPath(fixture) + "/" + guest + "/enabled",
                token,
                Map.of("enabled", enabled, "expectedVersion", version),
                status);
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
        return fixture(enable, password, List.of("index.html"));
    }

    private Fixture fixture(boolean enable, boolean password, List<String> pages)
            throws IOException {
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
            for (String page : pages) {
                zip.putNextEntry(new ZipEntry(page));
                zip.write(
                        "<html><body>Review fixture</body></html>"
                                .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
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
        var exchange = request.exchange();
        if (method == HttpMethod.POST && path.endsWith("/review-invitations") && status == 200)
            exchange.expectHeader().valueEquals("Cache-Control", "no-store");
        var response =
                exchange.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        return status == 200 ? response.path("result") : response;
    }

    private Actor actor(List<String> actions) {
        return actor(actions, "prototype-hub-share");
    }

    private Actor actor(List<String> actions, String permission) {
        return actor(actions, permission, "Review test");
    }

    private Actor actor(List<String> actions, String permission, String displayName) {
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
                        permission,
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
                                        displayName,
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
                            case "p_review_thread" ->
                                    "select count(*) as value from p_review_thread";
                            case "p_review_message" ->
                                    "select count(*) as value from p_review_message";
                            case "p_review_event" -> "select count(*) as value from p_review_event";
                            case "p_creation_receipt" ->
                                    "select count(*) as value from p_creation_receipt";
                            case "p_review_invitation" ->
                                    "select count(*) as value from p_review_invitation";
                            case "s_user" -> "select count(*) as value from s_user";
                            case "p_review_guest" -> "select count(*) as value from p_review_guest";
                            case "p_review_unverified_guest" ->
                                    "select count(*) as value from p_review_unverified_guest";
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

    private String text(String statement, String id) {
        return database.sql(statement)
                .bind("id", id)
                .map((row, metadata) -> row.get("value", String.class))
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
