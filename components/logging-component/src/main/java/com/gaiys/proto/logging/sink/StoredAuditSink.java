package com.gaiys.proto.logging.sink;

import com.gaiys.proto.logging.document.LogDocument;
import com.gaiys.proto.logging.event.AuditEvent;
import com.gaiys.proto.logging.store.LogStore;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Converts immutable audit events into documents accepted by {@link LogStore}.
 *
 * <p>The Spring singleton writes only when its returned publisher is subscribed. The store owns
 * masking, queueing and destination persistence; this adapter owns no background resources.
 *
 * @see AuditSink
 */
@Component
public class StoredAuditSink implements AuditSink {
    private final LogStore store;

    public StoredAuditSink(LogStore store) {
        this.store = store;
    }

    @Override
    public Mono<Void> append(AuditEvent event) {
        return Mono.fromRunnable(
                () ->
                        store.offer(
                                LogDocument.builder()
                                        .kind("audit")
                                        .createTime(event.timestamp())
                                        .traceId(event.traceId())
                                        .actorId(event.actorId())
                                        .action(event.action())
                                        .resourceType(event.resourceType())
                                        .resourceId(event.resourceId())
                                        .result(event.result())
                                        .duration(event.duration())
                                        .level("INFO")
                                        .message("audit")
                                        .build()));
    }
}
