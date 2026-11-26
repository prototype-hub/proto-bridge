package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeViewportCheckEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.DeliveryDocumentRequests;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({"unchecked", "rawtypes"})
class PrototypeDeliveryDocumentServiceTest {
    final String id = "a".repeat(64);

    @Test
    void readerCanAssembleAndExportActualParentHtmlPageWithoutEditOrWrites() {
        try (var work = new FileWork()) {
            var f = new Fixture(work);
            var input = input();
            var doc = f.service.assemble("v", "old", input).block(Duration.ofSeconds(5));
            assertEquals("old", doc.revisionId());
            assertEquals("实际父HTML页", doc.pages().getFirst().name());
            input.getPages().getFirst().setBody("会话文字");
            input.getPages()
                    .getFirst()
                    .setSourceFingerprint(doc.pages().getFirst().sourceFingerprint());
            var bytes = f.service.export("v", "old", input, "html").block(Duration.ofSeconds(5));
            assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("会话文字"));
            verify(f.access, never())
                    .requireVersion(any(), anyString(), eq(Action.EDIT), anyBoolean());
            verifyNoInteractions(f.descriptions, f.materials, f.checks, f.files);
        }
    }

    @Test
    void finalRevocationAfterRenderReturnsNoExportAndDoesNotUseCachedActor() {
        try (var work = new FileWork()) {
            var f = new Fixture(work);
            var input = input();
            var doc = f.service.assemble("v", "old", input).block(Duration.ofSeconds(5));
            input.getPages().getFirst().setBody("preserve");
            input.getPages()
                    .getFirst()
                    .setSourceFingerprint(doc.pages().getFirst().sourceFingerprint());
            var count = new AtomicInteger();
            when(f.access.actor("u"))
                    .thenAnswer(
                            ignored ->
                                    Mono.defer(
                                            () ->
                                                    count.incrementAndGet() == 3
                                                            ? Mono.error(
                                                                    PrototypeProjectAccessService
                                                                            .roleDenied())
                                                            : Mono.just(f.actor)));
            StepVerifier.create(f.service.export("v", "old", input, "html"))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException failure
                                            && failure.getStatus() == 403)
                    .verify(Duration.ofSeconds(5));
            assertEquals(3, count.get());
            assertEquals("preserve", input.getPages().getFirst().getBody());
        }
    }

    @Test
    void fixedQuerySelectedSourcesChangeDuringWorkRejectsAndNeverOverwritesDraft() {
        try (var work = new FileWork()) {
            var f = new Fixture(work);
            var query = mock(ReactiveQuery.class, RETURNS_SELF);
            when(f.descriptions.createQuery()).thenReturn(query);
            var first = description("old text");
            var changed = description("new text");
            when(query.fetch()).thenReturn(Flux.just(first), Flux.just(changed));
            var input = input();
            var ref = new DeliveryDocumentRequests.SourceRef();
            ref.setId("d");
            ref.setExpectedVersion(0L);
            input.getPages().getFirst().setDescriptions(List.of(ref));
            input.getPages().getFirst().setBody("keep draft");
            StepVerifier.create(f.service.assemble("v", "old", input))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException failure
                                            && failure.getStatus() == 409
                                            && failure.getCode()
                                                    .equals(
                                                            "PROTOTYPE_HUB_DELIVERY_DOCUMENT_SOURCE_CHANGED"))
                    .verify(Duration.ofSeconds(5));
            assertEquals("keep draft", input.getPages().getFirst().getBody());
            verify(query, times(2)).paging(0, 200);
            verifyNoInteractions(f.materials, f.checks, f.files);
        }
    }

    @Test
    void missingSelectedSourceIsConflictAndFoldersOrFakePagesCannotReachSourceQueries() {
        try (var work = new FileWork()) {
            var f = new Fixture(work);
            var input = input();
            input.getPages().getFirst().setPageId("c".repeat(64));
            StepVerifier.create(f.service.assemble("v", "old", input))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException failure
                                            && failure.getStatus() == 404)
                    .verify(Duration.ofSeconds(5));
            verifyNoInteractions(f.descriptions, f.materials, f.checks);
            input.getPages().getFirst().setPageId(id);
            var query = mock(ReactiveQuery.class, RETURNS_SELF);
            when(f.descriptions.createQuery()).thenReturn(query);
            when(query.fetch()).thenReturn(Flux.empty());
            var ref = new DeliveryDocumentRequests.SourceRef();
            ref.setId("deleted");
            ref.setExpectedVersion(0L);
            input.getPages().getFirst().setDescriptions(List.of(ref));
            StepVerifier.create(f.service.assemble("v", "old", input))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException failure
                                            && failure.getStatus() == 409)
                    .verify(Duration.ofSeconds(5));
        }
    }

    private PrototypeElementDescriptionEntity description(String text) {
        var d = new PrototypeElementDescriptionEntity();
        d.setId("d");
        d.setPageId(id);
        d.setConfigVersion(0L);
        d.setTitle(text);
        return d;
    }

    private DeliveryDocumentRequests.Document input() {
        var d = new DeliveryDocumentRequests.Document();
        d.setTitle("说明书");
        var p = new DeliveryDocumentRequests.Page();
        p.setPageId(id);
        d.setPages(List.of(p));
        return d;
    }

    private class Fixture {
        final ReactiveRepository<PrototypeElementDescriptionEntity, String> descriptions =
                mock(ReactiveRepository.class);
        final ReactiveRepository<PrototypeRevisionMaterialEntity, String> materials =
                mock(ReactiveRepository.class);
        final ReactiveRepository<PrototypeViewportCheckEntity, String> checks =
                mock(ReactiveRepository.class);
        final PrototypeProjectAccessService access = mock(PrototypeProjectAccessService.class);
        final PrototypeRevisionService revisions = mock(PrototypeRevisionService.class);
        final PrototypeRevisionMaterialFileService files =
                mock(PrototypeRevisionMaterialFileService.class);
        final Actor actor;
        final PrototypeDeliveryDocumentService service;

        Fixture(FileWork work) {
            var authentication = mock(Authentication.class);
            when(authentication.hasPermission("prototype-hub-version", "query")).thenReturn(true);
            actor = new Actor("u", authentication, false);
            when(access.current()).thenReturn(Mono.just(actor));
            when(access.actor("u")).thenReturn(Mono.just(actor));
            var version = new PrototypeVersionEntity();
            version.setId("v");
            version.setProjectId("p");
            version.setCurrentRevisionId("new");
            when(access.requireVersion(actor, "v", Action.READ, false))
                    .thenReturn(Mono.just(version));
            var revision = new PrototypeRevisionEntity();
            revision.setId("old");
            revision.setProjectId("p");
            revision.setVersionId("v");
            when(revisions.require(version, "old")).thenReturn(Mono.just(revision));
            when(revisions.pages(revision))
                    .thenReturn(
                            Mono.just(
                                    List.of(
                                            new PrototypePageInfo(
                                                    "目录",
                                                    null,
                                                    null,
                                                    true,
                                                    List.of(
                                                            new PrototypePageInfo(
                                                                    "实际父HTML页",
                                                                    id,
                                                                    "home.html",
                                                                    false,
                                                                    List.of(
                                                                            new PrototypePageInfo(
                                                                                    "child",
                                                                                    "b".repeat(64),
                                                                                    "child.html",
                                                                                    false,
                                                                                    List
                                                                                            .of()))))))));
            service =
                    new PrototypeDeliveryDocumentService(
                            descriptions,
                            materials,
                            checks,
                            access,
                            revisions,
                            files,
                            new DeliveryDocumentRenderer(new ObjectMapper()),
                            work);
        }
    }
}
