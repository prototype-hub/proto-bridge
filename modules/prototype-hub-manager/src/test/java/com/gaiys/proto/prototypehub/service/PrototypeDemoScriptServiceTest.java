package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionEntity;
import com.gaiys.proto.prototypehub.entity.PrototypeVersionEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Action;
import com.gaiys.proto.prototypehub.service.PrototypeProjectAccessService.Actor;
import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import com.gaiys.proto.prototypehub.web.info.PrototypePageInfo;
import com.gaiys.proto.prototypehub.web.request.DemoScriptRequests;
import java.util.List;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeDemoScriptServiceTest {
    @Test
    void permissionRevokedWhileWaitingForLocksPreventsReceiptReplayAndAnyMutation() {
        var access = mock(PrototypeProjectAccessService.class);
        var revisions = mock(PrototypeRevisionService.class);
        var receipts = mock(PrototypeCreationReceiptService.class);
        var transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class))).thenAnswer(call -> call.getArgument(0));
        var before = mock(Authentication.class);
        when(before.hasPermission("prototype-hub-version", "save")).thenReturn(true);
        var current = new Actor("u", before, false);
        var revoked = new Actor("u", mock(Authentication.class), false);
        var version = new PrototypeVersionEntity();
        version.setId("v");
        version.setProjectId("p");
        var fixed = new PrototypeRevisionEntity();
        fixed.setId("r");
        fixed.setProjectId("p");
        fixed.setVersionId("v");
        when(access.current()).thenReturn(Mono.just(current));
        when(access.requireVersion(current, "v", Action.EDIT, true)).thenReturn(Mono.just(version));
        when(revisions.require(version, "r")).thenReturn(Mono.just(fixed));
        when(revisions.pages(fixed))
                .thenReturn(
                        Mono.just(
                                List.of(
                                        new PrototypePageInfo(
                                                "Page", "page", "index.html", false, List.of()))));
        when(access.lockActiveUsers("u")).thenReturn(Mono.empty());
        when(access.lockProject("p")).thenReturn(Mono.empty());
        when(access.lockVersion("v")).thenReturn(Mono.empty());
        when(access.actor("u")).thenReturn(Mono.just(revoked));
        var input = new DemoScriptRequests.Fields();
        input.setTitle("Demo");
        input.setObjective("Goal");
        input.setClientRequestId("already-used-key");
        input.setSteps(List.of(new DemoScriptStep("step", "page", "")));
        var service =
                new PrototypeDemoScriptService(
                        null, access, revisions, receipts, null, transactions);
        StepVerifier.create(service.create("v", "r", input))
                .expectErrorMatches(
                        error ->
                                error instanceof PrototypeHubException business
                                        && business.getStatus() == 403)
                .verify();
        verifyNoInteractions(receipts);
        var order = inOrder(access, revisions);
        order.verify(revisions).pages(fixed);
        order.verify(access).lockActiveUsers("u");
        order.verify(access).lockProject("p");
        order.verify(access).lockVersion("v");
        order.verify(access).actor("u");
    }

    @Test
    void repeatedVisitsAndOpenableParentsAreValidButDirectoriesAndDuplicateStepIdsAreNot() {
        var child = new PrototypePageInfo("Child", "opaque-child", "child.html", false, List.of());
        var parent =
                new PrototypePageInfo(
                        "Parent", "opaque-parent", "index.html", false, List.of(child));
        var folder = PrototypePageInfo.folder("Folder", List.of(parent));
        var pages = List.of(folder);
        PrototypeDemoScriptService.validateSteps(
                List.of(
                        new DemoScriptStep("a", "opaque-parent", "start"),
                        new DemoScriptStep("b", "opaque-child", "child"),
                        new DemoScriptStep("c", "opaque-parent", "return")),
                pages);
        var duplicate =
                assertThrows(
                        PrototypeHubException.class,
                        () ->
                                PrototypeDemoScriptService.validateSteps(
                                        List.of(
                                                new DemoScriptStep("a", "opaque-parent", ""),
                                                new DemoScriptStep("a", "opaque-child", "")),
                                        pages));
        assertEquals(400, duplicate.getStatus());
        for (String id : List.of("Folder", "fake", "other-revision")) {
            var missing =
                    assertThrows(
                            PrototypeHubException.class,
                            () ->
                                    PrototypeDemoScriptService.validateSteps(
                                            List.of(new DemoScriptStep("a", id, "")), pages));
            assertEquals(404, missing.getStatus());
        }
    }
}
