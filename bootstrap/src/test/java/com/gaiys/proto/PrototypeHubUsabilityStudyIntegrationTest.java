package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.service.PrototypeReviewService;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
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

/** 实际HTTP/PostgreSQL覆盖独立研究同意、本人范围、持久回执、并发上限及原子清理。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml", "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
                    "proto.security.login-encryption-enabled=false",
            "prototype-hub.cleanup.interval-ms=3600000",
                    "prototype-hub.review.trusted-origin=https://research.example.invalid",
            "spring.mail.properties.mail.smtp.auth=false",
                    "spring.mail.properties.mail.smtp.starttls.enable=false",
            "spring.mail.properties.mail.smtp.starttls.required=false",
                    "spring.mail.properties.mail.smtp.ssl.enable=false"
        })
@Testcontainers
class PrototypeHubUsabilityStudyIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString(),
            ORIGIN = "https://research.example.invalid";
    private static final String PAGE = PrototypePageInfo.page("Home", "index.html").pageId();
    private static final Path FILES = temporaryStorage();
    private static final LocalReviewMailbox MAILBOX = new LocalReviewMailbox();

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
        p.add("spring.r2dbc.pool.max-size", () -> 4);
        p.add("spring.r2dbc.pool.initial-size", () -> 1);
        p.add("proto.admin.password", () -> PASSWORD);
        p.add("prototype-hub.storage.root", FILES::toString);
        p.add("spring.mail.host", () -> "127.0.0.1");
        p.add("spring.mail.port", MAILBOX::port);
        p.add("spring.mail.username", () -> "research@example.invalid");
        p.add("spring.mail.password", () -> "");
        p.add("spring.mail.protocol", () -> "smtp");
        p.add("spring.mail.properties.mail.smtp.from", () -> "research@example.invalid");
        p.add("spring.mail.properties.mail.from", () -> "research@example.invalid");
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired ReactiveRepository<PrototypeProjectEntity, String> projects;
    @Autowired ReactiveRepository<PrototypeProjectMemberEntity, String> members;
    @Autowired PrototypeRetentionService retention;
    @Autowired PrototypeReviewService review;
    @Autowired FileCleanupService cleanup;
    private String admin;

    @BeforeEach
    void isolate() {
        PrototypeHubTestData.clear(database);
        MAILBOX.reset();
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin = login("admin");
    }

    @AfterAll
    static void removeFiles() throws IOException {
        MAILBOX.close();
        SecureZipExtractor.deleteRecursively(FILES);
    }

    @Test
    void explicitConsentOwnDraftImmutableSubmissionAndCurrentReceiptReplay() throws Exception {
        Fixture f = fixture("fixed", false);
        String study = createOpen(f, "flow");
        Browser one = unverified(f), other = unverified(f);
        var definition = guest(f, null, study + "/context", Map.of(), null, ORIGIN, 200).data();
        assertTrue(definition.path("participation").isNull());
        assertEquals(0, count("p_usability_response"));
        assertEquals(8, definition.path("study").size());
        assertFalse(definition.toString().contains("createdBy"));
        guest(
                f,
                one,
                study + "/consent",
                Map.of("contentVersion", 1, "agreed", false, "clientRequestId", "no"),
                one.csrf(),
                ORIGIN,
                400);
        guest(f, null, study + "/consent", consent("no-cookie"), one.csrf(), ORIGIN, 403);
        guest(f, one, study + "/consent", consent("csrf"), "invalid", ORIGIN, 403);
        guest(
                f,
                one,
                study + "/consent",
                consent("origin"),
                one.csrf(),
                "https://foreign.invalid",
                403);
        guest(f, one, study + "/progress", answers(0, "before-consent"), one.csrf(), ORIGIN, 404);
        assertEquals(0, count("p_usability_response"));
        var accepted =
                guest(f, one, study + "/consent", consent("consent"), one.csrf(), ORIGIN, 200)
                        .data();
        assertEquals("draft", accepted.path("participation").path("status").asText());
        assertEquals(
                accepted,
                guest(f, one, study + "/consent", consent("consent"), one.csrf(), ORIGIN, 200)
                        .data());
        assertTrue(
                guest(f, other, study + "/context", Map.of(), null, ORIGIN, 200)
                        .data()
                        .path("participation")
                        .isNull());
        var saved =
                guest(f, one, study + "/progress", answers(0, "save"), one.csrf(), ORIGIN, 200)
                        .data();
        assertEquals(1, saved.path("participation").path("configVersion").asInt());
        assertEquals(
                0,
                result(
                                HttpMethod.POST,
                                endpoint(f) + "/" + study + "/responses/_query",
                                admin,
                                Map.of())
                        .path("total")
                        .asInt());
        guest(f, one, study + "/progress", answers(0, "stale"), one.csrf(), ORIGIN, 409);
        var incomplete = answers(1, "incomplete");
        incomplete.put("answers", List.of());
        guest(f, one, study + "/submit", incomplete, one.csrf(), ORIGIN, 400);
        var finalResult =
                guest(f, one, study + "/submit", answers(1, "submit"), one.csrf(), ORIGIN, 200)
                        .data();
        assertEquals("submitted", finalResult.path("participation").path("status").asText());
        assertEquals(
                finalResult,
                guest(f, one, study + "/progress", answers(0, "save"), one.csrf(), ORIGIN, 200)
                        .data());
        assertEquals(
                finalResult,
                guest(f, one, study + "/consent", consent("consent"), one.csrf(), ORIGIN, 200)
                        .data());
        guest(f, one, study + "/progress", answers(2, "immutable"), one.csrf(), ORIGIN, 409);
        var changed = answers(1, "submit");
        changed.put("feedback", "different");
        guest(f, one, study + "/submit", changed, one.csrf(), ORIGIN, 409);
        var page =
                result(
                        HttpMethod.POST,
                        endpoint(f) + "/" + study + "/responses/_query",
                        admin,
                        Map.of("pageSize", 1));
        assertEquals(1, page.path("total").asInt());
        JsonNode member = page.path("data").get(0);
        assertEquals(6, member.size());
        assertEquals("PRIVATE overall", member.path("feedback").asText());
        for (String excluded :
                List.of(
                        "guestId",
                        "shareId",
                        "verificationLevel",
                        "consentedAt",
                        "submittedAt",
                        "createdAt",
                        "updatedAt",
                        "configVersion")) assertFalse(member.has(excluded));
        assertEquals(
                member,
                result(
                        HttpMethod.GET,
                        endpoint(f) + "/" + study + "/responses/" + member.path("id").asText(),
                        admin,
                        null));
        var summary = result(HttpMethod.GET, endpoint(f) + "/" + study + "/summary", admin, null);
        assertEquals(1, summary.path("submittedCount").asInt());
        assertFalse(summary.toString().contains("PRIVATE"));
        assertEquals(1, summary.path("excludedTextQuestionCount").asInt());
        byte[] csv =
                client.get()
                        .uri(endpoint(f) + "/" + study + "/summary.csv")
                        .header("X-Access-Token", admin)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectHeader()
                        .valueEquals("Cache-Control", "private, no-store")
                        .expectHeader()
                        .valueEquals("X-Content-Type-Options", "nosniff")
                        .expectHeader()
                        .valueEquals(
                                "Content-Disposition",
                                "attachment; filename=\"usability-summary.csv\"")
                        .expectBody()
                        .returnResult()
                        .getResponseBody();
        assertFalse(new String(csv, StandardCharsets.UTF_8).contains("PRIVATE"));
        result(HttpMethod.POST, endpoint(f) + "/" + study + "/_close", admin, mutation(1, "close"));
        var withdrawn =
                guest(f, one, study + "/withdraw", withdraw(2, "withdraw"), one.csrf(), ORIGIN, 200)
                        .data();
        assertEquals("withdrawn", withdrawn.path("participation").path("status").asText());
        assertTrue(withdrawn.path("participation").path("taskAnswers").isEmpty());
        assertTrue(withdrawn.path("participation").path("answers").isEmpty());
        assertEquals("", withdrawn.path("participation").path("feedback").asText());
        assertEquals(
                withdrawn,
                guest(f, one, study + "/submit", answers(1, "submit"), one.csrf(), ORIGIN, 200)
                        .data());
        assertEquals(
                0,
                result(HttpMethod.GET, endpoint(f) + "/" + study + "/summary", admin, null)
                        .path("submittedCount")
                        .asInt());
        result(
                HttpMethod.POST,
                endpoint(f) + "/" + study + "/_delete",
                admin,
                mutation(2, "delete"));
        var missing =
                guest(f, one, study + "/submit", answers(1, "submit"), one.csrf(), ORIGIN, 200)
                        .data();
        assertEquals("missing", missing.path("status").asText());
        assertTrue(missing.path("participation").isNull());
        assertEquals(0, count("p_usability_response"));
    }

    @Test
    void currentMemberPermissionsIntersectMenuAndDraftAndResultDatabaseScopes() throws Exception {
        Fixture f = fixture("fixed", false), foreign = fixture("fixed", false);
        String study = createOpen(f, "permissions");
        Actor viewer = actor(List.of("query")),
                noQuery = actor(List.of("save")),
                outsider = actor(List.of("query", "save"));
        member(f.project(), viewer.id(), "viewer");
        member(f.project(), noQuery.id(), "editor");
        assertEquals(
                1,
                result(HttpMethod.POST, endpoint(f) + "/_query", viewer.token(), Map.of())
                        .path("total")
                        .asInt());
        call(HttpMethod.POST, endpoint(f), viewer.token(), definition("viewer-create"), 403);
        call(HttpMethod.POST, endpoint(f) + "/_query", noQuery.token(), Map.of(), 403);
        call(HttpMethod.POST, endpoint(f) + "/_query", outsider.token(), Map.of(), 404);
        call(HttpMethod.GET, endpoint(foreign) + "/" + study, admin, null, 404);
        call(
                HttpMethod.POST,
                endpoint(f) + "/" + study + "/_close",
                viewer.token(),
                mutation(1, "viewer-close"),
                403);
        var browser = unverified(f);
        guest(f, browser, study + "/consent", consent("consent"), browser.csrf(), ORIGIN, 200);
        guest(f, browser, study + "/submit", answers(0, "submit"), browser.csrf(), ORIGIN, 200);
        JsonNode page =
                result(
                        HttpMethod.POST,
                        endpoint(f) + "/" + study + "/responses/_query",
                        viewer.token(),
                        Map.of());
        assertEquals(1, page.path("total").asInt());
        call(
                HttpMethod.GET,
                endpoint(foreign)
                        + "/"
                        + study
                        + "/responses/"
                        + page.path("data").get(0).path("id").asText(),
                admin,
                null,
                404);
        database.sql("delete from p_project_member where project_id=:p and user_id=:u")
                .bind("p", f.project())
                .bind("u", viewer.id())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.GET, endpoint(f) + "/" + study + "/summary", viewer.token(), null, 404);
        client.get()
                .uri(endpoint(f) + "/" + study + "/summary")
                .cookie("prototype-review", browser.cookie())
                .exchange()
                .expectStatus()
                .isUnauthorized();
        var forged = answers(1, "spoof");
        forged.put("guestId", "other");
        guest(f, browser, study + "/progress", forged, browser.csrf(), ORIGIN, 400);
        // 当前访客停用先于已成功回执重放，不能用旧键绕开当前身份。
        database.sql("update p_review_unverified_guest set enabled=false where id=:id")
                .bind("id", browser.guest())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        guest(f, browser, study + "/submit", answers(0, "submit"), browser.csrf(), ORIGIN, 403);
        assertEquals(
                1,
                result(HttpMethod.GET, endpoint(f) + "/" + study + "/summary", admin, null)
                        .path("submittedCount")
                        .asInt());
    }

    @Test
    void commentDisabledAndLatestExactRevisionCanResearchWithoutGrantingCommentAccess()
            throws Exception {
        Fixture f = fixture("latest", false);
        String study = createOpen(f, "latest");
        Browser browser = unverified(f);
        guest(f, browser, study + "/consent", consent("consent"), browser.csrf(), ORIGIN, 200);
        guest(f, browser, study + "/submit", answers(0, "submit"), browser.csrf(), ORIGIN, 200);
        assertThrows(
                RuntimeException.class,
                () ->
                        review.requireVisitor(
                                        f.token(), f.revision(), f.accessKey(), browser.cookie())
                                .block(TIMEOUT));
        assertNotNull(
                review.requireStudyVisitor(f.token(), f.revision(), f.accessKey(), browser.cookie())
                        .block(TIMEOUT));
        database.sql("update p_review_session set verification_level='unknown' where guest_id=:id")
                .bind("id", browser.guest())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        guest(f, browser, study + "/submit", answers(0, "submit"), browser.csrf(), ORIGIN, 403);
        database.sql(
                        "update p_review_session set verification_level='unverified' where guest_id=:id")
                .bind("id", browser.guest())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_share set review_allow_unverified=false where id=:id")
                .bind("id", f.share())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        guest(f, browser, study + "/submit", answers(0, "submit"), browser.csrf(), ORIGIN, 403);
        assertEquals(1, count("p_usability_response"));
    }

    @Test
    void emailIdentityRemainsCanonicalAndHistoricalUnverifiedCleanupRetainsSubmittedResults()
            throws Exception {
        Fixture f = fixture("fixed", false);
        String study = createOpen(f, "history");
        Browser low = unverified(f), email = verified(f);
        for (Browser browser : List.of(low, email)) {
            guest(f, browser, study + "/consent", consent("consent"), browser.csrf(), ORIGIN, 200);
            guest(f, browser, study + "/submit", answers(0, "submit"), browser.csrf(), ORIGIN, 200);
        }
        database.sql("update p_review_session set verification_level=null where guest_id=:id")
                .bind("id", email.guest())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertEquals(
                "submitted",
                guest(f, email, study + "/context", Map.of(), null, ORIGIN, 200)
                        .data()
                        .path("participation")
                        .path("status")
                        .asText());
        database.sql("update p_review_unverified_guest set expires_at=1 where id=:id")
                .bind("id", low.guest())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_review_session set expires_at=1 where guest_id=:id")
                .bind("id", low.guest())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        review.cleanupExpired().block(TIMEOUT);
        assertEquals(0, count("p_review_unverified_guest"));
        assertEquals(2, count("p_usability_response"));
        assertEquals(
                2,
                result(HttpMethod.GET, endpoint(f) + "/" + study + "/summary", admin, null)
                        .path("submittedCount")
                        .asInt());
        guest(f, low, study + "/submit", answers(0, "submit"), low.csrf(), ORIGIN, 403);
    }

    @Test
    void concurrentConsentAndCasCapsAreAuthoritativeAndReceiptFailureRollsBack() throws Exception {
        Fixture f = fixture("fixed", false);
        String study = createOpen(f, "race");
        Browser first = unverified(f);
        var one =
                CompletableFuture.supplyAsync(
                        () ->
                                guest(
                                                f,
                                                first,
                                                study + "/consent",
                                                consent("same"),
                                                first.csrf(),
                                                ORIGIN,
                                                200)
                                        .data());
        var two =
                CompletableFuture.supplyAsync(
                        () ->
                                guest(
                                                f,
                                                first,
                                                study + "/consent",
                                                consent("same"),
                                                first.csrf(),
                                                ORIGIN,
                                                200)
                                        .data());
        assertEquals(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        assertEquals(1, count("p_usability_response"));
        var a =
                CompletableFuture.supplyAsync(
                        () -> guestStatus(f, first, study + "/progress", answers(0, "a")));
        var b =
                CompletableFuture.supplyAsync(
                        () -> guestStatus(f, first, study + "/progress", answers(0, "b")));
        assertEquals(
                List.of(200, 409),
                List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        // 伪造数据库故障必须连同答卷变化回滚；测试触发器不进入产品。
        long receipts = count("p_creation_receipt");
        sql(
                "create function reject_study_receipt_fixture() returns trigger language plpgsql as $$ begin raise exception 'fixture failure'; end $$");
        sql(
                "create trigger reject_study_receipt_fixture before insert on p_creation_receipt for each row execute function reject_study_receipt_fixture()");
        try {
            guest(f, first, study + "/submit", answers(1, "rollback"), first.csrf(), ORIGIN, 500);
            assertEquals(receipts, count("p_creation_receipt"));
            assertEquals(
                    0,
                    number(
                            "select count(*) as value from p_usability_response where status='submitted'"));
        } finally {
            sql("drop trigger reject_study_receipt_fixture on p_creation_receipt");
            sql("drop function reject_study_receipt_fixture()");
        }
        guest(f, first, study + "/submit", answers(1, "rollback"), first.csrf(), ORIGIN, 200);
        sql(
                "insert into p_usability_response(id,project_id,version_id,revision_id,study_id,share_id,guest_id,verification_level,content_version,status,consented_at,task_answers,answers,feedback,config_version,created_at,updated_at) select 'historical-'||s,project_id,version_id,revision_id,study_id,share_id,'historical-'||s,verification_level,content_version,'withdrawn',consented_at,'[]','[]','',0,created_at,updated_at from p_usability_response cross join generate_series(1,198) s limit 198");
        Browser next = unverified(f), last = unverified(f);
        var c =
                CompletableFuture.supplyAsync(
                        () -> guestStatus(f, next, study + "/consent", consent("cap-a")));
        var d =
                CompletableFuture.supplyAsync(
                        () -> guestStatus(f, last, study + "/consent", consent("cap-b")));
        assertEquals(
                List.of(200, 409),
                List.of(c.get(30, TimeUnit.SECONDS), d.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        assertEquals(200, count("p_usability_response"));
        assertEquals(
                1,
                result(HttpMethod.GET, endpoint(f) + "/" + study + "/summary", admin, null)
                        .path("submittedCount")
                        .asInt());
        for (int i = 1; i < 9; i++)
            result(HttpMethod.POST, endpoint(f), admin, definition("study-" + i));
        var e =
                CompletableFuture.supplyAsync(
                        () -> memberStatus(HttpMethod.POST, endpoint(f), definition("overflow-a")));
        var g =
                CompletableFuture.supplyAsync(
                        () -> memberStatus(HttpMethod.POST, endpoint(f), definition("overflow-b")));
        assertEquals(
                List.of(200, 409),
                List.of(e.get(30, TimeUnit.SECONDS), g.get(30, TimeUnit.SECONDS)).stream()
                        .sorted()
                        .toList());
        assertEquals(10, count("p_usability_study"));
    }

    @Test
    void draftValidationExactPagesOpenFreezeAndStableDatabasePages() throws Exception {
        Fixture f = fixture("fixed", false);
        var empty = definition("empty");
        empty.put("tasks", List.of());
        String draft = result(HttpMethod.POST, endpoint(f), admin, empty).path("id").asText();
        call(
                HttpMethod.POST,
                endpoint(f) + "/" + draft + "/_open",
                admin,
                mutation(0, "bad-open"),
                400);
        var invalid = definition("wrong-page");
        invalid.put(
                "tasks",
                List.of(
                        Map.of(
                                "id",
                                "t",
                                "title",
                                "Task",
                                "steps",
                                List.of(Map.of("id", "s", "pageId", "foreign")))));
        call(HttpMethod.POST, endpoint(f), admin, invalid, 404);
        var updated = definition("correct");
        updated.put("expectedVersion", 0);
        result(HttpMethod.PUT, endpoint(f) + "/" + draft, admin, updated);
        result(HttpMethod.POST, endpoint(f) + "/" + draft + "/_open", admin, mutation(1, "open"));
        updated.put("clientRequestId", "frozen");
        updated.put("expectedVersion", 2);
        call(HttpMethod.PUT, endpoint(f) + "/" + draft, admin, updated, 409);
        for (int i = 0; i < 3; i++)
            result(HttpMethod.POST, endpoint(f), admin, definition("draft-" + i));
        sql("update p_usability_study set updated_at=10");
        var ids = new ArrayList<String>();
        for (int i = 0; i < 4; i++)
            ids.add(
                    result(
                                    HttpMethod.POST,
                                    endpoint(f) + "/_query",
                                    admin,
                                    Map.of("pageIndex", i, "pageSize", 1))
                            .path("data")
                            .get(0)
                            .path("id")
                            .asText());
        assertEquals(ids.stream().sorted().toList(), ids);
        assertEquals(4, ids.stream().distinct().count());
        assertEquals(
                1,
                guest(f, null, "_query", Map.of(), null, ORIGIN, 200).data().path("total").asInt());
        assertEquals(
                0,
                guest(
                                f,
                                null,
                                "_query",
                                Map.of("pageIndex", 10000, "pageSize", 1),
                                null,
                                ORIGIN,
                                200)
                        .data()
                        .path("data")
                        .size());
    }

    @Test
    void softDeleteRestorePreservesResearchAndPermanentVersionOrProjectPurgeIsAtomic()
            throws Exception {
        Fixture f = fixture("fixed", false);
        String study = createOpen(f, "retention");
        Browser one = unverified(f);
        guest(f, one, study + "/consent", consent("consent"), one.csrf(), ORIGIN, 200);
        result(HttpMethod.DELETE, "/prototype-hub/versions/" + f.version(), admin, null);
        assertEquals(1, count("p_usability_study"));
        assertEquals(1, count("p_usability_response"));
        call(HttpMethod.GET, endpoint(f) + "/" + study, admin, null, 404);
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + f.version() + "/_restore",
                admin,
                null);
        assertEquals(
                study,
                result(HttpMethod.GET, endpoint(f) + "/" + study, admin, null).path("id").asText());
        result(HttpMethod.DELETE, "/prototype-hub/versions/" + f.version(), admin, null);
        result(HttpMethod.DELETE, "/prototype-hub/projects/" + f.project(), admin, null);
        assertEquals(1, count("p_usability_response"));
        result(
                HttpMethod.POST,
                "/prototype-hub/projects/" + f.project() + "/_restore",
                admin,
                null);
        assertEquals(1, count("p_usability_study"));
        result(
                HttpMethod.POST,
                "/prototype-hub/versions/" + f.version() + "/_restore",
                admin,
                null);
        result(HttpMethod.DELETE, "/prototype-hub/versions/" + f.version(), admin, null);
        age("p_version", f.version());
        sql(
                "create table f34_purge_blocker(study_id varchar(64) references p_usability_study(id))");
        try {
            sql("insert into f34_purge_blocker select id from p_usability_study");
            long receipts = count("p_creation_receipt");
            assertThrows(
                    RuntimeException.class,
                    () -> retention.purgeVersion(f.version()).block(TIMEOUT));
            assertEquals(1, count("p_usability_study"));
            assertEquals(1, count("p_usability_response"));
            assertEquals(receipts, count("p_creation_receipt"));
        } finally {
            sql("drop table f34_purge_blocker");
        }
        assertTrue(retention.purgeVersion(f.version()).block(TIMEOUT));
        assertEquals(0, count("p_usability_study"));
        assertEquals(0, count("p_usability_response"));
        cleanup.retryPending().block(TIMEOUT);
        Fixture orphan = fixture("fixed", false);
        String orphanStudy = createOpen(orphan, "orphan");
        Browser guest = unverified(orphan);
        guest(
                orphan,
                guest,
                orphanStudy + "/consent",
                consent("orphan-consent"),
                guest.csrf(),
                ORIGIN,
                200);
        database.sql("delete from p_version where id=:id")
                .bind("id", orphan.version())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        age("p_project", orphan.project());
        assertTrue(retention.purgeProject(orphan.project()).block(TIMEOUT));
        assertEquals(0, count("p_usability_study"));
        assertEquals(0, count("p_usability_response"));
    }

    private Fixture fixture(String mode, boolean comments) throws Exception {
        String project =
                result(
                                HttpMethod.POST,
                                "/prototype-hub/projects",
                                admin,
                                Map.of("name", "Research fixture"))
                        .path("id")
                        .asText();
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.html"));
            zip.write("<html><body>Research</body></html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var parts = new MultipartBodyBuilder();
        parts.part("versionName", "R1");
        parts.part(
                        "file",
                        new ByteArrayResource(bytes.toByteArray()) {
                            @Override
                            public String getFilename() {
                                return "site.zip";
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
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version.path("id").asText() + "/shares",
                        admin,
                        Map.of("revisionMode", mode));
        result(
                HttpMethod.PUT,
                "/prototype-hub/shares/" + share.path("id").asText() + "/review",
                admin,
                Map.of(
                        "enabled",
                        true,
                        "allowUnverified",
                        true,
                        "allowComment",
                        comments,
                        "expectedVersion",
                        1));
        JsonNode grant =
                result(
                        HttpMethod.GET,
                        "/prototype-hub/public/shares/" + share.path("token").asText(),
                        null,
                        null);
        return new Fixture(
                project,
                version.path("id").asText(),
                version.path("currentRevisionId").asText(),
                share.path("id").asText(),
                share.path("token").asText(),
                grant.path("accessKey").asText());
    }

    private String createOpen(Fixture f, String key) {
        String id =
                result(HttpMethod.POST, endpoint(f), admin, definition(key)).path("id").asText();
        result(
                HttpMethod.POST,
                endpoint(f) + "/" + id + "/_open",
                admin,
                mutation(0, key + "-open"));
        return id;
    }

    private Map<String, Object> definition(String key) {
        var m = new LinkedHashMap<String, Object>();
        m.put("title", "Research");
        m.put("introduction", "Study information");
        m.put("consentText", "I agree to this research");
        m.put("clientRequestId", key);
        m.put(
                "tasks",
                List.of(
                        Map.of(
                                "id",
                                "t",
                                "title",
                                "Task",
                                "steps",
                                List.of(
                                        Map.of(
                                                "id",
                                                "s",
                                                "pageId",
                                                PAGE,
                                                "narration",
                                                "Open page")))));
        m.put(
                "questions",
                List.of(
                        Map.of(
                                "id",
                                "choice",
                                "prompt",
                                "Choice",
                                "type",
                                "singleChoice",
                                "required",
                                true,
                                "options",
                                List.of(
                                        Map.of("id", "a", "label", "A"),
                                        Map.of("id", "b", "label", "B"))),
                        Map.of(
                                "id",
                                "rating",
                                "prompt",
                                "Rate",
                                "type",
                                "rating",
                                "required",
                                true,
                                "options",
                                List.of()),
                        Map.of(
                                "id",
                                "text",
                                "prompt",
                                "Private text",
                                "type",
                                "text",
                                "required",
                                false,
                                "options",
                                List.of())));
        return m;
    }

    private Map<String, Object> answers(long expected, String key) {
        var m = new LinkedHashMap<String, Object>(withdraw(expected, key));
        m.put(
                "taskAnswers",
                List.of(Map.of("taskId", "t", "outcome", "completed", "feedback", "PRIVATE task")));
        m.put(
                "answers",
                List.of(
                        Map.of("questionId", "choice", "optionId", "a"),
                        Map.of("questionId", "rating", "rating", 4),
                        Map.of("questionId", "text", "text", "PRIVATE answer")));
        m.put("feedback", "PRIVATE overall");
        return m;
    }

    private Map<String, Object> consent(String key) {
        return Map.of("contentVersion", 1, "agreed", true, "clientRequestId", key);
    }

    private Map<String, Object> withdraw(long version, String key) {
        return Map.of("contentVersion", 1, "expectedVersion", version, "clientRequestId", key);
    }

    private Map<String, Object> mutation(long version, String key) {
        return Map.of("expectedVersion", version, "clientRequestId", key);
    }

    private String endpoint(Fixture f) {
        return "/prototype-hub/versions/"
                + f.version()
                + "/revisions/"
                + f.revision()
                + "/usability-studies";
    }

    private Browser unverified(Fixture f) {
        Browser context = identity(f, null, "context", Map.of(), null, 200);
        return identity(
                f, context, "unverified", Map.of("displayName", "Visitor"), context.csrf(), 200);
    }

    private Browser verified(Fixture f) throws InterruptedException {
        Browser context = identity(f, null, "context", Map.of(), null, 200);
        Browser pending =
                identity(
                        f,
                        context,
                        "challenges",
                        Map.of("email", "researcher@example.invalid", "displayName", "Verified"),
                        context.csrf(),
                        200);
        String code = MAILBOX.receive().code();
        return identity(
                f,
                context,
                "verify",
                Map.of("challengeId", pending.data().path("challengeId").asText(), "code", code),
                context.csrf(),
                200);
    }

    private Browser identity(
            Fixture f,
            Browser browser,
            String operation,
            Map<String, Object> fields,
            String csrf,
            int status) {
        return publicCall(f, browser, operation, fields, csrf, ORIGIN, status);
    }

    private Browser guest(
            Fixture f,
            Browser browser,
            String operation,
            Map<String, Object> fields,
            String csrf,
            String origin,
            int status) {
        return publicCall(
                f, browser, "usability-studies/" + operation, fields, csrf, origin, status);
    }

    private Browser publicCall(
            Fixture f,
            Browser browser,
            String operation,
            Map<String, Object> fields,
            String csrf,
            String origin,
            int status) {
        var body = new LinkedHashMap<String, Object>(fields);
        body.put("revisionId", f.revision());
        body.put("accessKey", f.accessKey());
        var request =
                client.post()
                        .uri(
                                "/api/prototype-hub/public/shares/"
                                        + f.token()
                                        + "/review/"
                                        + operation)
                        .contentType(MediaType.APPLICATION_JSON);
        if (browser != null) request.cookie("prototype-review", browser.cookie());
        if (csrf != null) request.header("X-Review-CSRF", csrf);
        if (origin != null) request.header("Origin", origin);
        var response =
                request.bodyValue(body)
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult();
        var cookie = response.getResponseCookies().getFirst("prototype-review");
        return new Browser(
                status == 200
                        ? response.getResponseBody().path("result")
                        : response.getResponseBody(),
                cookie == null ? (browser == null ? null : browser.cookie()) : cookie.getValue());
    }

    private int guestStatus(
            Fixture f, Browser browser, String operation, Map<String, Object> fields) {
        var body = new LinkedHashMap<String, Object>(fields);
        body.put("revisionId", f.revision());
        body.put("accessKey", f.accessKey());
        return client.post()
                .uri(
                        "/api/prototype-hub/public/shares/"
                                + f.token()
                                + "/review/usability-studies/"
                                + operation)
                .contentType(MediaType.APPLICATION_JSON)
                .cookie("prototype-review", browser.cookie())
                .header("Origin", ORIGIN)
                .header("X-Review-CSRF", browser.csrf())
                .bodyValue(body)
                .exchange()
                .expectBody()
                .returnResult()
                .getStatus()
                .value();
    }

    private int memberStatus(HttpMethod method, String path, Object body) {
        return client.method(method)
                .uri(path)
                .header("X-Access-Token", admin)
                .bodyValue(body)
                .exchange()
                .expectBody()
                .returnResult()
                .getStatus()
                .value();
    }

    private JsonNode result(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, 200).path("result");
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return request.exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private Actor actor(List<String> actions) {
        String role = id();
        result(HttpMethod.POST, "/role", admin, Map.of("id", role, "name", "Research role"));
        result(
                HttpMethod.PATCH,
                "/autz-setting",
                admin,
                Map.of(
                        "id",
                        id(),
                        "permission",
                        "prototype-hub-version",
                        "dimensionType",
                        "role",
                        "dimensionTarget",
                        role,
                        "actions",
                        actions,
                        "state",
                        1));
        String name = "research-" + role;
        String user =
                result(
                                HttpMethod.POST,
                                "/user/detail/_create",
                                admin,
                                Map.of(
                                        "name",
                                        "Research member",
                                        "username",
                                        name,
                                        "password",
                                        PASSWORD))
                        .path("id")
                        .asText();
        result(HttpMethod.POST, "/role/" + role + "/users/_bind", admin, List.of(user));
        return new Actor(user, login(name));
    }

    private void member(String project, String user, String role) {
        var row = new PrototypeProjectMemberEntity();
        row.setId(id());
        row.setProjectId(project);
        row.setUserId(user);
        row.setRole(role);
        row.setCreateTime(1L);
        members.insert(row).block(TIMEOUT);
    }

    private String login(String user) {
        return result(
                        HttpMethod.POST,
                        "/authorize/login",
                        null,
                        Map.of("username", user, "password", PASSWORD))
                .path("token")
                .asText();
    }

    private long count(String table) {
        return number("select count(*) as value from " + table);
    }

    private long number(String statement) {
        return database.sql(statement)
                .map((r, m) -> ((Number) r.get("value")).longValue())
                .one()
                .block(TIMEOUT);
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(TIMEOUT);
    }

    private void age(String table, String id) {
        database.sql("update " + table + " set deleted_time=:time where id=:id")
                .bind(
                        "time",
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 1000)
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-study-http-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static String id() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private record Actor(String id, String token) {}

    private record Fixture(
            String project,
            String version,
            String revision,
            String share,
            String token,
            String accessKey) {}

    private record Browser(JsonNode data, String cookie) {
        String csrf() {
            return data.path("csrfToken").asText();
        }

        String guest() {
            return data.path("session").path("guestId").asText();
        }
    }
}
