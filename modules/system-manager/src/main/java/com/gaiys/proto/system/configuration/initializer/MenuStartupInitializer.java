package com.gaiys.proto.system.configuration.initializer;

import com.gaiys.proto.system.service.MenuStartupInitializationService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Completes optional empty-table menu initialization before application readiness.
 *
 * <p>EasyORM and system bootstrap finish first; this runner delegates the transactional import
 * without clearing any existing menus. Parsing, validation and persistence errors abort startup.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 300)
@RequiredArgsConstructor
public class MenuStartupInitializer implements ApplicationRunner {
    private final MenuStartupInitializationService initialization;

    @Override
    public void run(ApplicationArguments args) {
        // Blocking is confined to the synchronous startup boundary, like administrator setup.
        initialization
                .initializeIfEmpty()
                .then(initialization.ensureRecycleMenuIfMissing())
                .block(Duration.ofMinutes(2));
    }
}
