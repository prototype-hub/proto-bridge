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

    private Fixture feedbackFixture() throws IOException {
        Fixture f = fixture(true, false);
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
