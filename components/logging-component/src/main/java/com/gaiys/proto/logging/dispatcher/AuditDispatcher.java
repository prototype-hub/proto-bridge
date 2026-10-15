package com.gaiys.proto.logging.dispatcher;

import com.gaiys.proto.logging.event.AuditEvent;
import com.gaiys.proto.logging.sink.AuditSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Dispatches business audit events to optional sinks through a bounded background queue.
 *
 * <p>Spring owns the subscription and worker lifecycle. Events and their sinks are processed in
 * order; sink failures and timeouts are counted without rejecting the originating business request.
 * Existing Micrometer counters provide operational visibility without tracing each log event.
 *
 * @see AuditSink
 */
@Component
public class AuditDispatcher {
    private final List<AuditSink> sinks;
    private final Counter failed, dropped;
    private final Sinks.Many<AuditEvent> queue =
            Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<>(256));
    private final Scheduler worker = Schedulers.newBoundedElastic(2, 64, "audit-sinks");
    private Disposable subscription;

    public AuditDispatcher(List<AuditSink> sinks, MeterRegistry metrics) {
        this.sinks = sinks;
        failed = metrics.counter("proto.audit.sink.failures");
        dropped = metrics.counter("proto.audit.queue.dropped");
    }

    @PostConstruct
    public void start() {
        // This lifecycle subscription owns background delivery; request handlers only enqueue.
        subscription = queue.asFlux().concatMap(this::dispatchToSinks).subscribe();
    }

    private Mono<Void> dispatchToSinks(AuditEvent event) {
        return Flux.fromIterable(sinks).concatMap(sink -> appendToSink(sink, event)).then();
    }

    private Mono<Void> appendToSink(AuditSink sink, AuditEvent event) {
        // Defer invocation onto the worker so a synchronous SPI failure is isolated as well.
        return Mono.defer(() -> sink.append(event))
                .subscribeOn(worker)
                .timeout(Duration.ofSeconds(1))
                .onErrorResume(error -> recoverSinkFailure(event));
    }

    private Mono<Void> recoverSinkFailure(AuditEvent event) {
        failed.increment();
        LoggerFactory.getLogger("proto.audit.fallback")
                .atWarn()
                .addKeyValue("traceId", event.traceId())
                .addKeyValue("action", event.action())
                .log("Audit sink unavailable");
        return Mono.empty();
    }

    /** Never waits for a remote sink and never rejects a business request. */
    public void offer(AuditEvent event) {
        if (queue.tryEmitNext(event).isFailure()) dropped.increment();
    }

    @PreDestroy
    public void close() {
        // Cancel delivery before disposing the worker that runs sink subscriptions.
        if (subscription != null) subscription.dispose();
        worker.dispose();
    }
}
