package com.gaiys.proto;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.service.license.PrototypeEntitlementProvider;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.AdvancedEntitlements;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.EnforcedCapacity;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.FreeEntitlements;
import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.TrialActivation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 公共业务准入每次订阅读取当前权益；角色与项目权限仍由业务调用者负责。 */
class PrototypeAdvancedEntitlementTest {
    PrototypeLicenseStatus status(AdvancedEntitlements advanced) {
        return new PrototypeLicenseStatus(
                1,
                "test",
                "valid",
                true,
                null,
                null,
                new FreeEntitlements(5, 5, List.of()),
                new EnforcedCapacity(5, 5, "free"),
                new TrialActivation(null, null),
                advanced);
    }

    @Test
    void exactFeatureIdIsRequiredAndEverySubscriptionRereadsCurrentPolicy() {
        AtomicReference<AdvancedEntitlements> state =
                new AtomicReference<>(new AdvancedEntitlements(true, "trial", List.of("F52")));
        AtomicInteger reads = new AtomicInteger();
        PrototypeEntitlementProvider provider =
                () ->
                        Mono.defer(
                                () -> {
                                    reads.incrementAndGet();
                                    return Mono.just(status(state.get()));
                                });
        Mono<Void> admission = provider.requireFeature("F52");
        StepVerifier.create(admission).verifyComplete();
        StepVerifier.create(provider.requireFeature("F5"))
                .expectErrorSatisfies(
                        error ->
                                assertEquals(
                                        "PROTOTYPE_HUB_ADVANCED_FEATURE_UNAVAILABLE",
                                        ((PrototypeHubException) error).getCode()))
                .verify();
        state.set(new AdvancedEntitlements(true, "expired", List.of()));
        StepVerifier.create(admission)
                .expectErrorSatisfies(
                        error -> {
                            var failure = (PrototypeHubException) error;
                            assertEquals("PROTOTYPE_HUB_ADVANCED_TRIAL_EXPIRED", failure.getCode());
                            assertEquals(403, failure.getStatus());
                        })
                .verify();
        assertEquals(3, reads.get());
    }

    @Test
    void unavailableModuleAndUnlicensedStatesNeverAuthorizeEvenIfIdsArePresent() {
        for (String state :
                List.of("module_unavailable", "not_licensed", "legacy_trial", "not_started")) {
            PrototypeEntitlementProvider provider =
                    () ->
                            Mono.just(
                                    status(
                                            new AdvancedEntitlements(
                                                    !state.equals("module_unavailable"),
                                                    state,
                                                    List.of("F52"))));
            StepVerifier.create(provider.requireFeature("F52"))
                    .expectErrorSatisfies(
                            error ->
                                    assertEquals(
                                            "PROTOTYPE_HUB_ADVANCED_FEATURE_UNAVAILABLE",
                                            ((PrototypeHubException) error).getCode()))
                    .verify();
        }
    }

    @Test
    void databaseFailurePropagatesAndPermanentFeatureAdmissionDoesNotDependOnCapacity() {
        var unavailable = new IllegalStateException("database unavailable");
        PrototypeEntitlementProvider broken = () -> Mono.error(unavailable);
        StepVerifier.create(broken.currentAdvanced())
                .expectErrorMatches(error -> error == unavailable)
                .verify();
        StepVerifier.create(broken.requireFeature("F52"))
                .expectErrorMatches(error -> error == unavailable)
                .verify();
        PrototypeEntitlementProvider permanent =
                () ->
                        Mono.just(
                                status(
                                        new AdvancedEntitlements(
                                                true, "permanent", List.of("F52"))));
        StepVerifier.create(permanent.requireFeature("F52")).verifyComplete();
    }
}
