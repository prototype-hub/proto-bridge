package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrototypeWorkItemTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.com/tasks/123?project=one#details",
                "http://127.0.0.1:8080/work-item/42",
                "HTTPS://example.com:65535/task"
            })
    void acceptsExplicitHttpLinksWithoutFetchingOrRewritingThem(String value) {
        assertEquals(value, PrototypeWorkItemService.validatedUrl(value));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "javascript:alert(1)",
                "data:text/html,x",
                "file:///etc/passwd",
                "//example.com/task",
                "https://user:secret@example.com/task",
                "https://example.com:99999/task",
                "https://example.com/task\nother",
                "https://example.com/has space",
                "https:///task"
            })
    void rejectsUnsafeOrBrowserInvalidLinks(String value) {
        assertThrows(
                PrototypeHubException.class, () -> PrototypeWorkItemService.validatedUrl(value));
    }
}
