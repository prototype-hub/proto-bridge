package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.web.info.PageTraceSourceRef;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageTraceRequestsTest {
    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void unknownFieldsAndScalarCoercionAreRejectedAtEveryInputBoundary() {
        for (var type :
                List.of(
                        PageTraceRequests.Page.class,
                        PageTraceRequests.RelationQuery.class,
                        PageTraceRequests.SourceQuery.class,
                        PageTraceRequests.RelationFields.class,
                        PageTraceRequests.RelationUpdate.class,
                        PageTraceRequests.ContextUpdate.class,
                        PageTraceRequests.DecisionFields.class,
                        PageTraceRequests.DecisionUpdate.class,
                        PageTraceRequests.Delete.class))
            for (String field :
                    List.of("projectId", "actor", "authorId", "revisionId", "createdAt", "path"))
                assertThrows(
                        JsonProcessingException.class,
                        () -> mapper.readValue("{\"" + field + "\":null}", type));
        for (String field : List.of("fromPageId", "toPageId", "label", "note", "clientRequestId"))
            for (String value : List.of("7", "true", "{}", "[]"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        PageTraceRequests.RelationFields.class));
        for (String field : List.of("title", "decision", "rationale"))
            for (String value : List.of("7", "true", "{}", "[]"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        PageTraceRequests.DecisionFields.class));
        for (var type :
                List.of(
                        PageTraceRequests.RelationUpdate.class,
                        PageTraceRequests.ContextUpdate.class,
                        PageTraceRequests.DecisionUpdate.class,
                        PageTraceRequests.Delete.class))
            for (String value : List.of("0.5", "\"0\"", "true", "9223372036854775808"))
                assertThrows(
                        JsonProcessingException.class,
                        () -> mapper.readValue("{\"expectedVersion\":" + value + "}", type));
        for (String field : List.of("pageIndex", "pageSize"))
            for (String value : List.of("0.5", "\"1\"", "null", "2147483648"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        PageTraceRequests.Page.class));
        for (String value : List.of("0", "1", "\"true\"", "{}", "[]"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"entry\":" + value + "}",
                                    PageTraceRequests.ContextUpdate.class));
    }

    @Test
    void sourcePointersRoundTripStrictlyForHttpAndPersistedJsonCodec() throws Exception {
        var ref = new PageTraceSourceRef("work-item", "source-id", 9007199254740991L);
        assertEquals(
                ref, mapper.readValue(mapper.writeValueAsString(ref), PageTraceSourceRef.class));
        for (String bad :
                List.of(
                        "null",
                        "[]",
                        "{\"kind\":\"work-item\",\"id\":\"i\",\"expectedVersion\":0,\"body\":null}",
                        "{\"kind\":\"material\",\"id\":3,\"expectedVersion\":0}",
                        "{\"kind\":\"check\",\"id\":\"i\",\"expectedVersion\":\"0\"}",
                        "{\"kind\":\"check\",\"id\":\"i\",\"expectedVersion\":0.0}",
                        "{\"kind\":\"check\",\"id\":\"i\",\"expectedVersion\":9007199254740992}",
                        "{\"kind\":\"approval\",\"id\":\"i\",\"expectedVersion\":0}")) {
            if ("null".equals(bad))
                continue; // Null list items are rejected by Bean Validation, not record
            // construction.
            assertThrows(
                    JsonProcessingException.class,
                    () -> mapper.readValue(bad, PageTraceSourceRef.class),
                    bad);
        }
        for (String id : List.of("", "x\0", "x".repeat(129)))
            assertThrows(
                    IllegalArgumentException.class, () -> new PageTraceSourceRef("check", id, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageTraceSourceRef(null, "i", 0));
    }

    @Test
    void replacementBoundsPermitUninitializedContextButNeverNegativeEntityVersions()
            throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var context =
                    mapper.readValue(
                            "{\"entry\":false,\"references\":[],\"expectedVersion\":-1,\"clientRequestId\":\"key\"}",
                            PageTraceRequests.ContextUpdate.class);
            assertTrue(validator.validate(context).isEmpty());
            context.setExpectedVersion(-2L);
            assertFalse(validator.validate(context).isEmpty());
            context.setExpectedVersion(0L);
            var refs = new ArrayList<PageTraceSourceRef>();
            for (int i = 0; i < 40; i++) refs.add(new PageTraceSourceRef("check", "id-" + i, 0));
            context.setReferences(refs);
            assertTrue(validator.validate(context).isEmpty());
            refs.add(new PageTraceSourceRef("check", "last", 0));
            assertFalse(validator.validate(context).isEmpty());
            refs.clear();
            refs.add(null);
            assertFalse(validator.validate(context).isEmpty());
            context.setReferences(List.of());
            context.setEntry(null);
            assertFalse(validator.validate(context).isEmpty());
            var edge =
                    mapper.readValue(
                            "{\"fromPageId\":\"from\",\"toPageId\":\"to\",\"label\":\"Navigation\",\"clientRequestId\":\"key\",\"expectedVersion\":0}",
                            PageTraceRequests.RelationUpdate.class);
            assertTrue(validator.validate(edge).isEmpty());
            for (String bad : List.of("\u00a0\u3000\ufeff", "x\0", "x".repeat(121))) {
                edge.setLabel(bad);
                assertFalse(validator.validate(edge).isEmpty());
            }
            edge.setLabel("中文 relation");
            edge.setExpectedVersion(-1L);
            assertFalse(validator.validate(edge).isEmpty());
            var decision =
                    mapper.readValue(
                            "{\"title\":\"Decision\",\"decision\":\"Proceed\",\"rationale\":\"Reviewed impact\",\"clientRequestId\":\"key\"}",
                            PageTraceRequests.DecisionFields.class);
            assertTrue(validator.validate(decision).isEmpty());
            decision.setRationale("\u00a0\ufeff");
            assertFalse(validator.validate(decision).isEmpty());
            var query = new PageTraceRequests.Page();
            query.setPageSize(101);
            assertFalse(validator.validate(query).isEmpty());
            query.setPageSize(100);
            query.setPageIndex(10001);
            assertFalse(validator.validate(query).isEmpty());
        }
    }
}
