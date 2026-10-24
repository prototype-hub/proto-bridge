package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.service.PrototypeShareService;
import com.gaiys.proto.prototypehub.service.keys.PrototypeShareKeys;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** 分享生命周期的真实 HTTP、PostgreSQL 事务与 BCrypt 快照竞争回归。 */
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
class PrototypeHubShareLifecycleIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final String OLD_PASSWORD = UUID.randomUUID().toString();
    private static final String NEW_PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();
    private static final Map<String, List<String>> ALL_ACTIONS =
            Map.of(
                    "prototype-hub-project", List.of("query", "save", "delete"),
                    "prototype-hub-version", List.of("query", "save", "delete"),
                    "prototype-hub-share", List.of("query", "save", "delete"));

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
        properties.add("spring.r2dbc.pool.max-size", () -> 4);
        properties.add("spring.r2dbc.pool.initial-size", () -> 1);
        properties.add("proto.admin.password", () -> PASSWORD);
        properties.add("prototype-hub.storage.root", FILES::toString);
    }

    @Autowired WebTestClient client;
    @Autowired DatabaseClient database;
    @Autowired PrototypeShareService shares;
    @MockitoSpyBean FileWork work;
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

    @Test
    void fixedAndLatestUseOneAuthorizedRevisionForHtmlAssetsAndPages() throws Exception {
        JsonNode first = upload(project(admin), null, "R1", admin);
        String version = first.path("id").asText(), r1 = first.path("revisionId").asText();
        JsonNode fixed = create(version, Map.of());
        JsonNode latest = create(version, Map.of("revisionMode", "latest"));
        assertEquals("fixed", fixed.path("revisionMode").asText());
        assertEquals(r1, fixed.path("revisionId").asText());
        assertTrue(latest.path("revisionId").isNull());
        JsonNode grantedR1 = publicInfo(latest);
        assertSnapshot(grantedR1, r1, "R1");
        JsonNode second = upload(first.path("projectId").asText(), version, "R2", admin);
        String r2 = second.path("revisionId").asText();
        assertNotEquals(r1, r2);
        assertSnapshot(publicInfo(fixed), r1, "R1");
        JsonNode fixedGrant = publicInfo(fixed);
        String derivedAlias =
                "/prototype-hub/public/shares/"
                        + fixed.path("token").asText()
                        + "/"
                        + fixedGrant.path("accessKey").asText();
        assertTrue(resource(derivedAlias + "/runtime/index.html").contains("R1"));
        call(HttpMethod.GET, derivedAlias + "/pages", null, null, 200);
        JsonNode grantedR2 = publicInfo(latest);
        assertSnapshot(grantedR2, r2, "R2");
        for (String key :
                List.of(
                        grantedR1.path("accessKey").asText(),
                        shares.findById(latest.path("id").asText())
                                .block(TIMEOUT)
                                .getAccessKey())) {
            String latestAlias =
                    "/prototype-hub/public/shares/" + latest.path("token").asText() + "/" + key;
            denied(latestAlias + "/runtime/index.html", 403);
            call(HttpMethod.GET, latestAlias + "/pages", null, null, 403);
        }
        // 已授权的跟随分享也固定本次修订；上传不能使 HTML 与相对资源分属两个快照。
        assertSnapshot(grantedR1, r1, "R1");
        denied(
                grantedR1.path("runtimeBaseUrl").asText().replace("/" + r1 + "/", "/" + r2 + "/")
                        + "/index.html",
                403);
        assertFalse(publicInfo(fixed).has("passwordHash"));
        assertFalse(fixed.has("accessKey"));
        assertFalse(latest.has("passwordHash"));
        // 发布过原始密钥的固定分享也必须核对持久目标，不能只验证签名是否正确。
        var persisted = shares.findById(fixed.path("id").asText()).block(TIMEOUT);
        String signedWrongRevision =
                "/prototype-hub/public/shares/"
                        + fixed.path("token").asText()
                        + "/"
                        + PrototypeShareKeys.derive(persisted, r2)
                        + "/revisions/"
                        + r2;
        denied(signedWrongRevision + "/runtime/index.html", 403);
        call(HttpMethod.GET, signedWrongRevision + "/pages", null, null, 403);
        // 跟随策略允许重新解析当前修订，但暂停必须同时阻断新授权和此前两个快照的凭据。
        mutate(latest, "/enabled", Map.of("expectedVersion", 1, "enabled", false));
        call(HttpMethod.GET, publicPath(latest), null, null, 404);
        deniedGrant(grantedR1, 404);
        deniedGrant(grantedR2, 404);
    }

    @Test
    void editingFixedSharePreservesSnapshotAndModeChangesRequireExplicitIntent() throws Exception {
        JsonNode first = upload(project(admin), null, "R1", admin);
        String version = first.path("id").asText(), r1 = first.path("revisionId").asText();
        JsonNode fixed = create(version, Map.of());
        JsonNode previousGrant = publicInfo(fixed);
        JsonNode second = upload(first.path("projectId").asText(), version, "R2", admin);
        String r2 = second.path("revisionId").asText();
        Map<String, Object> edit = configuration(fixed, "fixed", null, "keep", null);
        edit.put("description", "仅修改描述");
        JsonNode edited = mutate(fixed, "", edit);
        assertEquals(r1, edited.path("revisionId").asText());
        assertEquals(2, edited.path("configVersion").asLong());
        deniedGrant(previousGrant, 403);
        assertSnapshot(publicInfo(edited), r1, "R1");
        JsonNode selected = mutate(edited, "", configuration(edited, "fixed", r2, "keep", null));
        assertSnapshot(publicInfo(selected), r2, "R2");
        JsonNode latest =
                mutate(selected, "", configuration(selected, "latest", null, "keep", null));
        call(
                HttpMethod.PUT,
                share(latest),
                admin,
                configuration(latest, "fixed", null, "keep", null),
                400);
        call(
                HttpMethod.PUT,
                share(latest),
                admin,
                configuration(latest, "latest", r1, "keep", null),
                400);
        assertEquals(4, management(latest).path("configVersion").asLong());
        JsonNode pinned = mutate(latest, "", configuration(latest, "fixed", r1, "keep", null));
        assertSnapshot(publicInfo(pinned), r1, "R1");
    }

    @Test
    void pauseResumePermanentlyInvalidatesCanonicalAndPublishedFixedKeys() throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        JsonNode grant = publicInfo(share);
        String raw = shares.findById(share.path("id").asText()).block(TIMEOUT).getAccessKey();
        String alias = "/prototype-hub/public/shares/" + share.path("token").asText() + "/" + raw;
        assertTrue(resource(alias + "/runtime/index.html").contains("R1"));
        call(HttpMethod.GET, alias + "/pages", null, null, 200);
        JsonNode paused = mutate(share, "/enabled", Map.of("expectedVersion", 1, "enabled", false));
        assertFalse(paused.path("enabled").asBoolean());
        call(HttpMethod.GET, publicPath(share), null, null, 404);
        deniedGrant(grant, 404);
        JsonNode resumed =
                mutate(paused, "/enabled", Map.of("expectedVersion", 2, "enabled", true));
        assertEquals(3, resumed.path("configVersion").asLong());
        deniedGrant(grant, 403);
        denied(alias + "/runtime/index.html", 403);
        call(HttpMethod.GET, alias + "/pages", null, null, 403);
        assertSnapshot(publicInfo(resumed), version.path("revisionId").asText(), "R1");
    }

    @Test
    void passwordReplacementRemovalAndExpiryChangesInvalidateAllEarlierGrants() throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of("password", OLD_PASSWORD));
        JsonNode locked = publicInfo(share);
        assertTrue(locked.path("passwordRequired").asBoolean());
        for (String field : List.of("accessKey", "runtimeBaseUrl", "previewUrl", "pagesUrl"))
            assertTrue(locked.path(field).isNull() || locked.path(field).isMissingNode(), field);
        JsonNode oldGrant = verify(share, OLD_PASSWORD, 200);
        JsonNode changed =
                mutate(share, "", configuration(share, "fixed", null, "replace", NEW_PASSWORD));
        verify(changed, OLD_PASSWORD, 403);
        assertFalse(
                result(
                                HttpMethod.POST,
                                share(changed) + "/verify-password",
                                admin,
                                Map.of("password", OLD_PASSWORD))
                        .asBoolean());
        assertTrue(
                result(
                                HttpMethod.POST,
                                share(changed) + "/verify-password",
                                admin,
                                Map.of("password", NEW_PASSWORD))
                        .asBoolean());
        deniedGrant(oldGrant, 403);
        JsonNode protectedGrant = verify(changed, NEW_PASSWORD, 200);
        JsonNode removed =
                mutate(changed, "", configuration(changed, "fixed", null, "remove", null));
        assertFalse(removed.path("passwordRequired").asBoolean());
        assertSnapshot(publicInfo(removed), version.path("revisionId").asText(), "R1");
        deniedGrant(protectedGrant, 403);
        Map<String, Object> expired = configuration(removed, "fixed", null, "keep", null);
        expired.put("expiresAt", System.currentTimeMillis() - 1);
        call(HttpMethod.PUT, share(removed), admin, expired, 400);
        assertEquals(3, management(removed).path("configVersion").asLong());
        JsonNode beforeExpiry = publicInfo(removed);
        database.sql("update p_share set expires_at=:expired where id=:id")
                .bind("expired", System.currentTimeMillis() - 1)
                .bind("id", removed.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.GET, publicPath(removed), null, null, 404);
        deniedGrant(beforeExpiry, 404);
        JsonNode cleared = mutate(removed, "", configuration(removed, "fixed", null, "keep", null));
        assertTrue(cleared.path("expiresAt").isNull());
        assertSnapshot(publicInfo(cleared), version.path("revisionId").asText(), "R1");
        deniedGrant(beforeExpiry, 403);
    }

    @Test
    void rotateChangesTokenAndSecretAtomicallyWithoutChangingFixedContent() throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        JsonNode oldGrant = publicInfo(share);
        JsonNode rotated = mutate(share, "/rotate-link", Map.of("expectedVersion", 1));
        assertEquals(share.path("id"), rotated.path("id"));
        assertTrue(!share.path("token").asText().equals(rotated.path("token").asText()));
        assertEquals(2, rotated.path("configVersion").asLong());
        call(HttpMethod.GET, publicPath(share), null, null, 404);
        deniedGrant(oldGrant, 404);
        String newTokenOldKey =
                oldGrant.path("runtimeBaseUrl")
                        .asText()
                        .replace(share.path("token").asText(), rotated.path("token").asText());
        denied(newTokenOldKey + "/index.html", 403);
        assertSnapshot(publicInfo(rotated), version.path("revisionId").asText(), "R1");
    }

    @Test
    void revokeIsTerminalButRetainsManagementRecordAndPhysicalDeleteRemainsAvailable()
            throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        JsonNode grant = publicInfo(share);
        JsonNode revoked = mutate(share, "/revoke", Map.of("expectedVersion", 1));
        assertTrue(revoked.path("revoked").asBoolean());
        assertTrue(management(revoked).path("revoked").asBoolean());
        call(HttpMethod.GET, publicPath(revoked), null, null, 404);
        deniedGrant(grant, 404);
        for (String operation : List.of("/enabled", "/rotate-link", "/revoke", "")) {
            Map<String, Object> body =
                    operation.isEmpty()
                            ? configuration(revoked, "fixed", null, "keep", null)
                            : new HashMap<>(Map.of("expectedVersion", 2, "enabled", true));
            call(
                    operation.equals("/rotate-link") || operation.equals("/revoke")
                            ? HttpMethod.POST
                            : HttpMethod.PUT,
                    share(revoked) + operation,
                    admin,
                    body,
                    409);
        }
        assertEquals(2, management(revoked).path("configVersion").asLong());
        result(HttpMethod.DELETE, share(revoked), admin, null);
        assertTrue(shares.findById(revoked.path("id").asText()).blockOptional(TIMEOUT).isEmpty());
    }

    @Test
    void simultaneousConfigurationWritersUseCasAndCannotSilentlyLoseOneUpdate() throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        JsonNode oldGrant = publicInfo(share);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<CompletableFuture<Integer>> writers = new ArrayList<>();
        for (String description : List.of("left writer", "right writer")) {
            writers.add(
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    Map<String, Object> body =
                                            configuration(share, "fixed", null, "keep", null);
                                    body.put("description", description);
                                    return client.put()
                                            .uri(share(share))
                                            .header("X-Access-Token", admin)
                                            .bodyValue(body)
                                            .exchange()
                                            .returnResult(JsonNode.class)
                                            .getStatus()
                                            .value();
                                } catch (Exception failure) {
                                    throw new RuntimeException(failure);
                                }
                            }));
        }
        assertEquals(
                List.of(200, 409), writers.stream().map(future -> future.join()).sorted().toList());
        JsonNode persisted = management(share);
        assertEquals(2, persisted.path("configVersion").asLong());
        assertTrue(
                List.of("left writer", "right writer")
                        .contains(persisted.path("description").asText()));
        deniedGrant(oldGrant, 403);
        assertSnapshot(publicInfo(persisted), version.path("revisionId").asText(), "R1");
        call(
                HttpMethod.PUT,
                share(share) + "/enabled",
                admin,
                Map.of("expectedVersion", 1, "enabled", false),
                409);
        assertTrue(management(share).path("enabled").asBoolean());
    }

    @Test
    void managementActionsIntersectProjectRolesAndPersistedRelationships() throws Exception {
        Actor owner = actor(ALL_ACTIONS),
                editor = actor(ALL_ACTIONS),
                viewer = actor(ALL_ACTIONS),
                outsider = actor(ALL_ACTIONS);
        String project = project(owner.token());
        JsonNode version = upload(project, null, "R1", owner.token());
        for (Actor actor : List.of(editor, viewer))
            result(
                    HttpMethod.PUT,
                    "/prototype-hub/projects/" + project + "/members/" + actor.id(),
                    admin,
                    Map.of("role", actor.equals(editor) ? "editor" : "viewer"));
        JsonNode share =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + version.path("id").asText() + "/shares",
                        owner.token(),
                        Map.of());
        for (Actor actor : List.of(viewer, outsider)) {
            int status = actor.equals(viewer) ? 403 : 404;
            call(
                    HttpMethod.PUT,
                    share(share),
                    actor.token(),
                    configuration(share, "fixed", null, "keep", null),
                    status);
            call(
                    HttpMethod.PUT,
                    share(share) + "/enabled",
                    actor.token(),
                    Map.of("expectedVersion", 1, "enabled", false),
                    status);
            for (String operation : List.of("/rotate-link", "/revoke"))
                call(
                        HttpMethod.POST,
                        share(share) + operation,
                        actor.token(),
                        Map.of("expectedVersion", 1),
                        status);
        }
        Actor noSave = actor(Map.of("prototype-hub-share", List.of("query")));
        result(
                HttpMethod.PUT,
                "/prototype-hub/projects/" + project + "/members/" + noSave.id(),
                admin,
                Map.of("role", "editor"));
        call(
                HttpMethod.PUT,
                share(share) + "/enabled",
                noSave.token(),
                Map.of("expectedVersion", 1, "enabled", false),
                403);
        JsonNode edited =
                result(
                        HttpMethod.PUT,
                        share(share),
                        editor.token(),
                        configuration(share, "fixed", null, "keep", null));
        JsonNode paused =
                mutate(edited, "/enabled", Map.of("expectedVersion", 2, "enabled", false));
        assertEquals(3, paused.path("configVersion").asLong());
        result(
                HttpMethod.PUT,
                share(paused) + "/enabled",
                owner.token(),
                Map.of("expectedVersion", 3, "enabled", true));
        assertEquals(4, management(share).path("configVersion").asLong());
    }

    @Test
    void incorrectRevisionOrCorruptedShareAssociationCannotBecomeAnAuthorizationShortcut()
            throws Exception {
        String project = project(admin);
        JsonNode version = upload(project, null, "R1", admin);
        JsonNode foreign = upload(project, null, "foreign", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        JsonNode grant = publicInfo(share);
        call(
                HttpMethod.POST,
                "/prototype-hub/versions/" + version.path("id").asText() + "/shares",
                admin,
                Map.of("revisionMode", "fixed", "revisionId", foreign.path("revisionId").asText()),
                404);
        call(
                HttpMethod.PUT,
                share(share),
                admin,
                configuration(share, "fixed", foreign.path("revisionId").asText(), "keep", null),
                404);
        assertEquals(1, management(share).path("configVersion").asLong());
        assertSnapshot(grant, version.path("revisionId").asText(), "R1");
        String otherProject = project(admin);
        database.sql("update p_share set project_id=:project where id=:id")
                .bind("project", otherProject)
                .bind("id", share.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(
                HttpMethod.PUT,
                share(share) + "/enabled",
                admin,
                Map.of("expectedVersion", 1, "enabled", false),
                404);
        call(
                HttpMethod.POST,
                share(share) + "/rotate-link",
                admin,
                Map.of("expectedVersion", 1),
                404);
        call(HttpMethod.GET, publicPath(share), null, null, 404);
        deniedGrant(grant, 404);
    }

    @Test
    void bcryptProofCannotAuthenticateASecretReloadedAfterConcurrentPasswordReplacement()
            throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of("password", OLD_PASSWORD));
        Sinks.One<Void> matched = Sinks.one(), release = Sinks.one();
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            Mono<?> original = (Mono<?>) invocation.callRealMethod();
                            return firstCall.compareAndSet(true, false)
                                    ? original.delayUntil(
                                            ignored -> {
                                                matched.tryEmitEmpty();
                                                return release.asMono();
                                            })
                                    : original;
                        })
                .when(work)
                .call(any());
        CompletableFuture<?> verification =
                shares.getPublicInfo(share.path("token").asText(), OLD_PASSWORD).toFuture();
        try {
            matched.asMono().block(TIMEOUT);
            JsonNode changed =
                    mutate(share, "", configuration(share, "fixed", null, "replace", NEW_PASSWORD));
            release.tryEmitEmpty();
            Object stale = verification.get(30, TimeUnit.SECONDS);
            JsonNode staleGrant =
                    new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(stale);
            // 允许返回旧快照的证明，但它不能获得新 secret；已完成的编辑使旧证明立即不可用。
            deniedGrant(staleGrant, 403);
            verify(changed, OLD_PASSWORD, 403);
            assertSnapshot(
                    verify(changed, NEW_PASSWORD, 200), version.path("revisionId").asText(), "R1");
        } finally {
            release.tryEmitEmpty();
        }
    }

    @Test
    void nullableLegacyLifecycleFieldsReadAsFixedZeroAndUpgradeOnlyOnSuccessfulMutation()
            throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        database.sql(
                        "update p_share set revision_mode=null, revoked=null, config_version=null where id=:id")
                .bind("id", share.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        JsonNode legacy = management(share);
        assertEquals("fixed", legacy.path("revisionMode").asText());
        assertFalse(legacy.path("revoked").asBoolean());
        assertEquals(0, legacy.path("configVersion").asLong());
        JsonNode grant = publicInfo(legacy);
        assertSnapshot(grant, version.path("revisionId").asText(), "R1");
        JsonNode edited = mutate(legacy, "", configuration(legacy, "fixed", null, "keep", null));
        assertEquals(1, edited.path("configVersion").asLong());
        deniedGrant(grant, 403);
        assertSnapshot(publicInfo(edited), version.path("revisionId").asText(), "R1");
    }

    @Test
    void invalidConfigCannotRotateKeysOrConsumeConfigurationVersion() throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        JsonNode grant = publicInfo(share);
        for (Map<String, Object> body :
                List.of(
                        configuration(share, "unknown", null, "keep", null),
                        configuration(share, "fixed", null, "unknown", null),
                        configuration(share, "fixed", null, "replace", null),
                        configuration(share, "fixed", null, "replace", "密".repeat(25))))
            call(HttpMethod.PUT, share(share), admin, body, 400);
        Map<String, Object> missingVersion = configuration(share, "fixed", null, "keep", null);
        missingVersion.remove("expectedVersion");
        call(HttpMethod.PUT, share(share), admin, missingVersion, 400);
        assertEquals(1, management(share).path("configVersion").asLong());
        assertSnapshot(grant, version.path("revisionId").asText(), "R1");
    }

    @Test
    void canonicalShareResponsesRemainUncacheableAcrossApiPrefixCorsAndAuthorizationFailures()
            throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        JsonNode share = create(version.path("id").asText(), Map.of());
        JsonNode grant = publicInfo(share);
        String runtime = grant.path("runtimeBaseUrl").asText() + "/index.html";
        client.get()
                .uri(runtime)
                .header("Origin", "https://other.test")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
        client.options()
                .uri(runtime)
                .header("Origin", "null")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Range")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "null");
        client.get()
                .uri("/api" + publicPath(share))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
        client.get()
                .uri(grant.path("pagesUrl").asText())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store");
        mutate(share, "/enabled", Map.of("expectedVersion", 1, "enabled", false));
        for (String path :
                List.of("/api" + publicPath(share), grant.path("pagesUrl").asText(), runtime))
            client.get()
                    .uri(path)
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectHeader()
                    .valueEquals("Cache-Control", "no-store");
    }

    @Test
    void migrationAndOperationalScriptsDistinguishLatestFromUnboundFixedShares() throws Exception {
        JsonNode version = upload(project(admin), null, "R1", admin);
        String versionId = version.path("id").asText();
        JsonNode fixed = create(versionId, Map.of());
        JsonNode latest = create(versionId, Map.of("revisionMode", "latest"));
        database.sql("update p_share set revision_id=null where id=:id")
                .bind("id", fixed.path("id").asText())
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        call(HttpMethod.GET, publicPath(fixed), null, null, 404);
        assertSnapshot(publicInfo(latest), version.path("revisionId").asText(), "R1");
        String latestBefore = shareRow(latest);
        for (String script :
                List.of(
                        "prototype-hub-table-names.sql",
                        "preflight-db.sql",
                        "snapshot-db.sql",
                        "export-storage-manifest.sql"))
            DATABASE.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "tools", script).toAbsolutePath()),
                    "/tmp/" + script);
        String preflight = psql("preflight-db.sql", null);
        assertTrue(preflight.contains("shares_pending_revision_binding\n1\n"));
        assertTrue(preflight.contains("shares_following_latest\n1\n"));
        JsonNode dry =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + versionId + "/revision-migration",
                        admin,
                        Map.of("apply", false));
        assertEquals(1, dry.path("sharesToBind").asInt());
        assertEquals(latestBefore, shareRow(latest));
        JsonNode applied =
                result(
                        HttpMethod.POST,
                        "/prototype-hub/versions/" + versionId + "/revision-migration",
                        admin,
                        Map.of("apply", true));
        assertEquals(1, applied.path("sharesToBind").asInt());
        assertEquals(latestBefore, shareRow(latest));
        assertTrue(management(latest).path("revisionId").isNull());
        assertSnapshot(publicInfo(fixed), version.path("revisionId").asText(), "R1");
        assertTrue(psql("preflight-db.sql", null).contains("shares_pending_revision_binding\n0\n"));
        assertTrue(psql("export-storage-manifest.sql", null).contains("\"integrityErrors\" : 0"));
        String before = lifecycleDigest(psql("snapshot-db.sql", null));
        mutate(latest, "/enabled", Map.of("expectedVersion", 1, "enabled", false));
        assertNotEquals(before, lifecycleDigest(psql("snapshot-db.sql", null)));
        // 同一数据库中的旧 schema 没有 F03 新列，也必须继续可预检、导出及比较快照。
        database.sql("create schema share_legacy_ops").fetch().rowsUpdated().block(TIMEOUT);
        for (String table : List.of("p_project", "p_version", "p_revision", "p_share"))
            database.sql("create table share_legacy_ops." + table + " as table public." + table)
                    .fetch()
                    .rowsUpdated()
                    .block(TIMEOUT);
        database.sql(
                        "alter table share_legacy_ops.p_share drop column revision_mode, drop column revoked, drop column config_version")
                .fetch()
                .rowsUpdated()
                .block(TIMEOUT);
        assertTrue(
                psql("preflight-db.sql", "share_legacy_ops")
                        .contains("shares_pending_revision_binding\n1\n"));
        assertFalse(psql("snapshot-db.sql", "share_legacy_ops").contains("share_lifecycle,"));
        assertTrue(
                psql("export-storage-manifest.sql", "share_legacy_ops")
                        .contains("\"integrityErrors\" : 0"));
    }

    @Test
    void operationalSnapshotsAndPreflightRetainAndDistinguishSoftDeletedRows() throws Exception {
        String project = project(admin);
        JsonNode version = upload(project, null, "Retained operations", admin);
        String versionId = version.path("id").asText();
        create(versionId, Map.of());
        for (String script :
                List.of("prototype-hub-table-names.sql", "preflight-db.sql", "snapshot-db.sql"))
            DATABASE.copyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "tools", script).toAbsolutePath()),
                    "/tmp/" + script);
        String before = psql("snapshot-db.sql", null);
        result(HttpMethod.DELETE, "/prototype-hub/versions/" + versionId, admin, null);
        String afterVersion = psql("snapshot-db.sql", null);
        assertNotEquals(deletionDigest(before, "version"), deletionDigest(afterVersion, "version"));
        assertEquals(deletionDigest(before, "project"), deletionDigest(afterVersion, "project"));
        result(HttpMethod.DELETE, "/prototype-hub/projects/" + project, admin, null);
        String afterProject = psql("snapshot-db.sql", null);
        assertNotEquals(deletionDigest(before, "project"), deletionDigest(afterProject, "project"));
        String preflight = psql("preflight-db.sql", null);
        assertTrue(preflight.contains("active_projects,soft_deleted_projects\n0,1\n"));
        assertTrue(preflight.contains("active_versions,soft_deleted_versions\n0,1\n"));
    }

    private String deletionDigest(String snapshot, String type) {
        return snapshot.lines()
                .filter(line -> line.startsWith(type + "_deletion_state,"))
                .findFirst()
                .orElseThrow();
    }

    private String shareRow(JsonNode share) {
        return database.sql("select to_jsonb(s)::text as snapshot from p_share s where id=:id")
                .bind("id", share.path("id").asText())
                .map((row, metadata) -> row.get("snapshot", String.class))
                .one()
                .block(TIMEOUT);
    }

    private String psql(String script, String schema) throws Exception {
        List<String> args = new ArrayList<>();
        if (schema != null) args.addAll(List.of("env", "PGOPTIONS=-c search_path=" + schema));
        args.addAll(
                List.of(
                        "psql",
                        "-X",
                        script.startsWith("export") ? "-qAt" : "--csv",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-U",
                        DATABASE.getUsername(),
                        "-d",
                        DATABASE.getDatabaseName(),
                        "-f",
                        "/tmp/" + script));
        var result = DATABASE.execInContainer(args.toArray(String[]::new));
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout();
    }

    private String lifecycleDigest(String snapshot) {
        return snapshot.lines()
                .filter(line -> line.startsWith("share_lifecycle,"))
                .findFirst()
                .orElseThrow();
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-hub-share-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
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

    private JsonNode create(String version, Object body) {
        return result(
                HttpMethod.POST, "/prototype-hub/versions/" + version + "/shares", admin, body);
    }

    private String share(JsonNode share) {
        return "/prototype-hub/shares/" + share.path("id").asText();
    }

    private String publicPath(JsonNode share) {
        return "/prototype-hub/public/shares/" + share.path("token").asText();
    }

    private JsonNode mutate(JsonNode share, String suffix, Object body) {
        return result(
                suffix.equals("/rotate-link") || suffix.equals("/revoke")
                        ? HttpMethod.POST
                        : HttpMethod.PUT,
                share(share) + suffix,
                admin,
                body);
    }

    private JsonNode management(JsonNode share) {
        JsonNode rows =
                result(
                        HttpMethod.GET,
                        "/prototype-hub/versions/" + share.path("versionId").asText() + "/shares",
                        admin,
                        null);
        for (JsonNode row : rows) if (row.path("id").equals(share.path("id"))) return row;
        throw new AssertionError("Management share record missing");
    }

    private Map<String, Object> configuration(
            JsonNode share, String mode, String revision, String passwordAction, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("expectedVersion", share.path("configVersion").asLong());
        body.put("description", null);
        body.put("expiresAt", null);
        body.put("revisionMode", mode);
        if (revision != null) body.put("revisionId", revision);
        body.put("passwordAction", passwordAction);
        if (password != null) body.put("password", password);
        return body;
    }

    private JsonNode publicInfo(JsonNode share) {
        return result(HttpMethod.GET, publicPath(share), null, null);
    }

    private JsonNode verify(JsonNode share, String password, int status) {
        JsonNode response =
                call(
                        HttpMethod.POST,
                        publicPath(share) + "/verify",
                        null,
                        Map.of("password", password),
                        status);
        return status == 200 ? response.path("result") : response;
    }

    private void assertSnapshot(JsonNode grant, String revision, String label) {
        assertEquals(revision, grant.path("revisionId").asText());
        String base = grant.path("runtimeBaseUrl").asText();
        assertTrue(base.contains("/revisions/" + revision + "/runtime"));
        assertTrue(resource(grant.path("previewUrl").asText()).contains(label));
        assertTrue(resource(base + "/nested/page.html").contains("nested " + label));
        assertTrue(resource(base + "/assets/style.css").contains(label));
        String pages = grant.path("pagesUrl").asText();
        assertTrue(pages.contains("/revisions/" + revision + "/pages"));
        assertFalse(result(HttpMethod.GET, backend(pages), null, null).isEmpty());
    }

    private String resource(String path) {
        return client.get()
                .uri(backend(path))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueMatches("Cache-Control", ".*no-store.*")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private void deniedGrant(JsonNode grant, int status) {
        denied(grant.path("previewUrl").asText(), status);
        denied(grant.path("runtimeBaseUrl").asText() + "/assets/style.css", status);
        call(HttpMethod.GET, backend(grant.path("pagesUrl").asText()), null, null, status);
    }

    private void denied(String path, int status) {
        client.get().uri(backend(path)).exchange().expectStatus().isEqualTo(status);
    }

    private static String backend(String path) {
        return path.startsWith("/api/") ? path.substring(4) : path;
    }

    private JsonNode result(HttpMethod method, String path, String token, Object body) {
        return call(method, path, token, body, 200).path("result");
    }

    private JsonNode call(HttpMethod method, String path, String token, Object body, int status) {
        var request = client.method(method).uri(path);
        if (token != null) request.header("X-Access-Token", token);
        var response = body == null ? request.exchange() : request.bodyValue(body).exchange();
        JsonNode result =
                response.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(result);
        return result;
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
