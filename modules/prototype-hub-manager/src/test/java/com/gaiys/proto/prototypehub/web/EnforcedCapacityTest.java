package com.gaiys.proto.prototypehub.web;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.web.response.PrototypeLicenseStatus.EnforcedCapacity;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EnforcedCapacityTest {
    @Test
    void legacyFieldsAndPerProjectLookupShareOneImmutableSnapshot() {
        var old = new EnforcedCapacity(5, 5, "free");
        assertEquals(5, old.versionLimitFor("p"));
        var limits = new java.util.HashMap<>(Map.of("p", 10));
        var value = new EnforcedCapacity(7, 5, "license", limits);
        limits.put("p", 20);
        assertEquals(10, value.versionLimitFor("p"));
        assertEquals(5, value.versionLimitFor("copy"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> value.projectVersionLimits().put("p", 15));
    }

    @Test
    void invalidExtensionOutputsCannotBecomeUnboundedAdmission() {
        assertThrows(IllegalArgumentException.class, () -> new EnforcedCapacity(-1, 5, "free"));
        assertThrows(IllegalArgumentException.class, () -> new EnforcedCapacity(5, 0, "free"));
        assertThrows(IllegalArgumentException.class, () -> new EnforcedCapacity(5, 5, null));
        assertThrows(IllegalArgumentException.class, () -> new EnforcedCapacity(5, 5, ""));
        assertThrows(IllegalArgumentException.class, () -> new EnforcedCapacity(5, 5, "unknown"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EnforcedCapacity(5, 5, "license", Map.of("p", -1)));
    }
}
