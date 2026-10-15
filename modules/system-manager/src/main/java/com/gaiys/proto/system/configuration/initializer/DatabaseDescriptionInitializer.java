package com.gaiys.proto.system.configuration.initializer;

import com.gaiys.proto.system.service.DatabaseDescriptionService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Synchronizes descriptions after EasyORM DDL and before application readiness. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DatabaseDescriptionInitializer implements ApplicationRunner {
    private final DatabaseDescriptionService descriptions;

    @Override
    public void run(ApplicationArguments args) {
        // Only the synchronous startup boundary blocks; schema drift and write errors abort
        // startup.
        descriptions.synchronize().block(Duration.ofMinutes(2));
    }
}
