package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.web.request.PrototypeSearchRequest;
import com.gaiys.proto.prototypehub.web.request.PrototypeSearchRequest.Type;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class PrototypeSearchServiceTest {
    @Test
    void literalExcerptKeepsTheMatchAndNeverSplitsSupplementaryCharacters() {
        String source = "😀".repeat(400) + "中文_100%<script>" + "后".repeat(400);
        String excerpt = PrototypeSearchService.excerpt(source, "100%<SCRIPT>");
        assertTrue(excerpt.contains("100%<script>"));
        assertEquals(240, excerpt.codePointCount(0, excerpt.length()));
        assertFalse(Character.isLowSurrogate(excerpt.charAt(0)));
        assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1)));
        assertEquals("literal_100%", PrototypeSearchService.excerpt("literal_100%", "_100%"));
    }

    @Test
    void sourceProjectionSelectsActualMatchingMetadataAndNeverReturnsStorageOrUrl() {
        var row = new PrototypeSearchService.Row();
        row.setTitle("😀".repeat(200));
        row.setFileName("document.pdf");
        row.setSourceTool("Axure");
        row.setSourceName("人工来源");
        row.setSourceNotes("Only exact needle_100% belongs here");
        var response = PrototypeSearchService.response(Type.MATERIAL, "NEEDLE_100%", row);
        assertEquals("sourceNotes", response.matchField());
        assertTrue(response.snippet().contains("needle_100%"));
        assertEquals(160, response.title().codePointCount(0, response.title().length()));
        assertNull(response.pageId());
        assertNull(response.threadId());
    }

    @Test
    void materialTextMatchUsesBodyExcerptAndKeepsTheOriginalFilename() {
        var row = new PrototypeSearchService.Row();
        row.setTitle("requirements.docx");
        row.setFileName("requirements.docx");
        row.setTextContent("😀".repeat(300) + "正文_100%<script>命中" + "后".repeat(300));
        var response = PrototypeSearchService.response(Type.MATERIAL, "_100%<SCRIPT>", row);
        assertEquals("textContent", response.matchField());
        assertEquals("requirements.docx", response.title());
        assertTrue(response.snippet().contains("正文_100%<script>命中"));
        assertEquals(240, response.snippet().codePointCount(0, response.snippet().length()));
        assertNull(response.pageId());
    }

    @Test
    void independentLinkMetadataMatchUsesItsTitleWithoutInventingAFile() {
        var row = new PrototypeSearchService.Row();
        row.setTitle("");
        row.setFileName("");
        row.setMaterialType("link");
        row.setSourceName("设计 PRD");
        row.setSourceNotes("Requirement needle");
        var response = PrototypeSearchService.response(Type.MATERIAL, "needle", row);
        assertEquals("设计 PRD", response.title());
        assertEquals("link", response.materialType());
        assertEquals("sourceNotes", response.matchField());
    }

    @Test
    void boundaryNormalizesBeforeValidationAndRejectsUnboundedOrUnsafeInputs() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var input = new PrototypeSearchRequest();
            input.setType(Type.COPY);
            input.setKeyword("  中文  ");
            assertEquals("中文", input.getKeyword());
            assertTrue(validator.validate(input).isEmpty());
            for (String value : new String[] {" ", "x".repeat(101), "a\u0000b", "\ud800"}) {
                input.setKeyword(value);
                assertFalse(validator.validate(input).isEmpty());
            }
            input.setKeyword("😀<script>_100%");
            assertTrue(validator.validate(input).isEmpty());
            input.setRevisionId("../other");
            assertFalse(validator.validate(input).isEmpty());
            input.setRevisionId(null);
            input.setPageSize(51);
            assertFalse(validator.validate(input).isEmpty());
        }
    }
}
