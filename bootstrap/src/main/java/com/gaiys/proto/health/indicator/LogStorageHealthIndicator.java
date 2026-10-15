package com.gaiys.proto.health.indicator;

import com.gaiys.proto.logging.store.LogStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reports optional Elasticsearch log-storage availability to reactive Actuator health checks.
 *
 * <p>Spring enables this singleton only with Elasticsearch logging. {@link LogStore} owns request
 * timeout and client lifecycle; this adapter returns health without exposing logs or connection
 * data.
 */
@Component("logStorageHealthIndicator")
@ConditionalOnProperty(
        prefix = "proto.logging.elasticsearch",
        name = "enabled",
        havingValue = "true")
public class LogStorageHealthIndicator implements ReactiveHealthIndicator {
    private final LogStore store;

    public LogStorageHealthIndicator(LogStore store) {
        this.store = store;
    }

    @Override
    public Mono<Health> health() {
        return store.ping().map(up -> up ? Health.up().build() : Health.down().build());
    }
}
