package com.gaiys.proto.logging.event;

public record AuditEvent(
        String traceId,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        String result,
        long duration,
        long timestamp) {}
