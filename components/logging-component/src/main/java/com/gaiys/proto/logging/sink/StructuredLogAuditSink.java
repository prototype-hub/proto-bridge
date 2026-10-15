package com.gaiys.proto.logging.sink;

import com.gaiys.proto.logging.event.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Writes business audit fields through the configured SLF4J logging pipeline.
 *
 * <p>The Spring singleton logs on subscription; Logback owns formatting, masking and destination
 * lifecycle. This sink does not query storage or open its own background subscription.
 *
 * @see AuditSink
 */
@Component
public class StructuredLogAuditSink implements AuditSink {
    private static final Logger LOG = LoggerFactory.getLogger("proto.audit");

    @Override
    public Mono<Void> append(AuditEvent e) {
        return Mono.fromRunnable(
                () ->
                        LOG.atInfo()
                                .addKeyValue("traceId", e.traceId())
                                .addKeyValue("actorId", e.actorId())
                                .addKeyValue("action", e.action())
                                .addKeyValue("resourceType", e.resourceType())
                                .addKeyValue("resourceId", e.resourceId())
                                .addKeyValue("result", e.result())
                                .addKeyValue("duration", e.duration())
                                .addKeyValue("timestamp", e.timestamp())
                                .log("audit"));
    }
}
