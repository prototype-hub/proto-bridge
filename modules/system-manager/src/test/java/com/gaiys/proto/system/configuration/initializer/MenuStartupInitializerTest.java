package com.gaiys.proto.system.configuration.initializer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.system.service.MenuStartupInitializationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.core.publisher.Mono;

class MenuStartupInitializerTest {
    @Test
    void completesInitializationAndExistingMenuSkipAtTheStartupBoundary() {
        for (boolean imported : new boolean[] {true, false}) {
            var service = mock(MenuStartupInitializationService.class);
            when(service.initializeIfEmpty()).thenReturn(Mono.just(imported));
            when(service.ensureRecycleMenuIfMissing()).thenReturn(Mono.just(false));
            var initializer = new MenuStartupInitializer(service);
            assertDoesNotThrow(() -> initializer.run(new DefaultApplicationArguments()));
            verify(service).initializeIfEmpty();
            verify(service).ensureRecycleMenuIfMissing();
        }
    }

    @Test
    void initializationFailureAbortsStartupInsteadOfBeingIgnored() {
        var service = mock(MenuStartupInitializationService.class);
        var failure = new IllegalStateException("Invalid default menu resource");
        when(service.initializeIfEmpty()).thenReturn(Mono.error(failure));
        when(service.ensureRecycleMenuIfMissing()).thenReturn(Mono.just(false));
        var initializer = new MenuStartupInitializer(service);
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> initializer.run(new DefaultApplicationArguments())));
    }
}
