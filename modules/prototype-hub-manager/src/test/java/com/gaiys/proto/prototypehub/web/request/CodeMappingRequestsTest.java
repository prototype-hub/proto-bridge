package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CodeMappingRequestsTest {
    private final ObjectMapper mapper =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final String CARD =
            "{\"alias\":\"Button\",\"repositoryPath\":\"src/Button.vue\",\"repositoryUrl\":\"\",\"documentationUrl\":\"\",\"snippet\":\"  <script>text</script>\\n  x\",\"framework\":\"Vue\",\"frameworkVersion\":\"3\",\"description\":null}";

    @Test
    void everyBoundaryRejectsUnknownFieldsAndScalarCoercion() {
        for (var type :
                List.of(
                        CodeMappingRequests.Create.class,
                        CodeMappingRequests.Update.class,
                        CodeMappingRequests.Delete.class,
                        CodeMappingRequests.Query.class,
                        CodeMappingRequests.Export.class,
                        CodeMappingRequests.Import.class,
                        CodeMappingRequests.Description.class,
                        CodeMappingRequests.Draft.class))
            for (String field :
                    List.of(
                            "projectId",
                            "actorId",
                            "idempotencyReceipt",
                            "anchor",
                            "status",
                            "__proto__"))
                assertThrows(
                        JsonProcessingException.class,
                        () -> mapper.readValue("{\"" + field + "\":null}", type));
        for (String field :
                List.of(
                        "alias",
                        "repositoryPath",
                        "repositoryUrl",
                        "documentationUrl",
                        "snippet",
                        "framework",
                        "frameworkVersion",
                        "clientRequestId"))
            for (String value : List.of("7", "true", "{}", "[]"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        CodeMappingRequests.Create.class));
        for (String value : List.of("0.1", "\"0\"", "true", "9223372036854775808"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"expectedVersion\":" + value + "}",
                                    CodeMappingRequests.Update.class));
        for (String field : List.of("pageIndex", "pageSize"))
            for (String value : List.of("0.5", "\"1\"", "null", "2147483648"))
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        CodeMappingRequests.Query.class));
        assertThrows(
                JsonProcessingException.class,
                () -> mapper.readValue("{\"ids\":[7]}", CodeMappingRequests.Export.class));
        assertThrows(
                JsonProcessingException.class,
                () -> mapper.readValue("{\"cards\":{}}", CodeMappingRequests.Import.class));
        assertThrows(
                JsonProcessingException.class,
                () ->
                        mapper.readValue(
                                CARD.replace(
                                        "\"description\":null",
                                        "\"description\":{\"id\":\"d\",\"expectedVersion\":0,\"status\":\"current\"}"),
                                CodeMappingRequests.Draft.class));
    }

    @Test
    void mandatoryFieldsExplicitNullAssociationAndNestedBatchBoundsAreValidated() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var card = mapper.readValue(CARD, CodeMappingRequests.Draft.class);
            assertTrue(validator.validate(card).isEmpty());
            assertEquals("  <script>text</script>\n  x", card.getSnippet());
            assertFalse(
                    validator
                            .validate(
                                    mapper.readValue(
                                            CARD.replace(",\"description\":null", ""),
                                            CodeMappingRequests.Draft.class))
                            .isEmpty());
            for (String field :
                    List.of(
                            "alias",
                            "repositoryPath",
                            "repositoryUrl",
                            "documentationUrl",
                            "snippet",
                            "framework",
                            "frameworkVersion")) {
                var tree = mapper.readTree(CARD);
                ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove(field);
                assertFalse(
                        validator
                                .validate(mapper.treeToValue(tree, CodeMappingRequests.Draft.class))
                                .isEmpty());
            }
            for (String bad : List.of("\u00a0\u3000\ufeff", "x\0", "x".repeat(121))) {
                card.setAlias(bad);
                assertFalse(validator.validate(card).isEmpty());
            }
            card.setAlias("Button");
            for (String bad : List.of("", "x\0", "x".repeat(2049))) {
                card.setRepositoryPath(bad);
                assertFalse(validator.validate(card).isEmpty());
            }
            card.setRepositoryPath("src/Button.vue");
            var pack =
                    mapper.readValue(
                            "{\"format\":\"prototype-hub-code-mapping-cards\",\"schemaVersion\":1,\"clientRequestId\":\"key\",\"cards\":["
                                    + CARD
                                    + "]}",
                            CodeMappingRequests.Import.class);
            assertTrue(validator.validate(pack).isEmpty());
            var cards = new ArrayList<CodeMappingRequests.Draft>();
            for (int i = 0; i < 50; i++) cards.add(card);
            pack.setCards(cards);
            assertTrue(validator.validate(pack).isEmpty());
            cards.add(card);
            assertFalse(validator.validate(pack).isEmpty());
            cards.clear();
            cards.add(null);
            assertFalse(validator.validate(pack).isEmpty());
            pack.setCards(List.of(card));
            pack.setSchemaVersion(2);
            assertFalse(validator.validate(pack).isEmpty());
            pack.setSchemaVersion(1);
            pack.setFormat("other-format");
            assertFalse(validator.validate(pack).isEmpty());
        }
    }
}
