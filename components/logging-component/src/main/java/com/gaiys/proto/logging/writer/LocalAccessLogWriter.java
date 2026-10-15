package com.gaiys.proto.logging.writer;

import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.properties.LogStorageProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Delivers structured access fields to local logging through a bounded background queue.
 *
 * <p>This Spring-managed worker is independent of Elasticsearch availability and its backlog.
 * Producers only enqueue; Logback owns console formatting and the JSON file. Queue loss remains
 * visible through the existing Micrometer counter, and shutdown releases the subscription and
 * worker.
 */
@Component
public class LocalAccessLogWriter {
    private final Sinks.Many<LogDocument> queue;
    private final Counter dropped;
    private final Scheduler worker = Schedulers.newBoundedElastic(1, 64, "local-access-log");
    private Disposable subscription;

    public LocalAccessLogWriter(LogStorageProperties settings, MeterRegistry metrics) {
        if (settings.getLocalWriteQueueCapacity() < 1)
            throw new IllegalArgumentException("validation.log_settings_invalid");
        queue =
                Sinks.many()
                        .unicast()
                        .onBackpressureBuffer(
                                new ArrayBlockingQueue<>(settings.getLocalWriteQueueCapacity()));
        dropped = metrics.counter("proto.logging.local.dropped");
    }

    @PostConstruct
    public void start() {
        // The lifecycle subscriber owns local logging; request producers never wait for file I/O.
        subscription =
                queue.asFlux()
                        .publishOn(worker, 64)
                        .subscribe(this::write, error -> dropped.increment());
    }

    public synchronized void offer(LogDocument document) {
        if (queue.tryEmitNext(document).isFailure()) dropped.increment();
    }

    private void write(LogDocument document) {
        LoggerFactory.getLogger("proto.access")
                .atLevel(Level.valueOf(document.getLevel()))
                .addKeyValue("traceId", document.getTraceId())
                .addKeyValue("actorId", document.getActorId())
                .addKeyValue("route", document.getUrl())
                .addKeyValue("httpMethod", document.getHttpMethod())
                .addKeyValue("status", document.getStatus())
                .addKeyValue("duration", document.getDuration())
                .addKeyValue("requestTime", document.getRequestTime())
                .addKeyValue("responseTime", document.getResponseTime())
                .addKeyValue("exception", document.getException())
                .log("access");
    }

    @PreDestroy
    public void close() {
        if (subscription != null) subscription.dispose();
        worker.dispose();
    }
}
