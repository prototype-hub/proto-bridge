package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ViewportCheckRequestsTest {
    @Test
    void numericFieldsKeepTheirIntegerJsonContract() throws Exception {
        var mapper = new ObjectMapper();
        for (String field : new String[] {"viewportWidth", "viewportHeight", "viewportScale"}) {
            for (String input : new String[] {"390.5", "\"390\""}) {
                assertThrows(
                        com.fasterxml.jackson.core.JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + input + "}",
                                        ViewportCheckRequests.Fields.class));
            }
        }
        for (String input : new String[] {"0.5", "\"0\""}) {
            assertThrows(
                    com.fasterxml.jackson.core.JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"expectedVersion\":" + input + "}",
                                    ViewportCheckRequests.Update.class));
            assertThrows(
                    com.fasterxml.jackson.core.JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"pageIndex\":" + input + "}",
                                    ViewportCheckRequests.Query.class));
        }
        assertEquals(
                390,
                mapper.readValue("{\"viewportWidth\":390}", ViewportCheckRequests.Fields.class)
                        .getViewportWidth());
        assertEquals(
                0L,
                mapper.readValue("{\"expectedVersion\":0}", ViewportCheckRequests.Update.class)
                        .getExpectedVersion());
    }
}
