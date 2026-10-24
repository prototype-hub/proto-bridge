package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.FileCleanupEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeInstallationEntity;
import com.gaiys.proto.prototypehub.entity.PrototypePreviewSessionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService;
import com.gaiys.proto.prototypehub.service.PrototypeInstallationService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectService;
import com.gaiys.proto.prototypehub.service.PrototypeRetentionService;
import com.gaiys.proto.prototypehub.service.PrototypeRevisionService;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService;
import com.gaiys.proto.prototypehub.service.PrototypeUploadService;
import com.gaiys.proto.prototypehub.service.PrototypeVersionService;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactories;
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
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
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
import reactor.core.scheduler.Schedulers;

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
    @Autowired PrototypeRetentionService retention;
    @Autowired PrototypeInstallationService installations;
    @Autowired ReactiveRepository<PrototypeProjectMemberEntity, String> memberRows;
    @Autowired ReactiveRepository<PrototypePreviewSessionEntity, String> sessionRows;
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
        installations
                .createUpdate()
                .setNull(PrototypeInstallationEntity::getRetentionDeletedTime)
                .setNull(PrototypeInstallationEntity::getRetentionVersionId)
                .setNull(PrototypeInstallationEntity::getRetentionProjectTime)
                .setNull(PrototypeInstallationEntity::getRetentionProjectId)
                .where(PrototypeInstallationEntity::getId, "default")
                .execute()
                .block(TIMEOUT);
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
    void comparisonPinsTwoExplicitRevisionsAfterCurrentChangesAndReadsNoRuntimeFiles()
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("R1", 203), null, null, 200);
        String id = first.path("id").asText(), r1 = first.path("revisionId").asText();
        String r2 =
                upload(project, id, archive("R2", 203), null, r1, 200).path("revisionId").asText();
        String r3 = upload(project, id, archive("R3"), null, r2, 200).path("revisionId").asText();
        String before = databaseSnapshot();
        String path = comparison(id, r1, r2);
        JsonNode compared =
                result(
                        HttpMethod.GET,
                        path
                                + "&pagesPageSize=1&pagesPageIndex=1&filesPageSize=100&filesPageIndex=2",
                        null);
        assertEquals(r1, compared.path("left").path("id").asText());
        assertEquals(r2, compared.path("right").path("id").asText());
        assertEquals("R1", compared.path("left").path("versionName").asText());
        assertEquals("R2", compared.path("right").path("versionName").asText());
        assertEquals(2, compared.path("summary").path("pages").path("changed").asInt());
        assertEquals(205, compared.path("summary").path("files").path("changed").asInt());
        assertEquals(2, compared.path("pages").path("total").asInt());
        assertEquals(1, compared.path("pages").path("data").size());
        assertEquals(205, compared.path("files").path("total").asInt());
        assertEquals(5, compared.path("files").path("data").size());
        assertEquals(2, compared.path("files").path("pageIndex").asInt());
        assertFalse(compared.path("left").has("storagePath"));
        assertFalse(compared.path("left").has("manifestJson"));
        assertFalse(compared.toString().contains("previewUrl"));
        assertEquals(
                r3,
                result(HttpMethod.GET, version(id) + "/current-revision", null)
                        .path("id")
                        .asText());
        JsonNode same = result(HttpMethod.GET, comparison(id, r1, r1), null);
        assertEquals(0, same.path("files").path("total").asInt());
        assertEquals(206, same.path("summary").path("files").path("unchanged").asInt());
        assertEquals(2, same.path("summary").path("pages").path("unchanged").asInt());
        JsonNode all =
                result(
                        HttpMethod.GET,
                        path + "&includeUnchanged=true&filesPageSize=100&pagesPageSize=1",
                        null);
        assertEquals(206, all.path("files").path("total").asInt());
        assertEquals(100, all.path("files").path("data").size());
        JsonNode pastEnd =
                result(
                        HttpMethod.GET,
                        path + "&pagesPageIndex=2147483647&filesPageIndex=2147483647",
                        null);
        assertTrue(pastEnd.path("pages").path("data").isEmpty());
        assertTrue(pastEnd.path("files").path("data").isEmpty());
        assertEquals(before, databaseSnapshot());
        // 清单是已发布事实；磁盘暂不可用时对比仍可查阅，不触发重散列或逐文件访问。
        SecureZipExtractor.deleteRecursively(FILES);
        assertEquals(
                compared,
                result(
                        HttpMethod.GET,
                        path
                                + "&pagesPageSize=1&pagesPageIndex=1&filesPageSize=100&filesPageIndex=2",
                        null));
        assertEquals(before, databaseSnapshot());
    }

    @Test
    void comparisonRejectsWrongScopeInvalidParametersAndUnmigratedVersions() throws Exception {
        String project = project();
        JsonNode own = upload(project, null, archive("R1"), null, null, 200);
        String id = own.path("id").asText(), rid = own.path("revisionId").asText();
        String sameProject =
                upload(project, null, archive("Other version"), null, null, 200)
                        .path("revisionId")
                        .asText();
        String otherProject =
                upload(project(), null, archive("Other project"), null, null, 200)
                        .path("revisionId")
                        .asText();
        for (String foreign : List.of(sameProject, otherProject, "missing")) {
            call(HttpMethod.GET, comparison(id, rid, foreign), admin, null, 404);
            call(HttpMethod.GET, comparison(id, foreign, rid), admin, null, 404);
        }
        call(HttpMethod.GET, comparison(id, rid, rid), null, null, 401);
        for (String invalid :
                List.of(
                        "pagesPageIndex=-1",
                        "filesPageIndex=-1",
                        "pagesPageSize=0",
                        "filesPageSize=101",
                        "pagesPageSize=oops",
                        "includeUnchanged=oops"))
            call(HttpMethod.GET, comparison(id, rid, rid) + "&" + invalid, admin, null, 400);
        call(
                HttpMethod.GET,
                version(id) + "/revisions/_compare?rightRevisionId=" + rid,
                admin,
                null,
                400);
        call(HttpMethod.GET, comparison(id, "", rid), admin, null, 400);
        makeLegacy(id);
        JsonNode error = call(HttpMethod.GET, comparison(id, rid, rid), admin, null, 409);
        assertEquals("PROTOTYPE_HUB_REVISION_MIGRATION_REQUIRED", error.path("code").asText());
    }

    @Test
    void comparisonAcceptsLegacyNullPageIdentityButRejectsMalformedSavedManifest()
            throws Exception {
        JsonNode own = upload(project(), null, archive("Legacy IDs"), null, null, 200);
        String id = own.path("id").asText(), rid = own.path("revisionId").asText();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) manifest(rid);
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.withArray("pages").get(0))
                .remove("pageId");
        String legacyJson = mapper.writeValueAsString(json);
        revisions
                .createUpdate()
                .set(PrototypeRevisionEntity::getManifestJson, legacyJson)
                .set(
                        PrototypeRevisionEntity::getManifestSha256,
                        sha(legacyJson.getBytes(StandardCharsets.UTF_8)))
                .where(PrototypeRevisionEntity::getId, rid)
                .execute()
                .block(TIMEOUT);
        JsonNode compared =
                result(HttpMethod.GET, comparison(id, rid, rid) + "&includeUnchanged=true", null);
        assertTrue(compared.path("pages").path("data").get(0).path("left").path("pageId").isNull());
        assertTrue(
                compared.path("pages").path("data").get(0).path("right").path("pageId").isNull());
        json.put("formatVersion", 2);
        revisions
                .createUpdate()
                .set(PrototypeRevisionEntity::getManifestJson, json.toString())
                .where(PrototypeRevisionEntity::getId, rid)
                .execute()
                .block(TIMEOUT);
        JsonNode error = call(HttpMethod.GET, comparison(id, rid, rid), admin, null, 400);
        assertEquals("PROTOTYPE_HUB_MANIFEST_INVALID", error.path("code").asText());
        revisions
                .createUpdate()
                .set(PrototypeRevisionEntity::getManifestJson, "null")
                .where(PrototypeRevisionEntity::getId, rid)
                .execute()
                .block(TIMEOUT);
        error = call(HttpMethod.GET, comparison(id, rid, rid), admin, null, 400);
        assertEquals("PROTOTYPE_HUB_MANIFEST_INVALID", error.path("code").asText());
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
        seedHistory(project, id, current, 201);
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        assertEquals(202, count("p_revision"));
        assertEquals(1, count("p_version"));
        assertEquals(0, count("p_file_cleanup"));
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(202, count("p_revision"));
        assertEquals(202, directoryCount(project));
        assertTrue(Files.exists(FILES.resolve(current.getStoragePath()).resolve("index.html")));
        ageVersion(id);
        assertTrue(retention.purgeVersion(id).block(TIMEOUT));
        assertEquals(0, count("p_version"));
        assertEquals(0, count("p_revision"));
        assertEquals(202, count("p_file_cleanup"));
        assertEquals(202, directoryCount(project));
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(152, count("p_file_cleanup"));
        assertEquals(152, directoryCount(project));
        for (int i = 0; i < 4; i++) cleanup.retryPending().block(TIMEOUT);
        assertEquals(0, count("p_file_cleanup"));
        assertEquals(0, directoryCount(project));
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
    void expiredRowsDisappearFromTrashBeforeTheWorkerAndCannotRestore() throws Exception {
        String project = project();
        JsonNode fresh = upload(project, null, archive("Fresh"), null, null, 200);
        JsonNode expired = upload(project, null, archive("Expired"), null, null, 200);
        call(HttpMethod.DELETE, version(fresh.path("id").asText()), admin, null, 200);
        call(HttpMethod.DELETE, version(expired.path("id").asText()), admin, null, 200);
        ageVersion(expired.path("id").asText());
        JsonNode page =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/_deleted/_query?projectId=" + project,
                        Map.of("pageSize", 1));
        assertEquals(1, page.path("total").asInt());
        assertEquals(fresh.path("id"), page.path("data").get(0).path("id"));
        call(HttpMethod.POST, version(expired.path("id").asText()) + "/_restore", admin, null, 404);
        assertEquals(2, count("p_version"));
        assertEquals(2, directoryCount(project));
        call(HttpMethod.POST, version(fresh.path("id").asText()) + "/_restore", admin, null, 200);

        String oldProject = project(), freshProject = project();
        call(HttpMethod.DELETE, "/prototype-hub/projects/" + oldProject, admin, null, 200);
        call(HttpMethod.DELETE, "/prototype-hub/projects/" + freshProject, admin, null, 200);
        ageProject(oldProject);
        JsonNode projectsPage =
                result(HttpMethod.POST, "/prototype-hub/projects/_deleted/_query", Map.of());
        assertEquals(1, projectsPage.path("total").asInt());
        assertEquals(freshProject, projectsPage.path("data").get(0).path("id").asText());
        call(
                HttpMethod.POST,
                "/prototype-hub/projects/" + oldProject + "/_restore",
                admin,
                null,
                404);
        call(
                HttpMethod.POST,
                "/prototype-hub/projects/" + freshProject + "/_restore",
                admin,
                null,
                200);
    }

    @Test
    void purgeRemovesTheCompleteExpiredGraphAndEndsItsCreationKeyDeduplication() throws Exception {
        String project = keyedProject("retention-project-key");
        JsonNode first = upload(project, null, archive("R1"), "retention-upload-key", null, 200);
        String id = first.path("id").asText();
        upload(
                project,
                id,
                archive("R2"),
                "retention-replace-key",
                first.path("revisionId").asText(),
                200);
        result(HttpMethod.POST, version(id) + "/shares", Map.of());
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        call(HttpMethod.DELETE, "/prototype-hub/projects/" + project, admin, null, 200);
        ageVersion(id);
        ageProject(project);
        PrototypeProjectMemberEntity member = new PrototypeProjectMemberEntity();
        member.setId("retained-member");
        member.setProjectId(project);
        member.setUserId("retained-user");
        member.setRole("viewer");
        member.setCreateTime(System.currentTimeMillis());
        memberRows.insert(member).block(TIMEOUT);
        PrototypePreviewSessionEntity session = sessionFixture(project, id, "retained-session");
        sessionRows.insert(session).block(TIMEOUT);
        assertTrue(retention.purgeVersion(id).block(TIMEOUT));
        assertEquals(0, count("p_version"));
        assertEquals(0, count("p_revision"));
        assertEquals(0, count("p_share"));
        assertEquals(0, count("p_preview_session"));
        assertEquals(1, count("p_project_member"));
        assertEquals(1, count("p_creation_receipt"));
        assertEquals(2, count("p_file_cleanup"));
        assertEquals(2, directoryCount(project));
        assertTrue(retention.purgeProject(project).block(TIMEOUT));
        assertEquals(0, count("p_project"));
        assertEquals(0, count("p_project_member"));
        assertEquals(0, count("p_creation_receipt"));
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(0, count("p_file_cleanup"));
        assertEquals(0, directoryCount(project));
        String newProject = keyedProject("retention-project-key");
        assertNotEquals(project, newProject);
        JsonNode replacement =
                upload(newProject, null, archive("R1"), "retention-upload-key", null, 200);
        assertNotEquals(id, replacement.path("id").asText());
        // 在同一仍活动父项目中，原上传键在版本永久清理后也只会创建新ID。
        String nextId = replacement.path("id").asText();
        call(HttpMethod.DELETE, version(nextId), admin, null, 200);
        ageVersion(nextId);
        assertTrue(retention.purgeVersion(nextId).block(TIMEOUT));
        assertNotEquals(
                nextId,
                upload(newProject, null, archive("R1"), "retention-upload-key", null, 200)
                        .path("id")
                        .asText());
    }

    @Test
    void youngerActiveChildrenProtectExpiredParentsAndOrphanTombstonesStillPurge()
            throws Exception {
        String project = project();
        JsonNode child = upload(project, null, archive("Child"), null, null, 200);
        String id = child.path("id").asText();
        // 模拟部分恢复的活动子行；父到期不是提前级联永久删除的授权。
        ageProject(project);
        assertFalse(retention.purgeProject(project).block(TIMEOUT));
        assertFalse(retention.purgeVersion(id).block(TIMEOUT));
        assertEquals(1, directoryCount(project));
        versions.createUpdate()
                .set(PrototypeVersionEntity::getDeletedTime, System.currentTimeMillis())
                .where(PrototypeVersionEntity::getId, id)
                .execute()
                .block(TIMEOUT);
        assertFalse(retention.purgeProject(project).block(TIMEOUT));
        assertFalse(retention.purgeVersion(id).block(TIMEOUT));
        ageVersion(id);
        assertTrue(retention.purgeVersion(id).block(TIMEOUT));
        assertTrue(retention.purgeProject(project).block(TIMEOUT));

        String orphanProject = project();
        String orphan =
                upload(orphanProject, null, archive("Orphan"), null, null, 200).path("id").asText();
        call(HttpMethod.DELETE, version(orphan), admin, null, 200);
        ageVersion(orphan);
        projects.deleteById(orphanProject).block(TIMEOUT);
        assertTrue(retention.purgeVersion(orphan).block(TIMEOUT));
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(0, directoryCount(orphanProject));
    }

    @Test
    void queueInsertAndLateDeleteFailuresRollBackTheEntireGraphBeforeAnyFileDeletion()
            throws Exception {
        String project = project();
        String id =
                upload(project, null, archive("Atomic"), "atomic-purge", null, 200)
                        .path("id")
                        .asText();
        result(HttpMethod.POST, version(id) + "/shares", Map.of());
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        ageVersion(id);
        String before = databaseSnapshot();
        for (String table : List.of("p_file_cleanup", "p_version")) {
            sql(
                    "create function reject_retention() returns trigger language plpgsql as 'begin raise exception ''fixture rejection''; end'");
            sql(
                    "create trigger reject_retention before "
                            + (table.equals("p_file_cleanup") ? "insert" : "delete")
                            + " on "
                            + table
                            + " for each row execute function reject_retention()");
            try {
                assertThrows(Exception.class, () -> retention.purgeVersion(id).block(TIMEOUT));
                assertEquals(before, databaseSnapshot());
                assertEquals(0, count("p_file_cleanup"));
                assertEquals(1, directoryCount(project));
            } finally {
                sql("drop trigger reject_retention on " + table);
                sql("drop function reject_retention()");
            }
        }
        assertTrue(retention.purgeVersion(id).block(TIMEOUT));
    }

    @ParameterizedTest(name = "canonical alias retained by raw {0}")
    @ValueSource(strings = {"version", "revision"})
    void canonicalAndAliasReferencesProtectSharedFilesUntilTheLastVersionIsPurged(String reference)
            throws Exception {
        String project = project();
        JsonNode first = upload(project, null, archive("Shared"), null, null, 200);
        String id = first.path("id").asText();
        String path = versions.findById(id).block(TIMEOUT).getStoragePath();
        String alias = path.replace("/versions/", "/versions/unused/../");
        String other = upload(project, null, archive("Alias"), null, null, 200).path("id").asText();
        if (reference.equals("version")) {
            makeLegacy(other);
            versions.createUpdate()
                    .set(PrototypeVersionEntity::getStoragePath, alias)
                    .where(PrototypeVersionEntity::getId, other)
                    .execute()
                    .block(TIMEOUT);
        } else
            revisions
                    .createUpdate()
                    .set(PrototypeRevisionEntity::getStoragePath, alias)
                    .where(PrototypeRevisionEntity::getVersionId, other)
                    .execute()
                    .block(TIMEOUT);
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        ageVersion(id);
        assertTrue(retention.purgeVersion(id).block(TIMEOUT));
        cleanup.retryPending().block(TIMEOUT);
        assertTrue(Files.exists(FILES.resolve(path).resolve("index.html")));
        assertEquals(1, count("p_file_cleanup"));
        assertEquals(1, cleanup.createQuery().fetchOne().block(TIMEOUT).getAttempts());
        call(HttpMethod.DELETE, version(other), admin, null, 200);
        ageVersion(other);
        assertTrue(retention.purgeVersion(other).block(TIMEOUT));
        assertEquals(reference.equals("version") ? 1 : 2, count("p_file_cleanup"));
        cleanup.createUpdate()
                .set(FileCleanupEntity::getNextAttempt, 0L)
                .where(FileCleanupEntity::getProjectId, project)
                .execute()
                .block(TIMEOUT);
        cleanup.retryPending().block(TIMEOUT);
        assertFalse(Files.exists(FILES.resolve(path)));
        assertEquals(0, count("p_file_cleanup"));
    }

    @Test
    void failedAndCancelledFileWorkRemainDurableForANewWorkerAndMissingDirectoriesAreIdempotent()
            throws Exception {
        String project = project();
        String id = upload(project, null, archive("Retry"), null, null, 200).path("id").asText();
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        ageVersion(id);
        retention.purgeVersion(id).block(TIMEOUT);
        FileCleanupEntity task = cleanup.createQuery().fetchOne().block(TIMEOUT);
        var failing = spy(storage);
        doAnswer(invocation -> Mono.error(new IOException("fixture storage offline")))
                .when(failing)
                .deleteStoredVersion(project, task.getStoragePath(), id);
        cleanupWorker(failing).attempt(task).block(TIMEOUT);
        assertEquals(1, cleanup.findById(task.getId()).block(TIMEOUT).getAttempts());
        assertEquals(1, directoryCount(project));

        var pending = spy(storage);
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> Mono.<Void>never().doOnSubscribe(ignored -> entered.countDown()))
                .when(pending)
                .deleteStoredVersion(project, task.getStoragePath(), id);
        var cancelled = cleanupWorker(pending).attempt(task).toFuture();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(cancelled.cancel(true));
        assertNotNull(cleanup.findById(task.getId()).block(TIMEOUT));
        cleanupWorker(storage).attempt(task).block(TIMEOUT);
        assertNull(cleanup.findById(task.getId()).block(TIMEOUT));
        assertEquals(0, directoryCount(project));
        FileCleanupEntity missing =
                cleanup.enqueueExpired(
                                project,
                                "missing",
                                "projects/" + project + "/versions/missing/site")
                        .block(TIMEOUT);
        cleanupWorker(storage).attempt(missing).block(TIMEOUT);
        assertNull(cleanup.findById(missing.getId()).block(TIMEOUT));
    }

    @ParameterizedTest(name = "restore wins project lock={0}")
    @ValueSource(booleans = {true, false})
    void restoreAndPurgeSerializeOnTheProjectLockAndRereadCurrentState(boolean restoreWins)
            throws Exception {
        String project = project();
        String id = upload(project, null, archive("Race"), null, null, 200).path("id").asText();
        call(HttpMethod.DELETE, version(id), admin, null, 200);
        if (!restoreWins) ageVersion(id);
        Connection anchor =
                Mono.from(
                                ConnectionFactories.get(
                                                "r2dbc:postgresql://"
                                                        + DATABASE.getUsername()
                                                        + ":"
                                                        + DATABASE.getPassword()
                                                        + "@"
                                                        + DATABASE.getHost()
                                                        + ":"
                                                        + DATABASE.getMappedPort(5432)
                                                        + "/"
                                                        + DATABASE.getDatabaseName())
                                        .create())
                        .block(TIMEOUT);
        assertNotNull(anchor);
        CompletableFuture<Boolean> purging;
        CompletableFuture<JsonNode> restoring;
        try {
            Mono.from(anchor.beginTransaction()).block(TIMEOUT);
            Flux.from(
                            anchor.createStatement(
                                            "select id from p_project where id=$1 for update")
                                    .bind(0, project)
                                    .execute())
                    .flatMap(row -> row.map((data, metadata) -> data.get("id", String.class)))
                    .then()
                    .block(TIMEOUT);
            if (restoreWins) {
                restoring =
                        Mono.fromCallable(
                                        () ->
                                                call(
                                                        HttpMethod.POST,
                                                        version(id) + "/_restore",
                                                        admin,
                                                        null,
                                                        200))
                                .subscribeOn(Schedulers.boundedElastic())
                                .toFuture();
                awaitProjectLock(anchor);
                purging = retention.purgeVersion(id).toFuture();
            } else {
                purging = retention.purgeVersion(id).toFuture();
                awaitProjectLock(anchor);
                restoring =
                        Mono.fromCallable(
                                        () ->
                                                call(
                                                        HttpMethod.POST,
                                                        version(id) + "/_restore",
                                                        admin,
                                                        null,
                                                        404))
                                .subscribeOn(Schedulers.boundedElastic())
                                .toFuture();
            }
            // 两个真实业务入口在同一单连接池中排队；首个请求已实际等待项目行，而非依靠 sleep 猜测。
            Mono.from(anchor.commitTransaction()).block(TIMEOUT);
            assertEquals(!restoreWins, purging.get(30, TimeUnit.SECONDS));
            assertNotNull(restoring.get(30, TimeUnit.SECONDS));
        } finally {
            Mono.from(anchor.close()).block(TIMEOUT);
        }
        if (restoreWins) {
            assertNull(versions.findById(id).block(TIMEOUT).getDeletedTime());
            assertEquals(0, count("p_file_cleanup"));
            assertEquals(1, directoryCount(project));
        } else {
            assertNull(versions.findById(id).block(TIMEOUT));
            assertEquals(1, count("p_file_cleanup"));
            assertEquals(1, directoryCount(project));
            cleanup.retryPending().block(TIMEOUT);
            assertEquals(0, directoryCount(project));
        }
    }

    private void awaitProjectLock(Connection anchor) {
        Flux.defer(
                        () ->
                                Flux.from(
                                                anchor.createStatement(
                                                                "select exists(select 1 from pg_stat_activity a where a.pid<>pg_backend_pid() and a.wait_event_type='Lock' and pg_backend_pid()=any(pg_blocking_pids(a.pid))) as blocked")
                                                        .execute())
                                        .flatMap(
                                                row ->
                                                        row.map(
                                                                (data, metadata) ->
                                                                        data.get(
                                                                                "blocked",
                                                                                Boolean.class))))
                .repeat()
                .filter(Boolean.TRUE::equals)
                .next()
                .timeout(TIMEOUT)
                .block(TIMEOUT);
    }

    @Test
    void expiredCleanupNeverFollowsAVersionDirectorySymlink() throws Exception {
        String project = project();
        Path neighbor = Files.createDirectories(FILES.resolve("neighbor"));
        Path keep = neighbor.resolve("keep.txt");
        Files.writeString(keep, "unowned files");
        Path target = FILES.resolve("projects/" + project + "/versions/trap");
        Files.createDirectories(target.getParent());
        Files.createSymbolicLink(target, neighbor);
        FileCleanupEntity task =
                cleanup.enqueueExpired(
                                project, "trap", "projects/" + project + "/versions/trap/site")
                        .block(TIMEOUT);
        cleanup.attempt(task).block(TIMEOUT);
        assertEquals("unowned files", Files.readString(keep));
        assertEquals(1, cleanup.findById(task.getId()).block(TIMEOUT).getAttempts());
        Files.delete(target);
        cleanup.attempt(cleanup.findById(task.getId()).block(TIMEOUT)).block(TIMEOUT);
        assertNull(cleanup.findById(task.getId()).block(TIMEOUT));
        assertEquals("unowned files", Files.readString(keep));
    }

    @Test
    void persistedVersionCursorPassesFiftyInvalidRowsAndResetsForRetry() throws Exception {
        String project = project();
        String healthy =
                upload(project, null, archive("Healthy"), null, null, 200).path("id").asText();
        PrototypeVersionEntity source = versions.findById(healthy).block(TIMEOUT);
        long blockedTime =
                System.currentTimeMillis() - PrototypeRetentionService.RETENTION_MILLIS - 120_000;
        List<PrototypeVersionEntity> blocked = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            PrototypeVersionEntity row = new PrototypeVersionEntity();
            org.springframework.beans.BeanUtils.copyProperties(source, row);
            row.setId(String.format("blocked-version-%03d", i));
            row.setStoragePath("../outside");
            row.setDeletedTime(blockedTime);
            blocked.add(row);
        }
        versions.insert(Flux.fromIterable(blocked)).block(TIMEOUT);
        call(HttpMethod.DELETE, version(healthy), admin, null, 200);
        ageVersion(healthy);
        retention.purgeExpired().block(TIMEOUT);
        assertNotNull(versions.findById(healthy).block(TIMEOUT));
        PrototypeInstallationEntity claimed = installations.current().block(TIMEOUT);
        assertEquals("blocked-version-049", claimed.getRetentionVersionId());
        assertEquals(blockedTime, claimed.getRetentionDeletedTime());
        // 下一轮仅从数据库读游标；不依赖当前实例的内存状态，重启仍可越过失败头页。
        retention.purgeExpired().block(TIMEOUT);
        assertNull(versions.findById(healthy).block(TIMEOUT));
        assertEquals(50, count("p_version"));
        assertNull(installations.current().block(TIMEOUT).getRetentionVersionId());
        retention.purgeExpired().block(TIMEOUT);
        assertEquals(
                "blocked-version-049",
                installations.current().block(TIMEOUT).getRetentionVersionId());
        assertEquals(50, count("p_version"));
    }

    @Test
    void persistedProjectCursorPassesFiftyDeleteFailuresAndParentsWithChildrenAreExcluded()
            throws Exception {
        String healthy = project();
        PrototypeProjectEntity source = projects.findById(healthy).block(TIMEOUT);
        long blockedTime =
                System.currentTimeMillis() - PrototypeRetentionService.RETENTION_MILLIS - 120_000;
        List<PrototypeProjectEntity> blocked = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            PrototypeProjectEntity row = new PrototypeProjectEntity();
            org.springframework.beans.BeanUtils.copyProperties(source, row);
            row.setId(String.format("blocked-project-%03d", i));
            row.setDeletedTime(blockedTime);
            blocked.add(row);
        }
        projects.insert(Flux.fromIterable(blocked)).block(TIMEOUT);
        ageProject(healthy);
        sql(
                "create function reject_selected_parent() returns trigger language plpgsql as 'begin if old.id like ''blocked-project-%'' then raise exception ''fixture rejection''; end if; return old; end'");
        sql(
                "create trigger reject_selected_parent before delete on p_project for each row execute function reject_selected_parent()");
        try {
            retention.purgeExpired().block(TIMEOUT);
            assertNotNull(projects.findById(healthy).block(TIMEOUT));
            assertEquals(
                    "blocked-project-049",
                    installations.current().block(TIMEOUT).getRetentionProjectId());
            retention.purgeExpired().block(TIMEOUT);
            assertNull(projects.findById(healthy).block(TIMEOUT));
            assertEquals(50, count("p_project"));
            assertNull(installations.current().block(TIMEOUT).getRetentionProjectId());
        } finally {
            sql("drop trigger reject_selected_parent on p_project");
            sql("drop function reject_selected_parent()");
        }
        // 同一个失败头页保留原始活动子行后，不应再占用项目候选页。
        List<PrototypeVersionEntity> children = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            PrototypeVersionEntity child = new PrototypeVersionEntity();
            child.setId("active-child-" + i);
            child.setProjectId(blocked.get(i).getId());
            child.setVersionName("Retained child");
            child.setStoragePath("projects/" + child.getProjectId() + "/versions/active/site");
            child.setEntryFile("index.html");
            child.setPreviewKey("fixture");
            child.setStatus("ready");
            child.setCreatorId(source.getCreatorId());
            child.setCreateTime(System.currentTimeMillis());
            children.add(child);
        }
        versions.insert(Flux.fromIterable(children)).block(TIMEOUT);
        String later = project();
        ageProject(later);
        retention.purgeExpired().block(TIMEOUT);
        assertNull(projects.findById(later).block(TIMEOUT));
        assertEquals(50, count("p_project"));
        assertEquals(50, count("p_version"));
    }

    private void seedHistory(String project, String id, PrototypeRevisionEntity current, int size)
            throws IOException {
        List<PrototypeRevisionEntity> history = new ArrayList<>();
        for (int i = 0; i < size; i++) {
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
    }

    private PrototypePreviewSessionEntity sessionFixture(
            String project, String version, String id) {
        PrototypeVersionEntity row = versions.findById(version).block(TIMEOUT);
        PrototypePreviewSessionEntity session = new PrototypePreviewSessionEntity();
        session.setId(id);
        session.setProjectId(project);
        session.setVersionId(version);
        session.setRevisionId(row.getCurrentRevisionId());
        session.setStoragePath(row.getStoragePath());
        session.setUserId("retained-user");
        session.setAccessEpoch("retained-epoch");
        session.setCreateTime(System.currentTimeMillis());
        session.setExpiresAt(System.currentTimeMillis() + 60000);
        return session;
    }

    private FileCleanupService cleanupWorker(PrototypeStorageService supplied) {
        return new FileCleanupService(
                supplied, revisions.getRepository(), versions.getRepository()) {
            @Override
            public ReactiveRepository<FileCleanupEntity, String> getRepository() {
                return cleanup.getRepository();
            }
        };
    }

    private void ageVersion(String id) {
        versions.createUpdate()
                .set(
                        PrototypeVersionEntity::getDeletedTime,
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 60_000L)
                .where(PrototypeVersionEntity::getId, id)
                .execute()
                .block(TIMEOUT);
    }

    private void ageProject(String id) {
        projects.createUpdate()
                .set(
                        PrototypeProjectEntity::getDeletedTime,
                        System.currentTimeMillis()
                                - PrototypeRetentionService.RETENTION_MILLIS
                                - 60_000L)
                .where(PrototypeProjectEntity::getId, id)
                .execute()
                .block(TIMEOUT);
    }

    private String keyedProject(String key) {
        return client.post()
                .uri("/prototype-hub/projects")
                .header("X-Access-Token", admin)
                .header("Idempotency-Key", key)
                .bodyValue(Map.of("name", "Keyed retention project"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody()
                .path("result")
                .path("id")
                .asText();
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
        PrototypeProjectMemberEntity orphan = new PrototypeProjectMemberEntity();
        orphan.setId("export-orphan-member");
        orphan.setProjectId("missing-project");
        orphan.setUserId("fixture-user");
        orphan.setRole("viewer");
        orphan.setCreateTime(System.currentTimeMillis());
        memberRows.insert(orphan).block(TIMEOUT);
        assertEquals(1, storageExport(null).path("integrityErrors").asInt());
        memberRows.deleteById(orphan.getId()).block(TIMEOUT);
        PrototypePreviewSessionEntity session = sessionFixture(project, id, "export-session");
        sessionRows.insert(session).block(TIMEOUT);
        assertEquals(0, storageExport(null).path("integrityErrors").asInt());
        sessionRows
                .createUpdate()
                .set(PrototypePreviewSessionEntity::getVersionId, legacyId)
                .where(PrototypePreviewSessionEntity::getId, session.getId())
                .execute()
                .block(TIMEOUT);
        assertEquals(1, storageExport(null).path("integrityErrors").asInt());
        sessionRows
                .createUpdate()
                .set(PrototypePreviewSessionEntity::getVersionId, id)
                .set(PrototypePreviewSessionEntity::getStoragePath, "wrong-snapshot")
                .where(PrototypePreviewSessionEntity::getId, session.getId())
                .execute()
                .block(TIMEOUT);
        assertEquals(1, storageExport(null).path("integrityErrors").asInt());
        sessionRows.deleteById(session.getId()).block(TIMEOUT);
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

    @Test
    void operationalSnapshotIncludesDurableQueueAndSweepCursorsWithoutChangingBusinessDigests()
            throws Exception {
        String project = project();
        upload(project, null, archive("Snapshot"), null, null, 200);
        for (String name : List.of("prototype-hub-table-names.sql", "snapshot-db.sql"))
            DATABASE.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "tools", name).toAbsolutePath()),
                    "/tmp/" + name);
        String before = toolSnapshot();
        String identity = installations.current().block(TIMEOUT).getInstallationId();
        cleanup.enqueueExpired(project, "pending", "projects/" + project + "/versions/pending/site")
                .block(TIMEOUT);
        String withQueue = toolSnapshot();
        assertNotEquals(
                snapshotLine(before, "file_cleanup"), snapshotLine(withQueue, "file_cleanup"));
        assertEquals(snapshotLine(before, "installation"), snapshotLine(withQueue, "installation"));
        installations
                .createUpdate()
                .set(PrototypeInstallationEntity::getRetentionDeletedTime, 1L)
                .set(PrototypeInstallationEntity::getRetentionVersionId, "cursor-version")
                .set(PrototypeInstallationEntity::getRetentionProjectTime, 2L)
                .set(PrototypeInstallationEntity::getRetentionProjectId, "cursor-project")
                .where(PrototypeInstallationEntity::getId, "default")
                .execute()
                .block(TIMEOUT);
        String withCursor = toolSnapshot();
        assertNotEquals(
                snapshotLine(before, "installation"), snapshotLine(withCursor, "installation"));
        assertEquals(identity, installations.current().block(TIMEOUT).getInstallationId());
        for (String kind :
                List.of(
                        "projects",
                        "versions",
                        "revisions",
                        "project_authorization",
                        "current_revision_bindings"))
            assertEquals(snapshotLine(before, kind), snapshotLine(withCursor, kind));
    }

    private String toolSnapshot() throws Exception {
        var result =
                DATABASE.execInContainer(
                        "psql",
                        "-X",
                        "-qAt",
                        "-F",
                        ",",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-U",
                        DATABASE.getUsername(),
                        "-d",
                        DATABASE.getDatabaseName(),
                        "-f",
                        "/tmp/snapshot-db.sql");
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout();
    }

    private String snapshotLine(String snapshot, String kind) {
        return snapshot.lines()
                .filter(line -> line.startsWith(kind + ","))
                .findFirst()
                .orElseThrow();
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

    private String comparison(String id, String left, String right) {
        return version(id)
                + "/revisions/_compare?leftRevisionId="
                + left
                + "&rightRevisionId="
                + right;
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
        return archive(content, 0);
    }

    private static byte[] archive(String content, int extraFiles) throws IOException {
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
            for (int i = 0; i < extraFiles; i++) {
                zip.putNextEntry(new ZipEntry("assets/%03d.txt".formatted(i)));
                zip.write(content.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
