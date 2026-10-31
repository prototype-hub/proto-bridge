package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeProjectMemberEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.ReviewMemberTextRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadCreateRequest;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadQueryRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewContextResponse.ReviewIdentity;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.hswebframework.web.system.authorization.api.entity.DimensionEntity;
import org.hswebframework.web.system.authorization.api.entity.UserEntity;
import org.hswebframework.web.system.authorization.api.service.reactive.ReactiveUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** 新增清单worker边界必须等待全部授权读取正常完成，且在项目/版本事务外执行。 */
class PrototypeReviewThreadBoundaryTest {
    @Test
    void visitorManifestWaitsForDatabaseReleaseAndRunsBeforeTransactionAndLocks() throws Exception {
        var release = Sinks.<Void>one();
        var releasing = new AtomicBoolean();
        var parsing = new AtomicBoolean();
        var locked = new AtomicBoolean();
        var inTransaction = new AtomicBoolean();
        var revisions = mock(ReactiveRepository.class);
        var query = mock(ReactiveQuery.class, RETURNS_SELF);
        when(revisions.createQuery()).thenReturn(query);
        when(query.fetch()).thenReturn(gate(revision(), releasing, release));
        var review = mock(PrototypeReviewService.class);
        var share = new PrototypeShareEntity();
        share.setId("s");
        share.setProjectId("p");
        share.setVersionId("v");
        var scope =
                new PrototypeReviewService.VisitorScope(
                        share,
                        new ReviewIdentity(
                                "g",
                                "客户",
                                null,
                                null,
                                "unverified",
                                System.currentTimeMillis() + 60000));
        when(review.requireVisitor(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(scope));
        when(review.lockedVisitor(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(
                        Mono.defer(
                                () -> {
                                    locked.set(true);
                                    return Mono.never();
                                }));
        var work = work(parsing, inTransaction);
        var transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(
                        call ->
                                Mono.defer(
                                        () -> {
                                            inTransaction.set(true);
                                            return ((Mono<?>) call.getArgument(0))
                                                    .doFinally(ignored -> inTransaction.set(false));
                                        }));
        var service =
                new PrototypeReviewThreadService(
                        review,
                        null,
                        revisions,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        work,
                        transactions,
                        null,
                        null,
                        null);
        var input = new ReviewThreadCreateRequest();
        input.setRevisionId("r");
        input.setPageId(page().pageId());
        input.setAccessKey("key");
        input.setText("文本");
        input.setClientRequestId("request");
        StepVerifier.create(service.create("token", input, "cookie"))
                .then(
                        () -> {
                            assertTrue(releasing.get());
                            assertFalse(parsing.get());
                            assertFalse(inTransaction.get());
                            assertFalse(locked.get());
                            release.tryEmitEmpty();
                        })
                .then(
                        () -> {
                            assertTrue(parsing.get());
                            assertTrue(inTransaction.get());
                            assertTrue(locked.get());
                        })
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void imagePreparationWaitsForDatabaseReleaseBeforeFileWorker(boolean creating)
            throws Exception {
        var release = Sinks.<Void>one();
        var releasing = new AtomicBoolean();
        var preparing = new AtomicBoolean();
        var revisions = mock(ReactiveRepository.class);
        var revisionQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(revisions.createQuery()).thenReturn(revisionQuery);
        when(revisionQuery.fetch())
                .thenReturn(
                        creating ? gate(revision(), releasing, release) : Flux.just(revision()));
        var threads = mock(ReactiveRepository.class);
        var threadQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(threads.createQuery()).thenReturn(threadQuery);
        when(threadQuery.fetch())
                .thenReturn(gate(new PrototypeReviewThreadEntity(), releasing, release));
        var review = mock(PrototypeReviewService.class);
        var share = new PrototypeShareEntity();
        share.setId("s");
        share.setProjectId("p");
        share.setVersionId("v");
        var identity =
                new ReviewIdentity(
                        "g", "客户", null, null, "unverified", System.currentTimeMillis() + 60000);
        when(review.requireVisitor(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new PrototypeReviewService.VisitorScope(share, identity)));
        var images = mock(PrototypeReviewImageService.class);
        when(images.upload(anyString(), anyString(), any(), any()))
                .thenAnswer(
                        call -> {
                            preparing.set(true);
                            return Mono.error(new IllegalStateException("image-boundary"));
                        });
        var service =
                new PrototypeReviewThreadService(
                        review,
                        null,
                        revisions,
                        threads,
                        null,
                        null,
                        null,
                        null,
                        null,
                        work(new AtomicBoolean(), new AtomicBoolean()),
                        null,
                        images,
                        null,
                        null);
        var input = new ReviewThreadCreateRequest();
        input.setRevisionId("r");
        input.setPageId(page().pageId());
        input.setAccessKey("key");
        input.setText("图片说明");
        input.setClientRequestId("image-key");
        var file = mock(org.springframework.http.codec.multipart.FilePart.class);
        StepVerifier.create(
                        creating
                                ? service.createImage("token", input, "cookie", file)
                                : service.imageVisitor("token", "thread", input, "cookie", file))
                .then(
                        () -> {
                            assertTrue(releasing.get());
                            assertFalse(preparing.get());
                            release.tryEmitEmpty();
                        })
                .expectErrorMatches(
                        error ->
                                error instanceof IllegalStateException
                                        && "image-boundary".equals(error.getMessage()))
                .verify(Duration.ofSeconds(5));
        assertTrue(preparing.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "administrator", "version", "project", "editor"})
    void memberAuthorizationCompletesAllReadsBeforeManifestWorker(String stage) throws Exception {
        var release = Sinks.<Void>one();
        var releasing = new AtomicBoolean();
        var parsing = new AtomicBoolean();
        var auth = mock(Authentication.class);
        var principal = new SimpleUser();
        principal.setId("u");
        principal.setUsername("member");
        principal.setName("Member");
        when(auth.getUser()).thenReturn(principal);
        when(auth.hasDimension("role", "ADMIN")).thenReturn(stage.equals("administrator"));
        var user = new UserEntity();
        user.setId("u");
        user.setStatus((byte) 1);
        var project = new PrototypeProjectEntity();
        project.setId("p");
        project.setOwnerId(stage.equals("editor") ? "owner" : "u");
        var version = new PrototypeVersionEntity();
        version.setId("v");
        version.setProjectId("p");
        var role = new DimensionEntity();
        role.setId("ADMIN");
        role.setTypeId("role");
        var member = new PrototypeProjectMemberEntity();
        member.setRole("editor");
        var projects = repository(project, stage.equals("project"), releasing, release);
        var versions = repository(version, stage.equals("version"), releasing, release);
        var dimensions = repository(role, stage.equals("administrator"), releasing, release);
        var members = repository(member, stage.equals("editor"), releasing, release);
        var users = mock(ReactiveUserService.class);
        var userRepository = mock(ReactiveRepository.class);
        var userQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(userRepository.createQuery()).thenReturn(userQuery);
        var userRows = stage.equals("user") ? gate(user, releasing, release) : Flux.just(user);
        when(userQuery.fetch()).thenReturn(userRows);
        when(users.findById("u")).thenAnswer(ignored -> userRows.take(1).singleOrEmpty());
        var dimensionUsers = mock(ReactiveRepository.class);
        var binding = mock(ReactiveQuery.class, RETURNS_SELF);
        when(dimensionUsers.createQuery()).thenReturn(binding);
        when(binding.count()).thenReturn(Mono.just(1));
        var access =
                new PrototypeProjectAccessService(
                        projects,
                        versions,
                        null,
                        users,
                        userRepository,
                        dimensions,
                        null,
                        dimensionUsers,
                        members,
                        null) {
                    @Override
                    public Mono<Actor> current() {
                        return actor(auth);
                    }
                };
        var revisions = repository(revision(), false, releasing, release);
        var threads = mock(ReactiveRepository.class);
        var threadQuery = mock(ReactiveQuery.class, RETURNS_SELF);
        when(threads.createQuery()).thenReturn(threadQuery);
        when(threadQuery.count()).thenReturn(Mono.never());
        when(threadQuery.fetch()).thenReturn(Flux.never());
        var service =
                new PrototypeReviewThreadService(
                        null,
                        access,
                        revisions,
                        threads,
                        null,
                        null,
                        null,
                        null,
                        null,
                        work(parsing, new AtomicBoolean()),
                        null,
                        null,
                        null,
                        null);
        var input = new ReviewThreadQueryRequest();
        input.setRevisionId("r");
        input.setPageId(page().pageId());
        // Stop at the actual FileWork boundary; no downstream repository is needed.
        var pending = service.queryMember("v", input);
        StepVerifier.create(pending)
                .then(
                        () -> {
                            assertTrue(releasing.get(), stage);
                            assertFalse(
                                    parsing.get(),
                                    stage + " cancellation cleanup must complete first");
                            release.tryEmitEmpty();
                        })
                .then(
                        () ->
                                assertTrue(
                                        parsing.get(),
                                        stage
                                                + " worker proceeds after normal connection completion"))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void revisionDeletedAfterManifestParsingCannotCreateAnyMessageOrReceipt() throws Exception {
        var principal = new SimpleUser();
        principal.setId("u");
        principal.setName("Member");
        var auth = mock(Authentication.class);
        when(auth.getUser()).thenReturn(principal);
        var actor = new PrototypeProjectAccessService.Actor("u", auth, false);
        var version = new PrototypeVersionEntity();
        version.setId("v");
        version.setProjectId("p");
        var access = mock(PrototypeProjectAccessService.class);
        when(access.current()).thenReturn(Mono.just(actor));
        when(access.requireVersion("v", PrototypeProjectAccessService.Action.EDIT))
                .thenReturn(Mono.just(version));
        when(access.requireVersion(actor, "v", PrototypeProjectAccessService.Action.EDIT, true))
                .thenReturn(Mono.just(version));
        when(access.lockProject("p")).thenReturn(Mono.empty());
        when(access.lockVersion("v")).thenReturn(Mono.empty());
        var revisions = mock(ReactiveRepository.class);
        var query = mock(ReactiveQuery.class, RETURNS_SELF);
        when(revisions.createQuery()).thenReturn(query);
        when(query.fetch()).thenReturn(Flux.just(revision()), Flux.empty());
        var transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        var parsing = new AtomicBoolean();
        var threads = mock(ReactiveRepository.class);
        var receipts = mock(PrototypeCreationReceiptService.class);
        var service =
                new PrototypeReviewThreadService(
                        null,
                        access,
                        revisions,
                        threads,
                        null,
                        null,
                        null,
                        null,
                        receipts,
                        work(parsing, new AtomicBoolean()),
                        transactions,
                        null,
                        null,
                        null);
        var input = new ReviewMemberTextRequest();
        input.setRevisionId("r");
        input.setPageId(page().pageId());
        input.setText("必须拒绝");
        input.setVisibility("public");
        input.setClientRequestId("request");
        StepVerifier.create(service.replyMember("v", "thread", input))
                .expectErrorSatisfies(
                        error ->
                                assertEquals(
                                        404,
                                        assertInstanceOf(PrototypeHubException.class, error)
                                                .getStatus()))
                .verify(Duration.ofSeconds(5));
        assertTrue(parsing.get());
        verify(access).lockProject("p");
        verify(access).lockVersion("v");
        verifyNoInteractions(threads, receipts);
    }

    private static FileWork work(AtomicBoolean parsing, AtomicBoolean inTransaction) {
        var work = mock(FileWork.class);
        when(work.call(any()))
                .thenAnswer(
                        call ->
                                Mono.defer(
                                        () -> {
                                            assertFalse(
                                                    inTransaction.get(),
                                                    "FileWork cannot hold a transaction connection");
                                            parsing.set(true);
                                            return Mono.fromCallable(
                                                    (Callable<?>) call.getArgument(0));
                                        }));
        return work;
    }

    private static PrototypePageInfo page() {
        return PrototypePageInfo.page("首页", "index.html");
    }

    private static PrototypeRevisionEntity revision() throws Exception {
        var row = new PrototypeRevisionEntity();
        row.setId("r");
        row.setManifestJson(
                new ObjectMapper()
                        .writeValueAsString(
                                new PrototypeManifest(
                                        1, "index.html", List.of(), List.of(page()))));
        return row;
    }

    private static ReactiveRepository repository(
            Object row, boolean gated, AtomicBoolean releasing, Sinks.One<Void> release) {
        var repository = mock(ReactiveRepository.class);
        var query = mock(ReactiveQuery.class, RETURNS_SELF);
        when(repository.createQuery()).thenReturn(query);
        var rows = gated ? gate(row, releasing, release) : Flux.just(row);
        when(query.fetch()).thenReturn(rows);
        when(query.fetchOne()).thenAnswer(ignored -> rows.take(1).singleOrEmpty());
        when(repository.findById(anyString())).thenAnswer(ignored -> rows.take(1).singleOrEmpty());
        return repository;
    }

    private static <T> Flux<T> gate(T row, AtomicBoolean releasing, Sinks.One<Void> release) {
        return Flux.usingWhen(
                Mono.just("connection"),
                ignored -> Flux.just(row),
                ignored -> {
                    releasing.set(true);
                    return release.asMono();
                },
                (ignored, error) -> release.asMono(),
                ignored -> {
                    releasing.set(true);
                    return release.asMono();
                });
    }
}
