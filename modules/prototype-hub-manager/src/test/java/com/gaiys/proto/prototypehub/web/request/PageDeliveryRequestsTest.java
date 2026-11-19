package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PageDeliveryRequestsTest {
    @Test
    void integersAndAssigneeRetainExactJsonTypes() throws Exception {
        var mapper = new ObjectMapper();
        for (String field : new String[] {"pageIndex", "pageSize"}) {
            for (String value : new String[] {"0.5", "\"1\"", "null", "2147483648"})
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        PageDeliveryRequests.Query.class));
        }
        for (String value : new String[] {"0.5", "\"0\"", "9223372036854775808"})
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"expectedVersion\":" + value + "}",
                                    PageDeliveryRequests.Save.class));
        for (String value : new String[] {"7", "true", "{}", "[]"})
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"assigneeId\":" + value + "}",
                                    PageDeliveryRequests.Save.class));
        assertEquals(
                200L,
                mapper.readValue("{\"expectedVersion\":200}", PageDeliveryRequests.Save.class)
                        .getExpectedVersion());
        assertEquals(20, mapper.readValue("{}", PageDeliveryRequests.Query.class).getPageSize());
        assertNull(
                mapper.readValue("{\"assigneeId\":null}", PageDeliveryRequests.Save.class)
                        .getAssigneeId());
    }
}
