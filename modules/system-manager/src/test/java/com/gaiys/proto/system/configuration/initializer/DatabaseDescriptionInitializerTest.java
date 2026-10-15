package com.gaiys.proto.system.configuration.initializer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.system.service.DatabaseDescriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import reactor.core.publisher.Mono;

class DatabaseDescriptionInitializerTest {
    @Test
    void completesBothUpdatesAndUnchangedDescriptionsBeforeStartupReturns() {
        for (int count : new int[] {0, 5}) {
            var service = mock(DatabaseDescriptionService.class);
            when(service.synchronize()).thenReturn(Mono.just(count));
            assertDoesNotThrow(
                    () ->
                            new DatabaseDescriptionInitializer(service)
                                    .run(new DefaultApplicationArguments()));
            verify(service).synchronize();
        }
    }

    @Test
    void schemaDriftFailureAbortsStartup() {
        var service = mock(DatabaseDescriptionService.class);
        var failure = new IllegalStateException("Mapped column is missing");
        when(service.synchronize()).thenReturn(Mono.error(failure));
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new DatabaseDescriptionInitializer(service)
                                        .run(new DefaultApplicationArguments())));
    }
}
