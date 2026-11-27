package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.web.request.ProjectOrganizationRequests;
import jakarta.validation.Validation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrototypeProjectOrganizationInputTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unicodeWhitespaceAndExactLabelsHaveOneSharedNormalization() {
        var input =
                mapper.convertValue(
                        Map.of(
                                "groupName",
                                "\u00a0\ufeff Group \u001c",
                                "tags",
                                List.of(" z ", "a", "a", " ", "A"),
                                "archived",
                                false,
                                "expectedVersion",
                                0,
                                "clientRequestId",
                                "same-key"),
                        ProjectOrganizationRequests.Organization.class);
        assertEquals("Group", input.getGroupName());
        assertEquals(List.of("A", "a", "z"), input.normalizedTags());
        assertEquals("\u0085", ProjectOrganizationRequests.normalize("\u0085"));
    }

    @Test
    void mutationBoundsAndOriginalKeyAreValidatedAtTheInputOwner() {
        var input = new ProjectOrganizationRequests.Organization();
        input.setTags(List.of());
        input.setArchived(false);
        input.setClientRequestId("key");
        input.setExpectedVersion(9007199254740991L);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(input).isEmpty());
            input.setExpectedVersion(9007199254740992L);
            assertFalse(validator.validate(input).isEmpty());
            input.setExpectedVersion(0L);
            input.setClientRequestId("bad key");
            assertFalse(validator.validate(input).isEmpty());
            input.setClientRequestId("key");
            input.setTags(java.util.Collections.nCopies(11, ""));
            assertFalse(validator.validate(input).isEmpty());
        }
    }

    @Test
    void booleansCountersTextAndUnknownIdentityAreNeverCoerced() {
        for (var changed :
                List.of(
                        Map.of("favorite", "true"),
                        Map.of("favorite", 1),
                        Map.of("userId", "other"),
                        Map.of("expectedVersion", 0.5))) {
            var input =
                    new HashMap<String, Object>(
                            Map.of(
                                    "favorite",
                                    true,
                                    "expectedVersion",
                                    0,
                                    "clientRequestId",
                                    "key"));
            input.putAll(changed);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> mapper.convertValue(input, ProjectOrganizationRequests.Favorite.class));
        }
    }
}
