package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.FileCleanupService;
import com.gaiys.proto.prototypehub.service.PrototypeCreationReceiptService;
import com.gaiys.proto.prototypehub.service.PrototypeDeletionService;
import com.gaiys.proto.prototypehub.service.PrototypeInstallationService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService;
import com.gaiys.proto.prototypehub.service.PrototypeProjectService;
import com.gaiys.proto.prototypehub.service.PrototypeRevisionMigrationService;
import com.gaiys.proto.prototypehub.service.PrototypeRevisionService;
import com.gaiys.proto.prototypehub.service.PrototypeStorageService;
import com.gaiys.proto.prototypehub.service.PrototypeUploadService;
import com.gaiys.proto.prototypehub.service.PrototypeVersionService;
import com.gaiys.proto.prototypehub.storage.builder.PrototypeManifestBuilder;
import com.gaiys.proto.prototypehub.storage.extractor.SecureZipExtractor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.request.ProjectRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hswebframework.web.api.crud.entity.TransactionManagers;
import org.hswebframework.web.authorization.token.ParsedToken;
import org.hswebframework.web.authorization.token.SimpleParsedToken;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.ReactiveTransaction;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** 固定 5/5 容量、持久化身份及创建重试的真实 PostgreSQL/HTTP/文件系统闭环。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=classpath:application-default.yml",
            "proto.redis.enabled=false",
            "proto.logging.elasticsearch.enabled=false",
            "proto.security.login-encryption-enabled=false",
            "CORS_ENABLED=true",
            "CORS_ALLOWED_ORIGINS=http://localhost:5173",
            "prototype-hub.cleanup.interval-ms=3600000"
        })
@Testcontainers
class PrototypeHubCapacityIntegrationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private String actor;
    private String otherActor;
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static final Path FILES = temporaryStorage();

    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.r2dbc.url",
                () ->
                        "r2dbc:postgresql://"
                                + DATABASE.getHost()
                                + ":"
                                + DATABASE.getMappedPort(5432)
                                + "/"
                                + DATABASE.getDatabaseName());
        registry.add("spring.r2dbc.username", DATABASE::getUsername);
        registry.add("spring.r2dbc.password", DATABASE::getPassword);
        registry.add("proto.admin.password", () -> PASSWORD);
        registry.add("prototype-hub.storage.root", () -> FILES.toString());
    }

    @Autowired PrototypeProjectService projects;
    @Autowired PrototypeProjectAccessService access;
    @Autowired ReactiveUserService users;
    @Autowired PrototypeVersionService versions;
    @Autowired PrototypeUploadService uploads;
    @Autowired PrototypeRevisionService revisions;
    @Autowired PrototypeRevisionMigrationService revisionMigration;
    @Autowired PrototypeManifestBuilder manifests;
    @Autowired PrototypeDeletionService deletion;
    @Autowired PrototypeCreationReceiptService receipts;
    @Autowired PrototypeInstallationService installations;
    @Autowired PrototypeStorageService storage;
    @Autowired SecureZipExtractor extractor;
    @Autowired FileWork work;
    @Autowired FileCleanupService cleanup;

    @Autowired
    @Qualifier(TransactionManagers.reactiveTransactionManager)
    ReactiveTransactionManager transactionManager;

    @Autowired DatabaseClient database;
    @Autowired ApplicationContext context;
    @Autowired WebTestClient client;
    private String admin;

    @BeforeEach
    void isolateBusinessData() throws IOException {
        PrototypeHubTestData.clear(database);
        SecureZipExtractor.deleteRecursively(FILES);
        Files.createDirectories(FILES);
        client = client.mutate().responseTimeout(TIMEOUT).build();
        admin =
                response(
                                client.post()
                                        .uri("/authorize/login")
                                        .bodyValue(
                                                Map.of("username", "admin", "password", PASSWORD))
                                        .exchange(),
                                200)
                        .path("result")
                        .path("token")
                        .asText();
        assertFalse(admin.isBlank());
        actor = users.findByUsername("admin").block(TIMEOUT).getId();
        var second = new UserEntity();
        second.setUsername("capacity-other-" + UUID.randomUUID());
        second.setName("Capacity second actor");
        second.setPassword(PASSWORD);
        second.setStatus((byte) 1);
        otherActor = users.addUser(second).block(TIMEOUT).getId();
    }

    @AfterAll
    static void removeFiles() throws IOException {
        SecureZipExtractor.deleteRecursively(FILES);
    }

    @Test
    void installationIdentitySurvivesConcurrentInitializationAndNewServiceInstances() {
        String original = installations.current().block(TIMEOUT).getInstallationId();
        var replica =
                context.getAutowireCapableBeanFactory()
                        .createBean(PrototypeInstallationService.class);
        assertNotSame(installations, replica);
        List<String> identities =
                Flux.range(0, 8)
                        .flatMap(i -> (i % 2 == 0 ? installations : replica).initialize())
                        .map(row -> row.getInstallationId())
                        .collectList()
                        .block(TIMEOUT);
        assertEquals(List.of(original), identities.stream().distinct().toList());
        assertEquals(1, count("p_installation"));
        assertEquals(original, projects.usage().block(TIMEOUT).installationId());
    }

    @Test
    void independentProjectServicesCannotBothTakeTheLastProjectSlot() {
        createProjects(4);
        // 独立服务实例共用真实连接池；每次创建拥有独立事务，不能依靠 JVM 内锁通过此测试。
        var replica =
                context.getAutowireCapableBeanFactory().createBean(PrototypeProjectService.class);
        assertNotSame(projects, replica);
        var requests = new ArrayList<Mono<PrototypeProjectEntity>>();
        for (int i = 0; i < 8; i++) {
            requests.add(
                    asAdmin((i % 2 == 0 ? projects : replica).create(input("race-" + i), actor)));
        }
        assertLastSlot(concurrent(requests), "PROTOTYPE_HUB_PROJECT_LIMIT_REACHED");
        assertEquals(5, count("p_project"));
        assertFalse(projects.usage().block(TIMEOUT).projects().canCreate());
        assertEquals(0, count("p_creation_receipt"));
    }

    @Test
    void independentUploadServicesCannotBothTakeTheLastVersionSlot() throws Exception {
        String project = project();
        byte[] zip = zip("original");
        for (int i = 0; i < 4; i++) upload(project, "v" + i, zip, null).block(TIMEOUT);
        var replica =
                context.getAutowireCapableBeanFactory().createBean(PrototypeUploadService.class);
        assertNotSame(uploads, replica);
        var requests = new ArrayList<Mono<PrototypeVersionEntity>>();
        for (int i = 0; i < 8; i++) {
            requests.add(
                    asAdmin(
                            (i % 2 == 0 ? uploads : replica)
                                    .upload(project, "race-" + i, null, file(zip), actor)));
        }
        assertLastSlot(concurrent(requests), "PROTOTYPE_HUB_VERSION_LIMIT_REACHED");
        assertEquals(5, count("p_version"));
        assertEquals(5, directoryCount(project));
        assertFalse(versions.usage(project).block(TIMEOUT).versions().canCreate());
    }

    @Test
    void projectRetriesReplayBeforeQuotaAndBindTheKeyToActorAndPayload() {
        createProjects(4);
        var replica =
                context.getAutowireCapableBeanFactory().createBean(PrototypeProjectService.class);
        var results =
                concurrent(
                        List.of(
                                asAdmin(projects.create(input(" project "), actor, "same-key")),
                                asAdmin(replica.create(input("project"), actor, "same-key"))));
        assertTrue(results.stream().allMatch(Signal::isOnNext));
        String id = results.getFirst().get().getId();
        assertEquals(id, results.getLast().get().getId());
        assertEquals(5, count("p_project"));
        assertEquals(1, count("p_creation_receipt"));
        assertEquals(
                id,
                asAdmin(projects.create(input("project"), actor, "same-key"))
                        .block(TIMEOUT)
                        .getId());
        expectCode(
                asAdmin(projects.create(input("different"), actor, "same-key")),
                "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT");
        asAdmin(deletion.deleteProject(id)).block(TIMEOUT);
        expectCode(
                asAdmin(projects.create(input("project"), actor, "same-key")),
                "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT");
        assertEquals(4, count("p_project"));
        assertNotEquals(
                id,
                asAdmin(projects.create(input("project"), otherActor, "same-key"))
                        .block(TIMEOUT)
                        .getId());
        assertEquals(5, count("p_project"));
    }

    @Test
    void versionRetriesBindZipBytesAndProjectAndCleanSuccessfulReplayDirectories()
            throws Exception {
        String project = project();
        byte[] zip = zip("original");
        var replica =
                context.getAutowireCapableBeanFactory().createBean(PrototypeUploadService.class);
        var results =
                concurrent(
                        List.of(
                                upload(project, "release", zip, "same-key"),
                                asAdmin(
                                        replica.upload(
                                                project,
                                                "release",
                                                null,
                                                file(zip),
                                                actor,
                                                "same-key"))));
        assertTrue(results.stream().allMatch(Signal::isOnNext));
        String id = results.getFirst().get().getId();
        assertEquals(id, results.getLast().get().getId());
        assertEquals(1, directoryCount(project));
        for (int i = 0; i < 4; i++) upload(project, "v" + i, zip, null).block(TIMEOUT);
        assertEquals(id, upload(project, "release", zip, "same-key").block(TIMEOUT).getId());
        expectCode(
                upload(project, "different", zip, "same-key"),
                "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT");
        expectCode(
                upload(project, "release", zip("changed"), "same-key"),
                "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT");
        assertEquals(5, directoryCount(project));
        assertEquals(5, count("p_version"));
        String another = project();
        assertNotEquals(id, upload(another, "release", zip, "same-key").block(TIMEOUT).getId());
        asAdmin(deletion.deleteVersion(id)).block(TIMEOUT);
        cleanup.retryPending().block(TIMEOUT);
        expectCode(
                upload(project, "release", zip, "same-key"), "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT");
        assertEquals(4, directoryCount(project));
        assertEquals(5, count("p_version"));
    }

    @Test
    void versionReplayAfterItsProjectWasDeletedIsAConflictAndDoesNotResurrectIt() throws Exception {
        String project = project();
        byte[] zip = zip("original");
        var original = upload(project, "release", zip, "deleted-project-key").block(TIMEOUT);
        asAdmin(deletion.deleteVersion(original.getId())).block(TIMEOUT);
        asAdmin(deletion.deleteProject(project)).block(TIMEOUT);
        expectCode(
                upload(project, "release", zip, "deleted-project-key"),
                "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT");
        assertEquals(0, count("p_project"));
        assertEquals(0, count("p_version"));
        assertEquals(0, directoryCount(project));
    }

    @Test
    void failedReceiptInsertRollsBackBothResourcesAndFilesAndAllowsTheSameKeysToRetry()
            throws Exception {
        String project = project();
        byte[] zip = zip("original");
        rejectMutation("p_creation_receipt", "insert");
        try {
            StepVerifier.create(asAdmin(projects.create(input("rollback"), actor, "project-retry")))
                    .expectError()
                    .verify(TIMEOUT);
            StepVerifier.create(upload(project, "rollback", zip, "version-retry"))
                    .expectError()
                    .verify(TIMEOUT);
            assertEquals(1, count("p_project"));
            assertEquals(0, count("p_version"));
            assertEquals(0, count("p_creation_receipt"));
            assertEquals(0, directoryCount(project));
        } finally {
            allowMutation("p_creation_receipt");
        }
        asAdmin(projects.create(input("rollback"), actor, "project-retry")).block(TIMEOUT);
        upload(project, "rollback", zip, "version-retry").block(TIMEOUT);
        assertEquals(2, count("p_project"));
        assertEquals(1, count("p_version"));
        assertEquals(2, count("p_creation_receipt"));
        assertEquals(1, directoryCount(project));
    }

    @Test
    void onlyCommittedDeletionReleasesProjectAndVersionCapacity() throws Exception {
        var rows = createProjects(5);
        String empty = rows.getLast().getId();
        rejectMutation("p_project", "delete");
        try {
            StepVerifier.create(asAdmin(deletion.deleteProject(empty)))
                    .expectError()
                    .verify(TIMEOUT);
            assertEquals(5, count("p_project"));
            expectCode(
                    asAdmin(projects.create(input("denied"), actor)),
                    "PROTOTYPE_HUB_PROJECT_LIMIT_REACHED");
        } finally {
            allowMutation("p_project");
        }
        asAdmin(deletion.deleteProject(empty)).block(TIMEOUT);
        assertTrue(projects.usage().block(TIMEOUT).projects().canCreate());
        asAdmin(projects.create(input("reclaimed"), actor)).block(TIMEOUT);
        String project = rows.getFirst().getId();
        byte[] zip = zip("original");
        PrototypeVersionEntity version = null;
        for (int i = 0; i < 5; i++) version = upload(project, "v" + i, zip, null).block(TIMEOUT);
        rejectMutation("p_version", "delete");
        try {
            StepVerifier.create(asAdmin(deletion.deleteVersion(version.getId())))
                    .expectError()
                    .verify(TIMEOUT);
            expectCode(upload(project, "denied", zip, null), "PROTOTYPE_HUB_VERSION_LIMIT_REACHED");
            assertEquals(5, directoryCount(project));
        } finally {
            allowMutation("p_version");
        }
        asAdmin(deletion.deleteVersion(version.getId())).block(TIMEOUT);
        cleanup.retryPending().block(TIMEOUT);
        assertEquals(4, versions.usage(project).block(TIMEOUT).versions().used());
        upload(project, "reclaimed", zip, null).block(TIMEOUT);
        assertEquals(5, count("p_version"));
        assertEquals(5, directoryCount(project));
    }

    @Test
    void existingOverLimitDataCanStartBeReadReplacedAndDeletedWithoutBeingPruned()
            throws Exception {
        var rows = createProjects(5);
        PrototypeProjectEntity extra = rows.getFirst().copyTo(new PrototypeProjectEntity());
        extra.setId(UUID.randomUUID().toString().replace("-", ""));
        projects.insert(extra).block(TIMEOUT); // 模拟已有库或备份恢复，不能借业务配置关闭额度。
        String project = rows.getFirst().getId();
        var oldVersions = new ArrayList<PrototypeVersionEntity>();
        for (int i = 0; i < 6; i++) oldVersions.add(seedVersion(project, i));
        String identity = installations.current().block(TIMEOUT).getInstallationId();
        assertEquals(identity, installations.initialize().block(TIMEOUT).getInstallationId());
        var projectUsage = projects.usage().block(TIMEOUT).projects();
        assertEquals(6, projectUsage.used());
        assertEquals(0, projectUsage.remaining());
        assertTrue(projectUsage.overLimit());
        assertFalse(projectUsage.canCreate());
        assertTrue(versions.usage(project).block(TIMEOUT).versions().overLimit());
        expectCode(
                asAdmin(projects.create(input("denied"), actor)),
                "PROTOTYPE_HUB_PROJECT_LIMIT_REACHED");
        expectCode(
                upload(project, "denied", zip("denied"), null),
                "PROTOTYPE_HUB_VERSION_LIMIT_REACHED");
        var old = oldVersions.getFirst();
        asAdmin(revisionMigration.migrate(old.getId(), true)).block(TIMEOUT);
        var replaced =
                asAdmin(uploads.update(old.getId(), "replacement", null, file(zip("replacement"))))
                        .block(TIMEOUT);
        assertEquals(old.getId(), replaced.getId());
        assertEquals(old.getPreviewKey(), replaced.getPreviewKey());
        assertTrue(
                Files.readString(FILES.resolve(replaced.getStoragePath()).resolve("index.html"))
                        .contains("replacement"));
        assertEquals(6, count("p_project"));
        assertEquals(6, count("p_version"));
        assertEquals(7, directoryCount(project));
        for (var row : rows) assertNotNull(projects.requireById(row.getId()).block(TIMEOUT));
        for (var row : oldVersions) assertNotNull(versions.requireById(row.getId()).block(TIMEOUT));
        asAdmin(deletion.deleteVersion(oldVersions.getLast().getId())).block(TIMEOUT);
        assertFalse(versions.usage(project).block(TIMEOUT).versions().canCreate());
        asAdmin(deletion.deleteVersion(oldVersions.get(4).getId())).block(TIMEOUT);
        assertTrue(versions.usage(project).block(TIMEOUT).versions().canCreate());
        upload(project, "reclaimed", zip("new"), null).block(TIMEOUT);
        asAdmin(deletion.deleteProject(rows.getLast().getId())).block(TIMEOUT);
        assertFalse(projects.usage().block(TIMEOUT).projects().canCreate());
        asAdmin(deletion.deleteProject(rows.get(3).getId())).block(TIMEOUT);
        asAdmin(projects.create(input("reclaimed"), actor)).block(TIMEOUT);
        assertEquals(5, count("p_project"));
    }

    @Test
    void httpUsageAndOptionalIdempotencyHeadersFollowTheExistingPermissionBoundary()
            throws Exception {
        client.get()
                .uri("/prototype-hub/projects/_usage")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/prototype-hub/versions/_usage?projectId=missing")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        String project = projectHttp("HTTP project", "http-project-key", 200).path("id").asText();
        for (String path :
                List.of(
                        "/prototype-hub/projects",
                        "/prototype-hub/projects/" + project + "/versions")) {
            client.options()
                    .uri(path)
                    .header("Origin", "http://localhost:5173")
                    .header("Access-Control-Request-Method", "POST")
                    .header(
                            "Access-Control-Request-Headers",
                            "content-type,x-access-token,idempotency-key")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .value(
                            "Access-Control-Allow-Headers",
                            value ->
                                    assertTrue(
                                            value.toLowerCase(java.util.Locale.ROOT)
                                                    .contains("idempotency-key")));
        }
        assertEquals(
                project, projectHttp("HTTP project", "http-project-key", 200).path("id").asText());
        projectHttp("changed", "http-project-key", 409);
        projectHttp("invalid", "invalid key", 400);
        byte[] zip = zip("HTTP");
        String version =
                uploadHttp(HttpMethod.POST, project, "http-version-key", zip, 200)
                        .path("id")
                        .asText();
        assertEquals(
                version,
                uploadHttp(HttpMethod.POST, project, "http-version-key", zip, 200)
                        .path("id")
                        .asText());
        var projectUsage =
                response(
                                client.get()
                                        .uri("/prototype-hub/projects/_usage")
                                        .header("X-Access-Token", admin)
                                        .exchange(),
                                200)
                        .path("result");
        assertEquals(
                installations.current().block(TIMEOUT).getInstallationId(),
                projectUsage.path("installationId").asText());
        assertEquals(1, projectUsage.path("projects").path("used").asLong());
        assertEquals(5, projectUsage.path("projects").path("limit").asInt());
        assertEquals(4, projectUsage.path("projects").path("remaining").asLong());
        assertTrue(projectUsage.path("projects").path("canCreate").asBoolean());
        var versionUsage =
                response(
                                client.get()
                                        .uri("/prototype-hub/versions/_usage?projectId=" + project)
                                        .header("X-Access-Token", admin)
                                        .exchange(),
                                200)
                        .path("result");
        assertEquals(project, versionUsage.path("projectId").asText());
        assertEquals(1, versionUsage.path("versions").path("used").asLong());
        response(
                client.get()
                        .uri("/prototype-hub/versions/_usage?projectId=missing")
                        .header("X-Access-Token", admin)
                        .exchange(),
                404);
        assertEquals(1, directoryCount(project));
    }

    @ParameterizedTest(name = "cancel while commit is pending, replace={0}")
    @ValueSource(booleans = {false, true})
    void cancellationDuringCommitRetainsTheFilesOwnedByTheCommittedVersion(boolean replace)
            throws Exception {
        String project = project();
        PrototypeVersionEntity original =
                replace ? upload(project, "original", zip("original"), null).block(TIMEOUT) : null;
        var commit = new CommitGate(transactionManager);
        var observedStorage = spy(storage);
        AtomicBoolean deletionSubscribed = new AtomicBoolean();
        Sinks.One<Void> deletionFinished = Sinks.one();
        doAnswer(
                        invocation -> {
                            Mono<Void> realDeletion =
                                    storage.deleteVersion(
                                            invocation.getArgument(0), invocation.getArgument(1));
                            return realDeletion
                                    .doOnSubscribe(ignored -> deletionSubscribed.set(true))
                                    .doFinally(ignored -> deletionFinished.tryEmitEmpty());
                        })
                .when(observedStorage)
                .deleteVersion(anyString(), anyString());
        var service =
                new PrototypeUploadService(
                        projects,
                        versions,
                        observedStorage,
                        extractor,
                        work,
                        TransactionalOperator.create(commit),
                        cleanup,
                        receipts,
                        access,
                        revisions,
                        manifests);
        Mono<PrototypeVersionEntity> operation =
                replace
                        ? asAdmin(
                                service.update(
                                        original.getId(),
                                        "committed",
                                        null,
                                        file(zip("committed"))))
                        : asAdmin(
                                service.upload(
                                        project,
                                        "committed",
                                        null,
                                        file(zip("committed")),
                                        actor,
                                        "commit-key"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var subscription = operation.subscribe(ignored -> {}, failure::set);
        boolean deletedBeforeOutcome;
        try {
            commit.entered.asMono().block(TIMEOUT);
            // 真实 PG 尚未提交：新增不可见，替换仍能读到旧目录；取消发生在 asyncComplete 内。
            if (replace) {
                assertEquals(
                        original.getStoragePath(),
                        versions.requireById(original.getId()).block(TIMEOUT).getStoragePath());
            } else {
                assertEquals(0, count("p_version"));
            }
            subscription.dispose();
            deletedBeforeOutcome = deletionSubscribed.get();
        } finally {
            subscription.dispose();
            commit.release.tryEmitEmpty();
            commit.finished.asMono().block(TIMEOUT);
        }
        if (deletionSubscribed.get()) deletionFinished.asMono().block(TIMEOUT);
        var persisted = versions.findByProjectId(project).single().block(TIMEOUT);
        assertNotNull(persisted);
        assertAll(
                () -> assertFalse(deletedBeforeOutcome, "事务结果未知时不得订阅临时目录删除操作"),
                () -> assertFalse(deletionSubscribed.get(), "已提交版本的新目录不得在取消清理中删除"),
                () ->
                        assertTrue(
                                Files.exists(
                                        FILES.resolve(persisted.getStoragePath())
                                                .resolve("index.html")),
                                "已提交的版本必须仍有可读取的原型文件"),
                () -> assertNull(failure.get()));
        assertEquals("committed", persisted.getVersionName());
        if (replace) {
            assertEquals(original.getId(), persisted.getId());
            assertTrue(
                    Files.exists(FILES.resolve(original.getStoragePath()).resolve("index.html")));
            assertEquals(2, count("p_revision"));
        } else assertEquals(1, count("p_creation_receipt"));
    }

    /** 门控的是事务管理器的公共 commit Publisher，不更改数据库或 Reactor 内部实现。 */
    private static final class CommitGate implements ReactiveTransactionManager {
        private final ReactiveTransactionManager delegate;
        private final Sinks.One<Void> entered = Sinks.one();
        private final Sinks.One<Void> release = Sinks.one();
        private final Sinks.One<Void> finished = Sinks.one();

        private CommitGate(ReactiveTransactionManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<ReactiveTransaction> getReactiveTransaction(TransactionDefinition definition) {
            return delegate.getReactiveTransaction(definition);
        }

        @Override
        public Mono<Void> commit(ReactiveTransaction transaction) {
            return Mono.defer(
                    () -> {
                        entered.tryEmitEmpty();
                        return release.asMono()
                                .then(Mono.defer(() -> delegate.commit(transaction)))
                                .doOnSuccess(ignored -> finished.tryEmitEmpty())
                                .doOnError(error -> finished.tryEmitError(error));
                    });
        }

        @Override
        public Mono<Void> rollback(ReactiveTransaction transaction) {
            return delegate.rollback(transaction);
        }
    }

    private List<PrototypeProjectEntity> createProjects(int count) {
        return Flux.range(0, count)
                .concatMap(i -> asAdmin(projects.create(input("project-" + i), actor)))
                .collectList()
                .block(TIMEOUT);
    }

    private String project() {
        return asAdmin(projects.create(input("project"), actor)).block(TIMEOUT).getId();
    }

    /** 复用真实登录令牌与框架 ParsedToken 扩展，不替换权限供应方或关闭生产校验。 */
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

    private static ProjectRequest input(String name) {
        return new ProjectRequest(name, null, null);
    }

    private Mono<PrototypeVersionEntity> upload(
            String project, String name, byte[] zip, String key) {
        return asAdmin(uploads.upload(project, name, null, file(zip), actor, key));
    }

    private static FilePart file(byte[] zip) {
        FilePart file = mock(FilePart.class);
        when(file.filename()).thenReturn("prototype.zip");
        when(file.content())
                .thenAnswer(ignored -> Flux.just(new DefaultDataBufferFactory().wrap(zip)));
        return file;
    }

    private static byte[] zip(String content) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            output.putNextEntry(new ZipEntry("index.html"));
            output.write(
                    ("<html><body>" + content + "</body></html>").getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private PrototypeVersionEntity seedVersion(String project, int number) throws IOException {
        var version = new PrototypeVersionEntity();
        version.setId(UUID.randomUUID().toString().replace("-", ""));
        version.setProjectId(project);
        version.setVersionName("legacy-" + number);
        version.setStoragePath("projects/" + project + "/versions/" + version.getId() + "/site");
        version.setEntryFile("index.html");
        version.setPreviewKey(UUID.randomUUID().toString());
        version.setStatus("ready");
        version.setCreatorId(actor);
        version.setCreateTime(System.currentTimeMillis());
        Path site = FILES.resolve(version.getStoragePath());
        Files.createDirectories(site);
        Files.writeString(site.resolve("index.html"), "<html>legacy</html>");
        versions.insert(version).block(TIMEOUT);
        return version;
    }

    private long directoryCount(String project) throws IOException {
        Path directories = FILES.resolve("projects/" + project + "/versions");
        if (!Files.exists(directories)) return 0;
        try (var paths = Files.list(directories)) {
            return paths.count();
        }
    }

    private long count(String table) {
        return database.sql("select count(*) from " + table)
                .map((row, metadata) -> ((Number) row.get(0)).longValue())
                .one()
                .block(TIMEOUT);
    }

    private void rejectMutation(String table, String event) {
        sql(
                "create function reject_capacity_mutation() returns trigger language plpgsql as $$"
                        + " begin raise exception 'controlled capacity failure'; end $$");
        sql(
                "create trigger reject_capacity_mutation before "
                        + event
                        + " on "
                        + table
                        + " for each row execute function reject_capacity_mutation()");
    }

    private void allowMutation(String table) {
        sql("drop trigger reject_capacity_mutation on " + table);
        sql("drop function reject_capacity_mutation()");
    }

    private void sql(String statement) {
        database.sql(statement).fetch().rowsUpdated().block(TIMEOUT);
    }

    private static <T> List<Signal<T>> concurrent(List<Mono<T>> requests) {
        return Flux.merge(requests.stream().map(Mono::materialize).toList())
                .collectList()
                .block(TIMEOUT);
    }

    private static void assertLastSlot(List<? extends Signal<?>> results, String code) {
        assertEquals(1, results.stream().filter(Signal::isOnNext).count());
        assertEquals(results.size() - 1, results.stream().filter(Signal::isOnError).count());
        results.stream()
                .filter(Signal::isOnError)
                .forEach(signal -> assertCode(signal.getThrowable(), code));
    }

    private static void expectCode(Mono<?> operation, String code) {
        StepVerifier.create(operation)
                .expectErrorSatisfies(error -> assertCode(error, code))
                .verify(TIMEOUT);
    }

    private static void assertCode(Throwable error, String code) {
        PrototypeHubException failure = assertInstanceOf(PrototypeHubException.class, error);
        assertEquals(409, failure.getStatus());
        assertEquals(code, failure.getCode());
    }

    private JsonNode projectHttp(String name, String key, int status) {
        return response(
                        client.post()
                                .uri("/prototype-hub/projects")
                                .header("X-Access-Token", admin)
                                .header("Idempotency-Key", key)
                                .bodyValue(Map.of("name", name))
                                .exchange(),
                        status)
                .path("result");
    }

    private JsonNode uploadHttp(
            HttpMethod method, String project, String key, byte[] zip, int status) {
        var body = new MultipartBodyBuilder();
        body.part("versionName", "HTTP version");
        body.part("file", new ByteArrayResource(zip)).filename("prototype.zip");
        return response(
                        client.method(method)
                                .uri("/prototype-hub/projects/" + project + "/versions")
                                .header("X-Access-Token", admin)
                                .header("Idempotency-Key", key)
                                .body(BodyInserters.fromMultipartData(body.build()))
                                .exchange(),
                        status)
                .path("result");
    }

    private static JsonNode response(WebTestClient.ResponseSpec response, int status) {
        JsonNode body =
                response.expectStatus()
                        .isEqualTo(status)
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertNotNull(body);
        return body;
    }

    private static Path temporaryStorage() {
        try {
            return Files.createTempDirectory("prototype-hub-capacity-");
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
