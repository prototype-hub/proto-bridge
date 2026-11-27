package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.entity.PrototypePageTraceContextEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.PageTraceSourceRef;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.PageTraceRequests;
import com.gaiys.proto.prototypehub.web.response.PageTraceResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypePageTraceServiceTest {
    @Test
    void realHtmlParentsRemainUsableAndOnlyFoldersAreExcluded() {
        var child = new PrototypePageInfo("Child", "child", "child.html", false, List.of());
        var parent = new PrototypePageInfo("Parent", "parent", "index.html", false, List.of(child));
        assertEquals(
                Set.of("parent", "child"),
                PrototypePageTraceService.realPages(
                        List.of(PrototypePageInfo.folder("Folder", List.of(parent)))));
    }

    @Test
    void duplicateSourceIdentityIsRejectedEvenWithDifferentExpectedVersions() {
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () ->
                                PrototypePageTraceService.validateReferences(
                                        List.of(
                                                new PageTraceSourceRef("check", "id", 0),
                                                new PageTraceSourceRef("check", "id", 1))));
        assertEquals(400, error.getStatus());
        PrototypePageTraceService.validateReferences(
                List.of(
                        new PageTraceSourceRef("check", "id", 0),
                        new PageTraceSourceRef("description", "id", 0)));
    }

    @Test
    void sourceChangesAndDeletionRemainVisibleWithoutCopyingOldContent() {
        var refs =
                List.of(
                        new PageTraceSourceRef("work-item", "w", 0),
                        new PageTraceSourceRef("description", "d", 3),
                        new PageTraceSourceRef("check", "c", 2));
        var live =
                Map.of(
                        "work-item:w",
                        source("work-item", "w", 0, "Current requirement"),
                        "description:d",
                        source("description", "d", 4, "Changed description"));
        var rows = PrototypePageTraceSourceService.resolve(refs, live);
        assertEquals(
                List.of("current", "changed", "missing"),
                rows.stream().map(PageTraceResponse.ResolvedSource::status).toList());
        assertEquals(3, rows.get(1).expectedVersion());
        assertEquals(4L, rows.get(1).currentVersion());
        assertNull(rows.get(2).title());
        assertNull(rows.get(2).currentVersion());
        // Members can independently change the entry declaration while retaining stale or missing
        // refs.
        PrototypePageTraceSourceService.validateAttach(refs, refs, live);
        var missing =
                assertThrows(
                        PrototypeHubException.class,
                        () ->
                                PrototypePageTraceSourceService.validateAttach(
                                        List.of(), List.of(refs.get(2)), live));
        assertEquals(404, missing.getStatus());
        var changed =
                assertThrows(
                        PrototypeHubException.class,
                        () ->
                                PrototypePageTraceSourceService.validateAttach(
                                        List.of(), List.of(refs.get(1)), live));
        assertEquals(409, changed.getStatus());
        assertEquals("PROTOTYPE_HUB_PAGE_TRACE_SOURCE_CHANGED", changed.getCode());
        PrototypePageTraceSourceService.validateAttach(
                refs, List.of(new PageTraceSourceRef("description", "d", 4)), live);
    }

    @Test
    void revokedSavePermissionAfterVersionLockPreventsEveryWriteAndReceiptReplay() {
        var f = new Fixture(false);
        var relation = new PageTraceRequests.RelationFields();
        relation.setFromPageId("page");
        relation.setToPageId("child");
        relation.setClientRequestId("prior-key");
        var relationUpdate = new PageTraceRequests.RelationUpdate();
        relationUpdate.setFromPageId("page");
        relationUpdate.setToPageId("child");
        var context = new PageTraceRequests.ContextUpdate();
        context.setReferences(List.of());
        var decision = new PageTraceRequests.DecisionFields();
        var update = new PageTraceRequests.DecisionUpdate();
        var delete = new PageTraceRequests.Delete();
        for (Mono<?> operation :
                List.of(
                        f.service.createRelation("v", "r", relation),
                        f.service.updateRelation("v", "r", "edge", relationUpdate),
                        f.service.deleteRelation("v", "r", "edge", delete),
                        f.service.updateContext("v", "r", "page", context),
                        f.service.createDecision("v", "r", "page", decision),
                        f.service.updateDecision("v", "r", "page", "decision", update),
                        f.service.deleteDecision("v", "r", "page", "decision", delete))) {
            StepVerifier.create(operation)
                    .expectErrorMatches(
                            e -> e instanceof PrototypeHubException p && p.getStatus() == 403)
                    .verify();
        }
        verifyNoInteractions(f.receipts, f.contexts, f.sources);
        var order = inOrder(f.access);
        order.verify(f.access).lockActiveUsers("u");
        order.verify(f.access).lockProject("p");
        order.verify(f.access).lockVersion("v");
        order.verify(f.access).actor("u");
    }

    @Test
    void originalContextKeyReadsClearedCurrentRowWithoutRestoringOldReferencesOrEntry() {
        var f = new Fixture(true);
        var row = new PrototypePageTraceContextEntity();
        row.setId("ctx");
        row.setProjectId("p");
        row.setVersionId("v");
        row.setRevisionId("r");
        row.setPageId("page");
        row.setEntry(false);
        row.setReferences(List.of());
        row.setConfigVersion(4L);
        row.setCreatedAt(1L);
        row.setUpdatedAt(2L);
        ReactiveQuery<PrototypePageTraceContextEntity> query =
                mock(ReactiveQuery.class, RETURNS_SELF);
        when(f.contexts.createQuery()).thenReturn(query);
        when(query.fetch()).thenReturn(Flux.just(row));
        when(f.sources.load(f.fixed, "page", List.of())).thenReturn(Mono.just(Map.of()));
        var realReceipts = new PrototypeCreationReceiptService();
        when(f.receipts.request(
                        anyString(), anyString(), anyString(), anyString(), any(String[].class)))
                .thenAnswer(
                        call ->
                                realReceipts.request(
                                        call.getArgument(0),
                                        call.getArgument(1),
                                        call.getArgument(2),
                                        call.getArgument(3),
                                        (String[]) call.getRawArguments()[4]));
        when(f.receipts.replay(any(), any()))
                .thenAnswer(
                        call -> {
                            Function<String, Mono<PageTraceResponse.Context>> current =
                                    call.getArgument(1);
                            return current.apply("v");
                        });
        var original = new PageTraceRequests.ContextUpdate();
        original.setEntry(true);
        original.setExpectedVersion(-1L);
        original.setClientRequestId("original-key");
        original.setReferences(List.of(new PageTraceSourceRef("check", "now-deleted", 0)));
        StepVerifier.create(f.service.updateContext("v", "r", "page", original))
                .assertNext(
                        result -> {
                            assertFalse(result.entry());
                            assertTrue(result.references().isEmpty());
                            assertEquals(4, result.configVersion());
                        })
                .verifyComplete();
        verify(f.contexts, never()).insert(any(PrototypePageTraceContextEntity.class));
        verify(f.contexts, never()).createUpdate();
        verify(f.sources).load(f.fixed, "page", List.of());
        verify(f.receipts, never()).record(any(), anyString(), anyString());
    }

    private static PageTraceResponse.SourceDetail source(
            String kind, String id, long version, String title) {
        return new PageTraceResponse.SourceDetail(kind, id, title, version, Map.of());
    }

    private static final class Fixture {
        final PrototypeProjectAccessService access = mock(PrototypeProjectAccessService.class);
        final PrototypeRevisionService revisions = mock(PrototypeRevisionService.class);
        final PrototypeCreationReceiptService receipts =
                mock(PrototypeCreationReceiptService.class);
        final PrototypePageTraceSourceService sources = mock(PrototypePageTraceSourceService.class);
        final ReactiveRepository<PrototypePageTraceContextEntity, String> contexts =
                mock(ReactiveRepository.class);
        final PrototypeRevisionEntity fixed = new PrototypeRevisionEntity();
        final PrototypePageTraceService service;

        Fixture(boolean authorizedAfterLock) {
            var tx = mock(TransactionalOperator.class);
            when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
            var auth = mock(Authentication.class);
            when(auth.hasPermission("prototype-hub-version", "save")).thenReturn(true);
            var actor = new Actor("u", auth, false);
            var after =
                    authorizedAfterLock ? actor : new Actor("u", mock(Authentication.class), false);
            var version = new PrototypeVersionEntity();
            version.setId("v");
            version.setProjectId("p");
            fixed.setId("r");
            fixed.setProjectId("p");
            fixed.setVersionId("v");
            when(access.current()).thenReturn(Mono.just(actor));
            when(access.requireVersion(actor, "v", Action.EDIT, true))
                    .thenReturn(Mono.just(version));
            when(access.requireVersion(actor, "v", Action.EDIT, false))
                    .thenReturn(Mono.just(version));
            when(revisions.require(version, "r")).thenReturn(Mono.just(fixed));
            when(revisions.pages(fixed))
                    .thenReturn(
                            Mono.just(
                                    List.of(
                                            new PrototypePageInfo(
                                                    "Page",
                                                    "page",
                                                    "index.html",
                                                    false,
                                                    List.of(
                                                            new PrototypePageInfo(
                                                                    "Child",
                                                                    "child",
                                                                    "child.html",
                                                                    false,
                                                                    List.of()))))));
            when(access.lockActiveUsers("u")).thenReturn(Mono.empty());
            when(access.lockProject("p")).thenReturn(Mono.empty());
            when(access.lockVersion("v")).thenReturn(Mono.empty());
            when(access.actor("u")).thenReturn(Mono.just(after));
            service =
                    new PrototypePageTraceService(
                            null, contexts, null, sources, access, revisions, receipts, tx);
        }
    }
}
