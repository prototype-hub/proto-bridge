package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class ElementDescriptionRequestsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refusesNonemptyElementTargetEvenWhenUnknownPropertiesAreIgnored() throws Exception {
        var webMapper =
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (Class<?> type :
                new Class<?>[] {
                    ElementDescriptionRequests.Create.class, ElementDescriptionRequests.Update.class
                }) {
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            webMapper.readValue(
                                    "{\"elementTarget\":{\"space\":\"frame-element-v1\"}}", type));
            assertNotNull(webMapper.readValue("{\"elementTarget\":null}", type));
        }
    }

    @Test
    void casAndPaginationDoNotCoerceFractionalOrStringInputs() throws Exception {
        for (String field : new String[] {"pageIndex", "pageSize"})
            for (String value : new String[] {"0.5", "\"1\"", "null", "2147483648"})
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        "{\"" + field + "\":" + value + "}",
                                        ElementDescriptionRequests.Query.class));
        for (Class<?> type :
                new Class<?>[] {
                    ElementDescriptionRequests.Update.class, ElementDescriptionRequests.Delete.class
                })
            for (String value : new String[] {"0.5", "\"0\"", "9223372036854775808"})
                assertThrows(
                        JsonProcessingException.class,
                        () -> mapper.readValue("{\"expectedVersion\":" + value + "}", type));
        assertEquals(
                20, mapper.readValue("{}", ElementDescriptionRequests.Query.class).getPageSize());
        assertEquals(
                0L,
                mapper.readValue("{\"expectedVersion\":0}", ElementDescriptionRequests.Update.class)
                        .getExpectedVersion());
    }

    @Test
    void validatesFixedScopeAndAllTextLimitsWithoutInterpretingPlainText() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var create =
                    mapper.readValue(
                            """
                    {"revisionId":"r1","pageId":"%s","clientRequestId":"request-1",
                     "title":"<script>literal</script>","purpose":"https://example.invalid/ordinary-text"}
                    """
                                    .formatted("a".repeat(64)),
                            ElementDescriptionRequests.Create.class);
            assertTrue(validator.validate(create).isEmpty());
            assertEquals("<script>literal</script>", create.getTitle());
            assertNull(create.getAnchor());
            for (String field :
                    new String[] {
                        "title",
                        "purpose",
                        "inputRules",
                        "permissionRules",
                        "navigationRules",
                        "notes"
                    }) {
                int max = field.equals("title") ? 120 : field.equals("notes") ? 4000 : 2000;
                var setter =
                        ElementDescriptionRequests.Create.class.getMethod(
                                "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1),
                                String.class);
                setter.invoke(create, "x".repeat(max));
                assertTrue(validator.validate(create).isEmpty(), field);
                setter.invoke(create, "x".repeat(max + 1));
                assertFalse(validator.validate(create).isEmpty(), field);
                setter.invoke(create, field.equals("title") ? "name" : null);
            }
            create.setTitle(" \n\t");
            assertFalse(validator.validate(create).isEmpty());
            create.setTitle("name");
            create.setPageId("folder.html");
            assertFalse(validator.validate(create).isEmpty());
            create.setPageId("a".repeat(64));
            create.setClientRequestId("contains space");
            assertFalse(validator.validate(create).isEmpty());

            var query = new ElementDescriptionRequests.Query();
            query.setRevisionId("r1");
            query.setPageId("a".repeat(64));
            query.setPageSize(100);
            assertTrue(validator.validate(query).isEmpty());
            query.setPageSize(101);
            assertFalse(validator.validate(query).isEmpty());
            var update = new ElementDescriptionRequests.Update();
            update.setRevisionId("r1");
            update.setPageId("a".repeat(64));
            update.setTitle("name");
            update.setClientRequestId("cas");
            assertFalse(validator.validate(update).isEmpty());
            update.setExpectedVersion(-1L);
            assertFalse(validator.validate(update).isEmpty());
            update.setExpectedVersion(0L);
            assertTrue(validator.validate(update).isEmpty());
        }
    }

    @Test
    void titleRejectsNonBreakingAndBomWhitespaceWithoutTrimmingNormalText() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var create = new ElementDescriptionRequests.Create();
            create.setRevisionId("r1");
            create.setPageId("a".repeat(64));
            create.setClientRequestId("title-whitespace");
            for (String blank : new String[] {"\u00a0", "\ufeff", " \t\r\n\u00a0\ufeff"}) {
                create.setTitle(blank);
                assertFalse(validator.validate(create).isEmpty());
            }
            for (String title :
                    new String[] {"对象用途", "Object purpose", " \u00a0Object 中文\ufeff\t "}) {
                create.setTitle(title);
                assertTrue(validator.validate(create).isEmpty());
                assertEquals(title, create.getTitle());
            }
        }
    }

    @Test
    void rejectsNulBeforeDatabaseWhilePreservingOrdinaryWhitespace() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var create = new ElementDescriptionRequests.Create();
            create.setRevisionId("r1");
            create.setPageId("a".repeat(64));
            create.setTitle("name");
            create.setClientRequestId("text-boundary");
            for (String field :
                    new String[] {
                        "title",
                        "purpose",
                        "inputRules",
                        "permissionRules",
                        "navigationRules",
                        "notes"
                    }) {
                var setter =
                        ElementDescriptionRequests.Create.class.getMethod(
                                "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1),
                                String.class);
                setter.invoke(create, "ordinary\tChinese 中文\r\ntext");
                assertTrue(validator.validate(create).isEmpty(), field + " whitespace");
                setter.invoke(create, "ordinary" + (char) 0 + "text");
                assertFalse(validator.validate(create).isEmpty(), field + " NUL");
                setter.invoke(create, field.equals("title") ? "name" : null);
            }
            var query = new ElementDescriptionRequests.Query();
            query.setRevisionId("r1");
            query.setPageId("a".repeat(64));
            query.setKeyword("ordinary" + (char) 0 + "text");
            assertFalse(validator.validate(query).isEmpty());
            query.setKeyword("ordinary\t\ntext");
            assertTrue(validator.validate(query).isEmpty());
            create.setRevisionId("r" + (char) 0 + "1");
            assertFalse(validator.validate(create).isEmpty());
        }
    }

    @Test
    void anchorUsesExistingStrictGeometryInsteadOfAcceptingSelectorsOrCoercions() throws Exception {
        String anchor =
                """
                {"space":"root-document-css-px-v1","shape":"rect","x":4,"y":5,
                 "width":20,"height":30,"documentWidth":100,"documentHeight":200,
                 "viewportWidth":80,"viewportHeight":90}
                """;
        var value =
                mapper.readValue(
                        "{\"anchor\":" + anchor + "}", ElementDescriptionRequests.Create.class);
        assertEquals("rect", value.getAnchor().shape());
        assertEquals(
                value.getAnchor(),
                mapper.readValue(
                                mapper.writeValueAsBytes(value),
                                ElementDescriptionRequests.Create.class)
                        .getAnchor());
        assertThrows(
                JsonProcessingException.class,
                () ->
                        mapper.readValue(
                                "{\"anchor\":" + anchor.replace("\"x\":4", "\"x\":4.5") + "}",
                                ElementDescriptionRequests.Create.class));
        assertThrows(
                JsonProcessingException.class,
                () ->
                        mapper.readValue(
                                "{\"anchor\":"
                                        + anchor.replace("\"x\":4", "\"selector\":\"#x\",\"x\":4")
                                        + "}",
                                ElementDescriptionRequests.Create.class));
    }
}
