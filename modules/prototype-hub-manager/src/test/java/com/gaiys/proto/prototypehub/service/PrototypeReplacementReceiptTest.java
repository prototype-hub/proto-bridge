package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.prototypehub.entity.PrototypeCreationReceiptEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import org.hswebframework.web.authorization.Authentication;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class PrototypeReplacementReceiptTest {
    @Test
    void stableKeyCannotBeReusedForADifferentExpectedRevisionOrPayload() {
        var receipts = spy(new PrototypeCreationReceiptService());
        var original =
                receipts.request(
                                "actor",
                                "replace-version",
                                "version",
                                "same-key",
                                "name",
                                "description",
                                "old-revision")
                        .withPayload("original-zip-hash");
        var saved = new PrototypeCreationReceiptEntity();
        saved.setId(original.id());
        saved.setFingerprint(original.fingerprint());
        saved.setRevisionId("created-revision");
        doReturn(Mono.just(saved)).when(receipts).findById(original.id());
        var same =
                receipts.request(
                                "actor",
                                "replace-version",
                                "version",
                                "same-key",
                                "name",
                                "description",
                                "old-revision")
                        .withPayload("original-zip-hash");
        StepVerifier.create(receipts.replayReceipt(same, row -> Mono.just(row.getRevisionId())))
                .expectNext("created-revision")
                .verifyComplete();
        var changedRevision =
                receipts.request(
                                "actor",
                                "replace-version",
                                "version",
                                "same-key",
                                "name",
                                "description",
                                "new-revision")
                        .withPayload("original-zip-hash");
        var changedPayload = same.withPayload("different-zip-hash");
        for (var changed :
                new PrototypeCreationReceiptService.Request[] {changedRevision, changedPayload})
            StepVerifier.create(receipts.replayReceipt(changed, row -> Mono.just("unexpected")))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException business
                                            && "PROTOTYPE_HUB_IDEMPOTENCY_CONFLICT"
                                                    .equals(business.getCode()))
                    .verify();
        assertNotEquals(
                original.id(),
                receipts.request("other-actor", "replace-version", "version", "same-key").id());
        assertNotEquals(
                original.id(),
                receipts.request("actor", "create-version", "version", "same-key").id());
    }

    @Test
    void resultLookupRequiresBothCurrentActionsAndRejectsMissingKey() {
        var access = mock(PrototypeProjectAccessService.class);
        var authentication = mock(Authentication.class);
        when(access.current())
                .thenReturn(
                        Mono.just(
                                new PrototypeProjectAccessService.Actor(
                                        "user", authentication, false)));
        var service =
                new PrototypeUploadService(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new PrototypeCreationReceiptService(),
                        access,
                        null,
                        null,
                        null);
        StepVerifier.create(service.operation("version", null))
                .expectError(PrototypeHubException.class)
                .verify();
        for (String action : new String[] {"query", "save"}) {
            reset(authentication);
            when(authentication.hasPermission("prototype-hub-version", action)).thenReturn(true);
            StepVerifier.create(service.operation("version", "key"))
                    .expectErrorMatches(
                            error ->
                                    error instanceof PrototypeHubException business
                                            && business.getStatus() == 403)
                    .verify();
        }
    }
}
