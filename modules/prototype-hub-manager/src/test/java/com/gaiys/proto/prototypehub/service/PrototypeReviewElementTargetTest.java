package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeCreationReceiptEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewEventEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewMessageEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewRateEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeReviewThreadEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeShareEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.storage.manifest.PrototypeManifest;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import com.gaiys.proto.prototypehub.web.info.ReviewElementTarget;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadCreateRequest;
import com.gaiys.proto.prototypehub.web.response.ReviewContextResponse.ReviewIdentity;
import com.gaiys.proto.prototypehub.web.response.ReviewThreadResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 通过原线程入口验证元素元数据与文字/图片、回执和首消息的一致性；数据库事务另由真实联调覆盖。 */
class PrototypeReviewElementTargetTest {
    private static final PrototypePageInfo PAGE = PrototypePageInfo.page("首页", "index.html");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void textAndImageKeepElementMetadataAndReplayOrConflictOnOriginalKey(boolean image)
            throws Exception {
        var fixture = new Fixture();
        var input = input("element-request");
        input.setElementTarget(target("确认 Confirm"));
        var result = fixture.create(input, image).block();
        assertNotNull(result);
        assertEquals(input.getElementTarget(), result.elementTarget());
        assertNull(result.anchor());
        assertEquals(PAGE.pageId(), result.pageId());
        assertEquals("index.html", result.pagePath());
        var stored = fixture.rows.get(result.id());
        assertEquals(input.getElementTarget(), stored.getElementTarget());
        assertEquals(1, fixture.messages.size());
        assertEquals(1, fixture.events.size());
        assertEquals(result.id(), fixture.events.getFirst().getThreadId());
        assertEquals("thread.created", fixture.events.getFirst().getEventType());
        assertEquals(image ? "image-sha" : null, fixture.messages.getFirst().getImageSha256());
        assertEquals(result, fixture.create(input, image).block());
        assertEquals(1, fixture.rows.size());
        assertEquals(1, fixture.messages.size());
        assertEquals(1, fixture.events.size());
        input.setElementTarget(target("Changed hint"));
        StepVerifier.create(fixture.create(input, image))
                .expectErrorMatches(
                        error ->
                                error instanceof PrototypeHubException business
                                        && "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT"
                                                .equals(business.getCode()))
                .verify();
        assertEquals(target("确认 Confirm"), stored.getElementTarget());
        assertEquals(1, fixture.receiptRows.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyUnpositionedAndGeometryFingerprintsRemainExact(boolean geometry) throws Exception {
        var fixture = new Fixture();
        var input = input("legacy-request");
        if (geometry)
            input.setAnchor(
                    new ReviewAnchor(
                            "root-document-css-px-v1", "point", 2, 3, 0, 0, 100, 100, 390, 600));
        fixture.create(input, false).block();
        var receipts = new PrototypeCreationReceiptService();
        String scope =
                receipts.request(
                                "review",
                                "scope",
                                "scope",
                                "scope",
                                "p",
                                "v",
                                "r",
                                "s",
                                PAGE.pageId(),
                                null)
                        .fingerprint();
        var expected =
                receipts.request(
                        "guest:unverified:g",
                        "review-thread",
                        scope,
                        "legacy-request",
                        "p",
                        "v",
                        "r",
                        PAGE.pageId(),
                        "s",
                        "Text 文本");
        if (geometry) expected = expected.withPayload(input.getAnchor().canonical());
        var row = fixture.receiptRows.get(expected.id());
        assertNotNull(row);
        assertEquals(expected.fingerprint(), row.getFingerprint());
        assertNull(fixture.rows.values().iterator().next().getElementTarget());
    }

    private static ReviewThreadCreateRequest input(String key) {
        var value = new ReviewThreadCreateRequest();
        value.setRevisionId("r");
        value.setPageId(PAGE.pageId());
        value.setAccessKey("access-key");
        value.setText("Text 文本");
        value.setClientRequestId(key);
        return value;
    }

    private static ReviewElementTarget target(String hint) {
        return new ReviewElementTarget(
                "frame-element-v1",
                List.of(new ReviewElementTarget.Frame("u1_input", "child.html")),
                "u2",
                "div",
                hint,
                390,
                600);
    }

    private static class Fixture {
        final Map<String, PrototypeReviewThreadEntity> rows = new HashMap<>();
        final Map<String, PrototypeCreationReceiptEntity> receiptRows = new HashMap<>();
        final List<PrototypeReviewMessageEntity> messages = new ArrayList<>();
        final List<PrototypeReviewEventEntity> events = new ArrayList<>();
        final PrototypeReviewThreadService service;

        Fixture() throws Exception {
            var revision = new PrototypeRevisionEntity();
            revision.setManifestJson(
                    new ObjectMapper()
                            .writeValueAsString(
                                    new PrototypeManifest(
                                            1, "index.html", List.of(), List.of(PAGE))));
            var revisions = mock(ReactiveRepository.class);
            var revisionQuery = mock(ReactiveQuery.class, RETURNS_SELF);
            when(revisions.createQuery()).thenReturn(revisionQuery);
            when(revisionQuery.fetch()).thenReturn(Flux.just(revision));
            var threads = mock(ReactiveRepository.class);
            var threadQuery = mock(ReactiveQuery.class, RETURNS_SELF);
            when(threads.createQuery()).thenReturn(threadQuery);
            when(threadQuery.fetch())
                    .thenReturn(Flux.defer(() -> Flux.fromIterable(rows.values())));
            when(threads.insert(any(PrototypeReviewThreadEntity.class)))
                    .thenAnswer(
                            call ->
                                    Mono.fromSupplier(
                                            () -> {
                                                var row =
                                                        (PrototypeReviewThreadEntity)
                                                                call.getArgument(0);
                                                rows.put(row.getId(), row);
                                                return 1;
                                            }));
            var messageRepository = mock(ReactiveRepository.class);
            when(messageRepository.insert(any(PrototypeReviewMessageEntity.class)))
                    .thenAnswer(
                            call ->
                                    Mono.fromSupplier(
                                            () -> {
                                                messages.add(call.getArgument(0));
                                                return 1;
                                            }));
            var eventRepository = mock(ReactiveRepository.class);
            when(eventRepository.insert(any(PrototypeReviewEventEntity.class)))
                    .thenAnswer(
                            call ->
                                    Mono.fromSupplier(
                                            () -> {
                                                events.add(call.getArgument(0));
                                                return 1;
                                            }));
            var rates = mock(ReactiveRepository.class);
            when(rates.findById(anyString())).thenReturn(Mono.empty());
            when(rates.save(any(PrototypeReviewRateEntity.class))).thenReturn(Mono.empty());
            var receipts = spy(new PrototypeCreationReceiptService());
            doAnswer(call -> Mono.justOrEmpty(receiptRows.get(call.getArgument(0))))
                    .when(receipts)
                    .findById(anyString());
            doAnswer(
                            call ->
                                    Mono.fromSupplier(
                                            () -> {
                                                var row =
                                                        (PrototypeCreationReceiptEntity)
                                                                call.getArgument(0);
                                                receiptRows.put(row.getId(), row);
                                                return 1;
                                            }))
                    .when(receipts)
                    .insert(any(PrototypeCreationReceiptEntity.class));
            var share = new PrototypeShareEntity();
            share.setId("s");
            share.setProjectId("p");
            share.setVersionId("v");
            var visitor =
                    new PrototypeReviewService.VisitorScope(
                            share,
                            new ReviewIdentity(
                                    "g",
                                    "Customer",
                                    null,
                                    null,
                                    "unverified",
                                    System.currentTimeMillis() + 60000));
            var review = mock(PrototypeReviewService.class);
            when(review.requireVisitor("token", "r", "access-key", "cookie"))
                    .thenReturn(Mono.just(visitor));
            when(review.lockedVisitor("token", "r", "access-key", "cookie"))
                    .thenReturn(Mono.just(visitor));
            var work = mock(FileWork.class);
            when(work.call(any()))
                    .thenAnswer(call -> Mono.fromCallable((Callable<?>) call.getArgument(0)));
            var transactions = mock(TransactionalOperator.class);
            when(transactions.transactional(any(Mono.class)))
                    .thenAnswer(call -> call.getArgument(0));
            var images = mock(PrototypeReviewImageService.class);
            when(images.upload(eq("p"), eq("v"), any(FilePart.class), any()))
                    .thenAnswer(
                            call -> {
                                Function<
                                                PrototypeReviewImageService.Candidate,
                                                Mono<
                                                        PrototypeReviewImageService.Stored<
                                                                ReviewThreadResponse>>>
                                        operation = call.getArgument(3);
                                return operation
                                        .apply(
                                                new PrototypeReviewImageService.Candidate(
                                                        "image-id",
                                                        "projects/p/review-images/image-id/image",
                                                        "image/png",
                                                        10,
                                                        "image-sha",
                                                        1,
                                                        1))
                                        .map(PrototypeReviewImageService.Stored::response);
                            });
            service =
                    new PrototypeReviewThreadService(
                            review,
                            null,
                            revisions,
                            threads,
                            messageRepository,
                            eventRepository,
                            rates,
                            null,
                            receipts,
                            work,
                            transactions,
                            images,
                            null,
                            null);
        }

        Mono<ReviewThreadResponse> create(ReviewThreadCreateRequest input, boolean image) {
            return image
                    ? service.createImage("token", input, "cookie", mock(FilePart.class))
                    : service.create("token", input, "cookie");
        }
    }
}
