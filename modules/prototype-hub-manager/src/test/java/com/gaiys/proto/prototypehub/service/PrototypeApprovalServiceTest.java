package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.entity.PrototypeCreationReceiptEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionApprovalEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.request.ApprovalRequests;
import com.gaiys.proto.prototypehub.web.response.ApprovalResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.hswebframework.ezorm.rdb.mapping.ReactiveQuery;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeApprovalServiceTest {
    @Test
    void explicitActionsHaveIndependentApprovalAndPublicationHistory() {
        var initial = new PrototypeRevisionApprovalEntity();
        initial.setConfigVersion(0L);
        initial.setApproved(false);
        initial.setPublished(false);
        var approved = PrototypeApprovalService.transition(initial, "approve", "a");
        assertEquals("a", approved.approvalId());
        assertNull(approved.publicationId());
        var current = row(1, true, false, "a", null);
        var published = PrototypeApprovalService.transition(current, "publish", "p");
        assertEquals("a", published.approvalId());
        assertEquals("p", published.publicationId());
        var released = row(2, true, true, "a", "p");
        var withdrawn = PrototypeApprovalService.transition(released, "withdraw", "w");
        assertTrue(withdrawn.approved());
        assertFalse(withdrawn.published());
        assertEquals("a", withdrawn.approvalId());
        assertNull(withdrawn.publicationId());
        var revoked = PrototypeApprovalService.transition(released, "revoke", "r");
        assertFalse(revoked.approved());
        assertFalse(revoked.published());
        assertNull(revoked.approvalId());
        assertNull(revoked.publicationId());
        assertTrue(released.getPublished(), "append-only decision never rewrites previous release");
        for (String action : new String[] {"revoke", "publish", "withdraw"})
            conflict(() -> PrototypeApprovalService.transition(initial, action, "bad"));
        conflict(() -> PrototypeApprovalService.transition(current, "approve", "bad"));
        conflict(() -> PrototypeApprovalService.transition(current, "withdraw", "bad"));
        conflict(() -> PrototypeApprovalService.transition(released, "publish", "bad"));
    }

    @Test
    void publicContextUsesShareReadGrantAndExposesNoPrivateDecisionFields() throws Exception {
        var shares = mock(PrototypeShareService.class);
        when(shares.requireRevisionAccess("token", "bad", "r"))
                .thenReturn(Mono.error(PrototypeHubException.missing("share")));
        var service = new PrototypeApprovalService(null, null, null, null, shares, null);
        var input = new ApprovalRequests.PublicContext();
        input.setRevisionId("r");
        input.setAccessKey("bad");
        StepVerifier.create(service.publicContext("token", input))
                .expectError(PrototypeHubException.class)
                .verify();
        verify(shares).requireRevisionAccess("token", "bad", "r");
        var json =
                new ObjectMapper()
                        .readTree(
                                new ObjectMapper()
                                        .writeValueAsString(
                                                new ApprovalResponse.PublicContext(
                                                        "r", true, false, 12L, null)));
        assertEquals(5, json.size());
        for (String field :
                new String[] {"actorId", "actorName", "note", "history", "projectId", "approval"})
            assertFalse(json.has(field));
    }

    @Test
    void originalPublishRetryReturnsCurrentWithdrawnStateAndNeverAppendsOrRevivesIt() {
        var fixture = new Fixture();
        var approved = fixture.act("approve", 0, "approve-key");
        assertTrue(approved.approved());
        assertEquals(1, approved.configVersion());
        var published = fixture.act("publish", 1, "publish-key");
        assertTrue(published.published());
        assertEquals("Current editor", published.publication().actorName());
        var withdrawn = fixture.act("withdraw", 2, "withdraw-key");
        assertFalse(withdrawn.published());
        var retry = fixture.act("publish", 1, "publish-key");
        assertFalse(retry.published());
        assertEquals(3, retry.configVersion());
        assertEquals(3, fixture.rows.size());
        assertEquals(3, fixture.receiptRows.size());
        conflict(() -> fixture.act("publish", 1, "new-key"));
        assertEquals(3, fixture.rows.size());
        var changed = fixture.input("publish", 1, "publish-key");
        changed.setNote("different note");
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> fixture.service.act("v", "r", changed).block());
        assertEquals("PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT", error.getCode());
        var revoked = fixture.act("revoke", 3, "revoke-key");
        assertFalse(revoked.approved());
        assertFalse(revoked.published());
        assertNull(revoked.approval());
        assertNull(revoked.publication());
        assertEquals(4, fixture.rows.size());
        assertTrue(
                fixture.rows.values().stream()
                        .anyMatch(row -> "publish".equals(row.getAction()) && row.getPublished()));
        assertEquals(
                List.of("user", "project", "version", "fresh-actor", "fresh-project"),
                fixture.locks.subList(0, 5));
    }

    @Test
    void reorderedJsonAndEmptyNoteCanonicalizationReplayTheSameDecision() throws Exception {
        var fixture = new Fixture();
        var mapper = new ObjectMapper();
        var first =
                mapper.readValue(
                        "{\"action\":\"approve\",\"expectedVersion\":0,\"clientRequestId\":\"canonical\",\"note\":null}",
                        ApprovalRequests.Act.class);
        fixture.service.act("v", "r", first).block();
        var same =
                mapper.readValue(
                        "{\"clientRequestId\":\"canonical\",\"expectedVersion\":0,\"action\":\"approve\"}",
                        ApprovalRequests.Act.class);
        var result = fixture.service.act("v", "r", same).block();
        assertEquals(1, result.configVersion());
        assertEquals("", result.approval().note());
        assertEquals(1, fixture.rows.size());
        assertEquals(1, fixture.receiptRows.size());
    }

    @Test
    void currentRightsAreRecheckedBeforeAnyOldKeyCanReadOrWrite() {
        var fixture = new Fixture();
        fixture.act("approve", 0, "saved-key");
        when(fixture.access.actor("u"))
                .thenReturn(Mono.error(PrototypeProjectAccessService.roleDenied()));
        StepVerifier.create(fixture.service.act("v", "r", fixture.input("approve", 0, "saved-key")))
                .expectErrorMatches(
                        error ->
                                error instanceof PrototypeHubException business
                                        && business.getStatus() == 403)
                .verify();
        assertEquals(1, fixture.rows.size());
        assertEquals(1, fixture.receiptRows.size());
        when(fixture.auth.hasPermission("prototype-hub-version", "query")).thenReturn(false);
        StepVerifier.create(fixture.service.detail("v", "r"))
                .expectError(PrototypeHubException.class)
                .verify();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static class Fixture {
        final Map<String, PrototypeRevisionApprovalEntity> rows = new HashMap<>();
        final Map<String, PrototypeCreationReceiptEntity> receiptRows = new HashMap<>();
        final List<String> locks = new ArrayList<>();
        final PrototypeProjectAccessService access = mock(PrototypeProjectAccessService.class);
        final Authentication auth = mock(Authentication.class);
        final PrototypeApprovalService service;

        Fixture() {
            var user = new SimpleUser();
            user.setId("u");
            user.setName("Current editor");
            when(auth.getUser()).thenReturn(user);
            when(auth.hasPermission("prototype-hub-version", "save")).thenReturn(true);
            when(auth.hasPermission("prototype-hub-version", "query")).thenReturn(true);
            var actor = new Actor("u", auth, false);
            when(access.current()).thenReturn(Mono.just(actor));
            when(access.actor("u"))
                    .thenReturn(
                            Mono.fromSupplier(
                                    () -> {
                                        locks.add("fresh-actor");
                                        return actor;
                                    }));
            var version = new PrototypeVersionEntity();
            version.setId("v");
            version.setProjectId("p");
            version.setCurrentRevisionId("new-r");
            when(access.requireVersion(eq(actor), eq("v"), any(Action.class), anyBoolean()))
                    .thenAnswer(
                            call ->
                                    Mono.fromSupplier(
                                            () -> {
                                                if (!(Boolean) call.getArgument(3))
                                                    locks.add("fresh-project");
                                                return version;
                                            }));
            when(access.lockActiveUsers("u"))
                    .thenReturn(Mono.fromRunnable(() -> locks.add("user")));
            when(access.lockProject("p")).thenReturn(Mono.fromRunnable(() -> locks.add("project")));
            when(access.lockVersion("v")).thenReturn(Mono.fromRunnable(() -> locks.add("version")));
            var revision = new PrototypeRevisionEntity();
            revision.setId("r");
            revision.setProjectId("p");
            revision.setVersionId("v");
            revision.setManifestSha256("hash");
            var revisions = mock(PrototypeRevisionService.class);
            when(revisions.require(version, "r")).thenReturn(Mono.just(revision));
            var repository = mock(ReactiveRepository.class);
            when(repository.createQuery())
                    .thenAnswer(
                            call -> {
                                var ids = new AtomicReference<Collection<String>>();
                                return mock(
                                        ReactiveQuery.class,
                                        invocation -> {
                                            if (invocation.getMethod().getName().equals("in"))
                                                ids.set(invocation.getArgument(1));
                                            if (invocation.getMethod().getName().equals("fetch"))
                                                return Flux.defer(
                                                        () -> {
                                                            if (ids.get() != null)
                                                                return Flux.fromIterable(
                                                                                rows.values())
                                                                        .filter(
                                                                                row ->
                                                                                        ids.get()
                                                                                                .contains(
                                                                                                        row
                                                                                                                .getId()));
                                                            return Flux.fromIterable(rows.values())
                                                                    .sort(
                                                                            Comparator.comparing(
                                                                                            PrototypeRevisionApprovalEntity
                                                                                                    ::getConfigVersion)
                                                                                    .reversed())
                                                                    .take(1);
                                                        });
                                            return RETURNS_SELF.answer(invocation);
                                        });
                            });
            when(repository.insert(any(PrototypeRevisionApprovalEntity.class)))
                    .thenAnswer(
                            call ->
                                    Mono.fromSupplier(
                                            () -> {
                                                var row =
                                                        (PrototypeRevisionApprovalEntity)
                                                                call.getArgument(0);
                                                rows.put(row.getId(), row);
                                                return 1;
                                            }));
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
            var transactions = mock(TransactionalOperator.class);
            when(transactions.transactional(any(Mono.class)))
                    .thenAnswer(call -> call.getArgument(0));
            service =
                    new PrototypeApprovalService(
                            repository, access, revisions, receipts, null, transactions);
        }

        ApprovalRequests.Act input(String action, long expected, String key) {
            var input = new ApprovalRequests.Act();
            input.setAction(action);
            input.setExpectedVersion(expected);
            input.setClientRequestId(key);
            input.setNote("note\nplain text");
            return input;
        }

        ApprovalResponse act(String action, long expected, String key) {
            return service.act("v", "r", input(action, expected, key)).block();
        }
    }

    private static PrototypeRevisionApprovalEntity row(
            long version,
            boolean approved,
            boolean published,
            String approval,
            String publication) {
        var row = new PrototypeRevisionApprovalEntity();
        row.setConfigVersion(version);
        row.setApproved(approved);
        row.setPublished(published);
        row.setApprovalId(approval);
        row.setPublicationId(publication);
        return row;
    }

    private static void conflict(Runnable action) {
        var error = assertThrows(PrototypeHubException.class, action::run);
        assertEquals(409, error.getStatus());
        assertEquals("PROTOTYPE_HUB_APPROVAL_CONFLICT", error.getCode());
    }
}
