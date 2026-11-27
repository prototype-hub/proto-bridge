package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.web.info.DemoScriptStep;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DemoScriptRequestsTest {
    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void everyInputBoundaryRejectsUnknownFieldsAndScalarCoercion() {
        for (Class<?> type :
                List.of(
                        DemoScriptRequests.Fields.class,
                        DemoScriptRequests.Update.class,
                        DemoScriptRequests.Delete.class,
                        DemoScriptRequests.Page.class,
                        DemoScriptRequests.PublicContext.class,
                        DemoScriptRequests.PublicQuery.class)) {
            for (String field :
                    List.of("actor", "projectId", "revisionMode", "path", "order", "createdAt")) {
                assertThrows(
                        JsonProcessingException.class,
                        () -> mapper.readValue("{\"" + field + "\":null}", type));
            }
        }
        for (String field :
                List.of("title", "objective", "prerequisites", "resetNote", "clientRequestId"))
            for (String value : List.of("7", "true", "{}", "[]"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        DemoScriptRequests.Fields.class));
        for (String field : List.of("revisionId", "accessKey"))
            for (String value : List.of("7", "true", "{}", "[]"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        DemoScriptRequests.PublicQuery.class));
        for (String value : List.of("0", "1", "\"true\"", "{}", "[]"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"shareEnabled\":" + value + "}",
                                    DemoScriptRequests.Fields.class));
        for (String value : List.of("0.5", "\"0\"", "true", "9223372036854775808"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"expectedVersion\":" + value + "}",
                                    DemoScriptRequests.Update.class));
        for (Class<?> type :
                List.of(DemoScriptRequests.Page.class, DemoScriptRequests.PublicQuery.class))
            for (String field : List.of("pageIndex", "pageSize"))
                for (String value : List.of("0.5", "\"1\"", "null", "2147483648"))
                    assertThrows(
                            JsonProcessingException.class,
                            () -> mapper.readValue("{\"" + field + "\":" + value + "}", type));
        for (String field : List.of("id", "pageId", "narration"))
            for (String value : List.of("7", "false", "{}", "[]"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"id\":\"s\",\"pageId\":\"opaque\",\""
                                                + field
                                                + "\":"
                                                + value
                                                + "}",
                                        DemoScriptStep.class));
        assertThrows(
                JsonProcessingException.class,
                () ->
                        mapper.readValue(
                                "{\"id\":\"s\",\"pageId\":\"p\",\"command\":null}",
                                DemoScriptStep.class));
    }

    @Test
    void requiredTextBoundsAndNullOptionalValuesMatchTheReplacementContract() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var input = valid();
            assertTrue(validator.validate(input).isEmpty());
            assertFalse(input.getShareEnabled());
            input.setShareEnabled(null);
            assertFalse(validator.validate(input).isEmpty());
            input.setShareEnabled(false);
            for (String value : List.of("", " \n", "\u00a0\u3000\ufeff", "x\0", "x".repeat(121))) {
                input.setTitle(value);
                assertFalse(validator.validate(input).isEmpty(), value);
            }
            input.setTitle("Named demo");
            for (String value : List.of("\u00a0\ufeff", "x\0", "x".repeat(2001))) {
                input.setObjective(value);
                assertFalse(validator.validate(input).isEmpty());
            }
            input.setObjective("Goal\nwith line breaks");
            input.setPrerequisites(null);
            input.setResetNote(null);
            assertTrue(validator.validate(input).isEmpty());
            input.setPrerequisites("x\0");
            assertFalse(validator.validate(input).isEmpty());
            input.setPrerequisites(null);
            input.setSteps(null);
            assertFalse(validator.validate(input).isEmpty());
            input.setSteps(new ArrayList<>());
            assertFalse(validator.validate(input).isEmpty());
            var steps = new ArrayList<DemoScriptStep>();
            steps.add(null);
            input.setSteps(steps);
            assertFalse(validator.validate(input).isEmpty());
            steps.clear();
            for (int index = 0; index < 50; index++)
                steps.add(new DemoScriptStep("s" + index, "opaque", null));
            assertTrue(validator.validate(input).isEmpty());
            steps.add(new DemoScriptStep("last", "opaque", ""));
            assertFalse(validator.validate(input).isEmpty());
            var update = new DemoScriptRequests.Update();
            update.setTitle("Demo");
            update.setObjective("Goal");
            update.setSteps(List.of(steps.getFirst()));
            update.setClientRequestId("key");
            for (long value : new long[] {-1, 9007199254740992L}) {
                update.setExpectedVersion(value);
                assertFalse(validator.validate(update).isEmpty());
            }
            update.setExpectedVersion(9007199254740991L);
            assertTrue(validator.validate(update).isEmpty());
            assertEquals(
                    "",
                    mapper.readValue(
                                    "{\"id\":\"s\",\"pageId\":\"opaque\",\"narration\":null}",
                                    DemoScriptStep.class)
                            .narration());
            for (String value : List.of("x\0", "x".repeat(2001)))
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new DemoScriptStep("s", "opaque", value));
        }
    }

    private DemoScriptRequests.Fields valid() throws Exception {
        return mapper.readValue(
                "{\"title\":\"Named demo\",\"objective\":\"Goal\",\"steps\":[{\"id\":\"step\",\"pageId\":\"opaque\"}],\"clientRequestId\":\"key\"}",
                DemoScriptRequests.Fields.class);
    }
}
