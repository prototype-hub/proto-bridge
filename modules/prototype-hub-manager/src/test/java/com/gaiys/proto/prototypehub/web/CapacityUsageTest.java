package com.gaiys.proto.prototypehub.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gaiys.proto.prototypehub.web.response.CapacityUsage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CapacityUsageTest {
    @ParameterizedTest
    @CsvSource({
        "0,5,5,false,true",
        "4,5,1,false,true",
        "5,5,0,false,false",
        "6,5,0,true,false",
        "6,10,4,false,true",
        "6,-1,-1,false,true",
        "0,-1,-1,false,true"
    })
    void realUsageAndEffectiveLimitShareTheAdmissionDecision(
            long used, int limit, long remaining, boolean overLimit, boolean canCreate) {
        assertEquals(
                new CapacityUsage(used, limit, remaining, overLimit, canCreate),
                CapacityUsage.from(used, limit));
    }
}
