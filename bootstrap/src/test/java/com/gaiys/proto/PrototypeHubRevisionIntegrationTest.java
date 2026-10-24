package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectService;
import com.gaiys.proto.prototypehub.service.PrototypeRevisionService;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService;
import com.gaiys.proto.prototypehub.service.PrototypeUploadService;
import com.gaiys.proto.prototypehub.service.PrototypeVersionService;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hswebframework.web.authorization.token.ParsedToken;
import org.hswebframework.web.authorization.token.SimpleParsedToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;

/** 不可变快照的真实 HTTP、单连接 PostgreSQL、事务竞争与文件生命周期边界。 */
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
class PrototypeHubRevisionIntegrationTest {
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
        properties.add("spring.r2dbc.pool.max-size", () -> 1);
        properties.add("spring.r2dbc.pool.initial-size", () -> 1);
        properties.add("proto.admin.password", () -> PASSWORD);
        properties.add("prototype-hub.storage.root", FILES::toString);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired PrototypeUploadService uploads;
    @Autowired PrototypeVersionService versions;
    @Autowired PrototypeRevisionService revisions;
    @Autowired FileCleanupService cleanup;
    @Autowired ObjectMapper mapper;
    @Autowired PrototypeProjectService projects;
    @Autowired PrototypeProjectAccessService access;
    @Autowired PrototypeCreationReceiptService receipts;
    @Autowired PrototypeStorageService storage;
    @Autowired SecureZipExtractor extractor;
    @Autowired PrototypeManifestBuilder manifests;
    @Autowired FileWork work;
    @Autowired TransactionalOperator transactions;
    private String admin;

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-hub-revision-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @BeforeEach
    void isolate() throws IOException {
        PrototypeHubTestData.clear(database);
        SecureZipExtractor.deleteRecursively(FILES);
        Files.createDirectories(FILES);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin =
                call(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", "admin", "password", PASSWORD),
                                200)
                        .path("result")
                        .path("token")
                        .asText();
    }

    @AfterAll
    static void removeFiles() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    @Test
    void replacementPreservesHistorySnapshotMetadataManifestPagesSharesAndCreationReceipt()
            throws Exception {
        String project = project();
        byte[] firstZip = archive("R1");
        JsonNode first = upload(project, null, firstZip, "first-key", null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        JsonNode oldSession = result(HttpMethod.POST, version(id) + "/preview-session", null);
        JsonNode share = result(HttpMethod.POST, version(id) + "/shares", Map.of());
        JsonNode oldManifest = manifest(r1);
        String oldJson = revisions.findById(r1).block(TIMEOUT).getManifestJson();
        assertManifest(r1);
        JsonNode second = upload(project, id, archive("R2"), null, r1, 200);
        String r2 = second.path("revisionId").asText();
        assertNotEquals(r1, r2);
        assertEquals(r2, second.path("currentRevisionId").asText());
        assertEquals(2, second.path("revisionCount").asInt());
        assertEquals(1, count("p_version"));
        assertEquals(2, count("p_revision"));
        assertEquals(0, count("p_file_cleanup"));
        assertTrue(runtime(oldSession).contains("R1"));
        assertFalse(runtime(oldSession).contains("R2"));
        // 刷新固定修订深链会重新签发凭证，不能因 current 已变而漂移到 R2。
        JsonNode reopenedR1 = result(HttpMethod.POST, revision(id, r1) + "/preview-session", null);
        assertTrue(runtime(reopenedR1).contains("R1"));
        assertFalse(runtime(reopenedR1).contains("R2"));
        assertEquals(
                r2,
                result(HttpMethod.GET, version(id) + "/current-revision", null)
                        .path("id")
                        .asText());
        assertTrue(
                runtime(result(HttpMethod.POST, revision(id, r2) + "/preview-session", null))
                        .contains("R2"));
        JsonNode publicShare =
                call(
                                HttpMethod.GET,
                                "/prototype-hub/public/shares/" + share.path("token").asText(),
                                null,
                                null,
                                200)
                        .path("result");
        assertEquals(r1, publicShare.path("revisionId").asText());
        assertTrue(runtime(publicShare).contains("R1"));
        JsonNode replay = upload(project, null, firstZip, "first-key", null, 200);
        assertEquals(r1, replay.path("revisionId").asText());
        assertEquals(r2, replay.path("currentRevisionId").asText());
        assertEquals("R1", replay.path("versionName").asText());
        assertEquals("description R1", replay.path("description").asText());
        assertEquals(2, replay.path("revisionCount").asInt());
        assertEquals(oldJson, revisions.findById(r1).block(TIMEOUT).getManifestJson());
        assertEquals(oldManifest, manifest(r1));
        assertManifest(r2);
        JsonNode firstPage = result(HttpMethod.GET, revision(id, r1) + "/pages", null).get(0);
        assertEquals(
                sha(firstPage.path("path").asText().getBytes(StandardCharsets.UTF_8)),
                firstPage.path("pageId").asText());
        assertFalse(result(HttpMethod.GET, revision(id, r1), null).has("storagePath"));
        assertFalse(result(HttpMethod.GET, revision(id, r1), null).has("manifestJson"));
        assertEquals(
                1,
                result(HttpMethod.GET, "/prototype-hub/versions/_usage?projectId=" + project, null)
                        .path("versions")
                        .path("used")
                        .asInt());
    }

    @Test
    void samePostInjectionBytesProduceSameCanonicalManifestAcrossDifferentRevisions()
            throws Exception {
        String project = project();
        byte[] zip = archive("identical");
        JsonNode first = upload(project, null, zip, null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        JsonNode second = upload(project, id, zip, null, r1, 200);
        String r2 = second.path("revisionId").asText();
        assertNotEquals(r1, r2);
        assertEquals(
                revisions.findById(r1).block(TIMEOUT).getManifestJson(),
                revisions.findById(r2).block(TIMEOUT).getManifestJson());
        assertEquals(
                revisions.findById(r1).block(TIMEOUT).getManifestSha256(),
                revisions.findById(r2).block(TIMEOUT).getManifestSha256());
        assertManifest(r1);
        assertManifest(r2);
    }

    @ParameterizedTest(name = "concurrent pointer compare, explicit={0}")
    @ValueSource(booleans = {false, true})
    void concurrentUploadsCaptureTheSamePointerAndOnlyOneCanCommit(boolean explicit)
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        Sinks.One<Void> firstEntered = Sinks.one(),
                secondEntered = Sinks.one(),
                release = Sinks.one();
        CompletableFuture<Signal<PrototypeVersionEntity>> left =
                asAdmin(
                                uploads.update(
                                        id,
                                        "R2-left",
                                        null,
                                        gatedFile(archive("left"), firstEntered, release),
                                        explicit ? r1 : null))
                        .materialize()
                        .toFuture();
        CompletableFuture<Signal<PrototypeVersionEntity>> right =
                asAdmin(
                                uploads.update(
                                        id,
                                        "R2-right",
                                        null,
                                        gatedFile(archive("right"), secondEntered, release),
                                        explicit ? r1 : null))
                        .materialize()
                        .toFuture();
        try {
            Mono.when(firstEntered.asMono(), secondEntered.asMono()).block(TIMEOUT);
        } finally {
            release.tryEmitEmpty();
        }
        var outcomes = List.of(left.get(), right.get());
        assertEquals(1, outcomes.stream().filter(Signal::isOnNext).count());
        Throwable failure =
                outcomes.stream()
                        .filter(Signal::isOnError)
                        .findFirst()
                        .orElseThrow()
                        .getThrowable();
        assertEquals(
                "PROTOTYPE_HUB_VERSION_CONFLICT",
                assertInstanceOf(PrototypeHubException.class, failure).getCode());
        assertEquals(2, count("p_revision"));
        assertEquals(1, count("p_version"));
        assertEquals(2, directoryCount(project));
        assertManifest(r1);
        JsonNode current = result(HttpMethod.GET, version(id), null);
        assertNotEquals(r1, current.path("currentRevisionId").asText());
        JsonNode conflict = upload(project, id, archive("stale"), null, r1, 409);
        assertEquals("PROTOTYPE_HUB_VERSION_CONFLICT", conflict.path("code").asText());
        assertEquals(2, count("p_revision"));
        assertEquals(2, directoryCount(project));
    }

    @Test
    void historyUsesStableKeysetAndRejectsCrossVersionRevisionOrCursorAndAnonymousAccess()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        JsonNode second = upload(project, id, archive("R2"), null, r1, 200);
        String r2 = second.path("revisionId").asText();
        JsonNode other = upload(project, null, archive("other"), null, null, 200);
        String foreign = other.path("revisionId").asText();
        String crossProject =
                upload(project(), null, archive("another project"), null, null, 200)
                        .path("revisionId")
                        .asText();
        JsonNode page = result(HttpMethod.GET, version(id) + "/revisions?limit=1", null);
        assertEquals(r2, page.get(0).path("id").asText());
        assertEquals(
                r1,
                result(HttpMethod.GET, version(id) + "/revisions?limit=1&after=" + r2, null)
                        .get(0)
                        .path("id")
                        .asText());
        assertTrue(result(HttpMethod.GET, version(id) + "/revisions?after=" + r1, null).isEmpty());
        for (int limit : List.of(0, 101))
            call(HttpMethod.GET, version(id) + "/revisions?limit=" + limit, admin, null, 400);
        for (String foreignRevision : List.of(foreign, crossProject)) {
            call(
                    HttpMethod.GET,
                    version(id) + "/revisions?after=" + foreignRevision,
                    admin,
                    null,
                    404);
            for (String path :
                    List.of(
                            revision(id, foreignRevision),
                            revision(id, foreignRevision) + "/pages"))
                call(HttpMethod.GET, path, admin, null, 404);
            call(
                    HttpMethod.POST,
                    revision(id, foreignRevision) + "/preview-session",
                    admin,
                    null,
                    404);
        }
        call(HttpMethod.GET, revision(id, r1), null, null, 401);
        // A different project member cannot turn a known immutable ID into project access.
        String username = "revision-outsider-" + UUID.randomUUID();
        JsonNode user =
                result(
                        HttpMethod.POST,
                        "/user/detail/_create",
                        Map.of(
                                "username",
                                username,
                                "name",
                                "Revision outsider",
                                "password",
                                PASSWORD));
        result(HttpMethod.POST, "/role/MEMBER/users/_bind", List.of(user.path("id").asText()));
        String outsider =
                call(
                                HttpMethod.POST,
                                "/authorize/login",
                                null,
                                Map.of("username", username, "password", PASSWORD),
                                200)
                        .path("result")
                        .path("token")
                        .asText();
        call(HttpMethod.GET, revision(id, r1), outsider, null, 404);
        call(
                HttpMethod.POST,
                version(id) + "/revision-migration",
                outsider,
                Map.of("apply", false),
                403);
    }

    @Test
    void legacyMigrationIsExplicitReadOnlyUntilApplyAndBindsOnlyAvailableContent()
            throws Exception {
        String project = project();
        byte[] zip = archive("legacy available");
        JsonNode first = upload(project, null, zip, "legacy-key", null, 200);
        String id = first.path("id").asText();
        JsonNode session = result(HttpMethod.POST, version(id) + "/preview-session", null);
        JsonNode share = result(HttpMethod.POST, version(id) + "/shares", Map.of());
        makeLegacy(id);
        String before = databaseSnapshot();
        JsonNode detail = result(HttpMethod.GET, version(id), null);
        assertTrue(detail.path("migrationRequired").asBoolean());
        assertTrue(detail.path("currentRevisionId").isNull());
        assertEquals(
                1,
                result(HttpMethod.GET, "/prototype-hub/versions?projectId=" + project, null)
                        .size());
        for (String suffix : List.of("/current-revision", "/pages", "/revisions"))
            assertEquals(
                    "PROTOTYPE_HUB_REVISION_MIGRATION_REQUIRED",
                    call(HttpMethod.GET, version(id) + suffix, admin, null, 409)
                            .path("code")
                            .asText());
        call(HttpMethod.POST, version(id) + "/shares", admin, Map.of(), 409);
        upload(project, id, archive("blocked"), null, null, 409);
        call(
                HttpMethod.GET,
                "/prototype-hub/public/shares/" + share.path("token").asText(),
                null,
                null,
                404);
        client.get()
                .uri(session.path("previewUrl").asText().substring(4))
                .exchange()
                .expectStatus()
                .isForbidden();
        JsonNode dry =
                result(
                        HttpMethod.POST,
                        version(id) + "/revision-migration",
                        Map.of("apply", false));
        assertTrue(dry.path("migrationRequired").asBoolean());
        assertFalse(dry.path("applied").asBoolean());
        assertEquals(1, dry.path("sharesToBind").asInt());
        assertEquals(1, dry.path("receiptsToBind").asInt());
        assertEquals(before, databaseSnapshot());
        JsonNode applied =
                result(HttpMethod.POST, version(id) + "/revision-migration", Map.of("apply", true));
        String revision = applied.path("revisionId").asText();
        assertFalse(applied.path("migrationRequired").asBoolean());
        assertTrue(applied.path("applied").asBoolean());
        assertEquals(dry.path("manifestSha256"), applied.path("manifestSha256"));
        assertManifest(revision);
        String after = databaseSnapshot();
        JsonNode rerun =
                result(HttpMethod.POST, version(id) + "/revision-migration", Map.of("apply", true));
        assertEquals(revision, rerun.path("revisionId").asText());
        assertEquals(0, rerun.path("sharesToBind").asInt());
        assertEquals(0, rerun.path("receiptsToBind").asInt());
        assertEquals(after, databaseSnapshot());
        assertEquals(
                revision,
                upload(project, null, zip, "legacy-key", null, 200).path("revisionId").asText());
        JsonNode publicShare =
                call(
                                HttpMethod.GET,
                                "/prototype-hub/public/shares/" + share.path("token").asText(),
                                null,
                                null,
                                200)
                        .path("result");
        assertEquals(revision, publicShare.path("revisionId").asText());
        assertTrue(runtime(publicShare).contains("legacy available"));
    }

    @Test
    void migrationFailureRollsBackRevisionPointerAndBindingsWithoutChangingFiles()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("legacy"), "legacy-key", null, 200);
        String id = first.path("id").asText();
        result(HttpMethod.POST, version(id) + "/shares", Map.of());
        makeLegacy(id);
        String before = databaseSnapshot();
        sql(
                "create function reject_revision_binding() returns trigger language plpgsql as"
                        + " 'begin raise exception ''fixture rejection''; end'");
        sql(
                "create trigger reject_revision_binding before update on p_share for each row"
                        + " execute function reject_revision_binding()");
        try {
            call(
                    HttpMethod.POST,
                    version(id) + "/revision-migration",
                    admin,
                    Map.of("apply", true),
                    500);
            assertEquals(before, databaseSnapshot());
            assertEquals(0, count("p_revision"));
            assertEquals(1, directoryCount(project));
        } finally {
            sql("drop trigger reject_revision_binding on p_share");
            sql("drop function reject_revision_binding()");
        }
        result(HttpMethod.POST, version(id) + "/revision-migration", Map.of("apply", true));
        assertEquals(1, count("p_revision"));
    }

    @Test
    void softDeletionRetainsEveryHistoricalPathAndReferencedCandidateCleanupNeverReclaimsIt()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        String oldPath = revisions.findById(r1).block(TIMEOUT).getStoragePath();
        upload(project, id, archive("R2"), null, r1, 200);
        var task = cleanup.enqueueCandidate(project, id, oldPath).block(TIMEOUT);
        cleanup.attempt(task).block(TIMEOUT);
        assertTrue(Files.exists(FILES.resolve(oldPath).resolve("index.html")));
        assertEquals(1, cleanup.findById(task.getId()).block(TIMEOUT).getAttempts());
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        assertEquals(2, count("p_revision"));
        assertEquals(1, count("p_version"));
        assertNotNull(versions.findById(id).block(TIMEOUT).getDeletedTime());
        assertEquals(2, directoryCount(project));
        assertEquals(1, count("p_file_cleanup"));
        sql("update p_file_cleanup set next_attempt=0");
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(2, directoryCount(project));
        assertEquals(1, count("p_file_cleanup"));
        assertTrue(Files.readString(FILES.resolve(oldPath).resolve("index.html")).contains("R1"));
    }

    @Test
    void cleanupConsumesAtMostFiftyTasksAndReleasesCursorBeforeSingleConnectionWrites()
            throws Exception {
        String project = project();
        for (int i = 0; i < 53; i++) {
            String relative = "projects/" + project + "/versions/gc-" + i + "/site";
            Files.createDirectories(FILES.resolve(relative));
            Files.writeString(FILES.resolve(relative).resolve("index.html"), "unreferenced");
            cleanup.enqueueCandidate(project, "gc-" + i, relative).block(TIMEOUT);
        }
        assertEquals(53, count("p_file_cleanup"));
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(3, count("p_file_cleanup"));
        assertEquals(3, directoryCount(project));
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(0, count("p_file_cleanup"));
        assertEquals(0, directoryCount(project));
    }

    @Test
    void softDeletionRetainsMoreThanTwoHundredHistoricalReferencesWithoutQueueingReclamation()
            throws Exception {
        String project = project();
        JsonNode uploaded = upload(project, null, archive("current"), null, null, 200);
        String id = uploaded.path("id").asText();
        var current = revisions.findById(uploaded.path("revisionId").asText()).block(TIMEOUT);
        List<PrototypeRevisionEntity> history = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            var revision = new PrototypeRevisionEntity();
            revision.setId(String.format("history-%04d", i));
            revision.setProjectId(project);
            revision.setVersionId(id);
            revision.setVersionName("Historical " + i);
            revision.setStoragePath("projects/" + project + "/versions/history-" + i + "/site");
            revision.setEntryFile("index.html");
            revision.setManifestJson(current.getManifestJson());
            revision.setManifestSha256(current.getManifestSha256());
            revision.setFileCount(current.getFileCount());
            revision.setTotalBytes(current.getTotalBytes());
            revision.setCreatorId(current.getCreatorId());
            revision.setCreateTime(current.getCreateTime() - i - 1);
            history.add(revision);
            Path site = FILES.resolve(revision.getStoragePath());
            Files.createDirectories(site);
            Files.writeString(site.resolve("index.html"), "historical fixture");
        }
        revisions.insert(Flux.fromIterable(history)).block(TIMEOUT);
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        assertEquals(202, count("p_revision"));
        assertEquals(1, count("p_version"));
        assertEquals(0, count("p_file_cleanup"));
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(202, count("p_revision"));
        assertEquals(202, directoryCount(project));
        assertTrue(Files.exists(FILES.resolve(current.getStoragePath()).resolve("index.html")));
    }

    @Test
    void unclassifiedCleanupTasksNeverDeleteUnreferencedOldDirectories() throws Exception {
        String project = project();
        for (String type : List.of("project", "version", "unknown")) {
            String relative = "projects/" + project + "/versions/legacy-" + type + "/site";
            Files.createDirectories(FILES.resolve(relative));
            Files.writeString(FILES.resolve(relative).resolve("index.html"), "retained old files");
            FileCleanupEntity task = new FileCleanupEntity();
            task.setId(UUID.randomUUID().toString());
            task.setResourceType(type);
            task.setProjectId(project);
            task.setVersionId("legacy-" + type);
            task.setStoragePath(relative);
            task.setCreateTime(System.currentTimeMillis());
            task.setNextAttempt(0L);
            task.setAttempts(0);
            cleanup.insert(task).block(TIMEOUT);
        }
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(3, directoryCount(project));
        assertEquals(3, count("p_file_cleanup"));
        assertTrue(
                cleanup.createQuery().fetch().collectList().block(TIMEOUT).stream()
                        .allMatch(
                                task ->
                                        task.getAttempts() == 1
                                                && task.getNextAttempt()
                                                        > System.currentTimeMillis()));
    }

    @Test
    void cancellationWaitsForAnInterruptIgnoringExtractorBeforeDeletingItsCandidate()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        var gatedExtractor = spy(extractor);
        var observedStorage = spy(storage);
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                extractorFinished = new CountDownLatch(1),
                deletionStarted = new CountDownLatch(1),
                deletionFinished = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            entered.countDown();
                            // File APIs need not stop on interrupt. Model an already-running writer
                            // which
                            // completes only after the owner releases it, then performs real
                            // extraction.
                            boolean waiting = true;
                            while (waiting) {
                                try {
                                    release.await();
                                    waiting = false;
                                } catch (InterruptedException ignored) {
                                    /* Continue until physical I/O may finish. */
                                }
                            }
                            try {
                                return extractor.extract(
                                        invocation.getArgument(0), invocation.getArgument(1));
                            } finally {
                                extractorFinished.countDown();
                            }
                        })
                .when(gatedExtractor)
                .extract(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        doAnswer(
                        invocation ->
                                storage.deleteVersion(
                                                invocation.getArgument(0),
                                                invocation.getArgument(1))
                                        .doOnSubscribe(ignored -> deletionStarted.countDown())
                                        .doFinally(ignored -> deletionFinished.countDown()))
                .when(observedStorage)
                .deleteVersion(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        var service =
                new PrototypeUploadService(
                        projects,
                        versions,
                        observedStorage,
                        gatedExtractor,
                        work,
                        transactions,
                        cleanup,
                        receipts,
                        access,
                        revisions,
                        manifests);
        Sinks.One<Void> fileRelease = Sinks.one();
        fileRelease.tryEmitEmpty();
        var operation =
                asAdmin(
                                service.update(
                                        id,
                                        "cancelled",
                                        null,
                                        gatedFile(archive("cancelled"), Sinks.one(), fileRelease)))
                        .subscribe();
        boolean cleanedWhileWriterWasRunning;
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            operation.dispose();
            cleanedWhileWriterWasRunning = deletionStarted.await(300, TimeUnit.MILLISECONDS);
        } finally {
            operation.dispose();
            release.countDown();
        }
        assertTrue(extractorFinished.await(10, TimeUnit.SECONDS));
        assertTrue(deletionFinished.await(10, TimeUnit.SECONDS));
        assertFalse(cleanedWhileWriterWasRunning, "候选目录清理必须等待物理写入终止，防止删后被重建");
        assertEquals(1, directoryCount(project));
        assertEquals(1, count("p_revision"));
        assertEquals(r1, versions.requireById(id).block(TIMEOUT).getCurrentRevisionId());
        assertManifest(r1);
    }

    @Test
    void cancellationDuringDirectoryPreparationCannotLoseOwnershipOfAnUnemittedResource()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        var observedStorage = spy(storage);
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                preparationFinished = new CountDownLatch(1),
                deletionFinished = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            // Directory preparation has started, but UploadPaths has not been
                            // emitted.
                            // The writer may create its directory after cancellation reaches
                            // usingWhen.
                            entered.countDown();
                            boolean waiting = true;
                            while (waiting) {
                                try {
                                    release.await();
                                    waiting = false;
                                } catch (InterruptedException ignored) {
                                    /* Model non-interruptible preparation. */
                                }
                            }
                            try {
                                return storage.prepareUploadNow(
                                        invocation.getArgument(0),
                                        invocation.getArgument(1),
                                        invocation.getArgument(2));
                            } finally {
                                preparationFinished.countDown();
                            }
                        })
                .when(observedStorage)
                .prepareUploadNow(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        doAnswer(
                        invocation ->
                                storage.deleteVersion(
                                                invocation.getArgument(0),
                                                invocation.getArgument(1))
                                        .doFinally(ignored -> deletionFinished.countDown()))
                .when(observedStorage)
                .deleteVersion(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        var service =
                new PrototypeUploadService(
                        projects,
                        versions,
                        observedStorage,
                        extractor,
                        work,
                        transactions,
                        cleanup,
                        receipts,
                        access,
                        revisions,
                        manifests);
        Sinks.One<Void> fileRelease = Sinks.one();
        fileRelease.tryEmitEmpty();
        var operation =
                asAdmin(
                                service.update(
                                        id,
                                        "cancelled prepare",
                                        null,
                                        gatedFile(
                                                archive("never received"),
                                                Sinks.one(),
                                                fileRelease)))
                        .subscribe();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            operation.dispose();
        } finally {
            operation.dispose();
            release.countDown();
        }
        assertTrue(preparationFinished.await(10, TimeUnit.SECONDS));
        assertTrue(deletionFinished.await(10, TimeUnit.SECONDS));
        assertEquals(1, directoryCount(project));
        assertEquals(1, count("p_revision"));
        assertEquals(r1, versions.requireById(id).block(TIMEOUT).getCurrentRevisionId());
        assertManifest(r1);
    }

    @Test
    void failedDirectoryAcquisitionNeverDeletesAnExistingUnownedDirectory() throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        var observedStorage = spy(storage);
        var collision = new java.util.concurrent.atomic.AtomicReference<Path>();
        doAnswer(
                        invocation -> {
                            Path existing =
                                    FILES.resolve(
                                            "projects/"
                                                    + invocation.getArgument(0)
                                                    + "/versions/"
                                                    + invocation.getArgument(1));
                            Files.createDirectories(existing);
                            Files.writeString(existing.resolve("keep"), "unowned directory");
                            collision.set(existing);
                            return storage.prepareUploadNow(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2));
                        })
                .when(observedStorage)
                .prepareUploadNow(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        var service =
                new PrototypeUploadService(
                        projects,
                        versions,
                        observedStorage,
                        extractor,
                        work,
                        transactions,
                        cleanup,
                        receipts,
                        access,
                        revisions,
                        manifests);
        Sinks.One<Void> fileRelease = Sinks.one();
        fileRelease.tryEmitEmpty();
        assertThrows(
                RuntimeException.class,
                () ->
                        asAdmin(
                                        service.update(
                                                id,
                                                "collision",
                                                null,
                                                gatedFile(
                                                        archive("unused"),
                                                        Sinks.one(),
                                                        fileRelease)))
                                .block(TIMEOUT));
        assertNotNull(collision.get());
        assertEquals("unowned directory", Files.readString(collision.get().resolve("keep")));
        assertEquals(1, count("p_revision"));
        assertEquals(r1, versions.requireById(id).block(TIMEOUT).getCurrentRevisionId());
    }

    @Test
    void storageExportIncludesHistoricalAndLegacyReferencesAndReportsBrokenRevisionAssociations()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), "retention-export", null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        JsonNode share = result(HttpMethod.POST, version(id) + "/shares", Map.of());
        upload(project, id, archive("R2"), null, r1, 200);
        JsonNode legacy = upload(project, null, archive("legacy"), null, null, 200);
        String legacyId = legacy.path("id").asText();
        makeLegacy(legacyId);
        for (String name : List.of("prototype-hub-table-names.sql", "export-storage-manifest.sql"))
            DATABASE.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "tools", name).toAbsolutePath()),
                    "/tmp/" + name);
        String current = versions.requireById(id).block(TIMEOUT).getCurrentRevisionId();
        String legacyPath = versions.requireById(legacyId).block(TIMEOUT).getStoragePath();
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        JsonNode exported = storageExport(null);
        assertTrue(exported.path("referencesComplete").asBoolean());
        assertTrue(exported.path("receiptReferencesChecked").asBoolean());
        assertEquals("immutable-revisions-v1", exported.path("sourceSchema").asText());
        assertEquals(0, exported.path("integrityErrors").asInt());
        assertEquals(3, exported.path("entries").size());
        var paths = new java.util.HashSet<String>();
        exported.path("entries").forEach(row -> paths.add(row.path("storagePath").asText()));
        assertTrue(paths.contains(revisions.findById(r1).block(TIMEOUT).getStoragePath()));
        assertTrue(paths.contains(legacyPath));
        sql(
                "update p_share set revision_id='missing-revision' where id='"
                        + share.path("id").asText()
                        + "'");
        assertEquals(1, storageExport(null).path("integrityErrors").asInt());
        sql(
                "update p_share set revision_id='"
                        + current
                        + "' where id='"
                        + share.path("id").asText()
                        + "'");
        sql("update p_creation_receipt set revision_id='missing-receipt-revision'");
        assertEquals(1, storageExport(null).path("integrityErrors").asInt());
        sql("update p_creation_receipt set revision_id='" + r1 + "'");
        assertEquals(0, storageExport(null).path("integrityErrors").asInt());
        sql("create schema legacy_export");
        sql(
                "create table legacy_export.p_version(id varchar(64),project_id"
                        + " varchar(64),storage_path varchar(1024))");
        JsonNode old = storageExport("legacy_export");
        assertFalse(old.path("referencesComplete").asBoolean());
        assertEquals("legacy-current-only", old.path("sourceSchema").asText());
        assertTrue(old.path("integrityErrors").isNull());
    }

    @Test
    void failedCandidateDeletionIsDurablyQueuedWithoutChangingThePublishedRevision()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1"), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        var failingStorage = spy(storage);
        doAnswer(invocation -> Mono.error(new IOException("fixture deletion failure")))
                .when(failingStorage)
                .deleteVersion(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        var service =
                new PrototypeUploadService(
                        projects,
                        versions,
                        failingStorage,
                        extractor,
                        work,
                        transactions,
                        cleanup,
                        receipts,
                        access,
                        revisions,
                        manifests);
        Sinks.One<Void> release = Sinks.one();
        release.tryEmitEmpty();
        assertThrows(
                RuntimeException.class,
                () ->
                        asAdmin(
                                        service.update(
                                                id,
                                                "invalid zip",
                                                null,
                                                gatedFile(
                                                        new byte[] {1, 2, 3},
                                                        Sinks.one(),
                                                        release)))
                                .block(TIMEOUT));
        assertEquals(1, count("p_file_cleanup"));
        assertEquals(2, directoryCount(project));
        assertEquals(r1, versions.requireById(id).block(TIMEOUT).getCurrentRevisionId());
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(0, count("p_file_cleanup"));
        assertEquals(1, directoryCount(project));
        assertManifest(r1);
    }

    private JsonNode storageExport(String schema) throws Exception {
        var command = new java.util.ArrayList<String>();
        if (schema != null) command.addAll(List.of("env", "PGOPTIONS=-c search_path=" + schema));
        command.addAll(
                List.of(
                        "psql",
                        "-X",
                        "-qAt",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-U",
                        DATABASE.getUsername(),
                        "-d",
                        DATABASE.getDatabaseName(),
                        "-f",
                        "/tmp/export-storage-manifest.sql"));
        var exported = DATABASE.execInContainer(command.toArray(String[]::new));
        assertEquals(0, exported.getExitCode(), exported.getStderr());
        return mapper.readTree(exported.getStdout());
    }

    private String project() {
        return result(
                        HttpMethod.POST,
                        "/prototype-hub/projects",
                        Map.of("name", "Revision project"))
                .path("id")
                .asText();
    }

    private String version(String id) {
        return "/prototype-hub/versions/" + id;
    }

    private String revision(String id, String rid) {
        return version(id) + "/revisions/" + rid;
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        WebTestClient.RequestBodySpec request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        WebTestClient.ResponseSpec response =
                body == null ? request.exchange() : request.bodyValue(body).exchange();
        JsonNode result =
                response.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(result);
        return result;
    }

    private JsonNode result(HttpMethod method, String path, Object body) {
        return call(method, path, admin, body, 200).path("result");
    }

    private JsonNode upload(
            String project, String id, byte[] zip, String key, String expected, int status) {
        var parts = new MultipartBodyBuilder();
        String label;
        try (var archive =
                new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            archive.getNextEntry();
            label =
                    new String(archive.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("<html><body>", "")
                            .replace("</body></html>", "");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        parts.part("versionName", label);
        parts.part("description", "description " + label);
        if (expected != null) parts.part("expectedRevisionId", expected);
        parts.part(
                        "file",
                        new ByteArrayResource(zip) {
                            @Override
                            public String getFilename() {
                                return "revision.zip";
                            }
                        })
                .contentType(MediaType.APPLICATION_OCTET_STREAM);
        var request =
                client.method(id == null ? HttpMethod.POST : HttpMethod.PUT)
                        .uri(
                                id == null
                                        ? "/prototype-hub/projects/" + project + "/versions"
                                        : version(id))
                        .header("X-Access-Token", admin)
                        .contentType(MediaType.MULTIPART_FORM_DATA);
        if (key != null) request.header("Idempotency-Key", key);
        JsonNode body =
                request.body(BodyInserters.fromMultipartData(parts.build()))
                        .exchange()
                        .expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(body);
        return status == 200 ? body.path("result") : body;
    }

    private JsonNode manifest(String revision) throws Exception {
        return mapper.readTree(revisions.findById(revision).block(TIMEOUT).getManifestJson());
    }

    private void assertManifest(String revision) throws Exception {
        var record = revisions.findById(revision).block(TIMEOUT);
        assertNotNull(record);
        assertEquals(
                sha(record.getManifestJson().getBytes(StandardCharsets.UTF_8)),
                record.getManifestSha256());
        JsonNode manifest = mapper.readTree(record.getManifestJson());
        assertFalse(manifest.has("revisionId"));
        assertFalse(manifest.has("createdAt"));
        assertEquals(1, manifest.path("formatVersion").asInt());
        assertEquals(record.getFileCount().intValue(), manifest.path("files").size());
        long total = 0;
        String previous = "";
        for (JsonNode file : manifest.path("files")) {
            String relative = file.path("path").asText();
            assertTrue(previous.compareTo(relative) < 0);
            previous = relative;
            Path path = FILES.resolve(record.getStoragePath()).resolve(relative);
            byte[] actual = Files.readAllBytes(path);
            assertEquals(actual.length, file.path("size").asInt());
            assertEquals(sha(actual), file.path("sha256").asText());
            assertFalse(file.path("mediaType").asText().isBlank());
            total += actual.length;
        }
        assertEquals(total, record.getTotalBytes());
        assertTrue(
                Files.readString(FILES.resolve(record.getStoragePath()).resolve("index.html"))
                        .contains("inspector.js"));
    }

    private void makeLegacy(String id) {
        database.sql("update p_version set current_revision_id=null where id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_share set revision_id=null where version_id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_preview_session set revision_id=null where version_id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("update p_creation_receipt set revision_id=null where resource_id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        database.sql("delete from p_revision where version_id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
    }

    private String databaseSnapshot() {
        return database.sql(
                        "select md5(jsonb_build_array((select jsonb_agg(to_jsonb(v) order by id)"
                                + " from p_version v),(select jsonb_agg(to_jsonb(r) order by id) from"
                                + " p_revision r),(select jsonb_agg(to_jsonb(s) order by id) from"
                                + " p_share s),(select jsonb_agg(to_jsonb(c) order by id) from"
                                + " p_creation_receipt c))::text) as hash")
                .map((row, metadata) -> row.get("hash", String.class))
                .one()
                .block(TIMEOUT);
    }

    private long count(String table) {
        return database.sql("select count(*) as count from " + table)
                .map((row, metadata) -> row.get("count", Long.class))
                .one()
                .block(TIMEOUT);
    }

    private long directoryCount(String project) throws IOException {
        try (var paths = Files.list(FILES.resolve("projects/" + project + "/versions"))) {
            return paths.count();
        }
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(TIMEOUT);
    }

    private String runtime(JsonNode grant) {
        return client.get()
                .uri(grant.path("previewUrl").asText().substring(4))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private <T> Mono<T> asAdmin(Mono<T> operation) {
        return operation.contextWrite(
                context ->
                        context.put(
                                ParsedToken.class,
                                SimpleParsedToken.of(
                                        "default",
                                        admin,
                                        (headers, token) -> headers.set("X-Access-Token", token))));
    }

    private static FilePart gatedFile(
            byte[] zip, Sinks.One<Void> entered, Sinks.One<Void> release) {
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("revision.zip");
        when(file.content())
                .thenAnswer(
                        ignored ->
                                Flux.defer(
                                        () -> {
                                            entered.tryEmitEmpty();
                                            return release.asMono()
                                                    .thenMany(
                                                            Flux.just(
                                                                    new DefaultDataBufferFactory()
                                                                            .wrap(zip)));
                                        }));
        return file;
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] archive(String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry :
                    List.of(
                            Map.entry("index.html", "<html><body>" + content + "</body></html>"),
                            Map.entry(
                                    "nested/中文 页面.html",
                                    "<html><body>nested " + content + "</body></html>"),
                            Map.entry("assets/style.css", "body{color:#123456}"))) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
