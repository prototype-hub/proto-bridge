package com.gaiys.proto.system.configuration.initializer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.system.service.MenuStartupInitializationService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.core.publisher.Mono;

class MenuStartupInitializerTest {
    private static final List<String> STAGES =
            List.of("initialize", "recycle", "statistics", "license");

    @Test
    void completesInitializationAndExistingMenuSkipAtTheStartupBoundary() {
        for (boolean imported : new boolean[] {true, false}) {
            var subscribed = new ArrayList<String>();
            var service = mock(MenuStartupInitializationService.class);
            when(service.initializeIfEmpty())
                    .thenReturn(stage(subscribed, "initialize", Mono.just(imported)));
            when(service.ensureRecycleMenuIfMissing())
                    .thenReturn(stage(subscribed, "recycle", Mono.just(false)));
            when(service.ensureStatisticsMenuIfMissing())
                    .thenReturn(stage(subscribed, "statistics", Mono.just(false)));
            when(service.ensureLicenseMenuIfMissing())
                    .thenReturn(stage(subscribed, "license", Mono.just(false)));
            var initializer = new MenuStartupInitializer(service);
            assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
            assertEquals(STAGES, subscribed);
            verify(service).initializeIfEmpty();
            verify(service).ensureRecycleMenuIfMissing();
            verify(service).ensureStatisticsMenuIfMissing();
            verify(service).ensureLicenseMenuIfMissing();
        }
    }

    @Test
    void initializationFailureAbortsStartupInsteadOfBeingIgnored() {
        for (int failed = 0; failed < STAGES.size(); failed++) {
            var subscribed = new ArrayList<String>();
            var failure = new IllegalStateException("Invalid menu stage: " + STAGES.get(failed));
            var stages = new ArrayList<Mono<Boolean>>();
            for (int index = 0; index < STAGES.size(); index++) {
                stages.add(
                        stage(
                                subscribed,
                                STAGES.get(index),
                                index == failed ? Mono.error(failure) : Mono.just(false)));
            }
            var service = mock(MenuStartupInitializationService.class);
            when(service.initializeIfEmpty()).thenReturn(stages.get(0));
            when(service.ensureRecycleMenuIfMissing()).thenReturn(stages.get(1));
            when(service.ensureStatisticsMenuIfMissing()).thenReturn(stages.get(2));
            when(service.ensureLicenseMenuIfMissing()).thenReturn(stages.get(3));
            var initializer = new MenuStartupInitializer(service);
            assertSame(
                    failure,
                    assertThrows(
                            IllegalStateException.class,
                            () -> initializer.run(new DefaultApplicationArguments())));
            assertEquals(STAGES.subList(0, failed + 1), subscribed);
        }
    }

    private Mono<Boolean> stage(List<String> subscribed, String name, Mono<Boolean> result) {
        // Mono arguments are assembled eagerly; startup side effects occur on subscription.
        return Mono.defer(
                () -> {
                    subscribed.add(name);
                    return result;
                });
    }
}
