package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeComponentCodeMappingEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeElementDescriptionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.CodeMappingRequests;
import com.gaiys.proto.prototypehub.web.response.CodeMappingResponse;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeCodeMappingServiceTest {
    @Test
    void linksAreOnlyExplicitHttpTargetsAndNeverCredentialsOrProtocolExecution() {
        for (String good :
                List.of(
                        "",
                        "https://example.com/src/Button.vue",
                        "http://localhost:8080/docs?a=b#part",
                        "https://[::1]:443/"))
            assertDoesNotThrow(() -> PrototypeCodeMappingService.validateLink(good));
        for (String bad :
                List.of(
                        "javascript:alert(1)",
                        "data:text/html,x",
                        "file:///tmp/a",
                        "vscode://open",
                        "//example.com/a",
                        "https://user:pass@example.com/",
                        "https://@example.com/",
                        "https://example.com:/",
                        "https://example.com:0/",
                        "https://example.com:65536/",
                        "https://example.com/\n",
                        "https://example.com/\u0085",
                        "https://example.com/\u00a0",
                        "https://example.com/\ufeff",
                        "https://example.com\\evil",
                        "https://"))
            assertThrows(
                    PrototypeHubException.class,
                    () -> PrototypeCodeMappingService.validateLink(bad),
                    bad);
    }

    @Test
    void sourceChangedAndMissingRemainReadableAndOnlyNewPointersNeedCurrentVersion() {
        var row = entity("card");
        row.setDescriptionId("d");
        row.setDescriptionVersion(3L);
        var live = new PrototypeElementDescriptionEntity();
        live.setId("d");
        live.setTitle("Current title");
        live.setConfigVersion(4L);
        var changed = PrototypeCodeMappingService.resolve(row, Map.of("d", live));
        assertEquals("changed", changed.status());
        assertEquals(3, changed.expectedVersion());
        assertEquals(4L, changed.currentVersion());
        var missing = PrototypeCodeMappingService.resolve(row, Map.of());
        assertEquals("missing", missing.status());
        assertNull(missing.title());
        assertNull(missing.currentVersion());
        var ref = new CodeMappingRequests.Description();
        ref.setId("d");
        ref.setExpectedVersion(3L);
        assertDoesNotThrow(() -> PrototypeCodeMappingService.validateAttach(row, ref, Map.of()));
        assertEquals(
                409,
                assertThrows(
                                PrototypeHubException.class,
                                () ->
                                        PrototypeCodeMappingService.validateAttach(
                                                null, ref, Map.of("d", live)))
                        .getStatus());
        assertEquals(
                404,
                assertThrows(
                                PrototypeHubException.class,
                                () ->
                                        PrototypeCodeMappingService.validateAttach(
                                                null, ref, Map.of()))
                        .getStatus());
        ref.setExpectedVersion(4L);
        assertDoesNotThrow(
                () -> PrototypeCodeMappingService.validateAttach(row, ref, Map.of("d", live)));
        assertDoesNotThrow(() -> PrototypeCodeMappingService.validateAttach(row, null, Map.of()));
        row.setDescriptionId(null);
        row.setDescriptionVersion(null);
        assertNull(PrototypeCodeMappingService.resolve(row, Map.of("d", live)));
    }

    @Test
    void lockReauthorizationPrecedesReplayAndEveryWrite() {
        var f = new Fixture(false);
        var create = create();
        var update = update();
        var deletion = new CodeMappingRequests.Delete();
        deletion.setExpectedVersion(0L);
        deletion.setClientRequestId("old-key");
        for (var operation :
                List.of(
                        f.service.create("v", "r", "page", create),
                        f.service.update("v", "r", "page", "card", update),
                        f.service.delete("v", "r", "page", "card", deletion),
                        f.service.importCards("v", "r", "page", pack())))
            StepVerifier.create(operation)
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException p
                                            && p.getStatus() == 403)
                    .verify();
        verifyNoInteractions(f.cards, f.descriptions, f.receipts);
        var order = inOrder(f.access);
        order.verify(f.access).lockActiveUsers("u");
        order.verify(f.access).lockProject("p");
        order.verify(f.access).lockVersion("v");
        order.verify(f.access).actor("u");
    }

    @Test
    void exactImportReplayWithEveryCardDeletedReturnsOnlyOriginalMissingItemsAndNeverCreates() {
        var f = new Fixture(true);
        ReactiveQuery<PrototypeComponentCodeMappingEntity> query =
                mock(ReactiveQuery.class, RETURNS_SELF);
        when(f.cards.createQuery()).thenReturn(query);
        when(query.fetch()).thenReturn(Flux.empty());
        f.realRequests();
        when(f.receipts.replay(any(), any()))
                .thenAnswer(
                        call ->
                                ((Function<String, Mono<CodeMappingResponse.ImportResult>>)
                                                call.getArgument(1))
                                        .apply("v"));
        var pack = pack();
        StepVerifier.create(f.service.importCards("v", "r", "page", pack))
                .assertNext(
                        result -> {
                            assertEquals(2, result.items().size());
                            assertNotEquals(result.items().get(0).id(), result.items().get(1).id());
                            assertTrue(
                                    result.items().stream()
                                            .allMatch(
                                                    item ->
                                                            item.status().equals("missing")
                                                                    && item.card() == null));
                        })
                .verifyComplete();
        verify(f.cards, never()).insert(any(org.reactivestreams.Publisher.class));
        verifyNoInteractions(f.descriptions);
        verify(f.receipts, never()).record(any(), anyString(), anyString());
    }

    @Test
    void originalCreateKeyReadsCurrentEditedCardWithoutRestoringOldText() {
        var f = new Fixture(true);
        f.realRequests();
        ReactiveQuery<PrototypeComponentCodeMappingEntity> query =
                mock(ReactiveQuery.class, RETURNS_SELF);
        var row = entity("card");
        row.setAlias("Later edit");
        row.setConfigVersion(7L);
        when(f.cards.createQuery()).thenReturn(query);
        when(query.fetch()).thenReturn(Flux.just(row));
        when(f.receipts.replay(any(), any()))
                .thenAnswer(
                        call ->
                                ((Function<String, Mono<CodeMappingResponse.Card>>)
                                                call.getArgument(1))
                                        .apply("v"));
        StepVerifier.create(f.service.create("v", "r", "page", create()))
                .assertNext(
                        current -> {
                            assertEquals("Later edit", current.alias());
                            assertEquals(7, current.configVersion());
                        })
                .verifyComplete();
        verify(f.cards, never()).insert(any(PrototypeComponentCodeMappingEntity.class));
        verify(f.receipts, never()).record(any(), anyString(), anyString());
    }

    @Test
    void badMiddleAssociationPreventsAnyBatchInsertOrReceipt() {
        var f = new Fixture(true);
        f.realRequests();
        when(f.receipts.replay(any(), any())).thenReturn(Mono.empty());
        ReactiveQuery<PrototypeElementDescriptionEntity> query =
                mock(ReactiveQuery.class, RETURNS_SELF);
        when(f.descriptions.createQuery()).thenReturn(query);
        when(query.fetch()).thenReturn(Flux.empty());
        var input = pack();
        var ref = new CodeMappingRequests.Description();
        ref.setId("gone");
        ref.setExpectedVersion(0L);
        input.getCards().get(1).setDescription(ref);
        StepVerifier.create(f.service.importCards("v", "r", "page", input))
                .expectErrorMatches(
                        error -> error instanceof PrototypeHubException p && p.getStatus() == 404)
                .verify();
        verifyNoInteractions(f.cards);
        verify(f.receipts, never()).record(any(), anyString(), anyString());
    }

    private static CodeMappingRequests.Create create() {
        var input = new CodeMappingRequests.Create();
        fill(input);
        input.setClientRequestId("key");
        return input;
    }

    private static CodeMappingRequests.Update update() {
        var input = new CodeMappingRequests.Update();
        fill(input);
        input.setClientRequestId("key");
        input.setExpectedVersion(0L);
        return input;
    }

    private static void fill(CodeMappingRequests.Draft input) {
        input.setAlias("Button");
        input.setRepositoryPath("src/Button.vue");
        input.setRepositoryUrl("");
        input.setDocumentationUrl("");
        input.setSnippet("  <script>plain text</script>\n  x");
        input.setFramework("Vue");
        input.setFrameworkVersion("3");
        input.setDescription(null);
    }

    private static CodeMappingRequests.Import pack() {
        var pack = new CodeMappingRequests.Import();
        pack.setFormat("prototype-hub-code-mapping-cards");
        pack.setSchemaVersion(1);
        pack.setClientRequestId("same-key");
        var a = new CodeMappingRequests.Draft();
        fill(a);
        var b = new CodeMappingRequests.Draft();
        fill(b);
        pack.setCards(List.of(a, b));
        return pack;
    }

    private static PrototypeComponentCodeMappingEntity entity(String id) {
        var row = new PrototypeComponentCodeMappingEntity();
        row.setId(id);
        row.setProjectId("p");
        row.setVersionId("v");
        row.setRevisionId("r");
        row.setPageId("page");
        row.setAlias("Button");
        row.setRepositoryPath("src/Button.vue");
        row.setRepositoryUrl("");
        row.setDocumentationUrl("");
        row.setSnippet("");
        row.setFramework("Vue");
        row.setFrameworkVersion("3");
        row.setConfigVersion(0L);
        row.setCreatedAt(1L);
        row.setUpdatedAt(1L);
        return row;
    }

    private static final class Fixture {
        final ReactiveRepository<PrototypeComponentCodeMappingEntity, String> cards =
                mock(ReactiveRepository.class);
        final ReactiveRepository<PrototypeElementDescriptionEntity, String> descriptions =
                mock(ReactiveRepository.class);
        final PrototypeProjectAccessService access = mock(PrototypeProjectAccessService.class);
        final PrototypeRevisionService revisions = mock(PrototypeRevisionService.class);
        final PrototypeCreationReceiptService receipts =
                mock(PrototypeCreationReceiptService.class);
        final PrototypeCodeMappingService service;

        Fixture(boolean afterLock) {
            var tx = mock(TransactionalOperator.class);
            when(tx.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
            var auth = mock(Authentication.class);
            when(auth.hasPermission("prototype-hub-version", "save")).thenReturn(true);
            var actor = new Actor("u", auth, false);
            var after = afterLock ? actor : new Actor("u", mock(Authentication.class), false);
            var version = new PrototypeVersionEntity();
            version.setId("v");
            version.setProjectId("p");
            var fixed = new PrototypeRevisionEntity();
            fixed.setId("r");
            fixed.setVersionId("v");
            fixed.setProjectId("p");
            when(access.current()).thenReturn(Mono.just(actor));
            when(access.actor("u")).thenReturn(Mono.just(after));
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
                                                    List.of()))));
            when(access.lockActiveUsers("u")).thenReturn(Mono.empty());
            when(access.lockProject("p")).thenReturn(Mono.empty());
            when(access.lockVersion("v")).thenReturn(Mono.empty());
            service =
                    new PrototypeCodeMappingService(
                            cards,
                            descriptions,
                            access,
                            revisions,
                            receipts,
                            tx,
                            new ObjectMapper());
        }

        void realRequests() {
            var real = new PrototypeCreationReceiptService();
            when(receipts.request(
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            any(String[].class)))
                    .thenAnswer(
                            call ->
                                    real.request(
                                            call.getArgument(0),
                                            call.getArgument(1),
                                            call.getArgument(2),
                                            call.getArgument(3),
                                            (String[]) call.getRawArguments()[4]));
        }
    }
}
