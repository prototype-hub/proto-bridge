package com.gaiys.proto.prototypehub.health.indicator;

import com.gaiys.proto.prototypehub.configuration.properties.PrototypeHubStorageProperties;
import com.gaiys.proto.prototypehub.storage.work.FileWork;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.AllArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component("prototypeStorage")
@AllArgsConstructor
public class StorageHealthIndicator implements ReactiveHealthIndicator {
    private final PrototypeHubStorageProperties properties;
    private final FileWork work;

    public Mono<Health> health() {
        return work.call(
                        () -> {
                            Path root = properties.getRoot().toAbsolutePath();
                            while (!Files.exists(root) && root.getParent() != null)
                                root = root.getParent();
                            long available = Files.getFileStore(root).getUsableSpace();
                            return (Files.isWritable(root) && available > 10 * 1_048_576L
                                            ? Health.up()
                                            : Health.down())
                                    .withDetail("freeBytes", available)
                                    .build();
                        })
                .onErrorReturn(Health.down().build());
    }
}
