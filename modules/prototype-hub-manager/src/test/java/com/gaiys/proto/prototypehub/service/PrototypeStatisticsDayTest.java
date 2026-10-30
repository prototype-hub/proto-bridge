package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PrototypeStatisticsDayTest {
    @Test
    void shanghaiNaturalDayDoesNotUseServerDefaultZone() {
        assertEquals(
                "2026-10-30",
                PrototypeStatisticsService.day(
                                Instant.parse("2026-10-30T15:59:59.999Z").toEpochMilli())
                        .toString());
        assertEquals(
                "2026-10-31",
                PrototypeStatisticsService.day(Instant.parse("2026-10-30T16:00:00Z").toEpochMilli())
                        .toString());
    }
}
