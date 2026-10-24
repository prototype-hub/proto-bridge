package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrototypeRetentionTest {
    @Test
    void thirtyDaysIsAnExactInclusiveBoundaryWithoutExpiringActiveOrFutureRows() {
        long now = 1_800_000_000_000L;
        long cutoff = now - PrototypeRetentionService.RETENTION_MILLIS;
        assertFalse(PrototypeRetentionService.expired(cutoff + 1, now));
        assertTrue(PrototypeRetentionService.expired(cutoff, now));
        assertTrue(PrototypeRetentionService.expired(cutoff - 1, now));
        assertFalse(PrototypeRetentionService.expired(null, now));
        assertFalse(PrototypeRetentionService.expired(now + 1, now));
    }
}
