package com.gaiys.proto.system.service.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

class ManagementSupportTest {
    @Test
    void rejectsNonPositiveAndOversizedPageSizesBeforeQueryExecution() {
        for (int size : List.of(Integer.MIN_VALUE, -1, 0, 201)) {
            QueryParamEntity query = new QueryParamEntity();
            query.setPageSize(size);
            BusinessException error =
                    assertThrows(BusinessException.class, () -> ManagementSupport.limit(query));
            assertEquals(400, error.getStatus());
            assertEquals("BAD_REQUEST", error.getCode());
        }
        for (int size : List.of(1, 200)) {
            QueryParamEntity query = new QueryParamEntity();
            query.setPageSize(size);
            ManagementSupport.limit(query);
            assertEquals(size, query.getPageSize());
        }
    }

    @Test
    void returnsEmptyForNullAndEmptyInput() {
        assertEquals(List.of(), ManagementSupport.ids(null));
        assertEquals(List.of(), ManagementSupport.ids(List.of()));
    }

    @Test
    void convertsMixedIdentifierRepresentations() {
        assertEquals(
                List.of("role-one", "role_two", "123"),
                ManagementSupport.ids(List.of(Map.of("id", "role-one"), "role_two", 123)));
    }

    @Test
    void keepsFirstSeenOrderWhenRemovingDuplicates() {
        assertEquals(
                List.of("second", "first", "third"),
                ManagementSupport.ids(
                        List.of("second", "first", Map.of("id", "second"), "third", "first")));
    }

    @Test
    void returnsImmutableIdentifierList() {
        List<String> identifiers = ManagementSupport.ids(List.of("one"));
        assertThrows(UnsupportedOperationException.class, () -> identifiers.add("two"));
    }

    @Test
    void enforcesExistingBatchSizeBeforeDeduplication() {
        assertEquals(List.of("same"), ManagementSupport.ids(Collections.nCopies(200, "same")));
        assertThrows(
                BusinessException.class,
                () -> ManagementSupport.ids(Collections.nCopies(201, "same")));
    }

    @Test
    void rejectsUnsupportedInputAndInvalidIdentifiers() {
        assertThrows(BusinessException.class, () -> ManagementSupport.ids("one"));
        for (Object value :
                Arrays.asList(
                        "", "has space", "slash/id", "a".repeat(65), null, Map.of("name", "one"))) {
            assertThrows(
                    BusinessException.class, () -> ManagementSupport.ids(Arrays.asList(value)));
        }
        assertEquals(List.of("a".repeat(64)), ManagementSupport.ids(List.of("a".repeat(64))));
    }
}
