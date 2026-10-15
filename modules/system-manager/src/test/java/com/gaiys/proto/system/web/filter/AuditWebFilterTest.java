package com.gaiys.proto.system.web.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.logging.dispatcher.AuditDispatcher;
import com.gaiys.proto.logging.event.AuditEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 验证只读密码确认不会成为写入审计，并保留分享创建、删除及请求体脱敏边界。 */
class AuditWebFilterTest {
    @Test
    void managementPasswordCheckContinuesWithoutCreatingAnAuditEvent() {
        var dispatcher = mock(AuditDispatcher.class);
        var filter = new AuditWebFilter(dispatcher);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post("/prototype-hub/shares/share-id/verify-password")
                                .header("X-Request-ID", "password-check")
                                .body("{\"password\":\"sensitive-input\"}"));
        var calls = new AtomicInteger();
        StepVerifier.create(
                        filter.filter(
                                exchange,
                                e -> {
                                    calls.incrementAndGet();
                                    return Mono.empty();
                                }))
                .verifyComplete();
        assertEquals(1, calls.get());
        assertEquals(
                "password-check", exchange.getResponse().getHeaders().getFirst("X-Request-ID"));
        verifyNoInteractions(dispatcher);
    }

    @ParameterizedTest
    @CsvSource({
        "POST,/prototype-hub/versions/version-id/shares,share.create,",
        "DELETE,/prototype-hub/shares/share-id,share.delete,share-id"
    })
    void sharingWritesKeepTheirAuditActionsWithoutRequestPasswords(
            HttpMethod method, String path, String action, String resourceId) {
        var dispatcher = mock(AuditDispatcher.class);
        var filter = new AuditWebFilter(dispatcher);
        var request =
                MockServerHttpRequest.method(method, path).header("X-Request-ID", "share-write");
        var exchange =
                MockServerWebExchange.from(
                        method == HttpMethod.POST
                                ? request.body("{\"password\":\"sensitive-input\"}")
                                : request.build());
        StepVerifier.create(filter.filter(exchange, e -> Mono.empty())).verifyComplete();
        var event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(dispatcher).offer(event.capture());
        AuditEvent recorded = event.getValue();
        assertEquals(action, recorded.action());
        assertEquals("share", recorded.resourceType());
        assertEquals(resourceId, recorded.resourceId());
        assertEquals("success", recorded.result());
        assertEquals("share-write", recorded.traceId());
        assertFalse(recorded.toString().contains("sensitive-input"));
        assertFalse(recorded.toString().contains("password"));
        verifyNoMoreInteractions(dispatcher);
    }
}
