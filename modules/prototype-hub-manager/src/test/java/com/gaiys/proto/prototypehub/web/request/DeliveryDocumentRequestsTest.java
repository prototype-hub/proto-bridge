package com.gaiys.proto.prototypehub.web.request;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryDocumentRequestsTest {
    @Test
    void validatesNestedPlainTextAndStrictSourceVersions() throws Exception {
        var mapper = new ObjectMapper();
        var doc = document();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(doc).isEmpty());
            doc.getPages().getFirst().setBody("<script>literal</script>\n中文");
            assertTrue(validator.validate(doc).isEmpty());
            doc.getPages().getFirst().setBody("x\u0000x");
            assertFalse(validator.validate(doc).isEmpty());
            doc.getPages().getFirst().setBody("x".repeat(20001));
            assertFalse(validator.validate(doc).isEmpty());
            doc.getPages().getFirst().setBody("");
            var ref = ref("d");
            ref.setExpectedVersion(-1L);
            doc.getPages().getFirst().setDescriptions(List.of(ref));
            assertFalse(validator.validate(doc).isEmpty());
        }
        for (String value : List.of("0.5", "\"0\"", "9223372036854775808"))
            assertThrows(
                    JsonProcessingException.class,
                    () ->
                            mapper.readValue(
                                    "{\"expectedVersion\":" + value + "}",
                                    DeliveryDocumentRequests.SourceRef.class));
    }

    @Test
    void rejectsDuplicateEmptyAndOverBudgetWhileAllowingExplicitCrossPageAssociation() {
        var doc = document();
        doc.validate(false);
        doc.setPages(List.of());
        assertEquals(
                400,
                assertThrows(PrototypeHubException.class, () -> doc.validate(false)).getStatus());
        var page = page('a');
        doc.setPages(List.of(page, page));
        assertThrows(PrototypeHubException.class, () -> doc.validate(false));
        doc.setPages(List.of(page));
        page.setDescriptions(List.of(ref("d"), ref("d")));
        assertThrows(PrototypeHubException.class, () -> doc.validate(false));
        var many = new ArrayList<DeliveryDocumentRequests.SourceRef>();
        for (int n = 0; n < 201; n++) many.add(ref("d" + n));
        page.setDescriptions(many);
        assertEquals(
                413,
                assertThrows(PrototypeHubException.class, () -> doc.validate(false)).getStatus());
        page.setDescriptions(List.of(ref("same")));
        var second = page('b');
        second.setDescriptions(List.of(ref("same")));
        doc.setPages(List.of(page, second));
        doc.validate(false);
        assertThrows(PrototypeHubException.class, () -> doc.validate(true));
        var pages = new ArrayList<DeliveryDocumentRequests.Page>();
        for (int n = 0; n < 18; n++) {
            var p = page('a');
            p.setPageId(String.format("%064x", n));
            p.setBody("界".repeat(20000));
            pages.add(p);
        }
        doc.setPages(pages);
        assertEquals(
                413,
                assertThrows(PrototypeHubException.class, () -> doc.validate(false)).getStatus());
    }

    private DeliveryDocumentRequests.Document document() {
        var doc = new DeliveryDocumentRequests.Document();
        doc.setTitle("说明书");
        doc.setPages(List.of(page('a')));
        return doc;
    }

    private DeliveryDocumentRequests.Page page(char c) {
        var p = new DeliveryDocumentRequests.Page();
        p.setPageId(String.valueOf(c).repeat(64));
        return p;
    }

    private DeliveryDocumentRequests.SourceRef ref(String id) {
        var r = new DeliveryDocumentRequests.SourceRef();
        r.setId(id);
        r.setExpectedVersion(0L);
        return r;
    }
}
