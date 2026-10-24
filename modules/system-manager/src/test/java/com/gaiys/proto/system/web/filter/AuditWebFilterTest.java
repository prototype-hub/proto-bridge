package com.gaiys.proto.system.web.filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gaiys.proto.logging.dispatcher.AuditDispatcher;
import com.gaiys.proto.logging.event.AuditEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** 验证分页、密码确认不产生写入审计，并保留项目、版本、分享写入及请求体脱敏边界。 */
class AuditWebFilterTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/prototype-hub/projects/_query",
                "/prototype-hub/versions/_query",
                "/prototype-hub/projects/_deleted/_query",
                "/prototype-hub/versions/_deleted/_query"
            })
    void pagedReadsContinueWithRequestIdWithoutCreatingWriteAuditEvents(String path) {
        var dispatcher = mock(AuditDispatcher.class);
        var filter = new AuditWebFilter(dispatcher);
        var exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.post(path)
                                .header("X-Request-ID", "paged-read")
                                .body("{\"pageIndex\":0,\"pageSize\":20}"));
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
        assertEquals("paged-read", exchange.getResponse().getHeaders().getFirst("X-Request-ID"));
        verifyNoInteractions(dispatcher);
    }

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
        "POST,/prototype-hub/projects,project.create,project,",
        "POST,/prototype-hub/projects/project-id/_restore,project.restore,project,project-id",
        "POST,/prototype-hub/versions/version-id/_restore,version.restore,version,version-id",
        "POST,/prototype-hub/projects/project-id/versions,version.upload,version,",
        "POST,/prototype-hub/versions/version-id/shares,share.create,share,",
        "POST,/prototype-hub/versions/version-id/preview-session,preview.authorize,version,version-id",
        "POST,/prototype-hub/projects/_ownership-migration,project.ownership.migrate,project,",
        "POST,/prototype-hub/projects/project-id/claim,project.claim,project,project-id",
        "PUT,/prototype-hub/projects/project-id/members/user-id,project.members.update,project,project-id",
        "DELETE,/prototype-hub/projects/project-id/members/user-id,project.members.delete,project,project-id",
        "DELETE,/prototype-hub/shares/share-id,share.delete,share,share-id"
    })
    void sharingWritesKeepTheirAuditActionsWithoutRequestPasswords(
            HttpMethod method, String path, String action, String resourceType, String resourceId) {
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
        assertEquals(resourceType, recorded.resourceType());
        assertEquals(resourceId, recorded.resourceId());
        assertEquals("success", recorded.result());
        assertEquals("share-write", recorded.traceId());
        assertFalse(recorded.toString().contains("sensitive-input"));
        assertFalse(recorded.toString().contains("password"));
        verifyNoMoreInteractions(dispatcher);
    }
}
