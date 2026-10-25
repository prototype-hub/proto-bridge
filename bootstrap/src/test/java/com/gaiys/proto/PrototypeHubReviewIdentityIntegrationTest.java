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
                        "modifyTime"),
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
