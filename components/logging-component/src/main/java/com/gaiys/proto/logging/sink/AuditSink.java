package com.gaiys.proto.logging.sink;

import com.gaiys.proto.logging.dispatcher.AuditDispatcher;
import com.gaiys.proto.logging.event.AuditEvent;
import reactor.core.publisher.Mono;

/**
 * Optional destination for immutable business audit events.
 *
 * <p>Implementations own their destination write; {@link AuditDispatcher} owns queueing, serial
 * delivery, timeout and failure isolation. Spring-managed implementations may hold resources but
 * must release those resources through their own lifecycle.
 *
 * @see AuditDispatcher
 * @see StructuredLogAuditSink
 * @see StoredAuditSink
 */
public interface AuditSink {
    /**
     * Writes one event when the dispatcher reaches this sink in its ordered background delivery.
     *
     * <p>The method is invoked lazily on the dispatcher's worker. Return a non-null publisher and
     * perform the write on subscription rather than starting a nested subscription. The dispatcher
     * serializes its own calls, cancels a write after one second and isolates synchronous or
     * reactive errors before invoking the next sink; it does not retry the event. Implementations
     * must respect cancellation and avoid retaining sensitive request payloads beyond the supplied
     * audit fields.
     *
     * @param event non-null immutable audit metadata; implementers must not alter its meaning
     * @return completion when the destination accepts the write; {@code Mono.empty()} is a
     *     successful completion, while an error marks this sink write as failed
     * @see AuditDispatcher
     */
    Mono<Void> append(AuditEvent event);
}
