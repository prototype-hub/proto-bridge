package com.gaiys.proto.system.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuEntityJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsLegacyStringsAndFrontendEnumEnvelopesWithoutChangingResponseShape()
            throws Exception {
        for (String value : List.of("unsupported", "support", "indirect")) {
            MenuEntity plain =
                    mapper.readValue("{\"accessSupport\":\"" + value + "\"}", MenuEntity.class);
            MenuEntity envelope =
                    mapper.readValue(
                            "{\"accessSupport\":{\"value\":\""
                                    + value
                                    + "\",\"label\":\"Legacy label\"}}",
                            MenuEntity.class);
            assertEquals(value, plain.getAccessSupport());
            assertEquals(value, envelope.getAccessSupport());
            assertEquals(value, mapper.valueToTree(envelope).path("accessSupport").asText());
        }
        assertEquals(null, mapper.readValue("{}", MenuEntity.class).getAccessSupport());
        assertEquals(
                null,
                mapper.readValue("{\"accessSupport\":null}", MenuEntity.class).getAccessSupport());
    }

    @Test
    void rejectsMalformedOrUnknownEnumsAndIgnoresWritableMemberCount() throws Exception {
        for (String value :
                List.of(
                        "{}",
                        "[]",
                        "false",
                        "42",
                        "\"unknown\"",
                        "{\"value\":42}",
                        "{\"value\":\"unknown\"}")) {
            assertThrows(
                    JsonProcessingException.class,
                    () -> mapper.readValue("{\"accessSupport\":" + value + "}", MenuEntity.class));
        }
        PositionEntity position =
                mapper.readValue(
                        "{\"name\":\"Position\",\"orgId\":\"org\",\"memberCount\":900}",
                        PositionEntity.class);
        assertEquals(0, position.getMemberCount());
        position.setMemberCount(2);
        assertEquals(2, mapper.valueToTree(position).path("memberCount").asLong());
    }
}
