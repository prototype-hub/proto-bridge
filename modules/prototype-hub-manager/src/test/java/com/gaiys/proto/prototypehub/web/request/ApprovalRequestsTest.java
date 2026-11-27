package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class ApprovalRequestsTest {
    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void untrustedFieldsAndCoercionsNeverChangeFormalDecisionSemantics() throws Exception {
        for (String field :
                new String[] {"projectId", "actorId", "manifestSha256", "createTime", "url"})
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"" + field + "\":\"injected\"}",
                                    ApprovalRequests.Act.class));
        for (String field :
                new String[] {"action", "clientRequestId", "note", "revisionId", "accessKey"}) {
            Class<?> type =
                    field.equals("revisionId") || field.equals("accessKey")
                            ? ApprovalRequests.PublicContext.class
                            : ApprovalRequests.Act.class;
            for (String value : new String[] {"7", "true", "{}", "[]"})
                assertThrows(
                        JsonProcessingException.class,
                        () -> mapper.readValue("{\"" + field + "\":" + value + "}", type));
        }
        for (String value : new String[] {"0.5", "\"0\"", "true", "9223372036854775808"})
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"expectedVersion\":" + value + "}",
                                    ApprovalRequests.Act.class));
        for (String field : new String[] {"pageIndex", "pageSize"})
            for (String value : new String[] {"0.5", "\"1\"", "null", "2147483648"})
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        ApprovalRequests.History.class));
        assertEquals(20, mapper.readValue("{}", ApprovalRequests.History.class).getPageSize());
    }

    @Test
    void actionRequestRequiresSafeCasVersionAndBoundedPlainText() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var input =
                    mapper.readValue(
                            "{\"action\":\"approve\",\"expectedVersion\":0,\"clientRequestId\":\"same-key\",\"note\":\"<b>plain text</b>\"}",
                            ApprovalRequests.Act.class);
            assertTrue(validator.validate(input).isEmpty());
            for (long value : new long[] {-1L, 9007199254740992L}) {
                input.setExpectedVersion(value);
                assertFalse(validator.validate(input).isEmpty());
            }
            input.setExpectedVersion(0L);
            input.setNote("x".repeat(2001));
            assertFalse(validator.validate(input).isEmpty());
            input.setNote(null);
            input.setAction("APPROVE");
            assertFalse(validator.validate(input).isEmpty());
            input.setAction("approve");
            input.setClientRequestId("spaces forbidden");
            assertFalse(validator.validate(input).isEmpty());
        }
    }
}
