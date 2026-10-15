package com.gaiys.proto.prototypehub.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.gaiys.proto.prototypehub.service.PrototypeStorageService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

class PrototypeRuntimeResponseTest {
    @Test
    void activePrototypeResourcesKeepTheirMimeAndRunInAnIsolatedOrigin() {
        for (MediaType type : List.of(MediaType.TEXT_HTML, MediaType.valueOf("image/svg+xml"))) {
            var resource = new ByteArrayResource("<script>parent.localStorage</script>".getBytes());
            var response =
                    PrototypeRuntimeController.response(
                            new PrototypeStorageService.StoredResource(resource, type));

            assertEquals(200, response.getStatusCode().value());
            assertSame(resource, response.getBody());
            assertEquals(type, response.getHeaders().getContentType());
            assertEquals("no-store", response.getHeaders().getCacheControl());
            assertEquals(
                    "sandbox allow-scripts allow-forms",
                    response.getHeaders().getFirst("Content-Security-Policy"));
            assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
            assertEquals("no-referrer", response.getHeaders().getFirst("Referrer-Policy"));
        }
    }
}
